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

import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;

import java.util.List;
import java.util.ArrayList;

/**
 * System private API for talking with the security manager service. This
 * provides calls from the application back to the security manager. 
 * 
 * {@hide}
 */
public interface ISecurityManager extends IInterface {

    public static final int FLAG_MONITOR_SENDING_MESSAGE = SecurityManager.SENDING_MESSAGE_WATCHER;
    public static final int FLAG_MONITOR_RECEIVED_MESSAGE = SecurityManager.RECEVIED_MESSAGE_WATCHER;
    public static final int FLAG_MONITOR_ALL_MESSAGE = SecurityManager.ALL_MESSAGE_WATCHER;

    public static final String MESSAGE_PHONE_NUMBER_KEY = "phoneNumber";
    public static final String MESSAGE_CONTENT_KEY = "content";
    public static final String MESSAGE_DIRECTION_KEY = "direction";
    public static final String MESSAGE_IDENT_KEY = "ident";
    public static final String MESSAGE_SENDING = "sending";
    public static final String MESSAGE_RECEIVED = "received";
    public static final String MESSAGE_SUBSCRIPTION_KEY = "subscription";

    public static final String BROADCAST_RESULT = "broadcastResult";

    public static final int FLAG_MONITOR_CALL_IN_APP = SecurityManager.MONITOR_CALL_IN_APP;
    public static final int FLAG_MONITOR_CALL_IN_SYS = SecurityManager.MONITOR_CALL_IN_SYS;

    public static final int CALL_MODE_APP = 1;
    public static final int CALL_MODE_SYS = 2;

    public static final int CALL_PASS = 1;
    public static final int CALL_BLOCK = 2;

    public static final String CALL_PHONE_NUMBER_KEY = "phoneNumber";
    public static final String CALL_IDENT_KEY = "ident";
    public static final String CALL_SUBSCRIPTION_KEY = "subscription";
    public static final String CALL_DATE_KEY = "date";

    public static final int FLAG_NETWORK_MOBILE = SecurityManager.FIREWALL_TYPE_MOBILE;
    public static final int FLAG_NETWORK_WIFI = SecurityManager.FIREWALL_TYPE_WIFI;
    public static final int FLAG_NETWORK_ALL = SecurityManager.FIREWALL_TYPE_ALL;

    public static final int FLAG_SCREEN_OFF_MODE = 1;
    public static final int FLAG_LOW_BATTERY_MODE = 1 << 1;
    public static final int FLAG_HIGH_TEMPERATURE_MODE = 1 << 2;
    public static final int FLAG_LIGHT_COMPUTING_MODE = 1 << 3;
    public static final int FLAG_ALL_MODES = FLAG_SCREEN_OFF_MODE | FLAG_LOW_BATTERY_MODE
        | FLAG_HIGH_TEMPERATURE_MODE | FLAG_LIGHT_COMPUTING_MODE;

    public int checkAuthority(IBinder token, String packageName) throws RemoteException;

    public int registerMessageWatcher(IBinder token, int flags) throws RemoteException;

    public int interceptMessage(IBinder token, int ident, int action) throws RemoteException;

    public int applyMessageToken(IMessageToken token) throws RemoteException;

    public Bundle sendMessageToBeChecked(Bundle message) throws RemoteException;

    public int setFirewallPolicy(IBinder token, Bundle policy) throws RemoteException;

    public int setPowerSaverMode(IBinder token, int mode) throws RemoteException;

    public int applyPermissionToken(IPermissionToken token) throws RemoteException;

    public int revokePermission(IBinder token, String permission, String packageName, int type) throws RemoteException;

    public int grantPermission(IBinder token, String permission, String packageName) throws RemoteException;

    public int revokePermission(IBinder token, String permission, int uid, int type) throws RemoteException;

    public int grantPermission(IBinder token, String permission, int uid) throws RemoteException;

    public int revokePermission(IBinder token, List<String> permissionList, String packageName, int type) throws RemoteException;

    public int grantPermission(IBinder token, List<String> permissionList, String packageName) throws RemoteException;

    public int revokePermission(IBinder token, List<String> permissionList, int uid, int type) throws RemoteException;

    public int grantPermission(IBinder token, List<String> permissionList, int uid) throws RemoteException;

    public int applyReceiverToken(IReceiverToken token) throws RemoteException;

    public int blockActionReceiver(IBinder token, String action, String packageName) throws RemoteException;

    public int restoreActionReceiver(IBinder token, String action, String packageName) throws RemoteException;

    public int registerCallWatcher(IBinder token, int flags) throws RemoteException;

    public int interceptCall(IBinder token, int ident, int action) throws RemoteException;

    public int applyCallToken(ICallToken token) throws RemoteException;

    public Bundle sendCallToBeChecked(Bundle call) throws RemoteException;

    public int setCallBlackList(IBinder token, Bundle callList) throws RemoteException;

    public int addCallBlackItem(IBinder token, Bundle call) throws RemoteException;

    public int removeCallBlackItem(IBinder token, Bundle call) throws RemoteException;

    String descriptor = "android.security.ISecurityManager";

    int CHECK_AUTHORITY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    int REGISTER_MESSAGE_WATCHER_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 1;
    int INTERCEPT_MESSAGE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 2;
    int APPLY_MESSAGE_TOKEN_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 3;
    int SEND_MESSAGE_TO_BE_CHECKED_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 4;
    int SET_FIREWALL_POLICY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 5;
    int SET_POWER_SAVER_MODE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 6;
    int APPLY_PERMISSION_TOKEN_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 7;
    int REVOKE_PERMISSION_PACKAGE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 8;
    int GRANT_PERMISSION_PACKAGE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 9;
    int REVOKE_PERMISSION_UID_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 10;
    int GRANT_PERMISSION_UID_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 11;
    int REVOKE_PERMISSION_LIST_PACKAGE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 12;
    int GRANT_PERMISSION_LIST_PACKAGE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 13;
    int REVOKE_PERMISSION_LIST_UID_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 14;
    int GRANT_PERMISSION_LIST_UID_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 15;
    int APPLY_RECEIVER_TOKEN_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 16;
    int BLOCK_ACTION_RECEIVER_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 17;
    int RESTORE_ACTION_RECEIVER_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 18;
    int REGISTER_CALL_WATCHER_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 19;
    int INTERCEPT_CALL_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 20;
    int APPLY_CALL_TOKEN_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 21;
    int SEND_CALL_TO_BE_CHECKED_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 22;
    int SET_CALL_BLACK_LIST_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 23;
    int ADD_CALL_BLACK_ITEM_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 24;
    int REMOVE_CALL_BLACK_ITEM_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 25;

}
