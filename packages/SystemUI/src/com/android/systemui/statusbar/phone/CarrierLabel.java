/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2011-2012 Code Aurora Forum. All rights reserved.
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

package com.android.systemui.statusbar.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.MSimTelephonyManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.LocaleNamesParser;
import android.util.Slog;
import android.widget.TextView;

import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.IccCard.State;
import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.R;
/**
 * This widget display an analogic clock with two hands for hours and minutes.
 */
public class CarrierLabel extends TextView {
    private static final int DEFAULT_SUB = 0;
    private static final boolean DEBUG = false;
    private boolean mAttached;
    private int mSubscription;
    private Context mContext;
    private boolean mShowSpn;
    private String mSpn;
    private boolean mShowPlmn;
    private String mPlmn;
    private boolean mAirplaneMode;
    private CharSequence mDisplayCarrierStr = null;
    private final LocaleNamesParser localeNamesParser;

    private StatusMode mSimStatus = StatusMode.Normal;
    private State mSimState;
    private CharSequence mCarrierTextSub;

    public CarrierLabel(Context context) {
        this(context, null);
    }

    public CarrierLabel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CarrierLabel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.systemui.R.styleable.CarrierLabel, 0,
                R.style.Widget_TextView);
        mSubscription = a.getInt(
                com.android.systemui.R.styleable.CarrierLabel_subscription,
                DEFAULT_SUB);
        a.recycle();

        mAirplaneMode = Settings.System.getInt(context.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) == 1;
        localeNamesParser = new LocaleNamesParser(mContext, "CarrierLabel",
                com.android.internal.R.array.origin_carrier_names,
                com.android.internal.R.array.locale_carrier_names);

        mSimStatus = StatusMode.Normal;
        mCarrierTextSub = mContext
                .getString(com.android.internal.R.string.lockscreen_carrier_default);
        mSimState = IccCard.State.READY;
        updateDisplay(mSimState);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(Telephony.Intents.SPN_STRINGS_UPDATED_ACTION);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            getContext().registerReceiver(mIntentReceiver, filter, null,
                    getHandler());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            getContext().unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int subscription = DEFAULT_SUB;
            if (Telephony.Intents.SPN_STRINGS_UPDATED_ACTION.equals(action)) {
                subscription = intent.getIntExtra(
                        MSimConstants.SUBSCRIPTION_KEY, subscription);
                if (subscription == mSubscription) {
                    mShowSpn = intent.getBooleanExtra(
                            Telephony.Intents.EXTRA_SHOW_SPN, false);
                    mSpn = intent.getStringExtra(Telephony.Intents.EXTRA_SPN);
                    mShowPlmn = intent.getBooleanExtra(
                            Telephony.Intents.EXTRA_SHOW_PLMN, false);
                    mPlmn = intent.getStringExtra(Telephony.Intents.EXTRA_PLMN);
                    updateNetworkName(mShowSpn, mSpn, mShowPlmn, mPlmn);
                    updateDisplay(mSimState);
                }
            } else if (Intent.ACTION_LOCALE_CHANGED.equals(action)) {
                int subscription1 = intent.getIntExtra(
                        MSimConstants.SUBSCRIPTION_KEY, 0);
                updateNetworkName(mShowSpn, mSpn, mShowPlmn, mPlmn);
                updateDisplay(mSimState);
            } else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                mAirplaneMode = intent.getBooleanExtra("state", false);
                updateDisplay(mSimState);
            } else if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action)) {
                IccCard.State state;
                subscription = intent.getIntExtra(
                        MSimConstants.SUBSCRIPTION_KEY, subscription);
                if (subscription == mSubscription) {
                    String stateExtra = intent
                            .getStringExtra(IccCard.INTENT_KEY_ICC_STATE);
                    if (IccCard.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
                        final String absentReason = intent
                                .getStringExtra(IccCard.INTENT_KEY_LOCKED_REASON);

                        if (IccCard.INTENT_VALUE_ABSENT_ON_PERM_DISABLED
                                .equals(absentReason)) {
                            state = IccCard.State.PERM_DISABLED;
                        } else {
                            state = IccCard.State.ABSENT;
                        }
                    } else if (IccCard.INTENT_VALUE_ICC_READY
                            .equals(stateExtra)) {
                        state = IccCard.State.READY;
                    } else if (IccCard.INTENT_VALUE_ICC_LOCKED
                            .equals(stateExtra)) {
                        final String lockedReason = intent
                                .getStringExtra(IccCard.INTENT_KEY_LOCKED_REASON);
                        if (IccCard.INTENT_VALUE_LOCKED_ON_PIN
                                .equals(lockedReason)) {
                            state = IccCard.State.PIN_REQUIRED;
                        } else if (IccCard.INTENT_VALUE_LOCKED_ON_PUK
                                .equals(lockedReason)) {
                            state = IccCard.State.PUK_REQUIRED;
                        } else if (IccCard.INTENT_VALUE_LOCKED_PERSO
                                .equals(lockedReason)) {
                            state = IccCard.State.PERSO_LOCKED;
                        } else {
                            state = IccCard.State.UNKNOWN;
                        }
                    } else if (IccCard.INTENT_VALUE_ICC_CARD_IO_ERROR
                            .equals(stateExtra)) {
                        state = IccCard.State.CARD_IO_ERROR;
                    } else if (IccCard.INTENT_VALUE_ICC_DEACTIVATED
                            .equals(stateExtra)) {
                        state = IccCard.State.CARD_DEACTIVATED;
                    } else {
                        state = IccCard.State.UNKNOWN;
                    }
                    if (state != IccCard.State.UNKNOWN && state != mSimState) {
                        mSimState = state;
                        updateDisplay(state);
                    }
                }
            }
        }
    };

    private boolean isAbsend() {
        if (TelephonyManager.getDefault().isMultiSimEnabled()) {
            MSimTelephonyManager manager = (MSimTelephonyManager) mContext
                    .getSystemService(Context.MSIM_TELEPHONY_SERVICE);
            return TelephonyManager.SIM_STATE_ABSENT == manager
                    .getSimState(mSubscription);
        } else {
            TelephonyManager manager = (TelephonyManager) mContext
                    .getSystemService(Context.TELEPHONY_SERVICE);
            return TelephonyManager.SIM_STATE_ABSENT == manager.getSimState();
        }
    }

    private String getMultiSimName(int subscription) {
        return Settings.System.getString(mContext.getContentResolver(),
                Settings.System.MULTI_SIM_NAME[subscription]);
    }

    void updateNetworkName(boolean showSpn, String spn, boolean showPlmn,
            String plmn) {
        if (DEBUG) {
            Slog.d("CarrierLabel", "updateNetworkName showSpn=" + showSpn
                    + " spn=" + spn + " showPlmn=" + showPlmn + " plmn=" + plmn);
        }

        boolean something = false;
        StringBuilder str = new StringBuilder();
        // because we need to support OMH SPN display
        // in OMH ,the showPlmn is false and plmn is empty
        // showPlmn is not set, we need to support UNICOM card,whose showPlmn
        // =fasle while plmn is not empty
        if (/* showPlmn && */!TextUtils.isEmpty(plmn)) {
            localeNamesParser.reload();
            str.append(localeNamesParser.getLocaleName(plmn));
            something = true;
        }
        if (showSpn && !TextUtils.isEmpty(spn)) {
            if (something) {
                str.append('\n');
            }
            str.append(spn);
        }
        mDisplayCarrierStr = str.toString();
    }

    private void updateDisplay() {
        if (mAirplaneMode) {
            setDisplayText(mContext
                    .getString(com.android.internal.R.string.airplane_mode_on_message));
        } else if (!TextUtils.isEmpty(mDisplayCarrierStr)) {
            setDisplayText((String) mDisplayCarrierStr);
        } else if (isAbsend()) {
            setDisplayText(mContext
                    .getString(com.android.internal.R.string.lockscreen_missing_sim_message_short));
        } else {
            setDisplayText(mContext
                    .getString(com.android.internal.R.string.lockscreen_carrier_default));
        }
    }

    private void updateDisplay(IccCard.State simState) {
        if (mAirplaneMode) {
            mCarrierTextSub = mContext
                    .getString(com.android.internal.R.string.airplane_mode_on_message);
        } else {
            mSimStatus = getStatusForIccState(simState);
            switch (mSimStatus) {
            case Normal:
                mCarrierTextSub = makeCarrierString(
                        mContext.getString(com.android.internal.R.string.lockscreen_carrier_default),
                        mDisplayCarrierStr);
                break;

            case PersoLocked:
                mCarrierTextSub = mContext
                        .getString(com.android.internal.R.string.lockscreen_perso_locked_message);
                break;

            case SimMissing:
                mCarrierTextSub = mContext
                        .getString(com.android.internal.R.string.lockscreen_missing_sim_message_short);
                break;

            case SimPermDisabled:
                mCarrierTextSub = mContext
                        .getString(com.android.internal.R.string.lockscreen_missing_sim_message_short);
                break;

            case SimMissingLocked:
                mCarrierTextSub = mContext
                        .getString(com.android.internal.R.string.lockscreen_missing_sim_message_short);
                break;

            case SimLocked:
                mCarrierTextSub = mContext
                        .getString(com.android.internal.R.string.lockscreen_sim_locked_message);
                break;

            case SimPukLocked:
                mCarrierTextSub = mContext
                        .getString(com.android.internal.R.string.lockscreen_sim_puk_locked_message);
                break;

            case SimIOError:
                mCarrierTextSub = makeCarrierString(
                        mDisplayCarrierStr,
                        mContext.getString(com.android.internal.R.string.lockscreen_sim_error_message_short));
                break;
            case SimDeactivated:
                mCarrierTextSub = mContext
                        .getString(com.android.internal.R.string.lockscreen_sim_deactivate);
                break;
            }
        }
        if (DEBUG) {
            Slog.d("CarrierLabel",
                    "updateCarrierStateWithSimStatus: SimStatus = "
                            + mSimStatus + ", SimState = " + simState);
        }
        setDisplayText(mCarrierTextSub.toString());
    }

    private void setDisplayText(String text) {
        setText(getMultiSimName(mSubscription) + ":" + text);
    }

    private CharSequence makeCarrierString(CharSequence defaultValue,
            CharSequence spn) {
        final boolean defaultValid = !TextUtils.isEmpty(defaultValue);
        final boolean spnValid = !TextUtils.isEmpty(spn);
        if (spnValid) {
            return spn;
        }
        return defaultValue;
    }

    enum StatusMode {
        /**
         * Normal case (sim card present, it's not locked)
         */
        Normal,

        /**
         * The sim card is 'network locked'.
         */
        PersoLocked,

        /**
         * The sim card is missing.
         */
        SimMissing,

        /**
         * The sim card is missing, and this is the device isn't provisioned, so
         * we don't let them get past the screen.
         */
        SimMissingLocked,

        /**
         * The sim card is PUK locked, meaning they've entered the wrong sim
         * unlock code too many times.
         */
        SimPukLocked,

        /**
         * The sim card is locked.
         */
        SimLocked,

        /**
         * The sim card is permanently disabled due to puk unlock failure
         */
        SimPermDisabled,

        /**
         * The sim card is faulty
         */
        SimIOError,

        /**
         * The sim card is deactivated
         */
        SimDeactivated;
    }

    StatusMode getStatusForIccState(IccCard.State simState) {
        if (simState == null) {
            return StatusMode.Normal;
        }
        boolean deviceProvision = Settings.Secure.getInt(
                mContext.getContentResolver(),
                Settings.Secure.DEVICE_PROVISIONED, 0) != 0;
        final boolean missingAndNotProvisioned = (!deviceProvision && (simState == IccCard.State.ABSENT || simState == IccCard.State.PERM_DISABLED));
        simState = missingAndNotProvisioned ? State.PERSO_LOCKED : simState;
        switch (simState) {
        case ABSENT:
            return StatusMode.SimMissing;
        case PERSO_LOCKED:
            return StatusMode.PersoLocked;
        case NOT_READY:
            return StatusMode.SimMissing;
        case PIN_REQUIRED:
            return StatusMode.SimLocked;
        case PUK_REQUIRED:
            return StatusMode.SimPukLocked;
        case READY:
            return StatusMode.Normal;
        case PERM_DISABLED:
            return StatusMode.SimPermDisabled;
        case UNKNOWN:
            return StatusMode.SimMissing;
        case CARD_IO_ERROR:
            return StatusMode.SimIOError;
        case CARD_DEACTIVATED:
            return StatusMode.SimDeactivated;
        }
        return StatusMode.SimMissing;
    }
}