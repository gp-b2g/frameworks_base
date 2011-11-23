LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_MODULE := spp_test
LOCAL_MODULE_TAGS := eng
include $(BUILD_JAVA_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE       := spp_test
LOCAL_MODULE_TAGS  := eng
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_SRC_FILES    := $(LOCAL_MODULE)
include $(BUILD_PREBUILT)
include $(CLEAR_VARS)
