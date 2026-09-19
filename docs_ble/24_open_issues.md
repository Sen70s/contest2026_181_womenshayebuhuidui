# 24. 遗留问题清单

> 日期：2026-08-22（原 08-21，Round 15 更新）
> 状态：PAN 上网主线已完成并回归通过（见 21 号文）。P0 已在 Round 15 修掉（构建层面
> 已验证，真机掉电回归待做）。这里记录**明确知道、但有意暂不做**的事，含各自的判断
> 依据和动手时的入口。
> 维护约定：解决一项就把它移到对应的复盘文档并在这里标注去向，不要直接删。

## P0 — 已修复（2026-08-22）

### 24.1 XIP 下 NOR 复位窗口未 RAM 驻留 ✅ 已修

> 修复见 `LOG.md` Round 15。本节保留原始症状与更正后的机制，方便回溯。
> **注意**：8-21 版本的机制描述有误（写成「HAL 那几个函数仍在 XIP flash 里」），
> 实际不是——`bf0_hal_mpi.c` / `bf0_hal_mpi_ex.c` 整个 TU 早已在 `.ramfunc`。
> 真正的漏洞只有一处，见下。

**现象**　非正常掉电后，下次开机偶发在
`nx_mount("/dev/config0", "/data", "littlefs")` 里 HardFault，任务 `AppBringUp`，
板子起不来：

```
INFO: NOR MTD registered at /dev/config0 (offset=2464 blocks=1024)
dump_assert_info: Assertion failed panic: at arm_hardfault.c:186 task: AppBringUp
sched_dumpstack: [ 3] [<0x1203ca06>] sf32lb_flash_unlock+0x1/0x20
```

**同一份镜像有时能过有时不能过**，pandbg 与正式镜像表现也不同——不是确定性的代码路径
问题。而且一旦中招，之后每次开机都中（损坏的元数据要写才能修，一写又炸）。

**机制**　`sf32lb_flash_hw_init()`（`vendor/sifli/chips/sf32lb52/sf32lb_flash.c:120`）
检测到自己在 XIP 执行就跳过 `HAL_FLASH_Init`：

```c
pc = (uintptr_t)&sf32lb_flash_hw_init;
if (pc >= FLASH2_BASE_ADDR && pc < FLASH2_BASE_ADDR + SF32LB_NOR_TOTAL_SIZE) {
    g_flash_hw_initialized = false;
    syslog(LOG_WARNING, "WARN: skip HAL_FLASH_Init during XIP bringup, ...");
    return OK;
}
```

于是首次 NOR 写/擦由 `sf32lb_nor_prepare_io()` 懒加载
`sf32lb_flash_preinit_runtime()`（同文件 :225）。该函数自己标了
`SF32LB_FLASH_RAMFUNC`，它调的 HAL 也都在 RAM 里——查 `System.map` 可证：

```
20021970 t sf32lb_flash_preinit_runtime
2001f590 T HAL_FLASH_PreInit          <- .ramfunc，不是 XIP
20020a4e T HAL_FLASH_ISSUE_CMD        <- .ramfunc
2002103c T HAL_FLASH_SET_QUAL_SPI     <- .ramfunc
12010628 W HAL_Delay_us               <- 只有这一个在 XIP flash 里
```

窗口的边界也比原文以为的窄。`HAL_FLASH_PreInit()` 结尾的
`HAL_FLASH_SET_QUAL_SPI(hflash, false)` 只把 AHB 读命令切成 FREAD（单线快读），
片子照样应答，**XIP 仍然可用**；`nor_qspi_switch(hflash, false)` 更是 `en==0` 直接
return，连 QE 位都没动。所以 PreInit 返回之后的 `sf32lb_flash_unlock()` / `syslog()` /
拷 ctable 的 `memcpy()` 虽然都在 XIP，其实都是安全的。

**真正的窗口只有这五行**（原 :232–241）：

```c
HAL_FLASH_ISSUE_CMD(hflash, SPI_FLASH_CMD_RST_EN, 0);  /* 0x66 */
HAL_FLASH_ISSUE_CMD(hflash, SPI_FLASH_CMD_RST, 0);     /* 0x99 片子开始复位 */
HAL_Delay_us(30);                        /* ← 从正在复位的那块 flash 取指 */
hflash->Mode = HAL_FLASH_QMODE;
HAL_FLASH_SET_QUAL_SPI(hflash, true);
```

收到 0x99 之后片子在 tRST 内不响应任何读，而下一条指令 `HAL_Delay_us` 恰恰要从它
取指。活不活取决于这段代码是否还在 I-cache 里——这就是间歇性的来源。更糟的是
上一行 `up_irq_restore()` 已经把中断放开了，复位窗口里来一个 ISR，向量表和处理函数
同样在 XIP，一样炸。非正常掉电正好让 littlefs 在 mount 时做恢复写入，命中它。

**恢复手段**（修复前需要，修复后一般用不上）　`docs_ble/tools/erase_data.py`：擦 NOR
`0x129A0000:0x400000`（`/data` 的 littlefs 分区），擦完重烧，`sifli_ap.c` 的
`forceformat` 分支会重建文件系统。镜像本体在 `0x12010000..0x125FA000`，与该区间不重叠。
代价是 bond 丢失，但板子发起连接时 SSP 走 user_confirm 自动接受，会自己重新配对
（实测 7 秒内重新拿到 IP），**不需要在手机上「忘记设备」**。

**实际修法**（`vendor/sifli/chips/sf32lb52/sf32lb_flash.c`，无需改 vendor HAL）

1. 把那五行收进 `sf32lb_flash_reset_to_qmode()`，标 `SF32LB_FLASH_RAMFUNC`；
2. `HAL_Delay_us` 换成 `sf32lb_flash_spin_us()`——同样 `.ramfunc`，计数器在栈上，
   按最高主频留足余量（宁可等久一点）；
3. 整个窗口用 `up_irq_save()/up_irq_restore()` 包住，堵掉 ISR 那条路；
4. 顺手把「确保可写」那处裸调的 `HAL_FLASH_CLR_PROTECT()` 也纳入 IRQ 保护，与
   驱动里其它 erase/write 路径的写法对齐。

反汇编验证窗口内已无 XIP 分支：

```
20021aec  bl 20020a4e <HAL_FLASH_ISSUE_CMD>
20021af6  bl 20020a4e <HAL_FLASH_ISSUE_CMD>
20021afc  bl 20021aa8 <sf32lb_flash_spin_us>
20021b0c  bl 2002103c <HAL_FLASH_SET_QUAL_SPI>
```

代价：SRAM 90.48% → 90.51%（+184 B）。

**还需要真机确认**　~~这一步只做到「代码层面窗口内不再碰 XIP，且二进制可证」。~~
**已在 Round 16 验证**：`logs/p0_wdogcut.py` 用 `wdog` 触发看门狗硬复位，同时在窗口里
连续 39 次写 `/data`，保证复位落在 littlefs 的 NOR 写/擦中间。5 轮全过：`assert=0`、
每轮都 `littlefs mounted on /data (persistent)`、一次都没退化到 `forceformat`、
桌面正常起来。日志 `logs/r15_wcut1..5.log`。

---

## P1 — 影响产品完整度

### 24.2 本机蓝牙地址是硬编码假值

手表对外显示 `cd:ab:78:56:34:12`，设备名 `Agent-Watch-cd:ab:78:56:34:12`，bt-pan 网卡
MAC 也是它。多台设备同时在场会撞地址；而 `pan_get_device_id()` 用
`SHA256(BD_ADDR)[0:16]` 生成 Device-Id/chip_id，假地址意味着**所有设备的 Device-Id
相同**，一旦接入需要设备标识的云侧服务就会出问题。

入口：LCPU 的 NVDS 区（`sf32lb52_bt_adapter.c` 里
`SF32LB52_BT_NVDS_BUF_START 0x2040FE00`、`SF32LB52_BT_NVDS_PATTERN`）。产品化做法是
每台设备烧片时写入唯一 BD_ADDR，或从芯片 UID 派生。

不阻塞当前功能，配对与 PAN 都正常。

### 24.3 吞吐没有实测数字（本轮放弃）

镜像里没有 iperf，也没有 wget / webclient，`ping` 只能测延迟不能测带宽。要出数字需往
defconfig 加 `NETUTILS_IPERF` 或一个 HTTP 下载客户端；当前 SRAM 占用 90.48%、余量约
37 KB，加进去要重新核 SRAM 预算（见 19 号文）。

**已知的间接指标**：协商 L2CAP MTU 1691，接口 MTU 1500，1500 字节包能连续跑，60 分钟
长稳里大包丢包约 4.4%、小包 0.33%，RTT 均值 159 ms（120 次统计）。

注意 22 号文规则 3 的约束：**靠加大 ACL 包长提吞吐这条路已经没有余量**（487 已是邮箱
ring 上限），要提只能做分片流水与链路参数（sniff / QoS）。

---

## P2 — 工程卫生

### 24.4 `vendor/sifli` 里有被跟踪的构建产物

```
boards/sf32lb52/drivers/cmake_install.cmake
boards/sf32lb52/drivers/input/cmake_install.cmake
boards/sf32lb52/drivers/lcd/cmake_install.cmake
```

这三个文件被 `ninja` 改写，内容是**上次构建目录的绝对路径**（`CMAKE_INSTALL_PREFIX`
指向 `out/xxx/staging`），因此每次换构建目录它们就变「脏」。本来不该入库。

处理方式：向 `open-vela/vendor_sifli` 提一个 `git rm --cached` + `.gitignore` 的清理 PR。
**给上游仓提 PR 前记得别把这三个文件的本地改动带上。**

### 24.5 团队仓 `README.md` 仍是组委会模板

团队仓 README 第六节要求提交前替换成自己的作品说明（作品名、赛道、运行方式、简介）。
作品尚未完成，暂不写。

另外 `TASK_STATUS.md` 关于 PAN 的记录已过期（还写着「PAN vs SLIP 待定」「官方未实现
PAN」），改 README 时一并更新。

### 24.6 大赛要求的其余交付物尚未准备

按《大赛总览》，除代码与 AI 日志外还需要：介绍文档（.docx/.pdf/.pptx）、5 分钟以内的
演示视频；若使用 AI Coding 则**至少提供一个可复用的 Skill**。这些都还没做。

三个公共仓的 PR 已提交（zblue#231、sifli#29、bluetooth#591，CI 全绿、MERGEABLE，等组委会
review），索引见 23 号文。

### 24.7 蓝牙设备名：宿主侧可改，空口上报改不动（P2）

**现象**（2026-09-16 实测）：手机扫描列表里显示 `Agent-Watch-cd:ab:78:56:34:12`，
即使在开机后无竞争地重写名字也一样：

```
nsh> bttool
bttool> get name
[bttool] Local Name:小云手表           <- 宿主侧已是产品名
bttool> set name 小云手表
[bttool] Local Name:小云手表 set success
# 手机重新扫描「可用设备」-> 仍是 Agent-Watch-cd:ab:78:56:34:12
```

**已核实的机制**：zblue 开了 `CONFIG_BT_DEVICE_NAME_DYNAMIC=y`，
`bt_set_name()` 会 `bt_settings_store_name()` 落盘 + `bt_br_write_local_name_mc()`
下发 HCI；SAL 侧 `bt_sal_set_name()` → `bt_set_name()` 也在（`sal_adapter_interface.c:819`）。
**PAN 服务在适配器上电时设的 `<前缀>-<MAC>` 确实上了空口**（手机能看到它），
说明这条路本身有效；但 App 侧后续的覆盖没有反映到空口。

**判断**：控制器（LCPU）侧的名字存储/生效时机与宿主侧不同步，属平台层行为，
应用层无法保证。未继续深挖（设备名不属大赛硬性要求）。

**根治路径（按代价排序）**：
1. 改框架 PAN 服务的前缀并去掉 MAC 后缀（`frameworks/connectivity/bluetooth/service/
   profiles/pan/panu_service.c` 的 `BT_NAME_PREFIX` 与 `pan_set_local_name_with_mac()`）
   ——这是唯一已验证能影响空口的路径，属公共仓改动，须走 fork + PR（同 23 号文的三项修复）。
2. 查 LCPU NVDS 里是否有名字字段（`vendor/sifli/chips/sf32lb52/sf32lb52_bt_adapter.c`
   的 NVDS 默认表），若能写入则在 BT 初始化前设名字——需供应商文档支持。

### 24.9 免打扰窗口按 UTC 判定（Round 30 实测）

> 与 24.7 的设备名问题无关，是 Round 30 调「空闲关怀」演示时新发现的。

`pet_care` 的静默期判定走 `care_local_hour()` → `localtime_r()`，但本构建
**未开 `CONFIG_LIBC_LOCALTIME`**，`localtime_r` 原样返回 RTC，而 RTC 是 SNTP
写入的 UTC。实测：北京时间 23:44 时设备 `date` 显示 `Sat, Sep 19 15:44:36 2026`。

**后果**：`CONFIG_AI_AGENT_CARE_QUIET_START=22` / `..._QUIET_END=7` 实际压制的是
**北京时间的 06:00–15:00**，与用户作息错位 8 小时。

**两层含义**：
- 演示口径（今晚）：UTC 15 点不在静默区，北京夜里拍「空闲关怀」不受影响。
  **不要为了「修正」它而在拍摄前改时区**——一改，这幕立刻被静默掉。
- 根治方向：给 `pet_care` 一个显式时区偏移（Kconfig 常量，或按 `time()` 语义
  在 `care_local_hour()` 里加 8 小时）。注意修完静默区就回到 22:00–07:00 北京，
  夜间拍摄必须把 `CARE_QUIET_START/END` 一并调整。

**当前处理（已决策 2026-09-16）**：走选项 A —— **接受现状**。
保留 App 侧覆盖（宿主侧名字正确、便于本地排查），交付文档中说明「手机显示名可能仍是
框架默认名」；不为此新增公共仓 PR（三项平台修复的 PR 链路已经够长）。
若赛后产品化，按上面路径 1 改框架前缀即可。

### 24.8 云端 LLM 配置会被清空（P1，已定位到写入路径但未根治）

**现象**（2026-09-16 两次）：`/data/ai_agent/config/config.json` 只剩网络组件写的两项，
云端 backend 与 API key 消失：

```
{"net.dns_primary":"223.5.5.5","net.dns_secondary":"8.8.8.8"}
```

表现为 `router_status` 的 `backend_count: 0`，对话静默回退端侧模型（`backend=-1`）。

**已排除**：
- 已加**原子写**（写 `config.json.tmp` 再 `rename`）后仍然复发 → 不是「写一半留下残缺文件」。
- 全仓只有 `config_store.c` 会打开 `AGENT_CONFIG_FILE` 写入；网络组件用的是
  合并安全的 `claw_config_set()`（加锁 + 读-改-写）。

**判断**：落在 `claw_config_set()` 的读-改-写里——**读取时拿到空对象**（文件缺失/为空/
解析失败），随后合并保存就只写回本次的键（DNS 恰是网络组件在启动/联网时写的）。
`config_store.c` 的 `load_json()` 已改为解析失败时打 `LOG_ERR`（此前静默），
所以下一步只需**抓一次启动日志**看是否出现该报错，即可区分「解析失败」与「文件当时为空」。

**规避（演示口径）**：重启后先读一次 `router_status`；若 `backend_count: 0`，重新执行：

```
nsh> ping -c 10 223.5.5.5        # 占住 NSH，让 vela> CLI 收到输入
set_llm https://api.stepfun.com/v1/chat/completions step-3.7-flash <key>
```

**根治方向**：`claw_config_set()` 在 load 失败时不要用空对象覆盖——应当**先备份坏文件再重建**，
或改为「只改不动其它键」的追加式写入。
