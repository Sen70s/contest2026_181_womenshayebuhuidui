# 设备验收测试清单

> **一键自检**（推荐先跑这个）：
> ```bash
> python3 docs_ble/tools/precheck.py
> # 云端配置若丢失，可自动补：
> STEPFUN_KEY=xxx python3 docs_ble/tools/precheck.py
> ```
> 它会依次检查控制台、bt-pan IP、公网/DNS、云端 backend、关怀调度、自定义 Skill，
> 最后直接打印「可以开拍」或「还需处理」（key 只从环境变量或 `~/.stepfun_key` 读，不写进仓库）。

> 用途：确认一块烧好固件的板子是否**可正常使用**。分「机器可自动判定」与「需要人看一眼」两类。
> 全部命令都在串口 console 里执行；`nsh>` 是 NSH 提示符，`vela>` 是 agent 自己的 CLI（两者抢同一个 console，见文末「已知坑」）。

## 一、上电与基础（约 30 秒）

| # | 操作 | 通过标准 |
|---|------|---------|
| 1 | 给板子上电（手机先开好蓝牙网络共享） | 串口出现启动日志 |
| 2 | 等 10 秒，敲回车 | 出现 `nsh>` 提示符 |
| 3 | `uname -a` | 打印 `NuttX ... <构建日期> arm ai_agent` |
| 4 | `echo OK` | 回显 `OK`（控制台可读写） |

**判读**：若 10 秒后既无日志也无提示符 → 见文末「串口静默」处理。

## 二、联网（约 20 秒，最关键的自动判据）

| # | 操作 | 通过标准 |
|---|------|---------|
| 5 | 观察启动日志 | 出现 `[pan] state=dhcp_ok dev=bt-pan ip=192.168.44.x` |
| 6 | `ifconfig` | `bt-pan` 为 `RUNNING` 且有 `192.168.44.x` |
| 7 | `ping -c 3 223.5.5.5` | `0% packet loss`（公网可达） |
| 8 | `ping -c 2 www.baidu.com` | `0% packet loss`（DNS 域名解析正常） |

**判读**：
- 有 IP 但 ping 不通 → 手机热点**上行**没通（手机侧换网络/重开共享），不是板子问题。
- 日志里 `[pan] state=connected` 之后立刻 `disconnected` → 手机侧共享没开或密钥不一致。
- 若长时间无任何 PAN 日志 → 自动重连在连续失败到上限后停止重试，**冷启动板子**（不要反复等）。

## 三、云端 LLM 与对话（约 30 秒）

| # | 操作 | 通过标准 |
|---|------|---------|
| 9 | 读配置：`ping -c 8 223.5.5.5` 占住 NSH 后立刻发 `router_status` | `"backend_count": 1` 且有 `api.stepfun.com` |
| 10 | `ask 你好` | 日志出现 `Handshake OK` + `backend=0`，屏幕上给出回答 |
| 11 | 若第 10 步走了端侧兜底（`local_lm answered locally`） | 说明云端不可达：先查网络（第二步），再查是否进了退避（等 60 秒或重发） |

**配置云端 LLM**（仅需一次，配置持久化）：

```
nsh> ping -c 12 223.5.5.5        # 先占住 NSH，让 vela> CLI 能收到输入
set_llm https://api.stepfun.com/v1/chat/completions step-3.7-flash <你的APIKey>
```

成功标志：`LLM backend: api.stepfun.com:443/v1/chat/completions (model: step-3.7-flash)`、`API key saved.`

## 四、主动能力 ×3（赛题核心，约 8 分钟）

| 场景 | 操作 | 通过标准 |
|------|------|---------|
| ① 闹钟叫醒 | `ask 2分钟后提醒我喝水` | 依次出现三条 `[pet_care] fire`：`emo=4`（预告）→ `emo=5`（叫醒）→ `emo=6`（无反应升级）；期间屏幕上先预告后叫醒 |
| ② 心率告警 | `hr_set event` | 30 秒内出现 `[pet_care] hr alert <bpm>` 且 bpm>120，屏幕出现担心表情与提醒气泡 |
| ③ 空闲关怀 | **什么都不做，静置 5 分钟** | 出现 `[pet_care] fire emo=6 ...`（按时段选模板），随后 `Processing message from care:pet_care`（模型追问） |

**实测记录（2026-09-16，三项连续跑通）**：

```
① 空闲关怀  冷启动后 313 s → fire emo=6「我在呢，想聊点什么吗？」+ care:pet_care 追问
② 闹钟三段  iter=0 tool=set_alarm → alarm set 2min → emo=4(106s) / emo=5(166s) / emo=6(286s)
③ 心率告警  hr_set event 后 2 s → fire emo=6「心跳有点快…」+ hr alert 124 bpm
```

**判读**：
- **测空闲关怀前先冷启动板子**。第一次实测失败的原因就是残留 RAM 状态：仍有闹钟 pending（抑制空闲关怀）＋上一次关怀的 10 分钟冷却未过。这两项都是 RAM 状态，`RESET`（软复位）清不掉，烧录/上电才清。
- 顺序建议：**先测空闲关怀**（要求 10 分钟冷却 + 5 分钟无活动），再测闹钟与心率——后两者的触发会把冷却重新计时。
- 场景① 需要一个干净的起算点：先按一次实体键复位空闲计时，再设闹钟。
- 场景③ **期间不要碰屏幕、不要按键**；桌面空白区点击不计入活动，但按键会计入。
- 22:00–07:00 是静默期，空闲关怀不触发（闹钟仍会响）。

## 五、自定义 Skill（约 40 秒）

| # | 操作 | 通过标准 |
|---|------|---------|
| 12 | `ls /data/ai_agent/skills` | 列出 `care-reminder.md` 等技能文件 |
| 13 | `ask 35分钟后提醒我吃药` | 日志出现 `tool=set_alarm` → `[pet_care] alarm set 35min` → `[tool_alarm] alarm armed for 35 min (cloud tool)`；回复是一句自然语言确认 |

**判读**：若日志显示 `tool=cron_add`，说明模型走了内置 reminder 的静默通知路径——此时**提醒不会主动叫醒**，属于 Skill 未被采纳（换措辞重试，或确认固件里 `care-reminder.md` 是当前版本）。

## 六、人机交互（需要人看/按，约 2 分钟）

| # | 操作 | 通过标准 |
|---|------|---------|
| 14 | 看桌面 | 显示日期与蓝牙网络状态行（联网后显示 IP；未联网显示「蓝牙未连接，请开启手机网络共享」） |
| 15 | 按 **KEY1** | 打开桌宠页 |
| 16 | 按 **KEY2** | 桌宠页文字层出现一条演示问句并在数秒内给出回答 |
| 17 | 触摸屏幕（桌宠页云朵 / 历史按钮 / 返回） | 有响应；点云朵会得到一句俏皮回复 |
| 18 | 闹钟响时碰一下设备 | 日志出现 `alarm acked by activity`，闹钟停止升级 |

## 七、可选：可靠性回归（各几分钟）

| 项 | 做法 | 通过标准 |
|---|------|---------|
| 掉电数据安全（P0 回归） | 写入过程中硬复位 5 次 | 每次都能 `littlefs mounted on /data (persistent)`，0 assert |
| 长稳 | `docs_ble/tools/pan_soak.py`（60 轮） | 0 assert、0 断链，堆用量首尾持平 |
| 换手机 | 见 `docs_ble/24` 与 LOG Round 26 的「零写入换机流程」 | 新机连接成功后 `last_nap` 自动切换，重启自动连新机 |

---

## 已知坑（判读前先读，避免误判）

1. **`RESET` 是软复位**：RAM 与 `CLOCK_MONOTONIC` 都保留，冷却计时/闹钟状态会跨复位存活。要测「新功能」请用**上电重启或重新烧录**。
2. **串口静默**：若连自发日志都没有，先按一次 `RESET` 冷启动；仍无输出就拔插 USB。不要反复发命令。
3. **`vela>` CLI 与 NSH 抢 console**：NSH 优先级更高，直接发 CLI 命令（`set_llm`/`router_status`）通常收不到。**先发一条耗时 NSH 命令占住它**（如 `ping -c 12`）再发。
4. **NSH 行长 256**：超长命令会被截断成两段执行，表现为 `xxx: command not found`。
5. **云端依赖手机热点稳定**：热点抖动会让云端调用失败并自动兜底端侧模型（这是设计行为），界面仍可用。
6. **路由器退避**：连续失败 3 次会跳过该后端 60 秒（`No available backend`）。
7. **蓝牙设备名**：宿主侧可改，手机扫描看到的仍是框架默认名 `Agent-Watch-cd:ab:78:56:34:12`（平台层限制，见 `docs_ble/24.7`）。
