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

#ifndef POSTPROC_CONTROLLER_INTERFACE_H_
#define POSTPROC_CONTROLLER_INTERFACE_H_

#include <media/stagefright/MediaDebug.h>

namespace android {

class PostProcMessage;

class PostProcMessageObserver {

public:
    PostProcMessageObserver() {}
    virtual ~PostProcMessageObserver() {}
    virtual void signalMessageReturned(PostProcMessage *msg) = 0;
};

class PostProcMessage {

public:
    PostProcMessage()
    {
        mObserver = NULL;
        mProcessed = false;
    }
    virtual ~PostProcMessage() {}
    void setObserver(PostProcMessageObserver *observer)
    {
        CHECK(observer == NULL || mObserver == NULL);
        mObserver = observer;
    }
    void release()
    {
        if (mObserver == NULL) {
            delete this;
            return;
        }
        mObserver->signalMessageReturned(this);
    }
    void processed()
    {
        mProcessed = true;
    }
    bool isProcessed()
    {
        return mProcessed;
    }
private:
    PostProcMessageObserver *mObserver;
    bool mProcessed;
};

class PostProcControllerInterface {

protected:
    virtual ~PostProcControllerInterface(){};
    virtual status_t signalMessage(PostProcMessage *msg) = 0;
};

}

#endif  // POSTPROC_CONTROLLER_INTERFACE_H_


