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

package com.android.server.sm;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;

import android.security.ICallToken;
import android.security.IMessageToken;
import android.security.IPermissionToken;
import android.security.IReceiverToken;
import android.security.ISecurityCallback;
import android.security.ISecurityManager;
import android.security.SecurityManager;
import android.security.SecurityManagerNative;
import android.security.SecurityRecord;
import android.security.SecurityResult;

import android.util.Slog;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;

/** {@hide} */
public final class SecurityManagerService extends SecurityManagerNative {
    static final String TAG = "SecurityManagerService";
    static final boolean DEBUG = false;

    private static boolean mSystemReady = false;

    private static RadioSecurityController mRadioController;

    HashMap<IBinder, SecurityRecord> mSecurityRecords = new HashMap<IBinder, SecurityRecord>();


    class ClientDeathRecipient implements IBinder.DeathRecipient {
        final IBinder mToken;

        ClientDeathRecipient(IBinder token) {
            mToken = token;
        }

        @Override
        public void binderDied() {
            LOG("binderDied", "Death received in " + this
                + " for token " + mToken);
            clientDied(mToken);
        }
    }

    private void clientDied(IBinder token) {
        SecurityRecord record = getSecurityRecord(token);
        // remove related info of this record. May disable message and call interception if
        // the last record is removed.
        mRadioController.unMonitorMessage(record);
        mRadioController.unMonitorCall(record);
        mSecurityRecords.remove(token);
    }

    public int checkAuthority(IBinder token, String packageName) {
        LOG("checkAuthority", "PKG: " + packageName);
        int result = SecurityResult.NOT_AUTHORIZED_PACKAGE;
        result = SecurityRecord.verifyPackage(packageName);
        if (result == SecurityResult.AUTHORIZED_PACKAGE) {
            SecurityRecord r = new SecurityRecord(token, true);
            mSecurityRecords.put(token, r);
            try {
                token.linkToDeath(new ClientDeathRecipient(token), 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "checkAuthority - remote error", e);
            }
        }
        LOG("checkAuthority", "res: " + result);
        return result;
    }

    public int registerMessageWatcher(IBinder token, int flags) {
        LOG("registerMessageWatcher", "flag: " + flags);
        if (token == null || flags == 0)
            return SecurityResult.REGISTER_FAILURE;

        SecurityRecord record = getSecurityRecord(token);

        if ((flags & FLAG_MONITOR_ALL_MESSAGE) != 0) {
            record.enableMonitorMessage(true, true);
            flags = 0;
        }
        if ((flags & FLAG_MONITOR_SENDING_MESSAGE) != 0) {
            record.enableMonitorMessage(true, false);
        }
        if ((flags & FLAG_MONITOR_RECEIVED_MESSAGE) != 0) {
            record.enableMonitorMessage(false, true);
        }
        LOG("registerMessageWatcher", "mRadioController.monitorMessage");
        mRadioController.monitorMessage(record);
        return SecurityResult.REGISTER_SUCCESS;
    }

    public Bundle sendMessageToBeChecked(Bundle message) {
        LOG("sendMessageToBeChecked", "message");
        return mRadioController.processCheckingMessage(message);
    }

    public int applyMessageToken(IMessageToken token) {
        mRadioController.applyMessageToken(token);
        return SecurityResult.APPLY_TOKEN_SUCCESS;
    }

    public int interceptMessage(IBinder token, int ident, int action) {
        LOG("interceptMessage", "ident: " + ident + "/action: " + action);
        SecurityRecord r = getSecurityRecord(token);
        if (r.isValidMessageIdent(ident)) {
            if (action == SecurityManager.ACTION_MESSAGE_BLOCK) {
                r.addInterceptMessage(ident);
            }
            mRadioController.interceptMessage(ident, action);
            return SecurityResult.INTERCEPT_MESSAGE_SUCCESS;
        }
        return SecurityResult.INTERCPET_MESSAGE_DUPLICATE;
    }

    private boolean isInvalidNetworkType(int type) {
        if (type < ISecurityManager.FLAG_NETWORK_MOBILE
                || type > ISecurityManager.FLAG_NETWORK_ALL) {
            return true;
        } else {
            return false;
        }
    }

    public int setFirewallPolicy(IBinder token, Bundle policy) {
        LOG("interceptMessage", "policy");
        // add a record here to record statistics
        int[] uids = policy.getIntArray("uid");
        boolean[] enabled = policy.getBooleanArray("enabled");
        int[] types = policy.getIntArray("type");
        if (uids.length != enabled.length
                || enabled.length != types.length
                || types.length != uids.length) {
            return SecurityResult.SET_FIREWALL_INVALID_INPUT;
        }

        SecurityRecord r = getSecurityRecord(token);
        for (int i = 0; i < uids.length; i++) {
            if (isInvalidNetworkType(types[i])) {
                return SecurityResult.SET_FIREWALL_INVALID_INPUT;
            }
            if(types[i] == ISecurityManager.FLAG_NETWORK_ALL){
                if (r.insertFirewallRecord(uids[i], enabled[i], ISecurityManager.FLAG_NETWORK_MOBILE)) {
                    mRadioController.setFirewallPolicy(uids[i], enabled[i], ISecurityManager.FLAG_NETWORK_MOBILE);
                }
                if (r.insertFirewallRecord(uids[i], enabled[i], ISecurityManager.FLAG_NETWORK_WIFI)) {
                    mRadioController.setFirewallPolicy(uids[i], enabled[i], ISecurityManager.FLAG_NETWORK_WIFI);
                }
            } else {
                if (r.insertFirewallRecord(uids[i], enabled[i], types[i])) {
                    mRadioController.setFirewallPolicy(uids[i], enabled[i], types[i]);
                }
            }
        }
        return SecurityResult.SET_FIREWALL_SUCCESS;
    }

    public int setPowerSaverMode(IBinder token, int mode) {
        LOG("setPowerSaverMode", "mode: " + mode);
        SecurityRecord record = getSecurityRecord(token);
        if (record.isValidPowerSaverMode(mode)) {
            return mPowerController.setPowerSaverMode(record);
        }
        return SecurityResult.SET_POWERMODE_UNSUPPORTED;
    }

    public int applyPermissionToken(IPermissionToken token) {
        LOG("applyPermissionToken", "Permission: ");
        mPermController.applyPermissionToken(token);
        return SecurityResult.APPLY_TOKEN_SUCCESS;
    }

    public int revokePermission(IBinder token,String permission,String packageName,int type) {
        LOG("revokePermission", "perm: " + permission + "/pkg: " + packageName + "/type: " + type);
        if (type > SecurityManager.PERM_DYNAMIC || type < SecurityManager.PERM_STATIC) {
            return SecurityResult.INVALID_PERM_TYPE;
        }
        SecurityRecord record = getSecurityRecord(token);
        if (record.revokePermission(permission, packageName, type)) {
           return mPermController.revokePermission(permission, packageName, type);
        }
        return SecurityResult.REVOKE_DUPLICATE_PERM;
    }

    public int grantPermission(IBinder token,String permission,String packageName) {
        LOG("grantPermission", "perm: " + permission + "/pkg: " + packageName);
        SecurityRecord record = getSecurityRecord(token);
        if (record.grantPermission(permission, packageName)) {
           return mPermController.grantPermission(permission, packageName);
        }
        return SecurityResult.GRANT_DUPLICATE_PERM;
    }

    public int revokePermission(IBinder token,String permission,int uid,int type) {
        LOG("revokePermission", "perm: " + permission + "/uid: " + uid + "/type: " + type);
        if (type > SecurityManager.PERM_DYNAMIC || type < SecurityManager.PERM_STATIC) {
            return SecurityResult.INVALID_PERM_TYPE;
        }
        SecurityRecord record = getSecurityRecord(token);
        if (record.revokePermission(permission, uid, type)) {
           return mPermController.revokePermission(permission, uid, type);
        }
        return SecurityResult.REVOKE_DUPLICATE_PERM;
    }

    public int grantPermission(IBinder token,String permission,int uid) {
        LOG("grantPermission", "perm: " + permission + "/uid: " + uid);
        SecurityRecord record = getSecurityRecord(token);
        if (record.grantPermission(permission, uid)) {
           return mPermController.grantPermission(permission, uid);
        }
        return SecurityResult.GRANT_DUPLICATE_PERM;
    }

    public int revokePermission(IBinder token,List<String> permissionList,String packageName,int type) {
        LOG("revokePermission", "perm: " + permissionList + "/pkg: " + packageName + "/type: " + type);
        if (type > SecurityManager.PERM_DYNAMIC || type < SecurityManager.PERM_STATIC) {
            return SecurityResult.INVALID_PERM_TYPE;
        }
        SecurityRecord record = getSecurityRecord(token);
        if (record.revokePermission(permissionList, packageName, type)) {
           return mPermController.revokePermission(permissionList, packageName, type);
        }
        return SecurityResult.REVOKE_DUPLICATE_PERM;
    }

    public int grantPermission(IBinder token,List<String> permissionList,String packageName) {
        LOG("grantPermission", "perm: " + permissionList + "/pkg: " + packageName);
        SecurityRecord record = getSecurityRecord(token);
        if (record.grantPermission(permissionList, packageName)) {
           return mPermController.grantPermission(permissionList, packageName);
        }
        return SecurityResult.GRANT_DUPLICATE_PERM;
    }

    public int revokePermission(IBinder token,List<String> permissionList,int uid,int type) {
        LOG("revokePermission", "perm: " + permissionList + "/uid: " + uid + "/type: " + type);
        if (type > SecurityManager.PERM_DYNAMIC || type < SecurityManager.PERM_STATIC) {
            return SecurityResult.INVALID_PERM_TYPE;
        }
        SecurityRecord record = getSecurityRecord(token);
        if (record.revokePermission(permissionList, uid, type)) {
           return mPermController.revokePermission(permissionList, uid, type);
        }
        return SecurityResult.REVOKE_DUPLICATE_PERM;
    }

    public int grantPermission(IBinder token,List<String> permissionList,int uid) {
        LOG("restorePermission", "perm: " + permissionList + "/uid: " + uid);
        SecurityRecord record = getSecurityRecord(token);
        if (record.grantPermission(permissionList, uid)) {
           return mPermController.grantPermission(permissionList, uid);
        }
        return SecurityResult.GRANT_DUPLICATE_PERM;
    }

    public int applyReceiverToken(IReceiverToken token) {
        LOG("applyReceiverToken", "Receiver: ");
        mReceiverController.applyReceiverToken(token);
        return SecurityResult.APPLY_TOKEN_SUCCESS;
    }

    public int blockActionReceiver(IBinder token,String action, String packageName) {
        LOG("blockActionReceiver", "action: " + action + "/packageName: " + packageName);
        SecurityRecord record = getSecurityRecord(token);
        if (record.addBlockAction(action, packageName)) {
           return mReceiverController.blockActionReceiver(action, packageName);
        }
        return SecurityResult.BLOCK_ACTION_SUCCESS;
    }

    public int restoreActionReceiver(IBinder token,String action,String packageName){
        LOG("restoreActionReceiver", "action: " + action + "/packageName: " + packageName);
        SecurityRecord record = getSecurityRecord(token);
        if (record.removeBlockAction(action, packageName)) {
           return mReceiverController.restoreActionReceiver(action, packageName);
        }
        return SecurityResult.RESTORE_ACTION_SUCCESS;
    }

    public int registerCallWatcher(IBinder token, int flags) {
        LOG("registerCallWatcher", "flag: " + flags);
        if (token == null || flags == 0)
            return SecurityResult.REGISTER_FAILURE;

        SecurityRecord record = getSecurityRecord(token);

        if ((flags & FLAG_MONITOR_CALL_IN_APP) != 0) {
            record.enableMonitorCallApp();
        }
        if ((flags & FLAG_MONITOR_CALL_IN_SYS) != 0) {
            record.enableMonitorCallSys();
        }
        LOG("registerCallWatcher", "mRadioController.monitorCall");
        mRadioController.monitorCall(record);
        return SecurityResult.REGISTER_SUCCESS;
    }

    public Bundle sendCallToBeChecked(Bundle call) {
        LOG("sendCallToBeChecked", "call");
        return mRadioController.processCheckingCall(call);
    }

    public int applyCallToken(ICallToken token) {
        mRadioController.applyCallToken(token);
        return SecurityResult.APPLY_TOKEN_SUCCESS;
    }

    public int interceptCall(IBinder token, int ident, int action) {
        LOG("interceptCall", "ident: " + ident + "/action: " + action);
        SecurityRecord r = getSecurityRecord(token);
        if (r.isValidCallIdent(ident)) {
            if (action == SecurityManager.ACTION_CALL_PASS) {
                r.removeBlockItem(ident);
            }
            mRadioController.interceptCall(ident, action);
            return SecurityResult.INTERCEPT_MESSAGE_SUCCESS;
        }
        return SecurityResult.INTERCPET_MESSAGE_DUPLICATE;
    }

    public int setCallBlackList(IBinder token, Bundle callList) {
        SecurityRecord r = getSecurityRecord(token);
        return r.insertCallBlackList(callList);
    }

    public int addCallBlackItem(IBinder token, Bundle call) {
        SecurityRecord r = getSecurityRecord(token);
        return r.addCallBlackItem(call);
    }

    public int removeCallBlackItem(IBinder token, Bundle call) {
        SecurityRecord r = getSecurityRecord(token);
        return r.removeCallBlackItem(call);
    }

    SecurityRecord getSecurityRecord(IBinder token) {
        return mSecurityRecords.get(token);
    }

    private static SecurityManagerService mSelf;

    public static SecurityManagerService main(Context context) {
        mSelf = new SecurityManagerService(context);
        try {
            mRadioController = RadioSecurityController.create(mSelf);
        } catch (InterruptedException e) {
        }
        ServiceManager.addService("security", mSelf);
        return mSelf;
    }

    private static Context mContext;
    private PowerSaverController mPowerController;
    private PermissionController mPermController;
    private ReceiverController mReceiverController;

    private SecurityManagerService(Context context) {
        mContext = context;
        mPowerController = new PowerSaverController(mContext);
        mPermController = new PermissionController(mContext);
        mReceiverController = new ReceiverController(mContext);
        SecurityRecord.initialize(mContext);
    }

    public void systemReady() {
        mSystemReady = true;
        mRadioController.systemReady();
        mReceiverController.systemReady();
    }

    private void LOG(String function, String msg) {
        if(DEBUG)
            Slog.d(TAG, "(" + function + "):" + msg);
    }
}
