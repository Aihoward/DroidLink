LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := droidlink_native
LOCAL_SRC_FILES := droidlink_native.c
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
