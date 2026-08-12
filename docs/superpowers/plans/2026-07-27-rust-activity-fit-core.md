# Rust Activity and FIT Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a deterministic, platform-neutral Rust library that validates routes, simulates activity samples, encodes a minimal FIT Activity file, and exposes Windows C ABI and Android JNI entry points.

**Architecture:** Pure Rust domain modules produce a quantized canonical `ActivityModel`; the FIT module serializes that model without Garmin runtime dependencies. The outer FFI layer accepts versioned UTF-8 JSON, catches panics, owns returned buffers, and keeps Rust layouts opaque.

**Tech Stack:** Rust 2024, serde, serde_json, thiserror, time, libc, jni, proptest, fitparser 0.10.

---

## Local-only version-control policy

Do not stage or commit. At every checkpoint run:

```bash
git diff --check
git status --short
```

## Target file map

Create:

```text
core-rust/
├── Cargo.toml
├── cbindgen.toml
├── include/fit_generator_core.h
├── src/
│   ├── lib.rs                 Public safe Rust API
│   ├── domain.rs              Requests and canonical models
│   ├── error.rs               Stable error codes
│   ├── validation.rs          Input and resource limits
│   ├── rng.rs                 SplitMix64 and seed derivation
│   ├── geo.rs                 Distance, closure, coordinate quantization
│   ├── simulation.rs          Laps, speed, time, heart-rate samples
│   ├── fit/
│   │   ├── mod.rs             FIT Activity orchestration
│   │   ├── crc.rs             FIT CRC-16
│   │   ├── writer.rs          Definition/data message primitives
│   │   └── profile.rs         Required message and field constants
│   ├── ffi.rs                 Opaque C ABI result interface
│   └── android_jni.rs         Thin JNI ByteArray adapter
└── tests/
    ├── validation_tests.rs
    ├── deterministic_tests.rs
    ├── fit_decode_tests.rs
    ├── ffi_tests.rs
    └── fixtures/
        ├── closed_single_lap.json
        ├── short_legal.json
        ├── fast_low_hr.json
        ├── open_two_laps.json
        └── invalid_heart_rate.json
```

Modify:

- `.gitignore`: ignore `core-rust/target/` and generated fuzz artifacts.
- `README.md`: add a short core architecture and local test section after the core is green.

### Task 1: Scaffold the crate and canonical domain types

**Files:**

- Create: `core-rust/Cargo.toml`
- Create: `core-rust/src/lib.rs`
- Create: `core-rust/src/domain.rs`
- Create: `core-rust/src/error.rs`
- Modify: `.gitignore`
- Test: `core-rust/src/domain.rs`

- [ ] **Step 1: Add the crate manifest**

```toml
[package]
name = "fit-generator-core"
version = "0.1.0"
edition = "2024"
license = "MIT"
publish = false

[lib]
crate-type = ["rlib", "cdylib"]

[features]
default = []
android-jni = ["dep:jni"]

[dependencies]
libc = "0.2"
libm = "0.2"
serde = { version = "1", features = ["derive"] }
serde_json = "1"
thiserror = "2"
time = { version = "0.3", features = ["formatting", "parsing", "serde"] }
jni = { version = "0.21", optional = true }

[dev-dependencies]
fitparser = "0.10"
proptest = "1"
```

- [ ] **Step 2: Write the domain serialization test**

Add to `core-rust/src/domain.rs`:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_uses_stable_camel_case_json() {
        let json = r#"{
          "schemaVersion":1,
          "startTimeUtc":"2026-07-27T08:00:00Z",
          "points":[{"lat":39.9042,"lng":116.4074},{"lat":39.9052,"lng":116.4084}],
          "paceSecondsPerKm":360.0,
          "hrRest":60,
          "hrMax":180,
          "lapCount":1,
          "variantIndex":1,
          "seed":42,
          "routeMode":"close_if_needed"
        }"#;
        let request: ActivityRequest = serde_json::from_str(json).unwrap();
        assert_eq!(request.schema_version, 1);
        assert_eq!(request.points.len(), 2);
        assert_eq!(request.route_mode, RouteMode::CloseIfNeeded);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run:

```bash
cargo test --manifest-path core-rust/Cargo.toml request_uses_stable_camel_case_json
```

Expected: compile failure because `ActivityRequest` and `RouteMode` are undefined.

- [ ] **Step 4: Implement the domain types**

```rust
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
pub struct GeoPoint {
    pub lat: f64,
    pub lng: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
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
```

In `src/error.rs` define stable codes:

```rust
use thiserror::Error;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum ErrorCode {
    Ok = 0,
    InvalidInput = 100,
    UnsupportedSchema = 101,
    ResourceLimit = 102,
    SimulationFailed = 200,
    FitEncodingFailed = 300,
    InternalError = 900,
}

#[derive(Debug, Error)]
pub enum CoreError {
    #[error("{0}")]
    InvalidInput(String),
    #[error("不支持的数据版本：{0}")]
    UnsupportedSchema(u32),
    #[error("{0}")]
    ResourceLimit(String),
    #[error("{0}")]
    SimulationFailed(String),
    #[error("{0}")]
    FitEncodingFailed(String),
}

impl CoreError {
    pub fn code(&self) -> ErrorCode {
        match self {
            Self::InvalidInput(_) => ErrorCode::InvalidInput,
            Self::UnsupportedSchema(_) => ErrorCode::UnsupportedSchema,
            Self::ResourceLimit(_) => ErrorCode::ResourceLimit,
            Self::SimulationFailed(_) => ErrorCode::SimulationFailed,
            Self::FitEncodingFailed(_) => ErrorCode::FitEncodingFailed,
        }
    }
}
```

- [ ] **Step 5: Export modules and verify**

`src/lib.rs`:

```rust
pub mod domain;
pub mod error;
```

Run:

```bash
cargo fmt --manifest-path core-rust/Cargo.toml
cargo test --manifest-path core-rust/Cargo.toml
```

Expected: the serialization test passes.

- [ ] **Step 6: Update ignore rules and checkpoint**

Append to `.gitignore`:

```gitignore
core-rust/target/
core-rust/fuzz/artifacts/
core-rust/fuzz/corpus/
```

Run `git diff --check` and `git status --short`.

### Task 2: Implement strict input validation

**Files:**

- Create: `core-rust/src/validation.rs`
- Modify: `core-rust/src/lib.rs`
- Test: `core-rust/tests/validation_tests.rs`

- [ ] **Step 1: Write failing boundary tests**

```rust
use fit_generator_core::domain::{ActivityRequest, GeoPoint, RouteMode, SCHEMA_VERSION};
use fit_generator_core::error::ErrorCode;
use fit_generator_core::validation::ValidatedRequest;

fn valid_request() -> ActivityRequest {
    ActivityRequest {
        schema_version: SCHEMA_VERSION,
        start_time_utc: "2026-07-27T08:00:00Z".into(),
        points: vec![
            GeoPoint { lat: 39.9042, lng: 116.4074 },
            GeoPoint { lat: 39.9052, lng: 116.4084 },
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

#[test]
fn rejects_reversed_heart_rate_range() {
    let mut request = valid_request();
    request.hr_rest = 100;
    request.hr_max = 100;
    let error = ValidatedRequest::try_from(request).unwrap_err();
    assert_eq!(error.code(), ErrorCode::InvalidInput);
    assert_eq!(error.to_string(), "最大心率必须高于静息心率");
}

#[test]
fn rejects_non_finite_coordinates() {
    let mut request = valid_request();
    request.points[0].lat = f64::NAN;
    assert_eq!(
        ValidatedRequest::try_from(request).unwrap_err().code(),
        ErrorCode::InvalidInput
    );
}
```

- [ ] **Step 2: Run the tests to verify failure**

Run:

```bash
cargo test --manifest-path core-rust/Cargo.toml --test validation_tests
```

Expected: compile failure because `validation` and `ValidatedRequest` do not exist.

- [ ] **Step 3: Implement all specified limits**

Create a private-field `ValidatedRequest` wrapper. Its `TryFrom<ActivityRequest>` implementation must enforce:

```rust
pub const MIN_POINTS: usize = 2;
pub const MAX_POINTS: usize = 50_000;
pub const MAX_EXPANDED_SAMPLES: usize = 500_000;
pub const MIN_LAPS: u16 = 1;
pub const MAX_LAPS: u16 = 100;
pub const MIN_PACE_SECONDS_PER_KM: f64 = 60.0;
pub const MAX_PACE_SECONDS_PER_KM: f64 = 3_600.0;
```

Use `time::OffsetDateTime::parse` with `Rfc3339`; require a UTC `Z` suffix. Reject:

- unsupported schema;
- missing, non-finite, or out-of-range coordinates;
- point count outside the limits;
- expanded point count above `MAX_EXPANDED_SAMPLES`;
- heart rates outside 30–120 and 100–220;
- `hr_max <= hr_rest`;
- pace outside the limits;
- lap count outside 1–100;
- variant index `0`;
- invalid UTC time.

Expose read-only accessors for downstream modules rather than public mutable fields.

- [ ] **Step 4: Add table-driven tests for every limit**

Use one test case per boundary and assert both `ErrorCode` and exact Chinese message. Include valid minimum and maximum values so the tests distinguish inclusive from exclusive limits.

- [ ] **Step 5: Verify**

Run:

```bash
cargo test --manifest-path core-rust/Cargo.toml --test validation_tests
cargo clippy --manifest-path core-rust/Cargo.toml --all-targets -- -D warnings
```

Expected: all validation tests pass and clippy reports no warnings.

### Task 3: Add deterministic random numbers and quantization

**Files:**

- Create: `core-rust/src/rng.rs`
- Modify: `core-rust/src/lib.rs`
- Test: `core-rust/tests/deterministic_tests.rs`

- [ ] **Step 1: Write the fixed-vector tests**

```rust
use fit_generator_core::rng::{derive_variant_seed, SplitMix64};

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
```

- [ ] **Step 2: Verify failure**

Run:

```bash
cargo test --manifest-path core-rust/Cargo.toml --test deterministic_tests
```

Expected: unresolved `rng` module.

- [ ] **Step 3: Implement SplitMix64**

```rust
#[derive(Debug, Clone)]
pub struct SplitMix64 {
    state: u64,
}

impl SplitMix64 {
    pub const fn new(seed: u64) -> Self {
        Self { state: seed }
    }

    pub fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9e37_79b9_7f4a_7c15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        z ^ (z >> 31)
    }

    pub fn next_unit_f64(&mut self) -> f64 {
        const DENOMINATOR: f64 = (1_u64 << 53) as f64;
        ((self.next_u64() >> 11) as f64) / DENOMINATOR
    }
}

pub fn derive_variant_seed(batch_seed: u64, variant_index: u16) -> u64 {
    let mixed = batch_seed ^ (u64::from(variant_index) << 32);
    SplitMix64::new(mixed).next_u64()
}
```

Add private helpers used by simulation:

```rust
pub fn quantize_time_ms(seconds: f64) -> u64 {
    (seconds * 1_000.0).round().max(0.0) as u64
}

pub fn quantize_distance_cm(meters: f64) -> u64 {
    (meters * 100.0).round().max(0.0) as u64
}

pub fn quantize_speed_mm_per_sec(meters_per_second: f64) -> u32 {
    (meters_per_second * 1_000.0).round().clamp(0.0, u32::MAX as f64) as u32
}
```

- [ ] **Step 4: Verify deterministic tests**

Run:

```bash
cargo fmt --manifest-path core-rust/Cargo.toml
cargo test --manifest-path core-rust/Cargo.toml --test deterministic_tests
```

Expected: both fixed-vector tests pass on every target.

### Task 4: Implement geometry, closure, and lap expansion

**Files:**

- Create: `core-rust/src/geo.rs`
- Modify: `core-rust/src/lib.rs`
- Test: `core-rust/tests/deterministic_tests.rs`

- [ ] **Step 1: Add failing geometry tests**

```rust
use fit_generator_core::domain::GeoPoint;
use fit_generator_core::geo::{build_closed_route, haversine_meters, to_semicircles};

#[test]
fn closes_an_open_route_once() {
    let points = vec![
        GeoPoint { lat: 39.9042, lng: 116.4074 },
        GeoPoint { lat: 39.9052, lng: 116.4084 },
    ];
    let closed = build_closed_route(&points);
    assert_eq!(closed.len(), 3);
    assert_eq!(closed.first(), closed.last());
}

#[test]
fn beijing_one_thousandth_degree_is_about_140_meters_diagonal() {
    let distance = haversine_meters(39.9042, 116.4074, 39.9052, 116.4084);
    assert!((distance - 140.1).abs() < 1.0);
}

#[test]
fn semicircle_conversion_is_stable() {
    assert_eq!(to_semicircles(180.0), i32::MIN);
    assert_eq!(to_semicircles(0.0), 0);
}
```

- [ ] **Step 2: Run and observe failure**

Run:

```bash
cargo test --manifest-path core-rust/Cargo.toml --test deterministic_tests
```

Expected: unresolved `geo` module.

- [ ] **Step 3: Implement geometry functions**

Use:

```rust
const EARTH_RADIUS_METERS: f64 = 6_371_000.0;
const METERS_PER_DEGREE_LAT: f64 = 111_320.0;
const CLOSED_THRESHOLD_METERS: f64 = 5.0;
const SEMICIRCLE_FACTOR: f64 = 2_147_483_648.0 / 180.0;
```

`to_semicircles` must compute in `i64`, wrap positive 180 degrees to `i32::MIN` per FIT signed semicircle representation, and clamp other values safely.

Implement:

- `haversine_meters`;
- `build_closed_route`;
- `offset_point_meters`;
- `cumulative_distances`;
- `expand_laps`, returning points plus exact lap start/end indices.

For lap noise, obtain one radius and angle per lap from `SplitMix64`; a single-lap route receives no offset.

- [ ] **Step 4: Add closure and lap property tests**

Assert:

- already-closed input is not duplicated;
- a two-lap route produces two lap ranges;
- cumulative distance is finite and monotonic;
- every generated coordinate remains in valid latitude/longitude bounds.

- [ ] **Step 5: Verify**

Run:

```bash
cargo test --manifest-path core-rust/Cargo.toml --test deterministic_tests
```

Expected: all deterministic and geometry tests pass.

### Task 5: Implement canonical activity simulation

**Files:**

- Create: `core-rust/src/simulation.rs`
- Modify: `core-rust/src/lib.rs`
- Test: `core-rust/tests/deterministic_tests.rs`
- Test: `core-rust/tests/fixtures/closed_single_lap.json`
- Test: `core-rust/tests/fixtures/short_legal.json`
- Test: `core-rust/tests/fixtures/fast_low_hr.json`
- Test: `core-rust/tests/fixtures/open_two_laps.json`

- [ ] **Step 1: Add fixture requests**

`closed_single_lap.json`:

```json
{
  "schemaVersion": 1,
  "startTimeUtc": "2026-07-27T08:00:00Z",
  "points": [
    { "lat": 39.9042, "lng": 116.4074 },
    { "lat": 39.9052, "lng": 116.4084 },
    { "lat": 39.9042, "lng": 116.4074 }
  ],
  "paceSecondsPerKm": 360.0,
  "hrRest": 60,
  "hrMax": 180,
  "lapCount": 1,
  "variantIndex": 1,
  "seed": 42,
  "routeMode": "close_if_needed"
}
```

`open_two_laps.json`:

```json
{
  "schemaVersion": 1,
  "startTimeUtc": "2026-07-27T08:00:00Z",
  "points": [
    { "lat": 39.9042, "lng": 116.4074 },
    { "lat": 39.9052, "lng": 116.4084 }
  ],
  "paceSecondsPerKm": 330.0,
  "hrRest": 55,
  "hrMax": 190,
  "lapCount": 2,
  "variantIndex": 1,
  "seed": 987654321,
  "routeMode": "close_if_needed"
}
```

`short_legal.json` uses start `2026-07-27T08:00:00Z`, points `(0, 0)` and `(0, 0.000001)`, pace `3600`, HR `120–220`, one lap, variant `1`, and seed `1`.

`fast_low_hr.json` uses the two Beijing points above, pace `60`, HR `30–100`, one lap, variant `2`, and seed `18446744073709551615`.

Both use schema `1` and `routeMode: "close_if_needed"`. Write every field explicitly in the JSON files; do not rely on serde defaults.

- [ ] **Step 2: Write the failing model invariants test**

```rust
use fit_generator_core::{build_activity_model, parse_and_validate};

#[test]
fn model_is_quantized_monotonic_and_reproducible() {
    let json = include_str!("fixtures/open_two_laps.json");
    let request = parse_and_validate(json.as_bytes()).unwrap();
    let first = build_activity_model(&request).unwrap();
    let second = build_activity_model(&request).unwrap();

    assert_eq!(first, second);
    assert_eq!(first.laps.len(), 2);
    assert!(first.samples.windows(2).all(|w| {
        w[0].time_ms <= w[1].time_ms && w[0].distance_cm <= w[1].distance_cm
    }));
    assert!(first.samples.iter().all(|s| (55..=190).contains(&s.heart_rate_bpm)));
}
```

- [ ] **Step 3: Run and verify failure**

Run:

```bash
cargo test --manifest-path core-rust/Cargo.toml --test deterministic_tests
```

Expected: missing `parse_and_validate` and `build_activity_model`.

- [ ] **Step 4: Implement parsing and simulation**

Expose from `lib.rs`:

```rust
pub fn parse_and_validate(input: &[u8]) -> Result<ValidatedRequest, CoreError>;
pub fn build_activity_model(request: &ValidatedRequest) -> Result<ActivityModel, CoreError>;
pub fn preview_json(input: &[u8]) -> Result<Vec<u8>, CoreError>;
```

Simulation must:

1. close the base route;
2. seed one `SplitMix64` with `derive_variant_seed(request.seed, request.variant_index)`;
3. expand laps with deterministic noise;
4. compute cumulative distance;
5. calculate target duration from pace;
6. generate long-wave and short-wave speed factors;
7. derive raw segment durations;
8. scale durations to the target total;
9. smooth heart rate toward the three-stage target;
10. quantize every sample;
11. construct lap ranges from the expansion metadata.

Protect all divisions with explicit nonzero checks. Return `SimulationFailed` if any intermediate value is non-finite.

Use this exact first-version algorithm:

- lap `0` keeps the input coordinates;
- each later lap consumes two RNG values, with offset radius `5 + 5u` meters and angle `2πv`;
- append every point of each shifted closed lap; the connection from the preceding lap's end to the new lap's start counts toward the new lap;
- lap `0` spans sample indices `0..base_len-1`; later lap `i` spans `i*base_len-1..(i+1)*base_len-1`, so neighboring lap ranges share their boundary sample and lap totals sum to the activity total;
- consume two RNG values for `long_phase = 2πu` and `short_phase = 2πv`;
- for segment index `i` in `1..sample_count`, let `p = i / (sample_count-1)`;
- speed factor is
  `clamp((1 + 0.08*sin(2πp + long_phase)) * (1 + 0.03*sin(10πp + short_phase)) * (0.98 + 0.04u), 0.80, 1.20)`;
- use `libm` for trigonometric and square-root operations in geometry and simulation instead of target C math libraries;
- target speed is `1000 / pace_seconds_per_km`;
- raw segment duration is `segment_distance / (target_speed * speed_factor)`;
- target total milliseconds is `round(total_distance_meters * pace_seconds_per_km)`;
- quantized cumulative time at segment `i` is
  `round(raw_cumulative_i / raw_total * target_total_ms)`, with the final sample forced to `target_total_ms`;
- each noninitial sample speed is its segment distance divided by its quantized segment duration; a zero-duration or zero-distance segment has speed `0`; the initial sample uses target speed;
- the initial sample's heart-rate speed factor is `1.0`; later samples use their segment factor;
- heart-rate progress is quantized cumulative time divided by `target_total_ms`;
- heart-rate stage fraction is linear `0.25→0.55` over progress `0.00→0.15`, sinusoidal `0.55 + 0.05*sin(2π*(p-0.15)/0.70)` over `0.15→0.85`, and linear `0.65→0.90` over `0.85→1.00`;
- add `(speed_factor - 1) * 0.15`, clamp the fraction to `0.10..0.95`, convert it into the configured HR range, then update `current_hr += 0.25*(target_hr-current_hr)`;
- consume one RNG value per sample for additive jitter `(u-0.5)*2.0` bpm, round to an integer, and clamp to `hr_rest..hr_max`;
- initialize `current_hr` to `hr_rest + 0.20*(hr_max-hr_rest)`.

- [ ] **Step 5: Add canonical contract assertions**

```rust
assert_eq!(model.algorithm_version, 1);
assert_eq!(model.seed, 987654321);
assert_eq!(model.laps.len(), 2);
assert!(model.samples.len() >= 5);
assert_eq!(model.samples.first().unwrap().time_ms, 0);
assert_eq!(model.samples.first().unwrap().distance_cm, 0);
assert_eq!(model.total_duration_ms, model.samples.last().unwrap().time_ms);
assert_eq!(model.total_distance_cm, model.samples.last().unwrap().distance_cm);
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
```

Serialize the model twice with `serde_json::to_vec` and assert byte-for-byte equality. These assertions define the stable cross-platform contract without accepting an implementation-generated snapshot as its own oracle.

- [ ] **Step 6: Verify properties**

Add proptest cases generating 2–100 finite WGS-84 points and assert no successful model contains decreasing time/distance or out-of-range heart rate.

For valid generated requests limited to 2–20 points and 1–3 laps, call `generate_fit` and require `fitparser::from_bytes` to succeed. Set the proptest case count to `64` so the normal test suite remains bounded.

Run:

```bash
cargo test --manifest-path core-rust/Cargo.toml --test deterministic_tests
cargo test --manifest-path core-rust/Cargo.toml --all-features
```

Expected: deterministic, fixture, and property tests pass.

### Task 6: Implement FIT CRC and binary writer primitives

**Files:**

- Create: `core-rust/src/fit/mod.rs`
- Create: `core-rust/src/fit/crc.rs`
- Create: `core-rust/src/fit/writer.rs`
- Create: `core-rust/src/fit/profile.rs`
- Modify: `core-rust/src/lib.rs`
- Test: `core-rust/src/fit/crc.rs`
- Test: `core-rust/src/fit/writer.rs`

- [ ] **Step 1: Write CRC reference tests**

```rust
#[test]
fn crc_matches_fit_reference_vector() {
    let bytes = [0x0e, 0x20, 0x82, 0x08, 0, 0, 0, 0, b'.', b'F', b'I', b'T'];
    // Verified with the public FIT 16-entry nibble-table algorithm.
    assert_eq!(fit_crc(&bytes), 0x675d);
}
```

- [ ] **Step 2: Implement FIT CRC-16**

Use the 16-entry nibble table from the FIT protocol and update twice per byte:

```rust
const CRC_TABLE: [u16; 16] = [
    0x0000, 0xcc01, 0xd801, 0x1400,
    0xf001, 0x3c00, 0x2800, 0xe401,
    0xa001, 0x6c00, 0x7800, 0xb401,
    0x5000, 0x9c01, 0x8801, 0x4400,
];
```

- [ ] **Step 3: Write a failing definition/data round-trip test**

Build one local message definition containing a uint32 timestamp and one data message. Assert exact bytes for:

- definition header;
- little-endian global message number;
- field count and field descriptor;
- local data header;
- little-endian data value.

- [ ] **Step 4: Implement writer primitives**

Define:

```rust
pub struct FieldDef {
    pub number: u8,
    pub size: u8,
    pub base_type: u8,
}

pub struct FitDataWriter {
    data: Vec<u8>,
}
```

Provide methods for definition messages, local data headers, and little-endian `u8`, `u16`, `u32`, and `i32`. Reject local message numbers above 15 and field counts above 255.

- [ ] **Step 5: Add required profile constants**

`profile.rs` must define only these global messages:

```text
file_id=0, session=18, lap=19, record=20,
event=21, device_info=23, activity=34
```

Define these field numbers:

```text
file_id: type=0, manufacturer=1, product=2, serial_number=3, time_created=4
device_info: timestamp=253, device_index=0, manufacturer=2, product=4
event: timestamp=253, event=0, event_type=1
record: timestamp=253, position_lat=0, position_long=1,
        heart_rate=3, distance=5, speed=6
lap: timestamp=253, event=0, event_type=1, start_time=2,
     start_position_lat=3, start_position_long=4,
     end_position_lat=5, end_position_long=6,
     total_elapsed_time=7, total_timer_time=8, total_distance=9,
     avg_speed=13, max_speed=14, avg_heart_rate=15, max_heart_rate=16
session: timestamp=253, event=0, event_type=1, start_time=2,
         start_position_lat=3, start_position_long=4, sport=5, sub_sport=6,
         total_elapsed_time=7, total_timer_time=8, total_distance=9,
         avg_speed=14, max_speed=15, avg_heart_rate=16, max_heart_rate=17,
         first_lap_index=25, num_laps=26
activity: timestamp=253, total_timer_time=0, num_sessions=1,
          type=2, event=3, event_type=4
```

Define FIT base types:

```text
enum=0x00, uint8=0x02, uint16=0x84, sint32=0x85,
uint32=0x86, uint32z=0x8c
```

Define enum values:

```text
file activity=4, manufacturer development=255,
sport running=1, sub_sport generic=0,
event timer=0, event_type start=0, event_type stop_all=4,
activity manual=0
```

Do not copy generated Garmin profile source.

- [ ] **Step 6: Verify**

Run:

```bash
cargo test --manifest-path core-rust/Cargo.toml fit::
```

Expected: CRC and writer byte-vector tests pass.

### Task 7: Encode a standards-shaped FIT Activity

**Files:**

- Modify: `core-rust/src/fit/mod.rs`
- Modify: `core-rust/src/lib.rs`
- Test: `core-rust/tests/fit_decode_tests.rs`

- [ ] **Step 1: Write the failing decode test**

```rust
use fit_generator_core::generate_fit;
use fitparser::profile::MesgNum;

#[test]
fn generated_activity_has_valid_header_crc_and_messages() {
    let request = include_bytes!("fixtures/closed_single_lap.json");
    let bytes = generate_fit(request).unwrap();

    assert_eq!(&bytes[8..12], b".FIT");
    let records = fitparser::from_bytes(&bytes).unwrap();
    let kinds: Vec<_> = records.iter().map(|record| record.kind()).collect();
    assert!(kinds.contains(&MesgNum::FileId));
    assert!(kinds.contains(&MesgNum::Record));
    assert!(kinds.contains(&MesgNum::Lap));
    assert!(kinds.contains(&MesgNum::Session));
    assert!(kinds.contains(&MesgNum::Activity));
}
```

- [ ] **Step 2: Run and verify failure**

Run:

```bash
cargo test --manifest-path core-rust/Cargo.toml --test fit_decode_tests
```

Expected: missing `generate_fit`.

- [ ] **Step 3: Implement FIT timestamp conversion**

Convert RFC3339 UTC to seconds since `1989-12-31T00:00:00Z`. Reject pre-epoch or overflowing timestamps with `FitEncodingFailed`.

- [ ] **Step 4: Implement message emission**

Write definitions once and data in this semantic order:

1. `file_id`
2. `device_info`
3. timer start `event`
4. chronological `record` messages
5. one `lap` per canonical lap
6. `session`
7. timer stop-all `event`
8. `activity`

Use:

- distance: centimeters encoded as uint32 scale 100;
- speed: millimeters/second encoded as uint16 scale 1000, rejecting values above `u16::MAX`;
- positions: canonical semicircle integers;
- elapsed/timer time: milliseconds encoded as uint32 scale 1000;
- timestamp: uint32 FIT epoch seconds.

Construct a 14-byte header, calculate its CRC, append data records, then append the complete file CRC.

- [ ] **Step 5: Expand independent decode assertions**

Decode every fixture and assert:

- zero parser errors;
- record count equals sample count;
- lap count equals request lap count;
- decoded total distance and duration match the canonical model within the FIT field resolution;
- first and last coordinates match the canonical semicircle values;
- trailing CRC matches a fresh independent calculation.

- [ ] **Step 6: Verify**

Run:

```bash
cargo test --manifest-path core-rust/Cargo.toml --test fit_decode_tests
cargo test --manifest-path core-rust/Cargo.toml --all-features
```

Expected: all generated fixtures decode successfully.

### Task 8: Expose the opaque C ABI

**Files:**

- Create: `core-rust/src/ffi.rs`
- Create: `core-rust/cbindgen.toml`
- Create: `core-rust/include/fit_generator_core.h`
- Modify: `core-rust/src/lib.rs`
- Test: `core-rust/tests/ffi_tests.rs`

- [ ] **Step 1: Write failing FFI ownership tests**

Call the exported Rust functions directly from Rust `unsafe` tests. Cover:

- null request pointer;
- invalid JSON;
- valid preview;
- valid FIT;
- result data access;
- error string access;
- exactly one `fg_result_free`;
- API version `1`.

Add a proptest that passes 0–4096 arbitrary bytes to both generation entry points, reads the code/error/data accessors, and frees every nonnull result. Run 256 cases and assert that no panic crosses the call boundary.

- [ ] **Step 2: Implement the opaque result**

```rust
pub struct FgResult {
    code: i32,
    data: Vec<u8>,
    error: std::ffi::CString,
}
```

Export exactly:

```c
FgResult* fg_preview(const uint8_t* request, size_t length);
FgResult* fg_generate_fit(const uint8_t* request, size_t length);
const uint8_t* fg_result_data(const FgResult* result);
size_t fg_result_length(const FgResult* result);
int32_t fg_result_code(const FgResult* result);
const char* fg_result_error(const FgResult* result);
void fg_result_free(FgResult* result);
uint32_t fg_core_api_version(void);
```

`FgResult` remains opaque in the generated C header.

Use `std::panic::catch_unwind` around parsing, preview, and FIT generation. Convert any panic to `InternalError` and the message `核心发生内部错误`.

Accessor rules:

- null result returns safe defaults;
- empty data returns a null data pointer and length zero;
- error pointer remains valid until `fg_result_free`;
- `fg_result_free(null)` is a no-op.

- [ ] **Step 3: Generate and inspect the header**

Configure cbindgen to export only the ABI functions and opaque `FgResult`. Generate:

```bash
cargo install cbindgen --version 0.29.0 --locked
cbindgen core-rust --config core-rust/cbindgen.toml --output core-rust/include/fit_generator_core.h
```

Expected: the header contains no Rust-only type layout and declares `FgResult` as incomplete.

- [ ] **Step 4: Verify ABI tests**

Run:

```bash
cargo test --manifest-path core-rust/Cargo.toml --test ffi_tests
cargo clippy --manifest-path core-rust/Cargo.toml --all-targets --all-features -- -D warnings
```

Expected: ownership, null, error, and success cases pass.

### Task 9: Add the Android JNI wrapper

**Files:**

- Create: `core-rust/src/android_jni.rs`
- Modify: `core-rust/src/lib.rs`
- Test: `core-rust/src/android_jni.rs`

- [ ] **Step 1: Define the Kotlin-facing contract in a Rust test**

The JNI wrapper exposes:

```text
nativeApiVersion(): Int
nativePreview(requestUtf8: ByteArray): ByteArray
nativeGenerateFit(requestUtf8: ByteArray): ByteArray
nativeLastError(): String
```

The wrapper delegates to the safe Rust API, never to raw FFI pointers. A failed call returns an empty byte array and stores a thread-local structured error JSON for `nativeLastError`.

- [ ] **Step 2: Implement JNI exports**

Under `#[cfg(all(target_os = "android", feature = "android-jni"))]`, export `extern "system"` functions matching:

```text
Java_com_alphaauxiliary_fitgenerator_core_NativeCore_nativeApiVersion
Java_com_alphaauxiliary_fitgenerator_core_NativeCore_nativePreview
Java_com_alphaauxiliary_fitgenerator_core_NativeCore_nativeGenerateFit
Java_com_alphaauxiliary_fitgenerator_core_NativeCore_nativeLastError
```

Use `jni::objects::JByteArray` and `JNIEnv::convert_byte_array`. Convert Rust output with `byte_array_from_slice`. Do not cache `JNIEnv`.

- [ ] **Step 3: Cross-compile both Android ABIs**

Install targets and cargo-ndk:

```bash
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --locked
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs \
  build --manifest-path core-rust/Cargo.toml --release --features android-jni
```

Expected:

```text
android/app/src/main/jniLibs/arm64-v8a/libfit_generator_core.so
android/app/src/main/jniLibs/x86_64/libfit_generator_core.so
```

- [ ] **Step 4: Verify host tests remain green**

Run:

```bash
cargo test --manifest-path core-rust/Cargo.toml --all-features
```

Expected: host build excludes Android-only JNI symbols and all tests pass.

### Task 10: Final quality, license, and documentation gate

**Files:**

- Modify: `README.md`
- Create: `core-rust/README.md`
- Create: `core-rust/THIRD_PARTY_LICENSES.md`

- [ ] **Step 1: Document the stable contract**

`core-rust/README.md` must include:

- supported schema and API version;
- exact cargo test commands;
- Windows and Android build commands;
- memory ownership rules;
- canonical quantization units;
- FIT message subset;
- statement that no Garmin SDK code or binary is included.

- [ ] **Step 2: Generate dependency license evidence**

Install and run:

```bash
cargo install cargo-deny --locked
cargo deny --manifest-path core-rust/Cargo.toml check licenses bans sources
```

Expected: all runtime and development dependencies use approved open-source licenses; no unknown registry or Git dependency appears.

- [ ] **Step 3: Run the complete core gate**

```bash
cargo fmt --manifest-path core-rust/Cargo.toml --check
cargo clippy --manifest-path core-rust/Cargo.toml --all-targets --all-features -- -D warnings
cargo test --manifest-path core-rust/Cargo.toml --all-features
cargo tree --manifest-path core-rust/Cargo.toml
git diff --check
```

Expected:

- all commands exit `0`;
- `cargo tree` contains no Garmin SDK dependency;
- generated FIT decode tests pass;
- working tree contains only intended local changes.
