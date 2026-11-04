# 31. 开发记录（2026-10-10 ~ 2026-11-03）

## 0. 本文说明

本文是**开发过程记录**，按时间线整理这一个月实际做了什么、改了哪些仓、怎么验证，
每一条都能对应到具体的提交或仓内文档。

它与 `logs/` 下的 **AI Coding 日志**不是一回事：

| 内容 | 位置 | 说明 |
|---|---|---|
| AI Coding 日志（人机对话 jsonl） | `logs/<github_login>/` | 由官方采集器自动导出，覆盖 2026-08-08 ~ 09-19 |
| 开发记录（本文） | `docs_ble/31_development_record_2026-10.md` | 2026-10-10 之后的工作，人工整理，逐条可追溯 |

之所以要单独整理这一段：10 月起开发换用了不在官方采集器支持列表内的 AI 工具，
`logs/` 无法继续产生合规的 jsonl，但开发本身没有中断，记录在这里补齐。

## 1. 时间线

### 1.1 音频链路打通（10-10 ~ 10-11）

| 事项 | 证据 |
|---|---|
| 板载麦克风真机验证，确认采集通路 | `docs_ble/28_audio_mic_verification.md` |
| 播放链路寄存器级验证（AUDPRC → AUDCODEC DAC → PA10） | `docs_ble/29_v3_design.md` |
| 新增 `audio_beep` 扬声器验证程序 | `16f23b9`、`e40fada` |
| `media_player_*` 实现在 `/dev/audio/pcm0p`（`stubs.c` 拆出） | `3e8232c`（ai_agent） |
| 开机自注册板载音频设备（pcm0c/pcm0p） | `98c88bb`（ai_agent） |
| 修 `getcaps` 采样率字段截断 | `a374b58` |

结果：播放与录音两条通路在真机上都可听/可采，音频缺陷汇总进 `25_flash_reliability_and_audio_defects.md`。

### 1.2 心率（MAX30102）从"出不了数"到稳定出 BPM（10-11 ~ 10-13）

这是一段典型的"多层根因"排查，共 20 余个提交：

| 层次 | 关键改动 | 提交 |
|---|---|---|
| 总线 | MAX30102 走独立 I2C2（PA41/PA42），修 `I2C_M_READ` 缺失 | `ad29073` |
| 总线恢复 | defconfig 打开 `CONFIG_I2C_RESET=y`，接上已实现的总线复位 | `774448c`、`08b0d39`(sifli) |
| 读路径 | FIFO 改按 `INT_STATUS` 门控、每轮固定 N=5；逐字节读兜底 | `e34cdec`、`70fd2d5`、`3507747` |
| 溢出 | 溢出分支接上恢复，去掉"清零后整轮 return" | `62baf79`、`81eab8f` |
| 采样 | 轮询 10ms → 50ms，单轮上限对齐 FIFO 容量 | `dcd0d4a` |
| 算法 | 阈值/复位两处根因修正，真正出 bpm | `e3edfd1` |
| 算法 | 杂峰归并（丢峰不丢间隔）+ 门限/容差/窗口/限速 | `746dc04` |
| 取证 | 逐操作记账、周期性诊断行、selftest | `7a3940f`、`1a24545`、`80f3b51` |
| 生命周期 | 修 stop 无效（`main()` 的 memset 加守卫、SHDN/close 移到采集线程退出路径） | `482c6af` |
| 多轮测量 | 每轮强制重新初始化，修"只有开机第一次能出数" | `9e5e730` |

真机验收数据与 7 条根因汇总在 `docs_ble/24_open_issues.md`（§24 全天总报告）。

### 1.3 语音链路：从"能说话"到"能对话"（10-11 ~ 10-14）

| 阶段 | 改动 | 提交 |
|---|---|---|
| 打通 | speak 命令（agent CLI）+ 异步说话，修 UI 卡死 | `d94d631` |
| TTS 后端 | Edge TTS 兜底；再增 MiniMax T2A v2，形成 edge → minimax → volc 回退链 | `dde3e0a`、`23eac9c`、`cdf5c4a` |
| 只念最终回答 | 过滤中间态，单 worker 有界队列，抗截断重试 | `fa74f7f`、`a6b5e76` |
| 上行编码 | 改用 Ogg Opus，上传体积降 15.5 倍，端到端 57s → 8s | `217eb5d`、`cae599c` |
| 实时采集 | 修"实时采集从未工作"（弱符号遮蔽），边录边编码 | `fa5e9d8` |
| VAD | 流式 VAD，只把有语音的音频送去识别（裁掉约 70%） | `217eb5d` |
| 交互 | 自动断句（说完自动收尾）+ 物理按键 PTT（KEY2） | `cae599c`、`a5d4289` |

验收：已知句子识别一字不差；端到端 8s 内完成一轮问答（见 `24_open_issues.md` §29/§30）。

### 1.4 稳定性：看门狗与挂死取证（10-13 ~ 10-14）

| 改动 | 提交 |
|---|---|
| 新增冻屏/死锁取证看门狗 | (ai_agent) 10-13 |
| 挂上硬件看门狗 `/dev/watchdog0`（20s） | (ai_agent) 10-13 |
| 修开机阶段被自己看门狗误复位（约 20s 一次启动循环） | (ai_agent) 10-14 |

### 1.5 蓝牙：BR/EDR 与 PAN（10-12 ~ 10-21）

| 仓 | 改动 | 提交 |
|---|---|---|
| `external_zblue` | `br_key.bin` 原子写 + CRC16（兼容旧格式），掉电不丢 bond | `573ca8f`、`4ea8268`、`df8e29b` |
| `external_zblue` | `Link_Key_Request` 早退分支查 key pool；`bt_keys_*` 补 `hdev->keys` 判空 | `df8e29b`、`56fcd49` |
| `external_zblue` | HCI 命令超时不再断言自杀（修"蓝牙共享开着也连不上"） | (zblue) 10-14 |
| `vendor_sifli` | ACL 信用 4→8 试过再回退（控制器虚报能力导致 TTS 零交付） | `2f6504a`、`1d25be7` |
| `frameworks_bluetooth` | 修适配器列表从 HCI/uv 线程读取导致的挂死（bt-hang-fix） | `36cc945` |
| `frameworks_bluetooth` | PAN/BNEP 服务层：编解码、连接生命周期、worker 引用、竞态 | 10-21 共 9 个提交 |

### 1.6 PR591 服务层归档（10-24）

把 PAN 服务层的修复整理成可提交上游的补丁集，归档到 `docs_ble/patches/pr591_service/`，
并记录服务边界与评审要点。对应上游 PR：`open-vela/frameworks_bluetooth#594`、`#596`。

### 1.7 界面与交付打磨（11-01 ~ 11-03）

| 事项 | 证据 |
|---|---|
| 桌宠页、设置页、导航、中文大字体（24px） | ai_agent `src/ui/` 70 个文件 |
| 语音播报入口与本地大模型对接 | `cf8a8b6` |
| 板级 defconfig、audio/sensor 集成、设计资源 | `2e076e2` |
| 决赛材料整理与提交 | PR `open-vela/contest2026_181_womenshayebuhuidui#5` |

## 2. 涉及的仓库与提交量（2026-10-01 ~ 11-03）

| 仓库 | 提交数 | 主要内容 |
|---|---|---|
| `contest2026_181_womenshayebuhuidui` | 91 | 板级配置、心率、语音入口、文档 |
| `packages_ai_agent` | 33 | 语音链路、UI、健康、看门狗 |
| `frameworks/connectivity/bluetooth` | 10 | PAN/BNEP 服务层与生命周期 |
| `external/zblue/zblue` | 6 | BR/EDR link key、HCI 超时 |
| `vendor/sifli` | 3 | ACL 信用、I2C 总线复位 |

## 3. 验证方式

- 全部结论以**真机**为准，不以"编译通过"作为完成判据
- 关键路径必须可观测：串口 syslog 落盘、逐操作记账、周期性诊断行
- 音频/心率/语音均有量化数据（削波率、BPM 稳定性、端到端耗时）
- 每个缺陷保留"现象 → 排除项 → 根因 → 修复 → 复验"的证据链，记录在 `docs_ble/`

## 4. 遗留问题

1. 弱网下实时语音的稳定性（丢包重传与超时策略）尚未系统验证
2. PAN 在部分手机型号上的兼容性只覆盖了少数机型
3. 心率在运动伪影下的准确度未做对照实验
4. 内存余量偏紧（见 `docs_ble/26_memory_budget.md`），后续功能需先算账
