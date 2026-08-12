using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using FitGenerator.Native.Map;

namespace FitGenerator.Native.Services;

internal interface ISettingsService
{
    AppSettings LoadSettings();

    void SaveSettings(AppSettings settings);

    SavedRoute LoadLastRoute();

    void SaveLastRoute(SavedRoute route);
}

internal interface ISettingsKeyProtector
{
    byte[] Protect(byte[] plaintext);

    byte[] Unprotect(byte[] protectedData);
}

internal sealed class DpapiSettingsKeyProtector : ISettingsKeyProtector
{
    public byte[] Protect(byte[] plaintext)
    {
        ArgumentNullException.ThrowIfNull(plaintext);
        return ProtectedData.Protect(
            plaintext,
            optionalEntropy: null,
            DataProtectionScope.CurrentUser);
    }

    public byte[] Unprotect(byte[] protectedData)
    {
        ArgumentNullException.ThrowIfNull(protectedData);
        return ProtectedData.Unprotect(
            protectedData,
            optionalEntropy: null,
            DataProtectionScope.CurrentUser);
    }
}

internal sealed class SettingsServiceException : Exception
{
    internal SettingsServiceException(string message, Exception innerException)
        : base(message, innerException)
    {
    }
}

internal sealed class SettingsService : ISettingsService
{
    internal const string SettingsFileName = "settings-v1.json";
    internal const string LastRouteFileName = "last-route-v1.json";

    private const int MaximumRoutePoints = 50_000;
    private const double MinimumPaceSecondsPerKilometer = 60.0;
    private const double MaximumPaceSecondsPerKilometer = 3_600.0;
    private const int MinimumRestingHeartRate = 30;
    private const int MaximumRestingHeartRate = 120;
    private const int MinimumMaximumHeartRate = 100;
    private const int MaximumMaximumHeartRate = 220;
    private const int MaximumLapCount = 100;
    private const int MaximumExportCount = 20;
    private const string SaveSettingsErrorMessage =
        "无法保存应用设置，请检查本地数据目录是否可写";
    private const string SaveRouteErrorMessage =
        "无法保存最近轨迹，请检查本地数据目录是否可写";

    private static readonly UTF8Encoding StrictUtf8 = new(
        encoderShouldEmitUTF8Identifier: false,
        throwOnInvalidBytes: true);

    private static readonly JsonSerializerOptions SerializerOptions = CreateSerializerOptions();

    private readonly string _settingsPath;
    private readonly string _lastRoutePath;
    private readonly ISettingsKeyProtector _keyProtector;
    private readonly TimeProvider _timeProvider;
    private readonly object _fileGate = new();

    internal SettingsService()
        : this(
            GetDefaultStorageDirectory(),
            new DpapiSettingsKeyProtector(),
            TimeProvider.System)
    {
    }

    internal SettingsService(
        string storageDirectory,
        ISettingsKeyProtector keyProtector,
        TimeProvider? timeProvider = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(storageDirectory);
        ArgumentNullException.ThrowIfNull(keyProtector);
        if (!Path.IsPathFullyQualified(storageDirectory))
        {
            throw new ArgumentException(
                "Settings storage must use an absolute path.",
                nameof(storageDirectory));
        }

        var normalizedDirectory = Path.GetFullPath(storageDirectory);
        _settingsPath = Path.Combine(normalizedDirectory, SettingsFileName);
        _lastRoutePath = Path.Combine(normalizedDirectory, LastRouteFileName);
        _keyProtector = keyProtector;
        _timeProvider = timeProvider ?? TimeProvider.System;
    }

    internal string SettingsFilePath => _settingsPath;

    internal string LastRouteFilePath => _lastRoutePath;

    public AppSettings LoadSettings()
    {
        lock (_fileGate)
        {
            if (!File.Exists(_settingsPath))
            {
                return AppSettings.Default;
            }

            try
            {
                var payload = File.ReadAllBytes(_settingsPath);
                var persisted = JsonSerializer.Deserialize<SettingsFileDto>(
                    payload,
                    SerializerOptions);
                if (persisted is null)
                {
                    throw new InvalidDataException("The settings payload is empty.");
                }

                return RestoreSettings(persisted);
            }
            catch (Exception exception) when (IsInvalidPayload(exception))
            {
                TryBackupInvalidFile(_settingsPath);
                return AppSettings.Default;
            }
            catch (Exception exception) when (IsRecoverableReadFailure(exception))
            {
                return AppSettings.Default;
            }
        }
    }

    public void SaveSettings(AppSettings settings)
    {
        ArgumentNullException.ThrowIfNull(settings);

        lock (_fileGate)
        {
            try
            {
                var persisted = PrepareSettings(settings);
                var payload = JsonSerializer.SerializeToUtf8Bytes(
                    persisted,
                    SerializerOptions);
                WriteAtomically(_settingsPath, payload);
            }
            catch (Exception exception) when (IsRecoverableWriteFailure(exception))
            {
                throw new SettingsServiceException(SaveSettingsErrorMessage, exception);
            }
        }
    }

    public SavedRoute LoadLastRoute()
    {
        lock (_fileGate)
        {
            if (!File.Exists(_lastRoutePath))
            {
                return SavedRoute.Empty;
            }

            try
            {
                var payload = File.ReadAllBytes(_lastRoutePath);
                var persisted = JsonSerializer.Deserialize<RouteFileDto>(
                    payload,
                    SerializerOptions);
                if (persisted is null)
                {
                    throw new InvalidDataException("The route payload is empty.");
                }

                return RestoreRoute(persisted);
            }
            catch (Exception exception) when (IsInvalidPayload(exception))
            {
                TryBackupInvalidFile(_lastRoutePath);
                return SavedRoute.Empty;
            }
            catch (Exception exception) when (IsRecoverableReadFailure(exception))
            {
                return SavedRoute.Empty;
            }
        }
    }

    public void SaveLastRoute(SavedRoute route)
    {
        ArgumentNullException.ThrowIfNull(route);

        lock (_fileGate)
        {
            try
            {
                var persisted = PrepareRoute(route);
                var payload = JsonSerializer.SerializeToUtf8Bytes(
                    persisted,
                    SerializerOptions);
                WriteAtomically(_lastRoutePath, payload);
            }
            catch (Exception exception) when (IsRecoverableWriteFailure(exception))
            {
                throw new SettingsServiceException(SaveRouteErrorMessage, exception);
            }
        }
    }

    private SettingsFileDto PrepareSettings(AppSettings settings)
    {
        ValidateSchemaVersion(settings.SchemaVersion, AppSettings.CurrentSchemaVersion);
        ValidateSettings(settings);

        var providers = settings.CustomMapProviders
            .Select(provider => PrepareProvider(provider))
            .ToArray();

        return new SettingsFileDto(
            settings.SchemaVersion,
            settings.ActiveMapProviderId,
            new MapViewportFileDto(
                settings.MapViewport.CenterWgs84Latitude,
                settings.MapViewport.CenterWgs84Longitude,
                settings.MapViewport.Zoom),
            settings.PaceSecondsPerKilometer,
            settings.RestingHeartRate,
            settings.MaximumHeartRate,
            settings.LapCount,
            settings.ExportCount,
            settings.ExportDirectory,
            settings.PreviewSeed,
            providers);
    }

    private AppSettings RestoreSettings(SettingsFileDto persisted)
    {
        ValidateSchemaVersion(persisted.SchemaVersion, AppSettings.CurrentSchemaVersion);
        if (persisted.CustomMapProviders is null)
        {
            throw new InvalidDataException("The settings payload is incomplete.");
        }

        var providers = persisted.CustomMapProviders
            .Select(RestoreProvider)
            .ToArray();

        var settings = new AppSettings
        {
            SchemaVersion = persisted.SchemaVersion,
            ActiveMapProviderId = persisted.ActiveMapProviderId,
            CustomMapProviders = providers,
            MapViewport = RestoreViewportOrDefault(persisted.MapViewport),
            PaceSecondsPerKilometer = persisted.PaceSecondsPerKilometer,
            RestingHeartRate = persisted.RestingHeartRate,
            MaximumHeartRate = persisted.MaximumHeartRate,
            LapCount = persisted.LapCount,
            ExportCount = persisted.ExportCount,
            ExportDirectory = persisted.ExportDirectory,
            PreviewSeed = persisted.PreviewSeed,
        };
        ValidateSettings(settings);
        return settings;
    }

    private MapProviderFileDto PrepareProvider(MapProviderDefinition provider)
    {
        ArgumentNullException.ThrowIfNull(provider);
        ValidateProvider(provider);

        return new MapProviderFileDto(
            provider.Id,
            provider.DisplayName,
            provider.XyzUrlTemplate,
            provider.CoordinateSystem,
            provider.MaximumZoom,
            provider.Attribution,
            provider.GeocoderUrl,
            provider.KeyPlacement,
            provider.KeyName,
            ProtectKey(provider.ApiKey));
    }

    private MapProviderDefinition RestoreProvider(MapProviderFileDto persisted)
    {
        if (persisted is null)
        {
            throw new InvalidDataException("The settings payload contains an empty provider.");
        }

        var provider = new MapProviderDefinition(
            persisted.Id,
            persisted.DisplayName,
            persisted.XyzUrlTemplate,
            persisted.CoordinateSystem,
            persisted.MaximumZoom,
            persisted.Attribution,
            persisted.GeocoderUrl,
            persisted.KeyPlacement,
            persisted.KeyName,
            UnprotectKey(persisted.ProtectedApiKey),
            IsBuiltIn: false);
        ValidateProvider(provider);
        return provider;
    }

    private static RouteFileDto PrepareRoute(SavedRoute route)
    {
        ValidateSchemaVersion(route.SchemaVersion, SavedRoute.CurrentSchemaVersion);
        ValidateRoutePoints(route.Wgs84Points);

        return new RouteFileDto(
            route.SchemaVersion,
            route.Wgs84Points
                .Select(point => new RoutePointFileDto(point.Latitude, point.Longitude))
                .ToArray());
    }

    private static SavedRoute RestoreRoute(RouteFileDto persisted)
    {
        ValidateSchemaVersion(persisted.SchemaVersion, SavedRoute.CurrentSchemaVersion);
        if (persisted.Wgs84Points is null)
        {
            throw new InvalidDataException("The route payload is incomplete.");
        }

        var points = persisted.Wgs84Points
            .Select(point => point is null
                ? throw new InvalidDataException("The route payload contains an empty point.")
                : new GeoCoordinate(point.Latitude, point.Longitude))
            .ToArray();
        ValidateRoutePoints(points);

        return new SavedRoute
        {
            SchemaVersion = persisted.SchemaVersion,
            Wgs84Points = points,
        };
    }

    private string? ProtectKey(string? apiKey)
    {
        if (string.IsNullOrWhiteSpace(apiKey))
        {
            return null;
        }

        var plaintext = StrictUtf8.GetBytes(apiKey);
        byte[]? protectedData = null;
        try
        {
            protectedData = _keyProtector.Protect(plaintext);
            return protectedData.Length == 0
                ? null
                : Convert.ToBase64String(protectedData);
        }
        catch (Exception exception) when (IsProtectionFailure(exception))
        {
            return null;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(plaintext);
            if (protectedData is not null)
            {
                CryptographicOperations.ZeroMemory(protectedData);
            }
        }
    }

    private string? UnprotectKey(string? protectedApiKey)
    {
        if (string.IsNullOrWhiteSpace(protectedApiKey))
        {
            return null;
        }

        byte[]? protectedData = null;
        byte[]? plaintext = null;
        try
        {
            protectedData = Convert.FromBase64String(protectedApiKey);
            plaintext = _keyProtector.Unprotect(protectedData);
            return plaintext.Length == 0 ? null : StrictUtf8.GetString(plaintext);
        }
        catch (Exception exception) when (
            IsProtectionFailure(exception)
            || exception is FormatException or DecoderFallbackException)
        {
            return null;
        }
        finally
        {
            if (protectedData is not null)
            {
                CryptographicOperations.ZeroMemory(protectedData);
            }

            if (plaintext is not null)
            {
                CryptographicOperations.ZeroMemory(plaintext);
            }
        }
    }

    private static void ValidateSettings(AppSettings settings)
    {
        if (string.IsNullOrWhiteSpace(settings.ActiveMapProviderId)
            || settings.CustomMapProviders is null
            || !IsValidViewport(settings.MapViewport)
            || !IsFinite(settings.PaceSecondsPerKilometer)
            || settings.PaceSecondsPerKilometer is
                < MinimumPaceSecondsPerKilometer or > MaximumPaceSecondsPerKilometer
            || settings.RestingHeartRate is
                < MinimumRestingHeartRate or > MaximumRestingHeartRate
            || settings.MaximumHeartRate is
                < MinimumMaximumHeartRate or > MaximumMaximumHeartRate
            || settings.MaximumHeartRate <= settings.RestingHeartRate
            || settings.LapCount is < 1 or > MaximumLapCount
            || settings.ExportCount is < 1 or > MaximumExportCount)
        {
            throw new InvalidDataException("The settings payload contains invalid values.");
        }

        foreach (var provider in settings.CustomMapProviders)
        {
            ValidateProvider(provider);
        }
    }

    private static MapViewportSettings RestoreViewportOrDefault(
        MapViewportFileDto? persisted)
    {
        if (persisted is null)
        {
            return MapViewportSettings.Default;
        }

        var viewport = new MapViewportSettings(
            persisted.CenterWgs84Latitude,
            persisted.CenterWgs84Longitude,
            persisted.Zoom);
        return IsValidViewport(viewport)
            ? viewport
            : MapViewportSettings.Default;
    }

    private static bool IsValidViewport(MapViewportSettings? viewport) =>
        viewport is not null
        && IsFinite(viewport.CenterWgs84Latitude)
        && viewport.CenterWgs84Latitude is >= -90.0 and <= 90.0
        && IsFinite(viewport.CenterWgs84Longitude)
        && viewport.CenterWgs84Longitude is >= -180.0 and <= 180.0
        && IsFinite(viewport.Zoom)
        && viewport.Zoom > 0.0;

    private static void ValidateProvider(MapProviderDefinition provider)
    {
        if (provider is null
            || string.IsNullOrWhiteSpace(provider.Id)
            || string.IsNullOrWhiteSpace(provider.DisplayName)
            || string.IsNullOrWhiteSpace(provider.XyzUrlTemplate)
            || string.IsNullOrWhiteSpace(provider.Attribution)
            || provider.MaximumZoom < 0
            || !Enum.IsDefined(provider.CoordinateSystem)
            || !Enum.IsDefined(provider.KeyPlacement))
        {
            throw new InvalidDataException("The settings payload contains an invalid provider.");
        }
    }

    private static void ValidateRoutePoints(IReadOnlyList<GeoCoordinate>? points)
    {
        if (points is null || points.Count > MaximumRoutePoints)
        {
            throw new InvalidDataException("The route payload contains an invalid point count.");
        }

        foreach (var point in points)
        {
            if (!IsFinite(point.Latitude)
                || point.Latitude is < -90.0 or > 90.0
                || !IsFinite(point.Longitude)
                || point.Longitude is < -180.0 or > 180.0)
            {
                throw new InvalidDataException("The route payload contains an invalid coordinate.");
            }
        }
    }

    private static void ValidateSchemaVersion(int actual, int expected)
    {
        if (actual != expected)
        {
            throw new InvalidDataException(
                $"Unsupported settings schema version: {actual.ToString(CultureInfo.InvariantCulture)}.");
        }
    }

    private static bool IsFinite(double value) =>
        !double.IsNaN(value) && !double.IsInfinity(value);

    private static string GetDefaultStorageDirectory()
    {
        var localApplicationData = Environment.GetFolderPath(
            Environment.SpecialFolder.LocalApplicationData);
        if (string.IsNullOrWhiteSpace(localApplicationData)
            || !Path.IsPathFullyQualified(localApplicationData))
        {
            throw new SettingsServiceException(
                "无法访问本地设置目录",
                new DirectoryNotFoundException(
                    "The local application data directory is unavailable."));
        }

        return Path.Combine(localApplicationData, "FitGenerator.Native");
    }

    private static void WriteAtomically(string destinationPath, byte[] payload)
    {
        var directory = Path.GetDirectoryName(destinationPath)
            ?? throw new IOException("The settings directory is unavailable.");
        Directory.CreateDirectory(directory);

        var temporaryPath = Path.Combine(
            directory,
            $".{Path.GetFileName(destinationPath)}.{Guid.NewGuid():N}.tmp");
        try
        {
            using (var stream = new FileStream(
                       temporaryPath,
                       FileMode.CreateNew,
                       FileAccess.Write,
                       FileShare.None,
                       bufferSize: 4096,
                       FileOptions.WriteThrough))
            {
                stream.Write(payload);
                stream.Flush(flushToDisk: true);
            }

            if (File.Exists(destinationPath))
            {
                File.Replace(
                    temporaryPath,
                    destinationPath,
                    destinationBackupFileName: null,
                    ignoreMetadataErrors: true);
            }
            else
            {
                File.Move(temporaryPath, destinationPath);
            }
        }
        finally
        {
            try
            {
                File.Delete(temporaryPath);
            }
            catch (Exception exception) when (IsRecoverableWriteFailure(exception))
            {
                // A failed cleanup must not hide the original persistence result.
            }
        }
    }

    private void TryBackupInvalidFile(string sourcePath)
    {
        try
        {
            var directory = Path.GetDirectoryName(sourcePath);
            if (directory is null || !File.Exists(sourcePath))
            {
                return;
            }

            var timestamp = _timeProvider.GetUtcNow().UtcDateTime.ToString(
                "yyyyMMdd'T'HHmmssfff'Z'",
                CultureInfo.InvariantCulture);
            var stem = Path.GetFileNameWithoutExtension(sourcePath);
            var extension = Path.GetExtension(sourcePath);
            var backupPath = Path.Combine(
                directory,
                $"{stem}.invalid-{timestamp}{extension}");

            for (var suffix = 1; File.Exists(backupPath); suffix++)
            {
                backupPath = Path.Combine(
                    directory,
                    $"{stem}.invalid-{timestamp}-{suffix.ToString(CultureInfo.InvariantCulture)}{extension}");
            }

            File.Move(sourcePath, backupPath);
        }
        catch (Exception exception) when (
            IsRecoverableReadFailure(exception)
            || IsRecoverableWriteFailure(exception))
        {
            // Settings corruption must never prevent application startup.
        }
    }

    private static JsonSerializerOptions CreateSerializerOptions()
    {
        var options = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = false,
            NumberHandling = JsonNumberHandling.Strict,
            UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
            WriteIndented = true,
        };
        options.Converters.Add(new JsonStringEnumConverter(
            JsonNamingPolicy.CamelCase,
            allowIntegerValues: false));
        return options;
    }

    private static bool IsProtectionFailure(Exception exception) =>
        exception is CryptographicException
            or PlatformNotSupportedException
            or NotSupportedException;

    private static bool IsInvalidPayload(Exception exception) =>
        exception is JsonException
            or NotSupportedException
            or InvalidDataException
            or DecoderFallbackException;

    private static bool IsRecoverableReadFailure(Exception exception) =>
        exception is IOException
            or UnauthorizedAccessException;

    private static bool IsRecoverableWriteFailure(Exception exception) =>
        exception is IOException
            or UnauthorizedAccessException
            or JsonException
            or NotSupportedException
            or InvalidDataException;

    private sealed record SettingsFileDto(
        int SchemaVersion,
        string ActiveMapProviderId,
        MapViewportFileDto? MapViewport,
        double PaceSecondsPerKilometer,
        int RestingHeartRate,
        int MaximumHeartRate,
        int LapCount,
        int ExportCount,
        string? ExportDirectory,
        ulong? PreviewSeed,
        IReadOnlyList<MapProviderFileDto> CustomMapProviders);

    private sealed record MapViewportFileDto(
        double CenterWgs84Latitude,
        double CenterWgs84Longitude,
        double Zoom);

    private sealed record MapProviderFileDto(
        string Id,
        string DisplayName,
        string XyzUrlTemplate,
        CoordinateSystem CoordinateSystem,
        int MaximumZoom,
        string Attribution,
        string? GeocoderUrl,
        ProviderKeyPlacement KeyPlacement,
        string? KeyName,
        string? ProtectedApiKey);

    private sealed record RouteFileDto(
        int SchemaVersion,
        IReadOnlyList<RoutePointFileDto> Wgs84Points);

    private sealed record RoutePointFileDto(double Latitude, double Longitude);
}
