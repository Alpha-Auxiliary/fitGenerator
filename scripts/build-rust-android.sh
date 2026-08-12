#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
output_dir="$repo_root/android/app/src/main/jniLibs"
core_dir="$repo_root/core-rust"
cargo_ndk_expected_version="3.5.4"

readonly script_dir repo_root output_dir core_dir cargo_ndk_expected_version

fail() {
    printf 'Android Rust build prerequisite error: %s\n' "$1" >&2
    exit 1
}

require_command() {
    local command_name="$1"
    command -v "$command_name" >/dev/null 2>&1 ||
        fail "missing command '$command_name'; install it explicitly before running this script"
}

require_command uname
require_command rustup
require_command cargo
require_command cargo-ndk
require_command mktemp
require_command mkdir
require_command cp
require_command mv
require_command rm

host_name="$(uname -s)"
readonly host_name
case "$host_name" in
    Linux* | Darwin* | MINGW* | MSYS* | CYGWIN*) ;;
    *) fail "unsupported host '$host_name'; cargo-ndk supports Linux, macOS, and Windows" ;;
esac

if ! cargo_ndk_version="$(cargo-ndk --version 2>/dev/null)"; then
    fail "unable to query cargo-ndk version"
fi
readonly cargo_ndk_version
if [[ "$cargo_ndk_version" != "cargo-ndk $cargo_ndk_expected_version" ]]; then
    fail "cargo-ndk $cargo_ndk_expected_version is required; found '$cargo_ndk_version'"
fi

required_targets=(
    "aarch64-linux-android"
    "x86_64-linux-android"
)
readonly -a required_targets

if ! installed_targets="$(rustup target list --installed 2>/dev/null)"; then
    fail "unable to inspect installed Rust targets"
fi
target_list=$'\n'"$installed_targets"$'\n'
missing_targets=""
for target in "${required_targets[@]}"; do
    if [[ "$target_list" != *$'\n'"$target"$'\n'* ]]; then
        missing_targets="$missing_targets $target"
    fi
done
if [[ -n "$missing_targets" ]]; then
    fail "missing Rust Android target(s):$missing_targets; add them explicitly with rustup"
fi

ndk_root=""
ndk_source=""
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    ndk_root="$ANDROID_NDK_HOME"
    ndk_source="ANDROID_NDK_HOME"
elif [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then
    ndk_root="$ANDROID_NDK_ROOT"
    ndk_source="ANDROID_NDK_ROOT"
elif [[ -n "${ANDROID_NDK_PATH:-}" ]]; then
    ndk_root="$ANDROID_NDK_PATH"
    ndk_source="ANDROID_NDK_PATH"
elif [[ -n "${NDK_HOME:-}" ]]; then
    ndk_root="$NDK_HOME"
    ndk_source="NDK_HOME"
fi
if [[ -z "$ndk_root" ]]; then
    fail "set ANDROID_NDK_HOME (or another cargo-ndk NDK variable) to an installed NDK; this script does not download one"
fi
if [[ ! -f "$ndk_root/source.properties" ]]; then
    fail "$ndk_source does not point to an Android NDK root containing source.properties"
fi

case "$host_name" in
    Linux*) ndk_host_pattern="linux-*" ;;
    Darwin*) ndk_host_pattern="darwin-*" ;;
    MINGW* | MSYS* | CYGWIN*) ndk_host_pattern="windows-*" ;;
esac
ndk_host_toolchain_found=false
for toolchain_dir in "$ndk_root/toolchains/llvm/prebuilt"/$ndk_host_pattern; do
    if [[ -d "$toolchain_dir" ]]; then
        ndk_host_toolchain_found=true
        break
    fi
done
if [[ "$ndk_host_toolchain_found" != true ]]; then
    fail "$ndk_source does not provide an NDK toolchain for host '$host_name'"
fi
export ANDROID_NDK_HOME="$ndk_root"

[[ -f "$core_dir/Cargo.toml" ]] || fail "missing core-rust/Cargo.toml"
[[ -f "$core_dir/Cargo.lock" ]] ||
    fail "missing core-rust/Cargo.lock; generate and review it before a locked build"
for abi in arm64-v8a x86_64; do
    [[ -d "$output_dir/$abi" ]] || fail "missing jniLibs ABI directory '$abi'"
done

expected_abis=(
    "arm64-v8a"
    "x86_64"
)
original_present=(
    "false"
    "false"
)
readonly -a expected_abis

transaction_root=""
promotion_started=false
promotion_committed=false

cleanup() {
    local exit_code=$?
    local cleanup_failed=false
    local rollback_failed=false
    local index abi destination backup_path

    trap - EXIT INT TERM HUP
    set +e

    if [[ "$promotion_started" == true && "$promotion_committed" != true ]]; then
        for index in "${!expected_abis[@]}"; do
            abi="${expected_abis[$index]}"
            destination="$output_dir/$abi/libfit_generator_core.so"
            backup_path="$transaction_root/backups/$abi/libfit_generator_core.so"
            if [[ "${original_present[$index]}" == true ]]; then
                if [[ -f "$backup_path" && ! -L "$backup_path" ]]; then
                    if ! mv -f "$backup_path" "$destination"; then
                        cleanup_failed=true
                        rollback_failed=true
                    fi
                else
                    cleanup_failed=true
                    rollback_failed=true
                fi
            else
                if ! rm -f "$destination"; then
                    cleanup_failed=true
                    rollback_failed=true
                fi
            fi
        done
    fi

    if [[ -n "$transaction_root" && -e "$transaction_root" ]]; then
        if [[ "$rollback_failed" != true ]]; then
            case "$transaction_root" in
                "$output_dir"/.fit-generator-stage.*)
                    rm -rf "$transaction_root" || cleanup_failed=true
                    ;;
                *)
                    cleanup_failed=true
                    ;;
            esac
        fi
    fi

    if [[ "$rollback_failed" == true ]]; then
        printf '%s\n' \
            'Android Rust build rollback error: recovery data remains under the jniLibs staging directory' \
            >&2
    elif [[ "$cleanup_failed" == true ]]; then
        printf '%s\n' 'Android Rust build cleanup error: a script-created staging directory remains' >&2
    fi
    if [[ "$cleanup_failed" == true ]]; then
        if [[ "$exit_code" -eq 0 ]]; then
            exit_code=1
        fi
    fi
    exit "$exit_code"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

if ! transaction_root="$(mktemp -d "$output_dir/.fit-generator-stage.XXXXXX")"; then
    fail "unable to create the Android native staging directory"
fi
case "$transaction_root" in
    "$output_dir"/.fit-generator-stage.*) ;;
    *) fail "the Android native staging directory is outside jniLibs" ;;
esac
staging_output="$transaction_root/output"
mkdir "$staging_output" || fail "unable to prepare the Android native staging directory"

cd "$repo_root"

cargo ndk \
    --manifest-path core-rust/Cargo.toml \
    --target arm64-v8a \
    --target x86_64 \
    --platform 23 \
    --output-dir "$staging_output" \
    build --release --locked --features android-jni

for abi in "${expected_abis[@]}"; do
    staged_library="$staging_output/$abi/libfit_generator_core.so"
    if [[ ! -f "$staged_library" || -L "$staged_library" || ! -s "$staged_library" ]]; then
        printf 'Missing or empty Android native library: android/app/src/main/jniLibs/%s\n' \
            "$abi/libfit_generator_core.so" >&2
        exit 1
    fi
done

for index in "${!expected_abis[@]}"; do
    abi="${expected_abis[$index]}"
    destination="$output_dir/$abi/libfit_generator_core.so"
    backup_directory="$transaction_root/backups/$abi"
    backup_path="$backup_directory/libfit_generator_core.so"

    if [[ -L "$destination" ]]; then
        fail "refusing to replace a symbolic link at jniLibs/$abi/libfit_generator_core.so"
    fi
    if [[ -e "$destination" ]]; then
        [[ -f "$destination" ]] ||
            fail "jniLibs/$abi/libfit_generator_core.so is not a regular file"
        mkdir -p "$backup_directory" || fail "unable to prepare an Android native backup"
        cp -p "$destination" "$backup_path" || fail "unable to back up an existing Android native library"
        original_present[$index]="true"
    fi
done

promotion_started=true
for abi in "${expected_abis[@]}"; do
    staged_library="$staging_output/$abi/libfit_generator_core.so"
    destination="$output_dir/$abi/libfit_generator_core.so"
    mv -f "$staged_library" "$destination" || fail "unable to promote both Android native libraries"
    [[ -f "$destination" && ! -L "$destination" && -s "$destination" ]] ||
        fail "a promoted Android native library is missing or empty"
done
promotion_committed=true

printf '%s\n' \
    'Android Rust libraries ready: arm64-v8a/libfit_generator_core.so, x86_64/libfit_generator_core.so'
