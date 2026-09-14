package com.wearable.inspection.mobile.detection;

/** Minimal JNI boundary for the pinned arm64 NCNN FP32 runtime. */
final class NanoDetNcnnNative {
    static {
        System.loadLibrary("ncnn");
        System.loadLibrary("nanodet_ncnn_runtime");
    }

    private NanoDetNcnnNative() {}

    static native long create(String paramPath, String modelPath);
    static native float[] infer(long handle, float[] inputNchw);
    static native void destroy(long handle);
}
