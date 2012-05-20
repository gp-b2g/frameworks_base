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

#ifndef ANDROID_HARDWARE_IGESTURE_DEVICE_SERVICE_H
#define ANDROID_HARDWARE_IGESTURE_DEVICE_SERVICE_H

#include <utils/RefBase.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>

#include <gestures/IGestureDeviceClient.h>
#include <gestures/IGestureDevice.h>

namespace android {

class IGestureDeviceService : public IInterface
{
public:
    enum {
        GET_NUMBER_OF_GESTURE_DEVICES = IBinder::FIRST_CALL_TRANSACTION,
        CONNECT
    };

public:
    DECLARE_META_INTERFACE(GestureDeviceService);

    virtual int32_t                getNumberOfGestureDevices() = 0;
    virtual sp<IGestureDevice>     connect(const sp<IGestureDeviceClient>& gestureClient,
                                    int v_Id) = 0;
};

// ----------------------------------------------------------------------------

class BnGestureDeviceService: public BnInterface<IGestureDeviceService>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

}; // namespace android

#endif
