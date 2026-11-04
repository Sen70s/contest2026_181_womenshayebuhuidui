# docs_ble — 蓝牙联网技术记录

> 板卡：SF32LB52-DevKit-LCD ｜ 目标：**让手表通过手机的「蓝牙网络共享」拿到真正的 IP 地址**（BNEP/PAN over BR/EDR + DHCP），
> 而不是让手机 App 做私有协议代理。

## 结论速览（2026-10-14 更新）

| 问题 | 结论 |
|------|------|
| PAN（BNEP over BR/EDR）能否用？ | ✅ **已打通并在真机回归**：修复三个平台移植层缺陷后，冷启动无人干预即联网（`dhcp_ok`），公网与 DNS 域名 ping 均 0% 丢包，60.6 分钟 / 60 轮长稳 0 assert 0 断链。完整证据链见 **[21](21_pan_breakthrough_authoritative.md)** |
| 联网的前提条件是什么？ | 三项公共仓缺陷修复（net_buf 池注册、HCPU→LCPU 邮箱 ring、PAN/BNEP 实现），索引见 **[23](23_upstream_contributions.md)** |
| 后续换设备/掉线怎么运维？ | 见 **[22](22_pan_engineering_guide.md)** 的四条硬规则与「症状→先查什么」对照表 |
| 还有哪些已知限制？ | 见 **[24](24_open_issues.md)**（含已修的 P0、蓝牙地址硬编码、设备名等 P1/P2） |
| 重烧固件老失败怎么办？ | 见 **[25](25_flash_reliability_and_audio_defects.md)**：烧录窗口只有上电后约 2000 ms，`Failed to download stub` 的确定性判据与恢复流程 |
| 板载麦克风能用吗？ | ✅ **能用（2026-10-14 更正；下面 2026-10-11 的"半通"结论已被推翻，保留作证据链）**：声音可辨 —— 静音 avg≈300，喊话 **peak=19036 / avg=933**（10× 响应）。原因是 ADC 增益链只给了 +12 dB，提到 **+30 dB** 即可。识别链路已接 **MiniMax**（同一条 key，不需要火山账号）并真机识别出中文，见 [30 §26.2–26.3](30_status_report.md)。<br>—— 以下为 2026-10-11 的历史记录：⚠️ **半通**：驱动/框架侧已修好 —— 漏调 `HAL_TURN_ON_PLL()` 导致的 `0x0000`/`0xFFFF` 常量已消除（真实数据 19% → 99%），v3 之后 **30 轮 start/stop 无挂死、无断言、无泄漏**。**真机拍手/说话和两次 1 kHz 播放音测试均未见可辨识响应**：受控拍手/说话采集逐秒 RMS 约 12.8~13.1、peak 约 43~62；两次 15 秒音调采集中均未出现明确的 1 kHz 窄带增强；第二次有开始/停止口令但无样本时间戳同步（`mic_speech_run.log` / `mic_knock_run.log` / `mic_tone_run.log` / `mic_tone_timed_run.log`）。这不能据此断言麦克风硬件坏或输入浮空；活动录音时 MICBIAS/REFGEN enable 位读回为 1，但实际偏置电压、MEMS 输出以及模块内部模拟 ADC 映射仍待外测/厂家资料确认。详见 [29 §8.5–8.8](29_v3_design.md)。**注意：原「✅ 音质已解决」只验证了「无人说话时基线不恒为常数」，从未验证「有声音时电平会变」。** 嫌疑与下一步见 **[29](29_v3_design.md) §8.5~8.7**；`nxrecorder` 用法见 **[25](25_flash_reliability_and_audio_defects.md)** 第三节 |
| 心率和语音能用吗？ | ✅ 能用（2026-10-14）：本地 MAX30102「测心率」按钮 → 出数 → **语音播报**（实测 85 / 94 / 136 bpm）；识别已从火山换成 **MiniMax**，真机识别出中文，且"听到→理解→说话"整条闭环打通。见 [30 §26](30_status_report.md) |
| 怎么跟手表说话？ | ✅ **按一下、说一句，完事**（2026-10-14）：物理按键 **KEY2 短按**开始录音，**VAD 听到你说完自动收尾**（1.2 秒静音），全程零操作 —— 真机实测说完到出文字 **5 秒**。KEY2 **长按 1.5 秒**仍是原来的"问模型"演示。见 [30 §30](30_status_report.md) |
| 实时语音通了吗？ | ✅ **通了**（2026-10-14）：麦克风 → 实时采集 → **边录边 Opus 编码** → Ogg → MiniMax → 中文文字，真机识别出屋里真实说话内容。过程中还挖出一个更根本的问题：**实时采集此前从未工作过** —— 固件里 `media_recorder_*` 全是 **weak 桩**（真实现没编进来），direct ALSA 那条路的开关也没开。已新增 `pcm_media_recorder.c` 用强符号直驱 `/dev/audio/pcm0c`。见 [30 §28](30_status_report.md)。**准确率已实测对照**（§28.11）：说话人说「明天北京天气怎么样」，识别结果**一字不差** |
| 只送语音、不送噪声？ | ✅ **已做**（2026-10-14）：加了**流式 VAD**，只把有语音的音频送去识别 —— 真机 18.3 秒录音只放行 **5.7 秒（裁掉 70%）**，说「小云手表，播放一首歌」识别结果**逐字相同**；全程静音的 105 秒则一帧不放行、直接跳过上传。另用 pcm-audio 技能把音频质量量化了：削波 **0.00%**、SNR **29.2 dB**（其中"802 个爆破音"经复核是**误报**——是语音本身的高频斜率，不是缓冲区缺陷）。见 [30 §29](30_status_report.md) |
| 语音有多快？ | ✅ **快约 7 倍**（2026-10-14）：上传前先编成 **Opus 16kbps + Ogg 容器**，真机同一文件 131072 → **8471 字节（15.5×）**，整轮（含编码）**57 秒 → 8 秒**。依据：蓝牙 PAN 上行的**结构性上限约 12KB/s**（ACL 在飞窗口 × 分片 × RTT），而 16kHz/16bit 原始 PCM 要 32KB/s —— 不压缩结构上不可能。踩过的坑：MiniMax **拒收无容器的裸 Opus 码流**；`external/opus` 此前被整库以 `-O0` 编译（补 `-O2` 后快 3 倍）。见 [30 §27](30_status_report.md) |
| 整机挂死修好了吗？ | ⚠️ **未根除**：已定性为「开机后 1~3s 蓝牙 PAN 建链期间**整机停摆**」——10 次冷启动复现 3 次，且**优先级 240 的看门狗线程也一行打不出** ⇒ 不是优先级饿死；已加 **20s 硬件看门狗自愈兜底**（不用再拔插 USB）。另外修掉一个真根因：控制器 HCI 超时会让 `bluetoothd` 自杀（见 [30 §25.3 / §26.4](30_status_report.md)） |
| 内存够加心率/喇叭吗？ | ✅ 够。SRAM 紧（82.4%）但 PSRAM 8 MB 空 90%；加心率+扬声器合计 < 10 KB SRAM，**且云端 `get_heartrate` 工具与火山 TTS 代码都已现成**——见 **[26](26_memory_budget.md)** |

> **2026-10-14 更正**：本页 2026-10-11 写的「板载麦克风半通、对声音无响应」**已被推翻** ——
> 那是 ADC 增益链的问题（`HAL_AUDCODEC_Config_ADCPath_Volume` 只给了 +12 dB），
> 提到 **+30 dB** 后喊话 peak 从约 43 升到 **19036**（10×）。驱动与硬件都是好的。
> 同时「音频子系统没进出货固件」也已解决（`.config` 现为 `CONFIG_AUDIO=y`）。
> 详见 [30 §26.2–26.3](30_status_report.md)。

> 早期版本的本页曾写下「PAN 不可用」的结论（2026-08-14 的代码级判断）。**该结论已被推翻**：
> 当时的判断基于「框架无 PAN 选项、zblue 无 BNEP、panu_service 未被编入构建」，
> 后续我们补齐了 BNEP 编解码与 PAN SAL，并修复了三个平台缺陷，PAN 现已可用（见 21 号文）。

## 文档索引

- **[21_pan_breakthrough_authoritative.md](21_pan_breakthrough_authoritative.md)** — PAN 上网打通的权威复盘：三个根因的完整证据链 + 验证结果
- **[22_pan_engineering_guide.md](22_pan_engineering_guide.md)** — 工程指南：四条硬规则、症状→先查什么、验证口径
- **[23_upstream_contributions.md](23_upstream_contributions.md)** — 公共仓贡献索引（按大赛规则走 PR 的三项平台修复）
- **[24_open_issues.md](24_open_issues.md)** — 遗留问题清单（P0 已修 / P1 / P2，含动手入口）
- **[25_flash_reliability_and_audio_defects.md](25_flash_reliability_and_audio_defects.md)** — 烧录失败签名对照表与恢复流程 + 音频驱动两处缺陷（apb 双重释放、getcaps 采样率字段截断）
- **[26_memory_budget.md](26_memory_budget.md)** — 内存账（SRAM/PSRAM/Flash 分布、库级与目标文件级明细、挪移评估）+ 云端心率/语音链路已现成的核查结论
- **[27_audio_driver_review.md](27_audio_driver_review.md)** — **音频驱动审查归档**：四轮 `driver-code-reviewer` 双轮审查的量化结论 + 两份裁判报告全文（9 → 43 → 44 分），含 v3 修复方向
- **[28_audio_mic_verification.md](28_audio_mic_verification.md)** — **板载麦克风真机验证记录**：烧录正解、`nxrecorder` 正确用法、`getcaps` 返回 `ac_len` 缺陷、DR-F001 真机回溯、PCM 质量分析（`pcm-audio`）
- **[29_v3_design.md](29_v3_design.md)** - **v3 design (handoff)**: apb ownership model (the missing apb_reference is the hang root cause), four changes, acceptance criteria, and the flashing/serial/analysis traps
- **[LOG.md](LOG.md)** — 逐轮调试日志（Round 1–29，含主动能力验收与两个真缺陷的根因）
- **[tools/](tools/)** — 真机验证脚本（PAN bring-up、长稳、抓包判定、NSH 执行器、演示前自检）

## 验证环境

- 工作区：`/home/aila/projects/vela_contest`
- 固件构建：`out/openvela_contest2026_181_board_ai_agent`（ninja）
- 演示前自检：`python3 docs_ble/tools/precheck.py`
- 验收测试清单：`docs/test_runbook.md`
