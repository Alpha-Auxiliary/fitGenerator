using FitGenerator.Native.Map;

namespace FitGenerator.Native.Services;

internal sealed record MapViewportSettings(
    double CenterWgs84Latitude,
    double CenterWgs84Longitude,
    double Zoom)
{
    internal static MapViewportSettings Default => new(
        CenterWgs84Latitude: 39.9042,
        CenterWgs84Longitude: 116.4074,
        Zoom: 9.0);
}

internal sealed record AppSettings
{
    internal const int CurrentSchemaVersion = 1;

    public int SchemaVersion { get; init; } = CurrentSchemaVersion;

    public string ActiveMapProviderId { get; init; } =
        MapProviderDefinition.OpenStreetMapId;

    public IReadOnlyList<MapProviderDefinition> CustomMapProviders { get; init; } =
        Array.Empty<MapProviderDefinition>();

    public MapViewportSettings MapViewport { get; init; } = MapViewportSettings.Default;

    public double PaceSecondsPerKilometer { get; init; } = 360.0;

    public int RestingHeartRate { get; init; } = 60;

    public int MaximumHeartRate { get; init; } = 180;

    public int LapCount { get; init; } = 1;

    public int ExportCount { get; init; } = 1;

    public string? ExportDirectory { get; init; }

    public ulong? PreviewSeed { get; init; }

    internal static AppSettings Default => new();
}

internal sealed record SavedRoute
{
    internal const int CurrentSchemaVersion = 1;

    public int SchemaVersion { get; init; } = CurrentSchemaVersion;

    public IReadOnlyList<GeoCoordinate> Wgs84Points { get; init; } =
        Array.Empty<GeoCoordinate>();

    internal static SavedRoute Empty => new();
}
