/**
 * Copyright (C) 2012, Code Aurora Forum. All rights reserved.
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
 */

package com.android.server.sm;

import android.security.SecurityRecord;
import android.security.SecurityResult;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.util.Log;
import android.os.BatteryManager;

import java.util.HashMap;
import java.util.Iterator;

/** {@hide} */
public final class PowerSaverController {
    private static final String TAG = "PowerSaverController";
    private boolean DEBUG = true;

    private final static int TEMPERATURE_THRESHOLD = 600;
    private final static int BATTERY_LEVEL_THRESHOLD = 15;
    private Context mContext;
    private Intent mBatteryStatus;
    private IntentFilter mFilter;
    private SecurityRecord mCurCtrl;

    private boolean isHighTemperateFlag = false;
    private boolean isLowBatteryFlag = false;
    private boolean isInitCompleted = false;
    private int currMode = -1;
    final PowerSaver mPowerSaver = new PowerSaver();
    HashMap<Integer, String> mAvailModes = new HashMap<Integer, String>();

    private BroadcastReceiver mModeReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                if(DEBUG)Log.e(TAG, "ACTION_SCREEN_ON");
                handleScreenOn();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                if(DEBUG)Log.e(TAG, "ACTION_SCREEN_OFF");
                handleScreenOff();
            } else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                if(DEBUG)Log.e(TAG, "ACTION_BATTERY_CHANGED");
                handleBatteryChanged();
            } else if (action.equals(Intent.ACTION_POWER_CONNECTED)) {
                if(DEBUG)Log.e(TAG, "ACTION_POWER_CONNECTED");
                handleChargingConnect();
            } else if (action.equals(Intent.ACTION_POWER_DISCONNECTED)) {
                if(DEBUG)Log.e(TAG, "ACTION_POWER_DISCONNECTED");
                handleChargingDisconnect();
            }
        }
    };

    public PowerSaverController(Context context) {
        mContext = context;
        mBatteryStatus = mContext.registerReceiver(null, new IntentFilter(
                Intent.ACTION_BATTERY_CHANGED));
    }

    private void handleChargingConnect() {
        int res = SecurityResult.SET_POWERMODE_SUCCESS;
        res |= setCpuMode(PowerSaver.SCREEN_ON_MODE);
    }

    private void handleChargingDisconnect() {
        int res = SecurityResult.SET_POWERMODE_SUCCESS;
        resetFilterAndRegister();
        if (mCurCtrl.isLightComputingMode()) {
            res = enableLightComputingMode();
        }
    }

    private void handleScreenOn() {
        int res = SecurityResult.SET_POWERMODE_SUCCESS;
        int status = mBatteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        if (!isCharging && !isLowBatteryFlag && !isHighTemperateFlag
                && !mCurCtrl.isLightComputingMode()) {
            res |= setCpuMode(PowerSaver.SCREEN_ON_MODE);
        }
    }

    private void handleScreenOff() {
        int res = SecurityResult.SET_POWERMODE_SUCCESS;
        int status = mBatteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        if (!isCharging && !isLowBatteryFlag && !isHighTemperateFlag
                && !mCurCtrl.isLightComputingMode()) {
            res |= setCpuMode(PowerSaver.SCREEN_OFF_MODE);
        }
    }

    private void handleBatteryChanged() {
        int res = SecurityResult.SET_POWERMODE_SUCCESS;
        int status = mBatteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        if (!isCharging) {
            if (mCurCtrl.isLowBatteryMode()) {
                int batterylevel = mBatteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                 if(batterylevel < BATTERY_LEVEL_THRESHOLD){
                    isLowBatteryFlag = true;
                    res |= setCpuMode(PowerSaver.LOW_BATTERY_MODE);
                } else {
                    isLowBatteryFlag = false;
                }
            }

            if (mCurCtrl.isHighTemperatureMode()) {
                int temperature = mBatteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                 if(temperature > TEMPERATURE_THRESHOLD){
                    isHighTemperateFlag = true;
                    if (!isLowBatteryFlag && !mCurCtrl.isLightComputingMode())
                        res |= setCpuMode(PowerSaver.HIGH_TEMPERATURE_MODE);
                } else {
                    isHighTemperateFlag = false;
                }
            }
        }
    }

    private void init(){
        mFilter = new IntentFilter();
        mFilter.addAction(Intent.ACTION_SCREEN_ON);
        mFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        mFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        mContext.registerReceiver(mModeReceiver, mFilter);
        isInitCompleted = true;
    };

    public int setPowerSaverMode(SecurityRecord r) {
        mCurCtrl = r;
        int result = SecurityResult.SET_POWERMODE_SUCCESS;
        boolean isNeedToReset = false;

        if(!isInitCompleted)init();

        if (mCurCtrl.isScreenOffModeChanged()) {
            if (mCurCtrl.isScreenOffMode()) {
                enableScreenOffMode();
            } else {
                disableScreenOffMode();
            }
            if(!isNeedToReset)isNeedToReset = true;
        }

        if (mCurCtrl.isLowBatteryModeChanged()) {
            if (mCurCtrl.isLowBatteryMode()) {
                enableLowBatteryMode();
            } else {
                result = disableLowBatteryMode();
                 if(result != SecurityResult.SET_POWERMODE_SUCCESS)return result;
            }
            if(!isNeedToReset)isNeedToReset = true;
        }

        if (mCurCtrl.isHighTemperatureModeChanged()) {
            if (mCurCtrl.isHighTemperatureMode()) {
                enableHighTemperatureMode();
            } else {
                result = disableHighTemperateMode();
                 if(result != SecurityResult.SET_POWERMODE_SUCCESS)return result;
            }
            if(!isNeedToReset)isNeedToReset = true;
        }

        if (mCurCtrl.isLightComputingModeChanged()) {
            if (mCurCtrl.isLightComputingMode()) {
                result = enableLightComputingMode();
                 if(result != SecurityResult.SET_POWERMODE_SUCCESS)return result;
            } else {
                result = disableLightComputingMode();
                 if(result != SecurityResult.SET_POWERMODE_SUCCESS)return result;
            }
            if(!isNeedToReset)isNeedToReset = true;
        }

        if(isNeedToReset)resetFilterAndRegister();
        return SecurityResult.SET_POWERMODE_SUCCESS;
    }

    private void resetFilterAndRegister() {
        mContext.unregisterReceiver(mModeReceiver);
        
        if(!mCurCtrl.isScreenOffMode() && !mCurCtrl.isLowBatteryMode()
            && !mCurCtrl.isHighTemperatureMode() && !mCurCtrl.isLightComputingMode()){
            isInitCompleted = false;
            return;
        }

        mFilter = new IntentFilter();
        mFilter.addAction(Intent.ACTION_SCREEN_ON);
        mFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        mFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        Iterator iterator = mAvailModes.keySet().iterator();
        while (iterator.hasNext()) {
            mFilter.addAction((String) mAvailModes.get(iterator.next()));
        }
        mContext.registerReceiver(mModeReceiver, mFilter);
    }

    private void enableScreenOffMode() {
        mAvailModes.put(PowerSaver.SCREEN_OFF_MODE, Intent.ACTION_SCREEN_OFF);
    }

    private void disableScreenOffMode() {
        mAvailModes.remove(PowerSaver.SCREEN_OFF_MODE);
    }

    private void enableLowBatteryMode() {
        mAvailModes.put(PowerSaver.LOW_BATTERY_MODE, Intent.ACTION_BATTERY_CHANGED);
    }

    private int disableLowBatteryMode() {
        mAvailModes.remove(PowerSaver.LOW_BATTERY_MODE);
        isLowBatteryFlag = false;
        // Are we charging / charged?
        int status = mBatteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        if(isCharging){
            return setCpuMode(PowerSaver.SCREEN_ON_MODE);
        }else if (!isHighTemperateFlag && !mCurCtrl.isLightComputingMode()) {
            return setCpuMode(PowerSaver.SCREEN_ON_MODE);
        }else if(mCurCtrl.isLightComputingMode()){
            return setCpuMode(PowerSaver.LIGHT_COMPUTING_MODE);
        }
        return SecurityResult.SET_POWERMODE_SUCCESS;
    }

    private void enableHighTemperatureMode() {
        mAvailModes.put(PowerSaver.HIGH_TEMPERATURE_MODE, Intent.ACTION_BATTERY_CHANGED);
    }

    private int disableHighTemperateMode() {
        mAvailModes.remove(PowerSaver.HIGH_TEMPERATURE_MODE);
        isHighTemperateFlag = false;
        // Are we charging / charged?
        int status = mBatteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        if(isCharging){
            return setCpuMode(PowerSaver.SCREEN_ON_MODE);
        }else if (!isLowBatteryFlag && !mCurCtrl.isLightComputingMode()) {
            return setCpuMode(PowerSaver.SCREEN_ON_MODE);
        }
        return SecurityResult.SET_POWERMODE_SUCCESS;
    }

    private int enableLightComputingMode() {
        // Are we charging / charged?
        int status = mBatteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        if (!isCharging && !isLowBatteryFlag) {
            return setCpuMode(PowerSaver.LIGHT_COMPUTING_MODE);
        }
        return SecurityResult.SET_POWERMODE_SUCCESS;
    }

    private int disableLightComputingMode() {
        // Are we charging / charged?
        int status = mBatteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        if(isCharging){
            return setCpuMode(PowerSaver.SCREEN_ON_MODE);
        }else if (!isLowBatteryFlag && !isHighTemperateFlag) {
            return setCpuMode(PowerSaver.SCREEN_ON_MODE);
        }
        return SecurityResult.SET_POWERMODE_SUCCESS;
    }

    private void updateCurrMode(int mode) {
        currMode = mode;
    }

    public int getPowerSaverMode() {
        return currMode;
    }

    private int setCpuMode(int mode) {
       updateCurrMode(mode);
       return mPowerSaver.setCpuMode(mode);
    }
}
