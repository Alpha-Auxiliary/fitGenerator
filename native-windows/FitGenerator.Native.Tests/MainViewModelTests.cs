using System.Globalization;
using FitGenerator.Native.Core;
using FitGenerator.Native.Map;
using FitGenerator.Native.Services;
using FitGenerator.Native.UI;
using FluentAssertions;
using Xunit;

namespace FitGenerator.Native.Tests;

public sealed class MainViewModelTests
{
    private static readonly IReadOnlyList<GeoCoordinate> ReadyRoute =
        Array.AsReadOnly(new[]
        {
            new GeoCoordinate(39.9042, 116.4074),
            new GeoCoordinate(39.9052, 116.4084),
        });

    [Fact]
    public void Empty_route_moves_through_drawing_ready_previewing_and_ready()
    {
        var core = new FakeCoreService();
        var map = new FakeRouteMapController();
        var viewModel = CreateViewModel(core: core, map: map);
        var phases = ObservePhases(viewModel);

        viewModel.StartDrawing();
        viewModel.FinishDrawing(ReadyRoute);
        viewModel.Preview();

        phases.Should().Equal(
            MainViewPhase.Empty,
            MainViewPhase.Drawing,
            MainViewPhase.Ready,
            MainViewPhase.Previewing,
            MainViewPhase.Ready);
        viewModel.State.PreviewModel.Should().BeSameAs(core.LastPreviewModel);
        viewModel.State.PreviewSeed.Should().NotBeNull();
        core.LastPreviewInput!.RoutePoints.Should().Equal(
            ReadyRoute.Select(point =>
                new ActivityRoutePoint(point.Latitude, point.Longitude)));
    }

    [Fact]
    public void Ready_route_moves_through_exporting_and_back_to_ready()
    {
        var export = new FakeExportService();
        var viewModel = CreateViewModel(
            route: ReadyRoute,
            exportDirectory: @"D:\Runs",
            export: export);
        viewModel.Preview();
        var previewSeed = viewModel.State.PreviewSeed!.Value;
        var phases = ObservePhases(viewModel);
        export.OnExport = _ =>
        {
            viewModel.State.Phase.Should().Be(MainViewPhase.Exporting);
            viewModel.Export();
            viewModel.ClearRoute();
        };

        viewModel.Export();

        phases.Should().Equal(
            MainViewPhase.Ready,
            MainViewPhase.Exporting,
            MainViewPhase.Ready);
        export.LastRequest.Should().NotBeNull();
        export.LastRequest!.Wgs84Activity.RoutePoints.Should().HaveCount(2);
        export.LastRequest.ExportDirectory.Should().Be(@"D:\Runs");
        export.LastRequest.Seed.Should().Be(previewSeed);
        viewModel.State.PreviewSeed.Should().Be(previewSeed);
        viewModel.State.RoutePoints.Should().Equal(ReadyRoute);
    }

    [Fact]
    public void Export_without_a_successful_preview_enters_error_and_does_not_resample()
    {
        var export = new FakeExportService();
        var viewModel = CreateViewModel(
            route: ReadyRoute,
            exportDirectory: @"D:\Runs",
            export: export);
        var phases = ObservePhases(viewModel);

        viewModel.Export();

        phases.Should().Equal(
            MainViewPhase.Ready,
            MainViewPhase.Error,
            MainViewPhase.Ready);
        export.LastRequest.Should().BeNull();
        viewModel.State.PreviewSeed.Should().BeNull();
        viewModel.State.PreviewModel.Should().BeNull();
    }

    [Fact]
    public void Failure_enters_error_then_restores_the_previous_usable_state()
    {
        var core = new FakeCoreService
        {
            PreviewException = new NativeCoreException(100, "预览参数无效"),
        };
        var viewModel = CreateViewModel(route: ReadyRoute, core: core);
        var phases = ObservePhases(viewModel);

        viewModel.Preview();

        phases.Should().Equal(
            MainViewPhase.Ready,
            MainViewPhase.Previewing,
            MainViewPhase.Error,
            MainViewPhase.Ready);
        viewModel.State.RoutePoints.Should().Equal(ReadyRoute);
        viewModel.State.StatusMessage.Should().Be("预览参数无效");
        viewModel.State.PreviewSeed.Should().NotBeNull(
            "a failed operation must preserve the batch seed for retry");
        viewModel.State.CanExport.Should().BeFalse();
    }

    [Fact]
    public void Changing_the_route_invalidates_the_previous_preview_and_seed()
    {
        var viewModel = CreateViewModel(route: ReadyRoute);
        viewModel.Preview();
        var previousModel = viewModel.State.PreviewModel;
        var previousSeed = viewModel.State.PreviewSeed;
        var changedRoute = new[]
        {
            ReadyRoute[0],
            ReadyRoute[1],
            new GeoCoordinate(39.9062, 116.4094),
        };

        viewModel.StartDrawing();
        viewModel.FinishDrawing(changedRoute);

        previousModel.Should().NotBeNull();
        previousSeed.Should().NotBeNull();
        viewModel.State.RoutePoints.Should().Equal(changedRoute);
        viewModel.State.PreviewModel.Should().BeNull();
        viewModel.State.PreviewSeed.Should().BeNull();
    }

    [Fact]
    public void Changing_only_the_export_directory_preserves_preview_and_seed()
    {
        var viewModel = CreateViewModel(
            route: ReadyRoute,
            exportDirectory: @"D:\Before");
        viewModel.Preview();
        var preview = viewModel.State.PreviewModel;
        var seed = viewModel.State.PreviewSeed;

        viewModel.UpdateExportOptions(@"D:\After", exportCount: 3);

        viewModel.State.ExportDirectory.Should().Be(@"D:\After");
        viewModel.State.ExportCount.Should().Be(3);
        viewModel.State.PreviewModel.Should().BeSameAs(preview);
        viewModel.State.PreviewSeed.Should().Be(seed);
    }

    [Fact]
    public void Changing_core_activity_input_invalidates_preview_and_seed()
    {
        var viewModel = CreateViewModel(route: ReadyRoute);
        viewModel.Preview();
        var previous = viewModel.State;

        viewModel.UpdateActivityParameters(
            previous.StartTime.AddMinutes(1),
            previous.PaceSecondsPerKilometer,
            previous.RestingHeartRate,
            previous.MaximumHeartRate,
            previous.LapCount);

        viewModel.State.PreviewModel.Should().BeNull();
        viewModel.State.PreviewSeed.Should().BeNull();
    }

    [Fact]
    public void Preview_ignores_reentrant_commands_while_busy()
    {
        var core = new FakeCoreService();
        var viewModel = CreateViewModel(route: ReadyRoute, core: core);
        var phases = ObservePhases(viewModel);
        core.OnPreview = () =>
        {
            viewModel.Preview();
            viewModel.ClearRoute();
        };

        viewModel.Preview();

        phases.Should().Equal(
            MainViewPhase.Ready,
            MainViewPhase.Previewing,
            MainViewPhase.Ready);
        viewModel.State.RoutePoints.Should().Equal(ReadyRoute);
    }

    [Fact]
    public void Export_failure_restores_ready_state_and_preserves_valid_preview()
    {
        var export = new FakeExportService
        {
            ExportException = new InvalidOperationException("disk unavailable"),
        };
        var viewModel = CreateViewModel(
            route: ReadyRoute,
            exportDirectory: @"D:\Runs",
            export: export);
        viewModel.Preview();
        var preview = viewModel.State.PreviewModel;
        var seed = viewModel.State.PreviewSeed;
        var phases = ObservePhases(viewModel);

        viewModel.Export();

        phases.Should().Equal(
            MainViewPhase.Ready,
            MainViewPhase.Exporting,
            MainViewPhase.Error,
            MainViewPhase.Ready);
        viewModel.State.PreviewModel.Should().BeSameAs(preview);
        viewModel.State.PreviewSeed.Should().Be(seed);
    }

    [Fact]
    public void Editing_a_custom_provider_replaces_and_persists_the_same_provider_id()
    {
        var original = CreateCustomProvider("custom-campus", "Campus A");
        var settings = new FakeSettingsService
        {
            Settings = AppSettings.Default with
            {
                ActiveMapProviderId = original.Id,
                CustomMapProviders = new[] { original },
            },
        };
        var map = new FakeRouteMapController();
        var viewModel = CreateViewModel(settings: settings, map: map);
        var edited = original with
        {
            DisplayName = "Campus B",
            XyzUrlTemplate = "https://tiles.example.test/edited/{z}/{x}/{y}.png",
        };

        viewModel.ChangeMapProvider(edited);

        viewModel.State.ActiveMapProvider.Should().Be(edited);
        viewModel.State.AvailableMapProviders
            .Where(provider => provider.Id == original.Id)
            .Should()
            .ContainSingle()
            .Which
            .Should()
            .Be(edited);
        settings.Settings.CustomMapProviders.Should().ContainSingle().Which.Should().Be(edited);
        map.LastProvider.Should().Be(edited);
    }

    [Fact]
    public void Invalid_active_provider_falls_back_without_discarding_a_valid_saved_route()
    {
        var invalidProvider = CreateCustomProvider("invalid-provider", "Invalid");
        var settings = new FakeSettingsService
        {
            Settings = AppSettings.Default with
            {
                ActiveMapProviderId = invalidProvider.Id,
                CustomMapProviders = new[] { invalidProvider },
            },
            Route = new SavedRoute { Wgs84Points = ReadyRoute },
        };
        var map = new FakeRouteMapController
        {
            SwitchProviderHandler = provider =>
            {
                if (provider.Id == invalidProvider.Id)
                {
                    throw new InvalidOperationException("invalid provider");
                }
            },
        };

        var viewModel = CreateViewModel(settings: settings, map: map);

        viewModel.State.ActiveMapProvider.Should().Be(MapProviderDefinition.OpenStreetMap);
        viewModel.State.RoutePoints.Should().Equal(ReadyRoute);
        viewModel.State.Phase.Should().Be(MainViewPhase.Ready);
        map.Wgs84RoutePoints.Should().Equal(ReadyRoute);
    }

    [Fact]
    public async Task Older_search_completion_cannot_override_the_latest_result()
    {
        var older = new TaskCompletionSource<IReadOnlyList<SearchResult>>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var newer = new TaskCompletionSource<IReadOnlyList<SearchResult>>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var search = new FakeSearchService
        {
            SearchHandler = (query, _, _, _) => query == "older" ? older.Task : newer.Task,
        };
        var map = new FakeRouteMapController();
        var viewModel = CreateViewModel(map: map, search: search);

        var olderSearch = viewModel.SearchAsync(
            "older",
            CultureInfo.InvariantCulture,
            CancellationToken.None);
        var newerSearch = viewModel.SearchAsync(
            "newer",
            CultureInfo.InvariantCulture,
            CancellationToken.None);
        var newerResult = new SearchResult(
            "New result",
            "New attribution",
            new GeoCoordinate(31.2304, 121.4737));
        newer.SetResult(new[] { newerResult });
        await newerSearch;

        older.SetResult(new[]
        {
            new SearchResult(
                "Old result",
                "Old attribution",
                new GeoCoordinate(39.9042, 116.4074)),
        });
        await olderSearch;

        viewModel.State.StatusMessage.Should().Contain("New result").And.NotContain("Old result");
        map.CenteredCoordinates.Should().ContainSingle().Which.Should().Be(newerResult.Coordinate);
    }

    [Fact]
    public async Task Search_completion_preserves_state_changes_made_while_it_was_pending()
    {
        var completion = new TaskCompletionSource<IReadOnlyList<SearchResult>>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var search = new FakeSearchService
        {
            SearchHandler = (_, _, _, _) => completion.Task,
        };
        var viewModel = CreateViewModel(search: search);
        var pending = viewModel.SearchAsync(
            "campus",
            CultureInfo.InvariantCulture,
            CancellationToken.None);

        viewModel.UpdateActivityParameters(
            viewModel.State.StartTime.AddMinutes(5),
            420,
            viewModel.State.RestingHeartRate,
            viewModel.State.MaximumHeartRate,
            viewModel.State.LapCount);
        completion.SetResult(Array.Empty<SearchResult>());
        await pending;

        viewModel.State.PaceSecondsPerKilometer.Should().Be(420);
        viewModel.State.StartTime.Minute.Should().Be(35);
        viewModel.State.StatusMessage.Should().Be("没有找到匹配地点");
    }

    [Fact]
    public void Startup_viewport_is_restored_once_after_the_host_reports_ready()
    {
        var expected = new MapViewportSettings(31.2304, 121.4737, 13.5);
        var settings = new FakeSettingsService
        {
            Settings = AppSettings.Default with { MapViewport = expected },
        };
        var map = new FakeRouteMapController();
        var viewModel = CreateViewModel(settings: settings, map: map);

        viewModel.RestoreMapViewportOnce();
        viewModel.RestoreMapViewportOnce();

        map.RestoredViewports.Should().ContainSingle().Which.Should().Be(expected);
    }

    [Fact]
    public void Invalid_startup_viewport_falls_back_to_the_safe_wgs84_default()
    {
        var invalid = new MapViewportSettings(200.0, 121.4737, 13.5);
        var settings = new FakeSettingsService
        {
            Settings = AppSettings.Default with { MapViewport = invalid },
        };
        var map = new FakeRouteMapController
        {
            RestoreViewportHandler = viewport =>
            {
                if (viewport == invalid)
                {
                    throw new ArgumentOutOfRangeException(nameof(viewport));
                }
            },
        };
        var viewModel = CreateViewModel(settings: settings, map: map);

        viewModel.RestoreMapViewportOnce();

        map.RestoredViewports.Should().Equal(invalid, MapViewportSettings.Default);
    }

    [Fact]
    public void Closing_persists_one_captured_wgs84_viewport_and_never_blocks_on_failure()
    {
        var captured = new MapViewportSettings(22.5431, 114.0579, 7.25);
        var settings = new FakeSettingsService();
        var map = new FakeRouteMapController { CapturedViewport = captured };
        var viewModel = CreateViewModel(settings: settings, map: map);
        viewModel.RestoreMapViewportOnce();

        viewModel.PersistMapViewportOnClose();
        viewModel.PersistMapViewportOnClose();

        settings.SaveSettingsCallCount.Should().Be(1);
        settings.Settings.MapViewport.Should().Be(captured);

        var failingViewModel = CreateViewModel(
            map: new FakeRouteMapController
            {
                CaptureViewportException = new InvalidOperationException("map unavailable"),
            });
        failingViewModel.RestoreMapViewportOnce();

        Action closing = () => failingViewModel.PersistMapViewportOnClose();
        closing.Should().NotThrow();
    }

    private static MainViewModel CreateViewModel(
        IReadOnlyList<GeoCoordinate>? route = null,
        string? exportDirectory = null,
        FakeCoreService? core = null,
        FakeRouteMapController? map = null,
        FakeExportService? export = null,
        FakeSettingsService? settings = null,
        FakeSearchService? search = null)
    {
        settings ??= new FakeSettingsService
        {
            Settings = AppSettings.Default with
            {
                ExportDirectory = exportDirectory,
            },
            Route = new SavedRoute
            {
                Wgs84Points = route ?? Array.Empty<GeoCoordinate>(),
            },
        };

        return new MainViewModel(
            core ?? new FakeCoreService(),
            settings,
            map ?? new FakeRouteMapController(),
            export ?? new FakeExportService(),
            new FakeApplicationClock(
                new DateTime(2026, 8, 4, 7, 30, 0, DateTimeKind.Local)),
            search ?? new FakeSearchService());
    }

    private static List<MainViewPhase> ObservePhases(MainViewModel viewModel)
    {
        var phases = new List<MainViewPhase> { viewModel.State.Phase };
        viewModel.StateChanged += state => phases.Add(state.Phase);
        return phases;
    }

    private sealed class FakeCoreService : INativeCoreService
    {
        internal Exception? PreviewException { get; init; }

        internal Action? OnPreview { get; set; }

        internal ActivityInput? LastPreviewInput { get; private set; }

        internal ActivityModelDto? LastPreviewModel { get; private set; }

        public ActivityModelDto Preview(ActivityInput input, ulong seed)
        {
            LastPreviewInput = input;
            OnPreview?.Invoke();
            if (PreviewException is not null)
            {
                throw PreviewException;
            }

            var model = CreatePreviewModel(seed);
            LastPreviewModel = model;
            return model;
        }

        public byte[] GenerateFit(ActivityInput input, ulong seed, int variantIndex) =>
            throw new InvalidOperationException("The view-model must use the export service.");
    }

    private sealed class FakeSettingsService : ISettingsService
    {
        internal AppSettings Settings { get; set; } = AppSettings.Default;

        internal SavedRoute Route { get; set; } = SavedRoute.Empty;

        internal int SaveSettingsCallCount { get; private set; }

        internal Exception? SaveSettingsException { get; init; }

        public AppSettings LoadSettings() => Settings;

        public void SaveSettings(AppSettings settings)
        {
            SaveSettingsCallCount++;
            if (SaveSettingsException is not null)
            {
                throw SaveSettingsException;
            }

            Settings = settings;
        }

        public SavedRoute LoadLastRoute() => Route;

        public void SaveLastRoute(SavedRoute route)
        {
            Route = route;
        }
    }

    private sealed class FakeRouteMapController : IRouteMapController
    {
        private readonly List<GeoCoordinate> _route = [];

        internal MapProviderDefinition? LastProvider { get; private set; }

        internal List<GeoCoordinate> CenteredCoordinates { get; } = [];

        internal List<MapViewportSettings> RestoredViewports { get; } = [];

        internal MapViewportSettings CapturedViewport { get; set; } =
            MapViewportSettings.Default;

        internal Exception? RestoreViewportException { get; set; }

        internal Action<MapViewportSettings>? RestoreViewportHandler { get; init; }

        internal Exception? CaptureViewportException { get; set; }

        internal Action<MapProviderDefinition>? SwitchProviderHandler { get; init; }

        public IReadOnlyList<GeoCoordinate> Wgs84RoutePoints =>
            Array.AsReadOnly(_route.ToArray());

        public void StartDrawing()
        {
        }

        public void StopDrawing()
        {
        }

        public void SetWgs84Route(IReadOnlyList<GeoCoordinate> points)
        {
            _route.Clear();
            _route.AddRange(points);
        }

        public void UndoPoint()
        {
            if (_route.Count > 0)
            {
                _route.RemoveAt(_route.Count - 1);
            }
        }

        public void ClearRoute() => _route.Clear();

        public void SwitchProvider(MapProviderDefinition provider)
        {
            SwitchProviderHandler?.Invoke(provider);
            LastProvider = provider;
        }

        public void RestoreViewport(MapViewportSettings viewport)
        {
            RestoredViewports.Add(viewport);
            RestoreViewportHandler?.Invoke(viewport);
            if (RestoreViewportException is not null)
            {
                throw RestoreViewportException;
            }
        }

        public MapViewportSettings CaptureViewport()
        {
            if (CaptureViewportException is not null)
            {
                throw CaptureViewportException;
            }

            return CapturedViewport;
        }

        public void ZoomToRouteOrDefault()
        {
        }

        public void CenterOnWgs84(GeoCoordinate coordinate)
        {
            CenteredCoordinates.Add(coordinate);
        }
    }

    private sealed class FakeExportService : IExportService
    {
        internal Action<ExportRequest>? OnExport { get; set; }

        internal Exception? ExportException { get; init; }

        internal ExportRequest? LastRequest { get; private set; }

        public void Export(ExportRequest request)
        {
            LastRequest = request;
            OnExport?.Invoke(request);
            if (ExportException is not null)
            {
                throw ExportException;
            }
        }
    }

    private sealed class FakeApplicationClock(DateTime localNow) : IApplicationClock
    {
        public DateTime LocalNow => localNow;
    }

    private sealed class FakeSearchService : ISearchService
    {
        internal Func<
            string,
            MapProviderDefinition,
            CultureInfo,
            CancellationToken,
            Task<IReadOnlyList<SearchResult>>>? SearchHandler { get; init; }

        public Task<IReadOnlyList<SearchResult>> SearchAsync(
            string query,
            MapProviderDefinition provider,
            CultureInfo uiCulture,
            CancellationToken cancellationToken) =>
            SearchHandler?.Invoke(query, provider, uiCulture, cancellationToken)
            ?? Task.FromResult<IReadOnlyList<SearchResult>>(Array.Empty<SearchResult>());

        public Task<ProviderConnectionResult> TestConnectionAsync(
            MapProviderDefinition provider,
            CancellationToken cancellationToken) =>
            Task.FromResult(new ProviderConnectionResult(true, "连接成功"));
    }

    private static MapProviderDefinition CreateCustomProvider(string id, string name) => new(
        Id: id,
        DisplayName: name,
        XyzUrlTemplate: "https://tiles.example.test/{z}/{x}/{y}.png",
        CoordinateSystem: CoordinateSystem.Wgs84,
        MaximumZoom: 18,
        Attribution: "Example attribution",
        GeocoderUrl: "https://search.example.test/search");

    private static ActivityModelDto CreatePreviewModel(ulong seed) => new(
        SchemaVersion: 1,
        AlgorithmVersion: 1,
        StartTimeUtc: "2026-08-03T23:30:00.0000000Z",
        Seed: seed,
        TotalDistanceCm: 12_345,
        TotalDurationMs: 74_070,
        Laps: new[]
        {
            new LapModelDto(1, 0, 1, 12_345, 74_070),
        },
        Samples: new[]
        {
            new ActivitySampleDto(0, 0, 1_667, 60, 0, 0),
            new ActivitySampleDto(74_070, 12_345, 1_667, 150, 0, 0),
        });
}
