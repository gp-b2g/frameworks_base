/*
 * Copyright (C) 2010 The Android Open Source Project
 * Copyright (c) 2011 Code Aurora Forum. All rights reserved.
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

package com.android.systemui.statusbar.policy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.MSimTelephonyManager;
import android.util.Slog;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.cdma.EriInfo;
import com.android.server.am.BatteryStatsService;
import com.android.internal.util.AsyncChannel;

import com.android.systemui.R;

public class MSimNetworkController extends BroadcastReceiver {
    // debug
    static final String TAG = "StatusBar.MSimNetworkController";
    static final boolean DEBUG = true; //false;
    static final boolean CHATTY = false; // additional diagnostics, but not logspew

    // telephony
    boolean mHspaDataDistinguishable;
    final MSimTelephonyManager mPhone;
    boolean[] mDataConnected;
    IccCard.State[] mSimState;
    int mPhoneState = TelephonyManager.CALL_STATE_IDLE;
    int mDataNetType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
    int mDataState = TelephonyManager.DATA_DISCONNECTED;
    int[] mDataActivity;
    ServiceState[] mServiceState;
    SignalStrength[] mSignalStrength;
    String[] mSignalIcon = {"phone_signal", "phone_signal_second_sub"};
    String[] mSimIcon = {"no_sim_card1", "no_sim_card2"};
    private PhoneStateListener[] mPhoneStateListener;
    private final StatusBarManager mService;

    int[] mDataIconList = TelephonyIcons.DATA_G[0];
    String[] mNetworkName;
    String mNetworkNameDefault;
    String mNetworkNameSeparator;
    int[] mPhoneSignalIconId;
    int[] mLastPhoneSignalIconId;
    private int[] mSimIconId;
    int[] mDataDirectionIconId; // data + data direction on phones
    int[] mDataSignalIconId;
    int[] mDataTypeIconId;
    boolean mDataActive;
    int[] mMobileActivityIconId; // overlay arrows for data direction
    int mLastSignalLevel;

    String[] mContentDescriptionPhoneSignal;
    String mContentDescriptionWifi;
    String[] mContentDescriptionCombinedSignal;
    String[] mContentDescriptionDataType;

    // wifi
    final WifiManager mWifiManager;
    AsyncChannel mWifiChannel;
    boolean mWifiEnabled, mWifiConnected;
    int mWifiRssi, mWifiLevel;
    String mWifiSsid;
    int mWifiIconId = 0;
    int mWifiActivityIconId = 0; // overlay arrows for wifi direction
    int mWifiActivity = WifiManager.DATA_ACTIVITY_NONE;

    // bluetooth
    private boolean mBluetoothTethered = false;
    private int mBluetoothTetherIconId =
        com.android.internal.R.drawable.stat_sys_tether_bluetooth;

    // data connectivity (regardless of state, can we access the internet?)
    // state of inet connection - 0 not connected, 100 connected
    private int mInetCondition = 0;
    private static final int INET_CONDITION_THRESHOLD = 50;

    private boolean mAirplaneMode = false;

    // our ui
    Context mContext;
    ArrayList<ImageView> mPhoneSignalIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mDataDirectionIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mDataDirectionOverlayIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mWifiIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mCombinedSignalIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mDataTypeIconViews = new ArrayList<ImageView>();
    ArrayList<TextView> mLabelViews = new ArrayList<TextView>();
    ArrayList<MSimSignalCluster> mSimSignalClusters = new ArrayList<MSimSignalCluster>();
    int[] mLastDataDirectionIconId;
    int mLastDataDirectionOverlayIconId = -1;
    int mLastWifiIconId = -1;
    int[] mLastCombinedSignalIconId;
    int[] mLastDataTypeIconId;
    int[] combinedSignalIconId;
    int[] combinedActivityIconId;
    String mLastLabel = "";

    private boolean mHasMobileDataFeature;

    boolean mDataAndWifiStacked = false;

    // yuck -- stop doing this here and put it in the framework
    IBatteryStats mBatteryStats;

    public interface MSimSignalCluster {
        void setWifiIndicators(boolean visible, int strengthIcon, int activityIcon,
                String contentDescription);
        void setMobileDataIndicators(boolean visible, int strengthIcon, int activityIcon,
                int typeIcon, String contentDescription, String typeContentDescription, int subscription);
        void setIsAirplaneMode(boolean is);
    }

    /**
     * Construct this controller object and register for updates.
     */
    public MSimNetworkController(Context context) {
        mContext = context;

        mService = (StatusBarManager)context.getSystemService(Context.STATUS_BAR_SERVICE);
        ConnectivityManager cm = (ConnectivityManager)mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        mHasMobileDataFeature = cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        int numPhones = TelephonyManager.getDefault().getPhoneCount();
        mSignalStrength = new SignalStrength[numPhones];
        mServiceState = new ServiceState[numPhones];
        mSimState = new IccCard.State[numPhones];
        mSimIconId = new int[numPhones];
        mPhoneSignalIconId = new int[numPhones];
        mDataTypeIconId = new int[numPhones];
        mMobileActivityIconId = new int[numPhones];
        mContentDescriptionPhoneSignal = new String[numPhones];
        mLastPhoneSignalIconId = new int[numPhones];
        mPhoneStateListener = new PhoneStateListener[numPhones];
        mNetworkName = new String[numPhones];
        mLastDataTypeIconId = new int[numPhones];
        mDataConnected = new boolean[numPhones];
        mDataSignalIconId = new int[numPhones];
        mDataDirectionIconId = new int[numPhones];
        mLastDataDirectionIconId = new int[numPhones];
        mLastCombinedSignalIconId = new int[numPhones];
        combinedSignalIconId = new int[numPhones];
        combinedActivityIconId = new int[numPhones];
        mDataActivity = new int[numPhones];
        mContentDescriptionCombinedSignal = new String[numPhones];
        mContentDescriptionDataType = new String[numPhones];
        // set up the default wifi icon, used when no radios have ever appeared
        updateWifiIcons();
        mNetworkNameDefault = mContext.getString(
                com.android.internal.R.string.lockscreen_carrier_default);

        mPhone = (MSimTelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        for (int i=0; i < numPhones; i++) {
            mSignalStrength[i] = new SignalStrength();
            mServiceState[i] = new ServiceState();
            mSimState[i] = IccCard.State.READY;
            // phone_signal
            mPhoneSignalIconId[i] = R.drawable.stat_sys_signal_0;
            mLastPhoneSignalIconId[i] = -1;
            mLastDataTypeIconId[i] = -1;
            mDataConnected[i] = false;
            mLastDataDirectionIconId[i] = -1;
            mLastCombinedSignalIconId[i] = -1;
            combinedSignalIconId[i] = 0;
            combinedActivityIconId[i] = 0;
            mDataActivity[i] = TelephonyManager.DATA_ACTIVITY_NONE;
            mPhoneStateListener[i] = getPhoneStateListener(i);

            // telephony
            mPhone.listen(mPhoneStateListener[i],
                              PhoneStateListener.LISTEN_SERVICE_STATE
                            | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                            | PhoneStateListener.LISTEN_CALL_STATE
                            | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
                            | PhoneStateListener.LISTEN_DATA_ACTIVITY);
            mNetworkName[i] = mNetworkNameDefault;
        }

        mHspaDataDistinguishable = mContext.getResources().getBoolean(
                R.bool.config_hspa_data_distinguishable);
        mNetworkNameSeparator = mContext.getString(R.string.status_bar_network_name_separator);

        // wifi
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        Handler handler = new WifiHandler();
        mWifiChannel = new AsyncChannel();
        Messenger wifiMessenger = mWifiManager.getMessenger();
        if (wifiMessenger != null) {
            mWifiChannel.connect(mContext, handler, wifiMessenger);
        }

        // broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(Telephony.Intents.SPN_STRINGS_UPDATED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(ConnectivityManager.INET_CONDITION_ACTION);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        context.registerReceiver(this, filter);

        // AIRPLANE_MODE_CHANGED is sent at boot; we've probably already missed it
        updateAirplaneMode();

        // yuck
        mBatteryStats = BatteryStatsService.getService();
    }

    public void addPhoneSignalIconView(ImageView v) {
        mPhoneSignalIconViews.add(v);
    }

    public void addDataDirectionIconView(ImageView v) {
        mDataDirectionIconViews.add(v);
    }

    public void addDataDirectionOverlayIconView(ImageView v) {
        mDataDirectionOverlayIconViews.add(v);
    }

    public void addWifiIconView(ImageView v) {
        mWifiIconViews.add(v);
    }

    public void addCombinedSignalIconView(ImageView v) {
        mCombinedSignalIconViews.add(v);
    }

    public void addDataTypeIconView(ImageView v) {
        mDataTypeIconViews.add(v);
    }

    public void addLabelView(TextView v) {
        mLabelViews.add(v);
    }

    public void addSignalCluster(MSimSignalCluster cluster, int subscription) {
        mSimSignalClusters.add(cluster);
        cluster.setWifiIndicators(
                mWifiConnected, // only show wifi in the cluster if connected
                mWifiIconId,
                mWifiActivityIconId,
                mContentDescriptionWifi);
        cluster.setMobileDataIndicators(
                mHasMobileDataFeature,
                mPhoneSignalIconId[subscription],
                mMobileActivityIconId[subscription],
                mDataTypeIconId[subscription],
                mContentDescriptionPhoneSignal[subscription],
                mContentDescriptionDataType[subscription], subscription);

    }

    public void setStackedMode(boolean stacked) {
        mDataAndWifiStacked = true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action.equals(WifiManager.RSSI_CHANGED_ACTION)
                || action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)
                || action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
            updateWifiState(intent);
            refreshViewsForSubscription(MSimTelephonyManager.getDefault().getDefaultSubscription());
        } else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
            updateSimState(intent);
            updateDataIcon(MSimTelephonyManager.getDefault().getDefaultSubscription());
            refreshViewsForSubscription(MSimTelephonyManager.getDefault().getDefaultSubscription());
        } else if (action.equals(Telephony.Intents.SPN_STRINGS_UPDATED_ACTION)) {
            final int subscription = intent.getIntExtra(MSimConstants.SUBSCRIPTION_KEY, 0);
            Slog.d(TAG, "Received SPN update on sub :" + subscription);
            updateNetworkName(intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_SPN, false),
                        intent.getStringExtra(Telephony.Intents.EXTRA_SPN),
                        intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_PLMN, false),
                        intent.getStringExtra(Telephony.Intents.EXTRA_PLMN), subscription);
            refreshViewsForSubscription(subscription);
        } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION) ||
                 action.equals(ConnectivityManager.INET_CONDITION_ACTION)) {
            updateConnectivity(intent);
            refreshViewsForSubscription(MSimTelephonyManager.getDefault().getDefaultSubscription());
        } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
            refreshViewsForSubscription(MSimTelephonyManager.getDefault().getDefaultSubscription());
        } else if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
            updateAirplaneMode();
            refreshViewsForSubscription(MSimTelephonyManager.getDefault().getDefaultSubscription());
        }
    }


    // ===== Telephony ==============================================================

    private PhoneStateListener getPhoneStateListener(int subscription) {
    PhoneStateListener phoneStateListener = new PhoneStateListener(subscription) {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            if (DEBUG) {
                Slog.d(TAG, "onSignalStrengthsChanged received on subscription :"
                    + mSubscription + "signalStrength=" + signalStrength +
                    ((signalStrength == null) ? "" : (" level=" + signalStrength.getLevel())));
            }
            mSignalStrength[mSubscription] = signalStrength;
            updateTelephonySignalStrength(mSubscription);
            refreshViewsForSubscription(mSubscription);
        }

        @Override
        public void onServiceStateChanged(ServiceState state) {
            if (DEBUG) {
                Slog.d(TAG, "onServiceStateChanged received on subscription :"
                    + mSubscription + "state=" + state.getState());
            }
            mServiceState[mSubscription] = state;
            updateTelephonySignalStrength(mSubscription);
            updateDataNetType(mSubscription);
            updateDataIcon(mSubscription);
            refreshViewsForSubscription(mSubscription);
        }

        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if (DEBUG) {
                Slog.d(TAG, "onCallStateChanged received on subscription :"
                + mSubscription + "state=" + state);
            }
            // In cdma, if a voice call is made, RSSI should switch to 1x.
            if (isCdma(mSubscription)) {
                updateTelephonySignalStrength(mSubscription);
                refreshViewsForSubscription(mSubscription);
            }
        }

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            if (DEBUG) {
                Slog.d(TAG, "onDataConnectionStateChanged received on subscription :"
                + mSubscription + "state=" + state + " type=" + networkType);
            }
            mDataState = state;
            mDataNetType = networkType;
            updateDataNetType(mSubscription);
            updateDataIcon(mSubscription);
            refreshViewsForSubscription(mSubscription);
        }

        @Override
        public void onDataActivity(int direction) {
            if (DEBUG) {
                Slog.d(TAG, "onDataActivity received on subscription :"
                    + mSubscription + "direction=" + direction);
            }
            mDataActivity[mSubscription] = direction;
            updateDataIcon(mSubscription);
            refreshViewsForSubscription(mSubscription);
        }
    };
    return phoneStateListener;
    }

    private final void updateSimState(Intent intent) {
        IccCard.State simState;
        String stateExtra = intent.getStringExtra(IccCard.INTENT_KEY_ICC_STATE);
        // Obtain the subscription info from intent.
        int sub = intent.getIntExtra(MSimConstants.SUBSCRIPTION_KEY, 0);
        Slog.d(TAG, "updateSimState for subscription :" + sub);
        if (IccCard.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
            simState = IccCard.State.ABSENT;
        }
        else if (IccCard.INTENT_VALUE_ICC_CARD_IO_ERROR.equals(stateExtra)) {
            simState = IccCard.State.CARD_IO_ERROR;
        }
        else if (IccCard.INTENT_VALUE_ICC_READY.equals(stateExtra)) {
            simState = IccCard.State.READY;
        }
        else if (IccCard.INTENT_VALUE_ICC_LOCKED.equals(stateExtra)) {
            final String lockedReason = intent.getStringExtra(IccCard.INTENT_KEY_LOCKED_REASON);
            if (IccCard.INTENT_VALUE_LOCKED_ON_PIN.equals(lockedReason)) {
                simState = IccCard.State.PIN_REQUIRED;
            }
            else if (IccCard.INTENT_VALUE_LOCKED_ON_PUK.equals(lockedReason)) {
                simState = IccCard.State.PUK_REQUIRED;
            }
            else {
                simState = IccCard.State.PERSO_LOCKED;
            }
        } else {
            simState = IccCard.State.UNKNOWN;
        }
        mSimState[sub] = simState;
        Slog.d(TAG, "updateSimState simState =" + mSimState[sub]);
        updateDataIcon(sub);
    }

    private boolean isCdma(int subscription) {
        return (mSignalStrength[subscription] != null) && !mSignalStrength[subscription].isGsm();
    }

    private boolean hasService(int subscription) {
        ServiceState ss = mServiceState[subscription];
        if (ss != null) {
            switch (ss.getState()) {
                case ServiceState.STATE_OUT_OF_SERVICE:
                case ServiceState.STATE_POWER_OFF:
                    return false;
                default:
                    return true;
            }
        } else {
            return false;
        }
    }

    private void updateAirplaneMode() {
        mAirplaneMode = (Settings.System.getInt(mContext.getContentResolver(),
            Settings.System.AIRPLANE_MODE_ON, 0) == 1);
    }

    private final void updateTelephonySignalStrength(int subscription) {
        Slog.d(TAG, "updateTelephonySignalStrength: subscription =" + subscription);
        if (!hasService(subscription)) {
            if (CHATTY) Slog.d(TAG, "updateTelephonySignalStrength: !hasService()");
            mPhoneSignalIconId[subscription] = R.drawable.stat_sys_signal_0;
            mDataSignalIconId[subscription] = R.drawable.stat_sys_signal_0;
        } else {
            if (mSignalStrength[subscription] == null) {
                if (CHATTY) Slog.d(TAG, "updateTelephonySignalStrength: mSignalStrength == null");
                mPhoneSignalIconId[subscription] = R.drawable.stat_sys_signal_0;
                mDataSignalIconId[subscription] = R.drawable.stat_sys_signal_0;
                mContentDescriptionPhoneSignal[subscription] = mContext.getString(
                        AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0]);
            } else {
                int iconLevel;
                int[] iconList;
                mLastSignalLevel = iconLevel = mSignalStrength[subscription].getLevel();
                if (isCdma(subscription)) {
                    if (isCdmaEri(subscription)) {
                        iconList = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_ROAMING[mInetCondition];
                    } else {
                        iconList = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[mInetCondition];
                    }
                } else {
                    // Though mPhone is a Manager, this call is not an IPC
                    if (mPhone.isNetworkRoaming(subscription)) {
                        iconList = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_ROAMING[mInetCondition];
                    } else {
                        iconList = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[mInetCondition];
                    }
                }
                mPhoneSignalIconId[subscription] = iconList[iconLevel];
                mContentDescriptionPhoneSignal[subscription] = mContext.getString(
                        AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[iconLevel]);

                mDataSignalIconId[subscription] = TelephonyIcons.DATA_SIGNAL_STRENGTH[mInetCondition][iconLevel];
            }
        }
    }

    private final void updateDataNetType(int subscription) {
        Slog.d(TAG,"updateDataNetType subscription =" + subscription + "mDataNetType =" + mDataNetType);
        switch (mDataNetType) {
            case TelephonyManager.NETWORK_TYPE_UNKNOWN:
                mDataIconList = TelephonyIcons.DATA_G[mInetCondition];
                mDataTypeIconId[subscription] = 0;
                mContentDescriptionDataType[subscription] = mContext.getString(
                        R.string.accessibility_data_connection_gprs);
                break;
            case TelephonyManager.NETWORK_TYPE_EDGE:
                mDataIconList = TelephonyIcons.DATA_E[mInetCondition];
                mDataTypeIconId[subscription] = R.drawable.stat_sys_data_connected_e;
                mContentDescriptionDataType[subscription] = mContext.getString(
                        R.string.accessibility_data_connection_edge);
                break;
            case TelephonyManager.NETWORK_TYPE_UMTS:
                mDataIconList = TelephonyIcons.DATA_3G[mInetCondition];
                mDataTypeIconId[subscription] = R.drawable.stat_sys_data_connected_3g;
                mContentDescriptionDataType[subscription] = mContext.getString(
                        R.string.accessibility_data_connection_3g);
                break;
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                if (mHspaDataDistinguishable) {
                    mDataIconList = TelephonyIcons.DATA_H[mInetCondition];
                    mDataTypeIconId[subscription] = R.drawable.stat_sys_data_connected_h;
                    mContentDescriptionDataType[subscription] = mContext.getString(
                            R.string.accessibility_data_connection_3_5g);
                } else {
                    mDataIconList = TelephonyIcons.DATA_3G[mInetCondition];
                    mDataTypeIconId[subscription] = R.drawable.stat_sys_data_connected_3g;
                    mContentDescriptionDataType[subscription] = mContext.getString(
                            R.string.accessibility_data_connection_3g);
                }
                break;
            case TelephonyManager.NETWORK_TYPE_CDMA:
                // display 1xRTT for IS95A/B
                mDataIconList = TelephonyIcons.DATA_1X[mInetCondition];
                mDataTypeIconId[subscription] = R.drawable.stat_sys_data_connected_1x;
                mContentDescriptionDataType[subscription] = mContext.getString(
                        R.string.accessibility_data_connection_cdma);
                break;
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                mDataIconList = TelephonyIcons.DATA_1X[mInetCondition];
                mDataTypeIconId[subscription] = R.drawable.stat_sys_data_connected_1x;
                mContentDescriptionDataType[subscription] = mContext.getString(
                        R.string.accessibility_data_connection_cdma);
                break;
            case TelephonyManager.NETWORK_TYPE_EVDO_0: //fall through
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
                mDataIconList = TelephonyIcons.DATA_3G[mInetCondition];
                mDataTypeIconId[subscription] = R.drawable.stat_sys_data_connected_3g;
                mContentDescriptionDataType[subscription] = mContext.getString(
                        R.string.accessibility_data_connection_3g);
                break;
            case TelephonyManager.NETWORK_TYPE_LTE:
                mDataIconList = TelephonyIcons.DATA_4G[mInetCondition];
                mDataTypeIconId[subscription] = R.drawable.stat_sys_data_connected_4g;
                mContentDescriptionDataType[subscription] = mContext.getString(
                        R.string.accessibility_data_connection_4g);
                break;
            case TelephonyManager.NETWORK_TYPE_GPRS:
                mDataIconList = TelephonyIcons.DATA_G[mInetCondition];
                mDataTypeIconId[subscription] = R.drawable.stat_sys_data_connected_g;
                mContentDescriptionDataType[subscription] = mContext.getString(
                        R.string.accessibility_data_connection_gprs);
                break;
            default:
                if (DEBUG) {
                    Slog.e(TAG, "updateDataNetType unknown radio:" + mDataNetType );
                }
                mDataNetType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
                mDataTypeIconId[subscription] = 0;
                break;
        }
        if ((isCdma(subscription) && isCdmaEri(subscription)) ||
             mPhone.isNetworkRoaming(subscription)) {
            mDataTypeIconId[subscription] = R.drawable.stat_sys_data_connected_roam;
        }
    }

    boolean isCdmaEri(int subscription) {
        if (mServiceState[subscription] != null) {
            final int iconIndex = mServiceState[subscription].getCdmaEriIconIndex();
            if (iconIndex != EriInfo.ROAMING_INDICATOR_OFF) {
                final int iconMode = mServiceState[subscription].getCdmaEriIconMode();
                if (iconMode == EriInfo.ROAMING_ICON_MODE_NORMAL
                        || iconMode == EriInfo.ROAMING_ICON_MODE_FLASH) {
                    return true;
                }
            }
        }
        return false;
    }

    private final void updateDataIcon(int subscription) {
        Slog.d(TAG,"updateDataIcon subscription =" + subscription);
        int iconId = 0;
        boolean visible = true;
        int dataSub = Settings.System.getInt(mContext.getContentResolver(),
            Settings.System.MULTI_SIM_DATA_CALL_SUBSCRIPTION, 0);
        Slog.d(TAG,"updateDataIcon dataSub =" + dataSub);
        // Update icon only if DDS in properly set and "subscription" matches DDS.
        if (subscription != dataSub) {
            Slog.d(TAG,"updateDataIcon return");
            return;
        }

        Slog.d(TAG,"updateDataIcon  when SimState =" + mSimState[subscription]);
        if (mDataNetType == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
            // If data network type is unknown do not display data icon
            visible = false;
        } else if (!isCdma(subscription)) {
             Slog.d(TAG,"updateDataIcon  when gsm mSimState =" + mSimState[subscription]);
            // GSM case, we have to check also the sim state
            if (mSimState[subscription] == IccCard.State.READY ||
                mSimState[subscription] == IccCard.State.UNKNOWN) {
                if (mDataState == TelephonyManager.DATA_CONNECTED) {
                    switch (mDataActivity[subscription]) {
                        case TelephonyManager.DATA_ACTIVITY_IN:
                            iconId = mDataIconList[1];
                            break;
                        case TelephonyManager.DATA_ACTIVITY_OUT:
                            iconId = mDataIconList[2];
                            break;
                        case TelephonyManager.DATA_ACTIVITY_INOUT:
                            iconId = mDataIconList[3];
                            break;
                        default:
                            iconId = mDataIconList[0];
                            break;
                    }
                    mDataDirectionIconId[subscription] = iconId;
                } else {
                    iconId = 0;
                    visible = false;
                }
            } else {
                Slog.d(TAG,"updateDataIcon when no sim");
                iconId = R.drawable.stat_sys_no_sim;
                visible = false; // no SIM? no data
            }
        } else {
            // CDMA case, mDataActivity can be also DATA_ACTIVITY_DORMANT
            if (mDataState == TelephonyManager.DATA_CONNECTED) {
                switch (mDataActivity[subscription]) {
                    case TelephonyManager.DATA_ACTIVITY_IN:
                        iconId = mDataIconList[1];
                        break;
                    case TelephonyManager.DATA_ACTIVITY_OUT:
                        iconId = mDataIconList[2];
                        break;
                    case TelephonyManager.DATA_ACTIVITY_INOUT:
                        iconId = mDataIconList[3];
                        break;
                    case TelephonyManager.DATA_ACTIVITY_DORMANT:
                    default:
                        iconId = mDataIconList[0];
                        break;
                }
            } else {
                iconId = 0;
                visible = false;
            }
        }

        // yuck - this should NOT be done by the status bar
        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.notePhoneDataConnectionState(mPhone.getNetworkType(subscription), visible);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        mDataDirectionIconId[subscription] = iconId;
        mDataConnected[subscription] = visible;
        Slog.d(TAG,"updateDataIcon when mDataConnected =" + mDataConnected[subscription]);
    }

    void updateNetworkName(boolean showSpn, String spn, boolean showPlmn, String plmn, int subscription) {
        if (false) {
            Slog.d("CarrierLabel", "updateNetworkName showSpn=" + showSpn + " spn=" + spn
                    + " showPlmn=" + showPlmn + " plmn=" + plmn);
        }
        StringBuilder str = new StringBuilder();
        boolean something = false;
        if (showPlmn && plmn != null) {
            str.append(plmn);
            something = true;
        }
        if (showSpn && spn != null) {
            if (something) {
                str.append(mNetworkNameSeparator);
            }
            str.append(spn);
            something = true;
        }
        if (something) {
            mNetworkName[subscription] = str.toString();
        } else {
            mNetworkName[subscription] = mNetworkNameDefault;
        }
    }

    // ===== Wifi ===================================================================

    class WifiHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        mWifiChannel.sendMessage(Message.obtain(this,
                                AsyncChannel.CMD_CHANNEL_FULL_CONNECTION));
                    } else {
                        Slog.e(TAG, "Failed to connect to wifi");
                    }
                    break;
                case WifiManager.DATA_ACTIVITY_NOTIFICATION:
                    if (msg.arg1 != mWifiActivity) {
                        mWifiActivity = msg.arg1;
                        refreshViewsForSubscription(MSimTelephonyManager.getDefault().getDefaultSubscription());
                    }
                    break;
                default:
                    //Ignore
                    break;
            }
        }
    }

    private void updateWifiState(Intent intent) {
        final String action = intent.getAction();
        if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
            mWifiEnabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;

        } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
            final NetworkInfo networkInfo = (NetworkInfo)
                    intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            boolean wasConnected = mWifiConnected;
            mWifiConnected = networkInfo != null && networkInfo.isConnected();
            // If we just connected, grab the inintial signal strength and ssid
            if (mWifiConnected && !wasConnected) {
                // try getting it out of the intent first
                WifiInfo info = (WifiInfo) intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
                if (info == null) {
                    info = mWifiManager.getConnectionInfo();
                }
                if (info != null) {
                    mWifiSsid = huntForSsid(info);
                } else {
                    mWifiSsid = null;
                }
            } else if (!mWifiConnected) {
                mWifiSsid = null;
            }
            // Apparently the wifi level is not stable at this point even if we've just connected to
            // the network; we need to wait for an RSSI_CHANGED_ACTION for that. So let's just set
            // it to 0 for now
            mWifiLevel = 0;
            mWifiRssi = -200;
        } else if (action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
            if (mWifiConnected) {
                mWifiRssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200);
                mWifiLevel = WifiManager.calculateSignalLevel(
                        mWifiRssi, WifiIcons.WIFI_LEVEL_COUNT);
            }
        }

        updateWifiIcons();
    }

    private void updateWifiIcons() {
        if (mWifiConnected) {
            mWifiIconId = WifiIcons.WIFI_SIGNAL_STRENGTH[mInetCondition][mWifiLevel];
            mContentDescriptionWifi = mContext.getString(
                    AccessibilityContentDescriptions.WIFI_CONNECTION_STRENGTH[mWifiLevel]);
        } else {
            if (mDataAndWifiStacked) {
                mWifiIconId = 0;
            } else {
                mWifiIconId = mWifiEnabled ? WifiIcons.WIFI_SIGNAL_STRENGTH[0][0] : 0;
            }
            mContentDescriptionWifi = mContext.getString(R.string.accessibility_no_wifi);
        }
    }

    private String huntForSsid(WifiInfo info) {
        String ssid = info.getSSID();
        if (ssid != null) {
            return ssid;
        }
        // OK, it's not in the connectionInfo; we have to go hunting for it
        List<WifiConfiguration> networks = mWifiManager.getConfiguredNetworks();
        for (WifiConfiguration net : networks) {
            if (net.networkId == info.getNetworkId()) {
                return net.SSID;
            }
        }
        return null;
    }


    // ===== Full or limited Internet connectivity ==================================

    private void updateConnectivity(Intent intent) {
        if (CHATTY) {
            Slog.d(TAG, "updateConnectivity: intent=" + intent);
        }

        NetworkInfo info = (NetworkInfo)(intent.getParcelableExtra(
                ConnectivityManager.EXTRA_NETWORK_INFO));
        int connectionStatus = intent.getIntExtra(ConnectivityManager.EXTRA_INET_CONDITION, 0);

        if (CHATTY) {
            Slog.d(TAG, "updateConnectivity: networkInfo=" + info);
            Slog.d(TAG, "updateConnectivity: connectionStatus=" + connectionStatus);
        }

        mInetCondition = (connectionStatus > INET_CONDITION_THRESHOLD ? 1 : 0);

        if (info != null && info.getType() == ConnectivityManager.TYPE_BLUETOOTH) {
            mBluetoothTethered = info.isConnected() ? true: false;
        } else {
            mBluetoothTethered = false;
        }

        // We want to update all the icons, all at once, for any condition change
        updateDataNetType(MSimTelephonyManager.getDefault().getDefaultSubscription());
        updateDataIcon(MSimTelephonyManager.getDefault().getDefaultSubscription());
        updateTelephonySignalStrength(MSimTelephonyManager.getDefault().getDefaultSubscription());
        updateWifiIcons();
    }


    // ===== Update the views =======================================================

    void refreshViewsForSubscription(int subscription) {
        Context context = mContext;

        String label = "";
        int N;
        Slog.d(TAG,"refreshViewsForSubscription subscription =" + subscription + "mDataConnected =" + mDataConnected[subscription]);
        Slog.d(TAG,"refreshViewsForSubscription mDataActivity =" + mDataActivity[subscription]);
        if (mDataConnected[subscription]) {
            label = mNetworkName[subscription];
            Slog.d(TAG,"refreshViewsForSubscription label =" + label);
            combinedSignalIconId[subscription] = mDataSignalIconId[subscription];
            switch (mDataActivity[subscription]) {
                case TelephonyManager.DATA_ACTIVITY_IN:
                    mMobileActivityIconId[subscription] = R.drawable.stat_sys_signal_in;
                    break;
                case TelephonyManager.DATA_ACTIVITY_OUT:
                    mMobileActivityIconId[subscription] = R.drawable.stat_sys_signal_out;
                    break;
                case TelephonyManager.DATA_ACTIVITY_INOUT:
                    mMobileActivityIconId[subscription] = R.drawable.stat_sys_signal_inout;
                    break;
                default:
                    mMobileActivityIconId[subscription] = 0;
                    break;
            }

            combinedActivityIconId[subscription] = mMobileActivityIconId[subscription];
            combinedSignalIconId[subscription] = mDataSignalIconId[subscription]; // set by updateDataIcon()
            mContentDescriptionCombinedSignal[subscription] = mContentDescriptionDataType[subscription];
        }

        if (mWifiConnected) {
            if (mWifiSsid == null) {
                label = context.getString(R.string.status_bar_settings_signal_meter_wifi_nossid);
                mWifiActivityIconId = 0; // no wifis, no bits
            } else {
                label = mWifiSsid;
                switch (mWifiActivity) {
                    case WifiManager.DATA_ACTIVITY_IN:
                        mWifiActivityIconId = R.drawable.stat_sys_wifi_in;
                        break;
                    case WifiManager.DATA_ACTIVITY_OUT:
                        mWifiActivityIconId = R.drawable.stat_sys_wifi_out;
                        break;
                    case WifiManager.DATA_ACTIVITY_INOUT:
                        mWifiActivityIconId = R.drawable.stat_sys_wifi_inout;
                        break;
                    case WifiManager.DATA_ACTIVITY_NONE:
                        mWifiActivityIconId = 0;
                        break;
                }
            }

            combinedActivityIconId[subscription] = mWifiActivityIconId;
            combinedSignalIconId[subscription] = mWifiIconId; // set by updateWifiIcons()
            mContentDescriptionCombinedSignal[subscription] = mContentDescriptionWifi;
        }

        if (mBluetoothTethered) {
            label = mContext.getString(R.string.bluetooth_tethered);
            combinedSignalIconId[subscription] = mBluetoothTetherIconId;
            mContentDescriptionCombinedSignal[subscription] = mContext.getString(
                    R.string.accessibility_bluetooth_tether);
        }

        if (mAirplaneMode &&
                (mServiceState[subscription] == null || (!hasService(subscription)
                    && !mServiceState[subscription].isEmergencyOnly()))) {
            // Only display the flight-mode icon if not in "emergency calls only" mode.
            label = context.getString(R.string.status_bar_settings_signal_meter_disconnected);
            mContentDescriptionCombinedSignal[subscription] = mContentDescriptionPhoneSignal[subscription]
                = mContext.getString(R.string.accessibility_airplane_mode);
            // look again; your radios are now airplanes
            mPhoneSignalIconId[subscription] = mDataSignalIconId[subscription] = R.drawable.stat_sys_signal_flightmode;
            if(subscription == MSimConstants.SUB1) {
                 mPhoneSignalIconId[MSimConstants.SUB1] = mDataSignalIconId[MSimConstants.SUB1] = 0;
            }

            mDataTypeIconId[subscription] = 0;

            combinedSignalIconId[subscription] = mDataSignalIconId[subscription];
        }
        else if (!mDataConnected[subscription] && !mWifiConnected && !mBluetoothTethered) {
            // pretty much totally disconnected

            label = context.getString(R.string.status_bar_settings_signal_meter_disconnected);
            // On devices without mobile radios, we want to show the wifi icon
            combinedSignalIconId[subscription] =
                mHasMobileDataFeature ? mDataSignalIconId[subscription] : mWifiIconId;
            mContentDescriptionCombinedSignal[subscription] = mHasMobileDataFeature
                ? mContentDescriptionDataType[subscription] : mContentDescriptionWifi;

            if ((isCdma(subscription) && isCdmaEri(subscription)) ||
                    mPhone.isNetworkRoaming(subscription)) {
                mDataTypeIconId[subscription] = R.drawable.stat_sys_data_connected_roam;
            } else {
                mDataTypeIconId[subscription] = 0;
            }
        }

        if (DEBUG) {
            Slog.d(TAG, "refreshViewsForSubscription connected={"
                    + (mWifiConnected?" wifi":"")
                    + (mDataConnected[subscription]?" data":"")
                    + " } level="
                    + ((mSignalStrength[subscription] == null)?"??":Integer.toString(mSignalStrength[subscription].getLevel()))
                    + " combinedSignalIconId=0x"
                    + Integer.toHexString(combinedSignalIconId[subscription])
                    + "/" + getResourceName(combinedSignalIconId[subscription])
                    + " combinedActivityIconId=0x" + Integer.toHexString(combinedActivityIconId[subscription])
                    + " mAirplaneMode=" + mAirplaneMode
                    + " mDataActivity=" + mDataActivity[subscription]
                    + " mPhoneSignalIconId=0x" + Integer.toHexString(mPhoneSignalIconId[subscription])
                    + " mDataDirectionIconId=0x" + Integer.toHexString(mDataDirectionIconId[subscription])
                    + " mDataSignalIconId=0x" + Integer.toHexString(mDataSignalIconId[subscription])
                    + " mDataTypeIconId=0x" + Integer.toHexString(mDataTypeIconId[subscription])
                    + " mWifiIconId=0x" + Integer.toHexString(mWifiIconId)
                    + " mBluetoothTetherIconId=0x" + Integer.toHexString(mBluetoothTetherIconId));
        }

        if (mLastPhoneSignalIconId[subscription]          != mPhoneSignalIconId[subscription]
         || mLastDataDirectionOverlayIconId != combinedActivityIconId[subscription]
         || mLastWifiIconId                 != mWifiIconId
         || mLastDataTypeIconId[subscription]             != mDataTypeIconId[subscription])
        {
            // NB: the mLast*s will be updated later
            for (MSimSignalCluster cluster : mSimSignalClusters) {
                cluster.setWifiIndicators(
                        mWifiConnected, // only show wifi in the cluster if connected
                        mWifiIconId,
                        mWifiActivityIconId,
                        mContentDescriptionWifi);
                cluster.setMobileDataIndicators(
                        mHasMobileDataFeature,
                        mPhoneSignalIconId[subscription],
                        mMobileActivityIconId[subscription],
                        mDataTypeIconId[subscription],
                        mContentDescriptionPhoneSignal[subscription],
                        mContentDescriptionDataType[subscription], subscription);
                cluster.setIsAirplaneMode(mAirplaneMode);
            }
        }

        // the phone icon on phones
        if (mLastPhoneSignalIconId[subscription] != mPhoneSignalIconId[subscription]) {
            mLastPhoneSignalIconId[subscription] = mPhoneSignalIconId[subscription];
            N = mPhoneSignalIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mPhoneSignalIconViews.get(i);
                v.setImageResource(mPhoneSignalIconId[subscription]);
                v.setContentDescription(mContentDescriptionPhoneSignal[subscription]);
            }
        }

        // the data icon on phones
        if (mLastDataDirectionIconId[subscription] != mDataDirectionIconId[subscription]) {
            mLastDataDirectionIconId[subscription] = mDataDirectionIconId[subscription];
            N = mDataDirectionIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mDataDirectionIconViews.get(i);
                v.setImageResource(mDataDirectionIconId[subscription]);
                v.setContentDescription(mContentDescriptionDataType[subscription]);
            }
        }

        // the wifi icon on phones
        if (mLastWifiIconId != mWifiIconId) {
            mLastWifiIconId = mWifiIconId;
            N = mWifiIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mWifiIconViews.get(i);
                if (mWifiIconId == 0) {
                    v.setVisibility(View.INVISIBLE);
                } else {
                    v.setVisibility(View.VISIBLE);
                    v.setImageResource(mWifiIconId);
                    v.setContentDescription(mContentDescriptionWifi);
                }
            }
        }

        // the combined data signal icon
        if (mLastCombinedSignalIconId[subscription] != combinedSignalIconId[subscription]) {
            mLastCombinedSignalIconId[subscription] = combinedSignalIconId[subscription];
            N = mCombinedSignalIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mCombinedSignalIconViews.get(i);
                v.setImageResource(combinedSignalIconId[subscription]);
                v.setContentDescription(mContentDescriptionCombinedSignal[subscription]);
            }
        }

        // the data network type overlay
        if (mLastDataTypeIconId[subscription] != mDataTypeIconId[subscription]) {
            mLastDataTypeIconId[subscription] = mDataTypeIconId[subscription];
            N = mDataTypeIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mDataTypeIconViews.get(i);
                if (mDataTypeIconId[subscription] == 0) {
                    v.setVisibility(View.INVISIBLE);
                } else {
                    v.setVisibility(View.VISIBLE);
                    v.setImageResource(mDataTypeIconId[subscription]);
                    v.setContentDescription(mContentDescriptionDataType[subscription]);
                }
            }
        }

        // the data direction overlay
        if (mLastDataDirectionOverlayIconId != combinedActivityIconId[subscription]) {
            if (DEBUG) {
                Slog.d(TAG, "changing data overlay icon id to " + combinedActivityIconId[subscription]);
            }
            mLastDataDirectionOverlayIconId = combinedActivityIconId[subscription];
            N = mDataDirectionOverlayIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mDataDirectionOverlayIconViews.get(i);
                if (combinedActivityIconId[subscription] == 0) {
                    v.setVisibility(View.INVISIBLE);
                } else {
                    v.setVisibility(View.VISIBLE);
                    v.setImageResource(combinedActivityIconId[subscription]);
                    v.setContentDescription(mContentDescriptionDataType[subscription]);
                }
            }
        }

        // the label in the notification panel
        if (!mLastLabel.equals(label)) {
            mLastLabel = label;
            N = mLabelViews.size();
            for (int i=0; i<N; i++) {
                TextView v = mLabelViews.get(i);
                v.setText(label);
            }
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args, int subscription) {
        pw.println("NetworkController state:");
        pw.println("  - telephony ------");
        pw.print("  hasService()=");
        pw.println(hasService(subscription));
        pw.print("  mHspaDataDistinguishable=");
        pw.println(mHspaDataDistinguishable);
        pw.print("  mDataConnected=");
        pw.println(mDataConnected[subscription]);
        pw.print("  mSimState=");
        pw.println(mSimState[subscription]);
        pw.print("  mPhoneState=");
        pw.println(mPhoneState);
        pw.print("  mDataState=");
        pw.println(mDataState);
        pw.print("  mDataActivity=");
        pw.println(mDataActivity[subscription]);
        pw.print("  mDataNetType=");
        pw.print(mDataNetType);
        pw.print("/");
        pw.println(TelephonyManager.getNetworkTypeName(mDataNetType));
        pw.print("  mServiceState=");
        pw.println(mServiceState[subscription]);
        pw.print("  mSignalStrength=");
        pw.println(mSignalStrength[subscription]);
        pw.print("  mLastSignalLevel");
        pw.println(mLastSignalLevel);
        pw.print("  mNetworkName=");
        pw.println(mNetworkName[subscription]);
        pw.print("  mNetworkNameDefault=");
        pw.println(mNetworkNameDefault);
        pw.print("  mNetworkNameSeparator=");
        pw.println(mNetworkNameSeparator.replace("\n","\\n"));
        pw.print("  mPhoneSignalIconId=0x");
        pw.print(Integer.toHexString(mPhoneSignalIconId[subscription]));
        pw.print("/");
        pw.println(getResourceName(mPhoneSignalIconId[subscription]));
        pw.print("  mDataDirectionIconId=");
        pw.print(Integer.toHexString(mDataDirectionIconId[subscription]));
        pw.print("/");
        pw.println(getResourceName(mDataDirectionIconId[subscription]));
        pw.print("  mDataSignalIconId=");
        pw.print(Integer.toHexString(mDataSignalIconId[subscription]));
        pw.print("/");
        pw.println(getResourceName(mDataSignalIconId[subscription]));
        pw.print("  mDataTypeIconId=");
        pw.print(Integer.toHexString(mDataTypeIconId[subscription]));
        pw.print("/");
        pw.println(getResourceName(mDataTypeIconId[subscription]));

        pw.println("  - wifi ------");
        pw.print("  mWifiEnabled=");
        pw.println(mWifiEnabled);
        pw.print("  mWifiConnected=");
        pw.println(mWifiConnected);
        pw.print("  mWifiRssi=");
        pw.println(mWifiRssi);
        pw.print("  mWifiLevel=");
        pw.println(mWifiLevel);
        pw.print("  mWifiSsid=");
        pw.println(mWifiSsid);
        pw.print(String.format("  mWifiIconId=0x%08x/%s",
                    mWifiIconId, getResourceName(mWifiIconId)));
        pw.print("  mWifiActivity=");
        pw.println(mWifiActivity);


        pw.println("  - Bluetooth ----");
        pw.print("  mBtReverseTethered=");
        pw.println(mBluetoothTethered);

        pw.println("  - connectivity ------");
        pw.print("  mInetCondition=");
        pw.println(mInetCondition);

        pw.println("  - icons ------");
        pw.print("  mLastPhoneSignalIconId=0x");
        pw.print(Integer.toHexString(mLastPhoneSignalIconId[subscription]));
        pw.print("/");
        pw.println(getResourceName(mLastPhoneSignalIconId[subscription]));
        pw.print("  mLastDataDirectionIconId=0x");
        pw.print(Integer.toHexString(mLastDataDirectionIconId[subscription]));
        pw.print("/");
        pw.println(getResourceName(mLastDataDirectionIconId[subscription]));
        pw.print("  mLastDataDirectionOverlayIconId=0x");
        pw.print(Integer.toHexString(mLastDataDirectionOverlayIconId));
        pw.print("/");
        pw.println(getResourceName(mLastDataDirectionOverlayIconId));
        pw.print("  mLastWifiIconId=0x");
        pw.print(Integer.toHexString(mLastWifiIconId));
        pw.print("/");
        pw.println(getResourceName(mLastWifiIconId));
        pw.print("  mLastCombinedSignalIconId=0x");
        pw.print(Integer.toHexString(mLastCombinedSignalIconId[subscription]));
        pw.print("/");
        pw.println(getResourceName(mLastCombinedSignalIconId[subscription]));
        pw.print("  mLastDataTypeIconId=0x");
        pw.print(Integer.toHexString(mLastDataTypeIconId[subscription]));
        pw.print("/");
        pw.println(getResourceName(mLastCombinedSignalIconId[subscription]));
        pw.print("  mLastLabel=");
        pw.print(mLastLabel);
        pw.println("");
    }

    private String getResourceName(int resId) {
        if (resId != 0) {
            final Resources res = mContext.getResources();
            try {
                return res.getResourceName(resId);
            } catch (android.content.res.Resources.NotFoundException ex) {
                return "(unknown)";
            }
        } else {
            return "(null)";
        }
    }

}
