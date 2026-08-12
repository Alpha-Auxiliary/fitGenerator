use fit_generator_core::{
    domain::{ActivityRequest, GeoPoint, RouteMode, SCHEMA_VERSION},
    error::ErrorCode,
    validation::ValidatedRequest,
};

fn valid_request() -> ActivityRequest {
    ActivityRequest {
        schema_version: SCHEMA_VERSION,
        start_time_utc: "2026-07-27T08:00:00Z".to_owned(),
        points: vec![
            GeoPoint {
                lat: 39.9042,
                lng: 116.4074,
            },
            GeoPoint {
                lat: 39.9052,
                lng: 116.4084,
            },
        ],
        pace_seconds_per_km: 360.0,
        hr_rest: 60,
        hr_max: 180,
        lap_count: 1,
        variant_index: 1,
        seed: 42,
        route_mode: RouteMode::CloseIfNeeded,
    }
}

fn assert_error(request: ActivityRequest, code: ErrorCode, message: &str) {
    let error = ValidatedRequest::try_from(request).expect_err("请求应被拒绝");
    assert_eq!(error.code(), code);
    assert_eq!(error.to_string(), message);
}

#[test]
fn accepts_a_valid_request_and_exposes_read_only_fields() {
    let validated = ValidatedRequest::try_from(valid_request()).expect("有效请求应通过");

    assert_eq!(validated.schema_version(), SCHEMA_VERSION);
    assert_eq!(validated.start_time_utc(), "2026-07-27T08:00:00Z");
    assert_eq!(validated.start_time().offset().whole_seconds(), 0);
    assert_eq!(validated.points().len(), 2);
    assert_eq!(validated.pace_seconds_per_km(), 360.0);
    assert_eq!(validated.hr_rest(), 60);
    assert_eq!(validated.hr_max(), 180);
    assert_eq!(validated.lap_count(), 1);
    assert_eq!(validated.variant_index(), 1);
    assert_eq!(validated.seed(), 42);
    assert_eq!(validated.route_mode(), RouteMode::CloseIfNeeded);
}

#[test]
fn rejects_reversed_heart_rate_range() {
    let mut request = valid_request();
    request.hr_rest = 100;
    request.hr_max = 100;

    assert_error(request, ErrorCode::InvalidInput, "最大心率必须高于静息心率");
}

#[test]
fn rejects_nan_coordinate() {
    let mut request = valid_request();
    request.points[0].lat = f64::NAN;

    assert_error(request, ErrorCode::InvalidInput, "轨迹坐标必须是有限数值");
}

#[test]
fn rejects_validation_boundaries_with_contract_messages() {
    struct Case {
        name: &'static str,
        request: ActivityRequest,
        code: ErrorCode,
        message: &'static str,
    }

    let mut schema = valid_request();
    schema.schema_version = 0;

    let mut one_point = valid_request();
    one_point.points.truncate(1);

    let mut too_many_points = valid_request();
    too_many_points.points = vec![GeoPoint { lat: 0.0, lng: 0.0 }; 50_001];

    let mut infinity = valid_request();
    infinity.points[0].lng = f64::INFINITY;
    let mut nan = valid_request();
    nan.points[0].lat = f64::NAN;

    let mut latitude_below = valid_request();
    latitude_below.points[0].lat = -90.000_001;
    let mut latitude_above = valid_request();
    latitude_above.points[0].lat = 90.000_001;

    let mut longitude_below = valid_request();
    longitude_below.points[0].lng = -180.000_001;
    let mut longitude_above = valid_request();
    longitude_above.points[0].lng = 180.000_001;

    let mut expanded_samples = valid_request();
    expanded_samples.points = vec![GeoPoint { lat: 0.0, lng: 0.0 }; 50_000];
    expanded_samples.lap_count = 11;

    let mut rest_below = valid_request();
    rest_below.hr_rest = 29;
    let mut rest_above = valid_request();
    rest_above.hr_rest = 121;

    let mut max_below = valid_request();
    max_below.hr_max = 99;
    let mut max_above = valid_request();
    max_above.hr_max = 221;

    let mut max_equals_rest = valid_request();
    max_equals_rest.hr_rest = 100;
    max_equals_rest.hr_max = 100;
    let mut max_below_rest = valid_request();
    max_below_rest.hr_rest = 101;
    max_below_rest.hr_max = 100;

    let mut pace_nan = valid_request();
    pace_nan.pace_seconds_per_km = f64::NAN;
    let mut pace_below = valid_request();
    pace_below.pace_seconds_per_km = 59.999;
    let mut pace_above = valid_request();
    pace_above.pace_seconds_per_km = 3600.001;

    let mut laps_below = valid_request();
    laps_below.lap_count = 0;
    let mut laps_above = valid_request();
    laps_above.lap_count = 101;

    let mut too_many_points_and_laps = valid_request();
    too_many_points_and_laps.points = vec![GeoPoint { lat: 0.0, lng: 0.0 }; 50_000];
    too_many_points_and_laps.lap_count = 101;

    let mut variant_zero = valid_request();
    variant_zero.variant_index = 0;

    let mut no_z = valid_request();
    no_z.start_time_utc = "2026-07-27T08:00:00+00:00".to_owned();
    let mut non_utc_offset = valid_request();
    non_utc_offset.start_time_utc = "2026-07-27T16:00:00+08:00".to_owned();
    let mut invalid_date = valid_request();
    invalid_date.start_time_utc = "2026-02-30T08:00:00Z".to_owned();

    let cases = [
        Case {
            name: "schema",
            request: schema,
            code: ErrorCode::UnsupportedSchema,
            message: "不支持的数据版本：0",
        },
        Case {
            name: "one point",
            request: one_point,
            code: ErrorCode::InvalidInput,
            message: "轨迹点数量必须在 2 到 50000 之间",
        },
        Case {
            name: "too many points",
            request: too_many_points,
            code: ErrorCode::InvalidInput,
            message: "轨迹点数量必须在 2 到 50000 之间",
        },
        Case {
            name: "infinite coordinate",
            request: infinity,
            code: ErrorCode::InvalidInput,
            message: "轨迹坐标必须是有限数值",
        },
        Case {
            name: "NaN coordinate",
            request: nan,
            code: ErrorCode::InvalidInput,
            message: "轨迹坐标必须是有限数值",
        },
        Case {
            name: "latitude below",
            request: latitude_below,
            code: ErrorCode::InvalidInput,
            message: "纬度必须在 -90 到 90 之间",
        },
        Case {
            name: "latitude above",
            request: latitude_above,
            code: ErrorCode::InvalidInput,
            message: "纬度必须在 -90 到 90 之间",
        },
        Case {
            name: "longitude below",
            request: longitude_below,
            code: ErrorCode::InvalidInput,
            message: "经度必须在 -180 到 180 之间",
        },
        Case {
            name: "longitude above",
            request: longitude_above,
            code: ErrorCode::InvalidInput,
            message: "经度必须在 -180 到 180 之间",
        },
        Case {
            name: "expanded samples",
            request: expanded_samples,
            code: ErrorCode::ResourceLimit,
            message: "展开后的采样点数量不能超过 500000",
        },
        Case {
            name: "rest below",
            request: rest_below,
            code: ErrorCode::InvalidInput,
            message: "静息心率必须在 30 到 120 之间",
        },
        Case {
            name: "rest above",
            request: rest_above,
            code: ErrorCode::InvalidInput,
            message: "静息心率必须在 30 到 120 之间",
        },
        Case {
            name: "max below",
            request: max_below,
            code: ErrorCode::InvalidInput,
            message: "最大心率必须在 100 到 220 之间",
        },
        Case {
            name: "max above",
            request: max_above,
            code: ErrorCode::InvalidInput,
            message: "最大心率必须在 100 到 220 之间",
        },
        Case {
            name: "max equals rest",
            request: max_equals_rest,
            code: ErrorCode::InvalidInput,
            message: "最大心率必须高于静息心率",
        },
        Case {
            name: "max below rest",
            request: max_below_rest,
            code: ErrorCode::InvalidInput,
            message: "最大心率必须高于静息心率",
        },
        Case {
            name: "pace nan",
            request: pace_nan,
            code: ErrorCode::InvalidInput,
            message: "配速必须在 60 到 3600 秒/公里之间",
        },
        Case {
            name: "pace below",
            request: pace_below,
            code: ErrorCode::InvalidInput,
            message: "配速必须在 60 到 3600 秒/公里之间",
        },
        Case {
            name: "pace above",
            request: pace_above,
            code: ErrorCode::InvalidInput,
            message: "配速必须在 60 到 3600 秒/公里之间",
        },
        Case {
            name: "laps below",
            request: laps_below,
            code: ErrorCode::InvalidInput,
            message: "圈数必须在 1 到 100 之间",
        },
        Case {
            name: "laps above",
            request: laps_above,
            code: ErrorCode::InvalidInput,
            message: "圈数必须在 1 到 100 之间",
        },
        Case {
            name: "laps above before resource limit",
            request: too_many_points_and_laps,
            code: ErrorCode::InvalidInput,
            message: "圈数必须在 1 到 100 之间",
        },
        Case {
            name: "variant zero",
            request: variant_zero,
            code: ErrorCode::InvalidInput,
            message: "变体序号必须大于 0",
        },
        Case {
            name: "missing Z",
            request: no_z,
            code: ErrorCode::InvalidInput,
            message: "开始时间必须是以 Z 结尾的有效 UTC 时间",
        },
        Case {
            name: "non-UTC offset",
            request: non_utc_offset,
            code: ErrorCode::InvalidInput,
            message: "开始时间必须是以 Z 结尾的有效 UTC 时间",
        },
        Case {
            name: "invalid date",
            request: invalid_date,
            code: ErrorCode::InvalidInput,
            message: "开始时间必须是以 Z 结尾的有效 UTC 时间",
        },
    ];

    for case in cases {
        let error = match ValidatedRequest::try_from(case.request) {
            Ok(_) => panic!("case should be rejected: {}", case.name),
            Err(error) => error,
        };
        assert_eq!(error.code(), case.code, "case: {}", case.name);
        assert_eq!(error.to_string(), case.message, "case: {}", case.name);
    }
}

#[test]
fn accepts_inclusive_minimum_and_maximum_values() {
    let mut minimum = valid_request();
    minimum.points = vec![
        GeoPoint {
            lat: -90.0,
            lng: -180.0,
        },
        GeoPoint {
            lat: 90.0,
            lng: 180.0,
        },
    ];
    minimum.pace_seconds_per_km = 60.0;
    minimum.hr_rest = 30;
    minimum.hr_max = 100;
    minimum.lap_count = 1;
    minimum.variant_index = 1;
    ValidatedRequest::try_from(minimum).expect("所有最小边界值应被接受");

    let mut maximum = valid_request();
    maximum.points = vec![GeoPoint { lat: 0.0, lng: 0.0 }; 50_000];
    maximum.pace_seconds_per_km = 3600.0;
    maximum.hr_rest = 120;
    maximum.hr_max = 220;
    maximum.lap_count = 9;
    maximum.variant_index = u16::MAX;
    ValidatedRequest::try_from(maximum).expect("互不冲突的最大边界值应被接受");

    let mut lap_maximum = valid_request();
    lap_maximum.lap_count = 100;
    ValidatedRequest::try_from(lap_maximum).expect("圈数最大值应被接受");

    let mut exact_resource_limit = valid_request();
    exact_resource_limit.points = vec![GeoPoint { lat: 0.0, lng: 0.0 }; 49_999];
    exact_resource_limit.lap_count = 10;
    ValidatedRequest::try_from(exact_resource_limit)
        .expect("展开后的采样点数量恰好为 500000 时应被接受");
}
