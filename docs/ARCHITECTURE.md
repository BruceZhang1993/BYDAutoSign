# BYD Auto Sign 架构（Binder-only 主用户授权）

## 部署与进程

```text
BYD Auto Sign APK
  ├── MainActivity / RootBridge.kt：状态、初始化、授权、立即签到
  ├── IpcBrokerService：供微信 Hook 主动绑定、注册 Binder Endpoint
  │    ├── ping() / getHookStatus() / triggerProbe()
  │    └── requestLoginCode(requestId, appId, callback)（仅显式授权）
  └── 打包的 root-install.sh、byd_sign.sh、lib.sh、curl/openssl

微信主进程（LSPosed，com.tencent.mm）
  └── WxLoginHook → HookIpcClient
      ├── Binder 注册、死亡清理与断线重新绑定
      ├── 动态 DEX 结构分析、指纹缓存及状态
      └── 用户触发 JsApiLogin 返回一次性 code

Root /data/adb/byd_sign
  ├── sessions/8088.session     # 仅 Root 可读、原子覆盖
  ├── sessions/8088.session.via # binder / http / unknown 非敏感历史来源
  ├── cron.d/root               # 默认 00:01,07:01,13:01,22:01
  ├── logs/
  └── /data/adb/service.d/byd_auto_sign.sh -> root-boot.sh
```

**当前仓库内不再包含 localhost HTTP 服务或 /login 接口。** Git/Root 的历史端口 `8088` 等仅代表已有 session 的存储槽位，不再是侦听端口。

## 认证流程

1. 打开 App，部署 Root 运行时（不会删除已有的用户设置和 session）。
2. 当用户明确点击初始化/重新授权时，`RootBridge` 查询 Binder。若微信未连接，可在此授权阶段通过 Root 唤醒微信后再等待 Binder 注册；签到阶段不做此操作。
3. Broker 通过 AIDL 回调微信 Hook，在专用工作线程执行动态映射的 `JsApiLogin`，异步返回一次性 code。调用者在每个 Binder 事务中通过 `AppOps.checkPackage(uid, package)` 校验真实调用 UID 对应的包，主用户跨 User 会被拒绝。
4. App 通过 Root 进程标准输入将 code 交给 `byd_sign.sh authorize-stdin 8088`；不通过 argv/URL/文件传递 code。
5. Shell 沿用现有 BYD AES/签名/网络协议将 code 兑换成 `session_id`，并使用私有文件（0600）持久化。旧的 session 在授权失败时不删除。
6. 定时/手动签到只读取已有 session，执行 `activity/sign/signIn`；不获取新 code，不要求微信运行。服务器明确判定 session 失效才按既有逻辑清除。

一次性 code 不应写入日志或诊断信息；存储的 `.session.via` 仅为来源标识，兼容历史无标识文件。

## DEX 探测

微信进程在 `Application.attach` 后异步验证已知映射及 DEX 指纹；如不匹配，通过 `DexProbe` 和 `HookMap` 自动发现、验证并缓存。管理端从 `IHookEndpoint.getHookStatus` 获取版本、映射来源、`probeRunning`、`codeVerified` 等状态；使用 `triggerProbe` 手动触发。无 HTTP 状态接口。

## Root 调度

`root-install.sh` 部署后由 `root-boot.sh` 启动 BusyBox `crond`。启动脚本检查 crond applet/真实进程存活并将错误写入 `logs/scheduler.log`；任务日志为 `logs/cron.log`。设备重启时依赖 Root 管理器支持 `/data/adb/service.d`。普通 cron **不保证 Android Doze 或关机后补执行**；具体诊断见 [CRON_TROUBLESHOOTING.md](CRON_TROUBLESHOOTING.md)。

## 兼容与安全边界

- **单 APK + Root + LSPosed**，没有额外的 HTTP server、端口监听或 NanoHTTPD 依赖。
- 保留已有 `/data/adb/byd_sign` 目录、会话、签到日期、通知配置和旧的 `code_api.conf` 槽位。升级后需在管理页重新部署 Root 环境，重启微信重新注入 Hook。
- 主微信 (Android User 0) 已有用户截图确认 Binder Ping/Pong 成功；**实际取码、Session 兑换、跨夜自动执行尚待设备验证**。自动化静态/模拟测试不等价于真机测试。
- 当前 Binder 不会跨 Android User 注册。系统微信分身的全新授权需要另设计跨用户安全 Broker；已有分身 session 在过期前仍可由签到脚本读取。
- AppOps 的包 UID 身份校验不是微信官方签名证书 Pinning。对微信进程和 Root 拥有控制权限者不在本方案安全边界之外；进一步签名策略、SELinux 和分身支持留待后续验收。
- 所有 BYD HTTPS/通知网络请求仍由 Shell curl 发起，不涉及 localhost IPC。还需单独消除旧 Shell 的 `--insecure` 等问题。

## 验收顺序

1. 编译/静态检测：AIDL 接口、Hook mapping、Root stdin 模拟授权、无本地 HTTP 依赖、签到不唤醒微信。
2. 真机：安装新 APK，重启微信并重新部署 Root runtime，检查 Binder 已注册；主动重新授权，核实 `session.via=binder`，随后立即签到成功。
3. 可靠性：重启微信/App、结束进程、锁屏 cron、会话失效、分身用户。全部单列测试，不用 Stage 1 的历史 HTTP 记录冒充 Binder 端到端成功。

