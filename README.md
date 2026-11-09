# TxnKeeper 

**TxnKeeper** is an offline-first Android app for recording your own transactions. Add them by hand, scan a receipt or payment screenshot, or just say them out loud. Everything is stored on your phone.

Privacy policy: https://txnkeeper.github.io/txnkeeper-privacy/

---

## ✨ Key Features

- **📸 Receipt scanning**: take a photo or pick an image (or share one into the app from your gallery or a payment app). Text is read on the device with **Google ML Kit** and **CameraX**, then amount, date, time, type and title are filled in for you.
  Besides payment screenshots and bank SMS it now reads shop and restaurant bills, GST invoices, supermarket, fuel, pharmacy, hotel, cafe, ride, train and utility receipts, including totals with no currency sign (`TOTAL 672.00`, `Total Due`, `Balance Due`), US style dates and bills where OCR reads labels and figures in separate columns. If the first reading finds no amount, the photo is cleaned up (shadow removal, contrast, resizing) and read once more. Nothing already found is ever overwritten by these extra passes (`ReceiptInterpreter.kt`, `ReceiptRows.kt`, `ReceiptImageEnhancer.kt`).
- **🎙️ Voice entry**: say one or several transactions ("paid 500 to Rahul, got 2000 salary"). Speech is turned into text on the device with **Whisper** (whisper.cpp, in the `whisperkit` module). The speech model is downloaded once the first time you use it.
- **💳 Multiple accounts**: bank, cash, card and so on, each with its own balance.
- **🔍 Search and filters**: search by title, amount, date, time of day or type, and filter by account, payment method, amount range and date range.
- **📤 Import and export**: CSV import and export, and PDF export. Export can cover everything, a date range, or the latest N transactions.
- **🎨 Light, dark or system theme**, built with **Jetpack Compose** and **ManuDesignSystem**.

Money is stored as whole paise (₹12.50 is saved as `1250`), so totals never drift. See `Money.kt`.

---

## 🛠️ Tech Stack

- **Language**: [Kotlin](https://kotlinlang.org/)
- **UI**: [Jetpack Compose](https://developer.android.com/jetpack/compose) and **ManuDesignSystem** (`com.github.manveer13667594:ManuDesignSystem`, from JitPack)
- **Database**: [Room](https://developer.android.com/training/data-storage/room) 2.8.5 with KSP. The database layout is exported to `app/schemas/` for every version; commit those files, they are the record used to check migrations.
- **Camera and OCR**: [CameraX](https://developer.android.com/training/camerax) and [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition) (the model is bundled, no download)
- **Voice**: [whisper.cpp](https://github.com/ggml-org/whisper.cpp) v1.9.5 through the `whisperkit` module (C++ via CMake)
- **Data flow**: Kotlin Coroutines and `Flow`

---

## 🚀 Getting Started

### Prerequisites
- **Android Studio** (a recent version that supports the Android Gradle Plugin used here)
- **Android SDK** with Compile SDK 37 (Min SDK 29, Target SDK 36)
- **NDK and CMake 3.22.1** (install both from SDK Manager → SDK Tools; needed for `whisperkit`)
- **JDK 17 or newer**
- Internet on the first build: CMake downloads whisper.cpp from GitHub

### Building and running
1. Clone the repository and open the project folder in Android Studio.
2. Let Gradle sync.
3. Connect an Android device (Android 10 / API 29 or newer) or start an emulator.
4. Press **Run** (`Shift + F10`).

### Tests
- Unit tests (parsers, CSV, money): `./gradlew testDebugUnitTest`
- Database migration test (needs a device or emulator): run `MigrationTest` from Android Studio.

### Release build
Release builds use R8 (code shrinking). The keep rules for the voice library are in `whisperkit/consumer-rules.keep` and `app/src/main/keepRules/rules.keep`.

1. Create a signing key once with **Build → Generate Signed App Bundle / APK** and keep the `.jks` file and its passwords **outside** the project (the `.gitignore` also blocks `*.jks`, `*.keystore` and `keystore.properties`). Back the key up: you need the same key for every update.
2. Build a signed **Android App Bundle** for Google Play, or a signed **APK** to install on your own phone.
3. Always test the release build on a real phone (voice, camera, import/export) before uploading, because R8 problems only show up there.

---

## 📄 License

This project is open-source and available for personal and educational use.
