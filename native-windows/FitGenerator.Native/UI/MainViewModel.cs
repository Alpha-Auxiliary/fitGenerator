using System.Globalization;
using System.Security.Cryptography;
using FitGenerator.Native.Core;
using FitGenerator.Native.Map;
using FitGenerator.Native.Services;

namespace FitGenerator.Native.UI;

internal enum MainViewPhase
{
    Empty,
    Drawing,
    Ready,
    Previewing,
    Exporting,
    Error,
}

internal sealed record MainViewState(
    MainViewPhase Phase,
    IReadOnlyList<GeoCoordinate> RoutePoints,
    DateTime StartTime,
    double PaceSecondsPerKilometer,
    int RestingHeartRate,
    int MaximumHeartRate,
    int LapCount,
    int ExportCount,
    string? ExportDirectory,
    MapProviderDefinition ActiveMapProvider,
    IReadOnlyList<MapProviderDefinition> AvailableMapProviders,
    ulong? PreviewSeed,
    ActivityModelDto? PreviewModel,
    string StatusMessage)
{
    internal bool IsBusy => Phase is MainViewPhase.Previewing or MainViewPhase.Exporting;

    internal bool CanPreview => RoutePoints.Count >= 2 && !IsBusy;

    internal bool CanExport =>
        RoutePoints.Count >= 2
        && !string.IsNullOrWhiteSpace(ExportDirectory)
        && PreviewSeed.HasValue
        && PreviewModel is not null
        && !IsBusy;
}

internal sealed record ExportRequest(
    ActivityInput Wgs84Activity,
    ulong Seed,
    int ExportCount,
    string ExportDirectory);

internal interface IExportService
{
    void Export(ExportRequest request);
}

internal interface IApplicationClock
{
    DateTime LocalNow { get; }
}

internal sealed class SystemApplicationClock : IApplicationClock
{
    public DateTime LocalNow => DateTime.Now;
}

internal sealed class MainViewModel
{
    private const int MinimumRoutePointCount = 2;
    private const int MaximumExportCount = 20;

    private readonly INativeCoreService _core;
    private readonly ISettingsService _settingsService;
    private readonly IRouteMapController _map;
    private readonly IExportService _exportService;
    private readonly ISearchService _searchService;
    private AppSettings _persistedSettings;
    private long _searchGeneration;
    private bool _startupViewportRestoreAttempted;
    private bool _viewportPersistedOnClose;

    internal MainViewModel(
        INativeCoreService core,
        ISettingsService settingsService,
        IRouteMapController map,
        IExportService exportService,
        IApplicationClock clock,
        ISearchService searchService)
    {
        ArgumentNullException.ThrowIfNull(core);
        ArgumentNullException.ThrowIfNull(settingsService);
        ArgumentNullException.ThrowIfNull(map);
        ArgumentNullException.ThrowIfNull(exportService);
        ArgumentNullException.ThrowIfNull(clock);
        ArgumentNullException.ThrowIfNull(searchService);

        _core = core;
        _settingsService = settingsService;
        _map = map;
        _exportService = exportService;
        _searchService = searchService;

        var startupMessage = string.Empty;
        SavedRoute savedRoute;
        try
        {
            _persistedSettings = settingsService.LoadSettings();
            savedRoute = settingsService.LoadLastRoute();
        }
        catch (Exception exception)
        {
            _persistedSettings = AppSettings.Default;
            savedRoute = SavedRoute.Empty;
            startupMessage = UserMessage(exception, "无法读取本地设置，已使用默认配置");
        }

        var providers = BuildProviderList(_persistedSettings);
        var activeProvider = ResolveActiveProvider(
            _persistedSettings.ActiveMapProviderId,
            providers);
        var route = CopyRoute(savedRoute.Wgs84Points);

        try
        {
            _map.SwitchProvider(activeProvider);
        }
        catch (Exception exception)
        {
            activeProvider = MapProviderDefinition.OpenStreetMap;
            startupMessage = UserMessage(exception, "地图配置无效，已恢复默认地图");
            _map.SwitchProvider(activeProvider);
        }

        try
        {
            _map.SetWgs84Route(route);
        }
        catch (Exception exception)
        {
            route = EmptyRoute();
            startupMessage = UserMessage(exception, "最近轨迹无效，已保留其他本地设置");
            _map.SetWgs84Route(route);
        }

        State = new MainViewState(
            PhaseForRoute(route),
            route,
            TrimToMinute(clock.LocalNow),
            _persistedSettings.PaceSecondsPerKilometer,
            _persistedSettings.RestingHeartRate,
            _persistedSettings.MaximumHeartRate,
            _persistedSettings.LapCount,
            _persistedSettings.ExportCount,
            _persistedSettings.ExportDirectory,
            activeProvider,
            providers,
            _persistedSettings.PreviewSeed,
            PreviewModel: null,
            StatusMessage: string.IsNullOrWhiteSpace(startupMessage)
                ? InitialStatus(route)
                : startupMessage);
    }

    internal event Action<MainViewState>? StateChanged;

    internal MainViewState State { get; private set; }

    internal void RestoreMapViewportOnce()
    {
        if (_startupViewportRestoreAttempted)
        {
            return;
        }

        _startupViewportRestoreAttempted = true;
        try
        {
            _map.RestoreViewport(_persistedSettings.MapViewport);
            return;
        }
        catch (Exception)
        {
            // A persisted viewport is optional startup state. Fall through to the
            // known-safe WGS84 default without delaying the first interactive frame.
        }

        if (_persistedSettings.MapViewport == MapViewportSettings.Default)
        {
            return;
        }

        try
        {
            _map.RestoreViewport(MapViewportSettings.Default);
            _persistedSettings = _persistedSettings with
            {
                MapViewport = MapViewportSettings.Default,
            };
        }
        catch (Exception)
        {
            // Map initialization failures are already visible through the map surface;
            // viewport recovery must not make the window unusable.
        }
    }

    internal void PersistMapViewportOnClose()
    {
        if (!_startupViewportRestoreAttempted || _viewportPersistedOnClose)
        {
            return;
        }

        _viewportPersistedOnClose = true;
        try
        {
            var viewport = _map.CaptureViewport();
            var updated = CreateSettingsSnapshot(State, viewport);
            _settingsService.SaveSettings(updated);
            _persistedSettings = updated;
        }
        catch (Exception)
        {
            // Closing must remain best-effort even when the map or settings store is
            // unavailable. The previous atomic settings file remains authoritative.
        }
    }

    internal void StartDrawing()
    {
        if (State.IsBusy)
        {
            return;
        }

        var previous = State;
        try
        {
            _map.StartDrawing();
            Publish(previous with
            {
                Phase = MainViewPhase.Drawing,
                StatusMessage = "自由绘制已开启：按住左键拖动绘制轨迹。",
            });
        }
        catch (Exception exception)
        {
            PublishError(previous, UserMessage(exception, "无法开始绘制轨迹"));
        }
    }

    internal void StopDrawing()
    {
        if (State.IsBusy)
        {
            return;
        }

        var previous = State;
        try
        {
            _map.StopDrawing();
            Publish(previous with
            {
                Phase = PhaseForRoute(previous.RoutePoints),
                StatusMessage = "自由绘制已关闭：可以拖动或缩放地图。",
            });
        }
        catch (Exception exception)
        {
            PublishError(previous, UserMessage(exception, "无法关闭绘制模式"));
        }
    }

    internal void FinishDrawing(IReadOnlyList<GeoCoordinate> mapPoints)
    {
        ArgumentNullException.ThrowIfNull(mapPoints);
        if (State.IsBusy)
        {
            return;
        }

        var previous = State;
        try
        {
            _map.SetWgs84Route(mapPoints);
            _map.StopDrawing();
            ApplyRouteMutation(previous, "自由绘制完成");
        }
        catch (Exception exception)
        {
            PublishError(previous, UserMessage(exception, "绘制的轨迹坐标无效"));
        }
    }

    internal void UndoPoint()
    {
        if (State.IsBusy)
        {
            return;
        }

        var previous = State;
        try
        {
            _map.UndoPoint();
            ApplyRouteMutation(previous, "已撤销最后一个轨迹点");
        }
        catch (Exception exception)
        {
            PublishError(previous, UserMessage(exception, "无法撤销轨迹点"));
        }
    }

    internal void ClearRoute()
    {
        if (State.IsBusy)
        {
            return;
        }

        var previous = State;
        try
        {
            _map.ClearRoute();
            ApplyRouteMutation(previous, "轨迹已清空");
        }
        catch (Exception exception)
        {
            PublishError(previous, UserMessage(exception, "无法清空轨迹"));
        }
    }

    internal void Preview()
    {
        if (State.IsBusy)
        {
            return;
        }

        var previous = State;
        if (previous.RoutePoints.Count < MinimumRoutePointCount)
        {
            PublishError(previous, "请至少绘制两个轨迹点后再预览");
            return;
        }

        var seed = previous.PreviewSeed ?? CreateBatchSeed();
        var retryable = previous with { PreviewSeed = seed };
        Publish(retryable with
        {
            Phase = MainViewPhase.Previewing,
            StatusMessage = "正在生成活动预览...",
        });

        ActivityModelDto model;
        try
        {
            model = _core.Preview(CreateActivityInput(retryable), seed);
        }
        catch (Exception exception)
        {
            PublishError(
                retryable,
                UserMessage(exception, "预览失败，请检查活动参数后重试"));
            PersistSettingsWithoutReporting(retryable);
            return;
        }

        var ready = retryable with
        {
            Phase = MainViewPhase.Ready,
            PreviewModel = model,
            StatusMessage = PreviewStatus(model),
        };
        Publish(ready);
        PersistSettings(ready);
    }

    internal void Export()
    {
        if (State.IsBusy)
        {
            return;
        }

        var previous = State;
        if (previous.RoutePoints.Count < MinimumRoutePointCount)
        {
            PublishError(previous, "请至少绘制两个轨迹点后再生成 FIT");
            return;
        }

        if (string.IsNullOrWhiteSpace(previous.ExportDirectory))
        {
            PublishError(previous, "请先选择 FIT 文件导出目录");
            return;
        }

        if (previous.PreviewSeed is not ulong seed || previous.PreviewModel is null)
        {
            PublishError(previous, "请先生成有效预览，再导出 FIT 文件");
            return;
        }

        var retryable = previous;
        Publish(retryable with
        {
            Phase = MainViewPhase.Exporting,
            StatusMessage = "正在生成 FIT 文件...",
        });

        try
        {
            _exportService.Export(new ExportRequest(
                CreateActivityInput(retryable),
                seed,
                retryable.ExportCount,
                retryable.ExportDirectory!));
        }
        catch (Exception exception)
        {
            PublishError(
                retryable,
                UserMessage(exception, "导出失败，请检查导出设置后重试"));
            PersistSettingsWithoutReporting(retryable);
            return;
        }

        var ready = retryable with
        {
            Phase = MainViewPhase.Ready,
            StatusMessage = $"已导出 {retryable.ExportCount} 个 FIT 文件",
        };
        Publish(ready);
        PersistSettings(ready);
    }

    internal void UpdateActivityParameters(
        DateTime startTime,
        double paceSecondsPerKilometer,
        int restingHeartRate,
        int maximumHeartRate,
        int lapCount)
    {
        if (State.IsBusy)
        {
            return;
        }

        if (!double.IsFinite(paceSecondsPerKilometer)
            || paceSecondsPerKilometer is < 60.0 or > 3_600.0
            || restingHeartRate is < 30 or > 120
            || maximumHeartRate is < 100 or > 220
            || maximumHeartRate <= restingHeartRate
            || lapCount is < 1 or > 100)
        {
            PublishError(State, "活动参数超出允许范围");
            return;
        }

        var normalizedStartTime = TrimToMinute(startTime);
        var changed = State.StartTime != normalizedStartTime
            || State.PaceSecondsPerKilometer != paceSecondsPerKilometer
            || State.RestingHeartRate != restingHeartRate
            || State.MaximumHeartRate != maximumHeartRate
            || State.LapCount != lapCount;
        if (!changed)
        {
            return;
        }

        var updated = State with
        {
            StartTime = normalizedStartTime,
            PaceSecondsPerKilometer = paceSecondsPerKilometer,
            RestingHeartRate = restingHeartRate,
            MaximumHeartRate = maximumHeartRate,
            LapCount = lapCount,
            PreviewSeed = null,
            PreviewModel = null,
            StatusMessage = "活动参数已更新，请重新预览",
        };
        Publish(updated);
        PersistSettings(updated);
    }

    internal void UpdateExportOptions(string? exportDirectory, int exportCount)
    {
        if (State.IsBusy)
        {
            return;
        }

        if (exportCount is < 1 or > MaximumExportCount)
        {
            PublishError(State, "导出份数必须在 1 到 20 之间");
            return;
        }

        var normalizedDirectory = string.IsNullOrWhiteSpace(exportDirectory)
            ? null
            : exportDirectory.Trim();
        if (State.ExportDirectory == normalizedDirectory && State.ExportCount == exportCount)
        {
            return;
        }

        var updated = State with
        {
            ExportDirectory = normalizedDirectory,
            ExportCount = exportCount,
            StatusMessage = normalizedDirectory is null
                ? "请选择 FIT 文件导出目录"
                : $"导出目录已设置为：{normalizedDirectory}",
        };
        Publish(updated);
        PersistSettings(updated);
    }

    internal void ChangeMapProvider(MapProviderDefinition provider)
    {
        ArgumentNullException.ThrowIfNull(provider);
        if (State.IsBusy || State.ActiveMapProvider == provider)
        {
            return;
        }

        var previous = State;
        try
        {
            _map.SwitchProvider(provider);
            Interlocked.Increment(ref _searchGeneration);
            var providers = EnsureProviderAvailable(previous.AvailableMapProviders, provider);
            var updated = previous with
            {
                ActiveMapProvider = provider,
                AvailableMapProviders = providers,
                StatusMessage = $"地图源已切换为：{provider.DisplayName}",
            };
            Publish(updated);
            PersistSettings(updated);
        }
        catch (Exception exception)
        {
            PublishError(previous, UserMessage(exception, "无法切换到所选地图源"));
        }
    }

    internal void ZoomToRoute()
    {
        try
        {
            _map.ZoomToRouteOrDefault();
        }
        catch (Exception exception)
        {
            PublishError(State, UserMessage(exception, "无法定位到当前轨迹"));
        }
    }

    internal async Task SearchAsync(
        string query,
        CultureInfo uiCulture,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(uiCulture);
        var generation = Interlocked.Increment(ref _searchGeneration);
        var normalizedQuery = query?.Trim();
        if (string.IsNullOrWhiteSpace(normalizedQuery))
        {
            PublishError(State, "请输入要搜索的地点");
            return;
        }

        var provider = State.ActiveMapProvider;
        Publish(State with { StatusMessage = "正在搜索地点..." });
        try
        {
            var results = await _searchService.SearchAsync(
                normalizedQuery,
                provider,
                uiCulture,
                cancellationToken);
            if (!IsCurrentSearch(generation, provider))
            {
                return;
            }

            if (results.Count == 0)
            {
                Publish(State with { StatusMessage = "没有找到匹配地点" });
                return;
            }

            var first = results[0];
            _map.CenterOnWgs84(first.Coordinate);
            if (!IsCurrentSearch(generation, provider))
            {
                return;
            }

            Publish(State with
            {
                StatusMessage = $"已定位到：{first.DisplayLabel} · {first.Attribution}",
            });
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            if (IsCurrentSearch(generation, provider))
            {
                Publish(State with { StatusMessage = "地点搜索已取消" });
            }
        }
        catch (OperationCanceledException exception)
        {
            if (IsCurrentSearch(generation, provider))
            {
                PublishError(State, UserMessage(exception, "地点搜索超时，请稍后重试"));
            }
        }
        catch (Exception exception)
        {
            if (IsCurrentSearch(generation, provider))
            {
                PublishError(State, UserMessage(exception, "地点搜索暂时不可用"));
            }
        }
    }

    private void ApplyRouteMutation(MainViewState previous, string action)
    {
        var route = CopyRoute(_map.Wgs84RoutePoints);
        var changed = !RoutesEqual(previous.RoutePoints, route);
        var updated = previous with
        {
            Phase = PhaseForRoute(route),
            RoutePoints = route,
            PreviewSeed = changed ? null : previous.PreviewSeed,
            PreviewModel = changed ? null : previous.PreviewModel,
            StatusMessage = RouteStatus(action, route),
        };
        Publish(updated);
        PersistRouteAndSettings(updated);
    }

    private void PersistRouteAndSettings(MainViewState usableState)
    {
        try
        {
            _settingsService.SaveLastRoute(new SavedRoute
            {
                Wgs84Points = usableState.RoutePoints,
            });
            SaveSettingsCore(usableState);
        }
        catch (Exception exception)
        {
            PublishError(
                usableState,
                UserMessage(exception, "轨迹已保留，但无法保存到本地"));
        }
    }

    private void PersistSettings(MainViewState usableState)
    {
        try
        {
            SaveSettingsCore(usableState);
        }
        catch (Exception exception)
        {
            PublishError(
                usableState,
                UserMessage(exception, "当前设置无法保存到本地"));
        }
    }

    private void PersistSettingsWithoutReporting(MainViewState state)
    {
        try
        {
            SaveSettingsCore(state);
        }
        catch (Exception)
        {
            // The original operation error remains the actionable message.
        }
    }

    private void SaveSettingsCore(MainViewState state)
    {
        var updated = CreateSettingsSnapshot(state, _persistedSettings.MapViewport);
        _settingsService.SaveSettings(updated);
        _persistedSettings = updated;
    }

    private AppSettings CreateSettingsSnapshot(
        MainViewState state,
        MapViewportSettings viewport) =>
        _persistedSettings with
        {
            ActiveMapProviderId = state.ActiveMapProvider.Id,
            CustomMapProviders = state.AvailableMapProviders
                .Where(provider => !provider.IsBuiltIn)
                .ToArray(),
            PaceSecondsPerKilometer = state.PaceSecondsPerKilometer,
            RestingHeartRate = state.RestingHeartRate,
            MaximumHeartRate = state.MaximumHeartRate,
            LapCount = state.LapCount,
            ExportCount = state.ExportCount,
            ExportDirectory = state.ExportDirectory,
            PreviewSeed = state.PreviewSeed,
            MapViewport = viewport,
        };

    private static ActivityInput CreateActivityInput(MainViewState state) => new(
        state.StartTime,
        state.RoutePoints
            .Select(point => new ActivityRoutePoint(point.Latitude, point.Longitude))
            .ToArray(),
        state.PaceSecondsPerKilometer,
        state.RestingHeartRate,
        state.MaximumHeartRate,
        state.LapCount);

    private static IReadOnlyList<MapProviderDefinition> BuildProviderList(AppSettings settings)
    {
        var providers = new List<MapProviderDefinition>(MapProviderDefinition.BuiltIns);
        foreach (var custom in settings.CustomMapProviders)
        {
            if (providers.All(existing => !string.Equals(
                    existing.Id,
                    custom.Id,
                    StringComparison.Ordinal)))
            {
                providers.Add(custom);
            }
        }

        return Array.AsReadOnly(providers.ToArray());
    }

    private static IReadOnlyList<MapProviderDefinition> EnsureProviderAvailable(
        IReadOnlyList<MapProviderDefinition> providers,
        MapProviderDefinition provider)
    {
        var existingIndex = -1;
        for (var index = 0; index < providers.Count; index++)
        {
            if (string.Equals(
                    providers[index].Id,
                    provider.Id,
                    StringComparison.Ordinal))
            {
                existingIndex = index;
                break;
            }
        }

        if (existingIndex < 0)
        {
            return Array.AsReadOnly(providers.Append(provider).ToArray());
        }

        if (providers[existingIndex] == provider)
        {
            return providers;
        }

        var updated = providers.ToArray();
        updated[existingIndex] = provider;
        return Array.AsReadOnly(updated);
    }

    private bool IsCurrentSearch(
        long generation,
        MapProviderDefinition provider) =>
        Volatile.Read(ref _searchGeneration) == generation
        && State.ActiveMapProvider == provider;

    private static MapProviderDefinition ResolveActiveProvider(
        string providerId,
        IReadOnlyList<MapProviderDefinition> providers) =>
        providers.FirstOrDefault(provider => string.Equals(
            provider.Id,
            providerId,
            StringComparison.Ordinal))
        ?? MapProviderDefinition.OpenStreetMap;

    private static IReadOnlyList<GeoCoordinate> CopyRoute(
        IReadOnlyList<GeoCoordinate> route) =>
        Array.AsReadOnly(route.ToArray());

    private static IReadOnlyList<GeoCoordinate> EmptyRoute() =>
        Array.AsReadOnly(Array.Empty<GeoCoordinate>());

    private static bool RoutesEqual(
        IReadOnlyList<GeoCoordinate> left,
        IReadOnlyList<GeoCoordinate> right)
    {
        if (left.Count != right.Count)
        {
            return false;
        }

        for (var index = 0; index < left.Count; index++)
        {
            if (left[index] != right[index])
            {
                return false;
            }
        }

        return true;
    }

    private static MainViewPhase PhaseForRoute(IReadOnlyList<GeoCoordinate> route) =>
        route.Count switch
        {
            0 => MainViewPhase.Empty,
            < MinimumRoutePointCount => MainViewPhase.Drawing,
            _ => MainViewPhase.Ready,
        };

    private static string InitialStatus(IReadOnlyList<GeoCoordinate> route) =>
        route.Count == 0
            ? "在右侧地图按住左键拖动绘制轨迹。"
            : RouteStatus("已恢复最近轨迹", route);

    private static string RouteStatus(
        string action,
        IReadOnlyList<GeoCoordinate> route) =>
        route.Count < MinimumRoutePointCount
            ? $"{action}。至少需要 2 个轨迹点才能预览或生成 FIT。"
            : $"{action}：当前 {route.Count} 个轨迹点，请生成预览查看距离和时间。";

    private static string PreviewStatus(ActivityModelDto model) =>
        $"预览成功：{model.Samples.Count} 个采样点，距离 "
        + $"{model.TotalDistanceCm / 100_000.0:0.00} 公里，预计 "
        + $"{model.TotalDurationMs / 60_000.0:0.0} 分钟。";

    private static DateTime TrimToMinute(DateTime value) =>
        new(
            value.Year,
            value.Month,
            value.Day,
            value.Hour,
            value.Minute,
            0,
            value.Kind);

    private static ulong CreateBatchSeed()
    {
        Span<byte> bytes = stackalloc byte[sizeof(ulong)];
        RandomNumberGenerator.Fill(bytes);
        return BitConverter.ToUInt64(bytes);
    }

    private static string UserMessage(Exception exception, string fallback) =>
        exception switch
        {
            NativeCoreException native => native.Message,
            ExportServiceException export => export.Message,
            SettingsServiceException settings => settings.Message,
            SearchServiceException search => search.Message,
            _ => fallback,
        };

    private void PublishError(MainViewState usableState, string message)
    {
        Publish(usableState with
        {
            Phase = MainViewPhase.Error,
            StatusMessage = message,
        });
        Publish(usableState with { StatusMessage = message });
    }

    private void Publish(MainViewState state)
    {
        State = state;
        StateChanged?.Invoke(state);
    }
}
