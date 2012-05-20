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

#define LOG_TAG "IGestureDeviceClient"
#include <utils/Log.h>
#include <stdint.h>
#include <sys/types.h>
#include <gestures/IGestureDeviceClient.h>

namespace android {

enum {
    NOTIFY_CALLBACK = IBinder::FIRST_CALL_TRANSACTION,
    DATA_CALLBACK,
};

class BpGestureDeviceClient: public BpInterface<IGestureDeviceClient>
{
public:
    BpGestureDeviceClient(const sp<IBinder>& impl)
        : BpInterface<IGestureDeviceClient>(impl)
    {
    }

    // generic callback from gesture device service to app
    void notifyCallback(int32_t msgType, int32_t ext1, int32_t ext2)
    {
        LOGV("notifyCallback");
        Parcel data, reply;
        data.writeInterfaceToken(IGestureDeviceClient::getInterfaceDescriptor());
        data.writeInt32(msgType);
        data.writeInt32(ext1);
        data.writeInt32(ext2);
        remote()->transact(NOTIFY_CALLBACK, data, &reply, IBinder::FLAG_ONEWAY);
    }

    // generic data callback from gesture device service to app
    void dataCallback(gesture_result_t* gs_results)
    {
        LOGV("dataCallback");
        Parcel data, reply;
        data.writeInterfaceToken(IGestureDeviceClient::getInterfaceDescriptor());
        data.writeInt32(gs_results->number_of_events);
        for (int i = 0; i < gs_results->number_of_events; i++) {
            data.writeInt32(gs_results->events[i].version);
            data.writeInt32(gs_results->events[i].type);
            data.writeInt32(gs_results->events[i].subtype);
            data.writeInt32(gs_results->events[i].id);
            data.writeInt64(gs_results->events[i].timestamp);
            data.writeFloat(gs_results->events[i].confidence);
            data.writeFloat(gs_results->events[i].velocity);
            data.writeInt32(gs_results->events[i].location.num_of_points);
            if (gs_results->events[i].location.num_of_points > 0) {
                int size = 
                    sizeof(gesture_vector_t) * gs_results->events[i].location.num_of_points;
                data.write(gs_results->events[i].location.pPoints, size);
            }
            data.writeInt32(gs_results->events[i].extendinfo.len);
            if (gs_results->events[i].extendinfo.len > 0) {
                data.write(gs_results->events[i].extendinfo.buf, 
                           gs_results->events[i].extendinfo.len);
            }
        }

        remote()->transact(DATA_CALLBACK, data, &reply, IBinder::FLAG_ONEWAY);
    }
};

IMPLEMENT_META_INTERFACE(GestureDeviceClient, "android.hardware.gesturedev.IGestureDeviceClient");

// ----------------------------------------------------------------------

status_t BnGestureDeviceClient::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case NOTIFY_CALLBACK: {
            LOGV("NOTIFY_CALLBACK");
            CHECK_INTERFACE(IGestureDeviceClient, data, reply);
            int32_t msgType = data.readInt32();
            int32_t ext1 = data.readInt32();
            int32_t ext2 = data.readInt32();
            notifyCallback(msgType, ext1, ext2);
            return NO_ERROR;
        } break;
        case DATA_CALLBACK: {
            LOGV("DATA_CALLBACK");
            CHECK_INTERFACE(IGestureDeviceClient, data, reply);

            gesture_result_t* gs_results = NULL;
            if (data.dataAvail() > 0) {
                gs_results = (gesture_result_t*)malloc(sizeof(gesture_result_t));
                if (NULL != gs_results) {
                    memset(gs_results, 0, sizeof(gesture_result_t));
                    gs_results->number_of_events = data.readInt32();
                    if (gs_results->number_of_events > 0) {
                        gs_results->events =
                            (gesture_event_t *)malloc(sizeof(gesture_event_t)*gs_results->number_of_events);
                        if (NULL != gs_results->events) {
                            memset(gs_results->events, 0, sizeof(gesture_event_t)*gs_results->number_of_events);
                            for (int i = 0; i < gs_results->number_of_events; i++) {
                                gs_results->events[i].version = data.readInt32();
                                gs_results->events[i].type = data.readInt32();
                                gs_results->events[i].subtype = data.readInt32();
                                gs_results->events[i].id = data.readInt32();
                                gs_results->events[i].timestamp = data.readInt64();
                                gs_results->events[i].confidence = data.readFloat();
                                gs_results->events[i].velocity = data.readFloat();
                                gs_results->events[i].location.num_of_points = data.readInt32();
                                if (gs_results->events[i].location.num_of_points > 0) {
                                    int size = 
                                        sizeof(gesture_vector_t) * gs_results->events[i].location.num_of_points;
                                    gs_results->events[i].location.pPoints =
                                        (gesture_vector_t *)data.readInplace(size);
                                }
                                gs_results->events[i].extendinfo.len = data.readInt32();
                                if (gs_results->events[i].extendinfo.len > 0) {
                                    gs_results->events[i].extendinfo.buf =
                                        (void *)data.readInplace(gs_results->events[i].extendinfo.len);
                                }
                            }
                        }
                    }
                    dataCallback(gs_results);
                    if (NULL != gs_results->events) {
                        free(gs_results->events);
                    }
                    free(gs_results);
                }
            }
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android

