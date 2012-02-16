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
import android.util.Log;
import android.view.View;

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
    }

    @Override
    protected void registerInfoCallback() {
    }

    void setCarrierText() {
        mCarrierText = mCarrierTextSub[MSimConstants.SUB1] + " , " +
                mCarrierTextSub[MSimConstants.SUB2];
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
        switch (mMSimStatus[subscription]) {
            case Normal:
                mCarrierTextSub[subscription] = makeCarierString(mMSimPlmn[subscription],
                        mMSimSpn[subscription]);
                break;

            case PersoLocked:
                mCarrierTextSub[subscription] = makeCarierString(mMSimPlmn[subscription],
                        getContext().getText(R.string.lockscreen_perso_locked_message));
                carrierHelpTextId = R.string.lockscreen_instructions_when_pattern_disabled;
                break;

            case SimMissing:
                mCarrierTextSub[subscription] = getContext().getText
                    (R.string.lockscreen_missing_sim_message_short);
                carrierHelpTextId = R.string.lockscreen_missing_sim_instructions_long;
                break;

            case SimPermDisabled:
                mCarrierTextSub[subscription] = getContext().getText
                    (R.string.lockscreen_missing_sim_message_short);
                carrierHelpTextId = R.string.lockscreen_permanent_disabled_sim_instructions;
                mEmergencyButtonEnabledBecauseSimLocked = true;
                break;

            case SimMissingLocked:
                mCarrierTextSub[subscription] = makeCarierString(mMSimPlmn[subscription],
                        getContext().getText(R.string.lockscreen_missing_sim_message_short));
                carrierHelpTextId = R.string.lockscreen_missing_sim_instructions;
                mEmergencyButtonEnabledBecauseSimLocked = true;
                break;

            case SimLocked:
                mCarrierTextSub[subscription] = makeCarierString(mMSimPlmn[subscription],
                        getContext().getText(R.string.lockscreen_sim_locked_message));
                mEmergencyButtonEnabledBecauseSimLocked = true;
                break;

            case SimPukLocked:
                mCarrierTextSub[subscription] = makeCarierString(mMSimPlmn[subscription],
                        getContext().getText(R.string.lockscreen_sim_puk_locked_message));
                if (!mLockPatternUtils.isPukUnlockScreenEnable()) {
                    mEmergencyButtonEnabledBecauseSimLocked = true;
                }
                break;

            case SimIOError:
                mCarrierTextSub[subscription] = makeCarierString(mMSimPlmn[subscription],
                        getContext().getText(R.string.lockscreen_sim_error_message_short));
                mEmergencyButtonEnabledBecauseSimLocked = true;
                break;
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

}
