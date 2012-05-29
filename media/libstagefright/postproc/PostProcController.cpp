/*
 * Copyright (c) 2012, Code Aurora Forum. All rights reserved.
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
/*--------------------------------------------------------------------------
Copyright (c) 2012, Code Aurora Forum. All rights reserved.
--------------------------------------------------------------------------*/
//#define LOG_NDEBUG 0
#define LOG_TAG "PostProcController"
#include <utils/Log.h>
#include <PostProcController.h>
#include <PostProc.h>

namespace android {

PostProcController::PostProcController()
{
    mPostProcModules.clear();
    mDoPostProcessing = false;
    mToggleSignalPending = false;
    mCurrentIndex = 0;
    mNumPostProcModules = 0;
}

PostProcController::PostProcController(Vector<sp<PostProc > > &postProcModules)
{
    mPostProcModules = postProcModules;
    mDoPostProcessing = false;
    mToggleSignalPending = false;
    mCurrentIndex = 0;
    mNumPostProcModules = mPostProcModules.size();
}

PostProcController::~PostProcController()
{
    Mutex::Autolock autoLock(mLock);
    LOGV("%s:  begin", __func__);
    mPostProcModules.clear();
}

void PostProcController::signalMessageReturned(PostProcMessage *msg)
{
    Mutex::Autolock autoLock(mLock);
    LOGV("%s:  begin", __func__);

    mCurrentIndex++;
    if ((mCurrentIndex < mNumPostProcModules) && (msg->isProcessed())) {
        sendSignal();
    } else {
        mToggleSignalPending = false;
    }

    msg->setObserver(NULL);
    msg->release();
}

void PostProcController::checkToggleIssued()
{
    Mutex::Autolock autoLock(mLock);
    LOGV("%s:  begin", __func__);

    if (mToggleSignalPending) {
        LOGV("Previous signal pending\n");
        return;
    }

    char value[PROPERTY_VALUE_MAX];
    bool doConversion = false;
    if (property_get("VideoPostProc.InPostProcMode", value, 0) > 0 && atoi(value) > 0){
        doConversion = true;
    }
    if ((mDoPostProcessing != doConversion) && (mNumPostProcModules > 0)) {
        LOGV("Switching to %s\n", doConversion ? "Post Process" : "Normal");
        mDoPostProcessing = doConversion;
        mCurrentIndex = 0;
        mToggleSignalPending = true;
        LOGV("Sending signal\n");
        sendSignal();
    }
}

// helper function
void PostProcController::sendSignal()
{
    LOGV("%s:  begin", __func__);

    sp<PostProc> postProc = mPostProcModules.itemAt(mCurrentIndex);
    PostProcMessage * msg = new PostProcMessage;
    msg->setObserver(this);
    if (postProc->signalMessage(msg) != OK) {
        LOGV("Recoverable error : Send signal failed\n");
        msg->setObserver(NULL);
        msg->release();
        mToggleSignalPending = false;
    }
}

}
