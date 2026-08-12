#![cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]

use std::{
    cell::RefCell,
    panic::{catch_unwind, AssertUnwindSafe},
};

use serde::Serialize;

use crate::error::{CoreError, ErrorCode};

#[cfg(target_os = "android")]
use std::ptr;

#[cfg(target_os = "android")]
use jni::{
    objects::{JByteArray, JObject, JString},
    sys::{jbyteArray, jint, jstring},
    JNIEnv,
};

#[cfg(target_os = "android")]
use crate::{domain::CORE_API_VERSION, generate_fit, preview_json};

const INTERNAL_ERROR_MESSAGE: &str = "核心发生内部错误";
const INTERNAL_ERROR_JSON: &str = "{\"code\":900,\"message\":\"核心发生内部错误\"}";

thread_local! {
    static LAST_ERROR: RefCell<String> = const { RefCell::new(String::new()) };
}

#[derive(Debug)]
enum BridgeFailure {
    Core(CoreError),
    Internal,
}

#[derive(Serialize)]
struct LastErrorPayload<'a> {
    code: i32,
    message: &'a str,
}

fn failure_json(failure: &BridgeFailure) -> String {
    let (code, message) = match failure {
        BridgeFailure::Core(error) => (error.code() as i32, error.to_string()),
        BridgeFailure::Internal => (
            ErrorCode::InternalError as i32,
            INTERNAL_ERROR_MESSAGE.to_owned(),
        ),
    };
    serde_json::to_string(&LastErrorPayload {
        code,
        message: &message,
    })
    .unwrap_or_else(|_| INTERNAL_ERROR_JSON.to_owned())
}

fn replace_last_error(value: String) {
    LAST_ERROR.with(|last_error| {
        if let Ok(mut current) = last_error.try_borrow_mut() {
            *current = value;
        }
    });
}

fn current_last_error() -> String {
    LAST_ERROR.with(|last_error| {
        match last_error.try_borrow() {
            Ok(current) => current.as_str().to_owned(),
            Err(_) => INTERNAL_ERROR_JSON.to_owned(),
        }
    })
}

fn update_last_error<T>(outcome: &Result<T, BridgeFailure>) {
    match outcome {
        Ok(_) => replace_last_error(String::new()),
        Err(failure) => replace_last_error(failure_json(failure)),
    }
}

fn catch_internal<T>(operation: impl FnOnce() -> T) -> Result<T, BridgeFailure> {
    catch_unwind(AssertUnwindSafe(operation)).map_err(|_| BridgeFailure::Internal)
}

#[cfg(target_os = "android")]
type CoreOperation = fn(&[u8]) -> Result<Vec<u8>, CoreError>;

#[cfg(target_os = "android")]
fn empty_byte_array(env: &JNIEnv<'_>) -> jbyteArray {
    match env.byte_array_from_slice(&[]) {
        Ok(array) => {
            // SAFETY: `into_raw` transfers this live local reference to the JVM
            // return value; Rust must not retain or release it afterwards.
            array.into_raw()
        }
        Err(_) => ptr::null_mut(),
    }
}

#[cfg(target_os = "android")]
fn create_output_byte_array(
    env: &JNIEnv<'_>,
    request: &JByteArray<'_>,
    operation: CoreOperation,
) -> Result<jbyteArray, BridgeFailure> {
    let request = env
        .convert_byte_array(request)
        .map_err(|_| BridgeFailure::Internal)?;
    let output = operation(&request).map_err(BridgeFailure::Core)?;
    let output = env
        .byte_array_from_slice(&output)
        .map_err(|_| BridgeFailure::Internal)?;
    // SAFETY: `into_raw` transfers this live local reference to the JVM return
    // value; Rust must not retain or release it afterwards.
    Ok(output.into_raw())
}

#[cfg(target_os = "android")]
fn invoke_byte_operation_inner(
    env: &JNIEnv<'_>,
    request: &JByteArray<'_>,
    operation: CoreOperation,
) -> jbyteArray {
    let outcome = create_output_byte_array(env, request, operation);

    update_last_error(&outcome);
    match outcome {
        Ok(output) => output,
        Err(_) => empty_byte_array(env),
    }
}

#[cfg(target_os = "android")]
fn invoke_byte_operation(
    env: &JNIEnv<'_>,
    request: &JByteArray<'_>,
    operation: CoreOperation,
) -> jbyteArray {
    match catch_internal(|| invoke_byte_operation_inner(env, request, operation)) {
        Ok(output) => output,
        Err(failure) => catch_internal(|| {
            replace_last_error(failure_json(&failure));
            empty_byte_array(env)
        })
        .unwrap_or(ptr::null_mut()),
    }
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub extern "system" fn Java_com_alphaauxiliary_fitgenerator_core_NativeCore_nativeApiVersion(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
) -> jint {
    catch_internal(|| CORE_API_VERSION as jint).unwrap_or(CORE_API_VERSION as jint)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub extern "system" fn Java_com_alphaauxiliary_fitgenerator_core_NativeCore_nativePreview(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
    request_utf8: JByteArray<'_>,
) -> jbyteArray {
    invoke_byte_operation(&env, &request_utf8, preview_json)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub extern "system" fn Java_com_alphaauxiliary_fitgenerator_core_NativeCore_nativeGenerateFit(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
    request_utf8: JByteArray<'_>,
) -> jbyteArray {
    invoke_byte_operation(&env, &request_utf8, generate_fit)
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub extern "system" fn Java_com_alphaauxiliary_fitgenerator_core_NativeCore_nativeLastError(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
) -> jstring {
    catch_internal(|| match env.new_string(current_last_error()) {
        Ok(error) => {
            let error: JString<'_> = error;
            // SAFETY: `into_raw` transfers this live local reference to the JVM
            // return value; Rust must not retain or release it afterwards.
            error.into_raw()
        }
        Err(_) => ptr::null_mut(),
    })
    .unwrap_or(ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use serde_json::Value;

    use super::{
        catch_internal, current_last_error, update_last_error, BridgeFailure,
        INTERNAL_ERROR_JSON,
    };
    use crate::error::CoreError;

    #[test]
    fn core_error_is_stored_as_exact_structured_json() {
        let outcome: Result<(), BridgeFailure> = Err(BridgeFailure::Core(
            CoreError::UnsupportedSchema(7),
        ));
        update_last_error(&outcome);

        let json = current_last_error();
        assert_eq!(json, "{\"code\":101,\"message\":\"不支持的数据版本：7\"}");
        let parsed: Value = serde_json::from_str(&json).expect("last error must be JSON");
        assert_eq!(parsed["code"], 101);
        assert_eq!(parsed["message"], "不支持的数据版本：7");
    }

    #[test]
    fn successful_outcome_clears_the_current_threads_error() {
        let failure: Result<(), BridgeFailure> = Err(BridgeFailure::Core(
            CoreError::InvalidInput("无效请求".to_owned()),
        ));
        update_last_error(&failure);
        assert!(!current_last_error().is_empty());

        let success: Result<(), BridgeFailure> = Ok(());
        update_last_error(&success);
        assert_eq!(current_last_error(), "");
    }

    #[test]
    fn panic_is_mapped_to_internal_error_without_details() {
        let outcome = catch_internal(|| -> () { panic!("private panic detail") });
        assert!(matches!(outcome, Err(BridgeFailure::Internal)));
        update_last_error(&outcome);

        let json = current_last_error();
        assert_eq!(json, INTERNAL_ERROR_JSON);
        let parsed: Value = serde_json::from_str(&json).expect("last error must be JSON");
        assert_eq!(parsed["code"], 900);
        assert_eq!(parsed["message"], "核心发生内部错误");
        assert!(!json.contains("private panic detail"));
    }
}
