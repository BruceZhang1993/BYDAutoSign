# 回归验证矩阵

## CI 自动检查

- [ ] Android 构建 `:app:assembleDebug`
- [ ] 配置校验单测 `:app:testDebugUnitTest`
- [ ] `sh -n` 校验 Root、签到 Shell
- [ ] 固定上游 curl / openssl Git blob SHA 一致性
- [ ] APK 包含 `xposed_init`、主/分身登录 Hook 和两种 ABI 资源

## 真机冒烟测试（必须实际执行后标记）

- [ ] Root 授权弹窗、一键初始化、重复部署无数据丢失
- [ ] LSPosed 作用域只选微信；微信重启后 `/whoami` 可连接
- [ ] 既有微信版本 8.0.49 / 8.0.62 / 8.0.70 / 8.0.71 / 8.0.72 / 8.0.74 / 8.0.76 的兼容性（按测试设备逐项验证）
- [ ] 点击初始化/重新授权，经 Binder 获得 code，Root stdin 兑换 session_id，然后立即签到成功
- [ ] 签到成功后状态文件正确；下次 cron 自动跳过
- [ ] 关屏、Doze 和无网络场景：成功或可解释失败/可重试
- [ ] 系统分身端口与多账号签到隔离
- [ ] 重启后 service.d、BusyBox crond 正常启动
- [ ] 已安装旧 byd_sign：保留邮件、Webhook、code_api.conf、签到历史
- [ ] Root 拒绝、微信关闭、服务端错误时给出明确诊断
- [ ] 微信 Hook 不监听所有网卡，且无敏感数据泄漏（Stage 1 只做基础隔离）
- [ ] 不同 ABI (arm64-v8a / armeabi-v7a) 的内置二进制可执行

**注意：** GitHub Actions 能验证构建和静态逻辑，无法代替 LSPosed + 微信 + 真实账号的端到端测试。

## 2026-10-22 微信版本适配回归

- [x] 静态检查确保 8.0.77、8.0.78、8.0.79 对应上游已发布 Hook 映射
- [x] 未知微信版本拒绝默认回退 8.0.62；返回 `HOOK_VERSION_UNSUPPORTED (-310)`
- [x] 找不到映射类时报 `HOOK_CLASS_NOT_FOUND (-311)`，包含微信版本及类名
- [x] 手动签到有失败端口时必须返回非 0 退出码，不再错误显示“操作完成”
- [ ] 微信 8.0.79 真机取码与签到测试（需要真实微信进程，CI 无法验证）
- [ ] 8.0.77 / 8.0.78 在不同厂商设备真机验证
- [ ] 从旧 APK 升级后强制停止并重新打开微信，确认新 Hook 已加载

> GitHub Actions Debug 包通常使用临时调试签名；签名不同可能无法覆盖安装旧版 APK，需先卸载旧调试包后安装新版本。Root 数据保留在 `/data/adb/byd_sign`，但卸载前仍建议备份数据。

## 动态适配回归矩阵

- [x] CI 静态验证：DEX 调用点分析器、回退结构搜索器、指纹缓存、反射校验和手动探测入口已纳入工程
- [ ] 实机微信已知映射快速路径：无多余 DEX 扫描，原签到能力不回归
- [ ] 实机微信未知混淆类：自动扫描 → 映射生效 → 真实取码 → 后续无重复扫描
- [ ] DEX/微信热补丁变化：缓存失效并重新探测
- [ ] 扫描期间登录等待、超时、重复请求与并发行为
- [ ] 候选缺失或 LoginTask 字段漂移时明确失败且不触发错误登录链

## 授权/签到解耦回归

- [x] CI Shell 语法检查和会话持久化隔离检查
- [x] 检查 sign-only 流程不直接调用 get_code/get_session_id/wake_wechat
- [ ] 真机初始化：微信可唤醒且 code → session_id 兑换正常
- [ ] 已授权后关闭微信，手动签到和锁屏定时签到仍然成功
- [ ] 跨天使用同一个 session 的有效性及服务端失效响应
- [ ] 未授权端口跳过并提示重新授权，不产生微信唤醒
- [ ] session 过期时清除对应凭证，其他账号不受影响
- [ ] 新增/删除端口、重复授权、Root 目录迁移不影响既有设置

## Binder-only IPC 迁移验收

- [x] 编译及静态检查：Hook 只注册 Binder，不启动 NanoHTTPD 服务器
- [x] 编译及静态检查：MainActivity/RootBridge 的状态/DEX 重新探测/取码通过 Binder
- [x] 模拟 Shell 测试：`authorize-stdin` 从 stdin 接收 code，保存仅 Root 可读的 session + 非敏感来源字段
- [x] 静态检查：签到主路径不调用微信、Binder 或 BYD code 兑换端点
- [x] 移除本地 `/login`、`/probe`、`/whoami` 等 HTTP 服务和测试入口
- [x] 用户设备截图：主用户微信 8.0.79 的 Binder 注册与 Ping/Pong 正常
- [ ] 在新 APK 上主动点击“通过 Binder 重新授权”，确认状态明确显示来源 `Binder IPC`（不能用历史 HTTP 初始化结果替代）
- [ ] 用上述 Binder 生成的 session 执行立即签到并成功
- [ ] 重启微信后重新注册 Binder，关闭管理 App、重启 Root scheduler，并验证预定时间自动签到
- [ ] 跨 User 微信分身：安全的 Binder/Root Broker 注册与授权（当前不支持）
- [ ] 签名 Release APK 可从 GitHub Actions Artifacts 直接下载并覆盖更新（当前签名 Secret 映射问题未解决）

> 本版本已移除旧 HTTP 网络接口，因此设备验证失败时需要暂缓合并或回滚 PR，而不是在运行时秘密回退到无鉴权 HTTP。
