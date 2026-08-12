using System.Text.Json;
using System.Text.Json.Serialization;

namespace FitGenerator.Native.Core;

internal sealed record GeoPointDto(double Lat, double Lng);

internal sealed record CoreRequestDto(
    int SchemaVersion,
    string StartTimeUtc,
    IReadOnlyList<GeoPointDto> Points,
    double PaceSecondsPerKm,
    int HrRest,
    int HrMax,
    int LapCount,
    int VariantIndex,
    ulong Seed,
    string RouteMode);

internal sealed record ActivitySampleDto(
    ulong TimeMs,
    ulong DistanceCm,
    uint SpeedMmPerSec,
    byte HeartRateBpm,
    int PositionLatSemicircles,
    int PositionLongSemicircles);

internal sealed record LapModelDto(
    int Index,
    int StartSample,
    int EndSample,
    ulong DistanceCm,
    ulong DurationMs);

internal sealed record ActivityModelDto(
    int SchemaVersion,
    int AlgorithmVersion,
    string StartTimeUtc,
    ulong Seed,
    ulong TotalDistanceCm,
    ulong TotalDurationMs,
    IReadOnlyList<LapModelDto> Laps,
    IReadOnlyList<ActivitySampleDto> Samples);

internal static class CoreJson
{
    internal static JsonSerializerOptions SerializerOptions { get; } = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = false,
        NumberHandling = JsonNumberHandling.Strict,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
    };
}

internal sealed class NativeCoreException : Exception
{
    internal NativeCoreException(int code, string message)
        : base(message)
    {
        Code = code;
    }

    internal int Code { get; }
}
