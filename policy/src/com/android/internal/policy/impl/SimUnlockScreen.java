/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2010-2012, Code Aurora Forum. All rights reserved
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

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.Phone;
import com.android.internal.widget.LockPatternUtils;

import android.telephony.MSimTelephonyManager;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.android.internal.R;

/**
 * Displays a dialer like interface to unlock the SIM PIN.
 */
public class SimUnlockScreen extends LinearLayout implements KeyguardScreen, View.OnClickListener {

    protected static final int DIGIT_PRESS_WAKE_MILLIS = 5000;

    protected final KeyguardUpdateMonitor mUpdateMonitor;
    protected final KeyguardScreenCallback mCallback;

    protected TextView mHeaderText;
    protected TextView mPinText;

    protected TextView mOkButton;

    protected View mBackSpaceButton;

    protected Context mContext;

    protected final int[] mEnteredPin = {0, 0, 0, 0, 0, 0, 0, 0};
    protected int mEnteredDigits = 0;

    protected ProgressDialog mSimUnlockProgressDialog = null;

    protected LockPatternUtils mLockPatternUtils;

    protected int mCreationOrientation;

    protected int mKeyboardHidden;

    protected KeyguardStatusViewManager mKeyguardStatusViewManager;

    protected static final char[] DIGITS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'};

    public SimUnlockScreen(Context context, Configuration configuration,
            KeyguardUpdateMonitor updateMonitor, KeyguardScreenCallback callback,
            LockPatternUtils lockpatternutils) {
        super(context);
        mUpdateMonitor = updateMonitor;
        mCallback = callback;
        mContext = context;

        mCreationOrientation = configuration.orientation;
        mKeyboardHidden = configuration.hardKeyboardHidden;
        mLockPatternUtils = lockpatternutils;
        layoutType(context);
        mHeaderText = (TextView) findViewById(R.id.headerText);
        mPinText = (TextView) findViewById(R.id.pinDisplay);
        mBackSpaceButton = findViewById(R.id.backspace);
        mBackSpaceButton.setOnClickListener(this);

        mOkButton = (TextView) findViewById(R.id.ok);

        mHeaderText.setText(R.string.keyguard_password_enter_pin_code);
        mPinText.setFocusable(false);

        mOkButton.setOnClickListener(this);

        if (TelephonyManager.getDefault().isMultiSimEnabled()) {
            mKeyguardStatusViewManager = new MSimKeyguardStatusViewManager(this, updateMonitor,
                    lockpatternutils, callback, true);
        } else {
            mKeyguardStatusViewManager = new KeyguardStatusViewManager(this, updateMonitor,
                    lockpatternutils, callback, true);
        }

        setFocusableInTouchMode(true);
    }

    protected void layoutType(Context context) {
        LayoutInflater inflater = LayoutInflater.from(context);
        if (mKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) {
            inflater.inflate(R.layout.keyguard_screen_sim_pin_landscape, this, true);
        } else {
            inflater.inflate(R.layout.keyguard_screen_sim_pin_portrait, this, true);
            new TouchInput();
        }
    }

    /** {@inheritDoc} */
    public boolean needsInput() {
        return true;
    }

    /** {@inheritDoc} */
    public void onPause() {
        mKeyguardStatusViewManager.onPause();
    }

    /** {@inheritDoc} */
    public void onResume() {
        // start fresh
        mHeaderText.setText(R.string.keyguard_password_enter_pin_code);

        // make sure that the number of entered digits is consistent when we
        // erase the SIM unlock code, including orientation changes.
        mPinText.setText("");
        mEnteredDigits = 0;

        mKeyguardStatusViewManager.onResume();
    }

    /** {@inheritDoc} */
    public void cleanUp() {
        // dismiss the dialog.
        if (mSimUnlockProgressDialog != null) {
            mSimUnlockProgressDialog.dismiss();
            mSimUnlockProgressDialog = null;
        }
        mUpdateMonitor.removeCallback(this);
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
                final int result = ITelephony.Stub.asInterface(ServiceManager
                        .checkService("phone")).supplyPinReportResult(mPin);
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

    public void onClick(View v) {
        if (v == mBackSpaceButton) {
            final Editable digits = mPinText.getEditableText();
            final int len = digits.length();
            if (len > 0) {
                digits.delete(len-1, len);
                mEnteredDigits--;
            }
            mCallback.pokeWakelock();
        } else if (v == mOkButton) {
            checkPin();
        }
    }

    protected Dialog getSimUnlockProgressDialog() {
        if (mSimUnlockProgressDialog == null) {
            mSimUnlockProgressDialog = new ProgressDialog(mContext);
            mSimUnlockProgressDialog.setMessage(
                    mContext.getString(R.string.lockscreen_sim_unlock_progress_dialog_message));
            mSimUnlockProgressDialog.setIndeterminate(true);
            mSimUnlockProgressDialog.setCancelable(false);
            mSimUnlockProgressDialog.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        }
        return mSimUnlockProgressDialog;
    }

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
                mPinText.post(new Runnable() {
                    public void run() {
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
                            mUpdateMonitor.reportSimUnlocked();
                            mCallback.goToUnlockScreen();
                        } else {
                            if (result == Phone.PIN_PASSWORD_INCORRECT) {
                                try {
                                    //Displays No. of attempts remaining to unlock PIN1 in case of wrong entry.
                                    int attemptsRemaining = ITelephony.Stub.asInterface(ServiceManager
                                            .checkService("phone")).getIccPin1RetryCount();
                                    if (attemptsRemaining >= 0) {
                                        String displayMessage = getContext().getString(R.string.keyguard_password_wrong_pin_code)
                                                + getContext().getString(R.string.pinpuk_attempts) + attemptsRemaining;
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
                });
            }
        }.start();
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mCallback.goToLockScreen();
            return true;
        }

        final char match = event.getMatch(DIGITS);
        if (match != 0) {
            reportDigit(match - '0');
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            if (mEnteredDigits > 0) {
                mPinText.onKeyDown(keyCode, event);
                mEnteredDigits--;
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            checkPin();
            return true;
        }

        return false;
    }

    protected void reportDigit(int digit) {
        if (mEnteredDigits == 0) {
            mPinText.setText("");
        }
        if (mEnteredDigits == 8) {
            return;
        }
        mPinText.append(Integer.toString(digit));
        mEnteredPin[mEnteredDigits++] = digit;
    }

    void updateConfiguration() {
        Configuration newConfig = getResources().getConfiguration();
        if (newConfig.orientation != mCreationOrientation) {
            mCallback.recreateMe(newConfig);
        } else if (newConfig.hardKeyboardHidden != mKeyboardHidden) {
            mKeyboardHidden = newConfig.hardKeyboardHidden;
            final boolean isKeyboardOpen = mKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
            if (mUpdateMonitor.isKeyguardBypassEnabled() && isKeyboardOpen) {
                mCallback.goToUnlockScreen();
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateConfiguration();
    }

    /** {@inheritDoc} */
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateConfiguration();
    }

    /**
     * Helper class to handle input from touch dialer.  Only relevant when
     * the keyboard is shut.
     */
    protected class TouchInput implements View.OnClickListener {
        protected TextView mZero;
        protected TextView mOne;
        protected TextView mTwo;
        protected TextView mThree;
        protected TextView mFour;
        protected TextView mFive;
        protected TextView mSix;
        protected TextView mSeven;
        protected TextView mEight;
        protected TextView mNine;
        protected TextView mCancelButton;

        protected TouchInput() {
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


        public void onClick(View v) {
            if (v == mCancelButton) {
                mPinText.setText(""); // clear the PIN entry field if the user cancels
                mCallback.goToLockScreen();
                return;
            }

            final int digit = checkDigit(v);
            if (digit >= 0) {
                mCallback.pokeWakelock(DIGIT_PRESS_WAKE_MILLIS);
                reportDigit(digit);
            }
        }

        protected int checkDigit(View v) {
            int digit = -1;
            if (v == mZero) {
                digit = 0;
            } else if (v == mOne) {
                digit = 1;
            } else if (v == mTwo) {
                digit = 2;
            } else if (v == mThree) {
                digit = 3;
            } else if (v == mFour) {
                digit = 4;
            } else if (v == mFive) {
                digit = 5;
            } else if (v == mSix) {
                digit = 6;
            } else if (v == mSeven) {
                digit = 7;
            } else if (v == mEight) {
                digit = 8;
            } else if (v == mNine) {
                digit = 9;
            }
            return digit;
        }
    }
}
