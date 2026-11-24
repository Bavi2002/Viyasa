# Vyasa (व्यॉस) — On-Device AI Writing Assistant

[![Android](https://img.shields.io/badge/Platform-Android_14%2F15-green?logo=android)](https://developer.android.com/)
[![Runtime](https://img.shields.io/badge/Runtime-Google_LiteRT--LM-blue?logo=google)](https://ai.google.dev/edge/litert)
[![Privacy](https://img.shields.io/badge/Privacy-100%25_Offline-black)](https://github.com/Bavi2002/Viyasa)
[![Design](https://img.shields.io/badge/Design-Material_3_Monochrome-lightgrey)](https://m3.material.io/)

**Vyasa** is a modern, high-performance, strictly offline AI writing and language assistant for Android. Powered by Google's **LiteRT-LM** (formerly TensorFlow Lite GenAI / MediaPipe GenAI), Vyasa runs state-of-the-art quantized language models directly on your smartphone with zero cloud dependencies, complete privacy, and zero subscription fees.

---

## Key Features

### 1. Instant System Text Replacement (`Rewrite`)
- Highlight any text in **WhatsApp**, **Gmail**, **Chrome**, **Slack**, or any Android text field.
- Tap **Rewrite** in the system text context menu to fix grammar, verb tenses, double negatives, and typos **in-place in under 2 seconds** with zero app-switching.

### 2. Vyasa Floating Tool Dock (`Vyasa`)
- Tap **Vyasa** in the text selection menu to open an overlay tool dock without leaving your current app:
  - **Rewrite**: Adjust phrasing tone (*Natural*, *Casual*, *Professional*, or *Custom instructions*).
  - **Translate**: Translate between languages (*English, Spanish, French, German, Hindi, Tamil, Arabic*) completely offline.
  - **Summarize**: Condense text into concise bullet points or single executive takeaway.
  - **Continue Writing**: Natural co-writer continuation in the same voice and style.

### 3. Integrated AI Playground & Hardware Monitor
- Test, experiment, and benchmark prompt transformations with live latency and memory usage reporting.
- Real-time device hardware inspection (CPU cores, RAM capacity, OpenCL GPU support).

---

## Supported AI Models

| Model | Size | Hardware Target | Specialization |
|---|---|---|---|
| **Gemma 3 (270M-IT)** | 272 MB | Any phone ($\ge$ 2 GB RAM) | Google's ultra-compact edge model. Optimized for multi-core CPU (XNNPack) for ~1.5s lightning inference. |
| **Qwen3 (0.6B Q4)** | 343 MB | Modern phones ($\ge$ 4 GB RAM) | Recommended for high multilingual accuracy across 30+ languages. |

> **Custom Sideloading**: Users can import any custom `.litertlm` model file directly from device storage via the built-in file picker.

---

## Architecture & Tech Stack

- **UI & Architecture**: Modern Jetpack Compose, Kotlin Coroutines, StateFlow, MVVM architecture.
- **Theme**: Material 3 Expressive with strict monochrome black & white contrast.
- **Inference Runtime**: Google LiteRT-LM Android API (`com.google.ai.edge.litertlm:litertlm-android`).
- **Storage & State**: AndroidX DataStore Preferences.
- **Integration**: Android 14/15 `ACTION_PROCESS_TEXT` system contract.

---

## Getting Started

### Prerequisites
- Android Studio Ladybug (2024.2+) or newer
- JDK 17
- Android SDK 35 (Android 15)
- Physical Android device (arm64-v8a) with Developer Options & USB Debugging enabled

### Building from Source

```bash
# Clone the repository
git clone https://github.com/Bavi2002/Viyasa.git
cd Viyasa

# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## License

This project is open-source under the Apache License 2.0.
