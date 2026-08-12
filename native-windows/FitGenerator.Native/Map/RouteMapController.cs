using System.Net.Http;
using BruTile.Predefined;
using BruTile.Web;
using Mapsui;
using Mapsui.Extensions;
using Mapsui.Layers;
using Mapsui.Nts;
using Mapsui.Providers;
using Mapsui.Styles;
using Mapsui.Tiling;
using Mapsui.Tiling.Layers;
using Mapsui.UI.WindowsForms;
using NetTopologySuite.Geometries;
using FitGenerator.Native.Services;
using MPoint = Mapsui.MPoint;
using NtsPoint = NetTopologySuite.Geometries.Point;
using StyleBrush = Mapsui.Styles.Brush;
using StylePen = Mapsui.Styles.Pen;

namespace FitGenerator.Native.Map;

internal sealed class RouteDrawingCompletedEventArgs(
    IReadOnlyList<GeoCoordinate> wgs84Points) : EventArgs
{
    internal IReadOnlyList<GeoCoordinate> Wgs84Points { get; } = wgs84Points;
}

internal sealed class BaseMapAvailabilityChangedEventArgs(
    string providerId,
    bool isAvailable) : EventArgs
{
    internal string ProviderId { get; } = providerId;

    internal bool IsAvailable { get; } = isAvailable;
}

internal interface IRouteMapController
{
    IReadOnlyList<GeoCoordinate> Wgs84RoutePoints { get; }

    void StartDrawing();

    void StopDrawing();

    void SetWgs84Route(IReadOnlyList<GeoCoordinate> points);

    void UndoPoint();

    void ClearRoute();

    void SwitchProvider(MapProviderDefinition provider);

    void RestoreViewport(MapViewportSettings viewport);

    MapViewportSettings CaptureViewport();

    void ZoomToRouteOrDefault();

    void CenterOnWgs84(GeoCoordinate coordinate);
}

internal sealed class RouteMapController : IRouteMapController, IDisposable
{
    private const double DefaultLatitude = 39.9042;
    private const double DefaultLongitude = 116.4074;
    private const double MinimumPointSpacingMeters = 8.0;
    private const int MaximumRoutePoints = 50_000;
    private const int MaximumSupportedZoom = 30;
    private const int TileFailureThreshold = 6;
    private const long TileFailureWindowMilliseconds = 15_000;
    private const string UserAgent =
        "fitGenerator/1.0 (+https://github.com/Alpha-Auxiliary/fitGenerator)";
    private static readonly int[] OnlineRetryDelaysMilliseconds =
        [30_000, 120_000, 300_000];

    private readonly MapControl _mapControl;
    private readonly System.Windows.Forms.Timer _onlineRetryTimer = new();
    private readonly object _tileHealthLock = new();
    private readonly List<GeoCoordinate> _wgs84RoutePoints = [];
    private readonly MemoryLayer _routeLayer = new("Route");
    private readonly MemoryLayer _pointLayer = new("Route points");
    private readonly MemoryLayer _playbackLayer = new("Playback marker");
    private ILayer? _baseMapLayer;
    private HttpClient? _baseMapHttpClient;
    private MapProviderDefinition _activeProvider = MapProviderDefinition.OpenStreetMap;
    private GeoCoordinate? _playbackMarkerWgs84;
    private bool _drawingEnabled;
    private bool _drawingStroke;
    private bool _drawingStrokeChanged;
    private long _providerGeneration;
    private long _tileFailureWindowStartedAt;
    private int _tileFailureCount;
    private int _onlineRetryAttempt;
    private bool _offlineBaseMap;
    private bool _awaitingOnlineConfirmation;
    private bool _fallbackScheduled;
    private bool _disposed;

    internal RouteMapController(MapControl mapControl)
    {
        ArgumentNullException.ThrowIfNull(mapControl);
        _mapControl = mapControl;
        _mapControl.Dock = DockStyle.Fill;
        _mapControl.Map = new Mapsui.Map();
        _mapControl.Map.BackColor = new Mapsui.Styles.Color(228, 233, 226);
        _onlineRetryTimer.Tick += OnOnlineRetryTimerTick;

        _routeLayer.Style = new VectorStyle
        {
            Line = new StylePen(new Mapsui.Styles.Color(209, 78, 57), 4),
        };
        _pointLayer.Style = new SymbolStyle
        {
            SymbolScale = 0.55,
            Fill = new StyleBrush(new Mapsui.Styles.Color(29, 111, 120)),
            Outline = new StylePen(new Mapsui.Styles.Color(255, 255, 255), 2),
        };
        _playbackLayer.Style = new SymbolStyle
        {
            SymbolScale = 0.8,
            Fill = new StyleBrush(new Mapsui.Styles.Color(209, 78, 57)),
            Outline = new StylePen(new Mapsui.Styles.Color(255, 255, 255), 3),
        };
        _mapControl.Map.Layers.Add(_routeLayer);
        _mapControl.Map.Layers.Add(_pointLayer);
        _mapControl.Map.Layers.Add(_playbackLayer);

        SwitchProvider(MapProviderDefinition.OpenStreetMap);
        RefreshRouteLayers();
        ZoomToDefault();

        _mapControl.MapPointerPressed += OnMapPointerPressed;
        _mapControl.MapPointerMoved += OnMapPointerMoved;
        _mapControl.MapPointerReleased += OnMapPointerReleased;
    }

    internal event EventHandler<RouteDrawingCompletedEventArgs>? DrawingCompleted;

    internal event EventHandler<BaseMapAvailabilityChangedEventArgs>?
        BaseMapAvailabilityChanged;

    public IReadOnlyList<GeoCoordinate> Wgs84RoutePoints =>
        Array.AsReadOnly(_wgs84RoutePoints.ToArray());

    public void StartDrawing()
    {
        ThrowIfDisposed();
        _drawingEnabled = true;
        if (_mapControl.Map is not null)
        {
            _mapControl.Map.Navigator.PanLock = true;
        }
    }

    public void StopDrawing()
    {
        ThrowIfDisposed();
        _drawingEnabled = false;
        _drawingStroke = false;
        _drawingStrokeChanged = false;
        if (_mapControl.Map is not null)
        {
            _mapControl.Map.Navigator.PanLock = false;
        }
    }

    public void SetWgs84Route(IReadOnlyList<GeoCoordinate> points)
    {
        ThrowIfDisposed();
        ArgumentNullException.ThrowIfNull(points);
        if (points.Count > MaximumRoutePoints)
        {
            throw new ArgumentOutOfRangeException(
                nameof(points),
                "轨迹点数量不能超过 50000");
        }

        var validated = new GeoCoordinate[points.Count];
        for (var index = 0; index < points.Count; index++)
        {
            validated[index] = CoordinateTransforms.ToWgs84(
                points[index],
                CoordinateSystem.Wgs84);
        }

        _wgs84RoutePoints.Clear();
        _wgs84RoutePoints.AddRange(validated);
        _playbackMarkerWgs84 = null;
        RefreshRouteLayers();
    }

    public void UndoPoint()
    {
        ThrowIfDisposed();
        if (_wgs84RoutePoints.Count == 0)
        {
            return;
        }

        _wgs84RoutePoints.RemoveAt(_wgs84RoutePoints.Count - 1);
        _playbackMarkerWgs84 = null;
        RefreshRouteLayers();
        ZoomToRouteOrDefault();
    }

    public void ClearRoute()
    {
        ThrowIfDisposed();
        _wgs84RoutePoints.Clear();
        _playbackMarkerWgs84 = null;
        RefreshRouteLayers();
        ZoomToDefault();
    }

    public void SwitchProvider(MapProviderDefinition provider)
    {
        SwitchProvider(provider, isAutomaticRetry: false);
    }

    private void SwitchProvider(
        MapProviderDefinition provider,
        bool isAutomaticRetry)
    {
        ThrowIfDisposed();
        ArgumentNullException.ThrowIfNull(provider);
        if (_mapControl.Map is null)
        {
            throw new InvalidOperationException("地图尚未初始化");
        }

        var previousLayer = _baseMapLayer;
        var previousHttpClient = _baseMapHttpClient;
        MapViewportSettings? retainedWgs84Viewport = null;
        if (previousLayer is not null)
        {
            try
            {
                retainedWgs84Viewport = CaptureViewport();
            }
            catch (Exception)
            {
                // An uninitialized viewport has nothing meaningful to retain.
            }
        }

        var generation = NextProviderGeneration();
        var (newBaseLayer, newHttpClient) = CreateBaseMapLayer(provider, generation);
        if (previousLayer is not null)
        {
            _mapControl.Map.Layers.Remove(previousLayer);
        }

        _activeProvider = provider;
        _baseMapLayer = newBaseLayer;
        _baseMapHttpClient = newHttpClient;
        lock (_tileHealthLock)
        {
            _providerGeneration = generation;
            _offlineBaseMap = false;
            _awaitingOnlineConfirmation = true;
            _fallbackScheduled = false;
            ResetTileFailureWindowLocked();
            if (!isAutomaticRetry)
            {
                _onlineRetryAttempt = 0;
            }
        }
        _onlineRetryTimer.Stop();
        _mapControl.Map.Layers.Insert(0, newBaseLayer);
        RefreshRouteLayers();
        if (retainedWgs84Viewport is not null)
        {
            RestoreViewport(retainedWgs84Viewport);
        }

        (previousLayer as IDisposable)?.Dispose();
        previousHttpClient?.Dispose();
    }

    public void RestoreViewport(MapViewportSettings viewport)
    {
        ThrowIfDisposed();
        ValidateViewport(viewport);
        if (_mapControl.Map is null)
        {
            throw new InvalidOperationException("地图尚未初始化");
        }

        var centerWgs84 = new GeoCoordinate(
            viewport.CenterWgs84Latitude,
            viewport.CenterWgs84Longitude);
        _mapControl.Map.Navigator.CenterOnAndZoomTo(
            ToWorldPoint(centerWgs84),
            viewport.Zoom);
    }

    public MapViewportSettings CaptureViewport()
    {
        ThrowIfDisposed();
        if (_mapControl.Map is null)
        {
            throw new InvalidOperationException("地图尚未初始化");
        }

        var viewport = _mapControl.Map.Navigator.Viewport;
        if (!double.IsFinite(viewport.CenterX)
            || !double.IsFinite(viewport.CenterY)
            || !double.IsFinite(viewport.Resolution)
            || viewport.Resolution <= 0.0)
        {
            throw new InvalidOperationException("当前地图视口无效");
        }

        var providerCenter = MercatorToCoordinate(
            viewport.CenterX,
            viewport.CenterY);
        var centerWgs84 = CoordinateTransforms.ToWgs84(
            providerCenter,
            _activeProvider.CoordinateSystem);
        return new MapViewportSettings(
            centerWgs84.Latitude,
            centerWgs84.Longitude,
            viewport.Resolution);
    }

    public void ZoomToRouteOrDefault()
    {
        ThrowIfDisposed();
        if (_mapControl.Map is null)
        {
            return;
        }

        if (_wgs84RoutePoints.Count == 0)
        {
            ZoomToDefault();
            return;
        }

        if (_wgs84RoutePoints.Count == 1)
        {
            var onlyPoint = ToWorldPoint(_wgs84RoutePoints[0]);
            _mapControl.Map.Navigator.CenterOnAndZoomTo(onlyPoint, 9, 250);
            return;
        }

        var worldPoints = _wgs84RoutePoints.Select(ToWorldPoint).ToArray();
        var minX = worldPoints.Min(point => point.X);
        var minY = worldPoints.Min(point => point.Y);
        var maxX = worldPoints.Max(point => point.X);
        var maxY = worldPoints.Max(point => point.Y);
        var padding = Math.Max(Math.Max(maxX - minX, maxY - minY) * 0.12, 80);
        var bounds = new MRect(
            minX - padding,
            minY - padding,
            maxX + padding,
            maxY + padding);
        _mapControl.Map.Navigator.ZoomToBox(bounds, MBoxFit.Fit, 250);
    }

    public void CenterOnWgs84(GeoCoordinate coordinate)
    {
        ThrowIfDisposed();
        var validated = CoordinateTransforms.ToWgs84(
            coordinate,
            CoordinateSystem.Wgs84);
        _mapControl.Map?.Navigator.CenterOnAndZoomTo(
            ToWorldPoint(validated),
            8,
            350);
    }

    internal void SetPlaybackMarker(
        int latitudeSemicircles,
        int longitudeSemicircles)
    {
        ThrowIfDisposed();
        var coordinate = new GeoCoordinate(
            SemicirclesToDegrees(latitudeSemicircles),
            SemicirclesToDegrees(longitudeSemicircles));
        try
        {
            coordinate = CoordinateTransforms.ToWgs84(
                coordinate,
                CoordinateSystem.Wgs84);
        }
        catch (ArgumentOutOfRangeException)
        {
            ClearPlaybackMarker();
            return;
        }

        if (_playbackMarkerWgs84 == coordinate)
        {
            return;
        }

        _playbackMarkerWgs84 = coordinate;
        RefreshPlaybackLayer();
    }

    internal void ClearPlaybackMarker()
    {
        ThrowIfDisposed();
        if (_playbackMarkerWgs84 is null)
        {
            return;
        }

        _playbackMarkerWgs84 = null;
        RefreshPlaybackLayer();
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _drawingEnabled = false;
        _drawingStroke = false;
        _drawingStrokeChanged = false;
        _playbackMarkerWgs84 = null;
        _mapControl.MapPointerPressed -= OnMapPointerPressed;
        _mapControl.MapPointerMoved -= OnMapPointerMoved;
        _mapControl.MapPointerReleased -= OnMapPointerReleased;
        if (_mapControl.Map is not null)
        {
            _mapControl.Map.Navigator.PanLock = false;
        }

        (_baseMapLayer as IDisposable)?.Dispose();
        _baseMapLayer = null;
        _baseMapHttpClient?.Dispose();
        _baseMapHttpClient = null;
        _onlineRetryTimer.Stop();
        _onlineRetryTimer.Tick -= OnOnlineRetryTimerTick;
        _onlineRetryTimer.Dispose();
        lock (_tileHealthLock)
        {
            _providerGeneration += 1;
            _offlineBaseMap = true;
            _fallbackScheduled = false;
            _awaitingOnlineConfirmation = false;
            ResetTileFailureWindowLocked();
        }
        DrawingCompleted = null;
        BaseMapAvailabilityChanged = null;
    }

    private void OnMapPointerPressed(object? sender, MapEventArgs eventArgs) =>
        HandlePointerPressed(eventArgs.WorldPosition);

    private void OnMapPointerMoved(object? sender, MapEventArgs eventArgs) =>
        HandlePointerMoved(eventArgs.WorldPosition);

    private void OnMapPointerReleased(object? sender, MapEventArgs eventArgs) =>
        HandlePointerReleased();

    private void HandlePointerPressed(MPoint worldPosition)
    {
        if (!_drawingEnabled || _disposed)
        {
            return;
        }

        _drawingStroke = true;
        _drawingStrokeChanged = AddWorldPoint(worldPosition);
    }

    private void HandlePointerMoved(MPoint worldPosition)
    {
        if (!_drawingEnabled || !_drawingStroke || _disposed)
        {
            return;
        }

        _drawingStrokeChanged |= AddWorldPoint(worldPosition);
    }

    private void HandlePointerReleased()
    {
        if (!_drawingStroke || _disposed)
        {
            return;
        }

        _drawingStroke = false;
        var routeChanged = _drawingStrokeChanged;
        _drawingStrokeChanged = false;
        if (routeChanged)
        {
            DrawingCompleted?.Invoke(
                this,
                new RouteDrawingCompletedEventArgs(Wgs84RoutePoints));
        }
    }

    private bool AddWorldPoint(MPoint worldPosition)
    {
        if (_wgs84RoutePoints.Count >= MaximumRoutePoints)
        {
            return false;
        }

        var providerCoordinate = MercatorToCoordinate(worldPosition.X, worldPosition.Y);
        var wgs84 = CoordinateTransforms.ToWgs84(
            providerCoordinate,
            _activeProvider.CoordinateSystem);
        if (_wgs84RoutePoints.Count > 0
            && CoordinateTransforms.DistanceMeters(_wgs84RoutePoints[^1], wgs84)
                < MinimumPointSpacingMeters)
        {
            return false;
        }

        _wgs84RoutePoints.Add(wgs84);
        _playbackMarkerWgs84 = null;
        RefreshRouteLayers();
        return true;
    }

    private void RefreshRouteLayers()
    {
        if (_mapControl.Map is null)
        {
            return;
        }

        _routeLayer.Features = BuildRouteFeatures();
        _pointLayer.Features = BuildPointFeatures();
        _playbackLayer.Features = BuildPlaybackFeatures();
        _mapControl.RefreshData(ChangeType.Discrete);
        _mapControl.ForceUpdate();
    }

    private void RefreshPlaybackLayer()
    {
        if (_mapControl.Map is null)
        {
            return;
        }

        _playbackLayer.Features = BuildPlaybackFeatures();
        _mapControl.RefreshData(ChangeType.Discrete);
        _mapControl.ForceUpdate();
    }

    private IEnumerable<IFeature> BuildRouteFeatures()
    {
        if (_wgs84RoutePoints.Count < 2)
        {
            yield break;
        }

        var coordinates = _wgs84RoutePoints
            .Select(point =>
            {
                var world = ToWorldPoint(point);
                return new Coordinate(world.X, world.Y);
            })
            .ToArray();
        yield return new GeometryFeature
        {
            Geometry = new LineString(coordinates),
        };
    }

    private IEnumerable<IFeature> BuildPointFeatures()
    {
        foreach (var point in _wgs84RoutePoints)
        {
            var world = ToWorldPoint(point);
            yield return new GeometryFeature
            {
                Geometry = new NtsPoint(world.X, world.Y),
            };
        }
    }

    private IEnumerable<IFeature> BuildPlaybackFeatures()
    {
        if (_playbackMarkerWgs84 is not { } point)
        {
            yield break;
        }

        var world = ToWorldPoint(point);
        yield return new GeometryFeature
        {
            Geometry = new NtsPoint(world.X, world.Y),
        };
    }

    private (ILayer Layer, HttpClient HttpClient) CreateBaseMapLayer(
        MapProviderDefinition provider,
        long generation)
    {
        ValidateProvider(provider);
        var tileUrl = PrepareTileUrl(provider);
        var subdomains = provider.Id == MapProviderDefinition.CartoLight.Id
            ? new[] { "a", "b", "c", "d" }
            : new[] { "a", "b", "c" };
        var tileSource = new HttpTileSource(
            new GlobalSphericalMercator(0, provider.MaximumZoom, provider.DisplayName),
            tileUrl,
            subdomains,
            name: provider.DisplayName,
            configureHttpRequestMessage: request => ConfigureTileRequest(request, provider));
        var httpClient = new HttpClient(
            new TileAvailabilityHandler(
                CreateTileHttpHandler(),
                onAvailable: () => ReportTileAvailable(generation),
                onUnavailable: () => ReportTileUnavailable(generation)),
            disposeHandler: true);
        try
        {
            var layer = new TileLayer(tileSource, httpClient: httpClient)
            {
                Name = provider.DisplayName,
            };
            return (layer, httpClient);
        }
        catch
        {
            httpClient.Dispose();
            throw;
        }
    }

    internal static HttpClientHandler CreateTileHttpHandler() => new()
    {
        AllowAutoRedirect = false,
        UseCookies = false,
    };

    private long NextProviderGeneration()
    {
        lock (_tileHealthLock)
        {
            return _providerGeneration + 1;
        }
    }

    private void ReportTileAvailable(long generation)
    {
        var notify = false;
        lock (_tileHealthLock)
        {
            if (_disposed || generation != _providerGeneration || _offlineBaseMap)
            {
                return;
            }

            ResetTileFailureWindowLocked();
            _onlineRetryAttempt = 0;
            if (_awaitingOnlineConfirmation)
            {
                _awaitingOnlineConfirmation = false;
                notify = true;
            }
        }

        if (notify)
        {
            PostToMapControl(() => BaseMapAvailabilityChanged?.Invoke(
                this,
                new BaseMapAvailabilityChangedEventArgs(_activeProvider.Id, true)));
        }
    }

    private void ReportTileUnavailable(long generation)
    {
        var activateFallback = false;
        lock (_tileHealthLock)
        {
            if (
                _disposed ||
                generation != _providerGeneration ||
                _offlineBaseMap ||
                _fallbackScheduled)
            {
                return;
            }

            var now = Environment.TickCount64;
            if (
                _tileFailureWindowStartedAt == 0 ||
                now - _tileFailureWindowStartedAt > TileFailureWindowMilliseconds)
            {
                _tileFailureWindowStartedAt = now;
                _tileFailureCount = 0;
            }

            _tileFailureCount += 1;
            if (_tileFailureCount >= TileFailureThreshold)
            {
                _fallbackScheduled = true;
                activateFallback = true;
            }
        }

        if (activateFallback)
        {
            PostToMapControl(() => ActivateOfflineBaseMap(generation));
        }
    }

    private void ActivateOfflineBaseMap(long generation)
    {
        if (_disposed || _mapControl.Map is null)
        {
            return;
        }

        lock (_tileHealthLock)
        {
            if (generation != _providerGeneration || _offlineBaseMap)
            {
                _fallbackScheduled = false;
                return;
            }

            _offlineBaseMap = true;
            _awaitingOnlineConfirmation = false;
            _fallbackScheduled = false;
            _onlineRetryAttempt = Math.Min(
                _onlineRetryAttempt + 1,
                OnlineRetryDelaysMilliseconds.Length);
            ResetTileFailureWindowLocked();
        }

        var unavailableLayer = _baseMapLayer;
        var unavailableClient = _baseMapHttpClient;
        if (unavailableLayer is not null)
        {
            _mapControl.Map.Layers.Remove(unavailableLayer);
        }
        _baseMapLayer = null;
        _baseMapHttpClient = null;
        (unavailableLayer as IDisposable)?.Dispose();
        unavailableClient?.Dispose();
        _mapControl.RefreshData(ChangeType.Discrete);

        _onlineRetryTimer.Stop();
        _onlineRetryTimer.Interval = OnlineRetryDelaysMilliseconds[
            Math.Max(0, _onlineRetryAttempt - 1)];
        _onlineRetryTimer.Start();
        BaseMapAvailabilityChanged?.Invoke(
            this,
            new BaseMapAvailabilityChangedEventArgs(_activeProvider.Id, false));
    }

    private void OnOnlineRetryTimerTick(object? sender, EventArgs eventArgs)
    {
        _onlineRetryTimer.Stop();
        if (_disposed || !_offlineBaseMap)
        {
            return;
        }

        try
        {
            SwitchProvider(_activeProvider, isAutomaticRetry: true);
        }
        catch (Exception)
        {
            lock (_tileHealthLock)
            {
                _offlineBaseMap = true;
                _onlineRetryAttempt = Math.Min(
                    _onlineRetryAttempt + 1,
                    OnlineRetryDelaysMilliseconds.Length);
            }
            _onlineRetryTimer.Interval = OnlineRetryDelaysMilliseconds[
                Math.Max(0, _onlineRetryAttempt - 1)];
            _onlineRetryTimer.Start();
        }
    }

    private void PostToMapControl(Action action)
    {
        if (_disposed || _mapControl.IsDisposed || !_mapControl.IsHandleCreated)
        {
            lock (_tileHealthLock)
            {
                _fallbackScheduled = false;
            }
            return;
        }

        try
        {
            _mapControl.BeginInvoke(action);
        }
        catch (InvalidOperationException) when (
            _disposed || _mapControl.IsDisposed || !_mapControl.IsHandleCreated)
        {
            lock (_tileHealthLock)
            {
                _fallbackScheduled = false;
            }
        }
    }

    private void ResetTileFailureWindowLocked()
    {
        _tileFailureWindowStartedAt = 0;
        _tileFailureCount = 0;
    }

    private static void ValidateProvider(MapProviderDefinition provider)
    {
        if (string.IsNullOrWhiteSpace(provider.XyzUrlTemplate)
            || string.IsNullOrWhiteSpace(provider.DisplayName)
            || provider.MaximumZoom is < 0 or > MaximumSupportedZoom
            || !Enum.IsDefined(provider.CoordinateSystem)
            || !Enum.IsDefined(provider.KeyPlacement))
        {
            throw new InvalidOperationException("地图源配置无效");
        }

        var testUrl = provider.XyzUrlTemplate
            .Replace("{z}", "0", StringComparison.OrdinalIgnoreCase)
            .Replace("{x}", "0", StringComparison.OrdinalIgnoreCase)
            .Replace("{y}", "0", StringComparison.OrdinalIgnoreCase)
            .Replace("{s}", "a", StringComparison.OrdinalIgnoreCase)
            .Replace("{key}", "key", StringComparison.OrdinalIgnoreCase);
        if (!Uri.TryCreate(testUrl, UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
        {
            throw new InvalidOperationException("地图源 URL 无效");
        }

        if (provider.KeyPlacement != ProviderKeyPlacement.None
            && string.IsNullOrWhiteSpace(provider.ApiKey))
        {
            throw new InvalidOperationException("当前地图源需要 API Key，但尚未配置");
        }

        if (provider.KeyPlacement is ProviderKeyPlacement.Header
                or ProviderKeyPlacement.QueryParameter
            && string.IsNullOrWhiteSpace(provider.KeyName))
        {
            throw new InvalidOperationException("地图源 Key 名称无效");
        }
    }

    private static string PrepareTileUrl(MapProviderDefinition provider)
    {
        var url = provider.XyzUrlTemplate;
        if (provider.KeyPlacement == ProviderKeyPlacement.UrlTemplate)
        {
            if (!url.Contains("{key}", StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidOperationException("地图源 URL 缺少 {key} 占位符");
            }

            url = url.Replace(
                "{key}",
                Uri.EscapeDataString(provider.ApiKey!),
                StringComparison.OrdinalIgnoreCase);
        }
        else if (provider.KeyPlacement == ProviderKeyPlacement.QueryParameter)
        {
            var separator = url.Contains('?') ? "&" : "?";
            url = $"{url}{separator}{Uri.EscapeDataString(provider.KeyName!)}="
                + Uri.EscapeDataString(provider.ApiKey!);
        }

        return url;
    }

    internal static void ConfigureTileRequest(
        HttpRequestMessage request,
        MapProviderDefinition provider)
    {
        request.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
        if (provider.KeyPlacement == ProviderKeyPlacement.Header
            && !request.Headers.TryAddWithoutValidation(
                provider.KeyName!,
                provider.ApiKey!))
        {
            throw new InvalidOperationException("地图源 Header Key 配置无效");
        }
    }

    private MPoint ToWorldPoint(GeoCoordinate wgs84)
    {
        var providerCoordinate = CoordinateTransforms.FromWgs84(
            wgs84,
            _activeProvider.CoordinateSystem);
        return CoordinateToMercator(providerCoordinate);
    }

    private void ZoomToDefault()
    {
        if (_mapControl.Map is null)
        {
            return;
        }

        var defaultPoint = new GeoCoordinate(DefaultLatitude, DefaultLongitude);
        _mapControl.Map.Navigator.CenterOnAndZoomTo(ToWorldPoint(defaultPoint), 9);
    }

    private static void ValidateViewport(MapViewportSettings viewport)
    {
        ArgumentNullException.ThrowIfNull(viewport);
        if (!double.IsFinite(viewport.CenterWgs84Latitude)
            || viewport.CenterWgs84Latitude is < -90.0 or > 90.0
            || !double.IsFinite(viewport.CenterWgs84Longitude)
            || viewport.CenterWgs84Longitude is < -180.0 or > 180.0
            || !double.IsFinite(viewport.Zoom)
            || viewport.Zoom <= 0.0)
        {
            throw new ArgumentOutOfRangeException(
                nameof(viewport),
                "地图视口必须使用有效的 WGS84 中心与正分辨率");
        }
    }

    private static MPoint CoordinateToMercator(GeoCoordinate coordinate)
    {
        const double radius = 6_378_137.0;
        var longitudeRadians = DegreesToRadians(coordinate.Longitude);
        var clampedLatitude = Math.Clamp(coordinate.Latitude, -85.05112878, 85.05112878);
        var latitudeRadians = DegreesToRadians(clampedLatitude);
        return new MPoint(
            radius * longitudeRadians,
            radius * Math.Log(Math.Tan(Math.PI / 4.0 + latitudeRadians / 2.0)));
    }

    private static GeoCoordinate MercatorToCoordinate(double x, double y)
    {
        const double radius = 6_378_137.0;
        return new GeoCoordinate(
            RadiansToDegrees(2.0 * Math.Atan(Math.Exp(y / radius)) - Math.PI / 2.0),
            RadiansToDegrees(x / radius));
    }

    private static double DegreesToRadians(double degrees) =>
        degrees * Math.PI / 180.0;

    private static double RadiansToDegrees(double radians) =>
        radians * 180.0 / Math.PI;

    private static double SemicirclesToDegrees(int semicircles) =>
        semicircles * (180.0 / 2_147_483_648.0);

    private void ThrowIfDisposed()
    {
        if (_disposed)
        {
            throw new ObjectDisposedException(nameof(RouteMapController));
        }
    }
}

internal sealed class TileAvailabilityHandler(
    HttpMessageHandler innerHandler,
    Action onAvailable,
    Action onUnavailable) : DelegatingHandler(innerHandler)
{
    protected override async Task<HttpResponseMessage> SendAsync(
        HttpRequestMessage request,
        CancellationToken cancellationToken)
    {
        try
        {
            var response = await base.SendAsync(request, cancellationToken)
                .ConfigureAwait(false);
            if (IsConnectivityFailure(response.StatusCode))
            {
                onUnavailable();
            }
            else
            {
                onAvailable();
            }
            return response;
        }
        catch (HttpRequestException)
        {
            onUnavailable();
            throw;
        }
        catch (OperationCanceledException exception) when (
            exception.InnerException is TimeoutException)
        {
            onUnavailable();
            throw;
        }
    }

    private static bool IsConnectivityFailure(System.Net.HttpStatusCode statusCode) =>
        statusCode == System.Net.HttpStatusCode.RequestTimeout
        || statusCode == System.Net.HttpStatusCode.TooManyRequests
        || (int)statusCode >= 500;
}
