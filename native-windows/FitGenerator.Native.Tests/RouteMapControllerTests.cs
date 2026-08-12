using FitGenerator.Native.Map;
using FluentAssertions;
using System.Net;
using Xunit;

namespace FitGenerator.Native.Tests;

public sealed class RouteMapControllerTests
{
    [Fact]
    public void Tile_transport_never_automatically_forwards_header_keys_on_redirect()
    {
        using var handler = RouteMapController.CreateTileHttpHandler();

        handler.AllowAutoRedirect.Should().BeFalse();
        handler.UseCookies.Should().BeFalse();
    }

    [Fact]
    public void Header_key_is_applied_only_to_the_explicit_original_tile_request()
    {
        var provider = new MapProviderDefinition(
            Id: "header-key-test",
            DisplayName: "Header key test",
            XyzUrlTemplate: "https://tiles.example.test/{z}/{x}/{y}.png",
            CoordinateSystem: CoordinateSystem.Wgs84,
            MaximumZoom: 18,
            Attribution: "Example attribution",
            GeocoderUrl: null,
            KeyPlacement: ProviderKeyPlacement.Header,
            KeyName: "X-Test-Key",
            ApiKey: "non-production-test-value");
        using var request = new HttpRequestMessage(
            HttpMethod.Get,
            "https://tiles.example.test/1/2/3.png");

        RouteMapController.ConfigureTileRequest(request, provider);

        request.Headers.GetValues("X-Test-Key")
            .Should()
            .Equal("non-production-test-value");
        request.Headers.UserAgent.ToString().Should().Contain("fitGenerator/1.0");
    }

    [Fact]
    public async Task Tile_transport_distinguishes_connectivity_failures_from_reachable_responses()
    {
        var available = 0;
        var unavailable = 0;
        using var handler = new TileAvailabilityHandler(
            new SequenceHandler(
                HttpStatusCode.ServiceUnavailable,
                HttpStatusCode.NotFound,
                HttpStatusCode.OK),
            onAvailable: () => available += 1,
            onUnavailable: () => unavailable += 1);
        using var client = new HttpClient(handler);

        using var first = await client.GetAsync("https://tiles.example.test/1");
        using var second = await client.GetAsync("https://tiles.example.test/2");
        using var third = await client.GetAsync("https://tiles.example.test/3");

        unavailable.Should().Be(1);
        available.Should().Be(2);
    }

    private sealed class SequenceHandler(params HttpStatusCode[] statuses) : HttpMessageHandler
    {
        private int _index;

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            var status = statuses[Math.Min(_index, statuses.Length - 1)];
            _index += 1;
            return Task.FromResult(new HttpResponseMessage(status));
        }
    }
}
