# 页枢

> 本地书架与 AI 文档助手
> 读书，也读懂资料。

页枢（应用标识：`app.yeshu.reader`）是一款面向日常阅读、个人藏书与学习资料整理的 Android 应用。它以本地书架和阅读体验为核心，AI 是用户按需启用的增强能力：不配置任何模型或 API Key，也可以导入、管理、阅读、搜索和备份自己的内容。

## 产品原则

- **本地优先**：书籍、资料、阅读进度、笔记和 AI 结果默认保存在设备本地。
- **日常阅读与资料理解并重**：既可管理小说与普通电子书，也可整理课程 PDF、课件和文档。
- **AI 完全可选**：只有用户主动发起摘要、问答、识图等操作时，应用才会调用所配置的模型服务。
- **自带 Key（BYOK）**：用户选择服务商、接口地址和模型，并使用自己的 API Key。
- **不建设中转服务**：页枢不通过自建服务器转发用户文档或 AI 请求。

## 支持格式

当前导入范围包括：

- 电子书与文本：EPUB、TXT、Markdown
- 文档与课件：PDF、DOCX、PPTX
- 图片：JPG、JPEG、PNG

阅读和预览能力按格式渐进实现：EPUB、TXT、Markdown 提供重排阅读；PDF 使用分页渲染；DOCX、PPTX 提供结构化内容预览；图片用于资料归档与视觉模型分析。旧版 DOC/PPT、Excel 和复杂 Office 排版还原暂不属于首版范围。

## BYOK 与 AI

页枢面向 OpenAI 兼容接口，内置常用服务商配置模板，同时允许填写自定义 Base URL 和模型名称。

- 文本模型与视觉模型分别配置，不把实验模型硬编码进应用。
- 视觉模型可用于扫描 PDF、课件图片和普通照片；未配置视觉能力时会明确提示。
- API Key 由 Android Keystore 保护，不写入源码或日志。
- 发起 AI 请求前，用户可决定发送当前页、章节、选中文字或指定文档范围。
- 长按段落可先「选一句处理」：整段与单句共用同一套动作，菜单顶部标明当前范围，结果里也会记下范围。
- AI 结果保存在本地，并与文档、模型和提示词版本关联。

具体的数据处理规则见 [PRIVACY.md](PRIVACY.md)。

## 备份与恢复

完整备份覆盖数据库、书籍原文件、封面、阅读进度、笔记、AI 结果和普通设置。**API Key 默认且始终不进入备份文件**，恢复后需要用户在设备上重新配置或使用本机已保存的安全凭据。

备份格式带版本号，并保留对旧“书阁”备份标识的兼容入口，以便迁移为“页枢”格式。

## 架构与迁移现状

项目当前处于渐进式重构阶段：

- 单 `app` 模块，最低 Android 8.0（API 26），目标 API 35，JDK 17。
- 新应用标识为 `app.yeshu.reader`，品牌由“书阁”迁移为“页枢”。
- 新外壳使用单 Activity、Jetpack Compose 和 Material 3；成熟的旧版阅读视图暂通过 `AndroidView` 复用，并按模块逐步迁移。
- 数据层使用 Room；数据库版本 7 包含从旧版版本 6 重建并导入书籍、文件夹、笔记、设置和阅读记录的迁移路径。
- 偏好设置使用 DataStore，导入与长耗时任务使用协程和 WorkManager；部分成熟阅读界面仍保留兼容层。
- 调试入口已限定到 debug 构建；发布包不应包含调试 Activity。

迁移期间应先保留旧应用数据备份。数据库迁移、包名迁移和完整备份恢复必须在真机或模拟器上通过验证后再用于正式数据。

## 本地构建

### 环境要求

- JDK 17
- Android SDK Platform 35
- Android SDK Build Tools（与 API 35 兼容版本）
- 可用的 Android SDK 路径（通过 `local.properties` 的 `sdk.dir` 或 `ANDROID_HOME` 配置）

项目使用 Gradle Wrapper 8.9，无需单独安装 Gradle。

### Windows PowerShell

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug lintRelease
.\gradlew.bat assembleDebug assembleRelease
```

### macOS / Linux

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug lintRelease
./gradlew assembleDebug assembleRelease
```

Debug APK 生成在 `app/build/outputs/apk/debug/`。APK、构建缓存、截图、测试文档和密钥文件不应提交到 Git。

## 持续集成

GitHub Actions 使用 Ubuntu 与 Temurin JDK 17，依次执行：

1. `testDebugUnitTest`
2. `lintDebug`
3. `assembleDebug`
4. `lintRelease`
5. `assembleRelease`

工作流还会审计 Release 清单，拒绝调试入口与疑似明文 API Key。

任一测试、Lint 错误或构建失败都会使工作流失败。

## 隐私提醒

不要在 Issue、提交记录、截图或日志中公开 API Key。曾经通过聊天、测试脚本或明文配置暴露过的 Key 应立即在对应服务商后台轮换。
