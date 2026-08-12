use time::{
    format_description::well_known::Rfc3339,
    OffsetDateTime, UtcOffset,
};

use crate::{
    domain::{ActivityRequest, GeoPoint, RouteMode, SCHEMA_VERSION},
    error::CoreError,
};

pub const MIN_POINTS: usize = 2;
pub const MAX_POINTS: usize = 50_000;
pub const MAX_EXPANDED_SAMPLES: usize = 500_000;
pub const MIN_LAPS: u16 = 1;
pub const MAX_LAPS: u16 = 100;
pub const MIN_PACE_SECONDS_PER_KM: f64 = 60.0;
pub const MAX_PACE_SECONDS_PER_KM: f64 = 3600.0;

const INVALID_START_TIME: &str = "开始时间必须是以 Z 结尾的有效 UTC 时间";
const RESOURCE_LIMIT: &str = "展开后的采样点数量不能超过 500000";

#[derive(Debug, Clone)]
pub struct ValidatedRequest {
    request: ActivityRequest,
    start_time: OffsetDateTime,
}

impl TryFrom<ActivityRequest> for ValidatedRequest {
    type Error = CoreError;

    fn try_from(request: ActivityRequest) -> Result<Self, Self::Error> {
        if request.schema_version != SCHEMA_VERSION {
            return Err(CoreError::UnsupportedSchema(request.schema_version));
        }

        let start_time = parse_utc_start_time(&request.start_time_utc)?;

        validate_points(&request.points)?;

        if !(MIN_LAPS..=MAX_LAPS).contains(&request.lap_count) {
            return Err(CoreError::InvalidInput("圈数必须在 1 到 100 之间".to_owned()));
        }
        validate_expanded_sample_limit(request.points.len(), request.lap_count)?;

        if !(30..=120).contains(&request.hr_rest) {
            return Err(CoreError::InvalidInput(
                "静息心率必须在 30 到 120 之间".to_owned(),
            ));
        }
        if !(100..=220).contains(&request.hr_max) {
            return Err(CoreError::InvalidInput(
                "最大心率必须在 100 到 220 之间".to_owned(),
            ));
        }
        if request.hr_max <= request.hr_rest {
            return Err(CoreError::InvalidInput("最大心率必须高于静息心率".to_owned()));
        }

        if !request.pace_seconds_per_km.is_finite()
            || !(MIN_PACE_SECONDS_PER_KM..=MAX_PACE_SECONDS_PER_KM)
                .contains(&request.pace_seconds_per_km)
        {
            return Err(CoreError::InvalidInput(
                "配速必须在 60 到 3600 秒/公里之间".to_owned(),
            ));
        }

        if request.variant_index == 0 {
            return Err(CoreError::InvalidInput("变体序号必须大于 0".to_owned()));
        }

        Ok(Self {
            request,
            start_time,
        })
    }
}

impl ValidatedRequest {
    pub fn schema_version(&self) -> u32 {
        self.request.schema_version
    }

    pub fn start_time_utc(&self) -> &str {
        &self.request.start_time_utc
    }

    pub fn start_time(&self) -> OffsetDateTime {
        self.start_time
    }

    pub fn points(&self) -> &[GeoPoint] {
        &self.request.points
    }

    pub fn pace_seconds_per_km(&self) -> f64 {
        self.request.pace_seconds_per_km
    }

    pub fn hr_rest(&self) -> u8 {
        self.request.hr_rest
    }

    pub fn hr_max(&self) -> u8 {
        self.request.hr_max
    }

    pub fn lap_count(&self) -> u16 {
        self.request.lap_count
    }

    pub fn variant_index(&self) -> u16 {
        self.request.variant_index
    }

    pub fn seed(&self) -> u64 {
        self.request.seed
    }

    pub fn route_mode(&self) -> RouteMode {
        self.request.route_mode
    }
}

fn parse_utc_start_time(value: &str) -> Result<OffsetDateTime, CoreError> {
    if !value.ends_with('Z') {
        return Err(CoreError::InvalidInput(INVALID_START_TIME.to_owned()));
    }

    let parsed = OffsetDateTime::parse(value, &Rfc3339)
        .map_err(|_| CoreError::InvalidInput(INVALID_START_TIME.to_owned()))?;
    // Rfc3339 的 Z 会解析为 UTC；此检查保留为防御性约束。
    if parsed.offset() != UtcOffset::UTC {
        return Err(CoreError::InvalidInput(INVALID_START_TIME.to_owned()));
    }

    Ok(parsed)
}

fn validate_points(points: &[GeoPoint]) -> Result<(), CoreError> {
    if !(MIN_POINTS..=MAX_POINTS).contains(&points.len()) {
        return Err(CoreError::InvalidInput(
            "轨迹点数量必须在 2 到 50000 之间".to_owned(),
        ));
    }

    for point in points {
        if !point.lat.is_finite() || !point.lng.is_finite() {
            return Err(CoreError::InvalidInput("轨迹坐标必须是有限数值".to_owned()));
        }
        if !(-90.0..=90.0).contains(&point.lat) {
            return Err(CoreError::InvalidInput("纬度必须在 -90 到 90 之间".to_owned()));
        }
        if !(-180.0..=180.0).contains(&point.lng) {
            return Err(CoreError::InvalidInput("经度必须在 -180 到 180 之间".to_owned()));
        }
    }

    Ok(())
}

fn validate_expanded_sample_limit(point_count: usize, lap_count: u16) -> Result<(), CoreError> {
    let expanded_points = point_count
        .checked_add(1)
        .and_then(|count| count.checked_mul(usize::from(lap_count)))
        .ok_or_else(|| CoreError::ResourceLimit(RESOURCE_LIMIT.to_owned()))?;

    if expanded_points > MAX_EXPANDED_SAMPLES {
        return Err(CoreError::ResourceLimit(RESOURCE_LIMIT.to_owned()));
    }

    Ok(())
}
