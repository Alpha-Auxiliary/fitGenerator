import dotenv from "dotenv";
import express from "express";
import path from "path";
import { fileURLToPath } from "url";
import open from "open";
import fs from "fs";
import {
  ActivityInputError,
  buildFitActivity,
  buildPreviewActivity,
} from "./src/fitCore.js";

dotenv.config();

// 自动适配：在 pkg 环境下使用 process.cwd() 或 __dirname
const isPkg = typeof process.pkg !== 'undefined';
// 编译后的文件会在根目录，所以直接找同级的 public
const __dirname = isPkg 
    ? path.dirname(process.execPath) 
    : path.dirname(fileURLToPath(import.meta.url));

// 但在 pkg 内部镜像系统中，资源是相对于代码文件的
// 强制使用相对路径，ncc 打包后 __dirname 会被处理
const getPublicPath = () => {
    // 尝试多个可能的路径，确保 100% 找到
    const paths = [
        path.join(process.cwd(), "public"),
        path.join(path.dirname(fileURLToPath(import.meta.url)), "public"),
        "C:\\snapshot\\public", // pkg 默认镜像路径
        "/snapshot/public"
    ];
    
    for (const p of paths) {
        if (fs.existsSync(p)) return p;
    }
    return path.join(process.cwd(), "public"); // 兜底
};

const app = express();
const PORT = process.env.PORT || 8080;

const MAP_CONFIG = {
  DEFAULT_PROVIDER: "osm",
  VALID_PROVIDERS: new Set(["osm", "baidu", "amap", "google"]),
};

function readBuildMapConfig() {
  const paths = [
    path.join(process.cwd(), "build-map-config.json"),
    path.join(path.dirname(fileURLToPath(import.meta.url)), "build-map-config.json"),
    path.join(__dirname, "build-map-config.json"),
    "C:\\snapshot\\build-map-config.json",
    "/snapshot/build-map-config.json",
  ];

  for (const p of paths) {
    try {
      if (fs.existsSync(p)) {
        return JSON.parse(fs.readFileSync(p, "utf8"));
      }
    } catch (e) {
      console.error("Failed to read build map config:", e);
    }
  }

  return {};
}

const buildMapConfig = readBuildMapConfig();

function getMapProviderFromEnv() {
  const provider = (
    process.env.MAP_DEFAULT_PROVIDER ||
    buildMapConfig.defaultProvider ||
    MAP_CONFIG.DEFAULT_PROVIDER
  ).toLowerCase();
  return MAP_CONFIG.VALID_PROVIDERS.has(provider) ? provider : MAP_CONFIG.DEFAULT_PROVIDER;
}

function envOrBuild(envName, providerId, keyName) {
  return process.env[envName] || buildMapConfig.providers?.[providerId]?.[keyName] || "";
}

//pkg 内部资源路径。__dirname 在被 ncc 编译后会指向虚拟系统的根。
const publicPath = path.join(path.resolve(), "public");

app.use(express.json({ limit: "5mb" }));
app.use(express.static(publicPath));

app.get("/api/map-config", (req, res) => {
  res.json({
    defaultProvider: getMapProviderFromEnv(),
    providers: {
      osm: {},
      baidu: {
        ak: envOrBuild("BAIDU_MAP_AK", "baidu", "ak"),
      },
      amap: {
        key: envOrBuild("AMAP_MAP_KEY", "amap", "key"),
        securityJsCode: envOrBuild("AMAP_SECURITY_JS_CODE", "amap", "securityJsCode"),
      },
      google: {
        apiKey: envOrBuild("GOOGLE_MAPS_API_KEY", "google", "apiKey"),
      },
    },
  });
});

// 兜底路由
app.get("*", (req, res, next) => {
    if (req.path.startsWith("/api")) return next();
    const indexPath = path.join(publicPath, "index.html");
    if (fs.existsSync(indexPath)) {
        res.sendFile(indexPath);
    } else {
        res.status(404).send("Index.html not found in " + publicPath);
    }
});

app.post("/api/preview", (req, res) => {
  try {
    return res.json(buildPreviewActivity(req.body));
  } catch (e) {
    if (e instanceof ActivityInputError) {
      return res.status(400).json({ error: e.message });
    }
    console.error("Preview generation error:", e);
    return res.status(500).json({ error: "生成预览失败" });
  }
});

app.post("/api/generate-fit", (req, res) => {
  try {
    const fitActivity = buildFitActivity(req.body);
    const buffer = Buffer.from(fitActivity.fitBytes);

    res.setHeader("Content-Type", "application/vnd.ant.fit");
    res.setHeader(
      "Content-Disposition",
      `attachment; filename=${fitActivity.filename}`,
    );
    return res.send(buffer);
  } catch (e) {
    if (e instanceof ActivityInputError) {
      return res.status(400).json({ error: e.message });
    }
    console.error("FIT file generation error:", e);
    return res.status(500).json({ error: "生成 FIT 文件失败" });
  }
});

app.listen(PORT, async () => {
  const url = `http://localhost:${PORT}`;
  console.log(`Server listening on ${url}`);
  
  if (process.pkg) {
    try {
      await open(url);
      console.log("Browser opened automatically.");
    } catch (err) {
      console.error("Failed to open browser:", err);
    }
  }
});
