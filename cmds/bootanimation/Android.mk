LOCAL_PATH:= $(call my-dir)

QRDExt_BootAnimation:=yes
include build/buildplus/target/QRDExt_target.min

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	bootanimation_main.cpp \
	BootAnimation.cpp

LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libbinder \
    libui \
	libskia \
    libEGL \
    libGLESv1_CM \
    libgui

# Boot Animation Processing
ifeq ($(strip $(QRDExt_BootAnimation)),no)
else
LOCAL_CFLAGS += -DBOOT_ANIMATION_ENABLE
endif
# Boot Animation Processing

LOCAL_C_INCLUDES := \
	$(call include-path-for, corecg graphics)

LOCAL_MODULE:= bootanimation


include $(BUILD_EXECUTABLE)
