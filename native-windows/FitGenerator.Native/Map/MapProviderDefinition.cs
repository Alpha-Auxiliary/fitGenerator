namespace FitGenerator.Native.Map;

internal enum ProviderKeyPlacement
{
    None,
    UrlTemplate,
    Header,
    QueryParameter,
}

internal sealed record MapProviderDefinition(
    string Id,
    string DisplayName,
    string XyzUrlTemplate,
    CoordinateSystem CoordinateSystem,
    int MaximumZoom,
    string Attribution,
    string? GeocoderUrl = null,
    ProviderKeyPlacement KeyPlacement = ProviderKeyPlacement.None,
    string? KeyName = null,
    string? ApiKey = null,
    bool IsBuiltIn = false)
{
    internal const string OpenStreetMapId = "osm";
    internal const string NominatimSearchUrl = "https://nominatim.openstreetmap.org/search";
    internal const string OpenStreetMapAttribution = "© OpenStreetMap contributors";

    internal static MapProviderDefinition OpenStreetMap { get; } = new(
        Id: OpenStreetMapId,
        DisplayName: "OpenStreetMap",
        XyzUrlTemplate: "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        CoordinateSystem: CoordinateSystem.Wgs84,
        MaximumZoom: 19,
        Attribution: OpenStreetMapAttribution,
        GeocoderUrl: NominatimSearchUrl,
        IsBuiltIn: true);

    internal static MapProviderDefinition OpenTopoMap { get; } = new(
        Id: "opentopomap",
        DisplayName: "OpenTopoMap",
        XyzUrlTemplate: "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png",
        CoordinateSystem: CoordinateSystem.Wgs84,
        MaximumZoom: 17,
        Attribution: "© OpenStreetMap contributors, SRTM | Map style © OpenTopoMap",
        GeocoderUrl: NominatimSearchUrl,
        IsBuiltIn: true);

    internal static MapProviderDefinition CartoLight { get; } = new(
        Id: "carto-light",
        DisplayName: "Carto Light",
        XyzUrlTemplate: "https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png",
        CoordinateSystem: CoordinateSystem.Wgs84,
        MaximumZoom: 20,
        Attribution: "© OpenStreetMap contributors © CARTO",
        GeocoderUrl: NominatimSearchUrl,
        IsBuiltIn: true);

    private static readonly IReadOnlyList<MapProviderDefinition> BuiltInValues =
        Array.AsReadOnly(new[] { OpenStreetMap, OpenTopoMap, CartoLight });

    internal static IReadOnlyList<MapProviderDefinition> BuiltIns => BuiltInValues;

    internal bool IsDefault =>
        IsBuiltIn && string.Equals(Id, OpenStreetMapId, StringComparison.Ordinal);

    public override string ToString() => DisplayName;
}
