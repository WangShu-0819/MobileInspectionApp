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

void throw_illegal_state(JNIEnv* env, const std::string& message) {
    jclass exception_class = env->FindClass("java/lang/IllegalStateException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
    }
}

std::string to_string(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_wearable_inspection_mobile_ncnn_NcnnSmokeNative_run(
        JNIEnv* env,
        jclass,
        jstring param_path,
        jstring model_path,
        jfloatArray input_values) {
    if (input_values == nullptr || env->GetArrayLength(input_values) != kChannels * kInputWidth * kInputHeight) {
        throw_illegal_state(env, "Expected one float32 NCHW tensor with shape [1,3,416,416]");
        return nullptr;
    }

    const std::string param_file = to_string(env, param_path);
    const std::string model_file = to_string(env, model_path);
    ncnn::Net net;
    net.opt.use_vulkan_compute = false;
    net.opt.use_fp16_storage = false;
    net.opt.use_fp16_packed = false;
    net.opt.use_fp16_arithmetic = false;
    net.opt.num_threads = 1;

    int result = net.load_param(param_file.c_str());
    if (result != 0) {
        throw_illegal_state(env, "NCNN load_param failed: " + std::to_string(result));
        return nullptr;
    }
    result = net.load_model(model_file.c_str());
    if (result != 0) {
        throw_illegal_state(env, "NCNN load_model failed: " + std::to_string(result));
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

    ncnn::Extractor extractor = net.create_extractor();
    result = extractor.input("in0", input);
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
    if (output.dims != 2 || output.w != kOutputWidth || output.h != kOutputHeight || output.elemsize != sizeof(float)) {
        throw_illegal_state(env, "Unexpected NCNN output shape or element size");
        return nullptr;
    }

    const jsize output_count = kOutputWidth * kOutputHeight;
    jfloatArray result_array = env->NewFloatArray(output_count);
    if (result_array == nullptr) return nullptr;
    jfloat* destination = env->GetFloatArrayElements(result_array, nullptr);
    if (destination == nullptr) return nullptr;
    for (int row = 0; row < kOutputHeight; ++row) {
        std::memcpy(destination + row * kOutputWidth, output.row(row), kOutputWidth * sizeof(float));
    }
    env->ReleaseFloatArrayElements(result_array, destination, 0);
    return result_array;
}
