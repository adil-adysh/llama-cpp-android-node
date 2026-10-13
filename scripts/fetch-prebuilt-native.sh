#!/usr/bin/env bash
set -euo pipefail

TAG="${1:-latest}"
REPO="${2:-adil-adysh/llama-cpp-android-node}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
TARGET_DIR="$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a"
TEMP_DIR="$PROJECT_ROOT/build/temp-native"

echo "==> Fetching prebuilt Android arm64-v8a native binaries from GitHub Release ($TAG)..."

rm -rf "$TEMP_DIR"
mkdir -p "$TEMP_DIR" "$TARGET_DIR"

if [ "$TAG" = "latest" ]; then
  gh release download -R "$REPO" -p "*.tar.gz" -D "$TEMP_DIR" --clobber
else
  gh release download "$TAG" -R "$REPO" -p "*.tar.gz" -D "$TEMP_DIR" --clobber
fi

TARBALL=$(find "$TEMP_DIR" -name "*.tar.gz" | head -n 1)
test -f "$TARBALL" || { echo "Error: No tarball downloaded"; exit 1; }

echo "Extracting $TARBALL..."
tar -xzf "$TARBALL" -C "$TEMP_DIR"

cp "$TEMP_DIR/ggml-rpc-server" "$TARGET_DIR/libggml-rpc-server.so"
cp "$TEMP_DIR/libomp.so" "$TARGET_DIR/libomp.so"

rm -rf "$TEMP_DIR"

echo "==> Successfully provisioned native libraries in app/src/main/jniLibs/arm64-v8a/"
ls -la "$TARGET_DIR"
