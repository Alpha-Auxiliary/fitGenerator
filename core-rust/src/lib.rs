#[cfg(feature = "android-jni")]
pub mod android_jni;
pub mod domain;
pub mod error;
pub mod ffi;
pub mod geo;
pub mod rng;
pub mod simulation;
pub mod validation;

pub use simulation::{build_activity_model, parse_and_validate, preview_json};
pub mod fit;
pub use fit::generate_fit;
