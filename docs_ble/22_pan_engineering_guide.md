# 22. openvela + SF32LB52 蓝牙/网络工程指南（经验总结版）

> 日期：2026-08-21
> 适用：openvela `dev-ai-contest-2026` + SiFli SF32LB52 双核（HCPU 跑 NuttX，LCPU 跑闭源
> BT 控制器固件），zblue 协议栈
> 来源：BLE 代理 → BR/EDR 配对 → BNEP/PAN 上网这条线上 11 轮真机调试的沉淀
> 配套：`21_pan_breakthrough_authoritative.md`（PAN 三个根因的完整证据链）

这篇不重复 21 号文的案情，讲**可复用的判断方法**：哪些坑是这个平台结构性带来的、
遇到某类症状先查什么、以及交付时容易漏的东西。

---

## 一、先记住这四条硬规则

违反任一条，症状都会表现为「莫名其妙、换个无关配置就转移」，极难定位。

### 规则 1：新增 net_buf 池，必须同时注册进 `_net_buf_pool_list[]`

`external/zblue/zblue/port/sections/defines.c`。zblue 的 NuttX port 不用 Zephyr 的
linker section 收集池，而是手写数组；`pool_id()` 遍历它反查指针，**找不到时
`__ASSERT` 在 release 下被编掉、静默返回 0**。

漏注册的后果不是报错，而是：buffer 拿到 `_net_buf_pool_list[0]` 的
`max_alloc_size` **和它的 `data_pool` 基址**——尺寸莫名其妙 + 往别人的存储区里写。

> 自查：`grep -rn "NET_BUF_POOL.*DEFINE" frameworks/connectivity/bluetooth/service/`
> 得到的每个池名，都要能在 `defines.c` 的 `_net_buf_pool_list[]` 里找到。
> 追加请放数组**末尾**，别插中间——zblue 内部池的下标不能变。

### 规则 2：SRAM 顶部 0x2007FB00 以上不属于我们

```
0x2007FE00..0x2007FFFF  HCPU2LCPU_MB_CH1 buffer   ← BT HCI H4 ring
0x2007FC00..0x2007FDFF  HCPU2LCPU_MB_CH2 buffer
0x2007FB00..0x2007FBFF  HCPU custom config
```

`up_allocate_heap()` 曾把堆一直开到 0x20080000。本工程 `.bss` ~455 KB、堆只剩 ~32 KB，
**一定会分配到邮箱上去**：LCPU 写 HCI 字节 = 直接改写某个 malloc 对象。

这解释了本项目一系列「换个无关配置崩点就转移」的怪现象，包括 20 号文里 unqlite
文件句柄被写坏那个案子。已在 `sifli_allocateheap.c` 把上界钉在 0x2007FB00。

> 判据：**任何指向 0x2007FB00..0x20080000 的野指针 / BFAR，先怀疑堆越界到邮箱**，
> 不要先怀疑业务代码。

### 规则 3：一个 H4 帧必须一次写进邮箱 ring

`struct circular_buf` 的 `read_idx_mirror`（LCPU 写）与 `write_idx_mirror`（HCPU 写）
在**同一条 D-cache line**，而 `ring_write()` 结尾 `up_clean_dcache()` 整个头部，会把
HCPU 缓存里那份陈旧 read_idx 一起写回去 → 回退 LCPU 读指针 → H4 解析失同步 →
`Hardware error, hardware code: 0` → 停回 NoCP → ACL 信用耗尽 → TX 死锁。

因此：**不要分块写 ring，不要在帧中途 trigger**。单帧上限由 ring 决定：

```
492 = (512 - sizeof(struct circular_buf)=20) & ~3
ACL_Data_Packet_Length ≤ 492 - 1(H4 type) - 4(HCI ACL hdr) = 487
```

比这长的 L2CAP PDU 交给 zblue 正常 ACL 分片，每个分片是独立的一次 ring 写入。

> 这条规则同样约束「未来想提高吞吐」的做法：**加大 ACL 包长没有余量**，要提吞吐
> 只能在分片流水和 sniff 参数上做。

### 规则 4：串口的 RTS 接板子电源

用 pyserial 默认参数 `serial.Serial(port)` 打开串口会置位 RTS/DTR，**等于给板子断一次
电**。表现为「刚才还好的板子突然没输出了」「测试脚本一跑就重启」。

正确姿势见 `tools/nsh2.py`：先建对象、把 `rts`/`dtr` 设 False，再 `open()`。
需要连续观察的验证（长稳、冷启动自动连接）必须**全程只保持一个串口句柄**。

---

## 二、症状 → 先查什么

| 症状 | 先查 | 依据 |
|------|------|------|
| net_buf 尺寸/`tailroom` 与配置不符 | 池是否注册进 `_net_buf_pool_list[]` | 规则 1 |
| 小帧内容在线上变成垃圾 | 同上（写进了别的池的存储区） | 规则 1 |
| `Hardware error, hardware code: 0` | 是否分块写 ring；ACL 包长是否 > 487 | 规则 3 |
| `Unable to allocate buffer within timeout` 连刷 | 上一条的**后果**，别在 buffer 池上找原因 | 规则 3 |
| 崩在完全无关的模块（getenv/unqlite/inode） | 堆是否越界到邮箱；`.bss` 是否刚变过 | 规则 2 |
| 「换个配置崩点就转移」 | 一律先按规则 2 排 | 规则 2 |
| 板子突然无输出 | 测试脚本是否重开过串口 | 规则 4 |
| DHCP 发得出去、收不到租约 | 抓包确认 OFFER 已到 BNEP 层 → 是 IP 层丢的 | 见第三节 |
| HCI 命令超时/Hardware Error 且刚改过驱动 | 自造 HCI 命令是否漏了 H4 type 前缀 0x01 | 历史坑 |
| 开机 `AppBringUp` 间歇 HardFault | `/data` littlefs 是否已损坏（见第五节） | 遗留缺陷 |

---

## 三、DHCP over BNEP 的两个必需开关

```
CONFIG_NET_BINDTODEVICE=y                   # 否则 DISCOVER 从 eth0 出去
CONFIG_NETUTILS_DHCPC_BOOTP_FLAGS=0x8000    # RFC 1542 广播位
```

广播位这条容易漏且现象很误导：Android 的 tethering dnsmasq 用**单播** OFFER 回它刚挑
的 yiaddr，而 bt-pan 此刻还是 0.0.0.0，`nuttx/net/devif/ipv4_input.c` 没有任何地址能
匹配 → 包被丢。**抓包能看到 OFFER 确实回来了**（`00 43 00 44` 的 352 字节 UDP），但
`dhcpc_request()` 一路超时——包到了 BNEP 层，死在 IP 层。

排查手法：在 `on_pan_data_incoming()` 里按协议号只记 ARP / DHCP / 写 TAP 失败三类
（其余是 mDNS/ND 噪声，会淹掉控制台）。看到 `rx_dhcp ... sport=67 dport=68` 就说明
BNEP 侧没问题，问题在上面。

---

## 四、真机验证的做法

镜像分两个：正式 `ai_agent` 和调试 `ai_agent_pandbg`（由
`tools/mk_pandbg_config.sh` 在正式 defconfig 之上生成，加满 fault 诊断 +
`BOARD_RESET_ON_ASSERT=2` + HCI/IPC 全量 trace）。

**结论必须在正式镜像上复现。** pandbg 的 trace 本身会改变时序和 `.bss` 布局——本轮
就有「pandbg 不复现、正式镜像 100% 复现」的情况（规则 2 那个 getenv 崩溃）。

**永远不要跑 `savedefconfig`**：它会 copy_if_different 回源 defconfig 并抹掉全部注释。
改配置就直接编辑 defconfig，然后 `ninja resetconfig`。

验证脚本（`tools/`）：

| 脚本 | 用途 |
|------|------|
| `flash_rts.py` | 烧录：后台起 sftool，主线程用 RTS 断电再上电抢 ROM bootloader 窗口 |
| `nsh2.py` | 不抖 RTS 的 NSH 执行器，日常敲命令用这个 |
| `pan_soak.py` | 长稳：每 60 s 一轮小包+大包+`free`，统计 assert/断链/丢包/堆 |
| `erase_data.py` | 擦 `/data` littlefs 分区，从第五节那个缺陷里恢复 |
| `mk_pandbg_config.sh` | 生成调试 defconfig |
| `gate_b.sh` + `bnep_pcap.py` | HCI trace → pcap → tshark 判定 BNEP 帧合法性 |

验收口径（本轮实际用的）：

- **数据面**：网关 / 公网 IP / DNS 域名 / `-s 1472` 大包，各自 0% 丢包
- **重连**：手机侧断链后自动重连 + 重新 DHCP + ping 通
- **冷启动**：烧录后上电全程无控制台输入，出现 `dhcp_ok` 与
  `netmgr Active channel: bt-pan (primary)`
- **长稳**：60 轮 / 1 小时，`asserts=0`、`disconnects=0`、堆 `used` 首尾持平
- 大包丢包率看**是否随时间恶化**，不看绝对值：1472 字节要拆 4 个 ACL 分片，
  4% 左右是这条链路的正常水平（同轮小包 0.33% 可作对照）

---

## 五、遗留缺陷：XIP 下 NOR 写路径未全部 RAM 驻留

**优先级高于任何新功能**，因为它会吃掉整块 `/data`。

`sf32lb_flash_hw_init()` 检测到自己在 XIP 执行就跳过 `HAL_FLASH_Init`，于是首次 NOR
写/擦由 `sf32lb_flash_preinit_runtime()` 懒加载。该函数自己标了
`SF32LB_FLASH_RAMFUNC`，但它调用的 `HAL_FLASH_PreInit` /
`HAL_FLASH_ISSUE_CMD(RST)` / `HAL_FLASH_SET_QUAL_SPI` / `HAL_FLASH_CLR_PROTECT`
**都还在 XIP flash 里**——等于在给自己正在取指的那块 flash 发 RESET、切 QSPI 模式，
活不活取决于后续指令是否已在 I-cache 里，所以是**间歇性**的。

触发链：非正常掉电 → 下次开机 littlefs mount 要做恢复写入 → 命中这条路径 →
`AppBringUp` HardFault → 起不来。而且一旦中招每次开机都中（要写才能修，一写又炸）。

- 恢复：`tools/erase_data.py`（擦 NOR `0x129A0000:0x400000`，镜像本体在
  `0x12010000..0x125FA000`，不重叠），擦完重烧，`sifli_ap.c` 的 `forceformat`
  分支会重建文件系统。代价是 bond 丢失，但板子发起连接时 SSP 自动接受，会自己重配。
- 修复方向：整条调用链 `__ramfunc` 化；或把 NOR 写使能提前到一个确定的、不在文件
  系统 mount 中间的时机，并保证那段代码 RAM 驻留。

---

## 六、大赛交付：改动分别落在哪个仓

工作区是 openvela `repo` checkout。**团队仓只放自己的作品，公共仓改动走各自的 PR**
（团队仓 README 第五节第 3 条）。

| 改动 | 仓 | GitHub | 交付方式 |
|------|-----|--------|----------|
| 板级 defconfig、文档、工具、app、quickapp | `contest2026_181_womenshayebuhuidui` | `Sen70s/contest2026_181_womenshayebuhuidui`（已 fork） | push 到 fork → 向专属仓发 PR，可自行合入 |
| BT 框架 / PAN profile / SAL | `frameworks/connectivity/bluetooth` | `open-vela/frameworks_bluetooth` | **需先 fork** → PR 到 `dev-ai-contest-2026` |
| zblue 协议栈与 port 层 | `external/zblue/zblue` | `open-vela/external_zblue` | **需先 fork** → PR 到 `dev-ai-contest-2026` |
| SiFli 芯片驱动 / bth4 / 堆布局 | `vendor/sifli` | `open-vela/vendor_sifli` | **需先 fork** → PR 到 `dev-ai-contest-2026` |

**一个容易犯的误判**：`frameworks/connectivity/.gitignore` 里有 `/*/`，看起来
`bluetooth/` 被忽略了、改动无处可提。实际上 `frameworks/connectivity/bluetooth` 本身
就是一个独立的 repo project（`openvela.xml:152`，`frameworks_bluetooth`），有自己的
`.git`——父仓忽略它正是因为 repo 单独 checkout 它。**别因此把代码抄成快照塞进团队仓**
（本轮一度这么做，已撤销，见 `fw_patches/README.md`）。

同理，`docs_ble/app_patches/` 里的 `.kt` 快照是**合理**的：那是本工作区之外的独立
Android 工程，没有别的形式可选。

提交前自查：

```bash
# 1. 四个 linkfile 是否都在位（团队仓子目录 → openvela 编译树软链）
ls -l packages/demos/contest2026_181_ai_agent \
      packages/demos/contest2026_181_hello_app \
      packages/apps/contest2026_181_hello_quickapp \
      vendor/openvela/boards/contest2026_181_board

# 2. defconfig 与实际构建的 .config 是否一致（挑关键符号）
for s in CONFIG_BT_L2CAP_TX_MTU CONFIG_BT_BUF_ACL_TX_SIZE \
         CONFIG_NETUTILS_DHCPC_BOOTP_FLAGS CONFIG_NET_BINDTODEVICE; do
  grep -H "^$s=" contest2026_181_womenshayebuhuidui/board/contest_board/configs/ai_agent/defconfig \
                 out/nuttx_contest_board_ai_agent/.config
done

# 3. 四个仓各自的分支 / 是否游离 HEAD / 是否干净
for r in frameworks/connectivity/bluetooth external/zblue/zblue \
         vendor/sifli contest2026_181_womenshayebuhuidui; do
  echo "$r: $(git -C $r rev-parse --abbrev-ref HEAD) \
clean=$([ -z "$(git -C $r status --porcelain)" ] && echo yes || echo NO)"
done
```

注意 **detached HEAD**：repo checkout 出来的仓默认是游离头，直接提交会产生游离
commit，一次 `repo sync` 就找不回来了。提交前先 `git checkout -b <分支名>`。

还有两件与代码无关但会影响评审的：

- 团队仓 `README.md` 目前仍是组委会给的使用说明书。README 第六节要求**提交前替换成
  自己的作品说明**。
- AI Coding 日志（`logs/Sen70s/`）由采集器自动写入本机，需**主动提交**才算交付。

---

## 七、这条线上踩过的历史坑（一句话版）

留作检索线索，细节见对应文档。

| 坑 | 结论 | 文档 |
|----|------|------|
| 自造 HCI 命令漏 H4 type 前缀 0x01 | 控制器收到垃圾 → Hardware Error → 后续命令全超时 | 20 |
| bth4 里自己回 SSP | 应透传给 zblue 原生处理，别在驱动层做协议 | 20 |
| `z_sys_init()` 被跑两遍 | 两个线程共用同一静态栈；按 owner pid 幂等 | 18 |
| unqlite 走 libuv 线程池 | 改为全同步 + pthread mutex | 20 |
| 陈旧 link key | 手机侧「忘记设备」后必须清 `br_key.bin`，否则 security err 2 | 20 |
| BLE GATT 代理不切分超 MTU 报文 | 所有 IP 包被丢；App 侧也要按 (MTU-3) 动态分片 | 03 |
| `savedefconfig` | 会抹掉 defconfig 注释，禁用 | 19 |
| PAN worker 等加密超时 2 s | SSP 配对 + 加密要更久，放到 30 s | 21 |
| `security_changed` 里等手机来连 | 手机不会主动连 PANU，要自己发起 L2CAP | 21 |

---

## 八、吞吐：现在给不出数字

镜像里没有 iperf，也没有 wget / webclient，`ping` 只能测延迟不能测带宽。要出数字需往
defconfig 加 `NETUTILS_IPERF` 或一个 HTTP 下载客户端；当前 SRAM 90.48%、余量约 37 KB。

已知的间接指标：协商 L2CAP MTU 1691，接口 MTU 1500，1500 字节包能连续跑、丢包
约 4%，RTT 均值 159 ms（120 次统计，60 分钟）。

注意规则 3 的约束：**靠加大 ACL 包长提吞吐这条路已经没有余量**（487 已是 ring 上限），
要提只能做分片流水与链路参数。
