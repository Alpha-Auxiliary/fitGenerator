using System.Globalization;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using FitGenerator.Native.Core;
using FluentAssertions;
using Xunit;

namespace FitGenerator.Native.Tests;

public sealed class NativeCoreServiceTests
{
    public static IEnumerable<object[]> InvalidPreviewPayloads
    {
        get
        {
            yield return new object[] { Array.Empty<byte>() };
            yield return new object[] { Encoding.UTF8.GetBytes("{") };
            yield return new object[] { Encoding.UTF8.GetBytes("null") };
            yield return new object[]
            {
                Encoding.UTF8.GetBytes(
                    """
                    {"schemaVersion":1,"algorithmVersion":1,"startTimeUtc":"2026-07-27T08:00:00Z","seed":42,"totalDistanceCm":0,"totalDurationMs":0,"laps":[],"samples":[]}
                    """),
            };
            yield return new object[]
            {
                Encoding.UTF8.GetBytes(
                    """
                    {"schemaVersion":1,"algorithmVersion":1,"startTimeUtc":"2026-07-27T08:00:00Z","seed":42,"totalDistanceCm":12345,"totalDurationMs":67890,"laps":[null],"samples":[null]}
                    """),
            };
        }
    }

    public static IEnumerable<object[]> InvalidFitPayloads
    {
        get
        {
            yield return new object[] { Array.Empty<byte>() };
            yield return new object[] { new byte[11] };
            yield return new object[] { new byte[12] };
        }
    }

    [Fact]
    public void Constructor_rejects_an_incompatible_core()
    {
        var api = new FakeNativeApi { ApiVersion = 2 };

        Action action = () => _ = new NativeCoreService(api);

        var exception = action.Should().Throw<NativeCoreException>()
            .WithMessage("*版本不兼容*")
            .Which;
        exception.Code.Should().Be(101);
    }

    [Fact]
    public void Preview_converts_platform_values_to_the_utf8_core_contract()
    {
        var utcStart = new DateTime(2026, 7, 27, 8, 0, 1, DateTimeKind.Utc)
            .AddTicks(2_345_678);
        var localStart = utcStart.ToLocalTime();
        const ulong batchSeed = 18_446_744_073_709_551_000;
        var input = new ActivityInput(
            localStart,
            new[]
            {
                new ActivityRoutePoint(39.9042, 116.4074),
                new ActivityRoutePoint(31.2304, 121.4737),
                new ActivityRoutePoint(22.5431, 114.0579),
            },
            365.5,
            58,
            187,
            3);
        var api = new FakeNativeApi
        {
            PreviewHandler = _ => CreatePreviewPayload(batchSeed),
        };
        var service = new NativeCoreService(api);

        var model = service.Preview(input, batchSeed);

        api.PreviewCallCount.Should().Be(1);
        api.GenerateFitCallCount.Should().Be(0);
        api.PreviewRequest.Should().NotBeNull();
        using var requestJson = JsonDocument.Parse(api.PreviewRequest!);
        var root = requestJson.RootElement;
        root.GetProperty("schemaVersion").GetInt32().Should().Be(1);
        root.GetProperty("startTimeUtc").GetString().Should().Be(
            utcStart.ToString("O", CultureInfo.InvariantCulture));
        root.GetProperty("paceSecondsPerKm").GetDouble().Should().Be(365.5);
        root.GetProperty("hrRest").GetInt32().Should().Be(58);
        root.GetProperty("hrMax").GetInt32().Should().Be(187);
        root.GetProperty("lapCount").GetInt32().Should().Be(3);
        root.GetProperty("variantIndex").GetInt32().Should().Be(1);
        root.GetProperty("seed").GetUInt64().Should().Be(batchSeed);
        root.GetProperty("routeMode").GetString().Should().Be("close_if_needed");

        var points = root.GetProperty("points").EnumerateArray().ToArray();
        points.Should().HaveCount(3);
        points[0].GetProperty("lat").GetDouble().Should().Be(39.9042);
        points[0].GetProperty("lng").GetDouble().Should().Be(116.4074);
        points[1].GetProperty("lat").GetDouble().Should().Be(31.2304);
        points[1].GetProperty("lng").GetDouble().Should().Be(121.4737);
        points[2].GetProperty("lat").GetDouble().Should().Be(22.5431);
        points[2].GetProperty("lng").GetDouble().Should().Be(114.0579);
        model.Seed.Should().Be(batchSeed);
    }

    [Theory]
    [InlineData(DateTimeKind.Utc)]
    [InlineData(DateTimeKind.Local)]
    [InlineData(DateTimeKind.Unspecified)]
    public void Preview_writes_every_DateTime_kind_as_rfc3339_utc(
        DateTimeKind kind)
    {
        var startTime = DateTime.SpecifyKind(
            new DateTime(2026, 7, 27, 8, 0, 1).AddTicks(2_345_678),
            kind);
        var expectedUtc = kind == DateTimeKind.Unspecified
            ? DateTime.SpecifyKind(startTime, DateTimeKind.Local).ToUniversalTime()
            : startTime.ToUniversalTime();
        var api = new FakeNativeApi
        {
            PreviewHandler = _ => CreatePreviewPayload(seed: 42),
        };
        var service = new NativeCoreService(api);

        service.Preview(CreateInput(startTime), seed: 42);

        using var requestJson = JsonDocument.Parse(api.PreviewRequest!);
        var serializedStart = requestJson.RootElement
            .GetProperty("startTimeUtc")
            .GetString();
        serializedStart.Should().Be(expectedUtc.ToString("O", CultureInfo.InvariantCulture));
        serializedStart.Should().EndWith("Z");
    }

    [Fact]
    public void GenerateFit_passes_the_exact_seed_and_selected_variant_index()
    {
        const ulong batchSeed = 9_876_543_210;
        const int variantIndex = 7;
        var expectedFit = CreateFitPayload();
        var api = new FakeNativeApi
        {
            GenerateFitHandler = _ => expectedFit,
        };
        var service = new NativeCoreService(api);

        var fit = service.GenerateFit(CreateInput(), batchSeed, variantIndex);

        fit.Should().BeSameAs(expectedFit);
        api.GenerateFitCallCount.Should().Be(1);
        api.PreviewCallCount.Should().Be(0);
        api.GenerateFitRequest.Should().NotBeNull();
        using var requestJson = JsonDocument.Parse(api.GenerateFitRequest!);
        requestJson.RootElement.GetProperty("seed").GetUInt64().Should().Be(batchSeed);
        requestJson.RootElement.GetProperty("variantIndex").GetInt32().Should().Be(variantIndex);
    }

    [Fact]
    public void Rust_json_error_keeps_its_code_and_chinese_message()
    {
        var rustError = new NativeCoreException(100, "请求数据不是有效的 JSON");
        var api = new FakeNativeApi
        {
            PreviewHandler = _ => throw rustError,
        };
        var service = new NativeCoreService(api);

        Action action = () => service.Preview(CreateInput(), 42);

        var exception = action.Should().Throw<NativeCoreException>().Which;
        exception.Should().BeSameAs(rustError);
        exception.Code.Should().Be(100);
        exception.Message.Should().Be("请求数据不是有效的 JSON");
    }

    [Fact]
    public void Unexpected_native_adapter_failure_becomes_a_domain_exception()
    {
        var api = new FakeNativeApi
        {
            GenerateFitHandler = _ => throw new InvalidOperationException("low-level failure"),
        };
        var service = new NativeCoreService(api);

        Action action = () => service.GenerateFit(CreateInput(), 42, 1);

        var exception = action.Should().Throw<NativeCoreException>()
            .WithMessage("无法调用 Rust 核心生成 FIT 文件")
            .Which;
        exception.Code.Should().Be(900);
    }

    [Theory]
    [InlineData(2, 1, "数据版本不兼容")]
    [InlineData(1, 2, "算法版本不兼容")]
    public void Preview_rejects_incompatible_response_versions(
        int schemaVersion,
        int algorithmVersion,
        string expectedMessage)
    {
        var api = new FakeNativeApi
        {
            PreviewHandler = _ => CreatePreviewPayload(
                seed: 42,
                schemaVersion: schemaVersion,
                algorithmVersion: algorithmVersion),
        };
        var service = new NativeCoreService(api);

        Action action = () => service.Preview(CreateInput(), 42);

        var exception = action.Should().Throw<NativeCoreException>().Which;
        exception.Code.Should().Be(101);
        exception.Message.Should().Contain(expectedMessage);
    }

    [Fact]
    public void Preview_rejects_a_response_for_a_different_seed()
    {
        var api = new FakeNativeApi
        {
            PreviewHandler = _ => CreatePreviewPayload(seed: 43),
        };
        var service = new NativeCoreService(api);

        Action action = () => service.Preview(CreateInput(), seed: 42);

        var exception = action.Should().Throw<NativeCoreException>()
            .WithMessage("*活动预览数据不完整*")
            .Which;
        exception.Code.Should().Be(900);
    }

    [Theory]
    [MemberData(nameof(InvalidPreviewPayloads))]
    public void Preview_rejects_empty_malformed_or_incomplete_payloads(byte[] payload)
    {
        var api = new FakeNativeApi
        {
            PreviewHandler = _ => payload,
        };
        var service = new NativeCoreService(api);

        Action action = () => service.Preview(CreateInput(), 42);

        var exception = action.Should().Throw<NativeCoreException>()
            .WithMessage("*活动预览数据*")
            .Which;
        exception.Code.Should().Be(900);
    }

    [Theory]
    [MemberData(nameof(InvalidFitPayloads))]
    public void GenerateFit_rejects_empty_or_invalid_payloads(byte[] payload)
    {
        var api = new FakeNativeApi
        {
            GenerateFitHandler = _ => payload,
        };
        var service = new NativeCoreService(api);

        Action action = () => service.GenerateFit(CreateInput(), 42, 1);

        var exception = action.Should().Throw<NativeCoreException>()
            .WithMessage("*FIT 数据无效*")
            .Which;
        exception.Code.Should().Be(900);
    }

    [Fact]
    [Trait("Category", "NativeIntegration")]
    public void Real_release_dll_supports_preview_and_fit_generation()
    {
        File.Exists(Path.Combine(AppContext.BaseDirectory, "fit_generator_core.dll"))
            .Should()
            .BeTrue("scripts/build-rust-windows.ps1 must run before native integration tests");

        var dllPath = Path.Combine(AppContext.BaseDirectory, "fit_generator_core.dll");
        var libraryHandle = NativeLibrary.Load(dllPath);
        try
        {
            var api = new NativeCoreApi();
            api.ApiVersion.Should().Be(1);
            var service = new NativeCoreService(api);

            var preview = service.Preview(CreateInput(), 987_654_321);
            var fit = service.GenerateFit(CreateInput(), 987_654_321, 1);

            preview.Samples.Should().NotBeEmpty();
            Encoding.ASCII.GetString(fit, 8, 4).Should().Be(".FIT");
        }
        finally
        {
            NativeLibrary.Free(libraryHandle);
        }
    }

    private static ActivityInput CreateInput(DateTime? startTime = null) =>
        new(
            startTime ?? new DateTime(2026, 7, 27, 8, 0, 0, DateTimeKind.Utc),
            new[]
            {
                new ActivityRoutePoint(39.9042, 116.4074),
                new ActivityRoutePoint(39.9052, 116.4084),
            },
            360,
            60,
            180,
            1);

    private static byte[] CreatePreviewPayload(
        ulong seed,
        int schemaVersion = 1,
        int algorithmVersion = 1)
    {
        var model = new ActivityModelDto(
            schemaVersion,
            algorithmVersion,
            "2026-07-27T08:00:00Z",
            seed,
            12_345,
            67_890,
            new[] { new LapModelDto(0, 0, 0, 12_345, 67_890) },
            new[] { new ActivitySampleDto(0, 0, 3_000, 80, 476_741_370, 1_388_945_652) });
        return JsonSerializer.SerializeToUtf8Bytes(model, CoreJson.SerializerOptions);
    }

    private static byte[] CreateFitPayload()
    {
        var payload = new byte[14];
        payload[0] = 14;
        payload[8] = (byte)'.';
        payload[9] = (byte)'F';
        payload[10] = (byte)'I';
        payload[11] = (byte)'T';
        return payload;
    }

    private sealed class FakeNativeApi : INativeCoreApi
    {
        public uint ApiVersion { get; init; } = 1;

        public Func<byte[], byte[]> PreviewHandler { get; init; } =
            _ => throw new InvalidOperationException("Preview handler was not configured.");

        public Func<byte[], byte[]> GenerateFitHandler { get; init; } =
            _ => throw new InvalidOperationException("GenerateFit handler was not configured.");

        public byte[]? PreviewRequest { get; private set; }

        public byte[]? GenerateFitRequest { get; private set; }

        public int PreviewCallCount { get; private set; }

        public int GenerateFitCallCount { get; private set; }

        public byte[] Preview(byte[] request)
        {
            PreviewCallCount++;
            PreviewRequest = request;
            return PreviewHandler(request);
        }

        public byte[] GenerateFit(byte[] request)
        {
            GenerateFitCallCount++;
            GenerateFitRequest = request;
            return GenerateFitHandler(request);
        }
    }
}
