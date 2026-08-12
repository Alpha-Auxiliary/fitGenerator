# Windows 与 Android 原生版设计

日期：2026-07-27

状态：已确认，等待用户审阅书面规格

## 1. 背景

现有项目包含三种运行形态：

- Web/Node 版负责地图绘制、预览和 FIT 生成。
- Windows 原生版使用 C#、WinForms、Mapsui 和 Garmin C# FIT SDK。
- Android 版是 WebView 外壳，必须连接一个正在运行的 Web 服务。

目标是把它收敛成两个可独立运行的原生应用：

- Windows 10/11 用户下载单个 EXE 后直接双击运行。
- Android 用户安装 APK 后直接运行。
- 除地图瓦片和地点搜索外，所有核心能力都在设备本地完成。
- Windows 和 Android 使用各自优化的原生界面。
- 两端共享同一个 Rust 核心，避免算法实现逐渐产生差异。

## 2. 产品目标

### 2.1 必须满足

- Windows 只发布一个自包含 EXE。
- Windows 不要求安装 .NET、Node.js、Rust、浏览器扩展或其他运行环境。
- Windows 不显示命令行窗口，不启动本地服务器，也不打开外部浏览器。
- Android 不依赖远程 Web 服务完成轨迹预览和 FIT 导出。
- 默认地图与地点搜索无需用户配置 Key。
- 高级设置允许配置其他地图服务、Key、坐标系和搜索服务。
- 断网时仍能打开最近保存的轨迹、预览数据并生成 FIT。
- Windows 和 Android 使用相同参数与随机种子时，得到相同的核心活动模型。
- 发布产物不包含 Garmin 专有 SDK，以满足开源分发和 SignPath 申请方向。

### 2.2 非目标

- 首版不提供自动更新后台服务。
- 首版不提供用户账户、云同步或在线轨迹存储。
- 首版不制作完整、通用的 FIT SDK。
- 首版不支持 Windows 7、macOS 或 iOS。
- 不提供向校园跑、运动平台或第三方服务自动上传数据的功能。
- 不实现设备身份伪造、平台检测绕过或任何作弊自动化。

本工具的定位保持为本地轨迹建模、FIT 格式学习和运动数据研究工具。

## 3. 总体架构

```text
┌────────────────────────────┐       ┌────────────────────────────┐
│ Windows 原生应用           │       │ Android 原生应用           │
│ C# / .NET 8 / WinForms     │       │ Kotlin / Jetpack Compose   │
│ Mapsui                      │       │ 原生地图组件               │
└─────────────┬──────────────┘       └─────────────┬──────────────┘
              │ P/Invoke                            │ JNI
              └────────────────┬────────────────────┘
                               │ C ABI
                    ┌──────────▼──────────┐
                    │ Rust 共享核心       │
                    │ 参数校验            │
                    │ 坐标与距离          │
                    │ 轨迹与多圈          │
                    │ 配速/心率模拟       │
                    │ FIT Activity 编码   │
                    └─────────────────────┘
```

Web/Node 版可以保留为算法迁移参考或独立在线版，但不再是 Windows 与 Android 的运行依赖。

## 4. 组件边界

### 4.1 Rust 核心

Rust workspace 至少包含以下模块：

- `domain`
  - 活动请求、轨迹点、圈、采样点、活动模型和导出结果。
- `validation`
  - 时间、坐标、配速、心率、圈数、导出份数、轨迹点数量和总距离校验。
- `geo`
  - Haversine 距离、WGS-84 坐标、轨迹闭合和坐标偏移。
- `simulation`
  - 多圈展开、圈间扰动、速度曲线、心率曲线和时间缩放。
- `fit`
  - 本项目所需的最小 FIT Activity 编码器。
- `ffi`
  - 稳定 C ABI、版本化 JSON、结果缓冲区和错误处理。

Rust 核心不依赖 UI、地图 SDK、文件选择器或平台存储。

### 4.2 Windows 应用

Windows 应用负责：

- WinForms 原生界面。
- Mapsui 地图、搜索结果、轨迹绘制和编辑。
- 高级地图设置。
- 最近轨迹与参数持久化。
- P/Invoke Rust 核心。
- 系统文件夹选择与 FIT/ZIP 写入。
- 单 EXE 自包含发布。

### 4.3 Android 应用

Android 应用负责：

- Jetpack Compose 原生界面。
- MapLibre Native Android 地图渲染、手势和轨迹编辑。
- 高级地图设置。
- 最近轨迹与参数持久化。
- JNI 调用 Rust 核心。
- Storage Access Framework 文件保存与系统分享。
- APK 内多 ABI 原生库打包。

Android 首版最低支持 API 23。发布 APK 至少包含：

- `arm64-v8a`：主流真机。
- `x86_64`：模拟器与测试。

除非实际用户需求证明必要，首版不包含 `armeabi-v7a`。

## 5. 核心数据模型

FFI 请求使用 UTF-8 JSON，并显式携带模式版本：

```json
{
  "schemaVersion": 1,
  "startTimeUtc": "2026-07-27T08:00:00Z",
  "points": [
    { "lat": 39.9042, "lng": 116.4074 }
  ],
  "paceSecondsPerKm": 360,
  "hrRest": 60,
  "hrMax": 180,
  "lapCount": 1,
  "variantIndex": 1,
  "seed": 123456789,
  "routeMode": "close_if_needed"
}
```

坐标进入 Rust 前必须已经转换为 WGS-84。

核心活动模型包含：

- 活动开始和结束时间。
- 总距离和总时长。
- 每圈的开始、结束、距离和时长。
- 每个采样点的相对时间、累计距离、速度、心率和 WGS-84 坐标。
- 实际使用的随机种子和算法版本。

随机种子由应用在创建导出任务时生成。核心使用项目内固定实现的 SplitMix64 伪随机算法，不依赖平台随机数实现。预览和最终导出复用同一个种子，从而保证预览结果与导出内容一致。批量导出根据批次种子和 `variantIndex` 确定性派生每份文件的种子。

模型返回前执行统一量化，消除不同 CPU 数学库带来的末位浮点差异：

- 时间量化到毫秒。
- 距离量化到厘米。
- 速度量化到毫米/秒。
- 心率使用整数 bpm。
- 坐标以 FIT semicircle 整数作为规范值，UI 所需经纬度由该整数反算。

黄金测试比较量化后的规范模型，因此 Windows 与 Android 可以做到逐字段完全一致。

## 6. FFI 设计

Rust 导出 C ABI，不直接向外暴露 Rust 结构体布局。

概念接口如下：

```c
FgResult* fg_preview(const uint8_t* request, size_t length);
FgResult* fg_generate_fit(const uint8_t* request, size_t length);
const uint8_t* fg_result_data(const FgResult* result);
size_t fg_result_length(const FgResult* result);
int32_t fg_result_code(const FgResult* result);
const char* fg_result_error(const FgResult* result);
void fg_result_free(FgResult* result);
uint32_t fg_core_api_version(void);
```

约束：

- `fg_preview` 返回 JSON 活动模型。
- `fg_generate_fit` 返回 FIT 二进制。
- 所有结果内存由 Rust 分配，并且只能由 `fg_result_free` 释放。
- 空指针、无效 UTF-8、无效 JSON 和不支持的版本返回结构化错误。
- 所有入口捕获 panic，panic 不得跨越 FFI 边界。
- API 版本独立于应用版本，发生不兼容变化时递增。
- Windows 和 Android 各自提供一个薄包装层，把原始接口转换为平台惯用的异常或结果类型。

## 7. 输入校验与资源限制

Rust 是最终校验边界，不能依赖 UI 控件限制。

首版限制：

- 轨迹点：2–50,000。
- 圈数：1–100。
- 纬度：`[-90, 90]`。
- 经度：`[-180, 180]`。
- 静息心率：30–120 bpm。
- 最大心率：100–220 bpm，且必须高于静息心率。
- 配速：60–3,600 秒/公里。
- 开始时间必须是有效 UTC 时间。
- 展开后的总采样点数量必须小于安全上限。

超过限制时返回明确、可操作的中文错误，不进行隐式截断。

批量导出由平台层调度，范围为 1–20。Rust 每次只接收一个活动请求并生成一个 FIT，平台不得并行发起无上限的生成任务。

## 8. 轨迹和模拟行为

### 8.1 坐标

- 核心内部只接受 WGS-84。
- 地图适配器声明自身坐标系：WGS-84、GCJ-02 或 BD-09。
- 绘制点在进入 Rust 前转换为 WGS-84。
- 从保存轨迹恢复到地图时执行反向转换。
- 自定义地图源必须由用户选择其坐标系，不能自动猜测。

### 8.2 轨迹模式

默认模式为 `close_if_needed`：

- 起点与终点距离小于 5 米时视为已经闭合。
- 否则在模型中增加返回起点的闭合段。
- UI 的距离显示必须调用 Rust 预览，不再自行计算一个不同的距离。

未来可以增加开放路线模式，但不属于首版范围。

### 8.3 多圈

- 先得到一圈的闭合基础轨迹，再展开多圈。
- 每圈可以应用确定性的 5–10 米整体扰动。
- 圈间连接距离计入活动总距离。
- 每圈都生成独立 lap 数据。

### 8.4 配速与心率

- 目标总时长由总距离和用户配速决定。
- 速度使用长波、短波与小幅基础因子生成自然波动。
- 对分段时长统一缩放，确保最终总时长严格匹配目标。
- 心率由热身、稳定、冲刺三个阶段与瞬时强度组合生成。
- 心率变化使用平滑追踪和小幅抖动，并限制在用户区间内。
- 所有随机行为只来自显式种子。

## 9. FIT 编码

Rust 只实现本项目需要的 FIT Activity 子集：

- 14 字节 FIT 文件头。
- 文件头 CRC 与文件尾 CRC。
- Definition Message 和 Data Message。
- `file_id`。
- `device_info`。
- 活动开始和结束 `event`。
- 每个采样点的 `record`。
- 每圈的 `lap`。
- `session`。
- `activity`。

支持的字段包括：

- 时间戳。
- WGS-84 经纬度 semicircle。
- 累计距离。
- 速度。
- 心率。
- 总距离、总时间、平均速度和圈数。

编码器遵守以下原则：

- 消息按活动语义顺序写入。
- Definition Message 必须先于对应 Data Message。
- 明确处理 little-endian、字段 scale/offset 和无效值。
- 不复制或打包 Garmin 专有 SDK 源码或二进制。
- 不扩展为通用 FIT Profile 生成器。

测试中使用 MIT 许可的 `fitparser` 作为独立解码器。它只作为开发和测试依赖，不进入发布产物。

## 10. 地图、搜索与设置

### 10.1 默认体验

- 默认地图无需 Key。
- 默认地点搜索无需 Key。
- Windows 默认使用 Mapsui 的 OpenStreetMap 图层。
- Android 默认使用 MapLibre Native Android 和随应用打包的 OpenStreetMap raster style。
- 第一次启动不显示配置向导。
- 地图或搜索联网失败时，核心功能仍然可用。
- 所有默认服务请求必须包含明确的应用标识、显示服务归属，并遵守服务方的频率和缓存规则。

### 10.2 高级地图设置

每个平台都提供相同概念的设置：

- 地图源名称。
- XYZ URL 模板或平台适配器类型。
- 可选 API Key。
- Key 在 URL、Header 或查询参数中的放置方式。
- 坐标系。
- 最大缩放级别。
- 地点搜索服务地址。

平台可以提供不同的原生地图实现，但必须遵守统一的 WGS-84 边界。

### 10.3 Key 存储

- Windows 使用 DPAPI 保护 Key。
- Android 使用 Android Keystore 保护 Key。
- UI 默认隐藏 Key。
- Key 只发送给用户选择的地图或搜索服务。
- 提供“测试连接”和“恢复默认地图”。

## 11. 本地持久化

自动保存：

- 最近一次轨迹。
- 地图视口。
- 心率、配速、圈数和导出参数。
- 当前地图源。
- 当前预览随机种子。

不自动保存：

- 已生成 FIT 的完整历史。
- 搜索历史。
- 用户未确认保存的批量导出文件。

本地数据使用版本化结构。新版本迁移失败时保留原文件备份，并回退默认设置，不得阻止应用启动。

## 12. 原生界面设计

### 12.1 设计主题

产品视觉主题是“校园跑道边的计时台”：

- 地图和轨迹是主角。
- 参数像计时记录，而不是通用表单卡片。
- 界面使用跑道标线、地图制图和秒表数据的视觉语言。
- 不使用大面积渐变、发光效果和堆叠卡片。

### 12.2 颜色

- 场地纸白：`#F3F6F1`
- 记分牌黑：`#16221C`
- 跑道朱红：`#D14E39`
- 配速青蓝：`#1D6F78`
- 心率莓红：`#C2385A`
- 边线灰绿：`#B8C3B8`

颜色角色必须语义稳定：轨迹永远使用跑道朱红，配速永远使用青蓝，心率永远使用莓红。

### 12.3 字体

Windows：

- 主字体：Segoe UI Variable。
- 中文回退：Microsoft YaHei UI。

Android：

- 主字体：Roboto。
- 中文回退：系统 Noto Sans CJK。

时间、距离、配速和心率数据启用等宽数字特性。应用不依赖在线字体。

### 12.4 标志性元素

两端共享“路线计时带”：

```text
● 3.42 km ━━━━━━━ 预计 20:31 ━━━━━━━ 6'00"/km ━━━━━━━ 154 bpm
```

- Windows 固定在地图底部。
- Android 作为底部抽屉的固定标题栏。
- 预览时路线标记和计时带同步推进。
- 这是界面唯一显著运动效果。
- 系统启用减少动态效果时，改为离散更新，不使用连续动画。

### 12.5 Windows 布局

```text
┌ 校园跑 FIT                         设置 ┐
├──────────────┬────────────────────────┤
│ 1 路线       │                        │
│ 地图源/搜索  │         地图           │
│              │      轨迹与编辑点      │
│ 2 模拟       │                        │
│ 圈数/心率    │                        │
│              ├────────────────────────┤
│ 3 导出       │ 路线计时带             │
│ 时间/份数    │ 距离 · 时间 · 配速 · HR│
│ [生成 FIT]   │                        │
└──────────────┴────────────────────────┘
```

- 左侧栏约 380 px。
- “路线、模拟、导出”编号代表真实操作顺序。
- 地图占据剩余空间。
- 绘制、撤销、定位和清空操作靠近地图。
- 主操作“生成 FIT”只使用一次跑道朱红强调。

### 12.6 Android 布局

```text
┌ 校园跑 FIT                    设置 ┐
│                                  │
│              地图                │
│                           [定位]  │
│                           [撤销]  │
│                           [绘制]  │
├ ● 3.42 km · 20:31 · 6'00" ──────┤
│       上拉：模拟参数与导出        │
└──────────────────────────────────┘
```

- 地图占主区域。
- 绘制、撤销和定位使用悬浮操作按钮。
- 参数、预览和导出位于底部抽屉。
- 抽屉内容仍按“模拟参数、预览、导出”的真实顺序排列。
- 不把 Windows 侧栏直接压缩到手机屏幕。

### 12.7 文案与可访问性

- 控件使用用户能理解的动作名称，例如“生成 FIT”“保存到文件夹”。
- 错误提示说明发生了什么以及如何修复。
- 空轨迹状态直接引导用户开始绘制。
- 所有交互控件支持键盘焦点或 Android 无障碍语义。
- 颜色不是唯一状态提示。
- 点击区域满足平台最低触控尺寸。

## 13. 文件导出

Windows：

- 用户选择导出目录。
- 单份导出为 `run.fit`。
- 多份导出为 `run_1.fit`、`run_2.fit` 等。
- 先在内存中生成整个批次。
- 全部生成成功后写入临时文件，再原子移动到目标文件名。

Android：

- 单份文件通过 Storage Access Framework 保存或分享。
- 多份文件可以逐份保存，或生成 ZIP 后分享。
- 不申请宽泛文件系统权限。

任何失败都保留当前轨迹、参数和种子，允许用户修正后重试。

## 14. 错误模型

Rust 错误分为：

- `invalid_input`
- `unsupported_schema`
- `resource_limit`
- `simulation_failed`
- `fit_encoding_failed`
- `internal_error`

平台错误分为：

- `map_unavailable`
- `search_unavailable`
- `settings_unavailable`
- `file_permission_denied`
- `disk_full`
- `native_core_unavailable`
- `native_core_version_mismatch`

平台包装层负责把错误码转换为一致的中文文案。不得直接向用户显示 Rust panic、堆栈或原始 JNI/P/Invoke 错误。

## 15. 隐私与安全

- 不包含遥测、广告、登录和后台服务。
- 只有用户主动搜索地点时才向搜索服务发送查询。
- 地图 Key 只发送到对应服务。
- 不上传轨迹、运动参数或生成文件。
- 仓库提供明确的隐私政策和代码签名政策。
- GitHub 与 SignPath 维护者账号必须启用 MFA。
- Rust FFI 对输入大小进行限制并执行严格解析。
- 构建生成 SBOM、依赖许可证报告和 SHA-256 校验值。

## 16. Windows 单文件发布

Windows 目标为 `win-x64`、.NET 8 自包含单文件：

- `PublishSingleFile=true`
- `SelfContained=true`
- `IncludeNativeLibrariesForSelfExtract=true`
- 不要求管理员权限。
- Rust DLL 和原生依赖嵌入 EXE。
- 启动时由 .NET 单文件机制解压到系统临时目录。
- 应用设置写入 `%LOCALAPPDATA%`，不写程序所在目录。

验收时必须在未安装开发工具和 .NET Runtime 的干净 Windows 10/11 虚拟机中验证。

## 17. Android 发布

- 使用 Release APK。
- Rust `.so` 位于 APK 的标准 ABI 目录。
- Android 长期签名密钥由项目所有者生成。
- 签名密钥和密码仅保存于 GitHub Actions Secrets。
- 同一应用 ID 的所有后续版本使用相同签名密钥。
- 地图需要 `INTERNET` 权限。
- 首版不申请定位权限和宽泛存储权限。

## 18. GitHub Actions 与签名

CI 流程：

```text
格式化、静态检查和许可证检查
    ↓
Rust 单元测试、黄金测试和 FFI 测试
    ↓
构建 win-x64 Rust DLL
构建 Android arm64-v8a/x86_64 Rust .so
    ↓
构建 Windows 单 EXE
构建 Android Release APK
    ↓
产物 FIT 冒烟测试
    ↓
生成 SHA-256、SBOM、许可证清单
    ↓
可选 SignPath 人工审批和 Windows 签名
    ↓
创建 GitHub Release
```

签名策略：

- 首次公开 Release 可以提供未签名 EXE和 SHA-256。
- 项目有公开 Release、维护记录和完整文档后申请 SignPath Foundation。
- CI 同时支持签名和未签名路径。
- SignPath 获批后，签名 EXE 作为推荐下载。
- 不在仓库或 GitHub Secrets 中保存 Windows 代码签名私钥。
- 有效 Authenticode 签名不等于立即获得 SmartScreen 信誉，文档不得承诺完全消除首次警告。

## 19. 测试策略

### 19.1 Rust 单元测试

- Haversine 距离。
- 坐标转换。
- 轨迹闭合阈值。
- 多圈展开。
- 确定性扰动。
- 配速总时长。
- 心率边界。
- FIT semicircle、scale、offset 和 CRC。
- 所有输入限制与错误码。

### 19.2 属性与模糊测试

- 有效输入不会产生 NaN、Infinity 或越界字段。
- 输出累计距离和时间单调不减。
- 心率始终位于配置范围。
- FFI 无效 JSON 和随机字节不会导致越界访问或 panic 穿透。
- 任意成功生成的 FIT 都通过独立解析。
- SplitMix64、量化规则和种子派生在所有编译目标上产生相同结果。

### 19.3 黄金数据测试

仓库保存一组小型、可审查的 JSON 测试向量：

- 开放路线。
- 已闭合路线。
- 单圈和多圈。
- 极短合法路线。
- 中国境内坐标转换样例。
- 不同配速和心率区间。

每个向量固定随机种子并断言：

- 核心活动模型。
- 总距离和总时间。
- lap 边界。
- record 数量。
- 解码后的 FIT 语义字段。

不要求 FIT 二进制逐字节稳定，但要求解码后的语义稳定。编码格式优化可以改变二进制布局，不能改变活动内容。

### 19.4 平台集成测试

Windows：

- P/Invoke 版本握手。
- 预览和 FIT 生成。
- 单文件启动。
- 文件原子写入。
- DPAPI Key 读写。

Android：

- JNI 版本握手。
- 预览和 FIT 生成。
- APK ABI 包含检查。
- Storage Access Framework 保存。
- Android Keystore Key 读写。

### 19.5 人工冒烟测试

- Windows 10/11 干净虚拟机。
- Android 真机。
- Android x86_64 模拟器。
- 无网络。
- 地图服务不可用。
- 错误 Key。
- 无文件权限。
- 磁盘空间不足。
- 大轨迹和多圈上限。

## 20. 验收标准

- Windows 用户只下载一个 EXE 即可运行。
- Windows 不出现控制台窗口、浏览器或本地服务器。
- Windows 不需要安装运行库。
- Android 不连接项目自建服务器即可完成核心流程。
- 默认地图零配置可用。
- 断网时可打开最近轨迹、预览并导出。
- 相同请求与种子产生相同核心活动模型。
- 预览与实际导出一致。
- 所有 FIT 通过 CRC 和独立解码检查。
- Windows 与 Android 的解码后活动语义一致。
- 发布产物不包含 Garmin 专有 SDK。
- CI 的格式化、静态检查、测试、许可证检查和产物验证全部通过。
- GitHub Release 同时包含产物、SHA-256、SBOM、许可证清单和版本说明。

## 21. 迁移边界

迁移期间：

- `src/fitCore.js` 与 `native-windows/FitCore.cs` 只作为行为参考。
- 新功能只进入 Rust 核心，避免继续扩展旧的重复算法。
- Windows UI 可以逐步复用现有 WinForms/Mapsui 代码。
- Android WebView 外壳由新原生应用替换。
- Web/Node 版独立保留，不与原生发布流程耦合。

Rust 核心和两个平台包装层通过黄金测试达到功能等价后，原生发布不再调用旧 JavaScript 或 C# 算法。

## 22. 参考资料

- [Garmin FIT Protocol](https://developer.garmin.com/fit/protocol/)
- [Rust FFI](https://doc.rust-lang.org/nightly/nomicon/ffi.html)
- [.NET 单文件部署](https://learn.microsoft.com/en-us/dotnet/core/deploying/single-file/overview)
- [Android ABI 管理](https://developer.android.com/ndk/guides/abis.html)
- [fitparse-rs](https://github.com/stadelmanma/fitparse-rs)
- [SignPath Foundation](https://signpath.org/)
- [SignPath OSS 条件](https://signpath.org/terms.html)
- [Windows 代码签名选项](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options)
