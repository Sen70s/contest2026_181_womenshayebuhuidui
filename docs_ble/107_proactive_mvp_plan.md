# 107. 异步主动陪伴 MVP 实现方案（P0，基于 v142c）

> 语音优化先挂起。本文件把《异步主动陪伴》方案落到当前固件的可执行 MVP；只做 P0，不改 /data 持久化（仍只读），不新增线程/静态大数组，保护刚稳定的 v142c。

## 1. 目标（P0）

用规则 + 可解释评分跑通：**感知 → 状态/特征快照 → Router 决策（ACTION/SILENT/DEFER）→ 现有 care 执行 → 决策日志**。

第一版不做：HMM/LightGBM/Bandit、云端 L2 每事件、复杂 Ticket 持久化、App 反馈闭环（P1/P2）。

## 2. 当前已有 vs 本次补

| 模块 | 已有 | P0 补什么 |
|---|---|---|
| Sensor | 心率静默测量、IMU 计步/静止、rest 三态、RTC、交互时间 | 统一 `care_features_t` 快照（只读聚合，不加驱动） |
| Feature | pet_care 内部零散标量 | 集中计算：hr/dev、steps delta、still、idle、dnd、quiet、last_interaction、quality |
| State | 无显式状态 | 规则状态 ACTIVE/RESTING/INACTIVE/ELEVATED/UNKNOWN + confidence |
| Router | 各场景各自 if | 统一评分 + 策略：ACTION/SILENT/DEFER；冷却、夜间、DND、每日预算、质量门槛 |
| Executor | care_fire/L1、care L2、cron | 复用，不新增线程；L2 默认不出声（防 TTS 并发 hardfault） |
| Feedback | 无 | 本 boot RAM 计数 + 串口决策日志（不落盘） |

## 3. 函数级落地

新增 `src/core/care_router.c/.h`（约 300 行，无静态大数组）：

```c
typedef struct {
    int hr_bpm; bool hr_ok; uint32_t hr_age_s; int hr_baseline_dev;
    uint32_t steps; uint32_t steps_delta_60s;
    uint32_t still_s; uint32_t idle_s;
    bool dnd; bool quiet; uint8_t hour;
    uint32_t last_interaction_s; bool in_call_or_busy;
} care_features_t;

typedef enum { CARE_ACTION, CARE_SILENT, CARE_DEFER } care_decision_t;
typedef struct {
    care_decision_t decision;
    int score; const char *type; const char *reason;
    int defer_s;
} care_eval_t;

void care_router_tick(void);          /* 1Hz，从 pet_care_tick 调一次 */
care_eval_t care_router_eval(void);
```

决策表（第一版权重）：
| 因素 | 取值 | 分 |
|---|---|---|
| ELEVATED（HR 相对基线 + 连续2次） | +3 | 质量不合格则 SILENT |
| INACTIVE（still>30min 且步数少） | +2 | 只允许 sedentary 场景 |
| 空闲 >5min 且非运动 | +1 | 近期刚交互则 SILENT |
| 夜间/免打扰 | 强制 SILENT（紧急仅记录） |
| 冷却/预算 | 超限 SILENT | 每 boot 最多 8–12 次，同类 10min 冷却 |

现有入口改造（小改，不重写）：
- `pet_care_tick` 1Hz：调用 `care_router_tick()`；用步数变化调用 `pet_care_note_activity()`（走路=活动，解决“只有触摸才算活动”）；
- `pet_care_hr_report`：先走 Router；夜间/DND/质量不足→只记录不出声；连续 2 次静默测量高心率才 ACTION；
- 空闲/久坐 `care_*_decide`：出动作前查 Router 预算/冷却/静默；
- `care_start_l2`：P0 默认不发云端 L2（或发但不进 TTS，防并发 hardfault）；
- 所有决策打 `[care_router] state=… score=… decision=… reason=… features=…`。

## 4. 安全/边界

- 不改 /data 写（v142c 只读）；不新增持久化；
- 不新增线程；Router 在现有 1Hz tick 内跑；
- 不增加静态大数组（features/eval 各几十字节；决策日志走 syslog）；
- 主动行为默认 **不出声**（只气泡/历史）；后续用 App 开关或固定缓存语音再开；
- 演示触发/真实触发分离：非演示构建编译开关关闭 demo；
- 心率用“最近一次”，不宣称实时；血氧禁用；
- 每日/每 boot 预算：先 8 次（记录日志可调）。

## 5. 验收

1. 串口出现 `[care_router]` 决策日志，含 features/score/reason；
2. DND/夜间手动开启后，主动气泡/语音为 0（日志 SILENT）；
3. 走路时不会触发“久坐/空闲”关怀；静止 30min 后可触发 1 次；
4. 同 boot 主动次数 ≤8；同类冷却 10min；
5. 24h soak：`SFBL/RSET=0`、`Assertion=0`、`tasks` 无异常增长、无 TTS/音频并发 hardfault；
6. 不改 /data 写：`grep 'NOR op begin: write/erase'` 在主动关怀期间为 0。

## 6. 后续（P1/P2）

- P1：care ticket/TTL（DEFER 用 cron/RAM 队列，不落盘）、反馈日志、App `evt=care`/`care_ack`；
- P2：个性化权重、手机代理、真实用户标注；
- 语音：等语音优化方案确定后，只对固定短句/缓存开启，不在 P0 引入 TTS 并发。
## 7. 真机验证结果（2026-11-04 12:00，v143）

- 固件 v143：7,451,920 B md5 `8e02194285d800f38e2cd33cd32224f6`（已烧录）。
- Router init：`budget=8 cooldown=600s min_gap=60s idle=300s still=1800s quiet=22-7 hr_high=120 hr_x=2`。
- DND 开：连续 5 分钟 `decision=SILENT reason=dnd`，期间 `[pet_care] fire` 0 次。
- DND 关：空闲 5min 后 `req type=idle decision=ACTION score=1 reason=idle_5min` → 气泡「休息一下眼睛吧」→ `action type=idle budget=1/8`；`l2 skipped (P0 default off)`。
- 下一分钟：`reason=min_gap` SILENT，说明最小间隔生效。
- 真机 up=546s，无 SFBL/RSET/Assertion；/data 仍只读（无 NOR 写）。
- 说明：P0 主动关怀只气泡/历史、不出声；这是刻意的（避免 TTS 与流式并发 hardfault）。
