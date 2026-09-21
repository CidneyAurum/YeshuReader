#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
页枢解析器测试语料生成器。

产出真实的文档文件（由 python-docx / python-pptx / ebooklib / reportlab 等真实
生产者生成，而不是手写 XML），写入 app/src/test/resources/corpus/。

这些文件供 DocParserCorpusTest 使用：只有用真实文件跑解析器，才能发现
「看起来对、实际读不出来」的兼容性问题。

用法：
    python tools/make_test_corpus.py
"""
import os
import shutil
import struct
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "test" / "resources" / "corpus"

CJK = "第一章 排期与重构\n\n今天下午和产品组过了新版本的排期，决定下周三前完成登录模块的重构。\n有点累，但方向清楚了。"
CJK2 = "第二章 接口文档\n\n记得周五前把接口文档补上，顺便把登录模块的时序图更新一下。"
LONG_PARA = "这是一段用于测试长段落分块的中文内容。" * 20


def reset():
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True, exist_ok=True)


# ---------------------------------------------------------------- OOXML 系

def make_docx():
    """真实 Word 文档：标题样式、表格、图片、项目符号、脚注、超链接、中文。"""
    from docx import Document
    from docx.shared import Inches, Pt
    from PIL import Image

    doc = Document()
    doc.add_heading("第一章 排期与重构", level=1)
    doc.add_paragraph(CJK.split("\n\n", 1)[1])
    doc.add_heading("1.1 关键决定", level=2)
    for item in ("下周三前完成登录模块重构", "周五前补上接口文档", "同步更新时序图"):
        doc.add_paragraph(item, style="List Bullet")
    table = doc.add_table(rows=3, cols=2)
    table.cell(0, 0).text = "模块"
    table.cell(0, 1).text = "负责人"
    table.cell(1, 0).text = "登录"
    table.cell(1, 1).text = "小张"
    table.cell(2, 0).text = "文档"
    table.cell(2, 1).text = "小李"
    # 内嵌图片（解析器必须跳过它而不是报错）
    img_path = OUT / "_tmp_docx_image.png"
    Image.new("RGB", (80, 60), (30, 90, 160)).save(img_path)
    doc.add_picture(str(img_path), width=Inches(1.2))
    img_path.unlink()
    doc.add_paragraph(LONG_PARA)
    doc.add_heading("第二章 接口文档", level=1)
    doc.add_paragraph(CJK2.split("\n\n", 1)[1])
    doc.save(OUT / "sample.docx")

    # docm：同结构、不同扩展名，验证扩展名映射
    shutil.copy(OUT / "sample.docx", OUT / "sample.docm")

    # 空文档（只有空段落）——必须报「未提取到文本」而不是崩溃
    empty = Document()
    empty.add_paragraph("")
    empty.save(OUT / "empty.docx")

    # 损坏文档：ZIP 合法但没有 word/document.xml
    with zipfile.ZipFile(OUT / "broken.docx", "w") as z:
        z.writestr("[Content_Types].xml", "<Types/>")
        z.writestr("word/styles.xml", "<styles/>")


def make_pptx():
    """真实 PPT：多页、标题+要点、备注、表格、图片。"""
    from pptx import Presentation
    from pptx.util import Inches
    from PIL import Image

    prs = Presentation()
    layout = prs.slide_layouts[1]
    for idx, (title, bullets) in enumerate([
        ("第 1 张：项目概览", ["目标：完成登录模块重构", "范围：客户端与服务端", "周期：两周"]),
        ("第 2 张：风险", ["排期与联调冲突", "文档滞后于实现"]),
    ], start=1):
        slide = prs.slides.add_slide(layout)
        slide.shapes.title.text = title
        body = slide.placeholders[1].text_frame
        body.text = bullets[0]
        for b in bullets[1:]:
            body.add_paragraph().text = b
        slide.notes_slide.notes_text_frame.text = f"第 {idx} 页的讲稿备注"
    table_slide = prs.slides.add_slide(prs.slide_layouts[5])
    table_slide.shapes.title.text = "第 3 张：分工"
    rows, cols = 2, 2
    table = table_slide.shapes.add_table(rows, cols, Inches(1), Inches(2), Inches(6), Inches(1.5)).table
    table.cell(0, 0).text = "模块"
    table.cell(0, 1).text = "负责人"
    table.cell(1, 0).text = "登录"
    table.cell(1, 1).text = "小张"
    prs.save(OUT / "sample.pptx")
    shutil.copy(OUT / "sample.pptx", OUT / "sample.ppsx")

    # 无幻灯片内容
    blank = Presentation()
    blank.save(OUT / "empty.pptx")


def make_epub2():
    """EPUB2：NCX 目录、多章、封面、中文。"""
    from ebooklib import epub

    book = epub.EpubBook()
    book.set_identifier("yeshu-corpus-epub2")
    book.set_title("排期与重构")
    book.set_language("zh")
    chapters = []
    for i, (title, body) in enumerate([("第一章 排期", CJK), ("第二章 文档", CJK2)], start=1):
        c = epub.EpubHtml(title=title, file_name=f"chap_{i}.xhtml", lang="zh")
        c.content = f"<h1>{title}</h1><p>{body.split(chr(10)+chr(10))[-1]}</p>"
        book.add_item(c)
        chapters.append(c)
    book.toc = tuple(chapters)
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = ["nav"] + chapters
    epub.write_epub(str(OUT / "sample_epub2.epub"), book)


def make_epub3():
    """EPUB3：nav 文档、嵌套目录、无 NCX。"""
    from ebooklib import epub

    book = epub.EpubBook()
    book.set_identifier("yeshu-corpus-epub3")
    book.set_title("Nested Book")
    book.set_language("zh")
    part = epub.EpubHtml(title="第一部分", file_name="part1.xhtml", lang="zh")
    part.content = "<h1>第一部分</h1><p>开篇。</p>"
    sub = epub.EpubHtml(title="第一章", file_name="part1_ch1.xhtml", lang="zh")
    sub.content = "<h1>第一章</h1><p>嵌套目录下的正文。</p>"
    sub2 = epub.EpubHtml(title="第二章", file_name="part1_ch2.xhtml", lang="zh")
    sub2.content = "<h1>第二章</h1><p>嵌套目录下的第二节。</p>"
    book.add_item(part)
    book.add_item(sub)
    book.add_item(sub2)
    # ebooklib 的嵌套目录形如 (父项, (子项...)) 作为一个整体元素，
    # 而不是「父项 + 兄弟元组」。
    book.toc = (
        (
            epub.Link("part1.xhtml", "第一部分", "part1"),
            (
                epub.Link("part1_ch1.xhtml", "第一章", "ch1"),
                epub.Link("part1_ch2.xhtml", "第二章", "ch2"),
            ),
        ),
    )
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = ["nav", part, sub, sub2]
    epub.write_epub(str(OUT / "sample_epub3.epub"), book)


def make_epub_broken():
    """EPUB 外壳但没有 container.xml。"""
    with zipfile.ZipFile(OUT / "broken.epub", "w") as z:
        z.writestr("mimetype", "application/epub+zip")
        z.writestr("content.opf", "<package/>")


def make_odt():
    """ODT：OpenDocument 文本，结构与 DOCX 不同（content.xml + text:p / text:h）。"""
    manifest = """<?xml version="1.0" encoding="UTF-8"?>
<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0">
  <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text"/>
  <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
</manifest:manifest>"""
    content = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content
    xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
  <office:body><office:text>
    <text:h text:outline-level="1">第一章 ODT 标题</text:h>
    <text:p>这是 ODT 的正文段落。</text:p>
    <text:h text:outline-level="2">1.1 小节</text:h>
    <text:p>小节正文，含<text:span>行内标记</text:span>。</text:p>
  </office:text></office:body>
</office:document-content>"""
    # 真实的 ODT 一定有 META-INF/manifest.xml 与 mimetype
    with zipfile.ZipFile(OUT / "sample.odt", "w") as z:
        z.writestr("mimetype", "application/vnd.oasis.opendocument.text")
        z.writestr("META-INF/manifest.xml", manifest)
        z.writestr("content.xml", content)
    # 精简版：部分导出工具不写 META-INF，也必须能读
    with zipfile.ZipFile(OUT / "minimal.odt", "w") as z:
        z.writestr("content.xml", content)


# ---------------------------------------------------------------- 纯文本系

def make_text_variants():
    body = CJK + "\n\n" + CJK2 + "\n"
    (OUT / "utf8.txt").write_bytes(body.encode("utf-8"))
    (OUT / "utf8_bom.txt").write_bytes(b"\xef\xbb\xbf" + body.encode("utf-8"))
    (OUT / "utf16le.txt").write_bytes(body.encode("utf-16-le"))
    (OUT / "utf16be_bom.txt").write_bytes(body.encode("utf-16-be"))
    (OUT / "gbk.txt").write_bytes(body.encode("gbk"))
    (OUT / "utf16le_bom.txt").write_bytes(b"\xff\xfe" + body.encode("utf-16-le"))
    # 无 BOM 的 UTF-16BE：中文 UTF-16 不产生 NUL，旧判据会把它当二进制
    (OUT / "utf16be_nobom.txt").write_bytes(body.encode("utf-16-be"))
    (OUT / "crlf.txt").write_bytes(body.replace("\n", "\r\n").encode("utf-8"))
    # 纯 ASCII：不应被误判为 UTF-16
    (OUT / "ascii.txt").write_bytes(("Chapter 1\n\n" + "plain english text. " * 40 + "\n").encode("ascii"))
    # 空文件
    (OUT / "empty.txt").write_bytes(b"")
    # 只有空白
    (OUT / "blank.txt").write_bytes("   \n\t\n  ".encode("utf-8"))
    # 没有换行的超长单行（分块器必须能切）
    (OUT / "one_line.txt").write_bytes(("一段没有任何换行的超长中文文本。" * 400).encode("utf-8"))
    # 极多段落，验证 MAX_BLOCKS 行为
    (OUT / "many_paragraphs.txt").write_bytes(
        "\n".join(f"第{i}段：内容。" for i in range(1, 3001)).encode("utf-8")
    )


def make_markdown():
    (OUT / "sample.md").write_text(
        "# 排期与重构\n\n"
        "正文第一段。\n\n"
        "## 1.1 关键决定\n\n"
        "- 下周三前完成重构\n- 周五前补文档\n\n"
        "```kotlin\nval x = 1\n```\n\n"
        "| 模块 | 负责人 |\n| --- | --- |\n| 登录 | 小张 |\n\n"
        "第三章 收尾\n\n最后一段。\n",
        encoding="utf-8",
    )


def make_html():
    (OUT / "sample.html").write_text(
        """<!DOCTYPE html><html><head><meta charset="utf-8"><title>单文件网页书</title></head>
<body>
<h1>第一章 网页正文</h1>
<p>这是一个单文件 HTML 文档的正文。</p>
<script>var x = 1;</script>
<style>body { color: red; }</style>
<h2>1.1 小节</h2>
<ul><li>要点一</li><li>要点二</li></ul>
<p>结束段落。</p>
</body></html>""",
        encoding="utf-8",
    )


def make_rtf():
    (OUT / "sample.rtf").write_text(
        r"{\rtf1\ansi\ansicpg936\deff0"
        r"{\fonttbl{\f0\fnil\fcharset134 SimSun;}}"
        r"\viewkind4\uc1\pard\f0\fs24 "
        r"\u31532\'d2\'bb\'d5\'c2 RTF \'b1\'ea\'cc\'e2\par "
        r"\'d5\'fd\'ce\'c4\'b6\'ce\'c2\'e4\'a1\'a3\par "
        r"\u31532\'b6\'fe\'d5\'c2 \'bd\'e1\'ce\'b2\par "
        r"}",
        encoding="ascii",
    )


def make_fb2():
    (OUT / "sample.fb2").write_text(
        """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"
             xmlns:l="http://www.w3.org/1999/xlink">
  <description><title-info><book-title>FB2 测试书</book-title></title-info></description>
  <body>
    <section>
      <title><p>第一章 FB2 标题</p></title>
      <p>这是 FB2 的正文段落。</p>
      <section><title><p>1.1 小节</p></title><p>小节正文。</p></section>
    </section>
    <section><title><p>第二章 收尾</p></title><p>最后一段。</p></section>
  </body>
</FictionBook>""",
        encoding="utf-8",
    )


def make_csv():
    (OUT / "sample.csv").write_text(
        "模块,负责人,状态\n登录,小张,进行中\n文档,小李,未开始\n", encoding="utf-8"
    )
    (OUT / "sample.tsv").write_text("模块\t负责人\n登录\t小张\n", encoding="utf-8")


def make_xlsx():
    from openpyxl import Workbook

    wb = Workbook()
    ws = wb.active
    ws.title = "排期"
    ws.append(["模块", "负责人", "状态"])
    ws.append(["登录", "小张", "进行中"])
    ws.append(["文档", "小李", "未开始"])
    ws2 = wb.create_sheet("风险")
    ws2.append(["风险", "影响"])
    ws2.append(["联调冲突", "高"])
    wb.save(OUT / "sample.xlsx")


# ---------------------------------------------------------------- 图像 / 压缩包

def make_images():
    from PIL import Image

    Image.new("RGB", (400, 300), (200, 40, 60)).save(OUT / "cover.jpg")
    Image.new("RGB", (400, 300), (20, 120, 80)).save(OUT / "cover.png")
    Image.new("RGB", (400, 300), (60, 60, 200)).save(OUT / "cover.webp")
    Image.new("RGB", (400, 300), (120, 120, 120)).save(OUT / "cover.bmp")
    Image.new("RGB", (400, 300), (10, 200, 200)).save(OUT / "cover.gif")
    # 扩展名与实际内容不符：应以内容签名判型
    shutil.copy(OUT / "cover.png", OUT / "mismatched.jpg")


def make_cbz():
    from PIL import Image

    with zipfile.ZipFile(OUT / "sample.cbz", "w") as z:
        for i in range(1, 4):
            p = OUT / f"_tmp_page{i}.png"
            Image.new("RGB", (600, 900), (i * 60, 40, 40)).save(p)
            z.write(p, f"page_{i:03d}.png")
            p.unlink()


def make_zip_of_book():
    with zipfile.ZipFile(OUT / "wrapped.zip", "w") as z:
        z.write(OUT / "sample.md", "book/sample.md")


def make_wps_variants():
    """WPS Office 的三种格式与 OOXML 同构，只是扩展名不同。"""
    shutil.copy(OUT / "sample.docx", OUT / "sample.wps")
    shutil.copy(OUT / "sample.xlsx", OUT / "sample.et")
    shutil.copy(OUT / "sample.pptx", OUT / "sample.dps")


def make_more_archives():
    # 嵌套压缩包：zip 里再放 zip，不应无限下钻
    with zipfile.ZipFile(OUT / "nested.zip", "w") as z:
        z.write(OUT / "wrapped.zip", "inner.zip")
    # 空压缩包
    with zipfile.ZipFile(OUT / "empty.zip", "w"):
        pass
    # 压缩包里放多个文档：无法确定读哪个，应给出可读提示
    with zipfile.ZipFile(OUT / "multi.zip", "w") as z:
        z.write(OUT / "sample.md", "a.md")
        z.write(OUT / "sample.csv", "b.csv")


def _epub_zip(name, container, opf_path, opf, entries):
    """按给定结构拼一个 EPUB，用于覆盖各种真实形态。"""
    with zipfile.ZipFile(OUT / name, "w") as z:
        z.writestr("mimetype", "application/epub+zip")
        z.writestr("META-INF/container.xml", container)
        z.writestr(opf_path, opf)
        for path, data in entries.items():
            z.writestr(path, data)


CONTAINER_ONE = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="{opf}" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""


def make_epub_variants():
    """真实 EPUB 的结构差异极大，这里把常见形态逐个钉住。"""

    # 1) XHTML 里用了未声明的 HTML 实体（&nbsp; &mdash;）——真实电子书极其常见，
    #    但严格 XML 会直接报「未定义实体」
    opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="i">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>实体测试</dc:title></metadata>
  <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine><itemref idref="c1"/></spine>
</package>"""
    xhtml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章</title></head>
<body><h1>第一章 实体</h1>
<p>这里有&nbsp;不换行空格&nbsp;和破折号&mdash;以及省略号&hellip;</p>
<p>还有版权符号&copy;与商标&trade;</p>
</body></html>"""
    _epub_zip("epub_entities.epub", CONTAINER_ONE.format(opf="content.opf"), "content.opf", opf,
              {"c1.xhtml": xhtml})

    # 2) OPF 在子目录，正文在更深一层，且用相对路径引用
    opf_nested = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="i">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>嵌套路径</dc:title></metadata>
  <manifest>
    <item id="c1" href="text/chap1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="../OEBPS/text/chap2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>"""
    _epub_zip("epub_nested.epub", CONTAINER_ONE.format(opf="OEBPS/content.opf"), "OEBPS/content.opf", opf_nested,
              {
                  "OEBPS/text/chap1.xhtml": "<html><body><h1>第一章 嵌套</h1><p>子目录里的正文。</p></body></html>",
                  "OEBPS/text/chap2.xhtml": "<html><body><h1>第二章</h1><p>用 ../ 回退引用的正文。</p></body></html>",
              })

    # 3) container.xml 声明了多个 rootfile（常见于同时含 EPUB2/3 的包）
    multi_container = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="missing.opf" media-type="application/oebps-package+xml"/>
    <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
    opf_simple = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="i">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>多 rootfile</dc:title></metadata>
  <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine><itemref idref="c1"/></spine>
</package>"""
    _epub_zip("epub_multi_rootfile.epub", multi_container, "content.opf", opf_simple,
              {"c1.xhtml": "<html><body><h1>第一章</h1><p>第一个可用的 rootfile。</p></body></html>"})

    # 4) 纯图片的固定版式 EPUB（漫画/扫描书）：spine 里全是包着 <img> 的页面，没有正文文字
    opf_img = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="i">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>图片书</dc:title></metadata>
  <manifest>
    <item id="p1" href="p1.xhtml" media-type="application/xhtml+xml"/>
    <item id="p2" href="p2.xhtml" media-type="application/xhtml+xml"/>
    <item id="i1" href="images/1.png" media-type="image/png"/>
    <item id="i2" href="images/2.png" media-type="image/png"/>
  </manifest>
  <spine><itemref idref="p1"/><itemref idref="p2"/></spine>
</package>"""
    from PIL import Image as _Image
    png = OUT / "_tmp_epub_page.png"
    _Image.new("RGB", (600, 900), (40, 60, 120)).save(png)
    png_bytes = png.read_bytes()
    png.unlink()
    _epub_zip("epub_image_only.epub", CONTAINER_ONE.format(opf="content.opf"), "content.opf", opf_img,
              {
                  "p1.xhtml": '<html><body><img src="images/1.png" alt="page 1"/></body></html>',
                  "p2.xhtml": '<html><body><img src="images/2.png" alt="page 2"/></body></html>',
                  "images/1.png": png_bytes,
                  "images/2.png": png_bytes,
              })

    # 5) XHTML 用 GBK 编码（中文盗版电子书常见）
    opf_gbk = opf_simple.replace("多 rootfile", "GBK 正文")
    _epub_zip("epub_gbk.epub", CONTAINER_ONE.format(opf="content.opf"), "content.opf", opf_gbk,
              {"c1.xhtml": """<?xml version="1.0" encoding="GBK"?>
<html><body><h1>第一章 GBK</h1><p>这是 GBK 编码的正文段落。</p></body></html>""".encode("gbk")})

    # 6) 没有 spine（只有 manifest）—— 应当给出可读原因而不是崩溃
    opf_no_spine = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="i">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>无 spine</dc:title></metadata>
  <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine></spine>
</package>"""
    _epub_zip("epub_no_spine.epub", CONTAINER_ONE.format(opf="content.opf"), "content.opf", opf_no_spine,
              {"c1.xhtml": "<html><body><p>不在 spine 里。</p></body></html>"})

    # 7) 带 DRM 声明（Adobe/Readium）—— 应提示受保护而不是「格式不支持」
    opf_drm = opf_simple.replace("多 rootfile", "受保护")
    _epub_zip("epub_drm.epub", CONTAINER_ONE.format(opf="content.opf"), "content.opf", opf_drm,
              {
                  "c1.xhtml": "<html><body><p>加密正文。</p></body></html>",
                  "META-INF/encryption.xml": """<?xml version="1.0"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#"><CipherData><CipherReference URI="c1.xhtml"/></CipherData></EncryptedData>
</encryption>""",
              })

    # 8) spine 里有 linear="no" 的条目（封面页等），不应被当正文
    opf_linear = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="i">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>linear</dc:title></metadata>
  <manifest>
    <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="cover" linear="no"/><itemref idref="c1"/></spine>
</package>"""
    _epub_zip("epub_linear_no.epub", CONTAINER_ONE.format(opf="content.opf"), "content.opf", opf_linear,
              {
                  "cover.xhtml": "<html><body><p>封面占位。</p></body></html>",
                  "c1.xhtml": "<html><body><h1>第一章</h1><p>正文内容。</p></body></html>",
              })

    # 9) 不规范的 XHTML：<br> 不闭合、属性不加引号
    _epub_zip("epub_sloppy.epub", CONTAINER_ONE.format(opf="content.opf"), "content.opf",
              opf_simple.replace("多 rootfile", "不规范"),
              {"c1.xhtml": """<html><body>
<h1>第一章 不规范</h1>
<p>第一行<br>第二行<br/>第三行</p>
<p align=center>居中段落</p>
</body></html>"""})

    # 10) XHTML 带 UTF-8 BOM
    _epub_zip("epub_bom.epub", CONTAINER_ONE.format(opf="content.opf"), "content.opf",
              opf_simple.replace("多 rootfile", "BOM"),
              {"c1.xhtml": b"\xEF\xBB\xBF" + "<html><body><h1>第一章 BOM</h1><p>带 BOM 的正文。</p></body></html>".encode("utf-8")})


def make_adversarial():
    # 路径穿越
    with zipfile.ZipFile(OUT / "traversal.docx", "w") as z:
        z.writestr("word/document.xml", "<w:document/>")
        z.writestr("../../evil.txt", "x")
    # 压缩炸弹（高压缩比）
    with zipfile.ZipFile(OUT / "bomb.docx", "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("word/document.xml", "<w:document>" + ("A" * 40_000_000) + "</w:document>")
    # 截断的 ZIP
    data = (OUT / "sample.docx").read_bytes()
    (OUT / "truncated.docx").write_bytes(data[: len(data) // 2])


def main():
    reset()
    make_docx()
    make_pptx()
    make_epub2()
    make_epub3()
    make_epub_broken()
    make_odt()
    make_text_variants()
    make_markdown()
    make_html()
    make_rtf()
    make_fb2()
    make_csv()
    make_xlsx()
    make_images()
    make_cbz()
    make_zip_of_book()
    make_epub_variants()
    make_wps_variants()
    make_more_archives()
    make_adversarial()
    files = sorted(p.name for p in OUT.iterdir())
    print(f"corpus written to {OUT}")
    for name in files:
        print(f"  {name:<24} {(OUT / name).stat().st_size:>10} bytes")


if __name__ == "__main__":
    main()
