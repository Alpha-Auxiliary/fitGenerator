param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Release",
    [switch]$WhatIf
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Stop-WithStableError {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    [Console]::Error.WriteLine("Windows Rust build error: $Message")
    exit 1
}

$targetTriple = "x86_64-pc-windows-msvc"
$repositoryRoot = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine($PSScriptRoot, "..")
)
$manifestRelativePath = "core-rust/Cargo.toml"
$lockRelativePath = "core-rust/Cargo.lock"
$profileName = if ($Configuration -eq "Release") { "release" } else { "debug" }
$sourceRelativePath = "core-rust/target/$targetTriple/$profileName/fit_generator_core.dll"
$destinationDirectoryRelativePath = "native-windows/FitGenerator.Native/runtimes/win-x64/native"
$destinationRelativePath = "$destinationDirectoryRelativePath/fit_generator_core.dll"

$manifestPath = Join-Path $repositoryRoot $manifestRelativePath
$lockPath = Join-Path $repositoryRoot $lockRelativePath
$sourcePath = Join-Path $repositoryRoot $sourceRelativePath
$destinationDirectory = Join-Path $repositoryRoot $destinationDirectoryRelativePath
$destinationPath = Join-Path $repositoryRoot $destinationRelativePath

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    Stop-WithStableError "missing core-rust/Cargo.toml"
}
if (-not (Test-Path -LiteralPath $lockPath -PathType Leaf)) {
    Stop-WithStableError "missing core-rust/Cargo.lock; generate and review it before a locked build"
}

$rustupCommand = Get-Command "rustup" -CommandType Application -ErrorAction SilentlyContinue
if ($null -eq $rustupCommand) {
    Stop-WithStableError "missing command 'rustup'; install it explicitly before running this script"
}
$cargoCommand = Get-Command "cargo" -CommandType Application -ErrorAction SilentlyContinue
if ($null -eq $cargoCommand) {
    Stop-WithStableError "missing command 'cargo'; install it explicitly before running this script"
}
$rustupPath = $rustupCommand.Source
$cargoPath = $cargoCommand.Source

$installedTargets = @(& $rustupPath target list --installed 2>$null)
if ($LASTEXITCODE -ne 0) {
    Stop-WithStableError "unable to inspect installed Rust targets"
}
if ($installedTargets -notcontains $targetTriple) {
    Stop-WithStableError (
        "missing Rust target '$targetTriple'; provision the fixed target before running this script"
    )
}

if ($WhatIf) {
    Write-Output "Preflight passed for the $Configuration Windows Rust build."
    Write-Output (
        "Would build $manifestRelativePath with --locked for $targetTriple and copy " +
            "fit_generator_core.dll to $destinationRelativePath."
    )
    return
}

$cargoArguments = @(
    "build",
    "--locked",
    "--manifest-path", $manifestRelativePath,
    "--target", $targetTriple
)
if ($Configuration -eq "Release") {
    $cargoArguments += "--release"
}

$cargoExitCode = 1
Push-Location -LiteralPath $repositoryRoot
try {
    & $cargoPath @cargoArguments
    $cargoExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($cargoExitCode -ne 0) {
    Stop-WithStableError "cargo build failed with exit code $cargoExitCode"
}

if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
    Stop-WithStableError "cargo completed without producing $sourceRelativePath"
}
if ((Get-Item -LiteralPath $sourcePath).Length -le 0) {
    Stop-WithStableError "cargo produced an empty $sourceRelativePath"
}

try {
    New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
} catch {
    Stop-WithStableError "unable to prepare the native runtime destination"
}

if (
    (Test-Path -LiteralPath $destinationPath) -and
    -not (Test-Path -LiteralPath $destinationPath -PathType Leaf)
) {
    Stop-WithStableError "the native runtime destination is not a regular file"
}
if (Test-Path -LiteralPath $destinationPath -PathType Leaf) {
    $destinationAttributes = (Get-Item -LiteralPath $destinationPath -Force).Attributes
    if (
        ($destinationAttributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
    ) {
        Stop-WithStableError "refusing to replace a reparse point at the native runtime destination"
    }
}

$destinationOriginallyExisted = Test-Path -LiteralPath $destinationPath -PathType Leaf
$randomName = [System.IO.Path]::GetRandomFileName()
$stagedPath = Join-Path $destinationDirectory ".fit_generator_core.$randomName.staged"
$backupPath = Join-Path $destinationDirectory ".fit_generator_core.$randomName.backup"
$replacementSucceeded = $false
$replacementFailed = $false
$rollbackFailed = $false
$cleanupFailed = $false

try {
    Copy-Item -LiteralPath $sourcePath -Destination $stagedPath
    if (
        -not (Test-Path -LiteralPath $stagedPath -PathType Leaf) -or
        (Get-Item -LiteralPath $stagedPath).Length -le 0
    ) {
        throw "staged DLL is missing or empty"
    }

    if ($destinationOriginallyExisted) {
        [System.IO.File]::Replace($stagedPath, $destinationPath, $backupPath)
    } else {
        [System.IO.File]::Move($stagedPath, $destinationPath)
    }

    if (
        -not (Test-Path -LiteralPath $destinationPath -PathType Leaf) -or
        (Get-Item -LiteralPath $destinationPath).Length -le 0
    ) {
        throw "installed DLL is missing or empty"
    }
    $replacementSucceeded = $true
} catch {
    $replacementFailed = $true
} finally {
    if (
        -not $replacementSucceeded -and
        $destinationOriginallyExisted -and
        (Test-Path -LiteralPath $backupPath -PathType Leaf)
    ) {
        try {
            if (Test-Path -LiteralPath $destinationPath -PathType Leaf) {
                [System.IO.File]::Replace($backupPath, $destinationPath, $null)
            } else {
                [System.IO.File]::Move($backupPath, $destinationPath)
            }
        } catch {
            $rollbackFailed = $true
        }
    }

    if (Test-Path -LiteralPath $stagedPath -PathType Leaf) {
        try {
            Remove-Item -LiteralPath $stagedPath -Force
        } catch {
            $cleanupFailed = $true
        }
    }
    if (
        $replacementSucceeded -and
        (Test-Path -LiteralPath $backupPath -PathType Leaf)
    ) {
        try {
            Remove-Item -LiteralPath $backupPath -Force
        } catch {
            $cleanupFailed = $true
        }
    }
}

if ($rollbackFailed) {
    Stop-WithStableError (
        "DLL replacement failed and the previous DLL could not be restored; " +
            "a recovery backup remains in the native runtime directory"
    )
}
if ($replacementFailed) {
    Stop-WithStableError "unable to atomically replace $destinationRelativePath"
}
if ($cleanupFailed) {
    Stop-WithStableError "the DLL was installed, but a script-created temporary file remains"
}

Write-Output "Windows Rust DLL ready: $destinationRelativePath"
