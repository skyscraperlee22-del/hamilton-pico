# Hamilton 音乐剧舞台 · PICO Full Space

走进低多边形音乐剧舞台，穿梭六个年份，与角色互动，聆听演唱选段。

这是面向 PICO Spatial SDK 的原生 Android Full Space 粉丝作品。包名 `com.hamilton.stage`，当前版本 `1.7.2`（versionCode 11）。

![应用图标](icon.png)

## 体验与功能

- 1776、1780、1781、1789、1791、1800 六个时间点；拖拽舞台上方时间轴，吸附切换年份与人物站位。
- 14 位 low-poly 角色、31 个角色演唱选段交互。
- 注视或手柄指向人物，悬停 750ms 显示胶囊姓名卡；点击人物或姓名卡后，Hamilton 走近、转向观众并开始演唱。
- 演唱取景、嘴部动画、人物简介，以及对齐当前演唱组的中英双语字幕；走动和准备期间隐藏字幕。
- 人物简介高度随内容变化，并避让人物面部；正在演唱的人物暂停悬停高亮。
- 音频选段与字幕随包提供，演唱无需在线加载。歌词为逐句近似对齐，不是逐字口型同步。

## 展示

以下为 PICO 原生应用运行截图：

![原生演唱与双语字幕](native-performance.png)

以下为宣传海报，属于宣传美术，不代表头显实时渲染画质：

![1776 宣传海报](poster-1776.png)

## 安装与兼容性

安装包在本仓库 Releases 发布，打包修复版见 `v1.7.2-buildfix`。版本面向支持 Spatial SDK 6.1 的 PICO OS 6 开发环境，Android API 35、ARM64。并非面向所有旧 PICO 系统的通用 VR 包。

提供的是开发体验用 debug APK，不是应用商店签名发行包。保留 Android 原有签名验证；若已有不同签名版本，需要按系统提示处理安装冲突。

```bash
adb install -r Hamilton-PICO-v1.7.2.apk
adb shell am start -n com.hamilton.stage/.platform.LaunchActivity
```

眼动交互需要系统授权；可用手柄指向作为备用。应用不记录原始注视数据。

## 构建

直接克隆本仓库，在仓库根目录执行 Gradle。完整工程与运行资源均已展开，无需解压源码 ZIP。

安装 JDK 21、Android SDK 35 和兼容的 PICO Spatial 开发环境。将 Android SDK 路径写入本机 `local.properties`，不要提交该文件：

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

```bash
chmod +x gradlew
./gradlew :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。首次构建需要网络下载 Gradle 与依赖；依赖仓库配置见 `settings.gradle.kts`。已经包含运行用 GLB、音频和字幕，无需重建资源即可编译。

技术栈：Kotlin 2.1.20、Android Gradle Plugin 8.13.2、PICO Spatial SDK BOM 6.1.9、Jetpack Compose、Spatial ECS、GLB/glTF、Android MediaPlayer、Gradle。

## 测试

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

设备集成测试需连接兼容模拟器或头显。测试覆盖舞台加载、年份与路线、交互状态及音频字幕清单。展示截图来自开发期间的原生运行记录；不包含帧率性能认证。

## 权利与使用声明

仅供个人娱乐使用，不用于盈利；如有侵权，请通过本仓库 Issues 联系删除。

本项目为非官方粉丝作品，与 Hamilton 官方无关联。音乐、歌词及相关作品的权利归各自权利人所有。本仓库不授予这些第三方内容的商业使用或再分发许可。

## 开发工具

使用 OpenAI Codex 辅助开发。历史记录未完整保存具体模型版本，因此不对具体型号作出声明。宣传图和图标使用 AI 图像生成工具辅助制作。

## 自动构建入口

仓库根目录包含 `settings.gradle.kts`、`build.gradle.kts`、`gradlew` 与 `app/`。使用 JDK 21 和 Android SDK 35，执行 `./gradlew :app:assembleRelease`。输出为 `app/build/outputs/apk/release/app-release-unsigned.apk`，交由平台签名；开发体验包使用 `./gradlew :app:assembleDebug`。不要在提交表单继续使用旧的 ZIP-only Commit ID。
