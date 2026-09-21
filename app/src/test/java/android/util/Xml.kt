package android.util

import org.xmlpull.v1.XmlPullParser

/**
 * JVM 单元测试垫片：`android.util.Xml`。
 *
 * android.jar 里这个方法体是 stub，调用即抛异常，导致 EPUB / DOCX / PPTX 三个
 * XML 解析器在离线单元测试中完全不可达——它们此前没有任何测试覆盖，而这三个格式
 * 恰恰是用户导入最多的。
 *
 * 这里委托给 kxml2：Android 的 XmlPullParser 实现本身就源自它，因此解析行为
 * （事件序列、命名空间处理、属性读取）与真机最接近。
 *
 * 必须复刻 Android 的默认 feature：`android.util.Xml.newPullParser()` 会显式打开
 * FEATURE_PROCESS_NAMESPACES，而 kxml2 默认是关闭的。少了这一行，`xp.name` 会返回
 * 带前缀的 `w:p` 而不是 `p`，解析器一个标签都匹配不上——测试会误报「文档内未提取到文本」。
 *
 * （Android 还会打开 FEATURE_PROCESS_DOCDECL，但 kxml2 2.3.0 不支持该 feature，
 * 而这里解析的 OOXML/EPUB 都不依赖 DTD，故省略。）
 *
 * 注意 `@JvmStatic`：生产代码是针对 android.jar 的静态方法编译的，字节码里是
 * invokestatic，垫片必须提供真正的静态方法才能被解析到。
 */
object Xml {

    @JvmStatic
    fun newPullParser(): XmlPullParser = org.kxml2.io.KXmlParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    }
}
