pub mod crc;
pub mod profile;
pub mod writer;

use time::{format_description::well_known::Rfc3339, OffsetDateTime};

use crate::{
    domain::{ActivityModel, ActivitySample, LapModel, ALGORITHM_VERSION, SCHEMA_VERSION},
    error::CoreError,
    fit::{
        crc::fit_crc,
        profile::{base_type, field, global, value},
        writer::{FieldDef, FitDataWriter},
    },
    simulation::{build_activity_model, parse_and_validate},
};

const FIT_EPOCH_UNIX_SECONDS: i64 = 631_065_600;
const FIT_PROFILE_VERSION: u16 = 0x0882;
const FIT_PROTOCOL_VERSION: u8 = 0x20;
const FIT_HEADER_SIZE: u8 = 14;

const FILE_LOCAL: u8 = 0;
const DEVICE_LOCAL: u8 = 1;
const EVENT_LOCAL: u8 = 2;
const RECORD_LOCAL: u8 = 3;
const LAP_LOCAL: u8 = 4;
const SESSION_LOCAL: u8 = 5;
const ACTIVITY_LOCAL: u8 = 6;

const FILE_FIELDS: [FieldDef; 5] = [
    FieldDef { number: field::file::TYPE, size: 1, base_type: base_type::ENUM },
    FieldDef { number: field::file::MANUFACTURER, size: 2, base_type: base_type::UINT16 },
    FieldDef { number: field::file::PRODUCT, size: 2, base_type: base_type::UINT16 },
    FieldDef { number: field::file::SERIAL, size: 4, base_type: base_type::UINT32Z },
    FieldDef { number: field::file::TIME_CREATED, size: 4, base_type: base_type::UINT32 },
];
const DEVICE_FIELDS: [FieldDef; 4] = [
    FieldDef { number: field::device::TIMESTAMP, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::device::DEVICE_INDEX, size: 1, base_type: base_type::UINT8 },
    FieldDef { number: field::device::MANUFACTURER, size: 2, base_type: base_type::UINT16 },
    FieldDef { number: field::device::PRODUCT, size: 2, base_type: base_type::UINT16 },
];
const EVENT_FIELDS: [FieldDef; 3] = [
    FieldDef { number: field::event::TIMESTAMP, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::event::EVENT, size: 1, base_type: base_type::ENUM },
    FieldDef { number: field::event::EVENT_TYPE, size: 1, base_type: base_type::ENUM },
];
const RECORD_FIELDS: [FieldDef; 6] = [
    FieldDef { number: field::record::TIMESTAMP, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::record::POSITION_LAT, size: 4, base_type: base_type::SINT32 },
    FieldDef { number: field::record::POSITION_LONG, size: 4, base_type: base_type::SINT32 },
    FieldDef { number: field::record::HEART_RATE, size: 1, base_type: base_type::UINT8 },
    FieldDef { number: field::record::DISTANCE, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::record::SPEED, size: 2, base_type: base_type::UINT16 },
];
const LAP_FIELDS: [FieldDef; 15] = [
    FieldDef { number: field::lap::TIMESTAMP, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::lap::EVENT, size: 1, base_type: base_type::ENUM },
    FieldDef { number: field::lap::EVENT_TYPE, size: 1, base_type: base_type::ENUM },
    FieldDef { number: field::lap::START_TIME, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::lap::START_POSITION_LAT, size: 4, base_type: base_type::SINT32 },
    FieldDef { number: field::lap::START_POSITION_LONG, size: 4, base_type: base_type::SINT32 },
    FieldDef { number: field::lap::END_POSITION_LAT, size: 4, base_type: base_type::SINT32 },
    FieldDef { number: field::lap::END_POSITION_LONG, size: 4, base_type: base_type::SINT32 },
    FieldDef { number: field::lap::TOTAL_ELAPSED_TIME, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::lap::TOTAL_TIMER_TIME, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::lap::TOTAL_DISTANCE, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::lap::AVG_SPEED, size: 2, base_type: base_type::UINT16 },
    FieldDef { number: field::lap::MAX_SPEED, size: 2, base_type: base_type::UINT16 },
    FieldDef { number: field::lap::AVG_HEART_RATE, size: 1, base_type: base_type::UINT8 },
    FieldDef { number: field::lap::MAX_HEART_RATE, size: 1, base_type: base_type::UINT8 },
];
const SESSION_FIELDS: [FieldDef; 17] = [
    FieldDef { number: field::session::TIMESTAMP, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::session::EVENT, size: 1, base_type: base_type::ENUM },
    FieldDef { number: field::session::EVENT_TYPE, size: 1, base_type: base_type::ENUM },
    FieldDef { number: field::session::START_TIME, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::session::START_POSITION_LAT, size: 4, base_type: base_type::SINT32 },
    FieldDef { number: field::session::START_POSITION_LONG, size: 4, base_type: base_type::SINT32 },
    FieldDef { number: field::session::SPORT, size: 1, base_type: base_type::ENUM },
    FieldDef { number: field::session::SUB_SPORT, size: 1, base_type: base_type::ENUM },
    FieldDef { number: field::session::TOTAL_ELAPSED_TIME, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::session::TOTAL_TIMER_TIME, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::session::TOTAL_DISTANCE, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::session::AVG_SPEED, size: 2, base_type: base_type::UINT16 },
    FieldDef { number: field::session::MAX_SPEED, size: 2, base_type: base_type::UINT16 },
    FieldDef { number: field::session::AVG_HEART_RATE, size: 1, base_type: base_type::UINT8 },
    FieldDef { number: field::session::MAX_HEART_RATE, size: 1, base_type: base_type::UINT8 },
    FieldDef { number: field::session::FIRST_LAP_INDEX, size: 2, base_type: base_type::UINT16 },
    FieldDef { number: field::session::NUM_LAPS, size: 2, base_type: base_type::UINT16 },
];
const ACTIVITY_FIELDS: [FieldDef; 6] = [
    FieldDef { number: field::activity::TIMESTAMP, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::activity::TOTAL_TIMER_TIME, size: 4, base_type: base_type::UINT32 },
    FieldDef { number: field::activity::NUM_SESSIONS, size: 2, base_type: base_type::UINT16 },
    FieldDef { number: field::activity::TYPE, size: 1, base_type: base_type::ENUM },
    FieldDef { number: field::activity::EVENT, size: 1, base_type: base_type::ENUM },
    FieldDef { number: field::activity::EVENT_TYPE, size: 1, base_type: base_type::ENUM },
];

/// Parses an activity request, builds its deterministic model, and encodes it as a FIT activity.
pub fn generate_fit(input: &[u8]) -> Result<Vec<u8>, CoreError> {
    let request = parse_and_validate(input)?;
    let model = build_activity_model(&request)?;
    encode_activity(&model)
}

/// Encodes an already-built activity model into a standards-shaped FIT activity file.
pub fn encode_activity(model: &ActivityModel) -> Result<Vec<u8>, CoreError> {
    validate_activity_model(model)?;
    let start_fit = fit_start_timestamp(&model.start_time_utc)?;
    let first = model.samples.first().ok_or_else(activity_model_inconsistent)?;
    let last = model.samples.last().ok_or_else(activity_model_inconsistent)?;
    let lap_count = u16::try_from(model.laps.len()).map_err(|_| fit_error("圈数超出 FIT 支持范围"))?;
    if lap_count == u16::MAX {
        return Err(fit_error("圈数超出 FIT 支持范围"));
    }
    validate_samples(&model.samples)?;

    let estimated = model.samples.len()
        .checked_mul(20)
        .and_then(|size| model.laps.len().checked_mul(45).and_then(|laps| size.checked_add(laps)))
        .and_then(|size| size.checked_add(311))
        .ok_or_else(memory_limit_error)?;
    let mut writer = FitDataWriter::with_capacity(estimated)?;
    write_definitions(&mut writer)?;

    write_file_id(&mut writer, start_fit, model.seed)?;
    write_device_info(&mut writer, start_fit)?;
    write_event(&mut writer, start_fit, value::event_type::START)?;
    for sample in &model.samples {
        write_record(&mut writer, start_fit, sample)?;
    }
    for lap in &model.laps {
        write_lap(&mut writer, start_fit, lap, &model.samples)?;
    }
    write_session(&mut writer, start_fit, model, first, last, lap_count)?;
    let end_timestamp = sample_timestamp(start_fit, model.total_duration_ms)?;
    write_event(&mut writer, end_timestamp, value::event_type::STOP_ALL)?;
    write_activity(&mut writer, end_timestamp, model.total_duration_ms)?;

    let data = writer.into_inner();
    let data_size = u32::try_from(data.len()).map_err(|_| memory_limit_error())?;
    let total_size = data.len().checked_add(16).ok_or_else(memory_limit_error)?;
    let mut output = Vec::new();
    output.try_reserve(total_size).map_err(|_| memory_limit_error())?;
    output.extend_from_slice(&[
        FIT_HEADER_SIZE,
        FIT_PROTOCOL_VERSION,
        FIT_PROFILE_VERSION as u8,
        (FIT_PROFILE_VERSION >> 8) as u8,
    ]);
    output.extend_from_slice(&data_size.to_le_bytes());
    output.extend_from_slice(b".FIT");
    let header_crc = fit_crc(&output);
    output.extend_from_slice(&header_crc.to_le_bytes());
    output.extend_from_slice(&data);
    let file_crc = fit_crc(&output);
    output.extend_from_slice(&file_crc.to_le_bytes());
    Ok(output)
}

fn write_definitions(writer: &mut FitDataWriter) -> Result<(), CoreError> {
    writer.write_definition(FILE_LOCAL, global::FILE_ID, &FILE_FIELDS)?;
    writer.write_definition(DEVICE_LOCAL, global::DEVICE_INFO, &DEVICE_FIELDS)?;
    writer.write_definition(EVENT_LOCAL, global::EVENT, &EVENT_FIELDS)?;
    writer.write_definition(RECORD_LOCAL, global::RECORD, &RECORD_FIELDS)?;
    writer.write_definition(LAP_LOCAL, global::LAP, &LAP_FIELDS)?;
    writer.write_definition(SESSION_LOCAL, global::SESSION, &SESSION_FIELDS)?;
    writer.write_definition(ACTIVITY_LOCAL, global::ACTIVITY, &ACTIVITY_FIELDS)
}

fn write_file_id(writer: &mut FitDataWriter, start: u32, seed: u64) -> Result<(), CoreError> {
    writer.write_data_header(FILE_LOCAL)?;
    writer.push_u8(value::file_type::ACTIVITY)?;
    writer.push_u16_le(value::manufacturer::DEVELOPMENT)?;
    writer.push_u16_le(0)?;
    writer.push_u32_le((seed as u32).max(1))?;
    writer.push_u32_le(start)
}

fn write_device_info(writer: &mut FitDataWriter, timestamp: u32) -> Result<(), CoreError> {
    writer.write_data_header(DEVICE_LOCAL)?;
    writer.push_u32_le(timestamp)?;
    writer.push_u8(0)?;
    writer.push_u16_le(value::manufacturer::DEVELOPMENT)?;
    writer.push_u16_le(0)
}

fn write_event(writer: &mut FitDataWriter, timestamp: u32, event_type: u8) -> Result<(), CoreError> {
    writer.write_data_header(EVENT_LOCAL)?;
    writer.push_u32_le(timestamp)?;
    writer.push_u8(value::event::TIMER)?;
    writer.push_u8(event_type)
}

fn write_record(writer: &mut FitDataWriter, start: u32, sample: &ActivitySample) -> Result<(), CoreError> {
    writer.write_data_header(RECORD_LOCAL)?;
    writer.push_u32_le(sample_timestamp(start, sample.time_ms)?)?;
    writer.push_i32_le(fit_position(sample.position_lat_semicircles)?)?;
    writer.push_i32_le(fit_position(sample.position_long_semicircles)?)?;
    writer.push_u8(fit_heart_rate(sample.heart_rate_bpm)?)?;
    writer.push_u32_le(fit_u32(sample.distance_cm, "距离")?)?;
    writer.push_u16_le(fit_speed(sample.speed_mm_per_sec)?)
}

fn write_lap(
    writer: &mut FitDataWriter,
    start: u32,
    lap: &LapModel,
    samples: &[ActivitySample],
) -> Result<(), CoreError> {
    if lap.start_sample > lap.end_sample || lap.end_sample >= samples.len() {
        return Err(fit_error("圈采样范围无效"));
    }
    let slice = &samples[lap.start_sample..=lap.end_sample];
    let first = slice.first().ok_or_else(|| fit_error("圈采样范围无效"))?;
    let last = slice.last().ok_or_else(|| fit_error("圈采样范围无效"))?;
    let avg_speed = average_speed(lap.distance_cm, lap.duration_ms)?;
    let (avg_hr, max_hr) = heart_rate_summary(slice)?;
    let max_speed = max_speed(slice)?;
    let duration = fit_u32(lap.duration_ms, "持续时间")?;

    writer.write_data_header(LAP_LOCAL)?;
    writer.push_u32_le(sample_timestamp(start, last.time_ms)?)?;
    writer.push_u8(value::event::TIMER)?;
    writer.push_u8(value::event_type::STOP_ALL)?;
    writer.push_u32_le(sample_timestamp(start, first.time_ms)?)?;
    writer.push_i32_le(fit_position(first.position_lat_semicircles)?)?;
    writer.push_i32_le(fit_position(first.position_long_semicircles)?)?;
    writer.push_i32_le(fit_position(last.position_lat_semicircles)?)?;
    writer.push_i32_le(fit_position(last.position_long_semicircles)?)?;
    writer.push_u32_le(duration)?;
    writer.push_u32_le(duration)?;
    writer.push_u32_le(fit_u32(lap.distance_cm, "距离")?)?;
    writer.push_u16_le(avg_speed)?;
    writer.push_u16_le(max_speed)?;
    writer.push_u8(avg_hr)?;
    writer.push_u8(max_hr)
}

fn write_session(
    writer: &mut FitDataWriter,
    start: u32,
    model: &ActivityModel,
    first: &ActivitySample,
    last: &ActivitySample,
    lap_count: u16,
) -> Result<(), CoreError> {
    let avg_speed = average_speed(model.total_distance_cm, model.total_duration_ms)?;
    let (avg_hr, max_hr) = heart_rate_summary(&model.samples)?;
    let duration = fit_u32(model.total_duration_ms, "持续时间")?;

    writer.write_data_header(SESSION_LOCAL)?;
    writer.push_u32_le(sample_timestamp(start, last.time_ms)?)?;
    writer.push_u8(value::event::TIMER)?;
    writer.push_u8(value::event_type::STOP_ALL)?;
    writer.push_u32_le(sample_timestamp(start, first.time_ms)?)?;
    writer.push_i32_le(fit_position(first.position_lat_semicircles)?)?;
    writer.push_i32_le(fit_position(first.position_long_semicircles)?)?;
    writer.push_u8(value::sport::RUNNING)?;
    writer.push_u8(value::sub_sport::GENERIC)?;
    writer.push_u32_le(duration)?;
    writer.push_u32_le(duration)?;
    writer.push_u32_le(fit_u32(model.total_distance_cm, "距离")?)?;
    writer.push_u16_le(avg_speed)?;
    writer.push_u16_le(max_speed(&model.samples)?)?;
    writer.push_u8(avg_hr)?;
    writer.push_u8(max_hr)?;
    writer.push_u16_le(0)?;
    writer.push_u16_le(lap_count)
}

fn write_activity(writer: &mut FitDataWriter, timestamp: u32, duration_ms: u64) -> Result<(), CoreError> {
    writer.write_data_header(ACTIVITY_LOCAL)?;
    writer.push_u32_le(timestamp)?;
    writer.push_u32_le(fit_u32(duration_ms, "持续时间")?)?;
    writer.push_u16_le(1)?;
    writer.push_u8(value::activity_type::MANUAL)?;
    writer.push_u8(value::event::TIMER)?;
    writer.push_u8(value::event_type::STOP_ALL)
}

fn validate_samples(samples: &[ActivitySample]) -> Result<(), CoreError> {
    for pair in samples.windows(2) {
        if pair[0].time_ms > pair[1].time_ms || pair[0].distance_cm > pair[1].distance_cm {
            return Err(fit_error("活动采样顺序无效"));
        }
    }
    for sample in samples {
        fit_u32(sample.distance_cm, "距离")?;
        fit_speed(sample.speed_mm_per_sec)?;
        fit_heart_rate(sample.heart_rate_bpm)?;
        fit_position(sample.position_lat_semicircles)?;
        fit_position(sample.position_long_semicircles)?;
    }
    Ok(())
}

fn validate_activity_model(model: &ActivityModel) -> Result<(), CoreError> {
    if model.schema_version != SCHEMA_VERSION || model.algorithm_version != ALGORITHM_VERSION {
        return Err(activity_model_inconsistent());
    }
    let first = model.samples.first().ok_or_else(activity_model_inconsistent)?;
    let last = model.samples.last().ok_or_else(activity_model_inconsistent)?;
    if first.time_ms != 0
        || first.distance_cm != 0
        || model.total_duration_ms != last.time_ms
        || model.total_distance_cm != last.distance_cm
        || model.laps.is_empty()
    {
        return Err(activity_model_inconsistent());
    }
    if model.samples.windows(2).any(|pair| {
        pair[0].time_ms > pair[1].time_ms || pair[0].distance_cm > pair[1].distance_cm
    }) {
        return Err(activity_model_inconsistent());
    }

    let mut total_distance = 0u64;
    let mut total_duration = 0u64;
    let mut previous_end = None;
    for (expected_index, lap) in model.laps.iter().enumerate() {
        if lap.index != u16::try_from(expected_index).map_err(|_| activity_model_inconsistent())?
            || lap.start_sample > lap.end_sample
            || lap.end_sample >= model.samples.len()
            || (expected_index == 0 && lap.start_sample != 0)
            || (expected_index > 0 && Some(lap.start_sample) != previous_end)
        {
            return Err(activity_model_inconsistent());
        }
        let start = &model.samples[lap.start_sample];
        let end = &model.samples[lap.end_sample];
        let distance = end.distance_cm.checked_sub(start.distance_cm)
            .ok_or_else(activity_model_inconsistent)?;
        let duration = end.time_ms.checked_sub(start.time_ms)
            .ok_or_else(activity_model_inconsistent)?;
        if lap.distance_cm != distance || lap.duration_ms != duration {
            return Err(activity_model_inconsistent());
        }
        total_distance = total_distance.checked_add(lap.distance_cm)
            .ok_or_else(activity_model_inconsistent)?;
        total_duration = total_duration.checked_add(lap.duration_ms)
            .ok_or_else(activity_model_inconsistent)?;
        previous_end = Some(lap.end_sample);
    }
    if previous_end != Some(model.samples.len() - 1)
        || total_distance != model.total_distance_cm
        || total_duration != model.total_duration_ms
    {
        return Err(activity_model_inconsistent());
    }
    Ok(())
}

fn fit_start_timestamp(value: &str) -> Result<u32, CoreError> {
    let parsed = OffsetDateTime::parse(value, &Rfc3339)
        .map_err(|_| fit_error("活动时间超出 FIT 支持范围"))?;
    let seconds = parsed.unix_timestamp();
    let fit_seconds = seconds.checked_sub(FIT_EPOCH_UNIX_SECONDS)
        .ok_or_else(|| fit_error("活动时间超出 FIT 支持范围"))?;
    if fit_seconds < 0 || fit_seconds >= i64::from(u32::MAX) {
        return Err(fit_error("活动时间超出 FIT 支持范围"));
    }
    u32::try_from(fit_seconds).map_err(|_| fit_error("活动时间超出 FIT 支持范围"))
}

fn sample_timestamp(start: u32, time_ms: u64) -> Result<u32, CoreError> {
    let elapsed = u32::try_from(time_ms / 1_000)
        .map_err(|_| fit_error("活动时间超出 FIT 支持范围"))?;
    let timestamp = start.checked_add(elapsed)
        .ok_or_else(|| fit_error("活动时间超出 FIT 支持范围"))?;
    if timestamp == u32::MAX {
        return Err(fit_error("活动时间超出 FIT 支持范围"));
    }
    Ok(timestamp)
}

fn fit_u32(value: u64, label: &str) -> Result<u32, CoreError> {
    let converted = u32::try_from(value).map_err(|_| fit_error(&format!("{label}超出 FIT 支持范围")))?;
    if converted == u32::MAX {
        return Err(fit_error(&format!("{label}超出 FIT 支持范围")));
    }
    Ok(converted)
}

fn fit_speed(value: u32) -> Result<u16, CoreError> {
    let converted = u16::try_from(value).map_err(|_| fit_error("速度超出 FIT 支持范围"))?;
    if converted == u16::MAX {
        return Err(fit_error("速度超出 FIT 支持范围"));
    }
    Ok(converted)
}

fn fit_heart_rate(value: u8) -> Result<u8, CoreError> {
    if value == u8::MAX {
        return Err(fit_error("心率超出 FIT 支持范围"));
    }
    Ok(value)
}

fn fit_position(value: i32) -> Result<i32, CoreError> {
    if value == i32::MAX {
        return Err(fit_error("位置超出 FIT 支持范围"));
    }
    Ok(value)
}

fn average_speed(distance_cm: u64, duration_ms: u64) -> Result<u16, CoreError> {
    if duration_ms == 0 {
        return Err(fit_error("持续时间超出 FIT 支持范围"));
    }
    let numerator = distance_cm.checked_mul(10_000)
        .and_then(|value| value.checked_add(duration_ms / 2))
        .ok_or_else(|| fit_error("速度超出 FIT 支持范围"))?;
    let raw = numerator / duration_ms;
    let raw = u32::try_from(raw).map_err(|_| fit_error("速度超出 FIT 支持范围"))?;
    fit_speed(raw)
}

fn max_speed(samples: &[ActivitySample]) -> Result<u16, CoreError> {
    let max = samples.iter().map(|sample| sample.speed_mm_per_sec).max()
        .ok_or_else(|| fit_error("活动采样不能为空"))?;
    fit_speed(max)
}

fn heart_rate_summary(samples: &[ActivitySample]) -> Result<(u8, u8), CoreError> {
    let mut total = 0u64;
    let mut max = 0u8;
    for sample in samples {
        let heart_rate = fit_heart_rate(sample.heart_rate_bpm)?;
        total = total.checked_add(u64::from(heart_rate))
            .ok_or_else(|| fit_error("心率超出 FIT 支持范围"))?;
        max = max.max(heart_rate);
    }
    let count = u64::try_from(samples.len()).map_err(|_| fit_error("心率超出 FIT 支持范围"))?;
    if count == 0 {
        return Err(fit_error("活动采样不能为空"));
    }
    let average = (total.checked_add(count / 2).ok_or_else(|| fit_error("心率超出 FIT 支持范围"))?) / count;
    let average = u8::try_from(average).map_err(|_| fit_error("心率超出 FIT 支持范围"))?;
    Ok((fit_heart_rate(average)?, max))
}

fn fit_error(message: impl Into<String>) -> CoreError {
    CoreError::FitEncodingFailed(message.into())
}

fn activity_model_inconsistent() -> CoreError {
    fit_error("活动模型不一致")
}

fn memory_limit_error() -> CoreError {
    CoreError::ResourceLimit("FIT 编码需要的内存过大".to_owned())
}
