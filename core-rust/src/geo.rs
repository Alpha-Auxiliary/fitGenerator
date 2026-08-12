use crate::{domain::GeoPoint, error::CoreError, rng::SplitMix64};

pub const EARTH_RADIUS_METERS: f64 = 6_371_000.0;
pub const METERS_PER_DEGREE_LAT: f64 = 111_320.0;
pub const CLOSED_THRESHOLD: f64 = 5.0;
pub const SEMICIRCLE_FACTOR: f64 = 2_147_483_648.0 / 180.0;
const MAX_VALID_FIT_SINT32: i32 = i32::MAX - 1;

const DISTANCE_FAILURE: &str = "轨迹距离计算失败";
const DISTANCE_ALLOCATION_FAILURE: &str = "轨迹距离数组需要的内存过大";
const EXPANSION_ALLOCATION_FAILURE: &str = "展开轨迹需要的内存过大";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LapRange {
    pub index: u16,
    pub start_sample: usize,
    pub end_sample: usize,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ExpandedRoute {
    pub points: Vec<GeoPoint>,
    pub laps: Vec<LapRange>,
}

/// Returns the great-circle distance between two WGS84 coordinate pairs in meters.
pub fn haversine_meters(lat1: f64, lng1: f64, lat2: f64, lng2: f64) -> f64 {
    let latitude_delta = (lat2 - lat1).to_radians();
    let longitude_delta = (lng2 - lng1).to_radians();
    let latitude1 = lat1.to_radians();
    let latitude2 = lat2.to_radians();
    let sin_latitude = libm::sin(latitude_delta / 2.0);
    let sin_longitude = libm::sin(longitude_delta / 2.0);
    let a = (sin_latitude * sin_latitude
        + libm::cos(latitude1) * libm::cos(latitude2) * sin_longitude * sin_longitude)
        .clamp(0.0, 1.0);

    EARTH_RADIUS_METERS * 2.0 * libm::asin(libm::sqrt(a))
}

/// Converts degrees to FIT semicircles, preserving FIT's shared representation of +/-180°.
pub fn to_semicircles(degrees: f64) -> i32 {
    if degrees.is_nan() {
        return 0;
    }

    let clamped = if degrees == f64::INFINITY {
        180.0
    } else if degrees == f64::NEG_INFINITY {
        -180.0
    } else {
        degrees.clamp(-180.0, 180.0)
    };

    if clamped == -180.0 || clamped == 180.0 {
        return i32::MIN;
    }

    let rounded = (clamped * SEMICIRCLE_FACTOR).round();
    if rounded <= i32::MIN as f64 {
        i32::MIN
    } else if rounded >= MAX_VALID_FIT_SINT32 as f64 {
        // FIT reserves 0x7fffffff as the invalid sentinel for sint32 fields.
        MAX_VALID_FIT_SINT32
    } else {
        rounded as i32
    }
}

/// Closes a validated route by appending its first point when its endpoints are at least five meters apart.
///
/// Callers normally obtain points from `ValidatedRequest`, which limits routes to 50,000 points.
/// This helper preserves its infallible API and therefore expects that validation boundary.
pub fn build_closed_route(points: &[GeoPoint]) -> Vec<GeoPoint> {
    if points.len() <= 1 {
        return points.to_vec();
    }

    let mut closed = points.to_vec();
    let first = points[0];
    let last = points[points.len() - 1];
    if haversine_meters(first.lat, first.lng, last.lat, last.lng) >= CLOSED_THRESHOLD {
        closed.push(first);
    }
    closed
}

/// Applies a north/east meter offset while keeping the result within geographic coordinate bounds.
pub fn offset_point_meters(point: &GeoPoint, north_meters: f64, east_meters: f64) -> GeoPoint {
    let lat = (point.lat + north_meters / METERS_PER_DEGREE_LAT).clamp(-90.0, 90.0);
    let cosine_latitude = libm::cos(point.lat.to_radians());
    let lng = if cosine_latitude.abs() <= f64::EPSILON {
        point.lng
    } else {
        normalize_longitude(point.lng + east_meters / (METERS_PER_DEGREE_LAT * cosine_latitude))
    };

    GeoPoint { lat, lng }
}

/// Builds cumulative segment distances beginning at zero.
pub fn cumulative_distances(points: &[GeoPoint]) -> Result<Vec<f64>, CoreError> {
    if points
        .iter()
        .any(|point| !point.lat.is_finite() || !point.lng.is_finite())
    {
        return Err(CoreError::SimulationFailed(DISTANCE_FAILURE.to_owned()));
    }

    let mut distances = Vec::new();
    distances
        .try_reserve(points.len())
        .map_err(|_| CoreError::ResourceLimit(DISTANCE_ALLOCATION_FAILURE.to_owned()))?;

    if points.is_empty() {
        return Ok(distances);
    }

    distances.push(0.0);
    let mut total = 0.0;
    for segment in points.windows(2) {
        let distance = haversine_meters(
            segment[0].lat,
            segment[0].lng,
            segment[1].lat,
            segment[1].lng,
        );
        if !distance.is_finite() {
            return Err(CoreError::SimulationFailed(DISTANCE_FAILURE.to_owned()));
        }

        total += distance;
        if !total.is_finite() {
            return Err(CoreError::SimulationFailed(DISTANCE_FAILURE.to_owned()));
        }
        distances.push(total);
    }

    Ok(distances)
}

/// Expands a closed route into deterministic, lightly offset laps.
pub fn expand_laps(
    base_closed: &[GeoPoint],
    lap_count: u16,
    rng: &mut SplitMix64,
) -> Result<ExpandedRoute, CoreError> {
    if base_closed.iter().any(|point| {
        !point.lat.is_finite()
            || !point.lng.is_finite()
            || !(-90.0..=90.0).contains(&point.lat)
            || !(-180.0..=180.0).contains(&point.lng)
    }) {
        return Err(CoreError::SimulationFailed(
            "基础轨迹包含无效坐标".to_owned(),
        ));
    }
    if base_closed.len() < 2 || base_closed.first() != base_closed.last() {
        return Err(CoreError::SimulationFailed(
            "基础轨迹必须闭合且至少包含两个点".to_owned(),
        ));
    }
    if lap_count == 0 {
        return Err(CoreError::SimulationFailed("圈数必须大于 0".to_owned()));
    }

    let base_length = base_closed.len();
    let lap_total = lap_count as usize;
    let total_points = base_length
        .checked_mul(lap_total)
        .ok_or_else(|| CoreError::ResourceLimit(EXPANSION_ALLOCATION_FAILURE.to_owned()))?;
    let mut points = Vec::new();
    points
        .try_reserve(total_points)
        .map_err(|_| CoreError::ResourceLimit(EXPANSION_ALLOCATION_FAILURE.to_owned()))?;
    let mut laps = Vec::new();
    laps.try_reserve(lap_total)
        .map_err(|_| CoreError::ResourceLimit(EXPANSION_ALLOCATION_FAILURE.to_owned()))?;

    points.extend_from_slice(base_closed);
    laps.push(LapRange {
        index: 0,
        start_sample: 0,
        end_sample: base_length - 1,
    });

    for lap_index in 1..lap_count {
        let radius = 5.0 + 5.0 * rng.next_unit_f64();
        let angle = core::f64::consts::TAU * rng.next_unit_f64();
        let north_meters = radius * libm::cos(angle);
        let east_meters = radius * libm::sin(angle);
        let start = (lap_index as usize)
            .checked_mul(base_length)
            .and_then(|value| value.checked_sub(1))
            .ok_or_else(|| CoreError::ResourceLimit(EXPANSION_ALLOCATION_FAILURE.to_owned()))?;
        let end = ((lap_index as usize) + 1)
            .checked_mul(base_length)
            .and_then(|value| value.checked_sub(1))
            .ok_or_else(|| CoreError::ResourceLimit(EXPANSION_ALLOCATION_FAILURE.to_owned()))?;

        for point in base_closed {
            points.push(offset_point_meters(point, north_meters, east_meters));
        }
        laps.push(LapRange {
            index: lap_index,
            start_sample: start,
            end_sample: end,
        });
    }

    Ok(ExpandedRoute { points, laps })
}

fn normalize_longitude(longitude: f64) -> f64 {
    let normalized = (longitude + 180.0).rem_euclid(360.0) - 180.0;
    if normalized == -180.0 && longitude > 0.0 {
        180.0
    } else {
        normalized
    }
}
