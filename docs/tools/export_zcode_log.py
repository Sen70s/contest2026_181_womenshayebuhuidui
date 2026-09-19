#!/usr/bin/env python3
"""把 ZCode 会话的 model-io 记录整理成可提交的 AI Coding 对话日志。

背景：大赛要求把与 AI 工具的对话导出到仓库 logs/。本工程的近期开发用 ZCode，
它不像 Claude Code 那样有官方收集器，所以这里自己做一次整理。

模型 I/O 记录是「尾部窗口」：每条记录带 messageOffset + 一段历史窗口，
因此按 offset 建索引重建顺序，再按角色输出。工具调用与结果做摘要化处理
（结果截断），保持文件可读、体积可控。

脱敏：API key / token / 本机用户名路径。**必须**在提交前跑一遍密钥复扫。

用法:
  python3 docs/tools/export_zcode_log.py <model-io.jsonl> <输出.jsonl>
"""
import json
import re
import sys

MAX_TEXT = 4000      # 单条消息正文上限（超长截断并标注）
MAX_TOOL = 800       # 工具结果上限

REDACTIONS = [
    (re.compile(r"Er8s[A-Za-z0-9]{40,}"), "StepFunKey_***REDACTED***"),
    (re.compile(r"sk-[A-Za-z0-9_-]{20,}"), "sk-***REDACTED***"),
    (re.compile(r"gho_[A-Za-z0-9]{20,}"), "gho_***REDACTED***"),
    # 经典 PAT (ghp_) 与细粒度 PAT (github_pat_)：日志要提交，必须一并打码
    (re.compile(r"ghp_[A-Za-z0-9]{20,}"), "ghp_***REDACTED***"),
    (re.compile(r"github_pat_[A-Za-z0-9_]{20,}"), "github_pat_***REDACTED***"),
    (re.compile(r"/home/aila"), "/home/dev"),
    # ls -la / groups 输出里的用户名（整词，不会命中 available）
    (re.compile(r"\baila\b"), "dev"),
    (re.compile(r"aila@"), "dev@"),
]


def redact(text: str) -> str:
    for pat, rep in REDACTIONS:
        text = pat.sub(rep, text)
    return text


def text_of(msg: dict) -> str:
    c = msg.get("content")
    if isinstance(c, str):
        return c
    if isinstance(c, list):
        parts = []
        for p in c:
            if isinstance(p, dict):
                if p.get("type") == "text":
                    parts.append(p.get("text", ""))
                elif p.get("type") == "reasoning":
                    parts.append("[思考] " + p.get("text", ""))
        return "\n".join(x for x in parts if x)
    return ""


def summarize(msg: dict) -> str:
    role = msg.get("role", "?")
    body = text_of(msg).strip()

    if role == "tool":
        name = msg.get("toolName") or "tool"
        if len(body) > MAX_TOOL:
            body = body[:MAX_TOOL] + f"\n…[截断，共 {len(body)} 字符]"
        return f"[工具结果 {name}]\n{body}"

    if msg.get("toolCalls"):
        names = []
        for tc in msg["toolCalls"]:
            if isinstance(tc, dict):
                names.append(tc.get("toolName") or tc.get("name") or "tool")
        body = (body + "\n" if body else "") + "[调用工具] " + ", ".join(names)

    if len(body) > MAX_TEXT:
        body = body[:MAX_TEXT] + f"\n…[截断，共 {len(body)} 字符]"
    return body


def main(src: str, dst: str) -> None:
    by_index = {}
    order = []
    session = None

    with open(src, errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            session = session or rec.get("sessionId")
            rq = rec.get("request") or {}
            msgs = rq.get("messages") or []
            off = rq.get("messageOffset") or 0
            for i, m in enumerate(msgs):
                idx = off + i
                if idx not in by_index:
                    by_index[idx] = m
                    order.append(idx)

    order.sort()
    kept = 0
    with open(dst, "w") as out:
        for idx in order:
            m = by_index[idx]
            body = summarize(m)
            if not body.strip():
                continue
            kept += 1
            out.write(json.dumps({
                "schema_version": "1.0",
                "tool": "zcode",
                "session_id": session,
                "seq": idx,
                "role": m.get("role"),
                "tool_name": m.get("toolName"),
                "content": redact(body),
            }, ensure_ascii=False) + "\n")

    print(f"total messages indexed: {len(order)}, written: {kept} -> {dst}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
