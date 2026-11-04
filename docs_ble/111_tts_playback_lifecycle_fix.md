# 111. TTS 播放卡顿/没有声音：root cause 与修复（v142m）

## 现象
- 语音 TTS 有时慢/像没声：speak done 28–46s；play wait 28–34s；pcm_media reap=0/deq=0；audio_tx deq=0。
- 重启后恢复正常（短句 2.4–3.3s、play wait<1s、reap=enq）。

## 根因
- pcm_media 在 worker stop 超时后 `worker_hung` 分支跳过 join，但 close 仍 free p/fd/mq/APB；
- 活 worker 持有已释放指针/旧设备状态 → UAF；audio 是设备级共享 upper，僵尸客户端污染后续 TTS；
- 后续 play 的 8 个 APB 已 enq 但无 DEQUEUE → reap=0 → stop 超时 → 更慢。

## 修复（v142m）
- F1 纯 leak 生命周期：超时只登记泄漏，不 join/free 活 worker；close 返回 -EBUSY；open 先 reclaim，僵尸未退则安全 REJECTED。
- F3 audio_tx tx_stop drain/in_fill/cur 锁内，避免 stop/worker 竞态；phase/fstall/lockfail 观测。
- F4 stop_lock 双 stop 串行化；P2-2 stop_done 重复 stop 快速返回。
- F2 音频 busy 快速失败，不再让用户等 8–35s。

## 实测（v142m，49ed44dd）
- clean boot/软重启后：speak 3.3–3.8s、play wait 0.87–0.99s、reap==enq、stop reason=free；
- 连续 3 短 + 1 长(25字)：enq/reap=62、long 14.8s、无 leak/REJECTED；
- 语音回答不再卡 30s。

## 遗留
- PTT 打断 TTS 的 abort 快路径（F5）未做，PTT 可能等 stop 预算（3–20s）；
- nuttx/audio/audio.c 最后 close 释放 upper->status 而 tx_shutdown 不 stop DMA 的结构问题未改；
- TTS 每 boot 20 次、/data 只读。
