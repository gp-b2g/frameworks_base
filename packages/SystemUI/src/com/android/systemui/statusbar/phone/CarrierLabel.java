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
import android.provider.Telephony;
import android.telephony.MSimTelephonyManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.View;
import android.widget.TextView;
import android.util.LocaleNamesParser;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

import com.android.internal.R;
import com.android.internal.telephony.MSimConstants;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;

/**
 * This widget display an analogic clock with two hands for hours and
 * minutes.
 */
public class CarrierLabel extends TextView {
    private static final int DEFAULT_SUB = 0;
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
                com.android.systemui.R.styleable.CarrierLabel, 0, R.style.Widget_TextView);
        mSubscription = a.getInt(com.android.systemui.R.styleable.CarrierLabel_subscription, DEFAULT_SUB);
        a.recycle();

        mAirplaneMode = Settings.System.getInt(context.getContentResolver(),
                        Settings.System.AIRPLANE_MODE_ON, 0) == 1;
        localeNamesParser = new LocaleNamesParser(mContext, "CarrierLabel",
                com.android.internal.R.array.origin_carrier_names,
                com.android.internal.R.array.locale_carrier_names);

        updateDisplay();
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
            getContext().registerReceiver(mIntentReceiver, filter, null, getHandler());
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
                subscription = intent.getIntExtra(MSimConstants.SUBSCRIPTION_KEY, subscription);
                if (subscription == mSubscription) {
                    mShowSpn = intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_SPN, false);
                    mSpn = intent.getStringExtra(Telephony.Intents.EXTRA_SPN);
                    mShowPlmn = intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_PLMN, false);
                    mPlmn = intent.getStringExtra(Telephony.Intents.EXTRA_PLMN);
                    updateNetworkName(mShowSpn, mSpn, mShowPlmn, mPlmn);
                }
            } else if(Intent.ACTION_LOCALE_CHANGED.equals(action)){
                int subscription1 = intent.getIntExtra(MSimConstants.SUBSCRIPTION_KEY, 0);
                updateNetworkName(mShowSpn, mSpn, mShowPlmn, mPlmn);
            } else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                mAirplaneMode = intent.getBooleanExtra("state", false);
            }

            updateDisplay();
        }
    };

    private boolean isAbsend() {
        if (TelephonyManager.getDefault().isMultiSimEnabled()) {
            MSimTelephonyManager manager = (MSimTelephonyManager) mContext
                    .getSystemService(Context.MSIM_TELEPHONY_SERVICE);
            return TelephonyManager.SIM_STATE_ABSENT == manager.getSimState(mSubscription);
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

    void updateNetworkName(boolean showSpn, String spn, boolean showPlmn, String plmn) {
        if (false) {
            Slog.d("CarrierLabel", "updateNetworkName showSpn=" + showSpn + " spn=" + spn
                    + " showPlmn=" + showPlmn + " plmn=" + plmn);
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
            setDisplayText(mContext.getString(com.android.internal.R.string.airplane_mode_on_message));
        } else if (!TextUtils.isEmpty(mDisplayCarrierStr)) {
            setDisplayText((String) mDisplayCarrierStr);
        } else if (isAbsend()) {
            setDisplayText(mContext.getString(com.android.internal.R.string.lockscreen_missing_sim_message_short));
        } else {
            setDisplayText(mContext.getString(com.android.internal.R.string.lockscreen_carrier_default));
        }
    }

    private void setDisplayText(String text){
        setText(getMultiSimName(mSubscription) + ":" + text);
    }
}