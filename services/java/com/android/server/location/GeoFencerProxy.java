/* Copyright (c) 2012, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
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
 *
 */

package com.android.server.location;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.app.PendingIntent;
import android.location.IGeoFencer;
import android.location.IGeoFenceListener;
import android.location.GeoFenceParams;

/**
 * A class for proxying IGeoFenceProvider implementations.
 *
 * {@hide}
 */
public class GeoFencerProxy extends GeoFencerBase {

    private static final String TAG = "GeoFencerProxy";
    private static final boolean LOGV_ENABLED = true;

    private final Context mContext;
    private final Intent mIntent;
    private IGeoFencer mGeoFencer;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            synchronized (this) {
                mGeoFencer = IGeoFencer.Stub.asInterface(service);
                notifyAll();
            }
            Log.v(TAG, "onServiceConnected: mGeoFencer - "+mGeoFencer);
        }
        public void onServiceDisconnected(ComponentName className) {
            synchronized (this) {
                mGeoFencer = null;
            }
            Log.v(TAG, "onServiceDisconnected");
        }
    };

    private final IGeoFenceListener.Stub mListener = new IGeoFenceListener.Stub() {
        @Override
        public void geoFenceExpired(PendingIntent intent) throws RemoteException {
            logv("geoFenceExpired - "+intent);
            remove(intent, true);
        }
    };

    private static GeoFencerProxy mGeoFencerProxy;
    public static GeoFencerProxy getGeoFencerProxy(Context context, String serviceName) {
        if (mGeoFencerProxy == null) {
            mGeoFencerProxy = new GeoFencerProxy(context, serviceName);
        }
        return mGeoFencerProxy;
    }

    private GeoFencerProxy(Context context, String serviceName) {
        mContext = context;
        mIntent = new Intent(serviceName);
        mContext.bindService(mIntent, mServiceConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND
                | Context.BIND_ALLOW_OOM_MANAGEMENT);
    }

    public void removeCaller(int uid) {
        super.removeCaller(uid);
        try {
            mGeoFencer.clearGeoFenceUser(uid);
        } catch (RemoteException re) {
        }
    }

    private boolean ensureGeoFencer() {
        if (mGeoFencer == null) {
            try {
                synchronized(mServiceConnection) {
                    logv("waiting...");
                    mServiceConnection.wait(60000);
                    logv("woke up!!!");
                }
            } catch (InterruptedException ie) {
                Log.w(TAG, "Interrupted while waiting for GeoFencer");
                return false;
            }

            if (mGeoFencer == null) {
                Log.w(TAG, "Timed out. No GeoFencer connection");
                return false;
            }
        }

        return true;
    }

    protected boolean start(GeoFenceParams geofence) {
        if (ensureGeoFencer()) {
            try {
                return mGeoFencer.setGeoFence(mListener, geofence);
            } catch (RemoteException re) {
            }
        }
        return false;
    }

    protected boolean stop(PendingIntent intent) {
        if (ensureGeoFencer()) {
            try {
                mGeoFencer.clearGeoFence(mListener, intent);
                return true;
            } catch (RemoteException re) {
            }
        }
        return false;
    }

    private void logv(String s) {
        if (LOGV_ENABLED) Log.v(TAG, s);
    }
}
