/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.location;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.location.Criteria;
import android.location.IGpsStatusListener;
import android.location.IGpsStatusProvider;
import android.location.ILocationManager;
import android.location.INetInitiatedListener;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationListener;
import android.location.LocationProvider;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkUtils;
import android.net.SntpClient;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.WorkSource;
import android.provider.Settings;
import android.provider.Telephony.Carriers;
import android.provider.Telephony.Sms.Intents;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;
import android.util.NtpTrustedTime;
import android.util.SparseIntArray;

import com.android.internal.app.IBatteryStats;
import com.android.internal.location.GpsNetInitiatedHandler;
import com.android.internal.location.GpsNetInitiatedHandler.GpsNiNotification;
import com.android.internal.telephony.Phone;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.net.InetAddress;

/**
 * A GPS implementation of LocationProvider used by LocationManager.
 *
 * {@hide}
 */
public class GpsLocationProvider implements LocationProviderInterface {

    private static final String TAG = "GpsLocationProvider";

    private static final boolean DEBUG = false;
    private static final boolean VERBOSE = false;

    // these need to match GpsPositionMode enum in gps.h
    private static final int GPS_POSITION_MODE_STANDALONE = 0;
    private static final int GPS_POSITION_MODE_MS_BASED = 1;
    private static final int GPS_POSITION_MODE_MS_ASSISTED = 2;

    // these need to match GpsPositionRecurrence enum in gps.h
    private static final int GPS_POSITION_RECURRENCE_PERIODIC = 0;
    private static final int GPS_POSITION_RECURRENCE_SINGLE = 1;

    // these need to match GpsStatusValue defines in gps.h
    private static final int GPS_STATUS_NONE = 0;
    private static final int GPS_STATUS_SESSION_BEGIN = 1;
    private static final int GPS_STATUS_SESSION_END = 2;
    private static final int GPS_STATUS_ENGINE_ON = 3;
    private static final int GPS_STATUS_ENGINE_OFF = 4;

    // these need to match GpsApgsStatusValue defines in gps.h
    /** AGPS status event values. */
    private static final int GPS_REQUEST_AGPS_DATA_CONN = 1;
    private static final int GPS_RELEASE_AGPS_DATA_CONN = 2;
    private static final int GPS_AGPS_DATA_CONNECTED = 3;
    private static final int GPS_AGPS_DATA_CONN_DONE = 4;
    private static final int GPS_AGPS_DATA_CONN_FAILED = 5;

    // these need to match GpsLocationFlags enum in gps.h
    private static final int LOCATION_INVALID = 0;
    private static final int LOCATION_HAS_LAT_LONG = 1;
    private static final int LOCATION_HAS_ALTITUDE = 2;
    private static final int LOCATION_HAS_SPEED = 4;
    private static final int LOCATION_HAS_BEARING = 8;
    private static final int LOCATION_HAS_ACCURACY = 16;
    private static final int LOCATION_HAS_SOURCE_INFO = 0x20;
    private static final int ULP_LOCATION_IS_FROM_HYBRID = 0x1;
    private static final int ULP_LOCATION_IS_FROM_GNSS = 0x2;


// IMPORTANT - the GPS_DELETE_* symbols here must match constants in gps.h
    private static final int GPS_DELETE_EPHEMERIS = 0x00000001;
    private static final int GPS_DELETE_ALMANAC = 0x00000002;
    private static final int GPS_DELETE_POSITION = 0x00000004;
    private static final int GPS_DELETE_TIME = 0x00000008;
    private static final int GPS_DELETE_IONO = 0x00000010;
    private static final int GPS_DELETE_UTC = 0x00000020;
    private static final int GPS_DELETE_HEALTH = 0x00000040;
    private static final int GPS_DELETE_SVDIR = 0x00000080;
    private static final int GPS_DELETE_SVSTEER = 0x00000100;
    private static final int GPS_DELETE_SADATA = 0x00000200;
    private static final int GPS_DELETE_RTI = 0x00000400;
    private static final int GPS_DELETE_CELLDB_INFO = 0x00000800;
    private static final int GPS_DELETE_ALMANAC_CORR = 0x00001000;
    private static final int GPS_DELETE_FREQ_BIAS_EST = 0x00002000;
    private static final int GPS_DELETE_EPHEMERIS_GLO = 0x00004000;
    private static final int GPS_DELETE_ALMANAC_GLO = 0x00008000;
    private static final int GPS_DELETE_SVDIR_GLO = 0x00010000;
    private static final int GPS_DELETE_SVSTEER_GLO = 0x00020000;
    private static final int GPS_DELETE_ALMANAC_CORR_GLO = 0x00040000;
    private static final int GPS_DELETE_TIME_GPS = 0x00080000;
    private static final int GPS_DELETE_TIME_GLO = 0x00100000;
    private static final int GPS_DELETE_ALL = 0xFFFFFFFF;

    // The GPS_CAPABILITY_* flags must match the values in gps.h
    private static final int GPS_CAPABILITY_SCHEDULING = 0x0000001;
    private static final int GPS_CAPABILITY_MSB = 0x0000002;
    private static final int GPS_CAPABILITY_MSA = 0x0000004;
    private static final int GPS_CAPABILITY_SINGLE_SHOT = 0x0000008;
    private static final int GPS_CAPABILITY_ON_DEMAND_TIME = 0x0000010;

    // Handler messages
    private static final int CHECK_LOCATION = 1;
    private static final int ENABLE = 2;
    private static final int ENABLE_TRACKING = 3;
    private static final int UPDATE_NETWORK_STATE = 4;
    private static final int INJECT_NTP_TIME = 5;
    private static final int DOWNLOAD_XTRA_DATA = 6;
    private static final int UPDATE_LOCATION = 7;
    private static final int ADD_LISTENER = 8;
    private static final int REMOVE_LISTENER = 9;
    private static final int REQUEST_SINGLE_SHOT = 10;
    private static final int REQUEST_PHONE_CONTEXT_SETTINGS = 11;
    private static final int UPDATE_NATIVE_PHONE_CONTEXT_SETTINGS = 12;
    private static final int REQUEST_NETWORK_LOCATION = 13;
    private static final int UPDATE_NETWORK_LOCATION = 14;
    // Request setid
    private static final int AGPS_RIL_REQUEST_SETID_IMSI = 1;
    private static final int AGPS_RIL_REQUEST_SETID_MSISDN = 2;

    // Request ref location
    private static final int AGPS_RIL_REQUEST_REFLOC_CELLID = 1;
    private static final int AGPS_RIL_REQUEST_REFLOC_MAC = 2;

    // ref. location info
    private static final int AGPS_REF_LOCATION_TYPE_GSM_CELLID = 1;
    private static final int AGPS_REF_LOCATION_TYPE_UMTS_CELLID = 2;
    private static final int AGPS_REG_LOCATION_TYPE_MAC        = 3;

    // set id info
    private static final int AGPS_SETID_TYPE_NONE = 0;
    private static final int AGPS_SETID_TYPE_IMSI = 1;
    private static final int AGPS_SETID_TYPE_MSISDN = 2;

    private static final String PROPERTIES_FILE = "/etc/gps.conf";

    private int mLocationFlags = LOCATION_INVALID;
    private volatile boolean mGpsSetting = false;
    private volatile boolean mAgpsSetting = false;
    private volatile boolean mNetworkProvSetting = false;
    private volatile boolean sWifiSetting = false;
    //ULP Defines
    private static final int ULP_NETWORK_POS_STATUS_REQUEST = 1;
    private static final int ULP_NETWORK_POS_START_PERIODIC_REQUEST = 2;
    private static final int ULP_NETWORK_POS_GET_LAST_KNOWN_LOCATION_REQUEST = 3;
    private static final int ULP_NETWORK_POS_STOP_REQUEST = 4;
    private static final int ULP_NETWORK_POSITION_SRC_WIFI = 1;
    private static final int ULP_NETWORK_POSITION_SRC_CELL = 2;
    private static final int ULP_NETWORK_POSITION_SRC_UNKNOWN = 255;

    private static final int ULP_PHONE_CONTEXT_GPS_SETTING       = 0x1;
    private static final int ULP_PHONE_CONTEXT_NETWORK_POSITION_SETTING       = 0x2;
    private static final int ULP_PHONE_CONTEXT_WIFI_SETTING= 0x4;
    /** The battery charging state context supports only
    * ON_CHANGE request type */
    private static final int ULP_PHONE_CONTEXT_BATTERY_CHARGING_STATE      = 0x8;
    private static final int ULP_PHONE_CONTEXT_AGPS_SETTING = 0x10;

    /** Required Settings subscription flag*/
    private static final int ULP_PHONE_CONTEXT_GPS_ON        = 0x1;
    private static final int ULP_PHONE_CONTEXT_GPS_OFF       = 0x2;
    private static final int ULP_PHONE_CONTEXT_AGPS_ON        = 0x4;
    private static final int ULP_PHONE_CONTEXT_AGPS_OFF       = 0x8;
    private static final int ULP_PHONE_CONTEXT_CELL_BASED_POSITION_ON= 0x10;
    private static final int ULP_PHONE_CONTEXT_CELL_BASED_POSITION_OFF= 0x20;
    private static final int ULP_PHONE_CONTEXT_WIFI_ON      = 0x40;
    private static final int ULP_PHONE_CONTEXT_WIFI_OFF     = 0x80;
    private static final int ULP_PHONE_CONTEXT_BATTERY_CHARGING_ON= 0x100;
    private static final int ULP_PHONE_CONTEXT_BATTERY_CHARGING_OFF= 0x200;

    /** return phone context only once */
    private static final int ULP_PHONE_CONTEXT_REQUEST_TYPE_SINGLE= 0x1;
    /** return phone context when it changes */
    private static final int ULP_PHONE_CONTEXT_REQUEST_TYPE_ONCHANGE= 0x2;
    private static final int ULP_PHONE_CONTEXT_UPDATE_TYPE_SINGLE= 0x1;
    private static final int ULP_PHONE_CONTEXT_UPDATE_TYPE_ONCHANGE= 0x2;
    private volatile int mRequestType = 0;
    private volatile int mRequestContextType = 0;

    // current status
    private int mStatus = LocationProvider.TEMPORARILY_UNAVAILABLE;

    // time for last status update
    private long mStatusUpdateTime = SystemClock.elapsedRealtime();

    // turn off GPS fix icon if we haven't received a fix in 10 seconds
    private static final long RECENT_FIX_TIMEOUT = 10 * 1000;

    // stop trying if we do not receive a fix within 60 seconds
    private static final int NO_FIX_TIMEOUT = 60 * 1000;

    // true if we are enabled
    private volatile boolean mEnabled;

    // true if we have network connectivity
    private boolean mNetworkAvailable;

    // flags to trigger NTP or XTRA data download when network becomes available
    // initialized to true so we do NTP and XTRA when the network comes up after booting
    private boolean mInjectNtpTimePending = true;
    private boolean mDownloadXtraDataPending = false;

    // set to true if the GPS engine does not do on-demand NTP time requests
    private boolean mPeriodicTimeInjection;

    // true if GPS is navigating
    private boolean mNavigating;

    // true if GPS engine is on
    private boolean mEngineOn;

    // requested frequency of fixes, in milliseconds
    private int mFixInterval = 1000;

    // true if we started navigation
    private boolean mStarted;

    // true if single shot request is in progress
    private boolean mSingleShot;

    // capabilities of the GPS engine
    private int mEngineCapabilities;

    // true if XTRA is supported
    private boolean mSupportsXtra;

    // for calculating time to first fix
    private long mFixRequestTime = 0;
    // time to first fix for most recent session
    private int mTTFF = 0;
    // time we received our last fix
    private long mLastFixTime;

    private int mPositionMode;

    // properties loaded from PROPERTIES_FILE
    private Properties mProperties;
    private String mSuplServerHost;
    private int mSuplServerPort;
    private String mC2KServerHost;
    private int mC2KServerPort;

    private final Context mContext;
    private final NtpTrustedTime mNtpTime;
    private final ILocationManager mLocationManager;
    private Location mLocation = new Location(LocationManager.GPS_PROVIDER);
    private Bundle mLocationExtras = new Bundle();
    private ArrayList<Listener> mListeners = new ArrayList<Listener>();

    // GpsLocationProvider's handler thread
    private final Thread mThread;
    // Handler for processing events in mThread.
    private Handler mHandler;
    // Used to signal when our main thread has initialized everything
    private final CountDownLatch mInitializedLatch = new CountDownLatch(1);

    private final ConnectivityManager mConnMgr;
    private final GpsNetInitiatedHandler mNIHandler;

    // Wakelocks
    private final static String WAKELOCK_KEY = "GpsLocationProvider";
    private final PowerManager.WakeLock mWakeLock;
    // bitfield of pending messages to our Handler
    // used only for messages that cannot have multiple instances queued
    private int mPendingMessageBits;
    // separate counter for ADD_LISTENER and REMOVE_LISTENER messages,
    // which might have multiple instances queued
    private int mPendingListenerMessages;

    // Alarms
    private final static String ALARM_WAKEUP = "com.android.internal.location.ALARM_WAKEUP";
    private final static String ALARM_TIMEOUT = "com.android.internal.location.ALARM_TIMEOUT";
    private final AlarmManager mAlarmManager;
    private final PendingIntent mWakeupIntent;
    private final PendingIntent mTimeoutIntent;

    private final IBatteryStats mBatteryStats;
    private final SparseIntArray mClientUids = new SparseIntArray();

    // how often to request NTP time, in milliseconds
    // current setting 24 hours
    private static final long NTP_INTERVAL = 24*60*60*1000;
    // how long to wait if we have a network error in NTP or XTRA downloading
    // current setting - 5 minutes
    private static final long RETRY_INTERVAL = 5*60*1000;

    private final IGpsStatusProvider mGpsStatusProvider = new IGpsStatusProvider.Stub() {
        public void addGpsStatusListener(IGpsStatusListener listener) throws RemoteException {
            if (listener == null) {
                throw new NullPointerException("listener is null in addGpsStatusListener");
            }

            synchronized(mListeners) {
                IBinder binder = listener.asBinder();
                int size = mListeners.size();
                for (int i = 0; i < size; i++) {
                    Listener test = mListeners.get(i);
                    if (binder.equals(test.mListener.asBinder())) {
                        // listener already added
                        return;
                    }
                }

                Listener l = new Listener(listener);
                binder.linkToDeath(l, 0);
                mListeners.add(l);
            }
        }

        public void removeGpsStatusListener(IGpsStatusListener listener) {
            if (listener == null) {
                throw new NullPointerException("listener is null in addGpsStatusListener");
            }

            synchronized(mListeners) {
                IBinder binder = listener.asBinder();
                Listener l = null;
                int size = mListeners.size();
                for (int i = 0; i < size && l == null; i++) {
                    Listener test = mListeners.get(i);
                    if (binder.equals(test.mListener.asBinder())) {
                        l = test;
                    }
                }

                if (l != null) {
                    mListeners.remove(l);
                    binder.unlinkToDeath(l, 0);
                }
            }
        }
    };

    public IGpsStatusProvider getGpsStatusProvider() {
        return mGpsStatusProvider;
    }

    private final BroadcastReceiver mBroadcastReciever = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(ALARM_WAKEUP)) {
                if (DEBUG) Log.d(TAG, "ALARM_WAKEUP");
                startNavigating(false);
            } else if (action.equals(ALARM_TIMEOUT)) {
                if (DEBUG) Log.d(TAG, "ALARM_TIMEOUT");
                hibernate();
            } else if (action.equals(Intents.DATA_SMS_RECEIVED_ACTION)) {
                checkSmsSuplInit(intent);
            } else if (action.equals(Intents.WAP_PUSH_RECEIVED_ACTION)) {
                checkWapSuplInit(intent);
             }
        }
    };

    private void checkSmsSuplInit(Intent intent) {
        SmsMessage[] messages = Intents.getMessagesFromIntent(intent);
        for (int i=0; i <messages.length; i++) {
            byte[] supl_init = messages[i].getUserData();
            native_agps_ni_message(supl_init,supl_init.length);
        }
    }

    private void checkWapSuplInit(Intent intent) {
        byte[] supl_init = (byte[]) intent.getExtra("data");
        native_agps_ni_message(supl_init,supl_init.length);
    }

    public static boolean isSupported() {
        return native_is_supported();
    }

    public GpsLocationProvider(Context context, ILocationManager locationManager) {
        mContext = context;
        mNtpTime = NtpTrustedTime.getInstance(context);
        mLocationManager = locationManager;
        mNIHandler = new GpsNetInitiatedHandler(context);

        mLocation.setExtras(mLocationExtras);

        // Create a wake lock
        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_KEY);
        mWakeLock.setReferenceCounted(false);

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        mWakeupIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(ALARM_WAKEUP), 0);
        mTimeoutIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(ALARM_TIMEOUT), 0);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intents.DATA_SMS_RECEIVED_ACTION);
        intentFilter.addDataScheme("sms");
        intentFilter.addDataAuthority("localhost","7275");
        context.registerReceiver(mBroadcastReciever, intentFilter);

        intentFilter = new IntentFilter();
        intentFilter.addAction(Intents.WAP_PUSH_RECEIVED_ACTION);
        try {
            intentFilter.addDataType("application/vnd.omaloc-supl-init");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            Log.w(TAG, "Malformed SUPL init mime type");
        }
        context.registerReceiver(mBroadcastReciever, intentFilter);

        mConnMgr = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);

        // Battery statistics service to be notified when GPS turns on or off
        mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService("batteryinfo"));

        mProperties = new Properties();
        try {
            File file = new File(PROPERTIES_FILE);
            FileInputStream stream = new FileInputStream(file);
            mProperties.load(stream);
            stream.close();

            mSuplServerHost = mProperties.getProperty("SUPL_HOST");
            String portString = mProperties.getProperty("SUPL_PORT");
            if (mSuplServerHost != null && portString != null) {
                try {
                    mSuplServerPort = Integer.parseInt(portString);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "unable to parse SUPL_PORT: " + portString);
                }
            }

            mC2KServerHost = mProperties.getProperty("C2K_HOST");
            portString = mProperties.getProperty("C2K_PORT");
            if (mC2KServerHost != null && portString != null) {
                try {
                    mC2KServerPort = Integer.parseInt(portString);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "unable to parse C2K_PORT: " + portString);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not open GPS configuration file " + PROPERTIES_FILE);
        }

        // wait until we are fully initialized before returning
        mThread = new GpsLocationProviderThread();
        mThread.start();
        while (true) {
            try {
                mInitializedLatch.await();
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void initialize() {
        // register our receiver on our thread rather than the main thread
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ALARM_WAKEUP);
        intentFilter.addAction(ALARM_TIMEOUT);
        mContext.registerReceiver(mBroadcastReciever, intentFilter);
    }

    /**
     * Returns the name of this provider.
     */
    public String getName() {
        return LocationManager.GPS_PROVIDER;
    }

    /**
     * Returns true if the provider requires access to a
     * data network (e.g., the Internet), false otherwise.
     */
    public boolean requiresNetwork() {
        return true;
    }

    public void updateNetworkState(int state, NetworkInfo info) {
        sendMessage(UPDATE_NETWORK_STATE, state, info);
    }

    private void handleUpdateNetworkState(int state, NetworkInfo info) {
        mNetworkAvailable = (state == LocationProvider.AVAILABLE);

        if (DEBUG) {
            Log.d(TAG, "updateNetworkState " + (mNetworkAvailable ? "available" : "unavailable")
                + " info: " + info);
        }

        if (info != null) {
            boolean dataEnabled = Settings.Secure.getInt(mContext.getContentResolver(),
                                                         Settings.Secure.MOBILE_DATA, 1) == 1;
            boolean networkAvailable = info.isAvailable() && dataEnabled;
            String defaultApn = getSelectedApn();
            if (defaultApn == null) {
                defaultApn = "dummy-apn";
            }

            native_update_network_state(info.isConnected(), info.getType(),
                                        info.isRoaming(), networkAvailable,
                                        info.getExtraInfo(), defaultApn);

            int connType = (ConnectivityManager.TYPE_MOBILE_SUPL == info.getType()) ?
                AGpsConnectionInfo.CONNECTION_TYPE_SUPL : AGpsConnectionInfo.CONNECTION_TYPE_WWAN_ANY;
            AGpsConnectionInfo agpsConnInfo = getAGpsConnectionInfo(connType);
            if (null != agpsConnInfo &&
                agpsConnInfo.mState == AGpsConnectionInfo.STATE_OPENING) {
                if (mNetworkAvailable) {
                    String apnName = info.getExtraInfo();
                    if (apnName == null) {
                        /* We use the value we read out from the database. That value itself
                           is default to "dummy-apn" if no value from database. */
                        apnName = defaultApn;
                    }
                    agpsConnInfo.mAPN = apnName;
                    // String ipv4Apn = info.getIpv4Apn();
                    // String ipv6Apn = info.getIpv6Apn();
                    // if (null == ipv6Apn || !ipv6Apn.equals(apnName)) {
                        agpsConnInfo.mBearerType = agpsConnInfo.BEARER_IPV4;
                    // } else if (null == ipv4Apn || !ipv4Apn.equals(apnName)) {
                    //     agpsConnInfo.mBearerType = agpsConnInfo.BEARER_IPV6;
                    // } else {
                    //     agpsConnInfo.mBearerType = agpsConnInfo.BEARER_IPV4V6;
                    // }

                    if (agpsConnInfo.mIPv4Addr != null) {
                        boolean route_result;
                        Log.d(TAG, "agpsConnInfo.mIPv4Addr " + agpsConnInfo.mIPv4Addr.toString());
                        route_result = mConnMgr.requestRouteToHostAddress(agpsConnInfo.mCMConnType,
                                                                          agpsConnInfo.mIPv4Addr);
                        if (route_result == false)
                            Log.d(TAG, "call requestRouteToHostAddress failed");
                    }
                    if (DEBUG) Log.d(TAG, "call native_agps_data_conn_open");
                    native_agps_data_conn_open(agpsConnInfo.mAgpsType, apnName, agpsConnInfo.mBearerType);
                    agpsConnInfo.mState = AGpsConnectionInfo.STATE_OPEN;
                } else {
                    if (DEBUG) Log.d(TAG, "call native_agps_data_conn_failed");
                    agpsConnInfo.mAPN = null;
                    agpsConnInfo.mState = AGpsConnectionInfo.STATE_CLOSED;
                    native_agps_data_conn_failed(agpsConnInfo.mAgpsType);
                }
            }
        }

        if (mNetworkAvailable) {
            if (mInjectNtpTimePending) {
                sendMessage(INJECT_NTP_TIME, 0, null);
            }
            if (mDownloadXtraDataPending) {
                sendMessage(DOWNLOAD_XTRA_DATA, 0, null);
            }
        }
    }

    private void handleInjectNtpTime() {
        if (!mNetworkAvailable) {
            // try again when network is up
            mInjectNtpTimePending = true;
            return;
        }
        mInjectNtpTimePending = false;

        long delay;

        // GPS requires fresh NTP time
        if (mNtpTime.forceRefresh()) {
            long time = mNtpTime.getCachedNtpTime();
            long timeReference = mNtpTime.getCachedNtpTimeReference();
            long certainty = mNtpTime.getCacheCertainty();
            long now = System.currentTimeMillis();

            Log.d(TAG, "NTP server returned: "
                    + time + " (" + new Date(time)
                    + ") reference: " + timeReference
                    + " certainty: " + certainty
                    + " system time offset: " + (time - now));

            native_inject_time(time, timeReference, (int) certainty);
            delay = NTP_INTERVAL;
        } else {
            if (DEBUG) Log.d(TAG, "requestTime failed");
            delay = RETRY_INTERVAL;
        }

        if (mPeriodicTimeInjection) {
            // send delayed message for next NTP injection
            // since this is delayed and not urgent we do not hold a wake lock here
            mHandler.removeMessages(INJECT_NTP_TIME);
            mHandler.sendMessageDelayed(Message.obtain(mHandler, INJECT_NTP_TIME), delay);
        }
    }

    private void handleDownloadXtraData() {
        if (!mNetworkAvailable) {
            // try again when network is up
            mDownloadXtraDataPending = true;
            return;
        }
        mDownloadXtraDataPending = false;


        GpsXtraDownloader xtraDownloader = new GpsXtraDownloader(mContext, mProperties);
        byte[] data = xtraDownloader.downloadXtraData();
        if (data != null) {
            if (DEBUG) {
                Log.d(TAG, "calling native_inject_xtra_data");
            }
            native_inject_xtra_data(data, data.length);
        } else {
            // try again later
            // since this is delayed and not urgent we do not hold a wake lock here
            mHandler.removeMessages(DOWNLOAD_XTRA_DATA);
            mHandler.sendMessageDelayed(Message.obtain(mHandler, DOWNLOAD_XTRA_DATA), RETRY_INTERVAL);
        }
    }

    /**
     * This is called to inform us when another location provider returns a location.
     * Someday we might use this for network location injection to aid the GPS
     */
    public void updateLocation(Location location) {
        sendMessage(UPDATE_LOCATION, 0, location);
    }

    private void handleUpdateLocation(Location location) {
        if (location.hasAccuracy()) {
            native_inject_location(location.getLatitude(), location.getLongitude(),
                    location.getAccuracy());
        }
    }

    /**
     * Returns true if the provider requires access to a
     * satellite-based positioning system (e.g., GPS), false
     * otherwise.
     */
    public boolean requiresSatellite() {
        return true;
    }

    /**
     * Returns true if the provider requires access to an appropriate
     * cellular network (e.g., to make use of cell tower IDs), false
     * otherwise.
     */
    public boolean requiresCell() {
        return false;
    }

    /**
     * Returns true if the use of this provider may result in a
     * monetary charge to the user, false if use is free.  It is up to
     * each provider to give accurate information.
     */
    public boolean hasMonetaryCost() {
        return false;
    }

    /**
     * Returns true if the provider is able to provide altitude
     * information, false otherwise.  A provider that reports altitude
     * under most circumstances but may occassionally not report it
     * should return true.
     */
    public boolean supportsAltitude() {
        return true;
    }

    /**
     * Returns true if the provider is able to provide speed
     * information, false otherwise.  A provider that reports speed
     * under most circumstances but may occassionally not report it
     * should return true.
     */
    public boolean supportsSpeed() {
        return true;
    }

    /**
     * Returns true if the provider is able to provide bearing
     * information, false otherwise.  A provider that reports bearing
     * under most circumstances but may occassionally not report it
     * should return true.
     */
    public boolean supportsBearing() {
        return true;
    }

    /**
     * Returns the power requirement for this provider.
     *
     * @return the power requirement for this provider, as one of the
     * constants Criteria.POWER_REQUIREMENT_*.
     */
    public int getPowerRequirement() {
        return Criteria.POWER_HIGH;
    }

    /**
     * Returns true if this provider meets the given criteria,
     * false otherwise.
     */
    public boolean meetsCriteria(Criteria criteria) {
        return (criteria.getPowerRequirement() != Criteria.POWER_LOW);
    }

    /**
     * Returns the horizontal accuracy of this provider
     *
     * @return the accuracy of location from this provider, as one
     * of the constants Criteria.ACCURACY_*.
     */
    public int getAccuracy() {
        return Criteria.ACCURACY_FINE;
    }

    /**
     * Enables this provider.  When enabled, calls to getStatus()
     * must be handled.  Hardware may be started up
     * when the provider is enabled.
     */
    public void enable() {
        synchronized (mHandler) {
            sendMessage(ENABLE, 1, null);
        }
    }

    private void handleEnable() {
        if (DEBUG) Log.d(TAG, "handleEnable");
        if (mEnabled) return;
        mEnabled = native_init();

        if (mEnabled) {
            mSupportsXtra = native_supports_xtra();
            if (mSuplServerHost != null) {
                native_set_agps_server(AGpsConnectionInfo.CONNECTION_TYPE_SUPL, mSuplServerHost, mSuplServerPort);
            }
            if (mC2KServerHost != null) {
                native_set_agps_server(AGpsConnectionInfo.CONNECTION_TYPE_C2K, mC2KServerHost, mC2KServerPort);
            }
        } else {
            Log.w(TAG, "Failed to enable location provider");
        }
    }

    /**
     * Disables this provider.  When disabled, calls to getStatus()
     * need not be handled.  Hardware may be shut
     * down while the provider is disabled.
     */
    public void disable() {
        synchronized (mHandler) {
            sendMessage(ENABLE, 0, null);
        }
    }

    private void handleDisable() {
        if (DEBUG) Log.d(TAG, "handleDisable");
        if (!mEnabled) return;

        mEnabled = false;
        stopNavigating();

        // do this before releasing wakelock
        native_cleanup();
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public int getStatus(Bundle extras) {
        if (extras != null) {
            extras.putInt("satellites", mSvCount);
        }
        return mStatus;
    }
    public int getCapability() {
        Log.d(TAG, "Entered getCapability and returned mEngineCapabilities: " + mEngineCapabilities);
        return mEngineCapabilities;
    }

    private void updateStatus(int status, int svCount) {
        if (status != mStatus || svCount != mSvCount) {
            mStatus = status;
            mSvCount = svCount;
            mLocationExtras.putInt("satellites", svCount);
            mStatusUpdateTime = SystemClock.elapsedRealtime();
        }
    }

    public long getStatusUpdateTime() {
        return mStatusUpdateTime;
    }

    public void enableLocationTracking(boolean enable) {
        // FIXME - should set a flag here to avoid race conditions with single shot request
        synchronized (mHandler) {
            sendMessage(ENABLE_TRACKING, (enable ? 1 : 0), null);
        }
    }

    private void handleEnableLocationTracking(boolean enable) {
        Log.d(TAG, "In handleEnableLocationTracking. enable " +enable);
        if (enable) {
            mTTFF = 0;
            mLastFixTime = 0;
            startNavigating(false);
        } else {
            if (!hasCapability(GPS_CAPABILITY_SCHEDULING)) {
                mAlarmManager.cancel(mWakeupIntent);
                mAlarmManager.cancel(mTimeoutIntent);
            }
            stopNavigating();
            // send an intent to notify that the GPS has been enabled or disabled.
            Intent intent = new Intent(LocationManager.GPS_ENABLED_CHANGE_ACTION);
            intent.putExtra(LocationManager.EXTRA_GPS_ENABLED, false);
            mContext.sendBroadcast(intent);
        }
    }

    public boolean requestSingleShotFix() {
        if (mStarted) {
            // cannot do single shot if already navigating
            return false;
        }
        synchronized (mHandler) {
            mHandler.removeMessages(REQUEST_SINGLE_SHOT);
            Message m = Message.obtain(mHandler, REQUEST_SINGLE_SHOT);
            mHandler.sendMessage(m);
        }
        return true;
    }

    private void handleRequestSingleShot() {
        mTTFF = 0;
        mLastFixTime = 0;
        startNavigating(true);
    }

    public void setMinTime(long minTime, WorkSource ws) {
        if (DEBUG) Log.d(TAG, "setMinTime " + minTime);

        if (minTime >= 0) {
            mFixInterval = (int)minTime;

            if (mStarted && hasCapability(GPS_CAPABILITY_SCHEDULING)) {
                if (!native_set_position_mode(mPositionMode, GPS_POSITION_RECURRENCE_PERIODIC,
                        mFixInterval, 0, 0)) {
                    Log.e(TAG, "set_position_mode failed in setMinTime()");
                }
            }
        }
    }

    public boolean updateCriteria(int action, long minTime, float minDistance,
                                  boolean singleShot,Criteria criteria) {
        boolean return_value = false;
        if (DEBUG) Log.d(TAG, "updateCriteria with minTime " + minTime +" minDistance "+
                               minDistance + " singleShot " + singleShot + " criteria: " + criteria);

        if (criteria != null) {
            //This is a ULP client app. Transalate criteria and push it down
            return_value = native_update_criteria(action, minTime, minDistance, singleShot,
                                                  criteria.getHorizontalAccuracy(),
                                                  criteria.getPowerRequirement());
        }
        else
        {   //This is a GPS provider app. Send the request down to the HAL
            return_value = native_update_criteria(action, minTime, minDistance, singleShot, 0, 0);
        }
        return return_value;
    }

    private void handleNativePhoneContextRequest(int contextType, int requestType)
    {
        //update the Global request settings and set up a native update cycle
        mRequestContextType = contextType;
        mRequestType = requestType;
        Log.d(TAG, "handleNativePhoneContextRequest invoked from native layer with mRequestContextType: "+
              mRequestContextType+" mRequestType:"+mRequestType);
        handleNativePhoneContextUpdate(ULP_PHONE_CONTEXT_UPDATE_TYPE_SINGLE, null);
    }

    private void handleNativeNetworkLocationRequest(int type, int interval)
    {
        LocationManager lm = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        switch(type) {
            case ULP_NETWORK_POS_START_PERIODIC_REQUEST:
                Log.d(TAG, "handleNativeNetworkLocationRequest NLP start from GP");
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER ,interval, 0, mNetworkLocationListener);
                break;
            case ULP_NETWORK_POS_GET_LAST_KNOWN_LOCATION_REQUEST:
                Location location = lm.getLastKnownLocation("LocationManager.NETWORK_PROVIDER");
                synchronized (mHandler) {
                mHandler.removeMessages(UPDATE_NETWORK_LOCATION);
                Message m = Message.obtain(mHandler, UPDATE_NETWORK_LOCATION);
                m.arg1 = 0;
                m.obj = location;
                mHandler.sendMessage(m);
                }
                break;
             case ULP_NETWORK_POS_STOP_REQUEST:
                 Log.d(TAG, "handleNativeNetworkLocationRequest NLP stop from GP");
                 lm.removeUpdates(mNetworkLocationListener);
                 break;
             default:
                  Log.e(TAG, "handleNativeNetworkLocationRequest. Inccorect request sent in: "+type);

            }
    }

    private void handleNativePhoneContextUpdate(int updateType, Bundle settingsValues)
    {
      int currentContextType = 0;
      //Read all the current settings and update mask value
      boolean currentAgpsSetting = false, currentWifiSetting  = false,
          currentGpsSetting  = false, currentNetworkProvSetting = false;
      ContentResolver resolver = mContext.getContentResolver();
      Log.d(TAG, "handleNativePhoneContextUpdate called. updateType: "+ updateType
            + " mRequestContextType: " + mRequestContextType + " mRequestType: " +
            mRequestType);
      if(mRequestContextType == 0) {
         Log.d(TAG, "handleNativePhoneContextUpdate. Update obtained before request. Ignoring");
         return;
      }
      try {
          //Update Gps Setting
          if( (mRequestContextType & ULP_PHONE_CONTEXT_GPS_SETTING) == ULP_PHONE_CONTEXT_GPS_SETTING )
          {
              if(updateType == ULP_PHONE_CONTEXT_UPDATE_TYPE_SINGLE) {
                  currentGpsSetting =
                      Settings.Secure.isLocationProviderEnabled(resolver, LocationManager.GPS_PROVIDER);
              }else
              {
                  currentGpsSetting = settingsValues.getBoolean("gpsSetting");
              }
          }
          //Update AGps Setting
          if( (mRequestContextType & ULP_PHONE_CONTEXT_AGPS_SETTING) == ULP_PHONE_CONTEXT_AGPS_SETTING)
          {
              if(updateType == ULP_PHONE_CONTEXT_UPDATE_TYPE_SINGLE) {
                  currentAgpsSetting =
                      (Settings.Secure.getInt(resolver,Settings.Secure.ASSISTED_GPS_ENABLED) == 1);
              }else
              {
                  currentAgpsSetting = settingsValues.getBoolean("agpsSetting");
              }
         }
          //Update GNP Setting
          if((mRequestContextType & ULP_PHONE_CONTEXT_NETWORK_POSITION_SETTING)
               == ULP_PHONE_CONTEXT_NETWORK_POSITION_SETTING)
          {
              if(updateType == ULP_PHONE_CONTEXT_UPDATE_TYPE_SINGLE) {
                  currentNetworkProvSetting =
                      Settings.Secure.isLocationProviderEnabled(resolver, LocationManager.NETWORK_PROVIDER );
              }else
              {
                  currentNetworkProvSetting = settingsValues.getBoolean("networkProvSetting");
              }
          }

          //Update WiFi Setting
          if( (mRequestContextType & ULP_PHONE_CONTEXT_WIFI_SETTING)
               == ULP_PHONE_CONTEXT_WIFI_SETTING )
          {
              if(updateType == ULP_PHONE_CONTEXT_UPDATE_TYPE_SINGLE) {
                  currentWifiSetting =
                       (Settings.Secure.getInt(resolver,Settings.Secure.WIFI_ON) == 1);
              }else
              {
                  currentWifiSetting = settingsValues.getBoolean("wifiSetting");
              }
          }
      } catch (Exception e) {
        Log.e(TAG, "Exception in handleNativePhoneContextUpdate:", e);
      }

      //Filter out settings update based on Single Vs On-change request type using validity mask
      if(updateType == ULP_PHONE_CONTEXT_UPDATE_TYPE_SINGLE ) {
          currentContextType = mRequestContextType;
      }
      else
      {//Need to mark only changed values as valid
          if(currentGpsSetting != mGpsSetting) {
               currentContextType |= ULP_PHONE_CONTEXT_GPS_SETTING;
          }
          if(currentAgpsSetting != mAgpsSetting) {
               currentContextType |= ULP_PHONE_CONTEXT_AGPS_SETTING;
          }

          if(currentNetworkProvSetting != mNetworkProvSetting) {
              currentContextType |= ULP_PHONE_CONTEXT_NETWORK_POSITION_SETTING;
          }
          if (currentWifiSetting != sWifiSetting) {
              currentContextType |= ULP_PHONE_CONTEXT_WIFI_SETTING;
          }

      }
      mGpsSetting = currentGpsSetting;
      mAgpsSetting = currentAgpsSetting;
      mNetworkProvSetting = currentNetworkProvSetting;
      sWifiSetting = currentWifiSetting;
      native_update_settings(currentContextType, currentGpsSetting, currentAgpsSetting,
                             currentNetworkProvSetting,currentWifiSetting );
      Log.d(TAG, "After calling native_update_settings. currentContextType: " +
            currentContextType+" mGpsSetting: "+mGpsSetting + "currentAgpsSetting "+
            currentAgpsSetting+" currentNetworkProvSetting:" + currentNetworkProvSetting
            + "currentWifiSetting"+ currentWifiSetting);
    }
    public boolean updateSettings(boolean gpsSetting,boolean networkProvSetting,
                                  boolean wifiSetting,boolean agpsSetting){
        Log.d(TAG, "updateSettings invoked from LMS and setting values. Gps:"+
                             gpsSetting +" GNP:"+ networkProvSetting+" WiFi:"+ wifiSetting+
                             " Agps:"+ agpsSetting);
        synchronized (mWakeLock) {
            mWakeLock.acquire();
            mHandler.removeMessages(UPDATE_NATIVE_PHONE_CONTEXT_SETTINGS);
            Message m = Message.obtain(mHandler, UPDATE_NATIVE_PHONE_CONTEXT_SETTINGS);
            Bundle b = new Bundle();
            b.putBoolean("gpsSetting", gpsSetting);
            b.putBoolean("networkProvSetting", networkProvSetting);
            b.putBoolean("wifiSetting", wifiSetting);
            b.putBoolean("agpsSetting", agpsSetting);
            m.setData(b);
            m.arg1 = ULP_PHONE_CONTEXT_UPDATE_TYPE_ONCHANGE;
            mHandler.sendMessage(m);
        }
        return true;
    }

    public String getInternalState() {
        return native_get_internal_state();
    }

    private final class Listener implements IBinder.DeathRecipient {
        final IGpsStatusListener mListener;

        int mSensors = 0;

        Listener(IGpsStatusListener listener) {
            mListener = listener;
        }

        public void binderDied() {
            if (DEBUG) Log.d(TAG, "GPS status listener died");

            synchronized(mListeners) {
                mListeners.remove(this);
            }
            if (mListener != null) {
                mListener.asBinder().unlinkToDeath(this, 0);
            }
        }
    }

    public void addListener(int uid) {
        synchronized (mWakeLock) {
            mPendingListenerMessages++;
            mWakeLock.acquire();
            Message m = Message.obtain(mHandler, ADD_LISTENER);
            m.arg1 = uid;
            mHandler.sendMessage(m);
        }
    }

    private void handleAddListener(int uid) {
        synchronized(mListeners) {
            if (mClientUids.indexOfKey(uid) >= 0) {
                // Shouldn't be here -- already have this uid.
                Log.w(TAG, "Duplicate add listener for uid " + uid);
                return;
            }
            mClientUids.put(uid, 0);
            if (mNavigating) {
                try {
                    mBatteryStats.noteStartGps(uid);
                } catch (RemoteException e) {
                    Log.w(TAG, "RemoteException in addListener");
                }
            }
        }
    }

    public void removeListener(int uid) {
        synchronized (mWakeLock) {
            mPendingListenerMessages++;
            mWakeLock.acquire();
            Message m = Message.obtain(mHandler, REMOVE_LISTENER);
            m.arg1 = uid;
            mHandler.sendMessage(m);
        }
    }

    private void handleRemoveListener(int uid) {
        synchronized(mListeners) {
            if (mClientUids.indexOfKey(uid) < 0) {
                // Shouldn't be here -- don't have this uid.
                Log.w(TAG, "Unneeded remove listener for uid " + uid);
                return;
            }
            mClientUids.delete(uid);
            if (mNavigating) {
                try {
                    mBatteryStats.noteStopGps(uid);
                } catch (RemoteException e) {
                    Log.w(TAG, "RemoteException in removeListener");
                }
            }
        }
    }

    public boolean sendExtraCommand(String command, Bundle extras) {

        long identity = Binder.clearCallingIdentity();
        boolean result = false;

        if ("delete_aiding_data".equals(command)) {
            result = deleteAidingData(extras);
        } else if ("force_time_injection".equals(command)) {
            sendMessage(INJECT_NTP_TIME, 0, null);
            result = true;
        } else if ("force_xtra_injection".equals(command)) {
            if (mSupportsXtra) {
                xtraDownloadRequest();
                result = true;
               }
        } else {
          if (native_inject_raw_command(command.getBytes(), command.length()) == false)
              result = true;
        }

        Binder.restoreCallingIdentity(identity);
        return result;
    }

    private boolean deleteAidingData(Bundle extras) {
        int flags;

        if (extras == null) {
            flags = GPS_DELETE_ALL;
        } else {
            flags = 0;
            if (extras.getBoolean("ephemeris")) flags |= GPS_DELETE_EPHEMERIS;
            if (extras.getBoolean("almanac")) flags |= GPS_DELETE_ALMANAC;
            if (extras.getBoolean("position")) flags |= GPS_DELETE_POSITION;
            if (extras.getBoolean("time")) flags |= GPS_DELETE_TIME;
            if (extras.getBoolean("iono")) flags |= GPS_DELETE_IONO;
            if (extras.getBoolean("utc")) flags |= GPS_DELETE_UTC;
            if (extras.getBoolean("health")) flags |= GPS_DELETE_HEALTH;
            if (extras.getBoolean("svdir")) flags |= GPS_DELETE_SVDIR;
            if (extras.getBoolean("svsteer")) flags |= GPS_DELETE_SVSTEER;
            if (extras.getBoolean("sadata")) flags |= GPS_DELETE_SADATA;
            if (extras.getBoolean("rti")) flags |= GPS_DELETE_RTI;
            if (extras.getBoolean("celldb-info")) flags |= GPS_DELETE_CELLDB_INFO;
            if (extras.getBoolean("almanac-corr")) flags |= GPS_DELETE_ALMANAC_CORR;
            if (extras.getBoolean("freq-bias-est")) flags |= GPS_DELETE_FREQ_BIAS_EST;
            if (extras.getBoolean("ephemeris-GLO")) flags |= GPS_DELETE_EPHEMERIS_GLO;
            if (extras.getBoolean("almanac-GLO")) flags |= GPS_DELETE_ALMANAC_GLO;
            if (extras.getBoolean("svdir-GLO")) flags |= GPS_DELETE_SVDIR_GLO;
            if (extras.getBoolean("svsteer-GLO")) flags |= GPS_DELETE_SVSTEER_GLO;
            if (extras.getBoolean("almanac-corr-GLO")) flags |= GPS_DELETE_ALMANAC_CORR_GLO;
            if (extras.getBoolean("time-gps")) flags |= GPS_DELETE_TIME_GPS;
            if (extras.getBoolean("time-GLO")) flags |= GPS_DELETE_TIME_GLO;
            if (extras.getBoolean("all")) flags |= GPS_DELETE_ALL;
        }

        if (flags != 0) {
            native_delete_aiding_data(flags);
            return true;
        }

        return false;
    }

    private void startNavigating(boolean singleShot) {
        if (!mStarted) {
            if (DEBUG) Log.d(TAG, "startNavigating");
            mStarted = true;
            mSingleShot = singleShot;
            mPositionMode = GPS_POSITION_MODE_STANDALONE;

             if (Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.ASSISTED_GPS_ENABLED, 1) != 0) {
                if (singleShot && hasCapability(GPS_CAPABILITY_MSA)) {
                    mPositionMode = GPS_POSITION_MODE_MS_ASSISTED;
                } else if (hasCapability(GPS_CAPABILITY_MSB)) {
                    mPositionMode = GPS_POSITION_MODE_MS_BASED;
                }
            }

            int interval = (hasCapability(GPS_CAPABILITY_SCHEDULING) ? mFixInterval : 1000);
            //We want to suppress position mode updates if ULP capability is available
            if(!hasCapability(LocationProviderInterface.ULP_CAPABILITY)) {
                if (!native_set_position_mode(mPositionMode, GPS_POSITION_RECURRENCE_PERIODIC,
                        interval, 0, 0)) {
                    mStarted = false;
                    Log.e(TAG, "set_position_mode failed in startNavigating()");
                    return;
                }
            }
            if (!native_start()) {
                mStarted = false;
                Log.e(TAG, "native_start failed in startNavigating()");
                return;
            }

            // reset SV count to zero
            updateStatus(LocationProvider.TEMPORARILY_UNAVAILABLE, 0);
            mFixRequestTime = System.currentTimeMillis();
            if (!hasCapability(GPS_CAPABILITY_SCHEDULING)) {
                // set timer to give up if we do not receive a fix within NO_FIX_TIMEOUT
                // and our fix interval is not short
                if (mFixInterval >= NO_FIX_TIMEOUT) {
                    mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + NO_FIX_TIMEOUT, mTimeoutIntent);
                }
            }
        }
    }

    private void stopNavigating() {
        if (DEBUG) Log.d(TAG, "stopNavigating");
        if (mStarted) {
            mStarted = false;
            mSingleShot = false;
            native_stop();
            mTTFF = 0;
            mLastFixTime = 0;
            mLocationFlags = LOCATION_INVALID;

            // reset SV count to zero
            updateStatus(LocationProvider.TEMPORARILY_UNAVAILABLE, 0);
        }
    }

    private void hibernate() {
        // stop GPS until our next fix interval arrives
        stopNavigating();
        mAlarmManager.cancel(mTimeoutIntent);
        mAlarmManager.cancel(mWakeupIntent);
        long now = SystemClock.elapsedRealtime();
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + mFixInterval, mWakeupIntent);
    }

    private boolean hasCapability(int capability) {
        return ((mEngineCapabilities & capability) != 0);
    }

    /**
     * called from native code to update our position.
     */
    private void reportLocation(int flags, double latitude, double longitude, double altitude,
                                float speed, float bearing, float accuracy, long timestamp,
                                int positionSource, byte[] rawData) {
        if (VERBOSE) Log.v(TAG, "reportLocation lat: " + latitude + " long: " + longitude +
                " timestamp: " + timestamp);

        synchronized (mLocation) {
            mLocationFlags = flags;
            if ((flags & LOCATION_HAS_LAT_LONG) == LOCATION_HAS_LAT_LONG) {
                mLocation.setLatitude(latitude);
                mLocation.setLongitude(longitude);
                mLocation.setTime(timestamp);
            }
            if ((flags & LOCATION_HAS_ALTITUDE) == LOCATION_HAS_ALTITUDE) {
                mLocation.setAltitude(altitude);
            } else {
                mLocation.removeAltitude();
            }
            if ((flags & LOCATION_HAS_SPEED) == LOCATION_HAS_SPEED) {
                mLocation.setSpeed(speed);
            } else {
                mLocation.removeSpeed();
            }
            if ((flags & LOCATION_HAS_BEARING) == LOCATION_HAS_BEARING) {
                mLocation.setBearing(bearing);
            } else {
                mLocation.removeBearing();
            }
            if ((flags & LOCATION_HAS_ACCURACY) == LOCATION_HAS_ACCURACY) {
                mLocation.setAccuracy(accuracy);
            } else {
                mLocation.removeAccuracy();
            }
            Log.d(TAG, "reportLocation.flag:" +flags);
            if((flags & LOCATION_HAS_SOURCE_INFO) == LOCATION_HAS_SOURCE_INFO)  {

                if(positionSource == ULP_LOCATION_IS_FROM_HYBRID) {
                    Log.d(TAG, "reportLocation. Location has source information. src -hybrid");
                    mLocationExtras.putBoolean("ProviderSourceIsUlp",true);
                } else {
                    mLocationExtras.remove("ProviderSourceIsUlp");
                }
                if(positionSource == ULP_LOCATION_IS_FROM_GNSS ) {
                    Log.d(TAG, "reportLocation. Location has source information. src -gnss");
                    mLocationExtras.putBoolean("ProviderSourceIsGnss",true);
                } else {
                    mLocationExtras.remove("ProviderSourceIsGnss");
                }
            } else {
                    mLocationExtras.remove("ProviderSourceIsUlp");
                    mLocationExtras.remove("ProviderSourceIsGnss");
            }

            if (rawData.length > 0) {
                 mLocationExtras.putByteArray("RawData", rawData);
            } else {
                mLocationExtras.remove("RawData");
            }
            mLocation.setExtras(mLocationExtras);

            try {
                mLocationManager.reportLocation(mLocation, false);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException calling reportLocation");
            }
        }

        mLastFixTime = System.currentTimeMillis();
        // report time to first fix
        if (mTTFF == 0 && (flags & LOCATION_HAS_LAT_LONG) == LOCATION_HAS_LAT_LONG) {
            mTTFF = (int)(mLastFixTime - mFixRequestTime);
            if (DEBUG) Log.d(TAG, "TTFF: " + mTTFF);

            // notify status listeners
            synchronized(mListeners) {
                int size = mListeners.size();
                for (int i = 0; i < size; i++) {
                    Listener listener = mListeners.get(i);
                    try {
                        listener.mListener.onFirstFix(mTTFF);
                    } catch (RemoteException e) {
                        Log.w(TAG, "RemoteException in stopNavigating");
                        mListeners.remove(listener);
                        // adjust for size of list changing
                        size--;
                    }
                }
            }
        }

        if (mSingleShot) {
            stopNavigating();
        }
        if (mStarted && mStatus != LocationProvider.AVAILABLE) {
            // we want to time out if we do not receive a fix
            // within the time out and we are requesting infrequent fixes
            if (!hasCapability(GPS_CAPABILITY_SCHEDULING) && mFixInterval < NO_FIX_TIMEOUT) {
                mAlarmManager.cancel(mTimeoutIntent);
            }

            // send an intent to notify that the GPS is receiving fixes.
            Intent intent = new Intent(LocationManager.GPS_FIX_CHANGE_ACTION);
            intent.putExtra(LocationManager.EXTRA_GPS_ENABLED, true);
            mContext.sendBroadcast(intent);
            updateStatus(LocationProvider.AVAILABLE, mSvCount);
        }

       if (!hasCapability(GPS_CAPABILITY_SCHEDULING) && mStarted && mFixInterval > 1000) {
            if (DEBUG) Log.d(TAG, "got fix, hibernating");
            hibernate();
        }
   }

    /**
     * called from native code to update our status
     */
    private void reportStatus(int status) {
        if (DEBUG) Log.v(TAG, "reportStatus status: " + status);

        synchronized(mListeners) {
            boolean wasNavigating = mNavigating;

            switch (status) {
                case GPS_STATUS_SESSION_BEGIN:
                    mNavigating = true;
                    mEngineOn = true;
                    break;
                case GPS_STATUS_SESSION_END:
                    mNavigating = false;
                    break;
                case GPS_STATUS_ENGINE_ON:
                    mEngineOn = true;
                    break;
                case GPS_STATUS_ENGINE_OFF:
                    mEngineOn = false;
                    mNavigating = false;
                    break;
            }

            if (wasNavigating != mNavigating) {
                int size = mListeners.size();
                for (int i = 0; i < size; i++) {
                    Listener listener = mListeners.get(i);
                    try {
                        if (mNavigating) {
                            listener.mListener.onGpsStarted();
                        } else {
                            listener.mListener.onGpsStopped();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "RemoteException in reportStatus");
                        mListeners.remove(listener);
                        // adjust for size of list changing
                        size--;
                    }
                }

                try {
                    // update battery stats
                    for (int i=mClientUids.size() - 1; i >= 0; i--) {
                        int uid = mClientUids.keyAt(i);
                        if (mNavigating) {
                            mBatteryStats.noteStartGps(uid);
                        } else {
                            mBatteryStats.noteStopGps(uid);
                        }
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "RemoteException in reportStatus");
                }

                // send an intent to notify that the GPS has been enabled or disabled.
                Intent intent = new Intent(LocationManager.GPS_ENABLED_CHANGE_ACTION);
                intent.putExtra(LocationManager.EXTRA_GPS_ENABLED, mNavigating);
                mContext.sendBroadcast(intent);
            }
        }
    }

    /**
     * called from native code to update SV info
     */
    private void reportSvStatus() {

        int svCount = native_read_sv_status(mSvs, mSnrs, mSvElevations, mSvAzimuths, mSvMasks);

        synchronized(mListeners) {
            int size = mListeners.size();
            for (int i = 0; i < size; i++) {
                Listener listener = mListeners.get(i);
                try {
                    listener.mListener.onSvStatusChanged(svCount, mSvs, mSnrs,
                            mSvElevations, mSvAzimuths, mSvMasks[EPHEMERIS_MASK],
                            mSvMasks[ALMANAC_MASK], mSvMasks[USED_FOR_FIX_MASK]);
                } catch (RemoteException e) {
                    Log.w(TAG, "RemoteException in reportSvInfo");
                    mListeners.remove(listener);
                    // adjust for size of list changing
                    size--;
                }
            }
        }

        if (VERBOSE) {
            Log.v(TAG, "SV count: " + svCount +
                    " ephemerisMask: " + Integer.toHexString(mSvMasks[EPHEMERIS_MASK]) +
                    " almanacMask: " + Integer.toHexString(mSvMasks[ALMANAC_MASK]));
            for (int i = 0; i < svCount; i++) {
                Log.v(TAG, "sv: " + mSvs[i] +
                        " snr: " + (float)mSnrs[i]/10 +
                        " elev: " + mSvElevations[i] +
                        " azimuth: " + mSvAzimuths[i] +
                        ((mSvMasks[EPHEMERIS_MASK] & (1 << (mSvs[i] - 1))) == 0 ? "  " : " E") +
                        ((mSvMasks[ALMANAC_MASK] & (1 << (mSvs[i] - 1))) == 0 ? "  " : " A") +
                        ((mSvMasks[USED_FOR_FIX_MASK] & (1 << (mSvs[i] - 1))) == 0 ? "" : "U"));
            }
        }

        // return number of sets used in fix instead of total
        updateStatus(mStatus, Integer.bitCount(mSvMasks[USED_FOR_FIX_MASK]));

        if (mNavigating && mStatus == LocationProvider.AVAILABLE && mLastFixTime > 0 &&
            System.currentTimeMillis() - mLastFixTime > RECENT_FIX_TIMEOUT) {
            // send an intent to notify that the GPS is no longer receiving fixes.
            Intent intent = new Intent(LocationManager.GPS_FIX_CHANGE_ACTION);
            intent.putExtra(LocationManager.EXTRA_GPS_ENABLED, false);
            mContext.sendBroadcast(intent);
            updateStatus(LocationProvider.TEMPORARILY_UNAVAILABLE, mSvCount);
        }
    }

    /**
     * called from native code to update AGPS status
     */
    private void reportAGpsStatus(int type, int status, int ipv4_addr, byte[] ipv6_addr) {
        AGpsConnectionInfo agpsConnInfo = getAGpsConnectionInfo(type);
        if (agpsConnInfo == null) {
            if (DEBUG) Log.d(TAG, "reportAGpsStatus agpsConnInfo is null for type "+type);
            // we do not handle this type of connection
            return;
        }

        switch (status) {
            case GPS_REQUEST_AGPS_DATA_CONN:
                if (DEBUG) Log.d(TAG, "GPS_REQUEST_AGPS_DATA_CONN");
                // Set agpsConnInfo.mState before calling startUsingNetworkFeature
                //  to avoid a race condition with handleUpdateNetworkState()
                agpsConnInfo.mState = AGpsConnectionInfo.STATE_OPENING;
                int result = mConnMgr.startUsingNetworkFeature(
                        ConnectivityManager.TYPE_MOBILE, agpsConnInfo.mPHConnFeatureStr);
                if ( ipv4_addr != 0xffffffff) {
                    agpsConnInfo.mIPv4Addr = NetworkUtils.intToInetAddress(ipv4_addr);
                } else {
                    // Don't handle ipv6 for now
                    agpsConnInfo.mIPv4Addr = null;
                }

                if (result == Phone.APN_ALREADY_ACTIVE) {
                    if (DEBUG) Log.d(TAG, "Phone.APN_ALREADY_ACTIVE");
                    if (agpsConnInfo.mAPN != null) {
                        if (agpsConnInfo.mIPv4Addr != null) {
                            boolean route_result;
                            if (DEBUG)
                                Log.d(TAG, "agpsConnInfo.mIPv4Addr " + agpsConnInfo.mIPv4Addr.toString());
                            route_result = mConnMgr.requestRouteToHostAddress(
                                agpsConnInfo.mCMConnType,
                                agpsConnInfo.mIPv4Addr);
                            if (route_result == false) Log.d(TAG, "call requestRouteToHostAddress failed");
                        }
                        native_agps_data_conn_open(agpsConnInfo.mAgpsType, agpsConnInfo.mAPN, agpsConnInfo.mBearerType);
                        agpsConnInfo.mState = AGpsConnectionInfo.STATE_OPEN;
                    } else {
                        Log.e(TAG, "agpsConnInfo.mAPN not set when receiving Phone.APN_ALREADY_ACTIVE");
                        agpsConnInfo.mState = AGpsConnectionInfo.STATE_CLOSED;
                        native_agps_data_conn_failed(agpsConnInfo.mAgpsType);
                    }
                } else if (result == Phone.APN_REQUEST_STARTED) {
                    if (DEBUG) Log.d(TAG, "Phone.APN_REQUEST_STARTED");
                    // Nothing to do here
                } else {
                    if (DEBUG) Log.d(TAG, "startUsingNetworkFeature failed with "+result);
                    agpsConnInfo.mState = AGpsConnectionInfo.STATE_CLOSED;
                    native_agps_data_conn_failed(agpsConnInfo.mAgpsType);
                }
                break;
            case GPS_RELEASE_AGPS_DATA_CONN:
                if (DEBUG) Log.d(TAG, "GPS_RELEASE_AGPS_DATA_CONN");
                if (agpsConnInfo.mState != AGpsConnectionInfo.STATE_CLOSED) {
                    mConnMgr.stopUsingNetworkFeature(
                            ConnectivityManager.TYPE_MOBILE, agpsConnInfo.mPHConnFeatureStr);
                    native_agps_data_conn_closed(agpsConnInfo.mAgpsType);
                    agpsConnInfo.mState = AGpsConnectionInfo.STATE_CLOSED;
                }
                break;
            case GPS_AGPS_DATA_CONNECTED:
                if (DEBUG) Log.d(TAG, "GPS_AGPS_DATA_CONNECTED");
                break;
            case GPS_AGPS_DATA_CONN_DONE:
                if (DEBUG) Log.d(TAG, "GPS_AGPS_DATA_CONN_DONE");
                break;
            case GPS_AGPS_DATA_CONN_FAILED:
                if (DEBUG) Log.d(TAG, "GPS_AGPS_DATA_CONN_FAILED");
                break;
        }
    }

    /**
     * called from native code to report NMEA data received
     */
    private void reportNmea(long timestamp) {
        synchronized(mListeners) {
            int size = mListeners.size();
            if (size > 0) {
                // don't bother creating the String if we have no listeners
                int length = native_read_nmea(mNmeaBuffer, mNmeaBuffer.length);
                String nmea = new String(mNmeaBuffer, 0, length);

                for (int i = 0; i < size; i++) {
                    Listener listener = mListeners.get(i);
                    try {
                        listener.mListener.onNmeaReceived(timestamp, nmea);
                    } catch (RemoteException e) {
                        Log.w(TAG, "RemoteException in reportNmea");
                        mListeners.remove(listener);
                        // adjust for size of list changing
                        size--;
                    }
                }
            }
        }
    }

    /**
     * called from native code to inform us what the GPS engine capabilities are
     */
    private void setEngineCapabilities(int capabilities) {
        if (DEBUG) Log.d(TAG, "setEngineCapabilities " + capabilities );
        mEngineCapabilities = capabilities;

        if (!hasCapability(GPS_CAPABILITY_ON_DEMAND_TIME) && !mPeriodicTimeInjection) {
            mPeriodicTimeInjection = true;
            requestUtcTime();
        }
    }

    /**
     * called from native code to request XTRA data
     */
    private void xtraDownloadRequest() {
        if (DEBUG) Log.d(TAG, "xtraDownloadRequest");
        sendMessage(DOWNLOAD_XTRA_DATA, 0, null);
    }

    //=============================================================
    // NI Client support
    //=============================================================
    private final INetInitiatedListener mNetInitiatedListener = new INetInitiatedListener.Stub() {
        // Sends a response for an NI reqeust to HAL.
        public boolean sendNiResponse(int notificationId, int userResponse)
        {
            // TODO Add Permission check

            StringBuilder extrasBuf = new StringBuilder();

            if (DEBUG) Log.d(TAG, "sendNiResponse, notifId: " + notificationId +
                    ", response: " + userResponse);
            native_send_ni_response(notificationId, userResponse);
            return true;
        }
    };

    public INetInitiatedListener getNetInitiatedListener() {
        return mNetInitiatedListener;
    }

    // Called by JNI function to report an NI request.
    public void reportNiNotification(
            int notificationId,
            int niType,
            int notifyFlags,
            int timeout,
            int defaultResponse,
            String requestorId,
            String text,
            int requestorIdEncoding,
            int textEncoding,
            String extras  // Encoded extra data
        )
    {
        Log.i(TAG, "reportNiNotification: entered");
        Log.i(TAG, "notificationId: " + notificationId +
                ", niType: " + niType +
                ", notifyFlags: " + notifyFlags +
                ", timeout: " + timeout +
                ", defaultResponse: " + defaultResponse);

        Log.i(TAG, "requestorId: " + requestorId +
                ", text: " + text +
                ", requestorIdEncoding: " + requestorIdEncoding +
                ", textEncoding: " + textEncoding +
                ", extras: " + extras);

        GpsNiNotification notification = new GpsNiNotification();

        notification.notificationId = notificationId;
        notification.niType = niType;
        notification.needNotify = (notifyFlags & GpsNetInitiatedHandler.GPS_NI_NEED_NOTIFY) != 0;
        notification.needVerify = (notifyFlags & GpsNetInitiatedHandler.GPS_NI_NEED_VERIFY) != 0;
        notification.privacyOverride = (notifyFlags & GpsNetInitiatedHandler.GPS_NI_PRIVACY_OVERRIDE) != 0;
        notification.timeout = timeout;
        notification.defaultResponse = defaultResponse;
        notification.requestorId = requestorId;
        notification.text = text;
        notification.requestorIdEncoding = requestorIdEncoding;
        notification.textEncoding = textEncoding;

        // Process extras, assuming the format is
        // one of more lines of "key = value"
        Bundle bundle = new Bundle();

        if (extras == null) extras = "";
        Properties extraProp = new Properties();

        try {
            extraProp.load(new StringReader(extras));
        }
        catch (IOException e)
        {
            Log.e(TAG, "reportNiNotification cannot parse extras data: " + extras);
        }

        for (Entry<Object, Object> ent : extraProp.entrySet())
        {
            bundle.putString((String) ent.getKey(), (String) ent.getValue());
        }

        notification.extras = bundle;

        mNIHandler.handleNiNotification(notification);
    }

    /**
     * Called from native code to request set id info.
     * We should be careful about receiving null string from the TelephonyManager,
     * because sending null String to JNI function would cause a crash.
     */

    private void requestSetID(int flags) {
        TelephonyManager phone = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        int    type = AGPS_SETID_TYPE_NONE;
        String data = "";

        if ((flags & AGPS_RIL_REQUEST_SETID_IMSI) == AGPS_RIL_REQUEST_SETID_IMSI) {
            String data_temp = phone.getSubscriberId();
            if (data_temp == null) {
                // This means the framework does not have the SIM card ready.
            } else {
                // This means the framework has the SIM card.
                data = data_temp;
                type = AGPS_SETID_TYPE_IMSI;
            }
        }
        else if ((flags & AGPS_RIL_REQUEST_SETID_MSISDN) == AGPS_RIL_REQUEST_SETID_MSISDN) {
            String data_temp = phone.getLine1Number();
            if (data_temp == null) {
                // This means the framework does not have the SIM card ready.
            } else {
                // This means the framework has the SIM card.
                data = data_temp;
                type = AGPS_SETID_TYPE_MSISDN;
            }
        }
        native_agps_set_id(type, data);
    }

    private void handleNetworkLocationUpdate(Location location) {
         Log.d(TAG, "handleNetworkLocationUpdate. lat" + location.getLatitude()+ "lon" +
            location.getLongitude() + "accurancy " + location.getAccuracy());
            if (location.hasAccuracy()) {
            native_send_network_location(location.getLatitude(), location.getLongitude(),
                    location.getAccuracy());
            }
    }

    /*
     * ULP request for network location info
     *
     */
    private LocationListener mNetworkLocationListener = new LocationListener() {
        public void onLocationChanged(Location location) {
              Log.d(TAG, "onLocationChanged for NLP lat" + location.getLatitude()+ "lon" +
              location.getLongitude() + "accurancy " + location.getAccuracy());
              synchronized (mHandler) {
              mHandler.removeMessages(UPDATE_NETWORK_LOCATION);
              Message m = Message.obtain(mHandler, UPDATE_NETWORK_LOCATION);
              m.arg1 = 0;
              m.obj = location;
              mHandler.sendMessage(m);
            }
        }
        public void onStatusChanged(String arg0, int arg1, Bundle arg2) {
              Log.d(TAG, "Status update for NLP" + arg0);
            }
        public void onProviderEnabled(String arg0) {
            Log.d(TAG, "onProviderEnabled for NLP.state " + arg0);
            }
        public void onProviderDisabled(String arg0) {
            Log.d(TAG, "onProviderEnabled for NLP.state " + arg0);
            }
    };
    /**
     * Called from native code to request utc time info
     */

    private void requestUtcTime() {
        sendMessage(INJECT_NTP_TIME, 0, null);
    }

    /*
     * Called from native code to request phone context info
     */
    private void requestPhoneContext (int context_type, int request_type)
    {
        if (DEBUG)
            Log.d(TAG, "requestPhoneContext from native layer.context_type: "+
                  context_type+ " request_type:"+ request_type);
        synchronized (mWakeLock) {
            mWakeLock.acquire();
            mHandler.removeMessages(REQUEST_PHONE_CONTEXT_SETTINGS);
            Message m = Message.obtain(mHandler, REQUEST_PHONE_CONTEXT_SETTINGS);
            m.arg1 = context_type;
            m.arg2 = request_type;
            mHandler.sendMessage(m);
        }
    }
    /**
     * Called from native code to request network location info
     */
    private void requestNetworkLocation(int type, int interval, int source)
    {
         if (DEBUG) Log.d(TAG, "requestNetworkLocation. type: "+ type+ "interval: "+ interval+"source "+source);
         synchronized (mWakeLock) {
            mWakeLock.acquire();
            mHandler.removeMessages(REQUEST_NETWORK_LOCATION);
            Message m = Message.obtain(mHandler, REQUEST_NETWORK_LOCATION);
            m.arg1 = type;
            m.arg2 = interval;
            mHandler.sendMessage(m);
        }
    }
    /**
     * Called from native code to request reference location info
     */

    private void requestRefLocation(int flags) {
        TelephonyManager phone = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (phone.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
            GsmCellLocation gsm_cell = (GsmCellLocation) phone.getCellLocation();
            if ((gsm_cell != null) && (phone.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) &&
                    (phone.getNetworkOperator() != null) &&
                        (phone.getNetworkOperator().length() > 3)) {
                int type;
                int mcc = Integer.parseInt(phone.getNetworkOperator().substring(0,3));
                int mnc = Integer.parseInt(phone.getNetworkOperator().substring(3));
                int networkType = phone.getNetworkType();
                if (networkType == TelephonyManager.NETWORK_TYPE_UMTS
                    || networkType == TelephonyManager.NETWORK_TYPE_HSDPA
                    || networkType == TelephonyManager.NETWORK_TYPE_HSUPA
                    || networkType == TelephonyManager.NETWORK_TYPE_HSPA) {
                    type = AGPS_REF_LOCATION_TYPE_UMTS_CELLID;
                } else {
                    type = AGPS_REF_LOCATION_TYPE_GSM_CELLID;
                }
                native_agps_set_ref_location_cellid(type, mcc, mnc,
                        gsm_cell.getLac(), gsm_cell.getCid());
            } else {
                Log.e(TAG,"Error getting cell location info.");
            }
        }
        else {
            Log.e(TAG,"CDMA not supported.");
        }
    }

    private void sendMessage(int message, int arg, Object obj) {
        // hold a wake lock while messages are pending
        synchronized (mWakeLock) {
            mPendingMessageBits |= (1 << message);
            mWakeLock.acquire();
            mHandler.removeMessages(message);
            Message m = Message.obtain(mHandler, message);
            m.arg1 = arg;
            m.obj = obj;
            mHandler.sendMessage(m);
        }
    }

    private final class ProviderHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            int message = msg.what;
            Log.d(TAG, "Gps MessageHandler. msg.what " + message);
            switch (message) {
                case ENABLE:
                    if (msg.arg1 == 1) {
                        handleEnable();
                    } else {
                        handleDisable();
                    }
                    break;
                case ENABLE_TRACKING:
                    handleEnableLocationTracking(msg.arg1 == 1);
                    break;
                case REQUEST_SINGLE_SHOT:
                    handleRequestSingleShot();
                    break;
                case UPDATE_NETWORK_STATE:
                    handleUpdateNetworkState(msg.arg1, (NetworkInfo)msg.obj);
                    break;
                case INJECT_NTP_TIME:
                    handleInjectNtpTime();
                    break;
                case DOWNLOAD_XTRA_DATA:
                    if (mSupportsXtra) {
                        handleDownloadXtraData();
                    }
                    break;
                case UPDATE_LOCATION:
                    handleUpdateLocation((Location)msg.obj);
                    break;
                case ADD_LISTENER:
                    handleAddListener(msg.arg1);
                    break;
                case REMOVE_LISTENER:
                    handleRemoveListener(msg.arg1);
                    break;
                case REQUEST_PHONE_CONTEXT_SETTINGS:
                    handleNativePhoneContextRequest(msg.arg1, msg.arg2);
                    break;
                case UPDATE_NATIVE_PHONE_CONTEXT_SETTINGS:
                    handleNativePhoneContextUpdate(msg.arg1, msg.getData());
                    break;
                case REQUEST_NETWORK_LOCATION:
                    handleNativeNetworkLocationRequest(msg.arg1, msg.arg2);
                    break;
                case UPDATE_NETWORK_LOCATION:
                    handleNetworkLocationUpdate((Location)msg.obj);
                    break;

            }
            // release wake lock if no messages are pending
            synchronized (mWakeLock) {
                mPendingMessageBits &= ~(1 << message);
                if (message == ADD_LISTENER || message == REMOVE_LISTENER) {
                    mPendingListenerMessages--;
                }
                if (mPendingMessageBits == 0 && mPendingListenerMessages == 0) {
                    mWakeLock.release();
                }
            }
        }
    };

    private final class GpsLocationProviderThread extends Thread {

        public GpsLocationProviderThread() {
            super("GpsLocationProvider");
        }

        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            initialize();
            Looper.prepare();
            mHandler = new ProviderHandler();
            // signal when we are initialized and ready to go
            mInitializedLatch.countDown();
            Looper.loop();
        }
    }

    private String getSelectedApn() {
        Uri uri = Uri.parse("content://telephony/carriers/preferapn");
        String apn = null;

        Cursor cursor = mContext.getContentResolver().query(uri, new String[] {"apn"},
                null, null, Carriers.DEFAULT_SORT_ORDER);

        if (null != cursor) {
            try {
                if (cursor.moveToFirst()) {
                    apn = cursor.getString(0);
                }
            } finally {
                cursor.close();
            }
        }
        return apn;
    }

    // for GPS SV statistics
    private static final int MAX_SVS = 32;
    private static final int EPHEMERIS_MASK = 0;
    private static final int ALMANAC_MASK = 1;
    private static final int USED_FOR_FIX_MASK = 2;

    // preallocated arrays, to avoid memory allocation in reportStatus()
    private int mSvs[] = new int[MAX_SVS];
    private float mSnrs[] = new float[MAX_SVS];
    private float mSvElevations[] = new float[MAX_SVS];
    private float mSvAzimuths[] = new float[MAX_SVS];
    private int mSvMasks[] = new int[3];
    private int mSvCount;
    // preallocated to avoid memory allocation in reportNmea()
    private byte[] mNmeaBuffer = new byte[120];

    static { class_init_native(); }
    private static native void class_init_native();
    private static native boolean native_is_supported();

    private native boolean native_init();
    private native void native_cleanup();
    private native boolean native_set_position_mode(int mode, int recurrence, int min_interval,
            int preferred_accuracy, int preferred_time);
    private native boolean native_update_criteria(int action, long minTime, float minDistance,
                                                  boolean singleShot, int horizontalAccuracy,
                                                  int powerRequirement);
    private native boolean native_update_settings(int currentContextType, boolean currentGpsSetting, boolean currentAgpsSetting,
                           boolean currentNetworkProvSetting,boolean currentWifiSetting );

    private native boolean native_start();
    private native boolean native_stop();
    private native void native_delete_aiding_data(int flags);
    // returns number of SVs
    // mask[0] is ephemeris mask and mask[1] is almanac mask
    private native int native_read_sv_status(int[] svs, float[] snrs,
            float[] elevations, float[] azimuths, int[] masks);
    private native int native_read_nmea(byte[] buffer, int bufferSize);
    private native void native_inject_location(double latitude, double longitude, float accuracy);
    private native void native_send_network_location(double latitude, double longitude, float accuracy);

    // XTRA Support
    private native void native_inject_time(long time, long timeReference, int uncertainty);
    private native boolean native_supports_xtra();
    private native void native_inject_xtra_data(byte[] data, int length);

    // Special Test Command Path
    private native boolean native_inject_raw_command(byte[] data, int length);

    // DEBUG Support
    private native String native_get_internal_state();

    // AGPS Support
    private native void native_agps_data_conn_open(int agpsType, String apn, int bearerType);
    private native void native_agps_data_conn_closed(int agpsType);
    private native void native_agps_data_conn_failed(int agpsType);
    private native void native_agps_ni_message(byte [] msg, int length);
    private native void native_set_agps_server(int type, String hostname, int port);

    // Network-initiated (NI) Support
    private native void native_send_ni_response(int notificationId, int userResponse);

    // AGPS ril suport
    private native void native_agps_set_ref_location_cellid(int type, int mcc, int mnc,
            int lac, int cid);
    private native void native_agps_set_id(int type, String setid);

    private native void native_update_network_state(boolean connected, int type,
            boolean roaming, boolean available, String extraInfo, String defaultAPN);

    private static AGpsConnectionInfo[] mAGpsConnections = new AGpsConnectionInfo[2];
    private AGpsConnectionInfo getAGpsConnectionInfo(int connType) {
        if (DEBUG) Log.d(TAG, "getAGpsConnectionInfo connType - "+connType);
        switch (connType)
        {
        case AGpsConnectionInfo.CONNECTION_TYPE_WWAN_ANY:
        case AGpsConnectionInfo.CONNECTION_TYPE_C2K:
            if (null == mAGpsConnections[0])
                mAGpsConnections[0] = new AGpsConnectionInfo(ConnectivityManager.TYPE_MOBILE, connType);
            return mAGpsConnections[0];
        case AGpsConnectionInfo.CONNECTION_TYPE_SUPL:
            if (null == mAGpsConnections[1])
                mAGpsConnections[1] = new AGpsConnectionInfo(ConnectivityManager.TYPE_MOBILE_SUPL, connType);
            return mAGpsConnections[1];
        default:
            return null;
        }
    }

    private class AGpsConnectionInfo {
        // these need to match AGpsType enum in gps.h
        private static final int CONNECTION_TYPE_ANY = 0;
        private static final int CONNECTION_TYPE_SUPL = 1;
        private static final int CONNECTION_TYPE_C2K = 2;
        private static final int CONNECTION_TYPE_WWAN_ANY = 3;

        // this must match the definition of gps.h
        private static final int BEARER_INVALID = -1;
        private static final int BEARER_IPV4 = 0;
        private static final int BEARER_IPV6 = 1;
        private static final int BEARER_IPV4V6 = 2;

        // for mState
        private static final int STATE_CLOSED = 0;
        private static final int STATE_OPENING = 1;
        private static final int STATE_OPEN = 2;

        // SUPL vs ANY (which really is non-SUPL)
        private final int mCMConnType;
        private final int mAgpsType;
        private final String mPHConnFeatureStr;
        private String mAPN;
        private int mIPvVerType;
        private int mState;
        private InetAddress mIPv4Addr;
        private InetAddress mIPv6Addr;
        private int mBearerType;

        private AGpsConnectionInfo(int connMgrConnType, int agpsType) {
            mCMConnType = connMgrConnType;
            mAgpsType = agpsType;
            if (ConnectivityManager.TYPE_MOBILE_SUPL == connMgrConnType) {
                mPHConnFeatureStr = Phone.FEATURE_ENABLE_SUPL;
            } else {
                mPHConnFeatureStr = Phone.FEATURE_ENABLE_MMS;
            }
            mAPN = null;
            mState = STATE_CLOSED;
            mIPv4Addr = null;
            mIPv6Addr = null;
            mBearerType = BEARER_INVALID;
        }
    }
}
