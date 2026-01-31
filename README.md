# FIT 轨迹生成工具 - 校园跑

一个基于 Web 的跑步轨迹绘制工具，可以在地图上自由绘制跑步路线，并生成符合 Garmin 标准的 FIT 运动文件。

## 功能特点

- 🗺️ **地图绘制**：在地图上自由手绘跑步轨迹
- 🔍 **地点搜索**：支持中文搜索，快速定位到任何地点
- ⚙️ **参数配置**：自定义心率、配速、圈数等参数
- 📊 **数据预览**：实时预览配速和心率曲线
- 📥 **批量导出**：支持一次生成多个不同时间的 FIT 文件
- 🎨 **精美界面**：支持深色/浅色主题，响应式设计
- 📱 **移动端适配**：完美支持手机和平板设备

## 截图

![界面预览](https://via.placeholder.com/800x600?text=FIT+Trajectory+Generator+Preview)

## 技术栈

### 后端
- [Express.js](https://expressjs.com/) - Web 框架
- [@garmin/fitsdk](https://www.npmjs.com/package/@garmin/fitsdk) - FIT 文件编码
- [dotenv](https://www.npmjs.com/package/dotenv) - 环境变量管理

### 前端
- [Leaflet](https://leafletjs.com/) - 地图库
- [Leaflet-Geoman](https://geoman.io/leaflet-geoman/) - 地图绘图工具
- [Chart.js](https://www.chartjs.org/) - 图表库
- [OpenStreetMap Nominatim](https://nominatim.org/) - 地点搜索 API

## 安装

### 前置要求

- Node.js >= 16.0.0
- npm >= 8.0.0

### 步骤

1. 克隆仓库
```bash
git clone <repository-url>
cd 校园跑
```

2. 安装依赖
```bash
npm install
```

3. 配置环境变量（可选）
```bash
cp .env.example .env
# 编辑 .env 文件设置自定义配置
```

4. 启动服务
```bash
npm start
```

5. 访问应用
打开浏览器访问 `http://localhost:3000`

## 使用指南

### 1. 定位地点
在搜索框中输入地点名称（如"清华大学"、"天安门"），选择搜索结果自动定位。

### 2. 绘制轨迹
1. 点击 **"自由手绘"** 按钮进入绘图模式
2. 在地图上按住鼠标左键并拖动绘制跑步轨迹
3. 绘制完成后自动退出绘图模式
4. 可以点击已绘制的轨迹进行编辑或拖拽

### 3. 配置参数

#### 心率设置
- **静息心率**：设置基准心率（默认 60 bpm）
- **最大心率**：设置最大心率（默认 180 bpm）

#### 跑步设置
- **圈数**：设置重复跑的圈数（默认 1 圈）
- **导出份数**：设置生成的 FIT 文件数量（1-10 份）

#### 导出配置
- 每份可以设置不同的开始时间和配速
- 配速格式：分 秒/公里（如 6'30" 表示 6 分 30 秒每公里）

### 4. 预览数据
点击 **"预览曲线"** 按钮，查看生成的配速和心率曲线。

### 5. 导出 FIT 文件
点击 **"生成 FIT 文件"** 按钮，浏览器将自动下载生成的 FIT 文件。

## API 文档

### POST /api/preview
生成预览数据

**请求体：**
```json
{
  "startTime": "2024-01-01T08:00:00.000Z",
  "points": [
    {"lat": 39.9042, "lng": 116.4074},
    {"lat": 39.9052, "lng": 116.4084}
  ],
  "paceSecondsPerKm": 360,
  "hrRest": 60,
  "hrMax": 180,
  "lapCount": 1
}
```

**响应：**
```json
{
  "totalDistanceMeters": 1500,
  "totalDurationSec": 540,
  "samples": [
    {
      "timeSec": 0,
      "distance": 0,
      "speed": 4.17,
      "heartRate": 95,
      "lat": 39.9042,
      "lng": 116.4074
    }
  ]
}
```

### POST /api/generate-fit
生成并下载 FIT 文件

**请求体：**
```json
{
  "startTime": "2024-01-01T08:00:00.000Z",
  "points": [...],
  "paceSecondsPerKm": 360,
  "hrRest": 60,
  "hrMax": 180,
  "lapCount": 1,
  "variantIndex": 1
}
```

**响应：** 二进制 FIT 文件

## 配置说明

### 环境变量

在 `.env` 文件中配置：

```env
PORT=3000              # 服务端口
NODE_ENV=development   # 运行环境
```

### 前端配置

编辑 `public/main.js` 中的 `CONFIG` 对象：

```javascript
const CONFIG = {
  MAP: {
    INITIAL_LAT: 39.9042,      // 默认纬度（北京）
    INITIAL_LNG: 116.4074,     // 默认经度
    INITIAL_ZOOM: 13,           // 默认缩放级别
    MAX_ZOOM: 19               // 最大缩放级别
  },
  // ... 其他配置
};
```

## 算法说明

### 距离计算
使用 Haversine 公式计算两点之间的球面距离。

### 配速模拟
- 基础配速 + 随机波动（长波 + 短波正弦叠加）
- 模拟真实跑步中的速度变化

### 心率模拟
- 基于运动强度计算目标心率
- 使用平滑过渡算法
- 添加随机抖动模拟真实心率波动

### 轨迹噪声
多圈跑时，每圈添加随机偏移（5-15米），避免轨迹完全重叠。

## 常见问题

### Q: 生成的 FIT 文件无法导入设备？
A: 确保设备支持 FIT 格式，尝试使用 Garmin Connect 等官方工具导入。

### Q: 地图显示不正常？
A: 检查网络连接，确保能访问 OpenStreetMap 服务器。

### Q: 搜索不到地点？
A: 确保网络连接正常，OpenStreetMap Nominatim API 可能有访问限制。

### Q: 如何自定义配色方案？
A: 编辑 `public/style.css` 中的颜色变量。

## 开发

### 项目结构
```
校园跑/
├── server.js           # Express 后端服务器
├── package.json        # 项目配置
├── .env.example        # 环境变量示例
├── public/
│   ├── index.html      # 前端页面
│   ├── main.js         # 前端逻辑
│   └── style.css       # 样式文件
└── README.md           # 项目文档
```

### 启动开发模式
```bash
npm start
```

### 代码规范
- 使用 ES6+ 语法
- 遵循函数单一职责原则
- 常量统一在 `CONFIG` 对象中定义

## 许可证

MIT License

## 贡献

欢迎提交 Issue 和 Pull Request！

## 联系方式

如有问题或建议，请通过以下方式联系：
- 提交 Issue
- 发送邮件

---

**注意**：本工具仅供学习交流使用，请勿用于作弊等违规行为。
