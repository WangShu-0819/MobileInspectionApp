package com.wearable.inspection.mobile.ncnn;

public final class NcnnSmokeNative {
    static {
        System.loadLibrary("ncnn_smoke");
    }

    private NcnnSmokeNative() {}

    public static native float[] run(String paramPath, String modelPath, float[] inputNchw);
}
