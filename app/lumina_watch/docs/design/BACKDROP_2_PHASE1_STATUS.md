# Backdrop 2.0.0 第一阶段实施记录

## 本次已完成

- 项目升级到 Backdrop 2.0.0；配套升级 AGP 9.2.1、Kotlin 2.3.21、Gradle 9.4.1 和 compileSdk 37。minSdk 26、targetSdk 36、业务数据结构及持久化未改。
- 底栏继续保持三入口和 64dp 的紧凑深/浅色轨道，原有 `bottomBarDark` 设置继续生效。
- 选中项改用 Backdrop 2.0.0 的 `vibrancy → blur → lens`，拖拽/按压时渐增折射、色散、高光、内阴影与放大；弹簧跟随、跨标签预览、松手切换和触觉反馈保留。
- 底栏文字/图标单独采样并与页面 backdrop 合成，透镜不是一块纯色覆盖物。

## 验证

- `:app:assembleDebug` 构建成功。
- 模拟器覆盖安装、启动成功，手表页拖到“发现”页成功；查看运行日志未见应用 FATAL 或 RenderThread 崩溃。
- 深色静态截图：`backdrop2_phase1/dark_initial.png`、`backdrop2_phase1/dark_selected_discover.png`。
- 设置页切到浅色、返回主界面后底栏同步变更；截图：`backdrop2_phase1/light_selected_watch.png`。验证后已恢复原来的深色偏好。
- `backdrop2_phase1/dark_drag_capture.png` 是拖动结束后、页面切换动画尚未完成的一帧，并非最终布局。

## 仍需验收/下一阶段

- 真机上确认拖拽过程中的形变、折射手感和耗电/帧率；截图无法证明动画质量。
- Android 12/13 的效果降级及无动画偏好还需逐项实测。
- 其他页面尚未按新版设计全面改造；现有健康、心情、关怀、闹钟、聊天、设置功能保持原状。底栏会覆盖首屏底部内容，后续需逐页补齐安全区。
