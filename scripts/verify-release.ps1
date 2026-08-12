param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ReleaseDirectory,
    [switch]$RequireWindowsSignature,
    [switch]$RequireAndroidSignature
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:verificationFailures = New-Object System.Collections.ArrayList
$strictUtf8WithoutBom = [System.Text.UTF8Encoding]::new($false, $true)
$maximumMetadataBytes = 1024 * 1024
$maximumChecksumBytes = 64 * 1024
$maximumSbomBytes = 64 * 1024 * 1024

function Add-VerificationFailure {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    [void]$script:verificationFailures.Add($Message)
}

function Complete-Verification {
    if ($script:verificationFailures.Count -gt 0) {
        foreach ($failure in $script:verificationFailures) {
            [Console]::Error.WriteLine("Release verification error: $failure")
        }
        exit 1
    }

    Write-Output "Release verification passed."
    exit 0
}

function Test-IsReparsePoint {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileSystemInfo]$Item
    )

    return (($Item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0)
}

function Get-RequiredReleaseFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Root,
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [System.IO.FileSystemInfo[]]$TopLevelItems
    )

    $matches = @($TopLevelItems | Where-Object { $_.Name -ieq $Name })
    if ($matches.Count -eq 0) {
        Add-VerificationFailure "missing required file '$Name'"
        return $null
    }
    if ($matches.Count -ne 1) {
        Add-VerificationFailure "required file '$Name' has a duplicate or casing collision"
        return $null
    }
    $item = $matches[0]
    if ($item.Name -cne $Name) {
        Add-VerificationFailure "required file '$Name' has incorrect filename casing"
        return $null
    }
    $expectedPath = [System.IO.Path]::GetFullPath((Join-Path $Root $Name))
    if ($item.FullName -cne $expectedPath) {
        Add-VerificationFailure "required file '$Name' is not at the expected top-level path"
        return $null
    }
    if ($item.PSIsContainer) {
        Add-VerificationFailure "required file '$Name' must be a regular file"
        return $null
    }
    if (Test-IsReparsePoint $item) {
        Add-VerificationFailure "required file '$Name' must not be a symbolic link or reparse point"
        return $null
    }
    if ($item.Length -le 0) {
        Add-VerificationFailure "required file '$Name' is empty"
    }
    return $item
}

function Read-StrictUtf8File {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$File,
        [Parameter(Mandatory = $true)]
        [Int64]$MaximumBytes,
        [Parameter(Mandatory = $true)]
        [string]$Context
    )

    if ($File.Length -gt $MaximumBytes) {
        Add-VerificationFailure "$Context exceeds the supported size"
        return $null
    }
    try {
        $bytes = [System.IO.File]::ReadAllBytes($File.FullName)
    } catch {
        Add-VerificationFailure "$Context could not be read"
        return $null
    }
    if ($bytes.LongLength -ne $File.Length) {
        Add-VerificationFailure "$Context changed while it was being read"
        return $null
    }
    if (
        $bytes.Length -ge 3 -and
        $bytes[0] -eq 0xEF -and
        $bytes[1] -eq 0xBB -and
        $bytes[2] -eq 0xBF
    ) {
        Add-VerificationFailure "$Context must use UTF-8 without a BOM"
        return $null
    }
    try {
        $text = $strictUtf8WithoutBom.GetString($bytes)
    } catch {
        Add-VerificationFailure "$Context is not strict UTF-8"
        return $null
    }
    return [pscustomobject]@{
        Bytes = [byte[]]$bytes
        Text = $text
    }
}

function Test-ExactJsonProperties {
    param(
        [AllowNull()]
        [object]$Object,
        [Parameter(Mandatory = $true)]
        [string[]]$ExpectedNames,
        [Parameter(Mandatory = $true)]
        [string]$Context
    )

    if ($null -eq $Object) {
        Add-VerificationFailure "$Context is null"
        return $false
    }
    $properties = @($Object.PSObject.Properties)
    if ($properties.Count -ne $ExpectedNames.Count) {
        Add-VerificationFailure "$Context has an unexpected property set"
        return $false
    }
    for ($index = 0; $index -lt $ExpectedNames.Count; $index += 1) {
        if ($properties[$index].Name -cne $ExpectedNames[$index]) {
            Add-VerificationFailure "$Context properties are missing, unexpected, or out of order"
            return $false
        }
    }
    return $true
}

function Test-IsJsonNonNegativeInteger {
    param(
        [AllowNull()]
        [object]$Value
    )

    if ($null -eq $Value -or $Value -is [string] -or $Value -is [bool]) {
        return $false
    }
    try {
        $text = [System.Convert]::ToString(
            $Value,
            [System.Globalization.CultureInfo]::InvariantCulture
        )
        return $text -cmatch '^(0|[1-9][0-9]*)$'
    } catch {
        return $false
    }
}

function Get-FileSha256Lower {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$File,
        [Parameter(Mandatory = $true)]
        [string]$Context
    )

    try {
        return (Get-FileHash -LiteralPath $File.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    } catch {
        Add-VerificationFailure "unable to hash $Context"
        return $null
    }
}

function Test-MetadataFileRecord {
    param(
        [AllowNull()]
        [object]$Record,
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$ExpectedFile,
        [Parameter(Mandatory = $true)]
        [string]$Context
    )

    if (-not (Test-ExactJsonProperties $Record @("fileName", "size", "sha256") $Context)) {
        return
    }
    if ($Record.fileName -isnot [string] -or $Record.fileName -cne $ExpectedFile.Name) {
        Add-VerificationFailure "$Context has an incorrect filename"
    }
    if (-not (Test-IsJsonNonNegativeInteger $Record.size)) {
        Add-VerificationFailure "$Context has an invalid size"
    } else {
        try {
            if ([Int64]$Record.size -ne $ExpectedFile.Length) {
                Add-VerificationFailure "$Context size does not match '$($ExpectedFile.Name)'"
            }
        } catch {
            Add-VerificationFailure "$Context size is outside the supported range"
        }
    }
    if ($Record.sha256 -isnot [string] -or $Record.sha256 -cnotmatch '^[0-9a-f]{64}$') {
        Add-VerificationFailure "$Context has an invalid SHA-256"
    } else {
        $actualHash = Get-FileSha256Lower $ExpectedFile "'$($ExpectedFile.Name)'"
        if ($null -ne $actualHash -and $Record.sha256 -cne $actualHash) {
            Add-VerificationFailure "$Context SHA-256 does not match '$($ExpectedFile.Name)'"
        }
    }
}

function Test-ReleaseMetadata {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$MetadataFile,
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$WindowsArtifact,
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$AndroidArtifact,
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$NoticeFile,
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$SbomFile
    )

    $metadataContent = Read-StrictUtf8File `
        $MetadataFile `
        $maximumMetadataBytes `
        "fit-generator-release-metadata.json"
    if ($null -eq $metadataContent) {
        return
    }
    try {
        $metadata = $metadataContent.Text | ConvertFrom-Json
    } catch {
        Add-VerificationFailure "fit-generator-release-metadata.json is not valid JSON"
        return
    }
    try {
        $canonicalMetadata = $metadata | ConvertTo-Json -Depth 6 -Compress
        $canonicalMetadata = $canonicalMetadata.Replace("`r`n", "`n").Replace("`r", "`n") + "`n"
    } catch {
        Add-VerificationFailure "fit-generator-release-metadata.json cannot be canonicalized"
        return
    }
    if ($metadataContent.Text -cne $canonicalMetadata) {
        Add-VerificationFailure (
            "fit-generator-release-metadata.json is not in canonical form " +
                "or contains duplicate JSON properties"
        )
    }
    $topLevelNames = @(
        "schemaVersion",
        "releaseVersion",
        "sourceCommit",
        "sbom",
        "notices",
        "artifacts"
    )
    if (-not (Test-ExactJsonProperties $metadata $topLevelNames "release metadata")) {
        return
    }
    $schemaVersionIsSupported = $false
    if (Test-IsJsonNonNegativeInteger $metadata.schemaVersion) {
        try {
            $schemaVersionIsSupported = [Int64]$metadata.schemaVersion -eq 1
        } catch {
            $schemaVersionIsSupported = $false
        }
    }
    if (-not $schemaVersionIsSupported) {
        Add-VerificationFailure "release metadata has an unsupported schemaVersion"
    }

    $releaseVersionIsValid = $metadata.releaseVersion -is [string] -and (
        $metadata.releaseVersion -ceq "UNSPECIFIED" -or
        $metadata.releaseVersion -cmatch '^v[0-9]+[.][0-9]+[.][0-9]+$'
    )
    if (-not $releaseVersionIsValid) {
        Add-VerificationFailure "release metadata has an invalid releaseVersion"
    }
    $sourceCommitIsValid = $metadata.sourceCommit -is [string] -and (
        $metadata.sourceCommit -ceq "UNSPECIFIED" -or
        $metadata.sourceCommit -cmatch '^([0-9a-f]{40}|[0-9a-f]{64})$'
    )
    if (-not $sourceCommitIsValid) {
        Add-VerificationFailure "release metadata has an invalid sourceCommit"
    }
    if ($releaseVersionIsValid -and $sourceCommitIsValid) {
        $versionIsSpecified = $metadata.releaseVersion -cne "UNSPECIFIED"
        $commitIsSpecified = $metadata.sourceCommit -cne "UNSPECIFIED"
        if ($versionIsSpecified -ne $commitIsSpecified) {
            Add-VerificationFailure (
                "release metadata releaseVersion and sourceCommit must both be specified " +
                    "or both be UNSPECIFIED"
            )
        }
    }

    Test-MetadataFileRecord $metadata.sbom $SbomFile "release metadata SBOM record"
    Test-MetadataFileRecord $metadata.notices $NoticeFile "release metadata notices record"

    $artifactRecords = @($metadata.artifacts)
    if ($artifactRecords.Count -ne 2) {
        Add-VerificationFailure "release metadata must contain exactly two artifact records"
        return
    }
    $expectedNames = [string[]]@($WindowsArtifact.Name, $AndroidArtifact.Name)
    [System.Array]::Sort($expectedNames, [System.StringComparer]::Ordinal)
    for ($index = 0; $index -lt $expectedNames.Count; $index += 1) {
        $record = $artifactRecords[$index]
        $context = "release metadata artifact record $($index + 1)"
        $propertyNames = @("fileName", "platform", "size", "sha256", "signingStatus")
        if (-not (Test-ExactJsonProperties $record $propertyNames $context)) {
            continue
        }
        $expectedName = $expectedNames[$index]
        $expectedFile = if ($expectedName -ceq $WindowsArtifact.Name) {
            $WindowsArtifact
        } else {
            $AndroidArtifact
        }
        if ($record.fileName -isnot [string] -or $record.fileName -cne $expectedName) {
            Add-VerificationFailure "$context has an incorrect filename or ordinal position"
        }
        $expectedPlatform = if ($expectedName -ceq $WindowsArtifact.Name) {
            "windows"
        } else {
            "android"
        }
        if ($record.platform -isnot [string] -or $record.platform -cne $expectedPlatform) {
            Add-VerificationFailure "$context has an incorrect platform"
        }
        $expectedSigningStatus = if ($expectedName -ceq "fit-generator-windows-x64-UNSIGNED.exe") {
            "unsigned"
        } elseif ($expectedName -ceq "fit-generator-windows-x64.exe") {
            "authenticode-verification-required"
        } else {
            "apksigner-verification-required"
        }
        if (
            $record.signingStatus -isnot [string] -or
            $record.signingStatus -cne $expectedSigningStatus
        ) {
            Add-VerificationFailure "$context has an incorrect signingStatus"
        }
        if (-not (Test-IsJsonNonNegativeInteger $record.size)) {
            Add-VerificationFailure "$context has an invalid size"
        } else {
            try {
                if ([Int64]$record.size -ne $expectedFile.Length) {
                    Add-VerificationFailure "$context size does not match '$expectedName'"
                }
            } catch {
                Add-VerificationFailure "$context size is outside the supported range"
            }
        }
        if (
            $record.sha256 -isnot [string] -or
            $record.sha256 -cnotmatch '^[0-9a-f]{64}$'
        ) {
            Add-VerificationFailure "$context has an invalid SHA-256"
        } else {
            $actualHash = Get-FileSha256Lower $expectedFile "'$expectedName'"
            if ($null -ne $actualHash -and $record.sha256 -cne $actualHash) {
                Add-VerificationFailure "$context SHA-256 does not match '$expectedName'"
            }
        }
    }
}

function Test-WindowsPortableExecutable {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$File
    )

    $stream = $null
    $reader = $null
    try {
        $stream = [System.IO.File]::Open(
            $File.FullName,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::Read
        )
        $reader = New-Object -TypeName System.IO.BinaryReader -ArgumentList (, $stream)
        if ($stream.Length -lt 90) {
            Add-VerificationFailure "Windows artifact is too small to be a PE executable"
            return
        }
        if ($reader.ReadByte() -ne 0x4D -or $reader.ReadByte() -ne 0x5A) {
            Add-VerificationFailure "Windows artifact does not have an MZ header"
            return
        }

        $stream.Position = 0x3C
        $peOffset = $reader.ReadInt32()
        if ($peOffset -lt 64 -or ([Int64]$peOffset + 26) -gt $stream.Length) {
            Add-VerificationFailure "Windows artifact has an invalid PE header offset"
            return
        }

        $stream.Position = $peOffset
        if ($reader.ReadUInt32() -ne 0x00004550) {
            Add-VerificationFailure "Windows artifact does not have a PE signature"
            return
        }
        if ($reader.ReadUInt16() -ne 0x8664) {
            Add-VerificationFailure "Windows artifact is not x86-64"
            return
        }

        $stream.Position = $peOffset + 20
        $optionalHeaderSize = $reader.ReadUInt16()
        if (
            $optionalHeaderSize -lt 2 -or
            ([Int64]$peOffset + 24 + $optionalHeaderSize) -gt $stream.Length
        ) {
            Add-VerificationFailure "Windows artifact has an invalid optional header"
            return
        }
        $stream.Position = $peOffset + 22
        $characteristics = $reader.ReadUInt16()
        if (
            ($characteristics -band 0x0002) -eq 0 -or
            ($characteristics -band 0x2000) -ne 0
        ) {
            Add-VerificationFailure "Windows artifact is not an executable PE application"
            return
        }
        $stream.Position = $peOffset + 24
        if ($reader.ReadUInt16() -ne 0x020B) {
            Add-VerificationFailure "Windows artifact is not a PE32+ executable"
        }
    } catch {
        Add-VerificationFailure "Windows artifact could not be parsed as a PE executable"
    } finally {
        if ($null -ne $reader) {
            $reader.Dispose()
        } elseif ($null -ne $stream) {
            $stream.Dispose()
        }
    }
}

function Test-AndroidArchive {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$File
    )

    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
    } catch {
        Add-VerificationFailure "ZIP inspection support is unavailable"
        return
    }

    $archive = $null
    try {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($File.FullName)
        $requiredCorePaths = @(
            "lib/arm64-v8a/libfit_generator_core.so",
            "lib/x86_64/libfit_generator_core.so"
        )
        $hasUnsafeEntryPath = $false
        $nativeAbis = @()
        foreach ($entry in $archive.Entries) {
            if (
                $entry.FullName.StartsWith("/", [System.StringComparison]::Ordinal) -or
                $entry.FullName.Contains("\") -or
                $entry.FullName -cmatch '(^|/)[.][.](/|$)'
            ) {
                $hasUnsafeEntryPath = $true
            }
            if ($entry.FullName -cmatch '^lib/([^/]+)/') {
                $nativeAbis += $Matches[1]
            }
        }
        if ($hasUnsafeEntryPath) {
            Add-VerificationFailure "APK contains an unsafe ZIP entry path"
        }

        foreach ($requiredCorePath in $requiredCorePaths) {
            $matches = @(
                $archive.Entries | Where-Object { $_.FullName -ceq $requiredCorePath }
            )
            if ($matches.Count -ne 1) {
                Add-VerificationFailure "APK must contain exactly one '$requiredCorePath'"
            } elseif ($matches[0].Length -le 0) {
                Add-VerificationFailure "APK entry '$requiredCorePath' is empty"
            }
        }

        $actualCorePaths = @(
            $archive.Entries |
                Where-Object {
                    $_.FullName -cmatch '^lib/[^/]+/libfit_generator_core[.]so$'
                } |
                ForEach-Object { $_.FullName } |
                Sort-Object -CaseSensitive -Unique
        )
        if (
            $actualCorePaths.Count -ne 2 -or
            -not ($actualCorePaths -ccontains $requiredCorePaths[0]) -or
            -not ($actualCorePaths -ccontains $requiredCorePaths[1])
        ) {
            Add-VerificationFailure "APK core library ABI set must be exactly arm64-v8a and x86_64"
        }
        $actualNativeAbis = @($nativeAbis | Sort-Object -CaseSensitive -Unique)
        if (
            $actualNativeAbis.Count -ne 2 -or
            -not ($actualNativeAbis -ccontains "arm64-v8a") -or
            -not ($actualNativeAbis -ccontains "x86_64")
        ) {
            Add-VerificationFailure "APK native ABI set must be exactly arm64-v8a and x86_64"
        }
    } catch {
        Add-VerificationFailure "Android artifact is not a readable APK/ZIP archive"
    } finally {
        if ($null -ne $archive) {
            $archive.Dispose()
        }
    }
}

$releaseItem = Get-Item -LiteralPath $ReleaseDirectory -Force -ErrorAction SilentlyContinue
if ($null -eq $releaseItem -or -not $releaseItem.PSIsContainer) {
    Add-VerificationFailure "release directory does not exist"
    Complete-Verification
}
if ($releaseItem.PSProvider.Name -cne "FileSystem") {
    Add-VerificationFailure "release directory must use the file-system provider"
    Complete-Verification
}
if (Test-IsReparsePoint $releaseItem) {
    Add-VerificationFailure "release directory must not be a symbolic link or reparse point"
    Complete-Verification
}
$releaseRoot = $releaseItem.FullName
try {
    $topLevelItems = @(Get-ChildItem -LiteralPath $releaseRoot -Force)
    $topLevelFiles = @($topLevelItems | Where-Object { -not $_.PSIsContainer })
} catch {
    Add-VerificationFailure "release directory could not be enumerated"
    Complete-Verification
}

$windowsArtifact = $null
$windowsArtifactCanBeInspected = $false
$windowsCandidates = @(
    $topLevelFiles | Where-Object { $_.Extension -ieq ".exe" }
)
if ($windowsCandidates.Count -ne 1) {
    Add-VerificationFailure "expected exactly one top-level Windows EXE"
} else {
    $windowsArtifact = $windowsCandidates[0]
    $expectedWindowsPath = [System.IO.Path]::GetFullPath(
        (Join-Path $releaseRoot $windowsArtifact.Name)
    )
    $allowedWindowsNames = @(
        "fit-generator-windows-x64.exe",
        "fit-generator-windows-x64-UNSIGNED.exe"
    )
    if (-not ($allowedWindowsNames -ccontains $windowsArtifact.Name)) {
        Add-VerificationFailure "Windows artifact filename is not an allowed signed/UNSIGNED name"
    }
    if ($windowsArtifact.FullName -cne $expectedWindowsPath) {
        Add-VerificationFailure "Windows artifact is not at the expected top-level path"
    } elseif (Test-IsReparsePoint $windowsArtifact) {
        Add-VerificationFailure "Windows artifact must not be a symbolic link or reparse point"
    } elseif ($windowsArtifact.Length -le 0) {
        Add-VerificationFailure "Windows artifact is empty"
    } else {
        $windowsArtifactCanBeInspected = $true
        Test-WindowsPortableExecutable $windowsArtifact
    }
}

$androidArtifact = $null
$androidArtifactCanBeInspected = $false
$androidCandidates = @(
    $topLevelFiles | Where-Object { $_.Extension -ieq ".apk" }
)
if ($androidCandidates.Count -ne 1) {
    Add-VerificationFailure "expected exactly one top-level Android APK"
} else {
    $androidArtifact = $androidCandidates[0]
    $expectedAndroidPath = [System.IO.Path]::GetFullPath(
        (Join-Path $releaseRoot $androidArtifact.Name)
    )
    if ($androidArtifact.Name -cne "fit-generator-android.apk") {
        Add-VerificationFailure "Android artifact must be named 'fit-generator-android.apk'"
    }
    if ($androidArtifact.FullName -cne $expectedAndroidPath) {
        Add-VerificationFailure "Android artifact is not at the expected top-level path"
    } elseif (Test-IsReparsePoint $androidArtifact) {
        Add-VerificationFailure "Android artifact must not be a symbolic link or reparse point"
    } elseif ($androidArtifact.Length -le 0) {
        Add-VerificationFailure "Android artifact is empty"
    } else {
        $androidArtifactCanBeInspected = $true
        Test-AndroidArchive $androidArtifact
    }
}

$adjacentDlls = @(
    $topLevelFiles | Where-Object { $_.Extension -ieq ".dll" }
)
if ($adjacentDlls.Count -gt 0) {
    $dllNames = @($adjacentDlls | ForEach-Object { $_.Name } | Sort-Object)
    Add-VerificationFailure (
        "portable Windows release must not contain adjacent DLLs: " +
            ($dllNames -join ", ")
    )
}

$checksumFile = Get-RequiredReleaseFile `
    $releaseRoot `
    "fit-generator-SHA256SUMS.txt" `
    $topLevelItems
$metadataFile = Get-RequiredReleaseFile `
    $releaseRoot `
    "fit-generator-release-metadata.json" `
    $topLevelItems
$noticeFile = Get-RequiredReleaseFile `
    $releaseRoot `
    "THIRD-PARTY-NOTICES.txt" `
    $topLevelItems
$sbomFile = Get-RequiredReleaseFile `
    $releaseRoot `
    "fit-generator-sbom.spdx.json" `
    $topLevelItems

$allowedTopLevelNames = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
)
foreach ($allowedName in @(
    "fit-generator-android.apk",
    "fit-generator-SHA256SUMS.txt",
    "fit-generator-release-metadata.json",
    "THIRD-PARTY-NOTICES.txt",
    "fit-generator-sbom.spdx.json"
)) {
    [void]$allowedTopLevelNames.Add($allowedName)
}
if ($null -ne $windowsArtifact) {
    [void]$allowedTopLevelNames.Add($windowsArtifact.Name)
}
foreach ($topLevelItem in $topLevelItems) {
    if (-not $allowedTopLevelNames.Contains($topLevelItem.Name)) {
        Add-VerificationFailure "release directory contains an unexpected top-level entry"
    } elseif ($topLevelItem.PSIsContainer) {
        Add-VerificationFailure "release directory must not contain top-level directories"
    } elseif (Test-IsReparsePoint $topLevelItem) {
        Add-VerificationFailure "release directory must not contain top-level reparse points"
    }
}

$repositoryRoot = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine($PSScriptRoot, "..")
)
$checkedInNoticePath = Join-Path $repositoryRoot "THIRD-PARTY-NOTICES.txt"
$checkedInNotice = Get-Item -LiteralPath $checkedInNoticePath -Force -ErrorAction SilentlyContinue
if (
    $null -eq $checkedInNotice -or
    $checkedInNotice.PSIsContainer -or
    $checkedInNotice.Name -cne "THIRD-PARTY-NOTICES.txt"
) {
    Add-VerificationFailure "checked-in THIRD-PARTY-NOTICES.txt is missing"
    $checkedInNotice = $null
} elseif (Test-IsReparsePoint $checkedInNotice) {
    Add-VerificationFailure "checked-in THIRD-PARTY-NOTICES.txt must not be a reparse point"
    $checkedInNotice = $null
} elseif ($checkedInNotice.Length -le 0) {
    Add-VerificationFailure "checked-in THIRD-PARTY-NOTICES.txt is empty"
    $checkedInNotice = $null
}

if (
    $null -ne $checkedInNotice -and
    $null -ne $noticeFile -and
    $noticeFile.Length -gt 0
) {
    $checkedInNoticeHash = Get-FileSha256Lower $checkedInNotice "checked-in notices"
    $releaseNoticeHash = Get-FileSha256Lower $noticeFile "release notices"
    if (
        $null -ne $checkedInNoticeHash -and
        $null -ne $releaseNoticeHash -and
        $checkedInNoticeHash -cne $releaseNoticeHash
    ) {
        Add-VerificationFailure "release notices do not match the checked-in notices"
    }
}

if ($null -ne $sbomFile -and $sbomFile.Length -gt 0) {
    $sbomContent = Read-StrictUtf8File `
        $sbomFile `
        $maximumSbomBytes `
        "fit-generator-sbom.spdx.json"
    if ($null -ne $sbomContent) {
        try {
            $sbomDocument = $sbomContent.Text | ConvertFrom-Json
        } catch {
            Add-VerificationFailure "fit-generator-sbom.spdx.json is not valid JSON"
            $sbomDocument = $null
        }
        if ($null -eq $sbomDocument) {
            Add-VerificationFailure "fit-generator-sbom.spdx.json must contain an SPDX document"
        } else {
            $spdxVersionProperty = @(
                $sbomDocument.PSObject.Properties |
                    Where-Object { $_.Name -ceq "spdxVersion" }
            )
            $spdxIdProperty = @(
                $sbomDocument.PSObject.Properties |
                    Where-Object { $_.Name -ceq "SPDXID" }
            )
            if (
                $spdxVersionProperty.Count -ne 1 -or
                $spdxVersionProperty[0].Value -isnot [string] -or
                $spdxVersionProperty[0].Value -cnotmatch '^SPDX-[0-9]+[.][0-9]+$'
            ) {
                Add-VerificationFailure "fit-generator-sbom.spdx.json has no valid spdxVersion"
            }
            if (
                $spdxIdProperty.Count -ne 1 -or
                $spdxIdProperty[0].Value -isnot [string] -or
                $spdxIdProperty[0].Value -cne "SPDXRef-DOCUMENT"
            ) {
                Add-VerificationFailure "fit-generator-sbom.spdx.json has no SPDX document identifier"
            }
        }
    }
}

$artifactNames = @()
if ($null -ne $windowsArtifact) {
    $artifactNames += $windowsArtifact.Name
}
if ($null -ne $androidArtifact) {
    $artifactNames += $androidArtifact.Name
}

$checksumRecords = @()
if ($null -ne $checksumFile -and $checksumFile.Length -gt 0) {
    $checksumContent = Read-StrictUtf8File `
        $checksumFile `
        $maximumChecksumBytes `
        "fit-generator-SHA256SUMS.txt"
    $checksumLines = @()
    if ($null -ne $checksumContent) {
        if (
            $checksumContent.Text.Contains("`r") -or
            -not $checksumContent.Text.EndsWith("`n", [System.StringComparison]::Ordinal)
        ) {
            Add-VerificationFailure (
                "fit-generator-SHA256SUMS.txt must use LF line endings and one final newline"
            )
        } else {
            $checksumBody = $checksumContent.Text.Substring(
                0,
                $checksumContent.Text.Length - 1
            )
            $checksumLines = @($checksumBody -split "`n")
        }
    }
    $lineNumber = 0
    foreach ($line in $checksumLines) {
        $lineNumber += 1
        if ($line -cnotmatch '^([0-9a-f]{64})  ([^/\\]+)$') {
            Add-VerificationFailure "checksum line $lineNumber has invalid format"
            continue
        }
        $expectedHash = $Matches[1]
        $fileName = $Matches[2]
        if (
            $fileName -eq "." -or
            $fileName -eq ".." -or
            [System.IO.Path]::GetFileName($fileName) -cne $fileName
        ) {
            Add-VerificationFailure "checksum line $lineNumber contains an unsafe filename"
            continue
        }
        if (-not ($artifactNames -ccontains $fileName)) {
            Add-VerificationFailure "checksum file contains unexpected entry '$fileName'"
            continue
        }
        if (@($checksumRecords | Where-Object { $_.Name -ceq $fileName }).Count -gt 0) {
            Add-VerificationFailure "checksum file contains duplicate entry '$fileName'"
            continue
        }
        $checksumRecords += New-Object PSObject -Property @{
            Hash = $expectedHash
            Name = $fileName
        }
    }

    foreach ($record in $checksumRecords) {
        $recordPath = Join-Path $releaseRoot $record.Name
        $recordItem = Get-Item -LiteralPath $recordPath -Force -ErrorAction SilentlyContinue
        if ($null -eq $recordItem -or $recordItem.PSIsContainer) {
            Add-VerificationFailure "checksum references missing file '$($record.Name)'"
            continue
        }
        if (Test-IsReparsePoint $recordItem) {
            Add-VerificationFailure "checksum target '$($record.Name)' must not be a reparse point"
            continue
        }
        try {
            $actualHash = (Get-FileHash -LiteralPath $recordItem.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($actualHash -cne $record.Hash) {
                Add-VerificationFailure "SHA-256 mismatch for '$($record.Name)'"
            }
        } catch {
            Add-VerificationFailure "unable to hash '$($record.Name)'"
        }
    }

    foreach ($artifactName in $artifactNames) {
        if (@($checksumRecords | Where-Object { $_.Name -ceq $artifactName }).Count -ne 1) {
            Add-VerificationFailure "checksum file must contain '$artifactName' exactly once"
        }
    }

    if ($checksumRecords.Count -eq $artifactNames.Count) {
        $expectedChecksumOrder = [string[]]@($artifactNames)
        [System.Array]::Sort(
            $expectedChecksumOrder,
            [System.StringComparer]::Ordinal
        )
        $checksumOrderMatches = $true
        for ($index = 0; $index -lt $expectedChecksumOrder.Count; $index += 1) {
            if ($checksumRecords[$index].Name -cne $expectedChecksumOrder[$index]) {
                $checksumOrderMatches = $false
                break
            }
        }
        if (-not $checksumOrderMatches) {
            Add-VerificationFailure "checksum records must use ordinal filename order"
        }
    }
}

if (
    $null -ne $metadataFile -and
    $metadataFile.Length -gt 0 -and
    $windowsArtifactCanBeInspected -and
    $androidArtifactCanBeInspected -and
    $null -ne $noticeFile -and
    $noticeFile.Length -gt 0 -and
    $null -ne $sbomFile -and
    $sbomFile.Length -gt 0
) {
    Test-ReleaseMetadata `
        $metadataFile `
        $windowsArtifact `
        $androidArtifact `
        $noticeFile `
        $sbomFile
}

if ($RequireWindowsSignature) {
    if (
        $null -eq $windowsArtifact -or
        $windowsArtifact.Name -cne "fit-generator-windows-x64.exe"
    ) {
        Add-VerificationFailure "a signed Windows artifact is required"
    } elseif (-not $windowsArtifactCanBeInspected) {
        Add-VerificationFailure "the Windows artifact is not safe to inspect for a signature"
    } else {
        $signatureCommand = Get-Command "Get-AuthenticodeSignature" `
            -CommandType Cmdlet `
            -ErrorAction SilentlyContinue
        if ($null -eq $signatureCommand) {
            Add-VerificationFailure "Authenticode verification is unavailable on this host"
        } else {
            try {
                $signature = & $signatureCommand -LiteralPath $windowsArtifact.FullName
                $signatureStatus = $signature.Status.ToString()
                if ($signatureStatus -ceq "NotSigned") {
                    Add-VerificationFailure "Windows artifact is unsigned"
                } elseif ($signatureStatus -cne "Valid") {
                    Add-VerificationFailure (
                        "Windows Authenticode signature is invalid " +
                            "(status '$signatureStatus')"
                    )
                }
            } catch {
                Add-VerificationFailure "Windows Authenticode signature could not be verified"
            }
        }
    }
} elseif (
    $null -ne $windowsArtifact -and
    $windowsArtifact.Name -ceq "fit-generator-windows-x64.exe"
) {
    Add-VerificationFailure (
        "the signed Windows filename requires explicit -RequireWindowsSignature verification"
    )
}

if ($RequireAndroidSignature) {
    if ($null -eq $androidArtifact) {
        Add-VerificationFailure "a signed Android artifact is required"
    } elseif (-not $androidArtifactCanBeInspected) {
        Add-VerificationFailure "the Android artifact is not safe to inspect for a signature"
    } else {
        $apkSigner = Get-Command "apksigner" -CommandType Application -ErrorAction SilentlyContinue
        if ($null -eq $apkSigner) {
            Add-VerificationFailure "apksigner is required for Android signature verification"
        } else {
            try {
                & $apkSigner.Source verify --verbose --print-certs $androidArtifact.FullName
                if ($LASTEXITCODE -ne 0) {
                    Add-VerificationFailure "Android APK signature verification failed"
                }
            } catch {
                Add-VerificationFailure "Android APK signature could not be verified"
            }
        }
    }
}

Complete-Verification
