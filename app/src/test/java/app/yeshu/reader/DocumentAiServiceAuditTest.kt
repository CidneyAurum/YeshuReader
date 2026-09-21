package app.yeshu.reader

import app.yeshu.reader.ai.DocumentAiService
import app.yeshu.reader.parse.Block
import app.yeshu.reader.parse.ParsedDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文档 AI 管线的离线用例：检索抽样、输出语言、逐段校验报告、测验 JSON 与本地判分。
 * 全部是纯函数，不触网、不依赖 Android 框架。
 */
class DocumentAiServiceAuditTest {

    // ---------- R14 输出语言 ----------

    @Test
    fun detectLanguage_separatesChineseEnglishAndJapanese() {
        assertEquals(
            DocumentAiService.LANGUAGE_ZH,
            DocumentAiService.detectLanguage("页枢优先在本地管理书籍，AI 只在用户主动触发时运行。")
        )
        assertEquals(
            DocumentAiService.LANGUAGE_EN,
            DocumentAiService.detectLanguage("Yeshu keeps the library local first and never uploads your files.")
        )
        assertEquals(
            DocumentAiService.LANGUAGE_JA,
            DocumentAiService.detectLanguage("これは日本語の資料です。ページを管理します。")
        )
        assertEquals(DocumentAiService.LANGUAGE_UNKNOWN, DocumentAiService.detectLanguage("   "))
    }

    @Test
    fun languageDirective_followsDocumentLanguageUnlessOverridden() {
        assertTrue(
            DocumentAiService.languageDirective(null, DocumentAiService.LANGUAGE_EN).contains("当前文档语言：英文")
        )
        assertTrue(
            DocumentAiService.languageDirective("英文", DocumentAiService.LANGUAGE_ZH).contains("用户指定")
        )
        // 语言不明时退化为「与文档主要语言一致」，不能凭空指定中文
        assertEquals(
            "输出语言：与文档主要语言一致",
            DocumentAiService.languageDirective(null, DocumentAiService.LANGUAGE_UNKNOWN)
        )
    }

    // ---------- R01 检索抽样 ----------

    @Test
    fun buildAnchoredContext_reportsTruncationAndOutlinesOmittedChapters() {
        val blocks = buildList {
            add(Block(Block.TEXT, "# 第一章 开端"))
            repeat(400) { index -> add(Block(Block.TEXT, "普通段落 $index：" + "填充内容".repeat(30))) }
            add(Block(Block.TEXT, "# 第二章 结尾"))
            add(Block(Block.TEXT, "第二章的正文。"))
        }
        val parsed = ParsedDoc("md", blocks, blocks.joinToString("\n") { it.text })

        val context = DocumentAiService.buildAnchoredContext(parsed, "md", 2_048)

        assertTrue(context.truncated)
        assertTrue(context.includedSegments < context.totalSegments)
        assertTrue(context.omittedSegments > 0)
        // 目录必须覆盖没有随上下文发送的章节，模型才知道自己「没看到什么」
        assertTrue(context.outline.contains("[CHAPTER:2]"))
        assertTrue(context.outline.contains("未提供"))
    }

    @Test
    fun buildAnchoredContext_ranksQueryRelevantSegmentIntoBudget() {
        val blocks = buildList {
            repeat(400) { index ->
                if (index == 60) {
                    add(
                        Block(
                            Block.TEXT,
                            "关键术语 量子纠缠 说明：纠缠态无法被局域变量完全描述。" + "补充说明".repeat(20)
                        )
                    )
                } else {
                    add(Block(Block.TEXT, "普通段落 $index：" + "填充内容".repeat(30)))
                }
            }
        }
        val parsed = ParsedDoc("md", blocks, blocks.joinToString("\n") { it.text })

        val plain = DocumentAiService.buildAnchoredContext(parsed, "md", 2_048)
        val asked = DocumentAiService.buildAnchoredContext(parsed, "md", 2_048, "量子纠缠是什么")

        assertTrue(plain.truncated)
        // 覆盖式抽样按「首尾中点」取段落，第 60 段落在预算之外
        assertFalse(plain.text.contains("量子纠缠"))
        // 带问题时该段落必须进入预算，并带上可点击的段落锚点
        assertTrue(asked.text.contains("量子纠缠"))
        assertTrue(asked.anchors.any { it.excerpt.contains("量子纠缠") })
    }

    @Test
    fun buildAnchoredContextFromText_reusesSamplingForChapterScopedText() {
        val text = (0 until 200).joinToString("\n") { index -> "章节片段 $index " + "内容".repeat(40) }

        val context = DocumentAiService.buildAnchoredContextFromText(text, "txt", 2_048)

        assertTrue(context.truncated)
        assertTrue(context.text.isNotBlank())
        assertTrue(context.anchors.any { it.type == AnchorType.PARAGRAPH })
    }

    @Test
    fun queryTerms_splitsLatinWordsAndCjkBigrams() {
        val terms = DocumentAiService.queryTerms("page 页枢 reader")

        assertTrue(terms.contains("page"))
        assertTrue(terms.contains("reader"))
        assertTrue(terms.contains("页枢"))
        assertTrue(DocumentAiService.queryTerms("   ").isEmpty())
    }

    @Test
    fun promptBuilders_carrySamplingNoticeOutlineAndOutputLanguage() {
        val context = DocumentAiService.AnchoredContext(
            text = "[PARAGRAPH:1]\n本地优先，资料不出本机。",
            anchors = listOf(DocumentAnchor(AnchorType.PARAGRAPH, 1, "段落 1")),
            includedSegments = 1,
            totalSegments = 9,
            truncated = true,
            maxChars = 2_048,
            outline = "共 9 段材料，本次提供 1 段；标注「未提供」的部分没有发送给你。"
        )

        val qa = DocumentAiService.buildQaPrompt("页枢手册", "txt", context, "资料存在哪里？")

        assertTrue(qa.contains("资料存在哪里？"))
        // 抽样上下文必须自报「只给了一部分」，否则模型会把抽样当全文
        assertTrue(qa.contains("材料为抽样"))
        assertTrue(qa.contains("材料目录："))
        assertTrue(qa.contains("当前文档语言：中文"))

        assertTrue(DocumentAiService.buildQuizPrompt("页枢手册", "txt", context).contains("\"type\":\"choice\""))
        assertTrue(DocumentAiService.buildSummaryPrompt("页枢手册", "txt", context).contains("材料为抽样"))
        assertTrue(DocumentAiService.buildRecapPrompt(context).contains("材料为抽样"))
    }

    // ---------- R10 / R22 逐段校验报告 ----------

    @Test
    fun validateModelOutput_reportsPerSectionCitationsWithTheirLines() {
        val context = anchoredContext(
            DocumentAnchor(AnchorType.CHAPTER, 1, "第一章"),
            DocumentAnchor(AnchorType.PARAGRAPH, 1, "段落 1")
        )
        val content = """
            ## 分层摘要
            一句话摘要 [CHAPTER:1]
            ## 目录大纲
            - 主题一 [CHAPTER:1]
            ## 关键概念
            概念 A [PARAGRAPH:9]
            ## 核心结论
            - 结论一 [CHAPTER:1]
            - 结论二没有出处
            ## 闪卡
            正面 / 背面 [CHAPTER:1]
            ## 测验
            题目 [PARAGRAPH:1]
        """.trimIndent()

        val validated = DocumentAiService.validateModelOutput(content, context)

        assertFalse(validated.isAcceptable)
        assertEquals(emptyList<String>(), validated.missingSections)

        val concepts = validated.sectionReport.single { it.section == "关键概念" }
        assertTrue(concepts.present)
        assertEquals(listOf("[PARAGRAPH:9]"), concepts.invalidTokens)
        assertEquals("概念 A [PARAGRAPH:9]", concepts.findings.single { !it.valid }.line)

        val conclusion = validated.sectionReport.single { it.section == DocumentAiService.CORE_SECTION }
        assertEquals(listOf("- 结论二没有出处"), conclusion.uncitedLines)

        // 无效引用要能指出所在行，有效引用也要被统计出来（旧实现只报「失败」）
        assertTrue(validated.details.any { it.contains("无效引用 [PARAGRAPH:9]（关键概念）：概念 A [PARAGRAPH:9]") })
        assertTrue(validated.details.any { it.startsWith("有效引用：") })
    }

    @Test
    fun validateModelOutput_requiresCitationsInOutlineConceptsAndFlashcards() {
        val context = anchoredContext(DocumentAnchor(AnchorType.PARAGRAPH, 1, "段落 1"))
        val content = """
            ## 分层摘要
            摘要 [PARAGRAPH:1]
            ## 目录大纲
            - 大纲没有引用
            ## 关键概念
            概念没有引用
            ## 核心结论
            - 结论 [PARAGRAPH:1]
            ## 闪卡
            闪卡没有引用
        """.trimIndent()

        val validated = DocumentAiService.validateModelOutput(content, context)

        assertFalse(validated.isAcceptable)
        assertEquals(listOf("测验"), validated.missingSections)
        // 全局至少一条有效引用已经满足旧规则，这里必须逐段要求
        assertTrue(validated.citations.valid.isNotEmpty())
        assertEquals(
            listOf("目录大纲", "关键概念", "闪卡"),
            validated.sectionReport.filter { it.requiresCitation && it.validCitationCount == 0 }.map { it.section }
        )
        assertTrue(validated.details.any { it.contains("关键概念：整段没有可核对的引用") })
    }

    @Test
    fun validateModelOutput_acceptsPackWithCitationsInEveryRequiredSection() {
        val context = anchoredContext(DocumentAnchor(AnchorType.PARAGRAPH, 1, "段落 1"))
        val content = """
            ## 分层摘要
            摘要 [PARAGRAPH:1]
            ## 目录大纲
            - 大纲 [PARAGRAPH:1]
            ## 关键概念
            概念 [PARAGRAPH:1]
            ## 核心结论
            - 结论 [PARAGRAPH:1]
            ## 闪卡
            正面 / 背面 [PARAGRAPH:1]
            ## 测验
            题目 [PARAGRAPH:1]
        """.trimIndent()

        val validated = DocumentAiService.validateModelOutput(content, context)

        assertTrue(validated.isAcceptable)
        assertTrue(validated.sectionReport.all { it.ok })
        assertEquals(6, validated.sectionReport.size)
    }

    @Test
    fun buildCitationRepairPrompt_listsInvalidTokensAndAllowedAnchors() {
        val context = anchoredContext(
            DocumentAnchor(AnchorType.CHAPTER, 1, "第一章"),
            DocumentAnchor(AnchorType.PARAGRAPH, 2, "段落 2")
        )

        val prompt = DocumentAiService.buildCitationRepairPrompt(
            originalOutput = "## 核心结论\n- 结论 [PARAGRAPH:9]",
            invalidTokens = listOf("[PARAGRAPH:9]"),
            context = context
        )

        assertTrue(prompt.contains("[PARAGRAPH:9]"))
        assertTrue(prompt.contains("[CHAPTER:1]"))
        assertTrue(prompt.contains("[PARAGRAPH:2]"))
        assertEquals(0.0, DocumentAiService.TEMPERATURE_GRADING, 0.0)
    }

    // ---------- R15 / R21 测验 JSON 与本地判分 ----------

    @Test
    fun parseQuizJson_acceptsFencedPayloadAndKeepsTheAnswerKey() {
        val payload = """
            好的，这是题目：
            ```json
            {"questions":[
              {"id":1,"type":"choice","stem":"页枢的默认策略是什么？","options":["A. 云端优先","B. 本地优先"],"answer":"B","explanation":"材料强调本地优先","citations":["CHAPTER:1"]},
              {"id":2,"type":"short","stem":"为什么本地优先？","options":[],"answer":"隐私与离线可用","explanation":"要点：隐私、离线","citations":["PARAGRAPH:2"]}
            ]}
            ```
        """.trimIndent()

        val quiz = DocumentAiService.parseQuizJson(payload)

        assertNotNull(quiz)
        assertEquals(2, quiz!!.questions.size)
        val choice = quiz.questions.first()
        assertEquals(1, choice.id)
        assertTrue(choice.isChoice)
        assertEquals(listOf("A. 云端优先", "B. 本地优先"), choice.options)
        assertEquals("B", choice.answerLetter)
        assertEquals(listOf("CHAPTER:1"), choice.citations)
        assertFalse(quiz.questions[1].isChoice)
        assertEquals("隐私与离线可用", quiz.questions[1].answer)
    }

    @Test
    fun parseQuizJson_rejectsMalformedPayloads() {
        assertNull(DocumentAiService.parseQuizJson("""{"questions": [{"id":1,"""))
        assertNull(DocumentAiService.parseQuizJson("完全不是 JSON"))
        assertNull(DocumentAiService.parseQuizJson("""{"questions": []}"""))
        assertNull(DocumentAiService.parseQuizJson("""{"items":[{"stem":"题干"}]}"""))
        assertNull(DocumentAiService.parseQuizJson(""))
    }

    @Test
    fun gradeObjective_scoresChoiceItemsLocallyRegardlessOfQuestionCount() {
        val questions = listOf(
            quizQuestion(1, DocumentAiService.TYPE_CHOICE, "B"),
            quizQuestion(2, DocumentAiService.TYPE_CHOICE, "A"),
            quizQuestion(3, DocumentAiService.TYPE_CHOICE, "C"),
            quizQuestion(4, DocumentAiService.TYPE_SHORT, "参考答案"),
            quizQuestion(5, DocumentAiService.TYPE_SHORT, "参考答案")
        )

        assertEquals(3, DocumentAiService.gradeObjective(questions, "1B 2A 3C"))
        assertEquals(2, DocumentAiService.gradeObjective(questions, "1B 2B 3C"))
        // 题目数不是 5、答案也不全时，只按答对的选择题计数，不会再出现「每题 20 分」的错算
        assertEquals(2, DocumentAiService.gradeObjective(questions, "1.B 2.A"))
        assertEquals(0, DocumentAiService.gradeObjective(questions, "选择题都不会"))
        // 没有可判分的选择题时返回 null，调用方回退到模型判分
        assertNull(DocumentAiService.gradeObjective(questions.filter { !it.isChoice }, "1B"))
        assertEquals(2, DocumentAiService.gradeObjective(questions, "1:B 2：A"))
    }

    @Test
    fun normalizeChoiceLetter_acceptsLettersWithExplanationText() {
        assertEquals("B", DocumentAiService.normalizeChoiceLetter("B"))
        assertEquals("B", DocumentAiService.normalizeChoiceLetter("b. 因为本地优先"))
        assertEquals("B", DocumentAiService.normalizeChoiceLetter("答案是B"))
        assertNull(DocumentAiService.normalizeChoiceLetter("无法判断"))
    }

    // ---------- 采样参数 ----------

    @Test
    fun defaultSampling_matchesTheTaskRequirements() {
        assertEquals(
            DocumentAiService.TEMPERATURE_STUDY_PACK,
            DocumentAiService.defaultTemperature(DocumentAiService.KIND_STUDY_PACK),
            0.0
        )
        assertEquals(DocumentAiService.MAX_TOKENS_STUDY_PACK, DocumentAiService.defaultMaxTokens(DocumentAiService.KIND_STUDY_PACK))
        assertEquals(
            DocumentAiService.TEMPERATURE_QUIZ,
            DocumentAiService.defaultTemperature(DocumentAiService.KIND_QUIZ),
            0.0
        )
        assertEquals(DocumentAiService.MAX_TOKENS_QUIZ, DocumentAiService.defaultMaxTokens(DocumentAiService.KIND_QUIZ))
        assertEquals(0.0, DocumentAiService.TEMPERATURE_GRADING, 0.0)
        assertEquals(0.9, DocumentAiService.TEMPERATURE_CONTINUE, 0.0)
        assertEquals(0.7, DocumentAiService.TEMPERATURE_CHAT, 0.0)
    }

    private fun quizQuestion(id: Int, type: String, answer: String) = DocumentAiService.QuizQuestion(
        id = id,
        type = type,
        stem = "第 $id 题",
        options = if (type == DocumentAiService.TYPE_CHOICE) listOf("A. 甲", "B. 乙", "C. 丙") else emptyList(),
        answer = answer,
        explanation = "",
        citations = emptyList()
    )

    private fun anchoredContext(vararg anchors: DocumentAnchor) = DocumentAiService.AnchoredContext(
        text = "test context",
        anchors = anchors.toList(),
        includedSegments = anchors.size,
        totalSegments = anchors.size,
        truncated = false,
        maxChars = 2_048
    )
}
