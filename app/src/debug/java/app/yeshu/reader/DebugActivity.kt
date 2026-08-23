package app.yeshu.reader

import android.app.Activity
import android.os.Bundle
import android.util.Log
import app.yeshu.reader.parse.Block
import app.yeshu.reader.parse.DocParser
import java.io.File

/**
 * 仅用于自动化验证的调试入口（logcat tag=PARSER_TEST），不做任何 UI 交互：
 * 1) 解析自检:  am start ... --es path <文件路径>
 * 2) 写入API:   --es action setapi --es url <base> --es key <k> --es model <m>
 * 3) 登记书籍:  --es action addbook --es path files/t1.docx --es title 标题
 *               （path 相对 app filesDir；文件需已由 run-as cp 放好）→ 日志 BOOK_ID=<n>
 * 4) AI摘要E2E: --es action summary --ei bookId <n>
 */
class DebugActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = StringBuilder()
        try {
            val action = intent.getStringExtra("action")
            when (action) {
                "setapi" -> {
                    val db = Db(this)
                    db.setSetting("ai_base_url", intent.getStringExtra("url") ?: "")
                    db.setAiKey(intent.getStringExtra("key") ?: "", intent.getStringExtra("url") ?: "")
                    db.setSetting("ai_model", intent.getStringExtra("model") ?: "")
                    log.append("OK settings saved: ")
                        .append(intent.getStringExtra("url")).append(" / ")
                        .append(intent.getStringExtra("model"))
                }
                "addbook" -> {
                    val rel = intent.getStringExtra("path")
                        ?: throw IllegalArgumentException("缺少 path")
                    val f = File(filesDir, rel)
                    if (!f.exists()) throw IllegalStateException("文件不存在: ${f.absolutePath}")
                    val db = Db(this)
                    val ext = f.extension.lowercase()
                    val fmt = if (ext == "pdf") "pdf"
                              else DocParser.detect(f.name).ifEmpty { "txt" }
                    val id = db.insertBook(
                        intent.getStringExtra("title") ?: f.nameWithoutExtension,
                        f.name,
                        fmt,
                        f.length()
                    )
                    try { CoverStore.generateSync(this, id, f, fmt) } catch (e: Exception) {}
                    log.append("BOOK_ID=").append(id)
                }
                "summary" -> {
                    val db = Db(this)
                    // 兼容 --es 与 --ei 两种传参（adb shell 对 --ei 传参偶发丢失）
                    val id = (intent.getStringExtra("bookId")?.toLongOrNull()
                        ?: intent.getLongExtra("bookId", -1L))
                    val b = db.getBook(id) ?: throw IllegalStateException("bookId $id 不存在")
                    val cfg = AiClient.config(db)
                    if (!AiClient.isReady(cfg)) throw IllegalStateException("AI 未配置")
                    val text = DocParser.parseText(File(filesDir, b.fileName)).fullText
                    // 网络必须子线程；完成后再写日志并退出
                    Thread {
                        val out = StringBuilder()
                        try {
                            val t0 = System.currentTimeMillis()
                            val reply = AiClient.chat(
                                cfg,
                                "你是专业的中文阅读助手。用简体中文回答。",
                                "请为下面的内容生成摘要：先一句话概括，再用要点列出核心内容。\n\n" + text.take(24000)
                            )
                            db.addNote(id, "summary", reply)
                            out.append("SUMMARY_OK ms=").append(System.currentTimeMillis() - t0)
                                .append(" notes_saved=true reply_chars=").append(reply.length)
                        } catch (t: Throwable) {
                            out.append("FAIL ").append(t.toString())
                        }
                        Log.i("PARSER_TEST", out.toString().trimEnd())
                        finish()
                    }.start()
                }
                "ask" -> {
                    val db = Db(this)
                    val id = (intent.getStringExtra("bookId")?.toLongOrNull()
                        ?: intent.getLongExtra("bookId", -1L))
                    val q = intent.getStringExtra("q") ?: "这份资料讲了什么？"
                    val b = db.getBook(id) ?: throw IllegalStateException("bookId $id 不存在")
                    val cfg = AiClient.config(db)
                    if (!AiClient.isReady(cfg)) throw IllegalStateException("AI 未配置")
                    val text = DocParser.parseText(File(filesDir, b.fileName)).fullText
                    // --ez stream true 时走 SSE 流式通道验证打字机链路
                    val useStream = intent.getBooleanExtra("stream", false)
                    Thread {
                        val out = StringBuilder()
                        try {
                            val t0 = System.currentTimeMillis()
                            val prompt = "根据以下资料回答问题：$q\n\n${text.take(20000)}"
                            val reply = if (useStream)
                                AiClient.chat(cfg, null, prompt, onDelta = { /* 流式增量回调，此处仅验证通路 */ })
                            else
                                AiClient.chat(cfg, null, prompt)
                            db.addNote(id, "ask", "问：$q\n\n答：$reply")
                            out.append("ASK_OK ms=").append(System.currentTimeMillis() - t0)
                                .append(" stream=").append(useStream)
                                .append(" reply_chars=").append(reply.length)
                        } catch (t: Throwable) {
                            out.append("FAIL ").append(t.toString())
                        }
                        Log.i("PARSER_TEST", out.toString().trimEnd())
                        finish()
                    }.start()
                }
                "setkv" -> {
                    val k = intent.getStringExtra("key") ?: throw IllegalArgumentException("缺少 key")
                    Db(this).setSetting(k, intent.getStringExtra("value") ?: "")
                    log.append("OK kv saved: $k")
                }
                "vsum" -> {
                    // PDF 视觉链路 E2E：渲染前几页 → base64 → mock 视觉模型
                    val db = Db(this)
                    val id = (intent.getStringExtra("bookId")?.toLongOrNull() ?: -1L)
                    val b = db.getBook(id) ?: throw IllegalStateException("bookId $id 不存在")
                    val cfg = AiClient.config(db)
                    if (!AiClient.isReady(cfg)) throw IllegalStateException("AI 未配置")
                    Thread {
                        val out = StringBuilder()
                        var pages: List<android.graphics.Bitmap> = emptyList()
                        try {
                            val fd = android.os.ParcelFileDescriptor.open(
                                java.io.File(filesDir, b.fileName),
                                android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                            val renderer = android.graphics.pdf.PdfRenderer(fd)
                            val n = minOf(renderer.pageCount, 3)
                            for (i in 0 until n) {
                                renderer.openPage(i).use { p ->
                                    val w = minOf(720, p.width)
                                    val h = maxOf(1, p.height * w / p.width)
                                    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                                    bmp.eraseColor(-1)
                                    p.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    pages += bmp
                                }
                            }
                            renderer.close()
                            val t0 = System.currentTimeMillis()
                            val reply = AiClient.chatVision(cfg, null,
                                "这些是文档前几页截图，请概括主题。", pages)
                            db.addNote(id, "summary", reply)
                            out.append("VISION_OK pages=").append(pages.size)
                                .append(" ms=").append(System.currentTimeMillis() - t0)
                                .append(" reply_chars=").append(reply.length)
                        } catch (t: Throwable) {
                            out.append("FAIL ").append(t.toString())
                        } finally {
                            pages.forEach { it.recycle() }
                        }
                        Log.i("PARSER_TEST", out.toString().trimEnd())
                        finish()
                    }.start()
                }
                else -> {
                    // 默认：解析自检
                    val path = intent.getStringExtra("path")
                        ?: throw IllegalArgumentException("缺少 path extra")
                    val f = File(path)
                    val fmt = DocParser.detect(f.name)
                    if (fmt == "pdf") {
                        log.append("SKIP pdf（PDF 走渲染通道）")
                    } else {
                        val doc = DocParser.parseText(f)
                        log.append("OK format=").append(doc.format)
                            .append(" blocks=").append(doc.blocks.size)
                            .append(" chars=").append(doc.fullText.length).append('\n')
                        log.append(" headings=").append(doc.blocks.count { it.type == Block.HEADING })
                    }
                }
            }
        } catch (t: Throwable) {
            log.append("FAIL ").append(t.toString())
        }
        Log.i("PARSER_TEST", log.toString().trimEnd())
        finish()
    }
}
