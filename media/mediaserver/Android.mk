LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	main_mediaserver.cpp 

LOCAL_SHARED_LIBRARIES := \
	libaudioflinger \
	libcameraservice \
	libmediaplayerservice \
	libdl      \
	libutils \
	libbinder \
	libgestureservice

base := $(LOCAL_PATH)/../..

LOCAL_C_INCLUDES := \
    $(base)/services/audioflinger \
    $(base)/services/camera/libcameraservice \
    $(base)/media/libmediaplayerservice \
    $(base)/services/gestures/libgestureservice

LOCAL_MODULE:= mediaserver

include $(BUILD_EXECUTABLE)
