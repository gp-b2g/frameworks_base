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

#include <stdint.h>
#include <sys/types.h>
 
#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/Timers.h>
#include <utils/String8.h>
#include <utils/String16.h>

#include <binder/Parcel.h>
#include <binder/IInterface.h>

#include <gui/IMplConnection.h>

namespace android {
// ----------------------------------------------------------------------------

enum {
    ADD_GLYPH= IBinder::FIRST_CALL_TRANSACTION,
    BEST_GLYPH,
    SET_GLYPH_SPEED_THRESH,
    START_GLYPH,
    STOP_GLYPH,
    GET_GLYPH,
    GET_GLYPH_LENGTH,
    CLEAR_GLYPH,
    LOAD_GLYPHS,
    STORE_GLYPHS,
    SET_GLYPH_PROB_THRESH,
    GET_LIBRARY_LENGTH,
};

class BpMplConnection : public BpInterface<IMplConnection>
{
public:
    BpMplConnection(const sp<IBinder>& impl_m)
        : BpInterface<IMplConnection>(impl_m)
    {
    }

    /* Glyph interfaces */
    virtual int rpcAddGlyph(unsigned short GlyphID) {
        Parcel data, reply;
        int rv;
        data.writeInterfaceToken(IMplConnection::getInterfaceDescriptor());
        data.writeInt32(GlyphID);
        remote()->transact(ADD_GLYPH, data, &reply);
        rv = reply.readInt32();
        return rv;
     }

    virtual int rpcBestGlyph(unsigned short *finalGesture){
        Parcel data, reply;
        int rv;
        data.writeInterfaceToken(IMplConnection::getInterfaceDescriptor());
        remote()->transact(BEST_GLYPH, data, &reply);
        *finalGesture = reply.readInt32();
        rv = reply.readInt32();
        return rv;
     }

    virtual int rpcSetGlyphSpeedThresh(unsigned short speed){
        Parcel data, reply;
        int rv;
        data.writeInterfaceToken(IMplConnection::getInterfaceDescriptor());
        data.writeInt32(speed);
        remote()->transact(SET_GLYPH_SPEED_THRESH, data, &reply);
        rv = reply.readInt32();
        return rv;
    }
    virtual int rpcStartGlyph(void){
        Parcel data, reply;
        int rv;
        data.writeInterfaceToken(IMplConnection::getInterfaceDescriptor());
        remote()->transact(START_GLYPH, data, &reply);
        rv = reply.readInt32();
        return rv;
    }
    virtual int rpcStopGlyph(void){
        Parcel data, reply;
        int rv;
        data.writeInterfaceToken(IMplConnection::getInterfaceDescriptor());
        remote()->transact(STOP_GLYPH, data, &reply);
        rv = reply.readInt32();
        return rv;
    }
    virtual int rpcGetGlyph(int index, int *x, int *y){
        Parcel data, reply;
        int rv;
        data.writeInterfaceToken(IMplConnection::getInterfaceDescriptor());
        data.writeInt32(index);
        remote()->transact(GET_GLYPH, data, &reply);
        *x = reply.readInt32();
        *y = reply.readInt32();
        rv = reply.readInt32();
        return rv;
    }
    virtual int rpcGetGlyphLength(unsigned short *length){
        Parcel data, reply;
        int rv;
        data.writeInterfaceToken(IMplConnection::getInterfaceDescriptor());
        remote()->transact(GET_GLYPH_LENGTH, data, &reply);
        *length = (unsigned short)reply.readInt32();
        rv = reply.readInt32();
        return rv;
    }
    virtual int rpcClearGlyph(void){
        Parcel data, reply;
        int rv;
        data.writeInterfaceToken(IMplConnection::getInterfaceDescriptor());
        remote()->transact(CLEAR_GLYPH, data, &reply);
        rv = reply.readInt32();
        return rv;
    }
    virtual int rpcLoadGlyphs(unsigned char *libraryData){
        Parcel data, reply;
        int rv;
        int i;
        //this next line must track the MPL implementation of glyph
        int len = ((unsigned short)libraryData[0]*256+(unsigned short)libraryData[1]);
        data.writeInterfaceToken(IMplConnection::getInterfaceDescriptor());
        data.writeInt32(len);
        for(i=0;i<len;i++)data.writeInt32(libraryData[i]);
        remote()->transact(LOAD_GLYPHS, data, &reply);
        rv = reply.readInt32();
        return rv;
    }
    virtual int rpcStoreGlyphs(unsigned char *libraryData, unsigned short *length){
        Parcel data, reply;
        int rv;
        int i;
        data.writeInterfaceToken(IMplConnection::getInterfaceDescriptor());
        remote()->transact(STORE_GLYPHS, data, &reply);
        *length = reply.readInt32();
        for(i=0;i<(*length);i++) libraryData[i] = (unsigned char)reply.readInt32();
        rv = reply.readInt32();
        return rv;
    }
    virtual int rpcSetGlyphProbThresh(unsigned short prob){
        Parcel data, reply;
        int rv;
        data.writeInterfaceToken(IMplConnection::getInterfaceDescriptor());
        data.writeInt32(prob);
        remote()->transact(SET_GLYPH_PROB_THRESH, data, &reply);
        rv = reply.readInt32();
        return rv;
    }
    virtual int rpcGetLibraryLength(unsigned short *length){
        Parcel data, reply;
        int rv;
        data.writeInterfaceToken(IMplConnection::getInterfaceDescriptor());
        remote()->transact(GET_LIBRARY_LENGTH, data, &reply);
        *length = reply.readInt32();
        rv = reply.readInt32();
        return rv;
    }

};

IMPLEMENT_META_INTERFACE(MplConnection, "android.gui.MplConnection");


// ----------------------------------------------------------------------------

status_t BnMplConnection::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
    case ADD_GLYPH:
    {
         CHECK_INTERFACE(IMplConnection, data, reply);
         unsigned short GlyphID;
         int rv;
         GlyphID = data.readInt32();
         rv = rpcAddGlyph(GlyphID);
         reply->writeInt32(rv);
         return NO_ERROR;
         break;
    }
    case BEST_GLYPH:
    {
         CHECK_INTERFACE(IMplConnection, data, reply);
         int rv;
         unsigned short finalGesture;
         rv = rpcBestGlyph(&finalGesture);
         reply->writeInt32(finalGesture);
         reply->writeInt32(rv);
         return NO_ERROR;
         break;
    }
    case SET_GLYPH_SPEED_THRESH:
    {
         CHECK_INTERFACE(IMplConnection, data, reply);
         int rv;
         unsigned short speed;
         speed = data.readInt32();
         rv = rpcSetGlyphSpeedThresh(speed);
         reply->writeInt32(rv);
         return NO_ERROR;
         break;
    }
    case START_GLYPH:
    {
         CHECK_INTERFACE(IMplConnection, data, reply);
         int rv;
         rv = rpcStartGlyph();
         reply->writeInt32(rv);
         return NO_ERROR;
         break;
    }
    case STOP_GLYPH:
    {
         CHECK_INTERFACE(IMplConnection, data, reply);
         int rv;
         rv = rpcStopGlyph();
         reply->writeInt32(rv);
         return NO_ERROR;
         break;
    }
    case GET_GLYPH:
    {
         CHECK_INTERFACE(IMplConnection, data, reply);
         int rv;
         int index;
         int x;
         int y;
         index = data.readInt32();
         rv = rpcGetGlyph(index, &x, &y);
         reply->writeInt32(x);
         reply->writeInt32(y);
         reply->writeInt32(rv);
         return NO_ERROR;
         break;
    }
    case GET_GLYPH_LENGTH:
    {
         CHECK_INTERFACE(IMplConnection, data, reply);
         int rv;
         unsigned short length;
         rv = rpcGetGlyphLength(&length);
         reply->writeInt32((int)length);
         reply->writeInt32(rv);
         return NO_ERROR;
         break;
    }
    case CLEAR_GLYPH:
    {
         CHECK_INTERFACE(IMplConnection, data, reply);
         int rv;
         rv = rpcClearGlyph();
         reply->writeInt32(rv);
         return NO_ERROR;
         break;
    }
    case LOAD_GLYPHS:
    {
         CHECK_INTERFACE(IMplConnection, data, reply);
         int rv;
         int i;
         int len;
         unsigned char *libraryData;
         len = data.readInt32();
         libraryData = (unsigned char*)malloc(len);
         for(i=0;i<len;i++) libraryData[i] = (unsigned char)data.readInt32();
         rv = rpcLoadGlyphs(libraryData);
         reply->writeInt32(rv);
         free(libraryData);
         return NO_ERROR;
         break;
    }
    case STORE_GLYPHS:
    {
         CHECK_INTERFACE(IMplConnection, data, reply);
         int rv;
         int i;
         unsigned short len;
         unsigned char *libraryData;
         rpcGetLibraryLength(&len);
         libraryData = (unsigned char*)malloc(len);
         rv = rpcStoreGlyphs(libraryData, &len);
         reply->writeInt32(len);
         for(i=0;i<len;i++) reply->writeInt32(libraryData[i]);
         reply->writeInt32(rv);
         free(libraryData);
         return NO_ERROR;
         break;
    }
    case SET_GLYPH_PROB_THRESH:
    {
         CHECK_INTERFACE(IMplConnection, data, reply);
         int rv;
         unsigned short prob;
         prob = (unsigned short) data.readInt32();
         rv = rpcSetGlyphProbThresh(prob);
         reply->writeInt32(rv);
         return NO_ERROR;
         break;
    }
    case GET_LIBRARY_LENGTH:
    {
         CHECK_INTERFACE(IMplConnection, data, reply);
         int rv;
         unsigned short length;
         rv = rpcGetLibraryLength(&length);
         reply->writeInt32(length);
         reply->writeInt32(rv);
         return NO_ERROR;
         break;
    }
    }
    return BBinder::onTransact(code, data, reply, flags);
}

// ----------------------------------------------------------------------------
}; // namespace android
