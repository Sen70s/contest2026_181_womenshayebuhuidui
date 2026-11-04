# 112. 语音端到端提速 P0：首token超时/首句TTS/模板音频缓存

> 基线 v142m（播放生命周期已修）。实测正常回合 11–14s，离群 52s：ASR 3–5s + LLM首token 4–45s + TTS首包 2.5–3.6s。

## 1. 目标
- 正常回合：12–13s → 8–10s；
- 离群回合：50s+ → ≤10s；
- 本地模板（你好/笑话/累）：3–5s 出声（缓存命中时只剩 ASR）。

## 2. 三项并行

### A. LLM 首 token 超时降级（llm_proxy.c）
- 流式请求增加 first-token deadline 8s；超时关闭连接、返回 -ETIMEDOUT；
- agent_loop 现有失败路径走 local fallback/短句；
- 日志 `[llm] first-token timeout 8000ms`；可编译开关。

### B. 首句流式 TTS（voice_channel + agent_loop）
- agent_loop 收到 voice 流式 delta 时调用 `voice_channel_stream_feed(delta)`；
- voice_channel 在第一个完整句子（。！？；换行）立即入队 TTS，后续句子继续入队；
- `voice_channel_stream_end(full_text)` 收尾；dispatch 检测 takeover 后不再重复整段 TTS；
- 保持 PTT abort/播放器所有权；首句出声提前 2–4s。

### C. 模板音频缓存（新 voice_tts_cache + 数据）
- 用 .v3_work/demo_voice/*.wav 转 16kHz mono PCM16 嵌入 Flash（4 条≈370KB，可后续补）；
- API `voice_tts_cache_play(text)`：命中直接 opens audio_playback(16k) 播放，无网络 TTS；
- voice_channel_speak 先查缓存；命中不占 TTS 网络 session；
- 日志 `[voice_cache] hit/miss`；不写 /data。

## 3. 验收
- 首 token 超时：人工断网/慢网，8s 内出本地/降级，不再 50s；
- 首句流式：voice 问非模板问题，日志 TTS first chunk 在 LLM 完成前出现；总第一声提前；
- 缓存：ask/voice_speak 固定台词，`[voice_cache] hit`，出声 <1s（除 ASR），无 TTS 网络请求；
- 稳定性：连续 5 次语音、PTT 打断、/data 零写、无复位。
## 4. 实现状态（v142n）
- 固件 v142n：7,845,820 B md5 `2b0aa4012b97bf4d070aa87da49a0b07`（已烧录，flash 78.25%）；
- VOICE-A：首 token 8s（从 voice begin 后计时）、ETIMEDOUT 跳过 failover 直达 local、生成预算 25s、非语音不变；
- VOICE-B：agent_loop voice delta -> voice_channel stream begin/feed/end，首句切分入队，takeover 防整段重复，PTT 清队列；
- VOICE-C：hello/greet/hr/alarm 4 条 16k PCM 缓存 366KB + 你好/晚上好别名；命中直接本地播放、不占 TTS session。
- 已验证：voice_speak 你好/晚上好 cache hit，无网络 TTS；长句 TTS 正常；连续 TTS 无泄漏。
- 待真机语音：首句流式 TTS 与 8s 首 token 降级需用户实际说话/弱网验证。
- 已知限制：vela_tls 的 s_post_lock 使 TTS 无法与 LLM 流并发；当前首句 TTS 在 LLM 结束后立即用短句合成（把长文本 TTS 首块 6.7s 降到 ~3s），真正“边说边合成”需放开/分片 s_post_lock。

## 5. v142t F1/F2/F3 修复（2026-11-04 18:45）
- 固件 7,916,104 B md5 `4cbcb95d1e1f37a8b30ad20db3323627`（已烧录，boot=3）。
- 首 token 主动 first-body deadline 9s（不再等 17–25s）；[lat] request_sent/first_byte/first_delta/local_lm 仪表化。
- 冲突修复：P2A 本地语音 turn_active 门禁、tts_begin 等 LLM 30s 超时、care 专用 cache-only 队列（miss/-EBUSY 不回退网络 TTS、不淘汰用户回答）。
- F2：联网首 token/看门狗超时语音回合跳过 TFLM，13 字短模板；真正离线仍 local_lm。
- F3：VAD 冷启动 600→200ms、短录音 rescue、连续 -ENODATA 计数/第3次引导；正常阈值不变。
- 真机：boot=3，voice_speak 你好 本地缓存 2.75s，无复位；语音 PTT/VAD 待用户实测。
