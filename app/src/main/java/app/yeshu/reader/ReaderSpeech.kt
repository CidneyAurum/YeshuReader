package app.yeshu.reader

import android.app.Activity
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 阅读朗读。
 *
 * 逐块朗读而不是把整章拼成一段：这样能知道「现在读到哪一块」，从而高亮并滚动跟随；
 * 整段朗读在长章节里既看不到进度，也无法在中途接着读。
 *
 * 引擎初始化是异步的，所以 [start] 可能在引擎就绪前被调用——这种情况会先记下请求，
 * 就绪后自动开始，而不是静默失败。
 */
class ReaderSpeech(
    private val activity: Activity,
    /** 朗读到第几块时回调，用于高亮与滚动跟随。 */
    private val onProgress: (Int) -> Unit,
    /** 整篇读完（或用户停止后自然结束）。 */
    private val onFinished: () -> Unit,
    /** 引擎不可用等异常情况，交给界面提示。 */
    private val onUnavailable: (String) -> Unit,
) {
    private var engine: TextToSpeech? = null
    private var ready = false
    private var released = false
    private var texts: List<String> = emptyList()
    private var cursor = 0
    private var paused = false

    /** 语速倍率。0.6 偏慢适合跟读，1.6 适合通读。 */
    var rate: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            engine?.setSpeechRate(field)
        }

    val isActive: Boolean get() = texts.isNotEmpty()

    init {
        engine = TextToSpeech(activity) { status ->
            if (released) return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                engine?.apply {
                    language = Locale.CHINA
                    setSpeechRate(rate)
                    setOnUtteranceProgressListener(progressListener)
                }
                // 初始化期间用户已经点了朗读：这里补上，否则那一次点击看起来毫无反应。
                if (texts.isNotEmpty()) speakCurrent()
            } else {
                onUnavailable("本机没有可用的语音引擎，无法朗读。可在系统设置里安装「文字转语音」后再试。")
            }
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            if (released || paused) return
            // 空块（纯图片占位、空行）会被跳过，所以推进时要跳过没有可读文本的块。
            var next = cursor + 1
            while (next < texts.size && texts[next].isBlank()) next++
            if (next >= texts.size) {
                stop()
                onFinished()
                return
            }
            cursor = next
            onProgress(next)
            speakCurrent()
        }

        @Deprecated("TextToSpeech 的旧回调，仍会被部分引擎调用")
        override fun onError(utteranceId: String?) {
            onUnavailable("朗读中断：语音引擎报错，请重试或换一个引擎。")
            stop()
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onUnavailable("朗读中断（错误码 $errorCode），请重试或换一个引擎。")
            stop()
        }
    }

    /** 从 [from] 块开始朗读。[blocks] 是与正文块一一对应的纯文本。 */
    fun start(blocks: List<String>, from: Int) {
        val first = from.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
        if (blocks.isEmpty() || blocks.all { it.isBlank() }) {
            onUnavailable("这篇文档没有可朗读的文字。")
            return
        }
        texts = blocks
        cursor = if (blocks[first].isBlank()) blocks.indexOfFirst { it.isNotBlank() }.coerceAtLeast(0) else first
        paused = false
        onProgress(cursor)
        if (ready) speakCurrent()
    }

    fun pause() {
        if (!isActive) return
        paused = true
        engine?.stop()
    }

    fun resume() {
        if (!isActive || !paused) return
        paused = false
        speakCurrent()
    }

    /** 停止并清空状态；再次朗读需要重新 [start]。 */
    fun stop() {
        texts = emptyList()
        cursor = 0
        paused = false
        engine?.stop()
    }

    fun release() {
        released = true
        texts = emptyList()
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    private fun speakCurrent() {
        val current = texts.getOrNull(cursor) ?: return
        val text = current.trim()
        if (text.isEmpty()) {
            // 跳过空块，避免朗读停在空白上不动。
            progressListener.onDone(null)
            return
        }
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yeshu-$cursor")
    }
}
