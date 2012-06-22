LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    Layer.cpp 								\
    LayerBase.cpp 							\
    LayerDim.cpp 							\
    LayerScreenshot.cpp						\
    DdmConnection.cpp						\
    DisplayHardware/DisplayHardware.cpp 	\
    DisplayHardware/DisplayHardwareBase.cpp \
    DisplayHardware/HWComposer.cpp 			\
    GLExtensions.cpp 						\
    MessageQueue.cpp 						\
    SurfaceFlinger.cpp 						\
    SurfaceTextureLayer.cpp 				\
    Transform.cpp 							\
    

LOCAL_CFLAGS:= -DLOG_TAG=\"SurfaceFlinger\"
LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

ifeq ($(BOARD_USES_QCOM_HARDWARE),true)
    LOCAL_WHOLE_STATIC_LIBRARIES += libqc-surfaceflinger
endif

ifeq ($(TARGET_BOARD_PLATFORM), omap3)
	LOCAL_CFLAGS += -DNO_RGBX_8888
endif
ifeq ($(TARGET_BOARD_PLATFORM), omap4)
	LOCAL_CFLAGS += -DHAS_CONTEXT_PRIORITY
endif
ifeq ($(TARGET_BOARD_PLATFORM), s5pc110)
	LOCAL_CFLAGS += -DHAS_CONTEXT_PRIORITY -DNEVER_DEFAULT_TO_ASYNC_MODE
	LOCAL_CFLAGS += -DREFRESH_RATE=56
endif

ifeq ($(TARGET_USES_MDP3), true)
     LOCAL_CFLAGS += -DUSE_MDP3
endif


LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libhardware \
	libutils \
	libEGL \
	libGLESv1_CM \
	libbinder \
	libui \
	libgui \
        libQcomUI

# this is only needed for DDMS debugging
LOCAL_SHARED_LIBRARIES += libdvm libandroid_runtime

ifeq ($(TARGET_USES_TESTFRAMEWORK),true)
LOCAL_CFLAGS += -DGFX_TESTFRAMEWORK
LOCAL_SHARED_LIBRARIES += libtestframework
endif

ifeq ($(TARGET_HAVE_BYPASS),true)
    LOCAL_CFLAGS += -DBUFFER_COUNT_SERVER=3
else
    LOCAL_CFLAGS += -DBUFFER_COUNT_SERVER=2
endif

LOCAL_C_INCLUDES := \
	$(call include-path-for, corecg graphics)

LOCAL_C_INCLUDES += hardware/libhardware/modules/gralloc \
                    hardware/qcom/display/libqcomui

LOCAL_MODULE:= libsurfaceflinger

include $(BUILD_SHARED_LIBRARY)
