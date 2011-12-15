/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.telephony;

import android.app.PendingIntent;
import android.util.Log;
import android.os.ServiceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * MSimIccSmsInterfaceManager to provide an inter-process communication to
 * access Sms in Icc.
 */
public class MSimIccSmsInterfaceManager extends ISmsMSim.Stub {
    static final String LOG_TAG = "RIL_MSimIccSms";

    protected Phone[] mPhone;

    protected MSimIccSmsInterfaceManager(Phone[] phone){
        mPhone = phone;

        if (ServiceManager.getService("isms_msim") == null) {
            ServiceManager.addService("isms_msim", this);
        }
    }

    protected void enforceReceiveAndSend(String message) {
        enforceReceiveAndSend(message, getPreferredSmsSubscription());
    }

    protected void enforceReceiveAndSend(String message, int subscription) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subscription);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.enforceReceiveAndSend(message);
        } else {
            Log.e(LOG_TAG,"enforceReceiveAndSend iccSmsIntMgr is null" +
                          " for Subscription:"+subscription);
        }
    }

    public boolean
    updateMessageOnIccEf(int index, int status, byte[] pdu, int subscription)
                throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subscription);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.updateMessageOnIccEf(index, status, pdu);
        } else {
            Log.e(LOG_TAG,"updateMessageOnIccEf iccSmsIntMgr is null" +
                          " for Subscription:"+subscription);
            return false;
        }
    }

    public boolean copyMessageToIccEf(int status, byte[] pdu, byte[] smsc, int subscription)
                throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subscription);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.copyMessageToIccEf(status, pdu, smsc);
        } else {
            Log.e(LOG_TAG,"copyMessageToIccEf iccSmsIntMgr is null" +
                          " for Subscription:"+subscription);
            return false;
        }
    }

    public List<SmsRawData> getAllMessagesFromIccEf(int subscription)
                throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subscription);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.getAllMessagesFromIccEf();
        } else {
            Log.e(LOG_TAG,"getAllMessagesFromIccEf iccSmsIntMgr is" +
                          " null for Subscription:"+subscription);
            return null;
        }
    }

    public void sendText(String destAddr, String scAddr,
            String text, PendingIntent sentIntent, PendingIntent deliveryIntent, int subscription) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subscription);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendText(destAddr, scAddr, text, sentIntent, deliveryIntent);
        } else {
            Log.e(LOG_TAG,"sendText iccSmsIntMgr is null for" +
                          " Subscription:"+subscription);
        }
    }

    public void sendMultipartText(String destAddr, String scAddr, List<String> parts,
            List<PendingIntent> sentIntents, List<PendingIntent> deliveryIntents, int subscription)
                    throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subscription);
        if (iccSmsIntMgr != null ) {
            iccSmsIntMgr.sendMultipartText(destAddr, scAddr, parts, sentIntents, deliveryIntents);
        } else {
            Log.e(LOG_TAG,"sendMultipartText iccSmsIntMgr is null for" +
                          " Subscription:"+subscription);
        }
    }

    public boolean enableCellBroadcast(int messageIdentifier, int subscription)
                throws android.os.RemoteException {
        return enableCellBroadcastRange(messageIdentifier, messageIdentifier, subscription);
    }

    public boolean enableCellBroadcastRange(int startMessageId, int endMessageId, int subscription) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subscription);
        if (iccSmsIntMgr != null ) {
            return iccSmsIntMgr.enableCellBroadcastRange(startMessageId, endMessageId);
        } else {
            Log.e(LOG_TAG,"enableCellBroadcast iccSmsIntMgr is null for" +
                          " Subscription:"+subscription);
        }
        return false;
    }

    public boolean disableCellBroadcast(int messageIdentifier, int subscription) {
        return disableCellBroadcastRange(messageIdentifier, messageIdentifier, subscription);
    }

    public boolean disableCellBroadcastRange(int startMessageId, int endMessageId, int subscription) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subscription);
        if (iccSmsIntMgr != null ) {
            return iccSmsIntMgr.disableCellBroadcastRange(startMessageId, endMessageId);
        } else {
            Log.e(LOG_TAG,"disableCellBroadcast iccSmsIntMgr is null for" +
                          " Subscription:"+subscription);
        }
       return false;
    }

    public boolean enableCdmaBroadcast(int messageIdentifier, int subscription) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subscription);
        if (iccSmsIntMgr != null ) {
            return iccSmsIntMgr.enableCdmaBroadcast(messageIdentifier);
        } else {
            Log.e(LOG_TAG,"enableCdmaBroadcast iccSmsIntMgr is null for" +
                          " Subscription:"+subscription);
        }
        return false;
    }

    public boolean disableCdmaBroadcast(int messageIdentifier, int subscription) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subscription);
        if (iccSmsIntMgr != null ) {
            return iccSmsIntMgr.disableCdmaBroadcast(messageIdentifier);
        } else {
            Log.e(LOG_TAG,"disableCdmaBroadcast iccSmsIntMgr is null for" +
                          " Subscription:"+subscription);
        }
       return false;
    }

    /**
     * get sms interface manager object based on subscription.
     **/
    private IccSmsInterfaceManager getIccSmsInterfaceManager(int subscription) {
        try {
            return ((MSimPhoneProxy)mPhone[subscription]).getIccSmsInterfaceManager();
        } catch (NullPointerException e) {
            Log.e(LOG_TAG, "Exception is :"+e.toString()+" For subscription :"+subscription );
            e.printStackTrace(); //This will print stact trace
            return null;
        } catch (ArrayIndexOutOfBoundsException e) {
            Log.e(LOG_TAG, "Exception is :"+e.toString()+" For subscription :"+subscription );
            e.printStackTrace(); //This will print stack trace
            return null;
        }
    }

    /**
       Gets User preferred SMS subscription */
    public int getPreferredSmsSubscription() {
        return MSimPhoneFactory.getSMSSubscription();
    }
}
