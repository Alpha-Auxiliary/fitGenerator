param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ReleaseDirectory,
    [ValidateNotNullOrEmpty()]
    [string]$ReleaseVersion = "UNSPECIFIED",
    [ValidateNotNullOrEmpty()]
    [string]$SourceCommit = "UNSPECIFIED"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$checksumFileName = "fit-generator-SHA256SUMS.txt"
$metadataFileName = "fit-generator-release-metadata.json"
$noticeFileName = "THIRD-PARTY-NOTICES.txt"
$sbomFileName = "fit-generator-sbom.spdx.json"
$unsignedWindowsName = "fit-generator-windows-x64-UNSIGNED.exe"
$signedWindowsName = "fit-generator-windows-x64.exe"
$androidName = "fit-generator-android.apk"
$maximumGeneratedTextBytes = 4 * 1024 * 1024
$maximumSbomBytes = 64 * 1024 * 1024
$utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
$strictUtf8WithoutBom = [System.Text.UTF8Encoding]::new($false, $true)

function Stop-MetadataGeneration {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    [Console]::Error.WriteLine("Release metadata error: $Message")
    exit 1
}

function Test-IsReparsePoint {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileSystemInfo]$Item
    )

    return (($Item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0)
}

function Assert-SafeRegularFile {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileSystemInfo]$Item,
        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    if ($Item.PSIsContainer) {
        Stop-MetadataGeneration "$Description must be a regular file"
    }
    if (Test-IsReparsePoint $Item) {
        Stop-MetadataGeneration "$Description must not be a symbolic link or reparse point"
    }
    if ($Item.Length -le 0) {
        Stop-MetadataGeneration "$Description is empty"
    }
}

function Get-RequiredTopLevelFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Root,
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [System.IO.FileSystemInfo[]]$TopLevelItems
    )

    $matches = @($TopLevelItems | Where-Object { $_.Name -ieq $Name })
    if ($matches.Count -ne 1) {
        Stop-MetadataGeneration "expected exactly one top-level '$Name'"
    }
    $item = $matches[0]
    if ($item.Name -cne $Name) {
        Stop-MetadataGeneration "'$Name' has incorrect filename casing"
    }
    $expectedPath = [System.IO.Path]::GetFullPath((Join-Path $Root $Name))
    if ($item.FullName -cne $expectedPath) {
        Stop-MetadataGeneration "'$Name' did not resolve to the expected top-level path"
    }
    Assert-SafeRegularFile $item "'$Name'"
    return [System.IO.FileInfo]$item
}

function Assert-SafeOutputTarget {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [System.IO.FileSystemInfo[]]$TopLevelItems
    )

    $matches = @($TopLevelItems | Where-Object { $_.Name -ieq $Name })
    if ($matches.Count -gt 1) {
        Stop-MetadataGeneration "multiple top-level entries collide with '$Name'"
    }
    if ($matches.Count -eq 1) {
        $item = $matches[0]
        if ($item.Name -cne $Name) {
            Stop-MetadataGeneration "existing output '$Name' has incorrect filename casing"
        }
        if ($item.PSIsContainer) {
            Stop-MetadataGeneration "existing output '$Name' is a directory"
        }
        if (Test-IsReparsePoint $item) {
            Stop-MetadataGeneration "existing output '$Name' must not be a symbolic link or reparse point"
        }
    }
}

function Get-FileDigest {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$File
    )

    $stream = $null
    $sha256 = $null
    try {
        $stream = [System.IO.File]::Open(
            $File.FullName,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::Read
        )
        $size = [Int64]$stream.Length
        $sha256 = [System.Security.Cryptography.SHA256]::Create()
        $hashBytes = $sha256.ComputeHash($stream)
        $hash = ([System.BitConverter]::ToString($hashBytes)).Replace("-", "").ToLowerInvariant()
        return [ordered]@{
            Sha256 = $hash
            Size = $size
        }
    } catch {
        Stop-MetadataGeneration "unable to hash '$($File.Name)'"
    } finally {
        if ($null -ne $sha256) {
            $sha256.Dispose()
        }
        if ($null -ne $stream) {
            $stream.Dispose()
        }
    }
}

function Get-ByteDigest {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [byte[]]$Bytes
    )

    $sha256 = $null
    try {
        $sha256 = [System.Security.Cryptography.SHA256]::Create()
        $hashBytes = $sha256.ComputeHash($Bytes)
        return [ordered]@{
            Sha256 = ([System.BitConverter]::ToString($hashBytes)).Replace("-", "").ToLowerInvariant()
            Size = [Int64]$Bytes.LongLength
        }
    } finally {
        if ($null -ne $sha256) {
            $sha256.Dispose()
        }
    }
}

function Write-NewStagingFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [byte[]]$Bytes
    )

    $stream = $null
    try {
        $stream = [System.IO.File]::Open(
            $Path,
            [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write,
            [System.IO.FileShare]::None
        )
        $stream.Write($Bytes, 0, $Bytes.Length)
        $stream.Flush($true)
    } finally {
        if ($null -ne $stream) {
            $stream.Dispose()
        }
    }

    $item = Get-Item -LiteralPath $Path -Force
    if (
        $item.PSIsContainer -or
        $item.Name -cne $Name -or
        (Test-IsReparsePoint $item) -or
        $item.Length -ne $Bytes.LongLength
    ) {
        throw "staging file validation failed"
    }
}

function Remove-OwnedStagingFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    try {
        $item = Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
        if ($null -eq $item) {
            return $true
        }
        if (
            $item.PSIsContainer -or
            $item.Name -cne $Name -or
            (Test-IsReparsePoint $item)
        ) {
            return $false
        }
        [System.IO.File]::Delete($item.FullName)
        return -not (Test-Path -LiteralPath $Path)
    } catch {
        return $false
    }
}

function Publish-OutputSet {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Root,
        [Parameter(Mandatory = $true)]
        [object[]]$Outputs
    )

    $seenNames = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    $currentItems = @(Get-ChildItem -LiteralPath $Root -Force)
    $records = @()
    $transactionId = [Guid]::NewGuid().ToString("N")

    foreach ($output in $Outputs) {
        if (
            $output.Name -isnot [string] -or
            [System.IO.Path]::GetFileName($output.Name) -cne $output.Name -or
            -not $seenNames.Add($output.Name) -or
            $output.Bytes -isnot [byte[]] -or
            $output.Bytes.LongLength -le 0 -or
            $output.Bytes.LongLength -gt $maximumGeneratedTextBytes
        ) {
            Stop-MetadataGeneration "generated output set is invalid"
        }

        $matches = @($currentItems | Where-Object { $_.Name -ieq $output.Name })
        if ($matches.Count -gt 1) {
            Stop-MetadataGeneration "multiple top-level entries collide with '$($output.Name)'"
        }

        $targetPath = [System.IO.Path]::GetFullPath((Join-Path $Root $output.Name))
        $hadExisting = $matches.Count -eq 1
        $originalBytes = $null
        $originalDigest = $null
        if ($hadExisting) {
            $existing = $matches[0]
            if (
                $existing.Name -cne $output.Name -or
                $existing.FullName -cne $targetPath -or
                $existing.PSIsContainer -or
                (Test-IsReparsePoint $existing) -or
                $existing.Length -gt $maximumGeneratedTextBytes
            ) {
                Stop-MetadataGeneration "existing output '$($output.Name)' is not a safe regular file"
            }
            try {
                $originalBytes = [System.IO.File]::ReadAllBytes($existing.FullName)
            } catch {
                Stop-MetadataGeneration "existing output '$($output.Name)' could not be read"
            }
            if ($originalBytes.LongLength -ne $existing.Length) {
                Stop-MetadataGeneration "existing output '$($output.Name)' changed during preflight"
            }
            $originalDigest = Get-ByteDigest $originalBytes
        }

        $stageName = ".$($output.Name).$transactionId.stage"
        $stagePath = [System.IO.Path]::GetFullPath((Join-Path $Root $stageName))
        $records += [pscustomobject]@{
            Name = $output.Name
            TargetPath = $targetPath
            Bytes = [byte[]]$output.Bytes
            NewDigest = (Get-ByteDigest ([byte[]]$output.Bytes))
            HadExisting = $hadExisting
            OriginalBytes = $originalBytes
            OriginalDigest = $originalDigest
            StageName = $stageName
            StagePath = $stagePath
            Promoted = $false
            RecoveryName = $null
            RecoveryPath = $null
        }
    }

    try {
        foreach ($record in $records) {
            Write-NewStagingFile $record.StagePath $record.StageName $record.Bytes
        }
    } catch {
        $stageCleanupFailed = $false
        foreach ($record in $records) {
            if (-not (Remove-OwnedStagingFile $record.StagePath $record.StageName)) {
                $stageCleanupFailed = $true
            }
        }
        if ($stageCleanupFailed) {
            Stop-MetadataGeneration (
                "unable to stage the complete release metadata set and staging cleanup failed"
            )
        }
        Stop-MetadataGeneration "unable to stage the complete release metadata set"
    }

    $promotionFailed = $false
    try {
        foreach ($record in $records) {
            $stageItem = Get-Item -LiteralPath $record.StagePath -Force
            if (
                $stageItem.PSIsContainer -or
                $stageItem.Name -cne $record.StageName -or
                (Test-IsReparsePoint $stageItem) -or
                $stageItem.Length -ne $record.NewDigest.Size
            ) {
                throw "staged output changed before promotion"
            }
            $stageBytes = [System.IO.File]::ReadAllBytes($stageItem.FullName)
            $stageDigest = Get-ByteDigest $stageBytes
            if ($stageDigest.Sha256 -cne $record.NewDigest.Sha256) {
                throw "staged output hash changed before promotion"
            }

            $currentTarget = Get-Item `
                -LiteralPath $record.TargetPath `
                -Force `
                -ErrorAction SilentlyContinue
            if ($record.HadExisting) {
                if (
                    $null -eq $currentTarget -or
                    $currentTarget.PSIsContainer -or
                    $currentTarget.Name -cne $record.Name -or
                    (Test-IsReparsePoint $currentTarget) -or
                    $currentTarget.Length -ne $record.OriginalDigest.Size
                ) {
                    throw "existing output changed before promotion"
                }
                $currentBytes = [System.IO.File]::ReadAllBytes($currentTarget.FullName)
                $currentDigest = Get-ByteDigest $currentBytes
                if ($currentDigest.Sha256 -cne $record.OriginalDigest.Sha256) {
                    throw "existing output hash changed before promotion"
                }
                [System.IO.File]::Replace(
                    $record.StagePath,
                    $record.TargetPath,
                    $null
                )
            } else {
                if ($null -ne $currentTarget) {
                    throw "output target appeared after preflight"
                }
                [System.IO.File]::Move($record.StagePath, $record.TargetPath)
            }
            $record.Promoted = $true
        }
    } catch {
        $promotionFailed = $true
    }

    if ($promotionFailed) {
        $rollbackFailed = $false
        for ($index = $records.Count - 1; $index -ge 0; $index -= 1) {
            $record = $records[$index]
            if (-not $record.Promoted) {
                continue
            }
            try {
                if ($record.HadExisting) {
                    $record.RecoveryName = ".$($record.Name).$transactionId.recovery"
                    $record.RecoveryPath = [System.IO.Path]::GetFullPath(
                        (Join-Path $Root $record.RecoveryName)
                    )
                    Write-NewStagingFile `
                        $record.RecoveryPath `
                        $record.RecoveryName `
                        $record.OriginalBytes
                    $currentTarget = Get-Item `
                        -LiteralPath $record.TargetPath `
                        -Force `
                        -ErrorAction SilentlyContinue
                    if ($null -eq $currentTarget) {
                        [System.IO.File]::Move($record.RecoveryPath, $record.TargetPath)
                    } elseif (
                        $currentTarget.PSIsContainer -or
                        $currentTarget.Name -cne $record.Name -or
                        (Test-IsReparsePoint $currentTarget)
                    ) {
                        throw "promoted output is not safe to roll back"
                    } else {
                        $currentBytes = [System.IO.File]::ReadAllBytes($currentTarget.FullName)
                        $currentDigest = Get-ByteDigest $currentBytes
                        if ($currentDigest.Sha256 -cne $record.NewDigest.Sha256) {
                            throw "promoted output changed before rollback"
                        }
                        [System.IO.File]::Replace(
                            $record.RecoveryPath,
                            $record.TargetPath,
                            $null
                        )
                    }
                } else {
                    $currentTarget = Get-Item `
                        -LiteralPath $record.TargetPath `
                        -Force `
                        -ErrorAction SilentlyContinue
                    if ($null -ne $currentTarget) {
                        if (
                            $currentTarget.PSIsContainer -or
                            $currentTarget.Name -cne $record.Name -or
                            (Test-IsReparsePoint $currentTarget)
                        ) {
                            throw "new output is not safe to roll back"
                        }
                        $currentBytes = [System.IO.File]::ReadAllBytes($currentTarget.FullName)
                        $currentDigest = Get-ByteDigest $currentBytes
                        if ($currentDigest.Sha256 -cne $record.NewDigest.Sha256) {
                            throw "new output changed before rollback"
                        }
                        [System.IO.File]::Delete($currentTarget.FullName)
                    }
                }
            } catch {
                $rollbackFailed = $true
            }
        }

        foreach ($record in $records) {
            if (-not (Remove-OwnedStagingFile $record.StagePath $record.StageName)) {
                $rollbackFailed = $true
            }
        }
        if (-not $rollbackFailed) {
            foreach ($record in $records) {
                if (
                    $null -ne $record.RecoveryPath -and
                    -not (Remove-OwnedStagingFile $record.RecoveryPath $record.RecoveryName)
                ) {
                    $rollbackFailed = $true
                }
            }
        }
        if ($rollbackFailed) {
            Stop-MetadataGeneration (
                "release metadata promotion failed and rollback was incomplete; " +
                    "do not use this release directory"
            )
        }
        Stop-MetadataGeneration "release metadata promotion failed; existing outputs were restored"
    }

    foreach ($record in $records) {
        if (-not (Remove-OwnedStagingFile $record.StagePath $record.StageName)) {
            Stop-MetadataGeneration "release metadata was committed but staging cleanup failed"
        }
    }
}

if ($ReleaseVersion -cne "UNSPECIFIED" -and $ReleaseVersion -cnotmatch '^v[0-9]+[.][0-9]+[.][0-9]+$') {
    Stop-MetadataGeneration "ReleaseVersion must be UNSPECIFIED or vMAJOR.MINOR.PATCH"
}
if (
    $SourceCommit -cne "UNSPECIFIED" -and
    $SourceCommit -cnotmatch '^([0-9a-f]{40}|[0-9a-f]{64})$'
) {
    Stop-MetadataGeneration "SourceCommit must be UNSPECIFIED or a lowercase 40/64-character hex commit ID"
}
$versionIsSpecified = $ReleaseVersion -cne "UNSPECIFIED"
$commitIsSpecified = $SourceCommit -cne "UNSPECIFIED"
if ($versionIsSpecified -ne $commitIsSpecified) {
    Stop-MetadataGeneration "ReleaseVersion and SourceCommit must either both be specified or both be UNSPECIFIED"
}

$releaseItem = Get-Item -LiteralPath $ReleaseDirectory -Force -ErrorAction SilentlyContinue
if ($null -eq $releaseItem -or -not $releaseItem.PSIsContainer) {
    Stop-MetadataGeneration "release directory does not exist"
}
if ($releaseItem.PSProvider.Name -cne "FileSystem") {
    Stop-MetadataGeneration "release directory must use the file-system provider"
}
if (Test-IsReparsePoint $releaseItem) {
    Stop-MetadataGeneration "release directory must not be a symbolic link or reparse point"
}
$releaseRoot = $releaseItem.FullName

try {
    $topLevelItems = @(Get-ChildItem -LiteralPath $releaseRoot -Force)
} catch {
    Stop-MetadataGeneration "release directory could not be enumerated"
}

$windowsCandidates = @(
    $topLevelItems |
        Where-Object { -not $_.PSIsContainer -and $_.Extension -ieq ".exe" }
)
if ($windowsCandidates.Count -ne 1) {
    Stop-MetadataGeneration "expected exactly one top-level Windows EXE"
}
$windowsArtifact = $windowsCandidates[0]
if (
    $windowsArtifact.Name -cne $unsignedWindowsName -and
    $windowsArtifact.Name -cne $signedWindowsName
) {
    Stop-MetadataGeneration "Windows artifact filename is not an allowed signed/UNSIGNED name"
}
Assert-SafeRegularFile $windowsArtifact "Windows artifact"
$expectedWindowsPath = [System.IO.Path]::GetFullPath(
    (Join-Path $releaseRoot $windowsArtifact.Name)
)
if ($windowsArtifact.FullName -cne $expectedWindowsPath) {
    Stop-MetadataGeneration "Windows artifact did not resolve to the expected top-level path"
}

$androidCandidates = @(
    $topLevelItems |
        Where-Object { -not $_.PSIsContainer -and $_.Extension -ieq ".apk" }
)
if ($androidCandidates.Count -ne 1) {
    Stop-MetadataGeneration "expected exactly one top-level Android APK"
}
$androidArtifact = $androidCandidates[0]
if ($androidArtifact.Name -cne $androidName) {
    Stop-MetadataGeneration "Android artifact must be named '$androidName'"
}
Assert-SafeRegularFile $androidArtifact "Android artifact"
$expectedAndroidPath = [System.IO.Path]::GetFullPath(
    (Join-Path $releaseRoot $androidArtifact.Name)
)
if ($androidArtifact.FullName -cne $expectedAndroidPath) {
    Stop-MetadataGeneration "Android artifact did not resolve to the expected top-level path"
}

$sbomFile = Get-RequiredTopLevelFile $releaseRoot $sbomFileName $topLevelItems
foreach ($outputName in @($checksumFileName, $metadataFileName, $noticeFileName)) {
    Assert-SafeOutputTarget $outputName $topLevelItems
}

$allowedTopLevelNames = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
)
foreach ($allowedName in @(
    $windowsArtifact.Name,
    $androidName,
    $sbomFileName,
    $checksumFileName,
    $metadataFileName,
    $noticeFileName
)) {
    [void]$allowedTopLevelNames.Add($allowedName)
}
foreach ($topLevelItem in $topLevelItems) {
    if (-not $allowedTopLevelNames.Contains($topLevelItem.Name)) {
        Stop-MetadataGeneration "release directory contains an unexpected top-level entry"
    }
}

$repositoryRoot = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine($PSScriptRoot, "..")
)
$noticeSourcePath = Join-Path $repositoryRoot $noticeFileName
$noticeSource = Get-Item -LiteralPath $noticeSourcePath -Force -ErrorAction SilentlyContinue
if ($null -eq $noticeSource) {
    Stop-MetadataGeneration "checked-in '$noticeFileName' is missing"
}
if ($noticeSource.Name -cne $noticeFileName) {
    Stop-MetadataGeneration "checked-in '$noticeFileName' has incorrect filename casing"
}
$expectedNoticeSourcePath = [System.IO.Path]::GetFullPath($noticeSourcePath)
if ($noticeSource.FullName -cne $expectedNoticeSourcePath) {
    Stop-MetadataGeneration "checked-in '$noticeFileName' did not resolve to the expected path"
}
Assert-SafeRegularFile $noticeSource "checked-in '$noticeFileName'"
$noticeDestinationPath = [System.IO.Path]::GetFullPath((Join-Path $releaseRoot $noticeFileName))
if (
    [System.StringComparer]::OrdinalIgnoreCase.Equals(
        $noticeSource.FullName,
        $noticeDestinationPath
    )
) {
    Stop-MetadataGeneration "release directory must not overwrite the checked-in notices file"
}

$artifactNames = [string[]]@($windowsArtifact.Name, $androidArtifact.Name)
[System.Array]::Sort($artifactNames, [System.StringComparer]::Ordinal)
$artifactRecords = @()
$checksumLines = @()
foreach ($artifactName in $artifactNames) {
    $artifact = if ($artifactName -ceq $windowsArtifact.Name) {
        $windowsArtifact
    } else {
        $androidArtifact
    }
    $digest = Get-FileDigest $artifact
    $platform = if ($artifactName -ceq $windowsArtifact.Name) { "windows" } else { "android" }
    $signingStatus = if ($artifactName -ceq $unsignedWindowsName) {
        "unsigned"
    } elseif ($artifactName -ceq $signedWindowsName) {
        "authenticode-verification-required"
    } else {
        "apksigner-verification-required"
    }
    $artifactRecords += [ordered]@{
        fileName = $artifactName
        platform = $platform
        size = [Int64]$digest.Size
        sha256 = $digest.Sha256
        signingStatus = $signingStatus
    }
    $checksumLines += "$($digest.Sha256)  $artifactName"
}

try {
    $noticeBytes = [System.IO.File]::ReadAllBytes($noticeSource.FullName)
} catch {
    Stop-MetadataGeneration "unable to read checked-in '$noticeFileName'"
}
if (
    $noticeBytes.LongLength -ne $noticeSource.Length -or
    $noticeBytes.LongLength -le 0 -or
    $noticeBytes.LongLength -gt $maximumGeneratedTextBytes
) {
    Stop-MetadataGeneration "checked-in '$noticeFileName' has an unsupported size"
}
if (
    $noticeBytes.Length -ge 3 -and
    $noticeBytes[0] -eq 0xEF -and
    $noticeBytes[1] -eq 0xBB -and
    $noticeBytes[2] -eq 0xBF
) {
    Stop-MetadataGeneration "checked-in '$noticeFileName' must use UTF-8 without a BOM"
}
try {
    [void]$strictUtf8WithoutBom.GetString($noticeBytes)
} catch {
    Stop-MetadataGeneration "checked-in '$noticeFileName' is not strict UTF-8"
}

if ($sbomFile.Length -gt $maximumSbomBytes) {
    Stop-MetadataGeneration "'$sbomFileName' exceeds the supported size"
}
try {
    $sbomBytes = [System.IO.File]::ReadAllBytes($sbomFile.FullName)
} catch {
    Stop-MetadataGeneration "unable to read '$sbomFileName'"
}
if ($sbomBytes.LongLength -ne $sbomFile.Length) {
    Stop-MetadataGeneration "'$sbomFileName' changed during preflight"
}
if (
    $sbomBytes.Length -ge 3 -and
    $sbomBytes[0] -eq 0xEF -and
    $sbomBytes[1] -eq 0xBB -and
    $sbomBytes[2] -eq 0xBF
) {
    Stop-MetadataGeneration "'$sbomFileName' must use UTF-8 without a BOM"
}
try {
    $sbomText = $strictUtf8WithoutBom.GetString($sbomBytes)
    $sbomDocument = $sbomText | ConvertFrom-Json
} catch {
    Stop-MetadataGeneration "'$sbomFileName' is not valid strict UTF-8 JSON"
}
if ($null -eq $sbomDocument) {
    Stop-MetadataGeneration "'$sbomFileName' must contain an SPDX JSON document"
}
$spdxVersionProperties = @(
    $sbomDocument.PSObject.Properties |
        Where-Object { $_.Name -ceq "spdxVersion" }
)
$spdxIdProperties = @(
    $sbomDocument.PSObject.Properties |
        Where-Object { $_.Name -ceq "SPDXID" }
)
if (
    $spdxVersionProperties.Count -ne 1 -or
    $spdxVersionProperties[0].Value -isnot [string] -or
    $spdxVersionProperties[0].Value -cnotmatch '^SPDX-[0-9]+[.][0-9]+$' -or
    $spdxIdProperties.Count -ne 1 -or
    $spdxIdProperties[0].Value -isnot [string] -or
    $spdxIdProperties[0].Value -cne "SPDXRef-DOCUMENT"
) {
    Stop-MetadataGeneration "'$sbomFileName' is not an SPDX JSON document"
}

$checksumText = [string]::Join("`n", [string[]]$checksumLines) + "`n"
$checksumBytes = $utf8WithoutBom.GetBytes($checksumText)
$noticeDigest = Get-ByteDigest $noticeBytes
$sbomDigest = Get-ByteDigest $sbomBytes
$currentSbomDigest = Get-FileDigest $sbomFile
if (
    $currentSbomDigest.Size -ne $sbomDigest.Size -or
    $currentSbomDigest.Sha256 -cne $sbomDigest.Sha256
) {
    Stop-MetadataGeneration "'$sbomFileName' changed after validation"
}
$metadata = [ordered]@{
    schemaVersion = 1
    releaseVersion = $ReleaseVersion
    sourceCommit = $SourceCommit
    sbom = [ordered]@{
        fileName = $sbomFileName
        size = [Int64]$sbomDigest.Size
        sha256 = $sbomDigest.Sha256
    }
    notices = [ordered]@{
        fileName = $noticeFileName
        size = [Int64]$noticeDigest.Size
        sha256 = $noticeDigest.Sha256
    }
    artifacts = $artifactRecords
}
$metadataJson = $metadata | ConvertTo-Json -Depth 6 -Compress
$metadataJson = $metadataJson.Replace("`r`n", "`n").Replace("`r", "`n") + "`n"
$metadataBytes = $utf8WithoutBom.GetBytes($metadataJson)

foreach ($artifactRecord in $artifactRecords) {
    $artifact = if ($artifactRecord.fileName -ceq $windowsArtifact.Name) {
        $windowsArtifact
    } else {
        $androidArtifact
    }
    $currentArtifactDigest = Get-FileDigest $artifact
    if (
        $currentArtifactDigest.Size -ne $artifactRecord.size -or
        $currentArtifactDigest.Sha256 -cne $artifactRecord.sha256
    ) {
        Stop-MetadataGeneration "release artifact changed after validation"
    }
}

$outputSet = @(
    [pscustomobject]@{
        Name = $noticeFileName
        Bytes = [byte[]]$noticeBytes
    }
    [pscustomobject]@{
        Name = $checksumFileName
        Bytes = [byte[]]$checksumBytes
    }
    [pscustomobject]@{
        Name = $metadataFileName
        Bytes = [byte[]]$metadataBytes
    }
)
Publish-OutputSet $releaseRoot $outputSet

if (-not $versionIsSpecified) {
    Write-Output "Release metadata generated with UNSPECIFIED version and source commit."
} else {
    Write-Output "Release metadata generated for $ReleaseVersion."
}
