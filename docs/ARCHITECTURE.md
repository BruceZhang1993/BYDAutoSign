# Stage 1 技术架构

## 流程

```text
BYD Auto Sign APK
 ├─ MainActivity（Kotlin/Compose：管理、Root 授权、诊断）
 ├─ RootBridge（su + 本地脚本提取 + 配置）
 ├─ Xposed init → xiaojw.hook.WxLoginHook（微信主进程）
 │    └─ LoginHttpServer（仅 127.0.0.1，按版本反射触发小程序登录）
 └─ runtime/（单 APK 内随包资源）
      ├─ byd_sign.sh + lib.sh + email.conf
      ├─ curl + openssl（两种架构）
      ├─ root-install.sh
      └─ root-boot.sh
             ↓ Root 初始化
/data/adb/byd_sign（旧版数据路径、状态、日志）
/data/adb/service.d/byd_auto_sign.sh（开机启动 BusyBox crond）
             ↓ 00:01 / 07:01 / 13:01 / 22:01
byd_sign.sh → localhost:8088/login?appId=... → 微信生成 code
            → /?service=mina.decryptCode → session_id
            → activity/sign/signIn → 签到结果和通知
```

### 数据兼容

安装器只覆盖引擎脚本、内置工具及启动脚本，不覆盖 `code_api.conf`、`email.conf`、`cron.d/root`、`sign_status/` 和 `logs/`。因此能原位兼容原 byd_sign 的签到数据。旧 Root 模块 service.sh 仍可能自行启动 crond；迁移后应停用旧模块。

用户配置格式：

```ini
ports=8088,8098
force_foreground_wake=false
```

默认调度使用上游既有计划：`1 0,7,13,22 * * * /data/adb/byd_sign/byd_sign.sh`。自动执行会跳过今日已成功账号；手动执行仍调用原脚本的 `nonauto` 模式。

### Root 生命周期

管理页面使用 `su -c` 执行 APK 内的 `root-install.sh`，脚本部署到持久 Root 目录；`service.d` 在支持的 Root 管理器上开机运行 `root-boot.sh`，独立 crond 定时执行。APK UI/Hook 进程不需要全天驻留。

### 微信 Hook

`xposed_init` 保持 `xiaojw.hook.WxLoginHook`；完整保留 wxcode 上游的版本适配表和 HTTP 返回契约。登录服务由原 NanoHTTPD 实现，但监听地址改成 `127.0.0.1`，不再通过 0.0.0.0 暴露给局域网。**这是过渡性缓解而非完整鉴权**：同机其他应用仍可访问该端口，下一阶段必须迁移安全 IPC。

上游 `KeepAliveService` 源码暂留，以控制初期移植风险。其在微信被注入的 Context 中启动非微信清单声明组件，可能受 Android 组件边界限制；本阶段不声称前台保活已被修复。

### 安全注意

- 保留原 Shell 协议实现意味着部分 HTTPS 请求仍使用 `--insecure`；必须在 Stage 2 移除。
- 日志对已知高风险的 session/code 输出进行初步脱敏；不应将 Root 日志公开上传。
- Root 写入固定路径，文件权限分别为 0700（程序）和 0600（配置）。
- 本地接口 GET 暴露授权功能是临时技术债，不应接入外网或公开反向代理。
- curl/OpenSSL 由既有上游源码提供：构建脚本固定 revision、验 Git blob SHA-1；安装不访问网络下载二进制资源。

## DEX 自适配机制

新 `DexProbe` + `JsApiLoginAnalyzer` 在微信的后台工作线程扫描宿主安装包和可枚举的运行时 DEX，优先提取 `JsApiLogin` 的真实构造、调用关系；必要时按结构搜索四类目标。该方案移植并改编自 `linbailo/byd-lsp`（MIT）。

`HookMap` 负责反射结构验证以及 APK、split APK、运行时 DEX 元数据及 ZIP DEX CRC 的 SHA-256 指纹。指纹不变时使用微信私有 SharedPreferences 缓存，取码成功后额外标记 `codeVerified`。缓存与微信进程绑定，不从公网获取。
`WxLoginHook` 保留内置表快速路径；当映射失效时以单任务形式异步运行扫描。签到取码会限时等待探测，避免返回过时的类名错误。管理应用通过本机 `/whoami` 和 `/probe` 查看进度与触发手动重新探测。

**限制**：结构校验只保证类、构造、提交方法及已知 LoginTask 字段可用；混淆字段语义变化和无法读取的内存 DEX 仍可能导致取码失败。后续需完善字段级 DEX 推断及安全 IPC。
