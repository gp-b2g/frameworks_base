/*
 * Copyright (c) 2012 Code Aurora Forum. All rights reserved.
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ANDROID_HARDWARE_GESTURE_DEVICE_HARDWARE_INTERFACE_H
#define ANDROID_HARDWARE_GESTURE_DEVICE_HARDWARE_INTERFACE_H

#include <binder/IMemory.h>
#include <binder/MemoryBase.h>
#include <binder/MemoryHeapBase.h>
#include <utils/RefBase.h>
#include <gestures/GestureDevice.h>
#include <system/window.h>
#include <hardware/gestures.h>

namespace android {

typedef void (*notify_callback)(int32_t msgType,
                                int32_t ext1,
                                int32_t ext2,
                                void* user);

typedef void (*data_callback)(gesture_result_t* gs_results,
                              void* user);

/**
 * GestureDeviceHardwareInterface.h defines the interface to the
 * gesture hardware abstraction layer, used for setting and
 * getting parameters, and starting/stopping gesture process. 
 *
 * It is a referenced counted interface with RefBase as its base class.
 * GestureDeviceService calls GestureDeviceHardwareInterface() 
 * to retrieve a strong pointer to the instance of this 
 * interface and may be called multiple times. The following 
 * steps describe a typical sequence: 
 *
 *   -# After GestureDeviceService calls
 *      GestureDeviceHardwareInterface(), getParameters() and
 *      setParameters() are used to initialize the geture device
 *      instance.
 *   -# startGesture() is called.  The gesture device instance
 *      then periodically sends the message GESTURE_MSG_GESTURE
 *      each time a new gesture result is available.
 *
 */

class GestureDeviceHardwareInterface : public virtual RefBase {
public:
    GestureDeviceHardwareInterface(const char *name)
    {
        mDevice = 0;
        mName = name;
    }

    ~GestureDeviceHardwareInterface()
    {
        LOGI("Destroying gesture device %s", mName.string());
        if(mDevice) {
            int rc = mDevice->common.close(&mDevice->common);
            if (rc != OK)
                LOGE("Could not close camera %s: %d", mName.string(), rc);
        }
    }

    status_t initialize(hw_module_t *module)
    {
        LOGI("Opening gesture device %s", mName.string());
        int rc = module->methods->open(module, mName.string(),
                                       (hw_device_t **)&mDevice);
        if (rc != OK) {
            LOGE("Could not open gesture device %s: %d", mName.string(), rc);
            return rc;
        }
        return rc;
    }

    /** Set the notification and data callbacks */
    void setCallbacks(notify_callback notify_cb,
                      data_callback data_cb,
                      void* user,
                      bool isreg)
    {
        mNotifyCb = notify_cb;
        mDataCb = data_cb;
        mCbUser = user;

        LOGV("%s(%s)", __FUNCTION__, mName.string());

        if (mDevice->ops->set_callbacks) {
            mDevice->ops->set_callbacks(mDevice,
                                   __notify_cb,
                                   __data_cb,
                                   this,
                                   isreg);
        }
    }

    /**
     * Start gesture.
     */
    status_t startGesture()
    {
        LOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->start)
            return mDevice->ops->start(mDevice);
        return INVALID_OPERATION;
    }

    /**
     * Stop gesture.
     */
    void stopGesture()
    {
        LOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->stop)
            mDevice->ops->stop(mDevice);
    }

    /**
     * Set the gesture device parameters. This returns BAD_VALUE if 
     * any parameter is invalid or not supported. */ 
    status_t setParameters(const char* params)
    {
        LOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->set_parameters)
            return mDevice->ops->set_parameters(mDevice, params);
        return INVALID_OPERATION;
    }

    /** Return the gesture device parameters. */
    String8 getParameters() const
    {
        LOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->get_parameters) {
            char *temp = mDevice->ops->get_parameters(mDevice);
            if(temp) {
                LOGE("%s(%s)", __FUNCTION__, temp);
                String8 parms(temp);
                free(temp);
                return parms;
            }
        }
        return String8();
    }

    /**
     * Send command to gesture device driver.
     */
    status_t sendCommand(int32_t cmd, int32_t arg1, int32_t arg2)
    {
        LOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->send_command)
            return mDevice->ops->send_command(mDevice, cmd, arg1, arg2);
        return INVALID_OPERATION;
    }

    /**
     * Dump state of the gesture device hardware
     */
    status_t dump(int fd, const Vector<String16>& args) const
    {
        LOGV("%s(%s)", __FUNCTION__, mName.string());
        if (mDevice->ops->dump)
            return mDevice->ops->dump(mDevice, fd);
        return OK; // It's fine if the HAL doesn't implement dump()
    }

private:
    gesture_device_t *      mDevice;
    String8                 mName;
    notify_callback         mNotifyCb;
    data_callback           mDataCb;
    void *                  mCbUser;

    static void __notify_cb(int32_t msg_type, int32_t ext1,
                            int32_t ext2, void *user)
    {
        LOGV("%s", __FUNCTION__);
        GestureDeviceHardwareInterface *__this =
                static_cast<GestureDeviceHardwareInterface *>(user);
        __this->mNotifyCb(msg_type, ext1, ext2, __this->mCbUser);
    }

    static void __data_cb(gesture_result_t* gs_results,
                          void *user)
    {
        LOGV("%s", __FUNCTION__);
        GestureDeviceHardwareInterface *__this =
                static_cast<GestureDeviceHardwareInterface *>(user);
        __this->mDataCb(gs_results, __this->mCbUser);
    }
};

};  // namespace android

#endif
