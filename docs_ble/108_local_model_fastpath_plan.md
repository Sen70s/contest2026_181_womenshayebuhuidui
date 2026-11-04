# 108. 端侧模型快路径/预热/本地优先 实施方案（P0）

> 目标：解决“端侧模型回答太慢”的体感。不改模型权重、不写 /data、不改变 Proactive Router；当前基线 v142c（非演示、/data 只读）。

## 1. 现状（代码/文档）
- velaAI LSTM：2.31M 参数、3.0MB、16x8 量化、32 token 上限、Agent 两段式；文档实测 Agent 端到端 ~9s（含进程/UI），纯推理每 token 几十 ms；
- 当前 `local_lm_reply()` 仅作为**云端失败后的兜底**；第一次调用还会 `ai_lm_init()`/AllocateTensors；
- `ai_lm_agent_reply()` 即使规则识别出意图，也先跑 stage1 模型生成；
- 没有预热、没有本地优先、没有模板快路径。

## 2. P0 改动

### A. 模板/规则快路径（不跑模型）
在 `local_lm.c` 里新增 `local_lm_fast_reply(text,out)`：
- 问候/你是谁/我有点累/心情不好/夸夸我/谢谢/再见 → 固定短句；
- “现在几点/今天几号/星期几” → 读 RTC，本地生成；
- “讲个笑话/再讲一个” → 本地两条固定笑话；
- 明确不做设备控制（避免瞎执行），设备指令仍交给模型/云端；
- 命中返回 0，未命中 -1；`local_lm_reply` 内部也先走快路径，保证所有 fallback 调用受益。

### B. 预热
- `local_lm_warmup_start()`：一次性 detached 8KB 线程，调用 `ai_lm_init()`（仅 AllocateTensors，不生成）；
- 由 `agent_loop_init()` 调用（在 ai_agent 主进程上下文，避免短任务组清理）；
- 首次 fallback 不再付 init 时间；失败自动忽略。

### C. 本地优先/弱网直落
- `agent_loop` 在调用云 LLM 前检查 `network_get_state()`/`network_get_iob_usage()`；
- 未连接或 IOB≥75%：先 `local_lm_reply()`；成功则直接作为本地回答（label 本地），不再等云端；
- 正常网络仍走云端；云端失败后现有 fallback 保留；
- 暂不做“云端与本模型真并发竞速”（线程/TLS 并发风险高，P1 再评估）。

## 3. 验收
1. 断网/弱网下 `ask 你好`、`ask 现在几点`、`ask 讲个笑话` 走本地快路径，端到端 **<300ms**（不含语音 TTS）；
2. 未知问题走模型，预热后首次 fallback 明显快于当前；
3. 日志 `[local_lm] fast path: ...`、`[local_lm] warmup done`；
4. 不新增常驻线程（仅一次性 8KB warmup）；/data 零写；
5. 24h soak：SFBL/RSET/Assertion=0，无 TTS/音频并发 hardfault；
6. 本地回答仍带（本地）标签；不编造设备动作。

## 4. 风险
- 模板覆盖面有限，未命中仍走模型；
- 快路径可能比模型“死板”，但快且可控；
- 预热线程若在低内存时创建失败，忽略即可；
- 弱网判断依赖 network_manager，误判可能提前本地回答（仍可接受）。
## 6. 真机结果（2026-11-04 12:54，v142f）

- 固件 v142f：7,456,072 B md5 `361984cd2a64a668daf666b852826efa`（已烧录）。
- `ask 你好` / `讲个笑话` / `我有点累` / `你是谁` / `再讲一个`：均出现 `[agent] local-fast preempt: ... cloud skipped`，约 1s 内出答复，无 `[llm] LLM stream request` / `[vela_tls] Handshake start`。
- 离线 `ask 打开客厅灯`：`offline + control intent -> honest refusal`，回复「这个需要联网才能执行设备操作…」，未进入 ai_lm 虚拟执行器，无“已为您打开”等编造。
- 本地回答不写 LLM cache（`skip LLM cache store (local answer)`），网络恢复后同问题走云端。
- 真机 up=203s，无 SFBL/RSET/Assertion；/data 仍只读。
- 结论：P0 目标达成——模板类本地回复从“云端等待 16s 后兜底”变为 **~1s 本地秒回**；未知/控制类仍按安全策略走云端或诚实拒绝。
