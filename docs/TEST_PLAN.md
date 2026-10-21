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
- [ ] 点击立即签到，code → session_id → signIn 端到端成功
- [ ] 签到成功后状态文件正确；下次 cron 自动跳过
- [ ] 关屏、Doze 和无网络场景：成功或可解释失败/可重试
- [ ] 系统分身端口与多账号签到隔离
- [ ] 重启后 service.d、BusyBox crond 正常启动
- [ ] 已安装旧 byd_sign：保留邮件、Webhook、code_api.conf、签到历史
- [ ] Root 拒绝、微信关闭、服务端错误时给出明确诊断
- [ ] 微信 Hook 不监听所有网卡，且无敏感数据泄漏（Stage 1 只做基础隔离）
- [ ] 不同 ABI (arm64-v8a / armeabi-v7a) 的内置二进制可执行

**注意：** GitHub Actions 能验证构建和静态逻辑，无法代替 LSPosed + 微信 + 真实账号的端到端测试。
