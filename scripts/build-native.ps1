param(
    [string]$LlamaRef = "b11429",
    [string]$NdkPath = $env:ANDROID_NDK_ROOT
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
$targetDir = Join-Path $projectRoot "app\src\main\jniLibs\arm64-v8a"
$upstreamDir = Join-Path $projectRoot "build\llama-upstream"
$buildDir = Join-Path $projectRoot "build\build-android"

# Detect NDK if not specified
if (-not $NdkPath) {
    $sdkDir = $env:ANDROID_HOME
    if (-not $sdkDir) { $sdkDir = $env:ANDROID_SDK_ROOT }
    if (-not $sdkDir) { $sdkDir = "$env:LOCALAPPDATA\Android\Sdk" }
    
    if (Test-Path "$sdkDir\ndk") {
        $latestNdk = Get-ChildItem -Path "$sdkDir\ndk" -Directory | Sort-Object Name -Descending | Select-Object -First 1
        if ($latestNdk) { $NdkPath = $latestNdk.FullName }
    }
}

if (-not $NdkPath -or -not (Test-Path $NdkPath)) {
    throw "Android NDK not found. Set ANDROID_NDK_ROOT or specify -NdkPath <path>"
}

Write-Host "==> Using Android NDK: $NdkPath" -ForegroundColor Cyan

$sysrootInclude = Get-ChildItem -Path $NdkPath -Directory -Recurse | Where-Object { $_.FullName -like "*sysroot\usr\include" } | Select-Object -First 1
if (-not $sysrootInclude) {
    throw "NDK sysroot include directory not found in $NdkPath"
}

$vulkanHpp = Join-Path $sysrootInclude.FullName "vulkan\vulkan.hpp"
$spirvHpp = Join-Path $sysrootInclude.FullName "spirv\unified1\spirv.hpp"

if (-not (Test-Path $vulkanHpp) -or -not (Test-Path $spirvHpp)) {
    Write-Host "==> Setting up Vulkan & SPIR-V C++ Headers (vulkan-sdk-1.4.304.0) for Android NDK..." -ForegroundColor Cyan
    $headerTmp = Join-Path $projectRoot "build\temp-headers"
    if (Test-Path $headerTmp) { Remove-Item -Recurse -Force $headerTmp }
    New-Item -ItemType Directory -Path $headerTmp -Force | Out-Null

    if (-not (Test-Path $vulkanHpp)) {
        git clone --depth 1 --branch vulkan-sdk-1.4.304.0 https://github.com/KhronosGroup/Vulkan-Headers.git "$headerTmp\vulkan-headers"
        $destVulkan = Join-Path $sysrootInclude.FullName "vulkan"
        New-Item -ItemType Directory -Path $destVulkan -Force | Out-Null
        Copy-Item -Path "$headerTmp\vulkan-headers\include\vulkan\*" -Destination $destVulkan -Recurse -Force
    }

    if (-not (Test-Path $spirvHpp)) {
        git clone --depth 1 --branch vulkan-sdk-1.4.304.0 https://github.com/KhronosGroup/SPIRV-Headers.git "$headerTmp\spirv-headers"
        $destSpirv = Join-Path $sysrootInclude.FullName "spirv"
        New-Item -ItemType Directory -Path $destSpirv -Force | Out-Null
        Copy-Item -Path "$headerTmp\spirv-headers\include\spirv\*" -Destination $destSpirv -Recurse -Force
    }

    Remove-Item -Recurse -Force $headerTmp
    Write-Host "==> Vulkan and SPIR-V headers successfully installed into $($sysrootInclude.FullName)" -ForegroundColor Green
}

# Clone or update upstream llama.cpp
if (-not (Test-Path $upstreamDir)) {
    Write-Host "==> Cloning ggml-org/llama.cpp..." -ForegroundColor Cyan
    git clone https://github.com/ggml-org/llama.cpp.git $upstreamDir
}

Push-Location $upstreamDir
try {
    Write-Host "==> Checking out ref '$LlamaRef'..."
    git fetch origin $LlamaRef
    git checkout $LlamaRef
} finally {
    Pop-Location
}

# Find NDK toolchain
$toolchainFile = Join-Path $NdkPath "build\cmake\android.toolchain.cmake"
if (-not (Test-Path $toolchainFile)) {
    throw "Toolchain file not found at $toolchainFile"
}

Write-Host "==> Configuring CMake for Android arm64-v8a (Vulkan + OpenMP)..." -ForegroundColor Cyan
cmake -B $buildDir -S $upstreamDir `
    -G Ninja `
    -DCMAKE_TOOLCHAIN_FILE="$toolchainFile" `
    -DANDROID_ABI="arm64-v8a" `
    -DANDROID_PLATFORM="android-28" `
    -DCMAKE_BUILD_TYPE="Release" `
    -DBUILD_SHARED_LIBS=OFF `
    -DGGML_BACKEND_DL=OFF `
    -DGGML_NATIVE=OFF `
    -DGGML_RPC=ON `
    -DGGML_VULKAN=ON `
    -DGGML_OPENMP=ON `
    -DLLAMA_BUILD_TESTS=OFF `
    -DLLAMA_BUILD_EXAMPLES=OFF `
    -DLLAMA_BUILD_SERVER=OFF `
    -DLLAMA_BUILD_COMMON=ON `
    -DLLAMA_BUILD_TOOLS=ON

Write-Host "==> Compiling ggml-rpc-server..." -ForegroundColor Cyan
ninja -C $buildDir ggml-rpc-server

$serverBin = Join-Path $buildDir "bin\ggml-rpc-server"
if (-not (Test-Path $serverBin)) {
    throw "Compiled binary not found at $serverBin"
}

# Find NDK libomp.so
$ompFile = Get-ChildItem -Path $NdkPath -Filter "libomp.so" -Recurse | Where-Object { $_.FullName -like "*aarch64*" } | Select-Object -First 1
if (-not $ompFile) {
    throw "libomp.so not found in NDK"
}

New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
Copy-Item $serverBin (Join-Path $targetDir "libggml-rpc-server.so") -Force
Copy-Item $ompFile.FullName (Join-Path $targetDir "libomp.so") -Force

Write-Host "==> Successfully compiled and installed native libraries into $targetDir" -ForegroundColor Green
Get-ChildItem $targetDir | Format-Table Name, Length, LastWriteTime
