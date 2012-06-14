/* Copyright (c) 2012 Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Code Aurora nor
 *       the names of its contributors may be used to endorse or promote
 *       products derived from this software without specific prior written
 *       permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */


#define LOG_TAG "FeatureConfig"

#include "jni.h"
#include <android_runtime/AndroidRuntime.h>
#include <utils/misc.h>
#include <utils/Log.h>
#include <cutils/properties.h>

extern "C" {
    bool isFeatureEnabled(int f);
}

#define FEATURECONFIG_PKG_NAME "android/net/FeatureConfig"

namespace android {

static jboolean android_net_config_isFeatureEnabled(JNIEnv* env, jobject clazz, jint feature)
{
    LOGD("android_net_utils_isFeatureEnabled in env=%p clazz=%p feature=%d",
        env, clazz, feature);
    bool result;
    result = ::isFeatureEnabled(feature);
    LOGD("isCneFeatureEnabled returned = %d", result);
    return (jboolean)(result);
}
// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static JNINativeMethod gFeatureConfigMethods[] = {
    /* name, signature, funcPtr */
    { "isEnabled", "(I)Z", (void*) android_net_config_isFeatureEnabled },
};

int register_android_net_FeatureConfig(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(env,
            FEATURECONFIG_PKG_NAME, gFeatureConfigMethods, NELEM(gFeatureConfigMethods));
}

}; // namespace android
