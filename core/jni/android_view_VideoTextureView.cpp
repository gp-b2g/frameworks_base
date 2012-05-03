/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (c) 2012, Code Aurora Forum. All rights reserved.
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

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_graphics_SurfaceTexture.h>

#include <ui/Region.h>
#include <ui/Rect.h>

#include <gui/SurfaceTexture.h>
#include <gui/SurfaceTextureClient.h>

#include <SkBitmap.h>
#include <SkCanvas.h>

namespace android {

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

static struct {
    jfieldID nativeWindow;
} gVideoTextureViewClassInfo;

#define GET_INT(object, field) \
    env->GetIntField(object, field)

#define SET_INT(object, field, value) \
    env->SetIntField(object, field, value)

// ----------------------------------------------------------------------------
// Native layer
// ----------------------------------------------------------------------------

static void android_view_VideoTextureView_setDefaultBufferSize(JNIEnv* env, jobject,
    jobject surface, jint width, jint height) {

    sp<SurfaceTexture> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, surface));
    surfaceTexture->setDefaultBufferSize(width, height);
}

/**
 * This is a private API, and this implementation is also provided in the NDK.
 * However, the NDK links against android_runtime, which means that using the
 * NDK implementation would create a circular dependency between the libraries.
 */
static int32_t native_window_lock(ANativeWindow* window, ANativeWindow_Buffer* outBuffer,
        Rect* inOutDirtyBounds) {
    return window->perform(window, NATIVE_WINDOW_LOCK, outBuffer, inOutDirtyBounds);
}

static int32_t native_window_unlockAndPost(ANativeWindow* window) {
    return window->perform(window, NATIVE_WINDOW_UNLOCK_AND_POST);
}

static void android_view_VideoTextureView_createNativeWindow(JNIEnv* env, jobject thiz,
        jobject surface) {

    sp<SurfaceTexture> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, surface));
    sp<ANativeWindow> window = new SurfaceTextureClient(surfaceTexture);

    window->incStrong(0);
    SET_INT(thiz, gVideoTextureViewClassInfo.nativeWindow, jint(window.get()));
}

static void android_view_VideoTextureView_destroyNativeWindow(JNIEnv* env, jobject thiz) {

    ANativeWindow* nativeWindow = (ANativeWindow*)
            GET_INT(thiz, gVideoTextureViewClassInfo.nativeWindow);

    if (nativeWindow) {
        sp<ANativeWindow> window(nativeWindow);
            window->decStrong(0);
        SET_INT(thiz, gVideoTextureViewClassInfo.nativeWindow, 0);
    }
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/VideoTextureView";

static JNINativeMethod gMethods[] = {
    {   "nSetDefaultBufferSize", "(Landroid/graphics/SurfaceTexture;II)V",
            (void*) android_view_VideoTextureView_setDefaultBufferSize },

    {   "nCreateNativeWindow", "(Landroid/graphics/SurfaceTexture;)V",
            (void*) android_view_VideoTextureView_createNativeWindow },
    {   "nDestroyNativeWindow", "()V",
            (void*) android_view_VideoTextureView_destroyNativeWindow },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(!var, "Unable to find class " className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(!var, "Unable to find field" fieldName);

int register_android_view_VideoTextureView(JNIEnv* env) {
    jclass clazz;
    FIND_CLASS(clazz, "android/view/VideoTextureView");
    GET_FIELD_ID(gVideoTextureViewClassInfo.nativeWindow, clazz, "mNativeWindow", "I");
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}
};
