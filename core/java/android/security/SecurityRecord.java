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
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Telephony.Sms.Intents;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.util.Slog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** {@hide} */
public class SecurityRecord {
    private final static boolean DBG = true;
    private final static String TAG = "SecurityRecord";
    private final static String DATABASE_NAME = "security_record.db";
    private static final int DATABASE_VERSION = 1;
    private static final String RECEIVER_RECORD_TABLE_NAME = "receiver_records";
    private static final String COLUMN_NAME_ACTION = "action";
    private static final String COLUMN_NAME_PKGNAME = "pkg_name";

	private static final String PERMISSION_RECORD_TABLE_NAME = "permission_records";
	private static final String FIREWALL_RECORD_TABLE_NAME = "firewall_records";
	private static final String BLOCK_ACTION_RECORD_TABLE_NAME = "block_action_records";
	private static final String COLUMN_NAME_PERMISSIONS = "permissions";
	private static final String COLUMN_NAME_ACTIONS = "actions";
	private static final String COLUMN_NAME_UID = "uid";
	private static final String COLUMN_NAME_BLOCK_MOBILE = "block_mobile";
	private static final String COLUMN_NAME_BLOCK_WIFI = "block_wifi";

	private IBinder mToken; // record remote clients
	private boolean mIsAuthorized; // check if the client is authoried by check
	private static HashMap<String, Integer> mVerificationRecords = new HashMap<String, Integer>();
	private static DatabaseHelper mOpenHelper;

    private void LOG(String msg) {
        if (DBG)
            Slog.d(TAG, msg);
    }

    public static void initialize(Context context) {
        mOpenHelper = new DatabaseHelper(context);
    }
    
    public static Bundle parseSms(Bundle message) {
        String direction = message.getString(ISecurityManager.MESSAGE_DIRECTION_KEY);
        if (ISecurityManager.MESSAGE_RECEIVED.equals(direction)) {
            // parse received sms from intent that contains pdu array to SmsMessage array.
            Intent intent = (Intent) (message.getParcelable(ISecurityManager.MESSAGE_CONTENT_KEY));
            SmsMessage[] msgs = Intents.getMessagesFromIntent(intent);
            StringBuilder body = new StringBuilder();
            for (SmsMessage msg : msgs) {
                if (msg.mWrappedSmsMessage != null) {
                    body.append(msg.getDisplayMessageBody());
                }
            }
            // Replace the content in message with parsed String.
            message.putString(ISecurityManager.MESSAGE_CONTENT_KEY, body.toString());
            message.putString(ISecurityManager.MESSAGE_PHONE_NUMBER_KEY,
                msgs[0].getDisplayOriginatingAddress());
            return message;
        } else {
            // We only need to parse received sms, so return directly for other cases.
            return message;
        }
    }

    private final class MessageRecord {
        private boolean mSendingWatcher = false;
        private boolean mReceivedWatcher = false;
        private ShortMessageRecord[] mShortMessageRecords;
        private HashMap<Integer, ShortMessageRecord> mCheckMessageRecords;

        private class ShortMessageItem {
            int mIdent;
            String mNumber;
            Object mContent;
            String mDirection;

            ShortMessageItem(int ident, String number, Object content, String direction) {
                mIdent = ident;
                   mNumber = number;
                mContent = content;
                mDirection = direction;
            }

            @Override
            public String toString() {
                return "ShortMessage Config{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + "/i: " + mIdent + "/num: " + mNumber 
                       + "/dir: " + mDirection + "}";
            }
        }

        private class ShortMessageRecord {
            //all sending/received messages
               private HashMap<Integer, ShortMessageItem> mShortMessages = new HashMap<Integer, ShortMessageItem>();
            //has been intercepted messages
            private ArrayList<ShortMessageItem> mInterceptedMessages = new ArrayList<ShortMessageItem>();

            int mSubscription;

            public ShortMessageRecord() {
                mSubscription = 0;
            }

            public ShortMessageRecord(int subscription) {
                mSubscription = subscription;
            }

            public void insertMessage(int ident, String number, String direction, Object content) {
                ShortMessageItem smi = new ShortMessageItem(ident, number, content, direction);
                mShortMessages.put(ident, smi);
            }

            public boolean isValidIdent(int ident) {
                ShortMessageItem smi = mShortMessages.get(ident);
                if (smi == null)
                    return false;
                return true;
            }

            public void addInterceptMessage(int ident) {
                ShortMessageItem smi = mShortMessages.get(ident);
                mInterceptedMessages.add(smi);
            }

            @Override
            public String toString() {
                StringBuffer sb = new StringBuffer("ShortMessageRecord: { \n");
                sb.append(mSubscription);
                sb.append("/Intercepted{ ");
                Iterator it = mInterceptedMessages.iterator();
                while(it.hasNext()) {
                    sb.append(((ShortMessageItem)it.next()).toString());
                    sb.append(";");
                }
                sb.append("}\n");
                sb.append("}");

                return sb.toString();
            }
        }

        public MessageRecord() {
            mShortMessageRecords = new ShortMessageRecord[TelephonyManager.getDefault().getPhoneCount()];
            for (int i = 0; i < mShortMessageRecords.length; i++) {
                mShortMessageRecords[i] = new ShortMessageRecord(i);
            }
            mCheckMessageRecords = new HashMap<Integer, ShortMessageRecord>();
        }

        public void setSendingWatcher(boolean enable) {
            mSendingWatcher = enable;
        }

        public void setReceivedWatcher(boolean enable) {
            mReceivedWatcher = enable;
        }

        public boolean isSendingWatcher() {
            return mSendingWatcher;
        }

        public boolean isReceivedWatcher() {
            return mReceivedWatcher;
        }

        public void insertMessage(int ident, String number, String direction, Object content, int subscription) {
            mShortMessageRecords[subscription].insertMessage(ident, number, direction, content);
            mCheckMessageRecords.put(ident, mShortMessageRecords[subscription]);
        }

        public boolean isValidIdent(int ident) {
            return mCheckMessageRecords.get(ident).isValidIdent(ident);
        }

        public void addInterceptMessage(int ident) {
            mCheckMessageRecords.get(ident).addInterceptMessage(ident);
        }
    }

    private MessageRecord mMessageRecord = new MessageRecord();

    public boolean isSendingWatcher() {
        return mMessageRecord.isSendingWatcher();
    }

    public boolean isReceivedWatcher() {
        return mMessageRecord.isReceivedWatcher();
    }

    public void enableMonitorMessage(boolean sending, boolean received) {
        LOG("enableMonitorMessage: sending: " + sending + " recv: " + received);
        mMessageRecord.setSendingWatcher(sending);
        mMessageRecord.setReceivedWatcher(received);
    }

    public void insertMessage(Bundle message) {
        int ident = message.getInt(ISecurityManager.MESSAGE_IDENT_KEY);
        String number = message.getString(ISecurityManager.MESSAGE_PHONE_NUMBER_KEY);
        String direction = message.getString(ISecurityManager.MESSAGE_DIRECTION_KEY);
        Object content;
        if (direction.equals(ISecurityManager.MESSAGE_SENDING)) {
            content = message.getString(ISecurityManager.MESSAGE_CONTENT_KEY);
        } else {
            content = message.getParcelable(ISecurityManager.MESSAGE_CONTENT_KEY);
        }
        LOG("insertMessasge: ident: " + ident + "/number: " + number + "/content: " + "/direction: " + direction);
        int subscription =  message.getInt(ISecurityManager.MESSAGE_SUBSCRIPTION_KEY, 0);
        mMessageRecord.insertMessage(ident, number, direction, content, subscription);
    }

    public boolean isValidMessageIdent(int ident) {
        return mMessageRecord.isValidIdent(ident);
    }

    public void addInterceptMessage(int ident) {
        mMessageRecord.addInterceptMessage(ident);
    }

    private class FirewallRecord {
        int mUid;
        boolean[] mEnable = {
                true, true
        }; // [0] for mobile, [1] for wifi

        FirewallRecord(int uid, boolean enable, int type) {
            mUid = uid;
		    /* Index need to minus by 1 because the type value is 1(mobile)/2(wifi)*/
            mEnable[type-1] = enable;
        }

        FirewallRecord(FirewallRecord r) {
            mUid = r.mUid;
            mEnable = r.mEnable;
        }

        public boolean isChanged(int type, boolean enable) {
            /* Index need to minus by 1 because the type value is 1(mobile)/2(wifi)*/
            if (mEnable[type-1] != enable) {
                mEnable[type-1] = enable;
                return true;
            }
            return false;
        }

        public boolean isAllEnabled() {
            boolean isAllEnabled = true;
            for (int i = 0; i < mEnable.length; i++) {
                isAllEnabled = isAllEnabled && mEnable[i];
            }
            return isAllEnabled;
        }

        @Override
        public String toString() {
            return "FirewallRecord Config{"
                + Integer.toHexString(System.identityHashCode(this))
                + "/uid: " + mUid + "/mobile: " + mEnable[0]
                + "/wifi: " + mEnable[1] + "}";
        }
    }

    
    
    private HashMap<Integer, FirewallRecord> mFirewallRecords = new HashMap<Integer, FirewallRecord>(); // blacklist
    private HashMap<Integer, FirewallRecord> mAllFirewallRecords = new HashMap<Integer, FirewallRecord>(); // all
                                                                                                           // list

    public SecurityRecord() {
        mToken = null;
        mIsAuthorized = false;
    }

    public SecurityRecord(IBinder token, boolean isAuthorized) {
        mToken = token;
        mIsAuthorized = isAuthorized;
        LOG("create record here: authorized: " + isAuthorized);
    }

    public IBinder getCallback() {
        return mToken;
    }

    public static int verifyPackage(String packageName){
        return mVerificationRecords.get(packageName);
    }

    public static void addVerifyInfo(String packageName, int verification){
        mVerificationRecords.put(packageName, verification);
    }

    public static void delVerifyInfo(String packageName){
        mVerificationRecords.remove(packageName);
    }

    // return ture if it is a new policy
    // else return false
    public boolean insertFirewallRecord(int uid, boolean enable, int type) {
        LOG("insertFirewallRecord");
        FirewallRecord record = mAllFirewallRecords.get(uid);
        boolean isChanged = false;
        if (record == null) {
            LOG("no record, create one");
            record = new FirewallRecord(uid, enable, type);
            if (!enable) {
                mFirewallRecords.put(uid, record);
                LOG("put to blacklist");
            }
            mAllFirewallRecords.put(uid, record);
            isChanged = true;
        } else {
            LOG("has record: " + record.toString());
            isChanged = record.isChanged(type, enable);
            if (isChanged) {
                // changed
                LOG("changed and refresh");
                FirewallRecord newRecord = new FirewallRecord(record);
                if (enable && newRecord.isAllEnabled()) {
                    mFirewallRecords.remove(uid);
                } else if (!enable) {
                    if (mFirewallRecords.get(uid) != null) {
                        mFirewallRecords.remove(uid);
                    }
                    mFirewallRecords.put(uid, newRecord);
                }
                mAllFirewallRecords.remove(uid);
                mAllFirewallRecords.put(uid, newRecord);
            }
        }
        return isChanged;
    }

    private class PowerSaverModeRecord {
        static final int SCREEN_OFF_MODE_INDEX = 0;
        static final int LOW_BATTERY_MODE_INDEX = 1;
        static final int HIGH_TEMPERATURE_MODE_INDEX = 2;
        static final int LIGHT_COMPUTING_MODE_INDEX = 3;

        boolean mIsScreenOffMode;
        boolean mIsLowBatteryMode;
        boolean mIsHighTemperatureMode;
        boolean mIsLightComputingMode;
        boolean mChanged[] = {
                false, false, false, false
        };

        public PowerSaverModeRecord() {
            mIsScreenOffMode = false;
            mIsLowBatteryMode = false;
            mIsHighTemperatureMode = false;
            mIsLightComputingMode = false;
        }

        public PowerSaverModeRecord(int mode) {
            mIsScreenOffMode = (mode & ISecurityManager.FLAG_SCREEN_OFF_MODE) != 0;
            mIsLowBatteryMode = (mode & ISecurityManager.FLAG_LOW_BATTERY_MODE) != 0;
            mIsHighTemperatureMode = (mode & ISecurityManager.FLAG_HIGH_TEMPERATURE_MODE) != 0;
            mIsLightComputingMode = (mode & ISecurityManager.FLAG_LIGHT_COMPUTING_MODE) != 0;
        }

        public boolean equals(Object o) {
            if (!(o instanceof PowerSaverModeRecord))
                return false;
            PowerSaverModeRecord r = (PowerSaverModeRecord) o;
            return (mIsScreenOffMode == r.mIsScreenOffMode)
                    && (mIsLowBatteryMode == r.mIsLowBatteryMode)
                    && (mIsHighTemperatureMode == r.mIsHighTemperatureMode)
                    && (mIsLightComputingMode == r.mIsLightComputingMode);
        }

        public void copyFrom(PowerSaverModeRecord source) {
            if (mIsScreenOffMode != source.mIsScreenOffMode) {
                mIsScreenOffMode = source.mIsScreenOffMode;
                mChanged[SCREEN_OFF_MODE_INDEX] = true;
            }else{
                mChanged[SCREEN_OFF_MODE_INDEX] = false;
            }
            if (mIsLowBatteryMode != source.mIsLowBatteryMode) {
                mIsLowBatteryMode = source.mIsLowBatteryMode;
                mChanged[LOW_BATTERY_MODE_INDEX] = true;
            }else{
                mChanged[LOW_BATTERY_MODE_INDEX] = false;
            }
            if (mIsHighTemperatureMode != source.mIsHighTemperatureMode) {
                mIsHighTemperatureMode = source.mIsHighTemperatureMode;
                mChanged[HIGH_TEMPERATURE_MODE_INDEX] = true;
            }else{
                mChanged[HIGH_TEMPERATURE_MODE_INDEX] = false;
            }
            if (mIsLightComputingMode != source.mIsLightComputingMode) {
                mIsLightComputingMode = source.mIsLightComputingMode;
                mChanged[LIGHT_COMPUTING_MODE_INDEX] = true;
            }else{
                mChanged[LIGHT_COMPUTING_MODE_INDEX] = false;
            }
        }

        public boolean isModeChanged(int index) {
            return mChanged[index];
        }

        public void resetModeChangedFlag(){
            mChanged[LOW_BATTERY_MODE_INDEX] = false;
            mChanged[LIGHT_COMPUTING_MODE_INDEX] = false;
            mChanged[HIGH_TEMPERATURE_MODE_INDEX] = false;
            mChanged[SCREEN_OFF_MODE_INDEX] = false;
        }

        @Override
        public String toString() {
            return "PowerSaverModeRecord Config{"
                + Integer.toHexString(System.identityHashCode(this))
                + "/soM: " + mIsScreenOffMode + "/lbM: " + mIsLowBatteryMode
                + "/htM: " + mIsHighTemperatureMode + "/lcM: " + mIsLightComputingMode
                + "}";
        }
    }

    private PowerSaverModeRecord mModeRecord = new PowerSaverModeRecord();

    public boolean isValidPowerSaverMode(int mode) {
        if (mode > ISecurityManager.FLAG_ALL_MODES)
            return false;

        PowerSaverModeRecord newRecord = new PowerSaverModeRecord(mode);
        if (!mModeRecord.equals(newRecord)) {
            mModeRecord.copyFrom(newRecord);
            return true;
        }else{
            mModeRecord.resetModeChangedFlag();
            return false;
        }
    }

    public boolean isScreenOffModeChanged() {
        return mModeRecord.isModeChanged(PowerSaverModeRecord.SCREEN_OFF_MODE_INDEX);
    }

    public boolean isLowBatteryModeChanged() {
        return mModeRecord.isModeChanged(PowerSaverModeRecord.LOW_BATTERY_MODE_INDEX);
    }

    public boolean isHighTemperatureModeChanged() {
        return mModeRecord.isModeChanged(PowerSaverModeRecord.HIGH_TEMPERATURE_MODE_INDEX);
    }

    public boolean isLightComputingModeChanged() {
        return mModeRecord.isModeChanged(PowerSaverModeRecord.LIGHT_COMPUTING_MODE_INDEX);
    }

    public boolean isScreenOffMode() {
        return mModeRecord.mIsScreenOffMode;
    }

    public boolean isLowBatteryMode() {
        return mModeRecord.mIsLowBatteryMode;
    }

    public boolean isHighTemperatureMode() {
        return mModeRecord.mIsHighTemperatureMode;
    }

    public boolean isLightComputingMode() {
        return mModeRecord.mIsLightComputingMode;
    }

    private final class PermissionRecord {
        private static final int CATEGORY_PACKAGE = 1;
        private static final int CATEGORY_UID = 2;
        HashMap<Integer, UidPerm> mUidPerms;
        HashMap<String, PackagePerm> mPkgPerms;

        private class Perm {
            String perm;
            int type;

            public Perm (String _perm, int _type) {
                perm = _perm;
                type = _type;
            }
        }

        private class PermsList {
            int category;
            HashMap<String, Perm> list;

            public PermsList(int _category) {
                category = _category;
                list = new HashMap<String, Perm>();
            }

            public boolean contains(String _perm) {
                return list.get(_perm) != null;
            }

            public void addPerm(String _perm, int type) {
                if(!contains(_perm)) {
                   Perm p = new Perm(_perm, type);
                   list.put(_perm, p);
                }
            }

            public void removePerm(String _perm) {
                if (contains(_perm))
                    list.remove(_perm);
            }

            public boolean isEmpty() {
                return list.isEmpty();
            }
        }

        private final class PackagePerm extends PermsList {
            String pkgName;

            public PackagePerm(String _pkgName) {
                super(CATEGORY_PACKAGE);
                pkgName = _pkgName;
            }

            public String getPackageName() {
                return pkgName;
            }
        }

        private final class UidPerm extends PermsList {
            int uid;
 
            public UidPerm(int _uid) {
                super(CATEGORY_UID);
                uid = _uid;
            }

            public int getUid() {
                return uid;
            }
        }

        public PermissionRecord() {
            mUidPerms = new HashMap<Integer, UidPerm>();
            mPkgPerms = new HashMap<String, PackagePerm>();
        }

        public boolean addPerm(String permission, String pkgName, int type) {
            PackagePerm p = mPkgPerms.get(pkgName);
            if (p == null) {
                p = new PackagePerm(pkgName);
                p.addPerm(permission, type);
                mPkgPerms.put(pkgName, p);
                return true;
            } else {
                if (!p.contains(permission)) {
                    p.addPerm(permission, type);
                    return true;
                }
            }
            return false;
        }

        public boolean removePerm(String permission, String pkgName) {
            PackagePerm p = mPkgPerms.get(pkgName);
            if (p != null) {
                p.removePerm(permission);
                if(p.isEmpty())
                    mPkgPerms.remove(pkgName);
                return true;
            }
            return false;

        }

        public boolean addPerm(String permission, int uid, int type) {
            UidPerm p = mUidPerms.get(uid);
            if (p == null) {
                p = new UidPerm(uid);
                p.addPerm(permission, type);
                mUidPerms.put(uid, p);
                return true;
            } else {
                if (!p.contains(permission)) {
                    p.addPerm(permission, type);
                    return true;
                }
            }
            return false;
        }

        public boolean removePerm(String permission, int uid) {
            UidPerm p = mUidPerms.get(uid);
            if (p != null) {
                p.removePerm(permission);
                if(p.isEmpty())
                    mUidPerms.remove(uid);
                return true;
            }
            return false;
        }

        public boolean addPermList(List<String> permissionList, String packageName, int type) {
            boolean result = false;
            Iterator<String> it = permissionList.iterator();
            while (it.hasNext()) {
                result |= addPerm(it.next(), packageName, type);
            }
            return result;
        }

        public boolean removePermList(List<String> permissionList, String packageName) {
            boolean result = false;
            Iterator<String> it = permissionList.iterator();
            while (it.hasNext()) {
                result |= removePerm(it.next(), packageName);
            }
            return result;
        }

        public boolean addPermList(List<String> permissionList, int uid, int type) {
            boolean result = false;
            Iterator<String> it = permissionList.iterator();
            while (it.hasNext()) {
                result |= addPerm(it.next(), uid, type);
            }
            return result;
        }

        public boolean removePermList(List<String> permissionList, int uid) {
            boolean result = false;
            Iterator<String> it = permissionList.iterator();
            while (it.hasNext()) {
                result |= removePerm(it.next(), uid);
            }
            return result;
        }
    }
    private PermissionRecord mPermRecord = new PermissionRecord();

    public boolean revokePermission(String permission, String pkgName, int type) {
        return mPermRecord.addPerm(permission, pkgName, type);
    }

    public boolean revokePermission(String permission, int uid, int type) {
        return mPermRecord.addPerm(permission, uid, type);
    }

    public boolean grantPermission(String permission, String pkgName) {
        return mPermRecord.removePerm(permission, pkgName);
    }

    public boolean grantPermission(String permission, int uid) {
        return mPermRecord.removePerm(permission, uid);
    }

    public boolean revokePermission(List<String> permissionList, String pkgName, int type) {
        return mPermRecord.addPermList(permissionList, pkgName, type);
    }

    public boolean revokePermission(List<String> permissionList, int uid, int type) {
        return mPermRecord.addPermList(permissionList, uid, type);
    }

    public boolean grantPermission(List<String> permissionList, String pkgName) {
        return mPermRecord.removePermList(permissionList, pkgName);
    }

    public boolean grantPermission(List<String> permissionList, int uid) {
        return mPermRecord.removePermList(permissionList, uid);
    }

    private final class ReceiverRecord {
        private HashMap<String, ArrayList<String>> mRecords;

        ReceiverRecord() {
            mRecords = new HashMap<String, ArrayList<String>>();
            loadDatabase();
        }

        private void loadDatabase() {
            SQLiteDatabase db = mOpenHelper.getReadableDatabase();
            String[] columns = { COLUMN_NAME_PKGNAME, COLUMN_NAME_ACTION };
            Cursor c = db.query(RECEIVER_RECORD_TABLE_NAME, columns, null, null, null, null, null);
            for (c.moveToFirst(); !c.isAfterLast(); c.moveToNext()) {
                String pkgName = c.getString(0);
                String action = c.getString(1);
                addBlockActionCached(action, pkgName);
            }
            LOG("receiver record database loaded with " + c.getCount() + " records");
            c.close();
        }

        private boolean addBlockActionCached(String action, String pkgName) {
            ArrayList<String> actions = mRecords.get(pkgName);
            if (actions == null) {
                actions = new ArrayList<String>();
                actions.add(action);
                mRecords.put(pkgName, actions);
                return true;
            } else {
                if (!actions.contains(action)) {
                    actions.add(action);
                    return true;
                }
            }
            return false;
        }

        private boolean insertRecord(String action, String pkgName) {
            SQLiteDatabase db = mOpenHelper.getWritableDatabase();

            ContentValues v = new ContentValues();
            v.put(COLUMN_NAME_PKGNAME, pkgName);
            v.put(COLUMN_NAME_ACTION, action);

            return (db.insert(RECEIVER_RECORD_TABLE_NAME, COLUMN_NAME_PKGNAME, v) != -1);
        }

        public boolean addBlockAction(String action, String pkgName) {
            if (addBlockActionCached(action, pkgName)) {
                return insertRecord(action, pkgName);
            }
            return false;
        }

        public boolean removeBlockActionCached(String action, String pkgName) {
            ArrayList<String> actions = mRecords.get(pkgName);
            if (actions != null && action.contains(action)) {
                actions.remove(action);
                if (actions.isEmpty())
                    mRecords.remove(pkgName);
                return true;
            }
            return false;
        }

        public boolean removeRecord(String action, String pkgName) {
            SQLiteDatabase db = mOpenHelper.getWritableDatabase();

            String where = COLUMN_NAME_PKGNAME + " = ? AND " + COLUMN_NAME_ACTION + " = ?";
            String[] args = { pkgName, action };

            return (db.delete(RECEIVER_RECORD_TABLE_NAME, where, args) != 0);
        }

        public boolean removeBlockAction(String action, String pkgName) {
            if (removeBlockActionCached(action, pkgName)) {
                return removeRecord(action, pkgName);
            }
            return false;
        }

        public void forEach(ReceiverRecordCallback callback) {
            int count = 0;
            for (Map.Entry<String, ArrayList<String>> entry : mRecords.entrySet()) {
                String pkgName = entry.getKey();
                for (String action : entry.getValue()) {
                    callback.apply(action, pkgName);
                    ++count;
                }
            }
            LOG("applied " + callback.toString() + " to " + count + " receiver records");
        }
    }

    public interface ReceiverRecordCallback {
        public void apply(String action, String pkgName);
    }

    private ReceiverRecord mReceiverRecord = new ReceiverRecord();

    public boolean addBlockAction(String action, String pkgName) {
        return mReceiverRecord.addBlockAction(action, pkgName);
    }

    public boolean removeBlockAction(String action, String pkgName) {
        return mReceiverRecord.removeBlockAction(action, pkgName);
    }

    public void forEachReceiverRecord(ReceiverRecordCallback callback) {
        mReceiverRecord.forEach(callback);
    }

    private final class CallRecord {
        private BlackRecord[] mRecords;
        private HashMap<Integer, BlackRecord> mCheckRecords;
        private int mMode;

        public CallRecord() {
            mRecords = new BlackRecord[TelephonyManager.getDefault().getPhoneCount()];
            for (int i = 0; i < mRecords.length; i++) {
                mRecords[i] = new BlackRecord(i);
            }
            mCheckRecords = new HashMap<Integer, BlackRecord>();
        }

        //here can be extended to contents which need by third party
        private class BlockItem {
            private int mIdent;
            private String mNumber;
            private long mDate;

            public BlockItem(int ident, String number) {
                mIdent = ident;
                mNumber = number;
                mDate = System.currentTimeMillis();
            }

            public String getNumber() {
                return mNumber;
            }

            public long getDate() {
                return mDate;
            }

            @Override
            public String toString() {
                return "BlockItem {" + " ident: "
                    + mIdent + "/number: "
                    + mNumber + "/date:"
                    + mDate + "}";
            }
        }

        private class BlackRecord {
            private HashSet<String> mBlackList = new HashSet<String>();
            private HashMap<Integer, BlockItem> mBlocked = new HashMap<Integer, BlockItem>();
            int mSubscription;

            public BlackRecord() {
                mSubscription = 0;
            }

            public BlackRecord(int subscription) {
                mSubscription = subscription;
            }

            public boolean addItemToList(String number) {
                LOG("BlackRecord:addItemToList/number: " + number);
                if (mBlackList.contains(number))
                    return false;
                mBlackList.add(number);
                return true;
            }

            public boolean removeBlackItem(String number) {
                LOG("BlackRecord:removeBlackItem/number: " + number);
                if (mBlackList.contains(number)) {
                    mBlackList.remove(number);
                    return true;
                }
                return false;
            }

            public void insertCheckItem(int ident, String number) {
                LOG("BlackRecord:insertCheckItem/number: " + number + "/ident: " + ident);
                BlockItem item = new BlockItem(ident, number);
                mBlocked.put(ident, item);
            }

            public BlockItem getCheckItem(int ident) {
                return mBlocked.get(ident);
            }

            public boolean isBlackItem(int ident) {
                BlockItem bi = getCheckItem(ident);
                if (mBlackList.contains(bi.getNumber())) {
                    LOG("contains black");
                    return true;
                }
                LOG("black not contains and remove it");
                mBlocked.remove(ident);
                return false;
            }

            public long getCallDate(int ident) {
                return mBlocked.get(ident).getDate();
            }

            public void removeBlockItem(int ident) {
                mBlocked.remove(ident);
            }

            @Override
            public String toString() {
                StringBuffer sb = new StringBuffer("BlackRecord: { \n");
                sb.append(mSubscription);
                sb.append("\nblacklist{ ");
                Iterator it = mBlackList.iterator();
                while(it.hasNext()) {
                    sb.append(it.next());
                    sb.append(";");
                }
                sb.append("}\n");
                sb.append("blocked { ");
                //traverse the block
                it = mBlocked.entrySet().iterator();
                while(it.hasNext()) {
                    Map.Entry entry = (Map.Entry) it.next();
                    sb.append("(key:");
                    sb.append(entry.getKey());
                    sb.append("|value:");
                    sb.append(entry.getValue().toString());
                    sb.append(");");
                }
                sb.append("}\n");
                sb.append("}");

                return sb.toString();
            }
        }

        public boolean addBlackItem(String number) {
            return addBlackItem(number, 0);
        }

        public boolean addBlackItem(String number, int subscription) {
            return mRecords[subscription].addItemToList(number);
        }

        public boolean removeBlackItem(String number) {
            return removeBlackItem(number, 0);
        }

        public boolean removeBlackItem(String number, int subscription) {
            return mRecords[subscription].removeBlackItem(number);
        }

        public void setMode(int mode) {
            mMode = mode;
        }

        public boolean isAppMode() {
           return mMode == ISecurityManager.CALL_MODE_APP;
        }

        public void insertCheckItem(int ident, String number, int subscription) {
           mRecords[subscription].insertCheckItem(ident, number);
           mCheckRecords.put(ident, mRecords[subscription]);
        }

        public boolean isValidIdent(int ident) {
           BlackRecord br = mCheckRecords.get(ident);
           if (br == null) {
               return false;
           }
           BlockItem bi = br.getCheckItem(ident);
           if (bi == null) {
               return false;
           }
           return true;
        }

        public boolean isBlackItem(int ident) {
           return mCheckRecords.get(ident).isBlackItem(ident);
        }

        public long getCallDate(int ident) {
           return mCheckRecords.get(ident).getCallDate(ident);
        }

        public void removeBlockItem(int ident) {
           mCheckRecords.get(ident).removeBlockItem(ident);
        }
    }

    private CallRecord mCallRecord = new CallRecord();

    public void enableMonitorCall(int mode) {
        mCallRecord.setMode(mode);
    }

    public void enableMonitorCallApp() {
        LOG("enableMonitorCallApp");
        enableMonitorCall(ISecurityManager.CALL_MODE_APP);
    }

    public void enableMonitorCallSys() {
        LOG("enableMonitorCallApp");
        enableMonitorCall(ISecurityManager.CALL_MODE_SYS);
    }

    public boolean isInAppCallMode() {
        return mCallRecord.isAppMode();
    }

    public int insertCallBlackList(Bundle list) {
        LOG("insertCallBlackList");
        ArrayList<String> numList = list.getStringArrayList(ISecurityManager.CALL_PHONE_NUMBER_KEY);
        if (numList == null || numList.size() == 0)
            return SecurityResult.INVALID_CALL_BLACK_LIST;
        Iterator it = numList.iterator();
        while (it.hasNext()) {
            if (it.next() == null)
                return SecurityResult.INVALID_CALL_BLACK_ITEM;
        }

        int subscription = list.getInt(ISecurityManager.CALL_SUBSCRIPTION_KEY, 0);
        it = numList.iterator();
        while(it.hasNext()) {
            mCallRecord.addBlackItem((String)it.next(), subscription);
        }
        return SecurityResult.INSERT_BLACK_LIST_SUCCESS;
    }

    public int addCallBlackItem(Bundle call) {
        String number = call.getString(ISecurityManager.CALL_PHONE_NUMBER_KEY);
        if (number == null)
            return SecurityResult.INVALID_CALL_BLACK_ITEM;
        int subscription = call.getInt(ISecurityManager.CALL_SUBSCRIPTION_KEY, 0);

        if (!mCallRecord.addBlackItem(number, subscription)) {
            return SecurityResult.DUPLICATE_BLACK_ITEM;
        }
        return SecurityResult.ADD_BLACK_ITEM_SUCCESS;
    }

    public int removeCallBlackItem(Bundle call) {
        String number = call.getString(ISecurityManager.CALL_PHONE_NUMBER_KEY);
        if (number == null)
            return SecurityResult.INVALID_CALL_BLACK_ITEM;
        int subscription = call.getInt(ISecurityManager.CALL_SUBSCRIPTION_KEY, 0);
        LOG("removeCallBlackItem { number: " + number + "/sub: " + subscription);
        if (!mCallRecord.removeBlackItem(number, subscription)) {
            return SecurityResult.NON_EXISTED_BLACK_ITEM;
        }
        return SecurityResult.REMOVE_BLACK_ITEM_SUCCESS;
    }

    public boolean isValidCallIdent(int ident) {
        return mCallRecord.isValidIdent(ident);
    }

    public void insertCallCheckItem(Bundle call) {
        int ident = call.getInt(ISecurityManager.CALL_IDENT_KEY);
        String number = call.getString(ISecurityManager.CALL_PHONE_NUMBER_KEY);
        int subscription = call.getInt(ISecurityManager.CALL_SUBSCRIPTION_KEY, 0);
        LOG("insertCallCheckItem: ident: " + ident + "/number: " + number + "/sub: " + subscription);
        mCallRecord.insertCheckItem(ident, number, subscription);
    }

    public boolean isCallInBlackList(int ident) {
        return mCallRecord.isBlackItem(ident);
    }

    public long getCallDate(int ident) {
        return mCallRecord.getCallDate(ident);
    }

    public void removeBlockItem(int ident) {
        mCallRecord.removeBlockItem(ident);
    }

    static class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

		@Override
		public void onCreate(SQLiteDatabase db) {
			db.execSQL("CREATE TABLE if not exists "
					+ RECEIVER_RECORD_TABLE_NAME + "(id INTEGER PRIMAY KEY,"
					+ COLUMN_NAME_ACTION + " TEXT, " + COLUMN_NAME_PKGNAME
					+ " TEXT," + " UNIQUE (" + COLUMN_NAME_ACTION + ", "
					+ COLUMN_NAME_PKGNAME + "));");

			db.execSQL("CREATE TABLE if not exists "
					+ PERMISSION_RECORD_TABLE_NAME + "(id INTEGER PRIMAY KEY,"
					+ COLUMN_NAME_PKGNAME + " TEXT," + COLUMN_NAME_PERMISSIONS
					+ " TEXT," + " UNIQUE (" + COLUMN_NAME_PKGNAME + "));");

			db.execSQL("CREATE TABLE if not exists " + FIREWALL_RECORD_TABLE_NAME
					+ "(id INTEGER PRIMAY KEY," + COLUMN_NAME_UID + " INTEGER,"
					+ COLUMN_NAME_BLOCK_MOBILE + " INTEGER,"
					+ COLUMN_NAME_BLOCK_WIFI + " INTEGER," + " UNIQUE ("
					+ COLUMN_NAME_UID + "));");

			db.execSQL("CREATE TABLE if not exists "
					+ BLOCK_ACTION_RECORD_TABLE_NAME
					+ "(id INTEGER PRIMAY KEY," + COLUMN_NAME_PKGNAME
					+ " TEXT," + COLUMN_NAME_ACTIONS + " TEXT," + " UNIQUE ("
					+ COLUMN_NAME_PKGNAME + "));");
		}

		@Override
		public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
			// do nothing for now
		}
	}


		/**
		 * get blocked actions
		 *
		 * @return list of blocked action entry
		 */
		public List<ActionReceiverEntry> getBlockedActionList() {
			try {
				SQLiteDatabase db = mOpenHelper.getReadableDatabase();

				String[] columns = { COLUMN_NAME_PKGNAME, COLUMN_NAME_ACTIONS };
				Cursor c = db.query(BLOCK_ACTION_RECORD_TABLE_NAME, columns,
						null, null, null, null, null);
				List<ActionReceiverEntry> actions = new ArrayList<ActionReceiverEntry>();
				for (c.moveToFirst(); !c.isAfterLast(); c.moveToNext()) {
					ActionReceiverEntry entry = new ActionReceiverEntry();
					entry.packageName = c.getString(0);
					String blockedActions = c.getString(1);
					entry.blockedActions = blockedActions.split(";");
					actions.add(entry);
				}
				c.close();
				return actions;
			} catch (Exception e) {
				return null;
			}
		}

		/**
		 * get blocked action by package name
		 *
		 * @param package name
		 * @return blocked action entry of given package name
		 */
		public ActionReceiverEntry getBlockedActionByPackage(String packName) {
			try {
				SQLiteDatabase db = mOpenHelper.getReadableDatabase();

				String[] columns = { COLUMN_NAME_PKGNAME, COLUMN_NAME_ACTIONS };
				String where = COLUMN_NAME_PKGNAME + " = '" + packName + "'";
				Cursor c = db.query(BLOCK_ACTION_RECORD_TABLE_NAME, columns,
						where, null, null, null, null);
				if (c.getCount() > 0) {
					c.moveToFirst();
					ActionReceiverEntry entry = new ActionReceiverEntry();
					entry.packageName = c.getString(0);
					String blockedActions = c.getString(1);
					entry.blockedActions = blockedActions.split(";");
					c.close();
					return entry;
				} else {
					c.close();
					return null;
				}
			} catch (Exception e) {
				return null;
			}
		}

		/**
		 * get fire wall list
		 *
		 * @return fire wall list
		 */
		public List<FirewallEntry> getFirewallList() {
			try {
				SQLiteDatabase db = mOpenHelper.getReadableDatabase();

				String[] columns = { COLUMN_NAME_UID, COLUMN_NAME_BLOCK_MOBILE,
						COLUMN_NAME_BLOCK_WIFI };
				Cursor c = db.query(FIREWALL_RECORD_TABLE_NAME, columns, null,
						null, null, null, null);
				List<FirewallEntry> firewallList = new ArrayList<FirewallEntry>();
				for (c.moveToFirst(); !c.isAfterLast(); c.moveToNext()) {
					FirewallEntry entry = new FirewallEntry();
					entry.uid = c.getInt(0);
					entry.mobileBlocked = c.getInt(1) == 1 ? true : false;
					entry.wifiBlocked = c.getInt(2) == 1 ? true : false;
					firewallList.add(entry);
				}
				c.close();
				return firewallList;
			} catch (Exception e) {
				return null;
			}
		}

		/**
		 * get fire wall entry by uid
		 *
		 * @param uid
		 * @return fire wall entry of given uid
		 */
		public FirewallEntry getFirewallByUID(int uid) {
			try {
				SQLiteDatabase db = mOpenHelper.getReadableDatabase();

				String[] columns = { COLUMN_NAME_UID, COLUMN_NAME_BLOCK_MOBILE,
						COLUMN_NAME_BLOCK_WIFI };
				String where = COLUMN_NAME_UID + " = '" + uid + "'";
				Cursor c = db.query(FIREWALL_RECORD_TABLE_NAME, columns, where,
						null, null, null, null);
				if (c.getCount() > 0) {
					c.moveToFirst();
					FirewallEntry entry = new FirewallEntry();
					entry.uid = c.getInt(0);
					entry.mobileBlocked = c.getInt(1) == 1 ? true : false;
					entry.wifiBlocked = c.getInt(2) == 1 ? true : false;
					c.close();
					return entry;
				} else {
					c.close();
					return null;
				}
			} catch (Exception e) {
				return null;
			}
		}

		/**
		 * get permission list
		 *
		 * @return permission list
		 */
		public List<PermissionEntry> getPermissionList() {
			try {
				SQLiteDatabase db = mOpenHelper.getReadableDatabase();

				String[] columns = { COLUMN_NAME_PKGNAME,
						COLUMN_NAME_PERMISSIONS };
				Cursor c = db.query(PERMISSION_RECORD_TABLE_NAME, columns,
						null, null, null, null, null);
				List<PermissionEntry> permissions = new ArrayList<PermissionEntry>();
				for (c.moveToFirst(); !c.isAfterLast(); c.moveToNext()) {
					PermissionEntry entry = new PermissionEntry();
					entry.packageName = c.getString(0);
					String permissionStr = c.getString(1);
					entry.revokedPermissions = permissionStr.split(";");
					permissions.add(entry);
				}
				c.close();
				return permissions;
			} catch (Exception e) {
				return null;
			}
		}

		/**
		 * get permission entry by package name
		 *
		 * @param package name
		 * @return permission entry of given package name
		 */
		public PermissionEntry getPermissionByPackage(String packName) {
			try {
				SQLiteDatabase db = mOpenHelper.getReadableDatabase();

				String[] columns = { COLUMN_NAME_PKGNAME,
						COLUMN_NAME_PERMISSIONS };
				String where = COLUMN_NAME_PKGNAME + " = '" + packName + "'";
				Cursor c = db.query(PERMISSION_RECORD_TABLE_NAME, columns,
						where, null, null, null, null);
				if (c.getCount() > 0) {
					c.moveToFirst();
					PermissionEntry entry = new PermissionEntry();
					entry.packageName = c.getString(0);
					String permissions = c.getString(1);
					entry.revokedPermissions = permissions.split(";");
					c.close();
					return entry;
				} else {
					c.close();
					return null;
				}
			} catch (Exception e) {
				return null;
			}
		}

		/**
		 * add permission list to database
		 *
		 * @param permission list
		 */
		public void addPermissionList(List<PermissionEntry> plist) {

			for (PermissionEntry entry : plist) {
				addPermissionEntry(entry);
			}
		}

		/**
		 * add permission entry to database
		 *
		 * @param permission entry
		 */
		public void addPermissionEntry(PermissionEntry entry) {
			try {
				SQLiteDatabase db = mOpenHelper.getWritableDatabase();

				StringBuilder permissions = new StringBuilder();
				int length = entry.revokedPermissions.length;
				for (int i = 0; i < length - 1; i++) {
					permissions.append(entry.revokedPermissions[i]).append(";");
				}
				permissions.append(entry.revokedPermissions[length - 1]);

				ContentValues v = new ContentValues();

				v.put(COLUMN_NAME_PKGNAME, entry.packageName);
				v.put(COLUMN_NAME_PERMISSIONS, permissions.toString());

				db.insert(PERMISSION_RECORD_TABLE_NAME, COLUMN_NAME_PKGNAME, v);
			} catch (Exception e) {
			}
		}

		/**
		 * add fire wall list to database
		 *
		 * @param fire wall list
		 */
		public void addFirewallList(List<FirewallEntry> flist) {

			for (FirewallEntry entry : flist) {
				addFirewallEntry(entry);
			}
		}

		/**
		 * add fire wall entry to database
		 *
		 * @param fire wall entry
		 */
		public void addFirewallEntry(FirewallEntry entry) {
			try {
				SQLiteDatabase db = mOpenHelper.getWritableDatabase();

				ContentValues v = new ContentValues();

				v.put(COLUMN_NAME_UID, entry.uid);
				v.put(COLUMN_NAME_BLOCK_MOBILE, entry.mobileBlocked == true ? 1 : 0);
				v.put(COLUMN_NAME_BLOCK_WIFI, entry.wifiBlocked == true ? 1 : 0);

				db.insert(FIREWALL_RECORD_TABLE_NAME, COLUMN_NAME_UID, v);
			} catch (Exception e) {
			}
		}

		/**
		 * add blocked action list to database
		 *
		 * @param blocked action list
		 */
		public void addBlockedActionList(List<ActionReceiverEntry> alist) {

			for (ActionReceiverEntry entry : alist) {
				addBlockedActionEntry(entry);
			}
		}

		/**
		 * add blocked action entry to database
		 *
		 * @param blocked action entry
		 */
		public void addBlockedActionEntry(ActionReceiverEntry entry) {
			try {
				SQLiteDatabase db = mOpenHelper.getWritableDatabase();

				StringBuilder actions = new StringBuilder();
				int length = entry.blockedActions.length;
				for (int i = 0; i < length - 1; i++) {
					actions.append(entry.blockedActions[i]).append(";");
				}
				actions.append(entry.blockedActions[length - 1]);

				ContentValues v = new ContentValues();

				v.put(COLUMN_NAME_PKGNAME, entry.packageName);
				v.put(COLUMN_NAME_ACTIONS, actions.toString());

				db.insert(BLOCK_ACTION_RECORD_TABLE_NAME, COLUMN_NAME_PKGNAME,
						v);
			} catch (Exception e) {
			}
		}

		/**
		 * update permissions to database by given  permission list
		 *
		 * @param permission list
		 */
		public void updatePermissionList(List<PermissionEntry> plist) {

			for (PermissionEntry entry : plist) {
				updatePermissionEntry(entry);
			}

		}

		/**
		 * update permissions to database by given  permission entry
		 *
		 * @param permission entry
		 */
		public void updatePermissionEntry(PermissionEntry entry) {
			try {
				SQLiteDatabase db = mOpenHelper.getWritableDatabase();

				StringBuilder permissions = new StringBuilder();
				int length = entry.revokedPermissions.length;
				for (int i = 0; i < length - 1; i++) {
					permissions.append(entry.revokedPermissions[i]).append(";");
				}
				permissions.append(entry.revokedPermissions[length - 1]);

				ContentValues v = new ContentValues();
				String where = COLUMN_NAME_PKGNAME + " = '" + entry.packageName + "'";
				v.put(COLUMN_NAME_PKGNAME, entry.packageName);
				v.put(COLUMN_NAME_PERMISSIONS, permissions.toString());

				db.update(PERMISSION_RECORD_TABLE_NAME, v, where, null);
			} catch (Exception e) {
			}
		}

		/**
		 * update fire wall to database by given fire wall list
		 *
		 * @param fire wall list
		 */
		public void updateFirewallList(List<FirewallEntry> flist) {

			for (FirewallEntry entry : flist) {
				updateFirewallEntry(entry);
			}
		}

		/**
		 * update fire wall to database by given fire wall entry
		 *
		 * @param fire wall entry
		 */
		public void updateFirewallEntry(FirewallEntry entry) {
			try {
				SQLiteDatabase db = mOpenHelper.getWritableDatabase();

				ContentValues v = new ContentValues();
				String where = COLUMN_NAME_UID + " = '" + entry.uid + "'";
				v.put(COLUMN_NAME_UID, entry.uid);
				v.put(COLUMN_NAME_BLOCK_MOBILE, entry.mobileBlocked == true ? 1: 0);
				v.put(COLUMN_NAME_BLOCK_WIFI, entry.wifiBlocked == true ? 1 : 0);

				db.update(FIREWALL_RECORD_TABLE_NAME, v, where, null);
			} catch (Exception e) {
			}
		}

		/**
		 * update blocked action to database by given blocked action list
		 *
		 * @param blocked action list
		 */
		public void updateBlockedActionList(List<ActionReceiverEntry> alist) {

			for (ActionReceiverEntry entry : alist) {
				updateBlockedActionEntry(entry);
			}
		}

		/**
		 * update blocked action to database by given blocked action entry
		 *
		 * @param blocked action entry
		 */
		public void updateBlockedActionEntry(ActionReceiverEntry entry) {
			try {
				SQLiteDatabase db = mOpenHelper.getWritableDatabase();

				StringBuilder actions = new StringBuilder();
				int length = entry.blockedActions.length;
				for (int i = 0; i < length - 1; i++) {
					actions.append(entry.blockedActions[i]).append(";");
				}
				actions.append(entry.blockedActions[length - 1]);

				ContentValues v = new ContentValues();
				String where = COLUMN_NAME_PKGNAME + " = '" + entry.packageName + "'";
				v.put(COLUMN_NAME_PKGNAME, entry.packageName);
				v.put(COLUMN_NAME_ACTIONS, actions.toString());

				db.update(BLOCK_ACTION_RECORD_TABLE_NAME, v, where, null);
			} catch (Exception e) {
			}
		}

		/**
		 * delete permissions to database by given  permission list
		 *
		 * @param permission list
		 */
		public void deletePermissionList(List<PermissionEntry> plist) {

			for (PermissionEntry entry : plist) {
				deletePermissionEntry(entry);
			}

		}

		/**
		 * delset permissions to database by given  permission entry
		 *
		 * @param permission entry
		 */
		public void deletePermissionEntry(PermissionEntry entry) {
			try {
				SQLiteDatabase db = mOpenHelper.getWritableDatabase();
				String where = COLUMN_NAME_PKGNAME + " = '" + entry.packageName + "'";
				db.delete(PERMISSION_RECORD_TABLE_NAME, where, null);
			} catch (Exception e) {
			}
		}

		/**
		 * delete fire wall to database by given fire wall list
		 *
		 * @param fire wall list
		 */
		public void deleteFirewallList(List<FirewallEntry> flist) {

			for (FirewallEntry entry : flist) {
				deleteFirewallEntry(entry);
			}
		}

		/**
		 * delete fire wall to database by given fire wall entry
		 *
		 * @param fire wall entry
		 */
		public void deleteFirewallEntry(FirewallEntry entry) {
			try {
				SQLiteDatabase db = mOpenHelper.getWritableDatabase();

				String where = COLUMN_NAME_UID + " = '" + entry.uid + "'";

				db.delete(FIREWALL_RECORD_TABLE_NAME, where, null);
			} catch (Exception e) {
			}
		}

		/**
		 * delete blocked action to database by given blocked action list
		 *
		 * @param blocked action list
		 */
		public void deleteBlockedActionList(List<ActionReceiverEntry> alist) {

			for (ActionReceiverEntry entry : alist) {
				deleteBlockedActionEntry(entry);
			}
		}

		/**
		 * delete blocked action to database by given blocked action entry
		 *
		 * @param blocked action entry
		 */
		public void deleteBlockedActionEntry(ActionReceiverEntry entry) {
			try {
				SQLiteDatabase db = mOpenHelper.getWritableDatabase();

				String where = COLUMN_NAME_PKGNAME + " = '" + entry.packageName + "'";

				db.delete(BLOCK_ACTION_RECORD_TABLE_NAME, where, null);
			} catch (Exception e) {
			}
		}


}
