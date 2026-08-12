# Code signing policy / 代码签名策略

本页定义校园跑 FIT 轨迹生成工具的公开签名策略。它不代表项目已经取得任何商业证书、公益证书或平台批准。

## 固定产物与状态

| 平台 | 状态 | 文件名 / 用途 | 发布要求 |
| --- | --- | --- | --- |
| Android | 已签名 | `fit-generator-android.apk` | 对应的四项签名输入齐全，且 `apksigner verify` 成功 |
| Android | 未签名 | Gradle 为 `app-release-unsigned.apk`；workflow 诊断副本为 `fit-generator-android-UNSIGNED.apk` | 仅供构建诊断，不作为可安装的 GitHub Release 产物 |
| Windows | SignPath 已批准并完成签名 | `fit-generator-windows-x64.exe` | Authenticode 状态必须为 `Valid` |
| Windows | 未获批、不可用或未启用 SignPath | `fit-generator-windows-x64-UNSIGNED.exe` | 文件名和发布说明必须明确 `UNSIGNED`，并同时提供 SHA-256 |

校验和可验证下载后的字节是否一致，但不能替代发布者身份签名。未签名 Windows 文件绝不改名为已签名文件；以后取得签名能力时应发布新的补丁版本。

## Android

Android 官方说明要求 APK 在安装或更新前经过数字签名，且应用生命周期内应保持相同签名身份；Google Play 场景还区分应用签名密钥与 upload key。详见 [Android 应用签名官方文档](https://developer.android.com/studio/publish/app-signing) 和 [`apksigner` 官方说明](https://developer.android.com/tools/apksigner)。自行生成的 Android keystore 不需要购买第三方 CA 证书，但这不免除应用商店账号、开发者验证或其他平台规则。

Gradle 仅在以下四项环境变量全部存在且非空时创建并绑定 release signing config：

- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

缺少任意一项时保持真正的 unsigned release，绝不回退到 debug keystore。这四项用于开发者本地 Gradle release 构建；当前 release workflow 则先在无签名 secret 的 job 中生成 unsigned APK，再仅在受保护的 `release` environment 中读取 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`，把 keystore 临时恢复到 runner、使用固定版本 `apksigner` 签名和验证，并在 `always()` 清理临时文件。keystore、私钥、密码以及其 Base64 内容不得进入仓库、artifact、缓存或日志。

## Windows 与 SignPath

Windows contributor build 和普通 CI build 默认未签名。本项目只计划通过固定的 SignPath 项目 `fit-generator` 和策略 `release-signing` 签署公开 Windows EXE，不在仓库或 runner 中存放 PFX 私钥。

[SignPath Foundation](https://signpath.org/) 当前为符合条件的开源项目提供免费服务，并说明私钥由其 HSM 保管；但项目必须先从[官方申请页](https://signpath.org/apply.html)提交申请并符合[开源项目条件](https://signpath.org/terms.html)，包括开源许可、已发布且持续维护、可验证构建、团队角色、变更审查和逐次人工批准等要求。该方案是申请制，不保证受理、批准、持续可用或免除所有系统信誉提示。

当前项目没有声称已经获批。仓库变量必须默认保持 `SIGNPATH_ENABLED=false`；只有官方批准、项目/策略配置完成且所需 token 以 GitHub secret 提供后，release workflow 才能启用签名分支。SignPath 不可用或尚未批准时，Windows 产物继续使用 `UNSIGNED` 文件名、SHA-256 和来源说明。

如果将来获得 SignPath Foundation 资格，项目首页和发布页还必须按其届时官方条件公开维护者/审查者/批准者角色、隐私说明，以及官方要求的资助声明；不得在批准前提前展示暗示已获资助或已有证书的措辞。

## 密钥与权限边界

- 私钥、keystore、PFX、密码和 API token 只能来自受保护的环境或 CI secrets，不能作为命令行回显、构建缓存或发布附件。
- verifier 只接收发布目录和“是否必须有签名”的开关，不接收任何密钥或密码。
- 地图服务 key 是用户运行时配置，不是构建 secret，也不得写入原生产物。
- 本地 activity/FIT 生成不访问项目自有服务器；只有用户主动使用地图或搜索时，客户端才会直接请求所选第三方提供商。
- 原生 Windows/Android 发布物不得包含 Garmin FIT SDK 包、源代码或二进制；FIT 编码来自仓库内的开源 Rust 核心。

## 离线验收

发布目录准备完成后运行：

```powershell
pwsh -NoProfile -File scripts/verify-release.ps1 -ReleaseDirectory release-output
```

正式 Android 发布增加 `-RequireAndroidSignature`；只有 SignPath 已启用并返回签名 EXE 时才增加 `-RequireWindowsSignature`。Windows 签名使用仅在 Windows 提供的 [`Get-AuthenticodeSignature` 官方接口](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.security/get-authenticodesignature)，Android 签名使用 Android SDK 的 `apksigner`。

不带 `-RequireAndroidSignature` 的基础模式只检查固定 release 目录布局和 APK 结构，不对 Android 签名作任何肯定；它也不是对 Gradle 原名 `app-release-unsigned.apk` 的直接发布验收。GitHub Release 必须使用该开关，不能把通过基础结构检查解释为 APK 已签名或可公开安装。
