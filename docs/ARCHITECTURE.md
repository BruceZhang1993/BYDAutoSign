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
