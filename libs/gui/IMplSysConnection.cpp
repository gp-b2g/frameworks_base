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

#include <gui/IMplSysConnection.h>

namespace android {
// ----------------------------------------------------------------------------

enum {
    GET_BIASES = IBinder::FIRST_CALL_TRANSACTION,
    SET_BIASES,
    SET_BIAS_UPDATE_FUNC,
    SET_SENSORS,
    GET_SENSORS,
    RESET_CAL,
    SELF_TEST,
    SET_LOCAL_MAG_FIELD,
    TEST,
    START_PED,
    STOP_PED,
    GET_STEPS,
    GET_WALK_TIME,
    CLEAR_PED_DATA,
};

class BpMplSysConnection : public BpInterface<IMplSysConnection>
{
public:
    BpMplSysConnection(const sp<IBinder>& impl)
        : BpInterface<IMplSysConnection>(impl)
    {
    }

    virtual status_t getBiases(float* b)
    {
        Parcel data, reply;
        int i, rv, len = 9;
        data.writeInterfaceToken(IMplSysConnection::getInterfaceDescriptor());
        LOGE("client side get biases");
        remote()->transact(GET_BIASES, data, &reply);
        for (i = 0; i < len; i++)
            b[i]=reply.readFloat();
        rv = reply.readInt32();
        return rv;
    }

    virtual status_t setBiases(float* b)
    {
        Parcel data, reply;
        int i, rv, len = 9;
        data.writeInterfaceToken(IMplSysConnection::getInterfaceDescriptor());
        for (i = 0; i < len; i++)
            data.writeFloat(b[i]);
        remote()->transact(SET_BIASES, data, &reply);
        rv = reply.readInt32();
        return rv;
    }

    virtual status_t setBiasUpdateFunc(long f)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMplSysConnection::getInterfaceDescriptor());
        data.writeInt32(f);
        remote()->transact(SET_BIAS_UPDATE_FUNC, data, &reply);
        return reply.readInt32();
    }

    virtual status_t setSensors(long s)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMplSysConnection::getInterfaceDescriptor());
        data.writeInt32(s);
        remote()->transact(SET_SENSORS, data, &reply);
        return reply.readInt32();
    }

    virtual status_t getSensors(long* s)
    {
        Parcel data, reply;
        long val;
        data.writeInterfaceToken(IMplSysConnection::getInterfaceDescriptor());
        remote()->transact(GET_SENSORS, data, &reply);
        val = reply.readInt32();
        *s = val;
        return reply.readInt32();
    }
        
    virtual status_t resetCal()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMplSysConnection::getInterfaceDescriptor());
        remote()->transact(RESET_CAL, data, &reply);
        return reply.readInt32();
    }

    virtual status_t selfTest()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMplSysConnection::getInterfaceDescriptor());
        remote()->transact(SELF_TEST, data, &reply);
        return reply.readInt32();
    }

    virtual status_t rpcSetLocalMagField(float x, float y, float z) {
        Parcel data, reply;
        data.writeInterfaceToken(IMplSysConnection::getInterfaceDescriptor());
        data.writeFloat(x);
        data.writeFloat(y);
        data.writeFloat(z);
        remote()->transact(SET_LOCAL_MAG_FIELD, data, &reply);
        return reply.readInt32();
    }

    virtual status_t test()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IMplSysConnection::getInterfaceDescriptor());
        LOGE("client side test");
        remote()->transact(TEST, data, &reply);
        return reply.readInt32();
    }

};

IMPLEMENT_META_INTERFACE(MplSysConnection, "android.gui.MplSysConnection");

// ----------------------------------------------------------------------------

status_t BnMplSysConnection::onTransact(uint32_t code, const Parcel& data,
                                        Parcel* reply, uint32_t flags)
{
    switch (code) {
    case GET_BIASES: {
        int i;
        float b[9];
        CHECK_INTERFACE(IMplSysConnection, data, reply);
        status_t result = getBiases(b);
        for (i = 0; i < 9; i++)
            reply->writeFloat(b[i]);
        reply->writeInt32(result);
        return NO_ERROR;
    }
        break;
    case SET_BIASES: {
        CHECK_INTERFACE(IMplSysConnection, data, reply);
        int i;
        float b[9];
        for (i = 0; i < 9; i++)
            b[i] = data.readFloat();
        status_t result = setBiases(b);
        reply->writeInt32(result);
        return NO_ERROR;
    }
        break;
    case SET_BIAS_UPDATE_FUNC: {
        long f;
        CHECK_INTERFACE(IMplSysConnection, data, reply);
        f = data.readInt32();
        status_t result = setBiasUpdateFunc(f);
        reply->writeInt32(result);
        return NO_ERROR;
    }
        break;
    case SET_SENSORS: {
        long s;
        CHECK_INTERFACE(IMplSysConnection, data, reply);
        s = data.readInt32();
        status_t result = setSensors(s);
        reply->writeInt32(result);
        return NO_ERROR;
    }
        break;
    case GET_SENSORS: {
        long s;
        CHECK_INTERFACE(IMplSysConnection, data, reply);
        status_t result = getSensors(&s);
        reply->writeInt32((int32_t) s);
        reply->writeInt32(result);
        return NO_ERROR;
    }
        break;
    case RESET_CAL: {
        CHECK_INTERFACE(IMplSysConnection, data, reply);
        status_t result = resetCal();
        reply->writeInt32(result);
        return NO_ERROR;
    }
        break;
    case SELF_TEST: {
        CHECK_INTERFACE(IMplSysConnection, data, reply);
        status_t result = selfTest();
        reply->writeInt32(result);
        return NO_ERROR;
    }
        break;
    case SET_LOCAL_MAG_FIELD: {
        float x, y, z;
        CHECK_INTERFACE(IMplSysConnection, data, reply);
        x = data.readFloat();
        y = data.readFloat();
        z = data.readFloat();
        status_t result = rpcSetLocalMagField(x, y, z);
        reply->writeInt32(result);
        return NO_ERROR;
    }
    case TEST: {
        CHECK_INTERFACE(IMplSysConnection, data, reply);
        LOGV("server side test\n");
        status_t result = test();
        reply->writeInt32(result);
        return NO_ERROR;
    }
        break;
    }
    return BBinder::onTransact(code, data, reply, flags);
}

// ----------------------------------------------------------------------------
}; // namespace android
