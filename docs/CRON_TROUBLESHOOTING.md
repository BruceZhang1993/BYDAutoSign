# 定时签到故障排查

自动签到由 Root 目录中的 BusyBox `crond` 执行，不依赖 BYDAutoSign 或微信常驻。默认任务位于 `/data/adb/byd_sign/cron.d/root`，执行时间采用**手机系统本地时区**的 `00:01、07:01、13:01、22:01`。没有触发时刻的补执行；当天已签到成功则自动跳过。

## 如何判断故障层次

新版管理页“环境状态”中同时显示**Root 运行环境**与**定时调度（BusyBox crond）**。此前仅判断 `byd_sign.sh`、curl、OpenSSL 文件是否存在，不能证明定时进程活着。

- **crond 未运行**：点击“修复定时调度”。失败时查看 `scheduler.log`；可能找不到 BusyBox、BusyBox 缺少 crond applet、Root 管理器没有启动 service.d、进程被系统杀死。
- **crond 正在运行，但任务表不包含签到脚本**：检查旧配置是否含自定义任务。安装器保留已有的非空 `cron.d/root` 文件，不会覆盖自定义规则；如需恢复默认任务，请先备份旧文件。
- **进程和任务表正常，但没有执行记录**：检查 `cron.log` 中是否有触发消息，核对手机系统时区、开机时刻和实际是否经过指定分钟。若设备长时间深度休眠，普通 crond 并**不保证 Doze/休眠下准时唤醒**。
- **有执行记录但没有签到成功**：查看当日日志中的业务失败原因；如有 `AUTH_EXPIRED` 或“需要重新授权”，与 cron 是否运行无关。签到时不会自动启动微信。
- **安装了旧 byd_sign Root 模块**：先检查是否存在重复的 `crond`、重复任务或相互结束进程的启动脚本，排查时保留备份。

## Root 环境诊断命令

在终端执行（需要 Root）。**先在新 APK 中点击“重新部署 Root 环境”**，因为旧版本启动脚本不支持只读 `status` 参数：

```sh
su -c 'sh /data/adb/service.d/byd_auto_sign.sh status'
su -c 'cat /data/adb/byd_sign/cron.d/root'
su -c 'tail -n 40 /data/adb/byd_sign/logs/scheduler.log'
su -c 'tail -n 40 /data/adb/byd_sign/logs/cron.log'
```

定时服务如果只在重启后失效，检查 Root 管理器的 service.d 支持情况和是否有其它开机脚本干扰；临时运行：

```sh
su -c 'sh /data/adb/service.d/byd_auto_sign.sh'
```

新版脚本现在必须确认 `crond` 进程实际存活才报告 `CRON_STARTED`，失败则返回非 0。使用稳定的 `logs/cron.log`，而不是启动时日期命名的旧文件；旧的 `cron_YYYY-MM-DD.log` 不会被删除。

**隐私提示：** 上述日志可能包含旧版上游接口的响应，请勿直接公开粘贴完整日志、session_id、微信登录 code 或私有 Webhook 配置。诊断时只需提供脱敏的报错行。

## 当前架构限制

BusyBox crond 是轮询时钟的传统 cron 调度器，**不是 Android AlarmManager 唤醒机制**。在设备深度待机、厂商节电策略和 Doze 下，`crond` 存活不保证能在锁屏状态准时触发。后续可增加 Root/Android 唤醒型调度或超时补偿；不应在无真机运行记录时把定时成功率标记为已验证。

## 2026-10-23：定时调度显示未运行（疑似误判）

设备界面可出现 **Root 环境正常、Binder 正常，但 BusyBox crond 显示未运行**。该提示只说明健康检查没有识别到本应用自己的 daemon，尚不能区分以下情况：

1. daemon 真正未启动、启动失败或被系统杀掉
2. BusyBox 进程参数被精简，旧检测器只依赖 `-c /data/adb/byd_sign/cron.d` 参数，造成**误报**

当前修复：检测 `/proc/<pid>/cmdline` 时，结合 `/proc/<pid>/comm` 验证 crond/busybox 进程身份，并以 `/proc/<pid>/cwd`（BusyBox crond 启动后切到 cron spool）作为备用识别依据；BusyBox 查找覆盖 Magisk、KernelSU、APatch 和常见 BusyBox NDK 模块，同时要求实际包含 crond applet。点击修复失败时，在 App 操作结果中展示脱敏的最后几条 CRON_ERROR 记录。

**设备侧确认方式**（不要把真实凭证贴出来）：

```sh
su -c 'ps -A | grep "[c]rond"'
su -c 'cat /data/adb/byd_sign/cron.d/root'
su -c 'tail -n 30 /data/adb/byd_sign/logs/scheduler.log'
```

如果命令一能看到本应用的 crond，但 App 显示未运行，优先排查健康检查误判；否则先看启动错误。只有 `CRON_RUNNING` 并且任务表包含 `byd_sign.sh` 时，应用才会显示绿色。进程正在运行不意味着屏幕熄灭期间能准确被唤醒。

## 2026-10-23：`CRON_STARTED` 后仍报错 124

若修复按钮显示：

```text
操作失败 (124)
操作超时
CRON_STARTED
```

说明启动脚本**已经走到确认 crond 存活的分支**，但管理端的 `su -c` 命令仍未在超时时间内正常退出。BusyBox `crond -b` 会在后台运行，如果未明确关闭/重定向标准输入、标准输出与标准错误描述符，部分 Root 管理器中会让后台 daemon 保持与当前 Root 命令关联的文件描述符，表现为「启动已成功，但调用者等待超时」。这与「找不到 BusyBox」不同。

修复措施：

- `restart_crond()` 在创建 daemon 时明确使用 `</dev/null >/dev/null 2>>scheduler.log`；保留错误日志，不让后台 daemon 使用父命令的输入输出管道。
- `RootBridge.shell()` 对不传入敏感 stdin 的命令立即关闭 `process.outputStream`，主动通知 Root 进程标准输入 EOF。
- `restartScheduler()` 启动结束后单独执行只读 `status`：只有返回 `CRON_RUNNING`，且任务表包含 `byd_sign.sh` 时才报告成功。若启动命令超时但实际已运行，会显示经过核验的成功信息；若状态仍不正常，则保留错误状态和诊断。
- CI 新增防止后台 fd 继承和只依赖 `CRON_STARTED` 的回归断言。

安装包含上述修复的新版 APK 后，请**先点击「重新部署 Root 环境」**，更新磁盘上的 `lib.sh` 和 service.d 启动脚本；之后点击「修复定时调度」，刷新确认。此前已保存的 BYD 会话和历史 cron 配置不应删除。

**注意：** `CRON_STARTED` 是之前的启动结果，不意味着指定时间签到肯定执行；仍须验证当天 `cron.log` 和签到日志，并考虑 Android 深度休眠下传统 crond 无法精准唤醒的限制。
