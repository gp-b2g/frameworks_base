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

#ifndef ANDROID_HARDWARE_IGESTURE_DEVICE_H
#define ANDROID_HARDWARE_IGESTURE_DEVICE_H

#include <utils/RefBase.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>
#include <binder/IMemory.h>
#include <utils/String8.h>
#include <gestures/GestureDevice.h>

namespace android {

class IGestureDeviceClient;

class IGestureDevice: public IInterface
{
public:
    DECLARE_META_INTERFACE(GestureDevice);

    virtual void            disconnect() = 0;

    // connect new client with existing gesture device remote
    virtual status_t        connect(const sp<IGestureDeviceClient>& client) = 0;

    // start gesture
    virtual status_t        startGesture() = 0;

    // stop gesture
    virtual void            stopGesture() = 0;

    // set preview/capture parameters - key/value pairs
    virtual status_t        setParameters(const String8& params) = 0;

    // get preview/capture parameters - key/value pairs
    virtual String8         getParameters() const = 0;

    // send command to gesture device driver
    virtual status_t        sendCommand(int32_t cmd, int32_t arg1, int32_t arg2) = 0;
};

// ----------------------------------------------------------------------------

class BnGestureDevice: public BnInterface<IGestureDevice>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

}; // namespace android

#endif
