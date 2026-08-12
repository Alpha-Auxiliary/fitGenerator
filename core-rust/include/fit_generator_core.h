/* Generated from the Rust C ABI. Keep synchronized with src/ffi.rs. */

#ifndef FIT_GENERATOR_CORE_H
#define FIT_GENERATOR_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct FgResult FgResult;

FgResult *fg_preview(const uint8_t *request, size_t length);
FgResult *fg_generate_fit(const uint8_t *request, size_t length);
const uint8_t *fg_result_data(const FgResult *result);
size_t fg_result_length(const FgResult *result);
int32_t fg_result_code(const FgResult *result);
const char *fg_result_error(const FgResult *result);
void fg_result_free(FgResult *result);
uint32_t fg_core_api_version(void);

#ifdef __cplusplus
}
#endif

#endif /* FIT_GENERATOR_CORE_H */
