
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_PRELINK_MODULE := false

LOCAL_SRC_FILES:= \
    QrdInside.cpp \
    PerfMan.cpp \
    ZygoteMemPolicy.cpp

LOCAL_C_INCLUDEf += \
    $(JNI_H_INCLUDE) \
    $(LOCAL_PATH)/../../include/utils \

LOCAL_SHARED_LIBRARIES := \
    libnativehelper \
    libutils \
    libdl

LOCAL_MODULE_TAGS := optional
LOCAL_MODULE:= libqrdinside

include $(BUILD_SHARED_LIBRARY)
