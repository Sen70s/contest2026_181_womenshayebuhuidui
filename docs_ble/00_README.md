# docs_ble — 蓝牙联网技术记录

> 板卡：SF32LB52-DevKit-LCD ｜ 目标：**让手表通过手机的「蓝牙网络共享」拿到真正的 IP 地址**（BNEP/PAN over BR/EDR + DHCP），
> 而不是让手机 App 做私有协议代理。

## 结论速览（2026-09 更新）

| 问题 | 结论 |
|------|------|
| PAN（BNEP over BR/EDR）能否用？ | ✅ **已打通并在真机回归**：修复三个平台移植层缺陷后，冷启动无人干预即联网（`dhcp_ok`），公网与 DNS 域名 ping 均 0% 丢包，60.6 分钟 / 60 轮长稳 0 assert 0 断链。完整证据链见 **[21](21_pan_breakthrough_authoritative.md)** |
| 联网的前提条件是什么？ | 三项公共仓缺陷修复（net_buf 池注册、HCPU→LCPU 邮箱 ring、PAN/BNEP 实现），索引见 **[23](23_upstream_contributions.md)** |
| 后续换设备/掉线怎么运维？ | 见 **[22](22_pan_engineering_guide.md)** 的四条硬规则与「症状→先查什么」对照表 |
| 还有哪些已知限制？ | 见 **[24](24_open_issues.md)**（含已修的 P0、蓝牙地址硬编码、设备名等 P1/P2） |

> 早期版本的本页曾写下「PAN 不可用」的结论（2026-08-14 的代码级判断）。**该结论已被推翻**：
> 当时的判断基于「框架无 PAN 选项、zblue 无 BNEP、panu_service 未被编入构建」，
> 后续我们补齐了 BNEP 编解码与 PAN SAL，并修复了三个平台缺陷，PAN 现已可用（见 21 号文）。

## 文档索引

- **[21_pan_breakthrough_authoritative.md](21_pan_breakthrough_authoritative.md)** — PAN 上网打通的权威复盘：三个根因的完整证据链 + 验证结果
- **[22_pan_engineering_guide.md](22_pan_engineering_guide.md)** — 工程指南：四条硬规则、症状→先查什么、验证口径
- **[23_upstream_contributions.md](23_upstream_contributions.md)** — 公共仓贡献索引（按大赛规则走 PR 的三项平台修复）
- **[24_open_issues.md](24_open_issues.md)** — 遗留问题清单（P0 已修 / P1 / P2，含动手入口）
- **[LOG.md](LOG.md)** — 逐轮调试日志（Round 1–29，含主动能力验收与两个真缺陷的根因）
- **[tools/](tools/)** — 真机验证脚本（PAN bring-up、长稳、抓包判定、NSH 执行器、演示前自检）

## 验证环境

- 工作区：`/home/aila/projects/vela_contest`
- 固件构建：`out/openvela_contest2026_181_board_ai_agent`（ninja）
- 演示前自检：`python3 docs_ble/tools/precheck.py`
- 验收测试清单：`docs/test_runbook.md`
