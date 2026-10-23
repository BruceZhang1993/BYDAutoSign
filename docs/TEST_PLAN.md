# v0.5.0 设备验收与 CI 测试

## CI
- [x] 纯 JVM AES-128-CBC/OpenSSL 向量、SHA256 checksum 对照
- [x] 调度时间解析和跨日顺序用例（代码已添加，实际状态以 CI 为准）
- [x] `assembleDebug` + `testDebugUnitTest` 通过（2026-10-23 CI）
- [x] 已签名 Release APK 生成、apksigner 验证，且 APK 不含 root runtime/curl/openssl（2026-10-23 CI）
- [x] MockWebServer 离线验证登录/签到请求头、URL、AES 加解密、嵌套 JSON、HTTP 错误脱敏
- [ ] MockWebServer 补全业务失败与 TLS 错误契约测试

## 真机（按实际验证范围区分）

> 2026-10-24：用户先确认主微信 Binder 取码 → Kotlin 原生会话兑换与 Android Keystore 保存成功，后反馈之前列出的**实际签到、关闭微信后的锁屏定时执行、重启后的调度恢复、跨天使用 session**均已实机验收正常。以下“实机通过”均为**用户报告**，不是 CI 或第三方实验室覆盖；未报告具体 Android 版本、ROM、强制 Doze、断网恢复或异常响应测试的数据仍标为待验收。
- [ ] 全新安装无需 Root 的全流程验收（当前确认的是升级后运行的无 Root 代码路径）
- [x] 用户实机反馈：主用户微信 Binder 通信与授权可用
- [x] 用户反馈：重新授权，从微信 code 兑换 NativeSignEngine session 并本地保存（与旧 Root 会话无关，2026-10-24）
- [x] 用户实机反馈：使用原生 session 立即签到成功（2026-10-24）
- [ ] 重复点击签到的当日幂等跳过、连续天数计数专项测试
- [x] 用户实机反馈：关闭微信后锁屏状态自动签到正常（2026-10-24）
- [ ] 强制 Doze、网络断开后的 WorkManager 延迟执行与恢复专项验收
- [ ] Android 12/13/14/15/16 精确闹钟权限允许/拒绝后均能解释下次唤醒
- [x] 用户实机反馈：重启手机后定时调度恢复正常（2026-10-24）
- [ ] 人为修改系统时间/时区、APK 升级、进程强制终止的专项测试
- [x] 用户实机反馈：跨天沿用已保存 session 签到正常（2026-10-24）
- [ ] 无网络、HTTP 5xx、HTTP 4xx、解密错误、明确 SESSION_EXPIRED 均安全失败且不泄漏 token
- [ ] 不会访问 `su` 或 `/data/adb`，不会在定时任务中唤醒微信
- [ ] 旧 Root 模块/cron 停用后无重复签到
- [ ] 更换证书/数据恢复失败时明确提示重新授权
