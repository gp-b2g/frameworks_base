/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2011-2012, Code Aurora Forum. All rights reserved.
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

import android.content.Context;
import android.content.res.Configuration;
import android.os.RemoteException;
import android.os.ServiceManager;

import android.telephony.TelephonyManager;
import com.android.internal.telephony.ITelephonyMSim;
import com.android.internal.telephony.Phone;
import com.android.internal.widget.LockPatternUtils;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;
import com.android.internal.R;
import android.util.Log;

/**
 * Displays a dialer like interface to unlock the SIM PIN.
 */
public class MSimSimUnlockScreen extends SimUnlockScreen implements KeyguardScreen,
        View.OnClickListener {

    private int mSubscription = 0;

    private static final int[] pinStrIds = {R.string.keyguard_password_enter_sim1_pin_code,
                                            R.string.keyguard_password_enter_sim2_pin_code};

    public MSimSimUnlockScreen(Context context, Configuration configuration,
        KeyguardUpdateMonitor updateMonitor, KeyguardScreenCallback callback,
        LockPatternUtils lockpatternutils, int subscription) {
        super(context, configuration, updateMonitor, callback,
                lockpatternutils);
        layoutType(context);
        mSubscription = subscription;
        if (TelephonyManager.getDefault().isMultiSimEnabled()) {
            String displayText = getContext().getString(pinStrIds[mSubscription]);
            mHeaderText.setText(displayText);
        } else {
            mHeaderText.setText(R.string.keyguard_password_enter_pin_code);
        }

        if (TelephonyManager.getDefault().isMultiSimEnabled()) {
            mKeyguardStatusViewManager = new MSimKeyguardStatusViewManager(this, updateMonitor,
                    lockpatternutils, callback, true);
        } else {
            mKeyguardStatusViewManager = new KeyguardStatusViewManager(this, updateMonitor,
                    lockpatternutils, callback, true);
        }

    }

    @Override
    protected void layoutType(Context context) {
        LayoutInflater inflater = LayoutInflater.from(context);
        if (mKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) {
            inflater.inflate(R.layout.keyguard_screen_sim_pin_landscape, this, true);
        } else {
            inflater.inflate(R.layout.keyguard_screen_sim_pin_portrait, this, true);
            new MSimTouchInput();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onResume() {
        if (mUpdateMonitor.isinAirplaneMode()) {
            mUpdateMonitor.reportSimUnlocked(mSubscription);
            mCallback.goToUnlockScreen();
        }
        // start fresh
        if (TelephonyManager.getDefault().isMultiSimEnabled()) {
            String displayText = getContext().getString(pinStrIds[mSubscription]);
            mHeaderText.setText(displayText);
        } else {
           mHeaderText.setText(R.string.keyguard_password_enter_pin_code);
        }
        // make sure that the number of entered digits is consistent when we
        // erase the SIM unlock code, including orientation changes.
        mPinText.setText("");
        mEnteredDigits = 0;
        mKeyguardStatusViewManager.onResume();
    }

    /**
     * Since the IPC can block, we want to run the request in a separate thread
     * with a callback.
     */
    private abstract class CheckSimPin extends Thread {

        private final String mPin;

        protected CheckSimPin(String pin) {
            mPin = pin;
        }

        abstract void onSimLockChangedResponse(final int result);

        @Override
        public void run() {
            try {
                final int result = ITelephonyMSim.Stub.asInterface(ServiceManager
                        .checkService("phone_msim")).supplyPinReportResult(mPin, mSubscription);
                post(new Runnable() {
                    public void run() {
                        onSimLockChangedResponse(result);
                    }
                });
            } catch (RemoteException e) {
                post(new Runnable() {
                    public void run() {
                        onSimLockChangedResponse(Phone.PIN_GENERAL_FAILURE);
                    }
                });
            }
        }
    }

    @Override
    protected void checkPin() {

        // make sure that the pin is at least 4 digits long.
        if (mEnteredDigits < 4) {
            // otherwise, display a message to the user, and don't submit.
            mHeaderText.setText(R.string.invalidPin);
            mPinText.setText("");
            mEnteredDigits = 0;
            mCallback.pokeWakelock();
            return;
        }
        getSimUnlockProgressDialog().show();

        new CheckSimPin(mPinText.getText().toString()) {
            void onSimLockChangedResponse(final int result) {
                if (mSimUnlockProgressDialog != null) {
                    mSimUnlockProgressDialog.hide();
                }
                if (result == Phone.PIN_RESULT_SUCCESS) {
                    //Display message to user that the PIN1 entered is accepted.
                    LayoutInflater inflater = LayoutInflater.from(mContext);
                    View layout = inflater.inflate(R.layout.transient_notification,
                    (ViewGroup) findViewById(R.id.toast_layout_root));

                    TextView text = (TextView) layout.findViewById(R.id.message);
                    text.setText(R.string.keyguard_pin_accepted);

                    Toast toast = new Toast(mContext);
                    toast.setDuration(Toast.LENGTH_LONG);
                    toast.setGravity(Gravity.CENTER_VERTICAL, 0, 0);
                    toast.setView(layout);
                    toast.show();

                    // before closing the keyguard, report back that
                    // the sim is unlocked so it knows right away
                    mUpdateMonitor.reportSimUnlocked
                        (mSubscription);
                    mCallback.goToUnlockScreen();
                } else {
                    if (result == Phone.PIN_PASSWORD_INCORRECT) {
                        try {
                            //Displays No. of attempts remaining to unlock
                            //PIN1 in case of wrong entry.
                            int attemptsRemaining = ITelephonyMSim.Stub.asInterface(
                                                    ServiceManager.checkService("phone_msim"))
                                                    .getIccPin1RetryCount(mSubscription);
                            if (attemptsRemaining >= 0) {
                                String displayMessage = getContext().getString
                                        (R.string.keyguard_password_wrong_pin_code)
                                        + getContext().getString(R.string.pinpuk_attempts)
                                        + attemptsRemaining;
                                mHeaderText.setText(displayMessage);
                            } else {
                                mHeaderText.setText(R.string.keyguard_password_wrong_pin_code);
                            }
                        } catch (RemoteException ex) {
                            mHeaderText.setText(R.string.keyguard_password_pin_failed);
                        }
                    } else {
                        mHeaderText.setText(R.string.keyguard_password_pin_failed);
                    }
                    mPinText.setText("");
                    mEnteredDigits = 0;
                }
                mCallback.pokeWakelock();
            }
        }.start();

    }

    /**
     * Helper class to handle input from touch dialer.  Only relevant when
     * the keyboard is shut.
     */
    private class MSimTouchInput extends TouchInput implements View.OnClickListener {

        private MSimTouchInput() {
            mZero = (TextView) findViewById(R.id.zero);
            mOne = (TextView) findViewById(R.id.one);
            mTwo = (TextView) findViewById(R.id.two);
            mThree = (TextView) findViewById(R.id.three);
            mFour = (TextView) findViewById(R.id.four);
            mFive = (TextView) findViewById(R.id.five);
            mSix = (TextView) findViewById(R.id.six);
            mSeven = (TextView) findViewById(R.id.seven);
            mEight = (TextView) findViewById(R.id.eight);
            mNine = (TextView) findViewById(R.id.nine);
            mCancelButton = (TextView) findViewById(R.id.cancel);

            mZero.setText("0");
            mOne.setText("1");
            mTwo.setText("2");
            mThree.setText("3");
            mFour.setText("4");
            mFive.setText("5");
            mSix.setText("6");
            mSeven.setText("7");
            mEight.setText("8");
            mNine.setText("9");

            mZero.setOnClickListener(this);
            mOne.setOnClickListener(this);
            mTwo.setOnClickListener(this);
            mThree.setOnClickListener(this);
            mFour.setOnClickListener(this);
            mFive.setOnClickListener(this);
            mSix.setOnClickListener(this);
            mSeven.setOnClickListener(this);
            mEight.setOnClickListener(this);
            mNine.setOnClickListener(this);
            mCancelButton.setOnClickListener(this);

        }

        @Override
        public void onClick(View v) {
            if (v == mCancelButton) {
                //here we don't want to skip pin lock or puk lock screen, so
                //don't call the update unlock cancel
                //mCallback.updatePinUnlockCancel(mSubscription);
                mCallback.goToLockScreen();
                return;
            }

            final int digit = checkDigit(v);
            if (digit >= 0) {
                mCallback.pokeWakelock(DIGIT_PRESS_WAKE_MILLIS);
                reportDigit(digit);
            }
        }

    }
}
