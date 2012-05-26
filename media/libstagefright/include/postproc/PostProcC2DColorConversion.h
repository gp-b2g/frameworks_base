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

#ifndef POSTPROC_C2DColorConversion_H_
#define POSTPROC_C2DColorConversion_H_

#include "PostProc.h"
#include <C2DColorConverter.h>

namespace android {

struct PostProcC2DColorConversion : public PostProc {

public:
    PostProcC2DColorConversion();

protected: // from PostProc
    virtual ~PostProcC2DColorConversion();
    virtual	status_t Init(sp<MediaSource> decoder, sp<PostProcNativeWindow> nativeWindow, const sp<MetaData> &meta);
    virtual status_t postProcessBuffer(MediaBuffer *inputBuffer, MediaBuffer *outputBuffer);
    virtual status_t setBufferInfo(const sp<MetaData> &meta);
    bool postProcessingPossible();

private:
    status_t initC2DLibrary();
    status_t convertUsingC2D(MediaBuffer *inputBuffer, MediaBuffer *outputBuffer);
    ColorConvertFormat getC2DFormat(int32_t format);
    int32_t getBufferSize(int32_t format, int32_t width, int32_t height);
    int32_t getStride(int32_t format, int32_t width);
    int32_t getSlice(int32_t format, int32_t height);

private:
    void *mC2DCCLibHandle;
    createC2DColorConverter_t *mC2DConvertOpen;
    destroyC2DColorConverter_t *mC2DConvertClose;
    C2DColorConverterBase * mC2DCC;
};

}  // namespace android

#endif  // POSTPROC_C2DColorConversion_H_

