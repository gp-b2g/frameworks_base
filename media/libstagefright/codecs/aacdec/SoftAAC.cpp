/*
 * Copyright (C) 2011 The Android Open Source Project
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
#define LOG_TAG "SoftAAC"
#include <utils/Log.h>

#include "SoftAAC.h"

#include "pvmp4audiodecoder_api.h"

#include <media/stagefright/foundation/ADebug.h>

namespace android {

template<class T>
static void InitOMXParams(T *params) {
    params->nSize = sizeof(T);
    params->nVersion.s.nVersionMajor = 1;
    params->nVersion.s.nVersionMinor = 0;
    params->nVersion.s.nRevision = 0;
    params->nVersion.s.nStep = 0;
}

SoftAAC::SoftAAC(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component)
    : SimpleSoftOMXComponent(name, callbacks, appData, component),
      mConfig(new tPVMP4AudioDecoderExternal),
      mDecoderBuf(NULL),
      mInputBufferCount(0),
      mUpsamplingFactor(2),
      mAnchorTimeUs(0),
      mNumSamplesOutput(0),
      mSignalledError(false),
      mTempBufferDataLen(0),
      mTempBufferTotalSize(0),
      mTempInputBuffer(0),
      mInputBufferSize(0),
      mAACStreamFormat(-1),
      mOutputPortSettingsChange(NONE) {
    initPorts();
    CHECK_EQ(initDecoder(), (status_t)OK);
}

SoftAAC::~SoftAAC() {
    free(mDecoderBuf);
    mDecoderBuf = NULL;

    delete mConfig;
    mConfig = NULL;

    //Reset temp buffer
    if( mTempInputBuffer != NULL ) {
       free(mTempInputBuffer);
       mTempInputBuffer = NULL;
    }
}

void SoftAAC::initPorts() {
    OMX_PARAM_PORTDEFINITIONTYPE def;
    InitOMXParams(&def);

    def.nPortIndex = 0;
    def.eDir = OMX_DirInput;
    def.nBufferCountMin = kNumBuffers;
    def.nBufferCountActual = def.nBufferCountMin;
    def.nBufferSize = 8192;
    def.bEnabled = OMX_TRUE;
    def.bPopulated = OMX_FALSE;
    def.eDomain = OMX_PortDomainAudio;
    def.bBuffersContiguous = OMX_FALSE;
    def.nBufferAlignment = 1;

    def.format.audio.cMIMEType = const_cast<char *>("audio/aac");
    def.format.audio.pNativeRender = NULL;
    def.format.audio.bFlagErrorConcealment = OMX_FALSE;
    def.format.audio.eEncoding = OMX_AUDIO_CodingAAC;

    addPort(def);

    def.nPortIndex = 1;
    def.eDir = OMX_DirOutput;
    def.nBufferCountMin = kNumBuffers;
    def.nBufferCountActual = def.nBufferCountMin;
    def.nBufferSize = 8192;
    def.bEnabled = OMX_TRUE;
    def.bPopulated = OMX_FALSE;
    def.eDomain = OMX_PortDomainAudio;
    def.bBuffersContiguous = OMX_FALSE;
    def.nBufferAlignment = 2;

    def.format.audio.cMIMEType = const_cast<char *>("audio/raw");
    def.format.audio.pNativeRender = NULL;
    def.format.audio.bFlagErrorConcealment = OMX_FALSE;
    def.format.audio.eEncoding = OMX_AUDIO_CodingPCM;

    addPort(def);
}

status_t SoftAAC::initDecoder() {
    memset(mConfig, 0, sizeof(tPVMP4AudioDecoderExternal));
    mConfig->outputFormat = OUTPUTFORMAT_16PCM_INTERLEAVED;
    mConfig->aacPlusEnabled = 1;

    // The software decoder doesn't properly support mono output on
    // AACplus files. Always output stereo.
    mConfig->desiredChannels = 2;

    UInt32 memRequirements = PVMP4AudioDecoderGetMemRequirements();
    mDecoderBuf = malloc(memRequirements);

    Int err = PVMP4AudioDecoderInitLibrary(mConfig, mDecoderBuf);
    if (err != MP4AUDEC_SUCCESS) {
        LOGE("Failed to initialize MP4 audio decoder");
        return UNKNOWN_ERROR;
    }

    return OK;
}

OMX_ERRORTYPE SoftAAC::internalGetParameter(
        OMX_INDEXTYPE index, OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamAudioAac:
        {
            OMX_AUDIO_PARAM_AACPROFILETYPE *aacParams =
                (OMX_AUDIO_PARAM_AACPROFILETYPE *)params;

            if (aacParams->nPortIndex != 0) {
                return OMX_ErrorUndefined;
            }

            aacParams->nBitRate = 0;
            aacParams->nAudioBandWidth = 0;
            aacParams->nAACtools = 0;
            aacParams->nAACERtools = 0;
            aacParams->eAACProfile = OMX_AUDIO_AACObjectMain;
            aacParams->eAACStreamFormat = OMX_AUDIO_AACStreamFormatMP4FF;
            aacParams->eChannelMode = OMX_AUDIO_ChannelModeStereo;

            if (!isConfigured()) {
                aacParams->nChannels = 1;
                aacParams->nSampleRate = 44100;
                aacParams->nFrameLength = 0;
            } else {
                aacParams->nChannels = mConfig->encodedChannels;
                aacParams->nSampleRate = mConfig->samplingRate;
                aacParams->nFrameLength = mConfig->frameLength;
            }

            return OMX_ErrorNone;
        }

        case OMX_IndexParamAudioPcm:
        {
            OMX_AUDIO_PARAM_PCMMODETYPE *pcmParams =
                (OMX_AUDIO_PARAM_PCMMODETYPE *)params;

            if (pcmParams->nPortIndex != 1) {
                return OMX_ErrorUndefined;
            }

            pcmParams->eNumData = OMX_NumericalDataSigned;
            pcmParams->eEndian = OMX_EndianBig;
            pcmParams->bInterleaved = OMX_TRUE;
            pcmParams->nBitPerSample = 16;
            pcmParams->ePCMMode = OMX_AUDIO_PCMModeLinear;
            pcmParams->eChannelMapping[0] = OMX_AUDIO_ChannelLF;
            pcmParams->eChannelMapping[1] = OMX_AUDIO_ChannelRF;

            if (!isConfigured()) {
                pcmParams->nChannels = 1;
                pcmParams->nSamplingRate = 44100;
            } else {
                pcmParams->nChannels = mConfig->desiredChannels;
                pcmParams->nSamplingRate = mConfig->samplingRate;
            }

            return OMX_ErrorNone;
        }

        default:
            return SimpleSoftOMXComponent::internalGetParameter(index, params);
    }
}

OMX_ERRORTYPE SoftAAC::internalSetParameter(
        OMX_INDEXTYPE index, const OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamStandardComponentRole:
        {
            const OMX_PARAM_COMPONENTROLETYPE *roleParams =
                (const OMX_PARAM_COMPONENTROLETYPE *)params;

            if (strncmp((const char *)roleParams->cRole,
                        "audio_decoder.aac",
                        OMX_MAX_STRINGNAME_SIZE - 1)) {
                return OMX_ErrorUndefined;
            }

            return OMX_ErrorNone;
        }

        case OMX_IndexParamAudioAac:
        {
            const OMX_AUDIO_PARAM_AACPROFILETYPE *aacParams =
                (const OMX_AUDIO_PARAM_AACPROFILETYPE *)params;

            if (aacParams->nPortIndex != 0) {
                return OMX_ErrorUndefined;
            }

            mAACStreamFormat = aacParams->eAACStreamFormat;
            LOGD(" mIsAacFormatAdif = %d ",mAACStreamFormat);

            return OMX_ErrorNone;
        }

        default:
            return SimpleSoftOMXComponent::internalSetParameter(index, params);
    }
}

bool SoftAAC::isConfigured() const {
    return mInputBufferCount > 0;
}

void SoftAAC::onQueueFilled(OMX_U32 portIndex) {
    static bool checkFragment = false;
    if (mSignalledError || mOutputPortSettingsChange != NONE) {
        return;
    }

    List<BufferInfo *> &inQueue = getPortQueue(0);
    List<BufferInfo *> &outQueue = getPortQueue(1);

    if (portIndex == 0 && mInputBufferCount == 0) {
        ++mInputBufferCount;

        BufferInfo *info = *inQueue.begin();
        OMX_BUFFERHEADERTYPE *header = info->mHeader;

        mConfig->pInputBuffer = header->pBuffer + header->nOffset;
        mConfig->inputBufferCurrentLength = header->nFilledLen;
        mConfig->inputBufferMaxLength = 0;

        Int err = PVMP4AudioDecoderConfig(mConfig, mDecoderBuf);
        if (err != MP4AUDEC_SUCCESS) {
            mSignalledError = true;
            notify(OMX_EventError, OMX_ErrorUndefined, err, NULL);
            return;
        }

        inQueue.erase(inQueue.begin());
        info->mOwnedByUs = false;
        notifyEmptyBufferDone(header);

        notify(OMX_EventPortSettingsChanged, 1, 0, NULL);
        mOutputPortSettingsChange = AWAITING_DISABLED;
        return;
    }

    uint8_t* inputBuffer = NULL;
    uint32_t inputBufferSize = 0;

    while (!inQueue.empty() && !outQueue.empty()) {
        BufferInfo *inInfo = *inQueue.begin();
        OMX_BUFFERHEADERTYPE *inHeader = inInfo->mHeader;

        BufferInfo *outInfo = *outQueue.begin();
        OMX_BUFFERHEADERTYPE *outHeader = outInfo->mHeader;

        if (inHeader->nFlags & OMX_BUFFERFLAG_EOS) {
            inQueue.erase(inQueue.begin());
            inInfo->mOwnedByUs = false;
            notifyEmptyBufferDone(inHeader);

            outHeader->nFilledLen = 0;
            outHeader->nFlags = OMX_BUFFERFLAG_EOS;

            outQueue.erase(outQueue.begin());
            outInfo->mOwnedByUs = false;
            notifyFillBufferDone(outHeader);

            //Reset temp buffer
            if( mTempInputBuffer != NULL ) {
               free(mTempInputBuffer);
               mTempInputBuffer = NULL;
            }
            return;
        }

        if (inHeader->nOffset == 0) {
            mAnchorTimeUs = inHeader->nTimeStamp;
            mNumSamplesOutput = 0;
        }

        inputBuffer = inHeader->pBuffer + inHeader->nOffset;
        inputBufferSize = inHeader->nFilledLen;


        if ( mInputBufferSize == 0 ) {
            // Remember the first input buffer size
            mInputBufferSize = inHeader->nFilledLen;
        }

        //Check if there was incomplete frame assembly started
        if (checkFragment && mTempBufferDataLen ) {
            LOGV("Incomplete frame assembly is in progress mTempBufferDataLen %d", mTempBufferDataLen);
            LOGV("mTempBufferDataLen(%d) inputBufferSize(%d) mTempBufferTotalSize(%d)",
                                         mTempBufferDataLen,inputBufferSize,mTempBufferTotalSize);

            if ( mTempBufferDataLen + inputBufferSize > mTempBufferTotalSize ) {
                LOGD("Temp buffer size exceeded %d input size %d", mTempBufferTotalSize, inputBufferSize);
                notify(OMX_EventError, OMX_ErrorUndefined, UNKNOWN_ERROR, NULL);
                return;
            }
            //append new input buffer to temp buffer
            memcpy( mTempInputBuffer + mTempBufferDataLen, inputBuffer, inputBufferSize );

            //update the new input buffer data
            if ( inputBufferSize + mTempBufferDataLen < mInputBufferSize ) {
                LOGV("Reached end of stream case" );
                inputBufferSize += mTempBufferDataLen;
                mTempBufferDataLen = 0;
                mInputBufferSize = inputBufferSize;
                // Watch for this issue, not very sure if needed
                inHeader->nFilledLen = inputBufferSize;
            }
            memcpy( inputBuffer, mTempInputBuffer, inputBufferSize);
            checkFragment = false;
        }


        //Get the input buffer
        LOGD(" Input Buffer Length %d Offset %d size %d", inHeader->nFilledLen,  inHeader->nOffset, mInputBufferSize);

        mConfig->pInputBuffer = inputBuffer;
        mConfig->inputBufferCurrentLength = inputBufferSize;

        mConfig->inputBufferMaxLength = 0;
        mConfig->inputBufferUsedLength = 0;
        mConfig->remainderBits = 0;

        mConfig->pOutputBuffer =
            reinterpret_cast<Int16 *>(outHeader->pBuffer + outHeader->nOffset);

        mConfig->pOutputBuffer_plus = &mConfig->pOutputBuffer[2048];
        mConfig->repositionFlag = false;

        Int32 prevSamplingRate = mConfig->samplingRate;
        Int decoderErr = PVMP4AudioDecodeFrame(mConfig, mDecoderBuf);

     /*
         * AAC+/eAAC+ streams can be signalled in two ways: either explicitly
         * or implicitly, according to MPEG4 spec. AAC+/eAAC+ is a dual
         * rate system and the sampling rate in the final output is actually
         * doubled compared with the core AAC decoder sampling rate.
         *
         * Explicit signalling is done by explicitly defining SBR audio object
         * type in the bitstream. Implicit signalling is done by embedding
         * SBR content in AAC extension payload specific to SBR, and hence
         * requires an AAC decoder to perform pre-checks on actual audio frames.
         *
         * Thus, we could not say for sure whether a stream is
         * AAC+/eAAC+ until the first data frame is decoded.
         */
        if (decoderErr == MP4AUDEC_SUCCESS && mInputBufferCount <= 2) {
            LOGV("audio/extended audio object type: %d + %d",
                mConfig->audioObjectType, mConfig->extendedAudioObjectType);
            LOGV("aac+ upsampling factor: %d desired channels: %d",
                mConfig->aacPlusUpsamplingFactor, mConfig->desiredChannels);

            if (mInputBufferCount == 1) {
                mUpsamplingFactor = mConfig->aacPlusUpsamplingFactor;
                // Check on the sampling rate to see whether it is changed.
                if (mConfig->samplingRate != prevSamplingRate) {
                    LOGW("Sample rate was %d Hz, but now is %d Hz",
                            prevSamplingRate, mConfig->samplingRate);

                    // We'll hold onto the input buffer and will decode
                    // it again once the output port has been reconfigured.

                    notify(OMX_EventPortSettingsChanged, 1, 0, NULL);
                    mOutputPortSettingsChange = AWAITING_DISABLED;
                    return;
                }
            } else {  // mInputBufferCount == 2
                if (mConfig->extendedAudioObjectType == MP4AUDIO_AAC_LC ||
                    mConfig->extendedAudioObjectType == MP4AUDIO_LTP) {
                    if (mUpsamplingFactor == 2) {
                        // The stream turns out to be not aacPlus mode anyway
                        LOGW("Disable AAC+/eAAC+ since extended audio object "
                             "type is %d",
                             mConfig->extendedAudioObjectType);
                        mConfig->aacPlusEnabled = 0;
                    }
                } else {
                    if (mUpsamplingFactor == 1) {
                        // aacPlus mode does not buy us anything, but to cause
                        // 1. CPU load to increase, and
                        // 2. a half speed of decoding
                        LOGW("Disable AAC+/eAAC+ since upsampling factor is 1");
                        mConfig->aacPlusEnabled = 0;
                    }
                }
            }
        }


        size_t numOutBytes =
            mConfig->frameLength * sizeof(int16_t) * mConfig->desiredChannels;


        if( (decoderErr == MP4AUDEC_INCOMPLETE_FRAME)  &&
            (mAACStreamFormat == OMX_AUDIO_AACStreamFormatADIF)) {
            LOGD("Handle Incomplete frame error inputBufSize %d, usedLength %d",
                  inputBufferSize, mConfig->inputBufferUsedLength);

            if(mConfig->inputBufferUsedLength == mInputBufferSize){
                LOGW("Decoder cannot process the buffer due to invalid frame");
                decoderErr = MP4AUDEC_INVALID_FRAME;
            }else {
                if( !mTempInputBuffer ) {
                    //Allocate Temp buffer
                    uint32_t bytesToAllocate = 2 * mInputBufferSize;
                    mTempInputBuffer = (uint8_t*)malloc( bytesToAllocate );
                    mTempBufferDataLen = 0;
                    if (mTempInputBuffer == NULL) {
                        LOGE("Could not allocate temp buffer bytesToAllocate quit playing");
                              notify(OMX_EventError, OMX_ErrorUndefined, UNKNOWN_ERROR, NULL);
                        return ;
                    }
                    mTempBufferTotalSize = bytesToAllocate;
                    LOGV("Allocated tempBuffer of size %d data len %d",
                          mTempBufferTotalSize, mTempBufferDataLen);
                }

                // copy the remaining data into temp buffer
                memcpy( mTempInputBuffer, inputBuffer, mConfig->inputBufferUsedLength );

                if (mTempBufferDataLen != 0) {
                    //append previous remaining data back into temp buffer
                    LOGV("Appending remaining data tempDataLen %d usedLength %d",
                          mTempBufferDataLen, mConfig->inputBufferUsedLength);
                    memcpy( mTempInputBuffer + mConfig->inputBufferUsedLength,
                            mTempInputBuffer + mInputBufferSize,
                            mTempBufferDataLen );
                }

                mTempBufferDataLen += mConfig->inputBufferUsedLength;
                LOGV("mTempBufferDataLen %d inputBufferUsedLength %d ",
                     mTempBufferDataLen, mConfig->inputBufferUsedLength);

                // temp buffer has accumulated one frame size worth data
                // copy it back to input buffer so that it is fed to decoder next
                if ( mTempBufferDataLen >= mInputBufferSize ) {
                    LOGV("mTempBufferDataLen %d exceeded mInputBufferSize %d ",
                          mTempBufferDataLen, mInputBufferSize);

                    memcpy((UChar*)(inHeader->pBuffer), mTempInputBuffer, mInputBufferSize );
                    mTempBufferDataLen -= mInputBufferSize;
                    inHeader->nFilledLen = mInputBufferSize;
                    mConfig->inputBufferUsedLength = 0;
                }
                checkFragment = true;
                //reset the output buffer size
                numOutBytes = 0;
            }
        }


        if ((decoderErr == MP4AUDEC_SUCCESS)|| (decoderErr == MP4AUDEC_INCOMPLETE_FRAME)) {
            CHECK_LE(mConfig->inputBufferUsedLength, inHeader->nFilledLen);

            inHeader->nFilledLen -= mConfig->inputBufferUsedLength;
            inHeader->nOffset += mConfig->inputBufferUsedLength;

            if((decoderErr == MP4AUDEC_SUCCESS) &&(inHeader->nFilledLen == 0) &&
                mTempBufferDataLen) {
                //put previous remaining data to temp buffer beginning
                memcpy( mTempInputBuffer,
                        mTempInputBuffer + mInputBufferSize,
                        mTempBufferDataLen );
            }
        } else if(decoderErr != MP4AUDEC_SUCCESS && decoderErr != MP4AUDEC_INCOMPLETE_FRAME) {
            LOGW(" AAC decoder returned error %d, substituting silence",
                 decoderErr);

            memset(outHeader->pBuffer + outHeader->nOffset, 0, numOutBytes);

            // Discard input buffer.
            inHeader->nFilledLen = 0;

            if(mTempBufferDataLen) {
                //put previous remaining data to temp buffer beginning
                memcpy( mTempInputBuffer,
                        mTempInputBuffer + mInputBufferSize,
                        mTempBufferDataLen );
            }

            // fall through
        }

        if (decoderErr == MP4AUDEC_SUCCESS || mNumSamplesOutput > 0) {
            // We'll only output data if we successfully decoded it or
            // we've previously decoded valid data, in the latter case
            // (decode failed) we'll output a silent frame.
            if (mUpsamplingFactor == 2) {
                if (mConfig->desiredChannels == 1) {
                    memcpy(&mConfig->pOutputBuffer[1024],
                           &mConfig->pOutputBuffer[2048],
                           numOutBytes * 2);
                }
                numOutBytes *= 2;
            }

            outHeader->nFilledLen = numOutBytes;
            outHeader->nFlags = 0;

            outHeader->nTimeStamp =
                mAnchorTimeUs
                    + (mNumSamplesOutput * 1000000ll) / mConfig->samplingRate;
            mNumSamplesOutput += mConfig->frameLength * mUpsamplingFactor;

            outInfo->mOwnedByUs = false;
            outQueue.erase(outQueue.begin());
            outInfo = NULL;
            notifyFillBufferDone(outHeader);
            outHeader = NULL;
        }

        if (inHeader != NULL && inHeader->nFilledLen == 0) {
            inInfo->mOwnedByUs = false;
            inQueue.erase(inQueue.begin());
            inInfo = NULL;
            notifyEmptyBufferDone(inHeader);
            inHeader = NULL;
        }

        if (decoderErr == MP4AUDEC_SUCCESS) {
            ++mInputBufferCount;
        }
    }
}

void SoftAAC::onPortFlushCompleted(OMX_U32 portIndex) {
    if (portIndex == 0) {
        // Make sure that the next buffer output does not still
        // depend on fragments from the last one decoded.
        PVMP4AudioDecoderResetBuffer(mDecoderBuf);
    }
}

void SoftAAC::onPortEnableCompleted(OMX_U32 portIndex, bool enabled) {
    if (portIndex != 1) {
        return;
    }

    switch (mOutputPortSettingsChange) {
        case NONE:
            break;

        case AWAITING_DISABLED:
        {
            CHECK(!enabled);
            mOutputPortSettingsChange = AWAITING_ENABLED;
            break;
        }

        default:
        {
            CHECK_EQ((int)mOutputPortSettingsChange, (int)AWAITING_ENABLED);
            CHECK(enabled);
            mOutputPortSettingsChange = NONE;
            break;
        }
    }
}

}  // namespace android

android::SoftOMXComponent *createSoftOMXComponent(
        const char *name, const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData, OMX_COMPONENTTYPE **component) {
    return new android::SoftAAC(name, callbacks, appData, component);
}
