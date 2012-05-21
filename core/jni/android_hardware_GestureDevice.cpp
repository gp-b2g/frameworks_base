/*
**
** Copyright (c) 2012 Code Aurora Forum. All rights reserved.
** Copyright (c) 2008, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#define LOG_TAG "GestureDevice-JNI"
#include <utils/Log.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <utils/Vector.h>

#include <gestures/GestureDevice.h>

using namespace android;

struct fields_t {
    jfieldID    context;
    jfieldID    vector_x;
    jfieldID    vector_y;
    jfieldID    vector_z;
    jfieldID    vector_error;
    jfieldID    evt_version;
    jfieldID    evt_type;
    jfieldID    evt_subtype;
    jfieldID    evt_id;
    jfieldID    evt_timestamp;
    jfieldID    evt_confidence;
    jfieldID    evt_location;
    jfieldID    evt_velocity;
    jfieldID    evt_extension;
    jmethodID   post_event;
    jmethodID   evt_constructor;
    jmethodID   vector_constructor;
};

static fields_t fields;
static Mutex sLock;

// provides persistent context for calls from native code to Java
class JNIGestureDeviceContext: public GestureDeviceListener
{
public:
    JNIGestureDeviceContext(JNIEnv* env, jobject weak_this, jclass clazz, 
                            const sp<GestureDevice>& gestureDevice);
    ~JNIGestureDeviceContext() { release(); }
    virtual void notify(int32_t msgType, int32_t ext1, int32_t ext2);
    virtual void postData(gesture_result_t* gs_results);
    sp<GestureDevice> getGestureDevice() { Mutex::Autolock _l(mLock); return mGestureDevice; }
    void release();

private:
    void postResult(JNIEnv* env, gesture_result_t* gs_results);

    jobject            mGestureDeviceJObjectWeak;     // weak reference to java object
    jclass             mGestureDeviceJClass;          // strong reference to java class
    sp<GestureDevice>  mGestureDevice;                // strong reference to native object
    jclass             mGestureResultClass;           // strong reference to GestureResult class
    jclass             mVectorClass;                  // strong reference to Vector class
    Mutex              mLock;
};

sp<GestureDevice> get_native_gesture_device(JNIEnv *env, jobject thiz, JNIGestureDeviceContext** pContext)
{
    sp<GestureDevice> gesture;
    Mutex::Autolock _l(sLock);
    JNIGestureDeviceContext* context = 
        reinterpret_cast<JNIGestureDeviceContext*>(env->GetIntField(thiz, fields.context));
    if (context != NULL) {
        gesture = context->getGestureDevice();
    }
    LOGV("get_native_gesture_device: context=%p, vision=%p", context, gesture.get());
    if (gesture == 0) {
        jniThrowRuntimeException(env, "Method called after release()");
    }

    if (pContext != NULL) *pContext = context;
    return gesture;
}

JNIGestureDeviceContext::JNIGestureDeviceContext(JNIEnv* env, jobject weak_this, 
                                                 jclass clazz, const sp<GestureDevice>& gesture)
{
    mGestureDeviceJObjectWeak = env->NewGlobalRef(weak_this);
    mGestureDeviceJClass = (jclass)env->NewGlobalRef(clazz);
    mGestureDevice = gesture;

    jclass resultClazz = env->FindClass("android/hardware/gesturedev/GestureResult");
    mGestureResultClass = (jclass) env->NewGlobalRef(resultClazz);

    jclass vectorClazz = env->FindClass("android/hardware/gesturedev/GestureResult$GSVector");
    mVectorClass = (jclass) env->NewGlobalRef(vectorClazz);
}

void JNIGestureDeviceContext::release()
{
    LOGV("release");
    Mutex::Autolock _l(mLock);
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    if (mGestureDeviceJObjectWeak != NULL) {
        env->DeleteGlobalRef(mGestureDeviceJObjectWeak);
        mGestureDeviceJObjectWeak = NULL;
    }
    if (mGestureDeviceJClass != NULL) {
        env->DeleteGlobalRef(mGestureDeviceJClass);
        mGestureDeviceJClass = NULL;
    }
    if (mGestureResultClass != NULL) {
        env->DeleteGlobalRef(mGestureResultClass);
        mGestureResultClass = NULL;
    }
    if (mVectorClass != NULL) {
        env->DeleteGlobalRef(mVectorClass);
        mVectorClass = NULL;
    }
    mGestureDevice.clear();
}

void JNIGestureDeviceContext::postResult(JNIEnv* env, gesture_result_t* gs_results)
{
    if (NULL == gs_results) {
        LOGE("postResult: gesture result is NULL");
        return;
    }

    jobjectArray obj = NULL;
    obj = (jobjectArray) env->NewObjectArray(gs_results->number_of_events,
                                             mGestureResultClass, NULL);
    if (NULL == obj) {
        LOGE("Couldn't allocate Gesture EventPayload array");
        return;
    }

    LOGI("postResult: Total gs results %d", gs_results->number_of_events);
    for (int i = 0; i < gs_results->number_of_events; i++) {
        jobject evt = env->NewObject(mGestureResultClass, fields.evt_constructor);
        env->SetObjectArrayElement(obj, i, evt);

        LOGI("Result %d: version(%d), type(%d), subtype(%d), id(%d), timestamp(%llu), confidence(%f), velocity(%f)",
             i, gs_results->events[i].version, gs_results->events[i].type, 
             gs_results->events[i].subtype, gs_results->events[i].id,
             gs_results->events[i].timestamp, gs_results->events[i].confidence,
             gs_results->events[i].velocity);
        env->SetIntField(evt, fields.evt_version, gs_results->events[i].version);
        env->SetIntField(evt, fields.evt_type, gs_results->events[i].type);
        env->SetIntField(evt, fields.evt_subtype, gs_results->events[i].subtype);
        env->SetIntField(evt, fields.evt_id, gs_results->events[i].id);
        env->SetLongField(evt, fields.evt_timestamp, gs_results->events[i].timestamp);
        env->SetFloatField(evt, fields.evt_confidence, gs_results->events[i].confidence);
        env->SetFloatField(evt, fields.evt_velocity, gs_results->events[i].velocity);

        LOGI("Result %d: location points (%d)", i, gs_results->events[i].location.num_of_points);
        if (gs_results->events[i].location.num_of_points > 0) {
            jobjectArray location = NULL;
            location = (jobjectArray) env->NewObjectArray(
                                                gs_results->events[i].location.num_of_points, 
                                                mVectorClass, 
                                                NULL);
            if (NULL != location) {
                for (int j = 0; j < gs_results->events[i].location.num_of_points; j++) {
                    LOGI("Point %d: x(%f), y(%f), z(%f)", j,
                         gs_results->events[i].location.pPoints[j].x,
                         gs_results->events[i].location.pPoints[j].y,
                         gs_results->events[i].location.pPoints[j].z);
                    jobject vector = env->NewObject(mVectorClass, fields.vector_constructor);
                    if (NULL != vector) {
                        env->SetObjectArrayElement(location, j, vector);
                        env->SetFloatField(vector,
                                         fields.vector_x,
                                         gs_results->events[i].location.pPoints[j].x);
                        env->SetFloatField(vector,
                                         fields.vector_y,
                                         gs_results->events[i].location.pPoints[j].y);
                        env->SetFloatField(vector,
                                         fields.vector_z,
                                         gs_results->events[i].location.pPoints[j].z);
                        env->DeleteLocalRef(vector);
                    }
                }
                env->SetObjectField(evt, fields.evt_location, location);
                env->DeleteLocalRef(location);
            }
        }

        if (gs_results->events[i].extendinfo.len > 0) {
            jbyteArray info = NULL;
            info = env->NewByteArray(gs_results->events[i].extendinfo.len);
            if (NULL != info) {
                env->SetByteArrayRegion(info, 0, 
                                        gs_results->events[i].extendinfo.len, 
                                        (const jbyte*)gs_results->events[i].extendinfo.buf);
                env->SetObjectField(evt, fields.evt_extension, info);
                env->DeleteLocalRef(info);
            }
        }

        env->DeleteLocalRef(evt);
    }

    env->CallStaticVoidMethod(mGestureDeviceJClass, fields.post_event,
            mGestureDeviceJObjectWeak, GESTURE_MSG_RESULT, 0, 0, obj);
    if (obj) {
        env->DeleteLocalRef(obj);
    }
}

void JNIGestureDeviceContext::notify(int32_t msgType, int32_t ext1, int32_t ext2)
{
    LOGV("notify");

    // VM pointer will be NULL if object is released
    Mutex::Autolock _l(mLock);
    if (mGestureDeviceJObjectWeak == NULL) {
        LOGW("callback on dead vision device object");
        return;
    }
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    env->CallStaticVoidMethod(mGestureDeviceJClass, fields.post_event,
            mGestureDeviceJObjectWeak, msgType, ext1, ext2, NULL);
}

void JNIGestureDeviceContext::postData(gesture_result_t* gs_results)
{
    // VM pointer will be NULL if object is released
    Mutex::Autolock _l(mLock);
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (mGestureDeviceJObjectWeak == NULL) {
        LOGW("callback on dead gesture device object");
        return;
    }

    postResult(env, gs_results);
}

static jint android_hardware_gesturedev_GestureDevice_getNumberOfGestureDevices(JNIEnv *env, jobject thiz)
{
    return GestureDevice::getNumberOfGestureDevices();
}

// connect to gesture device service
static void android_hardware_gesturedev_GestureDevice_native_setup(JNIEnv *env, jobject thiz,
    jobject weak_this, jint v_Id)
{
    sp<GestureDevice> device = GestureDevice::connect(v_Id);

    if (device == NULL) {
        jniThrowRuntimeException(env, "Fail to connect to gesture device service");
        return;
    }

    // make sure gesture device hardware is alive
    if (device->getStatus() != NO_ERROR) {
        jniThrowRuntimeException(env, "Gesture device initialization failed");
        return;
    }

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        jniThrowRuntimeException(env, "Can't find android/hardware/gesturedev/GestureDevice");
        return;
    }

    // We use a weak reference so the GestureDevice object can be garbage collected.
    // The reference is only used as a proxy for callbacks.
    sp<JNIGestureDeviceContext> context = new JNIGestureDeviceContext(env, weak_this, clazz, device);
    context->incStrong(thiz);
    device->setListener(context);

    // save context in opaque field
    env->SetIntField(thiz, fields.context, (int)context.get());
}

// disconnect from gesture device service
// It's okay to call this when the native gesture device context is already null.
// This handles the case where the user has called release() and the
// finalizer is invoked later.
static void android_hardware_gesturedev_GestureDevice_native_release(JNIEnv *env, jobject thiz)
{
    LOGV("release gesture device");
    JNIGestureDeviceContext* context = NULL;
    sp<GestureDevice> gesture;
    {
        Mutex::Autolock _l(sLock);
        context = reinterpret_cast<JNIGestureDeviceContext*>(env->GetIntField(thiz, fields.context));

        // Make sure we do not attempt to callback on a deleted Java object.
        env->SetIntField(thiz, fields.context, 0);
    }

    // clean up if release has not been called before
    if (context != NULL) {
        gesture = context->getGestureDevice();
        context->release();
        LOGV("native_release: context=%p gesture=%p", context, gesture.get());

        // clear callbacks
        if (gesture != NULL) {
            gesture->disconnect();
        }

        // remove context to prevent further Java access
        context->decStrong(thiz);
    }
}

static void android_hardware_gesturedev_GestureDevice_native_startGesture(JNIEnv *env, jobject thiz)
{
    LOGV("startGesture");
    sp<GestureDevice> gesture = get_native_gesture_device(env, thiz, NULL);
    if (gesture == 0) return;

    if (gesture->startGesture() != NO_ERROR) {
        jniThrowRuntimeException(env, "startGesture failed");
        return;
    }
}

static void android_hardware_gesturedev_GestureDevice_native_stopGesture(JNIEnv *env, jobject thiz)
{
    LOGV("stopGesture");
    sp<GestureDevice> c = get_native_gesture_device(env, thiz, NULL);
    if (c == 0) return;

    c->stopGesture();
}

static void android_hardware_gesturedev_GestureDevice_native_setParameters(JNIEnv *env, jobject thiz, jstring params)
{
    LOGV("setParameters");
    sp<GestureDevice> c = get_native_gesture_device(env, thiz, NULL);
    if (c == 0) return;

    const jchar* str = env->GetStringCritical(params, 0);
    String8 params8;
    if (params) {
        params8 = String8(str, env->GetStringLength(params));
        env->ReleaseStringCritical(params, str);
    }
    if (c->setParameters(params8) != NO_ERROR) {
        jniThrowRuntimeException(env, "setParameters failed");
        return;
    }
}

static jstring android_hardware_gesturedev_GestureDevice_native_getParameters(JNIEnv *env, jobject thiz)
{
    LOGV("getParameters");
    sp<GestureDevice> c = get_native_gesture_device(env, thiz, NULL);
    if (c == 0) return 0;

    String8 param = c->getParameters();
    LOGE("JNI getParameters: %s", param.string());
    return env->NewStringUTF(param.string());
}

//-------------------------------------------------

static JNINativeMethod gestureDeviceMethods[] = {
  { "getNumberOfGestureDevices",
    "()I",
    (void *)android_hardware_gesturedev_GestureDevice_getNumberOfGestureDevices },
  { "native_setup",
    "(Ljava/lang/Object;I)V",
    (void*)android_hardware_gesturedev_GestureDevice_native_setup },
  { "native_release",
    "()V",
    (void*)android_hardware_gesturedev_GestureDevice_native_release },
  { "native_startGesture",
    "()V",
    (void *)android_hardware_gesturedev_GestureDevice_native_startGesture },
  { "native_stopGesture",
    "()V",
    (void *)android_hardware_gesturedev_GestureDevice_native_stopGesture },
  { "native_setParameters",
    "(Ljava/lang/String;)V",
    (void *)android_hardware_gesturedev_GestureDevice_native_setParameters },
  { "native_getParameters",
    "()Ljava/lang/String;",
    (void *)android_hardware_gesturedev_GestureDevice_native_getParameters },
};

struct field {
    const char *class_name;
    const char *field_name;
    const char *field_type;
    jfieldID   *jfield;
};

static int find_fields(JNIEnv *env, field *fields, int count)
{
    for (int i = 0; i < count; i++) {
        field *f = &fields[i];
        jclass clazz = env->FindClass(f->class_name);
        if (clazz == NULL) {
            LOGE("Can't find %s", f->class_name);
            return -1;
        }

        jfieldID field = env->GetFieldID(clazz, f->field_name, f->field_type);
        if (field == NULL) {
            LOGE("Can't find %s.%s", f->class_name, f->field_name);
            return -1;
        }

        *(f->jfield) = field;
    }

    return 0;
}

// Get all the required offsets in java class and register native functions
int register_android_hardware_GestureDevice(JNIEnv *env)
{
    field fields_to_find[] = {
        { "android/hardware/gesturedev/GestureDevice", "mNativeContext",   "I", &fields.context },
        { "android/hardware/gesturedev/GestureResult$GSVector", "x", "F", &fields.vector_x },
        { "android/hardware/gesturedev/GestureResult$GSVector", "y", "F", &fields.vector_y },
        { "android/hardware/gesturedev/GestureResult$GSVector", "z", "F", &fields.vector_z },
        { "android/hardware/gesturedev/GestureResult$GSVector", "error", "F", &fields.vector_error },
        { "android/hardware/gesturedev/GestureResult", "version",
            "I", &fields.evt_version },
        { "android/hardware/gesturedev/GestureResult", "type",
            "I", &fields.evt_type },
        { "android/hardware/gesturedev/GestureResult", "subtype",
            "I", &fields.evt_subtype },
        { "android/hardware/gesturedev/GestureResult", "id",
            "I", &fields.evt_id },
        { "android/hardware/gesturedev/GestureResult", "timestamp",
            "J", &fields.evt_timestamp },
        { "android/hardware/gesturedev/GestureResult", "confidence",
            "F", &fields.evt_confidence },
        { "android/hardware/gesturedev/GestureResult", "velocity",
            "F", &fields.evt_velocity },
        { "android/hardware/gesturedev/GestureResult", "location",
            "[Landroid/hardware/gesturedev/GestureResult$GSVector;", &fields.evt_location },
        { "android/hardware/gesturedev/GestureResult", "extension",
            "[B", &fields.evt_extension },
    };

    if (find_fields(env, fields_to_find, NELEM(fields_to_find)) < 0)
        return -1;

    jclass clazz = env->FindClass("android/hardware/gesturedev/GestureDevice");
    fields.post_event = env->GetStaticMethodID(clazz, "postEventFromNative",
                                               "(Ljava/lang/Object;IIILjava/lang/Object;)V");
    if (fields.post_event == NULL) {
        LOGE("Can't find android/hardware/gesturedev/GestureDevice.postEventFromNative");
        return -1;
    }

    clazz = env->FindClass("android/hardware/gesturedev/GestureResult");
    fields.evt_constructor = env->GetMethodID(clazz, "<init>", "()V");
    if (fields.evt_constructor == NULL) {
        LOGE("Can't find android/hardware/gesturedev/GestureResult.GestureResult()");
        return -1;
    }

    clazz = env->FindClass("android/hardware/gesturedev/GestureResult$GSVector");
    fields.vector_constructor = env->GetMethodID(clazz, "<init>", "()V");
    if (fields.vector_constructor == NULL) {
        LOGE("Can't find android/hardware/gesturedev/GestureResult.GSVector()");
        return -1;
    }

    // Register native functions
    return AndroidRuntime::registerNativeMethods(env, "android/hardware/gesturedev/GestureDevice",
                                              gestureDeviceMethods, NELEM(gestureDeviceMethods));
}
