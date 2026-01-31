const CONFIG = {
  MAP: {
    INITIAL_LAT: 39.9042,
    INITIAL_LNG: 116.4074,
    INITIAL_ZOOM: 13,
    TILE_URL: "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
    MAX_ZOOM: 19,
    NOMINATIM_API: "https://nominatim.openstreetmap.org/search"
  },
  GEO: {
    EARTH_RADIUS_METERS: 6371000
  },
  HEART_RATE: {
    REST_MIN: 30,
    REST_MAX: 120,
    REST_DEFAULT: 60,
    MAX_MIN: 100,
    MAX_MAX: 220,
    MAX_DEFAULT: 180
  },
  PACE: {
    DEFAULT_MIN: 6,
    DEFAULT_SEC: 0
  },
  LAP: {
    MIN: 1,
    DEFAULT: 1
  },
  EXPORT: {
    MIN: 1,
    MAX: 10,
    DEFAULT: 1
  },
  PREVIEW: {
    MARKER_RADIUS: 6,
    MARKER_COLOR: "#1976d2",
    STEP_MS: 100
  },
  COLORS: {
    TRAJECTORY: "#ff5722",
    CHART_PACE: "#1976d2",
    CHART_HR: "#e53935"
  }
};

const map = L.map("map").setView(
  [CONFIG.MAP.INITIAL_LAT, CONFIG.MAP.INITIAL_LNG],
  CONFIG.MAP.INITIAL_ZOOM
);

L.tileLayer(CONFIG.MAP.TILE_URL, {
  maxZoom: CONFIG.MAP.MAX_ZOOM,
  attribution: "&copy; OpenStreetMap contributors"
}).addTo(map);

let routePoints = [];
let paceChart = null;
let hrChart = null;
let previewData = null;
let previewTimer = null;
let previewIndex = 0;
let previewMarker = null;
let freehandLayer = null;

let isFreehandMode = false;
let isDrawing = false;
let searchMarker = null;

async function searchLocation(query) {
  const resultsContainer = document.getElementById("searchResults");
  
  if (!query || query.trim().length < 2) {
    resultsContainer.innerHTML = "";
    return;
  }

  resultsContainer.innerHTML = '<div class="search-loading">搜索中...</div>';

  try {
    const response = await fetch(
      `${CONFIG.MAP.NOMINATIM_API}?format=json&q=${encodeURIComponent(query)}&limit=5&addressdetails=1`,
      {
        headers: {
          'Accept-Language': 'zh-CN'
        }
      }
    );

    if (!response.ok) {
      throw new Error("搜索失败");
    }

    const results = await response.json();

    if (results.length === 0) {
      resultsContainer.innerHTML = '<div class="search-error">未找到结果</div>';
      return;
    }

    resultsContainer.innerHTML = "";
    results.forEach(result => {
      const item = document.createElement("div");
      item.className = "search-result-item";
      
      const name = document.createElement("div");
      name.className = "search-result-name";
      name.textContent = result.display_name.split(',')[0];
      
      const address = document.createElement("div");
      address.className = "search-result-address";
      address.textContent = result.display_name;

      item.appendChild(name);
      item.appendChild(address);

      item.addEventListener("click", () => {
        const lat = parseFloat(result.lat);
        const lon = parseFloat(result.lon);
        
        if (searchMarker) {
          map.removeLayer(searchMarker);
        }
        
        searchMarker = L.marker([lat, lon]).addTo(map);
        map.setView([lat, lon], 15);
        resultsContainer.innerHTML = "";
        updateMessage(`已定位到：${result.display_name.split(',')[0]}`);
      });

      resultsContainer.appendChild(item);
    });
  } catch (e) {
    console.error("搜索错误:", e);
    resultsContainer.innerHTML = '<div class="search-error">搜索失败，请稍后重试</div>';
  }
}

let searchTimeout = null;
const searchInput = document.getElementById("searchInput");
const searchBtn = document.getElementById("searchBtn");

searchInput.addEventListener("input", (e) => {
  if (searchTimeout) {
    clearTimeout(searchTimeout);
  }
  searchTimeout = setTimeout(() => {
    searchLocation(e.target.value);
  }, 500);
});

searchInput.addEventListener("keypress", (e) => {
  if (e.key === "Enter") {
    searchLocation(e.target.value);
  }
});

searchBtn.addEventListener("click", () => {
  searchLocation(searchInput.value);
});

function updateMessage(text, isError = false) {
  const el = document.getElementById("message");
  el.textContent = text || "";
  el.className = "message" + (isError ? " error" : "");
}

function haversineDistance(lat1, lon1, lat2, lon2) {
  const R = CONFIG.GEO.EARTH_RADIUS_METERS;
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) *
      Math.cos(toRad(lat2)) *
      Math.sin(dLon / 2) *
      Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

function computeDistanceMeters(points) {
  if (!points || points.length < 2) return 0;
  let total = 0;
  for (let i = 1; i < points.length; i++) {
    const d = haversineDistance(
      points[i - 1].lat,
      points[i - 1].lng,
      points[i].lat,
      points[i].lng
    );
    if (!isNaN(d)) {
      total += d;
    }
  }
  return total;
}

function updateDistanceInfo() {
  const el = document.getElementById("distanceInfo");
  if (!el) return;
  if (!routePoints || routePoints.length < 2) {
    el.textContent = "总距离约：0 公里";
    return;
  }
  const baseMeters = computeDistanceMeters(routePoints);
  const baseKm = baseMeters / 1000;
  const lapInput = document.getElementById("lapCount");
  const laps = Math.max(CONFIG.LAP.MIN, parseInt(lapInput?.value, 10) || CONFIG.LAP.DEFAULT);
  const totalKm = baseKm * laps;
  const baseStr = isNaN(baseKm) ? "0.00" : baseKm.toFixed(2);
  const totalStr = isNaN(totalKm) ? "0.00" : totalKm.toFixed(2);
  if (laps > 1) {
    el.textContent = `总距离约：${totalStr} 公里（基础：${baseStr} 公里 × ${laps} 圈）`;
  } else {
    el.textContent = `总距离约：${baseStr} 公里`;
  }
}

function syncRouteFromMap() {
  if (freehandLayer) {
    const latlngs = freehandLayer.getLatLngs();
    if (latlngs && latlngs.length > 0) {
      routePoints = latlngs.map((p) => ({ lat: p.lat, lng: p.lng }));
    } else {
      routePoints = [];
    }
  } else {
    routePoints = [];
  }
  updateDistanceInfo();
  updateMessage(routePoints.length > 0 ? `已获取轨迹点：${routePoints.length}` : "尚未绘制轨迹");
}

map.pm.addControls({
  position: "topleft",
  drawMarker: false,
  drawCircleMarker: false,
  drawPolyline: false,
  drawRectangle: false,
  drawPolygon: false,
  drawCircle: false,
  editMode: true,
  dragMode: true,
  cutPolygon: false,
  removalMode: true
});

map.pm.setLang("zh");

document.getElementById("clearRoute").addEventListener("click", () => {
  if (freehandLayer) {
    map.removeLayer(freehandLayer);
    freehandLayer = null;
  }
  routePoints = [];
  updateMessage("轨迹已清除");
  updateDistanceInfo();
});

function toggleFreehandMode() {
  isFreehandMode = !isFreehandMode;
  const btn = document.getElementById("freehandBtn");
  
  if (isFreehandMode) {
    btn.style.background = CONFIG.COLORS.TRAJECTORY;
    btn.textContent = "正在退出手绘...";
    map.dragging.disable();
    map.getContainer().style.cursor = "crosshair";
    updateMessage("自由手绘模式：在地图上按住鼠标左键并拖动进行绘画");
  } else {
    btn.style.background = "";
    btn.textContent = "自由手绘";
    map.dragging.enable();
    map.getContainer().style.cursor = "";
    updateMessage("已退出手绘模式");
  }
}

function startFreehandDrawing(e) {
  if (!isFreehandMode) return;
  isDrawing = true;
  
  if (freehandLayer) {
    map.removeLayer(freehandLayer);
  }

  freehandLayer = L.polyline([e.latlng], {
    color: CONFIG.COLORS.TRAJECTORY,
    weight: 3
  }).addTo(map);
}

function updateFreehandDrawing(e) {
  if (!isFreehandMode || !isDrawing || !freehandLayer) return;
  freehandLayer.addLatLng(e.latlng);
}

function endFreehandDrawing() {
  if (!isFreehandMode || !isDrawing) return;
  isDrawing = false;

  if (freehandLayer) {
    freehandLayer.pm.enable();
    syncRouteFromMap();
  }

  isFreehandMode = false;
  const btn = document.getElementById("freehandBtn");
  btn.style.background = "";
  btn.textContent = "自由手绘";
  map.dragging.enable();
  map.getContainer().style.cursor = "";
}

document.getElementById("freehandBtn").addEventListener("click", toggleFreehandMode);
map.on("mousedown", startFreehandDrawing);
map.on("mousemove", updateFreehandDrawing);
map.on("mouseup", endFreehandDrawing);

freehandLayer?.on("pm:edit", syncRouteFromMap);
freehandLayer?.on("pm:dragend", syncRouteFromMap);

function dateToLocalInputValue(d) {
  const tzOffset = d.getTimezoneOffset();
  const local = new Date(d.getTime() - tzOffset * 60000);
  return local.toISOString().slice(0, 16);
}

function rebuildExportTimes() {
  const container = document.getElementById("exportTimes");
  const exportInput = document.getElementById("exportCount");
  if (!container || !exportInput) return;

  const count = Math.max(
    CONFIG.EXPORT.MIN,
    Math.min(CONFIG.EXPORT.MAX, parseInt(exportInput.value, 10) || CONFIG.EXPORT.DEFAULT)
  );
  const now = new Date();

  container.innerHTML = "";

  for (let i = 0; i < count; i++) {
    const row = document.createElement("div");
    row.className = "export-time-row";

    const label = document.createElement("span");
    label.textContent = `第 ${i + 1} 份`;

    const timeInput = document.createElement("input");
    timeInput.type = "datetime-local";
    timeInput.className = "export-time-input";
    timeInput.dataset.index = String(i);
    const d = new Date(now.getTime() + i * 24 * 60 * 60 * 1000);
    timeInput.value = dateToLocalInputValue(d);

    const paceMinInput = document.createElement("input");
    paceMinInput.type = "number";
    paceMinInput.className = "export-pace-min";
    paceMinInput.min = "0";
    paceMinInput.step = "0.1";
    paceMinInput.value = String(CONFIG.PACE.DEFAULT_MIN);

    const paceSecInput = document.createElement("input");
    paceSecInput.type = "number";
    paceSecInput.className = "export-pace-sec";
    paceSecInput.min = "0";
    paceSecInput.max = "59.9";
    paceSecInput.step = "0.1";
    paceSecInput.value = String(CONFIG.PACE.DEFAULT_SEC);

    row.appendChild(label);
    row.appendChild(timeInput);
    row.appendChild(paceMinInput);
    row.appendChild(paceSecInput);
    container.appendChild(row);
  }
}

function getActivityParams() {
  const hrRest = parseInt(document.getElementById("hrRest").value, 10) || CONFIG.HEART_RATE.REST_DEFAULT;
  const hrMax = parseInt(document.getElementById("hrMax").value, 10) || CONFIG.HEART_RATE.MAX_DEFAULT;
  const lapCount = Math.max(
    CONFIG.LAP.MIN,
    parseInt(document.getElementById("lapCount")?.value, 10) || CONFIG.LAP.DEFAULT
  );
  return { hrRest, hrMax, lapCount };
}

async function generateFit() {
  if (routePoints.length < 2) {
    updateMessage("请至少在地图上选择两个点形成轨迹", true);
    return;
  }

  const { hrRest, hrMax, lapCount } = getActivityParams();
  const exportCount = Math.max(
    CONFIG.EXPORT.MIN,
    Math.min(CONFIG.EXPORT.MAX, parseInt(document.getElementById("exportCount")?.value, 10) || CONFIG.EXPORT.DEFAULT)
  );

  const exportTimesContainer = document.getElementById("exportTimes");
  const timeInputs = Array.from(exportTimesContainer.querySelectorAll(".export-time-input"));
  const paceMinInputs = Array.from(exportTimesContainer.querySelectorAll(".export-pace-min"));
  const paceSecInputs = Array.from(exportTimesContainer.querySelectorAll(".export-pace-sec"));

  try {
    for (let i = 0; i < exportCount; i++) {
      updateMessage(`正在生成第 ${i + 1}/${exportCount} 个 FIT 文件，请稍候...`);

      const input = timeInputs[i];
      if (!input || !input.value) {
        updateMessage(`请为第 ${i + 1} 份设置开始日期时间`, true);
        return;
      }
      const fileStart = new Date(input.value);
      if (Number.isNaN(fileStart.getTime())) {
        updateMessage(`第 ${i + 1} 份的开始时间无效`, true);
        return;
      }

      const paceMinInput = paceMinInputs[i];
      const paceSecInput = paceSecInputs[i];
      if (paceMinInput && paceSecInput) {
        const pm = parseFloat(paceMinInput.value);
        const ps = parseFloat(paceSecInput.value);
        const sec = (Number.isFinite(pm) ? pm : 0) * 60 + (Number.isFinite(ps) ? ps : 0);
        if (!sec || sec <= 0) {
          updateMessage(`第 ${i + 1} 份的配速无效`, true);
          return;
        }

        var filePaceSecondsPerKm = sec;
      }

      const res = await fetch("/api/generate-fit", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          startTime: fileStart.toISOString(),
          points: routePoints,
          paceSecondsPerKm: filePaceSecondsPerKm,
          hrRest,
          hrMax,
          lapCount,
          variantIndex: i + 1
        })
      });

      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        updateMessage(err.error || "生成失败", true);
        return;
      }

      const blob = await res.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = exportCount > 1 ? `run_${i + 1}.fit` : "run.fit";
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
    }
    updateMessage(`已生成 ${exportCount} 个 FIT 文件并开始下载`);
  } catch (e) {
    console.error(e);
    updateMessage("请求失败，请稍后重试", true);
  }
}

function renderPreviewCharts(preview) {
  if (!preview || !Array.isArray(preview.samples) || preview.samples.length === 0) {
    updateMessage("预览数据为空", true);
    return;
  }

  const labels = preview.samples.map((s) => (s.timeSec / 60).toFixed(1));
  const paceData = preview.samples.map((s) => {
    const speed = s.speed > 0 ? s.speed : 0.01;
    const secPerKm = 1000 / speed;
    return secPerKm / 60;
  });
  const hrData = preview.samples.map((s) => s.heartRate);

  const paceCtx = document.getElementById("paceChart").getContext("2d");
  const hrCtx = document.getElementById("hrChart").getContext("2d");

  if (paceChart) paceChart.destroy();
  if (hrChart) hrChart.destroy();

  paceChart = new Chart(paceCtx, {
    type: "line",
    data: {
      labels,
      datasets: [
        {
          label: "配速 (min/km)",
          data: paceData,
          borderColor: CONFIG.COLORS.CHART_PACE,
          tension: 0.2,
          pointRadius: 0
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false }
      },
      scales: {
        x: {
          title: { display: true, text: "时间 (分钟)" },
          ticks: { font: { size: 10 } }
        },
        y: {
          title: { display: true, text: "min/km" },
          reverse: true,
          ticks: { font: { size: 10 } }
        }
      }
    }
  });

  hrChart = new Chart(hrCtx, {
    type: "line",
    data: {
      labels,
      datasets: [
        {
          label: "心率 (bpm)",
          data: hrData,
          borderColor: CONFIG.COLORS.CHART_HR,
          tension: 0.2,
          pointRadius: 0
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false }
      },
      scales: {
        x: {
          title: { display: true, text: "时间 (分钟)" },
          ticks: { font: { size: 10 } }
        },
        y: {
          title: { display: true, text: "bpm" },
          ticks: { font: { size: 10 } }
        }
      }
    }
  });
}

function updateLiveInfo(sample) {
  const el = document.getElementById("liveInfo");
  if (!el || !sample) return;
  const t = Math.max(0, sample.timeSec || 0);
  const min = Math.floor(t / 60);
  const sec = Math.floor(t % 60);
  const speed = sample.speed > 0 ? sample.speed : 0.01;
  const secPerKm = 1000 / speed;
  const paceMin = Math.floor(secPerKm / 60);
  const paceSec = Math.round(secPerKm % 60);
  const paceStr = `${paceMin}'${paceSec.toString().padStart(2, "0")}"/km`;
  const hr = sample.heartRate || 0;
  el.textContent = `时间 ${min}:${sec.toString().padStart(2, "0")}  配速 ${paceStr}  心率 ${hr} bpm`;
}

function startPreviewPlayback() {
  const samples = previewData?.samples || [];
  if (!samples.length) return;

  previewIndex = 0;

  if (previewTimer) {
    clearInterval(previewTimer);
  }

  previewTimer = setInterval(() => {
    if (previewIndex >= samples.length) {
      clearInterval(previewTimer);
      previewTimer = null;
      return;
    }
    const s = samples[previewIndex];
    if (previewMarker && s.lat != null && s.lng != null) {
      previewMarker.setLatLng([s.lat, s.lng]);
    }
    updateLiveInfo(s);
    previewIndex += 1;
  }, CONFIG.PREVIEW.STEP_MS);
}

async function previewActivity() {
  if (routePoints.length < 2) {
    updateMessage("请至少在地图上选择两个点形成轨迹", true);
    return;
  }

  const exportTimesContainer = document.getElementById("exportTimes");
  const timeInputs = Array.from(exportTimesContainer.querySelectorAll(".export-time-input"));
  const paceMinInputs = Array.from(exportTimesContainer.querySelectorAll(".export-pace-min"));
  const paceSecInputs = Array.from(exportTimesContainer.querySelectorAll(".export-pace-sec"));

  if (!timeInputs.length || !paceMinInputs.length || !paceSecInputs.length) {
    updateMessage("请先在导出列表中设置至少一份的时间和配速", true);
    return;
  }

  const firstTimeInput = timeInputs[0];
  if (!firstTimeInput.value) {
    const now = new Date();
    firstTimeInput.value = dateToLocalInputValue(now);
  }
  const start = new Date(firstTimeInput.value);
  if (Number.isNaN(start.getTime())) {
    updateMessage("预览使用的开始时间无效", true);
    return;
  }

  const firstPaceMinInput = paceMinInputs[0];
  const firstPaceSecInput = paceSecInputs[0];
  const pm = parseFloat(firstPaceMinInput.value);
  const ps = parseFloat(firstPaceSecInput.value);
  const paceSecondsPerKm = (Number.isFinite(pm) ? pm : 0) * 60 + (Number.isFinite(ps) ? ps : 0);
  if (!paceSecondsPerKm || paceSecondsPerKm <= 0) {
    updateMessage("预览使用的配速无效", true);
    return;
  }

  const { hrRest, hrMax, lapCount } = getActivityParams();

  updateMessage("正在生成预览，请稍候...");

  try {
    const res = await fetch("/api/preview", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        startTime: start.toISOString(),
        points: routePoints,
        paceSecondsPerKm,
        hrRest,
        hrMax,
        lapCount
      })
    });

    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      updateMessage(err.error || "预览失败", true);
      return;
    }

    const data = await res.json();
    renderPreviewCharts(data);

    const km = (data.totalDistanceMeters / 1000).toFixed(2);
    const min = (data.totalDurationSec / 60).toFixed(1);
    updateMessage(`预览已生成，总距离约 ${km} 公里，总时间约 ${min} 分钟`);
    previewData = data;
    previewIndex = 0;
    if (previewTimer) {
      clearInterval(previewTimer);
      previewTimer = null;
    }
    if (previewMarker) {
      map.removeLayer(previewMarker);
      previewMarker = null;
    }
    const samples = previewData.samples || [];
    if (samples.length > 0) {
      const first = samples[0];
      previewMarker = L.circleMarker([first.lat, first.lng], {
        radius: CONFIG.PREVIEW.MARKER_RADIUS,
        color: CONFIG.PREVIEW.MARKER_COLOR
      }).addTo(map);
      startPreviewPlayback();
    }
  } catch (e) {
    console.error(e);
    updateMessage("预览请求失败，请稍后重试", true);
  }
}

document.getElementById("generateFit").addEventListener("click", generateFit);
document.getElementById("previewBtn").addEventListener("click", previewActivity);
document.getElementById("lapCount").addEventListener("input", updateDistanceInfo);
document.getElementById("exportCount").addEventListener("input", rebuildExportTimes);

updateDistanceInfo();
rebuildExportTimes();
