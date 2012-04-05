/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_GUI_IMPL_SYS_CONNECTION_H
#define ANDROID_GUI_IMPL_SYS_CONNECTION_H

#include <stdint.h> 
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/RefBase.h>

#include <binder/IInterface.h>

namespace android {
// ----------------------------------------------------------------------------

class SensorChannel;

class IMplSysConnection : public IInterface
{
public:
    DECLARE_META_INTERFACE(MplSysConnection);

    virtual status_t getBiases(float*) = 0;
    virtual status_t setBiases(float*) = 0;
    virtual status_t setSensors(long) = 0;
    virtual status_t getSensors(long*) = 0;
    virtual status_t setBiasUpdateFunc(long) = 0;
    virtual status_t resetCal() = 0;
    virtual status_t selfTest() = 0;
    virtual status_t rpcSetLocalMagField(float, float, float) = 0;
    virtual status_t test() = 0;
};

// ----------------------------------------------------------------------------

class BnMplSysConnection : public BnInterface<IMplSysConnection>
{
public:
    virtual status_t onTransact(uint32_t code,
                                const Parcel& data,
                                Parcel* reply,
                                uint32_t flags = 0);
};

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_GUI_IMPL_SYS_CONNECTION_H
