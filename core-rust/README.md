# fit-generator-core

`fit-generator-core` 是 Windows 与 Android 共用的 Rust 2024 活动生成核心。它接收 UTF-8 JSON 请求，执行严格校验和确定性模拟，输出预览 JSON 或 FIT Activity 字节。crate 同时生成 Rust `rlib` 和平台可加载的 `cdylib`。

本目录只包含核心算法及边界适配，不负责地图、地点搜索、UI、网络访问、凭据、文件选择或文件保存。

> 验证状态：本文命令是供开发者和 CI 后续执行的说明。当前 WSL 会话依用户要求没有运行构建、测试、Cargo、格式化或依赖安装；本文不声称这些命令已经通过。

## 稳定版本

| 契约 | 常量 | 当前值 | 变更含义 |
| --- | --- | ---: | --- |
| 请求 schema | `SCHEMA_VERSION` | `1` | JSON 请求字段或校验语义出现不兼容变化时递增 |
| C/JNI API | `CORE_API_VERSION` | `1` | 原生调用约定出现不兼容变化时递增 |
| 模拟算法 | `ALGORITHM_VERSION` | `1` | 相同输入的确定性结果发生有意变化时递增 |

当前请求使用 camelCase JSON，`routeMode` 仅支持 `close_if_needed`：

```json
{
  "schemaVersion": 1,
  "startTimeUtc": "2026-07-27T08:00:00Z",
  "points": [
    { "lat": 39.9042, "lng": 116.4074 },
    { "lat": 39.9052, "lng": 116.4084 }
  ],
  "paceSecondsPerKm": 360.0,
  "hrRest": 60,
  "hrMax": 180,
  "lapCount": 1,
  "variantIndex": 1,
  "seed": 42,
  "routeMode": "close_if_needed"
}
```

`startTimeUtc` 必须是以 `Z` 结尾的有效 UTC RFC 3339 时间。核心限制轨迹点为 2–50,000、圈数为 1–100、展开后采样点不超过 500,000；纬度、经度、配速和心率也会在进入模拟前校验。

## Rust API

crate 根公开以下主要入口：

- `parse_and_validate(&[u8]) -> Result<ValidatedRequest, CoreError>`
- `build_activity_model(&ValidatedRequest) -> Result<ActivityModel, CoreError>`
- `preview_json(&[u8]) -> Result<Vec<u8>, CoreError>`
- `generate_fit(&[u8]) -> Result<Vec<u8>, CoreError>`
- `fit::encode_activity(&ActivityModel) -> Result<Vec<u8>, CoreError>`

稳定错误码为：成功 `0`、输入无效 `100`、schema 不支持 `101`、资源上限 `102`、模拟失败 `200`、FIT 编码失败 `300`、内部错误 `900`。

## 构建与检查

以下命令均从仓库根目录执行。

主机 release 构建：

```bash
cargo build --manifest-path core-rust/Cargo.toml --release --locked
```

完整核心检查：

```bash
cargo fmt --manifest-path core-rust/Cargo.toml --check
cargo clippy --manifest-path core-rust/Cargo.toml --all-targets --all-features --locked -- -D warnings
cargo test --manifest-path core-rust/Cargo.toml --all-features --locked
```

Windows x64 构建应在安装了 MSVC 构建工具的 Windows 主机或 Windows CI runner 上执行：

```powershell
rustup target add x86_64-pc-windows-msvc
cargo build --manifest-path core-rust/Cargo.toml --release --locked --target x86_64-pc-windows-msvc
```

目标动态库：

```text
core-rust/target/x86_64-pc-windows-msvc/release/fit_generator_core.dll
```

Android 构建需要已安装的 Android SDK/NDK、两个 Rust target 和固定版本的 `cargo-ndk`。推荐从仓库根目录运行项目脚本；脚本只使用仓库相对路径，并在结束前检查两个目标库均存在且非空：

```bash
bash scripts/build-rust-android.sh
```

脚本不会自动安装或下载 Rust target、`cargo-ndk` 或 Android NDK。它要求 `cargo-ndk 3.5.4`，并要求 `ANDROID_NDK_HOME`、`ANDROID_NDK_ROOT`、`ANDROID_NDK_PATH` 或 `NDK_HOME` 之一指向含 `source.properties` 和当前主机 LLVM toolchain 的 NDK 根目录；缺少前置条件时会在构建前退出。脚本使用仓库内已审阅的 `core-rust/Cargo.lock` 并以 `--locked` 构建，不会临时改写依赖解析。它先在自建的临时目录构建并验证两个 `.so`，两者均成功后才成组替换 `jniLibs` 中的目标库；中断或失败会保留旧库，且只清理脚本自建的临时目录。需要时由开发者明确执行一次：

```bash
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 3.5.4 --locked
```

前置条件就绪后的等价手工构建命令为：

```bash
cd core-rust
cargo ndk \
  --target arm64-v8a \
  --target x86_64 \
  --platform 23 \
  --output-dir ../android/app/src/main/jniLibs \
  build --release --locked --features android-jni
```

预期输出位置分别为：

```text
android/app/src/main/jniLibs/arm64-v8a/libfit_generator_core.so
android/app/src/main/jniLibs/x86_64/libfit_generator_core.so
```

`arm64-v8a` 对应 Rust target `aarch64-linux-android`，`x86_64` 对应 `x86_64-linux-android`。这些 `.so` 是本地/CI 构建产物并由仓库忽略，不应直接提交。

## C ABI

稳定头文件是 [`include/fit_generator_core.h`](include/fit_generator_core.h)。它只暴露不完整类型 `FgResult` 和以下 8 个符号：

```c
FgResult *fg_preview(const uint8_t *request, size_t length);
FgResult *fg_generate_fit(const uint8_t *request, size_t length);
const uint8_t *fg_result_data(const FgResult *result);
size_t fg_result_length(const FgResult *result);
int32_t fg_result_code(const FgResult *result);
const char *fg_result_error(const FgResult *result);
void fg_result_free(FgResult *result);
uint32_t fg_core_api_version(void);
```

所有权规则：

- `request` 只在入口调用期间借用；当 `length > 0` 时，调用方必须提供至少 `length` 个可读字节。空指针配合非零长度返回错误 `100`；超过平台 `isize::MAX` 的长度在读取指针前返回资源上限错误 `102`；长度为零时按空 JSON 输入处理，不读取指针。
- `fg_preview` 和 `fg_generate_fit` 返回的每个非空 `FgResult *` 都由调用方独占，并且必须恰好调用一次 `fg_result_free`。`fg_result_free(NULL)` 是 no-op；重复释放或传入非本库创建的指针属于调用方错误。
- `fg_result_data`、`fg_result_error` 返回的指针都是借用值，只在对应 `FgResult` 释放前有效，不得由调用方释放或修改。空 payload 的 data 指针为 `NULL`、长度为 `0`；成功结果的 error 是空 C 字符串。
- 对空 result 调用 accessor 时，data/error 返回 `NULL`、长度返回 `0`、code 返回 `900`。
- ABI 捕获 Rust unwind；可恢复的 panic 转换为 code `900` 和 `核心发生内部错误`。这不使无效非空指针或重复释放变得安全。

## Android JNI

启用 Cargo feature `android-jni` 后，Android target 导出 Kotlin object `com.alphaauxiliary.fitgenerator.core.NativeCore` 对应的接口：

```kotlin
nativeApiVersion(): Int
nativePreview(requestUtf8: ByteArray): ByteArray
nativeGenerateFit(requestUtf8: ByteArray): ByteArray
nativeLastError(): String
```

对应的 Android-only 导出符号是：

- `Java_com_alphaauxiliary_fitgenerator_core_NativeCore_nativeApiVersion`
- `Java_com_alphaauxiliary_fitgenerator_core_NativeCore_nativePreview`
- `Java_com_alphaauxiliary_fitgenerator_core_NativeCore_nativeGenerateFit`
- `Java_com_alphaauxiliary_fitgenerator_core_NativeCore_nativeLastError`

JNI 层直接调用安全 Rust API，不经过 C 裸指针，也不缓存 `JNIEnv`。错误状态使用当前线程独立的 `thread_local RefCell<String>`：

- preview/generate 成功后将 last error 清空为 `""`；
- CoreError 保留其稳定错误码，JNI 转换错误或 panic 使用 `900` 和 `核心发生内部错误`；
- 错误字符串是 `{"code":<i32>,"message":"..."}` 形式的 JSON，不包含堆栈或原始 JNI 错误；
- preview/generate 失败返回空 `ByteArray`，仅当 JVM 无法创建该空数组时返回 `null`；
- `nativeLastError` 读取后不清空；如果 JVM 无法创建 Java 字符串，则返回 `null`。

## 规范化单位与确定性

活动模型在序列化前使用固定单位和整数表示：

| 字段 | 类型与单位 | 规则 |
| --- | --- | --- |
| 时间、持续时间 | `u64` 毫秒 | 秒乘 1,000 后四舍五入；FIT timestamp 使用完整秒 |
| 累计距离 | `u64` 厘米 | 米乘 100 后四舍五入 |
| 速度 | `u32` 毫米/秒 | 米/秒乘 1,000 后四舍五入 |
| 心率 | `u8` 次/分钟（bpm） | 模拟结果四舍五入，并限制在请求的静息/最大心率范围内 |
| 经纬度 | `i32` FIT semicircles | 角度乘 `2^31 / 180` 后四舍五入；`±180°` 使用 FIT 的共享表示，避开 FIT 保留的 `i32::MAX` 无效哨兵值 |

随机序列使用仓库内的 SplitMix64、请求 `seed` 和 `variantIndex`。在 schema/API/algorithm version 相同的前提下，相同有效输入应产生相同的规范化活动模型与 FIT 字节；调整确定性输出必须同步递增算法版本并更新兼容性测试。

## FIT Activity 子集

编码器写入 14 字节 FIT header、header CRC、data records 和完整文件 CRC。当前只支持跑步 Activity 所需的以下全局消息：

- `file_id`
- `device_info`
- `event`（timer start / stop_all）
- `record`（timestamp、position、heart rate、distance、speed）
- `lap`
- `session`（running / generic）
- `activity`（manual）

这是有意限定的 FIT 子集，不是完整 FIT Profile SDK。实现使用 development manufacturer 标识，不包含 Garmin FIT SDK 的源代码、生成代码、库或二进制；`Cargo.toml` 也没有 Garmin SDK 依赖。

## 隐私与安全边界

- 核心只处理调用方提供的内存字节，不发起网络请求、不读写文件、不记录遥测、不访问地图服务或 API Key。
- 路线、开始时间和心率属于可能敏感的数据；宿主应用负责权限、日志脱敏、安全存储、导出位置和用户同意。
- 输入在分配大数组前经过数值与资源上限校验，但调用方仍需限制外部请求大小并妥善处理 code `102`。
- C ABI 的非空指针有效性、结果只释放一次以及 DLL 生命周期由宿主负责；JNI 的 Java 局部引用与线程归属由 JVM/JNI 调用约定负责。
- panic 边界用于避免 Rust unwind 穿过 FFI/JNI，不替代进程隔离，也不能恢复内存耗尽导致的进程终止或调用方触发的未定义行为。

直接依赖及许可证核对状态见 [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md)。
