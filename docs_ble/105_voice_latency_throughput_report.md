# 105. 语音/文本延迟与吞吐分析报告（v142c，2026-11-04）

> 先说明：本报告只做分析与测量方案，**没有执行任何代码改动/烧录**。数据来自当前 v142c（/data 只读）串口日志与 out/ble_align/.config。

## 0. 结论速览

1. **慢的主因不是文本流式，而是 ASR 上传 + LLM 首 token + TTS 生成/下载三段串行**：一次语音首声 12–30s，文本首字也要等 ASR+LLM TTFT。
2. **BT PAN 实测有效吞吐约 6–9KB/s**（不是手机热点/WiFi 带宽）；ASR WAV+base64 上传 32–43KB，TTS MP3 下载 8–56KB，都卡在这条链路上。
3. **当前 TTS 是同步 HTTP POST（MiMo `/v1/chat/completions`，非流式 MP3）**，不是 WSS 异步；WSS 本身不增加带宽，但异步+压缩编码可以把首声和实时性做好。
4. **SPP 与本次问题无关**：手表联网走 **PAN/BNEP**，不是 SPP 文件传输；`CONFIG_BLUETOOTH_SPP_MAX_CONNECTIONS=8` 不是瓶颈。真正瓶颈在 BT ACL/L2CAP/IOB 缓冲与链路吞吐。
5. **PCM16 流式 TTS 在这条链路上不可行**（24kHz 16bit ≈ 48KB/s，base64 后 ~64KB/s，而 PAN 只有 ~8KB/s）；必须用 **Opus/MP3 低码率 + 异步流式**。
6. **最高性价比**：ASR 上传/ TTS 下载改用 Opus（16–24kbps）→ 流量降 4–6 倍；LLM 按句切分，首句一出就开始 TTS，与后续生成并行。

## 1. 实测数据（v142c 串口）

### 1.1 端到端（典型一次语音问答）
| 阶段 | 实测 | 数据量 | 说明 |
|---|---|---|---|
| ASR（静音检测+上传+识别） | 6.3s（1.2s 语音） / **17.1s（2.0s 语音）** | wav 20–32KB，POST body 27–43KB | 含 TLS 握手 1–2s、VAD、base64 膨胀 33% |
| LLM 首 token | **8.2s**（区间 4–15s） | 请求~20KB，响应很小 | 云端 TTFT 为主，不是带宽 |
| LLM 完整回答 | 9.8s | 文本几十~几百字节 | 流式已开 |
| TTS 首块 | **1.7–8.4s**（常见 2–3s，长句 8.5s） | MP3 body 8–56KB | 同步 HTTP，生成+下载 |
| TTS 网络完成 | 2.0–9.7s | 同上 | 长句 55.7KB / 6.9s ≈ 8.1KB/s |
| 文本首字可见 | 约 ASR + LLM TTFT ≈ **14–19s** | 几百字节 | 文本渲染不是瓶颈 |
| **首声（总）** | 约 **12–30s**（差网络更久） | — | 三段串行，等待叠加 |

### 1.2 关键日志样本
```
[11:26:12] ASR finish: 6339ms            # 1.2s 语音
[11:26:22] first_token_ms=8203 stream_deltas=5
[11:31:28] [vela_tls] body=12294 reads=2 1800ms cl=12294 status=200
[11:31:28] [voice] TTS network done: 2365ms
[11:34:20] [mimo_asr] POST wav=32364 bytes (8000Hz, 2.0s speech), body=43321 bytes
[11:34:37] ASR finish: 17148ms
[11:35:11] [vela_tls] body=55719 reads=5 6868ms cl=55719 status=200
[11:35:12] [voice] TTS network done: 9705ms
```

## 2. 字节流/吞吐计算

### 2.1 实测吞吐
- TTS：12,294B / 1.80s = **6.8KB/s**；14,023B / 1.74s = 8.1KB/s；55,719B / 6.87s = **8.1KB/s**；
- ASR：43,321B 请求 + 26,681B 响应 ≈ 70KB；即使按 8KB/s 也要 8.8s，实测 17.1s（叠加 TLS 握手/重试/buffer 风暴）；
- 结论：**BT PAN 有效吞吐约 6–9KB/s（约 50–70kbps）**，与“蓝牙共享本身有上限、和手机热点两回事”一致。

### 2.2 音频码率账
| 格式 | 码率 | 8KB/s 链路实时性 |
|---|---|---|
| PCM16 24kHz mono（TTS 解码后/流式 raw） | 384kbps（48KB/s），base64 后 ~64KB/s | **不可实时**（慢 6–8 倍） |
| MP3 当前 TTS body | ~50–65kbps（6–8KB/s） | 刚好卡线，长句下载 6–10s |
| Opus 16–24kbps | 2–3KB/s | **可实时/低延迟**，且可流式 |
| ASR WAV 8kHz 16bit | 128kbps（16KB/s），base64 后 ~21KB/s | 上传慢 2–3 倍；2s 语音要 4–6s |
| ASR Opus 16–24kbps | 2–3KB/s | 2s 语音约 0.5–1s 上传 |

## 3. 瓶颈定位（按贡献排序）

1. **ASR 上传链路**：WAV+base64 膨胀；`POST body 43KB` 在 8KB/s 链路上就是 5.4s+；日志有 230 条 `Unable to allocate buffer within timeout`，ACL/IOB 缓冲不足导致重试/卡顿。
2. **LLM 云端 TTFT 8–10s**：与蓝牙无关，是服务端/网络 RTT；文本流式已生效，但首字必须等它。
3. **TTS 同步 HTTP**：MiMo 返回完整 MP3 才播放（`body=… cl=… status=200` 后解码），首声 = 生成 + 下载；无并行、无预连接、回退链有额外风险。
4. **BT 缓冲配置偏紧**：`CONFIG_BT_BUF_ACL_TX_COUNT=10 / RX_COUNT=0(+EXTRA=2)`、`CONFIG_BT_L2CAP_TX_BUF_COUNT=8`、`CONFIG_IOB_NBUFFERS=64`；每次 TLS 突发就出现 ACL 分配失败风暴。SPP 配置（8 连接）与此无关。
5. **文本输出**：不是网络瓶颈；首字受 ASR+LLM 限制，后续流式更新正常。

## 4. 对你几条判断的核对

| 你的判断 | 核对结果 |
|---|---|
| “TTS 这里应该是同步的不是异步 WS，换 WSS 异步就好” | **前半对**：当前确实是同步 HTTP POST（MiMo chat/completions + 非流式 MP3），不是 WS。**后半要修正**：WSS 只降低协议开销/支持流式，不会提高 8KB/s 链路带宽；必须同时用 **Opus/MP3 压缩 + 异步流式 + 预连接**。另外 MiMo 官方文档当前给的是 HTTP/SSE（pcm16），未确认 WSS TTS；Edge/Volc 才有 WSS。 |
| “蓝牙共享通常有 SPP 文件限制，查看 SPP 是否最大” | **不适用**：我们走的是 **PAN/BNEP IP 链路**，不是 SPP/RFCOMM 串口文件传输；`CONFIG_BLUETOOTH_SPP_MAX_CONNECTIONS=8` 不参与这条路径。真正可调的是 ACL RX/TX buffer、L2CAP TX buffer、IOB 数量和 TCP 窗口。 |
| “蓝牙共享本身有上限，与手机热点是两回事” | **正确**：实测 PAN 6–9KB/s；手机热点/WiFi 高一个量级。要快，要么压缩数据，要么让手机侧代理（App 有互联网）。 |
| “WSS 对网络传输要求其实本身很低” | **正确**：WSS 帧开销很小（几字节），但 TCP/TLS 仍需传输同样 payload；关键是 payload 必须压缩（Opus 2–3KB/s）。 |

## 5. 可执行优化路线（先方案，未执行）

### P0：压缩 + 异步流水线（收益最大）
1. **ASR 上传 Opus**（16–24kbps，2–3KB/s）：2s 语音上传从 5–8s 降到 <1s；需要确认 MiMo ASR 支持 Opus/容器格式，或本地转码（CPU/内存评估）。
2. **TTS 改为异步流式 + 低码率编码**：优先 MP3/Opus 流式（不要 raw PCM16）；首句生成即播放，边收边解码；预连接 TLS。
3. **句子级流水线**：LLM 首句一到就启动 TTS，后续句子并行生成/播放（分句 TTS + 单句短请求，降低单次 TTS 生成时间）。
4. **消除同步等待**：把“LLM 完整回答后才 TTS”改为“首句就 TTS”，首声从 12–30s 降到约 ASR(压缩后1–2s)+LLM首句(2–4s)+TTS首块(1–2s) ≈ **5–8s**（估算）。

### P1：BT 链路调优（需要 SRAM/稳定性评估）
- 核查 `CONFIG_BT_BUF_ACL_RX_COUNT=0 + EXTRA=2` 是否过小；评估提高 RX ACL 数量/L2CAP TX buffer/IOB 到 96–128；
- 检查 BNEP MTU、TCP 窗口（`CONFIG_NET_TCP_*`）、`CONFIG_IOB_THROTTLE=16`；
- 目标：PAN 吞吐从 8KB/s 提到 15–25KB/s（受 BT 链路和 SRAM 限制，需实测）；
- **不要动 SPP**（无关）；注意 SRAM 已 94–97%、/data 写冻结未解决。

### P2：手机侧代理/卸载（结构性方案）
- 手表只负责采集音频/播放音频，ASR/LLM/TTS 由 App（WiFi/4G）代理，再经 BLE 回传结果/音频；
- 但 BLE NUS 带宽同样有限，只适合回传压缩音频/文本，不适合大音频；需评估。

### P3：文本与 UI
- 保持流式；首字前先显示“小云思考中…”（已做）；
- 减少 LVGL 重排/整页重建（已做）；文本不是主矛盾。

## 6. 字节级测量方案（下一步可执行，只加日志不改逻辑）

1. **TLS 层**：每个请求记录 `method/body_bytes/read_bytes/per-read[time,size]/total_ms/status`；已有 `body=… reads=… ms`，增加每次 read 的 size/时间即可算瞬时吞吐。
2. **ASR/TTS**：记录 `wav_bytes/upload_bytes/response_bytes/ttfb/total`；TTS 记录 `first_audio_ms/audio_bytes/bitrate`。
3. **BT/PAN**：`ifconfig bnep0`、`netstat -a`、IOB/ACL 分配失败计数、`Unable to allocate buffer` 次数；最好抓一次 BOOT 起所有计数。
4. **端到端打点**：ASR begin/end、LLM first token、TTS first audio、文本首字、首声；统一用 monotonic ms。
5. **算例**：`吞吐 = bytes / (t_end - t_start)`；`实时因子 = 音频时长 / 传输秒数`；对比 WiFi/PAN、短/长句、有/无 buffer 风暴。
6. **对照实验**：同一句在手机热点（WiFi）vs 蓝牙 PAN；ASR 1s/2s/3s；TTS 10/30/60 字；各 20 次取中位数。

## 7. 当前版本/风险

- 固件 v142c：7,448,096 B / md5 `574f5238ae0ed8659f9a2a0cae8f77ab`（/data 只读、TTS 上限 20、TLS/TTS 修复）；
- PAN 风暴与 /data 写冻结是底座风险，做语音优化时不要同时改 NOR 持久化；
- 若改 Opus/TTS 流式，需评估解码器 CPU/内存、TTS 后端格式支持、AEC/音频设备占用；
- 任何优化都要用上面字节级测量验证，不能只看“感觉快了”。