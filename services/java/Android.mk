LOCAL_PATH:= $(call my-dir)

# the library
# ============================================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
            $(call all-subdir-java-files) \
	    com/android/server/EventLogTags.logtags \
	    com/android/server/am/EventLogTags.logtags

LOCAL_MODULE:= services

LOCAL_JAVA_LIBRARIES := android.policy

LOCAL_NO_EMMA_INSTRUMENT := true
LOCAL_NO_EMMA_COMPILE := true

include $(BUILD_JAVA_LIBRARY)

include $(BUILD_DROIDDOC)

# Copy the default QoS policy files to system/etc
include $(CLEAR_VARS)
LOCAL_MODULE       := QoSPolicy.xml
LOCAL_MODULE_TAGS  := optional eng
LOCAL_MODULE_CLASS := ETC
LOCAL_SRC_FILES    := $(LOCAL_MODULE)
include $(BUILD_PREBUILT)
