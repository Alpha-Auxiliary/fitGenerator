using System.Diagnostics;
using System.Globalization;
using System.Text.Json;
using FitGenerator.Native.Map;

namespace FitGenerator.Native.Services;

internal sealed record SearchResult(
    string DisplayLabel,
    string Attribution,
    GeoCoordinate Coordinate);

internal enum SearchErrorKind
{
    InvalidRequest,
    ProviderNotConfigured,
    Network,
    Timeout,
    InvalidResponse,
}

internal sealed class SearchServiceException : Exception
{
    internal SearchServiceException(SearchErrorKind kind, string message)
        : base(message)
    {
        Kind = kind;
    }

    internal SearchErrorKind Kind { get; }
}

internal sealed record ProviderConnectionResult(
    bool IsSuccess,
    string Message,
    SearchErrorKind? ErrorKind = null);

internal interface ISearchService
{
    Task<IReadOnlyList<SearchResult>> SearchAsync(
        string query,
        MapProviderDefinition provider,
        CultureInfo uiCulture,
        CancellationToken cancellationToken);

    Task<ProviderConnectionResult> TestConnectionAsync(
        MapProviderDefinition provider,
        CancellationToken cancellationToken);
}

internal interface IMonotonicClock
{
    long GetTimestamp();

    TimeSpan GetElapsedTime(long startingTimestamp, long endingTimestamp);

    Task DelayAsync(TimeSpan delay, CancellationToken cancellationToken);
}

internal sealed class SystemMonotonicClock : IMonotonicClock
{
    public long GetTimestamp() => Stopwatch.GetTimestamp();

    public TimeSpan GetElapsedTime(long startingTimestamp, long endingTimestamp) =>
        Stopwatch.GetElapsedTime(startingTimestamp, endingTimestamp);

    public Task DelayAsync(TimeSpan delay, CancellationToken cancellationToken) =>
        Task.Delay(delay, cancellationToken);
}

internal sealed class SearchService : ISearchService, IDisposable
{
    private const string UserAgent =
        "fitGenerator/1.0 (+https://github.com/Alpha-Auxiliary/fitGenerator)";
    private const string SuccessfulConnectionMessage = "连接成功";
    private const string MissingKeyMessage = "当前地图源需要 API Key，但尚未配置";
    private const string InvalidProviderMessage = "地图服务配置无效";
    private const string NetworkErrorMessage = "地点搜索服务暂时不可用";
    private const string TimeoutErrorMessage = "地点搜索请求超时";
    private const string InvalidResponseMessage = "地点搜索服务返回了无效数据";
    private const int MaximumResults = 5;
    private const int MaximumResponseBytes = 1024 * 1024;
    private static readonly TimeSpan MinimumNominatimInterval = TimeSpan.FromSeconds(1);
    private static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(10);

    private readonly HttpClient _httpClient;
    private readonly IMonotonicClock _clock;
    private readonly SemaphoreSlim _nominatimGate = new(1, 1);
    private readonly object _activeSearchGate = new();
    private CancellationTokenSource? _activeSearch;
    private long? _lastNominatimRequestTimestamp;
    private bool _disposed;

    internal SearchService()
        : this(CreateDefaultHttpHandler(), new SystemMonotonicClock())
    {
    }

    internal static HttpClientHandler CreateDefaultHttpHandler() => new()
    {
        // Provider keys are attached only to the explicitly configured origin.
        // Following a redirect could forward a custom Header key to another host.
        AllowAutoRedirect = false,
    };

    internal SearchService(HttpMessageHandler handler, IMonotonicClock clock)
    {
        ArgumentNullException.ThrowIfNull(handler);
        ArgumentNullException.ThrowIfNull(clock);

        _clock = clock;
        _httpClient = new HttpClient(handler, disposeHandler: true)
        {
            Timeout = RequestTimeout,
            MaxResponseContentBufferSize = MaximumResponseBytes,
        };
    }

    public async Task<IReadOnlyList<SearchResult>> SearchAsync(
        string query,
        MapProviderDefinition provider,
        CultureInfo uiCulture,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(provider);
        ArgumentNullException.ThrowIfNull(uiCulture);

        var normalizedQuery = query?.Trim();
        if (string.IsNullOrWhiteSpace(normalizedQuery))
        {
            throw new SearchServiceException(SearchErrorKind.InvalidRequest, "请输入要搜索的地点");
        }

        cancellationToken.ThrowIfCancellationRequested();
        using var searchCancellation = BeginSearch(cancellationToken);
        try
        {
            return await SearchCoreAsync(
                    normalizedQuery,
                    provider,
                    uiCulture,
                    searchCancellation.Token)
                .ConfigureAwait(false);
        }
        finally
        {
            EndSearch(searchCancellation);
        }
    }

    public async Task<ProviderConnectionResult> TestConnectionAsync(
        MapProviderDefinition provider,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(provider);
        ThrowIfDisposed();

        try
        {
            var requestUri = BuildConnectionUri(provider);
            using var request = new HttpRequestMessage(HttpMethod.Get, requestUri);
            AddStandardHeaders(request, CultureInfo.CurrentUICulture);
            AddHeaderKey(request, provider);
            using var response = await SendAsync(
                    request,
                    IsNominatimEndpoint(requestUri),
                    cancellationToken)
                .ConfigureAwait(false);

            return response.IsSuccessStatusCode
                ? ConnectionSuccess(provider)
                : ConnectionFailure(SearchErrorKind.Network, "连接失败：服务返回错误");
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (OperationCanceledException)
        {
            return ConnectionFailure(SearchErrorKind.Timeout, "连接失败：请求超时");
        }
        catch (SearchServiceException exception)
        {
            return ConnectionFailure(exception.Kind, exception.Message);
        }
        catch (Exception exception) when (exception is HttpRequestException or IOException)
        {
            return ConnectionFailure(SearchErrorKind.Network, "连接失败：服务暂时不可用");
        }
    }

    public void Dispose()
    {
        CancellationTokenSource? activeSearch;
        lock (_activeSearchGate)
        {
            if (_disposed)
            {
                return;
            }

            _disposed = true;
            activeSearch = _activeSearch;
            _activeSearch = null;
        }

        TryCancel(activeSearch);
        _httpClient.Dispose();
    }

    private async Task<IReadOnlyList<SearchResult>> SearchCoreAsync(
        string query,
        MapProviderDefinition provider,
        CultureInfo uiCulture,
        CancellationToken cancellationToken)
    {
        try
        {
            var requestUri = BuildSearchUri(query, provider);
            using var request = new HttpRequestMessage(HttpMethod.Get, requestUri);
            AddStandardHeaders(request, uiCulture);
            AddHeaderKey(request, provider);
            using var response = await SendAsync(
                    request,
                    IsNominatimEndpoint(requestUri),
                    cancellationToken)
                .ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                throw new SearchServiceException(SearchErrorKind.Network, NetworkErrorMessage);
            }

            await using var stream = await response.Content
                .ReadAsStreamAsync(cancellationToken)
                .ConfigureAwait(false);
            using var document = await JsonDocument.ParseAsync(
                    stream,
                    cancellationToken: cancellationToken)
                .ConfigureAwait(false);
            return ParseResults(document.RootElement, provider, requestUri);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (OperationCanceledException)
        {
            throw new SearchServiceException(SearchErrorKind.Timeout, TimeoutErrorMessage);
        }
        catch (SearchServiceException)
        {
            throw;
        }
        catch (JsonException)
        {
            throw new SearchServiceException(SearchErrorKind.InvalidResponse, InvalidResponseMessage);
        }
        catch (Exception exception) when (exception is HttpRequestException or IOException)
        {
            throw new SearchServiceException(SearchErrorKind.Network, NetworkErrorMessage);
        }
    }

    private static IReadOnlyList<SearchResult> ParseResults(
        JsonElement root,
        MapProviderDefinition provider,
        Uri requestUri)
    {
        if (root.ValueKind != JsonValueKind.Array)
        {
            throw new SearchServiceException(SearchErrorKind.InvalidResponse, InvalidResponseMessage);
        }

        var attribution = IsNominatimEndpoint(requestUri)
            ? MapProviderDefinition.OpenStreetMapAttribution
            : provider.Attribution;
        if (string.IsNullOrWhiteSpace(attribution))
        {
            throw new SearchServiceException(SearchErrorKind.ProviderNotConfigured, InvalidProviderMessage);
        }

        var results = new List<SearchResult>(MaximumResults);
        foreach (var item in root.EnumerateArray())
        {
            if (results.Count == MaximumResults)
            {
                break;
            }

            if (item.ValueKind != JsonValueKind.Object
                || !item.TryGetProperty("display_name", out var displayNameElement)
                || displayNameElement.ValueKind != JsonValueKind.String
                || string.IsNullOrWhiteSpace(displayNameElement.GetString())
                || !TryReadCoordinate(item, "lat", out var latitude)
                || !TryReadCoordinate(item, "lon", out var longitude)
                || latitude is < -90.0 or > 90.0
                || longitude is < -180.0 or > 180.0)
            {
                throw new SearchServiceException(SearchErrorKind.InvalidResponse, InvalidResponseMessage);
            }

            GeoCoordinate coordinate;
            try
            {
                coordinate = CoordinateTransforms.ToWgs84(
                    new GeoCoordinate(latitude, longitude),
                    provider.CoordinateSystem);
            }
            catch (ArgumentOutOfRangeException)
            {
                throw new SearchServiceException(
                    SearchErrorKind.ProviderNotConfigured,
                    InvalidProviderMessage);
            }

            results.Add(new SearchResult(
                displayNameElement.GetString()!,
                attribution,
                coordinate));
        }

        return results;
    }

    private async Task<HttpResponseMessage> SendAsync(
        HttpRequestMessage request,
        bool applyNominatimRateLimit,
        CancellationToken cancellationToken)
    {
        if (!applyNominatimRateLimit)
        {
            return await _httpClient.SendAsync(request, cancellationToken).ConfigureAwait(false);
        }

        await _nominatimGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (_lastNominatimRequestTimestamp is { } previousTimestamp)
            {
                var currentTimestamp = _clock.GetTimestamp();
                var elapsed = _clock.GetElapsedTime(previousTimestamp, currentTimestamp);
                if (elapsed < TimeSpan.Zero)
                {
                    elapsed = TimeSpan.Zero;
                }

                if (elapsed < MinimumNominatimInterval)
                {
                    await _clock.DelayAsync(
                            MinimumNominatimInterval - elapsed,
                            cancellationToken)
                        .ConfigureAwait(false);
                }
            }

            _lastNominatimRequestTimestamp = _clock.GetTimestamp();
            return await _httpClient.SendAsync(request, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _nominatimGate.Release();
        }
    }

    private CancellationTokenSource BeginSearch(CancellationToken cancellationToken)
    {
        var current = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        CancellationTokenSource? previous;
        lock (_activeSearchGate)
        {
            if (_disposed)
            {
                current.Dispose();
                throw new ObjectDisposedException(nameof(SearchService));
            }

            previous = _activeSearch;
            _activeSearch = current;
        }

        TryCancel(previous);
        return current;
    }

    private void EndSearch(CancellationTokenSource search)
    {
        lock (_activeSearchGate)
        {
            if (ReferenceEquals(_activeSearch, search))
            {
                _activeSearch = null;
            }
        }
    }

    private void ThrowIfDisposed()
    {
        lock (_activeSearchGate)
        {
            if (_disposed)
            {
                throw new ObjectDisposedException(nameof(SearchService));
            }
        }
    }

    private static Uri BuildSearchUri(string query, MapProviderDefinition provider)
    {
        EnsureKeyConfiguration(provider);
        var geocoderUrl = provider.GeocoderUrl;
        if (string.IsNullOrWhiteSpace(geocoderUrl))
        {
            throw new SearchServiceException(
                SearchErrorKind.ProviderNotConfigured,
                "当前地图源未配置地点搜索服务");
        }

        var endpoint = ApplyUrlTemplateKey(geocoderUrl, provider);
        var requestUri = CreateHttpUri(endpoint);
        requestUri = AddQueryParameters(
            requestUri,
            ("format", "jsonv2"),
            ("limit", MaximumResults.ToString(CultureInfo.InvariantCulture)),
            ("q", query));
        return AddQueryKey(requestUri, provider);
    }

    private static Uri BuildConnectionUri(MapProviderDefinition provider)
    {
        EnsureKeyConfiguration(provider);
        var geocoderUrl = provider.GeocoderUrl;
        if (!string.IsNullOrWhiteSpace(geocoderUrl))
        {
            var endpoint = ApplyUrlTemplateKey(geocoderUrl, provider);
            var geocoderUri = CreateHttpUri(endpoint);
            geocoderUri = AddQueryParameters(
                geocoderUri,
                ("format", "jsonv2"),
                ("limit", "1"),
                ("q", "connection test"));
            return AddQueryKey(geocoderUri, provider);
        }

        if (string.IsNullOrWhiteSpace(provider.XyzUrlTemplate))
        {
            throw new SearchServiceException(SearchErrorKind.ProviderNotConfigured, InvalidProviderMessage);
        }

        var tileUrl = provider.XyzUrlTemplate
            .Replace("{z}", "0", StringComparison.OrdinalIgnoreCase)
            .Replace("{x}", "0", StringComparison.OrdinalIgnoreCase)
            .Replace("{y}", "0", StringComparison.OrdinalIgnoreCase)
            .Replace("{s}", "a", StringComparison.OrdinalIgnoreCase);
        tileUrl = ApplyUrlTemplateKey(tileUrl, provider);
        return AddQueryKey(CreateHttpUri(tileUrl), provider);
    }

    private static string ApplyUrlTemplateKey(string url, MapProviderDefinition provider)
    {
        var apiKey = provider.ApiKey;
        if (provider.KeyPlacement != ProviderKeyPlacement.UrlTemplate
            || string.IsNullOrEmpty(apiKey))
        {
            return url;
        }

        if (!url.Contains("{key}", StringComparison.OrdinalIgnoreCase))
        {
            throw new SearchServiceException(SearchErrorKind.ProviderNotConfigured, InvalidProviderMessage);
        }

        return url.Replace(
            "{key}",
            Uri.EscapeDataString(apiKey),
            StringComparison.OrdinalIgnoreCase);
    }

    private static Uri AddQueryKey(Uri uri, MapProviderDefinition provider)
    {
        var apiKey = provider.ApiKey;
        if (provider.KeyPlacement != ProviderKeyPlacement.QueryParameter
            || string.IsNullOrEmpty(apiKey))
        {
            return uri;
        }

        var keyName = provider.KeyName;
        if (string.IsNullOrWhiteSpace(keyName))
        {
            throw new SearchServiceException(SearchErrorKind.ProviderNotConfigured, InvalidProviderMessage);
        }

        return AddQueryParameters(uri, (keyName, apiKey));
    }

    private static void AddHeaderKey(
        HttpRequestMessage request,
        MapProviderDefinition provider)
    {
        var apiKey = provider.ApiKey;
        if (provider.KeyPlacement != ProviderKeyPlacement.Header
            || string.IsNullOrEmpty(apiKey))
        {
            return;
        }

        var keyName = provider.KeyName;
        if (string.IsNullOrWhiteSpace(keyName)
            || !request.Headers.TryAddWithoutValidation(keyName, apiKey))
        {
            throw new SearchServiceException(SearchErrorKind.ProviderNotConfigured, InvalidProviderMessage);
        }
    }

    private static Uri AddQueryParameters(Uri uri, params (string Name, string Value)[] values)
    {
        var builder = new UriBuilder(uri);
        var existingQuery = builder.Query;
        if (existingQuery.StartsWith("?", StringComparison.Ordinal))
        {
            existingQuery = existingQuery[1..];
        }

        var addedQuery = string.Join(
            "&",
            values.Select(value =>
                $"{Uri.EscapeDataString(value.Name)}={Uri.EscapeDataString(value.Value)}"));
        builder.Query = string.IsNullOrEmpty(existingQuery)
            ? addedQuery
            : $"{existingQuery}&{addedQuery}";
        return builder.Uri;
    }

    private static Uri CreateHttpUri(string value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttps && uri.Scheme != Uri.UriSchemeHttp))
        {
            throw new SearchServiceException(SearchErrorKind.ProviderNotConfigured, InvalidProviderMessage);
        }

        return uri;
    }

    private static void EnsureKeyConfiguration(MapProviderDefinition provider)
    {
        if (provider.KeyPlacement == ProviderKeyPlacement.None)
        {
            return;
        }

        if (!Enum.IsDefined(provider.KeyPlacement))
        {
            throw new SearchServiceException(SearchErrorKind.ProviderNotConfigured, InvalidProviderMessage);
        }

        if (string.IsNullOrWhiteSpace(provider.ApiKey))
        {
            throw new SearchServiceException(SearchErrorKind.ProviderNotConfigured, MissingKeyMessage);
        }

        if (provider.KeyPlacement is ProviderKeyPlacement.Header or ProviderKeyPlacement.QueryParameter
            && string.IsNullOrWhiteSpace(provider.KeyName))
        {
            throw new SearchServiceException(SearchErrorKind.ProviderNotConfigured, InvalidProviderMessage);
        }
    }

    private static void AddStandardHeaders(HttpRequestMessage request, CultureInfo uiCulture)
    {
        request.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
        if (!string.IsNullOrWhiteSpace(uiCulture.Name))
        {
            request.Headers.TryAddWithoutValidation("Accept-Language", uiCulture.Name);
        }
    }

    private static bool TryReadCoordinate(
        JsonElement item,
        string propertyName,
        out double value)
    {
        value = default;
        if (!item.TryGetProperty(propertyName, out var element))
        {
            return false;
        }

        var parsed = element.ValueKind switch
        {
            JsonValueKind.String => double.TryParse(
                element.GetString(),
                NumberStyles.Float,
                CultureInfo.InvariantCulture,
                out value),
            JsonValueKind.Number => element.TryGetDouble(out value),
            _ => false,
        };
        return parsed && double.IsFinite(value);
    }

    private static bool IsNominatimEndpoint(Uri uri) =>
        uri.Scheme == Uri.UriSchemeHttps
        && string.Equals(
            uri.Host,
            "nominatim.openstreetmap.org",
            StringComparison.OrdinalIgnoreCase)
        && string.Equals(
            uri.AbsolutePath.TrimEnd('/'),
            "/search",
            StringComparison.OrdinalIgnoreCase);

    private static ProviderConnectionResult ConnectionFailure(
        SearchErrorKind kind,
        string message) =>
        new(false, message, kind);

    private static ProviderConnectionResult ConnectionSuccess(MapProviderDefinition provider) =>
        new(
            true,
            provider.KeyPlacement == ProviderKeyPlacement.None
                ? "连接成功（此地图源无需 API Key）"
                : SuccessfulConnectionMessage);

    private static void TryCancel(CancellationTokenSource? cancellation)
    {
        if (cancellation is null)
        {
            return;
        }

        try
        {
            cancellation.Cancel();
        }
        catch (ObjectDisposedException)
        {
        }
    }
}
