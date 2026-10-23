# BYD Auto Sign 原生架构（v0.5.0）

```text
用户点击初始化/重新授权
  → IpcBrokerService (UID/AppOps 校验)
  → LSPosed 微信 Hook (仅主 User 0)
  → 一次性 code
  → NativeSignEngine.exchangeSession (JCA + OkHttp TLS)
  → SignStore (AndroidKeyStore AES-GCM 密文)

每天用户定义的多个 HH:mm 时间
  → AlarmManager RTC_WAKEUP (精确/非精确)
  → SignAlarmReceiver (只排下一次闹钟并派发)
  → WorkManager (网络约束、指数重试)
  → SignCoordinator (今日幂等、互斥)
  → NativeSignEngine.sign (读已缓存 BYD session，不唤醒微信)
  → SignStore (记录成功日期、结果、连续天数)
  → SignHistory (100条结构化执行记录)
  → ScheduleTelemetry (计划/闹钟/入队/执行/自检)
  → SignNotifier (首次成功或终态失败；已签到及重试中不通知)
```

## Root 已彻底退出运行链

Android 应用不再调用 `su` / ProcessBuilder，不再复制到 `/data/adb`，不启动 `service.d`、BusyBox crond，不捆绑或运行 curl/OpenSSL。旧 Root 目录由用户通过旧 Root 管理工具手动停用或移除；普通 APK 无法合法代为访问或删除。

注意：LSPosed Hook 仍然是首次微信登录授权的外部前提。旧 wxcode 的前台常驻保活 Service 已移除；只保留短时授权工作线程。所谓“无 Root”指本应用运行链不调用 Root/Root 脚本，并不表示所有 LSPosed 安装方案都不需要系统级权限。

## 协议与安全

BYD 旧客户端协议：`AES-128-CBC/PKCS5Padding` + Base64，`SHA-256(APPSECRET+nonce+curtime)`，两个 POST 服务 `mina.decryptCode` 与 `activity/sign/signIn`。固定密钥和签名常量属于客户端协议，**不是个人 session 密钥**；会话存储另使用随机 IV 的 AndroidKeyStore AES-GCM。正常网络请求启用操作系统默认 CA 和主机名验证，不再接受上游 `curl --insecure` 的不安全做法。

服务端是否强制特殊 header、是否跨天有效和 session 失效信号尚未在新版设备上验证。正常业务失败不清空凭证，只有匹配明确过期反馈才清除。绝不记录敏感 code/session/原始响应。

## 调度语义

自定义时间按手机本地时区计算，每次仅注册下一次闹钟。Android 12+ 没有精确闹钟特权时回退 `setAndAllowWhileIdle`。闹钟广播只提交一次唯一 WorkManager 任务，签到在网络可用时运行，重试最多四次；开机/升级/时区/时间及权限变化重新注册，并在当天至少一个配置时刻已到且未签到时补偿。另有每 12 小时的 WorkManager 低频 watchdog：如系统在撤销精确闹钟权限时静默清除了闹钟，可延迟重建并补签（不保证准确时刻）。

WorkManager 不提供毫秒级执行时间保证。部分 ROM/深度睡眠也可能限制 AlarmManager，实机状态与连续运行验证不可省略。任务不请求 Binder、不唤醒微信、不刷新 session。

## 已知限制

- 主微信 Binder 取码、线上成功签到、锁屏调度、重启恢复及跨天复用已经当前设备实机验收；网络恢复、会话过期及其他 ROM 仍待专项验证。
- 跨 Android User 微信分身暂不支持新授权；不隐式读取旧 Root 会话。
- 尚未实现主动自动重授权、通知配置、多账号/多品牌 Provider。
- 安卓进程被系统限制、精确闹钟特权被撤销时执行可能延迟。
