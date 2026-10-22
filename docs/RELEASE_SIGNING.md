# GitHub Actions：直接下载已签名 Release APK

## 交付规则

CI 只将通过 Android `apksigner verify` 校验的 **Release APK 原始文件**上传到 **Actions → Artifacts**。不会创建 GitHub Release，也不再发布 Debug APK 作为供安装的 Artifact。

使用官方 [`actions/upload-artifact@v7`](https://github.com/actions/upload-artifact) 的 `archive: false` 参数。GitHub 自 2026-02-26 起支持这一模式，浏览器下载的是 `BYDAutoSign-release.apk`，**不再是 ZIP**。该选项只能上传单个文件；因此构建会先将签名后的 `app-release.apk` 复制为固定文件名 `dist/BYDAutoSign-release.apk`。

此工作流在主仓库的 `master` push、同仓库 PR 和手动触发时构建并上传签名包；Fork PR 不会读取主仓库签名 Secrets，也不会上传误导性的 Debug APK。

## 一次性配置发布签名

进入仓库的 **Settings → Secrets and variables → Actions → Repository secrets**，配置以下四个 Secret：

| Secret | 用途 |
| --- | --- |
| `BYD_SIGNING_KEYSTORE_BASE64` | 将同一份 release `.jks` 密钥库 Base64 编码成单行字符串 |
| `BYD_SIGNING_STORE_PASSWORD` | 密钥库密码 |
| `BYD_SIGNING_KEY_ALIAS` | 用于签名的别名 |
| `BYD_SIGNING_KEY_PASSWORD` | 对应私钥密码 |

首次创建密钥库的示例（**仅当尚无既有发布密钥时**）：

```bash
keytool -genkeypair -v \
  -keystore bydautosign-release.jks \
  -alias bydautosign \
  -keyalg RSA -keysize 3072 -validity 36500
```

在 Linux 上获取 Base64：`base64 -w 0 bydautosign-release.jks`；macOS 可使用 `base64 < bydautosign-release.jks | tr -d '\\n'`。

**切勿将 `.jks`、私钥、密码或 Base64 内容提交到 Git 仓库、Issue、PR 或日志。** 密钥应离线加密备份；所有正式构建必须复用同一密钥。GitHub Actions 并不自动提供 Android Release 签名证书。若未配置 Secret，签名步骤会明确失败，且不上传未签名产物。

## 下载与验证

1. 打开 [Actions](https://github.com/BruceZhang1993/BYDAutoSign/actions) 中成功完成的工作流运行。
2. 在 **Artifacts** 区域点击 `BYDAutoSign-release.apk`，直接下载原始 APK。
3. 需要验证安装包签名时，运行：
   ```bash
   apksigner verify --verbose --print-certs BYDAutoSign-release.apk
   ```

签名证书决定 Android 的原地更新兼容性。如果此前安装的是临时 Debug 签名的 APK，**不能直接通过新 Release 证书覆盖安装**。迁移前请先备份应用和 Root 数据；本项目的签到持久目录位于 `/data/adb/byd_sign`，与普通应用私有目录不同。安装新签名 APK 后，需要重新确认 LSPosed 模块生效与微信 Hook 初始化。

如果此前已存在正式签名发布版本，**必须复用原有密钥**，不要为每次 CI 重新生成随机证书。
