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
#include <limits.h>
#include <utils/Log.h>

#define LOG_TAG 				"ZygoteMemPolicyJNI"
#define MEM_POLICY_LIBRARY      "/system/lib/libmempolicy.so"

typedef void (*Fuc_updateMemPolicy)(const char*, int);

/* so library handle*/
static void *handle = NULL;

/* so function pointers*/
static Fuc_updateMemPolicy hUpdateMemPolicy = NULL;


/* native functions */
static void org_codeaurora_qrdinside_ZygoteMemPolicy_updateMemPolicy(
    JNIEnv* env, jobject clazz, jstring procnameObj, jint pid)
{
    const char* procname = env->GetStringUTFChars(procnameObj, NULL);
    //LOGE("native updatemempolicy, procname = %s", procname);

    if (hUpdateMemPolicy != NULL) {
        hUpdateMemPolicy(procname, pid);
    }

    return;
}

static jboolean org_codeaurora_qrdinside_ZygoteMemPolicy_init(
    JNIEnv* env, jobject clazz)
{
    /* load the so library and locate the funcPtrs*/
    handle = dlopen(MEM_POLICY_LIBRARY, RTLD_NOW);
    if (handle == NULL) {
        //LOGE("can't open %s, error = %s", MEM_POLICY_LIBRARY, dlerror());
        return false;
    }

    /* #define TRY_LOAD_FUC(hso, type, name, phandle)  */
    TRY_LOAD_FUC(handle, Fuc_updateMemPolicy, 	"updateMemPolicy", 	hUpdateMemPolicy);

    return true;
}

/* JNI registration */
static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "native_updateMemPolicy", "(Ljava/lang/String;I)V",
      (void *) org_codeaurora_qrdinside_ZygoteMemPolicy_updateMemPolicy },
    { "native_init", "()Z",
      (void *) org_codeaurora_qrdinside_ZygoteMemPolicy_init },
};

const char* const kProcessPathName = "org/codeaurora/qrdinside/ZygoteMemPolicy";

int register_org_codeaurora_qrdinside_ZygoteMemPolicy(JNIEnv* env)
{
    jclass clazz;

    clazz = env->FindClass(kProcessPathName);
    LOG_FATAL_IF(clazz == NULL, "Unable to find class %s", kProcessPathName);

    return jniRegisterNativeMethods(env, kProcessPathName, gMethods, NELEM(gMethods));
}
