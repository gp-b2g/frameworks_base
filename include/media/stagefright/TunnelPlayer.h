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

#ifndef TUNNEL_PLAYER_H_

#define TUNNEL_PLAYER_H_

#include "AudioPlayer.h"

#include <media/IAudioFlinger.h>
#include <utils/threads.h>
#include <utils/List.h>
#include <utils/Vector.h>
#include <pthread.h>
#include <binder/IServiceManager.h>

#include <linux/unistd.h>
#include <include/linux/msm_audio.h>

#include <include/TimedEventQueue.h>

// Pause timeout = 3sec
#define TUNNEL_PAUSE_TIMEOUT_USEC 3000000

namespace android {

class TunnelPlayer : public AudioPlayer  {
public:

    TunnelPlayer(const sp<MediaPlayerBase::AudioSink> &audioSink, bool &initCheck,
                AwesomePlayer *audioObserver = NULL, bool hasVideo = false);

    virtual ~TunnelPlayer();

    // Caller retains ownership of "source".
    virtual void setSource(const sp<MediaSource> &source);

    // Return time in us.
    virtual int64_t getRealTimeUs();

    virtual status_t start(bool sourceAlreadyStarted = false);

    virtual void pause(bool playPendingSamples = false);
    virtual void resume();

    // Returns the timestamp of the last buffer played (in us).
    virtual int64_t getMediaTimeUs();

    // Returns true if a mapping is established, i.e. the TunnelPlayer
    // has played at least one frame of audio.
    virtual bool getMediaTimeMapping(int64_t *realtime_us, int64_t *mediatime_us);

    virtual status_t seekTo(int64_t time_us);

    virtual bool isSeeking();
    virtual bool reachedEOS(status_t *finalStatus);


    void* mPlaybackHandle;
    void* mCaptureHandle;
    static int mTunnelObjectsAlive;

private:

    //Structure to hold pmem buffer information
    class BuffersAllocated {
    public:
        BuffersAllocated(void *buf1, int32_t nSize) :
        pmemBuf(buf1), pmemBufsize(nSize)
        {}
        void* pmemBuf;
        int32_t pmemBufsize;
        uint32_t bytesToWrite;
    };
    List<BuffersAllocated> mInputPmemEmptyQueue;
    List<BuffersAllocated> mInputPmemFilledQueue;
    List<BuffersAllocated> mInputBufPool;

    //Structure to recieve the BT notification from the flinger.
    class AudioFlingerTunnelDecodeClient: public IBinder::DeathRecipient, public BnAudioFlingerClient {
    public:
        AudioFlingerTunnelDecodeClient(void *obj);

        TunnelPlayer *pBaseClass;
        // DeathRecipient
        virtual void binderDied(const wp<IBinder>& who);

        // IAudioFlingerClient

        // indicate a change in the configuration of an output or input: keeps the cached
        // values for output/input parameters upto date in client process
        virtual void ioConfigChanged(int event, int ioHandle, void *param2);

        friend class TunnelPlayer;
    };

    //Audio Flinger related variables
    sp<IAudioFlinger> mAudioFlinger;
    sp<AudioFlingerTunnelDecodeClient> mAudioFlingerClient;
    friend class AudioFlingerTunnelDecodeClient;
    Mutex mAudioFlingerLock;

    //event fd to signal the EOS and Kill from the userspace
    int mEfd;

    //Declare all the threads
    pthread_t mEventThread;
    pthread_t mExtractorThread;
    pthread_t mA2DPThread;
    pthread_t mA2DPNotificationThread;

    //Kill Thread boolean
    bool mKillExtractorThread;
    bool mKillEventThread;
    bool mKillA2DPThread;
    bool mKillA2DPNotificationThread;

    //Thread alive boolean
    bool mExtractorThreadAlive;
    bool mEventThreadAlive;
    bool mA2dpThreadAlive;
    bool mA2dpNotificationThreadAlive;

    //Declare the condition Variables and Mutex
    Mutex mInputPmemRequestMutex;
    Mutex mInputPmemResponseMutex;
    Mutex mExtractorMutex;
    Mutex mEventMutex;
    Mutex mA2dpMutex;
    Mutex mA2dpNotificationMutex;

    Condition mEventCv;
    Condition mExtractorCv;
    Condition mA2dpCv;
    Condition mA2dpNotificationCv;

    //global lock for Tunnel Player
    Mutex mLock;
    Mutex mSeekLock;


    //Media source - (Parser  for tunnel mode)
    sp<MediaSource> mSource;

    //Buffer related variables
    MediaBuffer *mInputBuffer;
    uint32_t mInputBufferSize;
    int32_t mInputBufferCount;

    //Audio Parameters
    int mSampleRate;
    int32_t mNumChannels;
    String8 mMimeType;

    //Miscellaneous variables
    //Needed for AV sync
    int64_t mLatencyUs;
    bool mStarted;
    volatile bool mAsyncReset;
    bool mHasVideo;

    //Timestamp variable
    int64_t mPositionTimeMediaUs;
    int64_t mPositionTimeRealUs;
    int64_t mTimePaused;
    int64_t mSeekTimeUs;
    int64_t mDurationUs;
    int32_t mTimeout;

    //Seek variables
    bool mSeeking;
    bool mInternalSeeking;

    //EOS variables
    bool mReachedDecoderEOS;
    bool mReachedExtractorEOS;
    status_t mFinalStatus;

    //Pause variables 
    bool mIsPaused;
    bool mPlayPendingSamples;
    TimedEventQueue mQueue;
    bool mQueueStarted;
    sp<TimedEventQueue::Event>  mPauseEvent;
    bool mPauseEventPending;

    //Routing variables
    bool mAudioSinkOpen;
    bool mIsAudioRouted;

    //A2DP variables
    bool mA2dpDisconnectPause;
    volatile bool mIsA2DPEnabled;

    sp<MediaPlayerBase::AudioSink> mAudioSink;
    AwesomePlayer *mObserver;

    // helper function to obtain AudioFlinger service handle
    void getAudioFlinger();

    void handleA2DPSwitch();

    size_t fillBuffer(void *data, size_t size);

    int64_t getRealTimeUsLocked();

    void reset();

    void onPauseTimeOut();

    void pmemBufferAlloc(int32_t nSize);

    void pmemBufferDeAlloc();

    // make sure Decoder thread has exited
    void requestAndWaitForExtractorThreadExit();

    // make sure the event thread also exited
    void requestAndWaitForEventThreadExit();

    // make sure the A2dp thread also exited
    void requestAndWaitForA2DPThreadExit();

    // make sure the Effects thread also exited
    void requestAndWaitForA2DPNotificationThreadExit();

    //Thread functions
    static void *eventThreadWrapper(void *me);
    void eventThreadEntry();
    static void *extractorThreadWrapper(void *me);
    void extractorThreadEntry();
    static void *A2DPThreadWrapper(void *me);
    void A2DPThreadEntry();
    static void *A2DPNotificationThreadWrapper(void *me);
    void A2DPNotificationThreadEntry();

    void createThreads();


    status_t setPlaybackALSAParams();

    status_t setCaptureALSAParams();

    //Get time stamp from driver
    int64_t getAudioTimeStampUs();

    bool isReadyToPostEOS(int errPoll, void *fd);

    TunnelPlayer(const TunnelPlayer &);
    TunnelPlayer &operator=(const TunnelPlayer &);
};

struct TunnelEvent : public TimedEventQueue::Event {
    TunnelEvent(TunnelPlayer *player,
               void (TunnelPlayer::*method)())
        : mPlayer(player),
          mMethod(method) {
    }

protected:
    virtual ~TunnelEvent() {}

    virtual void fire(TimedEventQueue *queue, int64_t /* now_us */) {
        (mPlayer->*mMethod)();
    }

private:
    TunnelPlayer *mPlayer;
    void (TunnelPlayer::*mMethod)();

    TunnelEvent(const TunnelEvent &);
    TunnelEvent &operator=(const TunnelEvent &);
};

}  // namespace android

#endif  // TUNNEL_PLAYER_H_

