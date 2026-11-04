# 113. 手表语音“太慢”还有哪些解法？——联网调研与落地方案

> 调研日期 2026-11-04。基线：v142n（SF32LB52 手表 + Android App + MiMo 云；BT PAN；当前首声约 10–14s，离群 LLM 首 token 45s）。本文引用均保留 URL；标注【厂商宣称】的数据未在本项目复现。

## 0. 结论

网络/云端是主因，但不是“只能换网络”。按对本项目收益/成本排序：

| 排名 | 方案 | 预期首声 | 成本 | 风险 |
|---|---|---|---|---|
| 1 | 模板/常用回答音频缓存 + ADPCM 压缩 + 别名 | 固定台词只剩 ASR 3–5s；文字触发 <1s | 低 1–2 天 | 覆盖有限、Flash 占用 |
| 2 | TLS/连接预热复用 + 前缀缓存 + 回答限 1–3 句 | 省 1–2s | 低 2–4 天 | 连接池/0-RTT 细节 |
| 3 | 手机 TTS 代理（文本→手机本地 TTS→手表播） | TTS 0.5–1.5s | 中 1 周 | App 前台服务/BLE 音频 |
| 4 | 真·句级流式 TTS（放开 TLS 串行锁/分片） | 省 2–4s | 中 3–5 天 | 并发/TLS 稳定性 |
| 5 | 手机全链路代理（mic→Opus→手机 ASR/LLM/TTS） | 2.5–5s | 高 2–4 周 | App 后台、机型适配 |
| 6 | 本地唤醒词/命令词 | 唤醒<300ms；命令 1–2s | 中高 1–2 周 PoC | 麦克风连续采集不稳、中文数据 |
| - | 本地通用 ASR/TTS | 不可行 | 很高 | 内存/模型差 2 个数量级 |
| - | 手表直接 HTTP/2/QUIC、全双工 S2S | 不现实 | 高 | NuttX/mbedTLS 无成熟栈 |

一句话：先做缓存 + 连接/前缀 + 短回答（低风险），并行做手机 TTS 代理；全链路手机代理是终极方向；本地通用 ASR/TTS 与手表原生 QUIC 不建议。

## 1. 当前基线

| 段 | 实测 | 主要成本 |
|---|---|---|
| ASR | 3–5s | 上传 WAV/base64、云识别、端点等待 |
| LLM 首 token | 4–6s，离群 45s | PAN RTT/丢包、TLS 握手、云端排队 |
| LLM 生成 | 1–3s | 流式 token |
| TTS 首包 | 2.5–3.6s（长文本 6.7s） | TLS/网络 + 云端 TTS |
| 播放 | 音频时长 | 已修（v142m） |

## 2. 方案详解

### 2.1 缓存/模板（最低风险，先做）

- 现有 4 条 PCM 366KB；ADPCM 4:1 可压到 ~93KB，零依赖；Opus 16–24kbps 可到 24–36KB，但解码 CPU/RAM 需实测。
- 补“你好/晚上好/笑话/累/你是谁”等别名/音频；命中时完全不走网络 TTS。
- 参考：IMA ADPCM（https://education.siggraph.org/static/Drupal_2025/education.siggraph.org/static/HyperGraph/video/codecs/IMA.html）；Opus（https://opus-codec.org/）。ROM 余量约 2.18MB。

### 2.2 连接预热/复用 + 前缀缓存 + 短回答

- 每轮 TLS 握手实测 1–2s；启动预连接/TLS 池复用可省一轮 RTT。TLS1.3 0-RTT 再省 1 RTT，但非幂等 POST 有重放风险（https://www.rfc-editor.org/rfc/rfc8446.txt；https://cloud.tencent.cn/developer/article/1559709?policyId=1004）。
- 前缀缓存：system prompt/工具 schema 固定前置；DeepSeek Context Caching 默认开启（https://api-docs.deepseek.com/guides/kv_cache）。
- 语音回答限 1–3 句：Rime 实测限制长度/调端点可省 300ms，并指出 LLM 层是 voice agent 延迟大头（https://www.rime.ai/resources/faster-rime-voice-demos）。
- 预期省 1–2s，无架构风险。

### 2.3 手机 TTS 代理（性价比最高的结构性方案）

- 手表把文本（<1KB）经 BLE 发给手机；手机用 Android TextToSpeech 本地合成，回传 PCM/Opus。
- Android TTS 支持离线引擎（https://developer.android.com/reference/android/speech/tts/TextToSpeech）；BLE 2M PHY 实际约 1.4Mbps（https://www.ezurio.com/resources/blog/common-questions-about-bluetooth-le-throughput-and-high-data-rate-applications）。
- 预期 TTS 0.5–1.5s；风险：App 前台服务/后台限制（https://developer.android.com/develop/background-work/services/fgs/service-types）、机型差异、BLE 音频协议；iOS 暂不在范围。

### 2.4 真·句级流式 TTS（当前被 TLS 串行锁挡住）

- 目标：LLM 出第一句就合成/播放，不等整段。
- 当前 vela_tls 的全局 POST 锁让 LLM 与 TTS 不能并发；v142n 只能做到 LLM 结束后立即用首句短文本合成，把长文本首块 6.7s 降到 ~3s。
- 改法：分片 s_post_lock/允许并发，或把 TTS 放到手机代理。
- 参考：MiMo 流式 TTS 要求 pcm16（https://mimo.mi.com/static/docs/quick-start/usage-guide/audio/speech-synthesis-v2.5.md?plain=1）；Deepgram WebSocket 流式 TTS（https://developers.deepgram.com/docs/tts-websocket-streaming）；Cartesia 宣称 first audio <90ms【厂商宣称】（https://www.cartesia.ai/sonic）。

### 2.5 手机全链路代理（终极结构）

- 手表 mic → Opus（16kbps，3s 约 6KB）→ BLE → 手机 ASR/LLM/TTS → Opus → 手表喇叭。
- 预期首声 2.5–5s；彻底绕开手表 TLS/PAN/格式限制。
- 风险：App 前台服务/后台保活、机型、BLE 分片、回声/AEC；工程 2–4 周。
- 参考：Android SpeechRecognizer（https://developer.android.com/reference/android/speech/SpeechRecognizer）、离线偏好（https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_PREFER_OFFLINE）、AICore（https://developer.android.google.cn/ml/aicore）、ML Kit GenAI（https://developers.google.com/ml-kit/genai）；Apple 对等能力（https://developer.apple.com/documentation/speech/sfspeechrecognizerrequest/requiresondevicerecognition；https://developer.apple.com/documentation/avfaudio/avspeechsynthesizer；https://developer.apple.com/documentation/foundationmodels）。

### 2.6 端侧唤醒词/命令词（可选，先修麦克风）

- microWakeWord + TFLM：Apache-2.0，模型约 115KB/个，ESP32-S3+8MB PSRAM 产品已量产（https://raw.githubusercontent.com/OHF-Voice/micro-wake-word/main/README.md；https://esphome.io/components/micro_wake_word.html）；中文需自训。
- Porcupine：支持 Cortex-M/中文，需 AccessKey/商业授权（https://github.com/Picovoice/porcupine）。
- 最大前置风险：麦克风连续采集不稳（1/3 运行挂、连续第 3 次必挂）、AEC 关闭（本地 docs_ble/28）；不修无法产品化。
- 通用本地 ASR/TTS 不可行：whisper.cpp tiny 75MiB/运行约 273MB（https://github.com/ggml-org/whisper.cpp）；Vosk 中文 small 42MB/运行约 300MB（https://alphacephei.com/vosk/models）；sherpa 中文 matcha RPi4 单线程 RTF≈0.94（https://k2-fsa.github.io/sherpa/onnx/tts/all/Chinese/matcha-icefall-zh-baker.html）；Piper 28–57MB（https://aihub.qualcomm.com/iot/models/pipertts_en）。

### 2.7 感知延迟/预测（体验优化）

- 预连接/预生成凭证、填充词、动画：Rime 实测预生成凭证改善第一声（https://www.rime.ai/resources/faster-rime-voice-demos）；contextual preface 有“初始延迟 vs 正文间隔”取舍（https://arxiv.org/abs/2607.23204）。
- 听音期间提前推理（partial ASR）：Heartbeat Thinking 工具覆盖 +16pp、最终延迟 −36.4%（https://arxiv.org/abs/2610.26233）；需处理 partial 意图反转。
- 语义缓存：vCache 带误差保证、命中最多降 100×（https://ar5iv.labs.arxiv.org/html/2502.03771）；不要静态阈值硬缓存。

## 3. 推荐路线

### P0（1–3 天，低风险）
1. 缓存扩展：补“你好/晚上好/笑话/累/你是谁”；ADPCM 压缩 366KB→93KB。
2. TLS 预连接/池复用 + 前缀缓存；记录 handshake/cached_tokens。
3. 语音回答限 1–3 句、低延迟模型档。
4. 分段日志：conn_ms/handshake_ms/ttft_ms/gen_ms/tts_first_ms。

### P1（1–2 周）
5. 手机 TTS 代理 POC（文本→手机 TTS→BLE→手表），目标 TTS 0.5–1.5s。
6. 真句级流式 TTS：分片/放开 TLS 串行锁，或走手机代理。
7. 手机侧 partial ASR 提前请求（可选，需 cancel）。

### P2（2–4 周）
8. 手机全链路代理（mic Opus→手机 ASR/LLM/TTS），目标首声 2.5–5s。
9. 本地唤醒词/命令词：先修麦克风，再 microWakeWord/Porcupine PoC。
10. 多后端/区域、QoE 总预算；HTTP/2/QUIC 只在手机/网关做。

### 不建议
- 手表固件直接 HTTP/2/QUIC/全双工 S2S 长连接；
- 本地通用 ASR/TTS；
- 静态阈值语义缓存。

## 4. 风险与约束

| 风险 | 说明 |
|---|---|
| 麦克风驱动 | 连续采集不稳（docs_ble/28），唤醒词/全链路代理前置 |
| AEC/回声 | 当前 AEC 关闭，PTT 打断 TTS 是 workaround |
| MiMo ASR 格式 | 只收 mp3/wav，Opus 直传云端不可行；需手机代理/转码 |
| TLS 并发 | 手表全局锁，真流式 TTS 受限；手机代理可绕开 |
| SRAM/Flash | v142n Flash 78.25%、SRAM 97.5%；加 PCM/缓存需重算 |
| App 后台 | Android 前台服务/保活限制；机型差异 |
| 隐私/合规 | 音频/健康数据授权、最小化上传 |
| 厂商数据 | Cartesia/Deepgram/Groq 为厂商宣称，未在本项目复现 |

## 5. 参考来源（完整见研究文档）

- Rime：https://www.rime.ai/resources/faster-rime-voice-demos
- DeepSeek 前缀缓存：https://api-docs.deepseek.com/guides/kv_cache
- TLS 1.3：https://www.rfc-editor.org/rfc/rfc8446.txt
- Opus：https://www.rfc-editor.org/rfc/rfc6716.txt
- BLE 吞吐：https://www.ezurio.com/resources/blog/common-questions-about-bluetooth-le-throughput-and-high-data-rate-applications
- Deepgram 流式 TTS：https://developers.deepgram.com/docs/tts-websocket-streaming
- MiMo TTS：https://mimo.mi.com/static/docs/quick-start/usage-guide/audio/speech-synthesis-v2.5.md?plain=1
- Cartesia Sonic：https://www.cartesia.ai/sonic
- Android TTS：https://developer.android.com/reference/android/speech/tts/TextToSpeech
- Android SpeechRecognizer：https://developer.android.com/reference/android/speech/SpeechRecognizer
- microWakeWord：https://raw.githubusercontent.com/OHF-Voice/micro-wake-word/main/README.md
- Porcupine：https://github.com/Picovoice/porcupine
- whisper.cpp：https://github.com/ggml-org/whisper.cpp
- Vosk：https://alphacephei.com/vosk/models
- Heartbeat Thinking：https://arxiv.org/abs/2610.26233
- vCache：https://ar5iv.labs.arxiv.org/html/2502.03771
- 完整 URL/抓取状态：.v3_work/plan/research_voice_net.md、research_voice_cloud.md、research_voice_edge.md

## 6. 最终建议

不要继续只盯“模型/网络”单点。先做缓存扩展 + 连接复用 + 短回答（1–3 天可见效），并行做手机 TTS 代理（1 周，TTS 降到 1s 级），再评估手机全链路代理；本地通用 ASR/TTS 和手表原生 QUIC 不值得投入。

## 7. P0 落地结果（v142p，2026-11-04 17:03）

- 固件 v142p：7,910,180 B md5 `9fe002baf99b754016a5ae3193347d90`（已烧录，Flash 78.89%）。
- CACHE：从旧 `demo_voice_data.c.o` 恢复 11 条 24k PCM，IMA ADPCM 4:1（1,706,466→433,307 B），覆盖 hello/who/joke/tired/hr/greet/alarm/water/sleep/weather/sedentary；别名 你好/讲个笑话/我有点累/你是谁 等。
  - 真机：4 条全部 `[voice_cache] hit`，本地播放 2.7–6.2s，无网络 TTS、不占 TTS 额度。
- NET：TLS 预连接 `[lat] preconnect ok conn_ms=285 handshake_ms=636`；锁外握手/2s 超时/覆盖 MiMo 同网关/断链重置；`[lat] turn` 分段日志；前缀缓存 `cached_tokens=0`（MiMo 未返回，不能据此判断未命中）；语音 prompt 1–3 句、max_tokens 保持 110。
- 未验证：真实语音 ASR 后的首句 TTS/8s 首 token 降级（需真人说话/弱网）；preconnect 池约 10s 复用窗口，CLI `ask` 间隔 17s 已过期，实际收益有限。
- 结论：**P0 的缓存部分已明显生效；网络预连接收益受池窗口限制，前缀缓存供应商不可见；下一步优先手机 TTS 代理。**
