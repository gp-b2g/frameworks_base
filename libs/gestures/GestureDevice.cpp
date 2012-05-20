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

#define LOG_TAG "GestureDevice"
#include <utils/Log.h>
#include <utils/threads.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/IMemory.h>

#include <gestures/GestureDevice.h>
#include <gestures/IGestureDeviceService.h>

namespace android {

// client singleton for gesture device service binder interface
Mutex GestureDevice::mLock;
sp<IGestureDeviceService> GestureDevice::mGestureDeviceService;
sp<GestureDevice::DeathNotifier> GestureDevice::mDeathNotifier;

// establish binder interface to gesture device service
const sp<IGestureDeviceService>& GestureDevice::getGestureDeviceService()
{
    Mutex::Autolock _l(mLock);
    if (mGestureDeviceService.get() == 0) {
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder;
        do {
            binder = sm->getService(String16("media.gestures"));
            if (binder != 0)
                break;
            LOGW("GestureDeviceService not published, waiting...");
            usleep(500000); // 0.5 s
        } while(true);
        if (mDeathNotifier == NULL) {
            mDeathNotifier = new DeathNotifier();
        }
        binder->linkToDeath(mDeathNotifier);
        mGestureDeviceService = interface_cast<IGestureDeviceService>(binder);
    }
    LOGE_IF(mGestureDeviceService==0, "no GestureDeviceService!?");
    return mGestureDeviceService;
}

// ---------------------------------------------------------------------------

GestureDevice::GestureDevice()
{
    init();
}

// construct a gesture device client from an existing gesture device remote
sp<GestureDevice> GestureDevice::create(const sp<IGestureDevice>& gesture)
{
     LOGV("create");
     if (gesture == 0) {
         LOGE("gesture device remote is a NULL pointer");
         return 0;
     }

    sp<GestureDevice> c = new GestureDevice();
    if (gesture->connect(c) == NO_ERROR) {
        c->mStatus = NO_ERROR;
        c->mGestureDevice = gesture;
        gesture->asBinder()->linkToDeath(c);
        return c;
    }
    return 0;
}

void GestureDevice::init()
{
    mStatus = UNKNOWN_ERROR;
}

GestureDevice::~GestureDevice()
{
    // We don't need to call disconnect() here because if the GestureDeviceService
    // thinks we are the owner of the hardware, it will hold a (strong)
    // reference to us, and we can't possibly be here. We also don't want to
    // call disconnect() here if we are in the same process as mediaserver,
    // because we may be invoked by GestureDeviceService::Client::connect() and will
    // deadlock if we call any method of IGestureDevice here.
}

int32_t GestureDevice::getNumberOfGestureDevices()
{
    const sp<IGestureDeviceService>& cs = getGestureDeviceService();
    if (cs == 0) return 0;
    return cs->getNumberOfGestureDevices();
}

sp<GestureDevice> GestureDevice::connect(int v_Id)
{
    LOGV("connect");
    sp<GestureDevice> c = new GestureDevice();
    const sp<IGestureDeviceService>& cs = getGestureDeviceService();
    if (cs != 0) {
        c->mGestureDevice = cs->connect(c, v_Id);
    }
    if (c->mGestureDevice != 0) {
        c->mGestureDevice->asBinder()->linkToDeath(c);
        c->mStatus = NO_ERROR;
    } else {
        c.clear();
    }
    return c;
}

void GestureDevice::disconnect()
{
    LOGV("disconnect");
    if (mGestureDevice != 0) {
        mGestureDevice->disconnect();
        mGestureDevice->asBinder()->unlinkToDeath(this);
        mGestureDevice = 0;
    }
}

sp<IGestureDevice> GestureDevice::remote()
{
    return mGestureDevice;
}

// start gesture
status_t GestureDevice::startGesture()
{
    LOGV("startGesture");
    sp <IGestureDevice> c = mGestureDevice;
    if (c == 0) return NO_INIT;
    return c->startGesture();
}

// stop gesture
void GestureDevice::stopGesture()
{
    LOGV("stopGesture");
    sp <IGestureDevice> c = mGestureDevice;
    if (c == 0) return;
    c->stopGesture();
}

// set parameters - key/value pairs
status_t GestureDevice::setParameters(const String8& params)
{
    LOGV("setParameters");
    sp <IGestureDevice> c = mGestureDevice;
    if (c == 0) return NO_INIT;
    return c->setParameters(params);
}

// get parameters - key/value pairs
String8 GestureDevice::getParameters() const
{
    LOGV("getParameters");
    String8 params;
    sp <IGestureDevice> c = mGestureDevice;
    if (c != 0) params = mGestureDevice->getParameters();
    return params;
}

// send command to gesture device
status_t GestureDevice::sendCommand(int32_t cmd, int32_t arg1, int32_t arg2)
{
    LOGV("sendCommand");
    sp <IGestureDevice> c = mGestureDevice;
    if (c == 0) return NO_INIT;
    return c->sendCommand(cmd, arg1, arg2);
}

void GestureDevice::setListener(const sp<GestureDeviceListener>& listener)
{
    Mutex::Autolock _l(mLock);
    mListener = listener;
}

// callback from gesture device service
void GestureDevice::notifyCallback(int32_t msgType, int32_t ext1, int32_t ext2)
{
    sp<GestureDeviceListener> listener;
    {
        Mutex::Autolock _l(mLock);
        listener = mListener;
    }
    if (listener != NULL) {
        listener->notify(msgType, ext1, ext2);
    }
}

// callback from gesture device service when gesture result is ready
void GestureDevice::dataCallback(gesture_result_t* gs_results)
{
    sp<GestureDeviceListener> listener;
    {
        Mutex::Autolock _l(mLock);
        listener = mListener;
    }
    if (listener != NULL) {
        listener->postData(gs_results);
    }
}

void GestureDevice::binderDied(const wp<IBinder>& who) {
    LOGW("IGestureDevice died");
    notifyCallback(GESTURE_MSG_ERROR, GESTURE_ERROR_SERVER_DIED, 0);
}

void GestureDevice::DeathNotifier::binderDied(const wp<IBinder>& who) {
    LOGV("binderDied");
    Mutex::Autolock _l(GestureDevice::mLock);
    GestureDevice::mGestureDeviceService.clear();
    LOGW("GestureDevice server died!");
}

}; // namespace android
