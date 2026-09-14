#include <jni.h>
#include <cstring>
#include <string>

#include <ncnn/net.h>

namespace {
constexpr int kInputWidth = 416;
constexpr int kInputHeight = 416;
constexpr int kChannels = 3;
constexpr int kOutputWidth = 34;
constexpr int kOutputHeight = 3598;

struct Runtime {
    ncnn::Net net;
};

void throw_illegal_state(JNIEnv* env, const std::string& message) {
    jclass exception_class = env->FindClass("java/lang/IllegalStateException");
    if (exception_class != nullptr) env->ThrowNew(exception_class, message.c_str());
}

std::string to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_wearable_inspection_mobile_detection_NanoDetNcnnNative_create(
        JNIEnv* env, jclass, jstring param_path, jstring model_path) {
    const std::string param_file = to_string(env, param_path);
    const std::string model_file = to_string(env, model_path);
    if (param_file.empty() || model_file.empty()) {
        throw_illegal_state(env, "NCNN model paths are empty");
        return 0;
    }

    auto* runtime = new Runtime();
    runtime->net.opt.use_vulkan_compute = false;
    runtime->net.opt.use_fp16_storage = false;
    runtime->net.opt.use_fp16_packed = false;
    runtime->net.opt.use_fp16_arithmetic = false;
    runtime->net.opt.num_threads = 1;

    int result = runtime->net.load_param(param_file.c_str());
    if (result != 0) {
        delete runtime;
        throw_illegal_state(env, "NCNN load_param failed: " + std::to_string(result));
        return 0;
    }
    result = runtime->net.load_model(model_file.c_str());
    if (result != 0) {
        delete runtime;
        throw_illegal_state(env, "NCNN load_model failed: " + std::to_string(result));
        return 0;
    }
    return reinterpret_cast<jlong>(runtime);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_wearable_inspection_mobile_detection_NanoDetNcnnNative_infer(
        JNIEnv* env, jclass, jlong handle, jfloatArray input_values) {
    auto* runtime = reinterpret_cast<Runtime*>(handle);
    if (runtime == nullptr || input_values == nullptr ||
        env->GetArrayLength(input_values) != kChannels * kInputWidth * kInputHeight) {
        throw_illegal_state(env, "Expected a live runtime and float32 NCHW tensor [1,3,416,416]");
        return nullptr;
    }

    ncnn::Mat input(kInputWidth, kInputHeight, kChannels);
    jfloat* source = env->GetFloatArrayElements(input_values, nullptr);
    if (source == nullptr) return nullptr;
    const size_t plane_bytes = kInputWidth * kInputHeight * sizeof(float);
    for (int channel = 0; channel < kChannels; ++channel) {
        std::memcpy(input.channel(channel), source + channel * kInputWidth * kInputHeight, plane_bytes);
    }
    env->ReleaseFloatArrayElements(input_values, source, JNI_ABORT);

    ncnn::Extractor extractor = runtime->net.create_extractor();
    int result = extractor.input("in0", input);
    if (result != 0) {
        throw_illegal_state(env, "NCNN input in0 failed: " + std::to_string(result));
        return nullptr;
    }
    ncnn::Mat output;
    result = extractor.extract("out0", output);
    if (result != 0) {
        throw_illegal_state(env, "NCNN extract out0 failed: " + std::to_string(result));
        return nullptr;
    }
    if (output.dims != 2 || output.w != kOutputWidth || output.h != kOutputHeight ||
        output.elemsize != sizeof(float)) {
        throw_illegal_state(env, "Unexpected NCNN output shape or element size");
        return nullptr;
    }

    const jsize count = kOutputWidth * kOutputHeight;
    jfloatArray result_array = env->NewFloatArray(count);
    if (result_array == nullptr) return nullptr;
    jfloat* destination = env->GetFloatArrayElements(result_array, nullptr);
    if (destination == nullptr) return nullptr;
    for (int row = 0; row < kOutputHeight; ++row) {
        std::memcpy(destination + row * kOutputWidth, output.row(row), kOutputWidth * sizeof(float));
    }
    env->ReleaseFloatArrayElements(result_array, destination, 0);
    return result_array;
}

extern "C" JNIEXPORT void JNICALL
Java_com_wearable_inspection_mobile_detection_NanoDetNcnnNative_destroy(
        JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Runtime*>(handle);
}
