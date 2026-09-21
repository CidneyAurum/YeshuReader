# 页枢 · 交付说明（DELIVERY）

> 应用名称：页枢（Yeshu Reader）
> 应用标识：`app.yeshu.reader`
> 商店副标题：本地书架与 AI 文档助手
> 品牌口号：读书，也读懂资料
> 文档版本：2026-08-24

本文档用于交付最终成品。压缩包内不包含 `Android SDK`、Gradle 用户缓存、密钥、临时截图、QA 抓屏或调试副产物；这些内容只存在于开发者本机，团队成员拉取源码后可在自己的 `local.properties` 中配置 `sdk.dir` 后直接重建。

## 一、最终成品（APK）

| 构建变体 | 文件 | 大小 | SHA-256 |
| --- | --- | --- | --- |
| Debug（调试证书签名，可安装） | `app/build/outputs/apk/debug/app-debug.apk` | 20,489,286 字节 | `1B8B0431179F69565FC6E75C0962956BDD8FAB5118E59F2DA9CA7914EBBE5ECF` |
| Release（未签名） | `app/build/outputs/apk/release/app-release-unsigned.apk` | 3,250,766 字节 | `FE0A91E88F6FF9FF0044F81C0019697462FA9F21EE41CB1782870BF5192E1570` |

两个 APK 在交付时已经分别通过本地 Debug 编译 + Release 编译验证，来源项目的 `output-metadata.json` 同步保留。Release 已开启 R8 混淆与资源压缩（`isMinifyEnabled = true`、`isShrinkResources = true`，体积由 15.9MB 降至 3.25MB），保留规则见 `app/proguard-rules.pro`；混淆包已在 API 35 模拟器上实测安装、启动、书架与旧版阅读器渲染、底部弹层，无崩溃。

Release 默认产出未签名 APK：在仓库根目录放置 gitignored 的 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）后 `assembleRelease` 会自动签名。未签名的 APK 系统会拒绝安装，正式分发前必须用长期保存的发布证书签名。

## 二、产品定位

- 本地优先的个人书架，同时提供可选的 AI 文档理解能力。
- 小说阅读、日常藏书与学生资料整理同等重要。
- 不配置任何 AI 模型或 API Key，也能完整导入、管理、阅读、搜索和备份书籍与资料。
- AI 是按需启用的增强能力，从不主动上传。

## 三、压缩包结构

```
YeshuReader/
├── README.md                 产品总览（README）
├── PRIVACY.md                隐私说明
├── DELIVERY.md               本文件：交付清单
├── branding/                 品牌资产与图标
│   ├── ICON_DELIVERY.md      图标设计交付说明
│   ├── yeshu-icon.svg        1024 主图矢量母版
│   ├── yeshu-icon-1024.png   1024 主图 PNG
│   ├── yeshu-icon-preview.svg / .png   深浅背景 + 24/48 px 预览
│   └── concepts/             三张受控概念稿（设计追溯用）
├── .github/workflows/        CI 工作流（Ubuntu + Temurin JDK 17）
├── gradle/                   Gradle Wrapper 配置
├── gradlew / gradlew.bat     Gradle Wrapper 启动脚本
├── build.gradle.kts          项目级 Gradle 脚本
├── settings.gradle.kts       Settings 脚本
├── gradle.properties         Gradle JVM / 并行 / 缓存参数
├── local.properties          本机 SDK 路径（不提交；保留以备重编）
├── .gitignore                Git 忽略规则
└── app/
    ├── build.gradle.kts      模块级 Gradle 脚本
    ├── proguard-rules.pro    R8 规则（当前 release 未启用 R8）
    ├── src/                  源码（Kotlin + Compose + View 兼容层）
    └── build/outputs/apk/    最终 APK + 元数据
```

> 已从压缩包中移除：`.gradle/`、`.kotlin/`、所有 QA 截图与 xml 抓屏、`app/build/intermediates/`、`app/build/generated/`、`app/build/reports/`、`app/build/test-results/`、`app/build/kotlin*`、`app/build/snapshot/`、`app/build/tmp/`、`*.compiler.options`、`connected_android_test_additional_output`、`sdk-dependencies`、`baselineProfiles`、工作区根目录遗留的 `_yeshu-current.png`。

## 四、技术栈

- 语言 / JDK：Kotlin 2.0.21，JDK 17
- 构建：Android Gradle Plugin 8.7.3，Gradle Wrapper 8.9
- 最低 / 目标 / 编译：minSdk 26（Android 8.0）/ targetSdk 35 / compileSdk 35
- UI：单 Activity + Jetpack Compose + Material 3；旧版阅读器、书架等以 `AndroidView` 复用
- 数据：Room 2.6.1（含从 v6 重建并导入的迁移路径，当前 schema v7）
- 偏好：DataStore Preferences 1.1.2
- 异步：协程 + WorkManager 2.10.0
- 解析：内置 EPUB / TXT / Markdown / PDF / DOCX / PPTX 解析器；图片归档使用 ExifInterface

## 五、测试与质量门

| 阶段 | 结果 |
| --- | --- |
| JVM 单元测试（`testDebugUnitTest`） | 通过：33 用例，0 失败、0 错误 |
| 实况冒烟测试（可选） | `AiLiveSmokeTest` 5 用例，走真实服务商接口；未提供密钥时自动跳过 |
| API 35 模拟器（混淆 release 包） | 通过：安装、启动、书架与旧版阅读器渲染、底部弹层，无崩溃 |
| Debug Lint | 0 error / 98 warning |
| Release Lint | 0 error / 100 warning |
| Release manifest 自检 | 未发现 `DebugActivity` 引用、未发现疑似明文 API Key；`DebugActivity` 已设为 `exported="false"` |
| APK 构建 | `assembleDebug` 与 `assembleRelease` 成功 |

> 实况测试默认跳过，不影响离线 CI。启用方式：环境变量 `YESHU_AI_KEY`，或未跟踪的 `local.properties` 中写入 `yeshu.ai.key`（`yeshu.ai.baseUrl` / `yeshu.ai.model` 可覆盖默认端点）。密钥只从本机未跟踪文件读取，绝不写入源码或测试资源。

> `lint.xml` 仍可能保留若干条 i18n / 资源未使用 / 依赖升级提示的 warning，不影响构建；上线前建议再走一次完整国际化与依赖刷新。

## 六、AI / BYOK 使用要点

- 内置服务商模板：OpenAI、DeepSeek、通义、智谱、Kimi、本地 Ollama 等 OpenAI 兼容接口。
- 文本模型与视觉模型分别配置；视觉模型用于扫描 PDF、课件图片和普通照片。
- 自定义 Base URL 与模型名支持“裸域名 / 完整 URL / 带 query / 自定义相对路径”四种形式；非标鉴权头与前缀可逐项配置。
- API Key 加密保存在 Android Keystore，不写入日志、源码、截图或备份文件。
- 局域网 / 回环 HTTP 需用户显式二次确认；公网 HTTP 即使勾选也会被拒绝；跨源 URL 与受限 header 同样被拒。
- 支持流式输出（SSE）与“整段 JSON 回退”，并提供取消、重试、结果缓存；缓存键绑定文档指纹 + 模型 + 提示词版本。

> 安全提示：曾经粘贴到聊天里的 DeepSeek Key（`sk-51cf362be89f4d83b86f58384434f21f`、`sk_tr_hhq9XFAWHHrqLGu7tlZBXpKdkEDWS0Og3kNbwsfrwUE` 等）已经被用于调试，但代码与仓库从未包含明文 Key。请尽快在 DeepSeek 控制台轮换。

## 七、本地重建

```powershell
# 准备：确保 local.properties 指向本机 Android SDK
#   sdk.dir=D:/Android/android-sdk

.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug lintRelease
.\gradlew.bat assembleDebug assembleRelease
```

构建产物路径：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release-unsigned.apk`

## 八、CI 行为

`.github/workflows/android.yml` 在 Ubuntu + Temurin JDK 17 环境下按顺序执行 `testDebugUnitTest → lintDebug → assembleDebug → lintRelease → assembleRelease`，并在最后两步再扫描一遍 release 清单与源码，禁止 `DebugActivity` 漏入 release、禁止明文 API Key 入库。

## 九、数据迁移与备份

- 数据库 schema v7，附带从 v6 重建并导入旧版“书阁”数据的迁移路径。
- 包名迁移：原 `com.example.helloandroid` → 当前 `app.yeshu.reader`，旧数据在迁移前已备份到 `_migration_backup/com.example.helloandroid-20260823/`。
- 用户主动备份包含数据库、原文件、封面、笔记、AI 结果与普通设置；**API Key 永不进入备份**。

## 十、版本与许可

- 版本号：`versionCode = 2`，`versionName = "1.1"`
- 上线前需完成：正式商标查重、应用商店重名检查、`app.yeshu.reader` 唯一性终检、发布签名密钥（R8 规则已就位）、隐私政策发布与首发地区法规核对。
