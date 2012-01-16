/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_GUI_IMPL_CONNECTION_H
#define ANDROID_GUI_IMPL_CONNECTION_H

#include <stdint.h> 
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/RefBase.h>

#include <binder/IInterface.h> 

namespace android {
// ----------------------------------------------------------------------------

class SensorChannel;

class IMplConnection : public IInterface
{
public:
    DECLARE_META_INTERFACE(MplConnection);

    virtual int rpcAddGlyph(unsigned short GlyphID)=0;
    virtual int rpcBestGlyph(unsigned short *finalGesture)=0;
    virtual int rpcSetGlyphSpeedThresh(unsigned short speed)=0;
    virtual int rpcStartGlyph(void)=0;
    virtual int rpcStopGlyph(void)=0;
    virtual int rpcGetGlyph(int index, int *x, int *y)=0;
    virtual int rpcGetGlyphLength(unsigned short *length)=0;
    virtual int rpcClearGlyph(void)=0;
    virtual int rpcLoadGlyphs(unsigned char *libraryData)=0;
    virtual int rpcStoreGlyphs(unsigned char *libraryData,
                               unsigned short *length)=0;
    virtual int rpcSetGlyphProbThresh(unsigned short prob)=0;
    virtual int rpcGetLibraryLength(unsigned short *length)=0;
};

// ----------------------------------------------------------------------------

class BnMplConnection : public BnInterface<IMplConnection>
{
public:
    virtual status_t onTransact(uint32_t code,
                                const Parcel& data,
                                Parcel* reply,
                                uint32_t flags = 0);
};

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_GUI_IMPL_CONNECTION_H
