# 版本公告

## 1.7.3 真机帧率与字幕

- 固定布景的灯光烘焙至顶点颜色，17 个材质批次合并为 1 个。角色继续使用动态灯光，舞台关闭实时投影。
- 点击角色时，在后台准备当前唱段的双语字幕纹理。换句只绑定已准备的纹理，移除字幕模糊阴影。
- 演唱时只更新唱歌角色的嘴部。人物简介避让与字幕定位复用上次计算结果。
- B3110 真机、90 Hz、1776 伯尔场景，原包走近时的三个一秒样本为 52、50、50 FPS。新版在同一路线和演唱的六个一秒样本为 83、90、90、89、90、90 FPS。
- Android 仪器测试 `PerformanceTest#profileSixYearsOnHeadset` 可记录六个年份的角色走近、演唱与字幕换句。佩戴头显，按 `HamiltonPerformance` 的 SELECT、PLAYING、PROFILE_END 与系统 SpatialFrameTiming 的应用 Spatial FPS 对齐。可传 `yearIndex` 复测单场。启动、休眠和应用退出需单独标注。
- `CaptionRasterCostTest` 在真机交替绘制同一组字幕，每组 24 次有效采样。原尺寸 CPU 绘制中位数为有模糊阴影 1.61 ms、无阴影 1.53 ms。此测量不含 GPU 上传。

## 1.7.2 打包修复

- 将完整 Android Gradle 工程展开至仓库根目录，供自动构建系统直接识别。
- 保留可执行 Gradle Wrapper、版本清单、源代码、测试及离线运行资源。
- versionName 统一为 1.7.2，与提交表单一致；versionCode 保持 11。
- 不提交本机 SDK 路径、构建缓存或签名密钥。
- 舞台、角色和交互行为保持原版本。

Release 构建不包含私有签名密钥；上架签名由提交平台处理。
