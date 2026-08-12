/// A deterministic SplitMix64 pseudo-random number generator.
#[derive(Debug, Clone)]
pub struct SplitMix64 {
    state: u64,
}

impl SplitMix64 {
    /// Creates a generator with the supplied internal state.
    pub const fn new(seed: u64) -> Self {
        Self { state: seed }
    }

    /// Returns the next uniformly distributed 64-bit value.
    pub fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9e37_79b9_7f4a_7c15);
        let mut value = self.state;
        value = (value ^ (value >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        value = (value ^ (value >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        value ^ (value >> 31)
    }

    /// Returns the next value in the half-open interval `[0.0, 1.0)`.
    pub fn next_unit_f64(&mut self) -> f64 {
        ((self.next_u64() >> 11) as f64) / ((1_u64 << 53) as f64)
    }
}

/// Derives a deterministic per-variant seed from a batch seed and variant index.
pub fn derive_variant_seed(batch_seed: u64, variant_index: u16) -> u64 {
    let mixed = batch_seed ^ ((variant_index as u64) << 32);
    SplitMix64::new(mixed).next_u64()
}

/// Quantizes seconds to milliseconds, rounding to the nearest integer.
pub fn quantize_time_ms(seconds: f64) -> u64 {
    quantize_u64(seconds, 1_000.0)
}

/// Quantizes meters to centimeters, rounding to the nearest integer.
pub fn quantize_distance_cm(meters: f64) -> u64 {
    quantize_u64(meters, 100.0)
}

/// Quantizes meters per second to millimeters per second, rounding to the nearest integer.
pub fn quantize_speed_mm_per_sec(mps: f64) -> u32 {
    if mps.is_nan() || mps <= 0.0 {
        return 0;
    }
    if mps == f64::INFINITY {
        return u32::MAX;
    }

    let scaled = mps * 1_000.0;
    if !scaled.is_finite() || scaled >= u32::MAX as f64 {
        return u32::MAX;
    }

    let rounded = scaled.round();
    if rounded >= u32::MAX as f64 {
        u32::MAX
    } else {
        rounded as u32
    }
}

fn quantize_u64(value: f64, scale: f64) -> u64 {
    if value.is_nan() || value <= 0.0 {
        return 0;
    }
    if value == f64::INFINITY {
        return u64::MAX;
    }

    let scaled = value * scale;
    if !scaled.is_finite() || scaled >= u64::MAX as f64 {
        return u64::MAX;
    }

    let rounded = scaled.round();
    if rounded >= u64::MAX as f64 {
        u64::MAX
    } else {
        rounded as u64
    }
}
