# FIT 轨迹生成工具 - Keep校园跑

[![Build and Release EXE](https://github.com/Alpha-Auxiliary/fitGenerator/actions/workflows/build.yml/badge.svg)](https://github.com/Alpha-Auxiliary/fitGenerator/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Alpha-Auxiliary/fitGenerator?label=%E4%B8%8B%E8%BD%BD&color=blue)](https://github.com/Alpha-Auxiliary/fitGenerator/releases/latest)

一个基于 Web 的跑步轨迹绘制工具，可以在地图上自由绘制跑步路线，并生成符合 Garmin 标准的 FIT 运动文件。

![功能预览](example.png)
## ✨ 功能特点

- 🗺️ **地图绘制**：在地图上自由手绘跑步轨迹
- 🔍 **地点搜索**：支持中文搜索，快速定位到任何地点
- ⚙️ **参数配置**：自定义心率、配速、圈数等参数
- 📊 **数据预览**：实时预览配速和心率曲线
- 📥 **批量导出**：支持一次生成多个不同时间的 FIT 文件
- 🎨 **精美界面**：支持深色/浅色主题，响应式设计
- 📦 **免安装运行**：提供打包好的单文件 EXE，双击即用，自动开启浏览器

## 🚀 快速开始

### 方式 1：直接下载（推荐）
如果你是 Windows 用户，无需安装 Node.js 环境：
1. 前往 [Releases](https://github.com/Alpha-Auxiliary/fitGenerator/releases/latest) 页面。
2. 下载最新的 `fit-tool.exe`。
3. 双击运行，程序会自动在默认浏览器中打开工具界面。

### 方式 2：开发者模式
1. **克隆仓库**
   ```bash
   git clone https://github.com/Alpha-Auxiliary/fitGenerator.git
   cd fitGenerator
   ```
2. **安装依赖**
   ```bash
   npm install
   ```
3. **启动服务**
   ```bash
   npm run dev
   ```
4. **访问应用**
   打开浏览器访问 `http://localhost:8080`

## 🛠️ 技术栈

- **后端**：[Express.js](https://expressjs.com/), [@garmin/fitsdk](https://www.npmjs.com/package/@garmin/fitsdk)
- **编译/打包**：[@vercel/ncc](https://github.com/vercel/ncc), [pkg](https://github.com/vercel/pkg)
- **地图**：可切换百度地图、高德地图、谷歌地图
- **图表**：[Chart.js](https://www.chartjs.org/)
- **搜索**：跟随当前地图源使用对应地点搜索

## 🗺️ 地图源配置

本地运行时，在项目根目录的 `.env` 中配置地图源和对应 Key：

- `MAP_DEFAULT_PROVIDER`：默认地图源，支持 `google`、`baidu`、`amap`，默认 `google`
- `BAIDU_MAP_AK`：百度地图 AK
- `AMAP_MAP_KEY`：高德地图 Key
- `AMAP_SECURITY_JS_CODE`：高德安全密钥（如控制台开启了安全密钥校验）
- `GOOGLE_MAPS_API_KEY`：Google Maps JavaScript API Key

页面左侧“地图源”下拉框可以在百度地图、高德地图、谷歌地图之间切换。

GitHub Actions 中使用同名配置：

- Repository variable：`MAP_DEFAULT_PROVIDER`
- Repository secrets：`BAIDU_MAP_AK`、`AMAP_MAP_KEY`、`AMAP_SECURITY_JS_CODE`、`GOOGLE_MAPS_API_KEY`

自动构建时，GitHub Actions 会用这些 Secrets 生成临时的 `build-map-config.json` 并打包进 EXE。该文件已被 `.gitignore` 忽略，不会提交到仓库。这样别人下载你构建的程序时可以直接使用你配置的地图 Key，而 GitHub 仓库源码中不会出现 Key 明文。

注意：地图 JavaScript SDK 的 Key 必须下发到浏览器才能加载地图，所以运行程序的人仍可能通过浏览器开发者工具或网络请求看到这些 Key。请在百度/高德/Google 控制台为 Key 配置域名、IP、额度、API 白名单等限制。

## 📖 使用指南

1. **定位地点**：在搜索框中输入地点（如"天安门"），选择结果自动跳转。
2. **绘制轨迹**：点击 **"自由手绘"**，按住左键拖动鼠标画出路线。
3. **设置参数**：配置圈数、心率范围、导出份数等。
4. **预览与导出**：
   - 点击 **"预览曲线"** 查看模拟数据。
   - 点击 **"生成 FIT 文件"** 批量下载结果。

## ⚙️ 构建与分发

本项目配置了 **GitHub Actions** 自动化流水线：
- **自动构建**：每当代码推送到 `main` 分支时，会自动生成最新的 EXE。
- **自动发布**：推送以 `v` 开头的标签（如 `git tag v1.0.0`）会触发正式 Release。

**本地手工构建 EXE：**
```bash
npm run build
```
产物将生成在 `dist/fit-tool.exe`。

## 🔬 模拟算法

- **距离计算**：Haversine 球面距离公式。
- **配速模拟**：基础配速 + (长波+短波) 正弦波动，真实还原运动体力起伏。
- **心率模拟**：基于瞬时强度 (Effort) 动态计算目标心率，并添加心率抖动 (Jitter)。
- **轨迹噪声**：多圈模式下提供 5-10 米随机偏移，防止轨迹重叠过于僵硬。

## ⚠️ 免责声明

本工具仅供学习交流和运动科学研究使用。**严禁用于任何作弊、虚假打卡等违规行为。** 对于因不当使用造成的任何后果，开发者概不负责。

---

## 许可证
[MIT License](LICENSE)

欢迎提交 Issue 或 Pull Request 来完善本项目！
