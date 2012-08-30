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
import android.content.pm.IPackageManager;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.RemoteException;
import android.provider.Telephony.Sms.Intents;
import android.security.SecurityRecordBase;
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

    private static final String PERMISSION_RECORD_TABLE_NAME = "permission_records";
    private static final String FIREWALL_RECORD_TABLE_NAME = "firewall_records";
    private static final String BLOCK_ACTION_RECORD_TABLE_NAME = "block_action_records";
    private static final String COLUMN_NAME_PERMISSION = "permission";
    private static final String COLUMN_NAME_PKGNAME = "pkg_name";
    private static final String COLUMN_NAME_ACTION = "action";
    private static final String COLUMN_NAME_UID = "uid";
    private static final String COLUMN_NAME_NETWORK_TYPE = "network_type";

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
        String direction = message
                .getString(ISecurityManager.MESSAGE_DIRECTION_KEY);
        if (ISecurityManager.MESSAGE_RECEIVED.equals(direction)) {
            // parse received sms from intent that contains pdu array to SmsMessage array.
            Intent intent = (Intent) (message
                    .getParcelable(ISecurityManager.MESSAGE_CONTENT_KEY));
            SmsMessage[] msgs = Intents.getMessagesFromIntent(intent);
            StringBuilder body = new StringBuilder();
            for (SmsMessage msg : msgs) {
                if (msg.mWrappedSmsMessage != null) {
                    body.append(msg.getDisplayMessageBody());
                }
            }
            // Replace the content in message with parsed String.
            message.putString(ISecurityManager.MESSAGE_CONTENT_KEY,
                    body.toString());
            message.putString(ISecurityManager.MESSAGE_PHONE_NUMBER_KEY,
                    msgs[0].getDisplayOriginatingAddress());
            return message;
        } else {
            // We only need to parse received sms, so return directly for other
            // cases.
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

            ShortMessageItem(int ident, String number, Object content,
                    String direction) {
                mIdent = ident;
                mNumber = number;
                mContent = content;
                mDirection = direction;
            }

            @Override
            public String toString() {
                return "ShortMessage Config{"
                        + Integer.toHexString(System.identityHashCode(this))
                        + "/i: " + mIdent + "/num: " + mNumber + "/dir: "
                        + mDirection + "}";
            }
        }

        private class ShortMessageRecord {
            // all sending/received messages
            private HashMap<Integer, ShortMessageItem> mShortMessages = new HashMap<Integer, ShortMessageItem>();
            // has been intercepted messages
            private ArrayList<ShortMessageItem> mInterceptedMessages = new ArrayList<ShortMessageItem>();

            int mSubscription;

            public ShortMessageRecord() {
                mSubscription = 0;
            }

            public ShortMessageRecord(int subscription) {
                mSubscription = subscription;
            }

            public void insertMessage(int ident, String number,
                    String direction, Object content) {
                ShortMessageItem smi = new ShortMessageItem(ident, number,
                        content, direction);
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
                while (it.hasNext()) {
                    sb.append(((ShortMessageItem) it.next()).toString());
                    sb.append(";");
                }
                sb.append("}\n");
                sb.append("}");

                return sb.toString();
            }
        }

        public MessageRecord() {
            mShortMessageRecords = new ShortMessageRecord[TelephonyManager
                    .getDefault().getPhoneCount()];
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

        public void insertMessage(int ident, String number, String direction,
                Object content, int subscription) {
            mShortMessageRecords[subscription].insertMessage(ident, number,
                    direction, content);
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
        String number = message
                .getString(ISecurityManager.MESSAGE_PHONE_NUMBER_KEY);
        String direction = message
                .getString(ISecurityManager.MESSAGE_DIRECTION_KEY);
        Object content;
        if (direction.equals(ISecurityManager.MESSAGE_SENDING)) {
            content = message.getString(ISecurityManager.MESSAGE_CONTENT_KEY);
        } else {
            content = message
                    .getParcelable(ISecurityManager.MESSAGE_CONTENT_KEY);
        }
        LOG("insertMessasge: ident: " + ident + "/number: " + number
                + "/content: " + "/direction: " + direction);
        int subscription = message.getInt(
                ISecurityManager.MESSAGE_SUBSCRIPTION_KEY, 0);
        mMessageRecord.insertMessage(ident, number, direction, content,
                subscription);
    }

    public boolean isValidMessageIdent(int ident) {
        return mMessageRecord.isValidIdent(ident);
    }

    public void addInterceptMessage(int ident) {
        mMessageRecord.addInterceptMessage(ident);
    }

    private class FirewallRecord extends SecurityRecordBase {
        private final String FIREWALL_TYPE_WIFI_STRING = String.valueOf(SecurityManager.FIREWALL_TYPE_WIFI);
        private final String FIREWALL_TYPE_MOBILE_STRING = String.valueOf(SecurityManager.FIREWALL_TYPE_MOBILE);

        public FirewallRecord(SQLiteOpenHelper openHelper,
                String table, String columnKey, String columnValue) {
            super(openHelper, table, columnKey, columnValue);
        }

        private boolean add(int uid, int type) {
            LOG("firewall add: uid: " + uid + ", type: " + type);
            String _uid = String.valueOf(uid);
            if (type == SecurityManager.FIREWALL_TYPE_ALL) {
                boolean r = add(_uid, FIREWALL_TYPE_WIFI_STRING);
                r |= add(_uid, FIREWALL_TYPE_MOBILE_STRING);
                return r;
            } else {
                return add(_uid, String.valueOf(type));
            }
        }

        private boolean remove(int uid, int type) {
            LOG("firewall remove: uid: " + uid + ", type: " + type);
            String _uid = String.valueOf(uid);
            if (type == SecurityManager.FIREWALL_TYPE_ALL) {
                boolean r = remove(_uid, FIREWALL_TYPE_WIFI_STRING);
                r |= remove(_uid, FIREWALL_TYPE_MOBILE_STRING);
                return r;
            } else {
                return remove(_uid, String.valueOf(type));
            }
        }

        public boolean setFirewall(int uid, boolean blocked, int type) {
            LOG("setFirewall: uid: " + uid + ", blocked: " + blocked + ", type: " + type);
            if (blocked)
                return add(uid, type);
            else
                return remove(String.valueOf(uid), String.valueOf(type));
        }

        public List<FirewallEntry> getEntryList() {
            List<FirewallEntry> list = new ArrayList<FirewallEntry>();
            for (String uid : mRecords.keySet()) {
                FirewallEntry e = getEntryByUid(Integer.parseInt(uid));
                if (e != null) {
                    list.add(e);
                }
            }
            LOG("get firewall entry list: " + list);
            return list;
        }

        public FirewallEntry getEntryByUid(int uid) {
            String _uid = String.valueOf(uid);
            boolean mobile = contains(_uid, FIREWALL_TYPE_MOBILE_STRING);
            boolean wifi = contains(_uid, FIREWALL_TYPE_WIFI_STRING);
            LOG("get Firewall entry: uid: " + uid + ", mobile: " + mobile + ", wifi: " + wifi);
            if (mobile || wifi) {
                FirewallEntry e = new FirewallEntry();
                e.uid = uid;
                e.mobileBlocked = mobile;
                e.wifiBlocked = wifi;
                return e;
            }
            return null;
        }

        public int checkFirewall(int uid, int type) {
            String _uid = String.valueOf(uid);
            boolean blocked = false;
            if (type == SecurityManager.FIREWALL_TYPE_ALL) {
                blocked = contains(_uid, FIREWALL_TYPE_WIFI_STRING)
                        && contains(_uid, FIREWALL_TYPE_MOBILE_STRING);
            } else {
                blocked = contains(_uid, String.valueOf(type));
            }
            return blocked ? SecurityResult.FIREWALL_BLOCKED : SecurityResult.FIREWALL_NOT_BLOCKED;
        }
    }

    private FirewallRecord mFirewallRecord = new FirewallRecord(mOpenHelper,
            FIREWALL_RECORD_TABLE_NAME, COLUMN_NAME_UID, COLUMN_NAME_NETWORK_TYPE);

    public SecurityRecordBase getFirewallRecord() {
        return mFirewallRecord;
    }

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

    public void setCallback(IBinder token) {
        mToken = token;
    }

    public static int verifyPackage(String packageName) {
        return mVerificationRecords.get(packageName);
    }

    public static void addVerifyInfo(String packageName, int verification) {
        mVerificationRecords.put(packageName, verification);
    }

    public static void delVerifyInfo(String packageName) {
        mVerificationRecords.remove(packageName);
    }

    // return ture if it is a new policy
    // else return false
    public boolean insertFirewallRecord(int uid, boolean enable, int type) {
        LOG("insertFirewallRecord");
        return mFirewallRecord.setFirewall(uid, !enable, type);
    }

    public void clearFirewalls() {
        mFirewallRecord.removeAll();
    }

    public void clearPermissions() {
        mPermRecord.removeAll();
    }

    public void clearActionReceivers() {
        mReceiverRecord.removeAll();
    }

    public void clearFirewallsByUid(int uid) {
        mFirewallRecord.removeKey(String.valueOf(uid));
    }

    public void clearPermissionsByPkg(String packageName) {
        mPermRecord.removeKey(packageName);
    }

    public void clearActionReceiversByPkg(String packageName) {
        mReceiverRecord.removeKey(packageName);
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
        boolean mChanged[] = { false, false, false, false };

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
            } else {
                mChanged[SCREEN_OFF_MODE_INDEX] = false;
            }
            if (mIsLowBatteryMode != source.mIsLowBatteryMode) {
                mIsLowBatteryMode = source.mIsLowBatteryMode;
                mChanged[LOW_BATTERY_MODE_INDEX] = true;
            } else {
                mChanged[LOW_BATTERY_MODE_INDEX] = false;
            }
            if (mIsHighTemperatureMode != source.mIsHighTemperatureMode) {
                mIsHighTemperatureMode = source.mIsHighTemperatureMode;
                mChanged[HIGH_TEMPERATURE_MODE_INDEX] = true;
            } else {
                mChanged[HIGH_TEMPERATURE_MODE_INDEX] = false;
            }
            if (mIsLightComputingMode != source.mIsLightComputingMode) {
                mIsLightComputingMode = source.mIsLightComputingMode;
                mChanged[LIGHT_COMPUTING_MODE_INDEX] = true;
            } else {
                mChanged[LIGHT_COMPUTING_MODE_INDEX] = false;
            }
        }

        public boolean isModeChanged(int index) {
            return mChanged[index];
        }

        public void resetModeChangedFlag() {
            mChanged[LOW_BATTERY_MODE_INDEX] = false;
            mChanged[LIGHT_COMPUTING_MODE_INDEX] = false;
            mChanged[HIGH_TEMPERATURE_MODE_INDEX] = false;
            mChanged[SCREEN_OFF_MODE_INDEX] = false;
        }

        @Override
        public String toString() {
            return "PowerSaverModeRecord Config{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + "/soM: " + mIsScreenOffMode + "/lbM: "
                    + mIsLowBatteryMode + "/htM: " + mIsHighTemperatureMode
                    + "/lcM: " + mIsLightComputingMode + "}";
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
        } else {
            mModeRecord.resetModeChangedFlag();
            return false;
        }
    }

    public boolean isScreenOffModeChanged() {
        return mModeRecord
                .isModeChanged(PowerSaverModeRecord.SCREEN_OFF_MODE_INDEX);
    }

    public boolean isLowBatteryModeChanged() {
        return mModeRecord
                .isModeChanged(PowerSaverModeRecord.LOW_BATTERY_MODE_INDEX);
    }

    public boolean isHighTemperatureModeChanged() {
        return mModeRecord
                .isModeChanged(PowerSaverModeRecord.HIGH_TEMPERATURE_MODE_INDEX);
    }

    public boolean isLightComputingModeChanged() {
        return mModeRecord
                .isModeChanged(PowerSaverModeRecord.LIGHT_COMPUTING_MODE_INDEX);
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

    private final class PermissionRecord extends SecurityRecordBase {
        public PermissionRecord(SQLiteOpenHelper openHelper,
                String table, String columnKey, String columnValue) {
            super(openHelper, table, columnKey, columnValue);
        }

        public List<PermissionEntry> getEntryList() {
            List<PermissionEntry> list = new ArrayList<PermissionEntry>();
            for(String p : mRecords.keySet()) {
                list.add(getEntryByPackage(p));
            }
            return list;
        }

        public PermissionEntry getEntryByPackage(String pkgName) {
            PermissionEntry e = new PermissionEntry();
            e.packageName = pkgName;
            e.revokedPermissions = mRecords.get(pkgName);
            return e;
        }

        public int checkPermission(String pkgName, String permission) {
            if (contains(pkgName, permission))
                return SecurityResult.PERMISSION_DENIED;
            else
                return SecurityResult.PERMISSION_GRANTED;
        }

        public void forEach(PermsRecordCallback callback) {
            int count = 0;
            for (Map.Entry<String, ArrayList<String>> entry : mRecords
                    .entrySet()) {
                String pkgName = entry.getKey();
                for (String permission : entry.getValue()) {
                    callback.apply(permission, pkgName);
                    ++count;
                }
            }
            LOG("applied " + callback.toString() + " to " + count
                    + " perms records");
        }
    }

    public interface PermsRecordCallback {
        public void apply(String permission, String pkgName);
    }

    private PermissionRecord mPermRecord = new PermissionRecord(mOpenHelper,
            PERMISSION_RECORD_TABLE_NAME, COLUMN_NAME_PKGNAME, COLUMN_NAME_PERMISSION);

    public SecurityRecordBase getPermissionRecord() {
        return mPermRecord;
    }

    public boolean revokePermission(String permission, String pkgName, int type) {
        LOG("revokePermission: " + "[permission: " + permission + ", pkgName: "+ pkgName + "]");
        return mPermRecord.add(pkgName, permission);
    }

    public boolean revokePermission(String permission, int uid, int type) {
        return true;
    }//TODO It's work?

    public boolean grantPermission(String permission, String pkgName) {
        LOG("grantPermission: " + "[permission: " + permission + ", pkgName: "+ pkgName + "]");
        return mPermRecord.remove(pkgName, permission);
    }

    public boolean grantPermission(String permission, int uid) {
        return true;
    }//TODO It's work?

    public boolean revokePermission(List<String> permissionList,
            String pkgName, int type) {
        LOG("revokePermission: " + "[permission: " + permissionList + ", pkgName: "+ pkgName + "]");    
        // type is not used yet
        boolean ret = false;
        for(String perm : permissionList){
            ret |= mPermRecord.add(pkgName, perm);
        }
        LOG("revokePermission list, return " + ret);
        return ret;
    }

    public boolean revokePermission(List<String> permissionList, int uid,
            int type) {
        return true;
    }//TODO It's work?

    public boolean grantPermission(List<String> permissionList, String pkgName) {
        LOG("grantPermission: " + "[permission: " + permissionList + ", pkgName: "+ pkgName + "]");
        boolean ret = false;
        for(String perm : permissionList){
            ret |= mPermRecord.remove(pkgName, perm);
        }
        LOG("grantPermission list, return " + ret);
        return ret;
    }

    public boolean grantPermission(List<String> permissionList, int uid) {
        return true;
    }//TODO It's work?

    private final class ReceiverRecord extends SecurityRecordBase {
        public ReceiverRecord(SQLiteOpenHelper openHelper,
                String table, String columnKey, String columnValue) {
            super(openHelper, table, columnKey, columnValue);
        }

        public List<ActionReceiverEntry> getEntryList() {
            List<ActionReceiverEntry> list = new ArrayList<ActionReceiverEntry>();
            for(String p : mRecords.keySet()){
                list.add(getEntryByPackage(p));
            }
            return list;
        }

        public ActionReceiverEntry getEntryByPackage(String pkgName) {
            ActionReceiverEntry e = new ActionReceiverEntry();
            e.packageName = pkgName;
            e.blockedActions = mRecords.get(pkgName);
            return e;
        }

        public int checkBlockedAction(String pkgName, String action) {
            if (contains(pkgName, action))
                return SecurityResult.ACTION_BLOCKED;
            else
                return SecurityResult.ACTION_NOT_BLOCKED;
        }
    }

    private ReceiverRecord mReceiverRecord = new ReceiverRecord(mOpenHelper,
            BLOCK_ACTION_RECORD_TABLE_NAME, COLUMN_NAME_PKGNAME, COLUMN_NAME_ACTION);

    public SecurityRecordBase getReceiverRecord() {
        return mReceiverRecord;
    }

    public boolean addBlockAction(String action, String pkgName) {
        LOG("addBlockAction: " + "[action: " + action + ", pkgName: "+ pkgName + "]");
        return mReceiverRecord.add(pkgName, action);
    }

    public boolean removeBlockAction(String action, String pkgName) {
        LOG("removeBlockAction: " + "[action: " + action + ", pkgName: "+ pkgName + "]");
        return mReceiverRecord.remove(pkgName, action);
    }

    private final class CallRecord {
        private BlackRecord[] mRecords;
        private HashMap<Integer, BlackRecord> mCheckRecords;
        private int mMode;

        public CallRecord() {
            mRecords = new BlackRecord[TelephonyManager.getDefault()
                    .getPhoneCount()];
            for (int i = 0; i < mRecords.length; i++) {
                mRecords[i] = new BlackRecord(i);
            }
            mCheckRecords = new HashMap<Integer, BlackRecord>();
        }

        // here can be extended to contents which need by third party
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
                return "BlockItem {" + " ident: " + mIdent + "/number: "
                        + mNumber + "/date:" + mDate + "}";
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
                LOG("BlackRecord:insertCheckItem/number: " + number
                        + "/ident: " + ident);
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
                while (it.hasNext()) {
                    sb.append(it.next());
                    sb.append(";");
                }
                sb.append("}\n");
                sb.append("blocked { ");
                // traverse the block
                it = mBlocked.entrySet().iterator();
                while (it.hasNext()) {
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
        ArrayList<String> numList = list
                .getStringArrayList(ISecurityManager.CALL_PHONE_NUMBER_KEY);
        if (numList == null || numList.size() == 0)
            return SecurityResult.INVALID_CALL_BLACK_LIST;
        Iterator it = numList.iterator();
        while (it.hasNext()) {
            if (it.next() == null)
                return SecurityResult.INVALID_CALL_BLACK_ITEM;
        }

        int subscription = list.getInt(ISecurityManager.CALL_SUBSCRIPTION_KEY,
                0);
        it = numList.iterator();
        while (it.hasNext()) {
            mCallRecord.addBlackItem((String) it.next(), subscription);
        }
        return SecurityResult.INSERT_BLACK_LIST_SUCCESS;
    }

    public int addCallBlackItem(Bundle call) {
        String number = call.getString(ISecurityManager.CALL_PHONE_NUMBER_KEY);
        if (number == null)
            return SecurityResult.INVALID_CALL_BLACK_ITEM;
        int subscription = call.getInt(ISecurityManager.CALL_SUBSCRIPTION_KEY,
                0);

        if (!mCallRecord.addBlackItem(number, subscription)) {
            return SecurityResult.DUPLICATE_BLACK_ITEM;
        }
        return SecurityResult.ADD_BLACK_ITEM_SUCCESS;
    }

    public int removeCallBlackItem(Bundle call) {
        String number = call.getString(ISecurityManager.CALL_PHONE_NUMBER_KEY);
        if (number == null)
            return SecurityResult.INVALID_CALL_BLACK_ITEM;
        int subscription = call.getInt(ISecurityManager.CALL_SUBSCRIPTION_KEY,
                0);
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
        int subscription = call.getInt(ISecurityManager.CALL_SUBSCRIPTION_KEY,
                0);
        LOG("insertCallCheckItem: ident: " + ident + "/number: " + number
                + "/sub: " + subscription);
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
                    + BLOCK_ACTION_RECORD_TABLE_NAME + "(id INTEGER PRIMAY KEY,"
                    + COLUMN_NAME_PKGNAME + " TEXT, " + COLUMN_NAME_ACTION
                    + " TEXT," + " UNIQUE (" + COLUMN_NAME_PKGNAME + ", "
                    + COLUMN_NAME_ACTION + "));");

            db.execSQL("CREATE TABLE if not exists "
                    + PERMISSION_RECORD_TABLE_NAME + "(id INTEGER PRIMAY KEY,"
                    + COLUMN_NAME_PKGNAME + " TEXT," + COLUMN_NAME_PERMISSION
                    + " TEXT," + " UNIQUE (" + COLUMN_NAME_PKGNAME + ", "
                    + COLUMN_NAME_PERMISSION + "));");

            db.execSQL("CREATE TABLE if not exists " + FIREWALL_RECORD_TABLE_NAME
                    + "(id INTEGER PRIMAY KEY," + COLUMN_NAME_UID + " INTEGER,"
                    + COLUMN_NAME_NETWORK_TYPE + " INTEGER," + " UNIQUE ("
                    + COLUMN_NAME_UID + ", " + COLUMN_NAME_NETWORK_TYPE + "));");

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
        return mReceiverRecord.getEntryList();
    }

    /**
     * get blocked action by package name
     *
     * @param package name
     * @return blocked action entry of given package name
     */
    public ActionReceiverEntry getBlockedActionByPackage(String pkgName) {
        return mReceiverRecord.getEntryByPackage(pkgName);
    }

    /**
     * get fire wall list
     *
     * @return fire wall list
     */
    public List<FirewallEntry> getFirewallList() {
        return mFirewallRecord.getEntryList();
    }

    /**
     * get fire wall entry by uid
     *
     * @param uid
     * @return fire wall entry of given uid
     */
    public FirewallEntry getFirewallByUID(int uid) {
        return mFirewallRecord.getEntryByUid(uid);
    }

    /**
     * get permission list
     *
     * @return permission list
     */
    public List<PermissionEntry> getPermissionList() {
        return mPermRecord.getEntryList();
    }

    /**
     * get permission entry by package name
     *
     * @param package name
     * @return permission entry of given package name
     */
    public PermissionEntry getPermissionByPackage(String pkgName) {
        return mPermRecord.getEntryByPackage(pkgName);
    }

    /**
     * add permission list to database
     *
     * @param permission
     *            list
     */
    public void addPermissionList(List<PermissionEntry> plist) {

        for (PermissionEntry entry : plist) {
            addPermissionEntry(entry);
        }
    }

    /**
     * add permission entry to database
     *
     * @param permission
     *            entry
     */
    public void addPermissionEntry(PermissionEntry entry) {
        for (String perm : entry.revokedPermissions) {
            addPermissionByPackage(perm, entry.packageName);
        }
    }

    /**
     * add single permission to database by given package name
     *
     * @param permission
     * @param package name
     */
    public void addPermissionByPackage(String permission, String pkgName) {
        mPermRecord.add(pkgName, permission);
    }

    /**
     * add fire wall list to database
     *
     * @param fire
     *            wall list
     */
    public void addFirewallList(List<FirewallEntry> flist) {

        for (FirewallEntry entry : flist) {
            addFirewallEntry(entry);
        }
    }

    /**
     * add fire wall entry to database
     *
     * @param fire
     *            wall entry
     */
    public void addFirewallEntry(FirewallEntry entry) {
        mFirewallRecord
                .setFirewall(entry.uid, entry.mobileBlocked, SecurityManager.FIREWALL_TYPE_MOBILE);
        mFirewallRecord
                .setFirewall(entry.uid, entry.wifiBlocked, SecurityManager.FIREWALL_TYPE_WIFI);
    }

    /**
     * add blocked action list to database
     *
     * @param blocked
     *            action list
     */
    public void addBlockedActionList(List<ActionReceiverEntry> alist) {

        for (ActionReceiverEntry entry : alist) {
            addBlockedActionEntry(entry);
        }
    }

    /**
     * add blocked action entry to database
     *
     * @param blocked
     *            action entry
     */
    public void addBlockedActionEntry(ActionReceiverEntry entry) {
        for (String action : entry.blockedActions) {
            addBlockedActionByPackage(action,
                    entry.packageName);
        }
    }

    /**
     * add single blocked action to database by given package name
     *
     * @param blocked action
     * @param package name
     */
    public void addBlockedActionByPackage(String action, String pkgName) {
        mReceiverRecord.add(pkgName, action);
    }

    /**
     * remove permissions to database by given permission list
     *
     * @param permission
     *            list
     */
    public void removeAllPermissions() {
        mPermRecord.removeAll();
    }

    /**
     * remove permissions to database by given permission list
     *
     * @param permission
     *            list
     */
    public void removePermissionList(List<PermissionEntry> plist) {
        for (PermissionEntry entry : plist) {
            removePermissionEntry(entry);
        }
    }

    /**
     * remove permissions to database by given permission entry
     *
     * @param permission
     *            entry
     */
    public void removePermissionEntry(PermissionEntry entry) {
        mPermRecord.removeKey(entry.packageName);
    }

    /**
     * remove single permission to database by given package name
     *
     * @param permission
     * @param package name
     */
    public void removePermissionByPackage(String permission, String pkgName) {
        mPermRecord.remove(pkgName, permission);
    }

    /**
     * remove fire wall to database by given fire wall list
     *
     * @param fire
     *            wall list
     */
    public void removeAllFirewalls() {
        mFirewallRecord.removeAll();
    }

    /**
     * remove fire wall to database by given fire wall list
     *
     * @param fire
     *            wall list
     */
    public void removeFirewallList(List<FirewallEntry> flist) {
        for (FirewallEntry entry : flist) {
            removeFirewallEntry(entry);
        }
    }

    /**
     * remove fire wall to database by given fire wall entry
     *
     * @param fire
     *            wall entry
     */
    public void removeFirewallEntry(FirewallEntry entry) {
        // TODO: mFirewallRecord.remove(entry.uid);
    }

    /**
     * remove blocked action to database by given blocked action list
     *
     * @param blocked
     *            action list
     */
    public void removeAllBlockedActions() {
        mReceiverRecord.removeAll();
    }

    /**
     * remove blocked action to database by given blocked action lis
     *
     * @param blocked
     *            action list
     */
    public void removeBlockedActionList(List<ActionReceiverEntry> alist) {
        for (ActionReceiverEntry entry : alist) {
            removeBlockedActionEntry(entry);
        }
    }


    /**
     * remove blocked action to database by given blocked action entry
     *
     * @param blocked
     *            action entry
     */
    public void removeBlockedActionEntry(ActionReceiverEntry entry) {
        mReceiverRecord.removeKey(entry.packageName);
    }

    /**
     * remove single blocked action to database by given package name
     *
     * @param blocked action
     * @param package name
     */
    public void removeBlockedActionByPackage(String action,String pkgName) {
        mReceiverRecord.remove(pkgName, action);
    }

    public int checkFirewall(int uid, int type) {
        return mFirewallRecord.checkFirewall(uid, type);
    }

    public int checkPermission(String packageName, String permission) {
        return mPermRecord.checkPermission(packageName, permission);
    }

    public int checkBlockedAction(String packageName, String action) {
        return mReceiverRecord.checkBlockedAction(packageName, action);
    }
}
