# 110. P1b：App 关怀时间线 + ack + 设置同步（协议/范围）

> 基线：固件 v142i（P1a ticket/log/隐式反馈）、App 非演示 080ef0d1。约束：固件 /data 只读、不加线程；App 可本地持久化设置；所有 care 数据 JSON 走现有 BLE 命令通道。

## 1. BLE 协议（App ↔ 手表）

### care_list（App 拉时间线）
```json
{"cmd":"care_list","limit":8}
```
回包：
```json
{"evt":"care_list","count":8,"items":[
 {"seq":7,"ts":1793796000,"type":"idle","state":"RESTING",
  "decision":"ACTION","reason":"idle_5min","action":"fire",
  "feedback":"IGNORED","score":1,"hr":0,"dev":0,
  "still_min":0,"idle_s":300,"hour":13,"dnd":0,"quiet":0}
]}
```
- 按 seq 倒序（最新在前）；最多 8–16 条；无数据返回 count=0；
- 字段全部来自 RAM care_log，不读 /data。

### care_ack（App 显式反馈）
```json
{"cmd":"care_ack","seq":7,"feedback":"ACCEPTED|IGNORED|REJECTED"}
```
回包：
```json
{"evt":"care_ack","ok":1,"seq":7,"feedback":"ACCEPTED"}
```
- 更新对应 care_log feedback；覆盖隐式值；越界/不存在 ok=0；
- 不写盘（重启丢，App 自己保存历史）。

### care_settings_get / set
```json
{"cmd":"care_settings_get"}
{"cmd":"care_settings_set","dnd":0,"quiet_start":22,"quiet_end":7,
 "daily_budget":8,"types":{"hr":1,"idle":1,"sedentary":1}}
```
回包：
```json
{"evt":"care_settings","ok":1,"dnd":0,"quiet_start":22,"quiet_end":7,
 "daily_budget":8,"types":{"hr":1,"idle":1,"sedentary":1}}
```
- 设置只存 RAM；App 本地持久化，连接后重新下发；
- quiet 起止小时 0–23；daily_budget 1–12；types 缺省 1；
- DND 与现有 settings_set dnd 双向同步。

## 2. 固件改动（P1b）
1) care_log：`add` 返回 seq；`mark_pending(seq)`/`ack(seq,feedback)`；dump 保留；
2) ticket：去重不取消 FIRED（保留审计链）；status 增加 ACK 状态可选；
3) care_router：运行时可配 quiet_start/end、daily_budget、类型开关（默认沿用 22/7/8/1）；
4) BLE handler：实现 care_list/care_ack/care_settings_get/set；长度限制/越界校验；
5) care_test dump 继续可用；不新增线程/不写盘；静态 RAM 增量 ≤512B（JSON 缓冲可复用现有）。

## 3. App 改动（P1b）
1) WatchBleClient：sendCareList/sendCareAck/sendCareSettingsGet/Set；解析 evt=care_list/care_ack/care_settings；
2) AppStore：careEvents 列表、careSettings（本地持久化，SharedPreferences/DataStore）、拉取/ack/设置方法；5s 轮询或手动刷新；
3) UI：
   - 关怀页/设备页加「手表关怀时间线」：时间、类型、原因、决策、feedback，可点接受/忽略；
   - 「主动关怀设置」：DND、夜间时段、每日上限、类别开关；连接后自动下发，修改即下发；
   - 演示数据/真实数据区分（非演示版不显示演示数据）。
4) 构建 APK，dex 验收；不改固件其他功能。

## 4. 验收
1) 固件：`ui ble '{"cmd":"care_list"}'` 返回 JSON；ack 后再次 list feedback 变化；settings_set/get 生效；DND 设置后主动为 SILENT；
2) App：连接后时间线显示最近 8 条；点接受/忽略后手表回包并刷新；设置修改后 DND/夜间/预算/类别生效；
3) 重启：设置丢失（RAM）但 App 重连自动下发；/data 零写；
4) 稳定性：无 SFBL/RSET/Assertion，无新线程。

## 5. 风险
- BLE JSON 大小/分片：care_list 8 条约 1.2–1.6KB，现有 TX slot 1602B，需确认拼包；
- care_settings 只 RAM，重启依赖 App 重下发；
- 隐式/显式反馈冲突：App ack 覆盖隐式；
- App 轮询增加 BLE 流量，5s 一次可接受。