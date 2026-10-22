# BYD Auto Sign — 安全 IPC 技术研究（2026-10-22）

> 状态：**设计研究 / 待 POC 验证**。本文件不是已完成实现，也不代表通过真机验证。本次调研明确跳过 Stage 1 回归测试，不修改当前可用的 HTTP/Shell 签到链路。

## 1. 目标与现状

目标：将微信进程中的 LSPosed 登录 Hook 与 BYDAutoSign 管理 APK 的通信，从无鉴权的 localhost HTTP 逐步迁移为可校验对端 UID 的 Binder IPC。**初始化/用户主动重新授权才允许获取一次性微信 code；普通手动与定时签到只使用已缓存的 BYD session，绝不隐式唤醒微信**。

现有代码路径：

- `WxLoginHook.java`：只注入微信主进程，在 `Application.attach()` 中探测 DEX、初始化 `LoginHttpServer`，按 Android User ID 派生 8088 等端口；`doLogin()` 使用反射调用 `JsApiLogin`，最长约 25 秒等待 DEX 映射、30 秒等待登录结果。
- `LoginHttpServer.java`：对 `127.0.0.1` 暴露 `/login`、`/whoami`、`/probe`、`/config`、`/save_config`、`/register`、`/instances` 等 GET 入口。目前仅限制监听网卡，**同机其他应用仍可连接，无身份校验**。
- `RootBridge.kt`：从端口 8088 请求 `/whoami` 和 `/probe`；初始化、授权、签到经 `su` 启动 Root Shell。
- `byd_sign.sh` / `lib.sh`：`authorize` 通过本地 HTTP 取 code，向 BYD 换取 session 并保存到 `/data/adb/byd_sign/sessions/<port>.session`；`nonauto` 和 cron 仅用 session 签到。
- `AndroidManifest.xml`：只有管理 `MainActivity` 与既存 `KeepAliveService` 声明。Hook 注入的类不会自动成为微信 Manifest 中可绑定的组件。

本阶段**不重写微信登录协议或 BYD 签到协议**，优先改变通信边界。

## 2. 技术选型

| 方案 | 评价 | 结论 |
| --- | --- | --- |
| App 主动绑定「微信自定义 Service」 | 微信 Manifest 没有该组件；LSPosed 注入 Java 类并不使其成为已注册 Android Service | 不采用 |
| BYDAutoSign 导出 Service + 微信 Hook 主动注册 AIDL 回调 | Android 官方跨进程 Binder；允许在方法级验证 UID；无需改宿主 Manifest；能处理 Binder death | **主用户优先** |
| Messenger + `replyTo` | 可作为最小 IPC 验证；后续多个异步接口、账号管理、请求关联较繁琐 | POC 备选 |
| ContentProvider `call()` | 可跨进程，但异步回调/生命周期要另做；跨用户问题未解决 | 不选作主通道 |
| localhost HTTP + token | 可以止血，但 token 分发与同机攻击面仍需管理；不是目标中的彻底移除 HTTP | 仅临时兼容 |
| Root daemon + Unix domain socket（SO_PEERCRED） | 有望跨用户，但涉及 SELinux、Root 守护进程、socket 文件权限/可达性；必须真机证明 | **分身 fallback 研究项** |

官方参考：
- [Android Bound services](https://developer.android.com/develop/background-work/services/bound-services)
- [Android AIDL](https://developer.android.com/develop/background-work/services/aidl)
- [Android IPC security checklist](https://developer.android.com/privacy-and-security/security-tips)
- [Binder.getCallingUid](https://developer.android.com/reference/android/os/Binder)
- [Cross-user bindServiceAsUser](https://developer.android.com/reference/android/content/Context#bindServiceAsUser(android.content.Intent,android.content.ServiceConnection,int,android.os.UserHandle))
- [Android Multi-user](https://source.android.com/docs/devices/admin/multiuser-apps)

## 3. 推荐拓扑：反向注册 + 双向 Binder 回调

```text
微信主进程 (com.tencent.mm, LSPosed Hook, UID: WeChat UID)
  └─ Application.attach()
      ├─ 异步探测 DEX（沿用已有 HookMap）
      └─ 显式 bindService(模块 APK 中导出的 IpcBrokerService)
           └─ IHookBroker.registerHook(protocolVersion, IHookEndpoint)

BYDAutoSign APK 进程 (模块 App UID)
  ├─ IpcBrokerService
  │   ├─ 在每次 AIDL transaction 内校验 Binder.getCallingUid()
  │   ├─ 注册表 key = <经过核验的 Android userId, callingUid>
  │   ├─ binder death 自动清理已离线 Hook
  │   └─ requestId / 超时 / 请求方会话关联
  └─ 用户点击「初始化并授权」或「重新授权」
      ├─ 必要时唤醒微信（仅授权阶段）
      ├─ 选择已注册的 Hook（而不是猜测 TCP 端口）
      └─ IHookEndpoint.requestLoginCode(requestId, appId, ICodeResult)
                      |
                      v
       微信 Hook 异步执行现有 doLogin → 仅返回 code 或脱敏错误
                      |
                      v
       App 兑换并保存 BYD session（迁移过渡见下）
                      |
                      v
       之后 cron / 手动签到：只用已缓存 session，不需要 Binder/微信
```

注意：BYDAutoSign 的 Service **运行在本模块 APK 进程，不是微信进程**。Hook 是该服务的客户端，并向管理端提供远程回调 Binder。Binder 可以异步回调已建立连接的另一端；无需在微信包名下声明服务。

### 建议的最小 AIDL 契约（伪接口，不是现有实现）

```aidl
interface IHookBroker {
    void registerHook(int protocolVersion, IHookEndpoint endpoint);
    void unregisterHook(IHookEndpoint endpoint);
}

// 持有者是微信 Hook；调用者是经过授权的 BYDAutoSign
interface IHookEndpoint {
    void requestLoginCode(String requestId, String appId, ICodeResult callback);
    void requestProbe(String requestId, IStatusCallback callback);
}

interface ICodeResult {
    void onResult(String requestId, int status, String codeOrEmpty);
}
```

实际开发时应拆成各自对应的 `.aidl` 文件；必要时用 `oneway`、版本号和受限大小的 Parcelable 结果结构，避免 Binder 主线程阻塞。已有 `doLogin()` 是同步轮询，不可直接在 Binder 线程内长时间执行；应提交到专用 worker 并异步回调。

注册表可先只在内存中保留；结合 `IBinder.DeathRecipient` / `RemoteCallbackList` 自动清理，允许微信重启重注册、Broker 重启后客户端重连。**不要把 Binder 句柄、code、原始响应持久化**。

### 明确权限边界

- `IpcBrokerService` 必须 `android:exported="true"`，因微信 UID 与本模块 UID 不同；导出仅意味着能发起绑定，**不能意味着通过鉴权**。
- **不可简单加 `signature` permission**：微信不是由 BYDAutoSign 证书签名，签名权限会挡住真实客户端。
- 仅允许 `registerHook/unregisterHook` 来自**经过真实 Binder UID 校验的微信包**。在每个 AIDL 方法执行期间取得 `Binder.getCallingUid()`，按 UID 解析所属包名，确认 `com.tencent.mm` 并验证经人工确认的官方微信签名证书摘要；禁止只相信调用者传入的 packageName/userId。
- 函数权限采用最小能力原则：微信侧**不能主动请求 code 交付或替管理端修改配置**；应用进程的管理操作只允许自身 UID，或只通过应用内部非导出 API 调用。
- 回调结果再校验来源 UID 与此前注册的 Endpoint UID 相同，并匹配一次性 requestId、时限与授权意图。不要把可信身份判断放在 `onBind()` 或异步线程上（可能不是初始客户端 Binder 身份）。
- 注册请求限制频率；限制 `appId`、入参长度、并发数，错误结果不包含混淆类细节或敏感数据。仅在 App 用户明确初始化/重新授权时产生登录请求。
- 不要把 `code`、`session_id` 放进日志、URL、Toast、Intent extras、IPC 诊断内容；`code` 只允许单次请求的短期内存传递。Binder 事务不等于端到端加密，信任边界包括宿主微信进程、系统 Binder 与 Root。
- 模块 UI 可以展示 `UNBOUND / CONNECTING / REGISTERED / MAPPING_PENDING / READY / ERROR` 诊断状态，但不能把「注册成功」等同于「已真实取码验证」。

## 4. 过渡方案：不立即重写 Shell 签到

现有 `byd_sign.sh authorize` 同时执行取 code 与 session 兑换，而 Binder 改造后需要拆开：

1. 管理 App 在**显式授权**操作中通过 Binder 获取一次性 code。
2. 短期可增加 `authorize-stdin <validatedAccount>` Root Shell 内部入口，以 `ProcessBuilder("su", "-c", ...)` 的 stdin 将一次性 code 送到 Shell，复用已有 `get_session_id`、`save_session`，且只传无秘密的账号映射作为参数。
3. **不得将 code 放在 CLI 参数、临时 world-readable 文件或日志中**；Shell 仅在内存中使用，出错后不重放一次性 code。确保 stdin 消费/关闭和异常清理，实机验证 su stdin 的行为。
4. 原有 `nonauto` 和 cron 不变；最终在 Kotlin/JCA/OkHttp 协议迁移后直接于 App 进程完成 session 兑换，并废弃 `authorize-stdin`。
5. 直到明确切换成功之前，保留现有实现，但**不能将开放式 HTTP 登录与 Binder 方案长期并行作为正式发布模式**。切换后移除 localhost 服务与对应明文网络策略。

要注意：当前 `WxLoginHook.handleLoadPackage` 会先判断端口占用并在端口已占用时返回；Binder 方案不能保留这一提前 return，否则可能完全跳过新的 Hook 初始化。

## 5. Android 多用户 / 微信分身：未解决风险

Android 每个 user/profile 有独立的 app UID 和组件可见性。模块只在 user 0 安装，不代表 user 999 等分身用户内存在同一个可绑定 Service；普通 App 不能任意 `bindServiceAsUser()` 到其它用户（需要特定系统或跨 profile 权限）。

- **第一层：同一 Android User**。微信 Hook 在当前 User 内绑定本模块 Service；这条路径最少特权，作为 POC 首要目标。
- **第二层：系统分身 User**。先实测 `pm list users`、`cmd package list packages --user <id>`，确认微信与本模块安装关系。若可通过 Root 将**同一个 APK 安装到目标 user**，则可在对应 user 建立同用户 Binder 通路，但主用户的管理 UI 如何汇聚各 profile 的状态/授权结果仍须设计。
- **第三层：无法在 clone User 安装或跨 profile 交互被 ROM 限制**。评估 Root Broker + Unix 域套接字作为按需授权桥。连接双方的 Linux UID 可通过 `SO_PEERCRED` 获取，但还需解决权限、SELinux、socket 可达性、认证、生命周期和防重放；不能未经实测就宣称方案可用。
- 不再把端口 8088/8098 当成身份。账号/实例 key 必须依靠**已验证的 User ID + UID + 已注册 Endpoint**，品牌账号映射则另外保存。

暂不建议给普通 App 申请或依赖 `INTERACT_ACROSS_USERS_FULL`；它是受保护的系统权限，Root 并不意味着普通 APK 获得该权限。

## 6. POC 实施顺序（无业务侵入）

### POC A — 主微信 Ping 通道

- 新建 `IpcBrokerService` 与最小 `IHookBroker` / `IHookEndpoint`，只实现 register + ping/pong，**不传任何真实 code**。
- 微信主进程 `Application.attach` 之后在独立线程显式绑定；App 显示真实的注册、来源 UID、Android User、Binder 连通状态。
- Service 针对非微信 UID 立刻拒绝；反向调用 ping，微信端返回固定健康状态；微信/模块进程退出后清理注册。
- 记录在当前测试 ROM 下 `bindService` 是否受后台启动、包可见性与进程状态限制。

### POC B — 真正取码（仍不切换 HTTP）

- Broker 只允许由 App 发起的主动授权请求，异步调用已有 `doLogin`。
- 以有超时、有 requestId、有 UID 校验的结果回调返回 code，结果不落盘、不进日志。
- 在单独的实验操作入口展示脱敏成功/失败状态。HTTP 旧路径维持可用，避免影响当前签到，但不得让无鉴权接口进入正式长期共存状态。

### POC C — 授权链切换

- 新增只经 stdin 接收 code 的临时 Root 兑换接口，管理 App 用 IPC 取 code，再写入 stdin，Root 缓存原有 session。
- 保持 `authorize` 和签到链路可回滚；测试完成后移除旧 HTTP 登录、探测及配置接口，统一替换为 Binder。
- 管理 App 的 `checkHook()` / `probeNow()` 从固定 8088 HTTP 改为 IPC 查询；移除多实例 HTTP 注册表和端口配置。
- 如分身跨用户还未解决，不提前删除对应兼容逻辑；明确将其标为独立风险。

## 7. 设计验收（不是本次执行情况）

- [ ] 主微信 Hook → 本模块 Broker 的 AIDL 注册、双向 ping/pong 成功
- [ ] 不依赖微信 Manifest 自定义组件、无需常驻前台服务
- [ ] 非微信进程的 UID / 不可信签名连接被拒绝
- [ ] 微信退出或死亡后，Endpoint 从 Broker 自动失效；重新启动后自动注册
- [ ] App 在明确授权时请求 code，超时/并发/失效有稳定错误码
- [ ] Code 从不经过 localhost HTTP、命令行参数或永久文件
- [ ] 定时及手动签到仍不请求微信 IPC
- [ ] 分身跨 User 的「同 User 安装」或 Root Broker 可行性有真机结论
- [ ] 切换后的正式模式不保留无鉴权的 `/login`、`/save_config` 等 HTTP 入口

## 8. 当前结论

推荐 **AIDL 双向回调**作为主用户 IPC 主路线；分身跨 Android User **不能仅凭主用户 IPC 方案宣称已支持**。先完成完全不涉及真实 code 的 POC A，再进行 B/C；最终移除 HTTP 时保留“授权与签到解耦”的既有语义。

**本文件仅包含代码审阅、官方平台文档调研和拟议设计；没有执行设备级 POC、CI 或回归测试。**

## 9. POC 代码实施记录（2026-10-22）

- **POC A 已编码并通过 GitHub Actions 第 17 次 Android CI 构建**。包含 AIDL、模块 APK 内导出的 Broker Service、微信 Hook 主动绑定/注册、双向 Ping/Pong、Binder death/重连、管理界面状态。
- **POC B 已编码，仍需 CI 编译及真机验证**：新增 `ICodeResult` 与 `IHookEndpoint.requestLoginCode()`，仅由管理 App 的实验性按钮发起，在微信 Hook 工作线程调用原有 `doLogin()`；Binder callback 通过一次性 requestId、调用 UID 与超时接收 code，不保存或打印。
- **POC C 实验性接线已编码，仍需 CI 与真机验证**：App 将获得的 code 通过 `su` 进程的 **stdin** 传入 `byd_sign.sh authorize-stdin 8088`，只执行已有 Session 兑换、保存逻辑。普通 `authorize` / `nonauto` / cron 维持旧流程。
- 实验按钮当前只支持**主微信（本机端口 8088）**，分身跨 User 尚未完成。安装实验 APK 后还需通过管理页重新部署 Root 运行时脚本。
- 本阶段未验证被 Hook 注入的微信进程在所有 Android 版本/ROM 下能否绑定模块的导出 Service，特别是宿主微信的包可见性限制；**CI 成功不等于真机 IPC 成功，更不等于协议切换完毕**。
- 目前 Broker 在每个注册 transaction 中验证主用户 `com.tencent.mm` 的真实包 UID，Hook 回调校验模块包 UID；尚未建立官方微信签名证书固定指纹，因此该实现仅为测试功能，不是最终安全加固完成版。正式迁移需要证书信任策略、跨用户方案、严格审查与移除旧 HTTP 暴露面。
- 现有 `LoginHttpServer` 的无鉴权敏感端点仍在运行，因此**不能把本阶段标记为完成安全 IPC 迁移**。

本阶段按要求跳过真机业务回归验收；仍保留 CI 自带的静态检查与 Android 构建。

## 10. 主用户 POC A 真机验证进展（2026-10-22）

用户提供的 Android 真机界面截图显示：

- 微信 8.0.79，Hook 内置映射结构校验通过。
- Binder IPC 显示 `已注册 · UserHandle{0} · Ping/Pong 正常`。
- Root 环境部署完成，端口 8088 的 BYD session 已在 2026-10-22 23:31:29 初始化并保存。

因此 **POC A 主用户进程间注册与 Ping/Pong 已完成设备侧验证**。但这两张截图**不能证明**用于生成 session 的 code 经由 Binder：此前“重新部署并授权”仍可能使用兼容 HTTP。

为明确区分实验验证，签入原有 Root 会话时现在额外在仅 Root 可访问的 `sessions/<port>.session.via` 中保存非敏感来源 `binder` 或 `http`。状态页面通过 `byd_sign.sh status` 将其显示成 `Binder IPC` 或 `旧 HTTP`。升级前已保存的会话无来源字段，显示“来源未知”。源文件只放通道类别，不读取或记录 code/session。

**下一步设备验收：** 安装包含该变更的 APK、更新 Root 运行环境，然后明确点击“通过 Binder 重新授权（实验）”；看到成功提示及会话状态里标为“Binder IPC”，才可把 POC B/C 的全链路标记为真机通过。当前不能据此移除 HTTP 桥或宣称跨 User 分身支持。
