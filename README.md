# TxnKeeper 🪙

**TxnKeeper** is a modern, feature-rich Android application designed to help users effortlessly track financial transactions, scan and parse receipts using on-device OCR, manage multiple accounts, and keep tabs on their daily expenses.

---

## ✨ Key Features

- **📸 OCR Receipt Scanning**: Powered by **Google ML Kit** and **CameraX**, capture or share receipt images to automatically extract transaction amounts, merchants, dates, and details.
- **💳 Multi-Account Management**: Create and manage multiple financial accounts with real-time balance calculations.
- **🔍 Advanced Search & Filtering**: Quickly search transactions and filter by account, date range, or category.
- **📤 Share Intent Support**: Seamlessly share receipt images from your Gallery or other payment apps directly into TxnKeeper for instant parsing.
- **💾 Local-First & Private**: All financial data is stored securely on your device using **Android Room**.
- **🎨 Modern UI & Design System**: Built with **Jetpack Compose**, **Material 3**, and **ManuDesignSystem** for a fluid, responsive, and adaptive user experience across phones and foldables.

---

## 🛠️ Tech Stack & Architecture

- **Language**: [Kotlin](https://kotlinlang.org/)
- **UI Toolkit & Styling**: [Jetpack Compose](https://developer.android.com/jetpack/compose), Material 3, and **ManuDesignSystem** (`com.github.manveer13667594:ManuDesignSystem`)
- **Local Persistence**: [Android Room (v2.8.5)](https://developer.android.com/training/data-storage/room) with KSP
- **Camera & OCR**: [CameraX](https://developer.android.com/training/camerax) & [Google ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition)
- **Architecture**: Reactive unidirectional data flow using Kotlin Coroutines & `Flow`.

---

## 🚀 Getting Started

### Prerequisites
- **Android Studio** (Koala, Jellyfish, Ladybug, or newer recommended)
- **Android SDK** (Compile SDK 37, Min SDK 29+)
- JDK 11 or higher

### Building and Running
1. Clone the repository or open the project folder (`txnkeeper`) in Android Studio.
2. Let Gradle sync dependencies automatically.
3. Connect an Android device (API 29+) or start an emulator.
4. Click **Run (`Shift + F10`)** in Android Studio to build and launch the app.

---

## 📄 License

This project is open-source and available for personal and educational use.
