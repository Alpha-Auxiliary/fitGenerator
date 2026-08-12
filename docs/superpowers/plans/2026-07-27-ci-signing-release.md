# CI, Signing, and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build, test, attest, and publish the Windows portable EXE and Android APK from GitHub Actions, with mandatory Android signing and an optional SignPath open-source Windows-signing lane.

**Architecture:** Pull-request CI validates the Rust core and both native shells independently. A tag-only release workflow rebuilds all native artifacts from source, assembles checksums and provenance, signs Android from GitHub secrets, optionally submits the Windows artifact to SignPath, and publishes one GitHub Release without embedding map keys or other secrets.

**Tech Stack:** GitHub Actions, Rust stable, .NET 8, Java 17, Android SDK/NDK, cargo-ndk, Gradle 8.11.1, SignPath GitHub Action v2, GitHub artifact attestations, PowerShell 7, Bash.

---

## Preconditions and fixed release contract

- Complete all quality gates in the Rust, Windows, and Android plans.
- Keep the repository public and all shipped runtime components under OSI-approved licenses.
- Do not stage or commit while executing this local plan.
- Release tags use `vMAJOR.MINOR.PATCH`, for example `v1.0.0`.
- Public artifact names are fixed:
  - `fit-generator-windows-x64.exe`
  - `fit-generator-android.apk`
  - `fit-generator-SHA256SUMS.txt`
  - `fit-generator-sbom.spdx.json`
  - `THIRD-PARTY-NOTICES.txt`
- SignPath project slug is `fit-generator`.
- SignPath signing-policy slug is `release-signing`.
- Unsigned Windows fallback is named `fit-generator-windows-x64-UNSIGNED.exe`; it must never be published under the signed filename.

## Required GitHub configuration

Repository variables:

```text
SIGNPATH_ENABLED=false
```

Repository secrets for Android releases:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Additional repository secrets when `SIGNPATH_ENABLED=true`:

```text
SIGNPATH_API_TOKEN
SIGNPATH_ORGANIZATION_ID
```

No map-provider key is a build secret. Users enter optional provider keys at runtime, and the native settings stores protect them on-device.

## Target file map

Create:

```text
.github/
├── CODEOWNERS
└── workflows/
    ├── ci.yml
    └── release.yml
scripts/
├── build-rust-android.sh
├── build-rust-windows.ps1
├── make-release-metadata.ps1
└── verify-release.ps1
CODE_SIGNING.md
PRIVACY.md
SECURITY.md
THIRD-PARTY-NOTICES.txt
deny.toml
```

Modify:

- `.gitignore`
- `README.md`
- `android/app/build.gradle`
- `native-windows/FitGenerator.Native/FitGenerator.Native.csproj`

Delete only after both replacement workflows pass:

- `.github/workflows/build.yml`

### Task 1: Add deterministic native build scripts

**Files:**

- Create: `scripts/build-rust-windows.ps1`
- Create: `scripts/build-rust-android.sh`
- Modify: `.gitignore`

- [ ] **Step 1: Write failing script-contract checks**

Run:

```bash
test -x scripts/build-rust-android.sh
pwsh -NoProfile -File scripts/build-rust-windows.ps1 -WhatIf
```

Expected: both commands fail because the scripts do not exist.

- [ ] **Step 2: Implement the Windows Rust build script**

`scripts/build-rust-windows.ps1` accepts only `-Configuration Debug|Release` and `-WhatIf`. It derives every path from `$PSScriptRoot`, never from the caller's working directory.

The release path is:

```text
core-rust/target/x86_64-pc-windows-msvc/release/fit_generator_core.dll
```

The destination is:

```text
native-windows/FitGenerator.Native/runtimes/win-x64/native/fit_generator_core.dll
```

Its non-`WhatIf` commands are:

```powershell
rustup target add x86_64-pc-windows-msvc
cargo build --locked --manifest-path core-rust/Cargo.toml --release --target x86_64-pc-windows-msvc
Copy-Item core-rust/target/x86_64-pc-windows-msvc/release/fit_generator_core.dll native-windows/FitGenerator.Native/runtimes/win-x64/native/fit_generator_core.dll -Force
```

The script exits nonzero if the source DLL is missing or empty after `cargo build`.

- [ ] **Step 3: Implement the Android Rust build script**

`scripts/build-rust-android.sh` uses `set -euo pipefail`, derives `repo_root` from its own directory, and runs:

```bash
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 3.5.4 --locked
cargo ndk \
  --manifest-path core-rust/Cargo.toml \
  --target arm64-v8a \
  --target x86_64 \
  --platform 23 \
  --output-dir android/app/src/main/jniLibs \
  build --release --locked --features android-jni
```

After the build it verifies these exact nonempty files:

```text
android/app/src/main/jniLibs/arm64-v8a/libfit_generator_core.so
android/app/src/main/jniLibs/x86_64/libfit_generator_core.so
```

- [ ] **Step 4: Ignore copied native build products**

Append:

```gitignore
native-windows/FitGenerator.Native/runtimes/win-x64/native/fit_generator_core.dll
android/app/src/main/jniLibs/*/libfit_generator_core.so
release-output/
```

Keep `Cargo.lock` and Gradle wrapper files tracked.

- [ ] **Step 5: Verify**

On Windows:

```powershell
pwsh -NoProfile -File scripts/build-rust-windows.ps1 -Configuration Release
Test-Path native-windows/FitGenerator.Native/runtimes/win-x64/native/fit_generator_core.dll
```

On Linux with Android NDK configured:

```bash
bash scripts/build-rust-android.sh
test -s android/app/src/main/jniLibs/arm64-v8a/libfit_generator_core.so
test -s android/app/src/main/jniLibs/x86_64/libfit_generator_core.so
```

Expected: every command exits `0`.

### Task 2: Make signing and packaging explicit in both native projects

**Files:**

- Modify: `android/app/build.gradle`
- Modify: `native-windows/FitGenerator.Native/FitGenerator.Native.csproj`
- Create: `CODE_SIGNING.md`
- Test: `scripts/verify-release.ps1`

- [ ] **Step 1: Add an Android signing-contract test**

Before editing Gradle, run:

```bash
rg -n 'ANDROID_KEYSTORE_PATH|ANDROID_KEY_ALIAS|signingConfig signingConfigs.release' android/app/build.gradle
```

Expected: no matches.

- [ ] **Step 2: Configure Android release signing from environment variables**

At Gradle configuration time, read:

```text
ANDROID_KEYSTORE_PATH
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

If all four values are present, create `signingConfigs.release` and assign it to `buildTypes.release`. If any value is missing, leave the release variant unsigned and print one lifecycle warning. Never fall back to the debug keystore for a release build.

Keep shrinking disabled in the first release:

```groovy
release {
    minifyEnabled false
}
```

- [ ] **Step 3: Make the Windows native DLL part of publish content**

In the Windows project, include the copied DLL using:

```xml
<ItemGroup>
  <Content Include="runtimes\win-x64\native\fit_generator_core.dll">
    <Link>fit_generator_core.dll</Link>
    <CopyToOutputDirectory>PreserveNewest</CopyToOutputDirectory>
    <CopyToPublishDirectory>PreserveNewest</CopyToPublishDirectory>
    <ExcludeFromSingleFile>false</ExcludeFromSingleFile>
  </Content>
</ItemGroup>
```

The existing portable publish profile must set:

```xml
<PublishSingleFile>true</PublishSingleFile>
<SelfContained>true</SelfContained>
<RuntimeIdentifier>win-x64</RuntimeIdentifier>
<IncludeNativeLibrariesForSelfExtract>true</IncludeNativeLibrariesForSelfExtract>
<IncludeAllContentForSelfExtract>true</IncludeAllContentForSelfExtract>
<DebugType>none</DebugType>
<DebugSymbols>false</DebugSymbols>
```

- [ ] **Step 4: Document the fixed signing policy**

`CODE_SIGNING.md` states:

- Android release artifacts are signed by CI with a project-owned upload key.
- The keystore is never committed and is restored only into the runner temporary directory.
- Windows signing is performed only through the configured SignPath project.
- A contributor build is expected to be unsigned.
- If SignPath is unavailable, the release clearly publishes `UNSIGNED` in the Windows filename and release notes.
- No proprietary Garmin SDK binary or package is present in a release.

- [ ] **Step 5: Verify local packaging modes**

```bash
./android/gradlew -p android assembleRelease
```

Expected without signing variables: an unsigned release APK is produced and Gradle prints the explicit warning.

```powershell
pwsh -NoProfile -File scripts/build-rust-windows.ps1 -Configuration Release
dotnet publish native-windows/FitGenerator.Native/FitGenerator.Native.csproj -c Release -r win-x64
```

Expected: one app EXE is emitted; no loose runtime file is required beside it.

### Task 3: Replace the legacy workflow with pull-request CI

**Files:**

- Create: `.github/workflows/ci.yml`
- Delete after verification: `.github/workflows/build.yml`
- Create: `deny.toml`

- [ ] **Step 1: Add Rust license and dependency policy**

Create `deny.toml` with:

```toml
[advisories]
version = 2
yanked = "deny"

[licenses]
version = 2
confidence-threshold = 0.93
allow = [
  "Apache-2.0",
  "BSD-2-Clause",
  "BSD-3-Clause",
  "ISC",
  "MIT",
  "Unicode-3.0",
  "Zlib",
]

[bans]
multiple-versions = "warn"
wildcards = "deny"
deny = [
  { name = "Garmin.FIT.Sdk", reason = "Release components must be redistributable open source." },
]

[sources]
unknown-registry = "deny"
unknown-git = "deny"
allow-registry = ["https://github.com/rust-lang/crates.io-index"]
```

- [ ] **Step 2: Create the CI triggers and permissions**

`.github/workflows/ci.yml` triggers on:

```yaml
pull_request:
push:
  branches: [main]
workflow_dispatch:
```

Top-level permissions are:

```yaml
contents: read
```

Use a concurrency group based on workflow and ref and enable `cancel-in-progress: true`.

- [ ] **Step 3: Add the Rust job**

On `ubuntu-latest`:

```bash
rustup component add rustfmt clippy
cargo install cargo-deny --version 0.18.4 --locked
cargo fmt --manifest-path core-rust/Cargo.toml --check
cargo clippy --manifest-path core-rust/Cargo.toml --all-targets --all-features -- -D warnings
cargo test --manifest-path core-rust/Cargo.toml --all-features --locked
cargo deny --manifest-path core-rust/Cargo.toml check
```

Use `dtolnay/rust-toolchain@stable` and `Swatinem/rust-cache@v2` with `workspaces: core-rust`.

- [ ] **Step 4: Add the Windows job**

On `windows-latest`:

```powershell
pwsh -NoProfile -File scripts/build-rust-windows.ps1 -Configuration Release
dotnet test native-windows/FitGenerator.Native.Tests/FitGenerator.Native.Tests.csproj -c Release
dotnet publish native-windows/FitGenerator.Native/FitGenerator.Native.csproj -c Release -r win-x64
```

Then run the app's noninteractive core smoke mode:

```powershell
$exe = Get-ChildItem native-windows/FitGenerator.Native/bin/Release/net8.0-windows/win-x64/publish/*.exe | Select-Object -First 1
& $exe.FullName --self-test
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

The Windows implementation plan must provide `--self-test`; it loads the embedded Rust DLL, previews the fixed fixture, encodes FIT, validates `.FIT`, and exits without opening a form.

- [ ] **Step 5: Add the Android job**

On `ubuntu-latest`, install Java 17 and Android packages:

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018"
bash scripts/build-rust-android.sh
./android/gradlew -p android --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Use `actions/setup-java@v4`, `android-actions/setup-android@v3`, and `gradle/actions/setup-gradle@v4`. Set `ANDROID_NDK_HOME` to `$ANDROID_SDK_ROOT/ndk/27.2.12479018`.

- [ ] **Step 6: Remove the old workflow**

Delete `.github/workflows/build.yml` only after a local YAML syntax check and all three jobs have succeeded on a branch run. This removes Node, WebView URL, embedded map-key, automatic branch-release, and Garmin SDK build paths.

- [ ] **Step 7: Verify**

```bash
rg -n 'npm install|WEB_APP_URL|BAIDU_MAP_AK|AMAP_MAP_KEY|GOOGLE_MAPS_API_KEY|Garmin.FIT.Sdk' .github android native-windows
```

Expected: no shipped workflow, Android, or Windows project references remain. Mentions in migration documentation are allowed only under `docs/`.

### Task 4: Add release verification and metadata generation

**Files:**

- Create: `scripts/make-release-metadata.ps1`
- Create: `scripts/verify-release.ps1`
- Create: `THIRD-PARTY-NOTICES.txt`
- Create: `PRIVACY.md`
- Create: `SECURITY.md`

- [ ] **Step 1: Write failing verification-script tests**

With an empty temporary directory:

```powershell
$releaseDir = Join-Path $env:RUNNER_TEMP "empty-release"
New-Item -ItemType Directory -Force $releaseDir | Out-Null
pwsh -NoProfile -File scripts/verify-release.ps1 -ReleaseDirectory $releaseDir
```

Expected: nonzero exit listing each missing required artifact.

- [ ] **Step 2: Implement release metadata generation**

`make-release-metadata.ps1` accepts `-ReleaseDirectory` and:

1. hashes every `.exe` and `.apk` with SHA-256;
2. sorts records by filename using ordinal comparison;
3. writes lowercase hex plus two spaces plus filename to `fit-generator-SHA256SUMS.txt`;
4. copies the checked-in `THIRD-PARTY-NOTICES.txt`;
5. fails if either platform artifact is absent.

It never follows symlinks outside the supplied directory.

- [ ] **Step 3: Implement release verification**

`verify-release.ps1` accepts:

```powershell
param(
  [Parameter(Mandatory)][string]$ReleaseDirectory,
  [switch]$RequireWindowsSignature,
  [switch]$RequireAndroidSignature
)
```

It verifies:

- exactly one Windows EXE, either signed or explicitly `UNSIGNED`;
- exactly one Android APK;
- both files are nonempty;
- APK includes `lib/arm64-v8a/libfit_generator_core.so` and `lib/x86_64/libfit_generator_core.so`;
- EXE does not have adjacent DLL dependencies in the release directory;
- every checksum line matches a file;
- `THIRD-PARTY-NOTICES.txt` and `fit-generator-sbom.spdx.json` exist;
- `Get-AuthenticodeSignature` status is `Valid` when `-RequireWindowsSignature`;
- `apksigner verify --verbose --print-certs` exits `0` when `-RequireAndroidSignature`.

- [ ] **Step 4: Write public project policies**

`PRIVACY.md` states that activity generation is local, map/search requests go directly to the selected third-party provider, optional API keys remain on-device, and no project-owned analytics or server is used.

`SECURITY.md` provides GitHub private vulnerability reporting as the primary channel, supported release policy, and a request not to post secrets or real activity routes in public issues.

`THIRD-PARTY-NOTICES.txt` lists direct runtime dependencies and licenses, including Rust crates, .NET, Mapsui, Kotlin/AndroidX, and MapLibre. It explicitly excludes `Garmin.FIT.Sdk`.

- [ ] **Step 5: Verify**

```powershell
pwsh -NoProfile -File scripts/make-release-metadata.ps1 -ReleaseDirectory release-output
pwsh -NoProfile -File scripts/verify-release.ps1 -ReleaseDirectory release-output
```

Expected: both exit `0` for locally built unsigned artifacts; adding `-RequireWindowsSignature` fails for an unsigned EXE.

### Task 5: Build and publish tag releases

**Files:**

- Create: `.github/workflows/release.yml`
- Create: `.github/CODEOWNERS`
- Modify: `README.md`

- [ ] **Step 1: Define safe release triggers and permissions**

Trigger only on:

```yaml
push:
  tags: ["v[0-9]+.[0-9]+.[0-9]+"]
workflow_dispatch:
  inputs:
    tag:
      description: Existing vMAJOR.MINOR.PATCH tag
      required: true
```

Use:

```yaml
permissions:
  contents: write
  id-token: write
  attestations: write
```

The first job verifies that `github.ref_name` or the manual input matches `^v[0-9]+\.[0-9]+\.[0-9]+$` and that the tag resolves to the checked-out commit.

- [ ] **Step 2: Build and sign Android**

On `ubuntu-latest`:

```bash
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/fit-generator-release.jks"
```

Expose the four Gradle signing variables only to the `assembleRelease` step, run `bash scripts/build-rust-android.sh`, then:

```bash
./android/gradlew -p android --no-daemon testReleaseUnitTest lintRelease assembleRelease
apksigner verify --verbose --print-certs android/app/build/outputs/apk/release/app-release.apk
```

Copy the APK to `release-output/fit-generator-android.apk`, upload it with `actions/upload-artifact@v4`, and delete the temporary keystore in an `if: always()` step.

- [ ] **Step 3: Build unsigned Windows**

On `windows-latest`, run the same Rust tests and Windows tests as CI, then:

```powershell
pwsh -NoProfile -File scripts/build-rust-windows.ps1 -Configuration Release
dotnet publish native-windows/FitGenerator.Native/FitGenerator.Native.csproj -c Release -r win-x64
```

Copy the single EXE to `release-output/fit-generator-windows-x64-UNSIGNED.exe` and upload an artifact named `windows-unsigned` with `actions/upload-artifact@v4`. Capture that step's `artifact-id` output.

- [ ] **Step 4: Add the optional SignPath job**

Condition:

```yaml
if: vars.SIGNPATH_ENABLED == 'true'
```

Submit the `windows-unsigned` GitHub artifact:

```yaml
- uses: signpath/github-action-submit-signing-request@v2
  with:
    api-token: ${{ secrets.SIGNPATH_API_TOKEN }}
    organization-id: ${{ secrets.SIGNPATH_ORGANIZATION_ID }}
    project-slug: fit-generator
    signing-policy-slug: release-signing
    github-artifact-id: ${{ needs.windows.outputs.artifact_id }}
    wait-for-completion: true
    output-artifact-directory: release-output
```

Rename the returned signed EXE to `fit-generator-windows-x64.exe`, verify `Get-AuthenticodeSignature` is `Valid`, and upload it as `windows-release`.

When `SIGNPATH_ENABLED` is not `true`, pass the explicit unsigned filename to the final release job.

- [ ] **Step 5: Generate SBOM, checksums, and attestations**

The final job downloads the selected Windows artifact and Android artifact into one `release-output` directory.

Generate an SPDX JSON SBOM from the release directory:

```yaml
- uses: anchore/sbom-action@v0
  with:
    path: release-output
    format: spdx-json
    output-file: release-output/fit-generator-sbom.spdx.json
```

Run:

```powershell
pwsh -NoProfile -File scripts/make-release-metadata.ps1 -ReleaseDirectory release-output
pwsh -NoProfile -File scripts/verify-release.ps1 -ReleaseDirectory release-output -RequireAndroidSignature
```

Add `-RequireWindowsSignature` only in the SignPath-enabled job path.

Attest the EXE, APK, checksum file, and SBOM with `actions/attest-build-provenance@v3` using a multiline `subject-path`.

- [ ] **Step 6: Publish exactly one GitHub Release**

Use the GitHub CLI already present on the runner:

```bash
gh release create "$RELEASE_TAG" release-output/* \
  --verify-tag \
  --generate-notes \
  --title "FIT Generator $RELEASE_TAG"
```

If Windows is unsigned, prepend a release-note paragraph stating that Windows SmartScreen may warn because the EXE is unsigned; include its SHA-256 and link to `CODE_SIGNING.md`. Never silently replace an unsigned asset later—publish a new patch release after signing is available.

- [ ] **Step 7: Protect release workflow changes**

`.github/CODEOWNERS` contains:

```text
/.github/workflows/ @Alpha-Auxiliary
/CODE_SIGNING.md @Alpha-Auxiliary
/scripts/verify-release.ps1 @Alpha-Auxiliary
```

The handle matches the owner of the configured `origin` repository, `Alpha-Auxiliary/fitGenerator`.

- [ ] **Step 8: Document local and release builds**

In `README.md`, link the architecture specification, four implementation plans, `PRIVACY.md`, `SECURITY.md`, and `CODE_SIGNING.md`. Include exact local commands for Rust tests, Windows publish, Android debug build, and release artifact verification.

- [ ] **Step 9: Final verification**

Run:

```bash
git diff --check
rg -n 'ANDROID_KEYSTORE_BASE64|SIGNPATH_API_TOKEN|SIGNPATH_ORGANIZATION_ID' . --glob '!docs/**'
git status --short
```

Expected:

- `git diff --check` exits `0`;
- secret names appear only as environment/secret references, never as values;
- all changes remain local and unstaged;
- CI and release workflows contain no Node web-shell build, WebView URL, proprietary Garmin package, or embedded map key.
