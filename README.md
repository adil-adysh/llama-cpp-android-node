# llama-cpp-android-node 🦙📱

**llama.cpp Android RPC Distributed Inference Node (LlamaNode)**

`llama-cpp-android-node` is a high-performance Android application that runs `llama.cpp`'s `ggml-rpc-server` as a persistent background service. It transforms any modern Android device into a high-speed distributed inference node for your PC or server cluster over Wi-Fi or USB.

---

## Key Features

- **Dual Compute Backends:**
  - 🚀 **Vulkan GPU:** Direct tensor acceleration via mobile GPUs (Qualcomm Adreno 830, ARM Mali-G715 / Immortalis).
  - ⚡ **ARM CPU:** Multi-threaded Neon and DotProd execution powered by OpenMP.
  - 🔄 **Auto Backend:** Automatically selects GPU acceleration with seamless fallback to CPU.
  - 🔀 **Multi-Device Cluster:** Run multiple phones concurrently by passing comma-separated RPC endpoints.
- **Android SELinux Compliance:** Packaged into `nativeLibraryDir` via `android:extractNativeLibs="true"` to fully adhere to Android 10+ W^X execution policies.
- **Robust Background Service:** Runs as a foreground service with persistent notification, CPU `WakeLock`, and high-performance `WifiLock` to prevent Doze mode throttling.
- **Modern Jetpack Compose UI:** Material 3 interface with status badge, local IP auto-discovery, live console log viewer, and 1-tap copy commands for Windows host connection.

---

## Architecture & NDK Cross-Compilation

The bundled `libggml-rpc-server.so` is cross-compiled against `llama.cpp` (b11429) using:
- **Android NDK:** r28 (`arm64-v8a`, API 28)
- **Vulkan SDK:** 1.4 with LunarG SPIR-V shader generator
- **Runtime Dependencies:** Dynamically binds only to system Bionic libraries (`libc.so`, `libm.so`, `libdl.so`, `libvulkan.so`) and bundled NDK `libomp.so`, eliminating vendor symbol clashes.

---

## Quick Start

### 1. Build and Install

```powershell
cd LlamaNode

# Step 1: Provision verified prebuilt native binaries (or compile via .\scripts\build-native.ps1)
.\scripts\fetch-prebuilt-native.ps1

# Step 2: Assemble and install the Android application
.\gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Start the Server

1. Open **LlamaNode** on your device.
2. Select your desired compute backend (**Auto**, **Vulkan GPU**, **ARM CPU**, or **Both**).
3. If connecting over Wi-Fi, select **LAN / Wi-Fi (0.0.0.0)** under *Network Interface Exposure* (default is secure loopback `127.0.0.1`).
4. Set the port (default: `50052`) and enable local caching (`-c`).
5. Tap **START RPC SERVER**.

### 3. Connect from Windows PC Host

#### Option A: Over Local Wi-Fi (Requires LAN Mode 0.0.0.0 in app)
```powershell
llama-cli.exe `
  -m "path/to/model.gguf" `
  --rpc <DEVICE_WIFI_IP>:50052 `
  -ngl 99 `
  -p "What is the theory of relativity?"
```

#### Option B: Over USB Cable (Lowest Latency via ADB)
```powershell
adb forward tcp:50052 tcp:50052
llama-cli.exe `
  -m "path/to/model.gguf" `
  --rpc 127.0.0.1:50052 `
  -ngl 99 `
  -p "What is the theory of relativity?"
```

#### Option C: Multi-Phone Cluster (e.g., Pixel 8 Pro + Galaxy S25)
```powershell
llama-cli.exe `
  -m "path/to/model.gguf" `
  --rpc 192.168.0.101:50052,192.168.0.109:50052 `
  -ngl 99 `
  -p "Explain quantum computing in simple terms."
```

---

## Verified Device Performance

Tested with `SmolLM2-135M-Instruct-Q4_K_M.gguf` offloading 100% of layers (`-ngl 99`):

| Device | Chipset | Backend | Prompt Speed | Generation Speed |
| :--- | :--- | :--- | :--- | :--- |
| **Pixel 8 Pro** | Tensor G3 (Mali-G715) | Vulkan GPU | 24.6 t/s | 8.1 t/s |
| **Pixel 8 Pro** | Tensor G3 (ARMv9) | CPU (OpenMP) | 58.2 t/s | 10.6 t/s |
| **Pixel 8 Pro (USB)** | Tensor G3 | CPU (ADB Forward) | **165.7 t/s** | **31.6 t/s** |
| **Galaxy S25** | Snapdragon 8 Elite | CPU / Wi-Fi | **131.5 t/s** | **16.6 t/s** |
| **Cluster (Dual Phone)** | Pixel 8 Pro + S25 | Distributed Wi-Fi | 38.9 t/s | 6.4 t/s |

---

## License

MIT License. Uses `llama.cpp` licensed under MIT.
