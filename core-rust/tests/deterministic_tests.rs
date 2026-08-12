use fit_generator_core::rng::{
    SplitMix64, derive_variant_seed, quantize_distance_cm, quantize_speed_mm_per_sec,
    quantize_time_ms,
};
use fit_generator_core::{
    build_activity_model,
    domain::GeoPoint,
    error::CoreError,
    geo::{
        LapRange, build_closed_route, cumulative_distances, expand_laps, haversine_meters,
        offset_point_meters, to_semicircles,
    },
    parse_and_validate, preview_json,
};
use proptest::prelude::*;

const OPEN_TWO_LAPS: &[u8] = include_bytes!("fixtures/open_two_laps.json");

fn fixture(name: &str) -> &'static [u8] {
    match name {
        "closed_single_lap" => include_bytes!("fixtures/closed_single_lap.json"),
        "open_two_laps" => OPEN_TWO_LAPS,
        "short_legal" => include_bytes!("fixtures/short_legal.json"),
        "fast_low_hr" => include_bytes!("fixtures/fast_low_hr.json"),
        "invalid_heart_rate" => include_bytes!("fixtures/invalid_heart_rate.json"),
        _ => panic!("unknown fixture: {name}"),
    }
}

fn assert_model_invariants(
    model: &fit_generator_core::domain::ActivityModel,
    hr_rest: u8,
    hr_max: u8,
) {
    assert!(!model.samples.is_empty());
    assert!(
        model
            .samples
            .windows(2)
            .all(|pair| pair[0].time_ms <= pair[1].time_ms)
    );
    assert!(
        model
            .samples
            .windows(2)
            .all(|pair| pair[0].distance_cm <= pair[1].distance_cm)
    );
    assert!(
        model
            .samples
            .iter()
            .all(|sample| { (hr_rest..=hr_max).contains(&sample.heart_rate_bpm) })
    );
    assert_eq!(
        model.total_distance_cm,
        model.samples.last().unwrap().distance_cm
    );
    assert_eq!(
        model.total_duration_ms,
        model.samples.last().unwrap().time_ms
    );
}

#[test]
fn model_is_quantized_monotonic_and_reproducible() {
    let request = parse_and_validate(OPEN_TWO_LAPS).expect("fixture must validate");
    let first = build_activity_model(&request).expect("fixture must simulate");
    let second = build_activity_model(&request).expect("fixture must simulate twice");

    assert_eq!(first, second);
    assert_eq!(first.laps.len(), 2);
    assert_model_invariants(&first, 55, 190);
}

#[test]
fn activity_model_matches_canonical_public_contract() {
    let request = parse_and_validate(OPEN_TWO_LAPS).expect("fixture must validate");
    let model = build_activity_model(&request).expect("fixture must simulate");
    let serialized_once = serde_json::to_vec(&model).expect("model must serialize");
    let serialized_twice = serde_json::to_vec(&model).expect("model must serialize twice");
    let encoded_once = preview_json(OPEN_TWO_LAPS).expect("fixture must serialize");
    let encoded_twice = preview_json(OPEN_TWO_LAPS).expect("fixture must serialize twice");

    assert_eq!(model.algorithm_version, 1);
    assert_eq!(model.seed, 987_654_321);
    assert_eq!(model.laps.len(), 2);
    assert!(model.samples.len() >= 5);
    assert_eq!(model.samples[0].time_ms, 0);
    assert_eq!(model.samples[0].distance_cm, 0);
    assert_eq!(
        model.total_distance_cm,
        model.samples.last().unwrap().distance_cm
    );
    assert_eq!(
        model.total_duration_ms,
        model.samples.last().unwrap().time_ms
    );
    assert_eq!(model.laps.first().unwrap().start_sample, 0);
    assert_eq!(
        model.laps.last().unwrap().end_sample,
        model.samples.len() - 1
    );
    assert_eq!(
        model.laps.iter().map(|lap| lap.distance_cm).sum::<u64>(),
        model.total_distance_cm
    );
    assert_eq!(
        model.laps.iter().map(|lap| lap.duration_ms).sum::<u64>(),
        model.total_duration_ms
    );
    assert_eq!(serialized_once, serialized_twice);
    assert_eq!(encoded_once, encoded_twice);
}

#[test]
fn all_valid_fixtures_build_and_invalid_heart_rate_has_contract_error() {
    for name in [
        "closed_single_lap",
        "open_two_laps",
        "short_legal",
        "fast_low_hr",
    ] {
        let request = parse_and_validate(fixture(name)).expect("valid fixture must parse");
        build_activity_model(&request).expect("valid fixture must simulate");
    }

    assert!(matches!(
        parse_and_validate(fixture("invalid_heart_rate")),
        Err(CoreError::InvalidInput(message)) if message == "最大心率必须高于静息心率"
    ));
}

#[test]
fn malformed_or_unknown_json_is_rejected_with_the_public_parse_error() {
    for input in [
        b"not json".as_slice(),
        b"{\"schemaVersion\":1,\"startTimeUtc\":\"2026-07-27T08:00:00Z\",\"points\":[{\"lat\":0.0,\"lng\":0.0},{\"lat\":0.0,\"lng\":0.1}],\"paceSecondsPerKm\":360.0,\"hrRest\":60,\"hrMax\":180,\"lapCount\":1,\"variantIndex\":1,\"seed\":42,\"routeMode\":\"close_if_needed\",\"unexpected\":true}".as_slice(),
        &[0xff, 0xfe],
    ] {
        assert!(matches!(
            parse_and_validate(input),
            Err(CoreError::InvalidInput(message)) if message == "请求数据不是有效的 JSON"
        ));
    }
}

#[test]
fn model_duration_lap_ranges_and_integer_samples_follow_contract() {
    let request = parse_and_validate(OPEN_TWO_LAPS).expect("fixture must validate");
    let model = build_activity_model(&request).expect("fixture must simulate");
    let total_distance_m = cumulative_distances(
        &expand_laps(
            &build_closed_route(request.points()),
            request.lap_count(),
            &mut SplitMix64::new(derive_variant_seed(request.seed(), request.variant_index())),
        )
        .expect("fixture must expand")
        .points,
    )
    .expect("fixture must measure")
    .last()
    .copied()
    .unwrap();

    assert_eq!(
        model.total_duration_ms,
        (total_distance_m * request.pace_seconds_per_km()).round() as u64
    );
    for lap in &model.laps {
        assert!(lap.start_sample <= lap.end_sample);
        assert!(lap.end_sample < model.samples.len());
    }
    assert!(model.samples.iter().all(|sample| {
        sample.position_lat_semicircles as i64 >= i32::MIN as i64
            && sample.position_lat_semicircles as i64 <= i32::MAX as i64
            && sample.position_long_semicircles as i64 >= i32::MIN as i64
            && sample.position_long_semicircles as i64 <= i32::MAX as i64
    }));
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(64))]

    #[test]
    fn generated_valid_requests_produce_finite_monotonic_models(
        coordinates in prop::collection::vec((-89.0f64..89.0, -179.0f64..179.0), 2..20),
        lap_count in 1u16..4,
        pace in 60.0f64..3600.0,
        hr_rest in 30u8..=120,
        hr_max in 100u8..=220,
        variant_index in 1u16..u16::MAX,
        seed in any::<u64>(),
    ) {
        prop_assume!(coordinates.windows(2).any(|pair| {
            haversine_meters(pair[0].0, pair[0].1, pair[1].0, pair[1].1) > 5.0
        }));
        prop_assume!(hr_max > hr_rest);
        let request = fit_generator_core::domain::ActivityRequest {
            schema_version: 1,
            start_time_utc: "2026-07-27T08:00:00Z".to_owned(),
            points: coordinates.into_iter().map(|(lat, lng)| GeoPoint { lat, lng }).collect(),
            pace_seconds_per_km: pace,
            hr_rest,
            hr_max,
            lap_count,
            variant_index,
            seed,
            route_mode: fit_generator_core::domain::RouteMode::CloseIfNeeded,
        };
        let validated = fit_generator_core::validation::ValidatedRequest::try_from(request)
            .expect("generated request must validate");
        let model = build_activity_model(&validated).expect("generated request must simulate");

        assert_model_invariants(&model, hr_rest, hr_max);
    }
}

#[test]
fn splitmix64_has_stable_output() {
    let mut rng = SplitMix64::new(0);

    assert_eq!(rng.next_u64(), 0xe220_a839_7b1d_cdaf);
    assert_eq!(rng.next_u64(), 0x6e78_9e6a_a1b9_65f4);
}

#[test]
fn variant_seed_is_stable_and_distinct() {
    assert_eq!(derive_variant_seed(42, 1), derive_variant_seed(42, 1));
    assert_ne!(derive_variant_seed(42, 1), derive_variant_seed(42, 2));
}

#[test]
fn quantize_time_ms_rounds_and_clamps_boundaries() {
    assert_eq!(quantize_time_ms(0.0), 0);
    assert_eq!(quantize_time_ms(1.234), 1_234);
    assert_eq!(quantize_time_ms(0.000_5), 1);
    assert_eq!(quantize_time_ms(-1.0), 0);
    assert_eq!(quantize_time_ms(f64::NEG_INFINITY), 0);
    assert_eq!(quantize_time_ms(f64::NAN), 0);
    assert_eq!(quantize_time_ms(f64::INFINITY), u64::MAX);
}

#[test]
fn quantize_distance_cm_rounds_and_clamps_boundaries() {
    assert_eq!(quantize_distance_cm(0.0), 0);
    assert_eq!(quantize_distance_cm(12.34), 1_234);
    assert_eq!(quantize_distance_cm(0.005), 1);
    assert_eq!(quantize_distance_cm(-1.0), 0);
    assert_eq!(quantize_distance_cm(f64::NEG_INFINITY), 0);
    assert_eq!(quantize_distance_cm(f64::NAN), 0);
    assert_eq!(quantize_distance_cm(f64::INFINITY), u64::MAX);
}

#[test]
fn quantize_speed_mm_per_sec_rounds_and_clamps_boundaries() {
    assert_eq!(quantize_speed_mm_per_sec(0.0), 0);
    assert_eq!(quantize_speed_mm_per_sec(1.234), 1_234);
    assert_eq!(quantize_speed_mm_per_sec(0.000_5), 1);
    assert_eq!(quantize_speed_mm_per_sec(-1.0), 0);
    assert_eq!(quantize_speed_mm_per_sec(f64::NEG_INFINITY), 0);
    assert_eq!(quantize_speed_mm_per_sec(f64::NAN), 0);
    assert_eq!(quantize_speed_mm_per_sec(f64::INFINITY), u32::MAX);
}

fn beijing_points() -> [GeoPoint; 2] {
    [
        GeoPoint {
            lat: 39.9042,
            lng: 116.4074,
        },
        GeoPoint {
            lat: 39.9052,
            lng: 116.4084,
        },
    ]
}

#[test]
fn closes_an_open_route_once() {
    let points = beijing_points();
    let closed = build_closed_route(&points);

    assert_eq!(closed.len(), 3);
    assert_eq!(closed.first(), closed.last());
}

#[test]
fn already_closed_input_is_not_duplicated() {
    let points = beijing_points();
    let closed_input = vec![points[0], points[1], points[0]];

    assert_eq!(build_closed_route(&closed_input), closed_input);
}

#[test]
fn route_closure_uses_strict_five_meter_threshold() {
    const EARTH_RADIUS_METERS: f64 = 6_371_000.0;
    let first = GeoPoint { lat: 0.0, lng: 0.0 };
    let under_threshold = GeoPoint {
        lat: 0.0,
        lng: (4.999 / EARTH_RADIUS_METERS).to_degrees(),
    };
    let above_threshold = GeoPoint {
        lat: 0.0,
        lng: (5.001 / EARTH_RADIUS_METERS).to_degrees(),
    };

    assert!(
        haversine_meters(
            first.lat,
            first.lng,
            under_threshold.lat,
            under_threshold.lng
        ) < 5.0
    );
    assert_eq!(build_closed_route(&[first, under_threshold]).len(), 2);
    assert!(
        haversine_meters(
            first.lat,
            first.lng,
            above_threshold.lat,
            above_threshold.lng
        ) > 5.0
    );
    assert_eq!(build_closed_route(&[first, above_threshold]).len(), 3);
}

#[test]
fn haversine_matches_beijing_reference_distance() {
    let points = beijing_points();
    let distance = haversine_meters(points[0].lat, points[0].lng, points[1].lat, points[1].lng);

    assert!(
        (distance - 140.1).abs() < 1.0,
        "actual distance: {distance}"
    );
}

#[test]
fn semicircles_handle_boundary_values_and_round_trip_beijing() {
    let point = beijing_points()[0];

    assert_eq!(to_semicircles(180.0), i32::MIN);
    assert_eq!(to_semicircles(-180.0), i32::MIN);
    assert_eq!(to_semicircles(0.0), 0);
    assert_eq!(to_semicircles(f64::NAN), 0);
    assert_eq!(to_semicircles(f64::INFINITY), i32::MIN);
    assert_eq!(to_semicircles(f64::NEG_INFINITY), i32::MIN);
    assert_eq!(to_semicircles(179.999_999_99), i32::MAX - 1);
    assert_ne!(to_semicircles(179.999_999_99), i32::MAX);

    let round_trip = |degrees: f64| to_semicircles(degrees) as f64 * 180.0 / 2_147_483_648.0;
    assert!((round_trip(point.lat) - point.lat).abs() < 0.000_001);
    assert!((round_trip(point.lng) - point.lng).abs() < 0.000_001);
}

#[test]
fn cumulative_distances_start_at_zero_and_match_segment_sum() {
    let points = beijing_points();
    let closed = build_closed_route(&points);
    let distances = cumulative_distances(&closed).expect("finite route should calculate");
    let expected = haversine_meters(closed[0].lat, closed[0].lng, closed[1].lat, closed[1].lng)
        + haversine_meters(closed[1].lat, closed[1].lng, closed[2].lat, closed[2].lng);

    assert_eq!(distances[0], 0.0);
    assert!(distances.iter().all(|distance| distance.is_finite()));
    assert!(distances.windows(2).all(|pair| pair[0] <= pair[1]));
    assert_eq!(
        *distances.last().expect("closed route has samples"),
        expected
    );
}

#[test]
fn single_lap_preserves_base_route_without_rng_offset() {
    let closed = build_closed_route(&beijing_points());
    let mut rng = SplitMix64::new(17);
    let expanded = expand_laps(&closed, 1, &mut rng).expect("valid closed route expands");

    assert_eq!(expanded.points, closed);
    assert_eq!(
        expanded.laps,
        vec![LapRange {
            index: 0,
            start_sample: 0,
            end_sample: 2,
        }]
    );
}

#[test]
fn two_laps_have_overlapping_ranges_and_deterministic_offset() {
    let closed = build_closed_route(&beijing_points());
    let mut rng = SplitMix64::new(42);
    let expanded = expand_laps(&closed, 2, &mut rng).expect("valid closed route expands");
    let mut expected_rng = SplitMix64::new(42);
    let radius = 5.0 + 5.0 * expected_rng.next_unit_f64();
    let angle = core::f64::consts::TAU * expected_rng.next_unit_f64();
    let expected_first_offset = offset_point_meters(
        &closed[0],
        radius * libm::cos(angle),
        radius * libm::sin(angle),
    );

    assert_eq!(expanded.points.len(), 6);
    assert_eq!(
        expanded.laps[0],
        LapRange {
            index: 0,
            start_sample: 0,
            end_sample: 2,
        }
    );
    assert_eq!(
        expanded.laps[1],
        LapRange {
            index: 1,
            start_sample: 2,
            end_sample: 5,
        }
    );
    assert_eq!(expanded.points[3], expected_first_offset);
    assert_eq!(expanded.points[3], expanded.points[5]);
    assert!(expanded.points.iter().all(|point| {
        point.lat.is_finite()
            && point.lng.is_finite()
            && (-90.0..=90.0).contains(&point.lat)
            && (-180.0..=180.0).contains(&point.lng)
    }));
}

#[test]
fn cumulative_distances_handle_empty_and_nonfinite_coordinates() {
    assert!(
        cumulative_distances(&[])
            .expect("empty route has no distances")
            .is_empty()
    );

    for point in [
        GeoPoint {
            lat: f64::NAN,
            lng: 0.0,
        },
        GeoPoint {
            lat: 0.0,
            lng: f64::INFINITY,
        },
    ] {
        assert!(matches!(
            cumulative_distances(&[point]),
            Err(CoreError::SimulationFailed(message)) if message == "轨迹距离计算失败"
        ));
    }
}

#[test]
fn offsets_near_the_poles_remain_finite_and_bounded() {
    for point in [
        GeoPoint {
            lat: 89.999_999,
            lng: 179.999,
        },
        GeoPoint {
            lat: -89.999_999,
            lng: -179.999,
        },
    ] {
        let offset = offset_point_meters(&point, 500.0, 100.0);

        assert!(offset.lat.is_finite() && offset.lng.is_finite());
        assert!((-90.0..=90.0).contains(&offset.lat));
        assert!((-180.0..=180.0).contains(&offset.lng));
    }
}

#[test]
fn lap_expansion_rejects_invalid_base_routes_and_zero_laps() {
    let mut rng = SplitMix64::new(9);
    let too_short = [GeoPoint { lat: 0.0, lng: 0.0 }];
    assert!(matches!(
        expand_laps(&too_short, 1, &mut rng),
        Err(CoreError::SimulationFailed(message)) if message == "基础轨迹必须闭合且至少包含两个点"
    ));

    let non_closed = beijing_points();
    assert!(matches!(
        expand_laps(&non_closed, 1, &mut rng),
        Err(CoreError::SimulationFailed(message)) if message == "基础轨迹必须闭合且至少包含两个点"
    ));

    let closed = build_closed_route(&beijing_points());
    assert!(matches!(
        expand_laps(&closed, 0, &mut rng),
        Err(CoreError::SimulationFailed(message)) if message == "圈数必须大于 0"
    ));
}

#[test]
fn lap_expansion_rejects_invalid_closed_base_coordinates() {
    for point in [
        GeoPoint {
            lat: f64::NAN,
            lng: 0.0,
        },
        GeoPoint {
            lat: 0.0,
            lng: f64::INFINITY,
        },
        GeoPoint {
            lat: 90.001,
            lng: 0.0,
        },
        GeoPoint {
            lat: 0.0,
            lng: -180.001,
        },
    ] {
        let base_closed = [point, point];
        let mut rng = SplitMix64::new(11);
        assert!(matches!(
            expand_laps(&base_closed, 1, &mut rng),
            Err(CoreError::SimulationFailed(message)) if message == "基础轨迹包含无效坐标"
        ));
    }
}

#[test]
fn three_laps_use_two_rng_values_per_added_lap_and_share_boundaries() {
    let closed = build_closed_route(&beijing_points());
    let base_length = closed.len();
    let mut rng = SplitMix64::new(73);
    let expanded = expand_laps(&closed, 3, &mut rng).expect("valid closed route expands");
    let mut expected_rng = SplitMix64::new(73);

    let lap_one_radius = 5.0 + 5.0 * expected_rng.next_unit_f64();
    let lap_one_angle = core::f64::consts::TAU * expected_rng.next_unit_f64();
    let expected_lap_one_first = offset_point_meters(
        &closed[0],
        lap_one_radius * libm::cos(lap_one_angle),
        lap_one_radius * libm::sin(lap_one_angle),
    );
    let lap_two_radius = 5.0 + 5.0 * expected_rng.next_unit_f64();
    let lap_two_angle = core::f64::consts::TAU * expected_rng.next_unit_f64();
    let expected_lap_two_first = offset_point_meters(
        &closed[0],
        lap_two_radius * libm::cos(lap_two_angle),
        lap_two_radius * libm::sin(lap_two_angle),
    );

    assert_eq!(expanded.points.len(), base_length * 3);
    assert_eq!(
        expanded.laps,
        vec![
            LapRange {
                index: 0,
                start_sample: 0,
                end_sample: base_length - 1,
            },
            LapRange {
                index: 1,
                start_sample: base_length - 1,
                end_sample: base_length * 2 - 1,
            },
            LapRange {
                index: 2,
                start_sample: base_length * 2 - 1,
                end_sample: base_length * 3 - 1,
            },
        ]
    );
    assert_eq!(expanded.points[base_length], expected_lap_one_first);
    assert_eq!(expanded.points[base_length * 2], expected_lap_two_first);
    assert_eq!(expanded.laps[0].end_sample, expanded.laps[1].start_sample);
    assert_eq!(expanded.laps[1].end_sample, expanded.laps[2].start_sample);
}
