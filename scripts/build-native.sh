#!/usr/bin/env bash
set -euo pipefail

LLAMA_REF="${1:-b11429}"
NDK_PATH="${2:-${ANDROID_NDK_ROOT:-}}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
TARGET_DIR="$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a"
UPSTREAM_DIR="$PROJECT_ROOT/build/llama-upstream"
BUILD_DIR="$PROJECT_ROOT/build/build-android"

if [ -z "$NDK_PATH" ]; then
  SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  if [ -d "$SDK_DIR/ndk" ]; then
    NDK_PATH=$(find "$SDK_DIR/ndk" -maxdepth 1 -mindepth 1 | sort -V | tail -n 1)
  fi
fi

if [ -z "$NDK_PATH" ] || [ ! -d "$NDK_PATH" ]; then
  echo "Error: Android NDK not found. Set ANDROID_NDK_ROOT or specify NDK path."
  exit 1
fi

echo "==> Using Android NDK: $NDK_PATH"

if [ ! -d "$UPSTREAM_DIR" ]; then
  echo "==> Cloning ggml-org/llama.cpp..."
  git clone https://github.com/ggml-org/llama.cpp.git "$UPSTREAM_DIR"
fi

cd "$UPSTREAM_DIR"
git fetch origin "$LLAMA_REF"
git checkout "$LLAMA_REF"
cd "$PROJECT_ROOT"

TOOLCHAIN="$NDK_PATH/build/cmake/android.toolchain.cmake"
test -f "$TOOLCHAIN" || { echo "Toolchain file not found: $TOOLCHAIN"; exit 1; }

echo "==> Configuring CMake for Android arm64-v8a (Vulkan + OpenMP)..."
cmake -B "$BUILD_DIR" -S "$UPSTREAM_DIR" \
  -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI="arm64-v8a" \
  -DANDROID_PLATFORM="android-28" \
  -DCMAKE_BUILD_TYPE="Release" \
  -DBUILD_SHARED_LIBS=OFF \
  -DGGML_BACKEND_DL=OFF \
  -DGGML_NATIVE=OFF \
  -DGGML_RPC=ON \
  -DGGML_VULKAN=ON \
  -DGGML_OPENMP=ON \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_COMMON=ON \
  -DLLAMA_BUILD_TOOLS=ON

echo "==> Compiling ggml-rpc-server..."
ninja -C "$BUILD_DIR" ggml-rpc-server

OMP_PATH=$(find "$NDK_PATH" -name libomp.so | grep -E 'aarch64' | head -n 1)
test -f "$OMP_PATH" || { echo "libomp.so not found in NDK"; exit 1; }

mkdir -p "$TARGET_DIR"
cp "$BUILD_DIR/bin/ggml-rpc-server" "$TARGET_DIR/libggml-rpc-server.so"
cp "$OMP_PATH" "$TARGET_DIR/libomp.so"

echo "==> Successfully compiled and installed native libraries into $TARGET_DIR"
ls -la "$TARGET_DIR"
