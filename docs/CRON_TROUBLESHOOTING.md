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
