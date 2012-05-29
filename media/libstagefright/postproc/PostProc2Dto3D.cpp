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

//#define LOG_NDEBUG 0
#define LOG_TAG "PostProc2Dto3D"

#include <PostProc2Dto3D.h>

#define BYTES_PER_PIXEL 3
#define NUMBER_3D_BUFFERS 6

namespace android {

PostProc2Dto3D::PostProc2Dto3D()
{
    LOGV("%s:  begin", __func__);
    mName = "PostProc2Dto3D";
}

PostProc2Dto3D::~PostProc2Dto3D()
{
    LOGV("%s:  begin", __func__);

    status_t err = (mLib3dDeinit(&mLib3dContext) != from2dTo3d_ERROR_NONE ?
            UNKNOWN_ERROR : OK);
    CHECK(err == OK);
}

status_t PostProc2Dto3D::Init(sp<MediaSource> decoder, sp<PostProcNativeWindow> nativeWindow, const sp<MetaData> &meta)
{
    LOGV("%s:  begin", __func__);

    status_t err = PostProc::Init(decoder, nativeWindow, meta);
    if (err != OK) {
        LOGE("PostProc2Dto3D Init failed in base class");
        return err;
    }
    return init2DTo3DLibrary();
}

status_t PostProc2Dto3D::postProcessBuffer(MediaBuffer* inputBuffer, MediaBuffer* outputBuffer)
{
    LOGV("%s:  begin", __func__);

    CHECK(inputBuffer);
    CHECK(outputBuffer);
    return convert2DTo3D(inputBuffer, outputBuffer);
}

status_t PostProc2Dto3D::setBufferInfo(const sp<MetaData> &meta)
{
    LOGV("%s:  begin", __func__);

    mOutputFormat = new MetaData(*(meta.get()));
    int32_t width;
    int32_t height;
    int32_t format;

    CHECK(mOutputFormat->findInt32(kKeyWidth, &width));
    CHECK(mOutputFormat->findInt32(kKeyHeight, &height));

    mWidth = width;
    mHeight = height;
    mDstFormat = isConversionTopBottom() ? (HAL_PIXEL_FORMAT_YCbCr_444_SP
                    | HAL_3D_OUT_TOP_BOTTOM | HAL_3D_IN_TOP_BOTTOM) : (HAL_PIXEL_FORMAT_YCbCr_444_SP
                    | HAL_3D_OUT_SIDE_BY_SIDE | HAL_3D_IN_SIDE_BY_SIDE_L_R);
    mNumBuffers = NUMBER_3D_BUFFERS;

    mBufferSize = ALIGN(width * height * BYTES_PER_PIXEL, ALIGN8K);
    mStride = width;
    mSlice = height;
    return OK;
}

bool PostProc2Dto3D::postProcessingPossible()
{
    LOGV("%s:  begin", __func__);

    int32_t threeDFormat, interlacedFormat;
    if ((mSource->getFormat()->findInt32(kKey3D, &threeDFormat)) ||
            (mSource->getFormat()->findInt32(kKeyInterlaced, &interlacedFormat))) {
        LOGV("Detected 3D or interlaced format\n");
        return false;
    }
    return true;
}

status_t PostProc2Dto3D::init2DTo3DLibrary()
{
    LOGV("%s:  begin", __func__);

    mLib3dHandle = dlopen("libswdec_2dto3d.so", RTLD_LAZY);
    mLib3dInit = NULL;
    mLib3dDeinit = NULL;
    mLib3dSetProperty = NULL;
    mLib3dGetProperty = NULL;
    mLib3dAllocResources = NULL;
    mLib3dConvertFrom2dto3d = NULL;

    if (mLib3dHandle) {
        mLib3dInit = (init_deinit_Func_t *)dlsym(mLib3dHandle,"from2dTo3d_init");
        mLib3dDeinit = (init_deinit_Func_t *)dlsym(mLib3dHandle,"from2dTo3d_deInit");
        mLib3dSetProperty = (set_get_property_Func_t *)dlsym(mLib3dHandle,"from2dTo3d_setProperty");
        mLib3dGetProperty = (set_get_property_Func_t *)dlsym(mLib3dHandle,"from2dTo3d_getProperty");
        mLib3dAllocResources = (alloc_dealloc_resources_Func_t *)dlsym(mLib3dHandle,"from2dTo3d_allocResources");
        mLib3dConvertFrom2dto3d = (from2dTo3d_convert_Func_t *)dlsym(mLib3dHandle,"from2dTo3d_convert");
        if (mLib3dInit == NULL || mLib3dDeinit == NULL || mLib3dSetProperty == NULL || mLib3dGetProperty == NULL ||
            mLib3dAllocResources == NULL || mLib3dConvertFrom2dto3d == NULL) {
            LOGE("Could not get the 2dTo3d lib function pointers\n");
            CHECK(0);
        }
    }
    else {
        LOGE("Could not get 2dTo3d conversion lib handle\n");
        CHECK(0);
    }

    mLib3dContext = NULL;
    {
        from2dTo3d_ErrorType err = mLib3dInit(&mLib3dContext);
        CHECK_EQ(err, from2dTo3d_ERROR_NONE);
    }
    return configure2DTo3DLibrary();
}

status_t PostProc2Dto3D::configure2DTo3DLibrary()
{
    LOGV("%s:  begin", __func__);

    from2dTo3d_ErrorType err;
    from2dTo3d_property prop;
    from2dTo3d_dimension dim;

    dim.width = mWidth;
    dim.height = mHeight;
    dim.srcLumaStride = ALIGN(mWidth, ALIGN32); //Input format is SemiPlanar
    dim.srcCbCrStride = ALIGN(mWidth, ALIGN32);
    dim.dstLumaStride = mWidth;
    dim.dstCbCrStride = mWidth * 2;

    LOGV("width = %d, height = %d, \
            srcLumaStride = %d, srcCbCrStride = %d, \
            dstLumaStride = %d, dstCrStride = %d",
            dim.width, dim.height,
            dim.srcLumaStride, dim.srcCbCrStride,
            dim.dstLumaStride, dim.dstCbCrStride);

    prop.propId = from2dTo3d_PROP_DIMENSION;
    prop.propPayload.dim = dim;

    err = mLib3dSetProperty(mLib3dContext, prop);
    CHECK_EQ(err, from2dTo3d_ERROR_NONE);

    from2dTo3d_viewType framepacking;
    framepacking = isConversionTopBottom() ? from2dTo3d_TOP_DOWN : from2dTo3d_SIDE_BY_SIDE;
    prop.propId = from2dTo3d_PROP_VIEW_TYPE;
    prop.propPayload.view = framepacking;

    err = mLib3dSetProperty(mLib3dContext, prop);
    CHECK_EQ(err, from2dTo3d_ERROR_NONE);

    err = mLib3dAllocResources(mLib3dContext);
    CHECK_EQ(err, from2dTo3d_ERROR_NONE);
    return OK;
}

status_t PostProc2Dto3D::convert2DTo3D(MediaBuffer* inputBuffer, MediaBuffer* outputBuffer)
{
    LOGV("%s:  begin", __func__);

    DurationTimer dt;
    dt.start();

    void * srcLuma = NULL;
    void * dstLuma = NULL;

    if (inputBuffer->graphicBuffer() != 0) {
        private_handle_t *inputHandle =
        (private_handle_t *) inputBuffer->graphicBuffer()->getNativeBuffer()->handle;
        inputBuffer->graphicBuffer()->lock(GRALLOC_USAGE_SW_READ_OFTEN |
            GRALLOC_USAGE_SW_WRITE_OFTEN, &srcLuma);
    } else {
        post_proc_media_buffer_type * packet = (post_proc_media_buffer_type *)inputBuffer->data();
        native_handle_t * nh = const_cast<native_handle_t *>(packet->meta_handle);
        srcLuma = (void *)nh->data[4];
    }

    LOGV("srcLuma = %p", srcLuma);

    if (outputBuffer->graphicBuffer() != 0) {
        private_handle_t *outputHandle =
        (private_handle_t *) outputBuffer->graphicBuffer()->getNativeBuffer()->handle;
        outputBuffer->graphicBuffer()->lock(GRALLOC_USAGE_SW_READ_OFTEN |
            GRALLOC_USAGE_SW_WRITE_OFTEN, &dstLuma);
    } else {
        post_proc_media_buffer_type * packet = (post_proc_media_buffer_type *)outputBuffer->data();
        native_handle_t * nh = const_cast<native_handle_t *>(packet->meta_handle);
        dstLuma = (void *)nh->data[4];
    }

    LOGV("dstLuma = %p", dstLuma);

    from2dTo3d_ErrorType lib3dErr =
        mLib3dConvertFrom2dto3d(mLib3dContext,
                (unsigned char *)srcLuma,
                (unsigned char *)srcLuma + (ALIGN(mWidth, ALIGN32) * mHeight),
                NULL,
                (unsigned char *)dstLuma,
                (unsigned char *)dstLuma + (mWidth * mHeight),
                NULL);
    CHECK_EQ(lib3dErr, from2dTo3d_ERROR_NONE); // return err

    if (inputBuffer->graphicBuffer() != 0) {
        inputBuffer->graphicBuffer()->unlock();
    }

    if (outputBuffer->graphicBuffer() != 0) {
        outputBuffer->graphicBuffer()->unlock();
    }

    dt.stop();
    LOGV("convert to 3d elapsed = %f ms", ((float)dt.durationUsecs()/(float)1000));
    return OK;
}

bool PostProc2Dto3D::isConversionTopBottom()
{
    LOGV("%s:  begin", __func__);

    char value[PROPERTY_VALUE_MAX];
    if (property_get("VideoPostProc.2Dto3D.TopDown", value, 0) > 0 && atoi(value) > 0){
        LOGV("In top Down Mode\n");
        return true;
    }
    return false; // side by side
}

}
