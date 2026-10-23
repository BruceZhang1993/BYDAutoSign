# BYD Auto Sign

一个 APK 集成 **微信小程序登录 Hook（LSPosed）** 与 **比亚迪每日自动签到（Root）**。

当前为 **第一阶段 / Stage 1**：优先复用已验证的 [wxcode](https://github.com/xiaojinwen/wxcode) 登录逻辑和 [byd_sign](https://github.com/xiaojinwen/byd_sign) 签到 Shell 实现，避免立即重写私有接口引发回归。

## 能力

- 单 APK：同时提供签到管理界面和 LSPosed 模块，无需再安装两套分离的项目。
- 兼容已有的签到协议：微信小程序 `code` → 比亚迪 `session_id` → 签到请求。
- 支持 Root 定时任务（每天 00:01、07:01、13:01、22:01），自动跳过今日已签到的实例。
- 支持手动签到、主微信 Binder 授权、历史多槽位会话兼容、失败重试、签到日志。跨 Android User 的分身 Binder 授权仍待适配。
- 复用原有邮件、Webhook 设置，以及 `/data/adb/byd_sign` 配置和历史签到状态。
- 兼容现有 wxcode 的微信版本适配表；LSPosed 作用域为 **com.tencent.mm**。
- Material 3 / 跟随系统深浅色的轻量管理页面。

## 使用说明

1. 确认设备已有 **Root（Magisk / KernelSU / APatch）**、**LSPosed**，以及已登录的微信。
2. 在 [Actions](https://github.com/BruceZhang1993/BYDAutoSign/actions) 下载已签名的原始 `BYDAutoSign-release.apk`（无需解压）并安装。仓库维护者需先配置 [Release 签名 Secrets](docs/RELEASE_SIGNING.md)。
3. 在 LSPosed 启用 BYD Auto Sign，作用域勾选微信 `com.tencent.mm`，**强制停止后重新启动微信**，让新 Binder Hook 生效。
4. 打开 BYD Auto Sign，授予 Root，点击 **初始化并通过 Binder 授权**。安装器从 APK 释放 Root 签到环境并注册 service.d 定时任务，然后仅在授权阶段使用微信 Binder 获取一次性 code。
5. 确认「会话授权」区域显示已初始化会话，之后点击 **立即签到** 验证。原有 `8088,8098` 等会话槽位继续用于读取已保存的 session；当前 IPC 授权仅覆盖主微信 8088。

> 跨 Android User 的微信分身 Binder 通信尚未支持。界面的端口字段仅为旧会话文件兼容标识，不表示开放本地 HTTP 端口。

**已有 byd_sign 用户**：运行时继续使用 `/data/adb/byd_sign`，不会覆盖现有 `code_api.conf`、`email.conf`、签到状态和日志。若旧版 byd_sign Root 模块仍启用，建议在验证新应用工作后将旧模块停用，避免双重开机调度。

**目前的限制**：本阶段仍使用 Shell、curl、OpenSSL 兑换和签到；**已删除本机 HTTP 授权桥**，由 UID 校验的 Binder IPC 提供微信状态、DEX 探测和登录 code。**微信只在手动初始化/重新授权时需要**，日常签到使用已保存的 BYD `session_id`，不再唤醒微信；会话有效期尚未经验证，过期后必须手动重新授权。Root 定时任务在 Root 管理器支持 `/data/adb/service.d` 且 BusyBox crond 可用时独立于 APK 进程运行。前台保活、受保护 IPC 等改进详见路线图。

## 构建

需要 Android SDK 35、JDK 17、Gradle 8.10.2、网络连接。

```bash
bash scripts/prepare-runtime.sh  # 从固定上游版本下载并校验 curl / openssl
bash ./gradlew :app:testDebugUnitTest :app:assembleRelease  # 需要配置 Release 签名环境变量
```

生成的已签名 APK：`app/build/outputs/apk/release/app-release.apk`（配置签名环境变量后）。**安装 APK 时无需另行下载工具**；下载只发生在构建期。构建脚本使用固定的上游 commit，并校验每个二进制的 Git blob SHA-1。

CI：`.github/workflows/android.yml` 在 GitHub Actions → Artifacts 上传经 `apksigner` 验证的原始 Release APK，使用 `actions/upload-artifact@v7` 和 `archive: false`，不再套 ZIP。发布包使用固定的私有签名证书；仓库管理员必须先配置 [签名 Secrets](docs/RELEASE_SIGNING.md)，否则发布步骤明确失败而不会上传未签名 APK。

## 项目结构

```text
app/src/main/java/
  com/brucezhang/bydautosign/   # Kotlin 管理 UI 与 Root 生命周期
  xiaojw/hook/                  # vendored wxcode LSPosed Hook
app/src/main/assets/
  xposed_init                   # Xposed 入口
  runtime/                      # 原始 Shell 签到引擎及 Root 启动脚本
scripts/prepare-runtime.sh      # 固定 revision 二进制获取 / 校验
docs/ROADMAP.md                 # 阶段 2 / 3 及验收标准
docs/ARCHITECTURE.md            # 完整运行链路、迁移与安全边界
docs/TEST_PLAN.md               # 回归清单
```

## 来源、许可和风险

- 微信 Hook：[xiaojinwen/wxcode](https://github.com/xiaojinwen/wxcode)，MIT，固定源码基线 `052fd8808ec76c3b5571143b45b80ec20eed21df`。
- 签到引擎和 curl/OpenSSL 工具：[xiaojinwen/byd_sign](https://github.com/xiaojinwen/byd_sign)，MIT，固定源码基线 `b9001bb2f3793322e0fdf7923f5969ace33e79ca`。
- 保留上游许可于 `third_party/`；本项目自有代码采用 MIT。上游打包的第三方可执行文件可能适用各自单独的许可证，请在发布正式版本前检查随包第三方许可证。
- 本项目为非官方学习研究工具；可能受到微信 / 比亚迪接口变更、账号使用协议及设备权限策略影响。切勿在公开 issue、日志中上传登录 code、session_id 或个人信息。

## DEX 动态适配（v0.2.0）

从 v0.2.0 开始支持在**微信进程内后台解析已加载 DEX**，避免每次微信更新后必须重新填写混淆类名。支持：

- 优先使用当前已知、结构验证通过的内置映射（零 DEX 全盘扫描）。
- 已知映射不匹配或未知版本：后台扫描 `JsApiLogin` 的实际调用点，并在必要时通过构造方法结构搜索回调/提交类。
- 按微信 APK、分包及运行时 DEX 指纹缓存；只在相同指纹下复用扫描结果。
- 探测结果必须通过反射结构与 LoginTask 字段检查；真实取码成功后才标记为**已取码验证**。
- 应用内可以点击 **重新探测微信 DEX**，查看后台进度和可读的失败原因；HTTP 兼容桥的 `/whoami` 同步显示来源和验证状态。
- 保留精确匹配内置配置作为低风险快速路径。

目前四组混淆类、构造参数及任务提交方法可以动态发现；LoginTask 的字段映射仍要满足已知结构（代码会检查字段是否存在，不会盲目继续），微信若改变字段语义或登录协议，需要进一步更新解析器。也不保证对 Google Play 特殊变体、不可读运行时 DEX 或大规模逻辑重构自动兼容。

运行阶段只分析本机微信文件，不会上传 APK 或个人数据。手动探测与版本状态查询已迁移为受 Binder 身份校验的 IPC。

## 授权和签到分离（v0.3.0）

- **初始化并授权**：部署 Root 任务，按需唤醒微信，通过 Binder 获取一次性 `code`，经 Root stdin 调用原有 `mina.decryptCode` 换取 BYD `session_id`。仅将 session 保存在 Root 私有目录 `/data/adb/byd_sign/sessions/<port>.session`，不保存 code。
- **重新授权**：当前为主微信 8088 的 Binder 授权；成功后保存 session，失败不删除旧会话。跨 User 分身的重新授权需另行适配。
- **立即签到/cron**：只读取缓存 session，然后调用 `activity/sign/signIn`。即使微信已被系统结束，签到流程也不会调用微信、获取 code 或前台唤醒。
- 服务端明确响应 session 失效时，清除此端口 session 并提示重新授权；普通网络或业务失败时不会随意删除会话。
- 新增端口后需手动执行重新授权；应用只展示各端口是否有凭证及授权时间，不读取/打印真实 session。

**注意**：微信小程序的 code 不能长期复用。比亚迪服务端 session 的真实有效期未知，暂不支持保证授权一次永久签到，也不在自动签到任务中隐式刷新。请用真机确认该 session 能否跨天复用。如果服务端每日强制重新授权，需要单独设计受控的授权刷新机制，不能假装现有 session 永久有效。


## Binder IPC 迁移状态（2026-10）

- 微信主进程通过 AIDL 注册到模块 Broker；管理页的版本状态、DEX 探测及主动授权都经 Binder；旧 NanoHTTPD 服务器和 localhost 登录脚本已从源码移除。
- 用户真机截图确认**主用户 Binder 注册及 Ping/Pong 可用**。目前尚没有提供“Binder 取码 → BYD session 兑换 → 签到”完整的设备级验收证据；CI 和模拟会话测试不代表真实微信登录成功。
- 定时与立即签到仍只使用 Root 私有的 `session_id`，不连接 Binder、不唤醒微信。
- 升级至此版本后需重启微信使 LSPosed 注入生效，并在应用内**重新部署 Root 环境**后重新授权。
- 跨 Android User 的微信分身 IPC 尚未完成，使用旧会话只能维持到 session 过期。详见 [IPC 技术研究](docs/IPC_RESEARCH.md)。
