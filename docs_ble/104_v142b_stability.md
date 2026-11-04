# 104. v142b 稳定性验证：NOR 数据只读（2026-11-04）

## 结论
**黑屏频繁复位的直接根因是 /data 的 NOR 写/擦除窗口**。把 NOR 数据写关闭后，v142b 连续运行 8.6 分钟、6 次 TTS + 3 次 LLM、期间多次 BT PAN buffer 风暴，**零 SFBL/RSET/Assertion**。

## 证据
| 版本 | 数据写 | 结果 |
|---|---|---|
| v142（修复 TTS/TLS/NOR 超时，但允许写） | 开 | 11:05:01 TTS 结束后 NOR write seq=960/1024/1088，UI 心跳停，11:05:24 IWDT 复位 |
| v142b（`g_nor_data_ro=true`，MTD 写/擦除直接 -EROFS） | 关 | boot #7 起 **up=516s+**，6×voice_speak + 3×ask，无复位 |
| v132 非演示 | 开 | 6 分钟内 3 次 IWDT 复位 |

- 复位均为 `[RSET] WSR=00000020` = IWDT；无 Assertion/无 [stall]；保留心跳显示 UI 在写窗口附近停摆；
- v142b 日志出现 `[sf32lb_flash] erase rejected (data read-only)`，证明写在进入硬件前被拦截；
- 期间仍有 `Unable to allocate buffer within timeout`（PAN/ACL 压力），但不再复位 → BT 风暴是诱因/放大器，不是唯一根因；
- 说明 v142 的 TTS/TLS 修复有效（无 bad JSON/TRUNCATED/回退链），但 NOR 写窗口才是冻机主因。

## 当前 v142b 的代价（A 方案）
- `/data` 只读：health/steps/chat history/alarm/cron/bt_storage/persona/config 的写入全部失败（读取正常）；
- 重启后这些数据不保留；App 同步闹钟/表情等写操作会失败（需要时再恢复）；
- 实时功能（语音、聊天、天气、TTS、BLE 连接）正常。

## 下一步建议（v143：选择性安全持久化）
1. 先定位 NOR 写为什么在“正常 rc”后仍冻机：用 NSH 直接向 /data 写小文件/`dd` 复现；检查 write 后 `sf32lb_flash_restore_ahb_read()` 是否所有路径执行、cache invalidate 时序、littlefs 在写后读 XIP 的窗口；
2. 若无法短期修驱动：采用 **RAM 缓存 + 安全时机批量落盘**（只在 USB 供电/空闲/无音频时写，写前后做 AHB/XIP 自检），或只对配置/闹钟白名单写；
3. 恢复顺序：config/alarm → health/steps（低频）→ chat history（最后），每步做 30min soak；
4. 所有写失败必须被上层容忍（降级为内存态），不能 assert/卡死。

## 版本
- v142b：7,448,096 B，md5 `01d9c4d87882f98546293bf72940c2b3`，flash 74.28%，已烧录；
- v142（可写对照）：`03f60274579b55508a109afad1573b37`；v132：`b9536c8b6b075afeb38432c64bd13c46`；
- App：非演示 `080ef0d1a39fa048666e4a383869edee`（本轮未改）。
## 补充（v142c）：语音第 6 次起不出声
- 现象：用户第 6 次语音问答只有文字，日志 `[voice] TTS per-boot limit reached (5), text only`；不是回退链。
- 原因：v113d 的每 boot 5 次 TTS 上限（旧 TLS 复用停摆保护）。
- 处理：v142c 把上限升到 20；实测连续 8 次 voice_speak 全部出声，无复位。
- 版本：v142c 7,448,096 B md5 574f5238ae0ed8659f9a2a0cae8f77ab（已烧录）。
- 仍保留 /data 只读；上限用完 reboot 重置。
