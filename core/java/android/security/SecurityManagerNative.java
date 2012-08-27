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

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ServiceManager;
import android.os.RemoteException;

import android.util.Singleton;
import java.util.ArrayList;
import java.util.List;

/** {@hide} */
public abstract class SecurityManagerNative extends Binder implements ISecurityManager
{
    /**
     * Cast a Binder object into an security manager interface, generating a
     * proxy if needed.
     */
    static public ISecurityManager asInterface(IBinder obj) {
        if (obj == null) {
            return null;
        }
        ISecurityManager in =
                (ISecurityManager) obj.queryLocalInterface(descriptor);
        if (in != null) {
            return in;
        }

        return new SecurityManagerProxy(obj);
    }

    /**
     * Retrieve the system's default/global activity manager.
     */
    static public ISecurityManager getDefault() {
        return gDefault.get();
    }

    /**
     * Convenience for checking whether the system is ready. For internal use
     * only.
     */
    static public boolean isSystemReady() {
        if (!sSystemReady) {
            sSystemReady = true;
        }
        return sSystemReady;
    }

    static boolean sSystemReady = false;

    public SecurityManagerNative() {
        attachInterface(this, descriptor);
    }

    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        switch (code) {
            case CHECK_AUTHORITY_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                String pkgName = data.readString();

                int result = checkAuthority(token, pkgName);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case REGISTER_MESSAGE_WATCHER_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                int scopes = data.readInt();

                int result = registerMessageWatcher(token, scopes);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case APPLY_MESSAGE_TOKEN_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                IMessageToken m = IMessageToken.Stub.asInterface(token);

                int result = applyMessageToken(m);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case SEND_MESSAGE_TO_BE_CHECKED_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                Bundle message = data.readBundle();

                Bundle result = sendMessageToBeChecked(message);
                reply.writeNoException();
                result.writeToParcel(reply, 0);
                return true;
            }

            case INTERCEPT_MESSAGE_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                int ident = data.readInt();
                int action = data.readInt();

                int result = interceptMessage(token, ident, action);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case SET_FIREWALL_POLICY_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                Bundle policy = data.readBundle();

                int result = setFirewallPolicy(token, policy);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case SET_POWER_SAVER_MODE_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                int mode = data.readInt();

                int result = setPowerSaverMode(token, mode);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case APPLY_PERMISSION_TOKEN_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                IPermissionToken p = IPermissionToken.Stub.asInterface(token);

                int result = applyPermissionToken(p);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case ENABLE_PERMISSION_CONTROLLER_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                boolean enable = (data.readByte() == 1);

                int result = enablePermissionController(token, enable);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case REVOKE_PERMISSION_PACKAGE_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                String permission = data.readString();
                String packageName = data.readString();
                int type = data.readInt();

                int result = revokePermission(token, permission, packageName, type);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case GRANT_PERMISSION_PACKAGE_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                String permission = data.readString();
                String packageName = data.readString();
                int type = data.readInt();

                int result = grantPermission(token, permission, packageName);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case REVOKE_PERMISSION_LIST_PACKAGE_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                List<String> permissionList = data.createStringArrayList();
                String packageName = data.readString();
                int type = data.readInt();

                int result = revokePermission(token, permissionList, packageName, type);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case GRANT_PERMISSION_LIST_PACKAGE_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                List<String> permissionList = data.createStringArrayList();
                String packageName = data.readString();
                int type = data.readInt();

                int result = grantPermission(token, permissionList, packageName);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case APPLY_RECEIVER_TOKEN_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                IReceiverToken p = IReceiverToken.Stub.asInterface(token);

                int result = applyReceiverToken(p);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case BLOCK_ACTION_RECEIVER_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                String permission = data.readString();
                String packageName = data.readString();

                int result = blockActionReceiver(token, permission, packageName);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case RESTORE_ACTION_RECEIVER_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                String permission = data.readString();
                String packageName = data.readString();

                int result = restoreActionReceiver(token, permission, packageName);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case ENABLE_RECEIVER_CONTROLLER_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                boolean enable = (data.readByte() == 1);

                int result = enableReceiverController(token, enable);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case REGISTER_CALL_WATCHER_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                int scopes = data.readInt();

                int result = registerCallWatcher(token, scopes);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case APPLY_CALL_TOKEN_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                ICallToken c = ICallToken.Stub.asInterface(token);

                int result = applyCallToken(c);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case SEND_CALL_TO_BE_CHECKED_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                Bundle call = data.readBundle();

                Bundle result = sendCallToBeChecked(call);
                reply.writeNoException();
                result.writeToParcel(reply, 0);
                return true;
            }

            case INTERCEPT_CALL_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                int ident = data.readInt();
                int action = data.readInt();

                int result = interceptCall(token, ident, action);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case SET_CALL_BLACK_LIST_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                Bundle callList = data.readBundle();

                int result = setCallBlackList(token, callList);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case ADD_CALL_BLACK_ITEM_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                Bundle call = data.readBundle();

                int result = addCallBlackItem(token, call);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case REMOVE_CALL_BLACK_ITEM_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                Bundle call = data.readBundle();

                int result = removeCallBlackItem(token, call);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case GET_FIREWALL_POLICY_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                int uid = data.readInt();

                FirewallEntry result = getFirewall(token, uid);
                reply.writeNoException();
                result.writeToParcel(reply, 0);
                return true;
            }

            case GET_PERMISSION_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                String packageName = data.readString();

                PermissionEntry result = getPermission(token, packageName);
                reply.writeNoException();
                result.writeToParcel(reply, 0);
                return true;
            }

            case GET_ACTION_RECEIVER_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                String packageName = data.readString();

                ActionReceiverEntry result = getActionReceiver(token, packageName);
                reply.writeNoException();
                result.writeToParcel(reply, 0);
                return true;
            }

            case GET_FIREWALL_POLICY_LIST_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();

                List<FirewallEntry> result = getFirewallList(token);
                reply.writeNoException();
                reply.writeTypedList(result);
                return true;
            }

            case GET_PERMISSION_LIST_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();

                List<PermissionEntry> result = getPermissionList(token);
                reply.writeNoException();
                reply.writeTypedList(result);
                return true;
            }

            case GET_ACTION_RECEIVER_LIST_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();

                List<ActionReceiverEntry> result = getActionReceiverList(token);
                reply.writeNoException();
                reply.writeTypedList(result);
                return true;
            }

            case CHECK_GUARD_AVAILABLE: {
                data.enforceInterface(ISecurityManager.descriptor);
                //IBinder token = data.readStrongBinder();

                int result = isGuardAvailable();
                reply.writeNoException();
                reply.writeInt(result);
            }

            case CLEAR_ALL_SETTINGS_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();

                clearAllSettings(token);
                reply.writeNoException();
                return true;
            }

            case CLEAR_FIREWALL_SETTINGS_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();

                clearFirewallSettings(token);
                reply.writeNoException();
                return true;
            }

            case CLEAR_PERMISSION_SETTINGS_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();

                clearPermissionSettings(token);
                reply.writeNoException();
                return true;
            }

            case CLEAR_ACTION_RECEIVER_SETTINGS_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();

                clearActionReceiverSettings(token);
                reply.writeNoException();
                return true;
            }

            case CHECK_FIREWALL_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                int uid = data.readInt();
                int type = data.readInt();

                int result = checkFirewall(token, uid, type);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case CHECK_PERMISSION_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                String packageName = data.readString();
                String permission = data.readString();

                int result = checkPermission(token, packageName, permission);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case CHECK_ACTION_RECEIVER_TRANSACTION: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                String packageName = data.readString();
                String action = data.readString();

                int result = checkActionReceiver(token, packageName, action);
                reply.writeNoException();
                reply.writeInt(result);
                return true;
            }

            case CLEAR_SINGLE_SETTINGS: {
                data.enforceInterface(ISecurityManager.descriptor);
                IBinder token = data.readStrongBinder();
                String packageName = data.readString();
                int uid = data.readInt();

                clearSingleSettings(token, packageName, uid);
                reply.writeNoException();
                return true;
            }
        }

        return super.onTransact(code, data, reply, flags);
    }

    public IBinder asBinder() {
        return this;
    }

    private static final Singleton<ISecurityManager> gDefault = new Singleton<ISecurityManager>() {
        protected ISecurityManager create() {
            IBinder b = ServiceManager.getService("security");
            ISecurityManager sm = asInterface(b);
            return sm;
        }
    };
}

class SecurityManagerProxy implements ISecurityManager
{
    public SecurityManagerProxy(IBinder remote)
    {
        mRemote = remote;
    }

    public IBinder asBinder()
    {
        return mRemote;
    }

    public int checkAuthority(IBinder token, String packageName) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeString(packageName);

        mRemote.transact(CHECK_AUTHORITY_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int registerMessageWatcher(IBinder token, int flags) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeInt(flags);

        mRemote.transact(REGISTER_MESSAGE_WATCHER_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int interceptMessage(IBinder token, int ident, int action) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeInt(ident);
        data.writeInt(action);

        mRemote.transact(INTERCEPT_MESSAGE_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int applyMessageToken(IMessageToken token) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token.asBinder());

        mRemote.transact(APPLY_MESSAGE_TOKEN_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public Bundle sendMessageToBeChecked(Bundle message) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        message.writeToParcel(data, 0);

        mRemote.transact(SEND_MESSAGE_TO_BE_CHECKED_TRANSACTION, data, reply, 0);
        reply.readException();
        Bundle result = reply.readBundle();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int setFirewallPolicy(IBinder token, Bundle policy) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        policy.writeToParcel(data, 0);

        mRemote.transact(SET_FIREWALL_POLICY_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int setPowerSaverMode(IBinder token, int mode) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeInt(mode);

        mRemote.transact(SET_POWER_SAVER_MODE_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int applyPermissionToken(IPermissionToken token) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token.asBinder());

        mRemote.transact(APPLY_PERMISSION_TOKEN_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int enablePermissionController(IBinder token, boolean enable) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeByte((byte)(enable ? 1 : 0));

        mRemote.transact(ENABLE_PERMISSION_CONTROLLER_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int revokePermission(IBinder token, String permission, String packageName, int type) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeString(permission);
        data.writeString(packageName);
        data.writeInt(type);

        mRemote.transact(REVOKE_PERMISSION_PACKAGE_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int grantPermission(IBinder token, String permission, String packageName) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeString(permission);
        data.writeString(packageName);

        mRemote.transact(GRANT_PERMISSION_PACKAGE_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int revokePermission(IBinder token,List<String> permissionList,String packageName,int type) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeStringList(permissionList);
        data.writeString(packageName);
        data.writeInt(type);

        mRemote.transact(REVOKE_PERMISSION_LIST_PACKAGE_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int grantPermission(IBinder token,List<String> permissionList,String packageName) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeStringList(permissionList);
        data.writeString(packageName);

        mRemote.transact(GRANT_PERMISSION_LIST_PACKAGE_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int applyReceiverToken(IReceiverToken token) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token.asBinder());

        mRemote.transact(APPLY_RECEIVER_TOKEN_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int blockActionReceiver(IBinder token,String action,String packageName) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeString(action);
        data.writeString(packageName);

        mRemote.transact(BLOCK_ACTION_RECEIVER_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int restoreActionReceiver(IBinder token,String action,String packageName) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeString(action);
        data.writeString(packageName);

        mRemote.transact(RESTORE_ACTION_RECEIVER_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int enableReceiverController(IBinder token, boolean enable) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeByte((byte)(enable ? 1 : 0));

        mRemote.transact(ENABLE_RECEIVER_CONTROLLER_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }
    
    public int registerCallWatcher(IBinder token, int flags) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken(ISecurityManager.descriptor);
            data.writeStrongBinder(token);
            data.writeInt(flags);
    
            mRemote.transact(REGISTER_CALL_WATCHER_TRANSACTION, data, reply, 0);
            reply.readException();
            int result = reply.readInt();
            reply.recycle();
            data.recycle();
            return result;
        }

    public int interceptCall(IBinder token, int ident, int action) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeInt(ident);
        data.writeInt(action);

        mRemote.transact(INTERCEPT_CALL_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int applyCallToken(ICallToken token) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token.asBinder());

        mRemote.transact(APPLY_CALL_TOKEN_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public Bundle sendCallToBeChecked(Bundle call) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        call.writeToParcel(data, 0);

        mRemote.transact(SEND_CALL_TO_BE_CHECKED_TRANSACTION, data, reply, 0);
        reply.readException();
        Bundle result = reply.readBundle();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int setCallBlackList(IBinder token, Bundle callList) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        callList.writeToParcel(data, 0);

        mRemote.transact(SET_CALL_BLACK_LIST_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int addCallBlackItem(IBinder token, Bundle call) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        call.writeToParcel(data, 0);

        mRemote.transact(ADD_CALL_BLACK_ITEM_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public int removeCallBlackItem(IBinder token, Bundle call) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        call.writeToParcel(data, 0);

        mRemote.transact(REMOVE_CALL_BLACK_ITEM_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public FirewallEntry getFirewall(IBinder token, int uid) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeInt(uid);

        mRemote.transact(GET_FIREWALL_POLICY_TRANSACTION, data, reply, 0);
        reply.readException();
        FirewallEntry result = FirewallEntry.CREATOR.createFromParcel(reply); 
        reply.recycle();
        data.recycle();
        return result;
    }

    public PermissionEntry getPermission(IBinder token, String packageName) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeString(packageName);

        mRemote.transact(GET_PERMISSION_TRANSACTION, data, reply, 0);
        reply.readException();
        PermissionEntry result = PermissionEntry.CREATOR.createFromParcel(reply); 
        reply.recycle();
        data.recycle();
        return result;
    }

    public ActionReceiverEntry getActionReceiver(IBinder token, String packageName) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeString(packageName);

        mRemote.transact(GET_ACTION_RECEIVER_TRANSACTION, data, reply, 0);
        reply.readException();
        ActionReceiverEntry result = ActionReceiverEntry.CREATOR.createFromParcel(reply); 
        reply.recycle();
        data.recycle();
        return result;
    }

    public List<FirewallEntry> getFirewallList(IBinder token) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);

        mRemote.transact(GET_FIREWALL_POLICY_LIST_TRANSACTION, data, reply, 0);
        reply.readException();
        List<FirewallEntry> result = reply.createTypedArrayList(FirewallEntry.CREATOR);
        reply.recycle();
        data.recycle();
        return result;
    }

    public List<PermissionEntry> getPermissionList(IBinder token) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);

        mRemote.transact(GET_PERMISSION_LIST_TRANSACTION, data, reply, 0);
        reply.readException();
        List<PermissionEntry> result = reply.createTypedArrayList(PermissionEntry.CREATOR);
        reply.recycle();
        data.recycle();
        return result;
    }

    public List<ActionReceiverEntry> getActionReceiverList(IBinder token) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);

        mRemote.transact(GET_ACTION_RECEIVER_LIST_TRANSACTION, data, reply, 0);
        reply.readException();
        List<ActionReceiverEntry> result = reply.createTypedArrayList(ActionReceiverEntry.CREATOR);
        reply.recycle();
        data.recycle();
        return result;
    }

    public int isGuardAvailable() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);

        mRemote.transact(CHECK_GUARD_AVAILABLE, data, reply, 0);
        reply.readException();
        int result = reply.readInt();
        reply.recycle();
        data.recycle();
        return result;
    }

    public void clearAllSettings(IBinder token) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);

        mRemote.transact(CLEAR_ALL_SETTINGS_TRANSACTION, data, reply, 0);
        reply.readException();
        reply.recycle();
        data.recycle();
    }

    public void clearFirewallSettings(IBinder token) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);

        mRemote.transact(CLEAR_FIREWALL_SETTINGS_TRANSACTION, data, reply, 0);
        reply.readException();
        reply.recycle();
        data.recycle();
    }

    public void clearPermissionSettings(IBinder token) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);

        mRemote.transact(CLEAR_PERMISSION_SETTINGS_TRANSACTION, data, reply, 0);
        reply.readException();
        reply.recycle();
        data.recycle();
    }

    public void clearActionReceiverSettings(IBinder token) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);

        mRemote.transact(CLEAR_ACTION_RECEIVER_SETTINGS_TRANSACTION, data, reply, 0);
        reply.readException();
        reply.recycle();
        data.recycle();
    }

    public int checkFirewall(IBinder token, int uid, int type) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeInt(uid);
        data.writeInt(type);

        mRemote.transact(CHECK_FIREWALL_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt(); 
        reply.recycle();
        data.recycle();
        return result;
    }
    
    public int checkPermission(IBinder token, String packageName, String permission) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeString(packageName);
        data.writeString(permission);

        mRemote.transact(CHECK_PERMISSION_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt(); 
        reply.recycle();
        data.recycle();
        return result;
    }

    public int checkActionReceiver(IBinder token, String packageName, String action) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeString(packageName);
        data.writeString(action);

        mRemote.transact(CHECK_ACTION_RECEIVER_TRANSACTION, data, reply, 0);
        reply.readException();
        int result = reply.readInt(); 
        reply.recycle();
        data.recycle();
        return result;
    }

    public void clearSingleSettings(IBinder token, String packageName, int uid) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(ISecurityManager.descriptor);
        data.writeStrongBinder(token);
        data.writeString(packageName);
        data.writeInt(uid);

        mRemote.transact(CLEAR_SINGLE_SETTINGS, data, reply, 0);
        reply.readException();
        reply.recycle();
        data.recycle();
    }


    private IBinder mRemote;
}
