use fit_generator_core::{
    build_activity_model,
    domain::{ActivityModel, ActivitySample, LapModel},
    error::CoreError,
    fit::{crc::fit_crc, profile::base_type},
    generate_fit, parse_and_validate,
};
use fitparser::{FitDataRecord, from_bytes, profile::MesgNum};
use proptest::prelude::*;

const CLOSED_SINGLE_LAP: &[u8] = include_bytes!("fixtures/closed_single_lap.json");
const VALID_FIXTURES: [&[u8]; 4] = [
    CLOSED_SINGLE_LAP,
    include_bytes!("fixtures/open_two_laps.json"),
    include_bytes!("fixtures/short_legal.json"),
    include_bytes!("fixtures/fast_low_hr.json"),
];

fn records_by_kind(records: &[FitDataRecord], kind: MesgNum) -> Vec<&FitDataRecord> {
    records
        .iter()
        .filter(|record| record.kind() == kind)
        .collect()
}

fn assert_field_names(record: &FitDataRecord, expected: &[&str]) {
    let mut actual = record
        .fields()
        .iter()
        .map(|field| field.name().to_owned())
        .collect::<Vec<_>>();
    actual.sort();
    let mut expected = expected
        .iter()
        .map(|name| (*name).to_owned())
        .collect::<Vec<_>>();
    expected.sort();
    assert_eq!(
        actual,
        expected,
        "unexpected fields for {:?}",
        record.kind()
    );
}

fn field_display(record: &FitDataRecord, name: &str) -> String {
    let field = record
        .fields()
        .iter()
        .find(|field| field.name() == name)
        .unwrap_or_else(|| panic!("missing {name} on {:?}", record.kind()));
    format!("{}", field.value())
}

fn normalized_field_display(record: &FitDataRecord, name: &str) -> String {
    field_display(record, name)
        .trim_matches('"')
        .to_ascii_lowercase()
}

fn assert_enum_value(record: &FitDataRecord, name: &str, numeric: u64, label: &str) {
    let actual = normalized_field_display(record, name);
    assert!(
        actual == numeric.to_string() || actual == label,
        "unexpected {name} value {actual:?}; expected {numeric} or {label:?}",
    );
}

fn field_f64(record: &FitDataRecord, name: &str) -> f64 {
    record
        .fields()
        .iter()
        .find(|field| field.name() == name)
        .unwrap_or_else(|| panic!("missing {name} on {:?}", record.kind()))
        .value()
        .clone()
        .try_into()
        .unwrap_or_else(|_| panic!("{name} is not a FIT numeric value"))
}

fn field_i64(record: &FitDataRecord, name: &str) -> i64 {
    record
        .fields()
        .iter()
        .find(|field| field.name() == name)
        .unwrap_or_else(|| panic!("missing {name} on {:?}", record.kind()))
        .value()
        .clone()
        .try_into()
        .unwrap_or_else(|_| panic!("{name} is not an integer FIT value"))
}

fn assert_scaled_value(record: &FitDataRecord, name: &str, raw: u64, scale: f64) {
    let actual = field_f64(record, name);
    let expected = raw as f64 / scale;
    assert!(
        (actual - expected).abs() <= 0.000_1,
        "{name}: {actual} != {expected}"
    );
}

fn expected_average_speed(distance_cm: u64, duration_ms: u64) -> u64 {
    (distance_cm * 10_000 + duration_ms / 2) / duration_ms
}

fn expected_average_heart_rate(samples: &[ActivitySample]) -> i64 {
    let total = samples
        .iter()
        .map(|sample| i64::from(sample.heart_rate_bpm))
        .sum::<i64>();
    (total + i64::try_from(samples.len()).unwrap() / 2) / i64::try_from(samples.len()).unwrap()
}

#[test]
fn encoder_profile_base_type_constants_match_fit_wire_values() {
    assert_eq!(base_type::ENUM, 0x00);
    assert_eq!(base_type::UINT8, 0x02);
    assert_eq!(base_type::UINT16, 0x84);
    assert_eq!(base_type::SINT32, 0x85);
    assert_eq!(base_type::UINT32, 0x86);
    assert_eq!(base_type::UINT32Z, 0x8c);
}

#[test]
fn generated_fit_has_a_valid_header_crc_and_required_messages() {
    let bytes = generate_fit(CLOSED_SINGLE_LAP).expect("closed fixture must encode");

    assert!(bytes.len() >= 16);
    assert_eq!(&bytes[8..12], b".FIT");
    assert_eq!(
        usize::try_from(u32::from_le_bytes(bytes[4..8].try_into().unwrap())).unwrap(),
        bytes.len() - 16
    );
    assert_eq!(
        u16::from_le_bytes(bytes[12..14].try_into().unwrap()),
        fit_crc(&bytes[..12])
    );
    assert_eq!(
        u16::from_le_bytes(bytes[bytes.len() - 2..].try_into().unwrap()),
        fit_crc(&bytes[..bytes.len() - 2])
    );

    let records = from_bytes(&bytes).expect("FIT parser must accept bytes");
    for kind in [
        MesgNum::FileId,
        MesgNum::DeviceInfo,
        MesgNum::Event,
        MesgNum::Record,
        MesgNum::Lap,
        MesgNum::Session,
        MesgNum::Activity,
    ] {
        assert!(
            records.iter().any(|record| record.kind() == kind),
            "missing {kind:?}"
        );
    }
}

#[test]
fn decoded_messages_have_canonical_data_order_counts_and_fields() {
    let model = build_activity_model(&parse_and_validate(CLOSED_SINGLE_LAP).unwrap())
        .expect("fixture must produce a model");
    let bytes = generate_fit(CLOSED_SINGLE_LAP).expect("fixture must encode");
    let records = from_bytes(&bytes).expect("FIT parser must accept bytes");
    let kinds = records
        .iter()
        .map(|record| record.kind())
        .collect::<Vec<_>>();

    let expected = [MesgNum::FileId, MesgNum::DeviceInfo, MesgNum::Event]
        .into_iter()
        .chain(std::iter::repeat(MesgNum::Record).take(model.samples.len()))
        .chain(std::iter::repeat(MesgNum::Lap).take(model.laps.len()))
        .chain([MesgNum::Session, MesgNum::Event, MesgNum::Activity])
        .collect::<Vec<_>>();
    assert_eq!(kinds, expected);
    assert_eq!(
        records_by_kind(&records, MesgNum::Record).len(),
        model.samples.len()
    );
    assert_eq!(
        records_by_kind(&records, MesgNum::Lap).len(),
        model.laps.len()
    );

    let file = records_by_kind(&records, MesgNum::FileId);
    assert_eq!(file.len(), 1);
    assert_field_names(
        file[0],
        &[
            "type",
            "manufacturer",
            "product",
            "serial_number",
            "time_created",
        ],
    );
    assert_enum_value(file[0], "type", 4, "activity");
    assert_enum_value(file[0], "manufacturer", 255, "development");

    let device = records_by_kind(&records, MesgNum::DeviceInfo);
    assert_eq!(device.len(), 1);
    assert_field_names(
        device[0],
        &["timestamp", "device_index", "manufacturer", "product"],
    );

    let events = records_by_kind(&records, MesgNum::Event);
    assert_eq!(events.len(), 2);
    for event in &events {
        assert_field_names(event, &["timestamp", "event", "event_type"]);
        assert_enum_value(event, "event", 0, "timer");
    }
    assert_enum_value(events[0], "event_type", 0, "start");
    assert_enum_value(events[1], "event_type", 4, "stop_all");

    let decoded_records = records_by_kind(&records, MesgNum::Record);
    for record in &decoded_records {
        assert_field_names(
            record,
            &[
                "timestamp",
                "position_lat",
                "position_long",
                "heart_rate",
                "distance",
                "speed",
            ],
        );
    }
    for (record, sample) in [
        (decoded_records[0], &model.samples[0]),
        (
            decoded_records[decoded_records.len() - 1],
            model.samples.last().unwrap(),
        ),
    ] {
        assert_eq!(
            field_i64(record, "position_lat"),
            i64::from(sample.position_lat_semicircles)
        );
        assert_eq!(
            field_i64(record, "position_long"),
            i64::from(sample.position_long_semicircles)
        );
        assert_eq!(
            field_i64(record, "heart_rate"),
            i64::from(sample.heart_rate_bpm)
        );
        assert_scaled_value(record, "distance", sample.distance_cm, 100.0);
        assert_scaled_value(record, "speed", u64::from(sample.speed_mm_per_sec), 1_000.0);
    }

    let laps = records_by_kind(&records, MesgNum::Lap);
    for (record, lap) in laps.into_iter().zip(&model.laps) {
        assert_field_names(
            record,
            &[
                "timestamp",
                "event",
                "event_type",
                "start_time",
                "start_position_lat",
                "start_position_long",
                "end_position_lat",
                "end_position_long",
                "total_elapsed_time",
                "total_timer_time",
                "total_distance",
                "avg_speed",
                "max_speed",
                "avg_heart_rate",
                "max_heart_rate",
            ],
        );
        assert_enum_value(record, "event", 0, "timer");
        assert_enum_value(record, "event_type", 4, "stop_all");
        assert_scaled_value(record, "total_elapsed_time", lap.duration_ms, 1_000.0);
        assert_scaled_value(record, "total_timer_time", lap.duration_ms, 1_000.0);
        assert_scaled_value(record, "total_distance", lap.distance_cm, 100.0);
        assert_scaled_value(
            record,
            "avg_speed",
            expected_average_speed(lap.distance_cm, lap.duration_ms),
            1_000.0,
        );
        assert_scaled_value(
            record,
            "max_speed",
            u64::from(
                model.samples[lap.start_sample..=lap.end_sample]
                    .iter()
                    .map(|sample| sample.speed_mm_per_sec)
                    .max()
                    .unwrap(),
            ),
            1_000.0,
        );
        assert_eq!(
            field_i64(record, "avg_heart_rate"),
            expected_average_heart_rate(&model.samples[lap.start_sample..=lap.end_sample])
        );
        assert_eq!(
            field_i64(record, "max_heart_rate"),
            i64::from(
                model.samples[lap.start_sample..=lap.end_sample]
                    .iter()
                    .map(|sample| sample.heart_rate_bpm)
                    .max()
                    .unwrap(),
            )
        );
    }

    let session = records_by_kind(&records, MesgNum::Session);
    assert_eq!(session.len(), 1);
    assert_field_names(
        session[0],
        &[
            "timestamp",
            "event",
            "event_type",
            "start_time",
            "start_position_lat",
            "start_position_long",
            "sport",
            "sub_sport",
            "total_elapsed_time",
            "total_timer_time",
            "total_distance",
            "avg_speed",
            "max_speed",
            "avg_heart_rate",
            "max_heart_rate",
            "first_lap_index",
            "num_laps",
        ],
    );
    assert_enum_value(session[0], "event", 0, "timer");
    assert_enum_value(session[0], "event_type", 4, "stop_all");
    assert_enum_value(session[0], "sport", 1, "running");
    assert_enum_value(session[0], "sub_sport", 0, "generic");
    assert_scaled_value(
        session[0],
        "total_elapsed_time",
        model.total_duration_ms,
        1_000.0,
    );
    assert_scaled_value(
        session[0],
        "total_timer_time",
        model.total_duration_ms,
        1_000.0,
    );
    assert_scaled_value(session[0], "total_distance", model.total_distance_cm, 100.0);
    assert_scaled_value(
        session[0],
        "avg_speed",
        expected_average_speed(model.total_distance_cm, model.total_duration_ms),
        1_000.0,
    );
    assert_scaled_value(
        session[0],
        "max_speed",
        u64::from(
            model
                .samples
                .iter()
                .map(|sample| sample.speed_mm_per_sec)
                .max()
                .unwrap(),
        ),
        1_000.0,
    );
    assert_eq!(
        field_i64(session[0], "avg_heart_rate"),
        expected_average_heart_rate(&model.samples)
    );
    assert_eq!(
        field_i64(session[0], "max_heart_rate"),
        i64::from(
            model
                .samples
                .iter()
                .map(|sample| sample.heart_rate_bpm)
                .max()
                .unwrap()
        )
    );
    assert_eq!(
        field_i64(session[0], "num_laps"),
        i64::try_from(model.laps.len()).unwrap()
    );

    let activity = records_by_kind(&records, MesgNum::Activity);
    assert_eq!(activity.len(), 1);
    assert_field_names(
        activity[0],
        &[
            "timestamp",
            "total_timer_time",
            "num_sessions",
            "type",
            "event",
            "event_type",
        ],
    );
    assert_enum_value(activity[0], "type", 0, "manual");
    assert_enum_value(activity[0], "event", 0, "timer");
    assert_enum_value(activity[0], "event_type", 4, "stop_all");
    assert_scaled_value(
        activity[0],
        "total_timer_time",
        model.total_duration_ms,
        1_000.0,
    );
    assert_eq!(field_i64(activity[0], "num_sessions"), 1);
}

#[test]
fn every_valid_fixture_round_trips_through_fitparser() {
    for fixture in VALID_FIXTURES {
        let bytes = generate_fit(fixture).expect("fixture must encode");
        from_bytes(&bytes).expect("fixture FIT must decode");
    }
}

#[test]
fn malformed_and_pre_fit_epoch_requests_are_rejected() {
    assert!(matches!(
        generate_fit(b"not JSON"),
        Err(CoreError::InvalidInput(message)) if message == "请求数据不是有效的 JSON"
    ));

    let mut request: serde_json::Value = serde_json::from_slice(CLOSED_SINGLE_LAP).unwrap();
    request["startTimeUtc"] = serde_json::Value::String("1980-01-01T00:00:00Z".to_owned());
    assert!(matches!(
        generate_fit(&serde_json::to_vec(&request).unwrap()),
        Err(CoreError::FitEncodingFailed(message)) if message == "活动时间超出 FIT 支持范围"
    ));
}

#[test]
fn direct_model_rejects_u16_speed_overflow() {
    let model = ActivityModel {
        schema_version: 1,
        algorithm_version: 1,
        start_time_utc: "2026-07-27T08:00:00Z".to_owned(),
        seed: 1,
        total_distance_cm: 0,
        total_duration_ms: 0,
        laps: vec![LapModel {
            index: 0,
            start_sample: 0,
            end_sample: 0,
            distance_cm: 0,
            duration_ms: 0,
        }],
        samples: vec![ActivitySample {
            time_ms: 0,
            distance_cm: 0,
            speed_mm_per_sec: u32::from(u16::MAX) + 1,
            heart_rate_bpm: 100,
            position_lat_semicircles: 1,
            position_long_semicircles: 1,
        }],
    };

    assert!(matches!(
        fit_generator_core::fit::encode_activity(&model),
        Err(CoreError::FitEncodingFailed(message)) if message == "速度超出 FIT 支持范围"
    ));
}

#[test]
fn direct_models_reject_inconsistent_schema_totals_and_lap_ranges() {
    let model = build_activity_model(&parse_and_validate(CLOSED_SINGLE_LAP).unwrap())
        .expect("fixture must produce a model");

    let mut unsupported_schema = model.clone();
    unsupported_schema.schema_version = 0;
    let mut wrong_total = model.clone();
    wrong_total.total_distance_cm += 1;
    let mut invalid_lap_range = model;
    invalid_lap_range.laps[0].end_sample = invalid_lap_range.samples.len();

    for invalid in [unsupported_schema, wrong_total, invalid_lap_range] {
        assert!(matches!(
            fit_generator_core::fit::encode_activity(&invalid),
            Err(CoreError::FitEncodingFailed(message)) if message == "活动模型不一致"
        ));
    }
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(64))]

    #[test]
    fn generated_small_valid_requests_decode(
        coordinates in prop::collection::vec((-89.0f64..89.0, -179.0f64..179.0), 2..8),
        lap_count in 1u16..4,
        pace in 60.0f64..3600.0,
        hr_rest in 30u8..=120,
        hr_max in 100u8..=220,
        variant_index in 1u16..u16::MAX,
        seed in any::<u64>(),
    ) {
        prop_assume!(hr_max > hr_rest);
        let request = serde_json::json!({
            "schemaVersion": 1,
            "startTimeUtc": "2026-07-27T08:00:00Z",
            "points": coordinates.into_iter().map(|(lat, lng)| serde_json::json!({"lat": lat, "lng": lng})).collect::<Vec<_>>(),
            "paceSecondsPerKm": pace,
            "hrRest": hr_rest,
            "hrMax": hr_max,
            "lapCount": lap_count,
            "variantIndex": variant_index,
            "seed": seed,
            "routeMode": "close_if_needed",
        });
        let bytes = generate_fit(&serde_json::to_vec(&request).unwrap()).expect("generated request must encode");
        prop_assert!(from_bytes(&bytes).is_ok());
    }
}
