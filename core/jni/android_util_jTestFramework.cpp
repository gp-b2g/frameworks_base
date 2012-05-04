/*
 * Copyright (C) 2007 The Android Open Source Project
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


#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "jni.h"
#include <testframework/TestFramework.h>

namespace android {

static jint android_util_jTestFramework_print (JNIEnv * env, jclass cls,
                        jint eventtype, jstring eventgrp, jstring eventid, jstring eventmsg)
{
    jint rtn = 0;
#ifdef CUSTOM_EVENTS_TESTFRAMEWORK
    const char* grp = env->GetStringUTFChars(eventgrp, NULL);
    const char* id = env->GetStringUTFChars(eventid, NULL);
    const char* msg = env->GetStringUTFChars(eventmsg, NULL);

    if (!grp || !id || !msg)
        return NULL;
    rtn = TF_PRINT(eventtype, grp, id, msg);

    env->ReleaseStringUTFChars(eventgrp, grp);
    env->ReleaseStringUTFChars(eventid, id);
    env->ReleaseStringUTFChars(eventmsg, msg);
#endif
    return rtn;
}

static jint android_util_jTestFramework_print_if (JNIEnv * env, jclass cls, jboolean cond,
                        jint eventtype, jstring eventgrp, jstring eventid, jstring eventmsg)
{
    jint rtn = 0;
#ifdef CUSTOM_EVENTS_TESTFRAMEWORK
    const char* grp = env->GetStringUTFChars(eventgrp, NULL);
    const char* id = env->GetStringUTFChars(eventid, NULL);
    const char* msg = env->GetStringUTFChars(eventmsg, NULL);

    if (!grp || !id || !msg)
        return NULL;

    if (cond)
        rtn = TF_PRINT(eventtype, grp, id, msg);
    else
        (void)0;

    env->ReleaseStringUTFChars(eventgrp, grp);
    env->ReleaseStringUTFChars(eventid, id);
    env->ReleaseStringUTFChars(eventmsg, msg);
#endif
    return rtn;
}

static jint android_util_jTestFramework_write (JNIEnv * env, jclass cls, jstring eventmsg)
{
    jint rtn = 0;
#ifdef CUSTOM_EVENTS_TESTFRAMEWORK
    const char* msg = env->GetStringUTFChars(eventmsg, NULL);

    if (!msg)
        return NULL;
    rtn = TF_WRITE(msg);

    env->ReleaseStringUTFChars(eventmsg, msg);
#endif
    return rtn;
}

static jint android_util_jTestFramework_write_if (JNIEnv * env, jclass cls, jboolean cond, jstring eventmsg)
{
    jint rtn = 0;
#ifdef CUSTOM_EVENTS_TESTFRAMEWORK
    const char* msg = env->GetStringUTFChars(eventmsg, NULL);

    if (!msg)
        return NULL;

    if (cond)
        rtn = TF_WRITE(msg);
    else
        (void)0;

    env->ReleaseStringUTFChars(eventmsg, msg);
#endif
    return rtn;
}


static jint android_util_jTestFramework_turnon (JNIEnv * env, jclass cls)
{
    jint rtn = 0;
#ifdef CUSTOM_EVENTS_TESTFRAMEWORK
    rtn = TF_TURNON();
#endif
    return rtn;
}

static jint android_util_jTestFramework_turnoff (JNIEnv * env, jclass cls)
{
    jint rtn = 0;
#ifdef CUSTOM_EVENTS_TESTFRAMEWORK
    rtn = TF_TURNOFF();
#endif
    return rtn;
}


static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "print", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)I", (void*) android_util_jTestFramework_print },
    { "print_if", "(ZILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)I", (void*) android_util_jTestFramework_print_if },
    { "write", "(Ljava/lang/String;)I", (void*) android_util_jTestFramework_write },
    { "write_if", "(ZLjava/lang/String;)I", (void*) android_util_jTestFramework_write_if },
    { "turnon", "()I", (void*) android_util_jTestFramework_turnon },
    { "turnoff", "()I", (void*) android_util_jTestFramework_turnoff },
};

int register_android_util_jTestFramework(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(env,
            "android/util/jTestFramework", gMethods, NELEM(gMethods));
}

}; // namespace android
