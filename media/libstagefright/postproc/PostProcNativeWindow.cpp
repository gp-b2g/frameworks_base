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
//#define LOG_NDEBUG 0
#define LOG_TAG "PostProcNativeWindow"
#include <PostProcNativeWindow.h>
#include <utils/Log.h>
#include <MediaDebug.h>
#include <Errors.h>
#include <qcom_ui.h>

namespace android {

PostProcNativeWindow::PostProcNativeWindow(const sp<ANativeWindow> &nativeWindow)
{
    LOGV("%s:  begin", __func__);

    ANativeWindow::setSwapInterval  = hook_setSwapInterval;
    ANativeWindow::dequeueBuffer    = hook_dequeueBuffer;
    ANativeWindow::cancelBuffer     = hook_cancelBuffer;
    ANativeWindow::lockBuffer       = hook_lockBuffer;
    ANativeWindow::queueBuffer      = hook_queueBuffer;
    ANativeWindow::query            = hook_query;
    ANativeWindow::perform          = hook_perform;

    mNativeWindow = nativeWindow;
    mNormalBufferCount = 0;
    mPostProcBufferCount = 0;
    mPostProcBufferSize = 0;
    mNormalBuffers.clear();
    mFreeNormalBuffers.clear();
    mPostProcBuffers.clear();
    mFreePostProcBuffers.clear();
    mInPostProcMode = false;
    mNumNormalBuffersWithNativeWindow = 0;
    mDqGotAnError = false;
    mDqError = 0;
    mMinUndequeuedBufs = 0;
    mNativeWindow->query(mNativeWindow.get(),
            NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS, &mMinUndequeuedBufs);
}

int PostProcNativeWindow::postProc_cancelBuffer(ANativeWindowBuffer* buffer)
{
    LOGV("%s:  begin", __func__);
    return handleCancelBuffer(buffer, true);
}

int PostProcNativeWindow::postProc_dequeueBuffer(ANativeWindowBuffer** buffer)
{
    LOGV("%s:  begin", __func__);
    return handleDequeueBuffer(buffer, true);
}

int PostProcNativeWindow::postProc_perform(int operation, ...)
{
    LOGV("%s:  begin", __func__);

    int res = NO_ERROR;
    int w,h,f;
    va_list args;
    va_start(args, operation);
    switch (operation) {
        case POST_PROC_NATIVE_WINDOW_SET_BUFFER_COUNT:
            mPostProcBufferCount = va_arg(args, int);
            break;
        case POST_PROC_NATIVE_WINDOW_SET_BUFFERS_SIZE:
            mPostProcBufferSize = va_arg(args, int);
            break;
        case POST_PROC_NATIVE_WINDOW_UPDATE_BUFFERS_GEOMETRY:
            w = va_arg(args, int);
            h = va_arg(args, int);
            f = va_arg(args, int);
            mNativeWindow->perform(mNativeWindow.get(),
                                   NATIVE_WINDOW_UPDATE_BUFFERS_GEOMETRY,
                                   w, h, f);
            break;
        case POST_PROC_NATIVE_WINDOW_TOGGLE_MODE:
            mInPostProcMode = mInPostProcMode ? false : true;
            break;
        default:
            LOGE("Post Proc perform operation not supported :%d\n", operation);
            break;
    }
    return res;
}

PostProcNativeWindow::~PostProcNativeWindow()
{
    LOGV("%s:  begin", __func__);
    mNormalBuffers.clear();
    mFreeNormalBuffers.clear();
    mPostProcBuffers.clear();
    mFreePostProcBuffers.clear();
}

int PostProcNativeWindow::hook_setSwapInterval(ANativeWindow* window, int interval)
{
    LOGV("%s:  begin", __func__);
    PostProcNativeWindow* ppNW = getSelf(window);
    return ppNW->mNativeWindow->setSwapInterval(ppNW->mNativeWindow.get(), interval);
}

int PostProcNativeWindow::hook_dequeueBuffer(ANativeWindow* window, ANativeWindowBuffer** buffer)
{
    LOGV("%s:  begin", __func__);
    PostProcNativeWindow* ppNW = getSelf(window);
    return ppNW->handleDequeueBuffer(buffer, false);
}

int PostProcNativeWindow::hook_cancelBuffer(ANativeWindow* window, ANativeWindowBuffer* buffer)
{
    LOGV("%s:  begin", __func__);
    PostProcNativeWindow* ppNW = getSelf(window);
    return ppNW->handleCancelBuffer(buffer, false);
}

int PostProcNativeWindow::hook_lockBuffer(ANativeWindow* window, ANativeWindowBuffer* buffer)
{
    LOGV("%s:  begin", __func__);
    PostProcNativeWindow* ppNW = getSelf(window);
    return ppNW->mNativeWindow->lockBuffer(ppNW->mNativeWindow.get(), buffer);
}

int PostProcNativeWindow::hook_queueBuffer(ANativeWindow* window, ANativeWindowBuffer* buffer)
{
    LOGV("%s:  begin", __func__);
    PostProcNativeWindow* ppNW = getSelf(window);
    return ppNW->handleQueueBuffer(buffer);
}

int PostProcNativeWindow::hook_query(const ANativeWindow* window, int what, int* value)
{
    LOGV("%s:  begin", __func__);
    const PostProcNativeWindow* ppNW = getSelf(window);
    return ppNW->mNativeWindow->query(ppNW->mNativeWindow.get(), what, value);
}

int PostProcNativeWindow::hook_perform(ANativeWindow* window, int operation, ...)
{
    LOGV("%s:  begin", __func__);
    PostProcNativeWindow* ppNW = getSelf(window);
    va_list args;
    va_start(args, operation);
    return ppNW->parsePerformOperation(operation, args);
}

int PostProcNativeWindow::parsePerformOperation(int operation, va_list args)
{
    int res = NO_ERROR;
    switch (operation) {
        case NATIVE_WINDOW_CONNECT:
            // deprecated. must return NO_ERROR.
            break;
        case NATIVE_WINDOW_DISCONNECT:
            // deprecated. must return NO_ERROR.
            break;
        case NATIVE_WINDOW_SET_USAGE:
            res = dispatchSetUsage(args);
            break;
        case NATIVE_WINDOW_SET_CROP:
            res = dispatchSetCrop(args);
            break;
        case NATIVE_WINDOW_SET_BUFFER_COUNT:
            res = dispatchSetBufferCount(args);
            break;
        case NATIVE_WINDOW_SET_BUFFERS_GEOMETRY:
            res = dispatchSetBuffersGeometry(args);
            break;
        case NATIVE_WINDOW_SET_BUFFERS_TRANSFORM:
            res = dispatchSetBuffersTransform(args);
            break;
        case NATIVE_WINDOW_SET_BUFFERS_TIMESTAMP:
            res = dispatchSetBuffersTimestamp(args);
            break;
        case NATIVE_WINDOW_SET_BUFFERS_DIMENSIONS:
            res = dispatchSetBuffersDimensions(args);
            break;
        case NATIVE_WINDOW_SET_BUFFERS_FORMAT:
            res = dispatchSetBuffersFormat(args);
            break;
        case NATIVE_WINDOW_LOCK:
            res = dispatchLock(args);
            break;
        case NATIVE_WINDOW_UNLOCK_AND_POST:
            res = dispatchUnlockAndPost(args);
            break;
        case NATIVE_WINDOW_SET_SCALING_MODE:
            res = dispatchSetScalingMode(args);
            break;
        case NATIVE_WINDOW_API_CONNECT:
            res = dispatchConnect(args);
            break;
        case NATIVE_WINDOW_API_DISCONNECT:
            res = dispatchDisconnect(args);
            break;
        default:
            res = dispatchPerformQcomOperation(operation, args);
            break;
    }
    return res;
}

int PostProcNativeWindow::dispatchPerformQcomOperation(int operation, va_list args)
{
    int num_args = getNumberOfArgsForOperation(operation);
    if (-EINVAL == num_args) {
        LOGE("%s: invalid arguments for operation (operation = 0x%x)",
             __FUNCTION__, operation);
        return -1;
    }

    LOGV("%s: num_args = %d", __FUNCTION__, num_args);
    size_t arg[3] = {0, 0, 0};
    for (int i = 0; i < num_args; i++) {
        arg[i] = va_arg(args, size_t);
    }

    if (operation == NATIVE_WINDOW_SET_BUFFERS_SIZE) {
        arg[0] = (arg[0] < mPostProcBufferSize) ? mPostProcBufferSize : arg[0];
    }
    return performQcomOperation(operation, arg[0], arg[1], arg[2]);
}

int PostProcNativeWindow::performQcomOperation(int operation, int arg1,
                                               int arg2, int arg3)
{
    return mNativeWindow->perform(mNativeWindow.get(), operation, arg1, arg2, arg3);
}

int PostProcNativeWindow::dispatchConnect(va_list args)
{
    int api = va_arg(args, int);
    return mNativeWindow->perform(mNativeWindow.get(), NATIVE_WINDOW_API_CONNECT, api);
}

int PostProcNativeWindow::dispatchDisconnect(va_list args)
{
    int api = va_arg(args, int);
    return mNativeWindow->perform(mNativeWindow.get(), NATIVE_WINDOW_API_DISCONNECT, api);
}

int PostProcNativeWindow::dispatchSetUsage(va_list args)
{
    int usage = va_arg(args, int);
    return mNativeWindow->perform(mNativeWindow.get(), NATIVE_WINDOW_SET_USAGE, usage);
}

int PostProcNativeWindow::dispatchSetCrop(va_list args)
{
    android_native_rect_t const* rect = va_arg(args, android_native_rect_t*);
    return mNativeWindow->perform(mNativeWindow.get(), NATIVE_WINDOW_SET_CROP, rect);
}

int PostProcNativeWindow::dispatchSetBufferCount(va_list args)
{
    size_t bufferCount = va_arg(args, size_t);
    mNormalBufferCount = bufferCount;
    mNumNormalBuffersWithNativeWindow = mNormalBufferCount;
    LOGV("Total buffer count is %d\n",mNormalBufferCount + mPostProcBufferCount);
    return mNativeWindow->perform(mNativeWindow.get(), NATIVE_WINDOW_SET_BUFFER_COUNT, mNormalBufferCount + mPostProcBufferCount);
}

int PostProcNativeWindow::dispatchSetBuffersGeometry(va_list args)
{
    int w = va_arg(args, int);
    int h = va_arg(args, int);
    int f = va_arg(args, int);
    return mNativeWindow->perform(mNativeWindow.get(), NATIVE_WINDOW_SET_BUFFERS_GEOMETRY, w, h, f);
}

int PostProcNativeWindow::dispatchSetBuffersDimensions(va_list args)
{
    int w = va_arg(args, int);
    int h = va_arg(args, int);
    return mNativeWindow->perform(mNativeWindow.get(), NATIVE_WINDOW_SET_BUFFERS_DIMENSIONS, w, h);
}

int PostProcNativeWindow::dispatchSetBuffersFormat(va_list args)
{
    int f = va_arg(args, int);
    return mNativeWindow->perform(mNativeWindow.get(), NATIVE_WINDOW_SET_BUFFERS_FORMAT, f);
}

int PostProcNativeWindow::dispatchSetScalingMode(va_list args)
{
    int m = va_arg(args, int);
    return mNativeWindow->perform(mNativeWindow.get(), NATIVE_WINDOW_SET_SCALING_MODE, m);
}

int PostProcNativeWindow::dispatchSetBuffersTransform(va_list args)
{
    int transform = va_arg(args, int);
    return mNativeWindow->perform(mNativeWindow.get(), NATIVE_WINDOW_SET_BUFFERS_TRANSFORM, transform);
}

int PostProcNativeWindow::dispatchSetBuffersTimestamp(va_list args)
{
    int64_t timestamp = va_arg(args, int64_t);
    return mNativeWindow->perform(mNativeWindow.get(), NATIVE_WINDOW_SET_BUFFERS_TIMESTAMP, timestamp);
}

int PostProcNativeWindow::dispatchLock(va_list args)
{
    ANativeWindow_Buffer* outBuffer = va_arg(args, ANativeWindow_Buffer*);
    ARect* inOutDirtyBounds = va_arg(args, ARect*);
    return mNativeWindow->perform(mNativeWindow.get(), NATIVE_WINDOW_LOCK, outBuffer, inOutDirtyBounds);
}

int PostProcNativeWindow::dispatchUnlockAndPost(va_list args)
{
    return mNativeWindow->perform(mNativeWindow.get(), NATIVE_WINDOW_UNLOCK_AND_POST, NULL);
}

int PostProcNativeWindow::getNumberOfArgsForOperation(int operation)
{
    int num_args = -EINVAL;
    switch(operation) {
        case NATIVE_WINDOW_SET_BUFFERS_SIZE:
            num_args = 1;
            break;
        case  NATIVE_WINDOW_UPDATE_BUFFERS_GEOMETRY:
            num_args = 3;
            break;
        case NATIVE_WINDOW_SET_PIXEL_ASPECT_RATIO:
            num_args = 2;
            break;
        default:
            LOGE("%s: invalid operation(0x%x)", __FUNCTION__, operation);
            break;
    };
    return num_args;
}

int PostProcNativeWindow::handleDequeueBuffer(ANativeWindowBuffer** buffer, bool postProc)
{
    Mutex::Autolock lock(mLock);

    int err = 0;

    if (mDqGotAnError) {
        return mDqError;
    }
    *buffer = NULL;
    if (postProc) {
        if (!mFreePostProcBuffers.empty()) {
            *buffer = *mFreePostProcBuffers.begin();
            LOGV("Found a buffer in free post proc q (%p)\n", (*buffer)->handle);
            mFreePostProcBuffers.erase(mFreePostProcBuffers.begin());
            return 0;
        } else {
            return NO_MEMORY; //signal to post proc to wait
        }
    } else {
        if (!mFreeNormalBuffers.empty()) {
            LOGV("Found a buffer in free normal q \n");
            *buffer = *mFreeNormalBuffers.begin();
            mFreeNormalBuffers.erase(mFreeNormalBuffers.begin());
            return 0;
        } else if (mNormalBuffers.size() < mNormalBufferCount) {
            err = mNativeWindow->dequeueBuffer(mNativeWindow.get(), buffer);
            if (err < 0) {
                LOGE("Initial dequeue for normal buffer failed\n");
                return err;
            }
            LOGV("Dqd normal buffer \n");
            mNormalBuffers.push(*buffer);
            mNumNormalBuffersWithNativeWindow--;
            // Once all the normal buffers have been dequeue, we will dequeue post proc buffers
            if (mNormalBuffers.size() == mNormalBufferCount) {
                while(mPostProcBuffers.size() < mPostProcBufferCount) {
                    ANativeWindowBuffer* buf;
                    err = mNativeWindow->dequeueBuffer(mNativeWindow.get(), &buf);
                    if (err < 0) {
                        LOGE("Initial dequeue for post proc buffer failed\n");
                        return err;
                    }
                    LOGV("Dqd post proc buffer \n");
                    mPostProcBuffers.push(buf);
                    mFreePostProcBuffers.push_back(buf);
                }
            }
            return 0;
        }
    }
    CHECK(!"Should not be here");
}

int PostProcNativeWindow::handleCancelBuffer(ANativeWindowBuffer* buffer, bool postProc)
{
    Mutex::Autolock lock(mLock);

    int err = 0;
    if (postProc) {
        CHECK(mInPostProcMode);
        err = mNativeWindow->cancelBuffer(mNativeWindow.get(), buffer);
        if (err < 0) {
            LOGE("Warning cancel normal buffer failed\n");
        }
        LOGV("Cancelling buffer (%p)\n", buffer->handle);
        callDequeueBuffer();
        return err;
    }

    if (!mInPostProcMode) {
        err = mNativeWindow->cancelBuffer(mNativeWindow.get(), buffer);
        if (err < 0) {
            LOGE("Warning cancel normal buffer failed\n");
        }
        LOGV("Normal buffer cancelled to NW\n");
        mNumNormalBuffersWithNativeWindow++;
        size_t numPostProcBuffersWithNativeWindow = (mPostProcBufferCount -  mFreePostProcBuffers.size());
        if (mNumNormalBuffersWithNativeWindow + numPostProcBuffersWithNativeWindow > mMinUndequeuedBufs) {
            callDequeueBuffer();
        }
    } else {
        mFreeNormalBuffers.push_back(buffer);
    }

    //When all normal buffers have been cancelled to PostProcNativeWindow or the actual NativeWindow, cancel all PostProc buffers back to actual NativeWindow.
    if (mFreeNormalBuffers.size() + mNumNormalBuffersWithNativeWindow == mNormalBufferCount) {
        LOGV("Normal buffers with native window : %d. free list size : %d, total buffer count: %d\n",mNumNormalBuffersWithNativeWindow, mFreeNormalBuffers.size(), mNormalBufferCount);

        while(!mFreeNormalBuffers.empty()) {
            LOGV("Cancel all normal buffers with us\n");
            err = mNativeWindow->cancelBuffer(mNativeWindow.get(), *mFreeNormalBuffers.begin());
            if (err < 0) {
                LOGE("Warning cancel normal buffer failed\n");
            }
            mFreeNormalBuffers.erase(mFreeNormalBuffers.begin());
            mNumNormalBuffersWithNativeWindow++;
        }
        mNormalBuffers.clear();
        LOGV("Cancel all post proc buffers as all normal buffers have been cancelled\n");
        while(!mFreePostProcBuffers.empty()) {
            err = mNativeWindow->cancelBuffer(mNativeWindow.get(), *mFreePostProcBuffers.begin());
            if (err < 0) {
                LOGE("Warning cancel post proc buffer failed\n");
            }
            mFreePostProcBuffers.erase(mFreePostProcBuffers.begin());
        }
        mPostProcBuffers.clear();
    }
    return err;
}

int PostProcNativeWindow::handleQueueBuffer(ANativeWindowBuffer* buffer)
{
    Mutex::Autolock lock(mLock);

    int err = 0;
    Vector<ANativeWindowBuffer *> *ppBuffers = &mPostProcBuffers;
    ANativeWindowBuffer * ppBuf = NULL;

    bool isPostProcBuffer = checkForPostProcBuffer(buffer);

    LOGV("Queue buffer (%p) \n", buffer->handle);
    err = mNativeWindow->queueBuffer(mNativeWindow.get(), buffer);
    if (err < 0) {
        LOGE("Queue buffer failed\n");
        return err;
    }

    if (!isPostProcBuffer) {
        mNumNormalBuffersWithNativeWindow++;
    }

    callDequeueBuffer();
    return err;
}

// callDequeueBuffer is always called as part of a handshake, when we give a buffer to NativeWindow we can take one back.
void PostProcNativeWindow::callDequeueBuffer()
{
    int err = 0;
    ANativeWindowBuffer * buf;

    err = mNativeWindow->dequeueBuffer(mNativeWindow.get(), &buf);
    if (err < 0) {
        LOGE("Dequeue after queue failed\n");
        mDqGotAnError = true;
        mDqError = err;
        return;
    }

    if (checkForPostProcBuffer(buf)) {
        LOGV("Dqd Post Proc buffer\n");
        mFreePostProcBuffers.push_back(buf);
        return;
    }

    // assumption that if you come here its a normal buffer
    LOGV("Dqd Normal buffer\n");
    mNumNormalBuffersWithNativeWindow--;
    mFreeNormalBuffers.push_back(buf);
}

bool PostProcNativeWindow::checkForPostProcBuffer(ANativeWindowBuffer *buf)
{
    Vector<ANativeWindowBuffer *> *ppBuffers = &mPostProcBuffers;
    ANativeWindowBuffer * ppBuf;

    ppBuf = NULL;
    for (size_t i = 0; i < ppBuffers->size(); ++i) {
        ppBuf = ppBuffers->editItemAt(i);
        if (ppBuf->handle == buf->handle) {
            return true;
        }
    }
    return false;
}

}
