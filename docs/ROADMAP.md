# BYD Auto Sign 开发路线图

> 固定实施约定：先整合并验证现有能力，再重写协议实现；所有阶段必须有回归测试，未完成的能力不得标成已实现。

## Stage 1：统一项目与单 APK（当前）

目标：`wxcode` + `byd_sign` 统一 Android 工程，仅需安装一个 APK（底层需已有 Root 和 LSPosed）。

- [x] 保留 wxcode 原始 `JsApiLogin$LoginTask` / 微信版本 Hook 映射
- [x] 使用同一 APK 作为 LSPosed 模块，指定微信静态作用域
- [x] 整合 byd_sign 原始 `code → session_id → signIn` 签到脚本
- [x] 内置 `curl`、`openssl` 两种 ARM 架构资源，构建期固定提交和校验
- [x] APK 内管理界面：Root 初始化 / 手动签到 / 端口 / 唤醒选项 / 状态 / 最近日志
- [x] Root service.d + BusyBox crond 启动/开机恢复机制
- [x] 沿用 `/data/adb/byd_sign` 目录并保留已有配置、签到结果
- [x] GitHub Actions 构建、Shell 静态检查及基础配置单测
- [ ] 在真机验证微信主账号签到与锁屏定时执行
- [ ] 在真机验证微信多开分身与不同厂商 ROM
- [ ] 正式发布前检查第三方可执行文件许可证和签名密钥管理

阶段完成判定：一键初始化后手动签到成功；下一次预定时间在锁屏状态能执行；已有配置不会丢失；CI debug APK 构建成功。**构建成功不等于真机签到验证完成。**

## Stage 2：原生化、安全和稳定性

目标：移除 Shell 协议与裸 HTTP 桥接依赖，提高可维护性。

- [ ] 将 `byd_sign.sh` 加解密、签名、请求构造迁移为 Kotlin/JCA/OkHttp
- [ ] 增加原脚本与 Kotlin 的协议等价测试，使用脱敏 fixture 对比请求和返回
- [ ] 删除运行时对 curl/OpenSSL 的依赖，缩减 APK 体积
- [x] 主用户：将 wxcode HTTP 登录桥从运行时代码中移除，替换为 UID 校验的 Binder/AIDL（跨 Android User 仍未实现）
- [x] 主用户 Binder：通过 AppOps 校验调用 UID/包名，限制 appId/请求长度与并发，结果核验请求 ID 与调用 UID
- [ ] 网络恢复 TLS 主机名和证书校验；移除上游 `--insecure` 参数
- [ ] 将敏感 code / session_id / 原始响应从日志和错误报告中清除
- [ ] Kotlin 数据存储（DataStore/Room）、账号独立签到状态与严格幂等
- [ ] 整合 Webhook/邮件通知、可配置签到时间与错误重试策略至 APK 页面
- [ ] 修复跨包 Foreground Service 生命周期问题，默认按需启动
- [ ] 加入微信 Hook 适配健康检查和接口契约测试

阶段验收：无 Shell 依赖可完整签到；无未授权本机 HTTP 登录入口；核心网络安全检查通过；旧数据迁移经过回归验证。

## Stage 3：自动化与平台覆盖

目标：在 Root 设备上更稳定地无感执行，支持跨品牌和多用户场景。

- [ ] 按 Android User ID 自动发现主微信、分身及运行中的 Hook 实例
- [ ] 账号选择与实例映射持久化，不再依赖人工端口配置
- [ ] 对应微信/系统版本的可靠、最小打扰唤醒策略，给出后台限制诊断
- [ ] 支持重启、升级、微信被回收、无网络和 Doze 条件下的补偿性调度
- [ ] 将海洋网 / 王朝网 / 方程豹 / 腾势 / 仰望定义为独立 Provider 配置和测试用例
- [ ] 多账号隔离、独立重试、部分成功与签到历史/连续天数
- [ ] 自动升级兼容性检查、错误分级和可导出的脱敏诊断报告
- [ ] UI 适配 Material 3 Expressive、深色模式、无障碍、国际化
- [ ] 发布流水线：构建、自动测试、Release APK、可复现版本信息
- [ ] 兼容 Magisk / KernelSU / APatch 的 Root 生命周期并提供最小权限策略

阶段验收：多账号多品牌真实设备测试通过；在约定的设备和系统配置下连续运行七日且无丢签；出现不支持微信版本时有明确诊断与可回滚方案。

## 升级原则

1. 不破坏现有安装目录 `/data/adb/byd_sign` 的用户数据。
2. 网络协议、Hook 版本适配表修改需同步加入契约测试。
3. 设备测试必须区分真机已验证、CI 已验证与未验证。
4. 默认低功耗、按需唤醒；不能承诺所有厂商 ROM 100% 锁屏成功。
5. 禁止上传真实登录凭证、未经脱敏的会话数据。

## 动态 Hook 增量（2026-10）

- [x] 移植 MIT 授权的 DEX 调用点解析 + 结构搜索（参见第三方声明）
- [x] 不再仅靠微信版本号；已知映射先校验结构，失效则自动动态探测
- [x] DEX 指纹缓存与实际登录结果二次验证状态
- [x] 后台单任务探测、触发频率限制、UI 手动重探和诊断
- [ ] 实现 LoginTask 字段级动态定位：输入字段、状态字段和 code 字段不能仅检查旧名字
- [ ] 对微信补丁后运行时内存 DEX 的完整覆盖与更丰富的候选交叉验证
- [ ] 多个微信版本真机联调，包括未知混淆版本模拟测试

> 缓存里区分“反射结构验证通过”和“真实取码验证通过”。前者不等于签到可用，严禁将 CI 成功视作真机验证。

## 会话初始化与签到解耦（v0.3.0）

- [x] 首次初始化一并执行微信 code → BYD session 兑换
- [x] 支持用户主动重新授权，多端口会话隔离与 Root 安全持久化
- [x] 定时和手动签到只读取本地 session，不唤醒微信或重新获取 code
- [x] 会话失效显示重新授权，而不是自动隐式拉起微信
- [ ] 真机确认 BYD session 跨天/跨重启有效性与服务器过期信号
- [ ] 根据验证结果决定是否需要独立的定期授权刷新机制，不混入签到任务

## Stage 2 IPC 技术研究（2026-10-22）

详细方案见 [IPC_RESEARCH.md](IPC_RESEARCH.md)。该文档包含设计、已实现 POC 和设备验收状态；Binder Ping/Pong 已有主用户设备截图证据，取码/兑换仍待验证。

- [x] 审阅现有 Hook / localhost HTTP / Root 授权链
- [x] 对比 Binder AIDL、Messenger、ContentProvider、Root Unix socket 等路径
- [x] 选定「微信 Hook 反向注册到模块 APK Service，再通过 Binder 回调取 code」为主用户候选架构
- [x] POC A：Binder 注册与 Ping/Pong 成功（2026-10-22 用户真机截图，User 0）
- [x] POC B：已编码带鉴权/超时的异步 code 回调（CI 编译通过；真实 code 传输待真机验证）
- [x] POC C：已编码一次性 code 通过 Root stdin 兑换 session 的实验入口（CI 编译通过）
- [ ] POC B/C 真机验证：使用「通过 Binder 重新授权」确认 code → BYD session 全链路；仅 Ping/Pong 和旧 HTTP 会话不算通过
- [ ] 多 User/系统分身绑定能力验证与跨用户 fallback 选型

> 上述研究刻意不触及第一阶段业务回归或已投入使用的 HTTP/Shell 工作流。

## HTTP 通道移除（主用户，代码完成 / 真机验收待补）

- [x] 移除 `LoginHttpServer.java` / `ServerContext.java`、NanoHTTPD 依赖及 APK 的明文流量设置
- [x] Hook 的状态查询和手动 DEX 探测改用受限的 AIDL
- [x] 管理 App 不再发起任何 localhost HTTP 请求；初始化、重新授权只允许 Binder → stdin
- [x] 删除 Root Shell 旧 `get_code_from_api`、`get_code`、`authorize` HTTP 分支及 WebUI HTTP 测试入口
- [x] 保留 Root 已有 session 和定时签到行为；只有初始化/明确重新授权才允许唤醒微信
- [x] 添加源码契约与 Root stdin 模拟测试，检查无 HTTP 依赖、Session 权限、签到与取码隔离
- [ ] 用真实微信再次验证 Binder 取码和 BYD session 兑换 / 立即签到
- [ ] 解决微信分身跨 User Binder 注册及新授权；过期的分身 session 无法靠当前主用户 Binder 更新
- [ ] 回归验证锁屏定时、跨天 session 以及既有 Root 配置
- [ ] 完成可原地升级的同证书 Release APK CI（现有 `BYD_SIGNING_*` 密钥环境尚未映射成功）

> 迁移代码位于独立 Draft PR；**不能因编译成功将本阶段标成生产环境完全验收**。首次升级需重新部署 Root runtime、重启微信以加载新 Hook，旧会话原地保留。
