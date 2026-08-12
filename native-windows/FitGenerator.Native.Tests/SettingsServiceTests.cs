using System.Security.Cryptography;
using System.Text;
using FitGenerator.Native.Map;
using FitGenerator.Native.Services;
using FluentAssertions;
using Xunit;

namespace FitGenerator.Native.Tests;

public sealed class SettingsServiceTests : IDisposable
{
    private static readonly DateTimeOffset BackupTime = new(
        2026,
        8,
        4,
        1,
        2,
        3,
        4,
        TimeSpan.Zero);

    private readonly string _temporaryDirectory = Path.Combine(
        Path.GetTempPath(),
        $"FitGenerator.Native.Tests-{Guid.NewGuid():N}");

    [Fact]
    public void Missing_settings_and_route_return_zero_configuration_defaults()
    {
        var service = CreateService(new ReversibleKeyProtector());

        var settings = service.LoadSettings();
        var route = service.LoadLastRoute();

        settings.Should().BeEquivalentTo(AppSettings.Default);
        settings.ActiveMapProviderId.Should().Be(MapProviderDefinition.OpenStreetMapId);
        settings.CustomMapProviders.Should().BeEmpty();
        route.Should().BeEquivalentTo(SavedRoute.Empty);
    }

    [Fact]
    public void Schema_version_one_settings_and_route_round_trip()
    {
        var service = CreateService(new ReversibleKeyProtector());
        var provider = CreateCustomProvider("round-trip-key");
        var expectedSettings = AppSettings.Default with
        {
            ActiveMapProviderId = provider.Id,
            CustomMapProviders = new[] { provider },
            MapViewport = new MapViewportSettings(31.2304, 121.4737, 13.5),
            PaceSecondsPerKilometer = 325.0,
            RestingHeartRate = 58,
            MaximumHeartRate = 188,
            LapCount = 4,
            ExportCount = 3,
            ExportDirectory = @"D:\Runs",
            PreviewSeed = 9_876_543_210UL,
        };
        var expectedRoute = new SavedRoute
        {
            Wgs84Points = new[]
            {
                new GeoCoordinate(31.2304, 121.4737),
                new GeoCoordinate(31.2314, 121.4747),
            },
        };

        service.SaveSettings(expectedSettings);
        service.SaveLastRoute(expectedRoute);

        service.LoadSettings().Should().BeEquivalentTo(expectedSettings);
        service.LoadLastRoute().Should().BeEquivalentTo(expectedRoute);
        service.LoadSettings().SchemaVersion.Should().Be(1);
        service.LoadLastRoute().SchemaVersion.Should().Be(1);

        var replacement = expectedSettings with { LapCount = 5 };
        service.SaveSettings(replacement);

        service.LoadSettings().Should().BeEquivalentTo(replacement);
        File.ReadAllText(service.LastRouteFilePath, Encoding.UTF8)
            .Should()
            .Contain("wgs84Points");
        Directory
            .EnumerateFiles(_temporaryDirectory, "*.tmp")
            .Should()
            .BeEmpty();
    }

    [Fact]
    public void Invalid_json_is_renamed_with_a_timestamp_and_defaults_are_returned()
    {
        var service = CreateService(new ReversibleKeyProtector());
        Directory.CreateDirectory(_temporaryDirectory);
        File.WriteAllText(service.SettingsFilePath, "{ invalid json", Encoding.UTF8);

        var settings = service.LoadSettings();

        settings.Should().BeEquivalentTo(AppSettings.Default);
        File.Exists(service.SettingsFilePath).Should().BeFalse();
        var backupPath = Path.Combine(
            _temporaryDirectory,
            "settings-v1.invalid-20260804T010203004Z.json");
        File.Exists(backupPath).Should().BeTrue();
        File.ReadAllText(backupPath, Encoding.UTF8).Should().Be("{ invalid json");
    }

    [Fact]
    public void Unsupported_schema_is_backed_up_and_defaults_are_returned()
    {
        var service = CreateService(new ReversibleKeyProtector());
        Directory.CreateDirectory(_temporaryDirectory);
        File.WriteAllText(
            service.SettingsFilePath,
            """
            {
              "schemaVersion": 2,
              "activeMapProviderId": "osm",
              "mapViewport": {
                "centerWgs84Latitude": 39.9042,
                "centerWgs84Longitude": 116.4074,
                "zoom": 9
              },
              "paceSecondsPerKilometer": 360,
              "restingHeartRate": 60,
              "maximumHeartRate": 180,
              "lapCount": 1,
              "exportCount": 1,
              "exportDirectory": null,
              "previewSeed": null,
              "customMapProviders": []
            }
            """,
            Encoding.UTF8);

        var settings = service.LoadSettings();

        settings.Should().BeEquivalentTo(AppSettings.Default);
        Directory
            .EnumerateFiles(_temporaryDirectory, "settings-v1.invalid-*.json")
            .Should()
            .ContainSingle();
    }

    [Fact]
    public void Missing_viewport_uses_the_safe_default_without_discarding_other_settings()
    {
        var service = CreateService(new ReversibleKeyProtector());
        Directory.CreateDirectory(_temporaryDirectory);
        File.WriteAllText(
            service.SettingsFilePath,
            """
            {
              "schemaVersion": 1,
              "activeMapProviderId": "osm",
              "paceSecondsPerKilometer": 420,
              "restingHeartRate": 55,
              "maximumHeartRate": 185,
              "lapCount": 7,
              "exportCount": 2,
              "exportDirectory": null,
              "previewSeed": null,
              "customMapProviders": []
            }
            """,
            Encoding.UTF8);

        var settings = service.LoadSettings();

        settings.MapViewport.Should().Be(MapViewportSettings.Default);
        settings.LapCount.Should().Be(7);
        settings.PaceSecondsPerKilometer.Should().Be(420);
        File.Exists(service.SettingsFilePath).Should().BeTrue();
        Directory
            .EnumerateFiles(_temporaryDirectory, "settings-v1.invalid-*.json")
            .Should()
            .BeEmpty();
    }

    [Fact]
    public void Invalid_viewport_uses_the_safe_default_without_discarding_other_settings()
    {
        var service = CreateService(new ReversibleKeyProtector());
        Directory.CreateDirectory(_temporaryDirectory);
        File.WriteAllText(
            service.SettingsFilePath,
            """
            {
              "schemaVersion": 1,
              "activeMapProviderId": "osm",
              "mapViewport": {
                "centerWgs84Latitude": 200,
                "centerWgs84Longitude": 121.4737,
                "zoom": 0
              },
              "paceSecondsPerKilometer": 420,
              "restingHeartRate": 55,
              "maximumHeartRate": 185,
              "lapCount": 7,
              "exportCount": 2,
              "exportDirectory": null,
              "previewSeed": null,
              "customMapProviders": []
            }
            """,
            Encoding.UTF8);

        var settings = service.LoadSettings();

        settings.MapViewport.Should().Be(MapViewportSettings.Default);
        settings.LapCount.Should().Be(7);
        settings.PaceSecondsPerKilometer.Should().Be(420);
        File.Exists(service.SettingsFilePath).Should().BeTrue();
        Directory
            .EnumerateFiles(_temporaryDirectory, "settings-v1.invalid-*.json")
            .Should()
            .BeEmpty();
    }

    [Fact]
    public void Persisted_settings_do_not_contain_the_plaintext_api_key()
    {
        const string plaintextKey = "task-4-plaintext-secret";
        var protector = new ReversibleKeyProtector();
        var service = CreateService(protector);
        var provider = CreateCustomProvider(plaintextKey);

        service.SaveSettings(AppSettings.Default with
        {
            ActiveMapProviderId = provider.Id,
            CustomMapProviders = new[] { provider },
        });

        var persistedJson = File.ReadAllText(service.SettingsFilePath, Encoding.UTF8);
        persistedJson.Should().NotContain(plaintextKey);
        persistedJson.Should().Contain("protectedApiKey");
        protector.ProtectCallCount.Should().Be(1);
    }

    [Fact]
    public void Dpapi_failure_leaves_nonsecret_settings_usable_with_an_empty_key()
    {
        const string plaintextKey = "unavailable-after-restart";
        var writer = CreateService(new ReversibleKeyProtector());
        var provider = CreateCustomProvider(plaintextKey);
        writer.SaveSettings(AppSettings.Default with
        {
            ActiveMapProviderId = provider.Id,
            CustomMapProviders = new[] { provider },
            LapCount = 7,
            PreviewSeed = 42UL,
        });
        var reader = CreateService(new FailingUnprotectKeyProtector());

        var loaded = reader.LoadSettings();

        loaded.ActiveMapProviderId.Should().Be(provider.Id);
        loaded.LapCount.Should().Be(7);
        loaded.PreviewSeed.Should().Be(42UL);
        loaded.CustomMapProviders.Should().ContainSingle();
        loaded.CustomMapProviders[0].DisplayName.Should().Be(provider.DisplayName);
        loaded.CustomMapProviders[0].ApiKey.Should().BeNull();
        File.Exists(reader.SettingsFilePath).Should().BeTrue();
        Directory
            .EnumerateFiles(_temporaryDirectory, "settings-v1.invalid-*.json")
            .Should()
            .BeEmpty();
    }

    public void Dispose()
    {
        if (Directory.Exists(_temporaryDirectory))
        {
            Directory.Delete(_temporaryDirectory, recursive: true);
        }
    }

    private SettingsService CreateService(ISettingsKeyProtector keyProtector) =>
        new(
            _temporaryDirectory,
            keyProtector,
            new FixedTimeProvider(BackupTime));

    private static MapProviderDefinition CreateCustomProvider(string apiKey) => new(
        Id: "custom-map",
        DisplayName: "Custom Map",
        XyzUrlTemplate: "https://tiles.example.test/{z}/{x}/{y}.png",
        CoordinateSystem: CoordinateSystem.Gcj02,
        MaximumZoom: 18,
        Attribution: "Example attribution",
        GeocoderUrl: "https://search.example.test/lookup",
        KeyPlacement: ProviderKeyPlacement.Header,
        KeyName: "X-Api-Key",
        ApiKey: apiKey);

    private sealed class ReversibleKeyProtector : ISettingsKeyProtector
    {
        private const byte Mask = 0xA5;

        internal int ProtectCallCount { get; private set; }

        public byte[] Protect(byte[] plaintext)
        {
            ProtectCallCount++;
            return Transform(plaintext);
        }

        public byte[] Unprotect(byte[] protectedData) => Transform(protectedData);

        private static byte[] Transform(byte[] source)
        {
            var transformed = new byte[source.Length];
            for (var index = 0; index < source.Length; index++)
            {
                transformed[index] = (byte)(source[index] ^ Mask);
            }

            return transformed;
        }
    }

    private sealed class FailingUnprotectKeyProtector : ISettingsKeyProtector
    {
        public byte[] Protect(byte[] plaintext) => plaintext.ToArray();

        public byte[] Unprotect(byte[] protectedData) =>
            throw new CryptographicException("Simulated DPAPI failure.");
    }

    private sealed class FixedTimeProvider : TimeProvider
    {
        private readonly DateTimeOffset _utcNow;

        internal FixedTimeProvider(DateTimeOffset utcNow)
        {
            _utcNow = utcNow;
        }

        public override DateTimeOffset GetUtcNow() => _utcNow;
    }
}
