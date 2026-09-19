# AI Coding 日志说明

本目录是 Team 181 的 AI Coding 对话日志，按日期归档。

## 内容与来源

| 目录 | 工具 | 说明 |
|------|------|------|
| `2026-08-08` … `2026-08-19` | Claude Code | 官方收集器（`contest-snapshot` / `~/.claude/contest-collector-staging`）导出 |
| `2026-09-16` | ZCode | 见下「ZCode 会话的导出方式」 |

## ZCode 会话的导出方式

8-19 之后本工程改用 ZCode 开发，它没有官方收集器，因此用仓内脚本自行整理：

```bash
python3 docs/tools/export_zcode_log.py \
    ~/.zcode/cli/rollout/model-io-<session>.jsonl \
    logs/Sen70s/<日期>/zcode__<session>.jsonl
```

脚本做三件事：

1. **重建顺序**：ZCode 的模型 I/O 记录是「尾部窗口」（每条记录带 `messageOffset` + 一段历史），
   按 offset 建索引还原完整对话，避免重复。
2. **可读化**：工具结果截断到 800 字符、单条消息截断到 4000 字符，长内容标注截断点。
3. **脱敏**（见下）。

输出是 JSONL，每行一条消息：`{schema_version, tool, session_id, seq, role, tool_name, content}`。

## 脱敏规则

与 `redact.json` 一致，另加本机用户名：

| 形态 | 替换为 |
|------|--------|
| 云侧 API key（模式见 `redact.json` 第一条） | `StepFunKey_***REDACTED***` |
| `sk-…`（≥20 字符） | `sk-***REDACTED***` |
| `gho_…`（≥20 字符） | `gho_***REDACTED***` |
| 本机家目录路径、整词用户名 | `/home/<user>`、`dev` |

## 提交前的自查（本目录当前状态）

```bash
# 用户名（整词匹配，避免命中英文单词里的字母组合）
grep -rEo '\b<用户名>\b' logs/ | wc -l                      # 0
# 家目录路径
grep -rc "/home/<用户名>" logs/ | awk -F: '{s+=$2} END{print s+0}'   # 0
# key 类 token（模式见 redact.json；规则文件自身会命中模式文本）
grep -rEc "sk-[A-Za-z0-9_-]{20,}" logs/ | awk -F: '{s+=$2} END{print s+0}'  # 0
```

> 说明：本文件与 `redact.json` 里出现的是**规则文本**而非真实凭据；
> 会话日志中偶见的 key 前缀同样只是当时讨论脱敏规则时的文字，不是 key 本身。
> 另外直接 `grep -r "<用户名>"` 会误命中英文单词里的字母组合，判断请用整词匹配。

## 为什么没有 09-10 ~ 09-15

那几天的会话在 ZCode 的本地存储里仍是原始 15MB 级 model-io 文件（含完整系统提示与工具定义），
整理后的可读版本以 09-16 这一天的会话为代表提交；如需补齐，按上面的命令逐个转换即可。
