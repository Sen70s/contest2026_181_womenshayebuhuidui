# 「小云」——会主动关心你的 AI 陪伴手表

> 2026 首届 openvela AI 硬件开发者大赛 · Team 181 · 最后更新 2026-09-16

## 一、作品简介

**在没有 WiFi 模组的穿戴级硬件上，做一个能联网、能对话、并且会主动关心你的 AI 智能体手表。**

SF32LB52 这块板子只有蓝牙、没有 WiFi。常规做法是让手机 App 做一层私有协议代理，设备端只能访问被代理商定好的接口。我们走的是另一条路：**让手表通过手机的「蓝牙网络共享」拿到一个真正的 IP 地址**（BNEP/PAN over BR/EDR + DHCP），于是设备端拿到的是标准 socket 能力——ping、DNS、HTTP、任何云端 API 都能直接用，不需要手机侧配合做协议转发。

这条路在本项目开始时是不通的：openvela 的蓝牙框架里 PANU profile 从未被编入构建，zblue 也没有 BNEP 实现。我们补齐了 BNEP 编解码与 PAN SAL，并在过程中修掉了三个平台移植层缺陷（见第七节）。

在此基础上，我们把「主动」做成产品的核心：手表按**时间**（闹钟叫醒）、按**阈值**（心率告警）、按**空闲状态**（主动关怀）主动开口，而不是等你问它。

## 二、选题方向

**AI 硬件产品创新**。

理由：赛题要求基于 ai_agent 框架做出「主动+执行」的嵌入式应用，而不是又一个聊天机器人。我们的选择是把它做成**可穿戴陪伴设备**——把「联网能力」（蓝牙 PAN 拿到 IP）与「主动能力」（三场景 + 自定义 Skill）合在一台手表上，并用端云协同把云端理解力接到设备端动作上。

## 三、目录结构

```
app/ai_agent/            AI 智能体应用
  ├── ai_lm.cxx          端侧语言模型（TFLite Micro）与端侧工具执行
  ├── pet_care.c/.h      关怀调度器（主动能力核心：空闲关怀 / 闹钟 / 心率告警）
  ├── health/            心率采集（MAX30102）、算法与阈值判定
  ├── skills/            ★ 运行时 Skill：care-reminder.md（烧录后进 /data/agent/skills/）
  ├── speech/            语音播报入口与语音通道
  ├── model/             模型与 tokenizer 数据
  └── ui/                LVGL 页面（launcher / 桌宠 / 设置 / 历史对话 …）
app/lumina_watch/        手机端配套 App（Android/Kotlin，不参与固件构建）
board/contest_board/     板级 defconfig（正式版 ai_agent / 调试版 ai_agent_pandbg）
skills/                  ★ 开发期沉淀的 Skill（nuttx-build-forensics / vela-contest-feature /
                            storage-write-stability）
docs/                    交付手册与开发流程文档
docs_ble/                完整技术记录（21–24 号、25–32 号、104–115 号、device_tech/、LOG.md）
logs/Sen70s/             AI Coding 日志（16 个会话，2026-08-08 ~ 09-19）
tools/lumina_preview/    UI 页面/状态预览与自动校验工具
```

`docs_ble/` 是技术含量最集中的部分，建议评委从这里看：

| 文档 | 内容 |
|------|------|
| `21_pan_breakthrough_authoritative.md` | PAN 打通的权威复盘：三个根因的完整证据链 |
| `22_pan_engineering_guide.md` | 工程指南：四条平台硬规则、症状→排查对照表 |
| `23_upstream_contributions.md` | 公共仓贡献索引（三项平台修复） |
| `24_open_issues.md` | 遗留问题清单 |
| `LOG.md` | 逐轮调试日志（Round 1–28，含主动能力验收与两个真 bug 的根因） |
| `tools/` | 真机验证脚本（长稳、抓包判定、NSH 执行器…） |

## 四、运行方式

### 1. 拉取工程

```bash
repo init -u https://github.com/open-vela/contest2026_181_womenshayebuhuidui \
  -b dev-ai-contest-2026 -m contest2026_181_womenshayebuhuidui.xml
repo sync -c -j8
```

> ⚠️ 本作品依赖三项公共仓修复，目前以 PR 形式等待合入（见第七节）。合入前需在对应公共仓 checkout 相应分支，PAN 上网才能复现。

### 2. 编译

```bash
export PATH="$PWD/prebuilts/gcc/linux-x86_64/arm-none-eabi/bin:\
$PWD/prebuilts/build-tools/linux-x86_64/bin:\
$PWD/prebuilts/kconfig-frontends/bin:$PATH"

prebuilts/tools/cmake/bin/cmake -B out/openvela_contest2026_181_board_ai_agent -GNinja \
    -DBOARD_CONFIG=../contest2026_181_womenshayebuhuidui/board/contest_board/configs/ai_agent \
    -DEXTRA_FLAGS='-Wno-cpp -Wno-deprecated-declarations -Wno-error -Wno-strict-prototypes -Wno-undef' \
    nuttx
cd out/openvela_contest2026_181_board_ai_agent && ninja resetconfig && ninja -j8
```

产物：`out/openvela_contest2026_181_board_ai_agent/nuttx.bin`

> 不要跑 `savedefconfig`（会抹掉 defconfig 注释）；改配置直接编辑 defconfig 再 `ninja resetconfig`。

### 3. 烧录

板子的 RTS 接在电源控制上，一键脚本会处理复位时序：

```bash
./build_and_flash.sh flash --port /dev/ttyACM0 \
    --image out/openvela_contest2026_181_board_ai_agent/nuttx.bin \
    --before default_reset --after soft_reset
```

> 若报 `Failed to download stub`，是没抢到 ROM bootloader 窗口，直接重试 1–2 次。

### 4. 联网（手机侧只需开一次）

手机打开「蓝牙网络共享」，给板子上电，**不需要任何控制台操作**，10 秒内应看到：

```
[pan] state=adapter-on-auto-connect
[pan] BNEP setup OK, tx_mtu=1691
[pan] state=dhcp_ok dev=bt-pan ip=192.168.44.x
[netmgr] Active channel: bt-pan (primary)
```

验证：

```bash
nsh> ifconfig              # bt-pan 应有 192.168.44.x
nsh> ping -c 3 223.5.5.5
nsh> ping -c 3 www.baidu.com
```

### 5. 配置云端 LLM（在 agent 的 `vela>` CLI 里执行）

`vela>` CLI 与 NSH 共用同一个 console，而 NSH 优先级更高会抢走输入。**先发一条耗时命令占住 NSH，再发 CLI 命令**：

```
nsh> ping -c 12 223.5.5.5        # 占住 NSH 约 12 秒
set_llm https://api.stepfun.com/v1/chat/completions step-3.7-flash <你的APIKey>
```

成功会打印 `LLM backend: api.stepfun.com:443/v1/chat/completions (model: step-3.7-flash)`。配置持久化在 `/data/ai_agent/config/config.json`，重启保留。

### 6. 和它对话

```bash
nsh> ask 你好                       # 云端 LLM 回答（不可用时自动兜底端侧模型）
nsh> ask 10分钟后提醒我喝水          # 自定义 Skill 驱动：云端理解 → 设备端自主叫醒
```

### 7. 主动能力怎么演示

| 场景 | 操作 | 现象 |
|------|------|------|
| ① 闹钟叫醒 | `ask 2分钟后提醒我喝水` | 到点前 1 分钟先预告 → 到点主动叫醒 → 2 分钟无反应升级催促；期间碰一下设备即视为已读并撤销闹钟 |
| ② 心率告警 | NSH：`hr_set event` | 30 秒内心率爬过 120 → 桌宠担心表情 + 提醒气泡；60 秒冷却防轰炸 |
| ③ 空闲关怀 | 什么都不做，静置 5 分钟 | 宠物主动开口（按时段选模板），并让模型补一句个性化追问 |

阈值与时段可在板级 defconfig 调整：`AI_AGENT_CARE_IDLE_MIN` / `AI_AGENT_CARE_COOLDOWN_MIN` / `AI_AGENT_CARE_QUIET_START|END` / `AI_AGENT_HR_HIGH` / `AI_AGENT_HR_ALERT_COOLDOWN_S`。

## 五、AI Coding 使用说明

本项目使用 Claude Code 等 AI 工具完成全流程开发：需求拆解、方案设计、编码、真机排障、文档。完整对话日志见 `logs/Sen70s/`。

> **日志覆盖范围（如实说明）**：`logs/` 内为 2026-08-08 ~ 09-19 的 16 个会话，由大赛官方采集器导出。
> 9 月下旬之后开发改用不在官方采集器支持列表内的 AI 工具，无法导出同格式日志；
> 该阶段的开发过程记录在 `docs_ble/31_development_record_2026-10.md`（逐条对应提交与文档）。

这条线上 AI 协作最有价值的不是写代码，而是**在一个没有调试器、只有串口日志的双核闭源平台上做根因定位**：

- **从症状反推机制**：`tailroom=249` 与任何配置都不匹配，靠读 zblue NuttX port 的 `pool_id()` 实现，发现 `__ASSERT` 在 release 下被编掉、静默返回 0，才定位到「net_buf 池没注册」这个根因——而不是继续调 MTU 配置。
- **区分因果**：`Unable to allocate buffer within timeout` 连刷看起来像 buffer 池不够，实际是 `Hardware_Error` 导致 LCPU 停回 NoCP、ACL 信用耗尽的**后果**。
- **把一次性调试沉淀成规则**：`docs_ble/22` 那四条硬规则与「症状→先查什么」对照表，从 14 轮调试里提炼，下一个人不用重走。
- **AI 也会误判，证据链比结论重要**：Round 27 我们把「闹钟不响」当成缺陷，实际是触发路径没打日志、串口里看不见；补日志后一次看清（顺带修掉两个真 bug：LVGL 跨线程竞态、1Hz 调度慢 5 倍）。返工过程同样记录在 `LOG.md`。

**沉淀的可复用开发 Skill（4 个）**：

| Skill | 位置 | 作用 |
|---|---|---|
| SF32LB52 蓝牙 PAN 调试 | `docs/dev_skill_bt_pan.md` | 四条平台硬规则 + 症状排查表 + 链路吞吐预算 |
| NuttX 构建/链接产物取证 | `skills/nuttx-build-forensics/` | 符号强弱 / 实际编译选项 / 崩溃符号化（三个脚本） |
| 小云手表功能开发纪律 | `skills/vela-contest-feature/` | 五条硬规则 + 19 类踩坑清单 + 本板硬件坑 |
| 存储写路径稳定性 | `skills/storage-write-stability/` | 周期性复位时用只读隔离法先证伪 |

**作品内的运行时 Skill（11 个内置）**：`app/ai_agent/skills/care-reminder.md` 等——烧录后即存在于 `/data/agent/skills/`，并支持文件式加载、`/api/skills` 热更新与 Agent 自创建。完整说明见 `docs_ble/32_skills.md`。

## 六、主动能力与自定义 Skill（赛题核心）

| 赛题要求 | 我们的实现 |
|---------|-----------|
| 定时主动 | 闹钟：T-60s 预告 → T0 叫醒（含天气占位语）→ 2 分钟无反应升级催促；活动即确认 |
| 阈值主动 | 心率超阈值（默认 120）→ 担心表情 + 提醒气泡 + 历史记录，单事件只报一次、60 秒冷却 |
| 上下文主动 | 空闲 5 分钟主动开口（按时段选模板，22:00–07:00 静默不打扰，10 分钟冷却） |
| 至少 1 个自定义 Skill | `care-reminder`：云端 LLM 判断用户在要「被叫醒」时调用 `set_alarm`，到点后的动作全部由设备端自主完成 |

**云端与端侧只有一份状态机**：云端工具 `set_alarm` 与端侧工具 `set_timer` 都指向同一个 `pet_care_alarm_set()`，因此云端可用、不可用（自动兜底端侧模型）两条路径的行为完全一致。

## 七、公共仓改动（按大赛规则走 fork + PR）

以下修复不在本仓，而是以 PR 形式提交到各公共仓的 `dev-ai-contest-2026` 分支：

| 仓 | PR | 修的是什么 |
|----|----|-----------|
| `external_zblue` | [#231](https://github.com/open-vela/external_zblue/pull/231) / [#234](https://github.com/open-vela/external_zblue/pull/234) / [#236](https://github.com/open-vela/external_zblue/pull/236) | net_buf 池未注册导致 `pool_id()` 静默返回 0；BR/EDR link key 原子写与 CRC；HCI 超时不再断言 |
| `vendor_sifli` | [#29](https://github.com/open-vela/vendor_sifli/pull/29) / [#40](https://github.com/open-vela/vendor_sifli/pull/40) | 邮箱 ring 分块写入、堆上界盖住邮箱；H4 加密事件归一化、MPI/flash 修复 |
| `frameworks_bluetooth` | [#591](https://github.com/open-vela/frameworks_bluetooth/pull/591) / [#594](https://github.com/open-vela/frameworks_bluetooth/pull/594) / [#596](https://github.com/open-vela/frameworks_bluetooth/pull/596) | PAN/BNEP over BR/EDR 实现、TX 池尺寸；连接生命周期与竞态修复 |
| `packages_ai_agent` | [#47](https://github.com/open-vela/packages_ai_agent/pull/47) | 语音链路（VAD/Opus/TTS 回退）、桌宠 UI、健康模块、主动陪伴路由与 TTS 模板缓存 |

细节见 `docs_ble/23_upstream_contributions.md`，根因证据链见 `docs_ble/21`。

## 八、完成度与已知限制

### 已完成并在真机验证

| 能力 | 状态 | 证据 |
|------|------|------|
| 蓝牙 PAN 上网（BNEP/PANU + DHCP） | ✅ | 冷启动无人干预即联网，`dhcp_ok ip=192.168.44.140`；公网/域名 ping 0% 丢包 |
| 开机自动重连 / 断链恢复 | ✅ | bond 与 last_nap 持久化，上电自动连回手机 |
| 长时间稳定性 | ✅ | 60.6 分钟 / 60 轮：0 assert、0 断链，堆用量首尾持平 |
| BR/EDR 配对（SSP） | ✅ | HyperOS 手机 JUST_WORKS 自动接受，link key 落盘 |
| 设备名（App 侧覆盖） | ⚠️ 部分 | 宿主侧名称可改（`bttool get name` → `小云手表`）；但**空口上报仍是框架默认名** `Agent-Watch-<MAC>`，手机扫描看到的不是产品名——平台层限制，见 `docs_ble/24.7` |
| LVGL 显示与 UI 页面 | ✅ | launcher / 桌宠 / 关于页；桌面显示 bt-pan 的 IP；桌宠页有历史对话窗口 |
| 触摸输入 | ✅ | FT6146；修掉了「武装边沿中断前没排空锁存 INT」导致触摸全程无响应的缺陷 |
| 云端 LLM 对话（StepFun） | ✅ | `api.stepfun.com` + `step-3.7-flash`：TLS 握手成功、约 8.6 秒返回，`backend=0` 走云端 |
| 端侧语言模型兜底（velaAI TFLM） | ✅ | 云端不可用时自动兜底，实测正确回答域内问题 |
| 主动能力 ×3 | ✅ | 三个场景均真机验证（闹钟三段、心率 121/143/138 bpm 告警、静置 5 分钟主动开口 + 模型追问） |
| 自定义 Skill `care-reminder` | ✅ | 设备上 `/data/ai_agent/skills/care-reminder.md`，配套 `set_alarm`/`cancel_alarm` 工具 |
| 网络对时（自实现 SNTP） | ✅ | 联网后自动对时，`timesync` 可手动重试 |
| 心率采集（MAX30102） | ✅ | 独立 I2C2 总线 + FIFO 门控；静息 30 s 内出稳定 BPM |
| 语音链路（VAD → ASR → LLM → TTS） | ✅ | 流式 VAD + PTT 按键 + 自动断句；Opus 上行把端到端从 57 s 降到 8 s |
| TTS 模板缓存 | ✅ | 4 条固定台词以 ADPCM 存入 flash，命中时本地播放，不占 TTS session、不写 `/data` |
| 主动陪伴决策 | ✅ | `care_router` 规则+评分输出 ACTION / SILENT / DEFER，决策日志可回溯 |
| 手机端配套 App | ✅ | `app/lumina_watch/`（Android/Kotlin，源码随仓提交） |

固件规模：flash 约 7.52 MB（74.95%），SRAM 427 KB（81.48%）。

### 未完成 / 已知限制（如实列出）

- **离线命令词识别**（本地 TFLite 关键词）未并入当前固件；语音交互走「按键说话 → 云端 ASR」
- **演示期 `/data` 只读**：为避开 NOR 写窗口导致的 IWDT 复位，演示版本关闭持久化（见 `docs_ble/104`）；读取正常，重启后心跳/步数/闹钟不保留
- **端云推送闭环**（HTTP 推送服务器 + 设备端 log_pusher）本轮未做，属赛后演进
- 端侧模型推理 17–47 s（瓶颈在每 token 从 XIP flash 读权重），仅作兜底
- 本机蓝牙地址是硬编码假值（多台设备相同），影响同场多机与 Device-Id，见 `docs_ble/24` P1
- **触摸活动判定范围**：空闲关怀的「活动」信号来自实体键与桌宠页交互，桌面空白区点击不计入
- **云端 LLM 依赖手机热点稳定**：实测手机上行抖动时 TCP 连接会失败（`MBEDTLS_ERR_NET_CONNECT_FAILED`）并自动兜底端侧模型
- PAN 在手机共享反复抖动、连续失败达上限后会停止重试，需冷启动恢复（`docs_ble/24`）
- `time()` 返回本地时间而非 UTC（接需要 UTC 时间戳签名的云 API 时要在调用点减 8 小时）

## 九、为什么这么做（技术要点）

- **单 slot PAN + 单 bt-pan 网卡**：设备同一时刻只连一台手机，避免多路径抢路由；换手机靠「连接成功即持久化目标」，而不是猜配对列表顺序。
- **关怀调度零常驻线程**：`pet_care_tick` 挂在 LVGL render 线程上，用**墙钟秒**门控做 1Hz 逻辑（曾按「每圈 5ms × 200 圈」数 tick，实测每圈约 25ms，导致逻辑慢 5 倍）。
- **LVGL 跨线程必须串行化**：本固件 LVGL 以 `LV_OS_NONE` 构建（无内部锁），agent / 按键线程直接调 `lv_async_call` 会与 render 线程的 `lv_timer_handler` 抢定时器链表，实测导致 render 线程永久睡死；已统一改为加锁投递队列（`lvgl_ui_post`），由 render 线程每圈落地。
- **可观测性优先**：验收路径上「用户可见但我不可见」的动作必须落 syslog，否则会把正常行为误读成缺陷（Round 27 的教训）。
