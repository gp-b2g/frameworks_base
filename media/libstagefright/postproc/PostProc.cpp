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

//#define LOG_NDEBUG 0
#define LOG_TAG "PostProc"
#include <utils/Log.h>

#include <PostProc.h>

const static int64_t kPostProcBufferAvailableTimeOutNs = 3000000000LL;

namespace android {

#define POSTPROC_LOGI(x, ...) LOGI("[%s] "x, mName, ##__VA_ARGS__)
#define POSTPROC_LOGV(x, ...) LOGV("[%s] "x, mName, ##__VA_ARGS__)
#define POSTPROC_LOGE(x, ...) LOGE("[%s] "x, mName, ##__VA_ARGS__)

status_t PostProc::Init(sp<MediaSource> source, sp<PostProcNativeWindow> nativeWindow, const sp<MetaData> &meta)
{
    Mutex::Autolock autoLock(mLock);
    POSTPROC_LOGV("%s:  begin", __func__);

    mSource = source;
    CHECK(mSource.get());
    mNativeWindow = nativeWindow;
    mState = LOADED;

    mHeight = 0;
    mWidth = 0;
    mSrcFormat = 0;
    mDstFormat = 0;
    mStride = 0;
    mSlice = 0;

    status_t err = setBufferInfo(meta);
    if (err != OK) {
        POSTPROC_LOGE("Set buffer info failed\n");
        return err;
    }

    err = getInputFormat();
    if (err != OK) {
        POSTPROC_LOGE("Get input format failed\n");
        return err;
    }

    //as these three things can be accessed between init and start
    mDoPostProcessing = false;
    mPostProcMsg = NULL;
    mPostProcessingEnabled = true;
    return OK;
}

status_t PostProc::start(MetaData *params)
{
    Mutex::Autolock autoLock(mLock);
    POSTPROC_LOGV("%s:  begin", __func__);

    status_t err = OK;
    if (mState == ERROR) {
        return mReadError;
    }
    if (mState == EXECUTING) {
        return err;
    }
    if (mState == PAUSED) {
        err = mSource->start(params);
        if (err != OK) {
            POSTPROC_LOGE("source start failed while in paused failed\n");
            return err;
        }
        mState = EXECUTING;
        mPausedCondition.signal();
        return OK;
    }
    CHECK_EQ(mState, LOADED);
    mOutputBuffers.clear();
    mPostProcBuffers.clear();

    mLastBufferFormat = 0;
    mReadError = OK;
    mState = EXECUTING;
    mLastBufferWasPostProcessedInThread = false;
    mLastBufferWasPostProcessedInRead = false;
    mIonFd = -1;
    mFirstFrame = true;

    if(mNativeWindow != NULL) {
        err = notifyNativeWindow();
        if (err != OK) {
            POSTPROC_LOGE("Notify native window failed\n");
            return err;
        }
    }

    err = mSource->start(params);
    if (err != OK) {
        POSTPROC_LOGE("source start failed\n");
        return err;
    }

    if (mNativeWindow == NULL) {
        err = allocatePostProcBuffers();
        if (err != OK) {
            POSTPROC_LOGE("Could not allocate post proc buffers\n");
            return err;
        }
    }

    createWorkerThread();
    return OK;
}

status_t PostProc::stop()
{
    // check for state paused? or executing?
    bool callStart = false;
    POSTPROC_LOGV("%s:  begin", __func__);

    {
        Mutex::Autolock autoLock(mLock);
        if (mState == PAUSED) {
            callStart = true;
        }
    }

    if (callStart) {
        start();
    }

    {
        Mutex::Autolock autoLock(mLock);
        mDone = true;
        mState = STOPPED;
        releaseQueuedBuffers();
        mPostProcBufferCondition.signal();
    }

    POSTPROC_LOGV("Joining the worker thread\n");
    destroyWorkerThread();

    {
        Mutex::Autolock autoLock(mLock);
        CHECK(mOutputBuffers.empty());
        releasePostProcBuffers();
        if (mIonFd >= 0) { // 0 is a valid Fd
            close(mIonFd);
            mIonFd = -1;
        }
        if (mPostProcMsg) {
            mPostProcMsg->release();
            mPostProcMsg = NULL;
        }
    }
    POSTPROC_LOGV("Stopping source");
    return mSource->stop();
}

sp<MetaData> PostProc::getFormat()
{
    Mutex::Autolock autoLock(mLock);
    POSTPROC_LOGV("%s:  begin", __func__);

    return mOutputFormat;
}

status_t PostProc::read(MediaBuffer **buffer, const ReadOptions *options)
{
    Mutex::Autolock autoLock(mLock);
    POSTPROC_LOGV("%s:  begin", __func__);

    int64_t seekTimeUs;
    MediaSource::ReadOptions::SeekMode seekMode;

    if (mState == ERROR) {
        return mReadError;
    }
    if (options && options->getSeekTo(&seekTimeUs, &seekMode)) {
        POSTPROC_LOGV("ppread:  seeking");

        // store seek options for readLoop to pick up
        mReadOptions.setSeekTo(seekTimeUs, seekMode);
        mState = SEEKING;
        releaseQueuedBuffers();
        while (mState != EXECUTING) {
            mPostProcBufferCondition.signal();
            mSeekCondition.wait(mLock);
            POSTPROC_LOGV("Got out of seek lock condition\n");
        }
        CHECK(mOutputBuffers.empty());
    }

    while (mOutputBuffers.empty() && ((mState == EXECUTING) || (mState == PAUSED))) {
        POSTPROC_LOGV("ppread: Waiting for output buffers\n");
        status_t err = mOutputBufferCondition.waitRelative(mLock, kPostProcBufferAvailableTimeOutNs);
        if ((err != OK) && (mState != PAUSED)) {
            POSTPROC_LOGE("Timed out waiting for output buffers\n");
            mState = ERROR;
            mReadError = err;
            break;
        }
    }

    if (mState == ERROR) {
        if (mReadError == INFO_FORMAT_CHANGED) {
            POSTPROC_LOGE("Info format changed not supported\n");
            return UNKNOWN_ERROR;
        }
        return mReadError;
    }

    POSTPROC_LOGV("ppread: Got an output buffer\n");
    // Get the next buffer from the queue and return it
    OutputBuffer * outBuffer = *mOutputBuffers.begin();
    *buffer = outBuffer->buffer;
    if (mLastBufferWasPostProcessedInRead != outBuffer->isPostProcBuffer) {
        POSTPROC_LOGV("Switch happened from %s to %s\n",mLastBufferWasPostProcessedInRead ? "PostProcess" : "Normal", outBuffer->isPostProcBuffer ? "PostProcess" : "Normal");
        mPostProcMsg->processed();
        mPostProcMsg->release();
        mPostProcMsg = NULL;
    }

    if (outBuffer->isPostProcBuffer) {
        (*buffer)->setObserver(this);
        (*buffer)->add_ref();
    }
    mLastBufferWasPostProcessedInRead = outBuffer->isPostProcBuffer;
    delete outBuffer;
    mOutputBuffers.erase(mOutputBuffers.begin());

    int32_t ppBufFormat;
    CHECK((*buffer)->meta_data()->findInt32(kKeyColorFormat, &ppBufFormat));
    if ((mLastBufferFormat != ppBufFormat) && (mNativeWindow != NULL)) {
        POSTPROC_LOGV("update buffers geometry with old format : %d, new format :%d, stride : %d, slice : %d \n", mLastBufferFormat, ppBufFormat, mStride, mSlice);
        int err = mNativeWindow.get()->postProc_perform(POST_PROC_NATIVE_WINDOW_UPDATE_BUFFERS_GEOMETRY,
                                                        mStride, mSlice, ppBufFormat);
        CHECK_EQ(err, OK);
        mLastBufferFormat = ppBufFormat;
    }
    int64_t time;
    CHECK((*buffer)->meta_data()->findInt64(kKeyTime, &time));
    return OK;
}

status_t PostProc::pause()
{
    Mutex::Autolock autoLock(mLock);
    POSTPROC_LOGV("%s:  begin", __func__);

    if (mState == ERROR) {
        return mReadError;
    } else if (mState != EXECUTING) {
        return OK;
    }
    mState = PAUSED;

    POSTPROC_LOGV("Signalling post proc buffer condition\n");
    mPostProcBufferCondition.signal();
    POSTPROC_LOGV("now calling source pause\n");
    return mSource->pause();
}

void PostProc::signalBufferReturned(MediaBuffer *buffer)
{
    Mutex::Autolock autoLock(mLock);
    POSTPROC_LOGV("%s:  begin", __func__);

    flushPostProcBuffer(buffer);
    mPostProcBufferCondition.signal();
    return;
}

status_t PostProc::signalMessage(PostProcMessage *msg)
{
    Mutex::Autolock autoLock(mLock);
    POSTPROC_LOGV("%s:  begin", __func__);

    CHECK_EQ(mPostProcMsg, NULL);
    if (!mPostProcessingEnabled) {
        POSTPROC_LOGV("Post Processing disabled\n");
        return INVALID_OPERATION;
    }
    mDoPostProcessing = mDoPostProcessing ? false : true;
    mPostProcMsg = msg;
    return OK;
}

PostProc::~PostProc()
{
    POSTPROC_LOGV("%s:  begin", __func__);

    mOutputFormat.clear();
}

void PostProc::createWorkerThread()
{
    POSTPROC_LOGV("%s:  begin", __func__);

    mDone = false;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);
    pthread_create(&mThread, &attr, threadEntry, this);
    pthread_attr_destroy(&attr);
    return;
}

void PostProc::destroyWorkerThread()
{
    POSTPROC_LOGV("%s:  begin", __func__);

    void *dummy;
    pthread_join(mThread, &dummy);
    return;
}

void * PostProc::threadEntry(void* me)
{
    PostProc* pp = (PostProc *) me;
    LOGV("%s:  begin", __func__);

    if (pp == NULL) {
        return NULL;
    }
    pp->readLoop();
    return NULL;
}

void PostProc::readLoop()
{
    POSTPROC_LOGV("%s:  begin", __func__);

    status_t readError = OK;
    while (mDone != true) {
        MediaBuffer* buffer = NULL;
        ReadOptions optionsCopy;
        {
            Mutex::Autolock autolock(mLock);
            mReadError = readError;
            optionsCopy = ReadOptions(mReadOptions);
            mReadOptions.reset();

            if (mState == SEEKING) {
                POSTPROC_LOGV("ppreadloop:  got seeking before source read");
                mState = EXECUTING;
                mSeekCondition.signal();
            } else if (mState == PAUSED) {
                POSTPROC_LOGV("ppreadloop: In pause state\n");
                mPausedCondition.wait(mLock);
                POSTPROC_LOGV("ppreadloop: Out of pause state\n");
            }

            if (mReadError != OK) {
                mDone = true;
                mState = ERROR;
                mOutputBufferCondition.signal();
                return;
            }

            if (mState == STOPPED) {
                continue;
            }
        }

        readError = mSource->read(&buffer, &optionsCopy);

        if (readError != OK) {
            POSTPROC_LOGV("%s:  read returned 0x%X", __func__, readError);
            continue;
        } else {

            /*To disable post processing for formats not supported and detected on first read (like 3D, Interlaced, etc..)*/
            checkForFirstFrame();

            /* This block is to avoid post processing of the current buffer if seek is issued */
            {
                Mutex::Autolock autoLock(mLock);
                if (mState == SEEKING) {
                    POSTPROC_LOGV("ppreadloop:  got seeking after source read, drop the buffers\n");
                    releaseInputBuffer(buffer);
                    continue;
                }
            }

            int32_t inputFormat;
            if (!(buffer->meta_data()->findInt32(kKeyColorFormat, &inputFormat))) {
                POSTPROC_LOGV("Setting source input format as %d\n", mSrcFormat);
                buffer->meta_data()->setInt32(kKeyColorFormat, mSrcFormat);
            }

            MediaBuffer *bufferToQ = NULL;
            bool queuedBufferIsPostProcessed;
            if (needsPostProcessing()) {
                MediaBuffer * bufferPP = NULL;
                readError = findFreePostProcBuffer(&bufferPP);

                if ((readError != OK) || (bufferPP == NULL)) {
                    releaseInputBuffer(buffer);
                    continue;
                }

                if ((!mLastBufferWasPostProcessedInThread) && (mNativeWindow != NULL)) {
                    readError = mNativeWindow.get()->postProc_perform(POST_PROC_NATIVE_WINDOW_TOGGLE_MODE);
                    if (readError != OK) {
                        releaseInputBuffer(buffer);
                        releasePostProcBuffer(bufferPP);
                        continue;
                    }
                    mLastBufferWasPostProcessedInThread = true;
                }

                readError = postProcessBuffer(buffer, bufferPP);
                queuedBufferIsPostProcessed = true;

                (bufferPP)->meta_data()->setInt32(kKeyColorFormat, mDstFormat);
                int64_t time;
                if ((buffer)->meta_data()->findInt64(kKeyTime, &time)) {
                    (bufferPP)->meta_data()->setInt64(kKeyTime, time);
                }
                releaseInputBuffer(buffer);

                if (readError != OK) {
                    POSTPROC_LOGV("Release PP buffer as post processing gave an error\n");
                    releasePostProcBuffer(bufferPP);
                    continue;
                }
                bufferToQ = bufferPP;
            } else {
                bufferToQ = buffer;
                queuedBufferIsPostProcessed = false;
                if ((mLastBufferWasPostProcessedInThread) && (mNativeWindow != NULL)) {
                    readError = mNativeWindow.get()->postProc_perform(POST_PROC_NATIVE_WINDOW_TOGGLE_MODE);
                    if (readError != OK) {
                        releaseInputBuffer(buffer);
                        continue;
                    }
                    mLastBufferWasPostProcessedInThread = false;
                }
            }
            queueBuffer(bufferToQ, queuedBufferIsPostProcessed);
        }
    }
    return;
}

// funtions called from readLoop:

void PostProc::checkForFirstFrame()
{
    Mutex::Autolock autoLock(mLock);
    POSTPROC_LOGV("%s:  begin", __func__);

    if (mFirstFrame) {
        mFirstFrame = false;
        mPostProcessingEnabled = postProcessingPossible();
        if (!mPostProcessingEnabled && mPostProcMsg) {
            mPostProcMsg->release();
            mPostProcMsg = NULL;
        }
        updateInputFormat();
    }
    return;
}

bool PostProc::needsPostProcessing()
{
    Mutex::Autolock autoLock(mLock);
    POSTPROC_LOGV("%s:  begin", __func__);

    return (mDoPostProcessing & mPostProcessingEnabled);
}

status_t PostProc::findFreePostProcBuffer(MediaBuffer **buffer)
{
    Mutex::Autolock autoLock(mLock);
    POSTPROC_LOGV("%s:  begin", __func__);

    if (mNativeWindow != NULL) {
        return dequeuePostProcBufferFromNativeWindow(buffer);
    }

    while (mPostProcBuffers.empty()) {
        status_t err = mPostProcBufferCondition.waitRelative(mLock, kPostProcBufferAvailableTimeOutNs);
        POSTPROC_LOGV("Got out of the wait for post proc buffer\n");
        if (mState == SEEKING || mState == STOPPED || mState == PAUSED) {
            return OK;
        }
        if (err != OK) {
            POSTPROC_LOGE("Timed out waiting for post proc buffers\n");
            return err;
        }
    }
    *buffer = *mPostProcBuffers.begin();
    mPostProcBuffers.erase(mPostProcBuffers.begin());
    return OK;
}

void PostProc::releasePostProcBuffer(MediaBuffer *buffer)
{
    Mutex::Autolock autoLock(mLock);
    POSTPROC_LOGV("%s:  begin", __func__);

    CHECK(buffer);
    buffer->meta_data()->setInt32(kKeyRendered, 0);
    flushPostProcBuffer(buffer);
    return;
}

void PostProc::queueBuffer(MediaBuffer *buffer, bool isPostProcBuffer)
{
    Mutex::Autolock autoLock(mLock);
    POSTPROC_LOGV("%s:  begin", __func__);
    buffer->meta_data()->setInt32(kKeyRendered, 0);

    if ((mState == SEEKING) || (mState == STOPPED)) {
        POSTPROC_LOGV("queuebuffer:  In seek/stop state drop the buffer\n");
        if (isPostProcBuffer) {
            flushPostProcBuffer(buffer);
        } else {
            buffer->release();
        }
        return;
    }

    // check for pause or executing
    OutputBuffer * outBuffer = new OutputBuffer;
    outBuffer->buffer = buffer;
    outBuffer->isPostProcBuffer = isPostProcBuffer;
    mOutputBuffers.push_back(outBuffer);
    mOutputBufferCondition.signal();
    return;
}

void PostProc::releaseInputBuffer(MediaBuffer *buffer) // does not need a lock
{
    POSTPROC_LOGV("%s:  begin", __func__);

    CHECK(buffer);
    buffer->meta_data()->setInt32(kKeyRendered, 0);
    buffer->release();
    return;
}

// helper functions
status_t PostProc::notifyNativeWindow()
{
    // lock acquired in start
    POSTPROC_LOGV("%s:  start", __func__);

    status_t err = OK;
    err = mNativeWindow.get()->postProc_perform(POST_PROC_NATIVE_WINDOW_SET_BUFFER_COUNT, mNumBuffers);
    if (err != OK) {
        POSTPROC_LOGE("Post Proc set buffer count failed\n");
        return err;
    }
    return mNativeWindow.get()->postProc_perform(POST_PROC_NATIVE_WINDOW_SET_BUFFERS_SIZE, mBufferSize);
}

status_t PostProc::dequeuePostProcBufferFromNativeWindow(MediaBuffer **buffer)
{
    // lock acquired in findFreePostProcBuffer
    POSTPROC_LOGV("%s:  start", __func__);

    status_t err = OK;
    ANativeWindowBuffer* buf;
    bool handled = false;

    while (!handled) {
        err = mNativeWindow.get()->postProc_dequeueBuffer(&buf);
        if (err == NO_MEMORY && buf == NULL) {
            POSTPROC_LOGV("Wait for post proc buffer \n");
            status_t err = mPostProcBufferCondition.waitRelative(mLock, kPostProcBufferAvailableTimeOutNs);
            POSTPROC_LOGV("Got out of the wait for post proc buffer\n");
            if (mState == SEEKING || mState == STOPPED || mState == PAUSED) {
                return OK;
            }
            if (err != OK) {
                POSTPROC_LOGE("Timed out waiting for post proc buffers\n");
                return err;
            }
        } else if (err != OK) {
            POSTPROC_LOGE("dequeueBuffer failed: %s (%d)", strerror(-err), -err);
            return err;
        } else {
            handled = true;
        }
    }

    sp<GraphicBuffer> graphicBuffer(new GraphicBuffer(buf, false));
    *buffer = new MediaBuffer(graphicBuffer);
    return OK;
}

status_t PostProc::allocatePostProcBuffers()
{
    // lock acquired in start
    POSTPROC_LOGV("%s:  start", __func__);

    status_t err = OK;
    CHECK_EQ(mPostProcBuffers.size(), 0);

    if (mNativeWindow == NULL) {
        mIonFd = open("/dev/ion", O_RDONLY);
        if (mIonFd < 0) {
            POSTPROC_LOGE("Failed to open ion device, errno is %d", errno);
            err = mIonFd;
            return err;
        }

        for(int i = 0; i < mNumBuffers; i++) {
            post_proc_media_buffer_type * packet = new post_proc_media_buffer_type;
            packet->meta_handle = native_handle_create(1, 4);
            packet->buffer_type = kMetadataBufferTypeCameraSource; // fd followed by 4 ints
            native_handle_t * nh = const_cast<native_handle_t *>(packet->meta_handle);

            int fd = allocateIonBuffer(mBufferSize, &nh->data[3]);
            if (fd < 0) {
                POSTPROC_LOGE("Failed to allocate memory for linear buffers\n");
                err = fd;
                return err;
            }
            nh->data[0] = fd;
            nh->data[1] = 0;
            nh->data[2] = mBufferSize;
            POSTPROC_LOGV("handle is :%x\n",nh->data[3]);
            POSTPROC_LOGV("fd is %d\n",fd);

            void * data = mmap(NULL, mBufferSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
            if (data == MAP_FAILED) {
                POSTPROC_LOGE("Could not allocate ion buffer, errno is %s\n", strerror(errno));
                ion_handle * hnd = (ion_handle *)nh->data[3];
                ioctl(mIonFd, ION_IOC_FREE, &hnd);
                native_handle_delete(nh);
                delete packet;
                return -ENOMEM;
            }
            nh->data[4] = (int)data;
            MediaBuffer * buffer = new MediaBuffer((void *)packet, mBufferSize);
            POSTPROC_LOGV("allocated buffer of size = %d, fd = %d\n", mBufferSize, fd);
            mPostProcBuffers.push_back(buffer);
        }
        return err;
    }
    CHECK(!"Should not be here");
}

void PostProc::releasePostProcBuffers()
{
    // lock acquired in stop
    POSTPROC_LOGV("%s:  start", __func__);

    if (mNativeWindow == NULL) {
        POSTPROC_LOGV("buffer q size is %d, total number of buffers is %d\n",mPostProcBuffers.size(), mNumBuffers);
        CHECK_EQ(mPostProcBuffers.size(), mNumBuffers);
        while (!mPostProcBuffers.empty()) {

            MediaBuffer * buffer = *mPostProcBuffers.begin();
            encoder_media_buffer_type * packet = (encoder_media_buffer_type *)buffer->data();
            native_handle_t * nh = const_cast<native_handle_t *>(packet->meta_handle);
            ion_handle * hnd = (ion_handle *)nh->data[3];
            POSTPROC_LOGV("Release post proc buffer with handle : (%x)", nh->data[3]);
            if (munmap((void *)nh->data[4], nh->data[2]) == -1) {
                POSTPROC_LOGE("munmap failed\n");
            }
            int rc = ioctl(mIonFd, ION_IOC_FREE, &hnd);
            if (rc) {
                POSTPROC_LOGE("Free failed with %d!\n",rc);
            }
            native_handle_delete(nh);
            delete packet;
            buffer->setObserver(NULL);
            CHECK_EQ(buffer->refcount(), 0);
            buffer->release();
            mPostProcBuffers.erase(mPostProcBuffers.begin());
        }
        mPostProcBuffers.clear();
    }
    return;
}

void PostProc::releaseQueuedBuffers()
{
    // lock acquired in read/stop/pause
    POSTPROC_LOGV("%s:  begin", __func__);

    while (!mOutputBuffers.empty()) {
        POSTPROC_LOGV("Releasing queued buffer\n");
        OutputBuffer * outbuffer = *mOutputBuffers.begin();
        if (outbuffer->isPostProcBuffer) {
            flushPostProcBuffer(outbuffer->buffer);
        } else {
            outbuffer->buffer->release();
        }
        delete outbuffer;
        mOutputBuffers.erase(mOutputBuffers.begin());
    }
    mOutputBuffers.clear();
    return;
}

void PostProc::flushPostProcBuffer(MediaBuffer *buffer)
{
    // called from various places
    POSTPROC_LOGV("%s:  begin", __func__);

    buffer->setObserver(NULL);
    CHECK_EQ(buffer->refcount(), 0);
    if (mNativeWindow == NULL) {
         mPostProcBuffers.push_back(buffer);
    } else {
        int rendered = 0;
        if (!buffer->meta_data()->findInt32(kKeyRendered, &rendered)) {
            rendered = 0;
        }
        if (!rendered) {
            POSTPROC_LOGV("Cancel Post Proc buffer as buffer not rendered\n");
            status_t err = mNativeWindow.get()->postProc_cancelBuffer(buffer->graphicBuffer().get());

            if (err != OK) {
                POSTPROC_LOGE("Warning Cancel Post Proc buffer failed\n");
            }
        }
        buffer->release();
    }
    return;
}


int PostProc::allocateIonBuffer(size_t size, int *handle)
{
    // lock acquired in start
    POSTPROC_LOGV("%s:  begin", __func__);
#ifdef USE_ION
    struct ion_allocation_data alloc_data;
    struct ion_fd_data fd_data;
    int rc;
    alloc_data.len = size;
    alloc_data.align = ALIGN8K;
    alloc_data.flags = (ION_HEAP(ION_CP_MM_HEAP_ID) | ION_HEAP(ION_SYSTEM_HEAP_ID));
    rc = ioctl(mIonFd,ION_IOC_ALLOC,&alloc_data);
    if (rc) {
        POSTPROC_LOGE("Failed to allocate ion memory\n");
        return -ENOMEM;
    }
    fd_data.handle = alloc_data.handle;
    *handle = (int)fd_data.handle;
    POSTPROC_LOGV("handle is %d\n",*handle);
    rc = ioctl(mIonFd,ION_IOC_MAP,&fd_data);
    if (rc) {
        POSTPROC_LOGE("Failed to MAP ion memory\n");
        return -ENOMEM;
    }
    return fd_data.fd;
#else
    return INVALID_OPERATION;
#endif
}

status_t PostProc::getInputFormat()
{
    //lock acquired in init
    POSTPROC_LOGV("%s:  begin", __func__);

    int32_t format;
    CHECK(mSource->getFormat()->findInt32(kKeyColorFormat, &format));
    POSTPROC_LOGV("Format is %d\n",format);
    switch (format) {
        case QOMX_COLOR_FormatYUV420PackedSemiPlanar64x32Tile2m8ka:
        case HAL_PIXEL_FORMAT_YCbCr_420_SP_TILED:
            mSrcFormat = HAL_PIXEL_FORMAT_YCbCr_420_SP_TILED;
            break;
        case OMX_QCOM_COLOR_FormatYVU420SemiPlanar:
        case HAL_PIXEL_FORMAT_YCbCr_420_SP:
            mSrcFormat = HAL_PIXEL_FORMAT_YCbCr_420_SP;
            break;
        default:
            POSTPROC_LOGE("Format not supported\n");
            return UNKNOWN_ERROR;
    }
    return OK;
}

void PostProc::updateInputFormat()
{
    //lock acquired in handleFirstFrame
    POSTPROC_LOGV("%s:  begin", __func__);

    int32_t threeDFormat, interlacedFormat;
    if (mSource->getFormat()->findInt32(kKey3D, &threeDFormat)) {
        mSrcFormat |= threeDFormat;
    } else if (mSource->getFormat()->findInt32(kKeyInterlaced, &interlacedFormat)) {
        mSrcFormat ^= interlacedFormat;
    }
}

} //namespace
