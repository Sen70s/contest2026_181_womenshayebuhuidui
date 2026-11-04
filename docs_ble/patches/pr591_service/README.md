# PR #591 service 层补丁（产品侧归档）

官方 review 边界：`open-vela/frameworks_bluetooth` 的公开 PR #591/#594 只接受 stack/SAL。
本目录把 service 层改动归档到参赛产品仓，不提交 open-vela 公共树。

## 内容

- `0001-pan-service-fixes.patch`：10 个 service 文件，base `028b4ba7` -> 产品分支 `pr591-official-only`
- 覆盖 #6 DHCP、#7 本地名/身份、#8 storage、#9 TEMP-DIAG，以及 service_loop/main/ipc/adapter/profile API 支撑

## 使用方式

该补丁面向产品分支：

- frameworks 分支：`product/pan-service`（本地，基于 `pr591-official-only`）
- 不要单独应用到 #594 的 stack-only 分支；#594 使用旧 profile API 兼容层

应用：

```bash
cd frameworks/connectivity/bluetooth
git apply /path/to/0001-pan-service-fixes.patch
```

然后使用产品 defconfig 构建。

## 验证

- 产品镜像：PAN / DHCP / ping / DNS 通过
- #1 watchdog 故障注入通过
- #8 损坏 key 故障注入通过
- #9 启动日志无 TEMP-DIAG

## 边界

- 不提交到 `open-vela/frameworks_bluetooth`
- 不关闭 #591
- service 改动是否进入其他公共仓，需维护者明确指定

## 文件清单

```text
service/common/service_loop.c
service/common/service_loop.h
service/common/storage.c
service/common/storage.h
service/ipc/bluetooth_ipc.c
service/profiles/include/pan_service.h
service/profiles/pan/panu_service.c
service/src/adapter_service.c
service/src/btservice.c
service/src/main.c
```