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

#include <stdint.h>
#include <sys/types.h>
 
#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/Timers.h>

#include <binder/Parcel.h>
#include <binder/IInterface.h>

#include <gui/IMplSysPedConnection.h>

namespace android {
// ----------------------------------------------------------------------------

enum {
    START_PED = IBinder::FIRST_CALL_TRANSACTION,
    STOP_PED,
    GET_STEPS,
    GET_WALK_TIME,
    CLEAR_PED_DATA,
};

class BpMplSysPedConnection : public BpInterface<IMplSysPedConnection>
{
public:
    BpMplSysPedConnection(const sp<IBinder>& impl)
        : BpInterface<IMplSysPedConnection>(impl)
    {
    }

    virtual status_t rpcStartPed() {
        Parcel data, reply;
        data.writeInterfaceToken(IMplSysPedConnection::getInterfaceDescriptor());
        remote()->transact(START_PED, data, &reply);
        return reply.readInt32();
    }
    virtual status_t rpcStopPed() {
        Parcel data, reply;
        data.writeInterfaceToken(IMplSysPedConnection::getInterfaceDescriptor());
        remote()->transact(STOP_PED, data, &reply);
        return reply.readInt32();
    }
    virtual status_t rpcGetSteps() {
        Parcel data, reply;
        data.writeInterfaceToken(IMplSysPedConnection::getInterfaceDescriptor());
        remote()->transact(GET_STEPS, data, &reply);
        return reply.readInt32();
    }
    virtual double rpcGetWalkTime() {
        Parcel data, reply;
        data.writeInterfaceToken(IMplSysPedConnection::getInterfaceDescriptor());
        remote()->transact(GET_WALK_TIME, data, &reply);
        return reply.readDouble();
    }
    virtual status_t rpcClearPedData() {
        Parcel data, reply;
        data.writeInterfaceToken(IMplSysPedConnection::getInterfaceDescriptor());
        remote()->transact(CLEAR_PED_DATA, data, &reply);
        return reply.readInt32();
    }    
};

IMPLEMENT_META_INTERFACE(MplSysPedConnection, "android.gui.MplSysPedConnection");

// ----------------------------------------------------------------------------

status_t BnMplSysPedConnection::onTransact(uint32_t code, const Parcel& data,
                                        Parcel* reply, uint32_t flags)
{
    switch (code) {
    case START_PED: {
        CHECK_INTERFACE(IMplSysPedConnection, data, reply);
        status_t result = rpcStartPed();
        reply->writeInt32(result);
        return NO_ERROR;
    }
    case STOP_PED: {
        CHECK_INTERFACE(IMplSysPedConnection, data, reply);
        status_t result = rpcStopPed();
        reply->writeInt32(result);
        return NO_ERROR;
    }
    case GET_STEPS: {
        CHECK_INTERFACE(IMplSysPedConnection, data, reply);
        status_t result = rpcGetSteps();
        reply->writeInt32(result);
        return NO_ERROR;
    }
    case GET_WALK_TIME: {
        CHECK_INTERFACE(IMplSysPedConnection, data, reply);
            double result = rpcGetWalkTime();
            reply->writeDouble(result);
        return NO_ERROR;
    }
    case CLEAR_PED_DATA: {
        CHECK_INTERFACE(IMplSysPedConnection, data, reply);
        status_t result = rpcClearPedData();
        reply->writeInt32(result);
        return NO_ERROR;
    }
    }
    return BBinder::onTransact(code, data, reply, flags);
}

// ----------------------------------------------------------------------------
}; // namespace android
