/*
 * Copyright (c) 2012 Code Aurora Forum. All rights reserved.
 * Copyright (C) 2008 The Android Open Source Project
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

package android.hardware.gesturedev;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

/**
 * @hide
 */
public class GestureDevice {
    private static final String TAG = "GestureDevice";
    private int mNativeContext; // accessed by native methods
    private int mDeviceId;
    private EventHandler mEventHandler = null;
    private ArrayList<ErrorCallback> mErrorCBList = null;
    private ArrayList<GestureListener> mListenerList = null;
    private boolean mGestureRunning = false;

    private static GestureDevice[] mDeviceList = null;
    private static int[] mRefCountList = null;
    private static int mNumOfDevice = 0;

    private native final void native_setup(Object gesture_this, int gestureId);

    private native final void native_release();

    private native final void native_setParameters(String params);

    private native final String native_getParameters();

    private native final void native_startGesture();

    private native final void native_stopGesture();

    private static final int GESTURE_MSG_ERROR = 0x001;
    private static final int GESTURE_MSG_GESTURE = 0x002;
    private static final int GESTURE_MSG_ALL_MSGS = 0xFFF;

    private GestureDevice(int deviceId) {
        mDeviceId = deviceId;
        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
        }

        mErrorCBList = new ArrayList<ErrorCallback>();
        mListenerList = new ArrayList<GestureListener>();

        native_setup(new WeakReference<GestureDevice>(this), deviceId);
    }

    protected void finalize() {
        release();
    }

    private void release() {
        if (mErrorCBList != null) {
            mErrorCBList.clear();
            mErrorCBList = null;
        }
        if (mListenerList != null) {
            mListenerList.clear();
            mListenerList = null;
        }

        native_release();
        mGestureRunning = false;
    }

    private static void postEventFromNative(Object gesture_ref, int what,
            int arg1, int arg2, Object obj) {
        GestureDevice c = (GestureDevice) ((WeakReference) gesture_ref).get();
        if (c == null)
            return;

        if (c.mEventHandler != null) {
            Message m = c.mEventHandler.obtainMessage(what, arg1, arg2, obj);
            c.mEventHandler.sendMessage(m);
        }
    }

    private class EventHandler extends Handler {
        private GestureDevice mGesture;

        public EventHandler(GestureDevice c, Looper looper) {
            super(looper);
            mGesture = c;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case GESTURE_MSG_GESTURE:
                if (mListenerList != null) {
                    synchronized (mGesture) {
                        for (int i = 0; i < mListenerList.size(); i++) {
                            GestureListener listener = mListenerList.get(i);
                            if (listener != null) {
                                listener.onGestureResult(
                                        (GestureResult[]) msg.obj, mGesture);
                            }
                        }
                    }
                }
                return;

            case GESTURE_MSG_ERROR:
                Log.e(TAG, "Error " + msg.arg1);
                if (mErrorCBList != null) {
                    synchronized (mGesture) {
                        for (int i = 0; i < mErrorCBList.size(); i++) {
                            ErrorCallback cb = mErrorCBList.get(i);
                            if (cb != null) {
                                cb.onError(msg.arg1, mGesture);
                            }
                        }
                    }
                }
                return;

            default:
                Log.e(TAG, "Unknown message type " + msg.what);
                return;
            }
        }
    };

    /**
     * Returns the number of gesture device available on this device.
     */
    public native static int getNumberOfGestureDevices();

    /**
     * Registers a callback to be invoked when an error occurs.
     * 
     * @param cb
     *            The callback to run
     */
    public synchronized void registerErrorCallback(ErrorCallback cb, boolean reg) {
        if (reg) {
            mErrorCBList.add(cb);
        } else {
            mErrorCBList.remove(cb);
        }
    }

    /**
     * Registers a listener to be notified when gesture result is available from
     * gesture processing.
     * 
     * @param listener
     *            the listener to notify
     * @see #startGesture()
     */
    public synchronized void registerGestureListener(GestureListener listener,
            boolean reg) {
        if (reg) {
            mListenerList.add(listener);
        } else {
            mListenerList.remove(listener);
        }
    }

    /**
     * Starts the gesture processing. The gestureDevice will notify
     * {@link GestureListener} of the gesture processing result. Applications
     * should call {@link #stopGesture} to stop the geture processing.
     * 
     * @throws IllegalArgumentException
     *             if the gesture procesing is unsupported.
     * @throws RuntimeException
     *             if the method fails or the gesture processing is already
     *             running.
     * @see GestureListener
     * @see #stopGesture()
     */
    public synchronized void startGesture() {
        if (mGestureRunning) {
            throw new RuntimeException("Gesture is already running");
        }
        native_startGesture();
        mGestureRunning = true;
    }

    /**
     * Stops the gesture processing.
     * 
     * @see #startGesture()
     */
    public synchronized void stopGesture() {
        native_stopGesture();
        mGestureRunning = false;
    }

    /**
     * Changes the settings for this gesture device service.
     * 
     * @param params
     *            the Parameters to use for this Gesture device service
     * @throws RuntimeException
     *             if any parameter is invalid or not supported, or the gesture
     *             processing is already running..
     * @see #getParameters()
     */
    public synchronized void setParameters(GestureParameters params) {
        native_setParameters(params.flatten());
    }

    /**
     * Returns the current settings for this Gesture device service. If
     * modifications are made to the returned Parameters, they must be passed to
     * {@link #setParameters(GestureParameters)} to take effect.
     * 
     * @see #setParameters(GestureParameters)
     */
    public synchronized GestureParameters getParameters() {
        GestureParameters p = new GestureParameters();
        String s = native_getParameters();
        Log.e(TAG, "getParameter: (" + s + ")");
        p.unflatten(s);
        return p;
    }

    /**
     * Creates a new GestureDevice object to process guesture.
     * 
     * <p>
     * You must call {@link #release()} when you are done using the gesture
     * device, otherwise it will remain locked and be unavailable to other
     * applications.
     * 
     * <p>
     * Callbacks from other methods are delivered to the event loop of the
     * thread which called open(). If this thread has no event loop, then
     * callbacks are delivered to the main application event loop. If there is
     * no main application event loop, callbacks are not delivered.
     * 
     * <p class="caution">
     * <b>Caution:</b> On some devices, this method may take a long time to
     * complete. It is best to call this method from a worker thread (possibly
     * using {@link android.os.AsyncTask}) to avoid blocking the main
     * application UI thread.
     * 
     * @return a new GestureDevice object, connected and ready for use.
     * @throws RuntimeException
     *             if connection to the GestureDevice service fails or invalid
     *             deviceId is passed in.
     */
    public static synchronized GestureDevice open(int deviceId) {
        if (mNumOfDevice == 0) {
            mNumOfDevice = getNumberOfGestureDevices();
        }
        if (mDeviceList == null && mNumOfDevice > 0) {
            mDeviceList = new GestureDevice[mNumOfDevice];
            mRefCountList = new int[mNumOfDevice];
        }

        if ((deviceId < 0) || (deviceId >= mNumOfDevice)) {
            throw new RuntimeException("Invalid device ID");
        }

        if (mDeviceList[deviceId] == null) {
            mDeviceList[deviceId] = new GestureDevice(deviceId);
            mRefCountList[deviceId] = 0;
        }

        mRefCountList[deviceId]++;
        return mDeviceList[deviceId];
    }

    /**
     * Disconnects and releases the Gesture Device object resources.
     * 
     * <p>
     * You must call this as soon as you're done with the gesture object.
     * </p>
     */
    public static synchronized void close(GestureDevice device) {
        int deviceId = device.mDeviceId;
        if ((mRefCountList != null) && (mDeviceList != null)) {
            mRefCountList[deviceId]--;
            if (mRefCountList[deviceId] == 0) {
                device.release();
                mDeviceList[deviceId] = null;
            } else if (mRefCountList[deviceId] < 0) {
                mRefCountList[deviceId] = 0;
            }
        }
    }

    /**
     * Callback interface for gesture processing.
     * 
     */
    public interface GestureListener {
        /**
         * Notify the listener of the detected gestures.
         * 
         * @param result
         *            The result from gesture processing
         * @param gesture
         *            The {@link GestureDevice} service object
         */
        void onGestureResult(GestureResult[] results, GestureDevice gesture);
    }

    /**
     * Unspecified gesture device error.
     * 
     * @see GestureDevice.ErrorCallback
     */
    public static final int GESTURE_ERROR_UNKNOWN = 1;

    /**
     * Media server died. In this case, the application must release the
     * GestureDevice object and instantiate a new one.
     * 
     * @see GestureDevice.ErrorCallback
     */
    public static final int GESTURE_ERROR_SERVER_DIED = 100;

    /**
     * Callback interface for camera error notification.
     * 
     * @see #setErrorCallback(ErrorCallback)
     */
    public interface ErrorCallback {
        /**
         * Callback for gesture processing errors.
         * 
         * @param error
         *            error code:
         *            <ul>
         *            <li>{@link #GESTURE_ERROR_UNKNOWN}
         *            <li>{@link #GESTURE_ERROR_SERVER_DIED}
         *            </ul>
         * @param device
         *            the Gesture service object
         */
        void onError(int error, GestureDevice device);
    };
}
