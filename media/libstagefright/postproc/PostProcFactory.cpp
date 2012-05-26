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
#include <PostProcFactory.h>
#include <PostProcNativeWindow.h>
#include <OMXCodec.h>
#include <PostProcController.h>

#ifdef POSTPROC_SUPPORTED
#include <PostProc2Dto3D.h>
#include <PostProcC2DColorConversion.h>
#endif

#define LOG_TAG "PostProcFactory"
namespace android {

#ifdef POSTPROC_SUPPORTED
sp<MediaSource> PostProcFactoryCreate(const sp<MediaSource> &decoder, const sp<PostProcNativeWindow> &nativeWindow, const sp<MetaData> &meta, PostProcController **ppController, PostProcType ppType)
{
    Vector<sp<PostProc > > postProcModules;
    *ppController = NULL;

    if (ppType == PostProcType2DTo3D) {

        sp<PostProc> postProcC2D = new PostProcC2DColorConversion();
        if (postProcC2D == NULL) {
            LOGE("Failed to create post processing C2D object");
            return NULL;
        }

        sp<MetaData> md = new MetaData(*(meta.get()));
        md->setInt32(kKeyColorFormat, HAL_PIXEL_FORMAT_YCbCr_420_SP);
        int err = postProcC2D->Init(decoder, NULL, md);
        md.clear();
        if (err != OK) {
            postProcC2D.clear();
            LOGE("Failed to initialize post processing C2D object");
            return NULL;
        }

        postProcModules.push_back(postProcC2D);

        sp<PostProc> postProc2Dto3D = new PostProc2Dto3D();
        if (postProc2Dto3D == NULL) {
            LOGE("Failed to create post processing 2d to 3d object");
            postProcC2D.clear();
            return NULL;
        }

        err = postProc2Dto3D->Init(postProcC2D, nativeWindow, meta);
        if (err != OK) {
            LOGE("Failed to initialize post processing object");
            postProcC2D.clear();
            return NULL;
        }
        postProcModules.push_back(postProc2Dto3D);

        *ppController = new PostProcController(postProcModules);

        if (*ppController) {
            (*ppController)->checkToggleIssued();
        }
        return postProc2Dto3D;
    }
    return NULL;
}

#else
sp<MediaSource> PostProcFactoryCreate(const sp<MediaSource> &decoder, const sp<PostProcNativeWindow> &nativeWindow, const sp<MetaData> &meta, PostProcController **ppController, PostProcType ppType)
{
    return NULL;
}

#endif

}
