# FIT 轨迹生成工具 - Keep校园跑

[![Native CI](https://github.com/Alpha-Auxiliary/fitGenerator/actions/workflows/ci.yml/badge.svg)](https://github.com/Alpha-Auxiliary/fitGenerator/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Alpha-Auxiliary/fitGenerator?label=%E4%B8%8B%E8%BD%BD&color=blue)](https://github.com/Alpha-Auxiliary/fitGenerator/releases/latest)

一个面向 Windows 与 Android 的跑步轨迹绘制、活动模拟和 FIT 文件生成工具。项目正在从早期 Web/平台独立实现迁移到“原生界面 + Rust 共享核心”，以共享校验、确定性模拟和 FIT 编码逻辑。

> 当前源码状态：`core-rust/` 共享核心、Windows C ABI 接入和 Android 原生客户端代码均已落地。Windows 项目目标为 `win-x64` 自包含单 EXE；Android 项目目标为同时包含 `arm64-v8a` 与 `x86_64` Rust JNI 库的 Release APK。旧 Web 实现暂时保留作为迁移期对照，不是 Windows 或 Android 原生版的运行依赖。

## 新架构与目录

- [`core-rust/`](core-rust/README.md)：Rust 2024 共享核心，负责输入校验、轨迹与心率模拟、预览 JSON、最小 FIT Activity 编码，并提供 opaque C ABI 和 Android JNI。
- `native-windows/`：Windows Forms 原生客户端，通过 C ABI 调用 Rust 核心，发布目标是 Windows 10/11 x64 便携式单 EXE。
- `android/`：Kotlin、Jetpack Compose 与 MapLibre Android 客户端，通过 `com.alphaauxiliary.fitgenerator.core.NativeCore` 的 JNI 接口使用同一核心。
- `public/`、`src/`、`server.js`：迁移期间保留的旧 Web 实现。
- `docs/superpowers/`：已确认的架构规格与分阶段实施计划。

共享核心不访问地图、网络、文件系统或系统凭据；地图展示、地点搜索、文件保存和平台设置均由原生客户端负责。核心的接口、版本和内存所有权详见 [`core-rust/README.md`](core-rust/README.md)。

## ✨ 现有界面功能

- 🗺️ **地图绘制**：在地图上自由手绘跑步轨迹
- 🔍 **地点搜索**：支持中文搜索，快速定位到任何地点
- ⚙️ **参数配置**：自定义心率、配速、圈数等参数
- 📊 **数据预览**：实时预览配速和心率曲线
- 📥 **批量导出**：支持一次生成多个不同时间的 FIT 文件
- 🎨 **原生界面**：Windows 端使用原生桌面窗口和地图控件
- 📦 **免安装运行**：Windows 发布目标为单文件 EXE，不启动浏览器或本地服务

## 🚀 快速开始

### 方式 1：直接下载（推荐）

如果 Release 附件已提供 Windows 原生版：

1. 前往 [Releases](https://github.com/Alpha-Auxiliary/fitGenerator/releases/latest) 页面。
2. 下载 `fit-generator-windows-x64.exe`；如果项目尚未取得 Windows 签名能力，则文件名必须是 `fit-generator-windows-x64-UNSIGNED.exe`。
3. 附件是单个 EXE，无需解压或保留旁置 DLL；直接双击运行即可。无需安装 .NET、Node.js 或 Rust，也无需管理员权限。

默认 OpenStreetMap 地图和地点搜索无需 API Key。只有选用其他需要凭据的地图源时，才需在应用内的地图设置中填写 Key。客户端不要求代理，也不内置代理地址；它跟随操作系统网络设置。在线瓦片不可达时会自动切到随应用提供的离线绘图底板，并在后台退避重试。

> `UNSIGNED` 文件可能触发 Windows SmartScreen 声誉提示；下载后应先按 Release 中的 `fit-generator-SHA256SUMS.txt` 核对 SHA-256。单纯构建单 EXE 不会消除该提示。

如果 Release 附件已提供 Android 原生版：

1. 下载项目发布的已签名 `fit-generator-android.apk`。
2. 在 Android 设备上确认安装该 APK；Android 可能要求允许当前文件来源安装应用。
3. 启动后可直接绘制路线、离线预览并保存或分享 FIT，不需要配置服务器地址。详细地图瓦片与地点搜索需要可访问的网络服务；服务不可达时自动使用离线绘图底板，最近路线、绘制、预览和 FIT 生成仍可继续。

Android 首版只声明网络权限，不读取设备定位，也不申请存储、后台定位或“所有文件访问”权限。界面的“定位”操作只回到当前路线位置；保存使用系统文件选择器，分享只使用应用缓存中的临时文件。

### 方式 2：迁移期旧 Web 开发模式

1. **克隆仓库**
   ```bash
   git clone https://github.com/Alpha-Auxiliary/fitGenerator.git
   cd fitGenerator
   ```
2. **填写API**
   ```bash
   打开.env文件填写对应的地图API
   ```
3. **安装依赖**
   ```bash
   npm install
   ```
4. **启动服务**
   ```bash
   npm run dev
   ```
5. **访问应用**
   打开浏览器访问 `http://localhost:8080`

## 🛠️ 技术栈

- **共享核心**：Rust 2024，输出 `rlib` / `cdylib`，内置开源实现的 FIT Activity 子集编码器
- **Windows 架构**：.NET 8 Windows Forms + Mapsui，通过 opaque C ABI 调用 Rust 核心
- **Android 目标架构**：Kotlin 原生界面通过 JNI 调用 Rust 核心
- **迁移期旧实现**：[Express.js](https://expressjs.com/)、[@garmin/fitsdk](https://www.npmjs.com/package/@garmin/fitsdk)、[@vercel/ncc](https://github.com/vercel/ncc)、[pkg](https://github.com/vercel/pkg)
- **地图**：原生版内置 OpenStreetMap、无网络离线绘图底板和自动重试，并支持用户配置自定义地图源；百度、高德和谷歌的预设下拉项属于迁移期旧 Web 页面
- **迁移期 Web 图表**：[Chart.js](https://www.chartjs.org/)
- **搜索**：跟随当前地图源使用对应地点搜索

## 🗺️ 地图源配置

Windows 原生版不读取项目目录的 `.env`。默认 OpenStreetMap 无需 Key；可选地图源、自定义地图源及其 Key 在应用内设置，凭据由 Windows 用户范围的 DPAPI 保护。Windows 使用系统代理设置；无代理、断网或在线地图不可达时仍可在离线底板上操作路线。设置与上次路线保存在 `%LOCALAPPDATA%\FitGenerator.Native`，不写入 EXE 所在目录。

以下 `.env` 项只供迁移期旧 Web 实现使用：

- `MAP_DEFAULT_PROVIDER`：默认地图源，支持 `osm`、`google`、`baidu`、`amap`，默认 `osm`
- `BAIDU_MAP_AK`：百度地图 AK
- `AMAP_MAP_KEY`：高德地图 Key
- `AMAP_SECURITY_JS_CODE`：高德安全密钥（如控制台开启了安全密钥校验）
- `GOOGLE_MAPS_API_KEY`：Google Maps JavaScript API Key

旧 Web 页面左侧“地图源”下拉框可以在 OpenStreetMap、百度地图、高德地图、谷歌地图之间切换。

## 📖 使用指南

1. **定位地点**：在搜索框中输入地点（如"天安门"），选择结果自动跳转。
2. **绘制轨迹**：开启 **“绘制路线”** 后在地图上拖动画出路线，并可撤销、清空或移动顶点。
3. **设置参数**：配置配速、心率范围、圈数和导出份数。
4. **预览与导出**：
   - 先预览路线计时数据；预览与导出复用同一随机种子和活动模型。
   - 点击 **“生成 FIT”** 后，在 Android 系统文件选择器中保存，或通过系统分享发送临时文件。

## ⚙️ 构建与分发

### Rust 共享核心：供本地或 CI 后续执行

以下命令均从仓库根目录运行，用于本地复现与 CI 校验。提交前已在 Windows 主机完成 Rust/Windows 发布链路和部分 Android 编译校验；完整质量门仍以 GitHub Actions 的实际结果为准。

Linux CI 检查与主机构建：

```bash
rustup component add rustfmt clippy
cargo fmt --manifest-path core-rust/Cargo.toml --check
cargo clippy --manifest-path core-rust/Cargo.toml --all-targets --all-features --locked -- -D warnings
cargo test --manifest-path core-rust/Cargo.toml --all-features --locked
cargo build --manifest-path core-rust/Cargo.toml --release --locked
```

Windows CI（应在带 MSVC 工具链的 Windows runner 中执行）：

```powershell
rustup target add x86_64-pc-windows-msvc
cargo fmt --manifest-path core-rust/Cargo.toml --check
cargo clippy --manifest-path core-rust/Cargo.toml --all-targets --all-features --locked -- -D warnings
cargo test --manifest-path core-rust/Cargo.toml --all-features --locked
cargo build --manifest-path core-rust/Cargo.toml --release --locked --target x86_64-pc-windows-msvc
```

Windows 动态库的目标路径为 `core-rust/target/x86_64-pc-windows-msvc/release/fit_generator_core.dll`。Android 交叉编译、C ABI 和 JNI 契约见 [`core-rust/README.md`](core-rust/README.md)。

### Windows 便携式单 EXE

开发者构建需要 Windows 10/11 x64、.NET 8 SDK 和 Rust MSVC 工具链。先产生上述固定路径的 Rust DLL，再从仓库根目录发布：

```powershell
rustup target add x86_64-pc-windows-msvc
pwsh -NoProfile -File scripts/build-rust-windows.ps1 -Configuration Release
npm run build:native
```

在 `Release + win-x64 + PublishSingleFile` 发布中，如果 `fit_generator_core.dll` 不在约定路径，MSBuild 会在计算发布文件前显式停止并报出缺失路径，不会生成缺少核心的伪完整包；普通编辑和测试项目引用不会触发该发布检查。发布配置将 .NET 运行时和 Rust DLL 捆绑进 `dist/native-windows/FitGenerator.Native.exe`；运行时由 .NET 将内部文件解压到运行时管理的目录（Windows 默认位于 `%TEMP%\.net`），分发目录的目标只有这一个可分发 EXE。Windows 原生版不依赖 Garmin FIT SDK，FIT 编码由开源 Rust 核心完成。

非交互自检可用于验证打包后的核心边界：

```powershell
& .\dist\native-windows\FitGenerator.Native.exe --self-test
$LASTEXITCODE
```

自检先确认 Windows 10/11 x64 系统与进程架构，再加载 `fit_generator_core.dll`、完成核心 API 版本握手，并使用固定的北京两点、两圈请求验证 schema/algorithm 版本、预览和 FIT 生成。成功返回 `0`，失败将中文错误消息写入标准错误并返回 `2`，不创建主窗口。普通双击仍为无控制台的 Windows 应用；启动失败时会显示可操作的错误对话框。

正式发布前应在干净的 Windows 10 和 Windows 11 x64 虚拟机上只复制该 EXE，断网后依次验证：双击启动、载入已保存路线、预览、导出一个 FIT，并确认没有浏览器、本地服务、控制台、管理员提示或运行时安装器。

> 已在 Windows x64 主机使用 Rust 1.97.1 与 .NET SDK 8.0.421 生成 Rust DLL 和 self-contained single-file EXE；`--self-test` 返回 `0`，产物为 AMD64 PE 且发布目录无旁置 DLL。干净 Windows 10/11 虚拟机上的完整交互验收仍需在正式发布前执行。

### Android 原生 Release APK

Android 构建需要 JDK 17、Android SDK/NDK、Rust Android targets，以及固定版本 `cargo-ndk 3.5.4`。`.so` 属于本地或 CI 构建产物，不提交到仓库。先从仓库根目录生成两个 JNI 库：

```bash
bash scripts/build-rust-android.sh
```

脚本预期生成：

```text
android/app/src/main/jniLibs/arm64-v8a/libfit_generator_core.so
android/app/src/main/jniLibs/x86_64/libfit_generator_core.so
```

随后在 Linux、macOS、WSL 或 Git Bash 中构建 Release APK：

```bash
npm run build:apk
# 等价命令：./android/gradlew -p android assembleRelease
```

该命令只组装 Release APK，不生成 AAB；本阶段没有把 AAB 作为发布产物。

在 Windows PowerShell 或“命令提示符”中使用批处理 wrapper：

```powershell
.\android\gradlew.bat -p android assembleRelease
```

Release 构建会在打包前检查上述两个 JNI 库是否存在且非空，避免生成无法加载 Rust 核心的 APK。预期产物位于：

```text
# 四项签名环境变量完整时
android/app/build/outputs/apk/release/app-release.apk

# 未配置或未完整配置签名环境变量时
android/app/build/outputs/apk/release/app-release-unsigned.apk
```

发布签名只读取以下环境变量；四项全部存在且非空时才绑定 release 签名：

- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

缺少任意一项时，Gradle 只输出一次生命周期警告并生成未签名 APK，绝不回退使用 debug keystore。密钥库和密码不应写入仓库、Gradle 文件或命令输出；长期发布必须保管并持续使用同一 Android 应用签名密钥。

Android 应用签名不要求购买第三方 CA 证书：项目所有者可用 JDK `keytool` 生成并离线保管自签 release keystore。它是应用身份密钥，不是第三方认证的“免费证书”，也不会绕过 Android 或应用商店的安全策略。未签名 APK 只是待签名构件，不能作为可安装的 GitHub Release 附件；公开下载前必须用项目长期密钥完成签名。

打包后应检查 APK 中恰好包含两个目标 ABI 的原生库：

```bash
unzip -l android/app/build/outputs/apk/release/app-release.apk | \
  rg 'lib/(arm64-v8a|x86_64)/libfit_generator_core.so'
```

如果构建的是未签名产物，请将命令中的文件名改为 `app-release-unsigned.apk`。正式发布前还应在一台 arm64 真机和一个 x86_64 模拟器上分别验证：无服务器地址启动、路线绘制与编辑、预览、断网重启并恢复最近路线、FIT 保存/分享和独立解码；整个流程不应出现存储或定位权限请求。

对已签名 APK 的基础安装自检可使用 Android SDK 工具：

```bash
apksigner verify --verbose --print-certs \
  android/app/build/outputs/apk/release/app-release.apk
apkanalyzer manifest permissions \
  android/app/build/outputs/apk/release/app-release.apk
adb install -r android/app/build/outputs/apk/release/app-release.apk
adb shell am start -W \
  -n com.alphaauxiliary.fitgenerator/.MainActivity
```

权限输出应只有本项目声明的 `android.permission.INTERNET`；系统或依赖合并出的权限仍须在发布前逐项审查。未签名 APK 必须先完成正式签名，不能直接执行安装验收。

> Android 的 `compileDebugKotlin` 与 `compileDebugAndroidTestKotlin` 已在 Windows/Android Studio 工具链中通过；完整 `testDebugUnitTest`、lint、双 ABI Release APK、签名和真机矩阵仍由 CI/发布流程验证。生成的 `.so` 与 APK 均不纳入版本控制。

### 迁移期旧版构建

**本地手工构建 EXE：**

```bash
npm run build
```

产物将生成在 `dist/fit-tool.exe`。

旧 Web/Node 构建继续作为迁移参考；它不会被打包进 Android 原生 APK，也不得作为原生 Release 产物。

## 发布、校验与项目文档

架构与实施依据：

- [Windows + Android + Rust 核心架构规格](docs/superpowers/specs/2026-07-27-native-windows-android-rust-core-design.md)
- [Rust 共享核心实施计划](docs/superpowers/plans/2026-07-27-rust-activity-fit-core.md)
- [Windows 原生便携版实施计划](docs/superpowers/plans/2026-07-27-windows-native-portable.md)
- [Android 原生客户端实施计划](docs/superpowers/plans/2026-07-27-android-native-app.md)
- [CI、签名与发布实施计划](docs/superpowers/plans/2026-07-27-ci-signing-release.md)

公开策略与边界见 [隐私说明](PRIVACY.md)、[安全策略](SECURITY.md)、[代码签名策略](CODE_SIGNING.md) 和 [第三方依赖说明](THIRD-PARTY-NOTICES.txt)。

### 本地质量门

当前发布脚本要求仓库中已有经审查的 `core-rust/Cargo.lock`，不会在 CI 或发布 workflow 中临时生成。准备好固定工具链后，可分别执行：

```bash
cargo test --manifest-path core-rust/Cargo.toml --all-features --locked
```

```powershell
pwsh -NoProfile -File scripts/build-rust-windows.ps1 -Configuration Release
dotnet test native-windows/FitGenerator.Native.Tests/FitGenerator.Native.Tests.csproj -c Release
dotnet publish native-windows/FitGenerator.Native/FitGenerator.Native.csproj -c Release -r win-x64
```

```bash
bash scripts/build-rust-android.sh
./android/gradlew -p android --no-daemon testDebugUnitTest lintDebug assembleDebug
```

这些命令需要对应平台工具链；提交前的已执行验证和仍待 CI 覆盖的项目如下。

当前静态前置状态集中如下：

- 版本已对齐为 `1.0.0`：`package.json` / `package-lock.json`、Rust crate 和 Android `versionName` 使用同一版本；Windows release workflow 会从同一 tag 注入该版本。
- `core-rust/Cargo.lock` 已由固定 Rust 1.97.1 工具链生成并纳入版本控制；CI 和 release 均使用 `--locked`，不会临时改写解析结果。
- Windows Rust DLL、self-contained single-file EXE 和 `--self-test` 已在 Windows x64 主机通过；Windows 地图可用性测试通过 3/3。
- Android `compileDebugKotlin` 与 `compileDebugAndroidTestKotlin` 已通过。一次定向 JVM 测试运行在含中文的 Windows 工作区路径下发生测试类加载失败，因此完整 JVM 测试结果仍以 GitHub CI 为准。
- Windows `fit_generator_core.dll`、Android 两个 `libfit_generator_core.so`、APK/EXE、`release-output/` 与 SBOM 均为已忽略的构建或发布产物，不纳入源码提交；固定工具链的 CI/release 会重新生成并验证它们。
- 遗留 `.github/workflows/build.yml` 已移除，避免它在分支或 tag 上继续生成旧 Web 构件；`.github/workflows/ci.yml` 和 `.github/workflows/release.yml` 是当前唯一的原生质量门与发布入口。

### Tag Release 契约

`.github/workflows/release.yml` 只接受 `vMAJOR.MINOR.PATCH` tag，或对已存在 tag 的手动 dry-run；tag commit 还必须位于 `origin/main` 历史中。手动调用强制 `dry_run=true`，只构建、测试并上传明确未签名的诊断产物，不进入 `release` environment，不读取签名 secrets，也不创建 bundle、attestation 或 Release。正式发布前应在 GitHub 配置受保护的 `release` environment、仅允许主分支发布 tag 的 tag ruleset、必要审批，以及 [CODE_SIGNING.md](CODE_SIGNING.md) 中列出的 secrets。

发布矩阵固定为：

- Android 无秘密构建 job 先产生 `fit-generator-android-UNSIGNED.apk`；只有正式发布才进入受保护的汇总/签名 job。`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 四项 secret 全部存在且 `apksigner` 实际验证成功后，才生成 `fit-generator-android.apk`；任一缺失都阻止 GitHub Release，绝不回退 debug key。
- SignPath 已启用、受保护配置完整且返回有效 Authenticode 签名时使用 `fit-generator-windows-x64.exe`。
- SignPath 未获批、未启用、配置不全或签名失败时使用 `fit-generator-windows-x64-UNSIGNED.exe`，并在发布说明中包含状态与 SHA-256。

汇总 job 会从固定 artifact ID 下载两个平台产物，由固定版本的下载 action 检查 artifact digest，再复核产生 job 记录的文件 SHA-256。SPDX JSON SBOM 是对最终发布目录的二进制扫描，不声称代替 Cargo/NuGet/Gradle 锁文件审查；`THIRD-PARTY-NOTICES.txt` 用于补充已声明的直接依赖。随后生成 checksums 与 release metadata，并再次运行：

```powershell
pwsh -NoProfile -File scripts/verify-release.ps1 `
  -ReleaseDirectory release-output `
  -RequireAndroidSignature
```

Windows 为已签名文件时还必须增加 `-RequireWindowsSignature`。正式发布 job 会再次验证 tag、源码 commit、metadata、签名和目录布局，随后才为 EXE、APK、校验和与 SBOM 创建 GitHub provenance attestation，并发布固定附件。Provenance attestation 用于关联产物、workflow 与源码 commit，不是 Windows Authenticode 或 Android 应用签名的替代品；workflow 如发现同一 tag 已有 Release，会拒绝覆盖或追加未知附件。

首次创建发布 tag 前，仍应先确认新 `ci.yml` 在 GitHub 的 Rust、Windows 和 Android 三个 job 均真实通过，并完成受保护 `release` environment、Android release key 与可选 SignPath 配置。手动 dry-run 不读取签名 secrets，也不会创建 GitHub Release。

## 🔬 模拟算法

- **距离计算**：Haversine 球面距离公式。
- **确定性模拟**：相同请求、seed 和变体索引产生相同预览与 FIT 数据。
- **配速与心率**：按目标配速、静息心率和最大心率生成有界波动。
- **闭环与多圈**：根据路线模式闭合轨迹，并按圈数生成逐圈数据。
- **FIT 编码**：Rust 核心内置开源的最小 Activity 编码器，无 Windows Garmin SDK 运行时依赖。

## ⚠️ 免责声明

本工具仅供学习交流和运动科学研究使用。**严禁用于任何作弊、虚假打卡等违规行为。** 对于因不当使用造成的任何后果，开发者概不负责。

---

## 许可证

[MIT License](LICENSE)

欢迎提交 Issue 或 Pull Request 来完善本项目！
