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

#ifndef POSTPROC_H_
#define POSTPROC_H_

#include <ui/Region.h>
#include <android/native_window.h>
#include <media/IOMX.h>
#include <MediaBuffer.h>
#include <MediaSource.h>
#include <utils/threads.h>
#include <pthread.h>
#include <PostProcNativeWindow.h>
#include <linux/ion.h>
#include <QComOMXMetadata.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <MetaData.h>
#include <MediaDebug.h>
#include <gralloc_priv.h>
#include <qcom_ui.h>
#include <dlfcn.h>
#include <utils/Log.h>
#include <PostProcControllerInterface.h>
#include <OMX_QCOMExtns.h>

#define ALIGN8K 8192
#define ALIGN4K 4096
#define ALIGN2K 2048
#define ALIGN128 128
#define ALIGN32 32
#define ALIGN16 16

#define ALIGN( num, to ) (((num) + (to-1)) & (~(to-1)))

struct OutputBuffer
{
    MediaBuffer * buffer;
    bool isPostProcBuffer;
};

typedef encoder_media_buffer_type post_proc_media_buffer_type;

namespace android {

struct PostProc : public MediaSource,
                  public MediaBufferObserver,
                  public PostProcControllerInterface {

public:
    virtual status_t Init(sp<MediaSource> decoder, sp<PostProcNativeWindow> nativeWindow, const sp<MetaData> &meta);

public: // from MediaSource
    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

    virtual status_t pause();

public: // from MediaBufferObserver
    void signalBufferReturned(MediaBuffer *buffer);

public: // from PostProcControllerInterface
    status_t signalMessage(PostProcMessage *msg);

protected:
    virtual ~PostProc();
    virtual status_t postProcessBuffer(MediaBuffer *inputBuffer, MediaBuffer *outputBuffer) = 0;
    virtual status_t setBufferInfo(const sp<MetaData> &meta) = 0;
    virtual bool postProcessingPossible() = 0;

protected:
    sp<MediaSource> mSource;
    sp<PostProcNativeWindow> mNativeWindow;
    int32_t mBufferSize;
    int32_t mNumBuffers;
    sp<MetaData> mOutputFormat;
    int32_t mHeight;
    int32_t mWidth;
    int32_t mSrcFormat;
    int32_t mDstFormat;
    int32_t mStride;
    int32_t mSlice;
    char * mName;
    int32_t mIonFd;

private:
    void createWorkerThread();
    void destroyWorkerThread();
    static void *threadEntry(void* me);
    void readLoop();

    // Funtions that are called from readLoop(need to acquire a lock):
    void checkForFirstFrame();
    bool needsPostProcessing();
    status_t findFreePostProcBuffer(MediaBuffer **buffer);
    void releasePostProcBuffer(MediaBuffer *buffer);
    void queueBuffer(MediaBuffer *buffer, bool isPostProcBuffer);

    void releaseInputBuffer(MediaBuffer *buffer); // does not need a lock

    // Helper functions that are called from other functions(that have a lock):
    status_t notifyNativeWindow();
    status_t dequeuePostProcBufferFromNativeWindow(MediaBuffer **buffer);
    status_t allocatePostProcBuffers();
    void flushPostProcBuffer(MediaBuffer *buffer);
    void releasePostProcBuffers();
    void releaseQueuedBuffers();
    int allocateIonBuffer(uint32_t size, int *handle);
    status_t getInputFormat();
    void updateInputFormat();

    enum State {
        LOADED,
        EXECUTING,
        SEEKING,
        PAUSED,
        ERROR,
        STOPPED
    };

private:
    pthread_t mThread;
    bool mDone;

    ReadOptions mReadOptions;
    status_t mReadError;

    Mutex mLock;
    Condition mOutputBufferCondition;
    Condition mPausedCondition;
    Condition mSeekCondition;
    Condition mPostProcBufferCondition;

    List<OutputBuffer *> mOutputBuffers;
    List<MediaBuffer *> mPostProcBuffers;

    bool mLastBufferWasPostProcessedInThread;
    bool mLastBufferWasPostProcessedInRead;
    int32_t mLastBufferFormat;
    State mState;

    bool mDoPostProcessing;
    PostProcMessage * mPostProcMsg;

    bool mFirstFrame;
    bool mPostProcessingEnabled;
};

}  // namespace android

#endif  // POSTPROC_H_

