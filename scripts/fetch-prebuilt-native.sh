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
  gh release download -R "$REPO" -p "*.tar.gz" -p "checksums.txt" -D "$TEMP_DIR" --clobber
else
  gh release download "$TAG" -R "$REPO" -p "*.tar.gz" -p "checksums.txt" -D "$TEMP_DIR" --clobber
fi

TARBALL=$(find "$TEMP_DIR" -name "*.tar.gz" | head -n 1)
test -f "$TARBALL" || { echo "Error: No tarball downloaded"; exit 1; }

TARBALL_BASENAME=$(basename "$TARBALL")
CHECKSUMS_FILE="$TEMP_DIR/checksums.txt"

if [ -f "$CHECKSUMS_FILE" ]; then
  echo "==> Verifying SHA-256 checksum for $TARBALL_BASENAME..."
  EXPECTED_SHA=$(grep "$TARBALL_BASENAME" "$CHECKSUMS_FILE" | awk '{print $1}' || true)
  if [ -z "$EXPECTED_SHA" ]; then
    echo "Error: Checksum for $TARBALL_BASENAME not found in checksums.txt"
    exit 1
  fi
  ACTUAL_SHA=$(sha256sum "$TARBALL" | awk '{print $1}')
  if [ "$EXPECTED_SHA" != "$ACTUAL_SHA" ]; then
    echo "Error: Checksum mismatch for $TARBALL_BASENAME!"
    echo "  Expected: $EXPECTED_SHA"
    echo "  Actual:   $ACTUAL_SHA"
    exit 1
  fi
  echo "==> Checksum verified successfully ($ACTUAL_SHA)"
else
  echo "Warning: checksums.txt not found in release assets."
fi

echo "Extracting $TARBALL..."
tar -xzf "$TARBALL" -C "$TEMP_DIR"

cp "$TEMP_DIR/ggml-rpc-server" "$TARGET_DIR/libggml-rpc-server.so"
cp "$TEMP_DIR/libomp.so" "$TARGET_DIR/libomp.so"

rm -rf "$TEMP_DIR"

echo "==> Successfully provisioned native libraries in app/src/main/jniLibs/arm64-v8a/"
ls -la "$TARGET_DIR"
