LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	GestureDevice.cpp \
	IGestureDevice.cpp \
	IGestureDeviceClient.cpp \
	IGestureDeviceService.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libbinder \
	libhardware \
	libui \
	libgui

LOCAL_MODULE:= libgesture_client
LOCAL_MODULE_TAGS := optional
include $(BUILD_SHARED_LIBRARY)
