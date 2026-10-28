package io.whisperkit.internal

internal interface NativeCallback {
    /** 0..100 for the chunk currently being transcribed. */
    fun onProgress(percent: Int)

    /** Times are in 10 ms units, relative to the start of the chunk. */
    fun onSegment(t0: Long, t1: Long, textUtf8: ByteArray)
}

internal object NativeWhisper {
    init {
        System.loadLibrary("whisperkit")
    }

    @JvmStatic external fun nInit(modelPath: String): Long
    @JvmStatic external fun nFree(handle: Long)
    @JvmStatic external fun nAbort(handle: Long)
    @JvmStatic external fun nTranscribe(
        handle: Long,
        samples: FloatArray,
        threads: Int,
        language: String,
        translate: Boolean,
        callback: NativeCallback,
    ): Int
    @JvmStatic external fun nDetectedLanguage(handle: Long): String?
}
