# BYD Auto Sign 原生架构（v0.5.5）

```text
用户点击初始化/重新授权
  → IpcBrokerService (UID/AppOps 校验)
  → LSPosed 微信 Hook (仅主 User 0)
  → 一次性 code
  → NativeSignEngine.exchangeSession (JCA + OkHttp TLS)
  → SignStore (AndroidKeyStore AES-GCM 密文)

每天用户定义的一个 HH:mm 时间（失败时每小时重试）
  → AlarmManager RTC_WAKEUP (精确/非精确)
  → SignAlarmReceiver (只排下一次闹钟并派发)
  → WorkManager (网络约束、指数重试)
  → SignCoordinator (今日幂等、互斥)
  → AutoSessionRefresher (跨天使用 Binder 取新 Code 并兑换新 Session；离线回退旧会话)
  → NativeSignEngine.sign (使用新会话或安全回退的缓存会话，不启动微信)
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

WorkManager 不提供毫秒级执行时间保证。部分 ROM/深度睡眠也可能限制 AlarmManager，实机状态与连续运行验证不可省略。任务会优先通过 Binder 进行当日会话刷新（最迟等待 12 秒以允许既有 Hook 重连），但不主动启动微信、不调用 Root。若取码失败，优先回退到缓存会话；若失败疑似授权过期，则按当天每小时策略继续重试。

## v0.5.2 调度自愈与错误恢复

- 定时快照缺失或超期（容差 5 分钟）时可在前台触发重建；开机、时区/系统时间调整、APK 更新以及 12 小时 watchdog 均重新注册闹钟，恢复可能被系统静默取消的计划。重建结果与原因持久化供诊断展示。
- 本地认证丢失、不可解密、服务端明确过期的情况下，自动执行需等待再次授权；TLS、HTTP 4xx、协议、业务错误当日停止自动尝试；临时网络、超时、服务器故障则由 WorkManager 退避重试，补偿具备 30 分钟冷却。手动签到与明确的重新授权不受自动阻断限制。
- 签到成功后的本地日期使用响应完成时刻并同步持久化；跨午夜请求记录次数用于排查，后续任务按新日期进行幂等判断。服务端日期规则仍依赖协议实际行为，边界情况需真机验证。
- 诊断报告用字段白名单生成，仅包括枚举、权限布尔值、时间戳、调度配置和签到记录，不包含原始服务端错误、用户凭证或 Hook/Binder 原文。导出位置由 Android SAF 选择。

## 已知限制

- 主微信 Binder 取码、线上成功签到、锁屏调度、重启恢复及跨天复用已经当前设备实机验收；网络恢复、会话过期及其他 ROM 仍待专项验证。
- 跨 Android User 微信分身暂不支持新授权；不隐式读取旧 Root 会话。
- 已实现用户开启自动签到后的逐日授权刷新；但微信进程被系统终止时不能无 Root 强行启动，因此仍需微信 Hook 连接。暂不支持通知配置、多账号/多品牌 Provider。
- 安卓进程被系统限制、精确闹钟特权被撤销时执行可能延迟。


## 时间选择与调度记录（后续改进）
- 设置页采用原生 24 小时制时间选择器，逐条增删改、最多 8 个；存储仍使用原有规范化 `HH:mm` 列表，保持兼容。
- `SignHistory` 只记录真实签到请求；`ScheduleEventLog` 单独保留最近 100 条闹钟触发、任务提交和已签到/阻断跳过记录。若此前已有已签到标记、但旧版本没有历史，只有保存了可信的执行时间和成功结果时才补录一次。
- 闹钟触发后即使今日已签到，也记录触发与跳过（不再调用签到接口），并在首页、历史筛选页和脱敏诊断导出中展示。

## v0.5.5 微信授权自动恢复

- 每个本地日期首次真实签到时，若缓存会话来自前一日，则通过经 UID/AppOps 验证的微信 Binder 重新获取**一次性**登录 Code，立即兑换新 Session；不持久化 Code。
- Binder 暂未连接时最多等待 12 秒；微信 Hook 已有 5 秒重绑周期。修复 Hook 重绑成功后被旧重试定时器意外解绑的竞争条件。
- Hook 返回 -100（请求繁忙）时延迟 2.5 秒后最多再取码一次；对 -210/-603（取码超时）和映射失效不进行盲目循环请求。
- Session 刷新失败时保留旧缓存；疑似失效的 ret=50010 仅作为刷新线索，不能直接判定为授权过期。当失败发生时继续保留当天每小时重试与本地幂等控制。
- 只在首页和诊断导出保存授权刷新阶段枚举、取码状态整数和发生时间，不保存 Code、Session 和原始服务端响应。无法无 Root 保证后台唤醒已结束的微信进程。
