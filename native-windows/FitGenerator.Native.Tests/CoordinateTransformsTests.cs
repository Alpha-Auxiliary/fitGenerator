using FitGenerator.Native.Map;
using FluentAssertions;
using Xunit;

namespace FitGenerator.Native.Tests;

public sealed class CoordinateTransformsTests
{
    [Theory]
    [InlineData(39.9042, 116.4074)]
    [InlineData(31.2304, 121.4737)]
    public void Wgs84_to_gcj02_round_trip_stays_within_two_meters(
        double latitude,
        double longitude)
    {
        var original = new GeoCoordinate(latitude, longitude);

        var gcj = CoordinateTransforms.Wgs84ToGcj02(original);
        var restored = CoordinateTransforms.Gcj02ToWgs84(gcj);

        CoordinateTransforms.DistanceMeters(restored, original).Should().BeLessThan(2.0);
        CoordinateTransforms.DistanceMeters(gcj, original).Should().BeGreaterThan(100.0);
    }

    [Theory]
    [InlineData(39.910226, 116.403714)]
    [InlineData(31.228457, 121.478223)]
    public void Gcj02_to_bd09_round_trip_stays_within_two_meters(
        double latitude,
        double longitude)
    {
        var original = new GeoCoordinate(latitude, longitude);

        var bd09 = CoordinateTransforms.Gcj02ToBd09(original);
        var restored = CoordinateTransforms.Bd09ToGcj02(bd09);

        CoordinateTransforms.DistanceMeters(restored, original).Should().BeLessThan(2.0);
    }

    [Theory]
    [InlineData(51.5074, -0.1278)]
    [InlineData(-33.8688, 151.2093)]
    public void Gcj02_conversion_does_not_shift_coordinates_outside_China(
        double latitude,
        double longitude)
    {
        var original = new GeoCoordinate(latitude, longitude);

        CoordinateTransforms.Wgs84ToGcj02(original).Should().Be(original);
        CoordinateTransforms.Gcj02ToWgs84(original).Should().Be(original);
    }

    [Theory]
    [InlineData(90.0, 180.0)]
    [InlineData(-90.0, -180.0)]
    public void Valid_geographic_extremes_outside_China_remain_unchanged(
        double latitude,
        double longitude)
    {
        var original = new GeoCoordinate(latitude, longitude);

        CoordinateTransforms.Wgs84ToGcj02(original).Should().Be(original);
        CoordinateTransforms.Gcj02ToWgs84(original).Should().Be(original);
    }

    [Fact]
    public void Non_finite_or_out_of_range_coordinates_are_rejected()
    {
        Action nanAction = () => CoordinateTransforms.Wgs84ToGcj02(
            new GeoCoordinate(double.NaN, 116.4074));
        Action infinityAction = () => CoordinateTransforms.Gcj02ToBd09(
            new GeoCoordinate(39.9042, double.PositiveInfinity));
        Action rangeAction = () => CoordinateTransforms.DistanceMeters(
            new GeoCoordinate(91.0, 0.0),
            new GeoCoordinate(0.0, 0.0));

        nanAction.Should().Throw<ArgumentOutOfRangeException>();
        infinityAction.Should().Throw<ArgumentOutOfRangeException>();
        rangeAction.Should().Throw<ArgumentOutOfRangeException>();
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(2)]
    public void Coordinate_system_dispatch_round_trips_to_canonical_wgs84(
        int coordinateSystemValue)
    {
        var original = new GeoCoordinate(39.9042, 116.4074);
        var coordinateSystem = (CoordinateSystem)coordinateSystemValue;

        var providerCoordinate = CoordinateTransforms.FromWgs84(original, coordinateSystem);
        var restored = CoordinateTransforms.ToWgs84(providerCoordinate, coordinateSystem);

        CoordinateTransforms.DistanceMeters(restored, original).Should().BeLessThan(2.0);
    }
}
