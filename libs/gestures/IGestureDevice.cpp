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

#define LOG_TAG "IGestureDevice"
#include <utils/Log.h>
#include <stdint.h>
#include <sys/types.h>
#include <binder/Parcel.h>
#include <gestures/IGestureDevice.h>

namespace android {

enum {
    DISCONNECT = IBinder::FIRST_CALL_TRANSACTION,
    START_GESTURE,
    STOP_GESTURE,
    SET_PARAMETERS,
    GET_PARAMETERS,
    SEND_COMMAND,
    CONNECT,
};

class BpGestureDevice: public BpInterface<IGestureDevice>
{
public:
    BpGestureDevice(const sp<IBinder>& impl)
        : BpInterface<IGestureDevice>(impl)
    {
    }

    virtual status_t connect(const sp<IGestureDeviceClient>& gestureClient)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IGestureDevice::getInterfaceDescriptor());
        data.writeStrongBinder(gestureClient->asBinder());
        remote()->transact(CONNECT, data, &reply);
        return reply.readInt32();
    }

    // disconnect from gesture device service
    void disconnect()
    {
        LOGV("disconnect");
        Parcel data, reply;
        data.writeInterfaceToken(IGestureDevice::getInterfaceDescriptor());
        remote()->transact(DISCONNECT, data, &reply);
    }

    // start gesture
    status_t startGesture()
    {
        LOGV("startGesture");
        Parcel data, reply;
        data.writeInterfaceToken(IGestureDevice::getInterfaceDescriptor());
        remote()->transact(START_GESTURE, data, &reply);
        return reply.readInt32();
    }

    // stop gesture
    void stopGesture()
    {
        LOGV("stopGesture");
        Parcel data, reply;
        data.writeInterfaceToken(IGestureDevice::getInterfaceDescriptor());
        remote()->transact(STOP_GESTURE, data, &reply);
    }

    // set parameters - key/value pairs
    status_t setParameters(const String8& params)
    {
        LOGV("setParameters");
        Parcel data, reply;
        data.writeInterfaceToken(IGestureDevice::getInterfaceDescriptor());
        data.writeString8(params);
        remote()->transact(SET_PARAMETERS, data, &reply);
        return reply.readInt32();
    }

    // get parameters - key/value pairs
    String8 getParameters() const
    {
        LOGV("getParameters");
        Parcel data, reply;
        data.writeInterfaceToken(IGestureDevice::getInterfaceDescriptor());
        remote()->transact(GET_PARAMETERS, data, &reply);
        return reply.readString8();
    }
    virtual status_t sendCommand(int32_t cmd, int32_t arg1, int32_t arg2)
    {
        LOGV("sendCommand");
        Parcel data, reply;
        data.writeInterfaceToken(IGestureDevice::getInterfaceDescriptor());
        data.writeInt32(cmd);
        data.writeInt32(arg1);
        data.writeInt32(arg2);
        remote()->transact(SEND_COMMAND, data, &reply);
        return reply.readInt32();
    }
};

IMPLEMENT_META_INTERFACE(GestureDevice, "android.hardware.gesturedev.IGestureDevice");

// ----------------------------------------------------------------------

status_t BnGestureDevice::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case CONNECT: {
            CHECK_INTERFACE(IGestureDevice, data, reply);
            sp<IGestureDeviceClient> gestureClient = interface_cast<IGestureDeviceClient>(data.readStrongBinder());
            reply->writeInt32(connect(gestureClient));
            return NO_ERROR;
        } break;
        case DISCONNECT: {
            LOGV("DISCONNECT");
            CHECK_INTERFACE(IGestureDevice, data, reply);
            disconnect();
            return NO_ERROR;
        } break;
        case START_GESTURE: {
            LOGV("START_GESTURE");
            CHECK_INTERFACE(IGestureDevice, data, reply);
            reply->writeInt32(startGesture());
            return NO_ERROR;
        } break;
        case STOP_GESTURE: {
            LOGV("STOP_GESTURE");
            CHECK_INTERFACE(IGestureDevice, data, reply);
            stopGesture();
            return NO_ERROR;
        } break;
        case SET_PARAMETERS: {
            LOGV("SET_PARAMETERS");
            CHECK_INTERFACE(IGestureDevice, data, reply);
            String8 params(data.readString8());
            reply->writeInt32(setParameters(params));
            return NO_ERROR;
         } break;
        case GET_PARAMETERS: {
            LOGV("GET_PARAMETERS");
            CHECK_INTERFACE(IGestureDevice, data, reply);
            reply->writeString8(getParameters());
            return NO_ERROR;
         } break;
        case SEND_COMMAND: {
            LOGV("SEND_COMMAND");
            CHECK_INTERFACE(IGestureDevice, data, reply);
            int command = data.readInt32();
            int arg1 = data.readInt32();
            int arg2 = data.readInt32();
            reply->writeInt32(sendCommand(command, arg1, arg2));
            return NO_ERROR;
         } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android
