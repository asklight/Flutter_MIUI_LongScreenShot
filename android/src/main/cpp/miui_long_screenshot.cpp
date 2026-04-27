#include <jni.h>
#include <android/log.h>
#include <dart_api_dl.h>
#include <cstdint>

#define LOG_TAG "MIUI_LONG_SCREENSHOT"

static Dart_Port g_send_port = 0;
static JavaVM* g_java_vm = nullptr;
static jclass g_plugin_class = nullptr;
static jmethodID g_update_scroll_state_method = nullptr;
static jmethodID g_handle_scroll_result_method = nullptr;

static void set_dart_string(Dart_CObject& dart_object, const char* value) {
    dart_object.type = Dart_CObject_kString;
    dart_object.value.as_string = const_cast<char*>(value);
}

static void set_dart_int(Dart_CObject& dart_object, int32_t value) {
    dart_object.type = Dart_CObject_kInt32;
    dart_object.value.as_int32 = value;
}

static void set_dart_bool(Dart_CObject& dart_object, bool value) {
    dart_object.type = Dart_CObject_kBool;
    dart_object.value.as_bool = value;
}

static bool post_message(Dart_CObject* values[], intptr_t length) {
    if (g_send_port == 0) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Dart send port is not registered");
        return false;
    }

    Dart_CObject message;
    message.type = Dart_CObject_kArray;
    message.value.as_array.length = length;
    message.value.as_array.values = values;

    const bool posted = Dart_PostCObject_DL(g_send_port, &message);
    if (!posted) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Dart_PostCObject_DL failed");
    }
    return posted;
}

static JNIEnv* get_env() {
    if (g_java_vm == nullptr) return nullptr;

    JNIEnv* env = nullptr;
    jint result = g_java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (result == JNI_OK) return env;

    if (result == JNI_EDETACHED &&
        g_java_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        return env;
    }
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_java_vm = vm;

    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass local_plugin_class =
            env->FindClass("io/github/steven12138/miui_long_screenshot/MiuiLongScreenshotPlugin");
    if (local_plugin_class != nullptr) {
        g_plugin_class = reinterpret_cast<jclass>(env->NewGlobalRef(local_plugin_class));
        g_update_scroll_state_method = env->GetStaticMethodID(
                g_plugin_class,
                "updateCachedScrollStateFromDart",
                "(ZZZ)V"
        );
        g_handle_scroll_result_method = env->GetStaticMethodID(
                g_plugin_class,
                "handleDartScrollResult",
                "(II)V"
        );
    }

    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_steven12138_miui_1long_1screenshot_NativeLongScreenshotJni_initializeApi(
        JNIEnv*,
        jclass,
        jlong native_api_data
) {
    return Dart_InitializeApiDL(reinterpret_cast<void*>(native_api_data));
}

extern "C" __attribute__((visibility("default"))) intptr_t
initializeApi(void* native_api_data) {
    return Dart_InitializeApiDL(native_api_data);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_steven12138_miui_1long_1screenshot_NativeLongScreenshotJni_registerSendPort(
        JNIEnv*,
        jclass,
        jlong send_port
) {
    g_send_port = static_cast<Dart_Port>(send_port);
}

extern "C" __attribute__((visibility("default"))) void
registerSendPort(int64_t send_port) {
    g_send_port = static_cast<Dart_Port>(send_port);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_steven12138_miui_1long_1screenshot_NativeLongScreenshotJni_nativeCanScrollVertically(
        JNIEnv*,
        jclass,
        jint direction,
        jboolean start_screenshot,
        jint callback_id
) {
    Dart_CObject method;
    Dart_CObject arg_direction;
    Dart_CObject arg_start_screenshot;
    Dart_CObject arg_callback_id;

    set_dart_string(method, "canScrollVertically");
    set_dart_int(arg_direction, direction);
    set_dart_bool(arg_start_screenshot, start_screenshot == JNI_TRUE);
    set_dart_int(arg_callback_id, callback_id);

    Dart_CObject* values[] = {
            &method,
            &arg_direction,
            &arg_start_screenshot,
            &arg_callback_id
    };
    return post_message(values, 4) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_steven12138_miui_1long_1screenshot_NativeLongScreenshotJni_nativeScrollBy(
        JNIEnv*,
        jclass,
        jint x,
        jint y,
        jint callback_id
) {
    Dart_CObject method;
    Dart_CObject arg_x;
    Dart_CObject arg_y;
    Dart_CObject arg_callback_id;

    set_dart_string(method, "scrollBy");
    set_dart_int(arg_x, x);
    set_dart_int(arg_y, y);
    set_dart_int(arg_callback_id, callback_id);

    Dart_CObject* values[] = {
            &method,
            &arg_x,
            &arg_y,
            &arg_callback_id
    };
    return post_message(values, 4) ? JNI_TRUE : JNI_FALSE;
}

extern "C" __attribute__((visibility("default"))) void
updateScrollState(int32_t has_target, int32_t can_scroll_down, int32_t can_scroll_up) {
    JNIEnv* env = get_env();
    if (env == nullptr || g_plugin_class == nullptr ||
        g_update_scroll_state_method == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "updateScrollState skipped");
        return;
    }

    env->CallStaticVoidMethod(
            g_plugin_class,
            g_update_scroll_state_method,
            has_target != 0,
            can_scroll_down != 0,
            can_scroll_up != 0
    );
}

extern "C" __attribute__((visibility("default"))) void
resultCallback(int32_t callback_id, int32_t result) {
    JNIEnv* env = get_env();
    if (env == nullptr || g_plugin_class == nullptr ||
        g_handle_scroll_result_method == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "resultCallback skipped");
        return;
    }

    env->CallStaticVoidMethod(
            g_plugin_class,
            g_handle_scroll_result_method,
            callback_id,
            result
    );
}
