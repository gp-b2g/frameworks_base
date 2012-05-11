/*
 *
 * Copyright (c) 2012, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *     Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *     Neither the name of Code Aurora Forum, Inc. nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#include "QrdInside.h"
#include <dlfcn.h>
#include <utils/Log.h>

#define LOG_TAG 			"PerfManJNI"
#define PERFMAN_LIBRARY     "/system/lib/libperfman.so"

typedef int (*Fuc_initperfman)();
typedef int (*Fuc_notifyperfman)(int, int);

/* so library handle*/
static void* hperfman = NULL;

/* so function pointers */
static Fuc_initperfman hInitPerfman = NULL;
static Fuc_notifyperfman hNotifyPerfman = NULL;

/* native functions */
jboolean org_codeaurora_qrdinside_Perfman_InitPerfman(JNIEnv* env, jobject clazz) {
    /* load the so library and locate the funcPtrs*/
    hperfman = dlopen(PERFMAN_LIBRARY, RTLD_NOW);
    if (hperfman == NULL) {
        //LOGE("can't open %s, error = %s", PERFMAN_LIBRARY, dlerror());
        return false;
    }

    /* #define TRY_LOAD_FUC(hso, type, name, phandle)  */
    TRY_LOAD_FUC(hperfman, Fuc_initperfman, 	"init_perfman", 	hInitPerfman);
    TRY_LOAD_FUC(hperfman, Fuc_notifyperfman, 	"notify_perfman", 	hNotifyPerfman);

    return true;
}

jboolean org_codeaurora_qrdinside_Perfman_NotifyPerfman(JNIEnv* env, jobject clazz, int what, int pid) {
    if (hNotifyPerfman != NULL) {
        return hNotifyPerfman(what, pid) == 0;
    }
    return false;
}

/* JNI registration */
static const JNINativeMethod methods[] = {
    {"InitPerfman", "()Z", (void*)org_codeaurora_qrdinside_Perfman_InitPerfman},
    {"NotifyPerfman", "(II)Z", (void*)org_codeaurora_qrdinside_Perfman_NotifyPerfman},
};

const char* const kProcessPathName = "org/codeaurora/qrdinside/Perfman";

int register_org_codeaurora_qrdinside_PerfMan(JNIEnv* env)
{
    jclass clazz;

    clazz = env->FindClass(kProcessPathName);
    LOG_FATAL_IF(clazz == NULL, "Unable to find class %s", kProcessPathName);

    return jniRegisterNativeMethods(env, kProcessPathName, methods, NELEM(methods));
}
