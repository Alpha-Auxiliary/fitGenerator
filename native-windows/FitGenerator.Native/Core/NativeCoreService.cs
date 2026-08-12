using System.Globalization;
using System.Text.Json;

namespace FitGenerator.Native.Core;

internal sealed record ActivityRoutePoint(double Latitude, double Longitude);

internal sealed record ActivityInput(
    DateTime StartTime,
    IReadOnlyList<ActivityRoutePoint> RoutePoints,
    double PaceSecondsPerKilometer,
    int RestingHeartRate,
    int MaximumHeartRate,
    int LapCount);

internal interface INativeCoreService
{
    ActivityModelDto Preview(ActivityInput input, ulong seed);

    byte[] GenerateFit(ActivityInput input, ulong seed, int variantIndex);
}

internal sealed class NativeCoreService : INativeCoreService
{
    private const uint SupportedApiVersion = 1;
    private const int SupportedSchemaVersion = 1;
    private const int SupportedAlgorithmVersion = 1;
    private const int PreviewVariantIndex = 1;
    private const int InvalidInputCode = 100;
    private const int IncompatibleVersionCode = 101;
    private const int InternalErrorCode = 900;
    private const string RouteMode = "close_if_needed";

    private readonly INativeCoreApi _api;

    internal NativeCoreService()
        : this(new NativeCoreApi())
    {
    }

    internal NativeCoreService(INativeCoreApi api)
    {
        ArgumentNullException.ThrowIfNull(api);

        _api = api;
        var apiVersion = InvokeNative(
            () => api.ApiVersion,
            "无法读取 Rust 核心 API 版本");
        if (apiVersion != SupportedApiVersion)
        {
            throw new NativeCoreException(
                IncompatibleVersionCode,
                $"Rust 核心 API 版本不兼容：需要 {SupportedApiVersion}，实际 {apiVersion}");
        }
    }

    public ActivityModelDto Preview(ActivityInput input, ulong seed)
    {
        var request = SerializeRequest(input, seed, PreviewVariantIndex);
        var payload = InvokeNative(
            () => _api.Preview(request),
            "无法调用 Rust 核心生成活动预览");

        return DeserializePreview(payload, seed);
    }

    public byte[] GenerateFit(ActivityInput input, ulong seed, int variantIndex)
    {
        var request = SerializeRequest(input, seed, variantIndex);
        var payload = InvokeNative(
            () => _api.GenerateFit(request),
            "无法调用 Rust 核心生成 FIT 文件");

        if (!HasFitSignature(payload))
        {
            throw InvalidNativePayload("Rust 核心返回的 FIT 数据无效");
        }

        return payload;
    }

    private static byte[] SerializeRequest(ActivityInput input, ulong seed, int variantIndex)
    {
        if (input is null)
        {
            throw new NativeCoreException(InvalidInputCode, "活动参数不能为空");
        }

        if (input.RoutePoints is null)
        {
            throw new NativeCoreException(InvalidInputCode, "活动路线不能为空");
        }

        var points = new GeoPointDto[input.RoutePoints.Count];
        for (var index = 0; index < input.RoutePoints.Count; index++)
        {
            var point = input.RoutePoints[index];
            if (point is null)
            {
                throw new NativeCoreException(InvalidInputCode, "活动路线包含空坐标");
            }

            points[index] = new GeoPointDto(point.Latitude, point.Longitude);
        }

        var request = new CoreRequestDto(
            SupportedSchemaVersion,
            ToUtcString(input.StartTime),
            points,
            input.PaceSecondsPerKilometer,
            input.RestingHeartRate,
            input.MaximumHeartRate,
            input.LapCount,
            variantIndex,
            seed,
            RouteMode);

        try
        {
            return JsonSerializer.SerializeToUtf8Bytes(request, CoreJson.SerializerOptions);
        }
        catch (Exception exception) when (exception is JsonException or NotSupportedException or ArgumentException)
        {
            throw new NativeCoreException(InvalidInputCode, "活动参数无法转换为 Rust 核心请求");
        }
    }

    private static ActivityModelDto DeserializePreview(byte[] payload, ulong expectedSeed)
    {
        if (payload is null || payload.Length == 0)
        {
            throw InvalidNativePayload("Rust 核心返回的活动预览数据为空");
        }

        ActivityModelDto? model;
        try
        {
            model = JsonSerializer.Deserialize<ActivityModelDto>(
                payload,
                CoreJson.SerializerOptions);
        }
        catch (Exception exception) when (exception is JsonException or NotSupportedException)
        {
            throw InvalidNativePayload("Rust 核心返回的活动预览数据无效");
        }

        if (model is null)
        {
            throw InvalidNativePayload("Rust 核心返回的活动预览数据无效");
        }

        if (model.SchemaVersion != SupportedSchemaVersion)
        {
            throw new NativeCoreException(
                IncompatibleVersionCode,
                $"Rust 核心数据版本不兼容：需要 {SupportedSchemaVersion}，实际 {model.SchemaVersion}");
        }

        if (model.AlgorithmVersion != SupportedAlgorithmVersion)
        {
            throw new NativeCoreException(
                IncompatibleVersionCode,
                $"Rust 核心算法版本不兼容：需要 {SupportedAlgorithmVersion}，实际 {model.AlgorithmVersion}");
        }

        if (model.Seed != expectedSeed
            || string.IsNullOrWhiteSpace(model.StartTimeUtc)
            || model.Laps is null
            || model.Laps.Count == 0
            || ContainsNull(model.Laps)
            || model.Samples is null
            || model.Samples.Count == 0
            || ContainsNull(model.Samples))
        {
            throw InvalidNativePayload("Rust 核心返回的活动预览数据不完整");
        }

        return model;
    }

    private static string ToUtcString(DateTime startTime)
    {
        var utc = startTime.Kind == DateTimeKind.Utc
            ? startTime
            : startTime.ToUniversalTime();
        return utc.ToString("O", CultureInfo.InvariantCulture);
    }

    private static bool ContainsNull<T>(IReadOnlyList<T> values)
        where T : class
    {
        for (var index = 0; index < values.Count; index++)
        {
            if (values[index] is null)
            {
                return true;
            }
        }

        return false;
    }

    private static bool HasFitSignature(byte[]? payload) =>
        payload is { Length: >= 12 }
        && payload[8] == (byte)'.'
        && payload[9] == (byte)'F'
        && payload[10] == (byte)'I'
        && payload[11] == (byte)'T';

    private static T InvokeNative<T>(Func<T> operation, string failureMessage)
    {
        try
        {
            return operation();
        }
        catch (NativeCoreException)
        {
            throw;
        }
        catch (Exception)
        {
            throw new NativeCoreException(InternalErrorCode, failureMessage);
        }
    }

    private static NativeCoreException InvalidNativePayload(string message) =>
        new(InternalErrorCode, message);
}
