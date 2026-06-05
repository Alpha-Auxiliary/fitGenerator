const CONFIG = {
  MAP: {
    INITIAL_LAT: 39.9042,
    INITIAL_LNG: 116.4074,
    INITIAL_ZOOM: 13,
    MAX_ZOOM: 18,
    DEFAULT_PROVIDER: "google",
    PROVIDERS: {
      baidu: {
        label: "百度地图",
        ak: ""
      },
      amap: {
        label: "高德地图",
        key: "",
        securityJsCode: ""
      },
      google: {
        label: "谷歌地图",
        apiKey: ""
      }
    }
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
    MARKER_RADIUS: 8,
    MARKER_COLOR: "#1976d2",
    STEP_MS: 100
  },
  COLORS: {
    TRAJECTORY: "#ff5722",
    CHART_PACE: "#1976d2",
    CHART_HR: "#e53935"
  }
};

// 地图实例
let map = null;
let mapAdapter = null;
let activeProvider = localStorage.getItem("mapProvider") || CONFIG.MAP.DEFAULT_PROVIDER;
if (!CONFIG.MAP.PROVIDERS[activeProvider]) {
  activeProvider = CONFIG.MAP.DEFAULT_PROVIDER;
}
// 路线折线
let routePolyline = null;
// 路线点数组
let routePoints = [];
// 折线编辑器
let polyEditor = null;
// 是否处于绘图模式
let isDrawingMode = false;
// 是否正在绘制
let isDrawing = false;
// 绘制的临时点
let drawingPoints = [];
// 搜索结果标记
let searchMarker = null;
// 预览标记
let previewMarker = null;
// 预览数据
let previewData = null;
let previewTimer = null;
let previewIndex = 0;

// 图表
let paceChart = null;
let hrChart = null;

function loadScriptOnce(id, src, globalCheck) {
  if (globalCheck && globalCheck()) return Promise.resolve();

  return new Promise((resolve, reject) => {
    const existing = document.getElementById(id);
    if (existing) existing.remove();

    const script = document.createElement("script");
    script.id = id;
    script.src = src;
    script.async = true;
    script.defer = true;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(`地图脚本加载失败：${id}`));
    document.head.appendChild(script);
  });
}

function loadJsonpScript(id, src, callbackName, globalCheck) {
  if (globalCheck && globalCheck()) return Promise.resolve();

  return new Promise((resolve, reject) => {
    const existing = document.getElementById(id);
    if (existing) existing.remove();

    window[callbackName] = () => resolve();
    const script = document.createElement("script");
    script.id = id;
    script.src = `${src}${src.includes("?") ? "&" : "?"}callback=${callbackName}`;
    script.async = true;
    script.defer = true;
    script.onerror = () => reject(new Error(`地图脚本加载失败：${id}`));
    document.head.appendChild(script);
  });
}

function mapProviderConfig(id = activeProvider) {
  return CONFIG.MAP.PROVIDERS[id] || CONFIG.MAP.PROVIDERS[CONFIG.MAP.DEFAULT_PROVIDER];
}

async function loadMapRuntimeConfig() {
  try {
    const res = await fetch("/api/map-config");
    if (!res.ok) throw new Error("地图配置加载失败");
    const data = await res.json();

    if (data.defaultProvider && CONFIG.MAP.PROVIDERS[data.defaultProvider]) {
      CONFIG.MAP.DEFAULT_PROVIDER = data.defaultProvider;
    }

    if (data.providers) {
      Object.keys(CONFIG.MAP.PROVIDERS).forEach(providerId => {
        if (data.providers[providerId]) {
          Object.assign(CONFIG.MAP.PROVIDERS[providerId], data.providers[providerId]);
        }
      });
    }

    const savedProvider = localStorage.getItem("mapProvider");
    activeProvider = CONFIG.MAP.PROVIDERS[savedProvider] ? savedProvider : CONFIG.MAP.DEFAULT_PROVIDER;
  } catch (e) {
    console.error(e);
    updateMessage("地图配置加载失败，将使用默认地图源", true);
    activeProvider = CONFIG.MAP.DEFAULT_PROVIDER;
  }
}

function closePolylineEditor() {
  if (polyEditor && mapAdapter) {
    mapAdapter.closeEdit(polyEditor);
  }
  polyEditor = null;
}

function resetMapState() {
  if (previewTimer) {
    clearInterval(previewTimer);
    previewTimer = null;
  }
  closePolylineEditor();
  routePolyline = null;
  searchMarker = null;
  previewMarker = null;
  drawingPoints = [];
  routePoints = [];
  map = null;
  mapAdapter = null;
  const mapEl = document.getElementById("map");
  const freshMapEl = document.createElement("div");
  freshMapEl.id = "map";
  mapEl.replaceWith(freshMapEl);
  updateDistanceInfo();
}

function makeBaiduAdapter() {
  return {
    async load() {
      const cfg = mapProviderConfig("baidu");
      if (!cfg.ak) throw new Error("缺少 BAIDU_MAP_AK");
      await loadJsonpScript(
        "baidu-map-sdk",
        `https://api.map.baidu.com/api?v=1.0&type=webgl&ak=${encodeURIComponent(cfg.ak)}`,
        "initBaiduMapSdk",
        () => window.BMapGL
      );
    },
    init(containerId) {
      map = new BMapGL.Map(containerId);
      map.centerAndZoom(new BMapGL.Point(CONFIG.MAP.INITIAL_LNG, CONFIG.MAP.INITIAL_LAT), CONFIG.MAP.INITIAL_ZOOM);
      map.enableScrollWheelZoom(true);
      map.addControl(new BMapGL.ScaleControl());
      map.addControl(new BMapGL.ZoomControl({ anchor: BMAP_ANCHOR_TOP_RIGHT }));
    },
    getContainer: () => map.getContainer(),
    point: (lng, lat) => new BMapGL.Point(lng, lat),
    eventLngLat(e) {
      const point = e.latlng || e.point || e.lnglat;
      return { lng: point.lng, lat: point.lat };
    },
    onMapEvent: (name, handler) => map.addEventListener(name, handler),
    createPolyline(points) {
      const polyline = new BMapGL.Polyline(points, {
        strokeColor: CONFIG.COLORS.TRAJECTORY,
        strokeWeight: 4,
        strokeOpacity: 0.9
      });
      map.addOverlay(polyline);
      return polyline;
    },
    setPolylinePath: (polyline, points) => polyline.setPath(points),
    getPolylinePath: (polyline) => polyline.getPath().map(p => ({ lng: p.lng, lat: p.lat })),
    onPolylineClick: (polyline, handler) => polyline.addEventListener("click", handler),
    enablePolylineEdit(polyline, onUpdate) {
      polyline.enableEditing();
      polyline.addEventListener("lineupdate", onUpdate);
      return polyline;
    },
    closeEdit(editor) {
      if (editor && editor.disableEditing) editor.disableEditing();
    },
    removeOverlay: (overlay) => overlay && map.removeOverlay(overlay),
    addMarker(point, title) {
      const marker = new BMapGL.Marker(point, { title });
      map.addOverlay(marker);
      return marker;
    },
    centerAndZoom: (point, zoom) => map.centerAndZoom(point, zoom),
    setCursor: (cursor) => map.setDefaultCursor(cursor),
    enableDragging: () => map.enableDragging(),
    disableDragging: () => map.disableDragging(),
    createPreviewMarker(point) {
      const marker = new BMapGL.Circle(point, CONFIG.PREVIEW.MARKER_RADIUS, {
        fillColor: CONFIG.PREVIEW.MARKER_COLOR,
        fillOpacity: 1,
        strokeColor: CONFIG.PREVIEW.MARKER_COLOR,
        strokeWeight: 2
      });
      map.addOverlay(marker);
      return marker;
    },
    movePreviewMarker: (marker, point) => marker.setCenter(point),
    search(query, onResults, onError) {
      const localSearch = new BMapGL.LocalSearch(map, {
        onSearchComplete(results) {
          if (localSearch.getStatus() !== BMAP_STATUS_SUCCESS) {
            onResults([]);
            return;
          }
          const count = results.getCurrentNumPois ? results.getCurrentNumPois() : 0;
          const items = [];
          for (let i = 0; i < Math.min(count, 5); i++) {
            const poi = results.getPoi(i);
            if (poi && poi.point) {
              items.push({ name: poi.title, address: poi.address || "", point: poi.point });
            }
          }
          onResults(items);
        }
      });
      try {
        localSearch.search(query);
      } catch (e) {
        onError(e);
      }
    }
  };
}

function makeAmapAdapter() {
  return {
    async load() {
      const cfg = mapProviderConfig("amap");
      if (!cfg.key) throw new Error("缺少 AMAP_MAP_KEY");
      if (cfg.securityJsCode) {
        window._AMapSecurityConfig = { securityJsCode: cfg.securityJsCode };
      }
      await loadScriptOnce(
        "amap-sdk",
        `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(cfg.key)}&plugin=AMap.Scale,AMap.ToolBar,AMap.PolyEditor,AMap.PlaceSearch`,
        () => window.AMap
      );
    },
    init(containerId) {
      map = new AMap.Map(containerId, {
        zoom: CONFIG.MAP.INITIAL_ZOOM,
        center: [CONFIG.MAP.INITIAL_LNG, CONFIG.MAP.INITIAL_LAT],
        viewMode: "2D"
      });
      map.addControl(new AMap.Scale());
      map.addControl(new AMap.ToolBar({ position: "RT" }));
    },
    getContainer: () => map.getContainer(),
    point: (lng, lat) => [lng, lat],
    eventLngLat(e) {
      return { lng: e.lnglat.lng, lat: e.lnglat.lat };
    },
    onMapEvent: (name, handler) => map.on(name, handler),
    createPolyline(points) {
      const polyline = new AMap.Polyline({
        path: points,
        strokeColor: CONFIG.COLORS.TRAJECTORY,
        strokeWeight: 4,
        strokeOpacity: 0.9
      });
      map.add(polyline);
      return polyline;
    },
    setPolylinePath: (polyline, points) => polyline.setPath(points),
    getPolylinePath: (polyline) => polyline.getPath().map(p => ({ lng: p.lng, lat: p.lat })),
    onPolylineClick: (polyline, handler) => polyline.on("click", handler),
    enablePolylineEdit(polyline, onUpdate) {
      const editor = new AMap.PolyEditor(map, polyline);
      editor.open();
      editor.on("end", onUpdate);
      return editor;
    },
    closeEdit(editor) {
      if (editor && editor.close) editor.close();
    },
    removeOverlay: (overlay) => overlay && map.remove(overlay),
    addMarker(point, title) {
      const marker = new AMap.Marker({ position: point, title });
      map.add(marker);
      return marker;
    },
    centerAndZoom(point, zoom) {
      map.setCenter(point);
      map.setZoom(zoom);
    },
    setCursor: (cursor) => map.setDefaultCursor(cursor),
    enableDragging: () => map.setStatus({ dragEnable: true }),
    disableDragging: () => map.setStatus({ dragEnable: false }),
    createPreviewMarker(point) {
      const marker = new AMap.CircleMarker({
        center: point,
        radius: CONFIG.PREVIEW.MARKER_RADIUS,
        fillColor: CONFIG.PREVIEW.MARKER_COLOR,
        fillOpacity: 1,
        strokeColor: CONFIG.PREVIEW.MARKER_COLOR,
        strokeWeight: 2
      });
      map.add(marker);
      return marker;
    },
    movePreviewMarker: (marker, point) => marker.setCenter(point),
    search(query, onResults, onError) {
      AMap.plugin("AMap.PlaceSearch", () => {
        const placeSearch = new AMap.PlaceSearch({ pageSize: 5, pageIndex: 1 });
        placeSearch.search(query, (status, result) => {
          if (status !== "complete" || !result.poiList || !result.poiList.pois) {
            onResults([]);
            return;
          }
          onResults(result.poiList.pois.slice(0, 5).map(poi => ({
            name: poi.name,
            address: poi.address || poi.cityname || poi.adname || "",
            point: [poi.location.lng, poi.location.lat]
          })));
        });
      });
    }
  };
}

function makeGoogleAdapter() {
  return {
    async load() {
      const cfg = mapProviderConfig("google");
      if (!cfg.apiKey) throw new Error("缺少 GOOGLE_MAPS_API_KEY");
      await loadJsonpScript(
        "google-map-sdk",
        `https://maps.googleapis.com/maps/api/js?key=${encodeURIComponent(cfg.apiKey)}&libraries=places`,
        "initGoogleMapSdk",
        () => window.google && window.google.maps
      );
    },
    init(containerId) {
      map = new google.maps.Map(document.getElementById(containerId), {
        center: { lat: CONFIG.MAP.INITIAL_LAT, lng: CONFIG.MAP.INITIAL_LNG },
        zoom: CONFIG.MAP.INITIAL_ZOOM,
        mapTypeControl: false,
        streetViewControl: false
      });
    },
    getContainer: () => document.getElementById("map"),
    point: (lng, lat) => ({ lng, lat }),
    eventLngLat(e) {
      return { lng: e.latLng.lng(), lat: e.latLng.lat() };
    },
    onMapEvent: (name, handler) => google.maps.event.addListener(map, name, handler),
    createPolyline(points) {
      return new google.maps.Polyline({
        path: points,
        map,
        strokeColor: CONFIG.COLORS.TRAJECTORY,
        strokeWeight: 4,
        strokeOpacity: 0.9
      });
    },
    setPolylinePath: (polyline, points) => polyline.setPath(points),
    getPolylinePath(polyline) {
      return polyline.getPath().getArray().map(p => ({ lng: p.lng(), lat: p.lat() }));
    },
    onPolylineClick: (polyline, handler) => google.maps.event.addListener(polyline, "click", handler),
    enablePolylineEdit(polyline, onUpdate) {
      polyline.setEditable(true);
      const path = polyline.getPath();
      const listeners = [
        google.maps.event.addListener(path, "set_at", onUpdate),
        google.maps.event.addListener(path, "insert_at", onUpdate),
        google.maps.event.addListener(path, "remove_at", onUpdate)
      ];
      return { polyline, listeners };
    },
    closeEdit(editor) {
      if (!editor) return;
      editor.polyline.setEditable(false);
      editor.listeners.forEach(listener => listener.remove());
    },
    removeOverlay: (overlay) => overlay && overlay.setMap(null),
    addMarker(point, title) {
      return new google.maps.Marker({ position: point, map, title });
    },
    centerAndZoom(point, zoom) {
      map.setCenter(point);
      map.setZoom(zoom);
    },
    setCursor: (cursor) => map.setOptions({ draggableCursor: cursor || null }),
    enableDragging: () => map.setOptions({ draggable: true }),
    disableDragging: () => map.setOptions({ draggable: false }),
    createPreviewMarker(point) {
      return new google.maps.Circle({
        center: point,
        radius: CONFIG.PREVIEW.MARKER_RADIUS,
        fillColor: CONFIG.PREVIEW.MARKER_COLOR,
        fillOpacity: 1,
        strokeColor: CONFIG.PREVIEW.MARKER_COLOR,
        strokeWeight: 2,
        map
      });
    },
    movePreviewMarker: (marker, point) => marker.setCenter(point),
    search(query, onResults, onError) {
      const service = new google.maps.places.PlacesService(map);
      service.textSearch({ query }, (results, status) => {
        if (status !== google.maps.places.PlacesServiceStatus.OK || !results) {
          onResults([]);
          return;
        }
        onResults(results.slice(0, 5).map(place => ({
          name: place.name,
          address: place.formatted_address || place.vicinity || "",
          point: place.geometry.location
        })));
      });
    }
  };
}

function createMapAdapter(providerId) {
  if (providerId === "amap") return makeAmapAdapter();
  if (providerId === "google") return makeGoogleAdapter();
  return makeBaiduAdapter();
}

async function initMap(providerId = activeProvider) {
  activeProvider = providerId;
  localStorage.setItem("mapProvider", activeProvider);
  resetMapState();

  const select = document.getElementById("mapProvider");
  if (select) select.value = activeProvider;

  mapAdapter = createMapAdapter(activeProvider);
  try {
    await mapAdapter.load();
    mapAdapter.init("map");
    initFreehandDrawing();
    updateMessage(`当前地图源：${mapProviderConfig(activeProvider).label}`);
  } catch (e) {
    console.error(e);
    mapAdapter = null;
    updateMessage(`地图源加载失败：${mapProviderConfig(activeProvider).label}，请检查对应 Key`, true);
  }
}

// 初始化搜索功能
function initSearch() {
  const searchInput = document.getElementById("searchInput");
  const searchBtn = document.getElementById("searchBtn");
  const resultsContainer = document.getElementById("searchResults");

  let searchTimeout = null;

  searchInput.addEventListener("input", (e) => {
    if (searchTimeout) clearTimeout(searchTimeout);
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
}

// 搜索地点
function searchLocation(query) {
  const resultsContainer = document.getElementById("searchResults");
  
  if (!query || query.trim().length < 2) {
    resultsContainer.innerHTML = "";
    return;
  }

  resultsContainer.innerHTML = '<div class="search-loading">搜索中...</div>';

  try {
    if (!mapAdapter) {
      resultsContainer.innerHTML = '<div class="search-error">地图源尚未加载完成</div>';
      return;
    }

    mapAdapter.search(query, (results) => {
      if (!results.length) {
        resultsContainer.innerHTML = '<div class="search-error">未找到结果</div>';
        return;
      }

      resultsContainer.innerHTML = "";
      results.forEach(result => {
        const item = document.createElement("div");
        item.className = "search-result-item";
        
        const name = document.createElement("div");
        name.className = "search-result-name";
        name.textContent = result.name;
        
        const address = document.createElement("div");
        address.className = "search-result-address";
        address.textContent = result.address || '';

        item.appendChild(name);
        item.appendChild(address);

        item.addEventListener("click", () => {
          if (searchMarker) {
            mapAdapter.removeOverlay(searchMarker);
          }
          
          searchMarker = mapAdapter.addMarker(result.point, result.name);
          mapAdapter.centerAndZoom(result.point, 15);
          resultsContainer.innerHTML = "";
          updateMessage(`已定位到：${result.name}`);
        });

        resultsContainer.appendChild(item);
      });
    }, (e) => {
      console.error("搜索错误:", e);
      resultsContainer.innerHTML = '<div class="search-error">搜索失败，请稍后重试</div>';
    });
  } catch (e) {
    console.error("搜索错误:", e);
    resultsContainer.innerHTML = '<div class="search-error">搜索失败，请稍后重试</div>';
  }
}

// 更新消息
function updateMessage(text, isError = false) {
  const el = document.getElementById("message");
  el.textContent = text || "";
  el.className = "message" + (isError ? " error" : "");
}

// 计算两点间距离（Haversine公式）
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

// 计算总距离
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
    if (!isNaN(d)) total += d;
  }
  return total;
}

// 更新距离信息
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

// 从折线同步路线点
function syncRouteFromPolyline() {
  if (routePolyline && mapAdapter) {
    routePoints = mapAdapter.getPolylinePath(routePolyline).map(p => ({ lat: p.lat, lng: p.lng }));
    updateDistanceInfo();
    updateMessage(`已更新轨迹点：${routePoints.length}`);
  }
}

// 初始化自由手绘事件
function initFreehandDrawing() {
  if (!mapAdapter) return;
  const mapContainer = mapAdapter.getContainer();
  
  // 点击地图其他空白处时，关闭折线编辑器
  mapAdapter.onMapEvent('click', function() {
    closePolylineEditor();
  });

  mapAdapter.onMapEvent('mousedown', function(e) {
    // 忽略右键点击（开始绘制仅限左键或其他事件）
    if (e.originEvent && (e.originEvent.button === 2 || e.originEvent.which === 3)) return;

    if (!isDrawingMode) return;
    
    if (isDrawing) return; // 已经在绘制中，忽略重复左键点击

    isDrawing = true;
    
    const lnglat = mapAdapter.eventLngLat(e);
    drawingPoints = [mapAdapter.point(lnglat.lng, lnglat.lat)];
    
    // 创建临时折线
    if (routePolyline) {
      mapAdapter.removeOverlay(routePolyline);
    }
    routePolyline = mapAdapter.createPolyline(drawingPoints);
    
    console.log('开始绘制，起点:', drawingPoints[0]);
  });
  
  // 鼠标移动时添加点
  mapAdapter.onMapEvent('mousemove', function(e) {
    if (!isDrawingMode || !isDrawing) return;
    
    const lnglat = mapAdapter.eventLngLat(e);
    drawingPoints.push(mapAdapter.point(lnglat.lng, lnglat.lat));
    mapAdapter.setPolylinePath(routePolyline, drawingPoints);
  });
  
  const finishDrawing = () => {
    if (drawingPoints.length > 2) {
      // 保留原本绘制的图形，不进行直线转换(不执行简化算法)
      mapAdapter.setPolylinePath(routePolyline, drawingPoints);
      routePoints = mapAdapter.getPolylinePath(routePolyline).map(p => ({ lat: p.lat, lng: p.lng }));
      
      // 添加轨迹的点击编辑事件
      mapAdapter.onPolylineClick(routePolyline, function(evt) {
        // 防止事件冒泡到map的click上
        if (evt && evt.cancelBubble !== undefined) evt.cancelBubble = true;
        
        closePolylineEditor();
        polyEditor = mapAdapter.enablePolylineEdit(routePolyline, syncRouteFromPolyline);
      });
      
      updateDistanceInfo();
      updateMessage(`已获取轨迹点：${routePoints.length}`);
    } else {
      // 如果仅仅是误点击，没有绘制出有效轨迹，则撤销刚才的无效临时线
      if (routePolyline) {
        mapAdapter.removeOverlay(routePolyline);
        routePolyline = null;
      }
      drawingPoints = [];
    }

    // 无论是否绘制成功，结束绘制后都退出绘图模式
    isDrawingMode = false;
    const btn = document.getElementById("freehandBtn");
    btn.textContent = "自由手绘";
    btn.style.background = "";
    mapAdapter.setCursor('default');
    // 重新启用地图拖动
    mapAdapter.enableDragging();
  };

  mapContainer.addEventListener('mouseup', function() {
    if (isDrawingMode && isDrawing) {
      isDrawing = false;
      finishDrawing();
    }
  });

  // 监听容器的右键事件，这样即使在路径线上右击也能成功识别
  mapContainer.addEventListener('contextmenu', function(e) {
    if (isDrawingMode) {
      e.preventDefault(); // 防止弹出浏览器默认菜单
      if (isDrawing) {
        isDrawing = false;
        console.log('结束绘制，点数:', drawingPoints.length);
        finishDrawing();
      }
    }
  });
}

// 简化路径（Douglas-Peucker算法简化版）
function simplifyPath(points, tolerance) {
  if (points.length <= 2) return points;
  
  const result = [points[0]];
  for (let i = 1; i < points.length - 1; i++) {
    const prev = points[i - 1];
    const curr = points[i];
    const next = points[i + 1];
    
    // 计算点到线段的距离
    const dist = pointToLineDistance(curr, prev, next);
    if (dist > tolerance) {
      result.push(curr);
    }
  }
  result.push(points[points.length - 1]);
  return result;
}

// 计算点到线段的距离
function pointToLineDistance(point, lineStart, lineEnd) {
  const [x, y] = point;
  const [x1, y1] = lineStart;
  const [x2, y2] = lineEnd;
  
  const A = x - x1;
  const B = y - y1;
  const C = x2 - x1;
  const D = y2 - y1;
  
  const dot = A * C + B * D;
  const lenSq = C * C + D * D;
  
  if (lenSq === 0) {
    return Math.sqrt(A * A + B * B);
  }
  
  const param = dot / lenSq;
  let xx, yy;
  
  if (param < 0) {
    xx = x1;
    yy = y1;
  } else if (param > 1) {
    xx = x2;
    yy = y2;
  } else {
    xx = x1 + param * C;
    yy = y1 + param * D;
  }
  
  const dx = x - xx;
  const dy = y - yy;
  return Math.sqrt(dx * dx + dy * dy);
}

// 切换绘图模式
function toggleDrawingMode() {
  const btn = document.getElementById("freehandBtn");

  if (!mapAdapter) {
    updateMessage("地图源尚未加载完成，请先检查地图 Key", true);
    return;
  }
  
  if (isDrawingMode) {
    // 退出绘图模式
    isDrawingMode = false;
    isDrawing = false;
    btn.textContent = "自由手绘";
    btn.style.background = "";
    mapAdapter.setCursor('default');
    // 恢复地图拖动
    mapAdapter.enableDragging();
    updateMessage("已退出手绘模式");
  } else {
    // 进入绘图模式
    // 先清除旧路线
    if (routePolyline) {
      mapAdapter.removeOverlay(routePolyline);
      routePolyline = null;
    }
    closePolylineEditor();
    routePoints = [];
    drawingPoints = [];
    
    isDrawingMode = true;
    btn.textContent = "退出手绘";
    btn.style.background = CONFIG.COLORS.TRAJECTORY;
    mapAdapter.setCursor('crosshair');
    // 禁用地图拖动，避免干扰绘图
    mapAdapter.disableDragging();
    
    updateMessage("自由手绘模式：按住鼠标左键拖动绘制轨迹，松开结束");
    console.log('进入绘图模式');
  }
}

// 清除轨迹
function clearRoute() {
  if (!mapAdapter) {
    routePoints = [];
    updateDistanceInfo();
    updateMessage("轨迹已清除");
    return;
  }

  if (routePolyline) {
    mapAdapter.removeOverlay(routePolyline);
    routePolyline = null;
  }
  closePolylineEditor();
  routePoints = [];
  updateMessage("轨迹已清除");
  updateDistanceInfo();
}

// 日期转本地输入值
function dateToLocalInputValue(d) {
  const tzOffset = d.getTimezoneOffset();
  const local = new Date(d.getTime() - tzOffset * 60000);
  return local.toISOString().slice(0, 16);
}

// 重建导出时间列表
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

// 获取活动参数
function getActivityParams() {
  const hrRest = parseInt(document.getElementById("hrRest").value, 10) || CONFIG.HEART_RATE.REST_DEFAULT;
  const hrMax = parseInt(document.getElementById("hrMax").value, 10) || CONFIG.HEART_RATE.MAX_DEFAULT;
  const lapCount = Math.max(
    CONFIG.LAP.MIN,
    parseInt(document.getElementById("lapCount")?.value, 10) || CONFIG.LAP.DEFAULT
  );
  return { hrRest, hrMax, lapCount };
}

// 生成FIT文件
async function generateFit() {
  if (routePoints.length < 2) {
    updateMessage("请至少在地图上绘制两个点形成轨迹", true);
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

// 渲染预览图表
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

// 更新实时信息
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

// 开始预览回放
function startPreviewPlayback() {
  const samples = previewData?.samples || [];
  if (!samples.length || !mapAdapter) return;

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
      mapAdapter.movePreviewMarker(previewMarker, mapAdapter.point(s.lng, s.lat));
    }
    updateLiveInfo(s);
    previewIndex += 1;
  }, CONFIG.PREVIEW.STEP_MS);
}

// 预览活动
async function previewActivity() {
  if (!mapAdapter) {
    updateMessage("地图源尚未加载完成，请先检查地图 Key", true);
    return;
  }

  if (routePoints.length < 2) {
    updateMessage("请至少在地图上绘制两个点形成轨迹", true);
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
      mapAdapter.removeOverlay(previewMarker);
      previewMarker = null;
    }
    const samples = previewData.samples || [];
    if (samples.length > 0) {
      const first = samples[0];
      previewMarker = mapAdapter.createPreviewMarker(mapAdapter.point(first.lng, first.lat));
      startPreviewPlayback();
    }
  } catch (e) {
    console.error(e);
    updateMessage("预览请求失败，请稍后重试", true);
  }
}

// 绑定事件
document.getElementById("clearRoute").addEventListener("click", clearRoute);
document.getElementById("freehandBtn").addEventListener("click", toggleDrawingMode);
document.getElementById("generateFit").addEventListener("click", generateFit);
document.getElementById("previewBtn").addEventListener("click", previewActivity);
document.getElementById("lapCount").addEventListener("input", updateDistanceInfo);
document.getElementById("exportCount").addEventListener("input", rebuildExportTimes);
document.getElementById("mapProvider").addEventListener("change", (e) => {
  initMap(e.target.value);
});

// 初始化
async function bootstrapApp() {
  initSearch();
  updateDistanceInfo();
  rebuildExportTimes();
  await loadMapRuntimeConfig();
  initMap(activeProvider);
}

bootstrapApp();
