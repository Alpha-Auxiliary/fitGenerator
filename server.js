import dotenv from "dotenv";
import express from "express";
import path from "path";
import { fileURLToPath } from "url";
import { Encoder, Profile } from "@garmin/fitsdk";

dotenv.config();

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = process.env.PORT || 3000;

app.use(express.json({ limit: "5mb" }));
app.use(express.static(path.join(__dirname, "public")));

const CONSTANTS = {
  FIT: {
    SEMICIRCLE_FACTOR: 2147483648 / 180
  },
  GEO: {
    EARTH_RADIUS_METERS: 6371000,
    METERS_PER_DEG_LAT: 111320
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
    DEFAULT_SECONDS_PER_KM: 360
  },
  LAP: {
    MIN_COUNT: 1,
    DEFAULT_COUNT: 1
  },
  ROUTE: {
    MIN_POINTS: 2,
    CLOSED_THRESHOLD_METERS: 5,
    NOISE_RADIUS_MIN: 5,
    NOISE_RADIUS_MAX: 10,
    ELLIPSE_STEPS: 64,
    HIGH_PRECISION_STEPS: 72
  },
  SPEED: {
    BASE_SPEED_FACTOR_MIN: 0.98,
    BASE_SPEED_FACTOR_RANGE: 0.06
  },
  PLAYBACK: {
    STEP_MS: 100
  },
  PREVIEW: {
    MIN_SAMPLES: 0
  }
};

function toSemicircles(deg) {
  return Math.round(deg * CONSTANTS.FIT.SEMICIRCLE_FACTOR);
}

function haversineDistance(lat1, lon1, lat2, lon2) {
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
  return CONSTANTS.GEO.EARTH_RADIUS_METERS * c;
}

function offsetPointMeters(point, offsetLatMeters, offsetLonMeters) {
  const metersPerDegLon =
    CONSTANTS.GEO.METERS_PER_DEG_LAT *
    Math.cos((point.lat * Math.PI) / 180);
  return {
    lat: point.lat + offsetLatMeters / CONSTANTS.GEO.METERS_PER_DEG_LAT,
    lng: point.lng + offsetLonMeters / metersPerDegLon
  };
}

function buildClosedBasePoints(points) {
  if (!points || points.length < 2) return points || [];
  const first = points[0];
  const last = points[points.length - 1];
  const d = haversineDistance(first.lat, first.lng, last.lat, last.lng);
  if (d < CONSTANTS.ROUTE.CLOSED_THRESHOLD_METERS) {
    return points;
  }
  const closed = points.slice();
  closed.push({ lat: first.lat, lng: first.lng });
  return closed;
}

function computeSamples(allPoints, distances, totalDist, paceSecondsPerKm, hrRestVal, hrMaxVal) {
  const totalDistanceKm = totalDist / 1000;
  const targetDurationSec = totalDistanceKm * paceSecondsPerKm;

  const avgSpeedTarget = totalDist / targetDurationSec;
  const baseSpeedFactor = CONSTANTS.SPEED.BASE_SPEED_FACTOR_MIN + Math.random() * CONSTANTS.SPEED.BASE_SPEED_FACTOR_RANGE;
  const phase1 = Math.random() * Math.PI * 2;
  const phase2 = Math.random() * Math.PI * 2;

  const n = allPoints.length;
  const instSpeedRaw = new Array(n);
  const hrValues = new Array(n);

  let currentHr = hrRestVal;

  for (let i = 0; i < n; i++) {
    const frac = distances[i] / totalDist;

    const longWave = 0.04 * Math.sin(frac * Math.PI * 2 + phase1);
    const shortWave = 0.02 * Math.sin(frac * Math.PI * 6 + phase2);
    const speedRaw =
      avgSpeedTarget * baseSpeedFactor * (1 + longWave + shortWave);
    instSpeedRaw[i] = speedRaw;

    const effort = Math.min(
      1,
      Math.max(0, speedRaw / (avgSpeedTarget || 1e-6))
    );

    let intensityBase;
    if (frac < 0.1) {
      const f = frac / 0.1;
      intensityBase = 0.4 + 0.4 * f;
    } else if (frac < 0.8) {
      const f = (frac - 0.1) / 0.7;
      intensityBase = 0.8 + 0.05 * Math.sin(f * Math.PI * 2);
    } else {
      const f = (frac - 0.8) / 0.2;
      intensityBase = 0.85 + 0.1 * f;
    }

    const intensity = Math.min(
      1,
      Math.max(0, 0.7 * intensityBase + 0.3 * effort)
    );

    const hrTarget = hrRestVal + (hrMaxVal - hrRestVal) * intensity;
    currentHr += (hrTarget - currentHr) * 0.15;
    const hrJitter = (Math.random() - 0.5) * 3;
    const hrValue = Math.round(
      Math.min(hrMaxVal, Math.max(hrRestVal, currentHr + hrJitter))
    );
    hrValues[i] = hrValue;
  }

  const segDurationsRaw = new Array(Math.max(CONSTANTS.PREVIEW.MIN_SAMPLES, n - 1));
  let rawDuration = 0;
  for (let i = 1; i < n; i++) {
    const ds = distances[i] - distances[i - 1];
    const v = instSpeedRaw[i] > 0 ? instSpeedRaw[i] : avgSpeedTarget;
    const dt = ds / v;
    segDurationsRaw[i - 1] = dt;
    rawDuration += dt;
  }

  const scale = rawDuration > 0 ? targetDurationSec / rawDuration : 1;

  const samples = [];
  let t = 0;
  samples.push({
    timeSec: 0,
    distance: distances[0],
    speed: instSpeedRaw[0] / scale,
    heartRate: hrValues[0],
    lat: allPoints[0].lat,
    lng: allPoints[0].lng
  });

  for (let i = 1; i < n; i++) {
    const dt = segDurationsRaw[i - 1] * scale;
    t += dt;
    samples.push({
      timeSec: t,
      distance: distances[i],
      speed: instSpeedRaw[i] / scale,
      heartRate: hrValues[i],
      lat: allPoints[i].lat,
      lng: allPoints[i].lng
    });
  }

  const totalDurationSec = samples.length
    ? samples[samples.length - 1].timeSec
    : targetDurationSec;

  return { samples, totalDurationSec };
}

function validateRequest(req, res) {
  const { startTime, points } = req.body || {};

  if (!startTime) {
    return { valid: false, error: "缺少参数：需要 startTime" };
  }

  if (!points || !Array.isArray(points) || points.length < CONSTANTS.ROUTE.MIN_POINTS) {
    return { valid: false, error: `缺少参数：需要至少 ${CONSTANTS.ROUTE.MIN_POINTS} 个轨迹点 points` };
  }

  const startDate = new Date(startTime);
  if (Number.isNaN(startDate.getTime())) {
    return { valid: false, error: "startTime 格式不正确" };
  }

  return { valid: true, startDate };
}

function parseActivityParams(body) {
  const paceSecondsPerKm = Number(body.paceSecondsPerKm) > 0 
    ? Number(body.paceSecondsPerKm) 
    : CONSTANTS.PACE.DEFAULT_SECONDS_PER_KM;
  
  const hrRestVal = Number.isFinite(Number(body.hrRest)) 
    ? Number(body.hrRest) 
    : CONSTANTS.HEART_RATE.REST_DEFAULT;
  
  const hrMaxVal = Number.isFinite(Number(body.hrMax)) 
    ? Number(body.hrMax) 
    : CONSTANTS.HEART_RATE.MAX_DEFAULT;
  
  const lapsRaw = Number(body.lapCount);
  const laps = Number.isFinite(lapsRaw) && lapsRaw > CONSTANTS.LAP.MIN_COUNT - 1
    ? Math.floor(lapsRaw)
    : CONSTANTS.LAP.DEFAULT_COUNT;
  
  const variantRaw = Number(body.variantIndex);
  const variant = Number.isFinite(variantRaw) && variantRaw > 0
    ? Math.floor(variantRaw)
    : 1;

  return { paceSecondsPerKm, hrRestVal, hrMaxVal, laps, variant };
}

function computeRoutePoints(basePoints, laps) {
  const allPoints = [];
  const usedLaps = laps > 0 ? laps : CONSTANTS.LAP.DEFAULT_COUNT;

  for (let lapIndex = 0; lapIndex < usedLaps; lapIndex++) {
    for (let i = 0; i < basePoints.length; i++) {
      const p = basePoints[i];
      allPoints.push(p);
    }
  }

  return allPoints;
}

function computeRoutePointsWithNoise(basePoints, laps) {
  const allPoints = [];
  const usedLaps = laps > 0 ? laps : CONSTANTS.LAP.DEFAULT_COUNT;

  for (let lapIndex = 0; lapIndex < usedLaps; lapIndex++) {
    const radiusMeters = CONSTANTS.ROUTE.NOISE_RADIUS_MIN + Math.random() * 
      (CONSTANTS.ROUTE.NOISE_RADIUS_MAX - CONSTANTS.ROUTE.NOISE_RADIUS_MIN);
    const angle = Math.random() * Math.PI * 2;
    const offsetLatMeters = radiusMeters * Math.cos(angle);
    const offsetLonMeters = radiusMeters * Math.sin(angle);

    for (let i = 0; i < basePoints.length; i++) {
      const p = basePoints[i];
      const noisyPoint =
        usedLaps === 1
          ? p
          : offsetPointMeters(p, offsetLatMeters, offsetLonMeters);
      allPoints.push(noisyPoint);
    }
  }

  return allPoints;
}

function computeDistancesAndTotal(allPoints) {
  const distances = [0];
  let totalDist = 0;
  for (let i = 1; i < allPoints.length; i++) {
    const d = haversineDistance(
      allPoints[i - 1].lat,
      allPoints[i - 1].lng,
      allPoints[i].lat,
      allPoints[i].lng
    );
    totalDist += d;
    distances.push(totalDist);
  }
  return { distances, totalDist };
}

function buildFitFile(encoder, startDate, totalDist, totalDurationSec, samples, allPoints) {
  encoder.onMesg(Profile.MesgNum.FILE_ID, {
    manufacturer: "development",
    product: 1,
    timeCreated: startDate,
    type: "activity"
  });

  encoder.onMesg(Profile.MesgNum.DEVICE_INFO, {
    timestamp: startDate,
    manufacturer: "development",
    product: 1,
    serialNumber: 1
  });

  const avgSpeed = totalDist / totalDurationSec;
  const sessionEnd = new Date(startDate.getTime() + totalDurationSec * 1000);

  encoder.onMesg(Profile.MesgNum.SESSION, {
    timestamp: sessionEnd,
    startTime: startDate,
    totalElapsedTime: totalDurationSec,
    totalTimerTime: totalDurationSec,
    totalDistance: totalDist,
    sport: "running",
    subSport: "generic",
    avgSpeed
  });

  encoder.onMesg(Profile.MesgNum.ACTIVITY, {
    timestamp: sessionEnd,
    totalTimerTime: totalDurationSec,
    numSessions: 1,
    type: "manual"
  });

  for (let i = 0; i < samples.length; i++) {
    const s = samples[i];
    const timestamp = new Date(startDate.getTime() + s.timeSec * 1000);

    encoder.onMesg(Profile.MesgNum.RECORD, {
      timestamp,
      positionLat: toSemicircles(allPoints[i].lat),
      positionLong: toSemicircles(allPoints[i].lng),
      distance: s.distance,
      speed: s.speed,
      heartRate: s.heartRate
    });
  }

  return encoder;
}

app.post("/api/preview", (req, res) => {
  try {
    const validation = validateRequest(req, res);
    if (!validation.valid) return;

    const { startDate } = validation;
    const { paceSecondsPerKm, hrRestVal, hrMaxVal, laps } = parseActivityParams(req.body);

    const basePoints = buildClosedBasePoints(req.body.points);
    const allPoints = computeRoutePoints(basePoints, laps);

    const { distances, totalDist } = computeDistancesAndTotal(allPoints);

    if (totalDist === 0) {
      return res.status(400).json({ error: "轨迹距离为 0，请绘制更长的路线" });
    }

    const { samples, totalDurationSec } = computeSamples(
      allPoints,
      distances,
      totalDist,
      paceSecondsPerKm,
      hrRestVal,
      hrMaxVal
    );

    return res.json({
      totalDistanceMeters: totalDist,
      totalDurationSec,
      samples
    });
  } catch (e) {
    console.error("Preview generation error:", e);
    return res.status(500).json({ error: "生成预览失败" });
  }
});

app.post("/api/generate-fit", (req, res) => {
  try {
    const validation = validateRequest(req, res);
    if (!validation.valid) return;

    const { startDate } = validation;
    const { paceSecondsPerKm, hrRestVal, hrMaxVal, laps, variant } = parseActivityParams(req.body);

    const basePoints = buildClosedBasePoints(req.body.points);
    const allPoints = computeRoutePointsWithNoise(basePoints, laps);

    const { distances, totalDist } = computeDistancesAndTotal(allPoints);

    if (totalDist === 0) {
      return res.status(400).json({ error: "轨迹距离为 0，请绘制更长的路线" });
    }

    const { samples, totalDurationSec } = computeSamples(
      allPoints,
      distances,
      totalDist,
      paceSecondsPerKm,
      hrRestVal,
      hrMaxVal
    );

    const encoder = new Encoder();
    buildFitFile(encoder, startDate, totalDist, totalDurationSec, samples, allPoints);

    const uint8Array = encoder.close();
    const buffer = Buffer.from(uint8Array);

    res.setHeader("Content-Type", "application/vnd.ant.fit");
    res.setHeader(
      "Content-Disposition",
      `attachment; filename=run_${variant}.fit`
    );
    return res.send(buffer);
  } catch (e) {
    console.error("FIT file generation error:", e);
    return res.status(500).json({ error: "生成 FIT 文件失败" });
  }
});

app.listen(PORT, () => {
  console.log(`Server listening on http://localhost:${PORT}`);
});
