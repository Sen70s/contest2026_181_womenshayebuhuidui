# LuminaWatch · 小云

基于 Kotlin 和 Jetpack Compose 的 Android 应用。当前主导航包含 **健康、小云、手表** 三个入口；页面、交互与本机数据保存已实现，真实手表通信和云端服务仍在开发中。

## 本地运行

1. 用 Android Studio 打开仓库根目录，安装 Android SDK 37，并将项目的 **Gradle JDK 设置为 JDK 21**。
2. 同步 Gradle，运行 `app`；也可以在终端运行：

   ```bat
   gradlew.bat :app:assembleDebug
   ```

   终端运行时，请先将 `JAVA_HOME` 指向本机安装的 JDK 21。不要把个人电脑的 JDK 路径写进仓库的 `gradle.properties`。

3. Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。`local.properties` 由 Android Studio 或本机环境生成，用于设置 `sdk.dir`，不提交到仓库。

当前构建配置：Gradle 9.4.1、Android Gradle Plugin 9.2.1、Kotlin 2.3.21、`compileSdk 37`、`targetSdk 36`、`minSdk 26`。界面使用 Compose 和 kyant Backdrop 2.0.0。

## 页面与功能

| 一级入口 | 主要内容 |
|---|---|
| 健康 | 摘要卡片与编辑排序、健康分类与趋势、心理状态及问卷、心情记录与月历、小云关怀 |
| 小云 | 本机对话、会话历史、记忆与聊天风格、照片选择和拍摄入口 |
| 手表 | 设备列表与设置、表盘预览、连接与同步入口、闹钟、喝水提醒、关怀规则、小云形象与隐私设置 |

底部导航在各层级页面保持可见。普通页面使用横向层级切换，弹窗从底部出现；交互控件复用项目的液态玻璃组件。全屏内容以实体背景承载文字，玻璃效果主要用于按钮、底栏和弹层等交互区域。

## 数据与接入边界

- `AppStore` 使用 SharedPreferences 和 JSON 保存本机数据与偏好；当前没有账号或云同步。
- Debug 构建可显示明确标记的健康预览数据。Release 构建在没有真实数据源时显示无数据；预览值不能作为医疗判断依据。
- 蓝牙页面可发现或登记设备，但设备列表中的信息不代表已完成真实手表配对、传感器读取或同步。手机端表盘预览也尚未下发到手表。
- 闹钟保存在本机，目前不会同步到手表。喝水提醒使用手机本地通知，是否送达取决于通知权限与系统设置。
- 小云对话目前使用本地回复逻辑，没有连接云端模型服务；本机消息与偏好不会自动上传。
- 心理健康问卷是筛查与自我了解工具，结果不能代替专业诊断。

## 代码结构

```text
app/src/main/java/com/pointerwander/luminawatch/
├─ MainActivity.kt   应用入口
├─ app/              导航、页面切换与应用外壳
├─ data/             模型、本机状态与持久化
├─ ui/               主题、通用组件与玻璃交互
└─ feature/          健康、心情、关怀、聊天和手表页面
```

运行截图、APK、构建目录和本机配置均由 `.gitignore` 排除；`app/src/main/res` 中应用实际使用的图片素材保留在仓库中。
