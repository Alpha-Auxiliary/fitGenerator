using System.Globalization;
using System.Text;
using FitGenerator.Native.Core;
using FitGenerator.Native.Map;
using FitGenerator.Native.Services;
using FitGenerator.Native.UI;
using FluentAssertions;
using Xunit;

namespace FitGenerator.Native.Tests;

public sealed class ExportServiceTests
{
    private const string OutputDirectory = @"C:\Runs";

    private static readonly IReadOnlyList<GeoCoordinate> ReadyRoute =
        Array.AsReadOnly(new[]
        {
            new GeoCoordinate(39.9042, 116.4074),
            new GeoCoordinate(39.9052, 116.4084),
        });

    [Fact]
    public void Entire_batch_is_generated_before_any_temporary_or_final_file_is_written()
    {
        var operations = new List<string>();
        var core = new FakeCoreService(operations);
        var fileSystem = new InMemoryExportFileSystem(OutputDirectory, operations);
        var service = new ExportService(core, fileSystem);

        service.Export(CreateRequest(exportCount: 3));

        operations.Take(3).Should().Equal(
            "generate:1",
            "generate:2",
            "generate:3");
        operations.FindIndex(operation => operation.StartsWith("write:", StringComparison.Ordinal))
            .Should()
            .BeGreaterThan(2);
        fileSystem.ReadFile(Path.Combine(OutputDirectory, "run_1.fit"))
            .Should()
            .Equal(FitBytes(1));
        fileSystem.ReadFile(Path.Combine(OutputDirectory, "run_2.fit"))
            .Should()
            .Equal(FitBytes(2));
        fileSystem.ReadFile(Path.Combine(OutputDirectory, "run_3.fit"))
            .Should()
            .Equal(FitBytes(3));
        fileSystem.Paths.Should().OnlyContain(path => path.EndsWith(".fit", StringComparison.Ordinal));
    }

    [Fact]
    public void Variant_two_generation_failure_leaves_no_temporary_or_final_files()
    {
        var operations = new List<string>();
        var core = new FakeCoreService(operations) { FailingVariant = 2 };
        var fileSystem = new InMemoryExportFileSystem(OutputDirectory, operations);
        var service = new ExportService(core, fileSystem);

        Action action = () => service.Export(CreateRequest(exportCount: 3));

        action.Should().Throw<NativeCoreException>().WithMessage("variant 2 failed");
        operations.Should().Equal("generate:1", "generate:2");
        fileSystem.Paths.Should().BeEmpty();
    }

    [Fact]
    public void One_file_uses_run_fit_and_multiple_files_use_numbered_names()
    {
        var singleFileSystem = new InMemoryExportFileSystem(OutputDirectory);
        var single = new ExportService(new FakeCoreService(), singleFileSystem);
        single.Export(CreateRequest(exportCount: 1));

        var batchFileSystem = new InMemoryExportFileSystem(OutputDirectory);
        var batch = new ExportService(new FakeCoreService(), batchFileSystem);
        batch.Export(CreateRequest(exportCount: 2));

        singleFileSystem.Paths.Should().Equal(Path.Combine(OutputDirectory, "run.fit"));
        batchFileSystem.Paths.Should().BeEquivalentTo(new[]
        {
            Path.Combine(OutputDirectory, "run_1.fit"),
            Path.Combine(OutputDirectory, "run_2.fit"),
        });
    }

    [Fact]
    public void Existing_file_is_replaced_atomically_without_delete_then_move()
    {
        var finalPath = Path.Combine(OutputDirectory, "run.fit");
        var operations = new List<string>();
        var fileSystem = new InMemoryExportFileSystem(OutputDirectory, operations);
        fileSystem.AddFile(finalPath, Encoding.ASCII.GetBytes("old"));
        var service = new ExportService(new FakeCoreService(operations), fileSystem);

        service.Export(CreateRequest(exportCount: 1));

        fileSystem.ReadFile(finalPath).Should().Equal(FitBytes(1));
        operations.Should().Contain($"replace:{finalPath}");
        operations.Should().NotContain($"delete:{finalPath}");
        operations.Should().NotContain($"move:{finalPath}");
        fileSystem.Paths.Should().Equal(finalPath);
    }

    [Fact]
    public void Commit_failure_restores_existing_files_and_removes_work_files()
    {
        var firstPath = Path.Combine(OutputDirectory, "run_1.fit");
        var secondPath = Path.Combine(OutputDirectory, "run_2.fit");
        var firstOriginal = Encoding.ASCII.GetBytes("first-old");
        var secondOriginal = Encoding.ASCII.GetBytes("second-old");
        var fileSystem = new InMemoryExportFileSystem(OutputDirectory)
        {
            FailingReplaceDestination = secondPath,
        };
        fileSystem.AddFile(firstPath, firstOriginal);
        fileSystem.AddFile(secondPath, secondOriginal);
        var service = new ExportService(new FakeCoreService(), fileSystem);

        Action action = () => service.Export(CreateRequest(exportCount: 2));

        action.Should().Throw<ExportServiceException>();
        fileSystem.ReadFile(firstPath).Should().Equal(firstOriginal);
        fileSystem.ReadFile(secondPath).Should().Equal(secondOriginal);
        fileSystem.Paths.Should().BeEquivalentTo(new[] { firstPath, secondPath });
    }

    [Fact]
    public void New_file_commit_failure_removes_prior_finals_and_all_work_files()
    {
        var secondPath = Path.Combine(OutputDirectory, "run_2.fit");
        var fileSystem = new InMemoryExportFileSystem(OutputDirectory)
        {
            FailingMoveDestination = secondPath,
        };
        var service = new ExportService(new FakeCoreService(), fileSystem);

        Action action = () => service.Export(CreateRequest(exportCount: 3));

        action.Should().Throw<ExportServiceException>();
        fileSystem.Paths.Should().BeEmpty();
    }

    [Fact]
    public void Temporary_write_failure_cleans_files_written_earlier_in_the_batch()
    {
        var fileSystem = new InMemoryExportFileSystem(OutputDirectory)
        {
            FailingWriteNumber = 2,
        };
        var service = new ExportService(new FakeCoreService(), fileSystem);

        Action action = () => service.Export(CreateRequest(exportCount: 3));

        action.Should().Throw<ExportServiceException>();
        fileSystem.Paths.Should().BeEmpty();
    }

    [Fact]
    public void Cleanup_failure_is_reported_instead_of_claiming_clean_success()
    {
        var finalPath = Path.Combine(OutputDirectory, "run.fit");
        var fileSystem = new InMemoryExportFileSystem(OutputDirectory)
        {
            FailingDeleteSuffix = ".bak",
        };
        fileSystem.AddFile(finalPath, Encoding.ASCII.GetBytes("old"));
        var service = new ExportService(new FakeCoreService(), fileSystem);

        Action action = () => service.Export(CreateRequest(exportCount: 1));

        action.Should()
            .Throw<ExportServiceException>()
            .WithMessage("FIT 文件已写入*");
        fileSystem.ReadFile(finalPath).Should().Equal(FitBytes(1));
        fileSystem.Paths.Should().ContainSingle(
            path => path.EndsWith(".bak", StringComparison.Ordinal));
        fileSystem.Paths.Should().NotContain(
            path => path.EndsWith(".tmp", StringComparison.Ordinal));
    }

    [Fact]
    public void Rollback_failure_reports_both_original_and_recovery_errors()
    {
        var firstPath = Path.Combine(OutputDirectory, "run_1.fit");
        var secondPath = Path.Combine(OutputDirectory, "run_2.fit");
        var fileSystem = new InMemoryExportFileSystem(OutputDirectory)
        {
            FailingMoveDestination = secondPath,
            FailingDeletePath = firstPath,
        };
        var service = new ExportService(new FakeCoreService(), fileSystem);

        Action action = () => service.Export(CreateRequest(exportCount: 2));

        var exception = action.Should()
            .Throw<ExportServiceException>()
            .WithMessage("无法写入 FIT 文件*无法完全恢复*")
            .Which;
        var failures = exception.InnerException.Should()
            .BeOfType<AggregateException>()
            .Which
            .Flatten()
            .InnerExceptions;
        failures.Should().Contain(error => error.Message == "Simulated move failure.");
        failures.Should().Contain(error => error.Message == "Simulated delete failure.");
        fileSystem.Paths.Should().Equal(firstPath);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(21)]
    public void Export_count_outside_one_to_twenty_is_rejected_before_generation(int count)
    {
        var core = new FakeCoreService();
        var service = new ExportService(
            core,
            new InMemoryExportFileSystem(OutputDirectory));

        Action action = () => service.Export(CreateRequest(exportCount: count));

        var exception = action.Should().Throw<ExportServiceException>().Which;
        exception.Kind.Should().Be(ExportErrorKind.InvalidRequest);
        core.GeneratedVariants.Should().BeEmpty();
    }

    [Fact]
    public void Invalid_output_directory_returns_platform_error_and_preserves_view_state()
    {
        var core = new FakeCoreService();
        var export = new ExportService(
            core,
            new InMemoryExportFileSystem(OutputDirectory));
        var settings = new FakeSettingsService
        {
            Settings = AppSettings.Default with
            {
                ExportDirectory = @"C:\Missing",
                ExportCount = 1,
            },
            Route = new SavedRoute { Wgs84Points = ReadyRoute },
        };
        var viewModel = new MainViewModel(
            core,
            settings,
            new FakeRouteMapController(),
            export,
            new FakeApplicationClock(),
            new FakeSearchService());
        viewModel.Preview();
        var route = viewModel.State.RoutePoints.ToArray();
        var seed = viewModel.State.PreviewSeed;
        var model = viewModel.State.PreviewModel;

        viewModel.Export();

        viewModel.State.Phase.Should().Be(MainViewPhase.Ready);
        viewModel.State.RoutePoints.Should().Equal(route);
        viewModel.State.PreviewSeed.Should().Be(seed);
        viewModel.State.PreviewModel.Should().BeSameAs(model);
        viewModel.State.StatusMessage.Should().Contain("导出文件夹不存在");
        core.GeneratedVariants.Should().BeEmpty();
    }

    private static ExportRequest CreateRequest(int exportCount) => new(
        new ActivityInput(
            new DateTime(2026, 8, 4, 8, 0, 0, DateTimeKind.Local),
            ReadyRoute
                .Select(point => new ActivityRoutePoint(point.Latitude, point.Longitude))
                .ToArray(),
            360,
            60,
            180,
            1),
        Seed: 987_654_321,
        ExportCount: exportCount,
        ExportDirectory: OutputDirectory);

    private static byte[] FitBytes(int variantIndex) =>
        Encoding.ASCII.GetBytes($"fit-{variantIndex}");

    private sealed class FakeCoreService(List<string>? operations = null) : INativeCoreService
    {
        private readonly List<string> _operations = operations ?? [];

        internal int? FailingVariant { get; init; }

        internal List<int> GeneratedVariants { get; } = [];

        public ActivityModelDto Preview(ActivityInput input, ulong seed) => new(
            SchemaVersion: 1,
            AlgorithmVersion: 1,
            StartTimeUtc: "2026-08-04T00:00:00Z",
            Seed: seed,
            TotalDistanceCm: 12_345,
            TotalDurationMs: 74_070,
            Laps: new[] { new LapModelDto(1, 0, 1, 12_345, 74_070) },
            Samples: new[]
            {
                new ActivitySampleDto(0, 0, 1_667, 60, 476_741_370, 1_388_945_652),
                new ActivitySampleDto(
                    74_070,
                    12_345,
                    1_667,
                    150,
                    476_753_301,
                    1_388_957_583),
            });

        public byte[] GenerateFit(ActivityInput input, ulong seed, int variantIndex)
        {
            GeneratedVariants.Add(variantIndex);
            _operations.Add($"generate:{variantIndex}");
            if (FailingVariant == variantIndex)
            {
                throw new NativeCoreException(100, $"variant {variantIndex} failed");
            }

            return FitBytes(variantIndex);
        }
    }

    private sealed class InMemoryExportFileSystem : IExportFileSystem
    {
        private readonly HashSet<string> _directories = new(StringComparer.OrdinalIgnoreCase);
        private readonly Dictionary<string, byte[]> _files = new(StringComparer.OrdinalIgnoreCase);
        private readonly List<string> _operations;
        private int _writeCount;

        internal InMemoryExportFileSystem(
            string existingDirectory,
            List<string>? operations = null)
        {
            _directories.Add(existingDirectory);
            _operations = operations ?? [];
        }

        internal string? FailingReplaceDestination { get; init; }

        internal string? FailingMoveDestination { get; init; }

        internal string? FailingDeletePath { get; init; }

        internal string? FailingDeleteSuffix { get; init; }

        internal int? FailingWriteNumber { get; init; }

        internal IReadOnlyCollection<string> Paths => _files.Keys.ToArray();

        public bool DirectoryExists(string path) => _directories.Contains(path);

        public bool FileExists(string path) => _files.ContainsKey(path);

        public void WriteNewFile(string path, byte[] contents)
        {
            _writeCount++;
            if (FailingWriteNumber == _writeCount)
            {
                throw new IOException("Simulated write failure.");
            }

            if (_files.ContainsKey(path))
            {
                throw new IOException("File already exists.");
            }

            _operations.Add($"write:{path}");
            _files.Add(path, contents.ToArray());
        }

        public void MoveFile(string sourcePath, string destinationPath)
        {
            if (string.Equals(
                    FailingMoveDestination,
                    destinationPath,
                    StringComparison.OrdinalIgnoreCase))
            {
                throw new IOException("Simulated move failure.");
            }

            if (!_files.Remove(sourcePath, out var contents)
                || _files.ContainsKey(destinationPath))
            {
                throw new IOException("Move failed.");
            }

            _operations.Add($"move:{destinationPath}");
            _files.Add(destinationPath, contents);
        }

        public void ReplaceFile(
            string sourcePath,
            string destinationPath,
            string? backupPath)
        {
            if (string.Equals(
                    FailingReplaceDestination,
                    destinationPath,
                    StringComparison.OrdinalIgnoreCase))
            {
                throw new IOException("Simulated replace failure.");
            }

            if (!_files.TryGetValue(sourcePath, out var replacement)
                || !_files.TryGetValue(destinationPath, out var previous))
            {
                throw new IOException("Replace failed.");
            }

            if (backupPath is not null)
            {
                _files.Add(backupPath, previous.ToArray());
            }

            _files[destinationPath] = replacement;
            _files.Remove(sourcePath);
            _operations.Add($"replace:{destinationPath}");
        }

        public void DeleteFile(string path)
        {
            if (string.Equals(
                    FailingDeletePath,
                    path,
                    StringComparison.OrdinalIgnoreCase)
                || (FailingDeleteSuffix is not null
                    && path.EndsWith(
                        FailingDeleteSuffix,
                        StringComparison.OrdinalIgnoreCase)))
            {
                throw new IOException("Simulated delete failure.");
            }

            _operations.Add($"delete:{path}");
            _files.Remove(path);
        }

        internal void AddFile(string path, byte[] contents) =>
            _files.Add(path, contents.ToArray());

        internal byte[] ReadFile(string path) => _files[path].ToArray();
    }

    private sealed class FakeSettingsService : ISettingsService
    {
        internal AppSettings Settings { get; init; } = AppSettings.Default;

        internal SavedRoute Route { get; init; } = SavedRoute.Empty;

        public AppSettings LoadSettings() => Settings;

        public void SaveSettings(AppSettings settings)
        {
        }

        public SavedRoute LoadLastRoute() => Route;

        public void SaveLastRoute(SavedRoute route)
        {
        }
    }

    private sealed class FakeRouteMapController : IRouteMapController
    {
        private readonly List<GeoCoordinate> _route = [];

        public IReadOnlyList<GeoCoordinate> Wgs84RoutePoints =>
            Array.AsReadOnly(_route.ToArray());

        public void StartDrawing()
        {
        }

        public void StopDrawing()
        {
        }

        public void SetWgs84Route(IReadOnlyList<GeoCoordinate> points)
        {
            _route.Clear();
            _route.AddRange(points);
        }

        public void UndoPoint()
        {
            if (_route.Count > 0)
            {
                _route.RemoveAt(_route.Count - 1);
            }
        }

        public void ClearRoute() => _route.Clear();

        public void SwitchProvider(MapProviderDefinition provider)
        {
        }

        public void RestoreViewport(MapViewportSettings viewport)
        {
        }

        public MapViewportSettings CaptureViewport() => MapViewportSettings.Default;

        public void ZoomToRouteOrDefault()
        {
        }

        public void CenterOnWgs84(GeoCoordinate coordinate)
        {
        }
    }

    private sealed class FakeApplicationClock : IApplicationClock
    {
        public DateTime LocalNow { get; } =
            new(2026, 8, 4, 8, 0, 0, DateTimeKind.Local);
    }

    private sealed class FakeSearchService : ISearchService
    {
        public Task<IReadOnlyList<SearchResult>> SearchAsync(
            string query,
            MapProviderDefinition provider,
            CultureInfo uiCulture,
            CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<SearchResult>>(Array.Empty<SearchResult>());

        public Task<ProviderConnectionResult> TestConnectionAsync(
            MapProviderDefinition provider,
            CancellationToken cancellationToken) =>
            Task.FromResult(new ProviderConnectionResult(true, "连接成功"));
    }
}
