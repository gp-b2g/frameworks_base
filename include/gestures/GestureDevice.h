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

#ifndef ANDROID_HARDWARE_GESTURE_DEVICE_H
#define ANDROID_HARDWARE_GESTURE_DEVICE_H

#include <system/gestures.h>
#include <gestures/IGestureDeviceClient.h>

namespace android {

class IGestureDeviceService;
class IGestureDevice;
class Mutex;
class String8;

// ref-counted object for callbacks
class GestureDeviceListener: virtual public RefBase
{
public:
    virtual void notify(int32_t msgType, int32_t ext1, int32_t ext2) = 0;
    virtual void postData(gesture_result_t* gs_results) = 0;
};

class GestureDevice : public BnGestureDeviceClient, public IBinder::DeathRecipient
{
public:
            // construct a gesture device client from an existing remote
    static  sp<GestureDevice>  create(const sp<IGestureDevice>& gesture);
    static  int32_t     getNumberOfGestureDevices();
    static  sp<GestureDevice>  connect(int v_Id);
    virtual ~GestureDevice();
    void    init();

    void        disconnect();

    status_t    getStatus() { return mStatus; }

    // start gesture
    status_t    startGesture();

    // stop gesture
    void        stopGesture();

    // set parameters - key/value pairs
    status_t    setParameters(const String8& params);

    // get parameters - key/value pairs
    String8     getParameters() const;

    // send command to gesture device driver
    status_t    sendCommand(int32_t cmd, int32_t arg1, int32_t arg2);

    void        setListener(const sp<GestureDeviceListener>& listener);

    // IGestureDeviceClient interface
    virtual void        notifyCallback(int32_t msgType, int32_t ext, int32_t ext2);
    virtual void        dataCallback(gesture_result_t* gs_results);

    sp<IGestureDevice>   remote();

private:
    GestureDevice();
    GestureDevice(const GestureDevice&);
    GestureDevice& operator=(const GestureDevice);
    virtual void binderDied(const wp<IBinder>& who);

    class DeathNotifier: public IBinder::DeathRecipient
    {
    public:
        DeathNotifier() {
        }

        virtual void binderDied(const wp<IBinder>& who);
    };

    static sp<DeathNotifier> mDeathNotifier;

    // helper function to obtain gesture service handle
    static const sp<IGestureDeviceService>& getGestureDeviceService();

    sp<IGestureDevice>   mGestureDevice;
    status_t            mStatus;

    sp<GestureDeviceListener>  mListener;

    friend class DeathNotifier;

    static  Mutex               mLock;
    static  sp<IGestureDeviceService>  mGestureDeviceService;
};

}; // namespace android

#endif
