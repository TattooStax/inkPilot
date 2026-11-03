#include "inference_engine.h"
#include <jni.h>

namespace {
std::string read_bytes(JNIEnv * env, jbyteArray bytes, jsize limit) {
    if (!bytes || env->GetArrayLength(bytes) > limit) throw std::invalid_argument("Invalid native input length");
    std::string result(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(result.size()), reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Unable to read native input");
    return result;
}

void fail(JNIEnv * env, const char * type, const char * message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass(type), message);
}
} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_inkpilot_inference_NativeInference_generate(
    JNIEnv * env, jobject, jbyteArray path, jbyteArray instructions, jbyteArray user_data,
    jint context_tokens, jint output_tokens, jint reply_characters, jlong deadline_millis,
    jint threads, jobject cancellation) {
    try {
        if (!cancellation) throw std::invalid_argument("Missing cancellation hook");
        const auto callback_type = env->GetObjectClass(cancellation);
        const auto callback = env->GetMethodID(callback_type, "isCancelled", "()Z");
        if (!callback || env->ExceptionCheck()) return nullptr;
        auto is_cancelled = [&]() {
            const bool result = env->CallBooleanMethod(cancellation, callback);
            return result || env->ExceptionCheck();
        };
        const inkpilot::Limits limits{context_tokens, output_tokens, reply_characters, deadline_millis, threads};
        const auto output = inkpilot::generate(read_bytes(env, path, 4096), read_bytes(env, instructions, 32000),
                                               read_bytes(env, user_data, 64000), limits, is_cancelled);
        auto result = env->NewByteArray(static_cast<jsize>(output.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
                                           reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const inkpilot::Cancelled &) {
        fail(env, "java/util/concurrent/CancellationException", "Local inference cancelled.");
    } catch (const inkpilot::TimedOut &) {
        fail(env, "java/lang/IllegalStateException", "Local inference exceeded its time budget.");
    } catch (const std::exception &) {
        // Upstream exceptions may contain template or prompt fragments; keep them out of UI/logs.
        fail(env, "java/lang/IllegalStateException", "Local model could not produce a complete reply. Check the model and available memory.");
    } catch (...) {
        fail(env, "java/lang/IllegalStateException", "Local inference failed.");
    }
    return nullptr;
}
