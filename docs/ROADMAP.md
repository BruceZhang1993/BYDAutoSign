# BYD Auto Sign 路线图

> 当前主线：**去 Root Kotlin 原生化（v0.5.0）**；阶段标记只代表代码实现，不代表真机通过。

## Stage 1：单 APK / LSPosed Hook
- [x] 单 APK 集成 wxcode Hook、动态 DEX 探测与 Binder 受限授权
- [x] 主 User 0 Binder 注册 Ping/Pong
- [x] 删除无鉴权 localhost HTTP Hook 桥接
- [x] 用户反馈：主微信 Binder 取码 → Kotlin 原生 BYD session 兑换/加密保存的真机验收（2026-10-24）
- [ ] 使用新 session 立即签到并获取真实成功响应的真机验收
- [ ] 多微信版本动态探测的真实取码验证

## Stage 2：无 Root 原生签到与自动调度
- [x] Kotlin JCA + OkHttp 重写 session 兑换、BYD 签到协议
- [x] Android Keystore AES-GCM 保管 session，取消 Root 存储及 shell 调用
- [x] Android AlarmManager 自定义多个本地执行时间，支持闹钟权限降级
- [x] WorkManager 网络恢复重试、开机/时区变化/升级后调度重建
- [x] 进程内互斥、当日幂等与简单结果持久化
- [x] 去除打包 Root runtime、curl/OpenSSL、旧定时安装流程
- [x] AES/OpenSSL 对照向量与 SHA256 协议单测
- [x] 用户反馈：HTTPS 原生会话兑换与本地保存真机成功（2026-10-24）
- [ ] 签到接口的真实成功/失败响应及异常场景契约验证
- [ ] 不同 ROM 的锁屏、Doze、重启、权限撤销、网络恢复设备验收
- [ ] 更完善的错误分级/账号隔离及脱敏通知体系

## Stage 3：分身与自动化体验
- [ ] 跨 Android User Binder 授权、微信分身、多账号
- [ ] BYD 多品牌配置与独立账号签到历史
- [ ] Session 跨天真实有效期及受控刷新策略
- [ ] 同一账号跨进程事务幂等、远端已签到兼容
- [ ] Material 3 Expressive 深入打磨、可导出脱敏诊断、国际化
- [ ] 连续七日无漏签、受约束条件下的多品牌多 ROM 验证

## 从旧 Root 版升级

旧 `/data/adb/byd_sign` 的私有会话无法在不使用 Root 权限时自动导入。重新通过微信 Binder 授权即可创建新原生密文。旧 `service.d`、crond 必须手动移除，否则可能重复签到；**不能因新 APK 不含 Root 功能就声称已自动卸载历史模块**。

## 发布门槛

升级 APK 签名证书一致；CI 已签名原始 APK；协议回归/时区单测通过；设备手动授权+立即签到成功；锁屏执行有实际运行记录。不以 CI 编译成功替代真机链路验证。
