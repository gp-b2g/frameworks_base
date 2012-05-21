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

#define LOG_TAG "GestureDeviceService"

#include <stdio.h>
#include <sys/types.h>
#include <pthread.h>

#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <cutils/atomic.h>
#include <cutils/properties.h>
#include <hardware/hardware.h>
#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/String16.h>

#include "GestureDeviceService.h"
#include "GestureDeviceHardwareInterface.h"

namespace android {

// ----------------------------------------------------------------------------
// Logging support -- this is for debugging only
// Use "adb shell dumpsys media.gestures -v 1" to change it.
static volatile int32_t gLogLevel = 0;

#define LOG1(...) LOGD_IF(gLogLevel >= 1, __VA_ARGS__);
#define LOG2(...) LOGD_IF(gLogLevel >= 2, __VA_ARGS__);

static void setLogLevel(int level) {
    android_atomic_write(level, &gLogLevel);
}

// ----------------------------------------------------------------------------

static int getCallingPid() {
    return IPCThreadState::self()->getCallingPid();
}

static int getCallingUid() {
    return IPCThreadState::self()->getCallingUid();
}

// ----------------------------------------------------------------------------
static GestureDeviceService *gGestureDeviceService;

GestureDeviceService::GestureDeviceService()
:mModule(0)
{
    LOGI("GestureDeviceService started (pid=%d)", getpid());
    gGestureDeviceService = this;
}

void GestureDeviceService::onFirstRef()
{
    BnGestureDeviceService::onFirstRef();

    if (hw_get_module(GESTURE_HARDWARE_MODULE_ID,
                (const hw_module_t **)&mModule) < 0) {
        LOGE("Could not load gesture HAL module");
        mNumberOfGestureDevices = 0;
    }
    else {
        mNumberOfGestureDevices = mModule->get_number_of_gesture_devices();
        if (mNumberOfGestureDevices > MAX_GESTURE_DEVICES) {
            LOGE("Number of gesture devices(%d) > MAX_GESTURE_DEVICES(%d).",
                    mNumberOfGestureDevices, MAX_GESTURE_DEVICES);
            mNumberOfGestureDevices = MAX_GESTURE_DEVICES;
        }
        for (int i = 0; i < mNumberOfGestureDevices; i++) {
            setGestureDeviceFree(i);
        }
    }
}

GestureDeviceService::~GestureDeviceService() {
    for (int i = 0; i < mNumberOfGestureDevices; i++) {
        if (mBusy[i]) {
            LOGE("gesture device %d is still in use in destructor!", i);
        }
    }

    gGestureDeviceService = NULL;
}

int32_t GestureDeviceService::getNumberOfGestureDevices() {
    return mNumberOfGestureDevices;
}

sp<IGestureDevice> GestureDeviceService::connect(
        const sp<IGestureDeviceClient>& gestureClient, int v_Id) {
    int callingPid = getCallingPid();
    sp<GestureDeviceHardwareInterface> hardware = NULL;

    LOG1("GestureDeviceService::connect E (pid %d, id %d)", callingPid, v_Id);

    if (!mModule) {
        LOGE("gesture HAL module not loaded");
        return NULL;
    }

    sp<Client> client;
    if (v_Id < 0 || v_Id >= mNumberOfGestureDevices) {
        LOGE("GestureDeviceService::connect X (pid %d) rejected (invalid v_Id %d).",
            callingPid, v_Id);
        return NULL;
    }

    Mutex::Autolock lock(mServiceLock);
    if (mClient[v_Id] != 0) {
        client = mClient[v_Id].promote();
        if (client != 0) {
            if (gestureClient->asBinder() == client->getGestureDeviceClient()->asBinder()) {
                LOG1("GestureDeviceService::connect X (pid %d) (the same client)",
                    callingPid);
                return client;
            } else {
                LOGW("GestureDeviceService::connect X (pid %d) rejected (existing client).",
                    callingPid);
                return NULL;
            }
        }
        mClient[v_Id].clear();
    }

    if (mBusy[v_Id]) {
        LOGW("GestureDeviceService::connect X (pid %d) rejected"
             " (gesture device %d is still busy).", callingPid, v_Id);
        return NULL;
    }

    char gesture_device_name[10];
    snprintf(gesture_device_name, sizeof(gesture_device_name), "%d", v_Id);

    hardware = new GestureDeviceHardwareInterface(gesture_device_name);
    if (hardware->initialize(&mModule->common) != OK) {
        hardware.clear();
        return NULL;
    }

    client = new Client(this, gestureClient, hardware, v_Id, callingPid);
    mClient[v_Id] = client;
    LOG1("GestureDeviceService::connect X");
    return client;
}

void GestureDeviceService::removeClient(const sp<IGestureDeviceClient>& gestureClient) {
    int callingPid = getCallingPid();
    LOG1("GestureDeviceService::removeClient E (pid %d)", callingPid);

    for (int i = 0; i < mNumberOfGestureDevices; i++) {
        // Declare this before the lock to make absolutely sure the
        // destructor won't be called with the lock held.
        sp<Client> client;

        Mutex::Autolock lock(mServiceLock);

        // This happens when we have already disconnected (or this is
        // just another unused camera).
        if (mClient[i] == 0) continue;

        // Promote mClient. It can fail if we are called from this path:
        // Client::~Client() -> disconnect() -> removeClient().
        client = mClient[i].promote();

        if (client == 0) {
            mClient[i].clear();
            continue;
        }

        if (gestureClient->asBinder() == client->getGestureDeviceClient()->asBinder()) {
            // Found our gesture device, clear and leave.
            LOG1("removeClient: clear gesture device %d", i);
            mClient[i].clear();
            break;
        }
    }

    LOG1("GestureDeviceService::removeClient X (pid %d)", callingPid);
}

sp<GestureDeviceService::Client> GestureDeviceService::getClientById(int v_Id) {
    if (v_Id < 0 || v_Id >= mNumberOfGestureDevices) return NULL;
    return mClient[v_Id].promote();
}

status_t GestureDeviceService::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags) {
    return BnGestureDeviceService::onTransact(code, data, reply, flags);
}

// The reason we need this busy bit is a new GestureDeviceService::connect() request
// may come in while the previous Client's destructor has not been run or is
// still running. If the last strong reference of the previous Client is gone
// but the destructor has not been finished, we should not allow the new Client
// to be created because we need to wait for the previous Client to tear down
// the hardware first.
void GestureDeviceService::setGestureDeviceBusy(int v_Id) {
    android_atomic_write(1, &mBusy[v_Id]);
}

void GestureDeviceService::setGestureDeviceFree(int v_Id) {
    android_atomic_write(0, &mBusy[v_Id]);
}

// ----------------------------------------------------------------------------

GestureDeviceService::Client::Client(const sp<GestureDeviceService>& gestureService,
        const sp<IGestureDeviceClient>& gestureClient,
        const sp<GestureDeviceHardwareInterface>& hardware,
        int v_Id, int clientPid) {
    int callingPid = getCallingPid();
    LOG1("Client::Client E (pid %d)", callingPid);

    mGestureDeviceService = gestureService;
    mGestureDeviceClient = gestureClient;
    mHardware = hardware;
    mGestureId = v_Id;
    mClientPid = clientPid;
    mHardware->setCallbacks(notifyCallback,
                            dataCallback,
                            (void *)v_Id,
                            true);

    gestureService->setGestureDeviceBusy(v_Id);
    LOG1("Client::Client X (pid %d)", callingPid);
}

// tear down the client
GestureDeviceService::Client::~Client() {
    int callingPid = getCallingPid();
    LOG1("Client::~Client E (pid %d, this %p)", callingPid, this);

    // set mClientPid to let disconnet() tear down the hardware
    mClientPid = callingPid;
    disconnect();
    LOG1("Client::~Client X (pid %d, this %p)", callingPid, this);
}

// ----------------------------------------------------------------------------

status_t GestureDeviceService::Client::checkPid() const {
    int callingPid = getCallingPid();
    if (callingPid == mClientPid) return NO_ERROR;

    LOGW("attempt to use a locked camera from a different process"
         " (old pid %d, new pid %d)", mClientPid, callingPid);
    return EBUSY;
}

status_t GestureDeviceService::Client::checkPidAndHardware() const {
    status_t result = checkPid();
    if (result != NO_ERROR) return result;
    if (mHardware == 0) {
        LOGE("attempt to use a camera after disconnect() (pid %d)", getCallingPid());
        return INVALID_OPERATION;
    }
    return NO_ERROR;
}

// connect a new client to the gesture device
status_t GestureDeviceService::Client::connect(const sp<IGestureDeviceClient>& client) {
    int callingPid = getCallingPid();
    LOG1("connect E (pid %d)", callingPid);
    Mutex::Autolock lock(mLock);

    if (mClientPid != 0 && checkPid() != NO_ERROR) {
        LOGW("Tried to connect to a locked camera (old pid %d, new pid %d)",
                mClientPid, callingPid);
        return EBUSY;
    }

    if (mGestureDeviceClient != 0 && (client->asBinder() == mGestureDeviceClient->asBinder())) {
        LOG1("Connect to the same client");
        return NO_ERROR;
    }

    mClientPid = callingPid;
    mGestureDeviceClient = client;

    LOG1("connect X (pid %d)", callingPid);
    return NO_ERROR;
}

void GestureDeviceService::Client::disconnect() {
    int callingPid = getCallingPid();
    LOG1("disconnect E (pid %d)", callingPid);
    Mutex::Autolock lock(mLock);

    if (checkPid() != NO_ERROR) {
        LOGW("different client - don't disconnect");
        return;
    }

    if (mClientPid <= 0) {
        LOG1("camera is unlocked (mClientPid = %d), don't tear down hardware", mClientPid);
        return;
    }

    // Make sure disconnect() is done once and once only, whether it is called
    // from the user directly, or called by the destructor.
    if (mHardware == 0) return;

    LOG1("hardware teardown");
    mHardware->setCallbacks(notifyCallback,
                            dataCallback,
                            (void *)mGestureId,
                            false);
    mHardware->stopGesture();
    mHardware.clear();

    mGestureDeviceService->removeClient(mGestureDeviceClient);
    mGestureDeviceService->setGestureDeviceFree(mGestureId);

    LOG1("disconnect X (pid %d)", callingPid);
}

// start gesture
status_t GestureDeviceService::Client::startGesture() {
    LOG1("startGesture (pid %d)", getCallingPid());
    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;
    return mHardware->startGesture();
}

// stop gesture
void GestureDeviceService::Client::stopGesture() {
    LOG1("stopGesture (pid %d)", getCallingPid());
    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) return;

    mHardware->stopGesture();
}

// set parameters - key/value pairs
status_t GestureDeviceService::Client::setParameters(const String8& params) {
    LOGE("setParameters (pid %d) (%s)", getCallingPid(), params.string());

    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    return mHardware->setParameters(params.string());
}

// get parameters - key/value pairs
String8 GestureDeviceService::Client::getParameters() const {
    Mutex::Autolock lock(mLock);
    if (checkPidAndHardware() != NO_ERROR) return String8();

    String8 param = mHardware->getParameters();
    LOGE("GestureDeviceService::getParameters (%s)", param.string());
    return param;
}

status_t GestureDeviceService::Client::sendCommand(int32_t cmd, int32_t arg1, int32_t arg2) {
    LOG1("sendCommand (pid %d)", getCallingPid());
    Mutex::Autolock lock(mLock);
    status_t result = checkPidAndHardware();
    if (result != NO_ERROR) return result;

    return mHardware->sendCommand(cmd, arg1, arg2);
}

// ----------------------------------------------------------------------------

// Converts from a raw pointer to the client to a strong pointer during a
// hardware callback. This requires the callbacks only happen when the client
// is still alive.
sp<GestureDeviceService::Client> GestureDeviceService::Client::getClientFromCookie(void* user) {
    sp<Client> client = gGestureDeviceService->getClientById((int) user);

    // This could happen if the Client is in the process of shutting down (the
    // last strong reference is gone, but the destructor hasn't finished
    // stopping the hardware).
    if (client == 0) return NULL;

    // The checks below are not necessary and are for debugging only.
    if (client->mGestureDeviceService.get() != gGestureDeviceService) {
        LOGE("mismatch service!");
        return NULL;
    }

    if (client->mHardware == 0) {
        LOGE("mHardware == 0: callback after disconnect()?");
        return NULL;
    }

    return client;
}

void GestureDeviceService::Client::notifyCallback(int32_t msgType, int32_t ext1,
        int32_t ext2, void* user) {
    LOG2("notifyCallback(%d)", msgType);

    sp<Client> client = getClientFromCookie(user);
    if (client == 0) return;

    client->handleGenericNotify(msgType, ext1, ext2);
}

void GestureDeviceService::Client::dataCallback(gesture_result_t* gs_results,
                                               void* user) {
    LOG2("dataCallback");

    sp<Client> client = getClientFromCookie(user);
    if (client == 0) return;

    if (gs_results == 0) {
        LOGE("Null data returned in data callback");
        client->handleGenericNotify(GESTURE_MSG_ERROR, GESTURE_ERROR_UNKNOWN, 0);
        return;
    }

    client->handleGenericData(gs_results);
}

void GestureDeviceService::Client::handleGenericNotify(int32_t msgType,
    int32_t ext1, int32_t ext2) {
    sp<IGestureDeviceClient> c = mGestureDeviceClient;
    mLock.unlock();
    if (c != 0) {
        c->notifyCallback(msgType, ext1, ext2);
    }
}

void GestureDeviceService::Client::handleGenericData(gesture_result_t* gs_results) {
    sp<IGestureDeviceClient> c = mGestureDeviceClient;
    mLock.unlock();
    if (c != 0) {
        c->dataCallback(gs_results);
    }
}

static const int kDumpLockRetries = 50;
static const int kDumpLockSleep = 60000;

static bool tryLock(Mutex& mutex)
{
    bool locked = false;
    for (int i = 0; i < kDumpLockRetries; ++i) {
        if (mutex.tryLock() == NO_ERROR) {
            locked = true;
            break;
        }
        usleep(kDumpLockSleep);
    }
    return locked;
}

status_t GestureDeviceService::dump(int fd, const Vector<String16>& args) {
    static const char* kDeadlockedString = "GestureDeviceService may be deadlocked\n";

    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    if (checkCallingPermission(String16("android.permission.DUMP")) == false) {
        snprintf(buffer, SIZE, "Permission Denial: "
                "can't dump GestureDeviceService from pid=%d, uid=%d\n",
                getCallingPid(),
                getCallingUid());
        result.append(buffer);
        write(fd, result.string(), result.size());
    } else {
        bool locked = tryLock(mServiceLock);
        // failed to lock - GestureDeviceService is probably deadlocked
        if (!locked) {
            String8 result(kDeadlockedString);
            write(fd, result.string(), result.size());
        }

        bool hasClient = false;
        for (int i = 0; i < mNumberOfGestureDevices; i++) {
            sp<Client> client = mClient[i].promote();
            if (client == 0) continue;
            hasClient = true;
            sprintf(buffer, "Client[%d] (%p) PID: %d\n",
                    i,
                    client->getGestureDeviceClient()->asBinder().get(),
                    client->mClientPid);
            result.append(buffer);
            write(fd, result.string(), result.size());
            client->mHardware->dump(fd, args);
        }
        if (!hasClient) {
            result.append("No camera client yet.\n");
            write(fd, result.string(), result.size());
        }

        if (locked) mServiceLock.unlock();

        // change logging level
        int n = args.size();
        for (int i = 0; i + 1 < n; i++) {
            if (args[i] == String16("-v")) {
                String8 levelStr(args[i+1]);
                int level = atoi(levelStr.string());
                sprintf(buffer, "Set Log Level to %d", level);
                result.append(buffer);
                setLogLevel(level);
            }
        }
    }
    return NO_ERROR;
}

}; // namespace android
