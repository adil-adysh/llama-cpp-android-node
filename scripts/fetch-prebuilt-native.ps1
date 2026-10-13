param(
    [string]$Tag = "latest",
    [string]$Repo = "adil-adysh/llama-cpp-android-node"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
$targetDir = Join-Path $projectRoot "app\src\main\jniLibs\arm64-v8a"
$tempDir = Join-Path $projectRoot "build\temp-native"

Write-Host "==> Fetching prebuilt Android arm64-v8a native binaries from GitHub Release ($Tag)..." -ForegroundColor Cyan

if (Test-Path $tempDir) { Remove-Item -Recurse -Force $tempDir }
New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
New-Item -ItemType Directory -Path $targetDir -Force | Out-Null

$ghArgs = @("release", "download")
if ($Tag -ne "latest") {
    $ghArgs += $Tag
}
$ghArgs += @("-R", $Repo, "-p", "*.tar.gz", "-p", "checksums.txt", "-D", $tempDir, "--clobber")

Write-Host "Running: gh $($ghArgs -join ' ')"
& gh @ghArgs

$tarball = Get-ChildItem -Path $tempDir -Filter "*.tar.gz" | Select-Object -First 1
if (-not $tarball) {
    throw "No tarball found in release download."
}

$checksumsFile = Join-Path $tempDir "checksums.txt"
if (-not (Test-Path $checksumsFile)) {
    throw "checksums.txt not found in release assets. Cannot verify release integrity."
}

Write-Host "==> Verifying SHA-256 checksum for $($tarball.Name)..." -ForegroundColor Cyan
$checksumLines = Get-Content $checksumsFile
$expectedLine = $checksumLines | Where-Object { $_ -match [regex]::Escape($tarball.Name) } | Select-Object -First 1
if (-not $expectedLine) {
    throw "Checksum for $($tarball.Name) not found in checksums.txt"
}
$expectedSha = ($expectedLine -split "\s+")[0].Trim()
$actualSha = (Get-FileHash -Path $tarball.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ($expectedSha.ToLowerInvariant() -ne $actualSha) {
    throw "Checksum mismatch for $($tarball.Name)! Expected: $expectedSha, Actual: $actualSha"
}
Write-Host "==> Checksum verified successfully ($actualSha)" -ForegroundColor Green

Write-Host "Extracting $($tarball.FullName)..."
tar -xzf $tarball.FullName -C $tempDir

$rpcServerBin = Join-Path $tempDir "ggml-rpc-server"
$ompSo = Join-Path $tempDir "libomp.so"

if (-not (Test-Path $rpcServerBin)) { throw "ggml-rpc-server not found in tarball." }
if (-not (Test-Path $ompSo)) { throw "libomp.so not found in tarball." }

Copy-Item $rpcServerBin (Join-Path $targetDir "libggml-rpc-server.so") -Force
Copy-Item $ompSo (Join-Path $targetDir "libomp.so") -Force

Remove-Item -Recurse -Force $tempDir

Write-Host "==> Successfully provisioned native libraries in app/src/main/jniLibs/arm64-v8a/" -ForegroundColor Green
Get-ChildItem $targetDir | Format-Table Name, Length, LastWriteTime
