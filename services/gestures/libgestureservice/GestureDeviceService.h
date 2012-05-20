/*
**
** Copyright (c) 2012 Code Aurora Forum. All rights reserved.
** Copyright (C) 2008, The Android Open Source Project
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

#ifndef ANDROID_SERVERS_GESTURE_DEVICE_SERVICE_H
#define ANDROID_SERVERS_GESTURE_DEVICE_SERVICE_H

#include <binder/BinderService.h>
#include <gestures/IGestureDeviceService.h>
#include <hardware/gestures.h>

/* This needs to be increased if we can have more gesture devices */
#define MAX_GESTURE_DEVICES 4

namespace android {

class GestureDeviceHardwareInterface;
class GestureDeviceService;

class GestureDeviceService :
    public BinderService<GestureDeviceService>,
    public BnGestureDeviceService
{
    class Client;
    friend class BinderService<GestureDeviceService>;
public:
    static char const* getServiceName() { return "media.gestures"; }

    GestureDeviceService();
    virtual             ~GestureDeviceService();

    virtual int32_t     getNumberOfGestureDevices();
    virtual sp<IGestureDevice> connect(const sp<IGestureDeviceClient>& gestureClient, int v_Id);
    virtual void        removeClient(const sp<IGestureDeviceClient>& gestureClient);
    virtual sp<Client>  getClientById(int v_Id);

    virtual status_t    dump(int fd, const Vector<String16>& args);
    virtual status_t    onTransact(uint32_t code, const Parcel& data,
                                   Parcel* reply, uint32_t flags);
    virtual void onFirstRef();

private:
    Mutex               mServiceLock;
    wp<Client>          mClient[MAX_GESTURE_DEVICES];  // protected by mServiceLock

    int                 mNumberOfGestureDevices;

    // atomics to record whether the hardware is allocated to some client.
    volatile int32_t    mBusy[MAX_GESTURE_DEVICES];
    void                setGestureDeviceBusy(int v_Id);
    void                setGestureDeviceFree(int v_Id);

    class Client : public BnGestureDevice
    {
    public:
        // IGestureDevice interface (see IGestureDevice for details)
        virtual void            disconnect();
        virtual status_t        connect(const sp<IGestureDeviceClient>& client);
        virtual status_t        startGesture();
        virtual void            stopGesture();
        virtual status_t        setParameters(const String8& params);
        virtual String8         getParameters() const;
        virtual status_t        sendCommand(int32_t cmd, int32_t arg1, int32_t arg2);
    private:
        friend class GestureDeviceService;
        Client(const sp<GestureDeviceService>& gestureService,
               const sp<IGestureDeviceClient>& gestureClient,
               const sp<GestureDeviceHardwareInterface>& hardware,
               int v_Id,
               int clientPid);
        ~Client();

        // return our gesture device client
        const sp<IGestureDeviceClient>&    getGestureDeviceClient() { return mGestureDeviceClient; }

        // check whether the calling process matches mClientPid.
        status_t                checkPid() const;
        status_t                checkPidAndHardware() const;  // also check mHardware != 0

        // these are static callback functions
        static void             notifyCallback(int32_t msgType, int32_t ext1, int32_t ext2, void* user);
        static void             dataCallback(gesture_result_t* gs_results, void* user);
        // convert client from cookie
        static sp<Client>       getClientFromCookie(void* user);
        // handlers for messages
        void                    handleGenericNotify(int32_t msgType, int32_t ext1, int32_t ext2);
        void                    handleGenericData(gesture_result_t* gs_results);

        // these are initialized in the constructor.
        sp<GestureDeviceService>             mGestureDeviceService;  // immutable after constructor
        sp<IGestureDeviceClient>             mGestureDeviceClient;
        int                                  mGestureId;       // immutable after constructor
        pid_t                                mClientPid;
        sp<GestureDeviceHardwareInterface>   mHardware;       // cleared after disconnect()
        // Ensures atomicity among the public methods
        mutable Mutex                   mLock;
    };

    gesture_module_t *mModule;
};

} // namespace android

#endif
