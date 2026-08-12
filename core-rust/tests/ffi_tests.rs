use std::{ffi::CStr, ptr, slice};

use fit_generator_core::{
    error::ErrorCode,
    ffi::{
        FgResult, fg_core_api_version, fg_generate_fit, fg_preview, fg_result_code, fg_result_data,
        fg_result_error, fg_result_free, fg_result_length,
    },
};
use proptest::prelude::*;

const VALID_REQUEST: &[u8] = include_bytes!("fixtures/closed_single_lap.json");

struct ResultGuard(*mut FgResult);

impl Drop for ResultGuard {
    fn drop(&mut self) {
        // SAFETY: The guard owns the live handle and drops it exactly once;
        // `fg_result_free` also explicitly accepts null.
        unsafe { fg_result_free(self.0) };
    }
}

unsafe fn copy_data(result: *const FgResult) -> Vec<u8> {
    // SAFETY: Callers pass a live result handle owned by the current test.
    let length = unsafe { fg_result_length(result) };
    // SAFETY: Callers pass a live result handle owned by the current test.
    let data = unsafe { fg_result_data(result) };
    if length == 0 {
        assert!(data.is_null());
        Vec::new()
    } else {
        assert!(!data.is_null());
        // SAFETY: The result accessors guarantee `data` references `length`
        // bytes until the live result handle is freed.
        unsafe { slice::from_raw_parts(data, length) }.to_vec()
    }
}

unsafe fn copy_error(result: *const FgResult) -> String {
    // SAFETY: Callers pass a live result handle owned by the current test.
    let error = unsafe { fg_result_error(result) };
    assert!(!error.is_null());
    // SAFETY: `fg_result_error` returns a NUL-terminated string which remains
    // valid while `result` is live.
    unsafe { CStr::from_ptr(error) }
        .to_string_lossy()
        .into_owned()
}

#[test]
fn reports_api_version_one() {
    assert_eq!(fg_core_api_version(), 1);
}

#[test]
fn null_request_with_nonzero_length_returns_invalid_input() {
    // SAFETY: A null request is explicitly supported and no bytes are read.
    let result = unsafe { fg_preview(ptr::null(), 1) };
    assert!(!result.is_null());
    // SAFETY: `result` is live until the final free in this test.
    unsafe {
        assert_eq!(fg_result_code(result), ErrorCode::InvalidInput as i32);
        assert_eq!(fg_result_length(result), 0);
        assert!(fg_result_data(result).is_null());
        assert_eq!(copy_error(result), "请求数据指针为空");
        fg_result_free(result);
    }
}

#[test]
fn zero_length_null_request_is_processed_as_empty_json() {
    // SAFETY: Length zero means the request pointer is never dereferenced.
    let result = unsafe { fg_preview(ptr::null(), 0) };
    assert!(!result.is_null());
    // SAFETY: `result` is live until the final free in this test.
    unsafe {
        assert_eq!(fg_result_code(result), ErrorCode::InvalidInput as i32);
        assert_eq!(copy_error(result), "请求数据不是有效的 JSON");
        fg_result_free(result);
    }
}

#[test]
fn oversized_request_length_is_rejected_before_reading_the_pointer() {
    let request = ptr::NonNull::<u8>::dangling().as_ptr();
    let length = (isize::MAX as usize) + 1;
    // SAFETY: The oversized length is rejected before the deliberately dangling
    // pointer can be read.
    let result = unsafe { fg_preview(request, length) };
    assert!(!result.is_null());
    // SAFETY: `result` is live until the final free in this test.
    unsafe {
        assert_eq!(fg_result_code(result), ErrorCode::ResourceLimit as i32);
        assert_eq!(copy_error(result), "请求数据长度超出平台支持范围");
        fg_result_free(result);
    }
}

#[test]
fn invalid_json_returns_a_stable_error_string() {
    let request = b"{";
    // SAFETY: `request` supplies exactly the declared number of readable bytes.
    let result = unsafe { fg_generate_fit(request.as_ptr(), request.len()) };
    assert!(!result.is_null());
    // SAFETY: `result` is live until the final free in this test.
    unsafe {
        assert_eq!(fg_result_code(result), ErrorCode::InvalidInput as i32);
        assert!(copy_data(result).is_empty());
        assert_eq!(copy_error(result), "请求数据不是有效的 JSON");
        fg_result_free(result);
    }
}

#[test]
fn valid_preview_exposes_json_data_and_an_empty_error() {
    // SAFETY: The fixture supplies exactly the declared number of readable bytes.
    let result = unsafe { fg_preview(VALID_REQUEST.as_ptr(), VALID_REQUEST.len()) };
    assert!(!result.is_null());
    // SAFETY: `result` is live until the final free in this test.
    unsafe {
        assert_eq!(fg_result_code(result), ErrorCode::Ok as i32);
        let data = copy_data(result);
        let preview: serde_json::Value = serde_json::from_slice(&data).expect("preview JSON");
        assert_eq!(preview["schemaVersion"], 1);
        assert_eq!(copy_error(result), "");
        fg_result_free(result);
    }
}

#[test]
fn valid_fit_exposes_binary_data_until_free() {
    // SAFETY: The fixture supplies exactly the declared number of readable bytes.
    let result = unsafe { fg_generate_fit(VALID_REQUEST.as_ptr(), VALID_REQUEST.len()) };
    assert!(!result.is_null());
    // SAFETY: `result` is live until the final free in this test.
    unsafe {
        assert_eq!(fg_result_code(result), ErrorCode::Ok as i32);
        let data = copy_data(result);
        assert!(data.len() >= 14);
        assert_eq!(&data[8..12], b".FIT");
        assert_eq!(copy_error(result), "");
        fg_result_free(result);
    }
}

#[test]
fn null_result_accessors_and_free_use_safe_defaults() {
    // SAFETY: Every accessor and free explicitly accepts a null result handle.
    unsafe {
        assert!(fg_result_data(ptr::null()).is_null());
        assert_eq!(fg_result_length(ptr::null()), 0);
        assert_eq!(fg_result_code(ptr::null()), ErrorCode::InternalError as i32);
        assert!(fg_result_error(ptr::null()).is_null());
        fg_result_free(ptr::null_mut());
    }
}

#[test]
fn checked_in_header_exposes_only_the_opaque_contract() {
    let header = include_str!("../include/fit_generator_core.h");
    assert!(header.contains("typedef struct FgResult FgResult;"));
    assert!(!header.contains("struct FgResult {"));
    for declaration in [
        "FgResult *fg_preview(const uint8_t *request, size_t length);",
        "FgResult *fg_generate_fit(const uint8_t *request, size_t length);",
        "const uint8_t *fg_result_data(const FgResult *result);",
        "size_t fg_result_length(const FgResult *result);",
        "int32_t fg_result_code(const FgResult *result);",
        "const char *fg_result_error(const FgResult *result);",
        "void fg_result_free(FgResult *result);",
        "uint32_t fg_core_api_version(void);",
    ] {
        assert!(
            header.contains(declaration),
            "missing declaration: {declaration}"
        );
    }
    assert_eq!(header.matches("fg_").count(), 8);
}

fn exercise_arbitrary_request(
    entry: unsafe extern "C" fn(*const u8, usize) -> *mut FgResult,
    bytes: &[u8],
) {
    // SAFETY: The slice supplies exactly the declared number of readable bytes.
    let result = unsafe { entry(bytes.as_ptr(), bytes.len()) };
    let result = ResultGuard(result);
    assert!(!result.0.is_null());
    // SAFETY: The returned handle remains live throughout all accessor calls and
    // is released exactly once by `ResultGuard`, including assertion failures.
    unsafe {
        let code = fg_result_code(result.0);
        let length = fg_result_length(result.0);
        let data = fg_result_data(result.0);
        let error = fg_result_error(result.0);
        assert!(!error.is_null());
        let _ = CStr::from_ptr(error).to_bytes();
        if length == 0 {
            assert!(data.is_null());
        } else {
            assert!(!data.is_null());
            let _ = slice::from_raw_parts(data, length);
        }
        assert!(code == ErrorCode::Ok as i32 || code >= ErrorCode::InvalidInput as i32);
    }
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(256))]

    #[test]
    fn arbitrary_bytes_never_unwind_across_either_entry(
        bytes in prop::collection::vec(any::<u8>(), 0..=4096),
    ) {
        exercise_arbitrary_request(fg_preview, &bytes);
        exercise_arbitrary_request(fg_generate_fit, &bytes);
    }
}
