use crate::{
    domain::{
        ALGORITHM_VERSION, ActivityModel, ActivityRequest, ActivitySample, LapModel, SCHEMA_VERSION,
    },
    error::CoreError,
    geo::{build_closed_route, cumulative_distances, expand_laps, to_semicircles},
    rng::{SplitMix64, derive_variant_seed, quantize_distance_cm, quantize_speed_mm_per_sec},
    validation::ValidatedRequest,
};

const SIMULATION_TIME_FAILURE: &str = "活动时间计算失败";
const SAMPLE_ALLOCATION_FAILURE: &str = "活动采样数组需要的内存过大";
const LAP_ALLOCATION_FAILURE: &str = "活动圈数组需要的内存过大";
const QUANTIZATION_FAILURE: &str = "活动数值量化失败";
const LAP_SUMMARY_FAILURE: &str = "活动圈汇总计算失败";

/// Parses an external JSON request and applies the canonical input validation rules.
pub fn parse_and_validate(input: &[u8]) -> Result<ValidatedRequest, CoreError> {
    let request: ActivityRequest = serde_json::from_slice(input)
        .map_err(|_| CoreError::InvalidInput("请求数据不是有效的 JSON".to_owned()))?;
    ValidatedRequest::try_from(request)
}

/// Builds the deterministic, quantized activity preview for an already validated request.
pub fn build_activity_model(request: &ValidatedRequest) -> Result<ActivityModel, CoreError> {
    let mut base_closed = build_closed_route(request.points());
    // Routes whose endpoints are already within the geo helper's five-meter threshold are
    // semantically closed, but `expand_laps` needs an explicit shared endpoint.
    if base_closed.first() != base_closed.last() {
        let first = *base_closed.first().ok_or_else(|| {
            CoreError::SimulationFailed("基础轨迹必须闭合且至少包含两个点".to_owned())
        })?;
        base_closed
            .try_reserve(1)
            .map_err(|_| CoreError::ResourceLimit(SAMPLE_ALLOCATION_FAILURE.to_owned()))?;
        base_closed.push(first);
    }
    let mut rng = SplitMix64::new(derive_variant_seed(request.seed(), request.variant_index()));
    let expanded = expand_laps(&base_closed, request.lap_count(), &mut rng)?;
    let cumulative = cumulative_distances(&expanded.points)?;

    let total_distance_m = *cumulative
        .last()
        .ok_or_else(|| CoreError::SimulationFailed("轨迹总距离必须大于 0".to_owned()))?;
    if !total_distance_m.is_finite() || total_distance_m <= 0.0 {
        return Err(CoreError::SimulationFailed(
            "轨迹总距离必须大于 0".to_owned(),
        ));
    }

    let long_phase = core::f64::consts::TAU * rng.next_unit_f64();
    let short_phase = core::f64::consts::TAU * rng.next_unit_f64();
    let segment_count = expanded
        .points
        .len()
        .checked_sub(1)
        .ok_or_else(|| CoreError::SimulationFailed(SIMULATION_TIME_FAILURE.to_owned()))?;
    if segment_count == 0 || cumulative.len() != expanded.points.len() {
        return Err(CoreError::SimulationFailed(
            SIMULATION_TIME_FAILURE.to_owned(),
        ));
    }

    let pace_seconds_per_km = request.pace_seconds_per_km();
    if !pace_seconds_per_km.is_finite() || pace_seconds_per_km <= 0.0 {
        return Err(CoreError::SimulationFailed(
            SIMULATION_TIME_FAILURE.to_owned(),
        ));
    }
    let target_speed_mps = 1_000.0 / pace_seconds_per_km;
    if !target_speed_mps.is_finite() || target_speed_mps <= 0.0 {
        return Err(CoreError::SimulationFailed(
            SIMULATION_TIME_FAILURE.to_owned(),
        ));
    }

    let mut factors = Vec::new();
    factors
        .try_reserve(segment_count)
        .map_err(|_| CoreError::ResourceLimit(SAMPLE_ALLOCATION_FAILURE.to_owned()))?;
    let mut raw_cumulative = Vec::new();
    raw_cumulative
        .try_reserve(expanded.points.len())
        .map_err(|_| CoreError::ResourceLimit(SAMPLE_ALLOCATION_FAILURE.to_owned()))?;
    raw_cumulative.push(0.0);

    for index in 1..=segment_count {
        let progress = index as f64 / segment_count as f64;
        let noise = rng.next_unit_f64();
        let factor = ((1.0 + 0.08 * libm::sin(core::f64::consts::TAU * progress + long_phase))
            * (1.0 + 0.03 * libm::sin(5.0 * core::f64::consts::TAU * progress + short_phase))
            * (0.98 + 0.04 * noise))
            .clamp(0.80, 1.20);
        let segment_distance = cumulative[index] - cumulative[index - 1];
        let denominator = target_speed_mps * factor;
        if !factor.is_finite()
            || !segment_distance.is_finite()
            || segment_distance < 0.0
            || !denominator.is_finite()
            || denominator <= 0.0
        {
            return Err(CoreError::SimulationFailed(
                SIMULATION_TIME_FAILURE.to_owned(),
            ));
        }
        let raw_duration = segment_distance / denominator;
        let raw_total = raw_cumulative[index - 1] + raw_duration;
        if !raw_duration.is_finite() || raw_duration < 0.0 || !raw_total.is_finite() {
            return Err(CoreError::SimulationFailed(
                SIMULATION_TIME_FAILURE.to_owned(),
            ));
        }
        factors.push(factor);
        raw_cumulative.push(raw_total);
    }

    let raw_total = *raw_cumulative
        .last()
        .ok_or_else(|| CoreError::SimulationFailed(SIMULATION_TIME_FAILURE.to_owned()))?;
    if !raw_total.is_finite() || raw_total <= 0.0 {
        return Err(CoreError::SimulationFailed(
            SIMULATION_TIME_FAILURE.to_owned(),
        ));
    }
    let target_total_ms_raw = total_distance_m * pace_seconds_per_km;
    if !target_total_ms_raw.is_finite()
        || target_total_ms_raw <= 0.0
        || target_total_ms_raw >= u64::MAX as f64
    {
        return Err(CoreError::SimulationFailed(
            SIMULATION_TIME_FAILURE.to_owned(),
        ));
    }
    let target_total_ms = target_total_ms_raw.round() as u64;
    if target_total_ms == 0 {
        return Err(CoreError::SimulationFailed(
            SIMULATION_TIME_FAILURE.to_owned(),
        ));
    }

    let mut times = Vec::new();
    times
        .try_reserve(expanded.points.len())
        .map_err(|_| CoreError::ResourceLimit(SAMPLE_ALLOCATION_FAILURE.to_owned()))?;
    times.push(0_u64);
    let target_total_as_f64 = target_total_ms as f64;
    for (index, raw_elapsed) in raw_cumulative.iter().copied().enumerate().skip(1) {
        let scaled = (raw_elapsed / raw_total) * target_total_as_f64;
        if !scaled.is_finite() {
            return Err(CoreError::SimulationFailed(
                SIMULATION_TIME_FAILURE.to_owned(),
            ));
        }
        let rounded = scaled.round().clamp(0.0, target_total_as_f64);
        if !rounded.is_finite() {
            return Err(CoreError::SimulationFailed(
                SIMULATION_TIME_FAILURE.to_owned(),
            ));
        }
        let time_ms = if index == segment_count {
            target_total_ms
        } else {
            rounded as u64
        };
        let previous = *times
            .last()
            .ok_or_else(|| CoreError::SimulationFailed(SIMULATION_TIME_FAILURE.to_owned()))?;
        times.push(time_ms.max(previous));
    }

    let mut samples = Vec::new();
    samples
        .try_reserve(expanded.points.len())
        .map_err(|_| CoreError::ResourceLimit(SAMPLE_ALLOCATION_FAILURE.to_owned()))?;
    let hr_rest = request.hr_rest();
    let hr_max = request.hr_max();
    let hr_range = f64::from(hr_max - hr_rest);
    let mut current_hr = f64::from(hr_rest) + 0.20 * hr_range;

    for index in 0..expanded.points.len() {
        let point = expanded.points[index];
        let time_ms = times[index];
        let distance_m = cumulative[index];
        if !distance_m.is_finite() || distance_m < 0.0 {
            return Err(CoreError::SimulationFailed(QUANTIZATION_FAILURE.to_owned()));
        }
        let distance_cm = quantize_distance_cm(distance_m);
        let speed_mps = if index == 0 {
            target_speed_mps
        } else {
            let elapsed_ms = time_ms - times[index - 1];
            let segment_distance = cumulative[index] - cumulative[index - 1];
            if elapsed_ms > 0 && segment_distance > 0.0 {
                segment_distance / (elapsed_ms as f64 / 1_000.0)
            } else {
                0.0
            }
        };
        if !speed_mps.is_finite() || speed_mps < 0.0 {
            return Err(CoreError::SimulationFailed(QUANTIZATION_FAILURE.to_owned()));
        }

        let progress = (time_ms as f64 / target_total_as_f64).clamp(0.0, 1.0);
        let stage = if progress <= 0.15 {
            0.25 + 0.30 * (progress / 0.15)
        } else if progress <= 0.85 {
            0.55 + 0.05 * libm::sin(core::f64::consts::TAU * (progress - 0.15) / 0.70)
        } else {
            0.65 + 0.25 * ((progress - 0.85) / 0.15)
        };
        let speed_factor = if index == 0 { 1.0 } else { factors[index - 1] };
        let fraction = (stage + (speed_factor - 1.0) * 0.15).clamp(0.10, 0.95);
        let target_hr = f64::from(hr_rest) + fraction * hr_range;
        current_hr += 0.25 * (target_hr - current_hr);
        let jitter = (rng.next_unit_f64() - 0.5) * 2.0;
        let heart_rate_bpm = (current_hr + jitter)
            .round()
            .clamp(f64::from(hr_rest), f64::from(hr_max)) as u8;

        if !stage.is_finite()
            || !speed_factor.is_finite()
            || !fraction.is_finite()
            || !target_hr.is_finite()
            || !current_hr.is_finite()
            || !jitter.is_finite()
        {
            return Err(CoreError::SimulationFailed(QUANTIZATION_FAILURE.to_owned()));
        }
        samples.push(ActivitySample {
            time_ms,
            distance_cm,
            speed_mm_per_sec: quantize_speed_mm_per_sec(speed_mps),
            heart_rate_bpm,
            position_lat_semicircles: to_semicircles(point.lat),
            position_long_semicircles: to_semicircles(point.lng),
        });
    }

    let mut laps = Vec::new();
    laps.try_reserve(expanded.laps.len())
        .map_err(|_| CoreError::ResourceLimit(LAP_ALLOCATION_FAILURE.to_owned()))?;
    for range in &expanded.laps {
        let start = samples
            .get(range.start_sample)
            .ok_or_else(|| CoreError::SimulationFailed(LAP_SUMMARY_FAILURE.to_owned()))?;
        let end = samples
            .get(range.end_sample)
            .ok_or_else(|| CoreError::SimulationFailed(LAP_SUMMARY_FAILURE.to_owned()))?;
        laps.push(LapModel {
            index: range.index,
            start_sample: range.start_sample,
            end_sample: range.end_sample,
            distance_cm: end
                .distance_cm
                .checked_sub(start.distance_cm)
                .ok_or_else(|| CoreError::SimulationFailed(LAP_SUMMARY_FAILURE.to_owned()))?,
            duration_ms: end
                .time_ms
                .checked_sub(start.time_ms)
                .ok_or_else(|| CoreError::SimulationFailed(LAP_SUMMARY_FAILURE.to_owned()))?,
        });
    }

    let last = samples
        .last()
        .ok_or_else(|| CoreError::SimulationFailed(LAP_SUMMARY_FAILURE.to_owned()))?;
    Ok(ActivityModel {
        schema_version: SCHEMA_VERSION,
        algorithm_version: ALGORITHM_VERSION,
        start_time_utc: request.start_time_utc().to_owned(),
        seed: request.seed(),
        total_distance_cm: last.distance_cm,
        total_duration_ms: last.time_ms,
        laps,
        samples,
    })
}

/// Parses, validates, simulates, and serializes a deterministic activity preview.
pub fn preview_json(input: &[u8]) -> Result<Vec<u8>, CoreError> {
    let request = parse_and_validate(input)?;
    let model = build_activity_model(&request)?;
    serde_json::to_vec(&model)
        .map_err(|_| CoreError::InternalError("活动预览序列化失败".to_owned()))
}
