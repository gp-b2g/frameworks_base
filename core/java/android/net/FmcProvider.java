/* Copyright (c) 2011 Code Aurora Forum. All rights reserved.
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

package android.net;

import android.net.IConnectivityManager;
import android.net.FeatureConfig;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.IBinder;
import android.util.Log;
import android.os.Handler;
import android.os.Message;
import android.os.Looper;
import android.os.SystemProperties;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** {@hide}
 * This class provides a means for applications to specify their requirements
 * and request for a link. Apps can also report their satisfaction with the
 * assigned link and switch links when a new link is available.
 */
public class FmcProvider
{
    static final String LOG_TAG = "FmcProvider";

    /* FmcNotifier object to provide notification.
     */
    private FmcNotifier mFmcNotifier;

    /* handle to connectivity service obj
     */
    private IConnectivityManager mService;

    private Handler mHandler;

    private NotificationsThread mThread;

    private FmcEventListener mListener;

    private static final int ON_FMC_STATUS  =  1;

    /* Lock to synchronized the Nodify Handler
     */
    private Lock mLock;
    private Condition mHandlerAvail;

    /** {@hide}
     * This constructor can be used by apps to specify
     * FMC notifier object to receive notifications.
     * @param oem network name
     * @param notifier FmcNotifier object to provide notification to
     *                 the calling function
     */
    public FmcProvider(FmcNotifier notifier) throws InterruptedException {
        mFmcNotifier = notifier;

        Log.d(LOG_TAG,"FmcProvider");

        /* get handle to connectivity service */
        IBinder b = ServiceManager.getService("connectivity");
        mService = IConnectivityManager.Stub.asInterface(b);
        /* check for mservice to be null and throw a exception */
        if(mService == null){ throw new IllegalStateException(
                "mService can not be null");
        }

        mListener = (FmcEventListener)
              IFmcEventListener.Stub.asInterface(new FmcEventListener());

        mLock = new ReentrantLock();
        mHandlerAvail  = mLock.newCondition();
        mThread = new NotificationsThread();
        mThread.start();

        /* block until mHandler gets created. */
        try{
            mLock.lock();
            if (mHandler == null) {
                mHandlerAvail.await();
            }
        } finally {
            mLock.unlock();
        }
    }


    /** {@hide}
     * This function will be used by apps to request to start FMC
     * @return {@code true} if the request has been accepted,
     * {@code false} otherwise.  A return value of true does NOT mean that a
     * FMC is available for the app to use. That will delivered via
     * the FmcNotifier.
     */
    public boolean startFmc(){
        Log.d(LOG_TAG,"FmcProvider@startFmc");

        if (!FeatureConfig.isEnabled(FeatureConfig.FMC)) {
            Log.w(LOG_TAG, "startFmc: FMC is disabled. This API is invalid.");
            return false;
        }

        try {
            return mService.startFmc(mListener);
        } catch ( RemoteException e ) {
           Log.w(LOG_TAG,"FmcProvider@startFmc: RemoteException");
            e.printStackTrace();
            return false;
        }
    }

    /** {@hide}
    * This function will be used by apps to stop FMC.
    * @return {@code true} if the request has been accepted by Android
    * framework, {@code false} otherwise.
    */
   public boolean stopFmc(){
       Log.d(LOG_TAG,"FmcProvider@stopFmc");
       try {
           return mService.stopFmc(mListener);
       } catch ( RemoteException e ) {
           Log.w(LOG_TAG,"FmcProvider@stopFmc: RemoteException");
           e.printStackTrace();
           return false;
       }
   }

    /** {@hide}
    * This function will be used by apps to get FMC status.
    * @return integer representing enum of FmcNotifier status.
    */
   public int getFmcStatus(){
       Log.d(LOG_TAG,"FmcProvider@getFmcStatus");
       try {
           return mService.getFmcStatus(mListener);
       } catch ( RemoteException e ) {
           Log.w(LOG_TAG,"FmcProvider@getFmcStatus: RemoteException");
           e.printStackTrace();
           return -1;
       }
   }

    /** {@hide}
     * Callback functions.
     */
    private class FmcEventListener extends IFmcEventListener.Stub {
        /** {@hide}
        * Callback function to send status of current FMC.
        * @param status notify calling function with status.
        */
        public void onFmcStatus(int status) {
            //Log.d(LOG_TAG,"FmcProvider@onFmcStatus: status = "+status);
            Message msg = mHandler.obtainMessage(ON_FMC_STATUS, status, 0);
            msg.setTarget(mHandler);
            msg.sendToTarget();
            return;
        }
    };

    private class NotificationsThread extends Thread {
        public void run() {
            Looper.prepare();

            mHandler = new Handler(){
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case ON_FMC_STATUS:{
                        int status = (int)msg.arg1;
                        if(mFmcNotifier != null){
                            mFmcNotifier.onFmcStatus(status);
                        } else {
                            Log.d(LOG_TAG,"FmcProvider@handleMessage: mFmcNotifier callback is NULL");
                        }
                        break;
                    }
                    default:
                        Log.w(LOG_TAG,"FmcProvider@handleMessage: msg.what" + msg.what);
                        break;
                    }
                }
            };

            mLock.lock();
            mHandlerAvail.signal();
            mLock.unlock();

            Looper.loop();
        }
    };
}

