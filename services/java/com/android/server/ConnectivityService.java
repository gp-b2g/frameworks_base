/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2010-2012 Code Aurora Forum. All rights reserved.
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

package com.android.server;

import static android.Manifest.permission.MANAGE_NETWORK_POLICY;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE;
import static android.net.ConnectivityManager.isNetworkTypeValid;
import static android.net.NetworkPolicyManager.RULE_ALLOW_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_METERED;

import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.TYPE_MOBILE;

import android.bluetooth.BluetoothTetheringDataTracker;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.FmcNotifier;
import android.net.DummyDataStateTracker;
import android.net.EthernetDataTracker;
import android.net.IConnectivityManager;
import android.net.LinkCapabilities;
import android.net.ExtraLinkCapabilities;
import android.net.IFmcEventListener;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LinkProperties.CompareResult;
import android.net.MobileDataStateTracker;
import android.net.NetworkConfig;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkQuotaInfo;
import android.net.NetworkState;
import android.net.NetworkStateTracker;
import android.net.NetworkUtils;
import android.net.Proxy;
import android.net.ProxyProperties;
import android.net.RouteInfo;
import android.net.wifi.WifiStateTracker;
import android.net.wimax.WimaxManagerConstants;
import android.os.Binder;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseIntArray;

import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.telephony.Phone;
import com.android.internal.util.StateMachine;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.server.am.BatteryStatsService;
import com.android.server.connectivity.Tethering;
import com.android.server.connectivity.Vpn;

import com.google.android.collect.Lists;
import com.google.android.collect.Sets;
import dalvik.system.DexClassLoader;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dalvik.system.PathClassLoader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * @hide
 */
public class ConnectivityService extends IConnectivityManager.Stub {

    private static final boolean DBG = true;
    private static final boolean VDBG = true;
    private static final String TAG = "ConnectivityService";

    private static final boolean LOGD_RULES = false;
    //invalid arg to handler
    private static final int INVALID_MSG_ARG = -1;

    // how long to wait before switching back to a radio's default network
    private static final int RESTORE_DEFAULT_NETWORK_DELAY = 1 * 60 * 1000;
    // system property that can override the above value
    private static final String NETWORK_RESTORE_DELAY_PROP_NAME =
            "android.telephony.apn-restore";

    // used in recursive route setting to add gateways for the host for which
    // a host route was requested.
    private static final int MAX_HOSTROUTE_CYCLE_COUNT = 10;

    private Tethering mTethering;
    private boolean mTetheringConfigValid = false;

    private Vpn mVpn;

    /** Lock around {@link #mUidRules} and {@link #mMeteredIfaces}. */
    private Object mRulesLock = new Object();
    /** Currently active network rules by UID. */
    private SparseIntArray mUidRules = new SparseIntArray();
    /** Set of ifaces that are costly. */
    private HashSet<String> mMeteredIfaces = Sets.newHashSet();

    /**
     * Sometimes we want to refer to the individual network state
     * trackers separately, and sometimes we just want to treat them
     * abstractly.
     */
    private NetworkStateTracker mNetTrackers[];

    /**
     * The link properties that define the current links
     */
    private LinkProperties mCurrentLinkProperties[];

    /**
     * A per Net list of the PID's that requested access to the net
     * used both as a refcount and for per-PID DNS selection
     */
    private List mNetRequestersPids[];

    // priority order of the nettrackers
    // (excluding dynamically set mNetworkPreference)
    // TODO - move mNetworkTypePreference into this
    private int[] mPriorityList;

    private Context mContext;
    private int mNetworkPreference;
    private int mActiveDefaultNetwork = -1;
    // 0 is full bad, 100 is full good
    private int mDefaultInetCondition = 0;
    private int mDefaultInetConditionPublished = 0;
    private boolean mInetConditionChangeInFlight = false;
    private int mDefaultConnectionSequence = 0;

    private Object mDnsLock = new Object();
    private int mNumDnsEntries;
    private boolean mDnsOverridden = false;
    private int mRouteIdCtr = 0;

    private boolean mTestMode;
    private static ConnectivityService sServiceInstance;

    private INetworkManagementService mNetd;
    private INetworkPolicyManager mPolicyManager;

    private static final int ENABLED  = 1;
    private static final int DISABLED = 0;

    private static final boolean ADD = true;
    private static final boolean REMOVE = false;

    private static final boolean TO_DEFAULT_TABLE = true;
    private static final boolean TO_SECONDARY_TABLE = false;

    // Share the event space with NetworkStateTracker (which can't see this
    // internal class but sends us events).  If you change these, change
    // NetworkStateTracker.java too.
    private static final int MIN_NETWORK_STATE_TRACKER_EVENT = 1;
    private static final int MAX_NETWORK_STATE_TRACKER_EVENT = 100;

    /**
     * used internally as a delayed event to make us switch back to the
     * default network
     */
    private static final int EVENT_RESTORE_DEFAULT_NETWORK =
            MAX_NETWORK_STATE_TRACKER_EVENT + 1;

    /**
     * used internally to change our mobile data enabled flag
     */
    private static final int EVENT_CHANGE_MOBILE_DATA_ENABLED =
            MAX_NETWORK_STATE_TRACKER_EVENT + 2;

    /**
     * used internally to change our network preference setting
     * arg1 = networkType to prefer
     */
    private static final int EVENT_SET_NETWORK_PREFERENCE =
            MAX_NETWORK_STATE_TRACKER_EVENT + 3;

    /**
     * used internally to synchronize inet condition reports
     * arg1 = networkType
     * arg2 = condition (0 bad, 100 good)
     */
    private static final int EVENT_INET_CONDITION_CHANGE =
            MAX_NETWORK_STATE_TRACKER_EVENT + 4;

    /**
     * used internally to mark the end of inet condition hold periods
     * arg1 = networkType
     */
    private static final int EVENT_INET_CONDITION_HOLD_END =
            MAX_NETWORK_STATE_TRACKER_EVENT + 5;

    /**
     * used internally to set enable/disable cellular data
     * arg1 = ENBALED or DISABLED
     */
    private static final int EVENT_SET_MOBILE_DATA =
            MAX_NETWORK_STATE_TRACKER_EVENT + 7;

    /**
     * used internally to clear a wakelock when transitioning
     * from one net to another
     */
    private static final int EVENT_CLEAR_NET_TRANSITION_WAKELOCK =
            MAX_NETWORK_STATE_TRACKER_EVENT + 8;

    /**
     * used internally to reload global proxy settings
     */
    private static final int EVENT_APPLY_GLOBAL_HTTP_PROXY =
            MAX_NETWORK_STATE_TRACKER_EVENT + 9;

    /**
     * used internally to set external dependency met/unmet
     * arg1 = ENABLED (met) or DISABLED (unmet)
     * arg2 = NetworkType
     */
    private static final int EVENT_SET_DEPENDENCY_MET =
            MAX_NETWORK_STATE_TRACKER_EVENT + 10;

    /**
     * used internally to restore DNS properties back to the
     * default network
     */
    private static final int EVENT_RESTORE_DNS =
            MAX_NETWORK_STATE_TRACKER_EVENT + 11;

    /**
     * used internally to send a sticky broadcast delayed.
     */
    private static final int EVENT_SEND_STICKY_BROADCAST_INTENT =
            MAX_NETWORK_STATE_TRACKER_EVENT + 12;

    /**
     * Used internally to
     * {@link NetworkStateTracker#setPolicyDataEnable(boolean)}.
     */
    private static final int EVENT_SET_POLICY_DATA_ENABLE = MAX_NETWORK_STATE_TRACKER_EVENT + 13;

    private Handler mHandler;
    private ConnectivityServiceHSM mHSM;

    private ILinkManager mLinkManager = null;
    private Object mCneObj = null;
    private boolean mCneStarted = false;
    private static final String UseCne = "persist.cne.UseCne";
    private QosManager qosManager = null;

    // list of DeathRecipients used to make sure features are turned off when
    // a process dies
    private List<FeatureUser> mFeatureUsers;

    private boolean mSystemReady;
    private Intent mInitialBroadcast;

    private PowerManager.WakeLock mNetTransitionWakeLock;
    private String mNetTransitionWakeLockCausedBy = "";
    private int mNetTransitionWakeLockSerialNumber;
    private int mNetTransitionWakeLockTimeout;

    private InetAddress mDefaultDns;

    // this collection is used to refcount the added routes - if there are none left
    // it's time to remove the route from the route table
    private Collection<RouteInfo> mAddedRoutes = new ArrayList<RouteInfo>();

    // used in DBG mode to track inet condition reports
    private static final int INET_CONDITION_LOG_MAX_SIZE = 30;
    private ArrayList mInetLog;

    // track the current default http proxy - tell the world if we get a new one (real change)
    private ProxyProperties mDefaultProxy = null;
    private Object mDefaultProxyLock = new Object();
    private boolean mDefaultProxyDisabled = false;

    // track the global proxy.
    private ProxyProperties mGlobalProxy = null;
    private final Object mGlobalProxyLock = new Object();

    private SettingsObserver mSettingsObserver;

    NetworkConfig[] mNetConfigs;
    int mNetworksDefined;

    private static class RadioAttributes {
        public int mSimultaneity;
        public int mType;
        public RadioAttributes(String init) {
            String fragments[] = init.split(",");
            mType = Integer.parseInt(fragments[0]);
            mSimultaneity = Integer.parseInt(fragments[1]);
        }
    }
    RadioAttributes[] mRadioAttributes;

    private final class RouteAttributes {
        /**
         * Class for holding identifiers used to create custom tables for source
         * policy routing in the kernel.
         */
        private int tableId;
        private int metric;

        public RouteAttributes () {
        //We are assuming that MAX network types supported on android won't
        //exceed 253 in which case identifier assignment needs to change. Its
        //safe to do it this way for now.
            tableId = ++mRouteIdCtr;
            metric = 0;
        }

        public int getTableId() {
            return tableId;
        }

        public int getMetric() {
            return metric;
        }

        public void setMetric(int m) {
            metric = m;
        }
    }
    private RouteAttributes[]  mRouteAttributes;

    // the set of network types that can only be enabled by system/sig apps
    List mProtectedNetworks;

    private FmcStateMachine mFmcSM = null;
    private IFmcEventListener mListener = null;
    private boolean mFmcEnabled = false;


    public ConnectivityService(Context context, INetworkManagementService netd,
            INetworkStatsService statsService, INetworkPolicyManager policyManager) {
        if (DBG) log("ConnectivityService starting up");

        //HSM uses routeAttributes. So initialize it here, prior to creating HSM
        mRouteAttributes = new RouteAttributes[ConnectivityManager.MAX_NETWORK_TYPE+1];
        for (int i = 0; i < ConnectivityManager.MAX_NETWORK_TYPE+1; i++) {
            mRouteAttributes[i] = new RouteAttributes();
        }

        HandlerThread handlerThread = new HandlerThread("ConnectivityServiceThread");
        handlerThread.start();
        if (isCneAware()) { //TODO use featureConfig when ready
            mHSM = new ConnectivityServiceHSM( mContext,
                                               "ConnectivityServiceHSM",
                                               handlerThread.getLooper() );
            mHSM.start();
            mHandler = mHSM.getHandler();
        } else {
            mHandler = new MyHandler(handlerThread.getLooper());
        }

        // setup our unique device name
        if (TextUtils.isEmpty(SystemProperties.get("net.hostname"))) {
            String id = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            if (id != null && id.length() > 0) {
                String name = new String("android-").concat(id);
                SystemProperties.set("net.hostname", name);
            }
        }

        // read our default dns server ip
        String dns = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.DEFAULT_DNS_SERVER);
        if (dns == null || dns.length() == 0) {
            dns = context.getResources().getString(
                    com.android.internal.R.string.config_default_dns_server);
        }
        try {
            mDefaultDns = NetworkUtils.numericToInetAddress(dns);
        } catch (IllegalArgumentException e) {
            loge("Error setting defaultDns using " + dns);
        }

        mContext = checkNotNull(context, "missing Context");
        mNetd = checkNotNull(netd, "missing INetworkManagementService");
        mPolicyManager = checkNotNull(policyManager, "missing INetworkPolicyManager");

        try {
            mPolicyManager.registerListener(mPolicyListener);
        } catch (RemoteException e) {
            // ouch, no rules updates means some processes may never get network
            loge("unable to register INetworkPolicyListener" + e.toString());
        }

        final PowerManager powerManager = (PowerManager) context.getSystemService(
                Context.POWER_SERVICE);
        mNetTransitionWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mNetTransitionWakeLockTimeout = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_networkTransitionTimeout);

        mNetTrackers = new NetworkStateTracker[
                ConnectivityManager.MAX_NETWORK_TYPE+1];
        mCurrentLinkProperties = new LinkProperties[ConnectivityManager.MAX_NETWORK_TYPE+1];

        mNetworkPreference = getPersistedNetworkPreference();

        mRadioAttributes = new RadioAttributes[ConnectivityManager.MAX_RADIO_TYPE+1];
        mNetConfigs = new NetworkConfig[ConnectivityManager.MAX_NETWORK_TYPE+1];

        // Load device network attributes from resources
        String[] raStrings = context.getResources().getStringArray(
                com.android.internal.R.array.radioAttributes);
        for (String raString : raStrings) {
            RadioAttributes r = new RadioAttributes(raString);
            if (r.mType > ConnectivityManager.MAX_RADIO_TYPE) {
                loge("Error in radioAttributes - ignoring attempt to define type " + r.mType);
                continue;
            }
            if (mRadioAttributes[r.mType] != null) {
                loge("Error in radioAttributes - ignoring attempt to redefine type " +
                        r.mType);
                continue;
            }
            mRadioAttributes[r.mType] = r;
        }

        String[] naStrings = context.getResources().getStringArray(
                com.android.internal.R.array.networkAttributes);
        for (String naString : naStrings) {
            try {
                NetworkConfig n = new NetworkConfig(naString);
                if (n.type > ConnectivityManager.MAX_NETWORK_TYPE) {
                    loge("Error in networkAttributes - ignoring attempt to define type " +
                            n.type);
                    continue;
                }
                if (mNetConfigs[n.type] != null) {
                    loge("Error in networkAttributes - ignoring attempt to redefine type " +
                            n.type);
                    continue;
                }
                if (mRadioAttributes[n.radio] == null) {
                    loge("Error in networkAttributes - ignoring attempt to use undefined " +
                            "radio " + n.radio + " in network type " + n.type);
                    continue;
                }
                mNetConfigs[n.type] = n;
                mNetworksDefined++;
            } catch(Exception e) {
                // ignore it - leave the entry null
            }
        }

        mProtectedNetworks = new ArrayList<Integer>();
        int[] protectedNetworks = context.getResources().getIntArray(
                com.android.internal.R.array.config_protectedNetworks);
        for (int p : protectedNetworks) {
            if ((mNetConfigs[p] != null) && (mProtectedNetworks.contains(p) == false)) {
                mProtectedNetworks.add(p);
            } else {
                if (DBG) loge("Ignoring protectedNetwork " + p);
            }
        }

        // high priority first
        mPriorityList = new int[mNetworksDefined];
        {
            int insertionPoint = mNetworksDefined-1;
            int currentLowest = 0;
            int nextLowest = 0;
            while (insertionPoint > -1) {
                for (NetworkConfig na : mNetConfigs) {
                    if (na == null) continue;
                    if (na.priority < currentLowest) continue;
                    if (na.priority > currentLowest) {
                        if (na.priority < nextLowest || nextLowest == 0) {
                            nextLowest = na.priority;
                        }
                        continue;
                    }
                    mPriorityList[insertionPoint--] = na.type;
                }
                currentLowest = nextLowest;
                nextLowest = 0;
            }
        }

        mNetRequestersPids = new ArrayList[ConnectivityManager.MAX_NETWORK_TYPE+1];
        for (int i : mPriorityList) {
            mNetRequestersPids[i] = new ArrayList();
        }

        mFeatureUsers = new ArrayList<FeatureUser>();

        mNumDnsEntries = 0;

        mTestMode = SystemProperties.get("cm.test.mode").equals("true")
                && SystemProperties.get("ro.build.type").equals("eng");
        /*
         * Create the network state trackers for Wi-Fi and mobile
         * data. Maybe this could be done with a factory class,
         * but it's not clear that it's worth it, given that
         * the number of different network types is not going
         * to change very often.
         */
        for (int netType : mPriorityList) {
            switch (mNetConfigs[netType].radio) {
            case ConnectivityManager.TYPE_WIFI:
                mNetTrackers[netType] = new WifiStateTracker(netType,
                        mNetConfigs[netType].name);
                mNetTrackers[netType].startMonitoring(context, mHandler);
               break;
            case ConnectivityManager.TYPE_MOBILE:
                mNetTrackers[netType] = new MobileDataStateTracker(netType,
                        mNetConfigs[netType].name);
                mNetTrackers[netType].startMonitoring(context, mHandler);
                break;
            case ConnectivityManager.TYPE_DUMMY:
                mNetTrackers[netType] = new DummyDataStateTracker(netType,
                        mNetConfigs[netType].name);
                mNetTrackers[netType].startMonitoring(context, mHandler);
                break;
            case ConnectivityManager.TYPE_BLUETOOTH:
                mNetTrackers[netType] = BluetoothTetheringDataTracker.getInstance();
                mNetTrackers[netType].startMonitoring(context, mHandler);
                break;
            case ConnectivityManager.TYPE_WIMAX:
                mNetTrackers[netType] = makeWimaxStateTracker();
                if (mNetTrackers[netType]!= null) {
                    mNetTrackers[netType].startMonitoring(context, mHandler);
                }
                break;
            case ConnectivityManager.TYPE_ETHERNET:
                mNetTrackers[netType] = EthernetDataTracker.getInstance();
                mNetTrackers[netType].startMonitoring(context, mHandler);
                break;
            default:
                loge("Trying to create a DataStateTracker for an unknown radio type " +
                        mNetConfigs[netType].radio);
                continue;
            }
            mCurrentLinkProperties[netType] = null;
            if (mNetTrackers[netType] != null && mNetConfigs[netType].isDefault()) {
                mNetTrackers[netType].reconnect();
            }
        }

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService nmService = INetworkManagementService.Stub.asInterface(b);

        mTethering = new Tethering(mContext, nmService, statsService, this, mHandler.getLooper());
        mTetheringConfigValid = ((mTethering.getTetherableUsbRegexs().length != 0 ||
                                  mTethering.getTetherableWifiRegexs().length != 0 ||
                                  mTethering.getTetherableBluetoothRegexs().length != 0) &&
                                 mTethering.getUpstreamIfaceTypes().length != 0);

        mVpn = new Vpn(mContext, new VpnCallback());

        try {
            nmService.registerObserver(mTethering);
            nmService.registerObserver(mVpn);
        } catch (RemoteException e) {
            loge("Error registering observer :" + e);
        }

        if (DBG) {
            mInetLog = new ArrayList();
        }

        mSettingsObserver = new SettingsObserver(mHandler, EVENT_APPLY_GLOBAL_HTTP_PROXY);
        mSettingsObserver.observe(mContext);

        loadGlobalProxy();
    }
private NetworkStateTracker makeWimaxStateTracker() {
        //Initialize Wimax
        DexClassLoader wimaxClassLoader;
        Class wimaxStateTrackerClass = null;
        Class wimaxServiceClass = null;
        Class wimaxManagerClass;
        String wimaxJarLocation;
        String wimaxLibLocation;
        String wimaxManagerClassName;
        String wimaxServiceClassName;
        String wimaxStateTrackerClassName;

        NetworkStateTracker wimaxStateTracker = null;

        boolean isWimaxEnabled = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_wimaxEnabled);

        if (isWimaxEnabled) {
            try {
                wimaxJarLocation = mContext.getResources().getString(
                        com.android.internal.R.string.config_wimaxServiceJarLocation);
                wimaxLibLocation = mContext.getResources().getString(
                        com.android.internal.R.string.config_wimaxNativeLibLocation);
                wimaxManagerClassName = mContext.getResources().getString(
                        com.android.internal.R.string.config_wimaxManagerClassname);
                wimaxServiceClassName = mContext.getResources().getString(
                        com.android.internal.R.string.config_wimaxServiceClassname);
                wimaxStateTrackerClassName = mContext.getResources().getString(
                        com.android.internal.R.string.config_wimaxStateTrackerClassname);

                log("wimaxJarLocation: " + wimaxJarLocation);
                wimaxClassLoader =  new DexClassLoader(wimaxJarLocation,
                        new ContextWrapper(mContext).getCacheDir().getAbsolutePath(),
                        wimaxLibLocation, ClassLoader.getSystemClassLoader());

                try {
                    wimaxManagerClass = wimaxClassLoader.loadClass(wimaxManagerClassName);
                    wimaxStateTrackerClass = wimaxClassLoader.loadClass(wimaxStateTrackerClassName);
                    wimaxServiceClass = wimaxClassLoader.loadClass(wimaxServiceClassName);
                } catch (ClassNotFoundException ex) {
                    loge("Exception finding Wimax classes: " + ex.toString());
                    return null;
                }
            } catch(Resources.NotFoundException ex) {
                loge("Wimax Resources does not exist!!! ");
                return null;
            }

            try {
                log("Starting Wimax Service... ");

                Constructor wmxStTrkrConst = wimaxStateTrackerClass.getConstructor
                        (new Class[] {Context.class, Handler.class});
                wimaxStateTracker = (NetworkStateTracker)wmxStTrkrConst.newInstance(mContext,
                        mHandler);

                Constructor wmxSrvConst = wimaxServiceClass.getDeclaredConstructor
                        (new Class[] {Context.class, wimaxStateTrackerClass});
                wmxSrvConst.setAccessible(true);
                IBinder svcInvoker = (IBinder)wmxSrvConst.newInstance(mContext, wimaxStateTracker);
                wmxSrvConst.setAccessible(false);

                ServiceManager.addService(WimaxManagerConstants.WIMAX_SERVICE, svcInvoker);

            } catch(Exception ex) {
                loge("Exception creating Wimax classes: " + ex.toString());
                return null;
            }
        } else {
            loge("Wimax is not enabled or not added to the network attributes!!! ");
            return null;
        }

        return wimaxStateTracker;
    }
    /**
     * Sets the preferred network.
     * @param preference the new preference
     */
    public void setNetworkPreference(int preference) {
        enforceChangePermission();

        mHandler.sendMessage(mHandler.obtainMessage(
                    EVENT_SET_NETWORK_PREFERENCE, preference, INVALID_MSG_ARG));
    }

    public int getNetworkPreference() {
        enforceAccessPermission();
        int preference;
        synchronized(this) {
            preference = mNetworkPreference;
        }
        return preference;
    }

    private void handleSetNetworkPreference(int preference) {
        if (ConnectivityManager.isNetworkTypeValid(preference) &&
                mNetConfigs[preference] != null &&
                mNetConfigs[preference].isDefault()) {
            if (mNetworkPreference != preference) {
                final ContentResolver cr = mContext.getContentResolver();
                Settings.Secure.putInt(cr, Settings.Secure.NETWORK_PREFERENCE, preference);
                synchronized(this) {
                    mNetworkPreference = preference;
                }
                enforcePreference();
            }
        }
    }

    private int getConnectivityChangeDelay() {
        final ContentResolver cr = mContext.getContentResolver();

        /** Check system properties for the default value then use secure settings value, if any. */
        int defaultDelay = SystemProperties.getInt(
                "conn." + Settings.Secure.CONNECTIVITY_CHANGE_DELAY,
                Settings.Secure.CONNECTIVITY_CHANGE_DELAY_DEFAULT);
        return Settings.Secure.getInt(cr, Settings.Secure.CONNECTIVITY_CHANGE_DELAY,
                defaultDelay);
    }

    private int getPersistedNetworkPreference() {
        final ContentResolver cr = mContext.getContentResolver();

        final int networkPrefSetting = Settings.Secure
                .getInt(cr, Settings.Secure.NETWORK_PREFERENCE, -1);
        if (networkPrefSetting != -1) {
            return networkPrefSetting;
        }

        return ConnectivityManager.DEFAULT_NETWORK_PREFERENCE;
    }

    /**
     * Make the state of network connectivity conform to the preference settings
     * In this method, we only tear down a non-preferred network. Establishing
     * a connection to the preferred network is taken care of when we handle
     * the disconnect event from the non-preferred network
     * (see {@link #handleDisconnect(NetworkInfo)}).
     */
    private void enforcePreference() {
        if (mNetTrackers[mNetworkPreference].getNetworkInfo().isConnected())
            return;

        if (!mNetTrackers[mNetworkPreference].isAvailable())
            return;

        for (int t=0; t <= ConnectivityManager.MAX_RADIO_TYPE; t++) {
            if (t != mNetworkPreference && mNetTrackers[t] != null &&
                    mNetTrackers[t].getNetworkInfo().isConnected()) {
                if (DBG) {
                    log("tearing down " + mNetTrackers[t].getNetworkInfo() +
                            " in enforcePreference");
                }
                teardown(mNetTrackers[t]);
            }
        }
    }

    private boolean teardown(NetworkStateTracker netTracker) {
        if (netTracker.teardown()) {
            netTracker.setTeardownRequested(true);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Check if UID should be blocked from using the network represented by the
     * given {@link NetworkStateTracker}.
     */
    private boolean isNetworkBlocked(NetworkStateTracker tracker, int uid) {
        final String iface = tracker.getLinkProperties().getInterfaceName();

        final boolean networkCostly;
        final int uidRules;
        synchronized (mRulesLock) {
            networkCostly = mMeteredIfaces.contains(iface);
            uidRules = mUidRules.get(uid, RULE_ALLOW_ALL);
        }

        if (networkCostly && (uidRules & RULE_REJECT_METERED) != 0) {
            return true;
        }

        // no restrictive rules; network is visible
        return false;
    }

    /**
     * Return a filtered {@link NetworkInfo}, potentially marked
     * {@link DetailedState#BLOCKED} based on
     * {@link #isNetworkBlocked(NetworkStateTracker, int)}.
     */
    private NetworkInfo getFilteredNetworkInfo(NetworkStateTracker tracker, int uid) {
        NetworkInfo info = tracker.getNetworkInfo();
        if (isNetworkBlocked(tracker, uid)) {
            // network is blocked; clone and override state
            info = new NetworkInfo(info);
            info.setDetailedState(DetailedState.BLOCKED, null, null);
        }
        return info;
    }

    /**
     * Return NetworkInfo for the active (i.e., connected) network interface.
     * It is assumed that at most one network is active at a time. If more
     * than one is active, it is indeterminate which will be returned.
     * @return the info for the active network, or {@code null} if none is
     * active
     */
    @Override
    public NetworkInfo getActiveNetworkInfo() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        return getNetworkInfo(mActiveDefaultNetwork, uid);
    }

    @Override
    public NetworkInfo getActiveNetworkInfoForUid(int uid) {
        enforceConnectivityInternalPermission();
        return getNetworkInfo(mActiveDefaultNetwork, uid);
    }

    @Override
    public NetworkInfo getNetworkInfo(int networkType) {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        return getNetworkInfo(networkType, uid);
    }

    private NetworkInfo getNetworkInfo(int networkType, int uid) {
        NetworkInfo info = null;
        if (isNetworkTypeValid(networkType)) {
            final NetworkStateTracker tracker = mNetTrackers[networkType];
            if (tracker != null) {
                info = getFilteredNetworkInfo(tracker, uid);
            }
        }
        return info;
    }

    @Override
    public NetworkInfo[] getAllNetworkInfo() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        final ArrayList<NetworkInfo> result = Lists.newArrayList();
        synchronized (mRulesLock) {
            for (NetworkStateTracker tracker : mNetTrackers) {
                if (tracker != null) {
                    result.add(getFilteredNetworkInfo(tracker, uid));
                }
            }
        }
        return result.toArray(new NetworkInfo[result.size()]);
    }

    @Override
    public boolean isNetworkSupported(int networkType) {
        enforceAccessPermission();
        return (isNetworkTypeValid(networkType) && (mNetTrackers[networkType] != null));
    }

    /**
     * Return LinkProperties for the active (i.e., connected) default
     * network interface.  It is assumed that at most one default network
     * is active at a time. If more than one is active, it is indeterminate
     * which will be returned.
     * @return the ip properties for the active network, or {@code null} if
     * none is active
     */
    @Override
    public LinkProperties getActiveLinkProperties() {
        return getLinkProperties(mActiveDefaultNetwork);
    }

    @Override
    public LinkProperties getLinkProperties(int networkType) {
        enforceAccessPermission();
        if (isNetworkTypeValid(networkType)) {
            final NetworkStateTracker tracker = mNetTrackers[networkType];
            if (tracker != null) {
                return tracker.getLinkProperties();
            }
        }
        return null;
    }

    @Override
    public NetworkState[] getAllNetworkState() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        final ArrayList<NetworkState> result = Lists.newArrayList();
        synchronized (mRulesLock) {
            for (NetworkStateTracker tracker : mNetTrackers) {
                if (tracker != null) {
                    final NetworkInfo info = getFilteredNetworkInfo(tracker, uid);
                    result.add(new NetworkState(
                            info, tracker.getLinkProperties(), tracker.getLinkCapabilities()));
                }
            }
        }
        return result.toArray(new NetworkState[result.size()]);
    }

    private NetworkState getNetworkStateUnchecked(int networkType) {
        if (isNetworkTypeValid(networkType)) {
            final NetworkStateTracker tracker = mNetTrackers[networkType];
            if (tracker != null) {
                return new NetworkState(tracker.getNetworkInfo(), tracker.getLinkProperties(),
                        tracker.getLinkCapabilities());
            }
        }
        return null;
    }

    @Override
    public NetworkQuotaInfo getActiveNetworkQuotaInfo() {
        enforceAccessPermission();
        final NetworkState state = getNetworkStateUnchecked(mActiveDefaultNetwork);
        if (state != null) {
            try {
                return mPolicyManager.getNetworkQuotaInfo(state);
            } catch (RemoteException e) {
            }
        }
        return null;
    }

    public boolean setRadios(boolean turnOn) {
        boolean result = true;
        enforceChangePermission();
        for (NetworkStateTracker t : mNetTrackers) {
            if (t != null) result = t.setRadio(turnOn) && result;
        }
        return result;
    }

    public boolean setRadio(int netType, boolean turnOn) {
        enforceChangePermission();
        if (!ConnectivityManager.isNetworkTypeValid(netType)) {
            return false;
        }
        NetworkStateTracker tracker = mNetTrackers[netType];
        return tracker != null && tracker.setRadio(turnOn);
    }

    /**
     * Used to notice when the calling process dies so we can self-expire
     *
     * Also used to know if the process has cleaned up after itself when
     * our auto-expire timer goes off.  The timer has a link to an object.
     *
     */
    private class FeatureUser implements IBinder.DeathRecipient {
        int mNetworkType;
        String mFeature;
        IBinder mBinder;
        int mPid;
        int mUid;
        long mCreateTime;

        FeatureUser(int type, String feature, IBinder binder) {
            super();
            mNetworkType = type;
            mFeature = feature;
            mBinder = binder;
            mPid = getCallingPid();
            mUid = getCallingUid();
            mCreateTime = System.currentTimeMillis();

            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        void unlinkDeathRecipient() {
            mBinder.unlinkToDeath(this, 0);
        }

        public void binderDied() {
            log("ConnectivityService FeatureUser binderDied(" +
                    mNetworkType + ", " + mFeature + ", " + mBinder + "), created " +
                    (System.currentTimeMillis() - mCreateTime) + " mSec ago");
            stopUsingNetworkFeature(this, false);
        }

        public void expire() {
            if (VDBG) {
                log("ConnectivityService FeatureUser expire(" +
                        mNetworkType + ", " + mFeature + ", " + mBinder +"), created " +
                        (System.currentTimeMillis() - mCreateTime) + " mSec ago");
            }
            stopUsingNetworkFeature(this, false);
        }

        public boolean isSameUser(FeatureUser u) {
            if (u == null) return false;

            return isSameUser(u.mPid, u.mUid, u.mNetworkType, u.mFeature);
        }

        public boolean isSameUser(int pid, int uid, int networkType, String feature) {
            if ((mPid == pid) && (mUid == uid) && (mNetworkType == networkType) &&
                TextUtils.equals(mFeature, feature)) {
                return true;
            }
            return false;
        }

        public String toString() {
            return "FeatureUser("+mNetworkType+","+mFeature+","+mPid+","+mUid+"), created " +
                    (System.currentTimeMillis() - mCreateTime) + " mSec ago";
        }
    }

    // javadoc from interface
    public int startUsingNetworkFeature(int networkType, String feature,
            IBinder binder) {
        if (VDBG) {
            log("startUsingNetworkFeature for net " + networkType + ": " + feature);
        }
        enforceChangePermission();
        if (!ConnectivityManager.isNetworkTypeValid(networkType) ||
                mNetConfigs[networkType] == null) {
            return Phone.APN_REQUEST_FAILED;
        }

        FeatureUser f = new FeatureUser(networkType, feature, binder);

        // TODO - move this into individual networktrackers
        int usedNetworkType = convertFeatureToNetworkType(networkType, feature);

        if (mProtectedNetworks.contains(usedNetworkType)) {
            enforceConnectivityInternalPermission();
        }

        NetworkStateTracker network = mNetTrackers[usedNetworkType];
        if (network != null) {
            Integer currentPid = new Integer(getCallingPid());
            if (usedNetworkType != networkType) {
                NetworkInfo ni = network.getNetworkInfo();

                if (ni.isAvailable() == false) {
                    if (DBG) log("special network not available");
                    if (!TextUtils.equals(feature,Phone.FEATURE_ENABLE_DUN_ALWAYS)) {
                        return Phone.APN_TYPE_NOT_AVAILABLE;
                    } else {
                        // else make the attempt anyway - probably giving REQUEST_STARTED below
                    }
                }

                int restoreTimer = getRestoreDefaultNetworkDelay(usedNetworkType);

                synchronized(this) {
                    boolean addToList = true;
                    if (restoreTimer < 0) {
                        // In case there is no timer is specified for the feature,
                        // make sure we don't add duplicate entry with the same request.
                        for (FeatureUser u : mFeatureUsers) {
                            if (u.isSameUser(f)) {
                                // Duplicate user is found. Do not add.
                                addToList = false;
                                break;
                            }
                        }
                    }

                    if (addToList) mFeatureUsers.add(f);
                    if (!mNetRequestersPids[usedNetworkType].contains(currentPid)) {
                        // this gets used for per-pid dns when connected
                        mNetRequestersPids[usedNetworkType].add(currentPid);
                    }
                }

                if (restoreTimer >= 0) {
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                EVENT_RESTORE_DEFAULT_NETWORK, f), restoreTimer);
                }

                if ((ni.isConnectedOrConnecting() == true) &&
                        !network.isTeardownRequested()) {
                    if (ni.isConnected() == true) {
                        final long token = Binder.clearCallingIdentity();
                        try {
                            // add the pid-specific dns
                            handleDnsConfigurationChange(usedNetworkType);
                            if (VDBG) log("special network already active");
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                        return Phone.APN_ALREADY_ACTIVE;
                    }
                    if (VDBG) log("special network already connecting");
                    return Phone.APN_REQUEST_STARTED;
                }

                // check if the radio in play can make another contact
                // assume if cannot for now

                if (DBG) {
                    log("startUsingNetworkFeature reconnecting to " + networkType + ": " + feature);
                }
                network.reconnect();
                return Phone.APN_REQUEST_STARTED;
            } else {
                // need to remember this unsupported request so we respond appropriately on stop
                synchronized(this) {
                    mFeatureUsers.add(f);
                    if (!mNetRequestersPids[usedNetworkType].contains(currentPid)) {
                        // this gets used for per-pid dns when connected
                        mNetRequestersPids[usedNetworkType].add(currentPid);
                    }
                }
                return -1;
            }
        }
        return Phone.APN_TYPE_NOT_AVAILABLE;
    }

    // javadoc from interface
    public int stopUsingNetworkFeature(int networkType, String feature) {
        enforceChangePermission();

        int pid = getCallingPid();
        int uid = getCallingUid();

        FeatureUser u = null;
        boolean found = false;

        synchronized(this) {
            for (FeatureUser x : mFeatureUsers) {
                if (x.isSameUser(pid, uid, networkType, feature)) {
                    u = x;
                    found = true;
                    break;
                }
            }
        }
        if (found && u != null) {
            // stop regardless of how many other time this proc had called start
            return stopUsingNetworkFeature(u, true);
        } else {
            // none found!
            if (VDBG) log("stopUsingNetworkFeature - not a live request, ignoring");
            return 1;
        }
    }

    private int stopUsingNetworkFeature(FeatureUser u, boolean ignoreDups) {
        int networkType = u.mNetworkType;
        String feature = u.mFeature;
        int pid = u.mPid;
        int uid = u.mUid;

        NetworkStateTracker tracker = null;
        boolean callTeardown = false;  // used to carry our decision outside of sync block

        if (VDBG) {
            log("stopUsingNetworkFeature: net " + networkType + ": " + feature);
        }

        if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
            if (DBG) {
                log("stopUsingNetworkFeature: net " + networkType + ": " + feature +
                        ", net is invalid");
            }
            return -1;
        }

        // need to link the mFeatureUsers list with the mNetRequestersPids state in this
        // sync block
        synchronized(this) {
            // check if this process still has an outstanding start request
            if (!mFeatureUsers.contains(u)) {
                if (VDBG) {
                    log("stopUsingNetworkFeature: this process has no outstanding requests" +
                        ", ignoring");
                }
                return 1;
            }
            u.unlinkDeathRecipient();
            mFeatureUsers.remove(mFeatureUsers.indexOf(u));
            // If we care about duplicate requests, check for that here.
            //
            // This is done to support the extension of a request - the app
            // can request we start the network feature again and renew the
            // auto-shutoff delay.  Normal "stop" calls from the app though
            // do not pay attention to duplicate requests - in effect the
            // API does not refcount and a single stop will counter multiple starts.
            if (ignoreDups == false) {
                for (FeatureUser x : mFeatureUsers) {
                    if (x.isSameUser(u)) {
                        if (VDBG) log("stopUsingNetworkFeature: dup is found, ignoring");
                        return 1;
                    }
                }
            }

            // TODO - move to individual network trackers
            int usedNetworkType = convertFeatureToNetworkType(networkType, feature);

            tracker =  mNetTrackers[usedNetworkType];
            if (tracker == null) {
                if (DBG) {
                    log("stopUsingNetworkFeature: net " + networkType + ": " + feature +
                            " no known tracker for used net type " + usedNetworkType);
                }
                return -1;
            }
            if (usedNetworkType != networkType) {
                Integer currentPid = new Integer(pid);
                mNetRequestersPids[usedNetworkType].remove(currentPid);
                reassessPidDns(pid, true);
                if (mNetRequestersPids[usedNetworkType].size() != 0) {
                    if (VDBG) {
                        log("stopUsingNetworkFeature: net " + networkType + ": " + feature +
                                " others still using it");
                    }
                    return 1;
                }
                callTeardown = true;
            } else {
                if (DBG) {
                    log("stopUsingNetworkFeature: net " + networkType + ": " + feature +
                            " not a known feature - dropping");
                }
            }
        }

        if (callTeardown) {
            if (DBG) {
                log("stopUsingNetworkFeature: teardown net " + networkType + ": " + feature);
            }
            tracker.teardown();
            return 1;
        } else {
            return -1;
        }
    }

    /**
     * @deprecated use requestRouteToHostAddress instead
     *
     * Ensure that a network route exists to deliver traffic to the specified
     * host via the specified network interface.
     * @param networkType the type of the network over which traffic to the
     * specified host is to be routed
     * @param hostAddress the IP address of the host to which the route is
     * desired
     * @return {@code true} on success, {@code false} on failure
     */
    public boolean requestRouteToHost(int networkType, int hostAddress) {
        InetAddress inetAddress = NetworkUtils.intToInetAddress(hostAddress);

        if (inetAddress == null) {
            return false;
        }

        return requestRouteToHostAddress(networkType, inetAddress.getAddress());
    }

    /**
     * Ensure that a network route exists to deliver traffic to the specified
     * host via the specified network interface.
     * @param networkType the type of the network over which traffic to the
     * specified host is to be routed
     * @param hostAddress the IP address of the host to which the route is
     * desired
     * @return {@code true} on success, {@code false} on failure
     */
    public boolean requestRouteToHostAddress(int networkType, byte[] hostAddress) {
        enforceChangePermission();
        if (mProtectedNetworks.contains(networkType)) {
            enforceConnectivityInternalPermission();
        }

        if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
            if (DBG) log("requestRouteToHostAddress on invalid network: " + networkType);
            return false;
        }
        NetworkStateTracker tracker = mNetTrackers[networkType];

        if (tracker == null || !tracker.getNetworkInfo().isConnected() ||
                tracker.isTeardownRequested()) {
            if (VDBG) {
                log("requestRouteToHostAddress on down network " +
                           "(" + networkType + ") - dropped");
            }
            return false;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            InetAddress addr = InetAddress.getByAddress(hostAddress);
            LinkProperties lp = tracker.getLinkProperties();
            return addRouteToAddress(lp, addr);
        } catch (UnknownHostException e) {
            if (DBG) log("requestRouteToHostAddress got " + e.toString());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return false;
    }

    private boolean addRoute(LinkProperties p, RouteInfo r, boolean toDefaultTable) {
        return modifyRoute(p.getInterfaceName(), p, r, 0, ADD, toDefaultTable);
    }

    private boolean removeRoute(LinkProperties p, RouteInfo r, boolean toDefaultTable) {
        return modifyRoute(p.getInterfaceName(), p, r, 0, REMOVE, toDefaultTable);
    }

    private boolean addRouteToAddress(LinkProperties lp, InetAddress addr) {
        return modifyRouteToAddress(lp, addr, ADD, TO_DEFAULT_TABLE);
    }

    private boolean removeRouteToAddress(LinkProperties lp, InetAddress addr) {
        return modifyRouteToAddress(lp, addr, REMOVE, TO_DEFAULT_TABLE);
    }

    private boolean modifyRouteToAddress(LinkProperties lp, InetAddress addr, boolean doAdd,
            boolean toDefaultTable) {
        RouteInfo bestRoute = RouteInfo.selectBestRoute(lp.getRoutes(), addr);
        if (bestRoute == null) {
            bestRoute = RouteInfo.makeHostRoute(addr);
        } else {
            if (bestRoute.getGateway().equals(addr)) {
                // if there is no better route, add the implied hostroute for our gateway
                bestRoute = RouteInfo.makeHostRoute(addr);
            } else {
                // if we will connect to this through another route, add a direct route
                // to it's gateway
                bestRoute = RouteInfo.makeHostRoute(addr, bestRoute.getGateway());
            }
        }
        return modifyRoute(lp.getInterfaceName(), lp, bestRoute, 0, doAdd, toDefaultTable);
    }

    private boolean modifyRoute(String ifaceName, LinkProperties lp, RouteInfo r, int cycleCount,
            boolean doAdd, boolean toDefaultTable) {
        if ((ifaceName == null) || (lp == null) || (r == null)) {
            if (DBG) log("modifyRoute got unexpected null: " + ifaceName + ", " + lp + ", " + r);
            return false;
        }

        if (cycleCount > MAX_HOSTROUTE_CYCLE_COUNT) {
            loge("Error modifying route - too much recursion");
            return false;
        }

        if (r.isHostRoute() == false) {
            RouteInfo bestRoute = RouteInfo.selectBestRoute(lp.getRoutes(), r.getGateway());
            if (bestRoute != null) {
                if (bestRoute.getGateway().equals(r.getGateway())) {
                    // if there is no better route, add the implied hostroute for our gateway
                    bestRoute = RouteInfo.makeHostRoute(r.getGateway());
                } else {
                    // if we will connect to our gateway through another route, add a direct
                    // route to it's gateway
                    bestRoute = RouteInfo.makeHostRoute(r.getGateway(), bestRoute.getGateway());
                }
                modifyRoute(ifaceName, lp, bestRoute, cycleCount+1, doAdd, toDefaultTable);
            }
        }
        if (doAdd) {
            if (VDBG) log("Adding " + r + " for interface " + ifaceName);
            try {
                if (toDefaultTable) {
                    mAddedRoutes.add(r);  // only track default table - only one apps can effect
                    if (VDBG) log("Routes in main table - [ " + mAddedRoutes + " ]");
                    mNetd.addRoute(ifaceName, r);
                } else {
                    mNetd.addSecondaryRoute(ifaceName, r);
                }
            } catch (Exception e) {
                // never crash - catch them all
                if (DBG) loge("Exception trying to add a route: " + e);
                return false;
            }
        } else {
            // if we remove this one and there are no more like it, then refcount==0 and
            // we can remove it from the table
            if (toDefaultTable) {
                mAddedRoutes.remove(r);
                if (VDBG) log("Routes in main table - [ " + mAddedRoutes + " ]");
                if (mAddedRoutes.contains(r) == false) {
                    if (VDBG) log("Removing " + r + " for interface " + ifaceName);
                    try {
                        mNetd.removeRoute(ifaceName, r);
                    } catch (Exception e) {
                        // never crash - catch them all
                        if (DBG) loge("Exception trying to remove a route: " + e);
                        return false;
                    }
                } else {
                    if (VDBG) log("not removing " + r + " as it's still in use");
                }
            } else {
                if (VDBG) log("Removing " + r + " for interface " + ifaceName);
                try {
                    mNetd.removeSecondaryRoute(ifaceName, r);
                } catch (Exception e) {
                    // never crash - catch them all
                    if (DBG) loge("Exception trying to remove a route: " + e);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * @see ConnectivityManager#getMobileDataEnabled()
     */
    public boolean getMobileDataEnabled() {
        // TODO: This detail should probably be in DataConnectionTracker's
        //       which is where we store the value and maybe make this
        //       asynchronous.
        enforceAccessPermission();
        boolean retVal = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.MOBILE_DATA, 1) == 1;
        if (VDBG) log("getMobileDataEnabled returning " + retVal);
        return retVal;
    }

    public void setDataDependency(int networkType, boolean met) {
        enforceConnectivityInternalPermission();

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SET_DEPENDENCY_MET,
                (met ? ENABLED : DISABLED), networkType));
    }

    private void handleSetDependencyMet(int networkType, boolean met) {
        if (mNetTrackers[networkType] != null) {
            if (DBG) {
                log("handleSetDependencyMet(" + networkType + ", " + met + ")");
            }
            mNetTrackers[networkType].setDependencyMet(met);
        }
    }

    private INetworkPolicyListener mPolicyListener = new INetworkPolicyListener.Stub() {
        @Override
        public void onUidRulesChanged(int uid, int uidRules) {
            // only someone like NPMS should only be calling us
            mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

            if (LOGD_RULES) {
                log("onUidRulesChanged(uid=" + uid + ", uidRules=" + uidRules + ")");
            }

            synchronized (mRulesLock) {
                // skip update when we've already applied rules
                final int oldRules = mUidRules.get(uid, RULE_ALLOW_ALL);
                if (oldRules == uidRules) return;

                mUidRules.put(uid, uidRules);
            }

            // TODO: dispatch into NMS to push rules towards kernel module
            // TODO: notify UID when it has requested targeted updates
        }

        @Override
        public void onMeteredIfacesChanged(String[] meteredIfaces) {
            // only someone like NPMS should only be calling us
            mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

            if (LOGD_RULES) {
                log("onMeteredIfacesChanged(ifaces=" + Arrays.toString(meteredIfaces) + ")");
            }

            synchronized (mRulesLock) {
                mMeteredIfaces.clear();
                for (String iface : meteredIfaces) {
                    mMeteredIfaces.add(iface);
                }
            }
        }
    };

    /**
     * @see ConnectivityManager#setMobileDataEnabled(boolean)
     */
    public void setMobileDataEnabled(boolean enabled) {
        enforceChangePermission();
        if (DBG) log("setMobileDataEnabled(" + enabled + ")");

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SET_MOBILE_DATA,
                (enabled ? ENABLED : DISABLED), INVALID_MSG_ARG));
    }

    private void handleSetMobileData(boolean enabled) {
        if (mNetTrackers[ConnectivityManager.TYPE_MOBILE] != null) {
            if (VDBG) {
                log(mNetTrackers[ConnectivityManager.TYPE_MOBILE].toString() + enabled);
            }
            mNetTrackers[ConnectivityManager.TYPE_MOBILE].setUserDataEnable(enabled);
        }
        if (mNetTrackers[ConnectivityManager.TYPE_WIMAX] != null) {
            if (VDBG) {
                log(mNetTrackers[ConnectivityManager.TYPE_WIMAX].toString() + enabled);
            }
            mNetTrackers[ConnectivityManager.TYPE_WIMAX].setUserDataEnable(enabled);
        }
    }

    @Override
    public void setPolicyDataEnable(int networkType, boolean enabled) {
        // only someone like NPMS should only be calling us
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        mHandler.sendMessage(mHandler.obtainMessage(
                EVENT_SET_POLICY_DATA_ENABLE, networkType, (enabled ? ENABLED : DISABLED)));
    }

    private void handleSetPolicyDataEnable(int networkType, boolean enabled) {
        if (isNetworkTypeValid(networkType)) {
            final NetworkStateTracker tracker = mNetTrackers[networkType];
            if (tracker != null) {
                tracker.setPolicyDataEnable(enabled);
            }
        }
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                "ConnectivityService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE,
                "ConnectivityService");
    }

    // TODO Make this a special check when it goes public
    private void enforceTetherChangePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE,
                "ConnectivityService");
    }

    private void enforceTetherAccessPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                "ConnectivityService");
    }

    private void enforceConnectivityInternalPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "ConnectivityService");
    }

    /**
     * Handle a {@code DISCONNECTED} event. If this pertains to the non-active
     * network, we ignore it. If it is for the active network, we send out a
     * broadcast. But first, we check whether it might be possible to connect
     * to a different network.
     * @param info the {@code NetworkInfo} for the network
     */
    private void handleDisconnect(NetworkInfo info) {

        int prevNetType = info.getType();

        mNetTrackers[prevNetType].setTeardownRequested(false);
        /*
         * If the disconnected network is not the active one, then don't report
         * this as a loss of connectivity. What probably happened is that we're
         * getting the disconnect for a network that we explicitly disabled
         * in accordance with network preference policies.
         */
        if (!mNetConfigs[prevNetType].isDefault()) {
            List pids = mNetRequestersPids[prevNetType];
            for (int i = 0; i<pids.size(); i++) {
                Integer pid = (Integer)pids.get(i);
                // will remove them because the net's no longer connected
                // need to do this now as only now do we know the pids and
                // can properly null things that are no longer referenced.
                reassessPidDns(pid.intValue(), false);
            }
        }

        Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, info);
        if (info.isFailover()) {
            intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
            info.setFailover(false);
        }
        if (info.getReason() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_REASON, info.getReason());
        }
        if (info.getExtraInfo() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO,
                    info.getExtraInfo());
        }

        if (mNetConfigs[prevNetType].isDefault()) {
            tryFailover(prevNetType);
            if (mActiveDefaultNetwork != -1) {
                NetworkInfo switchTo = mNetTrackers[mActiveDefaultNetwork].getNetworkInfo();
                intent.putExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO, switchTo);
            } else {
                mDefaultInetConditionPublished = 0; // we're not connected anymore
                intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
            }
        }
        intent.putExtra(ConnectivityManager.EXTRA_INET_CONDITION, mDefaultInetConditionPublished);

        // Reset interface if no other connections are using the same interface
        boolean doReset = true;
        LinkProperties linkProperties = mNetTrackers[prevNetType].getLinkProperties();
        if (linkProperties != null) {
            String oldIface = linkProperties.getInterfaceName();
            if (TextUtils.isEmpty(oldIface) == false) {
                for (NetworkStateTracker networkStateTracker : mNetTrackers) {
                    if (networkStateTracker == null) continue;
                    NetworkInfo networkInfo = networkStateTracker.getNetworkInfo();
                    if (networkInfo.isConnected() && networkInfo.getType() != prevNetType) {
                        LinkProperties l = networkStateTracker.getLinkProperties();
                        if (l == null) continue;
                        if (oldIface.equals(l.getInterfaceName())) {
                            doReset = false;
                            break;
                        }
                    }
                }
            }
        }

        // do this before we broadcast the change
        handleConnectivityChange(prevNetType, doReset);

        final Intent immediateIntent = new Intent(intent);
        immediateIntent.setAction(CONNECTIVITY_ACTION_IMMEDIATE);
        sendStickyBroadcast(immediateIntent);
        sendStickyBroadcastDelayed(intent, getConnectivityChangeDelay());
        /*
         * If the failover network is already connected, then immediately send
         * out a followup broadcast indicating successful failover
         */
        if (mActiveDefaultNetwork != -1) {
            sendConnectedBroadcastDelayed(mNetTrackers[mActiveDefaultNetwork].getNetworkInfo(),
                    getConnectivityChangeDelay());
        }
    }

    private void tryFailover(int prevNetType) {
        /*
         * If this is a default network, check if other defaults are available.
         * Try to reconnect on all available and let them hash it out when
         * more than one connects.
         */
        if (mNetConfigs[prevNetType].isDefault()) {
            if (mActiveDefaultNetwork == prevNetType) {
                mActiveDefaultNetwork = -1;
            }

            // don't signal a reconnect for anything lower or equal priority than our
            // current connected default
            // TODO - don't filter by priority now - nice optimization but risky
//            int currentPriority = -1;
//            if (mActiveDefaultNetwork != -1) {
//                currentPriority = mNetConfigs[mActiveDefaultNetwork].mPriority;
//            }
            for (int checkType=0; checkType <= ConnectivityManager.MAX_NETWORK_TYPE; checkType++) {
                if (checkType == prevNetType) continue;
                if (mNetConfigs[checkType] == null) continue;
                if (!mNetConfigs[checkType].isDefault()) continue;
                if (mNetTrackers[checkType] == null) continue;

// Enabling the isAvailable() optimization caused mobile to not get
// selected if it was in the middle of error handling. Specifically
// a moble connection that took 30 seconds to complete the DEACTIVATE_DATA_CALL
// would not be available and we wouldn't get connected to anything.
// So removing the isAvailable() optimization below for now. TODO: This
// optimization should work and we need to investigate why it doesn't work.
// This could be related to how DEACTIVATE_DATA_CALL is reporting its
// complete before it is really complete.
//                if (!mNetTrackers[checkType].isAvailable()) continue;

//                if (currentPriority >= mNetConfigs[checkType].mPriority) continue;

                NetworkStateTracker checkTracker = mNetTrackers[checkType];
                NetworkInfo checkInfo = checkTracker.getNetworkInfo();
                if (!checkInfo.isConnectedOrConnecting() || checkTracker.isTeardownRequested()) {
                    checkInfo.setFailover(true);
                    checkTracker.reconnect();
                }
                if (DBG) log("Attempting to switch to " + checkInfo.getTypeName());
            }
        }
    }

    private void sendConnectedBroadcast(NetworkInfo info) {
        sendGeneralBroadcast(info, CONNECTIVITY_ACTION_IMMEDIATE);
        sendGeneralBroadcast(info, CONNECTIVITY_ACTION);
    }

    private void sendConnectedBroadcastDelayed(NetworkInfo info, int delayMs) {
        sendGeneralBroadcast(info, CONNECTIVITY_ACTION_IMMEDIATE);
        sendGeneralBroadcastDelayed(info, CONNECTIVITY_ACTION, delayMs);
    }

    private void sendInetConditionBroadcast(NetworkInfo info) {
        sendGeneralBroadcast(info, ConnectivityManager.INET_CONDITION_ACTION);
    }

    private Intent makeGeneralIntent(NetworkInfo info, String bcastType) {
        Intent intent = new Intent(bcastType);
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, info);
        if (info.isFailover()) {
            intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
            info.setFailover(false);
        }
        if (info.getReason() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_REASON, info.getReason());
        }
        if (info.getExtraInfo() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO,
                    info.getExtraInfo());
        }
        intent.putExtra(ConnectivityManager.EXTRA_INET_CONDITION, mDefaultInetConditionPublished);
        return intent;
    }

    private void sendGeneralBroadcast(NetworkInfo info, String bcastType) {
        sendStickyBroadcast(makeGeneralIntent(info, bcastType));
    }

    private void sendGeneralBroadcastDelayed(NetworkInfo info, String bcastType, int delayMs) {
        sendStickyBroadcastDelayed(makeGeneralIntent(info, bcastType), delayMs);
    }

    /**
     * Called when an attempt to fail over to another network has failed.
     * @param info the {@link NetworkInfo} for the failed network
     */
    private void handleConnectionFailure(NetworkInfo info) {
        mNetTrackers[info.getType()].setTeardownRequested(false);

        String reason = info.getReason();
        String extraInfo = info.getExtraInfo();

        String reasonText;
        if (reason == null) {
            reasonText = ".";
        } else {
            reasonText = " (" + reason + ").";
        }
        loge("Attempt to connect to " + info.getTypeName() + " failed" + reasonText);

        Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, info);
        if (getActiveNetworkInfo() == null) {
            intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
        }
        if (reason != null) {
            intent.putExtra(ConnectivityManager.EXTRA_REASON, reason);
        }
        if (extraInfo != null) {
            intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO, extraInfo);
        }
        if (info.isFailover()) {
            intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
            info.setFailover(false);
        }

        if (mNetConfigs[info.getType()].isDefault()) {
            tryFailover(info.getType());
            if (mActiveDefaultNetwork != -1) {
                NetworkInfo switchTo = mNetTrackers[mActiveDefaultNetwork].getNetworkInfo();
                intent.putExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO, switchTo);
            } else {
                mDefaultInetConditionPublished = 0;
                intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
            }
        }

        intent.putExtra(ConnectivityManager.EXTRA_INET_CONDITION, mDefaultInetConditionPublished);

        final Intent immediateIntent = new Intent(intent);
        immediateIntent.setAction(CONNECTIVITY_ACTION_IMMEDIATE);
        sendStickyBroadcast(immediateIntent);
        sendStickyBroadcast(intent);
        /*
         * If the failover network is already connected, then immediately send
         * out a followup broadcast indicating successful failover
         */
        if (mActiveDefaultNetwork != -1) {
            sendConnectedBroadcast(mNetTrackers[mActiveDefaultNetwork].getNetworkInfo());
        }
    }

    private void sendStickyBroadcast(Intent intent) {
        synchronized(this) {
            if (!mSystemReady) {
                mInitialBroadcast = new Intent(intent);
            }
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            if (VDBG) {
                log("sendStickyBroadcast: action=" + intent.getAction());
            }

            mContext.sendStickyBroadcast(intent);
        }
    }

    private void sendStickyBroadcastDelayed(Intent intent, int delayMs) {
        if (delayMs <= 0) {
            sendStickyBroadcast(intent);
        } else {
            if (VDBG) {
                log("sendStickyBroadcastDelayed: delayMs=" + delayMs + ", action="
                        + intent.getAction());
            }
            mHandler.sendMessageDelayed(mHandler.obtainMessage(
                    EVENT_SEND_STICKY_BROADCAST_INTENT, intent), delayMs);
        }
    }

    void systemReady() {
        synchronized(this) {
            mSystemReady = true;
            if (mInitialBroadcast != null) {
                mContext.sendStickyBroadcast(mInitialBroadcast);
                mInitialBroadcast = null;
            }
        }
        // load the global proxy at startup
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_APPLY_GLOBAL_HTTP_PROXY));
    }

    private void handleConnect(NetworkInfo info) {
        final int type = info.getType();

        // snapshot isFailover, because sendConnectedBroadcast() resets it
        boolean isFailover = info.isFailover();
        final NetworkStateTracker thisNet = mNetTrackers[type];


        // if this is a default net and other default is running
        // kill the one not preferred
        if (mNetConfigs[type].isDefault()) {
            if (mActiveDefaultNetwork != -1 && mActiveDefaultNetwork != type) {
                if ((type != mNetworkPreference &&
                        mNetConfigs[mActiveDefaultNetwork].priority >
                        mNetConfigs[type].priority) ||
                        mNetworkPreference == mActiveDefaultNetwork) {
                        // don't accept this one
                        if (VDBG) {
                            log("Not broadcasting CONNECT_ACTION " +
                                "to torn down network " + info.getTypeName());
                        }
                        teardown(thisNet);
                        return;
                } else {
                    // tear down the other
                    NetworkStateTracker otherNet =
                            mNetTrackers[mActiveDefaultNetwork];
                    if (DBG) {
                        log("Policy requires " + otherNet.getNetworkInfo().getTypeName() +
                            " teardown");
                    }
                    if (!teardown(otherNet)) {
                        loge("Network declined teardown request");
                        teardown(thisNet);
                        return;
                    }
                }
            }
            synchronized (ConnectivityService.this) {
                // have a new default network, release the transition wakelock in a second
                // if it's held.  The second pause is to allow apps to reconnect over the
                // new network
                if (mNetTransitionWakeLock.isHeld()) {
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(
                            EVENT_CLEAR_NET_TRANSITION_WAKELOCK,
                            mNetTransitionWakeLockSerialNumber,
                            INVALID_MSG_ARG),
                            1000);
                }
            }
            mActiveDefaultNetwork = type;
            // this will cause us to come up initially as unconnected and switching
            // to connected after our normal pause unless somebody reports us as reall
            // disconnected
            mDefaultInetConditionPublished = 0;
            mDefaultConnectionSequence++;
            mInetConditionChangeInFlight = false;
            // Don't do this - if we never sign in stay, grey
            //reportNetworkCondition(mActiveDefaultNetwork, 100);
        }
        thisNet.setTeardownRequested(false);
        updateNetworkSettings(thisNet);
        handleConnectivityChange(type, false);

        sendConnectedBroadcastDelayed(info, getConnectivityChangeDelay());

        // notify battery stats service about this network
        final String iface = thisNet.getLinkProperties().getInterfaceName();
        if (iface != null) {
            try {
                BatteryStatsService.getService().noteNetworkInterfaceType(iface, type);
            } catch (RemoteException e) {
                // ignored; service lives in system_server
            }
        }
    }

    /**
     * After a change in the connectivity state of a network. We're mainly
     * concerned with making sure that the list of DNS servers is set up
     * according to which networks are connected, and ensuring that the
     * right routing table entries exist.
     */
    private void handleConnectivityChange(int netType, boolean doReset) {
        int resetMask = doReset ? NetworkUtils.RESET_ALL_ADDRESSES : 0;

        /*
         * If a non-default network is enabled, add the host routes that
         * will allow it's DNS servers to be accessed.
         */
        handleDnsConfigurationChange(netType);

        LinkProperties curLp = mCurrentLinkProperties[netType];
        LinkProperties newLp = null;

        if (mNetTrackers[netType].getNetworkInfo().isConnected()) {
            newLp = mNetTrackers[netType].getLinkProperties();

            if (VDBG) {
                log("handleConnectivityChange: changed linkProperty[" + netType + "]:" +
                        " doReset=" + doReset + " resetMask=" + resetMask +
                        "\n   curLp=" + curLp +
                        "\n   newLp=" + newLp);
            }

            if (curLp != null) {
                if (curLp.isIdenticalInterfaceName(newLp)) {
                    CompareResult<LinkAddress> car = curLp.compareAddresses(newLp);
                    if ((car.removed.size() != 0) || (car.added.size() != 0)) {
                        for (LinkAddress linkAddr : car.removed) {
                            if (linkAddr.getAddress() instanceof Inet4Address) {
                                resetMask |= NetworkUtils.RESET_IPV4_ADDRESSES;
                            }
                            if (linkAddr.getAddress() instanceof Inet6Address) {
                                resetMask |= NetworkUtils.RESET_IPV6_ADDRESSES;
                            }
                        }
                        if (DBG) {
                            log("handleConnectivityChange: addresses changed" +
                                    " linkProperty[" + netType + "]:" + " resetMask=" + resetMask +
                                    "\n   car=" + car);
                        }
                    } else {
                        if (DBG) {
                            log("handleConnectivityChange: address are the same reset per doReset" +
                                   " linkProperty[" + netType + "]:" +
                                   " resetMask=" + resetMask);
                        }
                    }
                } else {
                    resetMask = NetworkUtils.RESET_ALL_ADDRESSES;
                    if (DBG) {
                        log("handleConnectivityChange: interface not not equivalent reset both" +
                                " linkProperty[" + netType + "]:" +
                                " resetMask=" + resetMask);
                    }
                }
            }
            if (mNetConfigs[netType].isDefault()) {
                handleApplyDefaultProxy(newLp.getHttpProxy());
            }
        } else {
            if (VDBG) {
                log("handleConnectivityChange: changed linkProperty[" + netType + "]:" +
                        " doReset=" + doReset + " resetMask=" + resetMask +
                        "\n  curLp=" + curLp +
                        "\n  newLp= null");
            }
        }
        mCurrentLinkProperties[netType] = newLp;
        boolean resetDns = updateRoutes( newLp,
                                         curLp,
                                         mNetConfigs[netType].isDefault(),
                                         mRouteAttributes[netType] );

        if (resetMask != 0 || resetDns) {
            LinkProperties linkProperties = mNetTrackers[netType].getLinkProperties();
            if (linkProperties != null) {
                String iface = linkProperties.getInterfaceName();
                if (TextUtils.isEmpty(iface) == false) {
                    if (resetMask != 0) {
                        if (DBG) log("resetConnections(" + iface + ", " + resetMask + ")");
                        NetworkUtils.resetConnections(iface, resetMask);

                        // Tell VPN the interface is down. It is a temporary
                        // but effective fix to make VPN aware of the change.
                        if ((resetMask & NetworkUtils.RESET_IPV4_ADDRESSES) != 0) {
                            mVpn.interfaceStatusChanged(iface, false);
                        }
                    }
                    if (resetDns) {
                        if (VDBG) log("resetting DNS cache for " + iface);
                        try {
                            mNetd.flushInterfaceDnsCache(iface);
                        } catch (Exception e) {
                            // never crash - catch them all
                            if (DBG) loge("Exception resetting dns cache: " + e);
                        }
                    }
                }
            }
        }

        // TODO: Temporary notifying upstread change to Tethering.
        //       @see bug/4455071
        /** Notify TetheringService if interface name has been changed. */
        if (TextUtils.equals(mNetTrackers[netType].getNetworkInfo().getReason(),
                             Phone.REASON_LINK_PROPERTIES_CHANGED)) {
            if (isTetheringSupported()) {
                mTethering.handleTetherIfaceChange(mNetTrackers[netType].getNetworkInfo());
            }
        }
    }

    /**
     * Add and remove routes using the old properties (null if not previously connected),
     * new properties (null if becoming disconnected).  May even be double null, which
     * is a noop.
     * Uses isLinkDefault to determine if default routes should be set.
     * Sets host routes to the dns servers
     * returns a boolean indicating the routes changed
     */
    private boolean updateRoutes(LinkProperties newLp, LinkProperties curLp,
            boolean isLinkDefault, RouteAttributes ra) {
        Collection<RouteInfo> routesToAdd = null;
        CompareResult<InetAddress> dnsDiff = new CompareResult<InetAddress>();
        CompareResult<RouteInfo> routeDiff = new CompareResult<RouteInfo>();
        CompareResult<LinkAddress> localAddrDiff = new CompareResult<LinkAddress>();
        if (curLp != null) {
            // check for the delta between the current set and the new
            routeDiff = curLp.compareRoutes(newLp);
            dnsDiff = curLp.compareDnses(newLp);
            localAddrDiff = curLp.compareAddresses(newLp);
        } else if (newLp != null) {
            routeDiff.added = newLp.getRoutes();
            dnsDiff.added = newLp.getDnses();
            localAddrDiff.added = newLp.getLinkAddresses();
        }

        boolean routesChanged = (routeDiff.removed.size() != 0 || routeDiff.added.size() != 0);

        for (RouteInfo r : routeDiff.removed) {
            if (isLinkDefault || ! r.isDefaultRoute()) {
                removeRoute(curLp, r, TO_DEFAULT_TABLE);
            }
            if (isLinkDefault == false) {
                // remove from a secondary route table
                removeRoute(curLp, r, TO_SECONDARY_TABLE);
            }
        }

        for (RouteInfo r :  routeDiff.added) {
            if (isLinkDefault || ! r.isDefaultRoute()) {
                addRoute(newLp, r, TO_DEFAULT_TABLE);
            } else {
                // add to a secondary route table
                addRoute(newLp, r, TO_SECONDARY_TABLE);

                // many radios add a default route even when we don't want one.
                // remove the default route unless somebody else has asked for it
                String ifaceName = newLp.getInterfaceName();
                if (TextUtils.isEmpty(ifaceName) == false && mAddedRoutes.contains(r) == false) {
                    if (VDBG) log("Removing " + r + " for interface " + ifaceName);
                    try {
                        mNetd.removeRoute(ifaceName, r);
                    } catch (Exception e) {
                        // never crash - catch them all
                        if (DBG) loge("Exception trying to remove a route: " + e);
                    }
                }
            }
        }

        if (localAddrDiff.removed.size() != 0) {
            for (LinkAddress la : localAddrDiff.removed) {
                if (VDBG) log("Removing src route for:" + la.getAddress().getHostAddress());
                try {
                     mNetd.delSrcRoute(la.getAddress().getAddress(), ra.getTableId());
                } catch (Exception e) {
                    loge("Exception while trying to remove src route: " + e);
                }
            }
        }

        if (localAddrDiff.added.size() != 0) {
            InetAddress gw4Addr = null, gw6Addr = null;
            String ifaceName = newLp.getInterfaceName();
            if (! TextUtils.isEmpty(ifaceName)) {
                for (RouteInfo r : newLp.getRoutes()) {
                    if (! r.isDefaultRoute()) continue;
                    if (r.getGateway() instanceof Inet4Address)
                        gw4Addr = r.getGateway();
                    else
                        gw6Addr = r.getGateway();
                } //gateway is optional so continue adding the source route.
                for (LinkAddress la : localAddrDiff.added) {
                    try {
                        if (la.getAddress() instanceof Inet4Address) {
                            mNetd.replaceSrcRoute(ifaceName, la.getAddress().getAddress(),
                                    gw4Addr.getAddress(), ra.getTableId());
                        } else {
                            mNetd.replaceSrcRoute(ifaceName, la.getAddress().getAddress(),
                                    gw6Addr.getAddress(), ra.getTableId());
                        }
                    } catch (Exception e) {
                        //never crash, catch them all
                        loge("Exception while trying to add src route: " + e);
                    }
                }
            }
        }

        // handle DNS routes for all net types - no harm done
        if (routesChanged) {
            // routes changed - remove all old dns entries and add new
            if (curLp != null) {
                for (InetAddress oldDns : curLp.getDnses()) {
                    removeRouteToAddress(curLp, oldDns);
                }
            }
            if (newLp != null) {
                for (InetAddress newDns : newLp.getDnses()) {
                    addRouteToAddress(newLp, newDns);
                }
            }
        } else {
            // no change in routes, check for change in dns themselves
            for (InetAddress oldDns : dnsDiff.removed) {
                removeRouteToAddress(curLp, oldDns);
            }
            for (InetAddress newDns : dnsDiff.added) {
                addRouteToAddress(newLp, newDns);
            }
        }
        return routesChanged;
    }


   /**
     * Reads the network specific TCP buffer sizes from SystemProperties
     * net.tcp.buffersize.[default|wifi|umts|edge|gprs] and set them for system
     * wide use
     */
   public void updateNetworkSettings(NetworkStateTracker nt) {
        String key = nt.getTcpBufferSizesPropName();
        String bufferSizes = SystemProperties.get(key);

        if (bufferSizes.length() == 0) {
            if (VDBG) log(key + " not found in system properties. Using defaults");

            // Setting to default values so we won't be stuck to previous values
            key = "net.tcp.buffersize.default";
            bufferSizes = SystemProperties.get(key);
        }

        // Set values in kernel
        if (bufferSizes.length() != 0) {
            if (VDBG) {
                log("Setting TCP values: [" + bufferSizes
                        + "] which comes from [" + key + "]");
            }
            setBufferSize(bufferSizes);
        }
    }

   /**
     * Writes TCP buffer sizes to /sys/kernel/ipv4/tcp_[r/w]mem_[min/def/max]
     * which maps to /proc/sys/net/ipv4/tcp_rmem and tcpwmem
     *
     * @param bufferSizes in the format of "readMin, readInitial, readMax,
     *        writeMin, writeInitial, writeMax"
     */
    private void setBufferSize(String bufferSizes) {
        try {
            String[] values = bufferSizes.split(",");

            if (values.length == 6) {
              final String prefix = "/sys/kernel/ipv4/tcp_";
                FileUtils.stringToFile(prefix + "rmem_min", values[0]);
                FileUtils.stringToFile(prefix + "rmem_def", values[1]);
                FileUtils.stringToFile(prefix + "rmem_max", values[2]);
                FileUtils.stringToFile(prefix + "wmem_min", values[3]);
                FileUtils.stringToFile(prefix + "wmem_def", values[4]);
                FileUtils.stringToFile(prefix + "wmem_max", values[5]);
            } else {
                loge("Invalid buffersize string: " + bufferSizes);
            }
        } catch (IOException e) {
            loge("Can't set tcp buffer sizes:" + e);
        }
    }

    /**
     * Adjust the per-process dns entries (net.dns<x>.<pid>) based
     * on the highest priority active net which this process requested.
     * If there aren't any, clear it out
     */
    private void reassessPidDns(int myPid, boolean doBump)
    {
        if (VDBG) log("reassessPidDns for pid " + myPid);
        for(int i : mPriorityList) {
            if (mNetConfigs[i].isDefault()) {
                continue;
            }
            NetworkStateTracker nt = mNetTrackers[i];
            if (nt.getNetworkInfo().isConnected() &&
                    !nt.isTeardownRequested()) {
                LinkProperties p = nt.getLinkProperties();
                if (p == null) continue;
                List pids = mNetRequestersPids[i];
                for (int j=0; j<pids.size(); j++) {
                    Integer pid = (Integer)pids.get(j);
                    if (pid.intValue() == myPid) {
                        Collection<InetAddress> dnses = p.getDnses();
                        writePidDns(dnses, myPid);
                        if (doBump) {
                            bumpDns();
                        }
                        return;
                    }
                }
           }
        }
        // nothing found - delete
        for (int i = 1; ; i++) {
            String prop = "net.dns" + i + "." + myPid;
            if (SystemProperties.get(prop).length() == 0) {
                if (doBump) {
                    bumpDns();
                }
                return;
            }
            SystemProperties.set(prop, "");
        }
    }

    // return true if results in a change
    private boolean writePidDns(Collection <InetAddress> dnses, int pid) {
        int j = 1;
        boolean changed = false;
        for (InetAddress dns : dnses) {
            String dnsString = dns.getHostAddress();
            if (changed || !dnsString.equals(SystemProperties.get("net.dns" + j + "." + pid))) {
                changed = true;
                SystemProperties.set("net.dns" + j + "." + pid, dns.getHostAddress());
            }
            j++;
        }
        return changed;
    }

    private void bumpDns() {
        /*
         * Bump the property that tells the name resolver library to reread
         * the DNS server list from the properties.
         */
        String propVal = SystemProperties.get("net.dnschange");
        int n = 0;
        if (propVal.length() != 0) {
            try {
                n = Integer.parseInt(propVal);
            } catch (NumberFormatException e) {}
        }
        SystemProperties.set("net.dnschange", "" + (n+1));
        /*
         * Tell the VMs to toss their DNS caches
         */
        Intent intent = new Intent(Intent.ACTION_CLEAR_DNS_CACHE);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        /*
         * Connectivity events can happen before boot has completed ...
         */
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcast(intent);
    }

    // Caller must grab mDnsLock.
    private boolean updateDns(String network, String iface,
            Collection<InetAddress> dnses, String domains) {
        boolean changed = false;
        int last = 0;
        if (dnses.size() == 0 && mDefaultDns != null) {
            ++last;
            String value = mDefaultDns.getHostAddress();
            if (!value.equals(SystemProperties.get("net.dns1"))) {
                if (DBG) {
                    loge("no dns provided for " + network + " - using " + value);
                }
                changed = true;
                SystemProperties.set("net.dns1", value);
            }
        } else {
            for (InetAddress dns : dnses) {
                ++last;
                String key = "net.dns" + last;
                String value = dns.getHostAddress();
                if (!changed && value.equals(SystemProperties.get(key))) {
                    continue;
                }
                if (VDBG) {
                    log("adding dns " + value + " for " + network);
                }
                changed = true;
                SystemProperties.set(key, value);
            }
        }
        for (int i = last + 1; i <= mNumDnsEntries; ++i) {
            String key = "net.dns" + i;
            if (VDBG) log("erasing " + key);
            changed = true;
            SystemProperties.set(key, "");
        }
        mNumDnsEntries = last;

        if (changed) {
            try {
                mNetd.setDnsServersForInterface(iface, NetworkUtils.makeStrings(dnses));
                mNetd.setDefaultInterfaceForDns(iface);
            } catch (Exception e) {
                if (DBG) loge("exception setting default dns interface: " + e);
            }
        }
        if (!domains.equals(SystemProperties.get("net.dns.search"))) {
            SystemProperties.set("net.dns.search", domains);
            changed = true;
        }
        return changed;
    }

    private void handleDnsConfigurationChange(int netType) {
        // add default net's dns entries
        NetworkStateTracker nt = mNetTrackers[netType];
        if (nt != null && nt.getNetworkInfo().isConnected() && !nt.isTeardownRequested()) {
            LinkProperties p = nt.getLinkProperties();
            if (p == null) return;
            Collection<InetAddress> dnses = p.getDnses();
            boolean changed = false;
            if (mNetConfigs[netType].isDefault()) {
                String network = nt.getNetworkInfo().getTypeName();
                synchronized (mDnsLock) {
                    if (!mDnsOverridden) {
                        changed = updateDns(network, p.getInterfaceName(), dnses, "");
                    }
                }
            } else {
                try {
                    mNetd.setDnsServersForInterface(p.getInterfaceName(),
                            NetworkUtils.makeStrings(dnses));
                } catch (Exception e) {
                    if (DBG) loge("exception setting dns servers: " + e);
                }
                // set per-pid dns for attached secondary nets
                List pids = mNetRequestersPids[netType];
                for (int y=0; y< pids.size(); y++) {
                    Integer pid = (Integer)pids.get(y);
                    changed = writePidDns(dnses, pid.intValue());
                }
            }
            if (changed) bumpDns();
        }
    }

    private int getRestoreDefaultNetworkDelay(int networkType) {
        String restoreDefaultNetworkDelayStr = SystemProperties.get(
                NETWORK_RESTORE_DELAY_PROP_NAME);
        if(restoreDefaultNetworkDelayStr != null &&
                restoreDefaultNetworkDelayStr.length() != 0) {
            try {
                return Integer.valueOf(restoreDefaultNetworkDelayStr);
            } catch (NumberFormatException e) {
            }
        }
        // if the system property isn't set, use the value for the apn type
        int ret = RESTORE_DEFAULT_NETWORK_DELAY;

        if ((networkType <= ConnectivityManager.MAX_NETWORK_TYPE) &&
                (mNetConfigs[networkType] != null)) {
            ret = mNetConfigs[networkType].restoreTime;
        }
        return ret;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ConnectivityService " +
                    "from from pid=" + Binder.getCallingPid() + ", uid=" +
                    Binder.getCallingUid());
            return;
        }
        pw.println();
        for (NetworkStateTracker nst : mNetTrackers) {
            if (nst != null) {
                if (nst.getNetworkInfo().isConnected()) {
                    pw.println("Active network: " + nst.getNetworkInfo().
                            getTypeName());
                }
                pw.println(nst.getNetworkInfo());
                pw.println(nst);
                pw.println();
            }
        }

        pw.println("Network Requester Pids:");
        for (int net : mPriorityList) {
            String pidString = net + ": ";
            for (Object pid : mNetRequestersPids[net]) {
                pidString = pidString + pid.toString() + ", ";
            }
            pw.println(pidString);
        }
        pw.println();

        pw.println("FeatureUsers:");
        for (Object requester : mFeatureUsers) {
            pw.println(requester.toString());
        }
        pw.println();

        synchronized (this) {
            pw.println("NetworkTranstionWakeLock is currently " +
                    (mNetTransitionWakeLock.isHeld() ? "" : "not ") + "held.");
            pw.println("It was last requested for "+mNetTransitionWakeLockCausedBy);
        }
        pw.println();

        mTethering.dump(fd, pw, args);

        if (mInetLog != null) {
            pw.println();
            pw.println("Inet condition reports:");
            for(int i = 0; i < mInetLog.size(); i++) {
                pw.println(mInetLog.get(i));
            }
        }
    }

    // must be stateless - things change under us.
    private class MyHandler extends Handler {
        public MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            NetworkInfo info;
            switch (msg.what) {
                case NetworkStateTracker.EVENT_STATE_CHANGED:
                    info = (NetworkInfo) msg.obj;
                    int type = info.getType();
                    NetworkInfo.State state = info.getState();

                    if (VDBG || (state == NetworkInfo.State.CONNECTED) ||
                            (state == NetworkInfo.State.DISCONNECTED)) {
                        log("ConnectivityChange for " +
                            info.getTypeName() + ": " +
                            state + "/" + info.getDetailedState());
                    }

                    // Connectivity state changed:
                    // [31-13] Reserved for future use
                    // [12-9] Network subtype (for mobile network, as defined
                    //         by TelephonyManager)
                    // [8-3] Detailed state ordinal (as defined by
                    //         NetworkInfo.DetailedState)
                    // [2-0] Network type (as defined by ConnectivityManager)
                    int eventLogParam = (info.getType() & 0x7) |
                            ((info.getDetailedState().ordinal() & 0x3f) << 3) |
                            (info.getSubtype() << 9);
                    EventLog.writeEvent(EventLogTags.CONNECTIVITY_STATE_CHANGED,
                            eventLogParam);

                    if (info.getDetailedState() ==
                            NetworkInfo.DetailedState.FAILED) {
                        handleConnectionFailure(info);
                    } else if (state == NetworkInfo.State.DISCONNECTED) {
                        handleDisconnect(info);
                    } else if (state == NetworkInfo.State.SUSPENDED) {
                        // TODO: need to think this over.
                        // the logic here is, handle SUSPENDED the same as
                        // DISCONNECTED. The only difference being we are
                        // broadcasting an intent with NetworkInfo that's
                        // suspended. This allows the applications an
                        // opportunity to handle DISCONNECTED and SUSPENDED
                        // differently, or not.
                        handleDisconnect(info);
                    } else if (state == NetworkInfo.State.CONNECTED) {
                        handleConnect(info);
                    }
                    break;
                case NetworkStateTracker.EVENT_CONFIGURATION_CHANGED:
                    info = (NetworkInfo) msg.obj;
                    // TODO: Temporary allowing network configuration
                    //       change not resetting sockets.
                    //       @see bug/4455071
                    handleConnectivityChange(info.getType(), false);
                    break;
                case EVENT_CLEAR_NET_TRANSITION_WAKELOCK:
                    String causedBy = null;
                    synchronized (ConnectivityService.this) {
                        if (msg.arg1 == mNetTransitionWakeLockSerialNumber &&
                                mNetTransitionWakeLock.isHeld()) {
                            mNetTransitionWakeLock.release();
                            causedBy = mNetTransitionWakeLockCausedBy;
                        }
                    }
                    if (causedBy != null) {
                        log("NetTransition Wakelock for " + causedBy + " released by timeout");
                    }
                    break;
                case EVENT_RESTORE_DEFAULT_NETWORK:
                    FeatureUser u = (FeatureUser)msg.obj;
                    u.expire();
                    break;
                case EVENT_INET_CONDITION_CHANGE:
                {
                    int netType = msg.arg1;
                    int condition = msg.arg2;
                    handleInetConditionChange(netType, condition);
                    break;
                }
                case EVENT_INET_CONDITION_HOLD_END:
                {
                    int netType = msg.arg1;
                    int sequence = msg.arg2;
                    handleInetConditionHoldEnd(netType, sequence);
                    break;
                }
                case EVENT_SET_NETWORK_PREFERENCE:
                {
                    int preference = msg.arg1;
                    handleSetNetworkPreference(preference);
                    break;
                }
                case EVENT_SET_MOBILE_DATA:
                {
                    boolean enabled = (msg.arg1 == ENABLED);
                    handleSetMobileData(enabled);
                    break;
                }
                case EVENT_APPLY_GLOBAL_HTTP_PROXY:
                {
                    handleDeprecatedGlobalHttpProxy();
                    break;
                }
                case EVENT_SET_DEPENDENCY_MET:
                {
                    boolean met = (msg.arg1 == ENABLED);
                    handleSetDependencyMet(msg.arg2, met);
                    break;
                }
                case EVENT_RESTORE_DNS:
                {
                    if (mActiveDefaultNetwork != -1) {
                        handleDnsConfigurationChange(mActiveDefaultNetwork);
                    }
                    break;
                }
                case EVENT_SEND_STICKY_BROADCAST_INTENT:
                {
                    Intent intent = (Intent)msg.obj;
                    sendStickyBroadcast(intent);
                    break;
                }
                case EVENT_SET_POLICY_DATA_ENABLE: {
                    final int networkType = msg.arg1;
                    final boolean enabled = msg.arg2 == ENABLED;
                    handleSetPolicyDataEnable(networkType, enabled);
                }
            }
        }
    }

    // javadoc from interface
    public int tether(String iface) {
        enforceTetherChangePermission();

        if (isTetheringSupported()) {
            return mTethering.tether(iface);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // javadoc from interface
    public int untether(String iface) {
        enforceTetherChangePermission();

        if (isTetheringSupported()) {
            return mTethering.untether(iface);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // javadoc from interface
    public int getLastTetherError(String iface) {
        enforceTetherAccessPermission();

        if (isTetheringSupported()) {
            return mTethering.getLastTetherError(iface);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // TODO - proper iface API for selection by property, inspection, etc
    public String[] getTetherableUsbRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.getTetherableUsbRegexs();
        } else {
            return new String[0];
        }
    }

    public String[] getTetherableWifiRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.getTetherableWifiRegexs();
        } else {
            return new String[0];
        }
    }

    public String[] getTetherableBluetoothRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.getTetherableBluetoothRegexs();
        } else {
            return new String[0];
        }
    }

    public int setUsbTethering(boolean enable) {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.setUsbTethering(enable);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // TODO - move iface listing, queries, etc to new module
    // javadoc from interface
    public String[] getTetherableIfaces() {
        enforceTetherAccessPermission();
        return mTethering.getTetherableIfaces();
    }

    public String[] getTetheredIfaces() {
        enforceTetherAccessPermission();
        return mTethering.getTetheredIfaces();
    }

    @Override
    public String[] getTetheredIfacePairs() {
        enforceTetherAccessPermission();
        return mTethering.getTetheredIfacePairs();
    }

    public String[] getTetheringErroredIfaces() {
        enforceTetherAccessPermission();
        return mTethering.getErroredIfaces();
    }

    // if ro.tether.denied = true we default to no tethering
    // gservices could set the secure setting to 1 though to enable it on a build where it
    // had previously been turned off.
    public boolean isTetheringSupported() {
        enforceTetherAccessPermission();
        int defaultVal = (SystemProperties.get("ro.tether.denied").equals("true") ? 0 : 1);
        boolean tetherEnabledInSettings = (Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.TETHER_SUPPORTED, defaultVal) != 0);
        return tetherEnabledInSettings && mTetheringConfigValid;
    }

    // An API NetworkStateTrackers can call when they lose their network.
    // This will automatically be cleared after X seconds or a network becomes CONNECTED,
    // whichever happens first.  The timer is started by the first caller and not
    // restarted by subsequent callers.
    public void requestNetworkTransitionWakelock(String forWhom) {
        enforceConnectivityInternalPermission();
        synchronized (this) {
            if (mNetTransitionWakeLock.isHeld()) return;
            mNetTransitionWakeLockSerialNumber++;
            mNetTransitionWakeLock.acquire();
            mNetTransitionWakeLockCausedBy = forWhom;
        }
        mHandler.sendMessageDelayed(mHandler.obtainMessage(
                EVENT_CLEAR_NET_TRANSITION_WAKELOCK,
                mNetTransitionWakeLockSerialNumber,
                INVALID_MSG_ARG),
                mNetTransitionWakeLockTimeout);
        return;
    }

    // 100 percent is full good, 0 is full bad.
    public void reportInetCondition(int networkType, int percentage) {
        if (VDBG) log("reportNetworkCondition(" + networkType + ", " + percentage + ")");
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.STATUS_BAR,
                "ConnectivityService");

        if (DBG) {
            int pid = getCallingPid();
            int uid = getCallingUid();
            String s = pid + "(" + uid + ") reports inet is " +
                (percentage > 50 ? "connected" : "disconnected") + " (" + percentage + ") on " +
                "network Type " + networkType + " at " + GregorianCalendar.getInstance().getTime();
            mInetLog.add(s);
            while(mInetLog.size() > INET_CONDITION_LOG_MAX_SIZE) {
                mInetLog.remove(0);
            }
        }
        mHandler.sendMessage(mHandler.obtainMessage(
            EVENT_INET_CONDITION_CHANGE, networkType, percentage));
    }

    private void handleInetConditionChange(int netType, int condition) {
        if (mActiveDefaultNetwork == -1) {
            if (DBG) log("handleInetConditionChange: no active default network - ignore");
            return;
        }
        if (mActiveDefaultNetwork != netType) {
            if (DBG) log("handleInetConditionChange: net=" + netType +
                            " != default=" + mActiveDefaultNetwork + " - ignore");
            return;
        }
        if (VDBG) {
            log("handleInetConditionChange: net=" +
                    netType + ", condition=" + condition +
                    ",mActiveDefaultNetwork=" + mActiveDefaultNetwork);
        }
        mDefaultInetCondition = condition;
        int delay;
        if (mInetConditionChangeInFlight == false) {
            if (VDBG) log("handleInetConditionChange: starting a change hold");
            // setup a new hold to debounce this
            if (mDefaultInetCondition > 50) {
                delay = Settings.Secure.getInt(mContext.getContentResolver(),
                        Settings.Secure.INET_CONDITION_DEBOUNCE_UP_DELAY, 500);
            } else {
                delay = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.INET_CONDITION_DEBOUNCE_DOWN_DELAY, 3000);
            }
            mInetConditionChangeInFlight = true;
            mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_INET_CONDITION_HOLD_END,
                    mActiveDefaultNetwork, mDefaultConnectionSequence), delay);
        } else {
            // we've set the new condition, when this hold ends that will get picked up
            if (VDBG) log("handleInetConditionChange: currently in hold - not setting new end evt");
        }
    }

    private void handleInetConditionHoldEnd(int netType, int sequence) {
        if (DBG) {
            log("handleInetConditionHoldEnd: net=" + netType +
                    ", condition=" + mDefaultInetCondition +
                    ", published condition=" + mDefaultInetConditionPublished);
        }
        mInetConditionChangeInFlight = false;

        if (mActiveDefaultNetwork == -1) {
            if (DBG) log("handleInetConditionHoldEnd: no active default network - ignoring");
            return;
        }
        if (mDefaultConnectionSequence != sequence) {
            if (DBG) log("handleInetConditionHoldEnd: event hold for obsolete network - ignoring");
            return;
        }
        // TODO: Figure out why this optimization sometimes causes a
        //       change in mDefaultInetCondition to be missed and the
        //       UI to not be updated.
        //if (mDefaultInetConditionPublished == mDefaultInetCondition) {
        //    if (DBG) log("no change in condition - aborting");
        //    return;
        //}
        NetworkInfo networkInfo = mNetTrackers[mActiveDefaultNetwork].getNetworkInfo();
        if (networkInfo.isConnected() == false) {
            if (DBG) log("handleInetConditionHoldEnd: default network not connected - ignoring");
            return;
        }
        mDefaultInetConditionPublished = mDefaultInetCondition;
        sendInetConditionBroadcast(networkInfo);
        return;
    }

    public ProxyProperties getProxy() {
        synchronized (mDefaultProxyLock) {
            return mDefaultProxyDisabled ? null : mDefaultProxy;
        }
    }

    public void setGlobalProxy(ProxyProperties proxyProperties) {
        enforceChangePermission();
        synchronized (mGlobalProxyLock) {
            if (proxyProperties == mGlobalProxy) return;
            if (proxyProperties != null && proxyProperties.equals(mGlobalProxy)) return;
            if (mGlobalProxy != null && mGlobalProxy.equals(proxyProperties)) return;

            String host = "";
            int port = 0;
            String exclList = "";
            if (proxyProperties != null && !TextUtils.isEmpty(proxyProperties.getHost())) {
                mGlobalProxy = new ProxyProperties(proxyProperties);
                host = mGlobalProxy.getHost();
                port = mGlobalProxy.getPort();
                exclList = mGlobalProxy.getExclusionList();
            } else {
                mGlobalProxy = null;
            }
            ContentResolver res = mContext.getContentResolver();
            Settings.Secure.putString(res, Settings.Secure.GLOBAL_HTTP_PROXY_HOST, host);
            Settings.Secure.putInt(res, Settings.Secure.GLOBAL_HTTP_PROXY_PORT, port);
            Settings.Secure.putString(res, Settings.Secure.GLOBAL_HTTP_PROXY_EXCLUSION_LIST,
                    exclList);
        }

        if (mGlobalProxy == null) {
            proxyProperties = mDefaultProxy;
        }
        //sendProxyBroadcast(proxyProperties);
    }

    private void loadGlobalProxy() {
        ContentResolver res = mContext.getContentResolver();
        String host = Settings.Secure.getString(res, Settings.Secure.GLOBAL_HTTP_PROXY_HOST);
        int port = Settings.Secure.getInt(res, Settings.Secure.GLOBAL_HTTP_PROXY_PORT, 0);
        String exclList = Settings.Secure.getString(res,
                Settings.Secure.GLOBAL_HTTP_PROXY_EXCLUSION_LIST);
        if (!TextUtils.isEmpty(host)) {
            ProxyProperties proxyProperties = new ProxyProperties(host, port, exclList);
            synchronized (mGlobalProxyLock) {
                mGlobalProxy = proxyProperties;
            }
        }
    }

    public ProxyProperties getGlobalProxy() {
        synchronized (mGlobalProxyLock) {
            return mGlobalProxy;
        }
    }

    private void handleApplyDefaultProxy(ProxyProperties proxy) {
        if (proxy != null && TextUtils.isEmpty(proxy.getHost())) {
            proxy = null;
        }
        synchronized (mDefaultProxyLock) {
            if (mDefaultProxy != null && mDefaultProxy.equals(proxy)) return;
            if (mDefaultProxy == proxy) return;
            mDefaultProxy = proxy;

            if (!mDefaultProxyDisabled) {
                sendProxyBroadcast(proxy);
            }
        }
    }

    private void handleDeprecatedGlobalHttpProxy() {
        String proxy = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.HTTP_PROXY);
        if (!TextUtils.isEmpty(proxy)) {
            String data[] = proxy.split(":");
            String proxyHost =  data[0];
            int proxyPort = 8080;
            if (data.length > 1) {
                try {
                    proxyPort = Integer.parseInt(data[1]);
                } catch (NumberFormatException e) {
                    return;
                }
            }
            ProxyProperties p = new ProxyProperties(data[0], proxyPort, "");
            setGlobalProxy(p);
        }
    }

    private void sendProxyBroadcast(ProxyProperties proxy) {
        if (proxy == null) proxy = new ProxyProperties("", 0, "");
        if (DBG) log("sending Proxy Broadcast for " + proxy);
        Intent intent = new Intent(Proxy.PROXY_CHANGE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING |
            Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(Proxy.EXTRA_PROXY_INFO, proxy);
        mContext.sendStickyBroadcast(intent);
    }

    private static class SettingsObserver extends ContentObserver {
        private int mWhat;
        private Handler mHandler;
        SettingsObserver(Handler handler, int what) {
            super(handler);
            mHandler = handler;
            mWhat = what;
        }

        void observe(Context context) {
            ContentResolver resolver = context.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.HTTP_PROXY), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            mHandler.obtainMessage(mWhat).sendToTarget();
        }
    }

    private void log(String s) {
        Slog.d(TAG, s);
    }

    private void loge(String s) {
        Slog.e(TAG, s);
    }

    private void logw(String s) {
        Slog.w(TAG, s);
    }

    private void logv(String s) {
        Slog.v(TAG, s);
    }

    int convertFeatureToNetworkType(int networkType, String feature) {
        int usedNetworkType = networkType;

        if(networkType == ConnectivityManager.TYPE_MOBILE) {
            if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_MMS)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_MMS;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_SUPL)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_SUPL;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_DUN) ||
                    TextUtils.equals(feature, Phone.FEATURE_ENABLE_DUN_ALWAYS)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_DUN;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_HIPRI)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_HIPRI;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_FOTA)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_FOTA;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_IMS)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_IMS;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_CBS)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_CBS;
            } else {
                loge("Can't match any mobile netTracker!");
            }
        } else if (networkType == ConnectivityManager.TYPE_WIFI) {
            if (TextUtils.equals(feature, "p2p")) {
                usedNetworkType = ConnectivityManager.TYPE_WIFI_P2P;
            } else {
                loge("Can't match any wifi netTracker!");
            }
        } else {
            loge("Unexpected network type");
        }
        return usedNetworkType;
    }

    private static <T> T checkNotNull(T value, String message) {
        if (value == null) {
            throw new NullPointerException(message);
        }
        return value;
    }

    /**
     * Protect a socket from VPN routing rules. This method is used by
     * VpnBuilder and not available in ConnectivityManager. Permissions
     * are checked in Vpn class.
     * @hide
     */
    @Override
    public boolean protectVpn(ParcelFileDescriptor socket) {
        try {
            int type = mActiveDefaultNetwork;
            if (ConnectivityManager.isNetworkTypeValid(type)) {
                mVpn.protect(socket, mNetTrackers[type].getLinkProperties().getInterfaceName());
                return true;
            }
        } catch (Exception e) {
            // ignore
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                // ignore
            }
        }
        return false;
    }

    /**
     * Prepare for a VPN application. This method is used by VpnDialogs
     * and not available in ConnectivityManager. Permissions are checked
     * in Vpn class.
     * @hide
     */
    @Override
    public boolean prepareVpn(String oldPackage, String newPackage) {
        return mVpn.prepare(oldPackage, newPackage);
    }

    /**
     * Configure a TUN interface and return its file descriptor. Parameters
     * are encoded and opaque to this class. This method is used by VpnBuilder
     * and not available in ConnectivityManager. Permissions are checked in
     * Vpn class.
     * @hide
     */
    @Override
    public ParcelFileDescriptor establishVpn(VpnConfig config) {
        return mVpn.establish(config);
    }

    /**
     * Start legacy VPN and return an intent to VpnDialogs. This method is
     * used by VpnSettings and not available in ConnectivityManager.
     * Permissions are checked in Vpn class.
     * @hide
     */
    @Override
    public void startLegacyVpn(VpnConfig config, String[] racoon, String[] mtpd) {
        mVpn.startLegacyVpn(config, racoon, mtpd);
    }

    /**
     * Return the information of the ongoing legacy VPN. This method is used
     * by VpnSettings and not available in ConnectivityManager. Permissions
     * are checked in Vpn class.
     * @hide
     */
    @Override
    public LegacyVpnInfo getLegacyVpnInfo() {
        return mVpn.getLegacyVpnInfo();
    }

    /**
     * Callback for VPN subsystem. Currently VPN is not adapted to the service
     * through NetworkStateTracker since it works differently. For example, it
     * needs to override DNS servers but never takes the default routes. It
     * relies on another data network, and it could keep existing connections
     * alive after reconnecting, switching between networks, or even resuming
     * from deep sleep. Calls from applications should be done synchronously
     * to avoid race conditions. As these are all hidden APIs, refactoring can
     * be done whenever a better abstraction is developed.
     */
    public class VpnCallback {

        private VpnCallback() {
        }

        public void override(List<String> dnsServers, List<String> searchDomains) {
            if (dnsServers == null) {
                restore();
                return;
            }

            // Convert DNS servers into addresses.
            List<InetAddress> addresses = new ArrayList<InetAddress>();
            for (String address : dnsServers) {
                // Double check the addresses and remove invalid ones.
                try {
                    addresses.add(InetAddress.parseNumericAddress(address));
                } catch (Exception e) {
                    // ignore
                }
            }
            if (addresses.isEmpty()) {
                restore();
                return;
            }

            // Concatenate search domains into a string.
            StringBuilder buffer = new StringBuilder();
            if (searchDomains != null) {
                for (String domain : searchDomains) {
                    buffer.append(domain).append(' ');
                }
            }
            String domains = buffer.toString().trim();

            // Apply DNS changes.
            boolean changed = false;
            synchronized (mDnsLock) {
                changed = updateDns("VPN", "VPN", addresses, domains);
                mDnsOverridden = true;
            }
            if (changed) {
                bumpDns();
            }

            // Temporarily disable the default proxy.
            synchronized (mDefaultProxyLock) {
                mDefaultProxyDisabled = true;
                if (mDefaultProxy != null) {
                    sendProxyBroadcast(null);
                }
            }

            // TODO: support proxy per network.
        }

        public void restore() {
            synchronized (mDnsLock) {
                if (mDnsOverridden) {
                    mDnsOverridden = false;
                    mHandler.sendEmptyMessage(EVENT_RESTORE_DNS);
                }
            }
            synchronized (mDefaultProxyLock) {
                mDefaultProxyDisabled = false;
                if (mDefaultProxy != null) {
                    sendProxyBroadcast(mDefaultProxy);
                }
            }
        }
    }

    /* CNE related methods. */
    public void startCne() {
        if (!isCneStarted()) {
            qosManager = new QosManager(mContext, this);
            mCneStarted = true;
            if (isCneAware()) {
                mCneObj = makeVendorCne(qosManager);
                if (mCneObj != null) {
                    logv("Vendor CNE is starting up");
                    mLinkManager = (ILinkManager) mCneObj;
                    return;
                }
            } else {
                logv("CNE is disabled.");
            }
            mLinkManager = new LinkManager(mContext, this, qosManager);
        } else {
            loge("CNE already Started");
        }
    }

    private Object makeVendorCne(QosManager qosMgr) {
        try {
            PathClassLoader cneClassLoader =
                new PathClassLoader("/system/framework/com.quicinc.cne.jar",
                                    ClassLoader.getSystemClassLoader());
            Class cneClass = cneClassLoader.loadClass("com.quicinc.cne.CNE");
            Constructor cneConstructor = cneClass.getConstructor
                        (new Class[] {Context.class,ConnectivityService.class,QosManager.class});
                return cneConstructor.newInstance(mContext,this,qosMgr);
            } catch (Exception e) { // ignored; lives in system server
                loge("Caught exception in makeVendorCne " + e);
            }
        loge("Could not make vendor Cne obj. Disabling CNE");
        return null;
    }

    /** @hide
     * Has CNE been started on this device?
     * @return true of CNE has been started, otherwise false
     */
    public boolean isCneStarted() {
        return mCneStarted;
    }

    /** @hide
     * Check if this android device is CNE aware.
     * @return true if CNE is enabled on this device, otherwise false
     */
    public boolean isCneAware() {
        try {
            return  (SystemProperties.get(UseCne, "none")).equalsIgnoreCase("vendor");
        } catch ( Exception e) {
            logv("Received Exception while reading UseCne property, Disabling CNE");
        }
        return false;
    }
    /*
     * LinkSocket code is below here.
     */

    /**
     * Starts the process of getting a new link for the LinkSocket in a different thread.
     *
     *  @return A unique id that the socket will use for further communication.
     */
    public int requestLink(LinkCapabilities capabilities, String remoteIPAddress, IBinder binder) {
        if (VDBG) log("requestLink(capabilities, callback)");
        if (mCneStarted == false) return 0;
        return mLinkManager.requestLink(capabilities, remoteIPAddress, binder);
    }

    /**
     * Dissociates a LinkSocket with a given link.
     */
    public void releaseLink(int id) {
        if (VDBG) log("releaseLink(id=" + id + ")");
        if (mCneStarted == false) return;
        mLinkManager.releaseLink(id);
    }

    /**
     * Triggers QoS transaction using the specified local port
     */
    public boolean requestQoS(int id, int localPort, String localAddress) {
        if (VDBG) log("requestQoS(aport)");
        if (mCneStarted == false) return false;
        return mLinkManager.requestQoS(id, localPort, localAddress);
    }

    /**
     * Triggers QoS suspend
     */
    public boolean suspendQoS(int id) {
        if (VDBG) log("suspendQoS()");
        if (mCneStarted == false) return false;
        return mLinkManager.suspendQoS(id);
    }

    /**
     * Triggers QoS resume
     */
    public boolean resumeQoS(int id) {
        if (VDBG) log("resumeQoS()");
        if (mCneStarted == false) return false;
        return mLinkManager.resumeQoS(id);
    }

    /**
     * Removes Qos Registration from link manager
     */
    public boolean removeQosRegistration(int id) {
        if (VDBG) log("removeQosRegistration");
        if (mCneStarted == false) return false;
        return mLinkManager.removeQosRegistration(id);
    }

    public LinkCapabilities requestCapabilities(int id, int[] capability_keys) {
        if (VDBG) log("requestCapabilities(id=" + id + ", capabilities)");
        if (mCneStarted == false) return null;

        int netType;
        ExtraLinkCapabilities cap = new ExtraLinkCapabilities();
        for (int key : capability_keys) {
            String temp = null;
            switch (key) {
                case LinkCapabilities.Key.RO_MIN_AVAILABLE_FWD_BW:
                    if ((temp = mLinkManager.getMinAvailableForwardBandwidth(id)) != null)
                        cap.put(LinkCapabilities.Key.RO_MIN_AVAILABLE_FWD_BW,temp);
                    break;
                case LinkCapabilities.Key.RO_MAX_AVAILABLE_FWD_BW:
                    if ((temp = mLinkManager.getMaxAvailableForwardBandwidth(id)) != null)
                        cap.put(LinkCapabilities.Key.RO_MAX_AVAILABLE_FWD_BW, temp);
                    break;
                case LinkCapabilities.Key.RO_MIN_AVAILABLE_REV_BW:
                    if ((temp = mLinkManager.getMinAvailableReverseBandwidth(id)) != null)
                       cap.put(LinkCapabilities.Key.RO_MIN_AVAILABLE_REV_BW, temp);
                    break;
                case LinkCapabilities.Key.RO_MAX_AVAILABLE_REV_BW:
                    if ((temp = mLinkManager.getMaxAvailableReverseBandwidth(id)) != null)
                        cap.put(LinkCapabilities.Key.RO_MAX_AVAILABLE_REV_BW, temp);
                    break;
                case LinkCapabilities.Key.RO_CURRENT_FWD_LATENCY:
                    if ((temp = mLinkManager.getCurrentFwdLatency(id)) != null)
                        cap.put(LinkCapabilities.Key.RO_CURRENT_FWD_LATENCY, temp);
                    break;
                case LinkCapabilities.Key.RO_CURRENT_REV_LATENCY:
                    if ((temp = mLinkManager.getCurrentRevLatency(id)) != null)
                        cap.put(LinkCapabilities.Key.RO_CURRENT_REV_LATENCY, temp);
                    break;
                case LinkCapabilities.Key.RO_NETWORK_TYPE:
                    cap.put(LinkCapabilities.Key.RO_NETWORK_TYPE,
                            Integer.toString(mLinkManager.getNetworkType(id)));
                    break;
                case LinkCapabilities.Key.RO_BOUND_INTERFACE:
                    netType = mLinkManager.getNetworkType(id);
                    if (netType > 0) {
                        cap.put(LinkCapabilities.Key.RO_BOUND_INTERFACE,
                                mNetTrackers[netType].getLinkProperties().getInterfaceName());
                    } else {
                        cap.put(LinkCapabilities.Key.RO_BOUND_INTERFACE, "unknown");
                    }
                    break;
                case LinkCapabilities.Key.RO_PHYSICAL_INTERFACE:
                    netType = mLinkManager.getNetworkType(id);
                    if (netType > 0) {
                        cap.put(LinkCapabilities.Key.RO_PHYSICAL_INTERFACE,
                                mNetTrackers[netType].getLinkProperties().getInterfaceName());
                    } else {
                        cap.put(LinkCapabilities.Key.RO_PHYSICAL_INTERFACE, "unknown");
                    }
                    break;
               case LinkCapabilities.Key.RO_QOS_STATE:
                    if ((temp = mLinkManager.getQosState(id)) != null)
                        cap.put(LinkCapabilities.Key.RO_QOS_STATE, temp);
                    break;
            }
        }
        return cap;
    }

    public void setTrackedCapabilities(int id, int[] capabilities) {
        if (VDBG) log("setTrackedCapabilities(id=" + id + ", capabilities)");
    }

    /* Used by FmcProvider to start FMC */
    public boolean startFmc(IBinder listener) {
        ConnectivityManager cm =
            (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        NetworkInfo.State networkState =
            (networkInfo == null ? NetworkInfo.State.UNKNOWN : networkInfo.getState());

        mListener = (IFmcEventListener) IFmcEventListener.Stub.asInterface(listener);

        if (networkState != NetworkInfo.State.CONNECTED) {
            try {
                mListener.onFmcStatus(FmcNotifier.FMC_STATUS_FAILURE);
                return false;
            } catch (Exception e) {
                // lives in system server, never crash - catch them all
                loge("Exception in startFmc " + e);
            }
        }

        mFmcSM = FmcStateMachine.create(mContext, mListener, this);
        if (mFmcSM != null) {
//            try {
//                mListener.onFmcStatus(mFmcSM.getStatus());
//            } catch (RemoteException e) { // ignored; lives in system server
//                loge("Exception during onFmcStatus " + e);
//            }
            mFmcEnabled = mFmcSM.startFmc();
            log("mFmcEnabled=" + mFmcEnabled);
            return mFmcEnabled;
        } else {
            log("mFmcSM is null while calling startFmc");
            return false;
        }
    }

    /* Used by FmcProvider stop FMC */
    public boolean stopFmc(IBinder listener) {
        setFmcDisabled();
        if (mFmcSM != null) {
            return mFmcSM.stopFmc();
        } else {
            log("mFmcSM is null while calling stopFmc");
            return false;
        }
    }

    /* Used by FmcProvider to get FMC status */
    public int getFmcStatus(IBinder listener) {
        if(mFmcSM != null) {
            return mFmcSM.getStatus();
        } else {
            log("mFmcSM is null while calling startFmc");
            return -1;
        }
    }

    /* Used by FmcStateMachine to control network connections */
    public void setFmcDisabled() {
        mFmcEnabled = false;
        log("mFmcEnabled=" + mFmcEnabled);
    }

    /* Used by FmcStateMachine to control network connections */
    public boolean bringUpRat(int ratType) {
        log("BringUpRat called for ratType=" + ratType);

        if (ratType == ConnectivityManager.TYPE_MOBILE) {
            if (!getMobileDataEnabled()) {
                if (DBG) log("mobile data service disabled");
                reconnect(ratType);
                return false;
            }
        } else if (ratType != ConnectivityManager.TYPE_WIFI) {
            return false;
        } else {
            log("Unknown RatType = " + ratType);
            return false;
        }

        return reconnect(ratType);
    }

    /* Used by FmcStateMachine to control network connections */
    public boolean bringDownRat(int ratType) {
        log("BringDownRat called for ratType=" + ratType);

        if (ratType == ConnectivityManager.TYPE_MOBILE) {
            NetworkStateTracker network = mNetTrackers[ratType];
            teardown(network);
            return true;
        } else if (ratType != ConnectivityManager.TYPE_WIFI) {
            return false;
        } else {
            log("Unknown RatType = " + ratType);
            return false;
        }
    }

    /* Used by FmcStateMachine to control network connections */
    public boolean reconnect(int networkType) {
        NetworkStateTracker network = mNetTrackers[networkType];
        try{
            network.setTeardownRequested(true);
            log("Sending Network Connection Request to Driver.");
            return network.reconnect();
        } catch (NullPointerException e) { // ignored; lives in system server
            log("network Obj is Null" + e);
        }
        return false;
    }

    /**
     * This function will be used by apps to request to start the ANDSF parser
     *
     * @hide
     * @param filePath
     *            location of ANDSF file
     */
    public boolean updateOperatorPolicy(String filePath)
    {
        log("Updating Operator Policy");
        Object andsfParser;
        if ( mContext != null ) {
            try {
                PathClassLoader andsfClassLoader = new PathClassLoader(
                        "/system/framework/com.quicinc.cne.jar",
                        ClassLoader.getSystemClassLoader());
                Class andsfClass = andsfClassLoader
                        .loadClass("com.quicinc.cne.andsf.AndsfParser");

                Constructor andsfConstructor = andsfClass
                        .getConstructor(new Class[] { Context.class });
                Slog.d("ANDSF", "Updating Operator Policy");
                andsfParser = andsfConstructor.newInstance(mContext);

                Method myMethod = andsfClass.getMethod("updateAndsf",
                        new Class[] { String.class });

                Boolean passed = (Boolean) myMethod.invoke(andsfParser,
                        new Object[] { "data/connectivity/andsfCne.xml" });

                return passed;

            } catch (Exception e) { // ignored; lives in system server
                loge("update operator policy error" + e);
            }
        }
        log("Updating Operator Policy failed");
        return false;
    }

    /**
     * Used by cne to set active default network
     * @hide
     */
    public void setActiveDefaultNetwork (int type, String reason) {
        enforceChangePermission();
        if (!ConnectivityManager.isNetworkTypeValid(type)) {
            return;
        }
        mHandler.sendMessage(mHandler.obtainMessage(
                    ConnectivityServiceHSM.HSM_EVENT_CONNECTIVITY_SWITCH,
                    type,
                    INVALID_MSG_ARG,
                    reason));
    }

    /**
     * Used by cne to set dns lookup priority.
     * Sets the given networks dns server to highest priority for lookups.
     * @hide
     */
    public void prioritizeDns (int type) {
        enforceChangePermission();
        if (!ConnectivityManager.isNetworkTypeValid(type)) {
            return;
        }
        mHandler.sendMessage(mHandler.obtainMessage(
                    ConnectivityServiceHSM.HSM_EVENT_REPRIORITIZE_DNS, type, INVALID_MSG_ARG));
    }


    private final class ConnectivityServiceHSM extends StateMachine {

        // min HSM internal message
        static final int HSM_MSG_MIN = 5000;

        // handleConnect
        static final int HSM_HANDLE_CONNECT = HSM_MSG_MIN + 1;
        // handleDisconnect
        static final int HSM_HANDLE_DISCONNECT = HSM_MSG_MIN + 2;
        // handleConnecitivtyChange
        static final int HSM_HANDLE_CONNECTIVITY_CHANGE = HSM_MSG_MIN + 3;
        // handleDnsConfigurationChange
        static final int HSM_HANDLE_DNS_CONFIGURATION_CHANGE = HSM_MSG_MIN + 4;
        // handleConnectionFailure
        static final int HSM_HANDLE_CONNECTION_FAILURE = HSM_MSG_MIN + 5;
        // handleInetConditionChange
        static final int HSM_HANDLE_INET_CONDITION_CHANGE = HSM_MSG_MIN + 6;
        // handleInetConditionHoldEnd
        static final int HSM_HANDLE_INET_CONDITION_HOLD_END = HSM_MSG_MIN + 7;
        // enforcePreference
        static final int HSM_EVENT_ENFORCE_PREFERENCE = HSM_MSG_MIN + 8;
        // restoredns
        static final int HSM_EVENT_RESTORE_DNS = HSM_MSG_MIN + 9;
        // handleDnsReprioritization
        static final int HSM_EVENT_REPRIORITIZE_DNS = HSM_MSG_MIN + 10;
        // handleConnectivitySwitch (int toNetType)
        static final int HSM_EVENT_CONNECTIVITY_SWITCH = HSM_MSG_MIN + 11;

        private int myDefaultDnsNet;
        // List to track multiple active default networks
        private ConnectedDefaultNetworkSet mConnectedDefaultNetworks;
        //Maximum simultaneous default networks supported in normal operation.

        public void sendMessageImmediate (Message msg) {
            sendMessageAtFrontOfQueue(msg);
        }

        /**
         * Class that tracks active default networks.
         * Allows simultaneous Mobile and Wifi default networks
         */
        private class ConnectedDefaultNetworkSet {

            private final int MAX_SIMULTANEOUS_DEFAULTS = 2;
            private Collection<Integer> mDefaultNetworks;

            // Empty constructor
            public ConnectedDefaultNetworkSet() {
                mDefaultNetworks = new HashSet<Integer>(2);
            }

            public int size() {
                return mDefaultNetworks.size();
            }

            public boolean add(int i) {
                Integer j = new Integer(i);
                // restrict size to max simultaneous default networks
                if (mDefaultNetworks.size() >= MAX_SIMULTANEOUS_DEFAULTS) return false;
                // Allow only wifi and mobile for simultaneous connection
                if ((i != TYPE_WIFI) && (i != TYPE_MOBILE)) return false;
                if (mDefaultNetworks.contains(j)) return true;
                return mDefaultNetworks.add(j);
            }

            public boolean remove(int i) {
                return mDefaultNetworks.remove(new Integer(i));
            }

            public boolean contains(int i) {
                return mDefaultNetworks.contains(new Integer(i));
            }

            public boolean isHigherPriorityNetwork(int i) {
                int res = 0;
                if (mDefaultNetworks.isEmpty()) return true;
                for (Integer type : mDefaultNetworks) {
                    res += (mNetConfigs[i].priority > mNetConfigs[type.intValue()].priority) ? 1:0;
                }
                return ((res > 0) ? (res == mDefaultNetworks.size()) : false);
            }

            public Collection<Integer> getActiveDefaults() {
                return Collections.unmodifiableCollection(mDefaultNetworks);
            }

            public void clear() {
                mDefaultNetworks.clear();
            }

        }

        private State mDefaultConnectivityState;
        private SmartConnectivityState  mSmartConnectivityState;
        private DualConnectivityState mWifiDefaultState;
        private DualConnectivityState mMobileDefaultState;
        private FmcInitialState mFmcInitialState;
        private FmcActiveState mFmcActiveState;
        private State myInitialState;

        /**
         *
         * STATE MAP
         *                     DefaultConnectivityState
         *                                |           \
         *                     SmartConnectivityState  FmcInitialState
         *                            /             \           \_________
         *                           /               \                    \
         *  Dual connectivity:  WifiDefaultState --  WwanDefaultState   FmcActiveState
         *
         */

        ConnectivityServiceHSM(Context context, String name, Looper looper) {
            super(name, looper);

            mConnectedDefaultNetworks = new ConnectedDefaultNetworkSet();

            mDefaultConnectivityState = new DefaultConnectivityState();
            addState(mDefaultConnectivityState);
            //TODO move from swim property check to cneFeatureConfig when ready
            final String isTrue = "true";
            if (isTrue.equalsIgnoreCase(SystemProperties.get("persist.cne.fmc.mode"))) {
                // fmc mode device
                mFmcInitialState = new FmcInitialState();
                addState(mFmcInitialState, mDefaultConnectivityState);
                mFmcActiveState = new FmcActiveState();
                addState(mFmcActiveState, mFmcInitialState);
                myInitialState = mFmcInitialState;
            } else if (isTrue.equalsIgnoreCase(SystemProperties.get("persist.cne.UseSwim"))) {
                // cne dual net mode enabled;
                mSmartConnectivityState = new SmartConnectivityState();
                addState(mSmartConnectivityState, mDefaultConnectivityState);
                mWifiDefaultState = new WifiDefaultState();
                addState(mWifiDefaultState, mSmartConnectivityState);
                mMobileDefaultState = new MobileDefaultState();
                addState(mMobileDefaultState, mSmartConnectivityState);
                myInitialState = mSmartConnectivityState;
            }  else {
                // cne single net, cne disabled mode device.
                myInitialState = mDefaultConnectivityState;
            }

            setInitialState(myInitialState);
        }

        private final class DefaultConnectivityState extends State {
            public DefaultConnectivityState() {
            }

            @Override
            public void enter() {
                if (DBG) log( "ConnectivityServiceHSM entering " + getCurrentState().getName());
            }

            @Override
            public void exit() {
                if (DBG) log( "ConnectivityServiceHSM leaving " + getCurrentState().getName());
            }

            @Override
            public boolean processMessage(Message msg) {

                if (DBG) log("Actual State: DefaultConnectivityState, Current State: " +
                        getCurrentState().getName() + ".processMessage what=" + msg.what);

                NetworkInfo info;
                switch (msg.what) {
                    case NetworkStateTracker.EVENT_STATE_CHANGED:
                    {
                        info = (NetworkInfo) msg.obj;
                        int type = info.getType();
                        NetworkInfo.State state = info.getState();

                        if (VDBG || (state == NetworkInfo.State.CONNECTED) ||
                                (state == NetworkInfo.State.DISCONNECTED)) {
                            log("ConnectivityChange for " +
                                info.getTypeName() + ": " +
                                state + "/" + info.getDetailedState());
                        }

                        // Connectivity state changed:
                        // [31-13] Reserved for future use
                        // [12-9] Network subtype (for mobile network, as defined
                        //         by TelephonyManager)
                        // [8-3] Detailed state ordinal (as defined by
                        //         NetworkInfo.DetailedState)
                        // [2-0] Network type (as defined by ConnectivityManager)
                        int eventLogParam = (info.getType() & 0x7) |
                                ((info.getDetailedState().ordinal() & 0x3f) << 3) |
                                (info.getSubtype() << 9);
                        EventLog.writeEvent(EventLogTags.CONNECTIVITY_STATE_CHANGED,
                                eventLogParam);

                        if (info.getDetailedState() ==
                                NetworkInfo.DetailedState.FAILED) {
                            sendMessageAtFrontOfQueue(HSM_HANDLE_CONNECTION_FAILURE, info);
                        } else if (state == NetworkInfo.State.DISCONNECTED) {
                            sendMessageAtFrontOfQueue(HSM_HANDLE_DISCONNECT, info);
                        } else if (state == NetworkInfo.State.SUSPENDED) {
                            // TODO: need to think this over.
                            // the logic here is, handle SUSPENDED the same as
                            // DISCONNECTED. The only difference being we are
                            // broadcasting an intent with NetworkInfo that's
                            // suspended. This allows the applications an
                            // opportunity to handle DISCONNECTED and SUSPENDED
                            // differently, or not.
                            sendMessageAtFrontOfQueue(HSM_HANDLE_DISCONNECT, info);
                        } else if (state == NetworkInfo.State.CONNECTED) {
                            sendMessageAtFrontOfQueue(HSM_HANDLE_CONNECT, info);
                        }
                        break;
                    }
                    case NetworkStateTracker.EVENT_CONFIGURATION_CHANGED:
                    {
                        info = (NetworkInfo) msg.obj;
                        // TODO: Temporary allowing network configuration
                        //       change not resetting sockets.
                        //       @see bug/4455071
                        sendMessageAtFrontOfQueue(obtainMessage(
                                    HSM_HANDLE_CONNECTIVITY_CHANGE,
                                    info.getType(), 0));
                        break;
                    }
                    case EVENT_CLEAR_NET_TRANSITION_WAKELOCK:
                    {
                        String causedBy = null;
                        synchronized (ConnectivityService.this) {
                            if (msg.arg1 == mNetTransitionWakeLockSerialNumber &&
                                    mNetTransitionWakeLock.isHeld()) {
                                mNetTransitionWakeLock.release();
                                causedBy = mNetTransitionWakeLockCausedBy;
                            }
                        }
                        if (causedBy != null) {
                            log("NetTransition Wakelock for " + causedBy + " released by timeout");
                        }
                        break;
                    }
                    case EVENT_RESTORE_DEFAULT_NETWORK:
                    {
                        FeatureUser u = (FeatureUser)msg.obj;
                        u.expire();
                        break;
                    }
                    case EVENT_INET_CONDITION_CHANGE:
                    {
                        sendMessageAtFrontOfQueue(obtainMessage(
                                    HSM_HANDLE_INET_CONDITION_CHANGE, msg.arg1, msg.arg2));
                        break;
                    }
                    case EVENT_INET_CONDITION_HOLD_END:
                    {
                        sendMessageAtFrontOfQueue(obtainMessage(
                                HSM_HANDLE_INET_CONDITION_HOLD_END, msg.arg1, msg.arg2));
                        break;
                    }
                    case EVENT_SET_NETWORK_PREFERENCE:
                    {
                        int preference = msg.arg1;
                        handleSetNetworkPreference(preference);
                        break;
                    }
                    case EVENT_SET_MOBILE_DATA:
                    {
                        boolean enabled = (msg.arg1 == ENABLED);
                        handleSetMobileData(enabled);
                        break;
                    }
                    case EVENT_APPLY_GLOBAL_HTTP_PROXY:
                    {
                        handleDeprecatedGlobalHttpProxy();
                        break;
                    }
                    case EVENT_SET_DEPENDENCY_MET:
                    {
                        boolean met = (msg.arg1 == ENABLED);
                        handleSetDependencyMet(msg.arg2, met);
                        break;
                    }
                    case EVENT_RESTORE_DNS:
                    {
                        sendMessageAtFrontOfQueue(HSM_EVENT_RESTORE_DNS);
                        break;
                    }
                    case EVENT_SEND_STICKY_BROADCAST_INTENT:
                    {
                        Intent intent = (Intent)msg.obj;
                        sendStickyBroadcast(intent);
                        break;
                    }
                    case EVENT_SET_POLICY_DATA_ENABLE: {
                        final int networkType = msg.arg1;
                        final boolean enabled = msg.arg2 == ENABLED;
                        handleSetPolicyDataEnable(networkType, enabled);
                        break;
                    }

                    /**
                     * Default connectivity service event handler implementation used
                     * by the default connectivity state
                     */
                    case HSM_HANDLE_CONNECT:
                        info = (NetworkInfo) msg.obj;
                        handleConnect(info);
                        break;
                    case HSM_HANDLE_DISCONNECT:
                        info = (NetworkInfo) msg.obj;
                        handleDisconnect(info);
                        break;
                    case HSM_HANDLE_CONNECTIVITY_CHANGE:
                    {
                        int type = msg.arg1;
                        boolean doReset = (msg.arg2 == 1);
                        handleConnectivityChange(type, doReset);
                        break;
                    }
                    case HSM_HANDLE_DNS_CONFIGURATION_CHANGE:
                        handleDnsConfigurationChange(msg.arg1);
                        break;
                    case HSM_HANDLE_CONNECTION_FAILURE:
                        info = (NetworkInfo) msg.obj;
                        handleConnectionFailure(info);
                        break;
                    case HSM_HANDLE_INET_CONDITION_CHANGE:
                    {
                        int netType = msg.arg1;
                        int condition = msg.arg2;
                        handleInetConditionChange(netType, condition);
                        break;
                    }
                    case HSM_HANDLE_INET_CONDITION_HOLD_END:
                    {
                        int netType = msg.arg1;
                        int sequence = msg.arg2;
                        handleInetConditionHoldEnd(netType, sequence);
                        break;
                    }
                    case HSM_EVENT_ENFORCE_PREFERENCE:
                        enforcePreference();
                        break;
                    case HSM_EVENT_RESTORE_DNS:
                    {
                        if (mActiveDefaultNetwork != -1) {
                            handleDnsConfigurationChange(mActiveDefaultNetwork);
                        }
                        break;
                    }
                    default:
                        loge(getCurrentState().getName() + " ignoring unhandled message");
                }
                // runs in system-server and is the root parent state for all
                // never return not_handled
                return true;
            }
        }

        private final class FmcInitialState extends State {
            @Override
            public void enter() {
                if (DBG) log( "ConnectivityServiceHSM entering " + getCurrentState().getName());
            }

            @Override
            public void exit() {
                if (DBG) log( "ConnectivityServiceHSM leaving " + getCurrentState().getName());
            }

            @Override
            public boolean processMessage(Message msg) {
                if (DBG) log(getCurrentState().getName() + ".processMessage what=" + msg.what);
                boolean ret = NOT_HANDLED; // by default leave all events as not_handled
                switch (msg.what) {
                    case HSM_HANDLE_CONNECT:
                    {
                        ret = handleConnect(msg);
                        break;
                    }

                    default:
                    log("Unhandled in this state.");
                }
                return ret;
            }

            private boolean handleConnect(Message msg) {
                NetworkInfo info = (NetworkInfo) msg.obj;
                if (isFmcActiveState(info)) {
                    deferMessage(msg); //handle this in fmcactivestate
                    transitionTo(mFmcActiveState);
                    return HANDLED;
                }
                return NOT_HANDLED;
            }

            /* If fmcEnabled is true, current default network is wifi &
               next available network is 3G then return true */
            private boolean isFmcActiveState(NetworkInfo info) {
                final int netType = info.getType();
                if (DBG) log("NetType = " + netType);
                if (mFmcEnabled &&
                        (mActiveDefaultNetwork == TYPE_WIFI) && (netType == TYPE_MOBILE)) {
                    return true;
                }
                return false;
            }
        }

        private final class FmcActiveState extends State {
            private RouteInfo wlanDefault = null;
            private RouteInfo wwanFmc = null;

            @Override
            public void enter() {
                if (DBG) log( "ConnectivityServiceHSM entering " + getCurrentState().getName());
                //when fmc is enabled, mobile is the default connection. So remove wifi
                //default routes. fmc will add host specific routes over wifi.
                LinkProperties curLp = mCurrentLinkProperties[TYPE_WIFI];
                removeRoutes(curLp);
            }

            @Override
            public void exit() {
                if (DBG) log( "ConnectivityServiceHSM leaving " + getCurrentState().getName());
            }

            @Override
            public boolean processMessage(Message msg) {
                if (DBG) log(getCurrentState().getName() + ".processMessage what=" + msg.what);
                boolean ret = NOT_HANDLED; // by default leave all events as not_handled
                switch (msg.what) {
                    case HSM_HANDLE_CONNECT:
                    {
                        ret = handleConnect(msg);
                        break;
                    }
                    case HSM_HANDLE_DISCONNECT:
                    {
                        ret = handleDisconnect(msg);
                        break;
                    }

                    default: {
                        if (DBG) log(getCurrentState().getName()+ " handle disconnect");
                    }
                }
                return ret;
            }

            private void removeRoutes(LinkProperties lp) {
                if (lp == null) return;
                Collection<RouteInfo> curRoutes = lp.getRoutes();

                if (curRoutes != null) {
                    for(RouteInfo r: curRoutes) {
                        removeRoute(lp, r, TO_DEFAULT_TABLE);
                    }
                }
            }

            private void addRoutes(LinkProperties lp) {
                if (lp == null) return;
                Collection<RouteInfo> newRoutes = lp.getRoutes();
                if (newRoutes != null) {
                    for(RouteInfo r: newRoutes) {
                        addRoute(lp, r, TO_DEFAULT_TABLE);
                    }
                }
            }

            private boolean handleConnect(Message msg) {
                if (DBG) log(getCurrentState().getName()+ " handle connect");
                NetworkInfo info = (NetworkInfo) msg.obj;
                final int type = info.getType();

                /* Don't do teardowns when FMC enabled. Rest is same as default implementation */
                mActiveDefaultNetwork = type;

                mDefaultInetConditionPublished = 0;
                mDefaultConnectionSequence++;
                mInetConditionChangeInFlight = false;

                final NetworkStateTracker thisNet = mNetTrackers[type];
                thisNet.setTeardownRequested(false);
                updateNetworkSettings(thisNet);

                ConnectivityService.this.handleConnectivityChange(type, false);
                sendConnectedBroadcastDelayed(info, getConnectivityChangeDelay());
                // notify battery stats service about this network
                final String iface = thisNet.getLinkProperties().getInterfaceName();
                if (iface != null) {
                    try {
                        BatteryStatsService.getService().noteNetworkInterfaceType(iface, type);
                    } catch (RemoteException e) {
                        // ignored; service lives in system_server
                    }
                }

                return HANDLED;
            }

            private boolean handleDisconnect(Message msg) {
                if (DBG) log(getCurrentState().getName()+ " handle disconnect");

                final int type = ((NetworkInfo) msg.obj).getType();

                /* if mobile connection lost but Wifi still connected, restore Wifi routes.
                 * else just allow default handling of HSM_HANDLE_DISCONNECT.
                 * If Wifi disconnects, then FMC will stop since the mobile connection
                 * will also be brought down (as its a tunnel over Wifi), in which case no need
                 * to restore routes. */
                if (type == TYPE_MOBILE) {
                    LinkProperties curLp = mCurrentLinkProperties[TYPE_WIFI];
                    addRoutes(curLp);
                    mActiveDefaultNetwork = TYPE_WIFI;
                    deferMessage(msg);
                    transitionTo(mFmcInitialState);
                    return HANDLED;
                } else if (type == TYPE_WIFI) {
                    deferMessage(msg);
                    transitionTo(mFmcInitialState);
                    return HANDLED;
                }
                return NOT_HANDLED;
            }

        }

        /**
         * Smart Connectivity State.
         * CS will be in this state when CNE smart connectivity is enabled and FMC is disabled.
         * Supports simultaneous wif + mobile connections.
         * Is a parent state for other smart connectivity states.
         */
        private final class SmartConnectivityState extends State {

            public SmartConnectivityState () {
            }

            @Override
            public void enter() {
                if (DBG) log( "ConnectivityServiceHSM entering " + getCurrentState().getName());
                //reset metric of default routes for wwan & wifi
                mRouteAttributes[TYPE_WIFI].setMetric(0);
                mRouteAttributes[TYPE_MOBILE].setMetric(0);
                //make wifi higher priority dns than 3g by default.
                myDefaultDnsNet = TYPE_WIFI;
            }

            @Override
            public void exit() {
                if (DBG) log( "ConnectivityServiceHSM leaving " + getCurrentState().getName());
            }

            /**
             * connected.
             */
            private boolean isNetworkSimultaneitySupported(NetworkInfo info) {
                final int type = info.getType();
                boolean ret = false;
                if (mNetConfigs[type].isDefault()) {
                    mConnectedDefaultNetworks.add(type);
                    if (mConnectedDefaultNetworks.size() > 1) {
                        ret = true;
                    }
                }
                return ret;
            }

            @Override
            public boolean processMessage(Message msg) {
                if (DBG) log(getCurrentState().getName() + ".processMessage what=" + msg.what);
                NetworkInfo info = null;
                boolean ret = NOT_HANDLED; // by default leave all events as not_handled
                switch (msg.what) {
                    case HSM_HANDLE_CONNECT :
                    {
                        info = (NetworkInfo) msg.obj;
                        if (isNetworkSimultaneitySupported(info)) {
                            log("Dual Connectivity Mode detected");
                            deferMessage(msg);
                            if (mActiveDefaultNetwork == TYPE_WIFI) {
                                transitionTo(mWifiDefaultState);
                            } else {
                                transitionTo(mMobileDefaultState);
                            }
                            ret = HANDLED;
                        }
                        break;
                    }
                    case HSM_HANDLE_DISCONNECT:
                    {
                        info = (NetworkInfo) msg.obj;
                        mConnectedDefaultNetworks.remove(info.getType());
                        break;
                    }
                    default: ret = NOT_HANDLED;
                }
                return ret;
            }
        }

        /**
         * Dual connectivity Mobile default state.
         * Mobile is treated as the mActiveDefaultNetwork in this state
         */
        private final class MobileDefaultState extends DualConnectivityState {
            public MobileDefaultState () {
                myDefaultNet = TYPE_MOBILE;
                otherDefaultNet = TYPE_WIFI;
            }

            @Override
            public void enter() {
                if (DBG) log( "ConnectivityServiceHSM entering " + getCurrentState().getName());
                runOnEnter();
            }

            @Override
            public void exit() {
                if (DBG) log( "ConnectivityServiceHSM leaving " + getCurrentState().getName());
            }

            @Override
            protected void transitionToOther() {
                if (DBG) log(getCurrentState().getName() + " transitionToOther");
                transitionTo(mWifiDefaultState); // transition to mWifiDefaultState
            }

        }

        /**
         * Dual connectivity Wifi default state.
         * Wifi is treated as the mActiveDefaultNetwork in this state
         */
        private final class WifiDefaultState extends DualConnectivityState {
            public WifiDefaultState () {
                myDefaultNet = TYPE_WIFI;
                otherDefaultNet = TYPE_MOBILE;
            }

            @Override
            public void enter() {
                if (DBG) log( "ConnectivityServiceHSM entering " + getCurrentState().getName());
                runOnEnter();
            }

            @Override
            public void exit() {
                if (DBG) log( "ConnectivityServiceHSM leaving " + getCurrentState().getName());
            }

            @Override
            protected void transitionToOther() {
                if (DBG) log(getCurrentState().getName() + " transitionToOther");
                transitionTo(mMobileDefaultState); // transition to mMobileDefaultState
            }

        }

        /**
         * Abstract class that provides framework to support 3G and Wifi
         * simultaneously.
         * All dual connectivity states except FMC must extend from this class
         */
        private abstract class DualConnectivityState extends State {

            protected int myDefaultNet;
            protected int otherDefaultNet,
                          mOtherDefaultInetCondition = 0,
                          mOtherDefaultInetConditionPublished = 0,
                          mOtherDefaultConnectionSequence = 0;
            protected boolean mOtherInetConditionChangeInFlight = false;

            @Override
            public boolean processMessage(Message msg) {
                if (DBG) log(getCurrentState().getName() + ".processMessage what=" + msg.what);
                NetworkInfo info;
                boolean ret = NOT_HANDLED; // by default leave all messages as unhandled
                switch (msg.what) {
                    case HSM_HANDLE_CONNECT:
                    {
                        info = (NetworkInfo) msg.obj;
                        int r = handleConnect(info);
                        if (r == 0) {
                            ret = HANDLED; // handled connect in this state
                        } else if (r == -1) {
                            ret = NOT_HANDLED; // let parent process this event
                        } else {
                            deferMessage(msg);
                            transitionTo(mSmartConnectivityState);
                            ret = HANDLED; // state change, transition to parent and then process
                        }
                        break;
                    }
                    case HSM_HANDLE_DISCONNECT:
                    {
                        info = (NetworkInfo) msg.obj;
                        int r = handleDisconnect(info); //private handler
                        if (r == 0) {
                            ret = NOT_HANDLED;
                        } else if (r == -1) {
                            deferMessage(msg);
                            transitionTo(mSmartConnectivityState);
                            ret = HANDLED;
                        } else {
                            transitionTo(mSmartConnectivityState);
                            ret = HANDLED;
                        }
                        break;
                    }
                    case HSM_HANDLE_CONNECTIVITY_CHANGE:
                    {
                        int type = msg.arg1;
                        boolean doReset = (msg.arg2 == 1);
                        handleConnectivityChange(type, doReset); //private handler
                        ret = HANDLED;
                        break;
                    }
                    case HSM_HANDLE_DNS_CONFIGURATION_CHANGE:
                    {
                        int type = msg.arg1;
                        handleDnsConfigurationChange(type); //private handler
                        ret = HANDLED;
                        break;
                    }
                    case HSM_HANDLE_CONNECTION_FAILURE:
                        break;
                    case HSM_HANDLE_INET_CONDITION_CHANGE:
                    {
                        int netType = msg.arg1;
                        int condition = msg.arg2;
                        if (handleInetConditionChange(netType, condition)) {
                            ret = HANDLED;
                        }
                        break;
                    }
                    case HSM_HANDLE_INET_CONDITION_HOLD_END:
                    {
                        int netType = msg.arg1;
                        int sequence = msg.arg2;
                        if (handleInetConditionHoldEnd(netType, sequence)) {
                            ret = HANDLED;
                        }
                        break;
                    }
                    case HSM_EVENT_ENFORCE_PREFERENCE:
                    {
                        loge("enforcing network preference not allowed in dual connectivity state");
                        ret = HANDLED;
                        break;
                    }
                    case HSM_EVENT_RESTORE_DNS:
                    {
                        handleDnsConfigurationChange(myDefaultDnsNet);
                        ret = HANDLED;
                        break;
                    }
                    case HSM_EVENT_CONNECTIVITY_SWITCH:
                    {
                        String reason = (String) msg.obj;
                        int type = msg.arg1;
                        if ( ! handleConnectivitySwitch(type, reason)) {
                            deferMessage(msg);
                            transitionToOther();
                        }
                        ret = HANDLED;
                        break;
                    }
                    case HSM_EVENT_REPRIORITIZE_DNS:
                    {
                        int type = msg.arg1;
                        if (type != myDefaultDnsNet) {
                            handleDnsReprioritization(type);
                        } else {
                            logw("Dns is already prioritized for network " + type);
                        }
                        ret = HANDLED;
                        break;
                    }
                    default:
                        ret = NOT_HANDLED;
                        if (DBG) {
                            log(getCurrentState().getName() +
                                     ": no handler for message="+ msg.what);
                        }
                }
                return ret;
            }

            /**
             * state specific route updates to be run as the first thing on
             * entering this state.
             */
            protected void runOnEnter() {
                if (DBG) log(getCurrentState().getName() + " runOnEnter");
                // reset to implementation state specific route metric and update default route
                mRouteAttributes[myDefaultNet].setMetric(0);
                mRouteAttributes[otherDefaultNet].setMetric(20);
                updateDefaultRouteMetric(myDefaultNet);
            }

            /**
             * To be implemented by implementing class of dualconnectivitystate
             * to transition from my to other default net state.
             */
            protected abstract void transitionToOther();


            /**
             * Broadcasts an intent indicating switch in default connectivity
             * from one network to another.
             * Modelled on the intent sent out in handleConnectionFailure
             * Is valid only in dual network states
             * Reason is provided by the implementing class
             */
            protected void sendConnectivitySwitchBroadcast(String reason) {

                if (DBG) log(getCurrentState().getName() + " sendConnectivitySwitchBroadcast");
                NetworkInfo newNetInfo = mNetTrackers[myDefaultNet].getNetworkInfo();
                NetworkInfo oldNetInfo = mNetTrackers[otherDefaultNet].getNetworkInfo();

                Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
                intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, oldNetInfo);
                if (reason != null && reason.length() > 0) {
                    intent.putExtra(ConnectivityManager.EXTRA_REASON, reason);
                }
                if (oldNetInfo.isFailover()) {
                    intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
                }
                intent.putExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO, newNetInfo);
                intent.putExtra(ConnectivityManager.EXTRA_INET_CONDITION,
                                mDefaultInetConditionPublished);

                final Intent immediateIntent = new Intent(intent);
                immediateIntent.setAction(CONNECTIVITY_ACTION_IMMEDIATE);
                sendStickyBroadcast(immediateIntent);
                sendStickyBroadcast(intent);

                sendConnectedBroadcast(newNetInfo);
            }

            /**
             * Switches default connectivity from one active default to another.
             * Same method is used by both pre-transition state and the post
             * transition state.
             * handles routes and intents in post transition
             * returns:
             *      true  - event handled completely, don't transition.
             *      false - event handled in old state, defermsg and transition to new state
             *              and handle the rest of it
             */
            protected boolean handleConnectivitySwitch (int netType, String reason) {

                if (DBG) log(getCurrentState().getName() + " handleConnectivitySwitch");
                boolean ret = true; // true - dont transition, false - transition

                if ( ! mConnectedDefaultNetworks.contains(netType) ) {
                    logw(" Network " + netType + " not supported for default connectivity");
                    return ret;
                }
                // mactive isn't updated yet, is updated post switch.
                if (mActiveDefaultNetwork == netType) {
                    logw(" Network" + netType + " is already default");
                    return ret;
                }
                if (myDefaultNet == netType) {
                    //post switch handling in new state
                    mActiveDefaultNetwork = myDefaultNet;
                    updateDefaultRouteMetric(otherDefaultNet);
                    sendConnectivitySwitchBroadcast(reason);
                } else {
                    //pre switch handling in old state
                    removeDefaultRoutes(myDefaultNet);
                    ret = false;  // transition to otherstate
                }
                return ret;
            }

            /**
             * Updates name server priority and sets up the dns cache.
             * can be double null in which case it will only update name server
             * priority.
             * Caller must grab mDnsLock
             */
            private boolean updateDns(String iface, Collection<InetAddress> netDnses) {

                if (DBG) log(getCurrentState().getName() + " updateDns");
                boolean changed = false;
                int last = 0;
                List<InetAddress> dnses = new ArrayList<InetAddress>();

                LinkProperties mlp = mNetTrackers[myDefaultNet].getLinkProperties();
                LinkProperties olp = mNetTrackers[otherDefaultNet].getLinkProperties();

                if (mlp != null) dnses.addAll(mlp.getDnses());
                if (olp != null) {
                    if (otherDefaultNet == myDefaultDnsNet) {
                        dnses.addAll(0, olp.getDnses());
                    } else {
                        dnses.addAll(olp.getDnses());
                    }
                }

                if (dnses.size() == 0 && mDefaultDns != null) {
                    ++last;
                    String value = mDefaultDns.getHostAddress();
                    if (!value.equals(SystemProperties.get("net.dns1"))) {
                        if (DBG) {
                            loge("no dns provided - using " + value);
                        }
                        changed = true;
                        SystemProperties.set("net.dns1", value);
                    }
                } else {
                    for (InetAddress dns : dnses) {
                        ++last;
                        String key = "net.dns" + last;
                        String value = dns.getHostAddress();
                        if (!changed && value.equals(SystemProperties.get(key))) {
                            continue;
                        }
                        if (VDBG) {
                            log("adding dns " + value );
                        }
                        changed = true;
                        SystemProperties.set(key, value);
                    }
                }
                for (int i = last + 1; i <= mNumDnsEntries; ++i) {
                    String key = "net.dns" + i;
                    if (VDBG) log("erasing " + key);
                    changed = true;
                    SystemProperties.set(key, "");
                }
                mNumDnsEntries = last;

                if (changed) {
                    try {
                        if (iface != null && netDnses != null) {
                            // only update interface dns cache for the changed iface.
                            mNetd.setDnsServersForInterface( iface,
                                    NetworkUtils.makeStrings(netDnses) );
                        }
                        // set appropriate default iface for dns cache
                        String defDnsIface = null;
                        if (myDefaultDnsNet == myDefaultNet && mlp != null) {
                            defDnsIface = mlp.getInterfaceName();
                        } else if (olp != null) {
                            defDnsIface = olp.getInterfaceName();
                        }
                        if (!TextUtils.isEmpty(defDnsIface)) {
                            mNetd.setDefaultInterfaceForDns(defDnsIface);
                        }
                    } catch (Exception e) {
                        if (VDBG) loge("exception setting default dns interface: " + e);
                    }
                }
                return changed;
            }

            /**
             * Reprioritizes the specified networks name servers.
             */
            protected void handleDnsReprioritization (int netType) {

                if (DBG) log(getCurrentState().getName() + " handleDnsReprioritization");
                // only change dns priority for networks we can handle in this state.
                if (!mConnectedDefaultNetworks.contains(netType)) {
                    logw("Cannot prioritize dns for unsupported type" + netType);
                    return;
                }

                log("Prioritizing Dns for network " + netType);

                synchronized (mDnsLock) {
                    myDefaultDnsNet = netType;
                    if (!mDnsOverridden) {
                        if (updateDns(null, null)) bumpDns();
                    }
                }
            }

            /**
             * Same as default state's implementation, with exception of calling
             * custom updateDns method.
             */
            protected void handleDnsConfigurationChange(int netType) {

                if (DBG) log(getCurrentState().getName() + " handleDnsConfigurationChange");
                // add default net's dns entries
                NetworkStateTracker nt = mNetTrackers[netType];
                if (nt != null && nt.getNetworkInfo().isConnected() && !nt.isTeardownRequested()) {
                    LinkProperties p = nt.getLinkProperties();
                    if (p == null) return;
                    Collection<InetAddress> dnses = p.getDnses();
                    boolean changed = false;
                    if (mNetConfigs[netType].isDefault()) {
                        String network = nt.getNetworkInfo().getTypeName();
                        synchronized (mDnsLock) {
                            if (!mDnsOverridden) {
                                changed = updateDns(p.getInterfaceName(), dnses);
                            }
                        }
                    } else {
                        try {
                            mNetd.setDnsServersForInterface(p.getInterfaceName(),
                                    NetworkUtils.makeStrings(dnses));
                        } catch (Exception e) {
                            if (VDBG) loge("exception setting dns servers: " + e);
                        }
                        // set per-pid dns for attached secondary nets
                        List pids = mNetRequestersPids[netType];
                        for (int y=0; y< pids.size(); y++) {
                            Integer pid = (Integer)pids.get(y);
                            changed = writePidDns(dnses, pid.intValue());
                        }
                    }
                    if (changed) bumpDns();
                }
            }

            /**
             * Handle a {@code DISCONNECTED} event of other Default network when
             * my default network is still connected.
             * Defer message to parent state for processing of my default net type.
             * handle non-defaults in parent
             * handle and transition to parent when other disconnects.
             * @param info the {@code NetworkInfo} for the network
             * returns:
             *      0 - NOT_HANDLED
             *     -1 - deferMsg and tansition to parent
             *     -2 - transition to parent
             */
            protected int handleDisconnect(NetworkInfo info) {

                if (DBG) log(getCurrentState().getName() + " handleDisconnect");
                int type = info.getType();

                // dont handle network types other than my and other DefaultNet
                if ( !mNetConfigs[type].isDefault() || !mConnectedDefaultNetworks.contains(type)) {
                    return 0;
                }

                if (type == myDefaultNet) {
                    if (myDefaultDnsNet == type) { // reprioritize dns to other
                        handleDnsReprioritization(otherDefaultNet);
                    }
                    mConnectedDefaultNetworks.remove(type);
                    return -1; // defer and transition to parent
                }

                //release the transitionWakeLock as some NetTrackers hold it after they disconnect.
                // We already have default network, release the transition wakelock immediately
                String causedBy = null;
                synchronized (ConnectivityService.this) {
                    if (mNetTransitionWakeLock.isHeld()) {
                        mNetTransitionWakeLock.release();
                        causedBy = mNetTransitionWakeLockCausedBy;
                    }
                }
                if (causedBy != null) {
                    log("NetTransition Wakelock for " +causedBy+ " released because of disconnect");
                }

                //handle other def net disconnect
                mNetTrackers[type].setTeardownRequested(false);

                Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
                intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, info);
                if (info.isFailover()) {
                    intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
                    info.setFailover(false);
                }
                if (info.getReason() != null) {
                    intent.putExtra(ConnectivityManager.EXTRA_REASON, info.getReason());
                }
                if (info.getExtraInfo() != null) {
                    intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO,
                            info.getExtraInfo());
                }
                intent.putExtra(ConnectivityManager.EXTRA_INET_CONDITION,
                                mDefaultInetConditionPublished);

                // Reset interface if no other connections are using the same interface
                boolean doReset = true;
                LinkProperties linkProperties = mNetTrackers[type].getLinkProperties();
                if (linkProperties != null) {
                    String oldIface = linkProperties.getInterfaceName();
                    if (TextUtils.isEmpty(oldIface) == false) {
                        for (NetworkStateTracker networkStateTracker : mNetTrackers) {
                            if (networkStateTracker == null) continue;
                            NetworkInfo networkInfo = networkStateTracker.getNetworkInfo();
                            if (networkInfo.isConnected() && networkInfo.getType() != type) {
                                LinkProperties l = networkStateTracker.getLinkProperties();
                                if (l == null) continue;
                                if (oldIface.equals(l.getInterfaceName())) {
                                    doReset = false;
                                    break;
                                }
                            }
                        }
                    }
                }
                // do this before we broadcast the change - use custom handler
                handleConnectivityChange(type, doReset);

                final Intent immediateIntent = new Intent(intent);
                immediateIntent.setAction(CONNECTIVITY_ACTION_IMMEDIATE);
                sendStickyBroadcast(immediateIntent);
                sendStickyBroadcastDelayed(intent, getConnectivityChangeDelay());

                // reprioritize dns to remove other net's dnses
                if (myDefaultDnsNet == type) {
                    handleDnsReprioritization(myDefaultNet);
                }
                //Stop tracking other default network
                mConnectedDefaultNetworks.remove(type);
                return -2; // true - transition to parent state.
            }

            /**
             * Handle a {@code CONNECTED} event of other default network.
             * If a higher priority network comes up, disconnect connected defaults and
             * transition to parent state for processing this event.
             * If a lower priority or non-default network comes up, process event in parent state.
             */
            protected boolean isHigherPriorityNet(int type) {

                if (DBG) log(getCurrentState().getName() + " isHigherPriorityNet");
                boolean ret = false;
                if (mConnectedDefaultNetworks.isHigherPriorityNetwork(type)) {
                    // a higher priority network is connected disconnect our active default
                    // networks, defer msg and transition to parent state
                    teardown(mNetTrackers[otherDefaultNet]);
                    mConnectedDefaultNetworks.remove(otherDefaultNet);
                    teardown(mNetTrackers[myDefaultNet]);
                    mConnectedDefaultNetworks.remove(myDefaultNet);
                    ret = true;
                } else {
                    teardown(mNetTrackers[type]);
                }
                return ret;
            }

            /**
             * Handle a {@code CONNECTED} event of the my and other
             * default network types.
             * returns:
             *      0 - handled connect for my and other and lower prio defaults
             *     -1 - NOT_HANDLED for non-default types
             *     -2 - Higher pri net connected, deferMsg and transition
             */
            protected int handleConnect(NetworkInfo info) {

                if (DBG) log(getCurrentState().getName() + " handleConnect");
                final int type = info.getType();
                final NetworkStateTracker thisNet = mNetTrackers[type];

                // handle non default networks in parent state.
                if ( ! mNetConfigs[type].isDefault() ) {
                   return -1;
                }

                // handle lower prio default in this state, higher prio defaults
                // in parent state by transitioning to it.
                if (! mConnectedDefaultNetworks.contains(type)) {
                    return (isHigherPriorityNet(type) ? -2 : 0);
                }

                // handle connect event for active defaults
                // release the transitionWakeLock as some NetTrackers hold it after they disconnect.
                // We already have default network, release the transition wakelock immediately
                String causedBy = null;
                synchronized (ConnectivityService.this) {
                    if (mNetTransitionWakeLock.isHeld()) {
                        mNetTransitionWakeLock.release();
                        causedBy = mNetTransitionWakeLockCausedBy;
                    }
                }
                if (causedBy != null) {
                    log("NetTransition Wakelock for " + causedBy + " released because of connect");
                }

                if (type == myDefaultNet) {
                    mDefaultInetConditionPublished = 0;
                    mDefaultConnectionSequence++;
                    mInetConditionChangeInFlight = false;
                } else {
                    mOtherDefaultInetConditionPublished = 0;
                    mOtherDefaultConnectionSequence++;
                    mOtherInetConditionChangeInFlight = false;
                }
                thisNet.setTeardownRequested(false);
                updateNetworkSettings(thisNet);
                handleConnectivityChange(type, false); // private handler
                sendConnectedBroadcastDelayed(info, getConnectivityChangeDelay());

                // notify battery stats service about this network
                final String iface = thisNet.getLinkProperties().getInterfaceName();
                if (iface != null) {
                    try {
                        BatteryStatsService.getService().noteNetworkInterfaceType(iface, type);
                    } catch (RemoteException e) {
                        // ignored; service lives in system_server
                    }
                }
                return 0;
            }

            /**
             * handles inet condition change for otherDefaultNet.
             * Defers processing of other net types to parent state
             */
            protected boolean handleInetConditionChange(int netType, int condition) {

                if (DBG) log(getCurrentState().getName() + " handleInetConditionChange");
                if (netType != otherDefaultNet) {
                    return false;
                }

                if (VDBG) {
                    log("handleInetConditionChange: net=" +
                            netType + ", condition=" + condition +
                            ", for other active default Network=" + netType);
                }

                mOtherDefaultInetCondition = condition;
                int delay;
                if (mOtherInetConditionChangeInFlight == false) {
                    if (VDBG) log("handleInetConditionChange: starting a change hold");
                    // setup a new hold to debounce this
                    if (mOtherDefaultInetCondition > 50) {
                        delay = Settings.Secure.getInt(mContext.getContentResolver(),
                                Settings.Secure.INET_CONDITION_DEBOUNCE_UP_DELAY, 500);
                    } else {
                        delay = Settings.Secure.getInt(mContext.getContentResolver(),
                        Settings.Secure.INET_CONDITION_DEBOUNCE_DOWN_DELAY, 3000);
                    }
                    mOtherInetConditionChangeInFlight = true;
                    sendMessageDelayed(obtainMessage(EVENT_INET_CONDITION_HOLD_END,
                                otherDefaultNet, mOtherDefaultConnectionSequence), delay);
                } else {
                    // we've set the new condition, when this hold ends that will get picked up
                    if (VDBG) {
                        log("handleInetConditionChange:" +
                            " currently in hold - not setting new end evt");
                    }
                }
                return true;
            }

            /**
             * handles inet condition hold end for otherDefaultNet.
             * Defers processing of other net types to parent
             */
            protected boolean handleInetConditionHoldEnd(int netType, int sequence) {

                if (DBG) log(getCurrentState().getName() + " handleInetConditionHoldEnd");
                if (netType != otherDefaultNet) {
                    return false;
                }

                if (DBG) {
                    log("handleInetConditionHoldEnd: net=" + netType +
                            ", condition=" + mOtherDefaultInetCondition +
                            ", published condition=" + mOtherDefaultInetConditionPublished);
                }
                mOtherInetConditionChangeInFlight = false;

                if (mOtherDefaultConnectionSequence != sequence) {
                    if (DBG) {
                        log("handleInetConditionHoldEnd: " +
                            "event hold for obsolete network - ignoring");
                    }
                    return true;
                }

                NetworkInfo networkInfo = mNetTrackers[otherDefaultNet].getNetworkInfo();
                if (networkInfo.isConnected() == false) {
                    if (DBG) log("handleInetConditionHoldEnd: default network " +
                            netType + " not connected - ignoring");
                    return true;
                }
                mOtherDefaultInetConditionPublished = mOtherDefaultInetCondition;
                sendInetConditionBroadcast(networkInfo);
                return true;
            }

            /**
             * Smart Connectivity networks handleConnectivityChange method.
             * Pretty much the same as default state's method barring exception
             * that it calls a private update route method.
             * -------------------------------
             * After a change in the connectivity state of a network. We're mainly
             * concerned with making sure that the list of DNS servers is set up
             * according to which networks are connected, and ensuring that the
             * right routing table entries exist.
             */
            private void handleConnectivityChange(int netType, boolean doReset) {

                if (DBG) log(getCurrentState().getName() + " handleConnectivityChange");
                int resetMask = doReset ? NetworkUtils.RESET_ALL_ADDRESSES : 0;
                 //If a non-default network is enabled, add the host routes that
                 //will allow it's DNS servers to be accessed.
                handleDnsConfigurationChange(netType); // use custom handler

                LinkProperties curLp = mCurrentLinkProperties[netType];
                LinkProperties newLp = null;

                if (mNetTrackers[netType].getNetworkInfo().isConnected()) {
                    newLp = mNetTrackers[netType].getLinkProperties();

                    if (VDBG) {
                        log("handleConnectivityChange: changed linkProperty[" + netType + "]:" +
                                " doReset=" + doReset + " resetMask=" + resetMask +
                                "\n   curLp=" + curLp +
                                "\n   newLp=" + newLp);
                    }

                    if (curLp != null) {
                        if (curLp.isIdenticalInterfaceName(newLp)) {
                            CompareResult<LinkAddress> car = curLp.compareAddresses(newLp);
                            if ((car.removed.size() != 0) || (car.added.size() != 0)) {
                                for (LinkAddress linkAddr : car.removed) {
                                    if (linkAddr.getAddress() instanceof Inet4Address) {
                                        resetMask |= NetworkUtils.RESET_IPV4_ADDRESSES;
                                    }
                                    if (linkAddr.getAddress() instanceof Inet6Address) {
                                        resetMask |= NetworkUtils.RESET_IPV6_ADDRESSES;
                                    }
                                }
                                if (DBG) {
                                    log("handleConnectivityChange: addresses changed" +
                                            " linkProperty[" + netType + "]:" +
                                            " resetMask=" + resetMask + "\n   car=" + car);
                                }
                            } else {
                                if (DBG) {
                                    log("handleConnectivityChange: address are the " +
                                            " same reset per doReset linkProperty[" +
                                            netType + "]: resetMask=" + resetMask);
                                }
                            }
                        } else {
                            resetMask = NetworkUtils.RESET_ALL_ADDRESSES;
                            if (DBG) {
                                log("handleConnectivityChange: interface not equivalent reset both"+
                                        " linkProperty[" + netType + "]: resetMask=" + resetMask);
                            }
                        }
                    }
                    if (mNetConfigs[netType].isDefault()) {
                        handleApplyDefaultProxy(newLp.getHttpProxy());
                    }
                } else {
                    if (VDBG) {
                        log("handleConnectivityChange: changed linkProperty[" + netType + "]:" +
                                " doReset=" + doReset + " resetMask=" + resetMask +
                                "\n  curLp=" + curLp +
                                "\n  newLp= null");
                    }
                }
                mCurrentLinkProperties[netType] = newLp;
                boolean resetDns = updateRoutes( newLp,
                                                 curLp,
                                                 mNetConfigs[netType].isDefault(),
                                                 mRouteAttributes[netType] );

                if (resetMask != 0 || resetDns) {
                    LinkProperties linkProperties = mNetTrackers[netType].getLinkProperties();
                    if (linkProperties != null) {
                        String iface = linkProperties.getInterfaceName();
                        if (TextUtils.isEmpty(iface) == false) {
                            if (resetMask != 0) {
                                if (DBG) log("resetConnections(" + iface + ", " + resetMask + ")");
                                NetworkUtils.resetConnections(iface, resetMask);

                                // Tell VPN the interface is down. It is a temporary
                                // but effective fix to make VPN aware of the change.
                                if ((resetMask & NetworkUtils.RESET_IPV4_ADDRESSES) != 0) {
                                    mVpn.interfaceStatusChanged(iface, false);
                                }
                            }
                            if (resetDns) {
                                if (VDBG) log("resetting DNS cache for " + iface);
                                try {
                                    mNetd.flushInterfaceDnsCache(iface);
                                } catch (Exception e) {
                                    // never crash - catch them all
                                    if (DBG) loge("Exception resetting dns cache: " + e);
                                }
                            }
                        }
                    }
                }

                // TODO: Temporary notifying upstread change to Tethering.
                //       @see bug/4455071
                /** Notify TetheringService if interface name has been changed. */
                if (TextUtils.equals(mNetTrackers[netType].getNetworkInfo().getReason(),
                                     Phone.REASON_LINK_PROPERTIES_CHANGED)) {
                    if (isTetheringSupported()) {
                        mTethering.handleTetherIfaceChange(mNetTrackers[netType].getNetworkInfo());
                    }
                }
            }

            /**
             * Updates route metric of mActiveDefaultNetwork when transitioning
             * from another network.
             */
            protected void updateDefaultRouteMetric(int type) {

                if (DBG) log(getCurrentState().getName() + " updateDefaultRouteMetric");
                LinkProperties lp = mCurrentLinkProperties[type];
                if (lp == null) return;

                for (RouteInfo r : lp.getRoutes()) {
                    if (r == null || r.isHostRoute()) continue;
                    addRoute(lp, r, 0, mRouteAttributes[type].getMetric());
                }
            }

            /**
             * Removes default routes for default networks if type is all (-1).
             * Else removes default route for specified type
             */
            protected void removeDefaultRoutes (int netType) {

                if (DBG) log(getCurrentState().getName() + " removeDefaultRoutes");
                if (netType == -1) {
                    if (DBG) log("removing default routes for all networks");
                    for (Integer type : mConnectedDefaultNetworks.getActiveDefaults()){
                        LinkProperties p = mCurrentLinkProperties[type.intValue()];
                        if (p == null ) continue;
                        for (RouteInfo r : p.getRoutes()) {
                            if (r != null && r.isDefaultRoute()) {
                                removeRoute(p, r, TO_DEFAULT_TABLE);
                            }
                        }
                    }
                } else if (mConnectedDefaultNetworks.contains(netType)) {
                    if (DBG) log("removing default routes for " + netType);
                    LinkProperties p = mCurrentLinkProperties[netType];
                    if (p == null) return;
                    for (RouteInfo r : p.getRoutes()) {
                        if (r != null && r.isDefaultRoute()) {
                            removeRoute(p, r, TO_DEFAULT_TABLE);
                        }
                    }
                }
            }

            /**
             * Custom addRoute implementation for smart connectivity states.
             * Supports metric routes for default networks.
             * Manages mAddedRoutes appropriately for dual default Network State.
             * Does not support adding route to secondary table.
             */
            protected boolean addRoute(LinkProperties lp, RouteInfo r,
                    int cycleCount, int defaultRouteMetric) {

                if (DBG) log(getCurrentState().getName() + " addRoute");

                int metric = 0;
                String ifaceName = lp.getInterfaceName();

                if ((ifaceName == null) || (lp == null) || (r == null)) {
                    return false;
                }

                if (cycleCount > MAX_HOSTROUTE_CYCLE_COUNT) {
                    loge("Error adding route - too much recursion");
                    return false;
                }

                if (r.isHostRoute() == false) {
                    // use state specific metric for default routes
                    metric = defaultRouteMetric;
                    RouteInfo bestRoute =
                        RouteInfo.selectBestRoute(lp.getRoutes(), r.getGateway());
                    if (bestRoute != null) {
                        if (bestRoute.getGateway().equals(r.getGateway())) {
                            //if there is no better route, add the implied hostroute for our gateway
                            bestRoute = RouteInfo.makeHostRoute(r.getGateway());
                        } else {
                            // if we will connect to our gateway through another route, add a direct
                            // route to it's gateway
                            bestRoute =
                                RouteInfo.makeHostRoute(r.getGateway(), bestRoute.getGateway());
                        }
                        addRoute(lp, bestRoute, cycleCount+1, metric);
                    }
                }

                if (VDBG) {
                    log("Adding " + r + " with metric " + metric + " for interface " + ifaceName);
                }
                try {
                     //metric update removes existing route and adds another
                     //with newer metric. So check for duplicate here.
                     if (! mAddedRoutes.contains(r))
                         mAddedRoutes.add(r);  // only track default table
                     if (VDBG) log("Routes in main table - [ " + mAddedRoutes + " ]");
                     mNetd.addRouteWithMetric(ifaceName, metric, r);
                } catch (Exception e) {
                    // never crash - catch them all
                    if (VDBG) loge("Exception trying to add a Metric Route: " + e);
                    return false;
                }
                return true;
            }

            /**
             * Add and remove routes using the old properties (null if not previously connected),
             * new properties (null if becoming disconnected).  May even be double null, which
             * is a noop.
             * updates dns routes for all networks.
             * Uses private addRoute method to handle metrics for default routes in default table
             * returns a boolean indicating the routes changed
             */
            protected boolean updateRoutes(LinkProperties newLp,
                    LinkProperties curLp, boolean isLinkDefault, RouteAttributes ra) {

                if (DBG) log(getCurrentState().getName() + " updateRoutes");

                Collection<RouteInfo> routesToAdd = null;
                CompareResult<InetAddress> dnsDiff = new CompareResult<InetAddress>();
                CompareResult<RouteInfo> routeDiff = new CompareResult<RouteInfo>();
                CompareResult<LinkAddress> localAddrDiff = new CompareResult<LinkAddress>();
                if (curLp != null) {
                    // check for the delta between the current set and the new
                    routeDiff = curLp.compareRoutes(newLp);
                    dnsDiff = curLp.compareDnses(newLp);
                    localAddrDiff = curLp.compareAddresses(newLp);
                } else if (newLp != null) {
                    routeDiff.added = newLp.getRoutes();
                    dnsDiff.added = newLp.getDnses();
                    localAddrDiff.added = newLp.getLinkAddresses();
                }

                boolean routesChanged =
                    (routeDiff.removed.size() != 0 || routeDiff.added.size() != 0);

                for (RouteInfo r : routeDiff.removed) {
                    if (isLinkDefault || ! r.isDefaultRoute()) {
                        removeRoute(curLp, r, TO_DEFAULT_TABLE);
                    }
                    if (isLinkDefault == false) {
                        // remove from a secondary route table
                        removeRoute(curLp, r, TO_SECONDARY_TABLE);
                    }
                }

                for (RouteInfo r : routeDiff.added) {
                    if (isLinkDefault || ! r.isDefaultRoute()) {
                        // add to main table - uses custom addRoute with metric
                        addRoute(newLp, r, 0, ra.getMetric());
                    } else {
                        // add to a secondary route table - uses default addRoute method
                        ConnectivityService.this.addRoute(newLp, r, TO_SECONDARY_TABLE);

                        // many radios add a default route even when we don't want one.
                        // remove the default route unless somebody else has asked for it
                        String ifaceName = newLp.getInterfaceName();
                        if ( ! TextUtils.isEmpty(ifaceName) && ! mAddedRoutes.contains(r)) {
                            if (VDBG) log("Removing " + r + " for interface " + ifaceName);
                            try {
                                mNetd.removeRoute(ifaceName, r);
                            } catch (Exception e) {
                                // never crash - catch them all
                                if (VDBG) loge("Exception trying to remove a route: " + e);
                            }
                        }
                    }
                }

                if (localAddrDiff.removed.size() != 0) {
                    for (LinkAddress la : localAddrDiff.removed) {
                        if (VDBG) log("Removing src route for:" + la.getAddress().getHostAddress());
                        try {
                             mNetd.delSrcRoute(la.getAddress().getAddress(), ra.getTableId());
                        } catch (Exception e) {
                            loge("Exception while trying to remove src route: " + e);
                        }
                    }
                }

                if (localAddrDiff.added.size() != 0) {
                    InetAddress gw4Addr = null, gw6Addr = null;
                    String ifaceName = newLp.getInterfaceName();
                    if (! TextUtils.isEmpty(ifaceName)) {
                        for (RouteInfo r : newLp.getRoutes()) {
                            if (! r.isDefaultRoute()) continue;
                            if (r.getGateway() instanceof Inet4Address) {
                                gw4Addr = r.getGateway();
                            } else {
                                gw6Addr = r.getGateway();
                            }
                        } //gateway is optional so continue adding the source route.
                        for (LinkAddress la : localAddrDiff.added) {
                            try {
                                if (la.getAddress() instanceof Inet4Address) {
                                    mNetd.replaceSrcRoute(ifaceName, la.getAddress().getAddress(),
                                            gw4Addr.getAddress(), ra.getTableId());
                                } else {
                                    mNetd.replaceSrcRoute(ifaceName, la.getAddress().getAddress(),
                                            gw6Addr.getAddress(), ra.getTableId());
                                }
                            } catch (Exception e) {
                                //never crash, catch them all
                                loge("Exception while trying to add src route: " + e);
                            }
                        }
                    }
                }

                // handle DNS routes
                if (routesChanged) {
                    // routes changed - remove all old dns entries and add new
                    if (curLp != null) {
                        for (InetAddress oldDns : curLp.getDnses()) {
                            removeRouteToAddress(curLp, oldDns);
                        }
                    }
                    if (newLp != null) {
                        for (InetAddress newDns : newLp.getDnses()) {
                            addRouteToAddress(newLp, newDns);
                        }
                    }
                } else {
                    // no change in routes, check for change in dns themselves
                    for (InetAddress oldDns : dnsDiff.removed) {
                        removeRouteToAddress(curLp, oldDns);
                    }
                    for (InetAddress newDns : dnsDiff.added) {
                        addRouteToAddress(newLp, newDns);
                    }
                }
                return routesChanged;
            }
        } // end dualConnectivityState class
    } // end ConnectivityServiceHSM
}
