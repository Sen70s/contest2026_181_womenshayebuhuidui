# 106. 语音优化 P0 前置核对：MiMo API 硬限制与修正方案

> 状态：只读核对官方文档，未改代码/未烧录。结论：原 P0「ASR Opus + TTS 异步压缩流」**无法只靠 MiMo API 实现**，需要调整方案。

## 1. 官方文档事实（2026-11-04 核对）

### ASR（MiMo‑V2.5‑ASR，/v1/chat/completions）
- `input_audio.data` 只支持 **mp3、wav** 两种格式（MIME audio/mpeg、audio/wav）；
- **不支持 Opus/OGG/WebM**；
- 因此手表端要做 Opus 上传，必须自己实现 **Opus 编码器**（当前固件只有解码/播放链路，没有编码器），或让手机 App 代理上传。

### TTS（MiMo‑V2.5‑TTS，/v1/chat/completions）
- 非流式：支持 MP3（当前使用，实测 body 8–56KB，6–9KB/s 链路可行但首块要等完整生成）；
- **流式：文档明确要求输出格式为 `pcm16`**（便于拼接）；
- 这意味着 **MiMo 不支持流式 MP3/Opus**：PCM16 24k mono ≈48KB/s（base64 后 ~64KB/s），而 BT PAN 实测 6–9KB/s，**流式 PCM16 不可行**；
- MiMo 没有 WSS 接口（只有 HTTP/SSE）。WSS 只存在于 Edge TTS / 火山 TTS 等其它后端。

### 协议事实
- 当前 TTS 是同步 HTTP POST + 完整 MP3（不是 WS/WSS）；
- 当前 ASR 也是 HTTP 批式（WAV+base64），不是流式 WS；
- 所以「换成 WSS 异步」不能直接用在 MiMo 上；换 Edge/火山 WSS 需要解决 TLS/凭证/稳定性问题（Edge 在本板 TLS 握手失败 -0x4C）。

## 2. 原 P0 的修正结论

| 原计划 | 可行性 | 原因 |
|---|---|---|
| ASR 上传 Opus 16–24kbps | ❌ 仅 MiMo | API 只收 mp3/wav；无本地 Opus 编码器 |
| TTS 异步流式 MP3/Opus | ❌ 仅 MiMo | 流式只支持 pcm16；PCM16 带宽不够 |
| WSS 异步 TTS | ⚠️ 非 MiMo | MiMo 无 WSS；Edge/Volc 有但需 TLS/凭证/后端改造 |
| 句子级 TTS + 异步流水线 | ✅ 可行 | 非流式短句 MP3，首句生成/下载更快，可边生成后续句子 |
| BT ACL/IOB 调优 | ✅ 可行 | 当前 ACL RX_COUNT=0(+EXTRA=2)、IOB=64，buffer 风暴 230 次；ASR 17s 主要是上传+重试 |

## 3. 修正后的 P0 优先级

### P0-A：BT PAN 缓冲/链路调优（收益最大，先做）
- 核查 `CONFIG_BT_BUF_ACL_RX_COUNT=0 + EXTRA=2` 是否过小；提高 ACL RX/TX、L2CAP TX buffer、IOB（64→96/128，注意 SRAM 94–97%）；
- 目标：ASR 43KB 上传从 17s 降到 6–8s，TTS 下载抖动减少；用字节级吞吐验证；
- 风险：SRAM/稳定性；/data 写冻结未根除，调缓冲需配合 soak。

### P0-B：句子级 TTS 异步流水线（MiMo 兼容，不改协议）
- LLM 流式输出按句切分；**第一句一到就发 MiMo TTS 非流式 MP3**（短句生成 1–2s、body 8–12KB）；
- 首句播放时并行生成后续句子；替代“完整回答后一次性 TTS”；
- 预期：TTS 段从 2–9s 降到 1–2s（首句），总首声仍受 ASR+LLM TTFT 限制；
- 不涉及 PCM16 流式，规避带宽和旧 TLS 风险。

### P0-C：Edge/火山 WSS 压缩 TTS 可行性实验（研究）
- Edge WSS 支持 `audio-24khz-48kbitrate-mono-mp3`（~6KB/s）或 raw；若 TLS 修好可低延迟流式；
- 但 Edge 是非官方免费服务，稳定性/合规风险；需先解决 TLS -0x4C、凭证/域名问题；
- 火山 WSS 需正式凭证；仅作为备选，不阻塞 P0-A/B。

### P1：手机 App 代理（结构性）
- App 有 WiFi/4G；手表只上传压缩音频/播放音频，ASR/TTS 在手机侧完成；
- BLE 带宽同样有限，只适合文本/压缩音频；可作为中长期方向。

## 4. 为什么“文本/语音慢”短期仍难到 5s 内
- ASR 上传（WAV）在当前链路 6–17s；LLM TTFT 8–10s；TTS 首块 2–9s；
- 句子级 TTS 只能砍 TTS 段，ASR/LLM 仍是前置；
- 要质变，必须 **降低上传字节（手机代理/编码器）** 或 **换更快的 ASR/LLM 路径**；
- 只靠 WSS 异步不会突破 8KB/s 物理链路。

## 5. 建议下一步
1. 先做 P0-A（BT 缓冲调优 + 字节级测量），验证 ASR 上传和 buffer 风暴改善；
2. 并行做 P0-B（句子级 TTS 流水线），无需 API 变更；
3. P0-C 只做文档/小实验，不纳入主路径；
4. ASR Opus 暂缓（需本地编码器或手机代理），不要在没有编码器时硬改协议。

## 6. 证据
- TTS 文档：`/static/docs/quick-start/usage-guide/audio/speech-synthesis-v2.5.md`（流式必须 pcm16）；
- ASR 文档：`/static/docs/api/audio/Speech-Recognition.md`（只支持 mp3/wav）；
- 实测/日志：docs_ble/105、v142c 串口；
- BT 配置：out/ble_align/.config（ACL RX_COUNT=0/EXTRA=2、IOB=64、L2CAP_TX_BUF=8）。