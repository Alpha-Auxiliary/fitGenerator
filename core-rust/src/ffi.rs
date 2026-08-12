use std::{
    ffi::{c_char, CString},
    panic::{catch_unwind, AssertUnwindSafe},
    ptr,
};

use crate::{
    domain::CORE_API_VERSION,
    error::{CoreError, ErrorCode},
    generate_fit, preview_json,
};

type CoreOperation = fn(&[u8]) -> Result<Vec<u8>, CoreError>;

/// Owned result returned across the C boundary.
///
/// The fields intentionally remain private: foreign callers must treat this as
/// an opaque handle and use the accessors below.
pub struct FgResult {
    code: i32,
    data: Vec<u8>,
    error: CString,
}

impl FgResult {
    fn success(data: Vec<u8>) -> Self {
        Self {
            code: ErrorCode::Ok as i32,
            data,
            error: CString::default(),
        }
    }

    fn failure(error: CoreError) -> Self {
        let code = error.code() as i32;
        Self {
            code,
            data: Vec::new(),
            error: safe_c_string(&error.to_string()),
        }
    }

    fn internal_error() -> Self {
        Self::failure(CoreError::InternalError("核心发生内部错误".to_owned()))
    }
}

fn safe_c_string(message: &str) -> CString {
    let sanitized = message.replace('\0', "�");
    match CString::new(sanitized) {
        Ok(value) => value,
        Err(_) => CString::default(),
    }
}

fn execute(
    request: *const u8,
    length: usize,
    operation: CoreOperation,
) -> *mut FgResult {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        let operation_result = if length == 0 {
            operation(&[])
        } else if request.is_null() {
            Err(CoreError::InvalidInput("请求数据指针为空".to_owned()))
        } else if length > isize::MAX as usize {
            Err(CoreError::ResourceLimit(
                "请求数据长度超出平台支持范围".to_owned(),
            ))
        } else {
            // SAFETY: The caller contract requires `request` to reference at
            // least `length` readable bytes whenever `length` is non-zero.
            let input = unsafe { std::slice::from_raw_parts(request, length) };
            operation(input)
        };

        let result = match operation_result {
            Ok(data) => FgResult::success(data),
            Err(error) => FgResult::failure(error),
        };
        Box::into_raw(Box::new(result))
    }));

    match outcome {
        Ok(result) => result,
        Err(_) => Box::into_raw(Box::new(FgResult::internal_error())),
    }
}

/// Builds a deterministic JSON preview.
///
/// # Safety
///
/// When `length` is non-zero, `request` must reference at least `length`
/// readable bytes for the duration of this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fg_preview(request: *const u8, length: usize) -> *mut FgResult {
    execute(request, length, preview_json)
}

/// Builds a deterministic FIT activity.
///
/// # Safety
///
/// When `length` is non-zero, `request` must reference at least `length`
/// readable bytes for the duration of this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fg_generate_fit(request: *const u8, length: usize) -> *mut FgResult {
    execute(request, length, generate_fit)
}

/// Returns the result payload, which remains valid until `fg_result_free`.
///
/// # Safety
///
/// `result` must be null or a live handle returned by this library.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fg_result_data(result: *const FgResult) -> *const u8 {
    catch_unwind(AssertUnwindSafe(|| {
        if result.is_null() {
            return ptr::null();
        }
        // SAFETY: The caller contract requires a non-null `result` to be a
        // live handle created by this library.
        let result = unsafe { &*result };
        if result.data.is_empty() {
            ptr::null()
        } else {
            result.data.as_ptr()
        }
    }))
    .unwrap_or(ptr::null())
}

/// Returns the payload length in bytes.
///
/// # Safety
///
/// `result` must be null or a live handle returned by this library.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fg_result_length(result: *const FgResult) -> usize {
    catch_unwind(AssertUnwindSafe(|| {
        if result.is_null() {
            return 0;
        }
        // SAFETY: The caller contract requires a non-null `result` to be a
        // live handle created by this library.
        unsafe { &*result }.data.len()
    }))
    .unwrap_or(0)
}

/// Returns zero for success or a stable core error code.
///
/// # Safety
///
/// `result` must be null or a live handle returned by this library.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fg_result_code(result: *const FgResult) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        if result.is_null() {
            return ErrorCode::InternalError as i32;
        }
        // SAFETY: The caller contract requires a non-null `result` to be a
        // live handle created by this library.
        unsafe { &*result }.code
    }))
    .unwrap_or(ErrorCode::InternalError as i32)
}

/// Returns a NUL-terminated error string valid until `fg_result_free`.
///
/// # Safety
///
/// `result` must be null or a live handle returned by this library.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fg_result_error(result: *const FgResult) -> *const c_char {
    catch_unwind(AssertUnwindSafe(|| {
        if result.is_null() {
            return ptr::null();
        }
        // SAFETY: The caller contract requires a non-null `result` to be a
        // live handle created by this library.
        unsafe { &*result }.error.as_ptr()
    }))
    .unwrap_or(ptr::null())
}

/// Releases a result handle. A non-null handle may be released exactly once.
///
/// # Safety
///
/// `result` must be null or a live, not-yet-freed handle returned by this
/// library. Passing the same non-null handle more than once is invalid.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fg_result_free(result: *mut FgResult) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if !result.is_null() {
            // SAFETY: The caller contract grants this function unique ownership
            // of a live handle returned by `Box::into_raw`, exactly once.
            unsafe { drop(Box::from_raw(result)) };
        }
    }));
}

/// Returns the stable C API version implemented by this library.
#[unsafe(no_mangle)]
pub extern "C" fn fg_core_api_version() -> u32 {
    catch_unwind(AssertUnwindSafe(|| CORE_API_VERSION)).unwrap_or(CORE_API_VERSION)
}
