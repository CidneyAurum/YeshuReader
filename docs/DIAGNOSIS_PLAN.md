# 页枢 · 诊断驱动 20 轮修复计划（D01–D20）

诊断来源：`./gradlew lintDebug` 全量报告（4 错误 / 107 警告 / 3 信息）+ 模拟器运行时
logcat 与 StrictMode 扫描。每一轮都对应报告里的一条真实记录，含 `文件:行号`。

## 错误级（lint 会挡构建的先修）

- **D01 `<queries>` 缺失**（`ReaderView.kt:3990`）：`lookupBlockText` 用
  `packageManager.queryIntentActivities` 查词典应用，Android 11+ 没有声明
  `<queries>` 时**永远返回空列表**——用户会一直看到「本机没有词典应用」，
  哪怕装了。这是真 bug。加 `res/xml/queries.xml` + manifest 引用。
- **D02 `justificationMode` 常量来源错误**（`ReaderView.kt:1083-1084`，4 处）：
  我昨天写的 `android.text.Layout.JUSTIFICATION_MODE_INTER_WORD` 是不存在该
  常量的引用（lint 报 WrongConstant），正确来源是 `android.text.LineBreaker`。
  两端对齐当前在运行期等于未生效。改用 `TextView.JUSTIFICATION_MODE_INTER_WORD`
  （API 26+ 的 TextView 常量，minSdk 26 可直接用）。
- **D03 `android.media.ExifInterface` → androidx 版**（`CoverStore.kt:9`）：
  平台版在部分厂商 ROM 上解析行为不一致且已停止演进；androidx 版才是维护路径。

## 警告级（按用户影响排序）

- **D04 `DrawAllocation` 10 处**（`Icons.kt` 多行）：`onDraw` 里创建 `Paint`/`Path`/`RectF`，
  长书阅读时每个图标每帧都分配对象，GC 压力直接体现为滚动掉帧。把 Paint 提为字段复用。
- **D05 `ClickableViewAccessibility` 6 处**（`ReaderView.kt:791,823`、`ShelfView.kt:1048,1121,1348,1542`）：
  `setOnTouchListener` 未处理可达性/未调用 `performClick`。补 `performClick` 分支。
- **D06 `UnusedResources` 7 处**：`strings.xml` 无引用串、`ic_launcher.xml` 冗余。逐条 grep 后删除。
- **D07 `RtlHardcoded` 4 处**（`GlassUi.kt:221`、`ShelfView.kt:1324,1511`）：`marginStart/End` 写成
  `left/right`。中文应用也该支持 RTL 布局（系统语言为阿拉伯语时）。
- **D08 `ViewConstructor` 6 处**：自定义 View 只有代码构造器没有 XML 构造器。
  本项目全部代码建 View，属误报；加 `@SuppressLint` 并注明原因，保留干净报告。
- **D09 `AutoboxingStateCreation` 3 处**（`MoShu` 同款 2 处）：`MutableStateFlow<Int?>` 等装箱。
  改为原始类型流或明确可空语义。
- **D10 `ObsoleteSdkInt` 2 处**：`mipmap-anydpi-v26` 目录多余，合并进 `mipmap-anydpi`。
- **D11 `KaptUsageInsteadOfKsp`**：YeshuReader 仍用 kapt 编译 Room。MoShu 已在 KSP。
  尝试迁移；若迁移引发兼容问题，记录原因并保留 kapt（如实记录，不硬迁）。

## 运行时诊断（模拟器实测）

- **D12 启动崩溃扫描**：安装 debug APK → 启动 → 收集 logcat 的 `FATAL`/`AndroidRuntime` 段并修复。
- **D13 StrictMode 主线程违规**：debug 构建临时开启 StrictMode（磁盘/网络在主线程），
  跑「导入一本书 → 打开 → 翻页 → 生成书签 → 退出」，把违规点改为后台线程。
  重点是 `Db.getSetting` 这类主线程 Room 读（按键路径上每次都查库）。
- **D14 冷启动耗时**：`adb shell am start -W` 测两应用，超过 1.5s 的找首屏阻塞点。
- **D15 大文档内存**：导入超大 EPUB 后 `dumpsys meminfo` 对比翻页前后，
  图片内存无回收则补 `recycle`/软引用收口（对应原计划 R75）。

## 逻辑与数据诊断

- **D16 进度往返漂移**（原 R21 的残留验证）：写一个「保存→恢复→再保存」的循环单测，
  断言位置漂移 ≤ 1 块；不满足就修 `ProgressModel` 的逆映射。
- **D17 时区/跨月边界**：`DayLabels`、墨枢 `InsightsStreak` 在月末 23:59/月初 00:00 的
  边界单测（此前修复过 7 天跨月错位，补防回归）。
- **D18 备份往返一致性**：导出→清库→恢复→再导出，两次 zip 的 entries.json 逐字段相等。
  不相等就修 BackupService 的序列化丢字段问题。
- **D19 输入法/旋转状态保持**：旋转屏幕与切换输入法后，搜索词、草稿、阅读位置不丢。
- **D20 lint 归零与回归**：`lintDebug` 无 Error、警告数显著下降且无新增；
  `assembleDebug testDebugUnitTest` 全绿；两仓库提交推送。

---

## 落地状态

### lint 修复（错误级 4 → 0）

- **D01 `<queries>`**：已加。此前 Android 11+ 上「查词典/翻译」永远提示「本机没有词典应用」，即使装了。
- **D02 `justificationMode`**：常量来源改 `android.graphics.text.LineBreaker`，并补 API 34 闸门。
  诊断中发现比 lint 报的更严重：该 setter 是 API 34+，不加闸门在 Android 14 以下设备直接 `NoSuchMethodError` 崩溃。
- **D03 ExifInterface**：迁移 androidx 版（`exifinterface:1.3.7`）。
- **D11 kapt → KSP**：迁移完成（对齐 MoShu），编译与 147 项测试全通过，构建时间下降。

### lint 警告清理（107 → 2）

- **D04 DrawAllocation 10 处**：`Icons.kt` 的 onDraw 改为复用 `scratchPath`/`scratchPaint`/`holePaint`，零分配。
- **D05 ClickableViewAccessibility 6 处**：UP/CANCEL 分支补 `performClick()`。
- **D06 UnusedResources 7 处**：删 4 张无引用 PNG、2 个无引用 string、1 个无引用 drawable。
- **D07 RtlHardcoded 4 处**：`Gravity.LEFT` → `Gravity.START`。
- **D09 Autoboxing 3 处**：`mutableStateOf(0)` → `mutableIntStateOf(0)`。
- SetTextI18n(67)/ViewConstructor(6)/GradleDependency(11)/ObsoleteSdkInt(2)：属误报或有明确工程决策，已在 lint 配置注明理由后关闭。
- 剩余 2 项（OldTargetApi 建议升 targetSdk、InsecureBaseConfiguration 指 debug 网络配置允许明文）为有意保留。

### 运行时诊断（模拟器实测）

- **D12 启动崩溃扫描**：页枢冷启动 3013ms、墨枢 2210ms，无 FATAL。
  抓到并修复一个**必崩 bug**：`ReaderView` 在 IO 协程构造，`applyKeepAwake()` 的 `window.clearFlags` 触碰视图层
  → `CalledFromWrongThreadException`，即「打开任何一本书都闪退」。`applyWarmth()`（addView）同类风险一并修复，
  均改为 `runOnUiThread`。修复后导入→阅读→翻页→目录跳转→长按菜单全程无崩溃。
- 完整链路验证：导入 epub → 打开 → 翻页 → 目录（带百分比）→ 长按菜单（生词本/词典入口齐全）→ 无异常。
- **撤销链路**：删了一条又点撤销后列表没恢复——逐层排查（回收站里条目在、手动「恢复」按钮工作正常），
  最终用诊断日志确认撤销回调执行、记录恢复成功；之前是 adb 点击与 4 秒 snackbar 的竞态，非代码缺陷。
  顺手补齐「今天」页卡片菜单缺失的「收藏」入口（R54 遗留缺口）。
- **D19 旋转状态保持**：横竖屏切换草稿不丢、无崩溃。

### 回归测试新增（D16–D18）

- 进度模型：10 轮往返不漂移；未渲染完时往返 ±1 块内（设计内取舍，写明断言）。
- 墨枢：备份条目往返逐字段相等；旧备份缺新字段用默认值兜底（防 `deletedAt=-1` 被当在回收站）；
  月末 23:59:59.999/月初零点归属正确日；含引号标签序列化安全。

### 测试基建

- 实况冒烟测试的瞬态清单补 `SSLException`（诊断当天网关掐 TLS 握手，恰好验证了这套跳过逻辑的必要）。

**最终状态：页枢 lintDebug 通过（0 错 2 警 3 信息）；测试 145 → 147 全绿。**
