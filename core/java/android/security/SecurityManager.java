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
    private static String TAG = "SM";
    private static boolean localLOGV = false;

    private static SecurityManager gInstance;
    /** Used when obtaining a reference to the singleton instance. */
    private static Object INSTANCE_LOCK = new Object();
    private boolean mInitialized;

    private Context mContext;
    private Handler mHandler = null;
    private static boolean mIsAuthorized = false;

    /**
     * Get an instance of SecurityManager.
     * 
     * @param context
     * @return an instance of SecurityManager if the client is authorized. 
     *         Otherwise it will return null.
     */
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

    /**
     * Message code for short message interception callback 
     */
    public static final int MONITOR_MESSAGE = 1;

    /**
     * Message code for call interception callback when using APP mode
     */
    public static final int MONITOR_CALL = 2;

    /**
     * Message code for call interception callback when using SYS mode
     */
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

    /**
     * Attach a handler for receiving message/call interception callback message.
     * 
     * @param handler A handler for receiving message/call interception. The message code can be {@link #MONITOR_MESSAGE},
     *        {@link #MONITOR_CALL} and {@link #MONITOR_CALL_BLOCKED}.
     * @return true if attached successfully else false.
     */
    public boolean attachHandler(Handler handler) {
        if (isAuthorized()) {
            mHandler = handler;
            return true;
        }
        return false;
    }

    // flags for Message monitor
    /**
     * Flag for monitoring sending short message.
     */
    public static final int SENDING_MESSAGE_WATCHER = 1;
    /**
     * Flag for monitoring received short message.
     */
    public static final int RECEVIED_MESSAGE_WATCHER = 2;
    /**
     * Flag for monitoring all short message.
     */
    public static final int ALL_MESSAGE_WATCHER = 4;
    /**
     * The key to retrieve phone number from received message bundle.
     */
    public static final String MESSAGE_KEY_PHONE_NUMBER = "phoneNumber";
    /**
     * The key to retrieve content from received message bundle.
     */
    public static final String MESSAGE_KEY_CONTENT = "content";
    /**
     * The key to retrieve direction from received message bundle.
     */
    public static final String MESSAGE_KEY_DIRECTION = "direction";
    /**
     * The key to retrieve ident from received message bundle.
     */
    public static final String MESSAGE_KEY_IDENT = "ident";
    /**
     * The value for direction of sending message.
     */
    public static final String MESSAGE_DIRECTION_SENDING = "sending";
    /**
     * The value for direction of received message.
     */
    public static final String MESSAGE_DIRECTION_RECEIVED = "received";
    // flags for Call monitor mode
    /**
     * Flag for monitoring call in app.
     */
    public static final int MONITOR_CALL_IN_APP = 8;
    /**
     * Flag for monitoring call in sys.
     */
    public static final int MONITOR_CALL_IN_SYS = 16;
    /**
     * The key to retrieve phone number from received call bundle.
     */
    public static final String CALL_KEY_PHONE_NUMBER = "phoneNumber";
    /**
     * The key to retrieve subscription from received call bundle.
     */
    public static final String CALL_KEY_SUBSCRIPTION = "subscription";
    /**
     * The key to retrieve ident from received call bundle.
     */
    public static final String CALL_KEY_IDENT = "ident";
    /**
     * The key to retrieve date from received call bundle.
     */
    public static final String CALL_KEY_DATE = "date";
    
    /**
     * Register client to monitor call/message.
     *  
     * @param flags The flags is used to represent which information the client want to monitor.
     * It can be one value of {@link #SENDING_MESSAGE_WATCHER}, {@link #RECEVIED_MESSAGE_WATCHER},
     * {@link #ALL_MESSAGE_WATCHER}, {@link #MONITOR_CALL_IN_APP} or {@link #MONITOR_CALL_IN_SYS}.
     * @return If registered successfully, it will return REGISTER_SUCCESS, or REGISTER_FAILURE if it failed.
     *      If RemoteException occurred, it will return REMOTE_ERROR.
     */
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

    /**
     * Constant to indicate that the message is passed.
     */
    public static final int ACTION_MESSAGE_PASS = 0;
    /**
     * Constant to indicate that the message is blocked.
     */
    public static final int ACTION_MESSAGE_BLOCK = 1;

    /**
     * Client can call this method to notify the framework to pass or block the message.
     * 
     * @param ident The identity for the message object.
     * @param action The action to indicate which action the framework should take. The value can be one of
     * {@link #ACTION_MESSAGE_PASS} or {@link #ACTION_MESSAGE_BLOCK}.
     * @return It will return  INTERCEPT_MESSAGE_SUCCESS if interception is successful, or it will return INTERCPET_MESSAGE_DUPLICATE.
     *      If RemoteException occurred, it will return REMOTE_ERROR.
     */
    public int interceptMessage(int ident, int action) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().interceptMessage(caller, ident, action);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    /**
     * Constant to indicate that the call is passed.
     */
    public static final int ACTION_CALL_PASS = 1;
    /**
     * Constant to indicate that the call is blocked.
     */
    public static final int ACTION_CALL_BLOCK = 2;

    /**
     * Client can call this method to notify the framework to pass or block the call.
     * 
     * @param ident The identity for the call object.
     * @param action The action to indicate which action the framework should take. The value can be one of
     * {@link #ACTION_CALL_PASS} or {@link #ACTION_CALL_BLOCK}.
     * @return It will return  INTERCEPT_MESSAGE_SUCCESS if interception is successful, or it will return INTERCPET_MESSAGE_DUPLICATE.
     *      If RemoteException occurred, it will return REMOTE_ERROR.
     */
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
    /**
     * Constants for mobile network to set firewall.
     */
    public static final int FIREWALL_TYPE_MOBILE = 1;
    /**
     * Constants for wifi network to set firewall.
     */
    public static final int FIREWALL_TYPE_WIFI = 2;
    /**
     * Constants for all network to set firewall.
     */
    public static final int FIREWALL_TYPE_ALL = 3;

    /**
     * Set specific firewall policy to enable/disable network on specific uids.
     * 
     * @param policy A Bundle object which contains all information about firewall options.
     * The policy object passed to method setFireWallPolicy is a Bundle object which likes a key-value map. 
     * Caller should put correct value into this object with correct key as follows.
     * Key              Value
     * uid              An int array of uid.
     *                  All the uids client want to set firewall option on.
     * enabled          A boolean array of enabled flag. 
     *                  true means enable network connection and false means disable network connection.
     * type             An int array of type.
     *                  Type can tell framework which network interfaces should be enable or disable.
     *                  The value of type should be FIREWALL_TYPE_MOBILE, FIREWALL_TYPE_WIFI or FIREWALL_TYPE_ALL.
     * All the arrays in this bundle should have same length. Otherwise, it will return error. 
     * The values in same position in these arrays form a policy option tuple. Framework will enable or disable on
     * certain network interface for certain uid for each policy option tuple.
     *
     * @return It will return SET_FIREWALL_SUCCESS if set firewall policy successfully, or SET_FIREWALL_INVALID_INPUT
     *      if set error due to incorrect data in parameter policy. It will return REMOTE_ERROR if remote error occurred.
     */
    public int setFirewallPolicy(Bundle policy) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().setFirewallPolicy(caller, policy);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    // mode for power save, can be combined
    /**
     * Constant that indicates to choose screen off mode
     */
    public static final int MODE_SCREEN_OFF = 1;
    /**
     * Constant that indicates to  choose low battery mode
     */
    public static final int MODE_LOW_BATTERY = 2;
    /**
     * Constant that indicates to  choose high temperature
     */
    public static final int MODE_HIGH_TEMPERATURE = 4;
    /**
     * Constant that indicates to  choose low light computing mode
     */
    public static final int MODE_LIGHT_COMPUTING = 8;

    /**
     * Set specific power saver mode.
     * 
     * @param mode Mode number indicates the type of the mode user chooses.
     * It can be MODE_LOW_BATTERY, MODE_LIGHT_COMPUTING, MODE_HIGH_TEMPERATURE or MODE_SCREEN_OFF
     * @return SET_POWERMODE_SUCCESS if set mode successfully, 
     *      SET_POWERMODE_INIT_ERROR if set error during init process,
     *      SET_POWERMODE_UNSUPPORTED if mode is unsupported,
     *      or REMOTE_ERROR if remote error.
     */
    public int setPowerSaverMode(int mode) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().setPowerSaverMode(caller, mode);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    public int getPowerSaverMode() {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().getPowerSaverMode(caller);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    //type for permission strategies
    public static final int PERM_STATIC = 0;
    public static final int PERM_DYNAMIC = 1;

    public int enablePermissionController(boolean enable) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().enablePermissionController(caller, enable);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    /**
     * Revoke the permission for a package.
     * 
     * @param permission The name of the permission to revoke.
     * @param packageName The name of the package.
     * @param type The type of the revoke. It can be PERM_STATIC or PERM_DYNAMIC.
     * 
     * @return Returns REVOKE_PERM_SUCCESS if revokes successfully, or returns INVALID_PKG_PERM if permission is invalid.
     *      It will return REMOTE_ERROR if remote error occurred.
     */
    public int revokePermission(String permission, String packageName, int type) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().revokePermission(caller, permission, packageName, type);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    /**
     * Grant the permission that was previously revoked with {@link #revokePermission(String, String, int)} for a package.
     * 
     * @param permission The name of the permission to grant.
     * @param packageName The name of the package.
     * 
     * @return Returns GRANT_PERM_SUCCESS if grants successfully, or returns INVALID_PKG_PERM if permission is invalid.
     *      It will return REMOTE_ERROR if remote error occurred.
     */
    public int grantPermission(String permission, String packageName) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().grantPermission(caller, permission, packageName);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    /**
     * Revoke a list of permissions for a package.
     * 
     * @param permissionList A String list of the permissions to revoke.
     * @param packageName The name of package.
     * @param type The type of the revoke. It can be PERM_STATIC or PERM_DYNAMIC.
     * 
     * @return Returns REVOKE_PERM_SUCCESS if revokes successfully, or returns INVALID_PKG_PERM if permission is invalid.
     *      It will return REMOTE_ERROR if remote error occurred.
     */
    public int revokePermission(List<String> permissionList, String packageName, int type) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().revokePermission(caller, permissionList, packageName, type);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    /**
     * Grant a list of permissions that was previously revoked with {@link #revokePermission(List, String, int)} for a package.
     * 
     * @param permissionList A String list of the permissions to grant.
     * @param packageName The name of package.
     * 
     * @return Returns GRANT_PERM_SUCCESS if grants successfully, or returns INVALID_PKG_PERM if permission is invalid.
     *      It will return REMOTE_ERROR if remote error occurred. 
     */
    public int grantPermission(List<String> permissionList, String packageName) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().grantPermission(caller, permissionList, packageName);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    //here implement block/restore actions in receivers
    
    /**
     * Block the action for receiver in a package.
     * 
     * @param action The name of action
     * @param packageName The name of package.
     * 
     * @return Returns BLOCK_ACTION_SUCCESS if blocks successfully, or returns INVALID_RECEVIER_ACTION if action is invalid.
     *      It will return REMOTE_ERROR if remote error occurred. 
     */
    public int blockActionReceiver(String action, String packageName) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().blockActionReceiver(caller, action, packageName);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    /**
     * Restore to receive the action that was previously blocked with {@link #blockActionReceiver(String, String)} for a package.
     * 
     * @param action The name of action.
     * @param packageName The name of package.
     * 
     * @return Returns RESTORE_ACTION_SUCCESS if restores successfully, or returns INVALID_RECEVIER_ACTION if action is invalid.
     *      It will return REMOTE_ERROR if remote error occurred. 
     */
    public int restoreActionReceiver(String action, String packageName) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().restoreActionReceiver(caller, action, packageName);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    public int enableReceiverController(boolean enable) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().enableReceiverController(caller, enable);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    /**
     * Get the FirewallEntry for a specific uid.
     * 
     * @param uid The user id for which you want to retrieve the firewall info.
     * 
     * @return Returns a FirewallEntry for the uid or null if remote error occurred.
     */
    public FirewallEntry getFirewall(int uid) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().getFirewall(caller, uid);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Get the PermissionEntry for a specific package.
     *  
     * @param packageName The name of the package which you want to retrieve the permision info.
     *  
     * @return Returns a PermissionEntry for the package or null if remote error occurred.
     */
    public PermissionEntry getPermission(String packageName) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().getPermission(caller, packageName);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Get the ActionReceiverEntry for a specific package.
     *  
     * @param packageName The name of the package which you want to retrieve the action receiver info.
     *  
     * @return Returns a ActionReceiverEntry for the package or null if remote error occurred.
     */
    public ActionReceiverEntry getActionReceiver(String packageName) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().getActionReceiver(caller, packageName);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Get all the firewall info.
     * 
     * @return Returns a List of FirewallEntry or null if remote error occurred.
     */
    public List<FirewallEntry> getFirewallList() {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().getFirewallList(caller);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Get all the permission info.
     * 
     * @return Returns a List of PermissionEntry or null if remote error occurred.
     */
    public List<PermissionEntry> getPermissionList() {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().getPermissionList(caller);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Get all the action receiver info.
     * 
     * @return Returns a List of ActionReceiverEntry or null if remote error occurred.
     */
    public List<ActionReceiverEntry> getActionReceiverList() {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().getActionReceiverList(caller);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Clear all the settings stored in system. It includes firewall, permission and action receiver.
     * 
     * @see #clearFirewallSettings()
     * @see #clearPermissionSettings()
     * @see #clearActionReceiverSettings()
     */
    public void clearAllSettings() {
        IBinder caller = getSecurityToken();
        try {
            SecurityManagerNative.getDefault().clearAllSettings(caller);
        } catch (RemoteException e) {
        }
    }

    /**
     * Clear the firewall settings stored in system.
     * 
     * @see #clearAllSettings()
     */
    public void clearFirewallSettings() {
        IBinder caller = getSecurityToken();
        try {
            SecurityManagerNative.getDefault().clearFirewallSettings(caller);
        } catch (RemoteException e) {
        }
    }

    /**
     * Clear the permission settings stored in system.
     * 
     * @see #clearAllSettings()
     */
    public void clearPermissionSettings() {
        IBinder caller = getSecurityToken();
        try {
            SecurityManagerNative.getDefault().clearPermissionSettings(caller);
        } catch (RemoteException e) {
        }
    }

    /**
     * Clear the action receiver settings stored in system.
     * 
     * @see #clearAllSettings()
     */
    public void clearActionReceiverSettings() {
        IBinder caller = getSecurityToken();
        try {
            SecurityManagerNative.getDefault().clearActionReceiverSettings(caller);
        } catch (RemoteException e) {
        }
    }

    /**
     * Check if the firewall for the particular uid of particular network type is set.
     * 
     * @param uid The user id you want to check.
     * @param type The network type you want to check. It can be FIREWALL_TYPE_MOBILE, FIREWALL_TYPE_WIFI or FIREWALL_TYPE_ALL.
     * 
     * @return Returns FIREWALL_BLOCKED if the particular network on this uid is blocked, or returns FIREWALL_NOT_BLOCKED.
     *      It will return REMOTE_ERROR if remote error occurred. 
     * 
     * @see #FIREWALL_TYPE_MOBILE
     * @see #FIREWALL_TYPE_WIFI
     * @see #FIREWALL_TYPE_ALL
     */
    public int checkFirewall(int uid, int type) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().checkFirewall(caller, uid, type);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    /**
     * Check if the particular permission for the particular package is set.
     * 
     * @param packageName The name of package
     * @param permission The name of permission
     * 
     * @return Returns PERMISSION_GRANTED if the permission for the pacakge is granted, or returns PERMISSION_DENIED.
     *      It will return REMOTE_ERROR if remote error occurred.
     */
    public int checkPermission(String packageName, String permission) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().checkPermission(caller, packageName, permission);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    /**
     * Check if the particular action for the particular package is set.
     * 
     * @param packageName The name of package.
     * @param action The name of action.
     * 
     * @return Returns ACTION_BLOCKED if the action for the package is blocked, or returns ACTION_NOT_BLOCKED.
     *      It will return REMOTE_ERROR if remote error occurred.
     */
    public int checkActionReceiver(String packageName, String action) {
        IBinder caller = getSecurityToken();
        try {
            return SecurityManagerNative.getDefault().checkActionReceiver(caller, packageName, action);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
    }

    /**
     * Clear all the settings for an application with particular package name and uid.
     * The package name and uid should be associated for one application.
     *  
     * @param packageName The name of package.
     * @param uid The user id for the application.
     */
    public void clearSingleSettings(String packageName, int uid) {
        IBinder caller = getSecurityToken();
        try {
            SecurityManagerNative.getDefault().clearSingleSettings(caller, packageName, uid);
        } catch (RemoteException e) {
        }
    }
}
