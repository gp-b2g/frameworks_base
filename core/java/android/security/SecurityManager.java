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

package android.security;

import android.content.Context;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Interact with the overall security stuff running in the system.
 */
public class SecurityManager {
    private static String TAG = "SecurityManager";
    private static boolean localLOGV = false;

    private static SecurityManager gInstance;
    /** Used when obtaining a reference to the singleton instance. */
    private static Object INSTANCE_LOCK = new Object();
    private boolean mInitialized;

    private Context mContext;
    private Handler mHandler = null;
    private static boolean mIsAuthorized = false;

    public static SecurityManager getInstance(Context context) {
        synchronized (INSTANCE_LOCK) {
            if (gInstance == null) {
                gInstance = new SecurityManager();
            }

            if (!gInstance.init(context)) {
                return null;
            }

            return gInstance;
        }
    }

    private boolean init(Context context) {
        if (mInitialized)
            return true;
        mInitialized = true;

        // This will be around as long as this process is
        mContext = context.getApplicationContext();
        String pkgName = mContext.getPackageName();
        mIsAuthorized = verifyAuthority(pkgName);
        if(localLOGV)Log.e(TAG,"mIsAuthorized is: " + mIsAuthorized);

        if (!mIsAuthorized) {
            return false;
        }

        return true;
    }

    private SecurityManager() {
    }

    public static final int MONITOR_MESSAGE = 1;

    public static final int MONITOR_CALL = 2;

    public static final int MONITOR_CALL_BLOCKED = 3;

    private boolean verifyAuthority(String packageName) {
        // Todo: need isv client to check this
        int result = SecurityResult.NOT_AUTHORIZED_PACKAGE;
        try {
            result = SecurityManagerNative.getDefault().checkAuthority((IBinder)mCallback, packageName);
        } catch (RemoteException e) {
            return false;
        }
        return (result > SecurityResult.NOT_AUTHORIZED_PACKAGE);
    }

    private boolean isAuthorized() {
        return mIsAuthorized;
    }

    private IBinder getSecurityToken() {
        return isAuthorized() ? ((IBinder) mCallback) : null;
    }

    private ISecurityCallback mCallback = new ISecurityCallback.Stub() {
        /**
         * This is called by the remote service regularly to tell us about new
         * values. Note that IPC calls are dispatched through a thread pool
         * running in each process, so the code executing here will NOT be
         * running in our main thread like most other things -- so, to update
         * the UI, we need to use a Handler to hop over there.
         */
        public void messageToBeChecked(Bundle message) {
            mHandler.sendMessage(mHandler.obtainMessage(MONITOR_MESSAGE, SecurityRecord.parseSms(message)));
        }

        public void callToBeChecked(Bundle call) {
            mHandler.sendMessage(mHandler.obtainMessage(MONITOR_CALL, call));
        }

        public void callBlocked(Bundle call) {
            mHandler.sendMessage(mHandler.obtainMessage(MONITOR_CALL_BLOCKED, call));
        }
    };

    public boolean attachHandler(Handler handler) {
        if (isAuthorized()) {
            mHandler = handler;
            return true;
        }
        return false;
    }

    // flags for Message monitor
    public static final int SENDING_MESSAGE_WATCHER = 1;
    public static final int RECEVIED_MESSAGE_WATCHER = 2;
    public static final int ALL_MESSAGE_WATCHER = 4;
    public static final String MESSAGE_KEY_PHONE_NUMBER = "phoneNumber";
    public static final String MESSAGE_KEY_CONTENT = "content";
    public static final String MESSAGE_KEY_DIRECTION = "direction";
    public static final String MESSAGE_KEY_IDENT = "ident";
    public static final String MESSAGE_DIRECTION_SENDING = "sending";
    public static final String MESSAGE_DIRECTION_RECEIVED = "received";
    // flags for Call monitor mode
    public static final int MONITOR_CALL_IN_APP = 8;
    public static final int MONITOR_CALL_IN_SYS = 16;
    public static final String CALL_KEY_PHONE_NUMBER = "phoneNumber";
    public static final String CALL_KEY_SUBSCRIPTION = "subscription";
    public static final String CALL_KEY_IDENT = "ident";
    public static final String CALL_KEY_DATE = "date";
    

    public int registerSecurityMonitor(int flags) {
        int result = SecurityResult.INVALID_HANDLER;
        if (mHandler != null) {
            IBinder caller = getSecurityToken();
            try {
                result = SecurityManagerNative.getDefault().registerMessageWatcher(caller, flags);
            } catch (RemoteException e) {
                return SecurityResult.REMOTE_ERROR;
            }
            try {
                result |= SecurityManagerNative.getDefault().registerCallWatcher(caller, flags);
            } catch (RemoteException e) {
                return SecurityResult.REMOTE_ERROR;
            }
        }
        return result;
    }

    public static final int ACTION_MESSAGE_PASS = 0;
    public static final int ACTION_MESSAGE_BLOCK = 1;

    public int interceptMessage(int ident, int action) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().interceptMessage(caller, ident, action);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    public static final int ACTION_CALL_PASS = 1;
    public static final int ACTION_CALL_BLOCK = 2;

    public int interceptCall(int ident, int action) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().interceptCall(caller, ident, action);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    public int setCallBlackList(Bundle callList) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().setCallBlackList(caller, callList);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        } 
    }

    public int addCallBlackItem(Bundle call) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().addCallBlackItem(caller, call);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        } 
    }

    
    public int removeCallBlackItem(Bundle call) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().removeCallBlackItem(caller, call);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        } 
    }    

    // FOR network policy type on interface
    public static final int FIREWALL_TYPE_MOBILE = 1;
    public static final int FIREWALL_TYPE_WIFI = 2;
    public static final int FIREWALL_TYPE_ALL = 3;

    public int setFirewallPolicy(Bundle policy) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().setFirewallPolicy(caller, policy);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    // mode for power save, can be combined
    public static final int MODE_SCREEN_OFF = 1;
    public static final int MODE_LOW_BATTERY = 2;
    public static final int MODE_HIGH_TEMPERATURE = 4;
    public static final int MODE_LIGHT_COMPUTING = 8;

    public int setPowerSaverMode(int mode) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().setPowerSaverMode(caller, mode);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    //type for permission strategies
    public static final int PERM_STATIC = 0;
    public static final int PERM_DYNAMIC = 1;

    public int revokePermission(String permission, String packageName, int type) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().revokePermission(caller, permission, packageName, type);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    public int grantPermission(String permission, String packageName) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().grantPermission(caller, permission, packageName);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    public int revokePermission(String permission, int uid, int type) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().revokePermission(caller, permission, uid, type);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    public int grantPermission(String permission, int uid) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().grantPermission(caller, permission, uid);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    public int revokePermission(List<String> permissionList, String packageName, int type) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().revokePermission(caller, permissionList, packageName, type);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    public int grantPermission(List<String> permissionList, String packageName) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().grantPermission(caller, permissionList, packageName);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    public int revokePermission(List<String> permissionList, int uid, int type) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().revokePermission(caller, permissionList, uid, type);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    public int grantPermission(List<String> permissionList, int uid) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().grantPermission(caller, permissionList, uid);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    //here implement block/restore actions in receivers
    public int blockActionReceiver(String action, String packageName) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().blockActionReceiver(caller, action, packageName);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    public int restoreActionReceiver(String action, String packageName) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().restoreActionReceiver(caller, action, packageName);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

}
