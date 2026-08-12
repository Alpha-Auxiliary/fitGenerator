using System.Globalization;
using System.Net;
using System.Text;
using FitGenerator.Native.Map;
using FitGenerator.Native.Services;
using FluentAssertions;
using Xunit;

namespace FitGenerator.Native.Tests;

public sealed class SearchServiceTests
{
    [Fact]
    public void Production_handler_does_not_follow_redirects_that_could_forward_a_key()
    {
        using var handler = SearchService.CreateDefaultHttpHandler();

        handler.AllowAutoRedirect.Should().BeFalse();
    }

    [Fact]
    public async Task Default_search_uses_the_nominatim_contract_and_caps_results()
    {
        var clock = new FakeMonotonicClock();
        CapturedRequest? captured = null;
        var handler = new StubHttpMessageHandler((request, _) =>
        {
            captured = CapturedRequest.From(request, clock.Elapsed);
            return Task.FromResult(JsonResponse(CreateResultsJson(count: 6)));
        });
        using var service = new SearchService(handler, clock);

        var results = await service.SearchAsync(
            "天安门 广场",
            MapProviderDefinition.OpenStreetMap,
            CultureInfo.GetCultureInfo("zh-CN"),
            CancellationToken.None);

        captured.Should().NotBeNull();
        captured!.Uri.GetLeftPart(UriPartial.Path).Should().Be(
            "https://nominatim.openstreetmap.org/search");
        QueryValue(captured.Uri, "format").Should().Be("jsonv2");
        QueryValue(captured.Uri, "limit").Should().Be("5");
        QueryValue(captured.Uri, "q").Should().Be("天安门 广场");
        captured.Uri.Query.Should().Contain($"q={Uri.EscapeDataString("天安门 广场")}");
        captured.Header("User-Agent").Should().Be(
            "fitGenerator/1.0 (+https://github.com/Alpha-Auxiliary/fitGenerator)");
        captured.Header("Accept-Language").Should().Be("zh-CN");
        results.Should().HaveCount(5);
        results.Should().OnlyContain(result =>
            result.Attribution == MapProviderDefinition.OpenStreetMapAttribution);
        results[0].DisplayLabel.Should().Be("Result 0");
        results[0].Coordinate.Should().Be(new GeoCoordinate(39.9042, 116.4074));
    }

    [Fact]
    public async Task Consecutive_nominatim_requests_start_at_least_one_second_apart()
    {
        var clock = new FakeMonotonicClock();
        var requestStarts = new List<TimeSpan>();
        var handler = new StubHttpMessageHandler((_, _) =>
        {
            requestStarts.Add(clock.Elapsed);
            return Task.FromResult(JsonResponse(CreateResultsJson()));
        });
        using var service = new SearchService(handler, clock);

        await service.SearchAsync(
            "first",
            MapProviderDefinition.OpenStreetMap,
            CultureInfo.InvariantCulture,
            CancellationToken.None);
        await service.SearchAsync(
            "second",
            MapProviderDefinition.OpenStreetMap,
            CultureInfo.InvariantCulture,
            CancellationToken.None);

        requestStarts.Should().HaveCount(2);
        (requestStarts[1] - requestStarts[0]).Should().BeGreaterThanOrEqualTo(
            TimeSpan.FromSeconds(1));
        clock.Delays.Should().ContainSingle().Which.Should().Be(TimeSpan.FromSeconds(1));
    }

    [Fact]
    public async Task A_newer_query_cancels_the_older_request()
    {
        var clock = new FakeMonotonicClock();
        var firstStarted = new TaskCompletionSource<bool>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var firstCancelled = new TaskCompletionSource<bool>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var requestCount = 0;
        var handler = new StubHttpMessageHandler(async (_, cancellationToken) =>
        {
            if (Interlocked.Increment(ref requestCount) == 1)
            {
                firstStarted.TrySetResult(true);
                try
                {
                    await WaitForCancellationAsync(cancellationToken);
                }
                catch (OperationCanceledException)
                {
                    firstCancelled.TrySetResult(true);
                    throw;
                }
            }

            return JsonResponse(CreateResultsJson());
        });
        using var service = new SearchService(handler, clock);

        var firstSearch = service.SearchAsync(
            "older",
            MapProviderDefinition.OpenStreetMap,
            CultureInfo.InvariantCulture,
            CancellationToken.None);
        await firstStarted.Task;
        var secondSearch = service.SearchAsync(
            "newer",
            MapProviderDefinition.OpenStreetMap,
            CultureInfo.InvariantCulture,
            CancellationToken.None);

        await firstCancelled.Task;
        Func<Task> firstAction = async () => await firstSearch;
        await firstAction.Should().ThrowAsync<OperationCanceledException>();
        (await secondSearch).Should().ContainSingle();
        requestCount.Should().Be(2);
    }

    [Fact]
    public async Task Caller_cancellation_is_propagated_without_becoming_a_search_error()
    {
        var requestStarted = new TaskCompletionSource<bool>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var handler = new StubHttpMessageHandler(async (_, cancellationToken) =>
        {
            requestStarted.TrySetResult(true);
            await WaitForCancellationAsync(cancellationToken);
            return JsonResponse(CreateResultsJson());
        });
        using var service = new SearchService(handler, new FakeMonotonicClock());
        using var cancellation = new CancellationTokenSource();

        var search = service.SearchAsync(
            "cancelled",
            CreateCustomProvider(),
            CultureInfo.InvariantCulture,
            cancellation.Token);
        await requestStarted.Task;
        cancellation.Cancel();

        Func<Task> action = async () => await search;
        await action.Should().ThrowAsync<OperationCanceledException>();
    }

    [Fact]
    public async Task Provider_coordinates_are_converted_to_canonical_wgs84()
    {
        var expected = new GeoCoordinate(39.9042, 116.4074);
        var gcj02 = CoordinateTransforms.Wgs84ToGcj02(expected);
        var handler = new StubHttpMessageHandler((_, _) => Task.FromResult(JsonResponse(
            CreateResultsJson(
                latitude: gcj02.Latitude,
                longitude: gcj02.Longitude))));
        var provider = CreateCustomProvider(
            coordinateSystem: CoordinateSystem.Gcj02,
            attribution: "Example attribution");
        using var service = new SearchService(handler, new FakeMonotonicClock());

        var results = await service.SearchAsync(
            "Beijing",
            provider,
            CultureInfo.GetCultureInfo("en-US"),
            CancellationToken.None);

        results.Should().ContainSingle();
        CoordinateTransforms.DistanceMeters(results[0].Coordinate, expected)
            .Should()
            .BeLessThan(2.0);
        results[0].Attribution.Should().Be("Example attribution");
    }

    [Theory]
    [InlineData("http", "Network")]
    [InlineData("timeout", "Timeout")]
    [InlineData("json", "InvalidResponse")]
    public async Task Search_failures_are_typed_and_do_not_mutate_the_route(
        string failure,
        string expectedKind)
    {
        var route = new List<GeoCoordinate>
        {
            new(39.9042, 116.4074),
            new(39.9052, 116.4084),
        };
        var originalRoute = route.ToArray();
        var handler = new StubHttpMessageHandler((_, _) => failure switch
        {
            "http" => Task.FromResult(new HttpResponseMessage(HttpStatusCode.BadGateway)),
            "timeout" => Task.FromException<HttpResponseMessage>(new TaskCanceledException()),
            "json" => Task.FromResult(JsonResponse("{")),
            _ => throw new InvalidOperationException("Unknown test failure."),
        });
        using var service = new SearchService(handler, new FakeMonotonicClock());

        Func<Task> action = () => service.SearchAsync(
            "Beijing",
            CreateCustomProvider(),
            CultureInfo.InvariantCulture,
            CancellationToken.None);

        var exception = (await action.Should().ThrowAsync<SearchServiceException>()).Which;
        exception.Kind.ToString().Should().Be(expectedKind);
        exception.Message.Should().NotBeNullOrWhiteSpace();
        route.Should().Equal(originalRoute);
    }

    [Fact]
    public async Task Connection_test_uses_the_configured_geocoder_and_hides_a_header_key()
    {
        const string secret = "secret-value";
        CapturedRequest? captured = null;
        var handler = new StubHttpMessageHandler((request, _) =>
        {
            captured = CapturedRequest.From(request, TimeSpan.Zero);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK));
        });
        var provider = CreateCustomProvider(
            keyPlacement: ProviderKeyPlacement.Header,
            keyName: "X-Api-Key",
            apiKey: secret);
        using var service = new SearchService(handler, new FakeMonotonicClock());

        var result = await service.TestConnectionAsync(provider, CancellationToken.None);

        result.IsSuccess.Should().BeTrue();
        result.ErrorKind.Should().BeNull();
        captured.Should().NotBeNull();
        captured!.Uri.GetLeftPart(UriPartial.Path).Should().Be("https://example.test/search");
        captured.Header("X-Api-Key").Should().Be(secret);
        result.Message.Should().NotContain(secret);
        provider.ToString().Should().Be("Custom Provider").And.NotContain(secret);
    }

    [Fact]
    public async Task Connection_test_requests_one_tile_and_applies_a_query_key()
    {
        const string secret = "a key/+?";
        CapturedRequest? captured = null;
        var handler = new StubHttpMessageHandler((request, _) =>
        {
            captured = CapturedRequest.From(request, TimeSpan.Zero);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NoContent));
        });
        var provider = CreateCustomProvider(
            xyzUrlTemplate: "https://tiles.example.test/{z}/{x}/{y}.png",
            geocoderUrl: null,
            keyPlacement: ProviderKeyPlacement.QueryParameter,
            keyName: "token",
            apiKey: secret);
        using var service = new SearchService(handler, new FakeMonotonicClock());

        var result = await service.TestConnectionAsync(provider, CancellationToken.None);

        result.IsSuccess.Should().BeTrue();
        captured.Should().NotBeNull();
        captured!.Uri.AbsolutePath.Should().Be("/0/0/0.png");
        QueryValue(captured.Uri, "token").Should().Be(secret);
        result.Message.Should().NotContain(secret);
    }

    [Fact]
    public async Task Connection_test_reports_a_missing_required_key_without_sending_a_request()
    {
        var requestCount = 0;
        var handler = new StubHttpMessageHandler((_, _) =>
        {
            Interlocked.Increment(ref requestCount);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK));
        });
        var provider = CreateCustomProvider(
            keyPlacement: ProviderKeyPlacement.Header,
            keyName: "X-Api-Key",
            apiKey: null);
        using var service = new SearchService(handler, new FakeMonotonicClock());

        var result = await service.TestConnectionAsync(provider, CancellationToken.None);

        result.IsSuccess.Should().BeFalse();
        result.ErrorKind.Should().Be(SearchErrorKind.ProviderNotConfigured);
        result.Message.Should().Contain("API Key");
        requestCount.Should().Be(0);
    }

    [Fact]
    public async Task Connection_test_identifies_a_provider_that_needs_no_key()
    {
        var handler = new StubHttpMessageHandler((_, _) =>
            Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)));
        using var service = new SearchService(handler, new FakeMonotonicClock());

        var result = await service.TestConnectionAsync(
            CreateCustomProvider(),
            CancellationToken.None);

        result.IsSuccess.Should().BeTrue();
        result.Message.Should().Contain("无需 API Key");
    }

    [Fact]
    public void Built_in_providers_are_immutable_wgs84_definitions()
    {
        MapProviderDefinition.BuiltIns.Should().HaveCount(3);
        MapProviderDefinition.BuiltIns.Should().Equal(
            MapProviderDefinition.OpenStreetMap,
            MapProviderDefinition.OpenTopoMap,
            MapProviderDefinition.CartoLight);
        MapProviderDefinition.BuiltIns.Should().OnlyContain(provider =>
            provider.IsBuiltIn && provider.CoordinateSystem == CoordinateSystem.Wgs84);
        MapProviderDefinition.OpenStreetMap.IsDefault.Should().BeTrue();
        MapProviderDefinition.OpenStreetMap.ApiKey.Should().BeNull();
        MapProviderDefinition.OpenStreetMap.GeocoderUrl.Should().Be(
            MapProviderDefinition.NominatimSearchUrl);

        var renamedCopy = MapProviderDefinition.OpenStreetMap with { DisplayName = "Changed" };
        renamedCopy.DisplayName.Should().Be("Changed");
        MapProviderDefinition.OpenStreetMap.DisplayName.Should().Be("OpenStreetMap");
    }

    private static MapProviderDefinition CreateCustomProvider(
        string xyzUrlTemplate = "https://tiles.example.test/{z}/{x}/{y}.png",
        string? geocoderUrl = "https://example.test/search",
        CoordinateSystem coordinateSystem = CoordinateSystem.Wgs84,
        string attribution = "Example attribution",
        ProviderKeyPlacement keyPlacement = ProviderKeyPlacement.None,
        string? keyName = null,
        string? apiKey = null) =>
        new(
            Id: "custom",
            DisplayName: "Custom Provider",
            XyzUrlTemplate: xyzUrlTemplate,
            CoordinateSystem: coordinateSystem,
            MaximumZoom: 18,
            Attribution: attribution,
            GeocoderUrl: geocoderUrl,
            KeyPlacement: keyPlacement,
            KeyName: keyName,
            ApiKey: apiKey);

    private static HttpResponseMessage JsonResponse(string json) =>
        new(HttpStatusCode.OK)
        {
            Content = new StringContent(json, Encoding.UTF8, "application/json"),
        };

    private static async Task WaitForCancellationAsync(CancellationToken cancellationToken)
    {
        var cancellation = new TaskCompletionSource<bool>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        using var registration = cancellationToken.Register(
            () => cancellation.TrySetResult(true));
        await cancellation.Task;
        cancellationToken.ThrowIfCancellationRequested();
    }

    private static string CreateResultsJson(
        int count = 1,
        double latitude = 39.9042,
        double longitude = 116.4074)
    {
        var latitudeText = latitude.ToString("R", CultureInfo.InvariantCulture);
        var longitudeText = longitude.ToString("R", CultureInfo.InvariantCulture);
        var items = Enumerable.Range(0, count).Select(index =>
            $$"""{"display_name":"Result {{index}}","lat":"{{latitudeText}}","lon":"{{longitudeText}}"}""");
        return $"[{string.Join(",", items)}]";
    }

    private static string? QueryValue(Uri uri, string name)
    {
        foreach (var pair in uri.Query.TrimStart('?').Split(
                     '&',
                     StringSplitOptions.RemoveEmptyEntries))
        {
            var separator = pair.IndexOf('=');
            var encodedName = separator < 0 ? pair : pair[..separator];
            if (!string.Equals(
                    Uri.UnescapeDataString(encodedName),
                    name,
                    StringComparison.Ordinal))
            {
                continue;
            }

            return separator < 0
                ? string.Empty
                : Uri.UnescapeDataString(pair[(separator + 1)..]);
        }

        return null;
    }

    private sealed class StubHttpMessageHandler : HttpMessageHandler
    {
        private readonly Func<
            HttpRequestMessage,
            CancellationToken,
            Task<HttpResponseMessage>> _send;

        internal StubHttpMessageHandler(
            Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send)
        {
            _send = send;
        }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) =>
            _send(request, cancellationToken);
    }

    private sealed class FakeMonotonicClock : IMonotonicClock
    {
        private readonly object _gate = new();
        private long _ticks;

        internal List<TimeSpan> Delays { get; } = new();

        internal TimeSpan Elapsed
        {
            get
            {
                lock (_gate)
                {
                    return TimeSpan.FromTicks(_ticks);
                }
            }
        }

        public long GetTimestamp()
        {
            lock (_gate)
            {
                return _ticks;
            }
        }

        public TimeSpan GetElapsedTime(long startingTimestamp, long endingTimestamp) =>
            TimeSpan.FromTicks(endingTimestamp - startingTimestamp);

        public Task DelayAsync(TimeSpan delay, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            lock (_gate)
            {
                Delays.Add(delay);
                _ticks += delay.Ticks;
            }

            return Task.CompletedTask;
        }
    }

    private sealed record CapturedRequest(
        Uri Uri,
        IReadOnlyDictionary<string, string> Headers,
        TimeSpan StartedAt)
    {
        internal static CapturedRequest From(HttpRequestMessage request, TimeSpan startedAt)
        {
            var headers = request.Headers.ToDictionary(
                header => header.Key,
                header => string.Join(" ", header.Value),
                StringComparer.OrdinalIgnoreCase);
            return new CapturedRequest(request.RequestUri!, headers, startedAt);
        }

        internal string? Header(string name) =>
            Headers.TryGetValue(name, out var value) ? value : null;
    }
}
