# 114. P2B-2 场景：喝水/睡觉/天气/眼睛休息 + App 手机亮屏检测

> 基线 v142y（P2B-1 voice 开关）、App 222cb911。目标：新增 4 个可落地场景，全部本地语音/角标，遵守 DND/夜间/预算/冷却/voice/PTT/音乐门禁。

## 场景与数据源
| 场景 | 触发 | 数据 | 音频 |
|---|---|---|---|
| 眼睛休息 | Android 手机连续亮屏 ≥ 阈值（40/60min） | App 前台服务 + PowerManager.isInteractive | 无缓存，先角标/历史，不出声 |
| 喝水 | App 设定时间/间隔 | App 调度，BLE care_event(type=water) | WATER 缓存 |
| 睡觉 | App 设定入睡时间 | App 调度，BLE care_event(type=sleep) | SLEEP 缓存 |
| 天气 | App/云端真实天气事件 | App 获取真实数据后发 care_event(type=weather) | WEATHER 缓存 |

## BLE 协议
```json
{"cmd":"care_event","type":"eye_rest|water|sleep|weather"}
```
回包：
```json
{"evt":"care_event","ok":1,"type":"eye_rest","suppressed":0}
```
- 手表只接受白名单 type；未知 type ok=0；
- 手表端 DND/夜间/预算/冷却/voice/PTT/音乐门禁仍生效；suppressed=1 表示被静默。

## 眼睛休息检测（Android）
- 前台服务 + 通知，30s 轮询 `PowerManager.isInteractive()`；
- 连续亮屏 ≥ 40/60min（设置可选），锁屏/熄屏清零；冷却 60min；
- 只发 `care_event eye_rest`，不上传屏幕内容/应用列表；
- App 设置开关/阈值/冷却；需要通知权限；
- iOS 暂不支持。

## 验收
- App 亮屏阈值到 → 手表角标/历史出现 eye_rest；DND/夜间不出现；
- water/sleep/weather 事件到 → 对应缓存本地语音（voice=1）；voice=0 只角标；
- 未知 type/非法 JSON ok=0；
- 不写 /data、无新线程（手表）、无复位。