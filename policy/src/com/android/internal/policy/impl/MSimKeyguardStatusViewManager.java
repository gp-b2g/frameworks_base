/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (c) 2010-2012, Code Aurora Forum. All rights reserved.
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

package com.android.internal.policy.impl;

import com.android.internal.R;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.IccCard.State;
import com.android.internal.telephony.MSimConstants;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.policy.impl.KeyguardUpdateMonitor.SimStateCallback;

import libcore.util.MutableInt;

import android.telephony.MSimTelephonyManager;
import android.telephony.TelephonyManager;
import android.util.LocaleNamesParser;
import android.util.Log;
import android.view.View;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Message;
import android.content.BroadcastReceiver;
import android.content.Context;

/***
 * Manages a number of views inside of LockScreen layouts. See below for a list of widgets
 *
 */
class MSimKeyguardStatusViewManager extends KeyguardStatusViewManager {
    private static final boolean DEBUG = false;
    private static final String TAG = "MSimKeyguardStatusView";

    private StatusMode[] mMSimStatus = {StatusMode.Normal};
    private CharSequence[] mCarrierTextSub;

    private CharSequence[] mMSimPlmn;
    private CharSequence[] mMSimSpn;

    private int mNumPhones;

    // last known SIM state
    private State[] mMSimState;

    //flag whether airplane mode is on
    private boolean mAirplaneMode;
    private static final int MSG_AIRPLANE_MODE_CHANGED = 1;

    private LocaleNamesParser localeNamesParser;

    public MSimKeyguardStatusViewManager(View view, KeyguardUpdateMonitor updateMonitor,
                LockPatternUtils lockPatternUtils, KeyguardScreenCallback callback,
                boolean emergencyButtonEnabledInScreen) {
        super(view, updateMonitor, lockPatternUtils, callback, emergencyButtonEnabledInScreen);
        if (DEBUG) Log.v(TAG, "KeyguardStatusViewManager()");
        mNumPhones = TelephonyManager.getDefault().getPhoneCount();
        mCarrierTextSub = new CharSequence[mNumPhones];
        mMSimPlmn = new CharSequence[mNumPhones];
        mMSimSpn = new CharSequence[mNumPhones];
        mMSimState = new State[mNumPhones];
        mMSimStatus = new StatusMode[mNumPhones];

        try {
            mAirplaneMode = Settings.System.getInt(getContext().getContentResolver(),
                            Settings.System.AIRPLANE_MODE_ON) == 1;
        } catch (SettingNotFoundException snfe) {
            Log.w(TAG,"get airplane mode exception");
        }

        // Sim States for the subscription
        for (int i = 0; i < mNumPhones; i++) {
            mMSimStatus[i] = StatusMode.Normal;
            mCarrierTextSub[i] = null;
            mMSimPlmn[i] = null;
            mMSimSpn[i] = null;
            mMSimState[i] = IccCard.State.READY;
        }
        mUpdateMonitor.registerInfoCallback(mMSimInfoCallback);
        mUpdateMonitor.registerSimStateCallback(mSimStateCallback);
        resetStatusInfo();

        mStatus = mMSimStatus[MSimTelephonyManager.getDefault().getDefaultSubscription()];
        mPlmn = mMSimPlmn[MSimTelephonyManager.getDefault().getDefaultSubscription()];
        mSpn = mMSimSpn[MSimTelephonyManager.getDefault().getDefaultSubscription()];
        mSimState = mMSimState[MSimTelephonyManager.getDefault().getDefaultSubscription()];


        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        getContext().registerReceiver(mAirplaneReceiver, filter);
    }

    @Override
    protected void registerInfoCallback() {
    }

    private String getMultiSimName(int subscription) {
        return Settings.System.getString(getContext().getContentResolver(),
                Settings.System.MULTI_SIM_NAME[subscription]);
    }

    void setCarrierText() {
        mCarrierText = getMultiSimName(MSimConstants.SUB1) + ":" + mCarrierTextSub[MSimConstants.SUB1] + "    " +
                getMultiSimName(MSimConstants.SUB2) + ":" + mCarrierTextSub[MSimConstants.SUB2];
        update(CARRIER_TEXT, mCarrierText);
    }

    @Override
    public void onPause() {
        if (DEBUG) Log.v(TAG, "onPause()");
        mUpdateMonitor.removeCallback(mMSimInfoCallback);
        mUpdateMonitor.removeCallback(mSimStateCallback);
    }

    @Override
    /** {@inheritDoc} */
    public void onResume() {
        if (DEBUG) Log.v(TAG, "onResume()");
        mUpdateMonitor.registerInfoCallback(mMSimInfoCallback);
        mUpdateMonitor.registerSimStateCallback(mSimStateCallback);
        resetStatusInfo();
    }

    /**
     * Update carrier text, carrier help and emergency button to match the current status based
     * on SIM state.
     *
     * @param simState
     */
    private void updateCarrierStateWithSimStatus(State simState, int subscription) {
        if (DEBUG) Log.d(TAG, "updateCarrierStateWithSimStatus(), simState = " + simState +
                " subscription = " + subscription);

        int carrierHelpTextId = 0;
        mEmergencyButtonEnabledBecauseSimLocked = false;
        mMSimStatus[subscription] = getStatusForIccState(simState);
        mMSimState[subscription] = simState;

        //if airplane mode is on, show "airplane mode"
        if (mAirplaneMode) {
            mCarrierTextSub[subscription] = getContext().getText(R.string.airplane_mode_on_message);
            carrierHelpTextId = R.string.airplane_mode_on_message;
        } else {
            if (localeNamesParser == null) {
                localeNamesParser = new LocaleNamesParser(getContext(), TAG,
                        com.android.internal.R.array.origin_carrier_names,
                        com.android.internal.R.array.locale_carrier_names);
            } else {
                localeNamesParser.reload();
            }
            CharSequence localPlmn = localeNamesParser.getLocaleName(mMSimPlmn[subscription]);
            switch (mMSimStatus[subscription]) {
                case Normal:
                    mCarrierTextSub[subscription] = makeCarierString(localPlmn,
                            mMSimSpn[subscription]);
                    break;

                case PersoLocked:
                    mCarrierTextSub[subscription] = makeCarrierStringOnEmergencyCapable(
                            getContext().getText(R.string.lockscreen_perso_locked_message),
                            localPlmn);
                    carrierHelpTextId = R.string.lockscreen_instructions_when_pattern_disabled;
                    break;

                case SimMissing:
                    // Shows "No SIM card | Emergency calls only" on devices that are voice-capable.
                    // This depends on mPlmn containing the text "Emergency calls only" when the radio
                    // has some connectivity. Otherwise, it should be null or empty and just show
                    // "No SIM card"
                    mCarrierTextSub[subscription] =  makeCarrierStringOnEmergencyCapable(
                            getContext().getText(R.string.lockscreen_missing_sim_message_short),
                            localPlmn);
                    carrierHelpTextId = R.string.lockscreen_missing_sim_instructions_long;
                    break;

                case SimPermDisabled:
                    mCarrierTextSub[subscription] = getContext().getText
                        (R.string.lockscreen_missing_sim_message_short);
                    carrierHelpTextId = R.string.lockscreen_permanent_disabled_sim_instructions;
                    mEmergencyButtonEnabledBecauseSimLocked = true;
                    break;

                case SimMissingLocked:
                    mCarrierTextSub[subscription] =  makeCarrierStringOnEmergencyCapable(
                            getContext().getText(R.string.lockscreen_missing_sim_message_short),
                            localPlmn);
                    carrierHelpTextId = R.string.lockscreen_missing_sim_instructions;
                    mEmergencyButtonEnabledBecauseSimLocked = true;
                    break;

                case SimLocked:
                    mCarrierTextSub[subscription] = makeCarrierStringOnEmergencyCapable(
                           getContext().getText(R.string.lockscreen_sim_locked_message),
                           localPlmn);
                    mEmergencyButtonEnabledBecauseSimLocked = true;
                    break;

                case SimPukLocked:
                    mCarrierTextSub[subscription] = makeCarrierStringOnEmergencyCapable(
                            getContext().getText(R.string.lockscreen_sim_puk_locked_message),
                            localPlmn);
                    if (!mLockPatternUtils.isPukUnlockScreenEnable()) {
                        // This means we're showing the PUK unlock screen
                        mEmergencyButtonEnabledBecauseSimLocked = true;
                    }
                    break;

                case SimIOError:
                    mCarrierTextSub[subscription] = makeCarierString(localPlmn,
                            getContext().getText(R.string.lockscreen_sim_error_message_short));
                    mEmergencyButtonEnabledBecauseSimLocked = true;
                    break;
                case SimDeactivated:
                    mCarrierTextSub[subscription] = getContext().getText(R.string.lockscreen_sim_deactivate);
                    carrierHelpTextId = R.string.lockscreen_sim_deactivate;
                    break;
            }
        }
        setCarrierText();
        setCarrierHelpText(carrierHelpTextId);
        updateEmergencyCallButtonState(mPhoneState);
    }

    private KeyguardUpdateMonitor.InfoCallback mMSimInfoCallback
            = new KeyguardUpdateMonitor.InfoCallback() {

        public void onRefreshBatteryInfo(boolean showBatteryInfo, boolean pluggedIn,
                int batteryLevel) {
            mShowingBatteryInfo = showBatteryInfo;
            mPluggedIn = pluggedIn;
            mBatteryLevel = batteryLevel;
            final MutableInt tmpIcon = new MutableInt(0);
            update(BATTERY_INFO, getAltTextMessage(tmpIcon));
        }

        public void onTimeChanged() {
            refreshDate();
        }

        public void onRefreshCarrierInfo(CharSequence plmn, CharSequence spn) {
            // ignored
        }

        public void onRefreshCarrierInfo(CharSequence plmn, CharSequence spn, int subscription) {
            mMSimPlmn[subscription] = plmn;
            mMSimSpn[subscription] = spn;
            updateCarrierStateWithSimStatus(mMSimState[subscription], subscription);
        }

        public void onRingerModeChanged(int state) {

        }

        public void onPhoneStateChanged(int phoneState) {
            mPhoneState = phoneState;
            updateEmergencyCallButtonState(phoneState);
        }

        /** {@inheritDoc} */
        public void onClockVisibilityChanged() {
            // ignored
        }

        public void onDeviceProvisioned() {
            // ignored
        }
    };

    private SimStateCallback mSimStateCallback = new SimStateCallback() {

        public void onSimStateChanged(State simState) {
            // ignored
        }

        public void onSimStateChanged(State simState, int subscription) {
            updateCarrierStateWithSimStatus(simState, subscription);
        }
    };

    private BroadcastReceiver mAirplaneReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.d(TAG, "received broadcast " + action);
            if(Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                handleAirplaneModeChanged(intent);
            }
        }
    };

    public void handleAirplaneModeChanged(Intent intent) {
        mAirplaneMode = intent.getBooleanExtra("state", false);
        mHandler.sendEmptyMessage(MSG_AIRPLANE_MODE_CHANGED);
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_AIRPLANE_MODE_CHANGED:
                Log.d(TAG, "handle message MSG_AIRPLANE_MODE_CHANGED");
                for (int i = 0; i < MSimConstants.MAX_PHONE_COUNT_DS; i++) {
                    updateCarrierStateWithSimStatus(mMSimState[i], i);
                }
                break;
            }
        }
    };

}
