# Windows Native Portable App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the duplicated C# activity algorithm with the Rust core and ship a polished Windows 10/11 x64 WinForms/Mapsui application as one self-contained EXE.

**Architecture:** A `NativeCoreService` owns P/Invoke, JSON serialization, API version checks, and native result lifetime. View models coordinate Mapsui routes, local settings, previews, and export services; `MainForm` only binds controls and events. The publish profile bundles the .NET runtime and Rust DLL into one self-extracting executable.

**Tech Stack:** .NET 8, C# 12, WinForms, Mapsui 5.1, System.Text.Json, DPAPI, xUnit, FluentAssertions.

---

## Preconditions

- Complete the Rust plan through its full quality gate.
- Build `core-rust/target/x86_64-pc-windows-msvc/release/fit_generator_core.dll`.
- Do not stage or commit; use `git diff --check` and `git status --short` at each checkpoint.

## Target file map

Create:

```text
native-windows/
├── FitGenerator.Native/
│   ├── Core/
│   │   ├── NativeMethods.cs
│   │   ├── NativeResultHandle.cs
│   │   ├── NativeCoreService.cs
│   │   └── CoreModels.cs
│   ├── Map/
│   │   ├── CoordinateSystem.cs
│   │   ├── CoordinateTransforms.cs
│   │   ├── MapProviderDefinition.cs
│   │   └── RouteMapController.cs
│   ├── Services/
│   │   ├── AppSettings.cs
│   │   ├── SettingsService.cs
│   │   ├── ExportService.cs
│   │   └── SearchService.cs
│   ├── UI/
│   │   ├── DesignTokens.cs
│   │   ├── RouteTimingStrip.cs
│   │   └── MainViewModel.cs
│   └── Properties/PublishProfiles/Portable.pubxml
└── FitGenerator.Native.Tests/
    ├── FitGenerator.Native.Tests.csproj
    ├── NativeCoreServiceTests.cs
    ├── CoordinateTransformsTests.cs
    ├── SettingsServiceTests.cs
    ├── SearchServiceTests.cs
    ├── ExportServiceTests.cs
    └── MainViewModelTests.cs
```

Modify:

- `native-windows/FitGenerator.Native/FitGenerator.Native.csproj`
- `native-windows/FitGenerator.Native/Program.cs`
- `native-windows/FitGenerator.Native/MainForm.cs`
- `package.json`
- `README.md`

Delete after replacement tests pass:

- `native-windows/FitGenerator.Native/FitCore.cs`

### Task 1: Create the test project and native bridge contract

**Files:**

- Create: `native-windows/FitGenerator.Native.Tests/FitGenerator.Native.Tests.csproj`
- Create: `native-windows/FitGenerator.Native/Core/CoreModels.cs`
- Create: `native-windows/FitGenerator.Native/Core/NativeMethods.cs`
- Create: `native-windows/FitGenerator.Native/Core/NativeResultHandle.cs`
- Modify: `native-windows/FitGenerator.Native/FitGenerator.Native.csproj`

- [ ] **Step 1: Create the xUnit project**

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <TargetFramework>net8.0-windows</TargetFramework>
    <ImplicitUsings>enable</ImplicitUsings>
    <Nullable>enable</Nullable>
    <IsPackable>false</IsPackable>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Microsoft.NET.Test.Sdk" Version="17.11.1" />
    <PackageReference Include="xunit" Version="2.9.2" />
    <PackageReference Include="xunit.runner.visualstudio" Version="2.8.2" />
    <PackageReference Include="FluentAssertions" Version="6.12.2" />
    <ProjectReference Include="../FitGenerator.Native/FitGenerator.Native.csproj" />
  </ItemGroup>
</Project>
```

- [ ] **Step 2: Write a failing API version test**

```csharp
public sealed class NativeCoreServiceTests
{
    [Fact]
    public void Constructor_rejects_an_incompatible_core()
    {
        var api = new FakeNativeApi { ApiVersion = 2 };
        var action = () => new NativeCoreService(api);
        action.Should().Throw<NativeCoreException>()
            .WithMessage("*版本不兼容*");
    }
}
```

Define `INativeCoreApi` in the test expectation with `uint ApiVersion`, `byte[] Preview(byte[])`, and `byte[] GenerateFit(byte[])`.

- [ ] **Step 3: Run and verify failure**

Run on Windows:

```powershell
dotnet test native-windows/FitGenerator.Native.Tests/FitGenerator.Native.Tests.csproj
```

Expected: compile failure because the bridge types do not exist.

- [ ] **Step 4: Implement P/Invoke and safe result ownership**

`NativeMethods` declares the exact `fg_*` functions using `CallingConvention.Cdecl`. `NativeResultHandle` derives from `SafeHandleZeroOrMinusOneIsInvalid` and calls `fg_result_free` in `ReleaseHandle`.

```csharp
internal interface INativeCoreApi
{
    uint ApiVersion { get; }
    byte[] Preview(byte[] request);
    byte[] GenerateFit(byte[] request);
}
```

The production adapter must:

- call `fg_core_api_version`;
- wrap every returned pointer immediately in `NativeResultHandle`;
- read `fg_result_code` before data;
- decode errors with `Marshal.PtrToStringUTF8`;
- copy result bytes with `Marshal.Copy`;
- never expose a raw pointer outside `NativeMethods`.

- [ ] **Step 5: Define C# JSON models**

Mirror the Rust camelCase contract exactly. Use records:

```csharp
internal sealed record GeoPointDto(double Lat, double Lng);
internal sealed record CoreRequestDto(
    int SchemaVersion,
    string StartTimeUtc,
    IReadOnlyList<GeoPointDto> Points,
    double PaceSecondsPerKm,
    int HrRest,
    int HrMax,
    int LapCount,
    int VariantIndex,
    ulong Seed,
    string RouteMode);
```

Define canonical sample, lap, and model records with integer quantized fields. Configure one shared `JsonSerializerOptions` with camelCase and strict number handling.

- [ ] **Step 6: Verify**

Run:

```powershell
dotnet test native-windows/FitGenerator.Native.Tests/FitGenerator.Native.Tests.csproj
```

Expected: version mismatch and bridge lifetime tests pass.

### Task 2: Implement the native core service

**Files:**

- Create: `native-windows/FitGenerator.Native/Core/NativeCoreService.cs`
- Create: `native-windows/FitGenerator.Native.Tests/NativeCoreServiceTests.cs`

- [ ] **Step 1: Write failing request/response tests**

Use a fake native API to assert:

- local `DateTime` converts to a UTC `Z` string;
- route points retain order;
- preview passes the exact batch seed;
- export passes the selected variant index;
- Rust JSON errors become `NativeCoreException` with the Rust Chinese message.

- [ ] **Step 2: Implement the service**

```csharp
internal interface INativeCoreService
{
    ActivityModelDto Preview(ActivityInput input, ulong seed);
    byte[] GenerateFit(ActivityInput input, ulong seed, int variantIndex);
}
```

`ActivityInput` contains platform domain values, not Rust DTO names. Keep conversion in private methods. Use `RandomNumberGenerator.GetBytes(sizeof(ulong))` only when the view model creates a new batch seed; never regenerate it inside `Preview` or `GenerateFit`.

- [ ] **Step 3: Add a real DLL integration test**

Mark the test with a trait `Category=NativeIntegration`. Copy the release Rust DLL to the test output through the app project content item. Begin the test with:

```csharp
File.Exists(Path.Combine(AppContext.BaseDirectory, "fit_generator_core.dll"))
    .Should()
    .BeTrue("scripts/build-rust-windows.ps1 must run before native integration tests");
```

Load the DLL, call preview and FIT generation, and assert:

- API version is `1`;
- preview model contains samples;
- FIT bytes 8–11 equal `.FIT`.

- [ ] **Step 4: Verify**

```powershell
dotnet test native-windows/FitGenerator.Native.Tests/FitGenerator.Native.Tests.csproj
```

Expected: unit and native integration tests pass. A missing DLL is a test failure with the exact build-script instruction above.

### Task 3: Implement coordinate systems and map-provider definitions

**Files:**

- Create: `native-windows/FitGenerator.Native/Map/CoordinateSystem.cs`
- Create: `native-windows/FitGenerator.Native/Map/CoordinateTransforms.cs`
- Create: `native-windows/FitGenerator.Native/Map/MapProviderDefinition.cs`
- Create: `native-windows/FitGenerator.Native/Services/SearchService.cs`
- Create: `native-windows/FitGenerator.Native.Tests/CoordinateTransformsTests.cs`
- Create: `native-windows/FitGenerator.Native.Tests/SearchServiceTests.cs`

- [ ] **Step 1: Write known-coordinate tests**

Use fixed Beijing samples and assert round trips:

```csharp
[Theory]
[InlineData(39.9042, 116.4074)]
[InlineData(31.2304, 121.4737)]
public void Wgs84_to_gcj02_round_trip_stays_within_two_meters(double lat, double lng)
{
    var gcj = CoordinateTransforms.Wgs84ToGcj02(new GeoCoordinate(lat, lng));
    var restored = CoordinateTransforms.Gcj02ToWgs84(gcj);
    CoordinateTransforms.DistanceMeters(restored, new(lat, lng)).Should().BeLessThan(2);
}
```

Also test BD-09↔GCJ-02 and that coordinates outside China remain unchanged for GCJ conversion.

- [ ] **Step 2: Implement transforms**

Keep all constants private and expose:

```csharp
GeoCoordinate ToWgs84(GeoCoordinate value, CoordinateSystem source);
GeoCoordinate FromWgs84(GeoCoordinate value, CoordinateSystem target);
```

Use iterative GCJ-02 inverse conversion until the error is below `1e-7` degrees or 10 iterations.

- [ ] **Step 3: Implement provider definitions**

Built-ins:

- OpenStreetMap/WGS-84/default;
- OpenTopoMap/WGS-84;
- Carto Light/WGS-84.

Custom providers include display name, XYZ URL, optional header/query Key placement, coordinate system, maximum zoom, attribution, and optional geocoder URL.

- [ ] **Step 4: Write failing search-service tests**

Inject an `HttpMessageHandler` and a monotonic clock. Assert:

- the OSM default calls `https://nominatim.openstreetmap.org/search`;
- query parameters are `format=jsonv2`, `limit=5`, and URL-encoded `q`;
- headers include `User-Agent: fitGenerator/1.0 (+https://github.com/Alpha-Auxiliary/fitGenerator)` and the current UI language;
- two network requests start at least one second apart;
- a newer query cancels the older one;
- provider coordinates convert to canonical WGS-84;
- HTTP, timeout, and malformed-JSON failures return a search error without changing the route.

- [ ] **Step 5: Implement `SearchService`**

Expose:

```csharp
Task<IReadOnlyList<SearchResult>> SearchAsync(
    string query,
    MapProviderDefinition provider,
    CultureInfo uiCulture,
    CancellationToken cancellationToken);

Task<ProviderConnectionResult> TestConnectionAsync(
    MapProviderDefinition provider,
    CancellationToken cancellationToken);
```

Use one reusable `HttpClient`, a one-second minimum interval for the default Nominatim endpoint, a five-result cap, a 10-second timeout, and no persistent search history. Each result contains display label, attribution, and canonical WGS-84 coordinate. Show `© OpenStreetMap contributors` beside default results.

`TestConnectionAsync` requests one tile or the configured geocoder health query without logging the Key. The settings action “恢复默认地图” replaces the active provider with the immutable OSM definition but does not delete user-created providers.

- [ ] **Step 6: Verify**

```powershell
dotnet test native-windows/FitGenerator.Native.Tests/FitGenerator.Native.Tests.csproj --filter "CoordinateTransforms|SearchService"
```

Expected: coordinate, search contract, cancellation, rate-limit, and failure-isolation tests pass.

### Task 4: Add settings persistence and DPAPI-protected keys

**Files:**

- Create: `native-windows/FitGenerator.Native/Services/AppSettings.cs`
- Create: `native-windows/FitGenerator.Native/Services/SettingsService.cs`
- Create: `native-windows/FitGenerator.Native.Tests/SettingsServiceTests.cs`
- Modify: `native-windows/FitGenerator.Native/FitGenerator.Native.csproj`

- [ ] **Step 1: Write failing migration and protection tests**

Tests must use a temporary directory and assert:

- missing settings return defaults;
- schema version 1 round-trips;
- invalid JSON is renamed to `.invalid-<timestamp>.json`;
- the persisted file does not contain the plaintext test Key;
- a DPAPI failure leaves the app usable with an empty Key.

- [ ] **Step 2: Add the DPAPI package**

```xml
<PackageReference Include="System.Security.Cryptography.ProtectedData" Version="8.0.0" />
```

- [ ] **Step 3: Implement settings**

Use:

```text
%LOCALAPPDATA%/FitGenerator.Native/settings-v1.json
%LOCALAPPDATA%/FitGenerator.Native/last-route-v1.json
```

Protect Key bytes with `ProtectedData.Protect(..., DataProtectionScope.CurrentUser)` and Base64-encode the ciphertext. Save by writing a sibling temporary file, flushing it, then replacing the destination.

- [ ] **Step 4: Verify**

```powershell
dotnet test native-windows/FitGenerator.Native.Tests/FitGenerator.Native.Tests.csproj --filter SettingsService
```

Expected: defaults, migration, invalid-file backup, and encrypted-Key tests pass.

### Task 5: Split map control and application view model

**Files:**

- Create: `native-windows/FitGenerator.Native/Map/RouteMapController.cs`
- Create: `native-windows/FitGenerator.Native/UI/MainViewModel.cs`
- Create: `native-windows/FitGenerator.Native.Tests/MainViewModelTests.cs`
- Modify: `native-windows/FitGenerator.Native/MainForm.cs`

- [ ] **Step 1: Write view-model state tests**

Assert the exact state transitions:

```text
Empty → Drawing → Ready → Previewing → Ready
Ready → Exporting → Ready
Any non-exporting state → Error → previous usable state
```

Test that changing a route invalidates the old preview seed and model, while changing only the export directory does not.

- [ ] **Step 2: Implement `MainViewModel`**

Expose immutable display state and commands:

```csharp
StartDrawing();
FinishDrawing(IReadOnlyList<GeoCoordinate> mapPoints);
UndoPoint();
ClearRoute();
Preview();
Export();
```

Use injected interfaces for core, settings, map, export, and clock. Do not reference WinForms controls from the view model.

- [ ] **Step 3: Implement `RouteMapController`**

Move Mapsui layer management, point spacing, drawing gestures, map provider switching, zoom-to-route, and coordinate conversion out of `MainForm`. The controller stores canonical WGS-84 route points; renderer coordinates are derived.

- [ ] **Step 4: Reduce `MainForm` to binding**

`MainForm` creates controls, subscribes to view-model state changes, and forwards UI events. Remove activity calculations, FIT generation, search HTTP code, file writing, and coordinate math from the form.

- [ ] **Step 5: Verify**

```powershell
dotnet test native-windows/FitGenerator.Native.Tests/FitGenerator.Native.Tests.csproj --filter MainViewModel
```

Expected: all state transition and seed invalidation tests pass.

### Task 6: Apply the approved Windows visual design

**Files:**

- Create: `native-windows/FitGenerator.Native/UI/DesignTokens.cs`
- Create: `native-windows/FitGenerator.Native/UI/RouteTimingStrip.cs`
- Modify: `native-windows/FitGenerator.Native/MainForm.cs`

- [ ] **Step 1: Define design tokens**

```csharp
internal static class DesignTokens
{
    public static readonly Color FieldPaper = ColorTranslator.FromHtml("#F3F6F1");
    public static readonly Color ScoreboardInk = ColorTranslator.FromHtml("#16221C");
    public static readonly Color TrackRed = ColorTranslator.FromHtml("#D14E39");
    public static readonly Color PaceTeal = ColorTranslator.FromHtml("#1D6F78");
    public static readonly Color HeartBerry = ColorTranslator.FromHtml("#C2385A");
    public static readonly Color LaneGrayGreen = ColorTranslator.FromHtml("#B8C3B8");
    public const int SidebarWidth = 380;
    public const int RadiusSmall = 6;
    public const int RadiusLarge = 10;
}
```

Use Segoe UI Variable with Microsoft YaHei UI fallback and tabular-number rendering for metric labels.

- [ ] **Step 2: Implement the timing strip**

The control renders:

```text
● distance ━ estimated time ━ pace ━ heart rate
```

It accepts a normalized progress value and sample data. Under Windows reduced-motion settings, update progress discretely without interpolation.

- [ ] **Step 3: Rebuild the form layout**

Use:

- 380 px left sequence panel with Route, Simulation, Export sections;
- map on the right;
- a map-top search field with cancelable results and visible provider attribution;
- timing strip fixed below the map;
- map-local buttons for draw, undo, locate, and clear;
- a settings dialog containing provider name, XYZ URL, Key placement/value, coordinate system, max zoom, attribution, and geocoder URL;
- a masked Key field plus “测试连接” and “恢复默认地图” actions;
- exactly one Track Red primary export button;
- flat separators and 6–10 px radii, without gradients.

- [ ] **Step 4: Keyboard and accessibility pass**

Set logical tab order, accessible names, focus cues, minimum target sizes, and text labels in addition to color. Verify 100%, 125%, and 150% display scaling.

- [ ] **Step 5: Run the UI smoke checklist**

Launch:

```powershell
dotnet run --project native-windows/FitGenerator.Native/FitGenerator.Native.csproj
```

Expected: no console window; the route workflow reads top-to-bottom; map remains the largest surface; resizing never hides the export action or timing strip.

### Task 7: Implement preview playback and atomic export

**Files:**

- Create: `native-windows/FitGenerator.Native/Services/ExportService.cs`
- Create: `native-windows/FitGenerator.Native.Tests/ExportServiceTests.cs`
- Modify: `native-windows/FitGenerator.Native/UI/MainViewModel.cs`
- Modify: `native-windows/FitGenerator.Native/UI/RouteTimingStrip.cs`

- [ ] **Step 1: Write failing atomic batch tests**

Use an in-memory file-system abstraction. Assert:

- all FIT byte arrays are generated before the first final filename appears;
- failure in variant 2 leaves no final files;
- success produces `run.fit` or numbered files;
- existing files are replaced atomically;
- invalid output directory returns a platform error and preserves route state.

- [ ] **Step 2: Implement batch generation**

Limit count to 1–20. Derive variant seeds through the Rust request contract. Generate every byte array first, then write sibling `.tmp` files, flush, and move them to final names.

- [ ] **Step 3: Implement preview playback**

Use a UI timer only for presentation. Select the canonical sample whose `timeMs` is nearest to playback progress; never interpolate new activity values. Synchronize the map marker and timing strip. Respect reduced motion.

- [ ] **Step 4: Verify**

```powershell
dotnet test native-windows/FitGenerator.Native.Tests/FitGenerator.Native.Tests.csproj --filter ExportService
```

Expected: all atomicity and failure cleanup tests pass.

### Task 8: Publish and verify the single EXE

**Files:**

- Create: `native-windows/FitGenerator.Native/Properties/PublishProfiles/Portable.pubxml`
- Modify: `native-windows/FitGenerator.Native/FitGenerator.Native.csproj`
- Modify: `native-windows/FitGenerator.Native/Program.cs`
- Modify: `package.json`
- Modify: `README.md`

- [ ] **Step 1: Add publish properties**

```xml
<PropertyGroup>
  <RuntimeIdentifier>win-x64</RuntimeIdentifier>
  <SelfContained>true</SelfContained>
  <PublishSingleFile>true</PublishSingleFile>
  <IncludeNativeLibrariesForSelfExtract>true</IncludeNativeLibrariesForSelfExtract>
  <IncludeAllContentForSelfExtract>true</IncludeAllContentForSelfExtract>
  <PublishTrimmed>false</PublishTrimmed>
  <DebugType>none</DebugType>
  <DebugSymbols>false</DebugSymbols>
  <UseAppHost>true</UseAppHost>
</PropertyGroup>
```

Include the release Rust DLL as publish content named `fit_generator_core.dll`. Fail the build with an MSBuild `Error` task when that exact file is absent.

- [ ] **Step 2: Add a noninteractive packaged-app smoke mode**

Before `Application.Run`, handle only the exact argument `--self-test`. In that mode:

1. instantiate the production `NativeCoreService`;
2. deserialize the same two-point Beijing request used by `open_two_laps.json`;
3. call preview with seed `987654321`;
4. require at least one sample;
5. generate variant `1`;
6. require bytes 8–11 to equal `.FIT`;
7. return exit code `0` without creating a form.

Return `2` and write the exception message to `Console.Error` on failure. Normal double-click startup remains a `WinExe` and never creates a console window.

- [ ] **Step 3: Replace the package script**

Set:

```json
"build:native": "dotnet publish native-windows/FitGenerator.Native/FitGenerator.Native.csproj -c Release -r win-x64 -o dist/native-windows"
```

The output acceptance check requires exactly one distributable EXE; build metadata files may remain in `obj` but not in `dist/native-windows`.

- [ ] **Step 4: Remove duplicated C# algorithm**

Delete `FitCore.cs` only after all native bridge and export tests are green. Search:

```powershell
rg "FitCore|Garmin.FIT.Sdk|Dynastream.Fit" native-windows
```

Expected: no runtime reference remains.

- [ ] **Step 5: Build and inspect**

```powershell
dotnet test native-windows/FitGenerator.Native.Tests/FitGenerator.Native.Tests.csproj -c Release
dotnet publish native-windows/FitGenerator.Native/FitGenerator.Native.csproj -c Release -r win-x64 -o dist/native-windows
Get-ChildItem dist/native-windows
$exe = Get-ChildItem dist/native-windows/*.exe | Select-Object -First 1
& $exe.FullName --self-test
```

Expected: tests pass, the distribution directory contains one EXE, and `--self-test` exits `0`.

- [ ] **Step 6: Run clean-machine smoke test**

On clean Windows 10 and Windows 11 x64 VMs:

1. copy only the EXE;
2. disconnect network;
3. launch by double-click;
4. load the persisted fixture route;
5. preview;
6. export one FIT;
7. confirm no browser, server, console, admin prompt, or runtime installer appears.

- [ ] **Step 7: Final local checkpoint**

```powershell
dotnet test native-windows/FitGenerator.Native.Tests/FitGenerator.Native.Tests.csproj -c Release
git diff --check
git status --short
```

Expected: all tests pass and only intended local changes are listed.
