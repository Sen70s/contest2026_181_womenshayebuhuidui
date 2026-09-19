# docs_ble 验证日志

## Round 1（2026-08-14，代码级验证）

- 新建 bletest 分支（contest 仓 + packages/ai_agent 仓），旧内容完整保留（dev-branch 快照提交）。
- 结论 1：PAN/BNEP 不可用（zblue 无 BNEP SAL、无 CONFIG_BLUETOOTH_PAN、LCPU 固件不支持 BREDR）；
  BLE IPSP/6LoWPAN 暂不可用（zblue 无适配层、Android 不开放 IPSP）→ 自研 GATT NUS+TUN 代理是唯一路径。
- 突破 2：定位并修复设备端分片缺陷（ble_gatt_send 对 >MTU-3 报文直接 -EMSGSIZE，所有 IP 包被丢）→
  8×1600B 队列 + on_notify_complete 逐片发送。
- 突破 3：App 端补齐 MTU 协商（requestMtu(247) + 按 MTU-3 分片，回退 20B）。
- 突破 4：定位并修复 TUN 无默认路由缺陷（0.0.0.0/0 → 192.168.55.1）。
- 固件两轮编译通过（SRAM 463016/524288 = 88.31%）。
- 阻塞：① App 编译需可写 ~/.gradle 的环境；② 真机验证需连接开发板（当前无串口设备）。

## Round 2（2026-08-15，App 协议层修复 + 编译验证）

- 突破 5：解决 App 编译阻塞 —— GRADLE_USER_HOME 迁至工作区可写目录（2.7G 缓存拷贝），
  assembleDebug --offline 构建成功，MTU 协商修改编译通过。
- 突破 6：定位并修复 App TcpProxy 5 处协议缺陷（见 05_app_proxy_fixes.md）：
  A) 纯 ACK ack=0 → 设备数据永不确认、无限重传；B) ACK seq 用错流 → NuttX 整段丢弃；
  C) SYN-ACK 后 serverSeq 未 +1 → 首段数据错位截断；D) DNS 应答 src/dst 写反；
  E) 失败 RST ack=0 → 设备 SYN 重传超时；F) 新增 ICMP echo 应答（链路自检）。
- 突破 7：核对设备侧 NuttX 行为与代理假设一致 —— tcp_input.c 段接受逻辑
  （seq==rcv_nxt 才处理）、TUN 软校验和、UDP 校验和 0 豁免 —— 两端协议握手成立。
- 突破 8：定位设备端启动竞态 —— ble_gatt_net_init 只调用一次，bluetoothd 未就绪时
  GATT 通道永远起不来（手机无法连接）；改为 5 次 × 3s 重试（network_manager.c）。
- 固件：重试修复后 ninja 编译通过；App 修复后再次构建通过。
- 核对：GATT notify 目标按 UUID 匹配 value 属性（zblue foreach_attr），
  attr_handle+srv_id 元素索引一致，通知路径无隐患。
- 阻塞：真机验证仍需连接开发板；App 安装包 app-debug.apk 已生成
  （com.agent.coapp-main/app/build/outputs/apk/debug/）。

## Round 3（2026-08-15，主机端全链路仿真验证）

- 突破 9：编写 docs_ble/tools/tunnel_sim.py —— 无真机条件下的端到端协议仿真，
  精确镜像两端已修复逻辑（帧协议/分片/NuttX 严格 seq 接受/代理 ACK 语义/DNS 方向/ICMP）。
- 突破 10：仿真 5 项测试全部 PASS、0 丢包 —— HTTP GET、64KB 大文件（跨多帧+多分片+
  seq/ack 推进）、ICMP ping（MTU 247 与 MTU 23 回退路径）、DNS 转发（方向修复验证）。
- 意义：协议设计层已闭环；剩余验证项只剩真机 BLE 射频/协议栈行为（GATT 连接、
  MTU 协商、notify 完成回调时序、LCPU 固件兼容性）——需物理板卡。
- 突破 11：回归测试（legacy_bugs 模式复现 Round2 前缺陷）→ 旧代码 0B 数据 + 3 丢包，
  证明仿真有判别力、修复必要且有效。
- 突破 12：web 检索恢复可用，定位 zblue 栈 3 个未启用性能开关
  （AUTO_UPDATE_CONN_PARAMS / PPCP / AUTO_DATA_LEN_UPDATE）+ 收集 NUS 吞吐文献
  （连接间隔是 4KB/s 瓶颈的实锤案例等），整理为 07 文档 + 真机试验矩阵。
- 突破 13（真机，8-15）：**BREDR 闸门 0 通过** —— LCPU 固件支持 BREDR！
  真机实测：HCI RESET/Features/Version 全响应、适配器 ON（state 4）、
  get addr 返回 CD:AB:78:56:34:12、**inquiry 发现 BREDR 设备**（Inquiry Result
  事件 04 02 ... a4:d1:fe:b3:xx 重复上报）、SPP 栈初始化成功。
  推翻 docs/11 的"BREDR 不支持"结论（此前失败是 CRLF+僵尸会话工具链问题）。
- 踩坑记录（工具链，真机调试必读）：
  ① bttool 是交互式工具，命令不带前缀；② 发送命令必须只带 \n（\r 残留导致
  UnKnow command）；③ create instance error = bluetoothd 没启动（rcS 为空，
  需手动 bluetoothd &）；④ 僵尸 bttool 会话命令表为空连 quit 都无效，
  只能物理拔插 USB 复位（RTS 复位不可靠）。

## Round 4（2026-08-15，PAN 实现）

- 突破 14：确认手机 a4:cc:b3:fe:d1:a4 被 inquiry 发现（HCI 事件 LSB-first 字节
  a4 d1 fe b3 cc a4 反转即手机地址）—— BREDR 双向链路确认。
- 突破 15：**自研 BNEP SAL 层完成并编译通过**（docs_ble/10）：
  sal_pan_interface.c/h（~500 行）：L2CAP BR PSM 0x000F + Setup 握手 +
  FRAME_ETH 数据路径 + 状态机；CONFIG_BLUETOOTH_PAN=y 启用；
  panu_service/bt_pan/bt_socket_pan/tools-panu 全部激活。
  SRAM 90.35%（+10KB）编译通过。
- 待办：真机验证 pan connect 手机 NAP → bt-pan → 上网（固件已含 PAN，需烧录）。

## Round 4-2（2026-08-15 深夜，PAN 真机调试）

- 突破 16：**L2CAP BR PSM 0x000F 连接被手机接受**（docs_ble/11）：
  完整链路 ACL up → 加密 level 2 → L2CAP connect ret=0 → 手机 CONN_RSP
  result=0x0000 SUCCESS（dcid=0x0072）。阻塞推进到 CONFIG 阶段。
- 修复 1（EEXIST 根因）：pan_conn_t.chan 类型错误——zblue BR_CHAN() 宏按
  bt_l2cap_br_chan 布局越界访问 psm（calloc 垃圾值非零）→ 改 bt_l2cap_br_chan。
- 修复 2：worker ACL up 等待 3s→10s 轮询（远端 page 可超 3s）。
- 修复 3（最新）：br_chan rx.mtu 未初始化=0 → l2cap_br_conf 发 MTU=0 配置 →
  手机回 CONF_RSP UNACCEPTABLE_PARAMS → 断链。已初始化 rx.mtu=672 + sec L2。
- 修复 4：LCPU bth4 RX/TX 打印 8→40 字节（可见完整 L2CAP 信令）。
- 待办（明天）：抓我方 CONF_REQ 完整字节分析被拒原因（嫌疑：MTU option、
  zblue conf 交互、或需显式 MTU=672 option）；目标 CONFIG 通过 → BNEP Setup →
  state:2 CONNECTED → 数据面。
- 环境要点：手机「蓝牙网络共享」必须开启（否则手机直接断 ACL reason 0x13）；
  烧录用 logs/flash_rts.py + for 重试 2-3 次（RTS 时序不稳）。

## Round 4-3（2026-08-15 深夜，加密关口根因定位）

- 突破 17：**加密不完成的根因 = link key 不持久化**。
  完整 HCI 解码（/tmp/pan_raw.log 17:45 trace）：
  [pan] worker: request encryption
  01 11 04 02 80 00        = Authentication_Requested (0x0411) h=0x0080
  04 0f 04 00 ...          = CC status=0
  04 16 06 a4 d1 fe b3 cc a4  = Link_Key_Request (0x16) —— 控制器无此设备密钥
  01 0c 04 06 a4 d1 fe b3 cc a4  = Link_Key_Neg_Reply (0x040C) —— zblue RAM 也无密钥
  [bttool] Pair Display [A4:CC:B3:FE:D1:A4][BREDR][PIN] please reply:
  —— 降级 legacy PIN 配对，等控制台输 PIN（无人应答）
  [pan] worker: timeout, L2CAP anyway → 未加密通道发 CONNECT_REQ → 手机拒绝
- 根因链：zblue 的 BR link key 只存 RAM（br_key_pool，CONFIG_BT_MAX_PAIRED=1）；
  持久化只在 CONFIG_BT_SETTINGS 下生效，而本移植（external/zblue port）没有
  settings 子系统（port/subsys 无 settings）→ 每次重启密钥即失 → 每次连接都要
  重新 legacy PIN 配对。控制器（LCPU 闭源固件）也不存 key，回 Link_Key_Request
  向 host 要。
- 对策（无需改代码即可验证）：**同一启动内先配对再 pan connect** —— 配对完成
  后 zblue RAM 里有 key，Link_Key_Request 会被 16 字节 key 应答 → 加密完成 →
  security_changed(level=2) → L2CAP → BNEP。zblue 路径已验证：
  ssp.c bt_hci_link_key_req → conn->br.link_key || bt_keys_find_link_key() →
  link_key_reply()；配对 store 点在 ssp.c:531 / smp.c:890。
- 配对应答链路：bttool pair pin <addr> 1 0000 → bt_device_set_pin_code_async →
  adapter_set_pin_code（要求 BOND_STATE_BONDING）→ bt_sal_pin_reply →
  zblue zblue_pin_reply → bt_conn_auth_pincode_entry(conn,"0000")。
- 板载 TAP 桥已完整：panu_service.c pan_tap_bridge_open("/dev/tun", IFF_TAP)
  → "bt-pan" 网卡（MAC=本机 BT 地址）；BNEP FRAME_ETH ⇄ TAP 读写闭环
  （pan_tap_poll_data → bt_sal_pan_write）。
- 待办：① 配对成功后同启动内 pan connect，验证 encryption→BNEP→bt-pan；
  ② 持久化方案（文件式 BR key store，port 层挂钩 bt_keys_link_key_store /
  bt_hci_link_key_req 加载）——突破后实现；③ 上网验证（镜像无 ping/dhcpc，
  需加 CONFIG_NETUTILS_PING 或静态 IP + 包计数）。

## Round 4-4（2026-08-15 晚，可发现性根因与卡死真相）

- 突破 18：**BR「可发现」失效根因 = Write_Scan_Enable 被 H4 驱动 emulate 吞掉**。
  sf32lb52_bth4.c sf32lb52_bt_emulate_cmd() 把 WRITE_SCAN_ENABLE / WRITE_PAGE_SCAN_ACTIVITY /
  WRITE_INQUIRY_SCAN_ACTIVITY / WRITE_INQUIRY_SCAN_TYPE / WRITE_PAGE_SCAN_TYPE /
  WRITE_EIR / WRITE_LOCAL_NAME / WRITE_SSP_MODE / WRITE_CLASS_OF_DEVICE 等整套
  BR 配置命令合成成功响应（不转发 LCPU）→ zblue 以为已设置，控制器实际没开
  inquiry/page scan → 手机列表永远看不到板子（与设置 scanmode 无关，
  adapter_set_scan_mode 缓存命中/EALREADY 静默，且从未有 HCI 命令发出）。
  证据：set scanmode 2 时 bth4 trace 无任何 HCI；而 inquiry 命令（不 emulate）正常。
- 突破 19：**Sifli 官方 SDK 证实 LCPU 支持 BR scan/PAN**：
  docs.sifli.com SDK 的 BT PAN Example（sf32lb52x/example/bt/pan）：
  「The example will enable Bluetooth Inquiry scan and page scan at startup, allowing
  phones and other devices to discover and connect to this device」——手机网络共享开启后
  PAN 自动连接，finsh 命令 pan_cmd conn_pan / weather / pan_cmd ota_pan（BT PAN 下载 OTA）。
  官方示例同样需要手机开「蓝牙网络共享」，默认名 sifli_pan。
- 突破 20：**BLE 广播失败根因 = bttool 的 adv type 语义**：`-t adv_ind` 是 ext 语义
  （convert 强制 BT_LE_ADV_OPT_EXT_ADV），而控制器 LE 特性被 emulate 返回全 0
  （LE_READ_LOCAL_FEATURES 合成全零）→ ext 不支持 → STACK_ERR(status:3)。
  需 `-m legacy`（adv_mode=1 → adv_type += BT_LE_LEGACY_ADV_IND → legacy 路径）。
- 突破 21：**系统卡死真相 = ai_agent 启动崩溃破坏 mm 锁**：
  本会话「!ai_agent &」启动 ai_agent → 立即 assert（ai_agent_main → mallinfo →
  mm_foreach assert，内存不足）→ 后续 bluetoothd 全部 IPC 无响应、串口静默
  （bttool 卡在 system()/IPC）。与 adv 无关。教训：不要在 bttool 会话里启动 ai_agent。
- 其它发现：bluetoothd 的 BT_LOGE 默认编译为空（CONFIG_BLUETOOTH_SERVICE_LOG_LEVEL
  未定义）→ 需在 defconfig 开启才能看 SAL 诊断日志；ncmd_sem 机制（CC 响应 ncmd=0
  不 give → 后续命令停摆）在 emulate 合成响应中 ncmd=1 正常。
- 下一步（用户在场）：① 物理拔插 USB 重启（RTS 复位无效）；② 不要启动 ai_agent；
  ③ `adv start -t adv_ind -m legacy -n Agent-Watch` 开 BLE 广播 → 手机配对(0000) →
  ④ pan connect → 加密(link key 在 RAM) → BNEP → bt-pan → 上网；
  ⑤ 备选：改 vendor bth4 移除 WRITE_SCAN_ENABLE emulate（BR 可发现，官方 SDK 证实
  LCPU 支持），需重编译烧录。

## Round 4-5（2026-08-15 晚，双通道可发现突破）

- 突破 22：**WRITE_SCAN_ENABLE 转发生效**：新固件（bth4 移除 emulate）烧录后，
  `set scanmode 1/2` 真正下发 HCI：`04 0e 04 06 1a 0c 00` =
  Command Status status=0 opcode 0x0C1A（Write_Scan_Enable）——LCPU 接受！
  控制器 inquiry+page scan 开启 → 板子 BR 可发现（Round 4-4 根因修复验证成功）。
- 突破 23：**BLE legacy 广播成功**：`adv start -t adv_ind -m legacy -n Agent-Watch`
  → `04 0e 04 06 0a 20 00`（LE_Set_Advertising_Parameters 0x200A status=0）→
  `[adv] zblue_start_adv: zblue start OK`（新加 syslog）→
  `on_advertising_start_cb status:0` SUCCESS！之前失败因 `-t adv_ind` 是 ext 语义。
- 修复过程记录：flash_rts.py 烧录后板子进 DFU（msh/RT-Thread，dfu_pan）——
  sftool soft_reset 后 boot 链进 dfu_pan；恢复方法：写 openvela_ftab.bin@0x12000000
  （ftab[3]/[7]→0x12010000）+ nuttx@0x12010000（flash_openvela_ftab.py，Round 4-2 流程）。
- 当前状态：BR 可发现 + BLE 广播双通道就绪，等待手机配对（PIN 0000）。

## Round 6-7（2026-08-15 深夜 ~ 08-16 凌晨，加密定位 + SSP 桥接 + 可发现突破）

- 突破 24：**04 13 是 Number-of-Completed-Packets 而非 EncryptChange**——R52 的"归一化"
  破坏合法事件（num 1→0），已删除；真加密事件 0x08 在认证失败路径从不出现。
- 突破 25：**rx_work 事件滞留**——zblue rx_work_handler 单事件+自提交模式在本移植
  丢事件（运行中 k_work_submit 不保证重调度）→ 改为 while 循环排空（0x23 事件曾
  进 bt_recv 但永不处理）。
- 突破 26：**SSP 事件号桥接**——LCPU 用标准号（0x23 IO_CAPA_REQ 等）且按 handle
  （大端）寻址，zblue 用偏移号（0x31/0x32/0x33/0x34/0x36/0x3b）按 bdaddr 寻址；
  bth4 建立 handle→addr 映射并重写事件 → **zblue 首次收到 io_capa_req 并成功回复
  IO_CAPABILITY_REPLY (0x042b)**——SSP 配对流程第一次真实推进（LCPU 随后卡在 IO 交换）。
- 突破 27：**可发现性最终修复**——zblue enable 只开 page scan（0x0c1a 参数 0x02）；
  bth4 强制 inquiry 位 + 100% 占空比 scan activity（0x0800/0x0800）+ interlaced 扫描
  类型 → **手机首次在蓝牙列表看到板子（cd:ab:78:56:34:12 = 板子 BR 地址）**。
- 突破 28：**inquiry 探针验证射频**——bth4 enable 后自动发 GIAC inquiry（10s）→
  LCPU 返回 Inquiry Result（手机 a4:d1:fe:b3:cc:a4）——BR 收发链路完全正常。
- 发现：bluetoothd bt_list_add_tail malloc NULL 崩溃（storage 写坏）→ 每次测试前
  rm -rf /data/misc/bt；崩溃 assert 停机需 USB 拔插恢复。
- 现状：手机可搜到板子但尚未完成配对；LCPU 名字字段存地址（Write_Local_Name 未生效）。
- 下一步：手机点板子配对（SSP 弹窗确认）→ link key → pan connect（跳过加密已实现）
  → BNEP → bt-pan → 上网。详见 docs_ble/15_r52_r62_breakthrough.md。

## Round 8（2026-08-17 下午，恢复调试固件 + inquiry 双缺陷修复）

- 背景：用户反馈"从未连接上板子，配对时显示无法通信"。探针发现板上跑的是无蓝牙的
  交付固件（bluetoothd/bttool 均不在 builtin 列表）→ 重烧 R62 调试固件恢复环境。
- 突破 29：**inquiry 崩溃根因 = zblue 写死 num_rsp=0xff + LCPU 拒答 + 断言致死**：
  ① br.c:1033 `cp->num_rsp = 0xff`，本 LCPU 固件对 num_rsp=0xff 的 Inquiry 连
  Command Status 都不回（R61 注入 num_rsp=0x00 却有 Inquiry Result）——BT 规范
  0x00 才是 unlimited；② 超时后 bt_hci_cmd_send_sync 的 BT_ASSERT 直接 Kernel
  oops 杀死 bluetoothd（sysworkq 上下文）。此前 R61 探针注入的 inquiry 与栈自身
  discovery 重叠加剧了 LCPU 沉默。
- 修复 A：vendor bth4 移除 R61 探针注入（诊断使命完成，纯致害）。
- 修复 B：zblue hci_core.c bt_hci_cmd_send_sync 两处 BT_ASSERT → 返回 -ETIMEDOUT
  （控制器超时是错误不是致命故障，bluetoothd 必须存活以便重试）。
- 修复 C（R63）：bth4 发送路径拦截 Inquiry(0x0401)，num_rsp!=0 强制改写为 0x00。
- 真机验证进展：R63 首跑 enable 阶段 0x1009(Read_BD_ADDR) 超时（LCPU 偶发沉默，
  前两把均正常应答）→ 二跑撞上已知 bt_list_add_tail 断言（kill bluetoothd 路径）
  → RTS 复位不彻底板子静默。待 USB 拔插后继续：干净会话 → enable → inquiry →
  createbond（预期 SSP 事件桥接链路首次完整走通）。
- 已知规避：不 kill bluetoothd（用断电获得干净会话）；探针脚本 ssp_trace_probe.py。

## Round 9（2026-08-17 晚，配对完全打通 🎉 —— "无法通信"总根因歼灭战）

- 突破 30：**板上固件曾被主线交付版覆盖**（无 bluetoothd/bttool）→ 重烧调试固件恢复。
- 突破 31：**inquiry 双缺陷修复**：
  ① zblue br.c num_rsp 写死 0xff，LCPU 对 0xff 拒答（连 CS 都不回）→ bth4 改写为 0x00；
  ② send_sync 超时 BT_ASSERT 直接 Kernel oops 杀死 bluetoothd → 改为返回 -ETIMEDOUT。
- 突破 32：**R59/R62 注入的 scan-activity 命令引发 LCPU Hardware Error(04 10 01 00)**
  并使其对所有 BR 操作沉默 → R64 全部移除（保留纯参数改写的 scan enable inquiry 位）。
- 突破 33：**R57 桥接表根本性错误**：0x23 是标准 Read_Remote_Extended_Features（13 参数
  完全吻合），被误当 io_capa_req 劫持 → zblue 永远等不到 features 完成 → 配对流程根本
  不启动。R65 重写为纯事件号映射（0x24→0x31, 0x25→0x32, 0x26→0x33, 0x27→0x34,
  0x29→0x36），参数原样透传（两边都是 bdaddr 寻址）。
- 突破 34：bt_list_add_tail malloc 失败断言杀 daemon → 改为降级告警（SRAM 90.9% 逼近）。
- 突破 35：**手机侧发起配对也失败（LMP 0x22）→ 排除"角色"因素，锁定事件流本身**。
- 突破 36（终极根因）：**zblue 的 Set_Event_Mask 用偏移事件号算位（IO_CAPA_REQ=BIT(48)…），
  而 LCPU 是标准控制器，SSP 事件 0x24..0x2b 对应标准位 35..42** → SSP 事件全部被
  mask 屏蔽 → LCPU 从不上报 IO_Capability_Request → host 无法应答 → 认证超时失败。
  板侧发起=Auth Complete 0x05；手机发起=LMP Response Timeout 0x22（手机显示"无法通信"）。
  与 Round 4-3 以来所有现象吻合（旧固件 emulate 掩盖了此问题——mask 从未真正下发）。
- 修复 R69：bth4 send 顶层拦截 Set_Event_Mask(0x0c01)，强制置位标准 SSP 位
  （octet4 |= 0x78, octet5 |= 0x05），并把 0x0c01 从 emulate 表移入"转发+合成CC"名单。
- **战果（2026-08-17 20:24，首次完整 SSP 配对）**：
  io_capa_resp(手机 cap=1 auth=3) → io_capa_req → user_confirm_req passkey=633589
  （bttool g_auto_accept_pair=true 自动确认，手机侧亦有弹窗）→
  **BOND_NONE → BONDED** → Link_Key_Notification(0x18) → **br_key store: 1 key(s)**
  （P2 文件持久化闭环，/data/misc/bt/br_key.bin）。
- 待办：① PAN e2e（pan connect→bt-pan→dhcp→ping）——首跑撞上串口挂死+烧录 rc=101，
  板子需 USB 拔插后继续；② 重启后验证 br_key.bin 免配对回连；③ P4 应用层状态机。
- 工具沉淀：ssp_trace_probe.py（createbond 全事件抓取）、phone_pair_watch.py（被动监听）、
  legacy_pin_pair.py、pan_e2e.py。

## Round 9（2026-08-18/19，BNEP 全链路打通 + 配对根因总修复）

### 阶段1：蓝牙栈稳定性修复
- R64：移除 R61 探针注入（与栈 inquiry 重叠导致 LCPU 静默 + Kernel oops）
- R65：重写 SSP 事件桥接——原 R57 把 0x23（Read_Remote_Ext_Features，标准事件）误当
  io_capa_req（LCPU 私有）→ 桥接劫持了 zblue 原生事件 → zblue 永远等不到 features
  完成 → createbond 不启动。改为纯事件号重映射（0x24→0x31, 0x25→0x32, 0x26→0x33,
  0x27→0x34, 0x29→0x36），0x23/0x28/0x2b 不再劫持。
- R66：bt_list OOM 断言改为降级告警（SRAM 90% 边缘 malloc 失败不再杀 daemon）
- R69：zblue Set_Event_Mask 用偏移位 BIT(48..53)，LCPU 标准控制器需 BIT(35..42) →
  SSP 事件被屏蔽。bth4 拦截 0x0c01 强制标准位（octet4|=0x78, octet5|=0x05）+ bit7
  (0x08 Encrypt Change)。
- R69 发现：Set_Event_Mask 在 emulate 表里被合成 CC 直接返回，改写代码永远执行不到 →
  移出 emulate 表到"转发+合成 CC"名单。

### 阶段2：BNEP 连接与 SDP
- R76：注册 PANU SDP Service Record（BT_SDP_PANU_SVCLASS + PSM 0x000F + BNEP 协议描述）。
- R85：L2CAP deferred 机制——板子 BNEP 通道先 LCONF_DONE → 等手机通道也 CONNECTED
  再发 BNEP setup，避免信号通道死锁。
- R89/R91：SDP record 精简——移除 SupportedNetworkAccessTypeList/SupportedFeatures 避免
  zblue SDP server continuation state 格式错误（03 f0）导致手机误判 PANU 不完整。
- R94：BNEP Setup Request 改用 128-bit NAP UUID（00001116-0000-1000-8000-00805f9b34fb）。
- R96：接受 HyperOS 4字节 BNEP Response（标准 6 字节），单字节状态码解析。
- **R96 后状态**：手机 BNEP Response status_byte=0x00 → 视为 SUCCESS → bt-pan 接口 UP。

### 阶段3：LCPU 硬件限制与 Inquiry
- inquiry scan activity 注入（Write_Inquiry_Scan_Activity）任何值均触发 Hardware Error 0x00
  → 完全移除注入。只靠 Write_Scan_Enable inquiry bit 强制开启（R60）。
- Write_SSP_Mode=1 后 LCPU 不走 legacy PIN（原 Round 4-3 有 PIN 弹窗），SSP IO 交换
  因 mask 位错长期未生效。R69 修正后 SSP 数字比较成功。
- inquiry num_rsp 0xff → 0x00（bth4 拦截）：LCPU 对 0xff 不回 CS。

### 阶段4：PAN 数据面验证（R99，2026-08-19 首次完整 e2e）
- 配对：io_capa_req(0x24→0x31 桥接) → io_capa_resp → user_confirm_req(passkey) → BONDED
- 加密：encrypt_change enc=1 + security level=2
- BNEP：conf_rsp SUCCESS → chan_connected → BNEP setup(128-bit NAP UUID) → 手机 Response
  status=0x00 → `pan_netif_state_cb ifname:bt-pan, state:1`（接口 UP）
- 待完成：bt-pan DHCP + ping（窗口期脚本问题——BNEP 在等待期建立后手机超时断链）

### 当前阻塞
- 手机侧"无法通信"已彻底解决（R69 mask + R65 桥接 + R96 接受 4 字节 Response）。
- BNEP 连接已稳定建立（R99 验证）。
- **当前卡点**：bt-pan UP 后需要立即跑 DHCP/ping，但手机约30s 后超时断链。
  需要写一个 BNEP 成功瞬间立即切 nsh 的脚本（pan_instant.py 已写，检测逻辑修正后待测）。
- 另一个方案：手机蓝牙共享设置里授权设备（HyperOS 可能需要显式授权该设备上网）。

## Round 10（2026-08-20，开机自启后的 HardFault 根因清理）

- rcS 改为自动 `bluetoothd &` + `ai_agent &` 之后，开机约 2 s 必崩：
  `BT LW WQ` 线程（process: bluetoothd）在 arm_hardfault.c:186 断言，控制台随后静默，
  只能物理拔插 USB 才能重新烧录。
- 调试镜像补上故障诊断开关（`DEBUG_HARDFAULT_ALERT`/`BUSFAULT`/`USAGEFAULT`/
  `ARCH_STACKDUMP`）与 `BOARD_RESET_ON_ASSERT=2`——断言后自动重启，既能重复取日志，
  也自动重开 SFBL 下载窗口。改动走 `tools/mk_pandbg_config.sh` 生成器，不用
  savedefconfig（它会 copy_if_different 回源 defconfig 并抹掉注释）。
- 根因：zblue 的 `z_sys_init()`（SYS_INIT 表）被跑了两遍——`sf32lb52_bt_initialize()`
  开机跑一次（group 0），`bt_sal_init()` 在 bluetoothd 里再跑一次（group 8）。
  表里的 `k_sys_work_q_init`/`long_wq_init` 用 `K_THREAD_STACK_DEFINE()` 的静态数组建
  线程（移植层 `pthread_attr_setstack`），第二遍等于两个线程共用同一块栈，且
  `k_work_queue_start()` 会重初始化已有等待者的信号量。dump_tasks 里两组 sysworkq /
  BT LW WQ 的 STACKBASE 完全相同，是直接证据。
- 修复：① bth4 不再调 `z_sys_init()`（HCI 的 fd 在 `h4_open()` 里拿，NuttX fd 表按
  task group，必须留给 bluetoothd）；② `z_sys_init()` 按 owner pid 探活幂等
  （不能用一次性标志：工作队列线程是调用者的 pthread，随进程一起死，bluetoothd
  重启后必须重跑）。
- 连带线索：bth4 里「为绕开 sysworkq HardFault 才把 SSP 搬进驱动」的历史 workaround，
  很可能是同一根因；按最小修复先行本轮不动。
- 详见 `18_zblue_sys_init_duplicate_fix.md`。

## Round 11（2026-08-21，PAN 上网彻底打通 🎉 —— 三个平台移植层根因）

- BNEP TX 的 `tailroom=249` / `encode failed -2` 卡了好几轮，根因不在 MTU 配置，而是
  **`pan_tx_pool` 从没注册进 zblue NuttX port 的 `_net_buf_pool_list[]`**。`pool_id()`
  遍历该数组反查指针，找不到时 `__ASSERT` 在 release 下被编掉、静默返回 0，于是 buf
  带着 `pool_id=0` 去 `fixed_data_alloc()`，拿的是 `_net_buf_pool_list[0]`
  （`discardable_pool`，258 → tailroom 249）的尺寸**和它的 data_pool 基址**。
  R75/R78 那个「3 字节 Setup Request 在线上变 8 字节垃圾」也是同一件事——不是
  `NET_BUF_POOL_FIXED_DEFINE` 坏了，是缺注册。SPP 的 `rfcomm_tx_pool` 同坑。
- 修完池，DHCP 一发就 `Hardware error, hardware code: 0`，之后 LCPU 停回 NoCP、ACL
  信用耗尽、刷 `Unable to allocate buffer within timeout`。根因是**邮箱 ring 分块写入
  会回退 LCPU 的读指针**：`read_idx_mirror`（LCPU 写）和 `write_idx_mirror`（HCPU 写）
  同一条 D-cache line，`ring_write()` 结尾的 `up_clean_dcache()` 把陈旧 read_idx 一起
  写回；旧代码先单独写 1 字节 H4 type 并触发中断，精确打开了这个窗口。改成整帧一次
  写入，并把 bth4 合成的 ACL_Data_Packet_Length 压到 `492-1-4=487`（ring 可用 492）。
  比 487 长的 PDU 由 zblue 正常 ACL 分片，`ping -s 1472` 验证这条路径。
- DHCP 拿不到租约的第三件事：Android dnsmasq 用**单播** OFFER 回 yiaddr，而 bt-pan
  还是 0.0.0.0，`ipv4_input()` 无从匹配。置 `CONFIG_NETUTILS_DHCPC_BOOTP_FLAGS=0x8000`
  （RFC 1542 广播位）后一次成功。
- 数据一流动 NSH 就在 `getenv()` HardFault，且 `.bss` 一变崩点就转移：`up_allocate_heap()`
  把 SRAM 顶 0x2007FB00..0x20080000（两个邮箱 buffer + custom config）算进了堆，
  LCPU 写 HCI 字节 = 直接改写 malloc 出来的对象。堆上界限到 0x2007FB00；为补回
  1,280 字节，`pan_tx_pool` buffer 数 4→2（净 +2.2 KB）。20 号文里 unqlite 文件句柄
  被写坏那个案子是同一根因家族。
- 开机自动连接补两处：`last_nap` 缺失时从 bond list 回退；连上后回写 `last_nap`。
  旧固件配过对的表以前冷启动永远不自动上网。
- 验证（正式 ai_agent 镜像，非 pandbg）：Gate B 网关/公网/DNS/1472B 全 0% 丢包；
  Gate D 手机侧断链后自动重连并重新 DHCP；Gate E 冷启动无人干预 `dhcp_ok` +
  `netmgr Active channel: bt-pan (primary)`；Gate F 两分钟流量前后堆用量无增长。
  SRAM 474,360 B / 90.48%。
- 遗留（与 PAN 无关，未修）：XIP 下 NOR 写路径未全部 RAM 驻留——
  `sf32lb_flash_preinit_runtime()` 自己是 RAMFUNC，但它调的 `HAL_FLASH_PreInit` /
  `ISSUE_CMD(RST)` / `SET_QUAL_SPI` 都在 XIP flash 里，等于给自己取指的 flash 发
  RESET、切模式，靠 I-cache 侥幸活着。非正常掉电后 littlefs mount 的恢复写入会命中，
  开机间歇性 HardFault 且一旦中招每次都中。恢复用 `logs/erase_data.py`
  （擦 `0x129A0000:0x400000`）。修复方向：整条调用链 `__ramfunc` 化。
- 吞吐仍无数字：镜像里没有 iperf / wget，ping 只能测延迟。
- 长稳 60.6 分钟/60 轮：0 assert、0 断链，56B 丢包 0.33%、1472B 丢包 4.44%（分片链路
  正常水平，未随时间恶化），堆用量首尾持平（1,100,304 → 1,100,296）。判定通过。
- 详见 `21_pan_breakthrough_authoritative.md`（权威版，取代 10/11/17 里关于 BNEP TX
  失败的推测性结论）。

## Round 12（2026-08-21，交付整理：指南文档 + 各仓归位）

- 写 `22_pan_engineering_guide.md`：把 11 轮沉淀提炼成四条硬规则（net_buf 池必须注册、
  SRAM 顶 0x2007FB00 以上不属于我们、一个 H4 帧必须一次写进 ring、串口 RTS 接板子电源）
  + 症状→先查什么对照表 + 真机验证口径 + 大赛交付路径 + 历史坑一句话版。
- **纠正一个误判**：上一轮以为 `frameworks/connectivity/bluetooth` 不受 git 跟踪（因为
  `frameworks/connectivity/.gitignore` 有 `/*/`），把两个 PAN 文件做成了
  `docs_ble/fw_patches/` 快照。实际上那个目录本身就是独立 repo project
  （`openvela.xml:152`，`frameworks_bluetooth`），父仓忽略它正是因为 repo 单独 checkout。
  已直接在该仓 `bletest` 分支提交 `ec9ad5c5`，并删掉团队仓里的代码副本。
- 大赛同步审计结果：4 个 `<linkfile>` 全部在位且指向团队仓；`ai_agent/defconfig` 与
  `out/nuttx_contest_board_ai_agent/.config` 关键符号逐一一致（`BT_L2CAP_TX_MTU=1691`、
  `BT_BUF_ACL_TX_SIZE=1695`、`NETUTILS_DHCPC_BOOTP_FLAGS=0x8000`、`NET_BINDTODEVICE=y`
  等），`BT_L2CAP_TX_BUF_COUNT=5`/`BT_CONN_FRAG_COUNT=2` 未写入 defconfig 但等于 Kconfig
  默认值，`SF32LB52_BT_TRACE` 两边都不存在（正式镜像 trace 关闭，符合预期）。
- 待办（需要人工在 GitHub 上操作）：`open-vela/frameworks_bluetooth`、`external_zblue`、
  `vendor_sifli` 三个公共仓**尚无 Sen70s fork**，公共仓改动按规则要 fork + PR 到
  `dev-ai-contest-2026`；团队仓 `README.md` 仍是组委会模板，README 第六节要求提交前
  替换为作品说明。

## Round 13（2026-08-21，交付收尾：公共仓贡献索引 + 遗留问题归档）

- 查清了大赛规则对「公共仓改动如何被评委看见」的说法：《参赛代码提交指南》只规定
  fork + PR 到 `dev-ai-contest-2026`，**没有**提供把公共仓改动同步回团队仓的机制
  （无 patch 目录约定、无 manifest revision 覆盖、也不要求团队仓记录上游 PR）。
  团队仓能承载的只有文档，于是写 `23_upstream_contributions.md` 作为评审可见入口：
  三个公共仓各改了什么、哪几行、修的是什么、PR 待回填。
- 顺带确认评分口径：技术难度 30 分明确包含「新芯片适配与底层驱动扩展」，项目完整度
  20 分含文档——所以这三项平台底层修复讲清楚是**加分项**，不是负担。上游贡献本身没有
  单独计分项，获奖后才要求 PR 至上游。
- `24_open_issues.md` 归档遗留：P0 是 XIP 下 NOR 写路径未全 RAM 驻留（会吃掉整块
  `/data`，含机制、恢复手段、两条修复方向、以及为什么本轮不做）；P1 是本机 BD 地址
  硬编码假值（多设备撞地址 + Device-Id 全同）、吞吐无实测数字（本轮放弃，且注明靠加大
  ACL 包长提吞吐已无余量）；P2 是 `vendor/sifli` 里三个被跟踪的构建产物、README 仍为
  模板、以及介绍文档/演示视频/可复用 Skill 尚未准备。
- 安装 `gh` CLI 到 `~/.local/bin/gh`（v2.63.2），等 token 就能代做 fork + push + PR。

## Round 14（2026-08-21，三个公共仓 PR 已提交）

- fork + push + PR 一次跑完（`upstream_patches/do_fork_pr.sh`，幂等）：
  [external_zblue#231](https://github.com/open-vela/external_zblue/pull/231)、
  [vendor_sifli#29](https://github.com/open-vela/vendor_sifli/pull/29)、
  [frameworks_bluetooth#591](https://github.com/open-vela/frameworks_bluetooth/pull/591)，
  均以 `dev-ai-contest-2026` 为 base，粒度按「整条线一起提」。
- `gh auth login` 硬性要求 `repo` + `read:org` 两个 scope，而我们只需要 `public_repo`；
  改走 `GH_TOKEN` 环境变量喂 token，跳过登录期 scope 校验，权限仍由 GitHub 按 token
  真实 scope 判定——建 fork / 推分支 / 开 PR 全部够用。
- 修掉三类门禁问题（都不是功能缺陷，但直接卡 PR）：
  1. **CLA 认不出假身份**：早期若干 commit 作者是 `zcode <zcode@local>`（工具默认身份），
     CLA 机器人无法对应到 GitHub 账号，报 `CLA required for 1/3 contributor(s)`。用
     `git filter-branch --env-filter` 改写为真实贡献者，`git diff` 验证树内容零变化，
     force-with-lease 推回。**教训：新仓开工前先设好真实 `user.name`/`user.email`。**
  2. **commit message 与源码注释都不许有中文**：checkpatch 里的 `chinese-detector`
     分开检查两者。一条 commit message（`"PIN错误"`）+ 三处源码注释被拦下。
  3. **clang-format 是硬门禁**：本仓 `.clang-format` 为 `BasedOnStyle: WebKit`，
     一行式 `if (x) { body; }` 与 Allman 的 `if (x)\n{` 都违规。021919aa 里 11 处
     NULL 守卫 + 2 处 OOM 守卫全部重排。
- 三个 PR 现已 **CI 全绿且 MERGEABLE**：checkpatch / clang-format / CLA 之外，`ci_dev`
  的五个平台构建矩阵（aurix/flagchip/goldfish/qemu/sil）也全部通过（zblue 10 pass、sifli
  10 pass、bluetooth 11 pass，各跳过 1 个只对 trunk 跑的 ci_trunk）。
- 回填 PR 链接到 21/23/24 号文；23 号文补了上面三类门禁问题的排查记录。

## Round 15（2026-08-22，P0 复位窗口 + 开机链路三个坑）

本轮目标是「重启一次就能用」：上电到桌面要快、触摸要能用、界面上要能看到 IP，
掉电之后不要起不来。

- **P0 先更正机制再修**。24 号文原来写「`HAL_FLASH_PreInit` 那几个 HAL 函数仍在 XIP
  flash 里」，查 `System.map` 发现不对：`bf0_hal_mpi.c` / `bf0_hal_mpi_ex.c` 整个 TU
  早就在 `.ramfunc`（`HAL_FLASH_PreInit` 0x2001f590、`ISSUE_CMD` 0x20020a4e、
  `SET_QUAL_SPI` 0x2002103c）。窗口也比以为的窄——`PreInit` 结尾的
  `SET_QUAL_SPI(false)` 只把 AHB 读切成 FREAD 单线快读，片子照样应答，XIP 不断；
  `nor_qspi_switch(hflash, false)` 在 `en==0` 时直接 return，QE 位都没动。
  **唯一的漏洞是 `HAL_Delay_us`（0x12010628，XIP）夹在 `0x99` 复位命令和
  `SET_QUAL_SPI(true)` 之间**：片子刚收到复位、tRST 内不响应任何读，下一条指令偏偏
  要从它取指；而且前一行 `up_irq_restore()` 已经把中断放开了，窗口里来个 ISR 一样炸。
  活不活全看这段是否还在 I-cache——这就是「同一份镜像有时能过有时不能过」的来源。
- 修法只动 `vendor/sifli/chips/sf32lb52/sf32lb_flash.c`，没碰 vendor HAL 的驻留属性：
  五行收进 `SF32LB_FLASH_RAMFUNC` 的 `sf32lb_flash_reset_to_qmode()`，`HAL_Delay_us`
  换成同样 `.ramfunc`、计数器在栈上的 `sf32lb_flash_spin_us()`，整窗 `up_irq_save()`；
  顺手给「确保可写」那处裸调的 `HAL_FLASH_CLR_PROTECT()` 补上 IRQ 保护。反汇编确认
  窗口内四条 `bl` 全部指向 0x2002xxxx，无 XIP 分支。代价 +184 B SRAM（90.48%→90.51%）。
- **开机 14 秒是 IMU 探测在等 I2C 超时**。板子上没有 LSM6DS3，
  `lsm6dsl_sensor_register()` 对 0x6a / 0x6b 各吃一次总线超时（日志里 9.46 s 和
  14.39 s 两条 -5），而这段跑在 `AppBringUp` 里，优先级 240，把优先级 95 的
  `lcd_async_init` 一起堵住。把探测挪到 `lcd_async_init_thread()` 末尾（触摸注册之后），
  开机路径上没有任何东西需要 IMU。
- **触摸时好时坏是竞争，不是驱动坏**。`/dev/input0` 由那个异步线程注册，而
  `lv_nuttx_init()` 只探一次 input 路径、ENOENT 就放弃。同一份镜像
  `rel_nsh_stress.log` 17.656 s 打开成功，`rel_final.log` / `rel_e2e4.log` /
  `boot_after_erase3.log` 都是 `errno=2` 失败——纯粹看谁先到。
  在 `lvgl_ui_channel_init()` 里加了上限 3 s、50 ms 一次的 `access()` 等待；
  与上一条叠加后 IMU 那 14 秒不再挡在触摸注册前面，竞争窗口基本消失。
- **launcher 加 IP 显示**。`HerSen. Welcome back.` 换行追加 `bt-pan` 的 IPv4 地址，
  2 s 一次 `netlib_get_ipv4addr()` 轮询，地址没变就不重排版；没拿到地址时保持单行，
  免得 connect/DHCP 过程中桌面来回跳。UI 文件 `packages/ai_agent/src/ui/` 与团队仓
  `app/ai_agent/ui/` 两份保持一致（构建走的是前者，见 `compile_commands.json`）。
- 编译通过：`out/nuttx_contest_board_ai_agent/nuttx.bin`，SRAM 474,544 B / 90.51%。
- **尚未真机验证**：本轮证据全在构建 + 符号表 / 反汇编层面。烧录后要跑的回归：
  ① 上电到桌面的时间（预期从 ~16 s 降到 ~2 s）；② 触摸是否稳定生效（看
  `lv_nuttx_touchscreen_create ... open success`）；③ 桌面第二行是否出现
  192.168.44.x；④ 反复非正常断电，确认 mount 失败走 `forceformat` 自愈而不是 HardFault。
- `vendor/sifli` 这两处改动属公共仓，按规则要并进 `vendor_sifli#29` 或另开 PR。

## Round 16（2026-08-22，Round 15 的真机验证 + 两个自己造的坑）

烧录 `out/nuttx_contest_board_ai_agent/nuttx.bin` 后逐项实测。**Round 15 里有两条预估
是错的，一条改动直接把板子搞死了**，都记在这。

### 真机结果

- **P0 通过**。`logs/p0_wdogcut.py`：发 `wdog` 触发看门狗硬复位，同时在那 4.6 s 窗口里
  连续 39 次往 `/data` 写文件，保证复位落在 littlefs 的 NOR 写/擦中间。
  5 轮全过：`assert=0`、每次都是 `littlefs mounted on /data (persistent)`、
  一次都没退化到 `forceformat`、桌面正常起来。日志 `logs/r15_wcut1..5.log`。
- **触摸设备打开 5/5**。以前同一份镜像时好时坏（`rel_final.log` 是 `errno=2`，
  `rel_nsh_stress.log` 是 `open success`），现在每次都
  `touchscreen /dev/input0 open success, maxpoint 1`。
- **IP 标签通过**。加了一行 `[launcher] title ip=...` 便于串口取证，实测
  `17.13 [pan] state=dhcp_ok ... ip=192.168.44.140` → `17.75 [launcher] title ip=192.168.44.140`，
  地址拿到后 0.62 s 内上屏。
- **触摸「点击→事件」这条链仍未验证**：需要有人在窗口期内真的点屏幕。已备
  `logs/touch_check.py`，跑起来后点左下「设置」/中间「桌宠」/右下「关于」，
  收到 `[Launcher] ... clicked` 即通。我自己跑了两次 75 s 窗口都是 0 条——
  只能说明没人点，不能说明链路坏。

### 更正 Round 15 的开机提速预估

写的是「~16 s 降到 ~2 s」，**实测是 17.1 s → 13.3 s，只省了约 4 s**。
错在把 `gateE_reboot.log` 里第二条 LSM6DS3 报错的绝对时间戳 14.39 当成了它的耗时。
真实账目（以 SFBL 为 0）：

| 段 | 旧 | 新 |
|----|----|----|
| SFBL → ADC init | 3.9 s | 4.8 s（SFBL 自身 + 早期 NuttX，与我们无关）|
| ADC → NOR MTD / littlefs | 5.3 s（两次 I2C 超时）| 0.4 s |
| → ai_agent 启动 | 2.9 s | 2.6 s |
| agent 自身 P0–P3 | 4.4 s | 4.6 s |
| **SFBL → 桌面** | **17.1 s** | **13.3 s** |

剩下的大头是 SFBL 前段 4.8 s 和 agent 自己的 P2/P3（`http_proxy_init` 1.7 s、
`llm_proxy_init` 3.1 s），两者都不在本轮范围内。

### 坑一：IMU INT 与触摸 IRQ 是同一个 PA31

Round 15 把 IMU 探测挪到触摸注册**之后**，无意中制造了引脚冲突：

```c
#define SF32LB52_LSM6DS3_INT_PIN  GET_PIN_2(hwp_gpio1, 31)   /* sifli_ap.c:98 */
CONFIG_TOUCH_IRQ_PIN=31                                       /* defconfig:175 */
```

IMU 初始化里的 `HAL_PIN_Set(PAD_PA31, ...)` + `sifli_gpio_config(PA31, GPIO_INPUT)`
会盖掉 ft6146 刚用 `sifli_gpio_set_event(PA31, falling, handler)` 注册的边沿事件。
改序之前 IMU 先跑，所以是触摸最后配置、赢了；改序之后反过来。

还牵出一件事：**ft6146 从来不自己做 PA31 的 pinmux**，它只 `sifli_gpio_config` +
`set_event`，把 PA31 从默认功能切到 `GPIO_A31` 一直是白蹭 IMU 初始化的副作用。

两处一起修（`sifli_ap.c`）：

1. `sf32lb52_lsm6ds3_initialize()` 改成**先探测、探到了才配 INT 引脚**——板子上没有
   这颗 IMU，于是永远不再碰 PA31；
2. 触摸初始化前显式 `HAL_PIN_Set(PAD_PA31, GPIO_A31, PIN_PULLUP, 1)`，pinmux 是板级
   该干的事，不该依赖别的驱动的副作用。

### 坑二：顺手关 LDO 把板子关死了

修坑一时多加了一句「探测失败就把传感器 LDO 关掉」当作清理：

```c
sifli_gpio_write(SF32LB52_LSM6DS3_LDO_PIN, false);   /* PA30 —— 这一行是错的 */
```

烧完板子**完全没有串口输出**，RTS 断电、重新上电都救不回来，只有 sftool 还能连
（说明 SoC 和 ROM bootloader 活着，是固件挂了）。PA30 这条 LDO 轨不只喂 IMU。
删掉这一行重烧，板子立刻恢复正常。

教训：这一句不在需求里，是我自己加的「顺手整理」，代价是一轮排查加一次重烧。
**改板级电源相关的 GPIO，不要当作顺手的清理。**

### 另外两件事

- `wdog` 只在 nsh 下有效。`CONFIG_EXAMPLES_AI_AGENT_VELA_SHELL_FULL=y` 的 agent 会
  在控制台上开自己的 `vela>` shell，和 nsh 抢同一个 console 的读——谁先 read 到字符
  谁执行。命令打到 `vela>` 上会刷一屏 `Unknown command`，而且刷多了会把控制台弄哑
  （需要重烧恢复）。写自动化脚本时要先确认当前提示符是 `nsh>` 还是 `vela>`。
- 本轮最后一次观察里 PAN 反复 `state=connecting` → 11 s 页超时 → `disconnected`，
  是**手机侧没应答**（蓝牙网络共享被关或手机不在范围），不是固件回归：同一份源码在
  `logs/r15_pa31c.log` 里 `dhcp_ok=16.71s` 正常拿到 192.168.44.140。

新增脚本：`logs/p0_wdogcut.py`（掉电鲁棒性回归）、`logs/touch_check.py`（触摸点击验证）、
`logs/verify_reset.py`（复位抓开机日志并给判定摘要）。

## Round 17（2026-08-22，触摸真因：FT6146 的 INT 是锁存的）

Round 16 结尾我把「触摸没反应」归到 PA30/PA31 引脚冲突上，**那个判断是错的**。
引脚冲突确实存在（而且我的改序确实让它变严重了），但把冲突消掉之后触摸依然没有任何
事件。真因是另一件事，本轮定位并修好，**用户已在真机确认三个按钮都能点、桌面也显示
了蓝牙 IP**。

### 怎么找到的

给 ft6146 驱动加了两个 Kconfig 选项做诊断（`INPUT_FT6146_TRACE` 逐样本打印、
`INPUT_FT6146_POLL_MS` 定时轮询），第一次跑出来的结果就把方向定了：

```
[ft6146] i2c read failed: -5      <- 轮询每次都失败，且每次耗时 13.6 s
[diag] ft6146 样本行=6  最大 irq 计数=0  Launcher 点击=0
```

`irq=0` 说明**边沿中断从来没触发过**。而 `[ft6146] id_h=0x64 id_l=0x56` 在 init 时是
正确读到的，`i2c dev -b 0` 扫描也能看到 `38` 应答——芯片在总线上活着，只是初始化之后
再也读不出东西。

于是做了一个最小实验：在 `sifli_gpio_set_event()` 武装中断**之后**再读一次寄存器，
看是不是武装动作本身把它弄坏了。结果是读得到（`ret=0 val=0x64`），但**加了这两次读之后
触摸立刻全好了**：

```
[ft6146] irq=2 n=1 x=187 y=386 DOWN
[Launcher] Pet icon clicked - entering pet page
[Launcher] About icon clicked - entering about page
[Launcher] Settings icon clicked - entering settings page
```

### 根因

FT6146 的 INT 是**锁存**的：它拉低 INT 并一直保持，直到主机把触摸数据寄存器读走。
驱动里 `ft6146_hw_init()` 的顺序是复位面板 → 读 ID → `sifli_gpio_set_event(falling)`，
**从头到尾没有读过 `TD_STATUS`**。如果武装下降沿的那一刻 INT 已经是低电平（上电/复位
过程中的抖动、或者手指正压在屏上都会造成），那之后就永远不会再有一次下降沿——
中断死掉，整个会话触摸都不响应。这解释了为什么它是「一直不能用」而不是「时好时坏」。

修法一行（`vendor/sifli/boards/sf32lb52/drivers/input/ft6146.c`）：武装中断之后读一次
`TD_STATUS` 把锁存的 INT 排空，让下降沿能重新触发。

### 顺带明确的两件事

- **轮询不能当兜底**：面板空闲时不应答，每次空转轮询都要吃满一次 I2C 超时（实测
  13.6 s）并把 LPWORK 堵住。`INPUT_FT6146_POLL_MS` 保留成 Kconfig 选项（默认 0、
  帮助文本里写清代价），只给中断真的不可用的板子；本项目关掉。
- **`CONFIG_I2C` 以前是白蹭来的**。Round 16 关掉 `SENSORS_LSM6DSL` 之后触摸驱动整个
  消失了，因为 `SENSORS_LSM6DSL` 有 `select I2C`，而 `INPUT_FT6146` 是
  `depends on I2C`——沒了前者，后者被 Kconfig 静默摘掉，`/dev/input0` 根本不存在。
  已在两个 defconfig 里显式写 `CONFIG_I2C=y` 并注明缘由。

### 关于 LSM6DSL：仍然应该关掉，但它不是触摸的病因

板子上没有这颗 IMU（每次开机两次 I2C 超时、约 5 s），而且它声明的两个引脚
（LDO=PA30、INT=PA31）正是 `BSP_PIN_Touch()` 里触摸面板的 `I2C1_SCL` 和 CTP 中断。
所以关掉它是对的——省开机时间、消除引脚冲突——但**触摸能用是靠上面那次 INT 排空**，
不是靠关它。`sifli_ap.c` 里我 Round 15 做的改序已全部回退，只在 IMU 初始化上方留了
一段注释说明这个引脚冲突。

### 本轮最终状态（正式镜像，`POLL_MS=0`、`TRACE` 关闭）

| 项 | 结果 |
|----|------|
| 触摸 | ✅ 用户真机确认：设置 / 桌宠 / 关于 三个按钮都响应 |
| 桌面 IP | ✅ `[launcher] title ip=192.168.44.140`，dhcp_ok 后约 0.6 s 上屏 |
| 掉电鲁棒性 | ✅ 5 轮写中硬复位，0 assert、每次 persistent mount |
| SRAM | 474,336 B / 90.47%（比 Round 15 前的 90.48% 略降）|
| pandbg 配置 | ✅ 同步改动后编译通过（90.50%）|

公共仓改动汇总（都属 `vendor_sifli`，需并进 `vendor_sifli#29` 或另开 PR）：
`sf32lb_flash.c` 的 XIP 复位窗口 RAM 驻留、`ft6146.c` 的 INT 排空 + 两个诊断 Kconfig
选项、`sifli_ap.c` 的引脚冲突注释。

## Round 18（2026-08-22，蓝牙固件与本地模型合并成一份全局固件）

在此之前这两条线**从来没在同一个镜像里**：蓝牙那份是官方框架 `packages/ai_agent`
（`EXAMPLES_AI_AGENT_VELA`，有 netmgr / BT-PAN / LVGL / 云端 LLM），本地模型那份是团队
仓 `app/ai_agent`（`EXAMPLES_AI_AGENT`，有 `ai_lm.cxx` + TFLM + 分词器）。正式 defconfig
里 `TFLITEMICRO`、`SYSTEM_FLATBUFFERS`、`MATH_GEMMLOWP`、`MATH_KISSFFT`、`HAVE_CXX`
一个都没开，所以现场固件根本没有本地模型的代码。本轮把模型并进有蓝牙的那一份。

**不动 /data 分区**（用户明确要求不能丢 bond），空间靠削冗余腾出来。

### Flash 预算（实测，非估算）

镜像区硬顶 `0x12010000..0x1299FFFF` = 10,027,008 B。

| 阶段 | flash | 说明 |
|------|-------|------|
| 起点 | 6,208,192 | |
| 删减后 | 4,359,284 | **省 1,848,908 B（1.76 MB）** |
| 并入模型后 | 7,618,476 | 模型 + TFLM 库共 +3,259,192 B |
| 余量 | **2,408,532 B（2.3 MB）** | 占用率 75.98% |

删减明细（都不丢已验证的功能）：

- **宠物动画** −720,000：`pet_cloud_open_px` / `pet_cloud_blink_px` 两张 300×300
  ARGB8888 删除，`pet_page.c` 的 `blend_frames()` 中间帧插值和眨眼定时器一起摘掉。
  桌宠现在用 LVGL 图元画（圆角块 + 两个鼓包 + 两只眼睛），`pet_display.c` 的表情动画
  仍在——`lv_image_set_scale/rotation` 换成 `lv_obj_set_style_transform_scale/rotation`
  的包装即可，只有眨眼帧真的没了。
- **壁纸 ARGB8888 → RGB565** −351,000：175,500 个像素 α 全是 0xff，而屏本来就是
  `LV_COLOR_DEPTH=16`，LVGL 每帧还在做降位转换。转换脚本
  `app/ai_agent/tools/gen_bg_rgb565.py`（可复现）。
- **删 `ui_font_sans_16_bold`** −278,915：一整份 3,879 字 CJK 字体，只为 2 个 label
  提供粗体，改指 `ui_font_sans_16`。
- 关 `LV_USE_DEMO_WIDGETS` + `EXAMPLES_LVGLDEMO` −182,694（附带甩掉 98 KB demo 头像图
  和两个 montserrat 字体）。
- 关 27 个用不到的 LVGL 控件 −110,951。app 只创建 obj/label/btn/image/timer，其余是被
  `lv_theme_default.c` 逐个引用才链进来的。
- 关测试类 builtin −111,222（`TESTING_OSTEST` 一个就 91,925）。
  **保留 `EXAMPLES_WATCHDOG` / `EXAMPLES_BUTTONS` / `SYSTEM_I2CTOOL` / `SYSTEM_PING`
  / `BLUETOOTH_TOOLS`**——`logs/` 和 `docs_ble/tools/` 下的验证脚本分别依赖
  `wdog` / `i2c` / `ping` / `bttool`。

计划里还写了删 `src/voice/*`（火山云 ASR/TTS）和 `llm_vision.c` 省 33 KB，**实际没做**：
`voice_channel.c` 提供的强符号覆盖着 `stubs.c` 里的弱符号，`cmd_voice.c` / `tool_vision.c`
还在引用，拆掉有链接断裂风险。已经省了 1.76 MB，没必要为 33 KB 冒这个险。

顺手把 `ld.script:23` 的 `LENGTH = 16M` 改成 `0x990000`。原来它从 0x12010000 一直算到
0x13010000，**完全不知道 /data 在 0x129A0000**——镜像涨过界不报错，直接写坏 littlefs。
现在越界是链接期错误，编译输出里的百分比也终于是对着真实可用区算的。

### SRAM

468,432 B / 89.35%，**比动手之前的 474,336 / 90.47% 还低**。关键是 `ai_lm.cxx` 的
64 KB tensor arena 从静态 BSS 改成 `aligned_alloc(16, ...)`：这块板子 SRAM 堆只有
32,576 B（`0x2007FB00` 减 idle 栈顶），8 MB PSRAM 是堆的第二个 region，所以 64 KB 的
分配必然落 PSRAM。`nm -S` 确认 `s_arena` 现在只有 4 字节（一个指针）。

`AGENT_AI_AGENT_STACK` 32 KB → 64 KB：推理跑在 `agent_loop` 线程上，32 KB 装不下
tokenizer/生成的栈帧（团队仓那边独立 worker 用的是 48 KB）。线程栈也是 heap 分配，
走 PSRAM，不吃 SRAM。

### 代码怎么接的

- **不复制代码**。模型留在团队仓（大赛要求作品代码在 `app/ai_agent`），
  `packages/ai_agent/CMakeLists.txt` 经由已有的 `packages/demos` linkfile 引用
  `../demos/contest2026_181_ai_agent/{ai_lm.cxx,tokenizer.c}`。`ai_lm.cxx` 里
  `#include "model/model_data.h"` 是源文件相对路径，自然解析到团队仓。
  `CONFIG_EXAMPLES_AI_AGENT` 保持关闭，不会多出第二个 app。
- **兑底点**：`src/core/agent_loop.c` 的 `run_react_loop`，云端失败且 router failover
  用尽之后，原来是 `break` 出去让 `dispatch_response` 打一句
  "Sorry, I encountered an error."，现在先试 `local_lm_reply()`。会话历史、缓存、
  outbound 分发全部复用官方管线，超时分支也覆盖。新增
  `src/llm/local_lm.{c,h}` 做一层薄封装，`CONFIG_TFLITEMICRO` 关掉时退化成
  "不可用"，官方框架的行为不变。
- **修一个拦路 bug**：`agent_loop_start()` 原来只在 `network_watch_task` /
  `net_state_change_cb` 里调用，断网时这个线程根本不创建，没人消费 inbound 队列。
  搬到 phase 5，并在函数内部加幂等守卫（三个调用点谁先到都只起一次）。
- **`ask` 得做成真正的 NSH builtin**。agent 自带的 CLI 里本来就有 `ask`，但那个线程
  优先级 30、NSH 是 100，两者抢同一个 console fd，**NSH 每次都赢**，agent CLI 永远读
  不到输入（实测连发 7 次全是 `nsh: ask: command not found`）。新增
  `src/channels/ask_main.c` 注册成 builtin：flat build 共享地址空间，直接
  `message_bus_push_inbound()`，回复由 `outbound_dispatch_task` 打 `[Agent]: ...`。

### 真机验证结果

```
nsh> ask 打开客厅的灯
Sent to agent: 打开客厅的灯
[llm_router] No available backend
[agent] LLM call failed (iter 0)
[local_lm] answered locally: 好的，已为您打开客厅灯
[Agent]: 好的，已为您打开客厅灯          elapsed=16s

nsh> ask 现在几点
[local_lm] answered locally: 现在是 6 点 29 分
[Agent]: 现在是 6 点 29 分               elapsed=49s
```

板子上没配 API key，`llm_chat_tools` 在 `llm_proxy.c:701` 直接返回 ERROR，所以每一次
提问都会走到本地模型——正是想要的效果。

| 项 | 结果 |
|----|------|
| 本地模型 | ✅ 两次提问都答对 |
| 推理耗时 | ⚠️ **16 s / 49 s**，比之前 SRAM arena 的 ~9 s 慢。arena 搬到 PSRAM 的代价，已知，未优化 |
| 蓝牙 PAN | ✅ `bt-pan inet addr:192.168.44.140`，`ping -c 3 223.5.5.5` 3/3、0% 丢包 |
| 桌面 IP 标签 | ✅ `[launcher] title ip=192.168.44.140` |
| 触摸 | ✅ 冷启动 `touchscreen /dev/input0 open success` |
| /data | ✅ 每次都 `littlefs mounted on /data (persistent)`，没触发 forceformat，bond 未丢 |
| 掉电鲁棒性 | ✅ `logs/p0_wdogcut.py` 3 轮全过：0 assert、persistent mount、触摸 OK、dhcp_ok |
| pandbg 配置 | ✅ 同步同样的改动后编译通过（7,624,684 / 76.04%）|

### 遗留

1. **推理慢了 1.8～5 倍**（PSRAM arena）。arena 实测真实占用只有 37,840 B，若能从别处
   腾出 40 KB SRAM 就能搬回去；当前 SRAM 89.35%，余量约 55 KB，值得单独一轮评估。
2. **语音（mic + 命令词 ASR + VAD）本轮没并**，按用户选择留到下一轮。要并需要另开
   `CONFIG_AUDIO` / `EXAMPLES_AUDIO_SETUP`，把 `audio_setup` 加进 rcS，并把
   `cmd_asr` 的 64 KB arena 和 `voice_question` 的 96 KB ring/window 也 malloc 化。
3. **触摸屏还是没法向 agent 提问**（`src/ui/` 里没有任何 `message_bus_push_inbound`），
   入口只有 NSH `ask`。
4. 后备 flash 余量（本轮没动）：`ui_font_cjk_18` 4bpp→2bpp +285 KB、`ALLSYMS=n`
   +221 KB、关 ARM unwind 表 +272 KB、`-O1/-Os` +790～967 KB。**`-Os` 特意不做**：
   defconfig 279–281 行记着一个对 `.bss` 布局敏感的既存堆损坏。
5. `packages/ai_agent/src/ui/assets/pet_cloud_{open,blink}.c` 还留在磁盘上（各约
   1.5 MB 源码），已不参与构建，团队仓另有一份。

## Round 19（2026-08-23，SRAM 腾出 41 KB + 桌宠页历史对话窗口）

### `g_allsyms` 白占 107 KB SRAM

排 SRAM 大户时，第一名不是任何 buffer 池，而是 `.data.g_allsyms` —— **107,088 B**，
`CONFIG_ALLSYMS=y` 让 `nuttx/tools/mkallsyms.py` 生成的符号表，内容全是 flash 地址。
它本该在 rodata：`nuttx/libs/libc/symtab/symtab_allsyms.c:34` 声明的就是
`extern const`，唯一写它的地方是 `arch/sim/src/sim/sim_head.c`（`CONFIG_ARCH_SIM` 下）。

根因是 `mkallsyms.py:61-64` 一个反了的标志位：

```python
def print_symbol_tables(self, isnoconst=False):
    noconst = "const"
    if not isnoconst:      # 默认走这里
        noconst = ""       # 于是发出的是「非 const」
```

而 ninja 规则又不传 `--noconst`。三条路可走：`CONFIG_ALLSYMS=n`（丢符号化 backtrace，
现在还在查堆损坏，不划算）、改 `mkallsyms.py`（上游正确解法，但要连带改前向声明，
否则 `extern struct` 与 `const struct` 冲突编不过）、或者在板级 ld.script 里把
`*(.data.g_allsyms)` 匹配进 `.text` 输出段。选了第三条：一行改动，保留符号名，
XIP 下它本来就是只读数据。

顺带说明为什么这 107 KB 特别值钱：`sifli_allocateheap.c` 只把
`[g_idle_topstack, 0x2007FB00)` 给堆，而 `g_idle_topstack = _ebss + IDLETHREAD_STACKSIZE`，
所以每省一字节 `.bss`/`.data` 就等量变成 SRAM 堆。改之前堆只有 38,480 B。

### arena 搬回 SRAM，但**推理没变快**

Round 18 把 tensor arena 从静态 BSS 改成 malloc（落 PSRAM），当时推断这就是推理从
~9 s 变成 16～49 s 的原因。腾出 107 KB 之后把它改回 `static uint8_t s_arena[64K]`
（确认在 SRAM，`nm` 显示 `b s_arena` 在 0x2005xxxx），**实测 17 s / 47 s，和 PSRAM
版本没有区别**。

所以那个假设是错的：arena 不是瓶颈。真正的开销应该在每个 token 都要从 XIP flash 读
3 MB 模型权重，以及 `ai_lm_agent_reply()` 是两段式生成（各 48 token，合计约 96 次前向）。
本轮不追这条线，但结论记下来，免得下一轮再往 arena 上使劲。

即便如此这次改动仍然是净收益：SRAM **468,432 → 426,896 B（89.35% → 81.42%）**，
堆从 38,480 B 涨到 **80,036 B**，而 arena 不再和别人抢那点堆。

其余候选（本轮都没动，按性价比排）：`IOB_NBUFFERS 64→44` 省 30,960 B 但影响 bt-pan
吞吐；`SYSTEM_WORKQUEUE_STACK_SIZE 32768→16384` 省 16,384 B，但 BT RX 跑在这个
workqueue 上、又没开 `STACK_COLORATION` 无从判断水位；`hp/lpwork` 栈各 16 KB → 8 KB
省 16,384 B；`TLS_CONN_POOL_SIZE 2→1` 省 9,232 B。**蓝牙那几个池一个都别碰**：
`BT_L2CAP_TX_MTU=1691` / `BT_BUF_ACL_RX_SIZE=1695` / `pan_tx_pool` / `acl_tx_pool`
是 Round 11 调出来的，动了直接威胁 BNEP。

### 桌宠页历史对话窗口

桌宠页原来只有一层 AI 文本，看不到之前说过什么。加了一个全页覆盖层：右上角 ☰ 按钮
打开，标题栏 + 关闭按钮，下面是可滚动的气泡列表（用户蓝底白字、Agent 浅底深字，
最新在底部），空的时候显示「还没有对话记录」。懒构建——不点就不占内存。

数据来自 `lvgl_ui_channel.c` 里已有的 20 条环形缓冲。新增三个 API：

- `lvgl_ui_channel_log(text, is_user)`：只记历史，不动气泡和桌宠表情
- `lvgl_ui_history_count()` / `lvgl_ui_history_get(newest_first, buf, len, is_user)`

之前那个环只有走 LVGL 通道的消息才进得去，NSH `ask` 的提问和以 `cli` 通道回来的回复
都不在里面。现在 `ask_main.c` 记提问、`agent_main.c` 的 `cli` 分支记回复，历史窗口能
看到完整对话。两个访问器只允许在 LVGL 线程上调用，`_log()` 内部经 `lv_async_call`
转过去——沿用 Round 15 学到的规矩：worker 线程绝不碰 `lv_*`。

### 真机回归

| 项 | 结果 |
|----|------|
| 本地模型 | ✅ `打开客厅的灯 → 好的，已为您打开客厅灯`；`现在几点 → 现在是 9 点 8 分` |
| 推理耗时 | ⚠️ 17 s / 47 s，与 arena 在 PSRAM 时一致（见上） |
| 蓝牙 PAN | ✅ `ping -c 3 223.5.5.5` 3/3、0% 丢包 |
| 桌面 IP | ✅ `[launcher] title ip=192.168.44.140` |
| 触摸 | ✅ 冷启动 `open success` |
| /data | ✅ `littlefs mounted (persistent)`，未触发 forceformat |
| 掉电鲁棒性 | ✅ `logs/p0_wdogcut.py` 3 轮全过，0 assert |
| flash / SRAM | 7,621,080 B（76.01%）/ 426,896 B（81.42%）|
| pandbg 配置 | ✅ 编译通过（7,627,288 / 76.07%）|

**历史窗口本身还没有目视验证**——需要有人在桌宠页点右上角 ☰。日志层面能确认的是
`ask` 与 `cli` 回复都调用了 `lvgl_ui_channel_log()`。

## Round 20（2026-08-23，联网对时：自己写 SNTP，不用 ntpclient）

板子的 RTC 上电是任意值（实测停在 `Wed, Feb 06 10:15 2036`），所以 `date` 和模型回答
「现在几点」都是错的。目标：联网后自动对时，并且一次成功要能扛住重启。

### 为什么不用 `apps/netutils/ntpclient`

先按常规做法开了 `CONFIG_NETUTILS_NTPCLIENT=y`，`ps` 里 `NTP_daemon` 确实在跑，
**但三分钟一次都没成功**，日志里一个字都没有。原因是它每次尝试前的门控：

```c
if (netlib_check_ipconnectivity(NULL, 1, 1) > 0)   /* ntpclient.c:1319 */
```

即「向 DNS nameserver 发 1 个 ICMP、1 秒超时」。这条件在蓝牙 PAN 上过不去——同一时刻
`ping -c 4 223.5.5.5` 是 4/4 全通，但 `ping -c 1 -W 1` 就 100% 丢。而它所有诊断都走
`ninfo()`，默认编译掉，所以门控失败时日志完全是空的，排查只能靠读源码。

失败后它按指数退避（上限 120 s，最多 60 次）重试，`POLLDELAYSEC` 只在**成功后**才生效——
这个设计是对的，问题纯粹在门控。

改成自己写 `src/infra/time_sync.c`（约 240 行）：一次尝试就是一个 SNTP 报文，每一步
都有 syslog，重试策略自己定（退避上限 60 s，成功后 6 小时再校一次）。服务器列表
`ntp.aliyun.com` → `203.107.6.88`（同一台的字面 IP，DNS 挂了也能走）→ `cn.pool.ntp.org`。
另加 NSH builtin `timesync` / `timesync status` 手动触发和查看。

回包做了两道校验：mode 必须是 4/5，换算出的 Unix 时间必须落在 2025–2100 之间——
否则是解码错误而不是「时钟不准」，宁可报错也不要把 RTC 写坏。

### 时区：`TZ` 一直是没用的

对上时间后 `date` 显示 08:20，而实际是 16:20——正好差 8 小时。根因是
**`CONFIG_LIBC_LOCALTIME` 没开**，此时 NuttX 的 `localtime_r()` 就是 `gmtime()`，
`TZ` 环境变量被完全忽略。也就是说 `agent_main.c` 里那句
`setenv("TZ", AGENT_TIMEZONE, 1); tzset();`（连带 `P0: timezone set` 那行开机日志）
**从来没有起过作用**。

要让它生效得开 `LIBC_LOCALTIME`，那会拖进 tzcode 加文件系统上的时区数据库——对一个
只在一个时区跑的产品不值得。改成让 RTC 直接存本地时间：`time_sync.c` 写入前加
`AGENT_UTC_OFFSET_SEC`（+8 h），于是 `date`、`time()`、模型的回答三者一致。

代价写在 `agent_config.h` 的注释里：`time()` 返回的是本地时间而非真 UTC。目前没有
东西需要真 UTC（TLS 只在日期粒度上校验证书有效期），但**将来接需要 UTC 时间戳签名的
云 API，得在调用点把这 8 小时减回去**。

### 验证

```
[timesync] started
[timesync] clock set from ntp.aliyun.com: 2026-08-23 16:30:49 (UTC+8)

nsh> date
Sun, Aug 23 16:29:55 2026          （主机 16:29:59，差值是串口读取延迟）
nsh> timesync status
time: 2026-08-23 16:27:27 (synced from network)
nsh> ask 现在几点
[Agent]: 现在是 16 点 27 分
```

冷启动自动对时也确认了：`dhcp_ok` 之后约 3 秒 `[timesync] clock set`。

**RTC 持久性**：代码路径已确认（`nuttx/sched/clock/clock_settime.c:82-85` 在
`CONFIG_RTC` 下调 `up_rtc_settime()`），硬件层面的间接证据是那个错误的 2036 时间在
多次复位和重烧之间一直在往前走（10:15 → 10:20 → 10:21 → 10:24），说明 RTC 本身
掉电/复位后是保值且在计时的。**没有单独做「断网冷启动」的隔离测试**——控制台在那个
时间窗口被 PAN 日志刷满，`date` 的回显抓不出来，暂记为未验证项。

flash 7,623,176 B（76.03%）/ SRAM 426,912 B（81.43%）；pandbg 同步编译通过
（7,629,408 / 76.09%）。

## Round 21（2026-08-23，桌面日期跟上真实时钟）

Round 20 把时钟对上之后，桌面左侧那一列日期还是**硬编码**的：

```c
lv_label_set_text(dat_label, "WED\nAPR\n1");   /* launcher_page.c:301 */
```

RTC 是随机值的时候这行无所谓，现在时钟准了它就成了明显的错。改成跟 `time_update_cb`
一起从同一次 `localtime_r()` 出结果：星期 / 月份缩写 / 日号三行。

几个具体决定：

- **和时钟共用一个 1 Hz 定时器**，不另开一个。日期只在「天变了」的时候才写 label，
  所以每秒跑一次的代价只是一次比较。
- **判定的 key 是 `tm_year * 1000 + tm_yday`，不是单看 `tm_yday`**。因为
  `time_sync.c` 对上时钟的那一刻会发生跳变，而跳变前后 `tm_yday` 相同、年份不同是
  可能的（本轮之前 RTC 停在 2036 年），那种情况下星期几会变而 `yday` 不变，只看
  `yday` 就不会刷新。
- 星期/月份名写死在函数里的两张表，不用 `strftime("%a"/"%b")`：设计要的是大写，而且
  这个 build 也没有 locale 支持可依赖。
- `s_shown_day_key` 放到文件作用域并在 `launcher_create()` 里置 -1，这样重建桌面时
  新 label 一定会被重画（放函数内 static 会记着上一个已销毁 label 的状态）。
- 字体确认过：`ui_font_sans_24` 的第一个 range 是 `range_start=32 len=95`，
  即 ASCII 32–126 全覆盖，新增的月份/星期字母和日号数字都在里面。

创建顺序上 date label 在 time label 之前，而 `time_update_cb(NULL)` 在两者都建好之后
才调用，所以首屏就是真实日期，不会先闪一下占位符。

**验证**：`date` → `Sun, Aug 23 17:47:11 2026`，与主机一致；今天是周日 8 月 23 日，
屏幕上应显示 `SUN / AUG / 23`。**屏幕本身需要目视确认**——串口看不到 LVGL 的渲染结果，
能确认的只是同一个 `tmv` 里的时分已经正确显示（时钟是准的）。

flash 7,623,536 B（76.03%）/ SRAM 426,928 B（81.43%）；pandbg 同步编译通过
（7,629,768 / 76.09%）。

## Round 22（2026-08-24～25，LVGL 卡顿：先测再改，空闲 CPU 94% → 0%）

用户报「LVGL 界面很卡」。先派三个调查 agent 摸清渲染链路，再开
`LV_USE_SYSMON + LV_USE_PERF_MONITOR + LOG_MODE` 拿真实数字，然后才动手。
**这一步很关键：我原本排的优先级有一半是错的。**

### 基线（改动前，实测）

```
sysmon: 3 FPS (refr_cnt: 2 | redraw_cnt: 2), refr 256ms
        (render 230ms | flush 22ms), CPU 94%
```

**空闲桌面上，什么都不做，CPU 就是 94%，每秒 3 帧。** 而且 render 230 ms 对
flush 22 ms——**瓶颈在 CPU 软件合成，不在面板传输**。我原先猜面板带宽是主因
（算出 14 ms 理论下限），方向是错的：传输只占 8.6%。

### 真正的根因：一个看不见的宠物在动

`lvgl_ui_channel.c` 的 `ui_build()` 把所有东西放在**同一个 screen** 上：气泡、
menu 按钮、history 页、`pet_display` 的宠物精灵，最后 `launcher_create()` 建一个
**不透明的 390×450 桌面**并 `lv_obj_move_foreground` 盖在最上面。

宠物在 `PET_EMOTION_IDLE` 下有一个 `LV_ANIM_REPEAT_INFINITE` 的上下漂浮动画
（`lv_obj_set_y`）。关键是：

> **LVGL 不做同级遮挡裁剪。** `lv_obj_area_is_visible()`（`lv_obj_pos.c:850-896`）
> 只检查自身的 `LV_OBJ_FLAG_HIDDEN` 和祖先的裁剪链，**从不遍历兄弟节点**。

所以这个被完全遮住、永远看不见的宠物，每个刷新周期都在作废自己 300×300 的区域。
再叠上单缓冲导致的 `RENDER_MODE_FULL`（`lv_refr.c:303-309` 把**任何**作废重写成全屏），
结果就是：**每秒 30 次全屏重绘，绘制一个谁也看不见的宠物。**

顺带确认一件事：Round 18 我删宠物动画时担心的 384 KB ARGB8888 transform 层，
**在空闲时并不触发**——IDLE 用的是 `lv_obj_set_y`，rotation=0、scale=256，
`calculate_layer_type()` 返回 `LAYER_TYPE_NONE`。那条路只在 LISTEN / SPEAKING /
CONFUSED / ACTIVE 下才走。之前把它排在第三位是过度担心。

### 五处改动

1. **隐藏宠物舞台**（根因）。新增 `pet_display_set_stage_visible(bool)`：给宠物本体
   和五个表情层打 `LV_OBJ_FLAG_HIDDEN` 并 `lv_anim_delete` 掉动画，`ui_build()` 在
   桌面上前台之后调用。menu 按钮同理。这样 `lv_obj_area_is_visible` 直接返回 false，
   作废被裁掉；删动画是为了连每 tick 的 style 写入也省掉。
2. **切 PARTIAL 渲染模式**。`CONFIG_LV_NUTTX_LCD_CUSTOM_BUFFER=y` +
   `BUFFER_SIZE=60`（单位是**行**）→ 缓冲 390×60×2 = 46,800 B。SRAM 堆有约 65 KB
   可用，所以它落 SRAM 而不再是 QSPI PSRAM，同时 351 KB 的全屏缓冲不再存在。
   **驱动侧安全性已逐层验证**：LVGL 每个子区域前用 `lv_draw_buf_reshape` 把 stride
   改成子区域宽度（`lv_refr.c:855`），而 `lcd_dev.c:250` 在 `stride==0` 时按
   `cols * pixel_size` 算，两者一致；`sf32lb_lcd_putarea` 走 `stride == row_bytes`
   的分块路径，`HAL_LCDC_LayerSetData` 的 `total_width` 保持 INVALID 从而按区域宽度
   算行长。整条链 stride 自洽。
3. **`CONFIG_LV_OPTLEVEL="-O2"`**。这个符号经
   `target_compile_options(lvgl PRIVATE ...)` 只作用于 lvgl 这一个 target
   （`apps/graphics/lvgl/CMakeLists.txt:188-190`），**不碰蓝牙栈，也不动别处的
   `.bss` 布局**——正是为了绕开 defconfig 里记的那个对布局敏感的既存堆损坏。
   全局 `-Os`（`CONFIG_DEBUG_FULLOPT`）本轮**故意不做**。
4. **`CONFIG_ARMV8M_MEMCPY=y`**。`LV_USE_CLIB_STRING=y` 让所有批量像素搬运走 libc
   memcpy，而之前编进去的只有 `lib_bsdmemcpy.c`（C，`-O0`）。这个符号 select
   `LIBC_ARCH_MEMCPY`，换成 `libs/libc/machine/arm/armv8-m/arch_memcpy.S`
   手写汇编（`ldmia`/`stmia` 64 字节展开）。零 `.bss` 影响，零编译选项影响。
5. **时钟 label 防抖**。`time_update_cb` 原来每秒无条件
   `lv_label_set_text(s_time_label, buf)`，哪怕 `"%02d:%02d"` 输出和上一秒一样。
   加一个 `s_shown_time` 比较，只在分钟真的跳变时才写——从每秒一次作废降到每分钟一次。

### 结果（实测）

| | 基线 | 改后 |
|---|---|---|
| 空闲 FPS | 3 | **0**（真的不重绘了）|
| 空闲 CPU | **94%** | **0–1%** |
| 空闲 redraw_cnt | 持续 | **0** |
| 单次重绘 render | 230 ms | **30 ms**（7.7×）|
| 单次重绘 flush | 22 ms | **6 ms**（3.7×）|
| flash | 7,633,576 | 7,507,020（−126 KB，−O2 反而更小）|
| SRAM | 426,896 | 426,896（不变，缓冲从 PSRAM 挪到 SRAM）|

「单次重绘」这两个数字是同一个操作的前后对照：时钟分钟跳变时重画时钟 label。
改后 150 秒的空闲观察里只出现了两次非零重绘，间隔正好 60 秒——正是分钟跳变，
其余时间完全静止。

### 回归

`date` = `Tue, Aug 25 00:16:04 2026`（对）；`ping -c 3 223.5.5.5` 3/3、0% 丢包；
`ask 打开客厅的灯` → `好的，已为您打开客厅灯`；`logs/p0_wdogcut.py` 3 轮掉电全过
（0 assert、persistent mount、touch_ok=True）；pandbg 同步编译通过
（7,513,236 / 74.93%）。

perf monitor 三个符号已在 defconfig 里注释掉（`LV_LOG()` 是无条件的，不能出货）；
要复测把它们打开即可。

### 待验 / 遗留

- **翻页时的实际手感需要目视**。我开了 110 秒窗口请用户点「设置→返回→桌宠→☰→关于」，
  窗口内没有出现任何 `[Launcher] ... clicked`，只有两次分钟跳变的重绘，所以整页切换的
  render 时间还没有测到。桌宠页/设置页是整屏重绘，30 ms 那个数字不代表它们。
- 剩下没做的余量：全局 `-Os`（约 967 KB flash + 全局 3–5× 内循环，但动 `.bss` 布局）、
  接 SiFli 的 EPIC 2D 引擎（`bf0_hal_epic.c` 已编进镜像但**没有任何调用者**，
  要写一个 `lv_draw_unit`）、`/dev/fb0` 那份没人用的 351 KB PSRAM 影子缓冲
  （`lcd_framebuffer.c:699` 分配，LVGL 走的是 `/dev/lcd0`）。
- `CONFIG_LCD_CO5300_VSYNC_ENABLE=y` 是个死符号：`co5300.c:140` 测的是没有
  `CONFIG_` 前缀的 `LCD_CO5300_VSYNC_ENABLE`，没人定义它，所以 TE 同步一直是关的。
  解释画面撕裂时别被这个符号骗了。
- `settings_page.c:187/201/226` 在 setter 里调 `lv_timer_handler()`。那三个
  `settings_page_update_*` 目前**没有任何调用者**，所以还不是 bug；但
  `LV_USE_OS=0` 意味着没有互斥锁，一旦有人从别的线程调它就是重入破坏。

## Round 23（2026-08-25，触摸间歇失效：定到中断层，但修不动）

用户报「老毛病了 触碰失效」。这轮**没修好，但把问题钉在了一层上，并排除了一条错路**。

### 分层判定：断在中断，不在 LVGL

烧了带 `CONFIG_INPUT_FT6146_TRACE=y` 的诊断镜像（Round 17 加的选项）。开机三行都正常：

```
[ft6146] id_h=0x64 id_l=0x56 irq_pin=31        芯片在 I2C 上应答
[ft6146] armed irq on pin 31, td_status=0x00   Round 17 的 INT 排空执行了，当时 INT 没被拉低
touchscreen /dev/input0 open success            LVGL 正常打开设备
```

之后约 180 秒窗口里**一条样本都没有**。`[ft6146]` 是驱动每上报一个样本打一行，
零条 = **中断根本没触发**。所以和 Round 22 那批渲染改动（PARTIAL / LVGL `-O2` /
asm memcpy / 隐藏宠物）都无关，问题在它们下面一层。

### 找到一个真缺陷：`HAL_GPIO_Init` 把 `Pull` 完全丢掉

`bf0_hal_gpio.c:181` 只 `HAL_ASSERT(IS_GPIO_PULL(GPIO_Init->Pull))`，**之后再也没有
把这个字段写进任何寄存器**——它只配方向、开漏、触发沿三组。pad 上拉在 PINMUX 里，
只有 `HAL_PIN_Set` 能设。

而 `BSP_PIN_Touch()`（`bsp_pinmux.c`）把 PA31 设成 `PIN_NOPULL`。于是
`sifli_gpio_irq_enable()` 为下降沿模式请求的 `GPIO_PULLUP`
（`sifli_gpio.c:233-235`）**从来没到过引脚**。FT6146 的 INT 是开漏低有效、驱动 armed
的是下降沿，这条线本该常态为高——没有上拉它就是悬空，边沿只能靠运气。这是「同一份
代码有的镜像能用有的不能用」最合理的解释。

### 但 PA31 加上拉会把板子搞死

把 `PIN_NOPULL` 改成 `PIN_PULLUP`，烧进去之后**串口 0 字节**，电源循环也不出声；
改回 `PIN_NOPULL` 重烧立刻恢复。机制不明。已回退，并把这段经历写进
`BSP_PIN_Touch()` 的注释里，避免下一个人再踩一遍。

**这条路封死。** 如果以后要再碰，方向应该是**电平触发中断**（FT6146 的 INT 是锁存的，
level-low 语义上比边沿更对，而且不会丢事件），或者**低频 re-arm 看门狗**，而不是动
pad 上拉。

### 结论：间歇性，复位可恢复

用户自己复位板子之后触摸就好了。所以这不是 Round 22 的回归，是那条悬空 INT 的老毛病。
**当前的临时对策就是复位一次。**

过程中还有一个方法论问题值得记：我连开了三个「请点屏幕」的窗口，三次都是零事件，
但**我无法区分「中断真的不触发」和「那段时间没人在点」**——Round 22 那次我就误判成
「用户没在」。为此写了 `logs/touch_diag.py`：分层打印驱动层 `[ft6146]` 和 UI 层
`[Launcher]`，跑完直接给判定，用户自己就能跑。以后这类需要人配合的验证一律走它。

### 顺带修掉一个我自己引入的隐患

Round 22 用 `CONFIG_LV_OPTLEVEL="-O2"` 给 LVGL 单独提优化等级，而全局仍是
`CONFIG_DEBUG_NOOPT=y`。NuttX 只在**全局**离开 `DEBUG_NOOPT` 时才补
`-fno-strict-aliasing`（`arch/arm/src/cmake/gcc.cmake:116`），所以 LVGL 拿到了
`-O2` 而 strict aliasing 是开的（已在 `compile_commands.json` 确认）。

想连带加上 `-fno-strict-aliasing` 时撞到 `apps/graphics/lvgl/CMakeLists.txt:187` 的一个
坑：`if(NOT ${CONFIG_LV_OPTLEVEL} STREQUAL "")` 里变量**没加引号**，所以
- 分号分隔的值会被 CMake 展开成多个 if 参数 → 配置期报错；
- 空格分隔的值能过 if，但会被当成**一个** argv 传给 gcc → 编译期报错。

于是保持单个 `-O2`，把这两点和「一旦出现渲染怪象先撤这个」写进 defconfig 注释。
LVGL 上游本身就是按 `-O2/-O3` 构建的，strict aliasing 开着是常态；Round 23 也已经
证明触摸故障在 LVGL 之下，不是它引起的。

顺带一个坑：改坏 `CONFIG_LV_OPTLEVEL` 之后 `ninja resetconfig` **自己也跑不起来**
——它要先用当前那份坏 `.config` 重新生成 `build.ninja`，于是死锁。解法是先直接
`sed` 修构建目录里的 `.config`，再跑 `resetconfig`。

### 当前状态

- 正式配置已恢复：`CONFIG_LV_OPTLEVEL="-O2"`、`CONFIG_ARMV8M_MEMCPY=y`、
  `CONFIG_INPUT_FT6146_TRACE` 关闭。
- 两个配置都编译通过：ai_agent 7,507,020 B（74.87%）/ SRAM 426,896 B（81.42%）；
  pandbg 7,513,236 B（74.93%）。
- **还没烧进去**：USB 又从总线上掉了（`/dev/ttyACM0` 消失，`sftool` 报 port does not
  exist），需要物理重插。板上现在跑的是 `Aug 25 2026 02:20:33` 那个 bisect 镜像
  （`-O2` 和 asm memcpy 都关着、ft6146 trace 开着），功能齐全只是慢一点、串口会多打
  触摸样本。
- 待做：重插 USB 后烧 `out/nuttx_contest_board_ai_agent/nuttx.bin`，跑一遍
  PAN / ask / 掉电回归。

## Round 24（2026-08-26，桌面网络状态行 + 蓝牙断连定位）

桌面上原来只在联网成功时显示 IP，失败时那一行是空的，用户看不出是板子坏了还是手机
没开共享。改成三态（`src/ui/launcher_page.c`）：

| 状态 | 显示 | 颜色 |
|---|---|---|
| 已拿到 IP | `192.168.x.x` | 白 |
| 开机 20 秒宽限期内 | 蓝牙连接中… | 灰 |
| 宽限期过后仍无 IP | 蓝牙未连接，请开启手机网络共享 | 琥珀 |

宽限期是 `LAUNCHER_NET_GRACE_POLLS = 10`（2s 一轮），避免开机瞬间先闪一下红字。
状态行的字体从 `ui_font_sans_24`（117 glyph，没有中文）换成 `ui_font_sans_16`
（3878 glyph 全中文），否则提示文字整行是方块。

同一轮定掉了「重启后蓝牙不连」：不是回连逻辑坏了，是**手机那边的网络共享关掉了**。
BNEP 需要手机作 NAP，共享一关板子就没有可连的服务。用户重新打开共享后立刻恢复。
这条以后先查手机，别再去翻 bond 列表。

## Round 25（2026-08-26，两颗实体按键：KEY1 开桌宠，KEY2 随机拷问本地模型）

### 板子上到底有几个键

参考 `/home/aila/projects/xiaozhi-sf32`（同 SoC 家族的小智工程）：
`CONFIG_BSP_KEY1_PIN=34` + `CONFIG_BSP_KEY1_ACTIVE_HIGH=y`、`CONFIG_BSP_KEY2_PIN=39`
（那块板的 KEY2 在 PA39，我们这块 PA39 被 LCDC1_8080_DIO3 占了）。我们的
`bsp_pinmux.c:169-173` 也写着 `// Key1 - Power key` 对应 PA34、`// Key2` 对应 PA11，
只是 `sf32lb52_buttons.c` 里一直只注册了 PA11 一个键（`NUM_BUTTONS 1`）。

**PA34 有个坑**：pinmux 里那行 `HAL_PIN_Set(PAD_PA34, GPIO_A34, PIN_NOPULL, 1)` 是被
注释掉的，注释说明「UART 下载驱动要用这个功能，去掉下拉下载就不工作」。所以这轮
**完全不动 pinmux**——`sifli_gpio_config()` 只配 GPIO 外设方向，不碰 pad 的 mux 和
上下拉（已读源码确认，`sifli_gpio.c:375-420`），下载通路不受影响。

### 板级：一键改两键

`sf32lb52_buttons.c` 把单键硬编码换成一张表（pin / bit / active_low），`readset` 遍历
求位图，`bl_supported` 返回 0x03，`bl_enable` 给两个 pin 都 arm 边沿事件（哪个 arm
失败就只靠原有的 10ms 轮询兜底，不再像以前那样直接把 handler 撤掉）。`board.h`:
`BUTTON_KEY2=0`（PA11）、`BUTTON_KEY1=1`（PA34）、`NUM_BUTTONS 2`。
init 里多打一行空闲电平，方便下次判断某个键是不是 pad 没接对。

### 应用：src/ui/key_input.c

一个线程 poll `/dev/buttons`，只认按下沿（`sample & ~last`），300ms 去抖：

- **KEY1** → `lv_async_call` 到 LVGL 线程，若当前在桌面则 `launcher_enter_page(PAGE_PET)`。
  不在桌面就什么都不做——`launcher_enter_page()` 每次调用都会 `pet_page_create()`，
  重复进会漏一个 page 对象。
- **KEY2** → 从 12 条演示问句里随机挑一条（避开上一条），走**和 NSH `ask` 完全同一条
  管线**：`lvgl_ui_channel_log(q, true)` 记历史 → 异步打开桌宠页并把文字层置成
  「{问句}\n本地模型思考中…」→ `message_bus_push_inbound(channel="cli")`。
  回复由 outbound dispatch 的 `cli` 分支回来，`lvgl_ui_channel_log(reply,false)` 进历史；
  这轮顺手在 `history_log_async_cb` 里补了一句 `pet_page_update_response()`，所以
  NSH 问的和按键问的回复都会落到桌宠页文字层（页面没开时是 no-op）。

12 条问句按 `ai_lm.cxx` 的 `ag_detect_intent()` 覆盖了灯 / 窗帘 / 空调设温 / 空调模式 /
设备开关 / 设备状态 / 报时 / 天气 / 温度 / 门锁 / 定时十一类意图，房间和设备名都取自
`AG_ROOMS` / `AG_DEVICES`——问句留在训练域内，答案才有意义（这是个 231 万参数的
智能家居 LSTM，不是通用聊天模型）。用词也都在 `ui_font_cjk_18` 的 3878 字子集里。

### 实测

- 两个配置都编译过：ai_agent 7,509,852 B（74.90%，比上轮 +2,528 B）；
  pandbg 7,516,068 B（74.96%）。SRAM 426,960 B（81.44%）不变。
- 已烧录。`buttons` 命令报 `Supported BUTTONs 0x03`，`hexdump /dev/buttons` 空闲读
  `00 00 00 00`——**PA34 认得出来，而且空闲不是按下态**（下拉在、极性对）。
- `ask 打开客厅的灯` → `[local_lm] answered locally: 好的，已为您打开客厅灯`，
  端到端 elapsed=1s。KEY2 走的就是这条链，只是问句由随机表给。
- 还差**物理按一下**：这一步只能人来。

### 两个踩坑记录

1. 这台机器上 **RTS 复位不灵**：`ser.rts=True/False` 之后 uptime 照旧往上走，板子根本
   没掉电（`/dev/ttyACM0` 是 CH343 外接桥，1a86:55d3）。烧录时 sftool 能进 bootloader
   是因为它自己还带 `--before/--after` 时序。以后要冷启动日志只能靠物理重插。
2. `hexdump /dev/buttons` 会**永远读不完**（字符设备没有 EOF），而这份 defconfig
   `CONFIG_TTY_SIGINT` 和 `CONFIG_BOARDCTL_RESET` 都没开——既 Ctrl-C 不掉也没有
   `reboot` 命令，只能等下一次上电。要看空闲电平请加长度参数：`hexdump /dev/buttons 4`。

## Round 26（2026-09-11，换手机后 PAN 不连：`last_nap` 锁定旧目标 + 一条零写入的换机路径）

### 症状

手机从 HyperOS 旧机（`A4:CC:B3:FE:D1:A4`）换成 REDMI Turbo 4 Pro
（`D0:C1:BF:FC:2D:08`）。新机在手机侧点「连接」后显示已配对，但手表开机后串口
反复出现：

```
[pan] state=connecting addr=A4:CC:B3:FE:D1:A4   ← 还在连旧机
[pan] ACL connect err 4
[pan] worker: ACL up timeout
[pan] state=disconnected
```

无限循环，永不尝试新机。

### 根因（两层）

1. **锁定层（真因）**：自动连接目标读的是 `/data/misc/bt/last_nap`（6 字节
   `bt_address_t`），只要文件存在就无条件使用（`panu_service.c` 的
   `pan_load_last_nap` → `pan_auto_do_connect`）。`last_nap` 只在两个时机写：
   `BOND_STATE_BONDED` 回调和 PAN `PROFILE_STATE_CONNECTED` 回调。新机虽然在
   手机侧完成了配对（`bt_storage.db` 里 `IsBonded: 1`），但那是更早固件时期
   的事，重启不会重发 BONDED 事件——于是 `last_nap` 永远是旧机地址。
   **「手机显示已配对」≠「下次 PAN 目标已切换」**，两者之间隔着一次成功的
   BONDED 回调或一次成功的 PAN 连接。
2. **干扰层（排查噪音）**：早期用 `bttool pan connect D0:C1:... 1 2` 手动连时
   撞上 `already exists / sal failed 4`，一度以为是持久卡死。读码确认这是
   **瞬态**：`bt_sal_pan_connect` 对 `ACL_PENDING` 残留条目返回 BUSY，但 worker
   线程的 ACL 超时路径（`sal_pan_interface.c:737-745`）会释放挂起条目并上报
   DISCONNECTED——等一轮（约 30s）自动解除，不需要复位。另外
   `bt_sal_pan_disconnect` 只清理 `>= L2CAP_PENDING` 的连接（`:968-970`），
   对 ACL_PENDING 残留无能为力，这是造成「看起来死了」的原因。

### 审计要点（换机方案安全性，全部有行号）

- PAN 连接成功 → `on_pan_connection_state_changed` 的 CONNECTED 分支**无条件**
  `pan_save_last_nap(addr)`（`panu_service.c:1020-1026`）→ 手动连一次新机，
  固件自己把目标切换掉，全程零文件写入。
- 字节序：`bt_address_t` 是裸 `uint8_t[6]`，`bt_addr_ba2str` 按 `addr[5]→addr[0]`
  打印（`bt_addr.c:72-77`）→ 文件字节 `a4 d1 fe b3 cc a4` ↔ 显示
  `A4:CC:B3:FE:D1:A4`；写新机应为 `08 2d fc bf c1 d0`。
- 自动循环（旧机）与手动连接（新机）共用单 slot `conn_list`，存在竞争窗口，
  需要**时机配合**（见下）。
- `PAN_RECONNECT_COOLDOWN_MS = 5000`（`sal_pan_interface.c:114`）。

### 修复方法（零写入换机流程，已实测成功）

```text
前提：新机已 BONDED、蓝牙网络共享已开
1. NSH 进入 bttool（此固件有 builtin），确认 Adapter State: 4 (ON)
2. 盯串口等自动循环出现 "[pan] state=disconnected"（每轮 connecting →
   ACL up timeout → disconnected 后约 10s 空档）
3. disconnected 出现后 +1s 发：pan connect <新机MAC> 1 2   ← 在空档内
   抢进 worker，避免 already exists
4. 观察完整链路：create_br → encryption → conf_rsp → BNEP setup OK →
   connected → ifup → dhcp_ok（此刻固件自动写 last_nap）
5. 验证：hexdump /data/misc/bt/last_nap = 新机地址；ifconfig bt-pan 有 IP；
   ping 223.5.5.5 / www.baidu.com
6. RESET 终验：开机全自动连新机（netmgr CONNECTED、bt-pan RUNNING、
   旧机地址零出现）
```

实测一次成功，重启后自动连接同样成功（IP 192.168.44.140 续约一致，
ping 0% 丢包）。

### 回滚（未用到，但备好）

`/data/misc/bt/last_nap.before-switch` 保留着旧机地址备份；恢复只需把它
cp 回 `last_nap` 并重启。`bt_storage.db` / `br_key.bin` 全程未动，新旧机
都在配对列表里，随时可双向切换。

### 教训（三个工具坑，都不是蓝牙问题）

1. **NSH `cat > 文件` 喂二进制会锁死控制台**：行缓冲下无换行的 6 字节
   （含 0x04）让 `cat` 永远等不到行结束，此后所有输入无回显无执行，表现
   极像死机。只能 RESET 恢复。要写二进制别用 shell 重定向，走「让固件
   自己写」或先在主机生成好文件。
2. **NSH `printf` 不认 `\xNN`**：写出来是 4 字节垃圾（`\010` 八进制也
   不可靠）。同上，别在板侧拼二进制。
3. **`ser.rts=True` 断电脉冲在这台机器上不灵**（Round 25 已记录，本次
   复现确认）：CH343 桥的 RTS 控制不了板子电源，冷启动只能物理重插/RESET。
   这轮一次「无输出 5 分钟」的虚惊就是误判了这一点，实际串口会话状态
   问题，换 1M 波特重开即恢复。

### 遗留（进 24 号文档的候选）

- 换机依赖「手动 bttool 连一次」——产品上不可接受。长期修复方向：BONDED
  回调把新目标与旧目标分开持久化、连接成功才正式切换；`pan_switch <MAC>`
  显式命令；ACL_PENDING 残留的主动清理（当前只靠超时）。属固件功能开发，
  归入赛后演进，不在交付计划内。

## Round 27（2026-09-14～16，主动关怀三场景真机验收：两个真 bug + 一次误判）

大赛交付计划 Phase 1 把「主动×3」做进 `pet_care`（空闲关怀）/ `set_timer`（闹钟）/
`hr_monitor`（心率）。写完先编译、自审，再烧到板上验收。这一轮的价值主要不在功能，
而在**验收过程中挖出的两个真问题**，以及一次必须留痕的**误判**。

### bug 1：LVGL 跨线程竞态（真问题，已修）

`ask` 一条会走两段式生成的问题后，render 线程**再不动**：串口只剩 `nsh` 活着，
hr 模拟值冻结，闹钟到点不响。`dumpstack 28` 给出行号级证据：

```
nxsched_switch → nxsig_clockwait → clock_nanosleep → usleep → render_thread+0x1f
```

线程没死、没死锁，是**睡不醒**。根因：这份 LVGL 以 `LV_OS_NONE` 构建
（`apps/graphics/lvgl/lvgl/Kconfig` 未开 OS 层），**没有任何内部锁**。而
agent/按键/NSH 线程一直在裸调 `lv_async_call()`——它内部是
`lv_timer_create()`，会改 LVGL 的定时器链表；render 线程同一时刻在
`lv_timer_handler()` 里遍历同一条链表。链表被改坏之后，render 自己那个
`usleep(5000)` 的唤醒定时器结构也一起烂掉，于是永远等不到唤醒。

为什么第一次能跑？render 线程 99% 时间在 `usleep` 里，撞上的概率低；
本轮连续高频 `ask` 把它撞出来了。

修法（不改公共仓 LVGL）：`lvgl_ui_channel.c` 里加 `lvgl_ui_post()`——
调用方只在互斥量里入队 `{cb, data}`（带空闲节点回收，不churn 堆），
render 线程每圈 `ui_post_flush()` 自己执行 `lv_async_call`。跨线程点全部改过来：
UI 气泡/历史、`pet_display_set_emotion`、idle 恢复定时器、按键两个回调。

### bug 2：1Hz 调度慢了 5 倍（真问题，已修）

闹钟设 2 分钟不响、hr 值看着冻结，一度以为 tick 停摆。**实测**每 2 秒采一次、
采 60 秒，只有 12 次变化 ≈ 0.2Hz；把 `TICKS_PER_SEC=200` 按「render 每圈
5ms × 200 = 1 秒」推算，而 `lv_timer_handler()` 让**每圈实际约 25ms**，
于是「1 秒逻辑」实跑 5 秒一次。

改法：丢掉数 tick，用 `care_now_s()` 的**墙钟秒**做门（`s_last_1hz_s`），
与循环速率解耦。修后复测 60 秒 20 次变化 ≈ 1Hz。

### 误判：把「没有日志」当成「没触发」

闹钟的预告/到点/升级三段**只上屏、不写 syslog**，我在串口里看不到，
于是先后得出「闹钟不响」「tick 死了」两个错误结论，还据此改了代码。
补上 `care_fire()` 的 `syslog` 后一次就看清了：

```
[pet_care] alarm set 2min
t+63s  [pet_care] fire emo=4 quiet=0 text=还有一分钟就到时间啦，准备好哦～
t+123s [pet_care] fire emo=5 quiet=0 text=起床啦～天气信息暂不可用，先看看今天的安排吧
t+243s [pet_care] fire emo=6 quiet=0 text=嘿，还没动呢？我可要痒痒你了！
```

三段全对，**闹钟一直是好的**。教训：验收路径上任何「用户可见但我不可见」的动作
都必须落日志，否则排障时会把系统行为误读成缺陷，并据此改坏代码。

### 另外两个顺手修掉的

- **心率告警冷却**：原来复用了关怀的 10 分钟冷却，且 `RESET` 是**软复位**
  （RAM 与 `CLOCK_MONOTONIC` 都保留），于是只有烧录后第一次告警能出来，
  之后十分钟全被吞。拆出独立的 `AI_AGENT_HR_ALERT_COOLDOWN_S`（默认 60 秒），
  演示连续注入两次都能看到，同一次事件内仍靠 per-event latch 去重。
- **可配置化**：`AI_AGENT_CARE_IDLE_MIN/COOLDOWN_MIN/QUIET_START/QUIET_END`、
  `AI_AGENT_HR_HIGH/HR_ALERT_COOLDOWN_S` 进 Kconfig，两个板级 defconfig 固化默认值。

### 真机验收结果

| 项 | 结果 | 证据 |
|---|---|---|
| H-3 闹钟三段 | ✅ | 上面三条 `fire` 日志（emo=4/5/6 对应 SPEAKING/HAPPY/CONFUSED）|
| H-4 心率告警 | ✅ | 60 秒冷却下连续注入三次三次告警（121/143/138 bpm）|
| `set_timer` 接线 | ✅ | `ask 20分钟后提醒我喝水` → `alarm set 20min` + 正确回复 |
| H-2 空闲关怀 | ✅ | 按键复位后静置 5 分钟：`fire emo=6 记得吃早餐…`，随后 L2 走通——`Processing message from care:pet_care` → `trace BEGIN chan=care` → `Dispatching response → care:pet_care`（第二条气泡）|
| 唤醒确认 | ✅ | 闹钟升级阶段按实体键 → 清闹钟，之后空闲关怀恢复可触发（行为已确认；日志在监测窗口外未截到）|
| 桌面空白区触摸 | 不算活动 | 插桩只在桌宠页云朵/返回键与实体键上，属已知限制，见下 |

补充观察：本次关怀用了「早晨」组模板，说明板子的本地时间落在 5-11 点。PAN 断网时
SNTP 对不上（`resolve ntp.aliyun.com failed`），RTC 保持上次的时间——模板选择逻辑
本身正确，但**时间漂移会改变时段模板**，演示前应确认联网对时成功。

### 已知限制（记进遗留，不改）

- 本地 LSTM 是 231 万参数的域内模型，同一句话多次问**输出会飘**：验证时遇到过
  `ask 十分钟后提醒我` 一次正确设置、一次回成「玄关空调已设为17度」。
  域内固定句式（KEY2 那 12 条）稳定；自由复述不稳定，属模型能力边界。
- 中文数字时长（「一分钟后」）不被 `ag_duration_scan()` 解析（只认 ASCII 数字，
  `ai_lm.cxx:1220`），演示统一用「N分钟后」。
- 触摸活动判定只插在桌宠页云朵与返回键，桌面空白区点击不重置空闲计时。

## Round 28（2026-09-16，蓝牙名改成产品名 + PAN 断网的真相）

### 蓝牙名：App 侧覆盖，不动公共仓

手机配对列表里显示 `Agent-Watch-cd:ab:78:56:34:12`——又长又像地址。这名字是
**框架行为**：PAN 服务在每次适配器上电时把本地名设成 `<前缀>-<MAC>`
（`panu_service.c: pan_set_local_name_with_mac`），前缀是公共仓里的
`#define BT_NAME_PREFIX "Agent-Watch"`，后缀是那枚硬编码假 MAC（24.2）。

**先查了官方要求：没有任何关于蓝牙设备名的规定**（全库搜「蓝牙名/设备名/命名/
device name」，只有仓库命名规则）。所以这是自选动作，走**能自控的路径**：
不改公共仓，在 App 侧覆盖。

`packages/ai_agent/src/agent_main.c` 新增一次性任务 `bt_name_task`：
等适配器到 `BT_ADAPTER_STATE_ON` → 再等 3 秒让 PAN 的 adapter-on 处理先落地
→ `bt_adapter_set_name("小云手表")` → `bt_adapter_get_name` 回读并打日志 → 退出。
无常驻线程。用到的 `bluetooth_get_instance` / `bt_adapter_set_name` 都在已链接的
`liblibbluetooth.a` 里，无需新增依赖。

真机结果：`bttool get name` → `Local Name:小云手表` ✅

两个坑记下来：
1. `bt_adapter_get_name()` 返回 **void**，不是 `bt_status_t`（头文件里那段
   `if (bt_adapter_get_name(...) == BT_STATUS_SUCCESS)` 是文档注释里的**示例**，
   照着写会编译失败：`void value not ignored`）。
2. 适配器名**持久化在 bt_storage.db** 里（`[bt_storage] set AdapterInfo`），
   所以覆盖一次后重启仍在，不必每次开机都设。

### PAN 断网的真相：不是坏，是「放弃了」

现象：`dhcpc_open` 失败 + `dhcp_retry` 循环，最后 `ping` 100% 丢包，
`ifconfig` 里连 `bt-pan` 都没有。查了 bluetoothd 的 fd 表——**只有 11 个，
没有 fd 泄漏**；heap 也正常（900KB/8.4MB）。

烧一次板子（清 RAM）后立刻抓启动时序，一切正常：

```
t+7s [pan] state=adapter-on-auto-connect
t+7s [pan] BNEP setup OK, tx_mtu=1691
t+7s [pan] state=dhcp_ok dev=bt-pan ip=192.168.44.140
     ping 223.5.5.5 → 3/3, 0% loss
```

结论：**PAN 在全新上电下完全正常**。之前那串失败是在手机共享状态反复抖动时，
链路反复连断、DHCP 反复失败，连续失败到 `PAN_ABNORMAL_MAX_RECONNECT=30`
之后状态机进 IDLE **不再重试**（`panu_service.c` 的异常重连上限），于是看起来
像「网络坏了」。恢复办法就是重启一次；`RESET` 按钮是软复位、状态可能保留，
所以**演示前用上电重启（或重新烧录）**。

运维口径（写进交付文档）：演示前确认手机「蓝牙网络共享」开着且手机别息屏，
板子冷启动后 10 秒内应看到 `dhcp_ok`；若没看到，重启板子而不是反复等。

## Round 29（2026-09-16，云端接入 + 自定义 Skill 端到端打通）

这一轮把「云端 LLM」与「设备端主动能力」接起来，并交付了赛题要求的自定义 Skill。

### 云端 LLM 接入（StepFun）

- 用 `set_llm https://api.stepfun.com/v1/chat/completions step-3.7-flash <key>`
  配置，落在 `/data/ai_agent/config/config.json`，重启保留。
- 实测：TLSv1.2 握手成功、约 8.6–12 s 返回、`backend=0` 走云端，工具调用链完整。

顺手记三个运行期坑：

1. **NSH 行长 64**（`CONFIG_NSH_LINELEN`）把 110 字符的命令截断成两段执行 → 提到 256。
2. **`set_llm`/`router_status` 在 agent 自己的 `vela>` CLI 里**，而它与 NSH 抢同一个
   console、NSH 优先级更高总赢。绕过办法：**先发一条耗时 NSH 命令**（`ping -c 12`）
   占住 NSH，此时只有 CLI 线程在读输入，命令就进去了。
3. **路由器连续失败 3 次会把后端打入退避**（`MAX_CONSECUTIVE_FAILURES=3`、
   `RECOVERY_INTERVAL_SEC=300`）；手机热点抖动时会累积到这个状态，表现为
   `No available backend` 并静默走端侧兜底。另外实测 **`config.json` 里的 LLM 配置
   被整文件覆盖过一次**（只剩 DNS 两项），怀疑是网络组件保存自身配置时未合并键值，
   记为待查（`24_open_issues`）。

### 自定义 Skill：`care-reminder`

- 内置进固件（`skill_loader.c` 的 `BUILTIN_CARE_ALARM`），烧录后即存在于
  `/data/ai_agent/skills/care-reminder.md`；团队仓保留同名 .md 作为可审阅副本。
- 配套新增两个**云端工具** `set_alarm` / `cancel_alarm`：云端 LLM 原本够不到设备端的
  主动叫醒能力（那是端侧模型 `set_timer` 的活），补上后两条路径共用同一个
  `pet_care_alarm_set()`，行为一致、只有一份状态机。

### 一个值得记下来的调参过程

第一次演示时模型选了 **`cron_add`**（内置 `reminder` 技能的静默通知），而且
**根本没读技能文件**——trace 里只有 `get_current_time` + `cron_add`。说明它只凭
**系统提示里的一行摘要 + 工具描述**就做了选择。改进三步：

1. 技能首段（进摘要）写明「手表上的提醒一律用本技能（优先于 reminder/cron）」
2. `set_alarm` 工具描述加一句 `PREFER THIS over cron_add`
3. 内置 `reminder` 技能里加一段：来自手表自身的提醒请求请改用 care-reminder

改完立刻生效：`iter=0 tool=set_alarm` → `[pet_care] alarm set 40min` → 自然语言确认。

### 又一个真 bug（顺手修掉）

StepFun 在工具调用成功后返回了**空文本**，agent 于是回了
`Sorry, I encountered an error.`——闹钟其实已经设上了，用户却看到报错。
`run_react_loop` 现在在「有工具调用成功但没有收尾文本」时给出确认语并把 trace 标成 OK。

### 另外两处平台限制（已记进 24 号文）

- **设备名**：宿主侧能改（`bttool get name` → `小云手表`），但空口上报仍是 PAN 服务
  设的 `<前缀>-<MAC>`；App 侧覆盖对空口无效，只能改框架（公共仓 PR）或 LCPU NVDS。
- **内置技能只在文件缺失时安装**：改了文案但 `/data` 上文件已存在就不会更新，
  需先删掉对应 .md 再重启。评委若用旧 `/data` 复现会看到旧文案。

---

## Round 30 — KEY1 进不了桌宠页：一个恒假的判断（2026-09-19 夜）

### 症状

`KEY1` 按下去串口有日志（`[key] KEY1: open pet page`），屏幕纹丝不动；`KEY2` 提问后
屏幕上也什么都不出现，但串口一直在打 `[PetPage] Response updated: ...`。

### 根因：`launcher_is_on_desktop()` 恒为 false

```c
desktop_page = lv_obj_create(lv_scr_act());       /* 是 screen 的子对象 */
bool launcher_is_on_desktop(void)
{
    return (lv_screen_active() == desktop_page);  /* 永远不成立 */
}
```

页面不是 `lv_screen_load()` 出来的，而是作为兄弟节点用 `lv_obj_move_foreground()`
叠在桌面上——所以「当前在哪一页」只有 `s_current_page` 知道，`lv_screen_active()`
永远返回那个 screen。这个判断恒假，把两个按键回调都挡掉了：

- `key_open_pet_cb`：KEY1 变成空操作
- `key_ask_shown_cb`：KEY2 想先自动进桌宠页同样进不去，而
  `pet_page_update_response()` 挂在这道门控**下游**且不检查页面是否打开，
  于是「有日志、无显示」

修法是把判断换成 `s_current_page == NULL`（页面叠上去时为非空）。
反汇编确认修复进了镜像：`launcher_is_on_desktop` 现在读 `s_current_page`（0x2003a638）并判零。

### 顺带补上：回复不再写进看不见的地方

`lvgl_ui_channel_send` / `lvgl_ui_channel_log` 只把文本交给桌宠页文本层，从不把页
带到前台；`lvgl_ui_channel_show()` 里那段「在桌面就自动进宠物页」只有调试命令
`show_chat` 调过一次，等于死代码。现在两条落地回调
（`bubble_update_async_cb`、`history_log_async_cb`，都在 LVGL 线程上执行）在
「非用户消息 + 当前在桌面」时先 `launcher_enter_page(PAGE_PET)` 再写文本。

真机验证（不依赖手指按键）：冷启动停在桌面 → 控制台发 `ask 现在几点了` →
`[Launcher] Entering pet page` → `[PetPage] Creating pet display page` →
`[PetPage] Response updated: ...`。与 KEY1 走的是同一个 `launcher_enter_page()`。

### 一次误判，记下来

想验证空闲关怀时，先按「免打扰 22:00–07:00」推断关怀被压制、准备改时区配置。
读了一次 `date` 才发现**设备时钟本来就是 UTC**（`Sat, Sep 19 15:44:36 2026`
对应北京 23:44）：`CONFIG_LIBC_LOCALTIME` 未开，`localtime_r` 原样返回 RTC，
而 RTC 是 SNTP 写进去的 UTC。于是「免打扰」实际按 UTC 小时判定——今晚 15 点
不落在静默区，关怀照常工作。

- 演示便利：北京夜里也能拍「空闲关怀」这一幕
- 真实缺陷：静默区相对用户作息偏了 8 小时（北京时间 06:00–15:00 才是静默区），
  已记入 24 号文。**修它会让今晚这幕立刻被静默掉，拍摄前不要动**
- 附带教训：先读一次 `date` 再谈时区，比从文档里的「本地时间」四个字推断可靠

---

## Round 31 — 一个手机网页：不用 App、不传文件、不外网（2026-09-19 深夜）

### 起因：评委看不到"可操控"的入口

现有可交互的东西散在两个按键、触摸页面和控制台上，演示时容易显得"没有产品感"。
用户提的方向是"搞一个最简单的 app"。工作区里确实有个现成的安卓配套 App
（`com.agent.coapp`：BLE 配网 + 配置 + 技能 + 对话 + 日志），但它要 Android SDK
构建、且依赖 BLE GATT/REST 一整套（当前构建里 `AI_AGENT_BLE_GATT`、`AI_AGENT_NODE`
都是关的），今晚不可能打通——**决定不复用它，按自己的需要写一个最小的**。

### 发现：设备里本来就有 WebSocket 服务

`ws_server_start()` 在 `agent_main` 里随网络就绪启动，监听 **28789**、绑定
`INADDR_ANY`（也就是 bt-pan 上的 192.168.44.140 直接可达），协议是
`{"type":"message","content":"..."}` 进、`{"type":"response","content":"..."}` 出。
缺的只有两件事：**没人给它一个网页**，以及**手机侧对话不上手表 UI**。

### 做法（4 处改动，全部在公共仓这一侧）

1. `api_handler.c`：原本就有 `api_try_handle()` 这个 HTTP 入口（`CONFIG_AI_AGENT_REST_API`
   只是默认关着），加一条 `GET /` 路由返回网页；新增 `send_response_html()`（JSON 版
   是现成的，HTML 只是换个 Content-Type）
2. `res/phone_chat.html`（7.4 KB，单文件零依赖）+ `tools/gen_chat_page.py` 生成
   `src/infra/chat_page.h`：非 ASCII 用八进制转义（`\x` 是贪婪的，会吃掉后一个字符）
3. `ws_server.c`：手机发来的话进手表历史环；`agent_main.c`：回复写进桌宠页文本层
   —— 于是手机对话会和本地对话一样，**自动把桌宠页带出来**（复用 Round 30 的修复）
4. defconfig 打开 `CONFIG_AI_AGENT_REST_API=y`（顺带激活 `/api/config`、`/api/skills`、
   `/api/logs` 三个端点，正好是那个安卓 App 需要的协议）

顺带修掉一个潜伏缺陷：REST 响应后原代码用 `SO_LINGER(0)` 直接发 RST 关连接，
小 JSON 看不出来，7.4 KB 网页有被截断的风险 —— 改成 `shutdown(SHUT_WR)` + 正常关闭。

### 真机结果：链路是唯一的拦路虎

手机浏览器访问后，**设备日志出现 `[api] serving phone chat page (7444 bytes)`**：
说明手机的网络确实打到了手表、页面确实发出去了（安卓网络共享**没有**拦截
手机→PANU 的入站连接）。但浏览器报"拒绝访问"——查下去发现真正的问题是
**bt-pan 链路在抖动**：日志里每 60～80 秒一次 `dhcp_start → dhcp_ok`，最后网络栈
`sendto failed: 101 (ENETUNREACH)`，状态机放弃重试（与 24 号文记的"重试耗尽"一致），
于是页面响应在传输途中链路断掉。`llm=fail backend=0`（80 ms 就失败）也是同一个原因。

结论：**网页这条链路本身是通的，卡在手机侧的网络共享不稳**。恢复顺序（已写进拍摄清单）：
手机上关掉再打开「蓝牙网络共享」→ 手表按 RESET 冷启动 → 串口等到
`dhcp_ok ip=192.168.44.140`。

### 两条运维经验（今晚各踩一次）

- **不要用 RTS 断电当"重启"**：芯片会停在 ROM bootloader，串口全静默。重启只按
  RESET 键（软复位）或重新烧录。RTS 断电只用于烧录抢 bootloader 窗口。
- **烧录偶发失败**：`Failed to download stub: Timeout("waiting for shell prompt")`，
  先做 2.5 秒断电再烧、或直接重试即可（今晚 3 次失败全部靠重试/深度断电通过）。
- **烧录彻底卡住时**：出现 `receiving UART frame` / `Failed to connect to the chip`
  并在各波特率（1000000/921600/460800/115200）、`no_reset`/`default_reset`、
  兼容模式下都失败时，芯片会停在 ROM bootloader（串口回 `msh >`），
  **拔插 USB 物理断电**一次即可恢复（实测有效，插回后应用正常启动）。

### 链路抖动：一次尚未闭合的根因定位（把已排除的和剩下的都写清）

从网页版固件上线前后开始，bt-pan 链路出现**间歇性反复断开**：某 4 分钟窗口断 6 次
（每 22～40 秒一次），另一个 130 秒窗口一次不断；断开时日志出现
`sf32lb52 bth4: conn unreg handle=0x0081`。**已排除**：

| 假设 | 证据 | 结论 |
|------|------|------|
| 空闲超时断链 | 持续 `ping -c 300` 的 4.5 分钟里仍断 5 次 | ❌ 不是空闲 |
| 内存不足（BT 缓冲被饿死） | `heap_info` 显示 PSRAM 堆 free 7.5 MB | ❌ 不是内存 |
| 我们自己的定时器拆链路 | 全部 3 处 `bt_sal_pan_disconnect` 调用点（DHCP 耗尽/自身清理/上层 API）都非周期性 | ❌ 代码侧无定时拆链 |
| DHCP 续租失败触发断链 | DHCP 客户端每次 PAN 连接只 request 一次即关闭，无续租逻辑；断链发生在 `dhcp_start` **之前** | ❌ 不是 DHCP |

**剩下的可能（按可能性排序）**：

1. **对端手机侧**（Android 网络共享的策略/省电）——没有任何手机侧日志，无法自证
2. **LCPU（供应商蓝牙控制器固件）**：`conn unreg` 来自二进制 blob（源码树中不存在），
   是控制器在注销连接；24 号文已记过 LCPU 在 ACL teardown 后状态陈旧
3. **RF/环境**（距离、2.4G 干扰）
4. **网页版构建多占 16.5 KB SRAM**（REST 的日志环）：BT 主机协议栈缓冲在 SRAM，
   理论上可能饿死 —— 但空闲时也断，机理偏弱

**下一步实验（硬件恢复后按序做，工具已备好）**：

1. `python3 docs_ble/tools/link_watch.py 300` 取基线（量化在线/离线时长与断开间隔）
2. 换变量重测：手机贴近 vs 放远、换一台手机、冷启动双方
3. A/B 固件：关掉 `CONFIG_AI_AGENT_REST_API` 的对照版（省回 16.5 KB SRAM）
4. 若要断开原因码：PAN 回调不带 reason，得在 HCI 层打点，而该层是 blob，
   需走 zblue 侧事件解析或空口抓包（成本高，放最后）

**不阻塞交付的重要结论**：不依赖链路的几幕（按键进桌宠页、端侧模型对话、
空闲关怀、闹钟三段、心率告警）全部可用，且**端侧模型本身就能离线设闹钟**
（`tool=set_alarm` → `[pet_care] alarm set 3min` → 三段照常响，实测过）。
链路只影响"云端对话"与"手机网页对话"两幕。

### 抓到并修掉一个真 bug：MTU 查询竞态导致"自己拆自己的链路"

用 `restart` 触发一次软复位后抓启动全过程，终于拿到关键序列：

```
BNEP setup OK, tx_mtu=1691                         ← BNEP 协商成功
state=ifup_abort reason=no_negotiated_mtu tx_mtu=0 ← ifup 却查到 MTU=0，直接放弃
state=disconnected                                 ← 上层于是把刚建好的链路拆掉
```

根因在 `sal_pan_interface.c`：`bt_sal_pan_get_tx_mtu()` **按地址精确查找**连接记录，
查不到或查到非 CONNECTED 的记录就返回 0；而 SAL 里有两条建连路径
（主动 `bt_sal_pan_connect()` 原样拷贝上层地址、被动 `pan_server_accept()`
用协议栈自己的地址表示），地址表示不一致时就会"查不到"→ 返回 0 →
上层 `pan_ifup_and_dhcp()` 日志 `ifup_abort` 并放弃 → 链路被拆 → 重连 → 循环。

修法（`PAN_MAX_CONNECTIONS` 本来就是 1，最多只有一个候选）：按地址查到就返回；
查不到则**退化为"唯一的已连接记录"**，并打一条 WARNING。修复后实测：

```
state=ifup dev=bt-pan tx_mtu=1691 if_mtu=1500   ← 不再 abort
dhcp_ok dev=bt-pan ip=192.168.44.140
```

`ifup_abort` 从"每次连接必现"变成 0 次。

### 但还有第二个原因：约 50～65 秒一次的周期性断开

修复后链路能稳定建立（每次都有 `dhcp_ok`），但仍在 ~60 秒周期上被拆：

```
57s:DOWN 68s:UP | 122s:DOWN 132s:UP | 168s:DOWN 180s:UP | 220s:DOWN 231s:UP
```

周期过于规整，像定时器/租期行为。我们这边已无线程周期性拆链（4 处调用点全部
核过），且期间实测：手机不在场时表现为 `ACL connect err 4`（HCI 0x04 Page Timeout，
对端不应答），在场时表现为 `reason=8`（链路监督超时，对端停止响应）——两者都指
"对端射频层面不应答"。

### 方法教训：只数"断开次数"会得出相反结论

一度用"240 秒零断开"判定"手机亮屏即稳定"，**结论是错的**：那 4 分钟里链路其实
已掉线、状态机处于放弃态（事件数为 0 被我当成了"一直连着"）。正确口径必须同时测
**事件（状态机在动吗）+ 连通性（网关探测通不通）**：

```
t+220s connected → ifup(tx_mtu=1691，无 abort) → dhcp_ok → dhcp_dns
t+224s 网关探测 OK            ← 连上立刻能通
t+238s 网关探测 无响应(超时)   ← 约 15 秒后对端哑掉，此时链路还在
t+248s state=disconnected     ← 再过约 10 秒，链路监督超时判定断开
```

统计：事件 36 条（每次重连都完整走通，`ifup_abort`=0，MTU 修复确认生效），
网关探测 OK 4 / 失败 14，断开间隔稳定 42 秒。**残留抖动的责任在对端**：手机共享
服务在每次连接约 15 秒后停止应答（先不回 ICMP，再在射频层沉默）→ 手表侧监督超时。
下一步只能从手机侧动手：关开共享、重启手机、换手机（或抓 Android 侧日志）。
不在本仓可修范围内。

### 之后又做的四项优化（用户批准 1/2/3，含一处自洽性修正）

1. **无上行时直接走端侧**（`core/agent_loop.c`）：调云端之前先查 `network_get_state()`，
   不是 CONNECTED 就跳过云端、直接走端侧模型。**刻意不计入后端失败**
   （不调 `llm_router_report_failure`）——后端本身没坏、坏的是射频，误罚会让它
   在恢复窗口里白白被禁用。
2. **socket 读超时 120s → 60s**（`include/agent_config.h`）：半死链路上挂起时间减半。
3. **后端失败后的禁用窗口 60s → 15s**（`src/llm/llm_router.c`）：实测 bt-pan 掉线约 40 秒，
   原来 60 秒的窗口意味着"链路刚恢复的那条消息仍由慢的端侧模型回答"。
4. **自洽性修正（差点埋雷）**：`AGENT_LLM_TIMEOUT_SEC` 的语义是**事后判定** ——
   一次"成功但耗时超过看门狗"的调用，其**正确回答会被丢弃并替换成超时文案**。
   实测单次云端调用出现过 39s、45s，因此把看门狗提到 **90s**、socket 超时定在 **60s**：
   保证"慢但正确的回答照用、挂死的请求 60 秒内失败并回退"。

**优化后的实测**：链路公网 ping 5/5、RTT 100～140ms、0% 丢包；云端单次调用 8.4s；
同一问题第二次问 **`llm_ms=0`（缓存命中、秒回）**。云端延迟本身在 8～39 秒间波动，
属服务端波动、非本仓可修项 —— 演示加速靠**预热缓存**（开拍前把要问的问题先问一遍）。
