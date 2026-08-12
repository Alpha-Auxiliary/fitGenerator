use serde::{Deserialize, Serialize};

pub const SCHEMA_VERSION: u32 = 1;
pub const CORE_API_VERSION: u32 = 1;
pub const ALGORITHM_VERSION: u32 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RouteMode {
    CloseIfNeeded,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GeoPoint {
    pub lat: f64,
    pub lng: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ActivityRequest {
    pub schema_version: u32,
    pub start_time_utc: String,
    pub points: Vec<GeoPoint>,
    pub pace_seconds_per_km: f64,
    pub hr_rest: u8,
    pub hr_max: u8,
    pub lap_count: u16,
    pub variant_index: u16,
    pub seed: u64,
    pub route_mode: RouteMode,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActivitySample {
    pub time_ms: u64,
    pub distance_cm: u64,
    pub speed_mm_per_sec: u32,
    pub heart_rate_bpm: u8,
    pub position_lat_semicircles: i32,
    pub position_long_semicircles: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LapModel {
    pub index: u16,
    pub start_sample: usize,
    pub end_sample: usize,
    pub distance_cm: u64,
    pub duration_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActivityModel {
    pub schema_version: u32,
    pub algorithm_version: u32,
    pub start_time_utc: String,
    pub seed: u64,
    pub total_distance_cm: u64,
    pub total_duration_ms: u64,
    pub laps: Vec<LapModel>,
    pub samples: Vec<ActivitySample>,
}

#[cfg(test)]
mod tests {
    use super::{ActivityRequest, RouteMode};

    #[test]
    fn request_uses_stable_camel_case_json() {
        let json = r#"{"schemaVersion":1,"startTimeUtc":"2026-07-27T08:00:00Z","points":[{"lat":39.9042,"lng":116.4074},{"lat":39.9052,"lng":116.4084}],"paceSecondsPerKm":360.0,"hrRest":60,"hrMax":180,"lapCount":1,"variantIndex":1,"seed":42,"routeMode":"close_if_needed"}"#;

        let request: ActivityRequest =
            serde_json::from_str(json).expect("完整的 camelCase 请求应可反序列化");

        assert_eq!(request.schema_version, 1);
        assert_eq!(request.points.len(), 2);
        assert_eq!(request.route_mode, RouteMode::CloseIfNeeded);
    }
}
