using System.Security;
using FitGenerator.Native.Core;
using FitGenerator.Native.UI;

namespace FitGenerator.Native.Services;

internal enum ExportErrorKind
{
    InvalidRequest,
    InvalidDirectory,
    PermissionDenied,
    DiskFull,
    FileSystem,
}

internal sealed class ExportServiceException : Exception
{
    internal ExportServiceException(
        ExportErrorKind kind,
        string message,
        Exception? innerException = null)
        : base(message, innerException)
    {
        Kind = kind;
    }

    internal ExportErrorKind Kind { get; }
}

internal interface IExportFileSystem
{
    bool DirectoryExists(string path);

    bool FileExists(string path);

    void WriteNewFile(string path, byte[] contents);

    void MoveFile(string sourcePath, string destinationPath);

    void ReplaceFile(
        string sourcePath,
        string destinationPath,
        string? backupPath);

    void DeleteFile(string path);
}

internal sealed class ExportTemporaryFileException : IOException
{
    internal ExportTemporaryFileException(
        Exception writeException,
        Exception cleanupException)
        : base(
            "Temporary export file could not be removed after a write failure.",
            new AggregateException(writeException, cleanupException))
    {
        WriteException = writeException;
    }

    internal Exception WriteException { get; }
}

internal sealed class PhysicalExportFileSystem : IExportFileSystem
{
    public bool DirectoryExists(string path) => Directory.Exists(path);

    public bool FileExists(string path) => File.Exists(path);

    public void WriteNewFile(string path, byte[] contents)
    {
        var ownsFile = false;
        try
        {
            using var stream = new FileStream(
                path,
                FileMode.CreateNew,
                FileAccess.Write,
                FileShare.None,
                bufferSize: 4096,
                options: FileOptions.WriteThrough);
            ownsFile = true;
            stream.Write(contents);
            stream.Flush(flushToDisk: true);
        }
        catch (Exception writeException)
        {
            if (ownsFile)
            {
                try
                {
                    File.Delete(path);
                }
                catch (Exception cleanupException)
                {
                    throw new ExportTemporaryFileException(
                        writeException,
                        cleanupException);
                }
            }

            throw;
        }
    }

    public void MoveFile(string sourcePath, string destinationPath) =>
        File.Move(sourcePath, destinationPath);

    public void ReplaceFile(
        string sourcePath,
        string destinationPath,
        string? backupPath) =>
        File.Replace(
            sourcePath,
            destinationPath,
            backupPath,
            ignoreMetadataErrors: true);

    public void DeleteFile(string path) => File.Delete(path);
}

internal sealed class ExportService : IExportService
{
    private const int MinimumExportCount = 1;
    private const int MaximumExportCount = 20;
    private const int ErrorHandleDiskFull = 39;
    private const int ErrorDiskFull = 112;

    private readonly INativeCoreService _core;
    private readonly IExportFileSystem _fileSystem;

    internal ExportService(INativeCoreService core)
        : this(core, new PhysicalExportFileSystem())
    {
    }

    internal ExportService(
        INativeCoreService core,
        IExportFileSystem fileSystem)
    {
        ArgumentNullException.ThrowIfNull(core);
        ArgumentNullException.ThrowIfNull(fileSystem);

        _core = core;
        _fileSystem = fileSystem;
    }

    public void Export(ExportRequest request)
    {
        var outputDirectory = ValidateRequest(request);

        // Keep batch identity in Rust: every variant receives the same seed and
        // only its one-based variant index changes. No file can appear until the
        // complete batch has been generated successfully in memory.
        var generatedFiles = new byte[request.ExportCount][];
        for (var index = 0; index < generatedFiles.Length; index++)
        {
            generatedFiles[index] = _core.GenerateFit(
                request.Wgs84Activity,
                request.Seed,
                variantIndex: index + 1);
        }

        var entries = CreateEntries(outputDirectory, request.ExportCount);
        try
        {
            for (var index = 0; index < entries.Length; index++)
            {
                var entry = entries[index];
                try
                {
                    _fileSystem.WriteNewFile(
                        entry.TemporaryPath,
                        generatedFiles[index]);
                    entry.TemporaryOwned = true;
                }
                catch (ExportTemporaryFileException)
                {
                    // PhysicalExportFileSystem created this unique path before
                    // its write and first cleanup both failed. Retain ownership
                    // so transaction cleanup can retry and report the outcome.
                    entry.TemporaryOwned = true;
                    throw;
                }
            }

            foreach (var entry in entries)
            {
                Commit(entry);
            }
        }
        catch (Exception exception) when (IsFileOperationException(exception))
        {
            var rollbackException = Rollback(entries);
            var cleanupException = CleanupTemporaryFiles(entries);
            var originalError = CreatePlatformException(exception);

            if (rollbackException is not null)
            {
                throw new ExportServiceException(
                    ExportErrorKind.FileSystem,
                    $"{originalError.Message} 同时无法完全恢复原有文件；"
                        + "已保留仍可用的备份（如有），临时文件也可能残留。"
                        + "请检查导出文件夹。",
                    CombineExceptions(
                        exception,
                        rollbackException,
                        cleanupException));
            }

            if (cleanupException is not null)
            {
                throw new ExportServiceException(
                    originalError.Kind,
                    $"{originalError.Message} 原有文件已恢复，但临时文件清理不完整；"
                        + "请关闭可能占用文件的程序后检查导出文件夹。",
                    CombineExceptions(exception, cleanupException));
            }

            throw originalError;
        }

        // Backups are deleted only after the whole batch commits. If cleanup is
        // incomplete, report that distinct outcome instead of claiming a clean
        // success after some backups may already have gone.
        var successCleanupException = CleanupWorkingFiles(entries);
        if (successCleanupException is not null)
        {
            throw new ExportServiceException(
                ExportErrorKind.FileSystem,
                "FIT 文件已写入，但无法删除导出临时或备份文件。"
                    + "请关闭可能占用文件的程序后手动清理隐藏的 .tmp/.bak 文件。",
                successCleanupException);
        }
    }

    private string ValidateRequest(ExportRequest? request)
    {
        if (request is null || request.Wgs84Activity is null)
        {
            throw new ExportServiceException(
                ExportErrorKind.InvalidRequest,
                "导出参数不完整，请重新生成预览后再试。");
        }

        if (request.ExportCount is < MinimumExportCount or > MaximumExportCount)
        {
            throw new ExportServiceException(
                ExportErrorKind.InvalidRequest,
                $"导出数量必须在 {MinimumExportCount}–{MaximumExportCount} 之间。");
        }

        if (string.IsNullOrWhiteSpace(request.ExportDirectory))
        {
            throw InvalidDirectory();
        }

        string outputDirectory;
        try
        {
            var requestedDirectory = request.ExportDirectory.Trim();
            if (!Path.IsPathFullyQualified(requestedDirectory))
            {
                throw InvalidDirectory();
            }

            outputDirectory = Path.TrimEndingDirectorySeparator(
                Path.GetFullPath(requestedDirectory));
        }
        catch (ExportServiceException)
        {
            throw;
        }
        catch (Exception exception) when (
            exception is ArgumentException
                or NotSupportedException
                or PathTooLongException)
        {
            throw InvalidDirectory(exception);
        }
        catch (Exception exception) when (
            exception is UnauthorizedAccessException or SecurityException)
        {
            throw CreatePlatformException(exception);
        }

        bool directoryExists;
        try
        {
            directoryExists = _fileSystem.DirectoryExists(outputDirectory);
        }
        catch (Exception exception) when (IsFileOperationException(exception))
        {
            throw CreatePlatformException(exception);
        }

        if (!directoryExists)
        {
            throw InvalidDirectory();
        }

        return outputDirectory;
    }

    private static ExportEntry[] CreateEntries(
        string outputDirectory,
        int exportCount)
    {
        var entries = new ExportEntry[exportCount];
        for (var index = 0; index < exportCount; index++)
        {
            var fileName = exportCount == 1
                ? "run.fit"
                : $"run_{index + 1}.fit";
            var identifier = Guid.NewGuid().ToString("N");
            entries[index] = new ExportEntry(
                Path.Combine(outputDirectory, fileName),
                Path.Combine(outputDirectory, $".{fileName}.{identifier}.tmp"),
                Path.Combine(outputDirectory, $".{fileName}.{identifier}.bak"));
        }

        return entries;
    }

    private void Commit(ExportEntry entry)
    {
        if (_fileSystem.DirectoryExists(entry.FinalPath))
        {
            throw new IOException(
                $"Export target is a directory: {entry.FinalPath}");
        }

        if (_fileSystem.FileExists(entry.FinalPath))
        {
            if (_fileSystem.FileExists(entry.BackupPath)
                || _fileSystem.DirectoryExists(entry.BackupPath))
            {
                throw new IOException(
                    $"Export backup path is already occupied: {entry.BackupPath}");
            }

            _fileSystem.ReplaceFile(
                entry.TemporaryPath,
                entry.FinalPath,
                entry.BackupPath);
            entry.TemporaryOwned = false;
            entry.BackupOwned = true;
            entry.State = ExportEntryState.Replaced;
            return;
        }

        _fileSystem.MoveFile(entry.TemporaryPath, entry.FinalPath);
        entry.TemporaryOwned = false;
        entry.State = ExportEntryState.Created;
    }

    private Exception? Rollback(IReadOnlyList<ExportEntry> entries)
    {
        List<Exception>? failures = null;
        for (var index = entries.Count - 1; index >= 0; index--)
        {
            var entry = entries[index];
            try
            {
                switch (entry.State)
                {
                    case ExportEntryState.Created:
                        // File.Delete is idempotent for a missing path. Calling
                        // it directly avoids treating File.Exists(false) on an
                        // access error as a successful rollback.
                        _fileSystem.DeleteFile(entry.FinalPath);

                        break;

                    case ExportEntryState.Replaced:
                        if (!_fileSystem.FileExists(entry.BackupPath))
                        {
                            throw new IOException(
                                $"Export backup is missing: {entry.BackupPath}");
                        }

                        if (_fileSystem.FileExists(entry.FinalPath))
                        {
                            _fileSystem.ReplaceFile(
                                entry.BackupPath,
                                entry.FinalPath,
                                backupPath: null);
                            entry.BackupOwned = false;
                        }
                        else
                        {
                            _fileSystem.MoveFile(
                                entry.BackupPath,
                                entry.FinalPath);
                            entry.BackupOwned = false;
                        }

                        break;
                }
            }
            catch (Exception exception) when (IsFileOperationException(exception))
            {
                failures ??= [];
                failures.Add(exception);
            }
        }

        return failures switch
        {
            null => null,
            { Count: 1 } => failures[0],
            _ => new AggregateException(failures),
        };
    }

    private Exception? CleanupWorkingFiles(IEnumerable<ExportEntry> entries)
    {
        List<Exception>? failures = null;
        foreach (var entry in entries)
        {
            if (entry.TemporaryOwned)
            {
                TryDelete(entry.TemporaryPath, ref failures);
            }

            if (entry.BackupOwned)
            {
                TryDelete(entry.BackupPath, ref failures);
            }
        }

        return CollapseExceptions(failures);
    }

    private Exception? CleanupTemporaryFiles(IEnumerable<ExportEntry> entries)
    {
        List<Exception>? failures = null;
        foreach (var entry in entries)
        {
            if (entry.TemporaryOwned)
            {
                TryDelete(entry.TemporaryPath, ref failures);
            }
        }

        return CollapseExceptions(failures);
    }

    private void TryDelete(string path, ref List<Exception>? failures)
    {
        try
        {
            // Owned work paths are unique and DeleteFile is idempotent. Do not
            // gate cleanup on File.Exists, which reports false for some access
            // errors even while the file still exists.
            _fileSystem.DeleteFile(path);
        }
        catch (Exception exception) when (IsFileOperationException(exception))
        {
            failures ??= [];
            failures.Add(exception);
        }
    }

    private static Exception? CollapseExceptions(List<Exception>? failures) =>
        failures switch
        {
            null => null,
            { Count: 1 } => failures[0],
            _ => new AggregateException(failures),
        };

    private static AggregateException CombineExceptions(
        params Exception?[] exceptions) =>
        new(exceptions.OfType<Exception>());

    private static ExportServiceException InvalidDirectory(
        Exception? innerException = null) =>
        new(
            ExportErrorKind.InvalidDirectory,
            "导出文件夹不存在或无法访问，请重新选择导出位置。",
            innerException);

    private static ExportServiceException CreatePlatformException(
        Exception exception)
    {
        if (exception is ExportTemporaryFileException temporary)
        {
            var writeError = CreatePlatformException(temporary.WriteException);
            return new ExportServiceException(
                writeError.Kind,
                writeError.Message,
                temporary);
        }

        if (exception is UnauthorizedAccessException or SecurityException)
        {
            return new ExportServiceException(
                ExportErrorKind.PermissionDenied,
                "无法写入导出文件夹，请选择有写入权限的位置。",
                exception);
        }

        if (exception is IOException ioException && IsDiskFull(ioException))
        {
            return new ExportServiceException(
                ExportErrorKind.DiskFull,
                "磁盘空间不足，无法完成导出。请释放空间后重试。",
                exception);
        }

        if (exception is DirectoryNotFoundException)
        {
            return InvalidDirectory(exception);
        }

        return new ExportServiceException(
            ExportErrorKind.FileSystem,
            "无法写入 FIT 文件，请检查导出位置后重试。",
            exception);
    }

    private static bool IsDiskFull(IOException exception)
    {
        var windowsError = exception.HResult & 0xFFFF;
        return windowsError is ErrorHandleDiskFull or ErrorDiskFull;
    }

    private static bool IsFileOperationException(Exception exception) =>
        exception is IOException
            or UnauthorizedAccessException
            or SecurityException
            or ArgumentException
            or NotSupportedException;

    private enum ExportEntryState
    {
        Pending,
        Created,
        Replaced,
    }

    private sealed class ExportEntry(
        string finalPath,
        string temporaryPath,
        string backupPath)
    {
        internal string FinalPath { get; } = finalPath;

        internal string TemporaryPath { get; } = temporaryPath;

        internal string BackupPath { get; } = backupPath;

        internal ExportEntryState State { get; set; }

        internal bool TemporaryOwned { get; set; }

        internal bool BackupOwned { get; set; }
    }
}
