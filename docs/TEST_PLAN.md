# v0.5.0 设备验收与 CI 测试

## CI
- [x] 纯 JVM AES-128-CBC/OpenSSL 向量、SHA256 checksum 对照
- [x] 调度时间解析和跨日顺序用例（代码已添加，实际状态以 CI 为准）
- [ ] `assembleDebug` + `testDebugUnitTest` 通过
- [ ] 已签名 Release APK 生成、apksigner 验证，且 APK 不含 root runtime/curl/openssl
- [ ] MockWebServer 模拟登录与签到服务，验证请求头、URL、业务失败、TLS 错误

## 真机（尚未验收）
- [ ] 全新安装无需 Root，LSPosed 微信主用户 Binder 已连接
- [ ] 重新授权，从微信 code 兑换 NativeSignEngine session（与旧 Root 会话无关）
- [ ] 立即签到成功、今日再次执行跳过，连续天数正确
- [ ] 锁屏/Doze 下 AlarmManager 如预期触发，WorkManager 网络恢复后执行
- [ ] Android 12/13/14/15/16 精确闹钟权限允许/拒绝后均能解释下次唤醒
- [ ] 系统重启、系统时间/时区变化、APK 升级、进程死亡后恢复下次调度
- [ ] 无网络、HTTP 5xx、HTTP 4xx、解密错误、明确 SESSION_EXPIRED 均安全失败且不泄漏 token
- [ ] 不会访问 `su` 或 `/data/adb`，不会在定时任务中唤醒微信
- [ ] 旧 Root 模块/cron 停用后无重复签到
- [ ] 更换证书/数据恢复失败时明确提示重新授权
