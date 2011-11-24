LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
    AudioFlinger.cpp            \
    AudioMixer.cpp.arm          \
    AudioResampler.cpp.arm      \
    AudioResamplerSinc.cpp.arm  \
    AudioResamplerCubic.cpp.arm \
    AudioPolicyService.cpp

LOCAL_C_INCLUDES := \
    system/media/audio_effects/include

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libutils \
    libbinder \
    libmedia \
    libhardware \
    libhardware_legacy \
    libeffects \
    libdl \
    libpowermanager

# SRS Processing
ifeq ($(strip $(BOARD_USES_SRS_TRUEMEDIA)),true)
LOCAL_SHARED_LIBRARIES += libsrsprocessing
LOCAL_CFLAGS += -DSRS_PROCESSING
LOCAL_C_INCLUDES += vendor/qcom/proprietary/mm-audio/audio-effects/srs/TruMedia
endif
# SRS Processing

LOCAL_STATIC_LIBRARIES := \
    libcpustats \
    libmedia_helper

LOCAL_MODULE:= libaudioflinger

include $(BUILD_SHARED_LIBRARY)
