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

#ifndef POSTPROC_CONTROLLER_H_
#define POSTPROC_CONTROLLER_H_

#include <utils/threads.h>
#include <utils/Vector.h>
#include <include/postproc/PostProcControllerInterface.h>

namespace android {

struct PostProc;

class PostProcController : public PostProcMessageObserver {

public:
    PostProcController();
    PostProcController(Vector<sp<PostProc > > &postProcModules);
    void checkToggleIssued();
    ~PostProcController();

protected:
    virtual void signalMessageReturned(PostProcMessage *msg);

private:
    void sendSignal();

private:
    Vector<sp<PostProc > > mPostProcModules;
    bool mDoPostProcessing;
    Mutex mLock;
    bool mToggleSignalPending;
    int mCurrentIndex;
    int mNumPostProcModules;
};

}

#endif  // POSTPROC_CONTROLLER_H_
