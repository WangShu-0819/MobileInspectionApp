package com.wearable.inspection.mobile.detection;

/** Minimal JNI boundary for the pinned arm64 NCNN FP32 runtime. */
final class NanoDetNcnnNative {
    static {
        System.loadLibrary("ncnn");
        System.loadLibrary("nanodet_ncnn_runtime");
    }

    private NanoDetNcnnNative() {}

    /**
     * @param paramPath  NCNN param file path
     * @param modelPath  NCNN bin file path
     * @param outputWidth expected output column width (e.g. 36 for 4-class, 34 for 2-class)
     */
    static native long create(String paramPath, String modelPath, int outputWidth);
    static native float[] infer(long handle, float[] inputNchw);
    static native void destroy(long handle);
}
