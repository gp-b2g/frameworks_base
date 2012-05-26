/*
 * Copyright (C) 2012 Code Aurora Forum
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
/*--------------------------------------------------------------------------
Copyright (c) 2012 Code Aurora Forum. All rights reserved.
--------------------------------------------------------------------------*/
#ifndef POSTPROC_NativeWindow_H_
#define POSTPROC_NativeWindow_H_

#include <ui/egl/android_natives.h>
#include <utils/RefBase.h>
#include <stdarg.h>
#include <utils/Vector.h>
#include <utils/List.h>
#include <utils/threads.h>

namespace android {

enum {
    POST_PROC_NATIVE_WINDOW_SET_BUFFER_COUNT        = 0x10000000,
    POST_PROC_NATIVE_WINDOW_UPDATE_BUFFERS_GEOMETRY = 0x20000000,
    POST_PROC_NATIVE_WINDOW_SET_BUFFERS_SIZE        = 0x40000000,
    POST_PROC_NATIVE_WINDOW_TOGGLE_MODE             = 0x80000000,
};

class PostProcNativeWindow
    : public EGLNativeBase<ANativeWindow, PostProcNativeWindow, RefBase>
{

public:
    PostProcNativeWindow(const sp<ANativeWindow> &nativeWindow);
    int postProc_cancelBuffer(ANativeWindowBuffer* buffer);
    int postProc_dequeueBuffer(ANativeWindowBuffer** buffer);
    int postProc_perform(int operation, ...);

protected:
    virtual ~PostProcNativeWindow();

private:
    // ANativeWindow hooks
    static int hook_cancelBuffer(ANativeWindow* window, ANativeWindowBuffer* buffer);
    static int hook_dequeueBuffer(ANativeWindow* window, ANativeWindowBuffer** buffer);
    static int hook_lockBuffer(ANativeWindow* window, ANativeWindowBuffer* buffer);
    static int hook_perform(ANativeWindow* window, int operation, ...);
    static int hook_query(const ANativeWindow* window, int what, int* value);
    static int hook_queueBuffer(ANativeWindow* window, ANativeWindowBuffer* buffer);
    static int hook_setSwapInterval(ANativeWindow* window, int interval);

    int parsePerformOperation(int operation, va_list args);
    int dispatchPerformQcomOperation(int operation, va_list args);
    int dispatchConnect(va_list args);
    int dispatchDisconnect(va_list args);
    int dispatchSetBufferCount(va_list args);
    int dispatchSetBuffersGeometry(va_list args);
    int dispatchSetBuffersDimensions(va_list args);
    int dispatchSetBuffersFormat(va_list args);
    int dispatchSetScalingMode(va_list args);
    int dispatchSetBuffersTransform(va_list args);
    int dispatchSetBuffersTimestamp(va_list args);
    int dispatchSetCrop(va_list args);
    int dispatchSetUsage(va_list args);
    int dispatchLock(va_list args);
    int dispatchUnlockAndPost(va_list args);
    int performQcomOperation(int operation, int arg1, int arg2, int arg3);
    int getNumberOfArgsForOperation(int operation);

    int handleDequeueBuffer(ANativeWindowBuffer** buffer, bool postProc);
    int handleCancelBuffer(ANativeWindowBuffer* buffer, bool postProc);
    int handleQueueBuffer(ANativeWindowBuffer* buffer);

    void callDequeueBuffer();
    bool checkForPostProcBuffer(ANativeWindowBuffer *buf);

private:
    Vector<ANativeWindowBuffer*> mPostProcBuffers;
    Vector<ANativeWindowBuffer*> mNormalBuffers;

    List<ANativeWindowBuffer*> mFreePostProcBuffers;
    List<ANativeWindowBuffer*> mFreeNormalBuffers;

    sp<ANativeWindow> mNativeWindow;
    size_t mPostProcBufferSize;
    size_t mPostProcBufferCount;
    size_t mNormalBufferCount;
    Mutex mLock;

    bool mInPostProcMode;
    size_t mNumNormalBuffersWithNativeWindow;
    bool mDqGotAnError;
    int mDqError;
    int mMinUndequeuedBufs;
};

}

#endif  // POSTPROC_NativeWindow_H_
