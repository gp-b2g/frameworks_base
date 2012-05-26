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

#ifndef POSTPROC_2Dto3D_H_
#define POSTPROC_2Dto3D_H_

#include "PostProc.h"
#include <from2dTo3d.h>

namespace android {

struct PostProc2Dto3D : public PostProc {

public:
    PostProc2Dto3D();

protected: // from PostProc
    virtual ~PostProc2Dto3D();
    virtual	status_t Init(sp<MediaSource> decoder, sp<PostProcNativeWindow> nativeWindow, const sp<MetaData> &meta);
    virtual status_t postProcessBuffer(MediaBuffer *inputBuffer, MediaBuffer* outputBuffer);
    status_t setBufferInfo(const sp<MetaData> &meta);
    bool postProcessingPossible();

private:
    status_t init2DTo3DLibrary();
    status_t configure2DTo3DLibrary();
    status_t convert2DTo3D(MediaBuffer *inputBuffer, MediaBuffer* outputBuffer);
    bool isConversionTopBottom();

private:
    void *mLib3dHandle;
    init_deinit_Func_t *mLib3dInit;
    init_deinit_Func_t *mLib3dDeinit;
    set_get_property_Func_t * mLib3dSetProperty;
    set_get_property_Func_t * mLib3dGetProperty;
    alloc_dealloc_resources_Func_t *mLib3dAllocResources;
    from2dTo3d_convert_Func_t *mLib3dConvertFrom2dto3d;

    void *mLib3dContext;
};

}  // namespace android

#endif  // POSTPROC_2Dto3D_H_

