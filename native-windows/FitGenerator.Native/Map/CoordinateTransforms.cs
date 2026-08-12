namespace FitGenerator.Native.Map;

internal static class CoordinateTransforms
{
    private const double Pi = Math.PI;
    private const double SemiMajorAxis = 6_378_245.0;
    private const double EccentricitySquared = 0.00669342162296594323;
    private const double BdPi = Pi * 3_000.0 / 180.0;
    private const double EarthRadiusMeters = 6_371_008.8;
    private const double ChinaMinimumLatitude = 0.8293;
    private const double ChinaMaximumLatitude = 55.8271;
    private const double ChinaMinimumLongitude = 72.004;
    private const double ChinaMaximumLongitude = 137.8347;
    private const double InverseToleranceDegrees = 1e-7;
    private const int MaximumInverseIterations = 10;

    internal static GeoCoordinate ToWgs84(GeoCoordinate value, CoordinateSystem source)
    {
        ValidateCoordinate(value, nameof(value));
        return source switch
        {
            CoordinateSystem.Wgs84 => value,
            CoordinateSystem.Gcj02 => Gcj02ToWgs84(value),
            CoordinateSystem.Bd09 => Gcj02ToWgs84(Bd09ToGcj02(value)),
            _ => throw new ArgumentOutOfRangeException(nameof(source), source, "不支持的坐标系"),
        };
    }

    internal static GeoCoordinate FromWgs84(GeoCoordinate value, CoordinateSystem target)
    {
        ValidateCoordinate(value, nameof(value));
        return target switch
        {
            CoordinateSystem.Wgs84 => value,
            CoordinateSystem.Gcj02 => Wgs84ToGcj02(value),
            CoordinateSystem.Bd09 => Gcj02ToBd09(Wgs84ToGcj02(value)),
            _ => throw new ArgumentOutOfRangeException(nameof(target), target, "不支持的坐标系"),
        };
    }

    internal static GeoCoordinate Wgs84ToGcj02(GeoCoordinate value)
    {
        ValidateCoordinate(value, nameof(value));
        if (IsOutsideChina(value))
        {
            return value;
        }

        var latitudeOffset = TransformLatitude(
            value.Longitude - 105.0,
            value.Latitude - 35.0);
        var longitudeOffset = TransformLongitude(
            value.Longitude - 105.0,
            value.Latitude - 35.0);
        var latitudeRadians = DegreesToRadians(value.Latitude);
        var sineLatitude = Math.Sin(latitudeRadians);
        var magic = 1.0 - EccentricitySquared * sineLatitude * sineLatitude;
        var squareRootMagic = Math.Sqrt(magic);
        latitudeOffset = latitudeOffset * 180.0
            / ((SemiMajorAxis * (1.0 - EccentricitySquared))
                / (magic * squareRootMagic)
                * Pi);
        longitudeOffset = longitudeOffset * 180.0
            / (SemiMajorAxis / squareRootMagic * Math.Cos(latitudeRadians) * Pi);

        return new GeoCoordinate(
            value.Latitude + latitudeOffset,
            value.Longitude + longitudeOffset);
    }

    internal static GeoCoordinate Gcj02ToWgs84(GeoCoordinate value)
    {
        ValidateCoordinate(value, nameof(value));
        if (IsOutsideChina(value))
        {
            return value;
        }

        var estimate = value;
        for (var iteration = 0; iteration < MaximumInverseIterations; iteration++)
        {
            var transformed = Wgs84ToGcj02(estimate);
            var latitudeError = transformed.Latitude - value.Latitude;
            var longitudeError = transformed.Longitude - value.Longitude;
            estimate = new GeoCoordinate(
                estimate.Latitude - latitudeError,
                estimate.Longitude - longitudeError);

            if (Math.Abs(latitudeError) < InverseToleranceDegrees
                && Math.Abs(longitudeError) < InverseToleranceDegrees)
            {
                break;
            }
        }

        return estimate;
    }

    internal static GeoCoordinate Gcj02ToBd09(GeoCoordinate value)
    {
        ValidateCoordinate(value, nameof(value));
        var radius = Math.Sqrt(
                value.Longitude * value.Longitude
                + value.Latitude * value.Latitude)
            + 0.00002 * Math.Sin(value.Latitude * BdPi);
        var angle = Math.Atan2(value.Latitude, value.Longitude)
            + 0.000003 * Math.Cos(value.Longitude * BdPi);

        return new GeoCoordinate(
            radius * Math.Sin(angle) + 0.006,
            radius * Math.Cos(angle) + 0.0065);
    }

    internal static GeoCoordinate Bd09ToGcj02(GeoCoordinate value)
    {
        ValidateCoordinate(value, nameof(value));
        var longitude = value.Longitude - 0.0065;
        var latitude = value.Latitude - 0.006;
        var radius = Math.Sqrt(longitude * longitude + latitude * latitude)
            - 0.00002 * Math.Sin(latitude * BdPi);
        var angle = Math.Atan2(latitude, longitude)
            - 0.000003 * Math.Cos(longitude * BdPi);

        return new GeoCoordinate(
            radius * Math.Sin(angle),
            radius * Math.Cos(angle));
    }

    internal static double DistanceMeters(GeoCoordinate first, GeoCoordinate second)
    {
        ValidateCoordinate(first, nameof(first));
        ValidateCoordinate(second, nameof(second));
        var latitudeDelta = DegreesToRadians(second.Latitude - first.Latitude);
        var longitudeDelta = DegreesToRadians(second.Longitude - first.Longitude);
        var firstLatitude = DegreesToRadians(first.Latitude);
        var secondLatitude = DegreesToRadians(second.Latitude);
        var haversine = Math.Sin(latitudeDelta / 2.0) * Math.Sin(latitudeDelta / 2.0)
            + Math.Cos(firstLatitude)
            * Math.Cos(secondLatitude)
            * Math.Sin(longitudeDelta / 2.0)
            * Math.Sin(longitudeDelta / 2.0);
        var centralAngle = 2.0 * Math.Asin(Math.Sqrt(Math.Clamp(haversine, 0.0, 1.0)));
        return EarthRadiusMeters * centralAngle;
    }

    private static bool IsOutsideChina(GeoCoordinate value) =>
        value.Longitude < ChinaMinimumLongitude
        || value.Longitude > ChinaMaximumLongitude
        || value.Latitude < ChinaMinimumLatitude
        || value.Latitude > ChinaMaximumLatitude;

    private static void ValidateCoordinate(GeoCoordinate value, string parameterName)
    {
        if (!double.IsFinite(value.Latitude) || !double.IsFinite(value.Longitude))
        {
            throw new ArgumentOutOfRangeException(parameterName, value, "坐标必须是有限数值");
        }

        if (value.Latitude is < -90.0 or > 90.0
            || value.Longitude is < -180.0 or > 180.0)
        {
            throw new ArgumentOutOfRangeException(parameterName, value, "坐标超出有效经纬度范围");
        }
    }

    private static double TransformLatitude(double longitude, double latitude)
    {
        var transformed = -100.0
            + 2.0 * longitude
            + 3.0 * latitude
            + 0.2 * latitude * latitude
            + 0.1 * longitude * latitude
            + 0.2 * Math.Sqrt(Math.Abs(longitude));
        transformed += (20.0 * Math.Sin(6.0 * longitude * Pi)
                + 20.0 * Math.Sin(2.0 * longitude * Pi))
            * 2.0
            / 3.0;
        transformed += (20.0 * Math.Sin(latitude * Pi)
                + 40.0 * Math.Sin(latitude / 3.0 * Pi))
            * 2.0
            / 3.0;
        transformed += (160.0 * Math.Sin(latitude / 12.0 * Pi)
                + 320.0 * Math.Sin(latitude * Pi / 30.0))
            * 2.0
            / 3.0;
        return transformed;
    }

    private static double TransformLongitude(double longitude, double latitude)
    {
        var transformed = 300.0
            + longitude
            + 2.0 * latitude
            + 0.1 * longitude * longitude
            + 0.1 * longitude * latitude
            + 0.1 * Math.Sqrt(Math.Abs(longitude));
        transformed += (20.0 * Math.Sin(6.0 * longitude * Pi)
                + 20.0 * Math.Sin(2.0 * longitude * Pi))
            * 2.0
            / 3.0;
        transformed += (20.0 * Math.Sin(longitude * Pi)
                + 40.0 * Math.Sin(longitude / 3.0 * Pi))
            * 2.0
            / 3.0;
        transformed += (150.0 * Math.Sin(longitude / 12.0 * Pi)
                + 300.0 * Math.Sin(longitude / 30.0 * Pi))
            * 2.0
            / 3.0;
        return transformed;
    }

    private static double DegreesToRadians(double value) => value * Pi / 180.0;
}
