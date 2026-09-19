# 开发 Skill：SF32LB52 蓝牙 PAN 调试

> 从 Team 181 十几天真机调试里提炼。适用于「openvela/NuttX + SiFli SF32LB52（双核 HCPU/LCPU，蓝牙栈跑在 LCPU）」这类**没有调试器、只有串口日志**的平台。
>
> 用法：把它交给 AI 助手当工作手册，或作为新人上手的排查表。目的不是解释平台，而是**把「看起来哪里都可能有问题」压缩成几条先验规则**，省掉重走 14 轮的成本。

## 一、四条硬规则（违反任一条，症状都会表现为「莫名其妙、换个无关配置就转移」）

### 规则 1：新增 net_buf 池，必须同时注册进 `_net_buf_pool_list[]`

zblue 的 NuttX port 用手写数组代替 Zephyr 的 linker section 收集 net_buf 池；`pool_id()` 遍历该数组反查指针，**找不到时静默返回 0**（`__ASSERT` 在 release 下被编掉）。

后果：任何未注册的池，其 buffer 会拿到 `_net_buf_pool_list[0]` 的 `max_alloc_size` **和它的 `data_pool` 基址**——尺寸不对，且写进别的池的存储区。

排查特征：`tailroom` 数值与任何配置都对不上（我们遇到的是 `tailroom=249`）。**不要再去调 MTU**。

### 规则 2：SRAM 顶部 `0x2007FB00` 以上不属于应用

堆上界之外还被 LCPU 邮箱 buffer 占用。堆越界不会立刻崩，而是**踩到邮箱**，表现为 `Hardware_Error`、LCPU 停回 NoCP。

排查特征：随机 Hardware_Error + 底层邮箱相关寄存器。

### 规则 3：一个 H4 帧必须一次写进邮箱 ring

HCPU→LCPU 邮箱 ring 要求整帧写入。**分块写入会让 LCPU 读指针回退**，直接 Hardware_Error。

排查特征：帧长接近 ring 容量边界时才复现；小包永远正常。

### 规则 4：串口的 RTS 接板子电源

- `pyserial` 默认 `open()` 会置位 RTS/DTR = **给板子断电**。要连续观察必须显式 `rts=False, dtr=False` 再 open。
- 本机 CH343 桥的 RTS **不能**可靠控制电源（实测拉高不重启），冷启动要靠物理 RESET 或重新烧录。
- `sftool --before default_reset` 抢 ROM bootloader 窗口有失败率，报 `Failed to download stub` 就重试 1–2 次。

## 二、症状 → 先查什么

| 症状 | 先查 | 不要先查 |
|------|------|---------|
| `tailroom=NNN` 与配置不符 | net_buf 池是否注册进 `_net_buf_pool_list[]`（规则 1） | MTU / buffer 数量配置 |
| 随机 `Hardware_Error`，LCPU 停 | 堆上界是否越界踩到邮箱（规则 2） | 蓝牙业务代码 |
| 大包偶发失败、小包正常 | H4 帧是否分块写入邮箱（规则 3） | 上层重传逻辑 |
| `Unable to allocate buffer within timeout` 连刷 | 是不是 `Hardware_Error` 之后的**后果**（ACL 信用耗尽） | 加 buffer 池大小 |
| 串口突然完全静默 | 板子是否真的卡住：看有无自发日志；必要时 RESET | 反复发命令 |
| 连上就断开 | 手机侧「蓝牙网络共享」是否开着；link key 是否双方一致 | 改连接参数 |
| DHCP 打不开（`dhcpc_open` 失败） | link key / 加密是否正常（见下） | 反复重连 |

## 三、DHCP over BNEP 的两个必需开关

1. **`CONFIG_NET_BINDTODEVICE=y`**：DHCP DISCOVER 必须发到广播地址，此时还没有本地 IP，只能靠 SO_BINDTODEVICE 指定出口网卡。
2. **TAP 网卡的 MTU 要按协商结果钳制**（`tx.mtu - 14`）：NuttX 用 `d_pktsize` 推导 TCP/UDP MSS，不钳制就会生成 BNEP 承载不了的帧。

## 四、调试方法论（比具体规则更值钱）

1. **先建证据链，再改代码**。我们在「闹钟不响」上翻过车：真实原因是触发路径没打日志，串口里看不见，于是被误判成缺陷并去改代码。**验收路径上「用户可见但我不可见」的动作必须落 syslog。**
2. **区分因果**。连刷的错误日志常常是**后果**而不是原因（见上表第 4 行）。
3. **把一次性调试沉淀成规则**。每定位一个根因，问一句：「下次出现类似症状，先查什么？」写进对照表。
4. **改动最小化**。修 A 时顺手改 B，会把下一次故障的归因成本翻倍（我们为此多烧了一次板）。
5. **认清板级特性**：本板 RESET 是**软复位**，RAM 与 `CLOCK_MONOTONIC` 都保留——冷却计时、闹钟状态会跨 RESET 存活。测新功能要用**烧录后的全新上电**，别用 RESET 按钮。

## 五、工具入口（本仓自带）

| 工具 | 用途 |
|------|------|
| `docs_ble/tools/nsh2.py` | 安全连串口跑 NSH 命令（不抖 RTS，不掉电） |
| `docs_ble/tools/pan_bringup.py` | PAN 上电自连 + DHCP 全链路驱动 |
| `docs_ble/tools/pan_soak.py` | 长稳回归（连接/断开计数 + 堆用量） |
| `docs_ble/tools/flash_rts.py` | 烧录（RTS 电源时序版） |
| `docs_ble/tools/bnep_codec_test/` | BNEP 编解码主机侧单元测试 |
| `build_and_flash.sh` | 编译 + 烧录一键脚本 |

## 六、相关文档

- `docs_ble/21_pan_breakthrough_authoritative.md` — 三个根因的完整证据链（权威复盘）
- `docs_ble/22_pan_engineering_guide.md` — 本 Skill 的详细版（含代码位置与寄存器细节）
- `docs_ble/24_open_issues.md` — 已知遗留缺陷与动手入口
- `docs_ble/LOG.md` — 逐轮调试日志（Round 1–28）
