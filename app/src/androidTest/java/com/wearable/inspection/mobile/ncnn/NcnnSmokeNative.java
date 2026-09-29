package com.wearable.inspection.mobile.ncnn;

public final class NcnnSmokeNative {
    static {
        System.loadLibrary("ncnn_smoke");
    }

    private NcnnSmokeNative() {}

    /** Fixed-width entry point: outputWidth=36 (exp09/exp22). */
    public static native float[] run(String paramPath, String modelPath, float[] inputNchw);

    /**
     * Parameterized entry point for models with non-standard output width.
     * e.g. exp23 uses outputWidth=34 (2-class + 32 regression bins).
     */
    public static native float[] runWithWidth(String paramPath, String modelPath,
                                               float[] inputNchw, int outputWidth);

    /** Writes actual NCNN Mat metadata as [dims, w, h, elemsize] into matInfo. */
    public static native float[] runWithWidthAndMatInfo(String paramPath, String modelPath,
                                                         float[] inputNchw, int outputWidth,
                                                         int[] matInfo);
}
