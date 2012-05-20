LOCAL_PATH:= $(call my-dir)

#
# libgestureservice
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
    GestureDeviceService.cpp

LOCAL_SHARED_LIBRARIES:= \
    libui \
    libutils \
    libbinder \
    libcutils \
    libmedia \
    libgesture_client \
    libgui \
    libhardware

LOCAL_MODULE:= libgestureservice
LOCAL_MODULE_TAGS := optional
include $(BUILD_SHARED_LIBRARY)
