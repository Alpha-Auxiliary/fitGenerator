# Native Windows and Android Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a zero-configuration Windows portable EXE and a standalone Android APK that share one local Rust activity/FIT engine.

**Architecture:** A Rust `cdylib` owns validation, deterministic simulation, canonical activity models, and minimal FIT Activity encoding. A C# WinForms/Mapsui shell calls it through P/Invoke, while a Kotlin/Compose/MapLibre shell calls it through JNI. GitHub Actions builds, verifies, packages, attests, and optionally signs both deliverables.

**Tech Stack:** Rust 2024, serde, thiserror, proptest, fitparser; .NET 8, WinForms, Mapsui, xUnit; Kotlin, Jetpack Compose, MapLibre Native, JUnit; GitHub Actions, cargo-ndk, SPDX/Syft, SignPath.

---

## Local-only version-control policy

The user explicitly requested local project changes without staging or commits.

- Do not run `git add`.
- Do not run `git commit`.
- After each task, run the listed tests and `git diff --check`.
- Use `git status --short` to show the local checkpoint.

## Delivery sequence

The specification spans four independently testable subsystems. Execute the plans in this order:

1. [Rust core plan](2026-07-27-rust-activity-fit-core.md)
2. [Windows native plan](2026-07-27-windows-native-portable.md)
3. [Android native plan](2026-07-27-android-native-app.md)
4. [CI and release plan](2026-07-27-ci-signing-release.md)

Windows and Android work may proceed in parallel only after the Rust FFI contract and golden fixtures are stable.

## Phase gates

### Gate 1: Rust core

- [ ] `cargo fmt --check` passes.
- [ ] `cargo clippy --all-targets --all-features -- -D warnings` passes.
- [ ] `cargo test --all-features` passes.
- [ ] Generated FIT files pass CRC and `fitparser` decoding.
- [ ] FFI fuzz/error tests show no unwind across the ABI.
- [ ] No Garmin SDK package appears in the runtime dependency graph.

### Gate 2: Windows

- [ ] xUnit bridge, settings, coordinate, export, and view-model tests pass.
- [ ] The app runs from a single EXE on a clean Windows 10/11 x64 VM.
- [ ] No browser, server, console, administrator prompt, or installed .NET runtime is required.
- [ ] Offline preview and export work with a previously saved route.

### Gate 3: Android

- [ ] JVM and instrumented tests pass.
- [ ] The APK contains `arm64-v8a` and `x86_64` Rust libraries.
- [ ] The app runs without a project-owned server.
- [ ] Storage Access Framework save/share works without broad storage permission.
- [ ] Offline preview and export work with a previously saved route.

### Gate 4: Release

- [ ] CI rebuilds every deliverable from source.
- [ ] Release contains EXE, APK, SHA-256 checksums, SBOMs, license report, and release notes.
- [ ] Android APK is release-signed.
- [ ] Windows EXE follows the optional SignPath path when credentials and approval are available.
- [ ] Unsigned fallback artifacts are clearly labeled.

## Final acceptance command set

Run from the repository root:

```bash
cargo fmt --manifest-path core-rust/Cargo.toml --check
cargo clippy --manifest-path core-rust/Cargo.toml --all-targets --all-features -- -D warnings
cargo test --manifest-path core-rust/Cargo.toml --all-features
dotnet test native-windows/FitGenerator.Native.Tests/FitGenerator.Native.Tests.csproj -c Release
./android/gradlew -p android --no-daemon testDebugUnitTest
```

On the platform-specific CI runners also run:

```powershell
dotnet publish native-windows/FitGenerator.Native/FitGenerator.Native.csproj -c Release -r win-x64
```

```bash
./android/gradlew -p android --no-daemon assembleRelease
```

Expected result: every command exits `0`; the packaging jobs then run the clean-machine smoke checks described in their phase plans.
