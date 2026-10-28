// Thin JNI bridge: load a model, run whisper_full, stream segments/progress to Kotlin.
#include <jni.h>

#include <atomic>
#include <cstring>
#include <string>

#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "WhisperKit", __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) (fprintf(stderr, __VA_ARGS__), fprintf(stderr, "\n"))
#endif

namespace {

struct Handle {
    whisper_context *ctx = nullptr;
    std::atomic<bool> abort{false};
};

struct CallbackData {
    JNIEnv *env;
    jobject callback;
    jmethodID onProgress;
    jmethodID onSegment;
    Handle *handle;
};

void on_new_segment(whisper_context *ctx, whisper_state *, int n_new, void *user_data) {
    auto *d = static_cast<CallbackData *>(user_data);
    const int total = whisper_full_n_segments(ctx);
    for (int i = total - n_new; i < total; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        const jsize len = static_cast<jsize>(std::strlen(text));
        // Raw bytes, not NewStringUTF: a segment can end mid-UTF-8-character, which would crash the JVM.
        jbyteArray bytes = d->env->NewByteArray(len);
        d->env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(text));
        d->env->CallVoidMethod(d->callback, d->onSegment,
                               static_cast<jlong>(whisper_full_get_segment_t0(ctx, i)),
                               static_cast<jlong>(whisper_full_get_segment_t1(ctx, i)), bytes);
        d->env->DeleteLocalRef(bytes);
        if (d->env->ExceptionCheck()) {
            d->env->ExceptionClear();
            d->handle->abort = true;
        }
    }
}

void on_progress(whisper_context *, whisper_state *, int progress, void *user_data) {
    auto *d = static_cast<CallbackData *>(user_data);
    d->env->CallVoidMethod(d->callback, d->onProgress, static_cast<jint>(progress));
    if (d->env->ExceptionCheck()) {
        d->env->ExceptionClear();
        d->handle->abort = true;
    }
}

bool should_abort(void *user_data) {
    return static_cast<Handle *>(user_data)->abort.load();
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_whisperkit_internal_NativeWhisper_nInit(JNIEnv *env, jclass, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        LOGE("Failed to load model");
        return 0;
    }
    auto *h = new Handle();
    h->ctx = ctx;
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_io_whisperkit_internal_NativeWhisper_nFree(JNIEnv *, jclass, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (h == nullptr) return;
    whisper_free(h->ctx);
    delete h;
}

// Safe to call from another thread while nTranscribe is running.
JNIEXPORT void JNICALL
Java_io_whisperkit_internal_NativeWhisper_nAbort(JNIEnv *, jclass, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (h != nullptr) h->abort = true;
}

// Returns 0 = ok, 1 = cancelled, negative = whisper error.
JNIEXPORT jint JNICALL
Java_io_whisperkit_internal_NativeWhisper_nTranscribe(JNIEnv *env, jclass, jlong ptr,
                                                      jfloatArray samples, jint threads,
                                                      jstring language, jboolean translate,
                                                      jobject callback) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    h->abort = false;

    const char *lang = env->GetStringUTFChars(language, nullptr);
    const std::string langStr(lang);
    env->ReleaseStringUTFChars(language, lang);

    jclass cls = env->GetObjectClass(callback);
    CallbackData data{env, callback, env->GetMethodID(cls, "onProgress", "(I)V"),
                      env->GetMethodID(cls, "onSegment", "(JJ[B)V"), h};
    if (data.onProgress == nullptr || data.onSegment == nullptr) return -2;

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.translate = translate == JNI_TRUE;
    p.language = langStr.c_str();   // "auto" = detect
    p.detect_language = false;
    p.n_threads = threads;
    p.no_context = true;            // avoids repetition loops carrying over between chunks
    p.single_segment = false;
    p.new_segment_callback = on_new_segment;
    p.new_segment_callback_user_data = &data;
    p.progress_callback = on_progress;
    p.progress_callback_user_data = &data;
    p.abort_callback = should_abort;
    p.abort_callback_user_data = h;

    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    const jsize n = env->GetArrayLength(samples);
    const int rc = whisper_full(h->ctx, p, pcm, n);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);

    if (h->abort.load()) return 1;
    return rc;
}

// Language detected by the last nTranscribe call ("en", "hi", "pa", ...), or null.
JNIEXPORT jstring JNICALL
Java_io_whisperkit_internal_NativeWhisper_nDetectedLanguage(JNIEnv *env, jclass, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    const int id = whisper_full_lang_id(h->ctx);
    if (id < 0) return nullptr;
    const char *s = whisper_lang_str(id);
    return s ? env->NewStringUTF(s) : nullptr;
}

} // extern "C"
