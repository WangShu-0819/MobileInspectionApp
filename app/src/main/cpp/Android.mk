LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := nanodet_ncnn_runtime
LOCAL_SRC_FILES := nanodet_ncnn_jni.cpp
LOCAL_CPPFLAGS := -std=c++17 -fexceptions -frtti
LOCAL_C_INCLUDES := $(NCNN_ROOT)/arm64-v8a/include
LOCAL_LDLIBS := -L$(NCNN_ROOT)/arm64-v8a/lib -lncnn -llog
include $(BUILD_SHARED_LIBRARY)
