/*
 * Copyright 2007, The Android Open Source Project
 * Copyright (c) 2010-2011, Code Aurora Forum. All rights reserved.
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

package com.android.server;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Environment;
import android.os.IHDMIService;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UEventObserver;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManagerPolicy;

import java.io.File;
import java.io.FileReader;
import java.util.StringTokenizer;

/**
 * @hide
 */
class HDMIService extends IHDMIService.Stub {

    private static final String TAG = "HDMIService";

    private Context mContext;

    private HDMIListener mListener;
    private boolean mHDMIUserOption = false;
    private int mHDMIModes[];
    private int mCurrHDMIMode = -1;
    public final String HDMICableConnectedEvent = "HDMI_CABLE_CONNECTED";
    public final String HDMICableDisconnectedEvent = "HDMI_CABLE_DISCONNECTED";
    public final String HDMIONEvent = "HDMI_CONNECTED";
    public final String HDMIOFFEvent = "HDMI_DISCONNECTED";

    final int m640x480p60_4_3         = 1;
    final int m720x480p60_4_3         = 2;
    final int m720x480p60_16_9        = 3;
    final int m1280x720p60_16_9       = 4;
    final int m1920x1080i60_16_9      = 5;
    final int m1440x480i60_4_3        = 6;
    final int m1440x480i60_16_9       = 7;
    final int m1920x1080p60_16_9      = 16;
    final int m720x576p50_4_3         = 17;
    final int m720x576p50_16_9        = 18;
    final int m1280x720p50_16_9       = 19;
    final int m1440x576i50_4_3        = 21;
    final int m1440x576i50_16_9       = 22;
    final int m1920x1080p50_16_9      = 31;
    final int m1920x1080p24_16_9      = 32;
    final int m1920x1080p25_16_9      = 33;
    final int m1920x1080p30_16_9      = 34;

    int getModeOrder(int mode)
    {
        switch (mode) {
        default:
        case m1440x480i60_4_3:
        case m1440x480i60_16_9:
            return 1; // 480i
        case m1440x576i50_4_3:
        case m1440x576i50_16_9:
            return 2; // 576i
        case m640x480p60_4_3:
            return 3; // 480p x640
        case m720x480p60_4_3:
        case m720x480p60_16_9:
            return 4; // 480p x720
        case m720x576p50_4_3:
        case m720x576p50_16_9:
            return 5; // 576p
        case m1920x1080i60_16_9:
            return 6; // 1080i
        case m1280x720p60_16_9:
        case m1280x720p50_16_9:
            return 7; // 720p
        case m1920x1080p24_16_9:
        case m1920x1080p25_16_9:
        case m1920x1080p30_16_9:
        case m1920x1080p50_16_9:
        case m1920x1080p60_16_9:
            return 8;
        }
    }

    int getBestMode()
    {
        int bestOrder = 0, bestMode = m640x480p60_4_3;
        for (int mode : mHDMIModes) {
            int order = getModeOrder(mode);
            if (order > bestOrder) {
                bestOrder = order;
                bestMode = mode;
            }
        }
        return bestMode;
    }

    public HDMIService(Context context) {
        mContext = context;
        // Register a BOOT_COMPLETED handler so that we can start
        // HDMIListener. We defer the startup so that we don't
        // start processing events before we ought-to
        mContext.registerReceiver(mBroadcastReceiver,
                new IntentFilter(Intent.ACTION_BOOT_COMPLETED), null, null);
        mListener =  new HDMIListener(this);
        String hdmiUserOption = Settings.System.getString(
                              mContext.getContentResolver(),
                  "HDMI_USEROPTION");
        if (hdmiUserOption != null && hdmiUserOption.equals("HDMI_ON"))
            mHDMIUserOption = true;
    }

    BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_BOOT_COMPLETED) &&
                    (SystemProperties.getBoolean("ro.hdmi.enable", false))


            ) {
                Thread thread = new Thread(mListener, HDMIListener.class.getName());
                thread.start();
            }
        }
    };

    public void shutdown() {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.SHUTDOWN)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires SHUTDOWN permission");
        }

        Log.e(TAG, "Shutting down");
    }

    public boolean isHDMIConnected() {
        return mListener.isHDMIConnected();
    }

    public void setHDMIOutput(boolean enableHDMI) {
        Settings.System.putString(mContext.getContentResolver(),
            "HDMI_USEROPTION", enableHDMI ? "HDMI_ON" : "HDMI_OFF");
        mHDMIUserOption = enableHDMI;

        synchronized(mListener) {
            if(enableHDMI == false) {
                boolean connected = false;
                broadcastHDMIPluggedEvent(connected);
                broadcastEvent(HDMICableDisconnectedEvent);
                mListener.enableHDMIOutput(false);
            }
            mListener.setHPD(getHDMIUserOption());
        }
    }

    public void setActionsafeWidthRatio(float asWidthRatio){
        mListener.setActionsafeWidthRatio(asWidthRatio);
    }

    public void setActionsafeHeightRatio(float asHeightRatio){
        mListener.setActionsafeHeightRatio(asHeightRatio);
    }

    public boolean getHDMIUserOption() {
        return mHDMIUserOption;
    }

    public void setMode(int mode) {
        mCurrHDMIMode = mode;
        mListener.changeDisplayMode(mode);
    }

    public int[] getModes() {
        return mHDMIModes;
    }

    public void broadcastEvent(String eventName) {
        Intent intent = new Intent(eventName);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        mContext.sendStickyBroadcast(intent);
        Log.e(TAG, "Broadcasting ... " + eventName);
    }

    public void broadcastEvent(String eventName, int[] modes) {
        Intent intent = new Intent(eventName);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.putExtra("EDID", modes);
        mContext.sendStickyBroadcast(intent);
        Log.e(TAG, "Broadcasting ... " + eventName + ", modes: " + modes.length);
    }

    public void broadcastHDMIPluggedEvent(boolean connected) {
        Intent intent = new Intent(WindowManagerPolicy.ACTION_HDMI_PLUGGED);
        intent.putExtra(WindowManagerPolicy.EXTRA_HDMI_PLUGGED_STATE, connected);
        if(connected)
            intent.putExtra("EDID", mHDMIModes);
        mContext.sendStickyBroadcast(intent);
        Log.e(TAG, "Broadcasting ... ACTION_HDMI_PLUGGED state = " + connected);
    }

    public void notifyHDMIConnected(int[] modes) {
        boolean connected = true;
        mHDMIModes = modes;
        broadcastEvent(HDMICableConnectedEvent);
        if(getHDMIUserOption()) {
            synchronized(mListener) {
                if(mCurrHDMIMode == -1) {
                    mCurrHDMIMode = getBestMode();
                }
                mListener.changeDisplayMode(mCurrHDMIMode);
                mListener.enableHDMIOutput(true);
            }
            if((mListener.getOnlineBroadcast())) {
                Log.d(TAG, "Broadcast HDMI connected");
                broadcastHDMIPluggedEvent(connected);
            }
        }
    }

    public void notifyHDMIDisconnected() {
        boolean connected = false;
        mHDMIModes = null;
        broadcastEvent(HDMICableDisconnectedEvent);
        if(getHDMIUserOption()) {
             broadcastHDMIPluggedEvent(connected);
             synchronized(mListener) {
                mListener.enableHDMIOutput(false);
                mListener.setHPD(getHDMIUserOption());
            }
        }
    }

    public void notifyHDMIAudioOn() {
        boolean connected = true;
        if(getHDMIUserOption()) {
            broadcastHDMIPluggedEvent(connected);
        }
    }

    public void notifyHDMIAudioOff() {
        boolean connected = false;
        if(getHDMIUserOption()) {
            broadcastHDMIPluggedEvent(connected);
       }
    }
}
