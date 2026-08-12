import { Encoder, Profile } from "@garmin/fitsdk";

export class ActivityInputError extends Error {
  constructor(message) {
    super(message);
    this.name = "ActivityInputError";
  }
}

export const FIT_CONSTANTS = {
  FIT: {
    SEMICIRCLE_FACTOR: 2147483648 / 180,
  },
  GEO: {
    EARTH_RADIUS_METERS: 6371000,
    METERS_PER_DEG_LAT: 111320,
  },
  HEART_RATE: {
    REST_DEFAULT: 60,
    MAX_DEFAULT: 180,
  },
  PACE: {
    DEFAULT_SECONDS_PER_KM: 360,
  },
  LAP: {
    MIN_COUNT: 1,
    DEFAULT_COUNT: 1,
  },
  ROUTE: {
    MIN_POINTS: 2,
    CLOSED_THRESHOLD_METERS: 5,
    NOISE_RADIUS_MIN: 5,
    NOISE_RADIUS_MAX: 10,
  },
  SPEED: {
    BASE_SPEED_FACTOR_MIN: 0.98,
    BASE_SPEED_FACTOR_RANGE: 0.06,
  },
  PREVIEW: {
    MIN_SAMPLES: 0,
  },
};

export function toSemicircles(deg) {
  return Math.round(deg * FIT_CONSTANTS.FIT.SEMICIRCLE_FACTOR);
}

export function haversineDistance(lat1, lon1, lat2, lon2) {
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
  return FIT_CONSTANTS.GEO.EARTH_RADIUS_METERS * c;
}

export function offsetPointMeters(point, offsetLatMeters, offsetLonMeters) {
  const metersPerDegLon =
    FIT_CONSTANTS.GEO.METERS_PER_DEG_LAT *
    Math.cos((point.lat * Math.PI) / 180);
  return {
    lat: point.lat + offsetLatMeters / FIT_CONSTANTS.GEO.METERS_PER_DEG_LAT,
    lng: point.lng + offsetLonMeters / metersPerDegLon,
  };
}

export function buildClosedBasePoints(points) {
  if (!points || points.length < 2) return points || [];
  const first = points[0];
  const last = points[points.length - 1];
  const distance = haversineDistance(first.lat, first.lng, last.lat, last.lng);
  if (distance < FIT_CONSTANTS.ROUTE.CLOSED_THRESHOLD_METERS) {
    return points;
  }

  const closed = points.slice();
  closed.push({ lat: first.lat, lng: first.lng });
  return closed;
}

export function parseActivityParams(body = {}) {
  const paceSecondsPerKm =
    Number(body.paceSecondsPerKm) > 0
      ? Number(body.paceSecondsPerKm)
      : FIT_CONSTANTS.PACE.DEFAULT_SECONDS_PER_KM;

  const hrRestVal = Number.isFinite(Number(body.hrRest))
    ? Number(body.hrRest)
    : FIT_CONSTANTS.HEART_RATE.REST_DEFAULT;

  const hrMaxVal = Number.isFinite(Number(body.hrMax))
    ? Number(body.hrMax)
    : FIT_CONSTANTS.HEART_RATE.MAX_DEFAULT;

  const lapsRaw = Number(body.lapCount);
  const laps =
    Number.isFinite(lapsRaw) && lapsRaw > FIT_CONSTANTS.LAP.MIN_COUNT - 1
      ? Math.floor(lapsRaw)
      : FIT_CONSTANTS.LAP.DEFAULT_COUNT;

  const variantRaw = Number(body.variantIndex);
  const variant =
    Number.isFinite(variantRaw) && variantRaw > 0 ? Math.floor(variantRaw) : 1;

  return { paceSecondsPerKm, hrRestVal, hrMaxVal, laps, variant };
}

export function validateActivityInput(body = {}) {
  const { startTime, points } = body;

  if (!startTime) {
    throw new ActivityInputError("缺少参数：需要 startTime");
  }

  if (
    !points ||
    !Array.isArray(points) ||
    points.length < FIT_CONSTANTS.ROUTE.MIN_POINTS
  ) {
    throw new ActivityInputError(
      `缺少参数：需要至少 ${FIT_CONSTANTS.ROUTE.MIN_POINTS} 个轨迹点 points`,
    );
  }

  const startDate = new Date(startTime);
  if (Number.isNaN(startDate.getTime())) {
    throw new ActivityInputError("startTime 格式不正确");
  }

  return { startDate, points };
}

export function computeRoutePoints(basePoints, laps) {
  const allPoints = [];
  const usedLaps = laps > 0 ? laps : FIT_CONSTANTS.LAP.DEFAULT_COUNT;

  for (let lapIndex = 0; lapIndex < usedLaps; lapIndex++) {
    for (let i = 0; i < basePoints.length; i++) {
      allPoints.push(basePoints[i]);
    }
  }

  return allPoints;
}

export function computeRoutePointsWithNoise(basePoints, laps) {
  const allPoints = [];
  const usedLaps = laps > 0 ? laps : FIT_CONSTANTS.LAP.DEFAULT_COUNT;

  for (let lapIndex = 0; lapIndex < usedLaps; lapIndex++) {
    const radiusMeters =
      FIT_CONSTANTS.ROUTE.NOISE_RADIUS_MIN +
      Math.random() *
        (FIT_CONSTANTS.ROUTE.NOISE_RADIUS_MAX -
          FIT_CONSTANTS.ROUTE.NOISE_RADIUS_MIN);
    const angle = Math.random() * Math.PI * 2;
    const offsetLatMeters = radiusMeters * Math.cos(angle);
    const offsetLonMeters = radiusMeters * Math.sin(angle);

    for (let i = 0; i < basePoints.length; i++) {
      const point = basePoints[i];
      const noisyPoint =
        usedLaps === 1
          ? point
          : offsetPointMeters(point, offsetLatMeters, offsetLonMeters);
      allPoints.push(noisyPoint);
    }
  }

  return allPoints;
}

export function computeDistancesAndTotal(allPoints) {
  const distances = [0];
  let totalDist = 0;

  for (let i = 1; i < allPoints.length; i++) {
    const distance = haversineDistance(
      allPoints[i - 1].lat,
      allPoints[i - 1].lng,
      allPoints[i].lat,
      allPoints[i].lng,
    );
    totalDist += distance;
    distances.push(totalDist);
  }

  return { distances, totalDist };
}

export function computeSamples(
  allPoints,
  distances,
  totalDist,
  paceSecondsPerKm,
  hrRestVal,
  hrMaxVal,
) {
  const totalDistanceKm = totalDist / 1000;
  const targetDurationSec = totalDistanceKm * paceSecondsPerKm;

  const avgSpeedTarget = totalDist / targetDurationSec;
  const baseSpeedFactor =
    FIT_CONSTANTS.SPEED.BASE_SPEED_FACTOR_MIN +
    Math.random() * FIT_CONSTANTS.SPEED.BASE_SPEED_FACTOR_RANGE;
  const phase1 = Math.random() * Math.PI * 2;
  const phase2 = Math.random() * Math.PI * 2;

  const pointCount = allPoints.length;
  const instSpeedRaw = new Array(pointCount);
  const hrValues = new Array(pointCount);
  let currentHr = hrRestVal;

  for (let i = 0; i < pointCount; i++) {
    const frac = distances[i] / totalDist;

    const longWave = 0.04 * Math.sin(frac * Math.PI * 2 + phase1);
    const shortWave = 0.02 * Math.sin(frac * Math.PI * 6 + phase2);
    const speedRaw =
      avgSpeedTarget * baseSpeedFactor * (1 + longWave + shortWave);
    instSpeedRaw[i] = speedRaw;

    const effort = Math.min(
      1,
      Math.max(0, speedRaw / (avgSpeedTarget || 1e-6)),
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
      Math.max(0, 0.7 * intensityBase + 0.3 * effort),
    );

    const hrTarget = hrRestVal + (hrMaxVal - hrRestVal) * intensity;
    currentHr += (hrTarget - currentHr) * 0.15;
    const hrJitter = (Math.random() - 0.5) * 3;
    hrValues[i] = Math.round(
      Math.min(hrMaxVal, Math.max(hrRestVal, currentHr + hrJitter)),
    );
  }

  const segDurationsRaw = new Array(
    Math.max(FIT_CONSTANTS.PREVIEW.MIN_SAMPLES, pointCount - 1),
  );
  let rawDuration = 0;
  for (let i = 1; i < pointCount; i++) {
    const ds = distances[i] - distances[i - 1];
    const v = instSpeedRaw[i] > 0 ? instSpeedRaw[i] : avgSpeedTarget;
    const dt = ds / v;
    segDurationsRaw[i - 1] = dt;
    rawDuration += dt;
  }

  const scale = rawDuration > 0 ? targetDurationSec / rawDuration : 1;
  const samples = [];
  let timeSec = 0;

  samples.push({
    timeSec: 0,
    distance: distances[0],
    speed: instSpeedRaw[0] / scale,
    heartRate: hrValues[0],
    lat: allPoints[0].lat,
    lng: allPoints[0].lng,
  });

  for (let i = 1; i < pointCount; i++) {
    timeSec += segDurationsRaw[i - 1] * scale;
    samples.push({
      timeSec,
      distance: distances[i],
      speed: instSpeedRaw[i] / scale,
      heartRate: hrValues[i],
      lat: allPoints[i].lat,
      lng: allPoints[i].lng,
    });
  }

  const totalDurationSec = samples.length
    ? samples[samples.length - 1].timeSec
    : targetDurationSec;

  return { samples, totalDurationSec };
}

export function buildActivityModel(body, options = {}) {
  const { startDate, points } = validateActivityInput(body);
  const { paceSecondsPerKm, hrRestVal, hrMaxVal, laps, variant } =
    parseActivityParams(body);
  const basePoints = buildClosedBasePoints(points);
  const allPoints = options.withNoise
    ? computeRoutePointsWithNoise(basePoints, laps)
    : computeRoutePoints(basePoints, laps);
  const { distances, totalDist } = computeDistancesAndTotal(allPoints);

  if (totalDist === 0) {
    throw new ActivityInputError("轨迹距离为 0，请绘制更长的路线");
  }

  const { samples, totalDurationSec } = computeSamples(
    allPoints,
    distances,
    totalDist,
    paceSecondsPerKm,
    hrRestVal,
    hrMaxVal,
  );

  return {
    startDate,
    variant,
    allPoints,
    distances,
    totalDist,
    totalDurationSec,
    samples,
  };
}

export function buildPreviewActivity(body) {
  const model = buildActivityModel(body, { withNoise: false });
  return {
    totalDistanceMeters: model.totalDist,
    totalDurationSec: model.totalDurationSec,
    samples: model.samples,
  };
}

export function writeFitMessages(
  encoder,
  startDate,
  totalDist,
  totalDurationSec,
  samples,
  allPoints,
) {
  encoder.onMesg(Profile.MesgNum.FILE_ID, {
    manufacturer: "development",
    product: 1,
    timeCreated: startDate,
    type: "activity",
  });

  encoder.onMesg(Profile.MesgNum.DEVICE_INFO, {
    timestamp: startDate,
    manufacturer: "development",
    product: 1,
    serialNumber: 1,
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
    avgSpeed,
  });

  encoder.onMesg(Profile.MesgNum.ACTIVITY, {
    timestamp: sessionEnd,
    totalTimerTime: totalDurationSec,
    numSessions: 1,
    type: "manual",
  });

  for (let i = 0; i < samples.length; i++) {
    const sample = samples[i];
    const timestamp = new Date(startDate.getTime() + sample.timeSec * 1000);

    encoder.onMesg(Profile.MesgNum.RECORD, {
      timestamp,
      positionLat: toSemicircles(allPoints[i].lat),
      positionLong: toSemicircles(allPoints[i].lng),
      distance: sample.distance,
      speed: sample.speed,
      heartRate: sample.heartRate,
    });
  }

  return encoder;
}

export function buildFitActivity(body) {
  const model = buildActivityModel(body, { withNoise: true });
  const encoder = new Encoder();

  writeFitMessages(
    encoder,
    model.startDate,
    model.totalDist,
    model.totalDurationSec,
    model.samples,
    model.allPoints,
  );

  return {
    fitBytes: encoder.close(),
    filename: `run_${model.variant}.fit`,
    totalDistanceMeters: model.totalDist,
    totalDurationSec: model.totalDurationSec,
  };
}
