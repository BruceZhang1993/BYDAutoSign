# BYD Auto Sign

纯 Android 原生自动签到应用（单 APK）。使用 LSPosed 从微信小程序**主动授权时**获取一次性 code；通过应用内 Kotlin/JCA/OkHttp 兑换 BYD session，并使用 AlarmManager + WorkManager 每日自动签到。**不需要 Root、BusyBox、crond、curl、OpenSSL 或常驻微信。**

> 状态：v0.5.0 原生重构候选版。协议的 AES/SHA-256 有原脚本对照单元测试，但实际 BYD 线上请求、不同 ROM 锁屏触发、会话跨天有效期仍须真机验收。不要把 CI 通过视为真实签到成功。

## 运行前提

- Android 8.0+；需要安装并运行微信，以及 LSPosed 提供微信小程序授权 Hook。**LSPosed 是唯一仍依赖的 Hook 环境，不再调用 su**。通常需要按 LSPosed 自身要求准备相应系统环境，但 BYD Auto Sign 本身没有 Root API。
- 启用 BYD Auto Sign 的 LSPosed 模块并勾选微信 `com.tencent.mm`；重启微信使 Hook 生效。
- 打开本应用，点击「初始化并授权」：经 Binder 从微信拿到短期 code，在 App 内兑换 session，并通过 Android Keystore AES-GCM 安全保存。需要网络连接。仅此操作可能主动拉起微信。
- 在「自动调度」填写本地时间，如 `07:01` 或 `00:01,07:01,13:01,22:01`；允许最多 8 个时间，保存后由 AlarmManager 调度。
- Android 12+ 可授予「精确闹钟」特殊权限以改善触发时效；没有授权时会降级为非精确唤醒。网络重试由 WorkManager 管理；系统的 Doze 和省电策略仍可能延迟签到。

## 迁移说明（从 v0.4.x Root 版本）

**新 APK 无法直接读取旧的 Root 私有会话。** 这是 Android 沙箱权限要求，不使用 su 就不能从 `/data/adb/byd_sign` 迁移。升级后须在 App 内通过微信重新授权一次。

**旧 Root 签到任务并不会被 APK 自动卸载。** 如果以前安装了 byd_sign Magisk/KernelSU/APatch 模块，或残留 `/data/adb/service.d/byd_auto_sign.sh`、`/data/adb/byd_sign/cron.d/root`，请通过原有 Root 管理工具手动停用或清理，避免和新调度重复执行。新 APK 不会请求 Root 清理它们。

当前仅支持主 Android User 的微信 Binder 授权。旧版分身槽位的 session 不会迁移；分身/多账号将另行实现跨 User 授权。

## 技术架构

- **WeChat Hook**：仅用于显式授权，DEX 动态探测 + Binder/AIDL；不暴露本地 HTTP 登录服务。
- **NativeSignEngine**：JCA AES-128-CBC/PKCS5Padding、SHA-256 校验、OkHttp TLS 请求；服务端 session 返回后缓存，不缓存微信 code。
- **SignStore**：AndroidKeyStore 生成 AES-GCM 本机密钥，SharedPreferences 仅保留加密密文；无 Root 目录，也不允许系统备份会话。
- **SignScheduler**：`RTC_WAKEUP`、`setExactAndAllowWhileIdle` 或 `setAndAllowWhileIdle`；开机、升级、时区/时间/授权变化自动重建。闹钟广播入队 WorkManager（网络就绪后执行，指数退避）。
- **SignCoordinator**：同一进程互斥，按手机本地日期幂等，今日成功后自动跳过；明确判定 session 过期时才清除。
- **界面**：Material 3，支持系统动态色和深浅主题。

默认执行时间继承原版 `00:01,07:01,13:01,22:01`。目前暂无通知配置 UI 和跨 User 多账号界面。

## 构建与交付

需要 Android SDK 35、JDK 17；直接运行：

```bash
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

GitHub Actions 会生成同一发布证书签名的 `BYDAutoSign-release.apk`，在 Actions → Artifacts 提供原始 APK，不会打 ZIP；参见 [签名配置](docs/RELEASE_SIGNING.md)。

原始授权 Hook 参考 [wxcode](https://github.com/xiaojinwen/wxcode)，协议参数来自 [byd_sign](https://github.com/xiaojinwen/byd_sign)（MIT，许可保留在 `third_party/`）。BYD Auto Sign 是非官方研究项目，不代表微信或比亚迪。切勿上传 code、session_id 或未经脱敏的服务端响应。

更多信息：[路线图](docs/ROADMAP.md) · [原生架构](docs/ARCHITECTURE.md) · [测试计划](docs/TEST_PLAN.md)。
