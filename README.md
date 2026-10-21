# BYD Auto Sign

一个 APK 集成 **微信小程序登录 Hook（LSPosed）** 与 **比亚迪每日自动签到（Root）**。

当前为 **第一阶段 / Stage 1**：优先复用已验证的 [wxcode](https://github.com/xiaojinwen/wxcode) 登录逻辑和 [byd_sign](https://github.com/xiaojinwen/byd_sign) 签到 Shell 实现，避免立即重写私有接口引发回归。

## 能力

- 单 APK：同时提供签到管理界面和 LSPosed 模块，无需再安装两套分离的项目。
- 兼容已有的签到协议：微信小程序 `code` → 比亚迪 `session_id` → 签到请求。
- 支持 Root 定时任务（每天 00:01、07:01、13:01、22:01），自动跳过今日已签到的实例。
- 支持手动签到、微信主用户 / 分身多端口配置、失败重试、签到日志。
- 复用原有邮件、Webhook 设置，以及 `/data/adb/byd_sign` 配置和历史签到状态。
- 兼容现有 wxcode 的微信版本适配表；LSPosed 作用域为 **com.tencent.mm**。
- Material 3 / 跟随系统深浅色的轻量管理页面。

## 使用说明

1. 确认设备已有 **Root（Magisk / KernelSU / APatch）**、**LSPosed**，以及已登录的微信。
2. 在 [Actions](https://github.com/BruceZhang1993/BYDAutoSign/actions) 下载最新成功构建的 `BYDAutoSign-debug` APK 并安装。
3. 在 LSPosed 启用 BYD Auto Sign，作用域勾选微信 `com.tencent.mm`，**强制停止后重新启动微信**。
4. 打开 BYD Auto Sign，授予 Root，点击 **一键初始化**。安装器会从 APK 内部释放 Shell 运行环境，并注册 service.d 开机任务（无需单独安装 Root 模块 ZIP）。
5. 检查微信 Hook 状态后，点击 **立即签到** 验证。分身用户可在端口框填写 `8088,8098` 等端口并保存。

> 系统分身的端口由 wxcode 按 User ID 计算，不应任意猜测；可查阅原 wxcode 文档。管理页目前探测默认主微信 `8088`。

**已有 byd_sign 用户**：运行时继续使用 `/data/adb/byd_sign`，不会覆盖现有 `code_api.conf`、`email.conf`、签到状态和日志。若旧版 byd_sign Root 模块仍启用，建议在验证新应用工作后将旧模块停用，避免双重开机调度。

**目前的限制**：本阶段仍使用原有 Shell、curl、OpenSSL 和本地 HTTP 登录桥。微信完全停止、Android 深度休眠或厂商后台限制时，仍可能需要首次手动打开微信；无法保证每一种 ROM 下无界面自动唤醒。Root 定时任务在 Root 管理器支持 `/data/adb/service.d` 且 BusyBox crond 可用时独立于 APK 进程运行。前台保活、受保护 IPC 等改进详见路线图。

## 构建

需要 Android SDK 35、JDK 17、Gradle 8.10.2、网络连接。

```bash
bash scripts/prepare-runtime.sh  # 从固定上游版本下载并校验 curl / openssl
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

生成的 APK：`app/build/outputs/apk/debug/app-debug.apk`。**安装 APK 时无需另行下载工具**；下载只发生在构建期。构建脚本使用固定的上游 commit，并校验每个二进制的 Git blob SHA-1。

CI：`.github/workflows/android.yml` 提供可下载的调试 APK。当前为调试签名，不保证跨设备适用，也不建议将调试包作为正式发行版。

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
