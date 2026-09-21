# 页枢 UI + 功能深度打磨计划（50 轮）

审计范围：`ReaderView.kt`(3259)、`ui/YeshuApp.kt`(2444)、`ShelfView.kt`(2233)、
`NotesView.kt`(591)、`StatsView.kt`(435)、`ChatView.kt`(431)、`BottomSheet.kt`(165)、
`GlassUi.kt`(267)、`Icons.kt`(209)、`Tokens.kt`(46)、`ui/YeshuTheme.kt`、`ui/GlassComponents.kt`、
`data/YeshuDatabase.kt`(475)、`Db.kt`(426)、`ai/`、`security/SecureKeyStore.kt`(145)、
`res/values/*`、`AndroidManifest.xml`。

架构事实（影响改法，先写清楚）：本项目**不是**纯 legacy View，也不是纯 Compose。
`MainActivity` 用 `setContent {}` 起 Compose 壳（`ComponentActivity`，`MainActivity.kt:132`），
书架/阅读器/笔记/统计是 legacy View，通过 `AndroidView` 挂进 Compose（`YeshuApp.kt:434-452`
的 `LegacyHost`、`YeshuApp.kt:391-405` 的 `ReaderViewHost`）。因此「Compose 侧」与
「legacy 侧」各有一套颜色/字号常量，两边的视觉语言必须同时改，否则会继续割裂。

严重度说明：BLOCKER = 用户当场能感知的鸡肋/谎报/功能缺失；HIGH = 明显影响信任或效率；
MEDIUM = 打磨项；末轮 R50 为全局字体/图标/配色扫尾。

## 实施状态：50 轮全部完成

本轮补齐的轮次（此前未做）：

- **R33 主题切换过渡**：`applyReaderTheme` 拆成「动画 + 换色」两步——先淡到 0.35、在中点换色、再淡回，避免深浅切换整屏瞬变。换色逻辑独立成 `applyReaderThemeColors()` 以便在中间帧调用。
- **R35 目录增强（补完）**：每章百分比与当前章高亮此前已实现；本轮补上「打开目录自动滚到当前章」（位置要等布局完成，放在 `scroll.post` 里）。
- **R36 设置页高级项折叠**：接口路径、鉴权 Header/前缀、局域网 HTTP 收进「高级（接口路径与鉴权）」可展开区，默认收起；展开状态只在内存里。
- **R38 统计页空数据说明**：一条阅读记录都没有时不再画全空图表，改为空状态卡 + 「去书架」按钮。
- **R47 导入进度**：`observeImportBatch` 在批次未完成时把「已完成/总数」暴露给 Compose 壳，底部显示进度条与「正在导入 N / M 项…」。
- **R32 空状态线性图标（补完）**：`Glass.emptyState` 增加可选 `icon` 参数；书架、笔记页、统计页的调用点改传线性图标名，emoji 只作兜底。
- **R49/R50 全局扫尾**：对话框标题与按钮里当装饰用的 emoji（`✨`/`✍️`/`📁`/`📂`/`📖`）全部去掉；笔记页那个大号 `✨` 按钮换成 `IconView` 线性图标；流式状态条去掉 `✨` 前缀；聊天气泡的 `🙋`/`🤖` 换成「你：」「书：」；思考状态去掉 `💭`。功能性的 `✓` 勾选标记保留。
- **顺带修掉一个真实的深色主题缺陷**：书籍详情对话框正文写死 `Color.parseColor("#333333")`，深色主题下几乎读不出来，已改用 `pal.textP`。

其余轮次（R1–R32、R34、R37、R39–R46、R48）已在前述提交中落地，本轮复核确认代码中符号与调用点均在。

此前记录的「未完成」中有若干实际已完成，本轮已用 grep 逐项纠正：R13（笔记按书聚合）、R16（笔记中枢「标记」区）、R17（书架/工作台统一「AI 设置」入口）、R19（AI 结果用量回执）、R28（成果卡「原书在回收站」+ 恢复）、R31（窄屏底栏格数与溢出）、R40（笔记排序）、R41（「重复上次」）、R42（宽屏侧栏 AI 问答）、R43（「最长的一天」替代无法还原的时段分布）、R44（书架显示「第 N 章」）、R46（导入失败原因聚合）。

核实依据（抽查代码符号与调用点，而非只看提交记录）：
- R1/R2 AI 菜单已是分组底部弹层，每项带图标 + 一行说明 + `aiSendEstimate()` 的发送量估算；「人物速查」已并入「前情提要」（`ReaderView.kt:2017-2030`、`:2024`）。
- R12 书内搜索已有「第 i/N 处」计数与上一处/下一处循环跳转（`ReaderView.kt:3746-3771`），此前记录的「未做」已过时。
- R14/R15 书签与高亮已落地：`BookmarkEntity`（3 个文件引用），高亮复用 `notes.anchor`/`status`，配 `MIGRATION_8_9`。
- R21 进度诚实化由 `ProgressModel`（2 个文件）统一进度条/顶栏/落库值三者。
- R23、R26 抽出 `ReadSession`（3 个文件）与 `DayLabels`（2 个文件），各自带单元测试。
- R25 笔记已可原地编辑并落库，同步对应 AI 成果并标记「已手动编辑」（`NotesView.kt:345-415`）。
- R29 恢复前先经 `BackupService.inspect` 读清单、由用户确认覆盖范围（`MainActivity.kt:87-101`）。
- R37/F1 `KeyPayloadState`（2 个文件）：Key 状态区分「未填/已保存/需重填/来源不符/无法解密」，不再谎报「已安全保存」。
- F2 `hasExtractableText` / `formatChars`（各 2 个文件）：图片型文档不再把「图片集：共 N 页」当正文发送，短文不再显示「0k 字符」。

部分完成：
- R35：目录已有当前章 ◀ 标记，**未做**每章百分比。
- R49/R50：AI 菜单与段落菜单已全部换成线性图标，**其余页面**的 emoji 图标与零散字号仍未扫完。

尚未实现（已用 grep 核实代码中不存在）：R7 R13 R16 R17 R19 R28 R31 R32 R33 R36 R38 R39 R40 R41 R42 R43 R44 R46 R47。
其中 R13（笔记按书聚合）、R16（笔记中枢的书签/高亮分区）、R38（图表空数据说明）、R43（专注时段）确认缺失。

---

## BLOCKER

### R1 [BLOCKER] 用分组说明式底部弹层替换 7 行纯文字 AI 菜单
- 现状: `ReaderView.kt:1487-1505` `showAiMenu()` 用 `AlertDialog.setItems()` 列出 7 条纯文字：
  `💬 和书聊聊 / ✨ 生成理解包 / 前段摘要 / 节选问答 / 出题自测 / 前情提要 / 人物速查`
  （数组在 `ReaderView.kt:1489`）。没有任何图标、说明、费用或数据流向提示；「前段摘要」和
  「节选问答」从字面看不出区别，「出题自测」看不出是付费调用。用户截图抱怨的就是这一屏。
  项目里已有 `BottomSheet.kt` 通用底部弹层（图标+标签+按压反馈+蒙层），但只支持单层 `item()`
  （`BottomSheet.kt:31-34`），没有分组和副标题。
- 改法: 扩展 `BottomSheet.kt` 支持 `section(title, subtitle)` 与
  `item(icon, label, description, onClick)`（新增 `description` 一行小字，用 `pal.textT`）；
  `ReaderView.showAiMenu()` 改为三组底部弹层：
  1) 「本地·不联网」：书内搜索(`search`)、目录(`list`)、收藏这段(`book`→见 R14)、
     我划的重点(`note`→见 R15)；
  2) 「理解与梳理（会发送所选范围到 <服务商>）」：理解包(`book`)、摘要(`note`)、
     前情提要(`chevron`)、段落解释(`bulb`)；
  3) 「互动」：和书聊聊(`search`)、节选问答(`search`)、出题自测(`check`)。
  每个 AI 项副标题写明「范围 = 全文抽样/当前章/所选页 · 发往 <host>」。图标用 `Icons.kt`
  已有名字，不再用 emoji。`openAiWorkbench()`(1509) 保持为唯一入口，不动调用点。
- 验收: 点阅读器「AI」→ 出现带分组标题、每项有图标+一行说明的底部弹层；点击「理解包」
  仍走原 `studyPackAction()`；无任何 emoji 字符出现在菜单里（`grep -n "💬\|✨" ReaderView.kt`
  在菜单区域应为空）。

### R2 [BLOCKER] 删除/合并「前段摘要」「人物速查」，消除重复动作
- 现状: `aiSummary`(`ReaderView.kt:2553-2599`) 与理解包 `generateTextStudyPack`(`1562`)
  的 act 高度重叠——都是「读全文抽样 → 输出摘要+要点」，只是提示词不同；`castAction`
  (`1788-1806`) 的「人物速查」与 `recapAction`(`1721-1765`) 的「前情提要」都从当前章/前文
  提取人物与事件，提示词里甚至明确写了「若为非小说类文档，则提取核心概念代替人物」
  (`1803`)，已经和 recap 撞车。菜单因此从 5 个真实需求膨胀成 7 行。
- 改法: 删除 `showAiMenu` 中的「前段摘要」「人物速查」两个入口（R1 的新弹层里不再列），
  保留 `aiSummary`/`castAction` 实现供「前情提要」结果页的二级动作复用；在 `recapAction`
  的提示词里合并「人物与动机」段落（当前提示词 `1759-1763` 已含「出场人物及其动机」，
  只需补一句「若为非虚构文档则列出核心概念」）。同时把 `NoteKindLabels`(`GlassUi.kt:240-260`)
  里变为孤儿的 `"cast"` 保留（老笔记仍要能显示标签），不删键。
- 验收: AI 菜单项从 7 条降到 5 条；对同一本书触发「前情提要」，输出里含人物/概念段；
  老笔记里 kind=cast 的条目在笔记列表仍显示「人物」标签。

### R3 [BLOCKER] 段落长按菜单改成底部弹层并补齐「划重点」入口
- 现状: `explainBlock`(`ReaderView.kt:2767-2817`) 是纯 `TextView` 行的 AlertDialog，
  5 行文字里 4 行是付费 AI（`2797-2809`），且**没有任何本地「划重点/高亮」动作**，
  只有「收藏金句」一条本地动作(`2794`)。行高靠 `setPadding` 撑，无图标、无分隔，
  和 R1 的菜单是两种视觉语言。
- 改法: `explainBlock` 改用 R1 扩展后的 `BottomSheet`：分组「本地」= 收藏金句 / 划重点（R15）
  / 复制这段；分组「AI 操作（会发送到 <host>）」= 解释 / 翻译 / 大白话 / 续写。
  段落预览放在弹层标题区（截 60 字 + 省略号），让用户确认点的是哪一段。
  同时把长按回调从 `makeBlockView`(`886-888`) 传入的 `index` 一并用于高亮锚点。
- 验收: 长按正文段落 → 底部弹层，第一组是本地动作且含「划重点」；点「解释含义」仍走
  `runBlockAi` 并把结果落笔记（kind=explain）。

### R4 [BLOCKER] 合并阅读器的两条竞争导航栏
- 现状: 阅读器同时存在两条横向栏：
  (a) 文档二层工作台 `documentTabs`(`ReaderView.kt:545-577`)= 阅读/目录/AI/笔记；
  (b) 底部悬浮工具坞 `bottom`(`ReaderView.kt:658-703`)= 搜索/目录/主题/亮度/A−/A+。
  两者都横跨整宽、视觉权重相同，且**「目录」重复出现**（`572` 与 `687`），
  用户无法判断该点哪一条；`showDocumentTabs=false` 时(a)整条被隐藏(`568`)，
  目录入口随之消失（只能从(b)进），行为不一致。顶栏还塞了一个等宽占位 View
  (`ReaderView.kt:539`) 只为了居中书名，没有任何功能。
- 改法: 删除 `documentTabs` 整块；把「AI」「笔记」并入底部工具坞，重排为
  `搜索 · 目录 · AI · 笔记 · 主题 · 字号(A−/A+)`，其中字号两格合并为一个可长按连发的
  「Aa」格（点击弹字号选择、长按连发，复用 `setupRepeatable`(`710`)）；
  亮度移入「阅读设置」长按/二级入口（和主题同层）。`showDocumentTabs` 参数保留但改为
  控制「是否显示 AI/笔记格」，避免 `YeshuApp.kt:346` 调用点编译失败。
- 验收: 阅读器只剩一条横栏；目录只在同一位置出现一次；窄屏(宽<840dp，`YeshuApp.kt:336`)
  与宽屏两种布局下 AI/笔记入口都存在。

### R5 [BLOCKER] 顶栏副标题在长书里长期空置/被系统栏压住
- 现状: 顶栏副标题 `curHeadTv`(`ReaderView.kt:524-531`) 初始 text 是作者名或「继续阅读」，
  只有 `tocHeads` 非空时才在滚动里被替换为「章名 · 百分比」(`622-638`)；对没有识别出
  章节标题的 TXT(`Parsers.kt:262` 依赖 `CHAPTER_RE`)、PDF(`639`)、图片集，滚动时它展示的
  是格式串而**没有真实进度百分比**（图片集分支完全不更新 `curHeadTv`）。
  同时顶栏 `top` 自带 padding(`499`)，而 insets 只作用于最外层 `col`(`706`)，导致刘海机型
  上顶栏紧贴状态栏，视觉上和 R2 的弹层标题重叠。
- 改法: 抽 `updateHeaderPosition()` 覆盖三种模式：文本块流用 `currentBlockIndex()`+章名；
  PDF 用 `currentPdfPageIndex()`+页码；图片集用可见页索引。无章节结构时只显示
  「第 N 块 / 共 M 块 · x%」的真实位置（不要显示无意义的格式串）。
  顶栏额外 `topMargin` 由 `applySystemBarInsets` 的 statusBars inset 决定。
- 验收: 打开没有章节标题的 TXT 并滚动，副标题显示真实块序号与百分比；PDF 显示页码。

### R6 [BLOCKER] AI 对话框的 5 个动作 chip 文案过长导致换行挤掉正文
- 现状: `showActions`(`ReaderView.kt:2015-2055`) 在窄屏把「复制 / 分享 / 保存为笔记 /
  重新生成 / 换模型重试 / 仍要保存」全部塞进一行 `horizontalChipRow`(`2374`)；
  每个 chip 文案带后缀（如「重新生成」+「换模型重试」），在 360dp 宽机型上必须横向滚动，
  用户看不到右侧的「停止」。`aiChip`(`2385`) 也没有图标。
- 改法: 按语义拆两行：第一行「停止 / 重新生成」（主操作，图标 `close`/`sort`），
  第二行「复制 / 分享 / 保存为笔记 / 仍要保存」（次要操作，图标 `note`/`share`/`check`）；
  流式进行中隐藏第二行，结束后显示，避免误触保存半截内容。`fillChipRow`(`2402`) 增加
  「是否换行」参数。所有 chip 加 `contentDescription` 以支持 TalkBack。
- 验收: 360dp 窄屏流式输出时「停止」无需横向滚动即可见；结束后出现第二行且「保存为笔记」
  一键可用。

### R9 [BLOCKER] 收敛成单一强调色系统
- 现状: 三套互不相同的「蓝/紫」强调色同时在用：
  Compose 侧 `ElectricBlue #5B5FF5` / `LuminousCyan #22D3EE` / `ActiveViolet #A855F7`
  (`ui/YeshuTheme.kt:10-12`)；legacy 侧 `T.accent #0A84FF`(`Tokens.kt:18`)；
  阅读器又自定义 `AI_HEAD_ACCENT #5B5FF5`(`ReaderView.kt:66`)、工具格 `#B7C0FF`/`#AAB5FF`
  (`401`)、`#F4F5FA`/`#AEB4C2`(`401,425`)、顶栏 `#F7F8FC`/`#AEB4C2`(`518,527`)、
  文档 tab `#B8BECC`(`557`)；对话框按钮色 `#8FB6FF`(`GlassUi.kt:115`)。
  同一屏里出现 4 种蓝紫，视觉上像三个不同 App 拼起来的——这就是「鸡肋」的观感来源之一。
- 改法: 建立单一来源：新增 `Accent.kt`（或扩充 `Tokens.kt`）定义
  `Accent.primary = #5B5FF5`、`Accent.primarySoft = #B7C0FF`、`Accent.onAccentSoft #AAB5FF`
  与 `AccentText.primary/secondary/tertiary`（浅底/深底两套），
  `ui/YeshuTheme.kt` 的 `ElectricBlue` 改为引用它；`ReaderView` 的
  `AI_HEAD_ACCENT`/`401`/`425`/`518`/`527`/`557` 常量改为引用 `Accent`；
  `GlassUi.kt:115` 的 `#8FB6FF` 改为 `Accent.primarySoft`；`Tokens.kt:18` 的 `T.accent`
  改为 `Accent.primary`（保留 `T.accent` 名字避免改所有调用点）。
- 验收: 全局 `grep -rn "0A84FF\|8FB6FF\|B7C0FF\|AAB5FF" app/src/main/java` 只剩 `Accent.kt`
  一处定义；同一屏幕内不再出现两种蓝。

### R14 [BLOCKER] 新增真正的书签功能（新建/列表/跳转/删除，持久化）
- 现状: 全项目没有任何书签实现：`grep -rn "书签\|bookmark" app/src/main` 只匹配到注释，
  `YeshuDatabase.kt` 的实体只有 books/notes/folders/settings/read_log/ai_artifacts(`257-268`)，
  DAO 无书签查询，阅读器底部工具坞(`ReaderView.kt:686-700`)也没有入口。用户只能靠
  「收藏金句」(`saveQuote`,`2860`)间接留下文字，且那要求选中整段文字，无法标记「我读到第 3 页这里」。
- 改法:
  1) 新增实体 `BookmarkEntity(table, bookId, position, label, createdAt)` 与 DAO
     `addBookmark/listBookmarks/deleteBookmark`（`data/YeshuDatabase.kt`）；
  2) DB 版本 8→9，新增 `MIGRATION_8_9` 用 `CREATE TABLE`（**不加破坏性迁移**），
     并加入 `addMigrations(...)`(`281-289`)；
  3) 在 `Db.kt` 加 `addBookmark/listBookmarks/deleteBookmark` 便捷方法；
  4) 阅读器底部工具坞（R4 重排后）新增「书签」格：点击 = 在当前位置新建书签
     （文本流用 `anchorForBlockIndex(currentBlockIndex())`(`2261`)，PDF 用
     `currentPdfPageIndex()`(`1849`)，图片集用可见页），再次点击打开书签列表并支持跳转/删除；
  5) 跳转复用已有 `jumpToCitation`/`blockIndexForAnchor`(`2184`,`2217`) 的定位逻辑，
     避免再写一套映射。
- 验收: 加书签 → 退出重进仍在（`listBookmarks` 返回）；从书签列表点击能回到原位置
  （文本块流滚动位置与原块一致，PDF 滚到对应页）；新装/升级（v8→v9）后 `assembleDebug`
  与 `YeshuMigrationTest` 均通过。

### R15 [BLOCKER] 新增文本高亮功能（选色新建/列表/跳转/删除，持久化）
- 现状: 没有高亮功能。`NoteEntity` 已有 `kind`/`anchor`/`status`
  (`data/YeshuDatabase.kt:40-50`、`MIGRATION_7_8` `311-316`) 但没有任何 `highlight` kind；
  `NoteKindLabels.all`(`GlassUi.kt:241-260`) 无对应键；`makeBlockView`(`866-893`) 的
  TextView 从未设置背景 span；`ReaderView` 里唯一的 `highlightColor` 用法是
  「搜索命中一闪而过」(3104) 与 AI 结果里清空高亮(1972,2164)。长按菜单(R3)里没有入口。
- 改法:
  1) 复用 `NoteEntity`：新增 kind `"highlight"`，`anchor` 存位置（同 R14 的锚点体系），
     `status` 复用来存颜色 id（`"amber"/"green"/"blue"/"rose"`）；这样**不需要新表**，
     只需在 `NoteKindLabels.all` 加 `"highlight" to "重点"`；
  2) `ReaderView` 在 `appendChunk`(`1048`) 渲染块后调用
     `applyHighlightsToBlock(index, tv)`，从内存缓存的 `Map<blockIndex, colorId>` 给
     `SpannableString` 加 `BackgroundColorSpan`（浅色主题用低饱和底、深色主题用高饱和低透明底，
     保证正文可读）；
  3) 长按菜单(R3)新增「划重点」二级选色（4 色 + 「清除本段高亮」）；
  4) 新增「我划的重点」列表入口（AI 菜单或笔记页 R16），支持跳转/删除。
- 验收: 段落划橙色重点后该段有背景色；重进书仍在；在「我划的重点」里删除后背景色消失；
  深色主题下高亮文字对比度仍可读（肉眼 + 截图确认）。

---

## HIGH

### R7 [HIGH] AI 弹层缺「本次会发多少、大概花多少」的量化说明
- 现状: 只有 `scopeLine` 常驻标题下方(`ReaderView.kt:1888-1895`)，格式是
  「范围：全文抽样，已发送 12k/340k 字符」(`2491-2496`)；用户仍不知道会消耗多少 token、
  发往哪个域名（`providerHost()` `2470` 只用在确认框里）。
- 改法: 在延迟弹层的「AI 操作」分组标题下加一行统一说明
  「发往 `<host>` · 本次约 N 字符 ≈ M token（估算）· 费用由服务商收取」，
  token 用 `字符数/2` 粗估并在文案里标明「估算」；`runAiStream` 的 `scopeTv` 保持现状。
- 验收: 菜单里能看到目标域名与量级估算；`grep` 确认没有把 Key 写进任何文案。

### R8 [HIGH] 阅读器没有「行距/页边距」设置，只有字号
- 现状: 底部工具坞只有 A−/A+ 改字号(`ReaderView.kt:695-700`)，行距写死 1.38
  (`884`，注释说「成熟阅读器共识」)，左右 padding 写死 20dp(`876,885`)，
  设置页(`YeshuApp.kt:1928-1947`)只有音量键翻页一项。长时间阅读的用户最常调的就是行距与边距。
- 改法: 在 R4 重排后的「Aa」格里加二级面板：字号(−/+) / 行距(紧凑 1.2 / 舒适 1.38 / 宽松 1.6) /
  页边距(窄 12 / 中 20 / 宽 28)。三项都写 `settings` 表（`reader_line_spacing`、`reader_margin_dp`），
  `setup`(`439`) 读取，`applyFontSp`(`897`) 改名为 `applyTypography()` 同时应用三者；
  重排后仍按 R21 的锚点还原滚动位置。
- 验收: 改行距立即生效且不丢当前位置；重进书设置保持。

### R10 [HIGH] 阅读器没有「跟随系统深色」选项
- 现状: 阅读器主题 `ReaderTheme`(`ReaderView.kt:86-90`) 只有 light/paper/dark 三选，
  存 `reader_theme`(`935`)，与全局外观设置完全独立；用户在设置页选了「跟随系统深色」
  (`YeshuApp.kt:1896`)，进阅读器却仍是纸张米色——两套主题互不知情。
- 改法: `ReaderTheme` 增加 `SYSTEM("system","跟随系统","随系统外观自动切换")`，
  `loadReaderTheme`(`312`) 在 `reader_theme == "system"` 时按
  `resources.configuration.uiMode` 解析为 light/dark；主题选择面板 `showThemePicker`(`956`)
  增加对应一行。`applyReaderTheme`(`931`) 仍只写 `reader_theme`，不碰全局键（保持现有注释的约束）。
- 验收: 设置页切深色 → 阅读器主题为「跟随系统」时正文变深色底；切回浅色恢复。

### R11 [HIGH] 阅读进度条只有 2dp 且在深色底上几乎不可见
- 现状: `Glass.progressTrack(act)`(`GlassUi.kt:165-183`) 轨道色 `argb(45,255,255,255)`、
  填充 `argb(235,255,255,255)`——在浅色/纸张主题下**白色填充画在米色背景上**，
  对比度极低；高度 2dp(`ReaderView.kt:606`) 也低于可感知阈值。
- 改法: `progressTrack` 增加 `accent` 参数，填充用 `Accent.primary`（R9），
  轨道用文字色 12% 透明；高度提到 3dp 并在填充端加 2dp 圆头。
  同时 `updProg()`(`609`) 改为按真实位置（PDF 用页索引、文本流用 `currentBlockIndex`）
  计算，与 R21 的进度口径统一。
- 验收: 三种主题下进度条都清晰可见；进度条比例与顶栏百分比一致。

### R12 [HIGH] 书内搜索没有「上一处/下一处」与命中计数
- 现状: `searchInBook`(`ReaderView.kt:2880-2950` 附近) 只切换到第一个命中并高亮，
  没有结果计数、没有循环跳转，用户想找第 3 个匹配只能反复输入。且对 PDF 直接提示
  「暂不支持文字搜索」(`2896`)。
- 改法: 搜索框改为带计数的浮层：「第 i/N 处」+ 上/下箭头 + 关闭；
  命中索引数组缓存在 `ReaderView`，跳转复用 `blockIndexForAnchor` 定位；
  PDF 分支提示改为引导到 AI 问答（已有文案），并给出「用 AI 定位」按钮直达 R1 弹层的问答。
- 验收: 搜索「第」能显示「第 1/37 处」并循环跳转；PDF 里点「用 AI 定位」打开问答。

### R13 [HIGH] 笔记页筛选 chips 与列表无「按书聚合」视图
- 现状: `NotesView.buildChips()`(`NotesView.kt:104-149`) 按 kind 平铺 chips，
  列表是全局按时间倒序；同一本书的摘记、摘要、问答混在一起，
  用户看完一本书想复习时无法按书收拢。笔记中枢(`YeshuApp.kt:1059`)同样是全局时间序。
- 改法: 笔记页增加分段控件「按时间 / 按书」；按书时列表按 `bookId` 分组，
  组头显示书名+条数，组长按可折叠；复用已有 `NoteRow`(`Models.kt`) 与
  `NoteKindLabels` 标签，不改 DAO（在内存分组即可，笔记量有限）。
- 验收: 切换到「按书」后能看到书名的分组头与每组条数；点击组内条目的行为与按时间一致。

### R16 [HIGH] 笔记中枢缺「书签/高亮」区，与新功能脱节
- 现状: 笔记中枢(`YeshuApp.kt:955-1164`)只列出 notes 与 AI 成果，
  新增的书签(R14)/高亮(R15)若只存在阅读器里，用户就永远看不到全部标记。
- 改法: `NotesHubData` 增加 `bookmarks: List<BookmarkEntity>` 与
  `highlights: List<Db.NoteDetail>`，在「AI 成果」上方新增「标记」区
  （书签显示位置 label、高亮显示颜色点+摘录），点击跳转到对应书与位置，
  删除走已有的 snackbar 撤销模式(`1002-1023`)。
- 验收: 加书签/划重点后进入笔记页能看到并跳转；删除后可在提示里撤销。

### R17 [HIGH] 书架「更多」菜单与工作台入口文案/行为不一致
- 现状: 工作台有「AI 设置」卡片(`YeshuApp.kt:534-538`)，书架侧同样功能藏在「更多」里
  （`ShelfView.kt:1945` 附近），工作台注释(`533`)自己都承认「书架里叫 AI 设置的入口藏在
  更多里，工作台给出同名入口」——说明两处命名/层级不一致，是靠文案「兜」的。
- 改法: 统一为「AI 设置」并从书架「更多」提到一级（与导入/搜索同层）；
  `ShelfView` 的「更多」弹层改用 R1 的 `BottomSheet` 分组样式，
  分组「书架管理」与「AI 与数据」，条目带 `description`。
- 验收: 书架与工作台两处入口名称与落点完全相同；从任一处进入都是设置页 AI 分区。

### R18 [HIGH] AI 结果的引用锚点 chip 不可点（无定位）
- 现状: `AnchorChip`(`YeshuApp.kt:1359-1368`) 注释写明「页枢暂不支持按锚点精确定位」，
  `onClick` 只在有 bookId 时打开书，**不跳到锚点位置**，用户点 `[CHAPTER:3]` 只会回到书首。
  阅读器侧 `jumpToCitation`(`2184`) 已能精确定位，但笔记中枢没接上。
- 改法: 给 `Destination.Reader` 增加可选 `anchor: String = ""`（`MainActivity.kt:41`），
  进入阅读器后由 `ReaderView` 在 `setup` 末尾消费一次 `pendingAnchor` 并调用
  已有的 `jumpToCitation`；`AnchorChip` 的 onClick 改为 `openReader(bookId, anchor)`。
  保留 `Destination.Reader(bookId)` 单参重载以避免改动所有调用点。
- 验收: 从笔记/成果点 `[PARAGRAPH:12]` 打开书并停在对应块，而不是文首。

### R19 [HIGH] AI 结果没有「用量/模型/耗时」回执
- 现状: `AiClient.ProbeResult`(`AiClient.kt:93-104`) 有 token 与耗时，但只用于设置页测试；
  流式结果对话框(`runAiStream`,`1865`)只在标题显示模型名(`1886`)，不显示本次 token 与耗时，
  用户无法判断一次理解包花了多少。
- 改法: `runAiStream` 结束态在 `bannerTv` 位置追加一行「模型 <m> · <n>s · 输入/输出 tokens」
  （`AiClient.chat` 已解析 usage，见 `TokenUsage` `86-90`；把返回值/回调补上 usage 即可）。
  不显示任何费用金额（服务商定价不由本地臆测）。
- 验收: 理解包完成后对话框显示耗时与 token；设置页行为不变。

### R20 [HIGH] 段落长按无「复制」与「搜索这段」本地动作
- 现状: `explainBlock`(`2767`) 的本地分组只有收藏金句(`2794`)；用户想把一段文字复制出去
  只能用 AI 结果里的「复制」(`2419`)。阅读器也没有「用这段做搜索词」的快捷。
- 改法: R3 的本地分组补「复制这段」（`ClipboardManager`，复用 `copyAiText` 的裁剪逻辑）
  与「搜索这段」（把段落前 20 字填进 R12 的搜索框并执行）。两者都不联网。
- 验收: 长按段落 → 复制后粘贴板内容正确；搜索这段直接出命中计数。

### R21 [HIGH] 阅读进度模型谎报：进度条、顶栏百分比、落库值三者不一致
- 现状: 三处口径不同：
  (a) `updProg()`(`ReaderView.kt:609-614`) 用 `child.height`（**已渲染前缀**的高度）算比例；
  (b) 顶栏百分比(`622-638`) 用同一个 `child.height` 比例，但章名用真实块位置；
  (c) `saveProgress()`(`3144-3166`) 在未加载完时乘 `renderedUpTo/total` 并把上限压到 0.98。
  于是「进度条 92% / 顶栏 92% / 落库 61%」同时存在；`YeshuApp.kt:145 isReadingProgress`
  又镜像 `Db.statusFor`(`Db.kt:420-424`) 的 0.005/0.99 边界，
  导致工作台 HeroCard 的「已读 x%」(`YeshuApp.kt:725`)与阅读器内部数字对不上。
  另外 `ensureRenderedUpTo`(`814`) 恢复时按 `p*(total-1)` 取块(`777`)，与 `saveProgress`
  的反向换算不是同一映射，往返会漂移。
- 改法: 定义唯一进度函数：文本流 `progress = currentBlockIndex()/max(1,total-1)`
  （已渲染前缀用 `renderedUpTo` 上限保护），PDF `= currentPdfPageIndex()/max(1,pageCount-1)`，
  图片集同理按可见页。新增 `private fun currentProgress(): Float` 并让
  `updProg`、顶栏、`saveProgress` **全部调用它**；恢复时用同一个函数的逆函数定位
  （按块索引直接 `ensureRenderedUpTo`+`scrollTo`）。把该函数抽成可单测的纯函数
  `ProgressModel.progressOf(renderedUpTo,total,scrollFraction,fullyLoaded)` 放进新文件，
  便于 JVM 单测覆盖边界。
- 验收: 新增单测覆盖 `renderedUpTo<total`、`total==0`、到末尾三种情况；
  实机上进度条与顶栏百分比始终相同；退出重进位置不漂移（±1 块）。

### R22 [HIGH] 大量内容不足一屏的书被误判「读完」
- 现状: `saveProgress`(`3162-3163`)：`range <= extent` 且 `fullyLoaded` 时直接写 `1f`。
  一篇 500 字的笔记类文档一打开就被记为 100%，工作台立刻显示「读完」，
  用户还没看。`statusFor`(`Db.kt:420-424`) 据此写 `done`。
- 改法: 把「不足一屏」的判定改为：仅在用户**实际滚动过**或**停留超过阈值**（如已计入阅读时长
  >30s）时才置 1f，否则保持 0f；用一个 `hasInteracted` 标志由 `onScrollChanged`(`617`) 置位。
- 验收: 打开一篇极短文档立即退出 → 进度仍为 0；滚动一下再退出 → 100%。

### R23 [HIGH] 阅读时长在快速切换页面时重复计入
- 现状: `onAttachedToWindow`(`3201-3204`) 置 `readSessionStart`，`flushReadTime`(`3207-3216`)
  落库并清零；`pauseReadSession`(`3219`) 与 `onDetachedFromWindow`(`3240-3241`) 都会调用，
  但 `MainActivity.onStop`(`MainActivity.kt:241-244`) 也会调用 `pauseReadSession`。
  若 detach 发生在 stop 之前，`readSessionStart` 已清零，第二次调用无副作用（正确）；
  然而宽屏布局下 `ReaderViewHost` 的 `update`(`YeshuApp.kt:402`) 会在每次重组时
  `registerLegacy(it)`，若触发重新 attach 会重新计时，造成同一分钟被记两次。
- 改法: 用 `attachedAtMs` + `accumulatedMs` 的显式累加器，`resume` 幂等（已计时则不重置），
  并在 `flushReadTime` 落库后校验 `delta` 不超过本次 attach 的真实墙钟时长。
- 验收: 新增单测覆盖「resume 两次只计一段」「pause 两次只落一次」。

### R24 [HIGH] AI 结果不可编辑，只能看/复制/存成笔记
- 现状: `runAiStream`(`ReaderView.kt:1865-2158`) 提供 停止/重试/复制/分享/保存为笔记
  (`2015-2055`)，**没有编辑**；`NotesView.showFull`(`NotesView.kt:349`) 只读展示；
  笔记中枢 `DetailSheet`(`YeshuApp.kt:1372-1458`) 也只有一个滚动 `Text`。
  用户想把摘要删掉一段废话、改个错别字，只能整段重生成。
- 改法:
  1) `runAiStream` 完成态增加「编辑」chip → 把正文 `TextView` 换成 `EditText`
     （保留同一 `ScrollView`，`inputType = textMultiLine`），底部按钮变为
     「保存修改 / 取消」，保存后写回 `ai_artifacts.content` 与对应 note 的 content
     （新增 `Db.updateNoteContent(id, content)` 与 `Db.updateArtifactContent`）；
  2) `DetailSheet` 增加「编辑」按钮，复用同一编辑器组件；
  3) 编辑后的内容 `status` 置 `"edited"`，在详情 meta 里显示「已手动编辑」，
     避免用户以为那是模型原文。
- 验收: 生成摘要 → 编辑 → 保存 → 退出重进笔记仍是修改后的文本，
  且 meta 显示「已手动编辑」；`ai_artifacts` 与 note 内容一致。

### R25 [HIGH] 笔记没有编辑能力，只能删除重建
- 现状: `NotesView` 的条目只有「删除」(`NotesView.kt:225`)与撤销(`258`)，
  没有编辑；`Db` 也没有 `updateNoteContent`。用户改一个错字要删了重加，锚点还丢了。
- 改法: 与 R24 共用编辑器；`NotesView.showFull`(`349`) 改为可编辑详情页；
  `Db` 补 `updateNote(id, content)`（DAO 新增 `UPDATE notes SET content=:content WHERE id=:id`）。
- 验收: 编辑一条笔记保存后列表内容更新，锚点与 kind 不变。

### R26 [HIGH] 统计页「近 7 天」在跨月/跨年时标签错位
- 现状: `Db.dailyReadMs`(`Db.kt:147-158`) 用 `Calendar` 逐日回退并把结果
  `add(0, ...)` 插到队首，`recentReadLog` 只取 `LIMIT n`(`YeshuDatabase.kt:161-162`)。
  跨月时 `SimpleDateFormat("yyyy-MM-dd")` 键正确，但 `StatsView` 的横轴标签若只取
  「日」两位数字，会出现 31→1 的视觉跳变而不显示月份。
- 改法: `StatsView` 的日标签在跨月时显示 `M/d`，同月时才显示 `d`；
  由 `dailyReadMs` 一并返回月份信息（把 `Pair<String,Long>` 换成小 data class）。
- 验收: 造跨月数据（单测直接构造 read_log）验证标签含月份。

### R27 [HIGH] 「近 7 天总量」可能大于「总阅读时长」
- 现状: `addReadTime`(`Db.kt:131-142`) 已用事务保证 `books` 更新成功才写 `read_log`，
  但 `BackupService` 恢复时 `mergeTotalReadMs`(`YeshuDatabase.kt:146-147`) 只取较大值，
  `read_log` 用 `mergeReadLog`(`167-168`) 同样取较大值，两条合并策略独立，
  恢复后可能出现日累计 > 总量。
- 改法: 恢复流程结束后做一次一致性校正：若 `sum(read_log) > totalAllReadMs()`，
  按比例缩放到总量（或在统计页显示时用 `min(日, 总)` 兜底并注明）。
  优先在 `BackupService` 恢复后加 `Db.reconcileReadTotals()`。
- 验收: 新增单测：构造不一致的 read_log/books，调用校正后 `sum <= total`。

### R28 [HIGH] 删除书后 AI 成果仍留在笔记中枢
- 现状: `purgeBook`(`Db.kt:184-190`) 会删 notes/artifacts，但软删除 `deleteBook`(`181`)
  不删；`listAllArtifacts`(`408`) 的成果在书被软删后 `bookTitle` 为 null，
  卡片显示「已不在书架的书目」且 `sourceBookId` 为 null(`YeshuApp.kt:933`)，
  用户点不进去也不知道该恢复还是删掉——回收站恢复书后才会重新出现。
- 改法: 成果卡片在书已软删时增加「恢复该书」动作（调 `restoreDeletedBook`）与
  「查看回收站」；文案明确「原书在回收站」而不是含糊的「已不在书架」。
- 验收: 软删一本书 → 笔记中枢的成果卡显示「原书在回收站」并提供恢复；
  恢复后卡片恢复可跳转。

### R29 [HIGH] 备份恢复不提示覆盖范围与结果数量
- 现状: `MainActivity` 的导入回调(`MainActivity.kt:86-93`)只 Toast 一行
  `BackupService.restore` 返回的字符串；`BackupService`(576 行)的恢复无预览，
  用户在选择 zip 前不知道会覆盖多少书/笔记。
- 改法: `BackupService` 增加 `inspect(uri)` 只读解析清单（书数、笔记数、成果数、导出时间），
  恢复前弹确认框列出这些数字并说明「同名书籍按内容哈希合并，不会删除本地多余条目」。
- 验收: 选 zip 后先看到清单确认框；取消则不写库。

### R30 [HIGH] 阅读器没有「本页/本块分享」与「分享为图片」
- 现状: 分享只在 AI 结果里(`shareAiText`,`2427`)，正文段落无法分享。
- 改法: R3 的本地分组加「分享这段」；用一个 `TextView` 离屏绘制成 Bitmap
  （带上书名、章名、正文，浅色底 + 品牌色），通过 `FileProvider` 走 `ACTION_SEND`。
  需要新增 `res/xml/file_paths.xml` 与 `AndroidManifest` 的 provider 声明（不涉及密钥）。
- 验收: 长按段落 → 分享 → 收到图片，图上有书名与章名。

---

## MEDIUM

### R31 [MEDIUM] 阅读器底栏图标格 52dp 高度够但点击热区在窄屏被挤到 <40dp
- 现状: `toolCell` 用 `0dp + weight=1` 平分(`ReaderView.kt:675-678`)，
  R4 合并后格数增加，360dp 屏上每格 width ≈ 40dp，低于 48dp 无障碍最小热区。
- 改法: 窄屏改为「5 格 + 溢出菜单」：搜索/目录/AI/书签/更多，其余进更多弹层；
  用 `resources.configuration.screenWidthDp` 判断。
- 验收: 360dp 下每格 >=48dp 且所有功能仍可达。

### R32 [MEDIUM] 空状态全是 emoji，风格不统一
- 现状: `Glass.emptyState`(`GlassUi.kt:124-162`) 用大号 emoji 作插画；
  `YeshuApp.kt:1074` 用「✎」，`NotesView.kt:72` 用「✨」。与 `Icons.kt` 的线性图标系统冲突。
- 改法: `emptyState` 增加可选 `icon: String?`，传入时用 `IconView` 画 48dp 线性图标；
  各调用点改用对应图标名。
- 验收: 空书架/空笔记页显示线性图标而非 emoji。

### R33 [MEDIUM] 主题切换无过渡动画，闪一下
- 现状: `applyReaderTheme`(`ReaderView.kt:931`) 直接 `setBackgroundColor` 与逐块 setTextColor，
  没有 crossfade；深色↔浅色切换时全屏瞬变。
- 改法: 用 `View.animate().alpha()` 或对根容器做 180ms 的 `TransitionDrawable` 过渡；
  文字色变化延后到动画中段，避免半截混色。
- 验收: 切换主题有平滑过渡（录屏确认）。

### R34 [MEDIUM] 字号调整无「当前值」显示
- 现状: `applyFontSp`(`897`) 只改字号，用户在 A−/A+ 连发时不知道当前是多少 sp。
- 改法: R8 的「Aa」面板里显示「字号 17」并在调整时实时更新；
  在阅读器顶部弹一个 700ms 的轻提示（复用 `toast` 的位置但不用 Toast，避免堆叠）。
- 验收: 调字号时能看到当前 sp 值。

### R35 [MEDIUM] 目录列表没有当前章高亮与进度
- 现状: `listToc`(`ReaderView.kt:2953-2975`) 只是一列章节标题，不标当前位置，
  也没有每章进度。
- 改法: 目录项显示「章名 + 右侧百分比」；当前章加 `Accent.primary` 背景，
  并在打开目录时自动滚到当前章（用 `currentBlockIndex` 找最近 `tocHeads`）。
- 验收: 打开目录停在当前章且高亮；滚到别的章后再开目录高亮跟着变。

### R36 [MEDIUM] 设置页 AI 区块过长，需要折叠
- 现状: `SettingsScreen`(`YeshuApp.kt:1950-2275`) 把所有高级字段
  （chatPath/modelsPath/authHeader/authPrefix/允许 HTTP）都平铺，普通用户要滚很久。
- 改法: 默认只显示「配置名/预设/Base URL/文本模型/视觉模型/Key」，
  其余收进「高级（接口路径与鉴权）」可展开区，展开状态记在内存。
- 验收: 首次进入设置页看不到路径/鉴权字段；展开后可见且值不变。

### R37 [MEDIUM] 设置页 Key 状态文案不区分「未填/已保存/需重填」
- 现状: label 三态(`YeshuApp.kt:2090-2097`)只有「正在读取/已安全保存，留空不修改」，
  无法表达「曾保存但解不开」（见 R39 的附带修复）。
- 改法: 与 R39 的 `KeyState` 联动，四态：未填写 / 已安全保存 / 需要重新填写 Key /
  密钥失效（历史保存无法解密，请重新输入）。
- 验收: 模拟 Keystore 失效（清 prefs 之外的 key alias 不便测，用单测覆盖状态机）后
  界面提示需要重新填写，而不是显示「已安全保存」。

### R38 [MEDIUM] 统计页图表无空数据说明
- 现状: `StatsView`(435 行) 在没有阅读记录时画空图。
- 改法: 无数据时显示 `Glass.emptyState`（图标版）说明「还没有阅读记录」并提供「去书架」。
- 验收: 新装 App 进入统计页看到空状态而非空白图表。

### R39 [MEDIUM] 阅读器「更多」无「打开方式/文件信息」
- 现状: 用户看不出当前文件格式、大小、来源路径。
- 改法: 在 R1 的本地分组加「文件信息」，弹层显示格式/大小/导入时间/文档哈希前 8 位
  （哈希不是秘密，`contentHash` 已存在 `YeshuDatabase.kt:35`）。
- 验收: 弹层显示的信息与书架条目一致。

### R40 [MEDIUM] 书签/高亮没有排序与筛选
- 现状: 新增的 R14/R15 列表最初只有按时间倒序。
- 改法: 列表顶部加「按位置 / 按时间」切换；高亮支持按颜色筛选。
- 验收: 切换排序后顺序变化且保持滚动位置。

### R41 [MEDIUM] AI 弹层每次重建，无法记住上次选择
- 现状: `showAiMenu`(`1487`) 无状态。
- 改法: 记住上次使用的 AI 动作（`settings` 表 `ai_last_action`），在弹层底部提供
  「重复上次：<动作>」快捷行。
- 验收: 用过「理解包」后再次打开弹层顶部出现「重复上次：理解包」。

### R42 [MEDIUM] 聊天页输入框在长对话时被键盘挤压
- 现状: `ChatView`(431 行) 已由 `LegacyHost` 加了 `imePadding`(`YeshuApp.kt:322-325`)，
  但宽屏侧栏布局(`DocumentWorkbenchScreen` 286dp 侧栏)没有聊天入口，
  聊天只在窄屏可用，宽屏用户找不到。
- 改法: 宽屏侧栏的 `DocumentTool` 列表(`YeshuApp.kt:374-377`)增加「AI 问答」，
  跳 `Destination.Chat`。
- 验收: 宽屏下从侧栏能进聊天并正常输入。

### R43 [MEDIUM] 统计页「专注时段」缺失
- 现状: `StatsView` 只有总量与近 7 天，没有一天内的时段分布。
- 改法: 用 `read_log` 无法得到时段（只存按天总量），需要新增 `read_session(hour,ms)`
  或从现有数据放弃该功能；此处选择**不做数据迁移**，改为「阅读最长的一天」等可由现有数据导出的指标。
- 验收: 新指标在统计页可见且数值可由 `read_log` 复算。

### R44 [MEDIUM] 书籍详情缺「最后阅读位置」展示
- 现状: 书架条目显示百分比（`ShelfView`），但看不出「读到第几章/第几页」。
- 改法: R14 的书签与 R21 的进度统一后，在书架条目的副标题加「第 N 章 · x%」
  （需 `ReaderView` 把章名写进 `settings` 的 `reader_last_chapter_<bookId>`）。
- 验收: 读到第 5 章退出后书架显示「第 5 章」。

### R45 [MEDIUM] 对话框在深色主题下的按钮文字色不跟随主题
- 现状: `Glass.recolorDialogText`(`GlassUi.kt:109-121`) 把 `Button` 文字硬编码为 `#8FB6FF`，
  与 R9 的强调色不统一，且负向按钮（取消）也是同一色，语义不清。
- 改法: 用 `Accent.primarySoft`，并把 `android.R.id.button2`（取消）改为 `palette.textS`。
- 验收: 深色对话框里「确定/取消」颜色可区分且与强调色一致。

### R46 [MEDIUM] 导入批次的失败原因未在汇总里说明
- 现状: `summarizeImportBatch`(`MainActivity.kt:205-218`) 只报「失败 N 项」，
  不说明为什么失败（格式不支持/损坏/过大）。
- 改法: 收集失败项的 `KEY_ERROR`，按原因聚合显示（最多 2 类），
  例如「失败 2 项（1 项格式不支持，1 项文件损坏）」。
- 验收: 导入一个损坏 docx + 一个正常 pdf，提示能区分原因。

### R47 [MEDIUM] 长文档导入无进度反馈
- 现状: `LibraryImportWorker`(39 行) 只发通知，列表里没有「正在导入」占位；
  `observeImportBatch`(`190`) 在全部结束前不提示。
- 改法: 书架列表用 WorkManager 的 `getWorkInfosByTagLiveData` 显示进行中的批次条
  （进度不确定的转圈 + 数量），结束后自动消失。
- 验收: 导入 5 个文件时书架顶部有进行中条，完成后消失并出现汇总提示。

### R48 [MEDIUM] 无障碍：自绘图标与自绘进度条无描述
- 现状: `IconView` 已设 `IMPORTANT_FOR_ACCESSIBILITY_NO`(`Icons.kt:29-30`) 且依赖外层描述，
  但 `Glass.progressTrack`(`GlassUi.kt:165`) 返回裸 `View`，TalkBack 读作无标签视图；
  `BottomSheet` 的图标圆底(`BottomSheet.kt:91-99`)描述落在整行（正确），但阅读器
  `renderToolCell` 的外层只有 `contentDescription`(`679`)，子 `TextView` 仍会重复播报。
- 改法: 进度条设 `importantForAccessibility = NO`（信息由顶栏百分比承担）；
  `renderToolCell` 的子 TextView 设 NO，仅保留容器描述。
- 验收: 打开 TalkBack，工具坞每格只播报一次。

### R49 [MEDIUM] 硬编码尺寸扫尾（字号/间距）
- 现状: `ReaderView.kt:1890` `11.5f`、`1898` `12.5f`、`423` `9.5f`、`417` `15f`；
  `YeshuApp` 大量 `.sp`/`.dp` 直接写在调用处（如 `239` `10.sp`、`257` `20.sp`）。
- 改法: 在 `Tokens.kt` 增补字号阶梯 `TypeScale`（caption 10 / body 12 / label 15 / title 20）
  与间距阶梯；把上述散落值改为引用。零散的 in-between 值（11.5）取最近档。
- 验收: `grep -n "11\.5f\|12\.5f" ReaderView.kt` 为空；视觉上无明显变化。

### R50 最终扫尾：全局字体/图标/配色一致性
- 现状: emoji 与自绘图标混用（`ReaderView.kt:1489` 💬✨、`2744` ✍️、`2794` ⭐、
  `YeshuApp.kt:529` ＋、`1074` ✎、`597` ◇、`131-136` ⌂▦✎⚙、`NotesView.kt:72` ✨）；
  强调色多套（R9）；字号散落（R49）。
- 改法: 逐文件替换 emoji/unicode 符号为 `Icons.kt` 中的线性图标（不足的名字补画）：
  chat/number/star/pen/sparkle/folder 等；统一引用 `Accent` 与 `TypeScale`；
  统一所有可点击容器为同一 `Glass.pressFx()` 反馈；核对三种阅读主题 + 明/暗全局主题
  的四象限截图无对比度问题。
- 验收: `grep -rn "[💬✨⭐✍️◇▦⌂▤]" app/src/main/java` 为空；
  四种主题组合截图通过人眼检查；`./gradlew assembleDebug testDebugUnitTest` 通过。

---

## 附带修复（不在 50 轮内，但必须做）

### F1 [BUG] `SecureKeyStore` 谎报「已安全保存」，实际请求 401
- 现状: `security/SecureKeyStore.kt:28-34` `readProfileApiKey` 有三条静默 `return ""`：
  profileId/origin 为空、storedOrigin 与 expectedOrigin 不等、解密失败被 `decryptPayload`
  的 `getOrElse { "" }`(`113`)吞掉。`hasProfileApiKey`(`36-37`) 只检查密文串非空，
  **不验证能否用当前 Keystore 密钥解密、也不校验 origin**。`Db.hasAiKey`(`Db.kt:287`) 直接
  转发它，`AiProfileStore.migrateLegacyBoundKey`(`315-331`) 用它决定「已有 Key 就不覆盖」，
  于是设备恢复/换机后 Keystore 密钥失效时，旧密文仍然让流程以为「已有 Key」，
  而设置页 `hasSavedKey`(`YeshuApp.kt:1485,1536`) 走的是能解密的路径却无法区分
  「从未填写」和「填过但失效」，用户看不到任何「需要重新填写」的提示。
- 改法:
  1) 新增 `enum class KeyState { NONE, OK, ORIGIN_MISMATCH, UNDECRYPTABLE }` 与
     `fun profileKeyState(profileId, expectedOrigin): KeyState`：读取密文存在性 →
     校验 origin → 尝试解密 → 分别返回四态。绝不把明文或密文写入日志。
  2) `readProfileApiKey` 改为基于 `profileKeyState`（仅 `OK` 时返回明文），
     并把三条静默返回合并为显式分支。
  3) `hasProfileApiKey(profileId, expectedOrigin)` 增加 origin 参数并只在 `OK` 时为真；
     保留旧单参重载（等价于 origin 未知时按「密文存在即可解密」判定）以免破坏调用点。
  4) `AiProfileStore.migrateLegacyBoundKey` 用新状态判定：`UNDECRYPTABLE` 时视为无有效 Key，
     允许用旧绑定 Key 覆盖迁移；`ORIGIN_MISMATCH` 时不动（不能跨来源复用）。
  5) 设置页四态文案（R37）：`UNDECRYPTABLE` → 「需要重新填写 Key（历史保存已无法解密）」，
     并置 `hasSavedKey=false`，让用户能直接输入新 Key 覆盖。
- 验收: 新增 JVM 单测覆盖 `KeyState` 四态（`SecureKeyStore` 对 Android Keystore 有依赖，
  把「载荷解析+origin 比对+状态映射」抽成不依赖 Keystore 的纯函数
  `KeyPayloadState.classify(storedOrigin, expectedOrigin, payloadPresent, decryptResult)` 做单测，
  Keystore 调用只保留一行委托）；单测断言结果字符串中不含任何 key 片段。

### F2 [BUG] AI 路径会把「图片集：共 N 页」当成正文发送，且 `0k 字符` 谎报
- 现状:
  (a) `docFullText` 只在 `setupBlocks`(`ReaderView.kt:756`) 与 `renderImageArchive`(`1123`)
  被赋值；`setupPdf`(`1213`)、`setupImage`(`1183`) 从不赋值，所以 PDF/图片走的是 `bookFormat`
  分支本身没问题，但 **固定版式 EPUB / CBZ 会走 `renderImageArchive`**，
  把 `docFullText` 设成 `"图片集：共 N 页"`(`1123`)。此时 `aiSummary`(`2584`)、
  `askAction`(`2645`)、`quizAction`(`2684`)、`castAction` 会把这句话当正文发给模型，
  用户看到「已发送 12/12 字符」——即「渲染没问题但 AI 什么都没发」。
  (b) 多处仍用整数除法显示字符数，几百字会显示成 `0k`：
  `castAction` 的 `chapter.length / 1000`(`1798`)、`recapAction` 的 `material.length / 1000`
  (`1748,1750`)、理解包确认框 `docFullText.length / 1000`(`1552-1553`) 与 `1568-1569`、
  `answerQuiz` 的 `textScope(refText.length)`(`2749`) 已修但 `quizAction`(`2671`) 仍拼
  `min(20000, docFullText.length)` 后走旧逻辑。
- 改法:
  1) 新增 `private fun hasExtractableText(): Boolean`：当 `docBlocks` 全是标题/占位
     （图片集、固定版式）或 `docFullText` 长度 < 200 且不含实质段落时返回 false，
     并在所有文本 AI 分支**先判断**，改为弹「本文档是图片版式，没有可提取文字；
     请用『理解这张图片 / PDF 理解包』的视觉通道」并直接跳到视觉动作，
     而不是发一句占位文案。
  2) 把 `0k` 的整数除法统一换成一个 helper `formatChars(n)`（沿用 `textScope` `2491-2496`
     已修好的口径：<1000 显示精确值），替换 `1748/1750/1798/1552-1553/1568-1569/2671`。
- 验收: 新增 JVM 单测覆盖 `formatChars`（0/1/999/1000/12345）；
  打开纯图片 EPUB 点「理解包」→ 提示走视觉通道，不再显示「已发送 12 字符」。

### F3 [BUG] 备份恢复后每日阅读时长可能超过总时长（与 R27 同源，先落地校正函数）
- 现状: 见 R27。`mergeReadLog`(`YeshuDatabase.kt:167-168`) 与 `mergeTotalReadMs`(`146-147`)
  独立取大值。
- 改法: 在 `Db` 增加 `reconcileReadTotals()`（把 `sum(read_log)` 压到
  `totalAllReadMs()` 以内，超出部分按天比例回缩），并在 `BackupService.restore` 成功后调用一次。
- 验收: 单测构造不一致数据，调用后 `sum(read_log) <= totalAllReadMs()`。

### F4 [BUG] 长按菜单在 `cfg == null`（未配置 AI）时直接吞掉本地动作
- 现状: `explainBlock`(`ReaderView.kt:2767-2768`) 第一行就是
  `val cfg = cfgOverride ?: (aiReady() ?: return)`——**未配置 AI 时直接 return**，
  连「收藏金句」这个完全本地的动作都点不出来。`aiReady()`(`1824`) 还会弹「未配置 AI」对话框。
- 改法: 把 `cfg` 改为可空，先构建并显示弹层，本地分组始终可用；
  只有点 AI 项时才 `aiReady() ?: return@item`。
- 验收: 清空 Key 后长按段落仍能收藏/复制/划重点。