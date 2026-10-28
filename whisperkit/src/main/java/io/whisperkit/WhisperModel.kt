package io.whisperkit

/** Ready-made models, downloaded from the official whisper.cpp Hugging Face repo on first use. */
enum class WhisperModel(val fileName: String, val approxSizeMb: Int) {
    /** Recommended: good accuracy, runs on most phones. Multilingual (Hindi, Punjabi, English...). */
    SmallQ5("ggml-small-q5_1.bin", 181),

    /** English-only variant of small, a bit more accurate for English. */
    SmallEnQ5("ggml-small.en-q5_1.bin", 181),

    /** Faster, less accurate. Multilingual. */
    BaseQ5("ggml-base-q5_1.bin", 57),

    /** English-only base: same speed as BaseQ5, usually more accurate for English. */
    BaseEnQ5("ggml-base.en-q5_1.bin", 57),

    /** Fastest, roughly good enough for short clear English. Multilingual. */
    TinyQ5("ggml-tiny-q5_1.bin", 31);

    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}
