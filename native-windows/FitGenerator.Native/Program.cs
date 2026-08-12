using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;
using FitGenerator.Native.Core;

namespace FitGenerator.Native;

internal static class Program
{
    private const ulong SelfTestSeed = 987654321;
    private const int SelfTestVariantIndex = 1;
    private const int ExpectedSchemaVersion = 1;
    private const int ExpectedAlgorithmVersion = 1;
    private const string SelfTestRequestJson = """
        {
          "schemaVersion": 1,
          "startTimeUtc": "2026-07-27T08:00:00Z",
          "points": [
            { "lat": 39.9042, "lng": 116.4074 },
            { "lat": 39.9052, "lng": 116.4084 }
          ],
          "paceSecondsPerKm": 330.0,
          "hrRest": 55,
          "hrMax": 190,
          "lapCount": 2,
          "variantIndex": 1,
          "seed": 987654321,
          "routeMode": "close_if_needed"
        }
        """;

    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length == 1
            && string.Equals(args[0], "--self-test", StringComparison.Ordinal))
        {
            return RunSelfTest();
        }

        try
        {
            ApplicationConfiguration.Initialize();
            Application.Run(new MainForm());
            return 0;
        }
        catch (Exception exception)
        {
            MessageBox.Show(
                $"Windows 客户端无法启动：{exception.Message}\n\n"
                + "请确认使用的是完整的 Windows 10/11 x64 便携版；如果问题持续，请重新下载。",
                "FIT 轨迹生成工具",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            return 1;
        }
    }

    private static int RunSelfTest()
    {
        try
        {
            EnsureSupportedPlatform();

            // Construction loads fit_generator_core.dll and performs the API-version handshake.
            var core = new NativeCoreService();
            var request = JsonSerializer.Deserialize<CoreRequestDto>(
                    SelfTestRequestJson,
                    CoreJson.SerializerOptions)
                ?? throw new InvalidOperationException("无法读取自检请求。");

            if (request.SchemaVersion != 1
                || request.Seed != SelfTestSeed
                || request.VariantIndex != SelfTestVariantIndex
                || !string.Equals(request.RouteMode, "close_if_needed", StringComparison.Ordinal)
                || request.Points is not { Count: 2 })
            {
                throw new InvalidOperationException("自检请求与标准样例不一致。");
            }

            var startTime = DateTimeOffset.Parse(
                request.StartTimeUtc,
                CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal).UtcDateTime;
            var activity = new ActivityInput(
                startTime,
                request.Points
                    .Select(point => new ActivityRoutePoint(point.Lat, point.Lng))
                    .ToArray(),
                request.PaceSecondsPerKm,
                request.HrRest,
                request.HrMax,
                request.LapCount);

            var preview = core.Preview(activity, SelfTestSeed);
            if (preview.SchemaVersion != ExpectedSchemaVersion
                || preview.AlgorithmVersion != ExpectedAlgorithmVersion)
            {
                throw new InvalidOperationException("自检返回的数据或算法版本不兼容。");
            }

            if (preview.Samples is not { Count: > 0 })
            {
                throw new InvalidOperationException("自检预览未返回采样点。");
            }

            var fit = core.GenerateFit(activity, SelfTestSeed, SelfTestVariantIndex);
            if (!HasFitSignature(fit))
            {
                throw new InvalidOperationException("自检生成的 FIT 文件签名无效。");
            }

            return 0;
        }
        catch (Exception exception)
        {
            Console.Error.WriteLine($"自检失败：{exception.Message}");
            return 2;
        }
    }

    private static void EnsureSupportedPlatform()
    {
        if (!OperatingSystem.IsWindowsVersionAtLeast(10))
        {
            throw new PlatformNotSupportedException("自检仅支持 Windows 10/11 x64。");
        }

        if (RuntimeInformation.OSArchitecture != Architecture.X64
            || RuntimeInformation.ProcessArchitecture != Architecture.X64)
        {
            throw new PlatformNotSupportedException("自检需要 Windows x64 系统与 x64 进程。");
        }
    }

    private static bool HasFitSignature(byte[] payload) =>
        payload.Length >= 12
        && payload[8] == (byte)'.'
        && payload[9] == (byte)'F'
        && payload[10] == (byte)'I'
        && payload[11] == (byte)'T';
}
