# Android Native App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the WebView shell with a standalone Kotlin/Compose/MapLibre Android app that previews and exports FIT locally through the shared Rust core.

**Architecture:** A Kotlin `NativeCoreService` wraps four JNI methods and exposes typed domain results to a lifecycle-aware view model. Compose renders the approved map-first mobile interface; MapLibre handles map projection and route layers, while DataStore/Keystore and Storage Access Framework provide local persistence and export.

**Tech Stack:** Android API 23+, Kotlin 2.2, Jetpack Compose BOM 2026.06.00, MapLibre Native Android 13.2.0, Kotlin serialization, coroutines, DataStore, Android Keystore, JUnit, MockK, Turbine.

---

## Preconditions

- Complete the Rust core including Android JNI exports.
- Produce `arm64-v8a` and `x86_64` `libfit_generator_core.so`.
- Do not stage or commit; checkpoint with `git diff --check` and `git status --short`.

## Target file map

Create:

```text
android/
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── gradlew
├── gradlew.bat
└── app/src/
    ├── main/
    │   ├── assets/osm-raster-style.json
    │   ├── java/com/alphaauxiliary/fitgenerator/
    │   │   ├── MainActivity.kt
    │   │   ├── core/
    │   │   │   ├── NativeCore.kt
    │   │   │   ├── NativeCoreService.kt
    │   │   │   └── CoreModels.kt
    │   │   ├── map/
    │   │   │   ├── CoordinateTransforms.kt
    │   │   │   ├── LocationSearchService.kt
    │   │   │   ├── MapProvider.kt
    │   │   │   └── RouteMapState.kt
    │   │   ├── data/
    │   │   │   ├── AppSettings.kt
    │   │   │   ├── SettingsRepository.kt
    │   │   │   ├── SecretStore.kt
    │   │   │   └── RouteRepository.kt
    │   │   ├── export/
    │   │   │   └── ExportCoordinator.kt
    │   │   └── ui/
    │   │       ├── MainViewModel.kt
    │   │       ├── MainScreen.kt
    │   │       ├── RouteMap.kt
    │   │       ├── RouteTimingStrip.kt
    │   │       ├── SimulationSheet.kt
    │   │       ├── SettingsScreen.kt
    │   │       └── theme/
    │   │           ├── Color.kt
    │   │           ├── Theme.kt
    │   │           └── Type.kt
    │   └── jniLibs/{arm64-v8a,x86_64}/libfit_generator_core.so
    ├── test/java/com/alphaauxiliary/fitgenerator/
    │   ├── AppLaunchContractTest.kt
    │   ├── core/NativeCoreServiceTest.kt
    │   ├── data/{RouteRepositoryTest,SettingsRepositoryTest}.kt
    │   ├── export/ExportCoordinatorTest.kt
    │   ├── map/{CoordinateTransformsTest,LocationSearchServiceTest}.kt
    │   └── ui/MainViewModelTest.kt
    └── androidTest/java/com/alphaauxiliary/fitgenerator/
        ├── core/NativeCoreInstrumentedTest.kt
        ├── data/SecretStoreInstrumentedTest.kt
        └── ui/MainScreenTest.kt
```

Delete after the native launch test passes:

- `android/app/src/main/java/com/alphaauxiliary/fitgenerator/MainActivity.java`

### Task 1: Convert the Android module to Kotlin and Compose

**Files:**

- Modify: `android/build.gradle`
- Modify: `android/settings.gradle`
- Modify: `android/app/build.gradle`
- Create: `android/gradlew`
- Create: `android/gradlew.bat`
- Create: `android/gradle/wrapper/gradle-wrapper.jar`
- Create: `android/gradle/wrapper/gradle-wrapper.properties`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/MainActivity.kt`
- Test: `android/app/src/test/java/com/alphaauxiliary/fitgenerator/AppLaunchContractTest.kt`

- [ ] **Step 1: Add a failing launch-contract test**

```kotlin
class AppLaunchContractTest {
    @Test
    fun `the app has no configured web app url`() {
        val fields = BuildConfig::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("WEB_APP_URL"))
    }
}
```

- [ ] **Step 2: Run and verify failure**

```bash
gradle --no-daemon -p android testDebugUnitTest
```

Expected: the old module still defines `WEB_APP_URL` or lacks Kotlin test support.

- [ ] **Step 3: Configure plugins and dependencies**

Root plugins:

```groovy
plugins {
    id "com.android.application" version "8.10.1" apply false
    id "org.jetbrains.kotlin.android" version "2.2.0" apply false
    id "org.jetbrains.kotlin.plugin.compose" version "2.2.0" apply false
    id "org.jetbrains.kotlin.plugin.serialization" version "2.2.0" apply false
}
```

App configuration:

```groovy
plugins {
    id "com.android.application"
    id "org.jetbrains.kotlin.android"
    id "org.jetbrains.kotlin.plugin.compose"
    id "org.jetbrains.kotlin.plugin.serialization"
}

android {
    namespace "com.alphaauxiliary.fitgenerator"
    compileSdk 35

    defaultConfig {
        applicationId "com.alphaauxiliary.fitgenerator"
        minSdk 23
        targetSdk 35
        versionCode 1
        versionName "1.0.0"
        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters "arm64-v8a", "x86_64" }
    }

    buildFeatures {
        buildConfig true
        compose true
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
```

Use this exact dependency block:

```groovy
dependencies {
    implementation platform("androidx.compose:compose-bom:2026.06.00")
    androidTestImplementation platform("androidx.compose:compose-bom:2026.06.00")

    implementation "androidx.activity:activity-compose:1.12.4"
    implementation "androidx.compose.material3:material3"
    implementation "androidx.compose.ui:ui"
    implementation "androidx.compose.ui:ui-tooling-preview"
    implementation "androidx.lifecycle:lifecycle-runtime-compose:2.9.2"
    implementation "androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2"
    implementation "androidx.datastore:datastore-preferences:1.1.7"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2"
    implementation "org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0"
    implementation "org.maplibre.gl:android-sdk:13.2.0"

    testImplementation "junit:junit:4.13.2"
    testImplementation "io.mockk:mockk:1.14.5"
    testImplementation "app.cash.turbine:turbine:1.2.1"
    testImplementation "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2"

    androidTestImplementation "androidx.test.ext:junit:1.3.0"
    androidTestImplementation "androidx.test.espresso:espresso-core:3.7.0"
    androidTestImplementation "androidx.compose.ui:ui-test-junit4"
    debugImplementation "androidx.compose.ui:ui-tooling"
    debugImplementation "androidx.compose.ui:ui-test-manifest"
}
```

- [ ] **Step 4: Generate a Gradle wrapper**

```bash
gradle -p android wrapper --gradle-version 8.11.1
```

Expected: `android/gradlew`, `gradlew.bat`, wrapper JAR, and properties exist.

- [ ] **Step 5: Replace the WebView activity**

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FitGeneratorTheme {
                MainScreen()
            }
        }
    }
}
```

Remove `WEB_APP_URL`, cleartext-traffic opt-in, WebView imports, and the old Java activity.

- [ ] **Step 6: Verify**

```bash
./android/gradlew -p android testDebugUnitTest assembleDebug
```

Expected: launch-contract test passes and a native Compose debug APK builds.

### Task 2: Package and wrap the Rust JNI core

**Files:**

- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/core/NativeCore.kt`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/core/CoreModels.kt`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/core/NativeCoreService.kt`
- Create: `android/app/src/test/java/com/alphaauxiliary/fitgenerator/core/NativeCoreServiceTest.kt`
- Create: `android/app/src/androidTest/java/com/alphaauxiliary/fitgenerator/core/NativeCoreInstrumentedTest.kt`

- [ ] **Step 1: Write failing service tests**

Use a fake JNI gateway and assert:

- API version other than `1` fails with `native_core_version_mismatch`;
- preview serializes camelCase schema version 1;
- the same seed is reused for preview and export;
- empty JNI bytes plus structured last error become a typed exception.

- [ ] **Step 2: Define the JNI gateway**

```kotlin
internal object NativeCore {
    init { System.loadLibrary("fit_generator_core") }

    external fun nativeApiVersion(): Int
    external fun nativePreview(requestUtf8: ByteArray): ByteArray
    external fun nativeGenerateFit(requestUtf8: ByteArray): ByteArray
    external fun nativeLastError(): String
}
```

Wrap this object behind an injectable `NativeCoreGateway` interface so unit tests do not load `.so` files.

- [ ] **Step 3: Define Kotlin serialization models**

Mirror Rust property names and integer units exactly:

```kotlin
@Serializable
data class ActivitySampleDto(
    val timeMs: Long,
    val distanceCm: Long,
    val speedMmPerSec: Int,
    val heartRateBpm: Int,
    val positionLatSemicircles: Int,
    val positionLongSemicircles: Int,
)
```

Configure `Json` with `ignoreUnknownKeys = false`, `isLenient = false`, and `explicitNulls = false`.

- [ ] **Step 4: Implement `NativeCoreService`**

Expose suspend functions on `Dispatchers.Default`:

```kotlin
suspend fun preview(input: ActivityInput, seed: ULong): ActivityModelDto
suspend fun generateFit(input: ActivityInput, seed: ULong, variantIndex: Int): ByteArray
```

The service checks API version once, converts local date-time to UTC, validates JNI output length, and maps structured core errors to `CoreException`.

- [ ] **Step 5: Add JNI instrumented smoke test**

Copy the Rust `.so` files into both ABI directories, then assert on an emulator/device:

- API version `1`;
- preview samples are nonempty;
- FIT bytes contain `.FIT` at offsets 8–11.

- [ ] **Step 6: Verify**

```bash
./android/gradlew -p android testDebugUnitTest connectedDebugAndroidTest
```

Expected: JVM service tests and device JNI smoke tests pass.

### Task 3: Implement coordinate transforms and map providers

**Files:**

- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/map/CoordinateTransforms.kt`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/map/LocationSearchService.kt`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/map/MapProvider.kt`
- Create: `android/app/src/main/assets/osm-raster-style.json`
- Test: `android/app/src/test/java/com/alphaauxiliary/fitgenerator/map/CoordinateTransformsTest.kt`
- Test: `android/app/src/test/java/com/alphaauxiliary/fitgenerator/map/LocationSearchServiceTest.kt`

- [ ] **Step 1: Copy the same coordinate vectors as Windows**

Assert WGS-84↔GCJ-02 round trips within two meters for Beijing and Shanghai, BD-09↔GCJ-02, and no shift outside China.

- [ ] **Step 2: Implement transforms**

Expose:

```kotlin
fun toWgs84(point: GeoCoordinate, source: CoordinateSystem): GeoCoordinate
fun fromWgs84(point: GeoCoordinate, target: CoordinateSystem): GeoCoordinate
```

Use the same constants, iteration tolerance, and maximum iterations as the Windows implementation. Store the shared vectors in equivalent JSON fixture files so CI can compare both suites.

- [ ] **Step 3: Add the default style**

Bundle a MapLibre style JSON with:

- one raster source using `https://tile.openstreetmap.org/{z}/{x}/{y}.png`;
- tile size 256;
- zoom range 0–19;
- visible OpenStreetMap attribution;
- no embedded Key.

- [ ] **Step 4: Define provider settings**

`MapProvider` contains id, display name, style/XYZ URL, Key placement, coordinate system, max zoom, attribution, and geocoder URL. Include OSM as immutable default plus user-created custom providers.

- [ ] **Step 5: Write failing search tests**

Inject a fake `HttpTransport` and monotonic clock. Assert the same contract as Windows:

- default endpoint `https://nominatim.openstreetmap.org/search`;
- `format=jsonv2`, `limit=5`, encoded `q`;
- application-identifying User-Agent and current language;
- one-second minimum request interval;
- cancellation of superseded queries;
- provider-coordinate conversion to WGS-84;
- network/parser failures do not mutate the route.

- [ ] **Step 6: Implement location search**

Use `HttpURLConnection` behind the injectable `HttpTransport`, on `Dispatchers.IO`, with 10-second connect/read timeouts. Return at most five results with label, attribution, and WGS-84 coordinate. Persist no search history. The Compose search field applies a 400 ms debounce before calling the service and always shows `© OpenStreetMap contributors` for default results.

Expose a connection-test operation that requests one provider resource without logging its Key. “恢复默认地图” activates the bundled OSM provider while keeping custom providers saved.

- [ ] **Step 7: Verify**

```bash
./android/gradlew -p android testDebugUnitTest \
  --tests '*CoordinateTransformsTest' \
  --tests '*LocationSearchServiceTest'
```

Expected: coordinate, search contract, cancellation, rate-limit, and failure-isolation tests pass.

### Task 4: Implement secure settings and route persistence

**Files:**

- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/data/AppSettings.kt`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/data/SettingsRepository.kt`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/data/SecretStore.kt`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/data/RouteRepository.kt`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/ui/SettingsScreen.kt`
- Test: `android/app/src/test/java/com/alphaauxiliary/fitgenerator/data/SettingsRepositoryTest.kt`
- Test: `android/app/src/test/java/com/alphaauxiliary/fitgenerator/data/RouteRepositoryTest.kt`
- Test: `android/app/src/androidTest/java/com/alphaauxiliary/fitgenerator/data/SecretStoreInstrumentedTest.kt`

- [ ] **Step 1: Write repository behavior tests**

Assert:

- missing data yields zero-config OSM defaults;
- route and parameters round-trip;
- unknown schema backs up the raw payload and returns defaults;
- plaintext Key never appears in DataStore;
- failed Key decryption removes only the unusable Key, not the route.

- [ ] **Step 2: Implement nonsecret persistence**

Use Preferences DataStore for active provider id, map camera, heart rates, pace, laps, export count, and preview seed. Store the last WGS-84 route as versioned JSON in app-private storage using a temporary file and atomic rename.

- [ ] **Step 3: Implement `SecretStore`**

Create an AES/GCM key in `AndroidKeyStore` with alias `fit-generator-map-keys-v1`. Store IV+ciphertext as Base64 in DataStore. Require no biometric prompt because map Keys are app configuration, not user authentication credentials.

- [ ] **Step 4: Implement advanced map settings**

`SettingsScreen` edits provider name, XYZ URL, Key placement/value, coordinate system, max zoom, attribution, and geocoder URL. Mask the Key by default. Provide “测试连接” and “恢复默认地图”; restoring the default activates bundled OSM without deleting saved custom providers.

- [ ] **Step 5: Verify**

```bash
./android/gradlew -p android testDebugUnitTest connectedDebugAndroidTest
```

Expected: persistence and Keystore instrumented tests pass.

### Task 5: Build the map-first Compose screen

**Files:**

- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/ui/MainViewModel.kt`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/ui/MainScreen.kt`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/ui/RouteMap.kt`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/map/RouteMapState.kt`
- Test: `android/app/src/test/java/com/alphaauxiliary/fitgenerator/ui/MainViewModelTest.kt`

- [ ] **Step 1: Write state-machine tests**

Use `runTest` and Turbine to assert:

```text
Empty → Drawing → Ready → Previewing → Ready
Ready → Exporting → Ready
Ready → Error → Ready
```

Route edits clear the previous preview; export-directory changes do not. Core errors preserve route and input values.

- [ ] **Step 2: Implement `MainViewModel`**

Use immutable `MainUiState` in a `StateFlow`. Inject core, settings, route repository, location search, export coordinator, and clock. Run preview/export off the main thread. Apply the 400 ms search debounce, cancel superseded searches, and keep search results out of persistence.

- [ ] **Step 3: Implement MapLibre lifecycle**

Host `MapView` through `AndroidView`, forwarding lifecycle events from `LifecycleEventObserver`. Load the bundled OSM style and add:

- GeoJSON route source;
- Track Red line layer;
- vertex circle layer;
- preview marker source/layer.

- [ ] **Step 4: Implement freehand drawing**

When draw mode is active, place a transparent Compose gesture overlay over the map. On drag:

1. convert screen point through MapLibre projection;
2. convert provider coordinate to WGS-84;
3. add only if at least eight meters from the prior point;
4. update GeoJSON source.

When draw mode is inactive, remove the gesture overlay so normal map pan/zoom receives events.

- [ ] **Step 5: Add undo, clear, locate, and vertex editing**

All operations edit canonical WGS-84 points in the view model. Map layers are a projection of state and never become the source of truth.

- [ ] **Step 6: Verify**

```bash
./android/gradlew -p android testDebugUnitTest --tests '*MainViewModelTest'
```

Expected: route and preview state tests pass.

### Task 6: Apply the approved Android visual system

**Files:**

- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/ui/theme/Color.kt`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/ui/theme/Theme.kt`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/ui/theme/Type.kt`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/ui/RouteTimingStrip.kt`
- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/ui/SimulationSheet.kt`
- Modify: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/ui/MainScreen.kt`
- Test: `android/app/src/androidTest/java/com/alphaauxiliary/fitgenerator/ui/MainScreenTest.kt`

- [ ] **Step 1: Define semantic colors**

```kotlin
val FieldPaper = Color(0xFFF3F6F1)
val ScoreboardInk = Color(0xFF16221C)
val TrackRed = Color(0xFFD14E39)
val PaceTeal = Color(0xFF1D6F78)
val HeartBerry = Color(0xFFC2385A)
val LaneGrayGreen = Color(0xFFB8C3B8)
```

Use Roboto/Noto Sans CJK and tabular figures for metrics. Do not add a network font dependency.

- [ ] **Step 2: Write UI semantics tests**

Assert content descriptions and visible text for:

- draw;
- undo;
- locate;
- clear;
- expand simulation sheet;
- generate FIT;
- route-empty guidance.

- [ ] **Step 3: Implement the timing strip**

The collapsed sheet header always displays distance, estimated time, pace, and heart rate. The preview marker and strip share the same canonical sample index. Honor the system animator duration scale/reduced-motion preference.

- [ ] **Step 4: Implement mobile layout**

Use:

- map as the dominant surface;
- top app bar with title and settings;
- map-top location search with provider attribution;
- right-side floating map actions;
- persistent timing strip;
- bottom sheet ordered Simulation → Preview → Export;
- one Track Red “生成 FIT” action;
- flat surfaces, 6–10 dp radii, and no gradients.

- [ ] **Step 5: Accessibility and size tests**

Verify:

- touch targets are at least 48 dp;
- font scaling at 1.0, 1.3, and 2.0 does not hide export;
- TalkBack reads controls in workflow order;
- state is not conveyed by color alone.

- [ ] **Step 6: Run screenshot/manual review**

Capture phone portrait screenshots for empty, ready, previewing, and error states. Compare against the approved “trackside timing desk” direction and remove any decorative card or motion that does not encode state.

### Task 7: Implement local export and sharing

**Files:**

- Create: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/export/ExportCoordinator.kt`
- Modify: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/ui/MainViewModel.kt`
- Modify: `android/app/src/main/java/com/alphaauxiliary/fitgenerator/MainActivity.kt`
- Test: `android/app/src/test/java/com/alphaauxiliary/fitgenerator/export/ExportCoordinatorTest.kt`

- [ ] **Step 1: Write failing export tests**

Assert:

- one export proposes `run.fit`;
- multiple exports propose `runs.zip`;
- all FIT payloads are generated before opening the destination stream;
- a generation failure writes zero bytes;
- ZIP contains ordered `run_1.fit` entries;
- cancellation leaves the screen in Ready state.

- [ ] **Step 2: Implement single-file save**

Register `ActivityResultContracts.CreateDocument("application/vnd.ant.fit")`. The coordinator holds generated bytes only until the result URI arrives, then writes once through `ContentResolver`.

- [ ] **Step 3: Implement multi-file ZIP**

Generate 1–20 FIT byte arrays first. On success, stream them through `ZipOutputStream` with deterministic names. Do not request storage permission.

- [ ] **Step 4: Implement share**

Write a temporary file in app cache, expose it through `FileProvider`, and launch `ACTION_SEND`. Delete stale cache exports on next startup.

- [ ] **Step 5: Verify**

```bash
./android/gradlew -p android testDebugUnitTest --tests '*ExportCoordinatorTest'
```

Expected: cancellation, all-or-nothing generation, file naming, and ZIP tests pass.

### Task 8: Build, package, and verify the release APK

**Files:**

- Modify: `android/app/build.gradle`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `package.json`
- Modify: `README.md`

- [ ] **Step 1: Minimize permissions**

Manifest keeps only:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Set `usesCleartextTraffic="false"`. Do not request location or storage permission.

- [ ] **Step 2: Add release signing inputs**

Read `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` from environment variables. When all four are present, bind `buildTypes.release` to `signingConfigs.release`. When one or more are absent, emit one lifecycle warning and produce an unsigned release APK; never fall back to the debug keystore.

- [ ] **Step 3: Update build script**

Use the wrapper:

```json
"build:apk": "./android/gradlew -p android assembleRelease"
```

On Windows, document the equivalent `android\\gradlew.bat`.

- [ ] **Step 4: Verify ABI packaging**

```bash
./android/gradlew -p android assembleRelease
unzip -l android/app/build/outputs/apk/release/app-release.apk | \
  rg 'lib/(arm64-v8a|x86_64)/libfit_generator_core.so'
```

Expected: exactly both required ABI paths are present.

- [ ] **Step 5: Run final checks**

```bash
./android/gradlew -p android testDebugUnitTest lintRelease assembleRelease
git diff --check
git status --short
```

Expected: unit tests, lint, and release build pass.

- [ ] **Step 6: Run device acceptance**

On one arm64 phone and one x86_64 emulator:

1. install the release APK;
2. start without any server URL;
3. draw and edit a route;
4. preview;
5. turn off network;
6. reopen saved route;
7. export and decode a FIT;
8. verify no storage/location permission prompt appears.
