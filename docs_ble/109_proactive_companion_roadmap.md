# 109. 主动陪伴产品与技术路线（规划稿）

> 基线 v142f（P0 已落地：规则 Router、DND/夜间/预算/冷却、HR 连续两次、走路算活动、L2 默认关闭、/data 只读）。本文件只做规划，不代表已实现。

## 1. 目标与非目标

### 目标
- 让手表在**合适时机**主动给出**短、可解释、可忽略**的陪伴；
- 用户能控制频率/类型/静默时段，并能反馈“接受/忽略/拒绝”；
- 端侧负责时机与安全，云端只负责“说什么/长记忆”；
- 先跑通闭环与指标，再谈个性化模型。

### 非目标（P1/P2 不做）
- HMM/LightGBM/Contextual Bandit 实时推理；
- 血氧/血压/睡眠分期作为决策依据；
- 无授权的持续录音/上传原始生理数据；
- 主动语音长篇生成（先固定短句/缓存）；
- 重新打开 /data 持久化（NOR 写冻结未修好前）。

## 2. 当前基线（P0）

| 能力 | 现状 |
|---|---|
| 特征/状态 | `care_features_t`：HR/age/基线偏差、步数/静止/空闲、DND/夜间、最近交互、busy |
| 决策 | `care_router` 规则：ACTION/SILENT/DEFER（DEFER 目前仅建议，无 ticket） |
| 场景 | 心率异常（连续2次）、久坐、空闲 |
| 安全 | DND/夜间强制静默；每 boot 预算 8；同类冷却 10min；最小间隔 60s；控制类不进本地模型 |
| 执行 | L1 气泡/历史；L2 云端接话默认关闭；不发声 |
| 反馈 | 无结构化记录（只有串口日志） |
| 持久化 | /data 只读（NOR 写冻结根因），重启不保留 |

## 3. 产品定义：先做 5 个场景

| 场景 | 触发依据 | 行为 | 风险控制 |
|---|---|---|---|
| 心率异常关怀 | 连续 2 次静默测量 ≥120 或比基线+20，数据 <20min | 气泡「心跳有点快，先坐下歇会儿」 | DND/夜间只记录；不诊断 |
| 久坐提醒 | 静止 ≥45min 且 IMU alive、非运动 | 气泡「坐太久啦，起来走两步」 | 走路中不触发；10min 冷却 |
| 长时间静止/空闲陪伴 | 无交互 >5min、非运动、非夜间 | 轻量气泡「我在呢，想聊点什么吗」 | 刚交互 60s 内不触发；预算内 |
| 喝水/睡觉提醒 | **用户/App 预设**（不假装传感器检测） | 固定文案气泡/语音 | 只在用户设定时间；可关闭 |
| 步数里程碑 | 真实步数达阈值 | 庆祝气泡（动态数字，不配音） | 每阈值一次；不占预算/不吞闹钟 |

暂不做：天气变化提醒（需要额外数据源与真实性校验）、血氧提醒、情绪诊断。

## 4. 架构：端侧 Router + Ticket + 执行 + 反馈

```
Sensor/IMU/RTC/交互
  ↓
care_features_t (1Hz 只读快照)
  ↓
care_router_eval → ACTION / SILENT / DEFER
  ↓ ACTION
care_ticket (RAM, TTL/优先级/幂等/去重)
  ↓
care_fire (气泡/历史)  +  可选固定缓存语音（安全门禁）
  ↓
feedback (接受/忽略/拒绝/关闭类别) → RAM 日志 → App/云端
```

### 关键设计
- **Ticket 只放 RAM**：字段 task_id/event/created/expires/priority/status/action_plan；不写 /data；重启即丢（可接受，P2 再持久化）。
- **DEFER 用 tick 时间轮**：不新增线程/定时器；expires 到点重新 eval。
- **云端只做内容生成**：端侧 ticket 通过后，才允许请求 LLM 生成一句话（可选）；云端不能决定“何时打扰”。
- **语音默认关**：先只气泡；固定短句缓存语音需单独开关 + 音频占用检查 + TTS 预算；绝不在流式 LLM 回答期间播。
- **控制类/设备操作**永远不交给主动路径。

## 5. 分阶段计划

### P1a（3–4 天）：闭环骨架
- [ ] `care_ticket` RAM 模块：create/cancel/expire/dedup，最大 8 条；
- [ ] DEFER 时间轮接入 `care_router_tick`；
- [ ] 场景白名单：心率/久坐/空闲/里程碑；喝水/睡觉先由 App 预设；
- [ ] `care_log` RAM 环形 16 条：event/features/state/score/decision/action/feedback/reason；
- [ ] 用户反馈入口：气泡“知道了/不用了”（触摸/按键/App ack）；
- [ ] 验收：24h soak、预算≤8、DND/夜间 0 违规、无复位。

### P1b（3–4 天）：App 与用户控制
- [ ] App `evt=care`（时间线）+ `care_ack`（接受/忽略/拒绝）；
- [ ] App 设置同步：DND、夜间时段、每日上限、类别开关（心率/久坐/空闲/喝水/睡觉）；
- [ ] 手表本地也遵守 App 设置；
- [ ] 验收：设置改动能即时生效；关闭类别后 0 触发；App 时间线与手表一致。

### P2（1 周）：语音/内容增强（可选，安全优先）
- [ ] 固定短句缓存语音（仅 5 条核心文案）：音频占用检查、TTS 不并发、可一键关；
- [ ] 云端 L2 一句话生成：端侧 ticket 通过后请求，限制 20 字、失败降级模板；
- [ ] 个性化评分：从反馈日志统计接受率，调整权重（仍规则，不上模型）；
- [ ] 验收：语音首声 <1s（缓存）、无 TTS/流式并发 hardfault、DND 下无声。

### P3（依赖 NOR 修复）：持久化与分析
- care_log 落盘、跨重启预算/冷却恢复；
- App 周报/接受率；离线导出；
- 未来才考虑 int8 逻辑回归/极浅树（数据量足够后）。

## 6. 指标（上线前定义，避免“感觉”验收）

| 指标 | 目标 |
|---|---|
| 主动次数/天 | ≤8（可配置） |
| DND/夜间违规 | 0 |
| 同类冷却违规 | 0 |
| 用户忽略率 | 先观察，P2 目标 <40% |
| 主动气泡→用户交互率 | ≥20% 作为“有价值”初判 |
| 决策耗时 | <10ms/tick |
| 24h 复位/Assertion | 0 |
| 主动期间 NOR 写 | 0（P1/P2） |

## 7. 资源与安全红线

- SRAM 94–97%：ticket 8 条 + care_log 16 条 ≈ 1–2KB，需实测余量；不新增线程；
- `/data` 只读：P1/P2 全 RAM；P3 才恢复写；
- 音频单所有者、TTS 每 boot 20 次：主动语音必须单独预算/互斥；
- 心率质量门槛/夜间/DND/每日预算由 Router 强制，云端不能覆盖；
- 演示数据与真实数据隔离；不伪造设备动作。

## 8. 风险

| 风险 | 处理 |
|---|---|
| 误报/打扰过载 | 预算/冷却/类别开关/忽略反馈 |
| 心率误判 | 连续 2 次 + 数据新鲜度 + 不诊断 |
| 语音并发 hardfault | 默认关；启用时互斥/不并发 |
| SRAM 不足 | 固定上限、静态分配审查 |
| 重启丢 ticket/log | P1 接受；P3 持久化 |
| 用户不信任 | 可解释日志/设置/一键关 |

## 9. 待决策
1. P1 优先做「闭环骨架」还是先做 App 时间线？建议先骨架；
2. 主动语音是否 P2 才开？建议是；
3. 场景是否先只做心率/久坐/空闲 3 个？喝水/睡觉需 App 预设，建议 P1b；
4. 每日上限/夜间时段默认值（8 次/22–7）是否接受？
5. 是否现在启动 P1a（3–4 天）？
## 10. P1a 真机结果（2026-11-04 13:59，v142i）

- 固件 v142i：7,459,748 B md5 `f77a162d54c64d279ee3eff583380dd1`（已烧录）。
- 空闲 5min -> `[care_router] req type=idle decision=ACTION reason=idle_5min` -> `[care_ticket] create id=1 type=idle status=FIRED` -> 气泡。
- 78s 后 `[care_log] count=1/16 accepted=0 ignored=1`，条目 feedback=IGNORED；ticket active=1。
- 验证中发现并修复：单步/IMU 噪声会累计触发 walked，导致 idle 永不触发；改为 60s 内 >=20 步才算走路（Router + pet_care）。
- up=379s，无 SFBL/RSET/Assertion；/data 仍只读；静态 RAM +956B。
- 未验证：ACCEPTED 路径（需真实触摸/PTT）、DEFER ticket 过期、DND SILENT 日志；P1b 用 App ack 补显式反馈。
- 已知复核项：隐式反馈口径、care_log pending 用 s_last_idx 而非 id、ticket 去重会取消 FIRED 审计链（P1b 修）。

## 11. P1b 代码完成（2026-11-04 14:27，待烧录联调）
- 固件 v142j 镜像：7,463,408 B md5 `d836dc8f64e99609d3b56749bfdd203c`；
- App：26,982,028 B md5 `ba974903757594b7bf21f01d12de009f`（已发布）；
- 内容：care_list/care_ack/care_settings_get/set、时间线与设置 UI、DND 双向同步、seq 精确反馈；
- 复核修复：quiet start<end 同日窗口、ts<2024 输出0、App care_list 默认6条；
- 状态：构建完成；watch USB 不在（无 /dev/ttyACM*），**固件未烧录**，待插回后 flash + BLE 联调。

## 12. P1b 真机联调通过（2026-11-04 14:46，v142k）

- 固件 v142k：7,463,440 B md5 `55910fbaeb2f5455b3e6f455a470e8cd`（已烧录）；
- App：26,982,028 B md5 `96d1be73bb58fdb7c0fc6c203467ec62`（已发布）；
- care_settings_get 默认 22/7/8 全开；set 23-6/2/types idle=0 后回读一致；
- idle ACTION -> care_ticket id=1 FIRED -> care_list 返回 seq=1/ts 有效/feedback=IGNORED；
- care_ack seq=1 ACCEPTED -> 再次 care_list feedback=ACCEPTED；
- up=465s，无 SFBL/RSET/Assertion；/data 只读；care_list 默认 6 条防单帧超限；
- 复核修复：ts 两遍门控、quiet 同日窗口、App hr_alert/原因/DEFER/按钮过滤/设置回包对账。

## 13. P1c 验收（v142q，2026-11-04 17:31）
- 固件 v142q：7,910,936 B md5 `791f75c229ad620d6cd4512f260a46a1`（已烧录）。
- care_settings_set 原子：`dnd=1+quiet_start=24` -> ok=0（quiet_start out of range），再次 get dnd=0/22-7 未变；合法 23-6/2/types idle=0 -> ok=1 回读一致。
- App：26,982,028 B md5 `f7e115fe90a741971c24582390ffe13d`；时间线仅 ACTION+fire 可 ack、原因/决策中文化、设置回填/持久化、6s/4s/5s 超时与“重新同步到手表”。
- 待验：care_ack 幂等/双发、真实 idle 记录时间线、App 真机连接。

## 14. P1c 真机结果（v142q，2026-11-04 17:38）
- care_settings_set 原子：dnd=1+quiet=24 -> ok=0，get 仍 0/22-7；合法 23-6/2/types -> ok=1。
- idle -> ticket id=1 FIRED；care_list count=1、seq=1、ts/字段齐全。
- care_ack 幂等：同 seq+ACCEPTED 第二次 `ack idempotent`，accepted 仍 1；改 REJECTED 后 accepted=0/rejected=1。
- App APK 26,982,028 B md5 `f7e115fe90a741971c24582390ffe13d`（已发布）。
- up=415s 无复位；/data 仍只读。
- 待真机 App 验证：时间线、ack 按钮、设置页、重启重下发；已知 P2：DND 双源、docs 8→6、ack 防抖。

## 15. P2A 主动本地语音（v142r，2026-11-04 18:06）
- 固件 v142r：7,911,112 B md5 `761a5e35e1b4696f50f2456dad58bfcd`（已烧录）。
- idle/sedentary/hr_alert/alarm 的气泡文本改为与 `voice_tts_cache` 缓存文案同一宏，做到“看到=听到”。
- 播放门禁：recording→music→busy→cache lookup→voice_channel_speak_async；managed 类型 DND/夜间由 Router 运行时设置；alarm 穿透 DND/夜间。
- 不启用网络 TTS/L2、不占 TTS 额度、不写 /data、无新线程。
- 真机：30s demo alarm 到点 `[voice_cache] hit ALARM`，本地播放 2.97s，无网络 TTS。
- 待验：真实 idle/sedentary/hr_alert 的 `[pet_care] local voice: queued` 与 DND/音乐 skip。
- 18:06 实测 demo alarm 到点本地 ALARM 缓存播放 2.97s，无网络 TTS（cron→voice 通道）；18:12 idle 因 IMU 噪声未触发，P2A helper 的真机 idle 语音待重测。
