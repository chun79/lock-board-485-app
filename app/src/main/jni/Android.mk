LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := lockboard_serial
LOCAL_SRC_FILES := serial_port.c
LOCAL_CFLAGS := -Wall -Wextra -Werror -O2
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
