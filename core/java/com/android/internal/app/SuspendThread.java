/* Copyright (c) 2012, Code Aurora Forum. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */
package com.android.internal.app;

import android.content.Intent;
import android.content.Context;
import android.os.SystemClock;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.Message;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.app.ActivityManager;
import android.content.SharedPreferences;

import java.util.List;


public final class SuspendThread {
    // constants
    private static final String TAG = "SuspendThread";
    private static final String INPUT_METHOD_SERVICE = "com.android.inputmethod.latin";
    private static final String SUSPEND_PREF = "suspendPrefsFile";

    // state tracking
    private static Object sIsStartedGuard = new Object();
    private static boolean sIsStarted = false;

    // static instance of this thread
    private static final SuspendThread sInstance = new SuspendThread();

    private static Context mContext;

    private SuspendThread() {
    }

    private static boolean aquireSuspendLock() {
        synchronized (sIsStartedGuard) {
            if (sIsStarted) {
                return false;
            }
            sIsStarted = true;
            return true;
        }
    }

    private static void releaseSuspendLock() {
        synchronized (sIsStartedGuard) {
            sIsStarted = false;
        }
        return;
    }

    /**
     * Read  SuspendStatus
     * @param context Context used.
     */
    public static boolean getSuspendStatus(final Context context) {
        SharedPreferences settings;

        if(context == null) {
            Log.d(TAG, "Context is null!");
            return false;
        }

        settings = context.getSharedPreferences(SUSPEND_PREF, 0);
        return settings.getBoolean("suspendStatus", false);
    }

    /**
     * Store SuspendStatus
     * @param context Context used.
     */
    public static void setSuspendStatus(final Context context, boolean value) {
        SharedPreferences settings;
        SharedPreferences.Editor editor;

        if(context == null) {
            Log.d(TAG, "Context is null!");
            return;
        }

        settings = context.getSharedPreferences(SUSPEND_PREF, 0);
        editor = settings.edit();
        editor.putBoolean("suspendStatus", value);
        editor.commit();
    }

    /**
     * Read AirplaneSetBySuspendStatus
     * @param context Context used.
     */
    private static boolean getAirplaneSetBySuspendStatus(final Context context) {
        SharedPreferences settings;

        if(context == null) {
            Log.d(TAG, "Context is null!");
            return false;
        }

        settings = context.getSharedPreferences(SUSPEND_PREF, 0);
        return settings.getBoolean("airplaneSetBySuspend", false);
    }

    /**
     * Store AirplaneSetBySuspendStatus
     * @param context Context used.
     */
    private static void setAirplaneSetBySuspendStatus(final Context context, boolean value) {
        SharedPreferences settings;
        SharedPreferences.Editor editor;

        if(context == null) {
            Log.d(TAG, "Context is null!");
            return;
        }

        settings = context.getSharedPreferences(SUSPEND_PREF, 0);
        editor = settings.edit();
        editor.putBoolean("airplaneSetBySuspend", value);
        editor.commit();
    }

    /**
     * Request a clean suspend, waiting for subsystems.
     * @param context Context used to display the suspend progress dialog.
     * @param confirm true if user confirmation is needed before suspending.
     */
    public static void suspend(final Context context, boolean confirm) {
        // ensure that only one thread is trying to suspend.
        // any additional calls are just returned
        synchronized (sIsStartedGuard) {
            if (sIsStarted) {
                Log.d(TAG, "Request to suspend/wake already running, returning.");
                return;
            }
        }

        beginSuspendSequence(context);
    }

    /**
     * Request a Wake up.
     * @param context Context used to display the suspend progress dialog.
     */
    public static void wakeup(final Context context) {
        // ensure that only one thread is trying to wake up.
        // any additional calls are just returned
        synchronized (sIsStartedGuard) {
            if (sIsStarted) {
                Log.d(TAG, "Request to suspend/wake already running, returning.");
                return;
            }
        }

        beginWakeupSequence(context);
    }

    /**
     * Main suspend function.
     */
    private static void beginSuspendSequence(Context context) {
        if(aquireSuspendLock() == false)
            return;

        if(context == null) {
            Log.d(TAG, "Context is null!");

            //Release Suspend Lock
            releaseSuspendLock();
            return;
        }

        sInstance.mContext = context;

        //Do following only if device is not in suspend state
        if(getSuspendStatus(context) == false) {

            //Store suspend status in persistent storage.
            setSuspendStatus(mContext, true);

            // Should n't suspend during Monkey-run ( Similar to suspend scenario )
            boolean isDebuggableMonkeyBuild =
                SystemProperties.getBoolean("ro.monkey", false);

            if (isDebuggableMonkeyBuild) {
                Log.d(TAG, "Rejected suspend as monkey is detected to be running. Destroying activity..");

                //Release Suspend Lock
                releaseSuspendLock();
                return;
            }

            forceKillActiveServices();

            // Disable keypad , Enable airplane-mode as we are entering RESUME state
            setKeypadEnabled(false);
            setAirplaneModeEnabled(true);
        }

        //Release Suspend Lock
        releaseSuspendLock();

        //Request Suspend
        requestPmSuspend();
    }

    /**
     * Main wakeUp function.
     * @param context Context used to display the suspend progress dialog.
     */
    private static void beginWakeupSequence(Context context) {
        if(aquireSuspendLock() == false)
            return;

        if(context == null) {
            Log.d(TAG, "Context is null!");
            //Release Suspend Lock
            releaseSuspendLock();
            return;
        }
        sInstance.mContext = context;

        // Activity can just quit as the device is back now
        restoreOriginalState();

        // Set property to "disabled"
        setSuspendStatus(mContext, false);

        //Release Suspend Lock
        releaseSuspendLock();
    }

    /**
     * Checks whether any active services are present., Its better to force-stop them( and their originating process'es)
     * to ensure smooth sleep (power-collapse) state is entered.,
     * One Example - MediaplaybackService., Music Application doesn't handle onPause/suspend properly.,
     * Force-Stop == stops the process gracefully and then restarts it in another 5 secs
     */
    private static void forceKillActiveServices() {
        List<ActivityManager.RunningServiceInfo> runningServices;
        ActivityManager activityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);

        // AM's API getRunningServices() requires a ?int? parameter. Here, we are getting first 50 services used/active by user applications.
        // Though this actul figure is quite less
        runningServices = activityManager.getRunningServices(50);

        for (int i = 0; i < runningServices.size(); i++) {
            ActivityManager.RunningServiceInfo si = runningServices.get(i);
            // Don't touch NON-started services
            if (!si.started && si.clientLabel == 0) {
                Log.i( TAG, "Ignore [ !si.started && si.clientLabel == 0  ] : Process = " + si.process + " with component " + si.service.getClassName() );
                continue;
            }
            // Don't touch PERSISTENT services. They are already part of system and wont trouble much
            if ((si.flags&ActivityManager.RunningServiceInfo.FLAG_PERSISTENT_PROCESS) != 0) {
                Log.i( TAG, "Ignore [ FLAG_PERSISTENT_PROCESS  ] : Process = " + si.process + " with component " + si.service.getClassName() + " clientLabel = " + si.clientLabel );
                continue;
            }
            // Don't touch Inputmethod.latin service. If stopped, this (current foreground) activity might be destroyed & recreated.
            if ( si.process.startsWith(INPUT_METHOD_SERVICE) ) {
                Log.i( TAG, "No POINT in killing System-Input Process = " + si.process + " with component " + si.service.getClassName() + " clientLabel = " + si.clientLabel );
                continue;
            }
            try {
                activityManager.forceStopPackage(si.process);
                //  activityManager.killBackgroundProcesses(si.process);
                Log.i( TAG, "forceStopPackage [SUCCESS] : Process = " + si.process + " with component " + si.service.getClassName() + " clientLabel = " + si.clientLabel );
            }
            catch (Exception e) {
                Log.i( TAG, "forceStopPackage [FAIL] ! : Process = " + si.process + " with component " + si.service.getClassName() + " clientLabel = " + si.clientLabel );
            }
        }
    }

    /**
     * Disable Keypad (Except POWER-Key) when device enters suspend state
     * @param context Context used to display the suspend progress dialog.
     */
    private static void setKeypadEnabled( boolean on ) {
        Log.d( TAG, "setKeypadEnabled() : on = " + on);

        String disableStr = (on ? "0" : "1" );
        SystemProperties.set( "hw.keyboard.disable" , disableStr );
    }

    /**
     * Enable Airplane-mode when device enters suspend state., Restore to original state when device resumes.
     * @param context Context used to display the suspend progress dialog.
     */
    private static void setAirplaneModeEnabled( boolean on ) {
        Log.d( TAG, "setAirplaneModeEnabled() : on = " + on);

        if ( on ) {
            int airplaneMode = Settings.System.getInt( mContext.getContentResolver(), Settings.System.AIRPLANE_MODE_ON, 0 );

            boolean AirplaneModeSetBy = ( airplaneMode == 0 );
            changeAirplaneModeSystemSetting(true);

            if ( AirplaneModeSetBy ) {
                setAirplaneSetBySuspendStatus(mContext, true);
                Log.d( TAG, "NO airplaneMode set. Lets enable it!");
            }
            else {
                Log.d( TAG, "AirplaneMode already set. Lets not worry :-)");
            }
        }
        else {
            if ( getAirplaneSetBySuspendStatus(mContext) ) {
                Log.d( TAG, "Suspend has enabled Airplane mode. Lets disable it!" );
                setAirplaneSetBySuspendStatus(mContext, false);
                changeAirplaneModeSystemSetting(false);
            }
            else {
                Log.d( TAG, "AirplaneMode NOT set by SUSPEND. Lets not worry :-)");
            }
        }
    }

    /**
     * Change the airplane mode system setting
     * Copied from "frameworks/policies/base/phone/com/android/internal/policy/impl/GlobalActions.java"
     */
    private static void changeAirplaneModeSystemSetting( boolean on ) {
        Log.d( TAG, "changeAirplaneModeSystemSetting() : " + SystemClock.elapsedRealtime());
        Settings.System.putInt(
                mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON,
                on ? 1 : 0);

        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("state", on);
        mContext.sendBroadcast(intent);
    }

    /**
     * Request Suspend
     */
    private static void  requestPmSuspend() {
        PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        pm.goToSleep( SystemClock.uptimeMillis() );
    }

    /**
     * Does n't need to do anything wrt Device-Resume., This activity's onResume() is called soon after
     * the Device-Resume happens by POWER-key press.,
     * So, if the Airplane Mode was earlier enabled by us, lets disable it and bring back the state to
     * the Device's original state.
     */
    private static void restoreOriginalState() {
        // Enable keypad , Disable airplane-mode as we are entering RESUME state
        setKeypadEnabled(true);
        setAirplaneModeEnabled(false);
    }
}
