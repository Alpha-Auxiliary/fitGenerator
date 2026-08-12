namespace FitGenerator.Native.Map;

internal enum CoordinateSystem
{
    Wgs84,
    Gcj02,
    Bd09,
}

internal readonly record struct GeoCoordinate(double Latitude, double Longitude);
