/*
**
** Copyright (c) 2012 Code Aurora Forum. All rights reserved.
** Copyright (c) 2008, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#include <stdint.h>
#include <sys/types.h>

#include <binder/Parcel.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>

#include <gestures/IGestureDeviceService.h>

namespace android {

class BpGestureDeviceService: public BpInterface<IGestureDeviceService>
{
public:
    BpGestureDeviceService(const sp<IBinder>& impl)
        : BpInterface<IGestureDeviceService>(impl)
    {
    }

    // get number of gesture devcies available
    virtual int32_t getNumberOfGestureDevices()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IGestureDeviceService::getInterfaceDescriptor());
        remote()->transact(BnGestureDeviceService::GET_NUMBER_OF_GESTURE_DEVICES, data, &reply);
        return reply.readInt32();
    }

    // connect to gesture device service
    virtual sp<IGestureDevice> connect(const sp<IGestureDeviceClient>& gestureClient, int v_Id)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IGestureDeviceService::getInterfaceDescriptor());
        data.writeStrongBinder(gestureClient->asBinder());
        data.writeInt32(v_Id);
        remote()->transact(BnGestureDeviceService::CONNECT, data, &reply);
        return interface_cast<IGestureDevice>(reply.readStrongBinder());
    }
};

IMPLEMENT_META_INTERFACE(GestureDeviceService, "android.hardware.gesturedev.IGestureDeviceService");

// ----------------------------------------------------------------------

status_t BnGestureDeviceService::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case GET_NUMBER_OF_GESTURE_DEVICES: {
            CHECK_INTERFACE(IGestureDeviceService, data, reply);
            reply->writeInt32(getNumberOfGestureDevices());
            return NO_ERROR;
        } break;
        case CONNECT: {
            CHECK_INTERFACE(IGestureDeviceService, data, reply);
            sp<IGestureDeviceClient> gestureClient = interface_cast<IGestureDeviceClient>(data.readStrongBinder());
            sp<IGestureDevice> gesture = connect(gestureClient, data.readInt32());
            reply->writeStrongBinder(gesture->asBinder());
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android

