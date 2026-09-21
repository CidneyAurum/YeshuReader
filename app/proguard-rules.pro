# R8 混淆/裁剪规则（release 构建已启用 isMinifyEnabled + isShrinkResources）。
#
# 依赖自带的 consumer 规则已覆盖大部分反射点，无需重复声明：
#   - androidx.work:work-runtime  : ListenableWorker / InputMerger 子类的类名与构造器
#   - androidx.room:room-runtime  : RoomDatabase 子类（含生成的 *_Impl）
#   - androidx.compose.*、androidx.datastore、androidx.lifecycle 等 AndroidX 库
#   - org.json 属于平台类（android.jar）；legacy View 全部直接 new，均不需要 keep
# 本文件只补充应用自身的反射入口。

# WorkManager 通过 WorkSpec 里持久化的类名字符串反射实例化 Worker：
# 类名必须保持稳定（否则升级后已入队的任务找不到实现），构造器也不能被裁剪或改名。
-keepnames class app.yeshu.reader.data.LibraryImportWorker
-keepclassmembers class app.yeshu.reader.data.LibraryImportWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# DocumentAiService 解析模型输出后用 AnchorType.valueOf(字符串) 反查枚举
# （ai/DocumentAiService.kt:337）。枚举常量名必须原样保留，valueOf/values 也不能被移除。
-keep class app.yeshu.reader.AnchorType { *; }
