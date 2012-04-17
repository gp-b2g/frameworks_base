/*
 * Copyright (C) 2009 The Android Open Source Project
 * Copyright (c) 2009-2012, Code Aurora Forum. All rights reserved.
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
#define LOG_NDDEBUG 0
#define LOG_TAG "TunnelPlayer"
#include <utils/Log.h>
#include <utils/threads.h>

#include <fcntl.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/poll.h>
#include <sys/eventfd.h>

#include <binder/IPCThreadState.h>
#include <media/AudioTrack.h>

extern "C" {
    #include <asound.h>
    #include "alsa_audio.h"
}
#include <media/stagefright/TunnelPlayer.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaErrors.h>
#include <hardware_legacy/power.h>

#include <linux/unistd.h>
#include <include/linux/msm_audio.h>

#include "include/AwesomePlayer.h"

#define ION_BUFFER_SIZE (600 *1024)
#define ION_BUFFER_COUNT 4

#define ION_CAPTURE_BUFFER_SIZE 4096
#define ION_CAPTURE_BUFFER_COUNT 24

//Values to exit poll via eventfd
#define KILL_EVENT_THREAD 1
#define SIGNAL_EVENT_THREAD 2
#define KILL_A2DP_THREAD 3
#define SIGNAL_A2DP_THREAD 4

#define NUM_FDS 2
#define TUNNEL_SESSION_ID 2
#define RENDER_LATENCY 24000
#define AFE_PROXY_SAMPLE_RATE 48000
#define AFE_PROXY_CHANNEL_COUNT 2
#define AFE_PROXY_PERIOD_SIZE 3072

namespace android {
int TunnelPlayer::mTunnelObjectsAlive = 0;

TunnelPlayer::TunnelPlayer(
                    const sp<MediaPlayerBase::AudioSink> &audioSink, bool &initCheck,
                    AwesomePlayer *observer, bool hasVideo)
:AudioPlayer(audioSink,observer),
mInputBuffer(NULL),
mSampleRate(0),
mNumChannels(0),
mLatencyUs(0),
mStarted(false),
mAsyncReset(false),
mPositionTimeMediaUs(-1),
mPositionTimeRealUs(-1),
mSeeking(false),
mInternalSeeking(false),
mReachedDecoderEOS(false),
mReachedExtractorEOS(false),
mFinalStatus(OK),
mIsPaused(false),
mPlayPendingSamples(false),
mAudioSinkOpen(false),
mIsAudioRouted(false),
mIsA2DPEnabled(false),
mAudioSink(audioSink),
mObserver(observer) {
    LOGD("TunnelPlayer::TunnelPlayer()");

    mAudioFlinger = NULL;
    mAudioFlingerClient = NULL;

    mTunnelObjectsAlive++;

    mQueue.start();
    mQueueStarted      = true;
    mPauseEvent        = new TunnelEvent(this, &TunnelPlayer::onPauseTimeOut);
    mPauseEventPending = false;

    getAudioFlinger();
    LOGD("Registering client with AudioFlinger");
    mAudioFlinger->registerClient(mAudioFlingerClient);

    mEfd = -1;
    mA2DPEfd = -1;
    mMimeType.setTo("");
    mA2dpDisconnectPause = false;

    mTimePaused  = 0;
    mDurationUs = 0;
    mSeekTimeUs = 0;
    mTimeout = -1;

    mInputBufferSize =  ION_BUFFER_SIZE;
    mInputBufferCount = ION_BUFFER_COUNT;
    mCaptureBufferSize = ION_CAPTURE_BUFFER_SIZE;
    mCaptureBufferCount = ION_CAPTURE_BUFFER_COUNT;
    mCaptureBuffer = NULL;
    mPlaybackHandle = NULL;
    mCaptureHandle = NULL;

    LOGV("mInputBufferSize = %d, mInputBufferCount = %d",\
            mInputBufferSize ,mInputBufferCount);

    mHasVideo = hasVideo;
    initCheck = true;
}

TunnelPlayer::~TunnelPlayer() {
    LOGD("TunnelPlayer::~TunnelPlayer()");
    if (mQueueStarted) {
        mQueue.stop();
    }

    reset();
    mAudioFlinger->deregisterClient(mAudioFlingerClient);
    mTunnelObjectsAlive--;
}

void TunnelPlayer::getAudioFlinger() {
    Mutex::Autolock _l(mAudioFlingerLock);

    if ( mAudioFlinger.get() == 0 ) {
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder;
        do {
            binder = sm->getService(String16("media.audio_flinger"));
            if ( binder != 0 )
                break;
            LOGW("AudioFlinger not published, waiting...");
            usleep(500000); // 0.5 s
        } while ( true );
        if ( mAudioFlingerClient == NULL ) {
            mAudioFlingerClient = new AudioFlingerTunnelDecodeClient(this);
        }

        binder->linkToDeath(mAudioFlingerClient);
        mAudioFlinger = interface_cast<IAudioFlinger>(binder);
    }
    LOGE_IF(mAudioFlinger==0, "no AudioFlinger!?");
}

TunnelPlayer::AudioFlingerTunnelDecodeClient::AudioFlingerTunnelDecodeClient(void *obj)
{
    LOGD("TunnelPlayer::AudioFlingerTunnelDecodeClient - Constructor");
    pBaseClass = (TunnelPlayer*)obj;
}

void TunnelPlayer::AudioFlingerTunnelDecodeClient::binderDied(const wp<IBinder>& who) {
    Mutex::Autolock _l(pBaseClass->mAudioFlingerLock);

    pBaseClass->mAudioFlinger.clear();
    LOGW("AudioFlinger server died!");
}

void TunnelPlayer::AudioFlingerTunnelDecodeClient::ioConfigChanged(int event, int ioHandle, void *param2) {
    LOGV("ioConfigChanged() event %d", event);

    if (event != AudioSystem::A2DP_OUTPUT_STATE) {
        return;
    }

    switch ( event ) {
    case AudioSystem::A2DP_OUTPUT_STATE:
        {
            if ( -1 == ioHandle ) {
                if ( pBaseClass->mIsA2DPEnabled ) {
                    pBaseClass->mIsA2DPEnabled = false;
                    if (pBaseClass->mStarted) {
                        pBaseClass->handleA2DPSwitch();
                    }
                    LOGV("ioConfigChanged:: A2DP Disabled");
                }
            } else {
                if ( !pBaseClass->mIsA2DPEnabled ) {
                    pBaseClass->mIsA2DPEnabled = true;
                    if (pBaseClass->mStarted) {
                        pBaseClass->handleA2DPSwitch();
                    }
                    LOGV("ioConfigChanged:: A2DP Enabled");
                }
            }
        }
        break;
    default:
        break;
    }
    LOGV("ioConfigChanged Out");
}

void TunnelPlayer::handleA2DPSwitch() {

    LOGV("handleA2dpSwitch()");
    if(mIsA2DPEnabled) {
        struct pcm * local_handle = (struct pcm *)mPlaybackHandle;

        // 1.	If not paused - pause the driver
        //TODO: CHECK if audio routed has to be checked
        if (!mIsPaused && mIsAudioRouted) {
            if (ioctl(local_handle->fd, SNDRV_PCM_IOCTL_PAUSE,1) < 0) {
                LOGE("AUDIO PAUSE failed");
            }
        }
        //If internal seek reqd
        //mSeekTimeUs += getAudioTimeStampUs();
        //2. If not paused - Query the time. - Not reqd , time need not be stored

        //TODO: Is Internal Seeking required ? I believe not
	//3 Signal Notification thread
        mA2dpNotificationCv.signal();

    } else {
        //TODO :
        //If paused signal notification thread
        if (mIsPaused)
            mA2dpNotificationCv.signal();
        //else flag a2dp disconnect- signal from pause()
        else
            mA2dpDisconnectPause = true;
    }
}

void TunnelPlayer::setSource(const sp<MediaSource> &source) {
    CHECK_EQ(mSource, NULL);
    LOGD("Setting source from Tunnel Player");
    mSource = source;
}

status_t TunnelPlayer::start(bool sourceAlreadyStarted) {
    Mutex::Autolock autoLock(mLock);
    CHECK(!mStarted);
    CHECK(mSource != NULL);

    LOGD("start: sourceAlreadyStarted %d", sourceAlreadyStarted);
    //Check if the source is started, start it
    status_t err;
    if (!sourceAlreadyStarted) {
        err = mSource->start();
        if (err != OK) {
            return err;
        }
    }

    sp<MetaData> format = mSource->getFormat();
    const char *mime;
    bool success = format->findCString(kKeyMIMEType, &mime);
    mMimeType = mime;
    CHECK(success);

    success = format->findInt32(kKeySampleRate, &mSampleRate);
    CHECK(success);

    success = format->findInt32(kKeyChannelCount, &mNumChannels);
    CHECK(success);

    //Sending some dumb channel value to avoid crash - at ALSA HW params.
    if(!mNumChannels)
        mNumChannels = 2;
    int64_t durationUs;
    success = format->findInt64(kKeyDuration, &mDurationUs);
    LOGV("mDurationUs = %lld",mDurationUs);
    //Create event, extractor and a2dp thread and initialize all the
    //mutexes and coditional variables
    createThreads();
    LOGV("All Threads Created.");

    //TODO : Add state machine for A2DP
    if (!mIsA2DPEnabled) {
        LOGV("Opening a routing session for audio playback:\
                mSampleRate = %d mNumChannels =  %d",\
                mSampleRate, mNumChannels);

        err = mAudioSink->openSession(
                AUDIO_FORMAT_PCM_16_BIT, TUNNEL_SESSION_ID,
                mSampleRate, mNumChannels);
        if (err != OK) {

            //TODO: Release first buffer if it is being handled

            if (!sourceAlreadyStarted) {
                mSource->stop();
            }
            LOGE("Opening a routing session failed");
            return err;
        }
        mIsAudioRouted = true;
    }
    else {
        LOGV("Before Audio Sink Open");
        err = mAudioSink->open(
                AFE_PROXY_SAMPLE_RATE,
                AFE_PROXY_CHANNEL_COUNT,
                AUDIO_FORMAT_PCM_16_BIT,
                DEFAULT_AUDIOSINK_BUFFERCOUNT);
        if(err != OK) {
            LOGE("Audio Sink -open failed = %d", err);
            return err;
        }
        mAudioSink->start();
        LOGV("After Audio Sink Open");
        mAudioSinkOpen = true;

        err = setCaptureMixerControl(1);
        if(err) {
            LOGE("setCaptureMixerControl failed err = %d",err);
            return err;
        }
        char *captureDevice = (char *) "hw:0,8";
        LOGV("pcm_open capture device hardware %s for Tunnel Mode ",\
                captureDevice);

        // Open the capture device
        mCaptureHandle = (void *)pcm_open((DEBUG_ON | PCM_MMAP| PCM_STEREO |
                    PCM_IN) , captureDevice);
        struct pcm * capture_handle = (struct pcm *)mCaptureHandle;
        if (!capture_handle) {
            LOGE("Failed to initialize ALSA hardware hw:0,8");
            return BAD_VALUE;
        }

        capture_handle->flags = (DEBUG_ON | PCM_MMAP| PCM_STEREO | PCM_IN);

        //Set the hardware and software params for the capture device
        err = setCaptureALSAParams();
        if(err != OK) {
            LOGE("Set Capture AALSA Params = %d", err);
            return err;
        }

        mmap_buffer(capture_handle);
        //Prepare the capture  device
        err = pcm_prepare(capture_handle);
        if(err != OK) {
            LOGE("PCM Prepare - capture failed err = %d", err);
            return err;
        }
        mCaptureHandle = (void *)capture_handle;
    }

    char *tunnelDevice = (char *) "hw:0,9";
    LOGD("pcm_open hardware %s for Tunnel Mode ", tunnelDevice);
    //Open PCM driver for playback
    if (mNumChannels == 1)
        mPlaybackHandle = (void *)pcm_open((PCM_MMAP | DEBUG_ON | PCM_MONO | PCM_OUT) , tunnelDevice);
    else
        mPlaybackHandle = (void *)pcm_open((PCM_MMAP | DEBUG_ON | PCM_STEREO | PCM_OUT) , tunnelDevice);
    struct pcm * local_handle = (struct pcm *)mPlaybackHandle;

    if (!local_handle) {
        LOGE("Failed to initialize ALSA hardware hw:0,9");
        return BAD_VALUE;
    }

    //Configure the pcm device for playback
    err = setPlaybackALSAParams();
    if(err != OK) {
        LOGE("Set Playback AALSA Params = %d", err);
        return err;
    }

    //mmap the buffers for playback
    mmap_buffer(local_handle);
    //prepare the driver for playback
    err = pcm_prepare(local_handle);
    if(err) {
        LOGE("PCM Prepare failed - playback err = %d", err);
        return err;
    }
    mPlaybackHandle = (void *)local_handle;
    ionBufferAlloc(mInputBufferSize);
    LOGD(" Tunnel Driver Started");
    mStarted = true;

    LOGD("Waking up extractor thread");
    mExtractorCv.signal();
    return OK;
}

status_t TunnelPlayer::seekTo(int64_t time_us) {
    Mutex::Autolock autoLock1(mSeekLock);
    Mutex::Autolock autoLock(mLock);
    status_t err = NO_ERROR;
    LOGD("seekTo: time_us %lld", time_us);
    if (mReachedExtractorEOS) {
        mReachedExtractorEOS = false;
        mReachedDecoderEOS = false;
        mTimeout = -1;
    }
    mSeeking = true;
    mInternalSeeking = false;
    mSeekTimeUs = time_us;
    mTimePaused = 0;
    struct pcm * local_handle = (struct pcm *)mPlaybackHandle;
    struct pcm * capture_handle = (struct pcm *)mCaptureHandle;

    LOGV("In seekTo(), mSeekTimeUs %lld",mSeekTimeUs);
    if (mStarted) {
        LOGV("Paused case, %d",mIsPaused);
        mInputIonResponseMutex.lock();
        mInputIonRequestMutex.lock();
        mInputIonFilledQueue.clear();
        mInputIonEmptyQueue.clear();

        List<BuffersAllocated>::iterator it = mInputBufPool.begin();
        for(;it!=mInputBufPool.end();++it) {
             mInputIonEmptyQueue.push_back(*it);
        }
        mInputIonRequestMutex.unlock();
        mInputIonResponseMutex.unlock();
        LOGV("Transferred all the buffers from response queue to\
                request queue to handle seek");
        err = flush(mPlaybackHandle);
        if(err) {
            LOGE("flush returned err = %d",err);
            return err;
        }
    }
    if (mIsA2DPEnabled) {
         if(!mIsPaused) {
            mAudioSink->pause();
            mAudioSink->flush();
            mAudioSink->start();
         }
    }
    mExtractorCv.signal();
    return OK;
}

void TunnelPlayer::pause(bool playPendingSamples) {
    Mutex::Autolock autolock(mLock);
    CHECK(mStarted);
    LOGD("Pause: playPendingSamples %d", playPendingSamples);
    mPlayPendingSamples = playPendingSamples;
    mIsPaused = true;
    mTimeout  = -1;
    struct pcm * local_handle = (struct pcm *)mPlaybackHandle;
    struct pcm * capture_handle = (struct pcm *)mCaptureHandle;

    if (playPendingSamples) {
        if (ioctl(local_handle->fd, SNDRV_PCM_IOCTL_PAUSE,1) < 0) {
            LOGE("Audio Pause failed");
            postEOSOnError();
            return;
        }
        if (!mIsA2DPEnabled) {
            if (!mPauseEventPending) {
                LOGV("Posting an event for Pause timeout\
                         - acquire_wake_lock");
                acquire_wake_lock(PARTIAL_WAKE_LOCK, "TUNNEL_LOCK");
                mQueue.postEventWithDelay(mPauseEvent,
                                          TUNNEL_PAUSE_TIMEOUT_USEC);
                mPauseEventPending = true;
            }
            if (mAudioSink.get() != NULL)
                mAudioSink->pauseSession();
        }
        else {
            if (mAudioSink.get() != NULL)
                mAudioSink->stop();
        }
        mTimePaused = mSeekTimeUs + getAudioTimeStampUs();
    }
    else {
        if (mA2dpDisconnectPause) {
            mA2dpDisconnectPause = false;
            LOGV("mA2dpDisconnectPause - TunnelPlayer::Pause - Pause driver");
             if (ioctl(local_handle->fd, SNDRV_PCM_IOCTL_PAUSE,1) < 0) {
                LOGE("Audio Pause failed");
                postEOSOnError();
                return;
              }
            mA2dpNotificationCv.signal();
        } else {
            LOGV("TunnelPlayer::Pause - Pause driver");
            if (ioctl(local_handle->fd, SNDRV_PCM_IOCTL_PAUSE,1) < 0) {
                LOGE("Audio Pause failed");
                postEOSOnError();
                return;
            }
            if (!mIsA2DPEnabled) {
                if(!mPauseEventPending) {
                    LOGV("Posting an event for Pause timeout -\
                            acquire_wake_lock");
                    acquire_wake_lock(PARTIAL_WAKE_LOCK, "TUNNEL_LOCK");
                    mQueue.postEventWithDelay(mPauseEvent,
                                              TUNNEL_PAUSE_TIMEOUT_USEC);
                    mPauseEventPending = true;
                }

                if (mAudioSink.get() != NULL) {
                    mAudioSink->pauseSession();
                }
            }
            else {
                LOGV(" Pause: sink - pause & flush");
                mAudioSink->pause();
                mAudioSink->flush();
            }
        }
        mTimePaused = mSeekTimeUs + getAudioTimeStampUs();
    }
}

void TunnelPlayer::resume() {
    Mutex::Autolock autoLock(mLock);
    LOGV("Resume: mIsPaused %d",mIsPaused);
    struct pcm * capture_handle = (struct pcm *)mCaptureHandle;

    status_t err = NO_ERROR;
    if (mIsPaused) {
        CHECK(mStarted);
        if (!mIsA2DPEnabled) {
            LOGV("Tunnel Player::resume - Resuming Driver");
            if(mPauseEventPending) {
                LOGV("Resume(): Cancelling the puaseTimeout event");
                mPauseEventPending = false;
                mQueue.cancelEvent(mPauseEvent->eventID());
                release_wake_lock("TUNNEL_LOCK");
            }

            if (!mIsAudioRouted) {
                LOGV("Opening a session for TUNNEL playback");
                err = mAudioSink->openSession(AUDIO_FORMAT_PCM_16_BIT,
                                                       TUNNEL_SESSION_ID);
                if(err) {
                    postEOSOnError();
                    return;
                }
                mIsAudioRouted = true;
            }
        }
        LOGV("Attempting Sync resume\n");
        err = syncResume(mPlaybackHandle);
        if(err)
           return;
        if(!mIsA2DPEnabled) {
            if (mAudioSink.get() != NULL) {
                mAudioSink->resumeSession();
            }
            mIsPaused = false;
            mExtractorCv.signal();
            return;
        } else {
            if (!mAudioSinkOpen) {
                if (mAudioSink.get() != NULL) {
                    LOGV("%s mAudioSink close session", __func__);
                    mAudioSink->closeSession();
                    mIsAudioRouted = false;
                } else {
                    LOGE("close session NULL");
                }

                LOGV("Resume: Before Audio Sink Open");
                status_t ret = mAudioSink->open(AFE_PROXY_SAMPLE_RATE,
                                                AFE_PROXY_CHANNEL_COUNT,
                                                AUDIO_FORMAT_PCM_16_BIT,
                                                DEFAULT_AUDIOSINK_BUFFERCOUNT);
                if(ret) {
                    postEOSOnError();
                    return;
                }
                mAudioSink->start();
                LOGV("Resume: After Audio Sink Open");
                mAudioSinkOpen = true;
                err = pcm_prepare(capture_handle);
                if(err != OK) {
                    LOGE("PCM Prepare - capture failed err = %d", err);
                }
                if (ioctl(capture_handle->fd, SNDRV_PCM_IOCTL_START)) {
                    LOGE("SNDRV_PCM_IOCTL_START call failed with error no %d"\
                            , errno);
                    err = -errno;
                    if (errno == EPIPE) {
                        LOGE("Failed in SNDRV_PCM_IOCTL_START\n");
                    } else {
                        LOGE("IOCTL_START failed for proxy Err no: %d \n", errno);
                    }
                } else {
                    LOGD(" Proxy Driver started(IOCTL_START Success)\n");
                    capture_handle->start = 1;
                }
                mIsPaused = false;
                mExtractorCv.signal();
                LOGV("Resume: Waking up the decoder thread");
            } else {
                /* If AudioSink is already open just start it */
                mAudioSink->start();
                mIsPaused = false;
                err = pcm_prepare(capture_handle);
                if(err != OK) {
                    LOGE("PCM Prepare - capture failed err = %d", err);
                }
                if (ioctl(capture_handle->fd, SNDRV_PCM_IOCTL_START)) {
                    err = -errno;
                    if (errno == EPIPE) {
                        LOGE("Failed in SNDRV_PCM_IOCTL_START\n");
                    } else {
                        LOGE("IOCTL_START failed for proxy Err no: %d \n", errno);
                    }
                } else {
                    LOGD(" Proxy Driver started(IOCTL_START Success)\n");
                    capture_handle->start = 1;
                }
            }
            mA2dpCv.signal();
            mExtractorCv.signal();
        }
    }
}

void TunnelPlayer::reset() {

    LOGD("Reset called!!!!!");
    mAsyncReset = true;

    struct pcm * local_handle = (struct pcm *)mPlaybackHandle;
    struct pcm * capture_handle = (struct pcm *)mCaptureHandle;
    LOGV("reset() Empty Queue.size() = %d, Filled Queue.size() = %d",\
            mInputIonEmptyQueue.size(),\
            mInputIonFilledQueue.size());

    // make sure Extractor thread has exited
    requestAndWaitForExtractorThreadExit();

    // make sure the event thread also has exited
    requestAndWaitForEventThreadExit();

    requestAndWaitForA2DPThreadExit();

    requestAndWaitForA2DPNotificationThreadExit();

    // Close the audiosink after all the threads exited to make sure
    // there is no thread writing data to audio sink or applying effect

    ionBufferDeAlloc();
    LOGD("Buffer Deallocation complete! Closing pcm handle");
    if(local_handle) {
        pcm_close(local_handle);
        local_handle = NULL;
        mPlaybackHandle = (void*)local_handle;
    }

    if (mIsA2DPEnabled) {

        //TODO : Deallocate the buffer for capture side
        if(capture_handle) {
            pcm_close(capture_handle);
            capture_handle = NULL;
            mCaptureHandle = (void*)capture_handle;
        }
        mAudioSink->close();
        mAudioSinkOpen = false;
    } else {
        mAudioSink->closeSession();
        mIsAudioRouted =  false;
    }
    mAudioSink.clear();

    if (mInputBuffer != NULL) {
        LOGV("Tunnel Player releasing input buffer.");
        mInputBuffer->release();
        mInputBuffer = NULL;
    }

    mSource->stop();
    mSource.clear();

    if(mCaptureBuffer == NULL) {
        free(mCaptureBuffer);
        mCaptureBuffer = NULL;
    }

    LOGV("reset() after Empty Queue size = %d,\
            Filled Queue size() = %d ",\
            mInputIonEmptyQueue.size(),\
            mInputIonFilledQueue.size());

    mPositionTimeMediaUs = -1;
    mPositionTimeRealUs = -1;

    mSeeking = false;
    mInternalSeeking = false;

    mReachedDecoderEOS = false;
    mReachedExtractorEOS = false;
    mFinalStatus = OK;

    mIsPaused = false;
    mPauseEventPending = false;
    mPlayPendingSamples = false;

    mTimePaused  = 0;
    mDurationUs = 0;
    mSeekTimeUs = 0;
    mTimeout = -1;

    mNumChannels = 0;
    mMimeType.setTo("");
    mInputBuffer = NULL;

    mStarted = false;
}

bool TunnelPlayer::isSeeking() {
    Mutex::Autolock autoLock(mLock);
    return mSeeking;
}

bool TunnelPlayer::reachedEOS(status_t *finalStatus) {
    *finalStatus = OK;
    Mutex::Autolock autoLock(mLock);
    *finalStatus = mFinalStatus;
    return mReachedDecoderEOS;
}


void *TunnelPlayer::extractorThreadWrapper(void *me) {
    static_cast<TunnelPlayer *>(me)->extractorThreadEntry();
    return NULL;
}


void TunnelPlayer::extractorThreadEntry() {

    mExtractorMutex.lock();
    pid_t tid  = gettid();
    androidSetThreadPriority(tid, ANDROID_PRIORITY_AUDIO);
    prctl(PR_SET_NAME, (unsigned long)"Tunnel DecodeThread", 0, 0, 0);
    LOGV("extractorThreadEntry wait for signal \n");

    while (!mStarted && !mKillExtractorThread) {
        mExtractorCv.wait(mExtractorMutex);
    }

    LOGV("extractorThreadEntry ready to work \n");
    mExtractorMutex.unlock();

    while (!mKillExtractorThread) {

        mInputIonRequestMutex.lock();
        LOGV("extractor Empty Queue size() = %d,\
                Filled Queue size() = %d ",\
                mInputIonEmptyQueue.size(),\
                mInputIonFilledQueue.size());

        if (mInputIonEmptyQueue.empty() || mReachedExtractorEOS || mIsPaused ||
            (mIsA2DPEnabled && !mAudioSinkOpen) || mAsyncReset ) {
            LOGV("extractorThreadEntry: mIsPaused %d  mReachedExtractorEOS %d\
                 mIsA2DPEnabled %d mAudioSinkOpen %d mAsyncReset %d ",\
                 mIsPaused, mReachedExtractorEOS, mIsA2DPEnabled,\
                 mAudioSinkOpen, mAsyncReset);
            LOGV("extractorThreadEntry: waiting on mExtractorCv");
            mExtractorCv.wait(mInputIonRequestMutex);
            //TODO: Guess this should be removed plz verify
            mInputIonRequestMutex.unlock();
            LOGV("extractorThreadEntry: received a signal to wake up");
            continue;
        }

        mInputIonRequestMutex.unlock();
        Mutex::Autolock autoLock1(mSeekLock);
        mInputIonRequestMutex.lock();

        List<BuffersAllocated>::iterator it = mInputIonEmptyQueue.begin();
        BuffersAllocated buf = *it;
        mInputIonEmptyQueue.erase(it);
        mInputIonRequestMutex.unlock();
        LOGV("Calling fillBuffer for size %d",mInputBufferSize);
        buf.bytesToWrite = fillBuffer(buf.ionBuf, mInputBufferSize);
        LOGV("fillBuffer returned size %d",buf.bytesToWrite);
        if (buf.bytesToWrite <= 0) {
            mInputIonRequestMutex.lock();
            mInputIonEmptyQueue.push_back(buf);
            mInputIonRequestMutex.unlock();
            /*Post EOS to Awesome player when i/p EOS is reached,
            all input buffers have been decoded and response queue is empty*/
            if(mObserver && mReachedExtractorEOS &&
                       mInputIonFilledQueue.empty()) {
                LOGD("Posting EOS event..zero byte buffer and\
                        response queue is empty");
                mReachedDecoderEOS = true;
                mObserver->postAudioEOS(0);
            }
            continue;
        }
        mInputIonResponseMutex.lock();
        mInputIonFilledQueue.push_back(buf);
        mInputIonResponseMutex.unlock();

        LOGV("Start Event thread\n");
        mEventCv.signal();
        if (mSeeking) {
            continue;
        }
        LOGV("PCM write start");
        struct pcm * local_handle = (struct pcm *)mPlaybackHandle;
        pcm_write(local_handle, buf.ionBuf, local_handle->period_size);

        if (mReachedExtractorEOS) {
            //TODO : Is this code reqd - start seems to fail?
            if (ioctl(local_handle->fd, SNDRV_PCM_IOCTL_START) < 0)
                LOGE("AUDIO Start failed");
            else
                local_handle->start = 1;
        }
        if (buf.bytesToWrite < mInputBufferSize &&
                    mInputIonFilledQueue.size() == 1) {
            LOGD("Last buffer case");
            uint64_t writeValue = SIGNAL_EVENT_THREAD;
            write(mEfd, &writeValue, sizeof(uint64_t));
        }
        LOGV("PCM write complete");
        if (mIsA2DPEnabled) {
            LOGV("A2DP signal");
            mA2dpCv.signal();
        }
    }
    mExtractorThreadAlive = false;
    LOGD("Extractor Thread is dying");

}

void * TunnelPlayer::eventThreadWrapper(void *me) {
    static_cast<TunnelPlayer *>(me)->eventThreadEntry();
    return NULL;
}

void  TunnelPlayer::eventThreadEntry() {

    int rc = 0;
    int err_poll = 0;
    int avail = 0;
    int i = 0;
    struct pollfd pfd[NUM_FDS];
    struct pcm * local_handle = NULL;
    mEventMutex.lock();
    mTimeout = -1;
    pid_t tid  = gettid();
    androidSetThreadPriority(tid, ANDROID_PRIORITY_AUDIO);
    prctl(PR_SET_NAME, (unsigned long)"Tunnel EventThread", 0, 0, 0);

    while(!mStarted && !mKillEventThread) {
        LOGV("eventThreadEntry wait for signal \n");
        mEventCv.wait(mEventMutex);
        LOGV("eventThreadEntry ready to work \n");
        continue;
    }
    mEventMutex.unlock();

    LOGV("Allocating poll fd");
    if(!mKillEventThread) {
        LOGV("Allocating poll fd");
        local_handle = (struct pcm *)mPlaybackHandle;
        pfd[0].fd = local_handle->timer_fd;
        pfd[0].events = (POLLIN | POLLERR | POLLNVAL);
        LOGV("Allocated poll fd");
        mEfd = eventfd(0,0);
        pfd[1].fd = mEfd;
        pfd[1].events = (POLLIN | POLLERR | POLLNVAL);
    }
    while(!mKillEventThread && ((err_poll = poll(pfd, NUM_FDS, mTimeout)) >=0)) {
	LOGV("pfd[0].revents =%d ", pfd[0].revents);
	LOGV("pfd[1].revents =%d ", pfd[1].revents);
        if (err_poll == EINTR)
            LOGD("Timer is intrrupted");
        if (pfd[1].revents & POLLIN) {
            uint64_t u;
            read(mEfd, &u, sizeof(uint64_t));
            LOGV("POLLIN event occured on the event fd, value written to %llu",\
                    (unsigned long long)u);
            pfd[1].revents = 0;
            if (u == SIGNAL_EVENT_THREAD) {
                LOGV("Setting timeout last buffer");
                {
                    Mutex::Autolock autoLock(mLock);
                    mTimeout = ((mDurationUs -
                            (mSeekTimeUs + getAudioTimeStampUs())) / 1000);
                }
                LOGV("Setting timeout due Last buffer seek to %d,\
                        mReachedExtractorEOS %d, Fille Queue size() %d",\
                        mTimeout, mReachedExtractorEOS,\
                        mInputIonFilledQueue.size());
                continue;
            }
        }
        if ((pfd[1].revents & POLLERR) || (pfd[1].revents & POLLNVAL)) {
            LOGE("POLLERR or INVALID POLL");
        }

        {
            Mutex::Autolock autoLock(mLock);
            if(isReadyToPostEOS(err_poll, pfd)) {
                LOGD("Posting EOS event to AwesomePlayer");
                mReachedDecoderEOS = true;
                mObserver->postAudioEOS(0);
                mTimeout = -1;
            }
            if (!mReachedExtractorEOS) {
                LOGV("timeout is -1");
                mTimeout = -1;
            }
        }
        struct snd_timer_tread rbuf[4];
        read(local_handle->timer_fd, rbuf, sizeof(struct snd_timer_tread) * 4 );
        if((pfd[0].revents & POLLERR) || (pfd[0].revents & POLLNVAL))
            continue;

        if (pfd[0].revents & POLLIN && !mKillEventThread) {
            pfd[0].revents = 0;
            if (mIsPaused)
                continue;
            LOGV("After an event occurs");

            {
                Mutex::Autolock autoLock(mLock);
                mInputIonResponseMutex.lock();
                BuffersAllocated buf = *(mInputIonFilledQueue.begin());
                mInputIonFilledQueue.erase(mInputIonFilledQueue.begin());
                /*If the rendering is complete report EOS to the AwesomePlayer*/
                if (mObserver && !mAsyncReset && mReachedExtractorEOS &&
                        mInputIonFilledQueue.size() == 1) {
                      mTimeout = ((mDurationUs -
                              (mSeekTimeUs + getAudioTimeStampUs())) / 1000);

                    LOGD("Setting timeout to %d, mReachedExtractorEOS %d,\
                             Filled Queue size() %d", mTimeout,\
                             mReachedExtractorEOS,\
                             mInputIonFilledQueue.size());
                }

                mInputIonResponseMutex.unlock();
                // Post buffer to request Q

                mInputIonRequestMutex.lock();

                mInputIonEmptyQueue.push_back(buf);

                mInputIonRequestMutex.unlock();
                mExtractorCv.signal();
            }
        }
    }
    mEventThreadAlive = false;
    if (mEfd != -1)
        close(mEfd);

    LOGD("Event Thread is dying.");
    return;

}

void *TunnelPlayer::A2DPNotificationThreadWrapper(void *me) {
    static_cast<TunnelPlayer *>(me)->A2DPNotificationThreadEntry();
    return NULL;
}


void TunnelPlayer::A2DPNotificationThreadEntry() {

    //Wait on A2DP Notification Mutex
    while (1) {
        mA2dpNotificationMutex.lock();
        mA2dpNotificationCv.wait(mA2dpNotificationMutex);
        mA2dpNotificationMutex.unlock();

        //Check for thread exit signal
        if (mKillA2DPNotificationThread) {
            break;
        }

        status_t err = OK;
        LOGV("A2DP notification has come mIsA2DPEnabled: %d", mIsA2DPEnabled);

        if(mIsA2DPEnabled) {

            // Close Routing session if open
            if (!mAudioSinkOpen) {
                LOGV("Close Session");
                if (mAudioSink.get() != NULL) {
                    mAudioSink->closeSession();
                    mIsAudioRouted = false;
                } else {
                    LOGE("close session NULL");
                }

                // Open  and Start Sink
                status_t err = mAudioSink->open(AFE_PROXY_SAMPLE_RATE,
                        AFE_PROXY_CHANNEL_COUNT,
                        AUDIO_FORMAT_PCM_16_BIT,
                        DEFAULT_AUDIOSINK_BUFFERCOUNT);
                mAudioSink->start();
                LOGV("After Audio Sink Open");
                mAudioSinkOpen = true;
            }

            err = setCaptureMixerControl(1);
            if(err) {
                LOGE("setCaptureMixerControl failed err = %d",err);
                postEOSOnError();
                continue;
            }
            // open capture device
            char *captureDevice = (char *) "hw:0,8";
            LOGV("pcm_open capture device hardware %s for Tunnel Mode ",\
                    captureDevice);

            {
                Mutex::Autolock autolock (mA2dpMutex);
                // Open the capture device
                mCaptureHandle = (void *)pcm_open((PCM_MMAP | DEBUG_ON | PCM_STEREO |
                            PCM_IN) , captureDevice);
                struct pcm * capture_handle = (struct pcm *)mCaptureHandle;
                if (!capture_handle) {
                    LOGE("Failed to initialize ALSA hardware hw:0,8");
                    postEOSOnError();
                    continue;
                }

                capture_handle->flags = (DEBUG_ON | PCM_MMAP| PCM_STEREO | PCM_IN);

                //Set the hardware and software params for the capture device
                err = setCaptureALSAParams();
                if(err != OK) {
                    LOGE("Set Capture AALSA Params = %d", err);
                    postEOSOnError();
                    continue;
                }

                mmap_buffer(capture_handle);

                //Prepare the capture  device
                err = pcm_prepare(capture_handle);
                if(err != OK) {
                    LOGE("PCM Prepare - capture failed err = %d", err);
                    postEOSOnError();
                    continue;
                }
                mCaptureHandle = (void *)capture_handle;
            }
            // RESUME
            if(!mIsPaused) {
                if (ioctl(((struct pcm *)mPlaybackHandle)->fd,
                         SNDRV_PCM_IOCTL_PAUSE,0) < 0) {
                    LOGE("AUDIO Resume failed");
                    postEOSOnError();
                    continue;
                }
                LOGV("Sync resume done\n");
            }

            // Signal Extractor thread
            LOGD("Signalling to extractor condition variable");
            if(!err) {
                mExtractorCv.signal();
                mA2dpCv.signal();
            }
        } else {
            // Stop and close the sink
            mAudioSink->stop();
            mAudioSink->close();
            mAudioSinkOpen = false;
            LOGV("resume:: opening audio session with mSampleRate %d\
                    numChannels %d ", mSampleRate, mNumChannels);
            // Mixer control disable
            err = setCaptureMixerControl(0);
            if(err) {
                LOGE("setCaptureMixerControl failed err = %d",err);
                postEOSOnError();
            }

            // open session / pause session
            err = mAudioSink->openSession(AUDIO_FORMAT_PCM_16_BIT,
                    TUNNEL_SESSION_ID, mSampleRate, mNumChannels);
            if (mAudioSink.get() != NULL) {
                mAudioSink->pauseSession();
            }

            // stop capture device
            //TODO : De allocate the buffer for capture side
            {
                if(mA2DPEfd != -1) {
                    uint64_t writeValue = SIGNAL_A2DP_THREAD;
                    LOGD("Writing to mEfd %d",mA2DPEfd);
                    write(mA2DPEfd, &writeValue, sizeof(uint64_t));
                }

                Mutex::Autolock autolock (mA2dpMutex);
                pcm_close((struct pcm *)mCaptureHandle);
                mCaptureHandle = NULL;
            }

            if(mCaptureBuffer) {
                free(mCaptureBuffer);
                mCaptureBuffer = NULL;
            }

            // Signal extractor thread
           if(!err)
               mExtractorCv.signal();
               //TODO: check if we need to signal A2Dp thread
        }
    }
    //TODO : Need to see if internal seek is required
    //  Since in the decoding is in dsp it might
    //  not be require.

    mA2dpNotificationThreadAlive = false;
    LOGD("A2DPNotificationThread is dying");

}
void *TunnelPlayer::A2DPThreadWrapper(void *me) {
    static_cast<TunnelPlayer *>(me)->A2DPThreadEntry();
    return NULL;
}

void TunnelPlayer::A2DPThreadEntry() {

    pid_t tid  = gettid();
    androidSetThreadPriority(tid, ANDROID_PRIORITY_AUDIO);
    prctl(PR_SET_NAME, (unsigned long)"Tunnel A2DPThread", 0, 0, 0);
    int ionBufCount = 0;
    uint32_t bytesWritten = 0;
    uint32_t numBytesRemaining = 0;
    uint32_t bytesAvailInBuffer = 0;
    struct snd_xferi x;
    unsigned avail = 0;
    struct pollfd pfdProxy[NUM_FDS];
    void  *data;
    long frames = 0;
    int err = NO_ERROR;
    int err_poll = 0;
    x.frames = 0;

    while(!mKillA2DPThread) {

        if(!mIsA2DPEnabled) {
             bytesWritten = 0;
             numBytesRemaining = 0;
             bytesAvailInBuffer = 0;
             avail = 0;
             frames = 0;
             x.frames = 0;
             err = NO_ERROR;
             err_poll = 0;
             if(mA2DPEfd != -1) {
                 close(mA2DPEfd);
                 mA2DPEfd = -1;
             }
        }

        {

            Mutex::Autolock autolock(mA2dpMutex);
            struct pcm * capture_handle = (struct pcm *)mCaptureHandle;

            if (!mAudioSinkOpen || mIsPaused || !mIsA2DPEnabled ||
                    mReachedDecoderEOS || !capture_handle || (err != NO_ERROR)) {
                LOGD("A2DPThreadEntry:: mAudioSinkOpen %d isPaused %d\
                       bIsA2DPEnabled %d",\
                       mAudioSinkOpen, mIsPaused,\
                       mIsA2DPEnabled);
                LOGD("A2DPThreadEntry:: Waiting on mA2DPCv");
                mA2dpCv.wait(mA2dpMutex);
                LOGD("A2DPThreadEntry:: received signal to wake up");
                mA2dpMutex.unlock();
                continue;
            }

            //TODO: Check capture fd also here!!!
            if(mA2DPEfd == -1) {
                LOGV("Allocating A2Dp poll fd");
                pfdProxy[0].fd = capture_handle->fd;
                pfdProxy[0].events = (POLLIN | POLLERR | POLLNVAL);
                LOGV("Allocated A2DP poll fd");
                mA2DPEfd = eventfd(0,0);
                pfdProxy[1].fd = mA2DPEfd;
                pfdProxy[1].events = (POLLIN | POLLERR | POLLNVAL);
                frames = (capture_handle->flags & PCM_MONO) ?
                         (capture_handle->period_size / 2) :
                         (capture_handle->period_size / 4);
                x.frames = (capture_handle->flags & PCM_MONO) ?
                           (capture_handle->period_size / 2) :
                           (capture_handle->period_size / 4);
            }
            CHECK(capture_handle);
            if ((!capture_handle->start && !mKillA2DPThread )) {
                if (ioctl(capture_handle->fd, SNDRV_PCM_IOCTL_START)) {
                    err = -errno;
                    if (errno == EPIPE) {
                        LOGV("Failed in SNDRV_PCM_IOCTL_START\n");
                        /* we failed to make our window -- try to restart */
                        capture_handle->underruns++;
                        capture_handle->running = 0;
                        capture_handle->start = 0;
                        continue;
                    } else {
                        LOGE("IOCTL_START failed for proxy err: %d \n", errno);
                        postEOSOnError();
                        continue;
                    }
                } else {
                    LOGD(" Proxy Driver started(IOCTL_START Success)\n");
                    capture_handle->start = 1;
                }
            }

            capture_handle->sync_ptr->flags = SNDRV_PCM_SYNC_PTR_APPL |
                   SNDRV_PCM_SYNC_PTR_AVAIL_MIN;

            while(!mKillA2DPThread &&  mIsA2DPEnabled &&  !mReachedDecoderEOS) {
                LOGV("Calling sync_ptr(proxy");
                err = sync_ptr(capture_handle);
                if(err == EPIPE) {
                    LOGE("Failed in sync_ptr \n");
                    /* we failed to make our window -- try to restart */
                    capture_handle->underruns++;
                    capture_handle->running = 0;
                    capture_handle->start = 0;
                    continue;
                }else if(err != NO_ERROR){
                    LOGE("Error: Sync ptr returned %d", err);
                    postEOSOnError();
                    break;
                }

                avail = pcm_avail(capture_handle);
                LOGV("avail is = %d frames = %ld, avai_min = %d\n",\
                         avail, frames,(int)capture_handle->sw_p->avail_min);
                if (avail < capture_handle->sw_p->avail_min) {
                    err_poll = poll(pfdProxy, NUM_FDS, TIMEOUT_INFINITE);
                    if (pfdProxy[1].revents & POLLIN) {
                       LOGV("Event on userspace fd");
                    }
                    if ((pfdProxy[1].revents & POLLERR) ||
                            (pfdProxy[1].revents & POLLNVAL)) {
                        LOGV("POLLERR or INVALID POLL");
                        err = BAD_VALUE;
                        postEOSOnError();
                        break;
                    }
                    if((pfdProxy[0].revents & POLLERR) ||
                            (pfdProxy[0].revents & POLLNVAL)) {
                        LOGV("POLLERR or INVALID POLL on zero");
                        err = BAD_VALUE;
                        postEOSOnError();
                        break;
                    }
                    if (pfdProxy[0].revents & POLLIN) {
                        LOGV("POLLIN on zero");
                    }
                    LOGV("err_poll = %d",err_poll);
                    continue;
                }
                break;
            }
            if (x.frames > avail)
                frames = avail;

            data  = dst_address(capture_handle);
            if(mCaptureBuffer == NULL)
                mCaptureBuffer =  malloc(mCaptureBufferSize);
            memcpy(mCaptureBuffer, (char *)data, mCaptureBufferSize);

            x.frames -= frames;
            capture_handle->sync_ptr->c.control.appl_ptr += frames;
            capture_handle->sync_ptr->flags = 0;
            LOGV("Calling sync_ptr for proxy after sync");
            err = sync_ptr(capture_handle);
            if(err == EPIPE) {
                LOGV("Failed in sync_ptr \n");
                capture_handle->running = 0;
                //TODO :should return error here
                continue;
            }else if( err != NO_ERROR ){
                LOGE("Error: Sync ptr returned %d", err);
                postEOSOnError();
                break;
            }
        }
        void *copyBuffer = mCaptureBuffer;
        numBytesRemaining = mCaptureBufferSize;
        while (err == OK && (numBytesRemaining  > 0) && !mKillA2DPThread
                && mIsA2DPEnabled &&  !mReachedDecoderEOS) {
           if(mIsPaused && numBytesRemaining) {
                mA2dpMutex.lock();
                LOGV("Paused : wait  for A2DPCV");
                mA2dpCv.wait(mA2dpMutex);
                LOGV("Signalled!!!!");
                mA2dpMutex.unlock();
                continue;
            }
            bytesAvailInBuffer = mAudioSink->bufferSize();
            uint32_t writeLen = bytesAvailInBuffer > numBytesRemaining ?
                    numBytesRemaining : bytesAvailInBuffer;
            LOGV("Writing %d bytes to A2DP ", writeLen);
            bytesWritten = mAudioSink->write(copyBuffer, writeLen);
            LOGV("bytesWritten = %d",bytesWritten);
            //Need to check warning here - void used in arithmetic
            copyBuffer = (char *)copyBuffer + bytesWritten;
            numBytesRemaining -= bytesWritten;
            LOGV("@_@bytes To write2:%d",numBytesRemaining);
        }
    }

    if(mA2DPEfd != -1) {
        close(mA2DPEfd);
        mA2DPEfd = -1;
    }
    mA2dpThreadAlive = false;
    LOGD("A2DP Thread is dying");
}

void TunnelPlayer::ionBufferAlloc(int32_t nSize) {

    void  *ion_buf = NULL;
    int i = 0;

    struct pcm * local_handle = (struct pcm *)mPlaybackHandle;

    for (i = 0; i < mInputBufferCount; i++) {
        ion_buf = (int32_t *)local_handle->addr + (nSize * i/sizeof(int));
        BuffersAllocated buf(ion_buf, nSize);
        memset(buf.ionBuf, 0x0, nSize);
        mInputIonEmptyQueue.push_back(buf);
        mInputBufPool.push_back(buf);
    }
    LOGV("ionBufferAlloc calling with required size %d", nSize);
    LOGD("The ION that is allocated - buffer is %x",\
           (unsigned int)ion_buf);
}

void TunnelPlayer::ionBufferDeAlloc() {

    //Remove all the buffers from request queue
    while (!mInputBufPool.empty()) {
        List<BuffersAllocated>::iterator it = mInputBufPool.begin();
        BuffersAllocated &ionBuffer = *it;
        LOGD("Removing input buffer from Buffer Pool ");
        mInputBufPool.erase(it);
    }
}

void TunnelPlayer::createThreads() {

    //Initialize all the Mutexes and Condition Variables
    // Create 4 threads Effect, extractor, event and A2dp
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

    mKillExtractorThread = false;
    mKillEventThread = false;
    mKillA2DPThread = false;
    mKillA2DPNotificationThread = false;

    mExtractorThreadAlive = true;
    mEventThreadAlive = true;
    mA2dpThreadAlive = true;
    mA2dpNotificationThreadAlive = true;

    LOGD("Creating Event Thread");
    pthread_create(&mEventThread, &attr, eventThreadWrapper, this);

    LOGD("Creating Extractor Thread");
    pthread_create(&mExtractorThread, &attr, extractorThreadWrapper, this);

    LOGD("Creating A2DP Thread");
    pthread_create(&mA2DPThread, &attr, A2DPThreadWrapper, this);

    LOGD("Creating A2DP Notification Thread");
    pthread_create(&mA2DPNotificationThread, &attr, A2DPNotificationThreadWrapper, this);

    pthread_attr_destroy(&attr);
}

size_t TunnelPlayer::fillBuffer(void *data, size_t size) {

    if (mReachedExtractorEOS) {
        return 0;
    }

    size_t size_done = 0;
    size_t size_remaining = size;

    while (size_remaining > 0) {
        MediaSource::ReadOptions options;
        {
            Mutex::Autolock autoLock(mLock);

            if (mSeeking || mInternalSeeking) {

                MediaSource::ReadOptions::SeekMode seekMode;
                seekMode = MediaSource::ReadOptions::SEEK_CLOSEST_SYNC;
                options.setSeekTo(mSeekTimeUs, seekMode );
                if (mInputBuffer != NULL) {
                    mInputBuffer->release();
                    mInputBuffer = NULL;
                }

                // This is to ignore the data already filled in the output buffer
                size_done = 0;
                size_remaining = size;

                mSeeking = false;
                if (mObserver && !mAsyncReset && !mInternalSeeking) {
                    LOGD("fillBuffer: Posting audio seek complete event");
                    mObserver->postAudioSeekComplete();
                }
                mInternalSeeking = false;
            }
        }
        if (mInputBuffer == NULL) {
            status_t err;
            err = mSource->read(&mInputBuffer, &options);

            CHECK((err == OK && mInputBuffer != NULL)
                  || (err != OK && mInputBuffer == NULL));
            {
                Mutex::Autolock autoLock(mLock);

                if (err != OK) {
                    LOGD("fill buffer - reached eos true");
                    mReachedExtractorEOS = true;
                    mFinalStatus = err;
                    break;
                }
            }

        }
        if (mInputBuffer->range_length() == 0) {
            mInputBuffer->release();
            mInputBuffer = NULL;
            continue;
        }

        size_t copy = size_remaining;
        if (copy > mInputBuffer->range_length()) {
            copy = mInputBuffer->range_length();
        }
        memcpy((char *)data + size_done,
               (const char *)mInputBuffer->data() + mInputBuffer->range_offset(),
               copy);

        mInputBuffer->set_range(mInputBuffer->range_offset() + copy,
                                mInputBuffer->range_length() - copy);

        size_done += copy;
        size_remaining -= copy;
    }
    if(mReachedExtractorEOS)
        memset((char *)data + size_done, 0x0, size_remaining);
    LOGV("fill buffer size_done = %d",size_done);
    return size_done;
}

int64_t TunnelPlayer::getRealTimeUs() {
    Mutex::Autolock autoLock(mLock);

    mPositionTimeRealUs = 0;
    return mPositionTimeRealUs;
}

int64_t TunnelPlayer::getMediaTimeUs() {
    Mutex::Autolock autoLock(mLock);
    if (mIsPaused) {
        LOGV("paused = %lld",mTimePaused);
        return mTimePaused;
    }
    else {
          LOGV("mSeekTimeUs = %lld", mSeekTimeUs);
          return  ( mSeekTimeUs + getAudioTimeStampUs());
    }
}

bool TunnelPlayer::getMediaTimeMapping(
                                   int64_t *realtime_us, int64_t *mediatime_us) {
    Mutex::Autolock autoLock(mLock);
    mPositionTimeMediaUs = (mSeekTimeUs + getAudioTimeStampUs());
    *realtime_us = mPositionTimeRealUs;
    *mediatime_us = mPositionTimeMediaUs;

    return mPositionTimeRealUs != -1 && mPositionTimeMediaUs != -1;
}

void TunnelPlayer::requestAndWaitForExtractorThreadExit() {

    if (!mExtractorThreadAlive)
        return;
    mKillExtractorThread = true;
    mExtractorCv.signal();
    pthread_join(mExtractorThread,NULL);
    LOGD("Extractor thread killed");
}

void TunnelPlayer::requestAndWaitForEventThreadExit() {

    if (!mEventThreadAlive)
        return;
    mKillEventThread = true;
    if(mEfd != -1) {
        uint64_t writeValue = KILL_EVENT_THREAD;
        LOGD("Writing to mEfd %d",mEfd);
        write(mEfd, &writeValue, sizeof(uint64_t));
    }
    mEventCv.signal();
    pthread_join(mEventThread,NULL);
    LOGD("event thread killed");
}

void TunnelPlayer::requestAndWaitForA2DPThreadExit() {

    if (!mA2dpThreadAlive) {
        LOGD("Return - thread not live");
        return;
    }
    mKillA2DPThread = true;
    if(mA2DPEfd != -1) {
        uint64_t writeValue = KILL_A2DP_THREAD;
        LOGD("Writing to mEfd %d",mEfd);
        write(mA2DPEfd, &writeValue, sizeof(uint64_t));
    }
    mA2dpCv.signal();
    pthread_join(mA2DPThread,NULL);
    LOGD("a2dp thread killed");
}

void TunnelPlayer::requestAndWaitForA2DPNotificationThreadExit() {

    if (!mA2dpNotificationThreadAlive)
        return;
    mKillA2DPNotificationThread = true;
    mA2dpNotificationCv.signal();
    pthread_join(mA2DPNotificationThread,NULL);
    LOGD("a2dp notification thread killed");
}

void TunnelPlayer::onPauseTimeOut() {
    Mutex::Autolock autoLock(mLock);
    LOGD("onPauseTimeOut");
    if (!mPauseEventPending) {
        return;
    }
    mPauseEventPending = false;

    if(!mIsA2DPEnabled) {

        if(!mSeeking) {
            mInternalSeeking = true;
            mSeekTimeUs += getAudioTimeStampUs();
        }
        if(mReachedExtractorEOS) {
            mReachedExtractorEOS = false;
            mReachedDecoderEOS = false;
            mTimeout = -1;
        }

        LOGD("onPauseTimeOut seektime= %lld", mSeekTimeUs);

        mInputIonResponseMutex.lock();
        mInputIonRequestMutex.lock();
        mInputIonFilledQueue.clear();
        mInputIonEmptyQueue.clear();
        List<BuffersAllocated>::iterator it = mInputBufPool.begin();
        for(;it!=mInputBufPool.end();++it) {
             mInputIonEmptyQueue.push_back(*it);
        }
        mInputIonRequestMutex.unlock();
        mInputIonResponseMutex.unlock();
        LOGV("onPauseTimeOut after Empty Queue size() = %d,\
                Filled Queue.size() = %d ",\
                mInputIonEmptyQueue.size(),\
                mInputIonFilledQueue.size());
        mAudioSink->closeSession();
        mIsAudioRouted = false;
        release_wake_lock("TUNNEL_LOCK");
    }
}

status_t  TunnelPlayer::setPlaybackALSAParams() {

    struct pcm * pcm_handle = (struct pcm *)mPlaybackHandle;
    struct snd_compr_caps compr_cap;
    struct snd_compr_params compr_params;
    status_t err = OK;
    int32_t minPeroid, maxPeroid;
    struct snd_pcm_hw_params *hwParams = NULL;
    struct snd_pcm_sw_params *swParams = NULL;

    LOGD("setPlaybackALSAParams");

    if (ioctl(pcm_handle->fd, SNDRV_COMPRESS_GET_CAPS, &compr_cap)) {
        LOGE("SNDRV_COMPRESS_GET_CAPS, failed Error no %d \n", errno);
        err = -errno;
        goto fail;
    }

    minPeroid = compr_cap.min_fragment_size;
    maxPeroid = compr_cap.max_fragment_size;
    LOGV("Min peroid size = %d , Maximum Peroid size = %d",\
            minPeroid, maxPeroid);
    if( !strcasecmp(mMimeType,MEDIA_MIMETYPE_AUDIO_AAC) ) {
        LOGW("### AAC CODEC");
        compr_params.codec.id = compr_cap.codecs[1];
    }
    else {
         LOGW("### MP3 CODEC");
         compr_params.codec.id = compr_cap.codecs[0];
    }
    if (ioctl(pcm_handle->fd, SNDRV_COMPRESS_SET_PARAMS, &compr_params)) {
        LOGE("SNDRV_COMPRESS_SET_PARAMS,failed Error no %d \n", errno);
        err = -errno;
        goto fail;
    }

    hwParams = (struct snd_pcm_hw_params*)
           calloc(1, sizeof(struct snd_pcm_hw_params));
    if (!hwParams) {
        LOGV( "Failed to allocate ALSA hardware parameters!");
        err = -1;
        goto fail;
    }
    param_init(hwParams);
    param_set_mask(hwParams, SNDRV_PCM_HW_PARAM_ACCESS,
            (pcm_handle->flags & PCM_MMAP) ? SNDRV_PCM_ACCESS_MMAP_INTERLEAVED
            : SNDRV_PCM_ACCESS_RW_INTERLEAVED);
    param_set_mask(hwParams, SNDRV_PCM_HW_PARAM_FORMAT,
            SNDRV_PCM_FORMAT_S16_LE);
    param_set_mask(hwParams, SNDRV_PCM_HW_PARAM_SUBFORMAT,
            SNDRV_PCM_SUBFORMAT_STD);

   if(mHasVideo)
        param_set_min(hwParams, SNDRV_PCM_HW_PARAM_PERIOD_TIME, 10);
   else {
        mInputBufferSize = ION_BUFFER_SIZE;
        param_set_min(hwParams, SNDRV_PCM_HW_PARAM_PERIOD_BYTES,
                mInputBufferSize);
   }

    param_set_int(hwParams, SNDRV_PCM_HW_PARAM_SAMPLE_BITS, 16);
    param_set_int(hwParams, SNDRV_PCM_HW_PARAM_FRAME_BITS,
                mNumChannels - 1 ? 32 : 16);
    param_set_int(hwParams, SNDRV_PCM_HW_PARAM_CHANNELS, mNumChannels);
    param_set_int(hwParams, SNDRV_PCM_HW_PARAM_RATE, mSampleRate);
    param_set_hw_refine(pcm_handle, hwParams);
    if (param_set_hw_params(pcm_handle, hwParams)) {
        LOGV( "Cannot set ALSA HW params");
         err = -22;
        goto fail;
    }
    param_dump(hwParams);
    pcm_handle->buffer_size = pcm_buffer_size(hwParams);
    pcm_handle->period_size = pcm_period_size(hwParams);
    pcm_handle->period_cnt = pcm_handle->buffer_size/pcm_handle->period_size;
    LOGD("period_cnt = %d\n", pcm_handle->period_cnt);
    LOGD("period_size = %d\n", pcm_handle->period_size);
    LOGD("buffer_size = %d\n", pcm_handle->buffer_size);

    mInputBufferSize =  pcm_handle->period_size;
    mInputBufferCount =  pcm_handle->period_cnt;
    /*if(mInputBufferCount > (ION_BUFFER_COUNT << 1)) {
        LOGE("mInputBufferCount = %d , (ION_BUFFER_COUNT << 1) = %d,\
        ION_BUFFER_COUNT = %d",mInputBufferCount,\
        (ION_BUFFER_COUNT << 1), ION_BUFFER_COUNT);
        mInputBufferCount = (ION_BUFFER_COUNT << 1);
    }*/

    swParams = (struct snd_pcm_sw_params*)
            calloc(1, sizeof(struct snd_pcm_sw_params));
    if (!swParams) {
        LOGV( "Failed to allocate ALSA software parameters!\n");
        err = -1;
        goto fail;
    }
    // Get the current software parameters
    swParams->tstamp_mode = SNDRV_PCM_TSTAMP_NONE;
    swParams->period_step = 1;
    //TODO : Avail minimum and start threshold what are right values?
    swParams->avail_min = pcm_handle->period_size/2;
    swParams->start_threshold = pcm_handle->period_size/2;
    swParams->stop_threshold =  pcm_handle->buffer_size;
    /* needed for old kernels */
    swParams->xfer_align = (pcm_handle->flags & PCM_MONO) ?
            pcm_handle->period_size/2 : pcm_handle->period_size/4;
    swParams->silence_size = 0;
    swParams->silence_threshold = 0;
    if (param_set_sw_params(pcm_handle, swParams)) {
        LOGV( "Cannot set ALSA SW params");
        err = -22;
        goto fail;
    }

fail:
    return err;
}

status_t  TunnelPlayer::setCaptureALSAParams() {

     struct pcm * capture_handle = (struct pcm *)mCaptureHandle;
     struct snd_pcm_hw_params *hwParams = NULL;
     struct snd_pcm_sw_params *swParams = NULL;
     status_t err = OK;

     LOGV("setCaptureALSAParams");

    capture_handle->channels = AFE_PROXY_CHANNEL_COUNT;
    capture_handle->rate = AFE_PROXY_SAMPLE_RATE;
    capture_handle->period_size = AFE_PROXY_PERIOD_SIZE;

    hwParams = (struct snd_pcm_hw_params*)
             calloc(1, sizeof(struct snd_pcm_hw_params));
    if (!hwParams) {
         LOGE("Failed to allocate ALSA hardware parameters - Capture!");
         err = -ENOMEM;
         goto fail;
    }

    param_init(hwParams);

    param_set_mask(hwParams, SNDRV_PCM_HW_PARAM_ACCESS,
            (capture_handle->flags & PCM_MMAP)?
            SNDRV_PCM_ACCESS_MMAP_INTERLEAVED :
            SNDRV_PCM_ACCESS_RW_INTERLEAVED);
    param_set_mask(hwParams, SNDRV_PCM_HW_PARAM_FORMAT, SNDRV_PCM_FORMAT_S16_LE);
    param_set_mask(hwParams, SNDRV_PCM_HW_PARAM_SUBFORMAT,
            SNDRV_PCM_SUBFORMAT_STD);
    param_set_min(hwParams, SNDRV_PCM_HW_PARAM_PERIOD_BYTES,
            capture_handle->period_size);

     //param_set_min(hwParams, SNDRV_PCM_HW_PARAM_PERIOD_TIME, 10);
     param_set_int(hwParams, SNDRV_PCM_HW_PARAM_SAMPLE_BITS, 16);
     param_set_int(hwParams, SNDRV_PCM_HW_PARAM_FRAME_BITS,
                    (AFE_PROXY_CHANNEL_COUNT - 1) ? 32 : 16);

     param_set_int(hwParams, SNDRV_PCM_HW_PARAM_CHANNELS,
             capture_handle->channels);
     param_set_int(hwParams, SNDRV_PCM_HW_PARAM_RATE,
             capture_handle->rate);

     param_set_hw_refine(capture_handle, hwParams);

     if (param_set_hw_params(capture_handle, hwParams)) {
         LOGE("Cannot set hw params - Capture");
         err = -errno;
         goto fail;
     }

     param_dump(hwParams);
     capture_handle->buffer_size = pcm_buffer_size(hwParams);
     capture_handle->period_size = pcm_period_size(hwParams);
     capture_handle->period_cnt =
             capture_handle->buffer_size/capture_handle->period_size;
     LOGV("Capture - period_size (%d)", capture_handle->period_size);
     LOGV("Capture - buffer_size (%d)", capture_handle->buffer_size);
     LOGV("Capture - period_cnt  (%d)\n", capture_handle->period_cnt);

     mCaptureBufferSize = capture_handle->period_size;
     mCaptureBufferCount = capture_handle->period_cnt;

     //Set Software params
     swParams = (struct snd_pcm_sw_params*)
            calloc(1, sizeof(struct snd_pcm_sw_params));
     if (!swParams) {
         LOGE("Failed to allocate ALSA software parameters -Capture !\n");
         err = -ENOMEM;
         goto fail;
     }

     swParams->tstamp_mode = SNDRV_PCM_TSTAMP_NONE;
     swParams->period_step = 1;
     swParams->avail_min = (capture_handle->flags & PCM_MONO) ?
             capture_handle->period_size/2 : capture_handle->period_size/4;
     swParams->start_threshold = 1;
     swParams->stop_threshold = capture_handle->buffer_size;

     /* needed for old kernels */
     swParams->xfer_align = (capture_handle->flags & PCM_MONO) ?
            capture_handle->period_size/2 : capture_handle->period_size/4;
     swParams->silence_size = 0;
     swParams->silence_threshold = 0;

     if (param_set_sw_params(capture_handle, swParams)) {
         LOGE("Cannot set sw params - Capture");
         err = -22;
         goto fail;
     }

fail:
     return err;
}

int64_t TunnelPlayer::getAudioTimeStampUs() {

    struct pcm * local_handle = (struct pcm *)mPlaybackHandle;
    struct snd_compr_tstamp tstamp;

    if (ioctl(local_handle->fd, SNDRV_COMPRESS_TSTAMP, &tstamp)) {
        LOGE("Tunnel Player: failed SNDRV_COMPRESS_TSTAMP\n");
        return 0;
    }
    else {
        LOGV("timestamp = %lld\n", tstamp.timestamp);
        return (tstamp.timestamp + RENDER_LATENCY);
    }
}

bool TunnelPlayer::isReadyToPostEOS(int errPoll, void *fd) {

    struct pollfd *pfd =  (struct pollfd *) fd;
    if (    //timeout return value for poll or event on the fd
            (errPoll == 0 || (pfd[0].revents & POLLIN &&

            //Filled Queue has only the last buffer
            mInputIonFilledQueue.size() == 1)) &&

            //EOS from parser set to true
            mReachedExtractorEOS &&

            //No kill thread signal from client
            !mKillEventThread &&

            //Not paused. Reqd when u pause with
            //2 sec of playback left and EOS is set
            !(mIsPaused && !mPlayPendingSamples)) {

        //positive timeout value set for EOS
        if(mTimeout != -1) {
            LOGD("isReadyToPostEOS true");
            return true;
        }
        else {
            mTimeout = ((mDurationUs - (mSeekTimeUs + getAudioTimeStampUs())) / 1000);
            LOGD("Recalculate timeout = %d,mSeekTimeUs=%lld,mDurationUs=%lld",mTimeout,mSeekTimeUs,mDurationUs);
            return false;
        }
    }
    else {
        return false;
    }
}

status_t TunnelPlayer::setCaptureMixerControl(int value) {


    struct mixer *mixer;
    struct mixer_ctl *ctl;
    LOGD("setCaptureMixerControl = %d", value);
    status_t status = NO_ERROR;
    const char* device = "/dev/snd/controlC0";

    mixer = mixer_open(device);
    if (!mixer){
        LOGE("Mixer Open failed ");
        return -1;
    }

    ctl = mixer_get_control(mixer , "AFE_PCM_RX Audio Mixer MultiMedia4" ,0);
    if (!ctl) {
        LOGE("Cannot find control\n");
        mixer_close(mixer);
        return -1;
    }

    status =  mixer_ctl_set(ctl, value );
    if(status) {
        LOGE("mixer_ctl_set set failed");
        return status;
    }

    if(value == 1) {
        ctl = mixer_get_control(mixer, "COMPRESSED RX Volume", 0);
        if (!ctl) {
            LOGE("Volume : Cannot find control\n");
            mixer_close(mixer);
            return -1;
        }
        status =  mixer_ctl_set(ctl, 100 );
        if(status) {
            LOGE("Volume : mixer_ctl_set set failed");
            return status;
        }
    }

    mixer_close(mixer);

    return status;
}

void TunnelPlayer::postEOSOnError() {
    mFinalStatus = BAD_VALUE;
    mReachedDecoderEOS = true;
    if(mObserver) {
        mObserver->postAudioEOS();
    }
}

status_t  TunnelPlayer::syncResume(void *handle) {

    status_t err = NO_ERROR;
        struct pcm * local_handle = (struct pcm *)handle;
        if (!(mSeeking || mInternalSeeking)) {
            if (ioctl(local_handle->fd, SNDRV_PCM_IOCTL_PAUSE,0) < 0) {
                LOGE("AUDIO Resume failed");
                postEOSOnError();
                return err;
            }
            LOGV("Sync resume done\n");
        }
        else {
            local_handle->start = 0;
            err = pcm_prepare(local_handle);
            if(err) {
                postEOSOnError();
                return err;
            }
            LOGV("Reset, drain and prepare completed");
            local_handle->sync_ptr->flags =
                    SNDRV_PCM_SYNC_PTR_APPL | SNDRV_PCM_SYNC_PTR_AVAIL_MIN;
            sync_ptr(local_handle);
            LOGV("appl_ptr= %d",\
                    (int)local_handle->sync_ptr->c.control.appl_ptr);

        }
    return err;

}

status_t TunnelPlayer::flush(void * handle) {

     struct pcm * local_handle = (struct pcm *)handle;
     status_t err = NO_ERROR;
     if (!mIsPaused) {
         if (ioctl(local_handle->fd, SNDRV_PCM_IOCTL_PAUSE,1) < 0) {
              LOGE("Audio Pause failed");
              return UNKNOWN_ERROR;
         }
         local_handle->start = 0;
         err = pcm_prepare(local_handle);
         if(err)
             return err;
         LOGV("Reset, drain and prepare completed");
         local_handle->sync_ptr->flags =
                  SNDRV_PCM_SYNC_PTR_APPL | SNDRV_PCM_SYNC_PTR_AVAIL_MIN;
         sync_ptr(local_handle);
         LOGV("appl_ptr= %d",\
                   (int)local_handle->sync_ptr->c.control.appl_ptr);

     }
    return err;
}

} //namespace android
