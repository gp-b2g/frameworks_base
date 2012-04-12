/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2012 Code Aurora Forum. All rights reserved.
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


import android.app.Activity;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.SQLException;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Telephony;
import android.provider.Telephony.Sms.Intents;
import android.telephony.SmsManager;
import android.telephony.SmsMessage.MessageClass;
import android.util.Log;
import com.android.internal.telephony.CommandsInterface.RadioTechnologyFamily;
import com.android.internal.telephony.SmsMessageBase.TextEncodingDetails;
import com.android.internal.telephony.UiccManager.AppFamily;
import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.cdma.SmsMessage;
import com.android.internal.telephony.cdma.sms.SmsEnvelope;
import com.android.internal.telephony.cdma.sms.UserData;
import com.android.internal.telephony.Subscription;
import com.android.internal.telephony.SubscriptionManager;
import com.android.internal.util.HexDump;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;

import android.content.res.Resources;


final class CdmaSMSDispatcher extends SMSDispatcher {
    private static final String TAG = "CDMA";
    private ImsSMSDispatcher mImsSMSDispatcher;
    private int mTeleserviceId = 0;

    private byte[] mLastDispatchedSmsFingerprint;
    private byte[] mLastAcknowledgedSmsFingerprint;

    private final boolean mCheckForDuplicatePortsInOmadmWapPush = Resources.getSystem().getBoolean(
            com.android.internal.R.bool.config_duplicate_port_omadm_wappush);

    CdmaSMSDispatcher(PhoneBase phone, SmsStorageMonitor storageMonitor,
            SmsUsageMonitor usageMonitor, ImsSMSDispatcher imsSMSDispatcher) {
        super(phone, storageMonitor, usageMonitor);
        mImsSMSDispatcher = imsSMSDispatcher;
        mCm.setOnNewCdmaSms(this, EVENT_NEW_SMS, null);
        Log.d(TAG, "CdmaSMSDispatcher created");
    }

    @Override
    public void dispose() {
        mCm.unSetOnNewCdmaSms(this);
        super.dispose();
    }

    @Override
    protected String getFormat() {
        return android.telephony.SmsMessage.FORMAT_3GPP2;
    }

    /**
     * Called when a Class2 SMS is  received.
     *
     * @param ar AsyncResult passed to this function. "ar.result" should
     *           be representing the INDEX of SMS on SIM.
     */
    protected void handleSmsOnIcc(AsyncResult ar) {
        Log.d(TAG, "handleSmsOnIcc function is not applicable for CDMA");
    }

    /**
     * Called when a SMS on SIM is retrieved.
     *
     * @param ar AsyncResult passed to this function.
     */
    protected void handleGetIccSmsDone(AsyncResult ar) {
        Log.d(TAG, "handleGetIccSmsDone function is not applicable for CDMA");
    }

    private void handleCdmaStatusReport(SmsMessage sms) {
        for (int i = 0, count = deliveryPendingList.size(); i < count; i++) {
            SmsTracker tracker = deliveryPendingList.get(i);
            if (tracker.mMessageRef == sms.messageRef) {
                // Found it.  Remove from list and broadcast.
                deliveryPendingList.remove(i);
                PendingIntent intent = tracker.mDeliveryIntent;
                Intent fillIn = new Intent();
                fillIn.putExtra("pdu", sms.getPdu());
                fillIn.putExtra("format", android.telephony.SmsMessage.FORMAT_3GPP2);
                try {
                    intent.send(mContext, Activity.RESULT_OK, fillIn);
                } catch (CanceledException ex) {}
                break;  // Only expect to see one tracker matching this message.
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected int dispatchMessage(SmsMessageBase smsb) {

        // If sms is null, means there was a parsing error.
        if (smsb == null) {
            Log.e(TAG, "dispatchMessage: message is null");
            return Intents.RESULT_SMS_GENERIC_ERROR;
        }

        String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        if (inEcm.equals("true")) {
            return Activity.RESULT_OK;
        }

        if (mSmsReceiveDisabled) {
            // Device doesn't support receiving SMS,
            Log.d(TAG, "Received short message on device which doesn't support "
                    + "receiving SMS. Ignored.");
            return Intents.RESULT_SMS_HANDLED;
        }

        // See if we have a network duplicate SMS.
        SmsMessage sms = (SmsMessage) smsb;
        mLastDispatchedSmsFingerprint = sms.getIncomingSmsFingerprint();
        if (mLastAcknowledgedSmsFingerprint != null &&
                Arrays.equals(mLastDispatchedSmsFingerprint, mLastAcknowledgedSmsFingerprint)) {
            return Intents.RESULT_SMS_HANDLED;
        }
        // Decode BD stream and set sms variables.
        sms.parseSms();
        int teleService = sms.getTeleService();
        boolean handled = false;

        if ((SmsEnvelope.TELESERVICE_VMN == teleService) ||
                (SmsEnvelope.TELESERVICE_MWI == teleService)) {
            // handling Voicemail
            updateMessageWaitingIndicator(sms.getNumOfVoicemails());
            handled = true;
        } else if (((SmsEnvelope.TELESERVICE_WMT == teleService) ||
                (SmsEnvelope.TELESERVICE_WEMT == teleService)) &&
                sms.isStatusReportMessage()) {
            handleCdmaStatusReport(sms);
            handled = true;
        } else if ((sms.getUserData() == null)) {
            if (false) {
                Log.d(TAG, "Received SMS without user data");
            }
            handled = true;
        }

        if (handled) {
            return Intents.RESULT_SMS_HANDLED;
        }

        if (!mStorageMonitor.isStorageAvailable() &&
                sms.getMessageClass() != MessageClass.CLASS_0) {
            // It's a storable message and there's no storage available.  Bail.
            // (See C.S0015-B v2.0 for a description of "Immediate Display"
            // messages, which we represent as CLASS_0.)
            return Intents.RESULT_SMS_OUT_OF_MEMORY;
        }

        mTeleserviceId = teleService;
        if (SmsEnvelope.TELESERVICE_WAP == teleService) {
            return processCdmaWapPdu(sms.getUserData(), sms.messageRef,
                    sms.getOriginatingAddress());
        }

        if (SmsEnvelope.TELESERVICE_CT_WAP == teleService) {
            return processCdmaWapPdu(sms.getUserData(), sms.messageRef,
                    sms.getOriginatingAddress());
        }

        // Reject (NAK) any messages with teleservice ids that have
        // not yet been handled and also do not correspond to the two
        // kinds that are processed below.
        if ((SmsEnvelope.TELESERVICE_WMT != teleService) &&
                (SmsEnvelope.TELESERVICE_WEMT != teleService) &&
                (SmsEnvelope.MESSAGE_TYPE_BROADCAST != sms.getMessageType())) {
            return Intents.RESULT_SMS_UNSUPPORTED;
        }

        if (sms.getMessageType() == SmsEnvelope.MESSAGE_TYPE_BROADCAST
                && sms.getServiceCategory() >= SmsEnvelope.EMERGENCY_MESSAGE_ID_START
                && sms.getServiceCategory() <= SmsEnvelope.EMERGENCY_MESSAGE_ID_END) {
            // This is cmas message
            byte[][] pdus = new byte[1][];
            pdus[0] = sms.getPdu();
            dispatchBroadcastPdus(pdus, true);
            return Activity.RESULT_OK;
        }
        return dispatchNormalMessage(smsb);
    }

    protected void dispatchBroadcastPdus(byte[][] pdus, boolean isEmergencyMessage) {
        if (isEmergencyMessage) {
            Intent broadcastIntent = new Intent(Intents.EMERGENCY_CDMA_MESSAGE_RECEIVED_ACTION);
            broadcastIntent.putExtra("pdus", pdus);
            Log.d(TAG, "Dispatching " + pdus.length + " emergency SMS CB pdus");
            dispatch(broadcastIntent, RECEIVE_EMERGENCY_BROADCAST_PERMISSION);
        } else {
            /*TODO: CDMA non emergency messages*/
            Log.d(TAG,"CDMA non emergency messages: not supported");
        }
    }

    /**
     * Processes inbound messages that are in the WAP-WDP PDU format. See
     * wap-259-wdp-20010614-a section 6.5 for details on the WAP-WDP PDU format.
     * WDP segments are gathered until a datagram completes and gets dispatched.
     *
     * @param pdu The WAP-WDP PDU segment
     * @return a result code from {@link Telephony.Sms.Intents}, or
     *         {@link Activity#RESULT_OK} if the message has been broadcast
     *         to applications
     */
    protected int processCdmaWapPdu(byte[] pdu, int referenceNumber, String address) {
        int segment;
        int totalSegments;
        int index = 0;
        int msgType;

        int sourcePort = 0;
        int destinationPort = 0;

        byte subParamID = 0;
        int subParamLen = 0;
        int msgID = 0;
        int msgTemp = 0;
        int msgHeadID = 0;
        int numField = 0;

        byte[] push_pdu = null;
        Log.d(TAG,"processCdmaWapPdu,pdu="+ IccUtils.bytesToHexString(pdu));

        if (SmsEnvelope.TELESERVICE_CT_WAP == mTeleserviceId) {
            subParamID = pdu[index++];
            if (subParamID != 0) {
                Log.w(TAG, "WAP SMS SUBPARAMETER_ID failed!.");
                return Intents.RESULT_SMS_HANDLED;
            }
            subParamLen = pdu[index++];
            if (subParamLen != 3) {
                Log.w(TAG, "WAP SUBPARAM_LEN failed!.");
                return Intents.RESULT_SMS_HANDLED;
            }
            // See CDMA_IS-637, WAP-203-WSP and WAP-259-WDP for details of CDMA
            // push message format.
            msgType = pdu[index] & 0xF0;
            msgType >>= 4;
            msgID = pdu[index++] & 0x0F;
            msgID = (msgID << 12) & 0xF000;
            msgTemp = pdu[index++];
            msgTemp = (msgTemp << 4) & 0x0FF0;
            msgID |= msgTemp;

            msgTemp = pdu[index] & 0xF0;
            msgID |= (msgTemp >> 4) & 0xF;
            msgHeadID = pdu[index++] & 0x04;

            subParamID = pdu[index++];
            if (subParamID != 1) {
                Log.w(TAG, "WAP SMS SUBPARAMETER_ID failed!.");
                return Intents.RESULT_SMS_HANDLED;
            }
            subParamLen = (int) (pdu[index++] & 0xff);
            if (0 != ((pdu[index] & 0xF8) >> 3)) {
                Log.w(TAG, "WAP SMS MESSAGE_ENCODING failed!.");
                return Intents.RESULT_SMS_HANDLED;
            }
            push_pdu = new byte[subParamLen];
            for (int i = 0; i < subParamLen - 1; i++) {
                push_pdu[i] = (byte) ((((pdu[index] & 0x07) << 5) & 0xe0) | (((pdu[index + 1] & 0xF8) >> 3) & 0x1F));
                index++;
            }

            index = 0;
            numField = (int) (push_pdu[index++] & 0xFF);
        } else {
            push_pdu = new byte[pdu.length];
            System.arraycopy(pdu, 0, push_pdu, 0, pdu.length);
            index = 0;
            msgID = referenceNumber;
        }


        msgType = push_pdu[index++];

        if (msgType != 0){
            Log.w(TAG, "Received a WAP SMS which is not WDP. Discard.");
            return Intents.RESULT_SMS_HANDLED;
        }
        totalSegments = push_pdu[index++]; // >=1
        segment = push_pdu[index++]; // >=0

        // Only the first segment contains sourcePort and destination Port
        if (segment == 0) {
            //process WDP segment
            sourcePort = (0xFF & push_pdu[index++]) << 8;
            sourcePort |= 0xFF & push_pdu[index++];
            destinationPort = (0xFF & push_pdu[index++]) << 8;
            destinationPort |= 0xFF & push_pdu[index++];
            // Some carriers incorrectly send duplicate port fields in omadm wap pushes.
            // If configured, check for that here
            if (mCheckForDuplicatePortsInOmadmWapPush) {
                if (checkDuplicatePortOmadmWappush(push_pdu,index)) {
                    index = index + 4; // skip duplicate port fields
                }
            }
        }
        // Lookup all other related parts
        StringBuilder where = new StringBuilder("reference_number =");
        where.append(msgID);
        where.append(" AND address = ?");
        String[] whereArgs = new String[] {address};

        Log.i(TAG, "Received WAP PDU. Type = " + msgType + ", originator = " + address
                + ", src-port = " + sourcePort + ", dst-port = " + destinationPort
                + ", ID = " + msgID + ", segment# = " + segment + "/" + totalSegments);

        Log.d(TAG,"push_pdu= "+ IccUtils.bytesToHexString(push_pdu) + " Index: " + index);
        byte[][] pdus = null;
        Cursor cursor = null;
        try {
            cursor = mResolver.query(mRawUri, RAW_PROJECTION, where.toString(), whereArgs, null);
            int cursorCount = cursor.getCount();
            Log.i(TAG, "cursorCount " + cursorCount + "totalSegments = " + totalSegments);
            if (cursorCount != totalSegments - 1) {
                // We don't have all the parts yet, store this one away
                ContentValues values = new ContentValues();
                values.put("date", (long) 0);
                if (SmsEnvelope.TELESERVICE_CT_WAP == mTeleserviceId) {
                    values.put("pdu", HexDump.toHexString(push_pdu, index, push_pdu.length - index - 1));
                } else {
                    values.put("pdu", HexDump.toHexString(push_pdu, index, push_pdu.length - index));
                }
                values.put("address", address);
                values.put("reference_number", msgID);
                values.put("count", totalSegments);
                values.put("sequence", segment);
                values.put("destination_port", destinationPort);

                mResolver.insert(mRawUri, values);

                return Intents.RESULT_SMS_HANDLED;
            }

            // All the parts are in place, deal with them
            int pduColumn = cursor.getColumnIndex("pdu");
            int sequenceColumn = cursor.getColumnIndex("sequence");

            pdus = new byte[totalSegments][];
            for (int i = 0; i < cursorCount; i++) {
                cursor.moveToNext();
                int cursorSequence = (int)cursor.getLong(sequenceColumn);
                // Read the destination port from the first segment
                if (cursorSequence == 0) {
                    int destinationPortColumn = cursor.getColumnIndex("destination_port");
                    destinationPort = (int)cursor.getLong(destinationPortColumn);
                }
                pdus[cursorSequence] = HexDump.hexStringToByteArray(
                        cursor.getString(pduColumn));
            }
            // The last part will be added later

            // Remove the parts from the database
            mResolver.delete(mRawUri, where.toString(), whereArgs);
        } catch (SQLException e) {
            Log.e(TAG, "Can't access multipart SMS database", e);
            return Intents.RESULT_SMS_GENERIC_ERROR;
        } finally {
            if (cursor != null) cursor.close();
        }

        // Build up the data stream
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int i = 0; i < totalSegments; i++) {
            // reassemble the (WSP-)pdu
            if (i == segment) {
                // This one isn't in the DB, so add it
                output.write(push_pdu, index, push_pdu.length - index);
            } else {
                output.write(pdus[i], 0, pdus[i].length);
            }
        }
        Log.i(TAG, "Whole MMS push to handle");
        byte[] datagram = output.toByteArray();
        // Dispatch the PDU to applications
        switch (destinationPort) {
        case SmsHeader.PORT_WAP_PUSH:
            // Handle the PUSH
            Log.i(TAG, "Dispatch MMS push");
            return mWapPush.dispatchWapPdu(datagram);

        default:{
            pdus = new byte[1][];
            pdus[0] = datagram;
            // The messages were sent to any other WAP port
            dispatchPortAddressedPdus(pdus, destinationPort);
            return Activity.RESULT_OK;
        }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void sendData(String destAddr, String scAddr, int destPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        SmsMessage.SubmitPdu pdu = SmsMessage.getSubmitPdu(
                scAddr, destAddr, destPort, data, (deliveryIntent != null));

        HashMap map =  SmsTrackerMapFactory(destAddr, scAddr, destPort, data, pdu);
        SmsTracker tracker = SmsTrackerFactory(map, sentIntent, deliveryIntent,
                getFormat());
        sendSubmitPdu(tracker);
    }

    /** {@inheritDoc} */
    @Override
    protected void sendText(String destAddr, String scAddr, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent) {
        SmsMessage.SubmitPdu pdu = SmsMessage.getSubmitPdu(
                scAddr, destAddr, text, (deliveryIntent != null), null);

        HashMap map =  SmsTrackerMapFactory(destAddr, scAddr, text, pdu);
        SmsTracker tracker = SmsTrackerFactory(map, sentIntent,
                deliveryIntent, getFormat());
        sendSubmitPdu(tracker);
    }

    /** {@inheritDoc} */
    @Override
    protected TextEncodingDetails calculateLength(CharSequence messageBody,
            boolean use7bitOnly) {
        return SmsMessage.calculateLength(messageBody, use7bitOnly);
    }

    /** {@inheritDoc} */
    @Override
    protected void sendNewSubmitPdu(String destinationAddress, String scAddress,
            String message, SmsHeader smsHeader, int encoding,
            PendingIntent sentIntent, PendingIntent deliveryIntent, boolean lastPart) {
        UserData uData = new UserData();
        uData.payloadStr = message;
        uData.userDataHeader = smsHeader;
        if (encoding == android.telephony.SmsMessage.ENCODING_7BIT) {
            uData.msgEncoding = UserData.ENCODING_7BIT_ASCII;
        } else { // assume UTF-16
            uData.msgEncoding = UserData.ENCODING_UNICODE_16;
        }
        uData.msgEncodingSet = true;

        /* By setting the statusReportRequested bit only for the
         * last message fragment, this will result in only one
         * callback to the sender when that last fragment delivery
         * has been acknowledged. */
        SmsMessage.SubmitPdu submitPdu = SmsMessage.getSubmitPdu(destinationAddress,
                uData, (deliveryIntent != null) && lastPart);

        HashMap map =  SmsTrackerMapFactory(destinationAddress, scAddress,
                message, submitPdu);
        SmsTracker tracker = SmsTrackerFactory(map, sentIntent,
                deliveryIntent, getFormat());
        sendSubmitPdu(tracker);
    }

    protected void sendSubmitPdu(SmsTracker tracker) {
        if (SystemProperties.getBoolean(TelephonyProperties.PROPERTY_INECM_MODE, false)) {
            if (tracker.mSentIntent != null) {
                try {
                    tracker.mSentIntent.send(SmsManager.RESULT_ERROR_NO_SERVICE);
                } catch (CanceledException ex) {}
            }
            if (false) {
                Log.d(TAG, "Block SMS in Emergency Callback mode");
            }
            return;
        }
        sendRawPdu(tracker);
    }

    /** {@inheritDoc} */
    @Override
    protected void sendSms(SmsTracker tracker) {
        HashMap<String, Object> map = tracker.mData;

        // byte smsc[] = (byte[]) map.get("smsc");  // unused for CDMA
        byte pdu[] = (byte[]) map.get("pdu");

        Message reply = obtainMessage(EVENT_SEND_SMS_COMPLETE, tracker);

        Log.d(TAG, "sendSms: "
                +" isIms()="+isIms()
                +" mRetryCount="+tracker.mRetryCount
                +" mImsRetry="+tracker.mImsRetry
                +" mMessageRef="+tracker.mMessageRef
                +" SS=" +mPhone.getServiceState().getState());

        if ( 0 == tracker.mImsRetry && !isIms()) {
            mCm.sendCdmaSms(pdu, reply);
        } else {
            mCm.sendImsCdmaSms(pdu, tracker.mImsRetry, tracker.mMessageRef, reply);
            // increment it here, so in case of SMS_FAIL_RETRY over IMS
            // next retry will be sent using IMS request again.
            tracker.mImsRetry++;
        }
    }

    protected void sendRetrySms(SmsTracker tracker) {
        //re-routing to ImsSMSDispatcher
        mImsSMSDispatcher.sendRetrySms(tracker);
    }

    /** {@inheritDoc} */
    @Override
    protected void acknowledgeLastIncomingSms(boolean success, int result, Message response) {
        String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        if (inEcm.equals("true")) {
            return;
        }

        int causeCode = resultToCause(result);
        mCm.acknowledgeLastIncomingCdmaSms(success, causeCode, response);

        if (causeCode == 0) {
            mLastAcknowledgedSmsFingerprint = mLastDispatchedSmsFingerprint;
        }
        mLastDispatchedSmsFingerprint = null;
    }

    private static int resultToCause(int rc) {
        switch (rc) {
        case Activity.RESULT_OK:
        case Intents.RESULT_SMS_HANDLED:
            // Cause code is ignored on success.
            return 0;
        case Intents.RESULT_SMS_OUT_OF_MEMORY:
            return CommandsInterface.CDMA_SMS_FAIL_CAUSE_RESOURCE_SHORTAGE;
        case Intents.RESULT_SMS_UNSUPPORTED:
            return CommandsInterface.CDMA_SMS_FAIL_CAUSE_INVALID_TELESERVICE_ID;
        case Intents.RESULT_SMS_GENERIC_ERROR:
        default:
            return CommandsInterface.CDMA_SMS_FAIL_CAUSE_ENCODING_PROBLEM;
        }
    }

    /**
     * Optional check to see if the received WapPush is an OMADM notification with erroneous
     * extra port fields.
     * - Some carriers make this mistake.
     * ex: MSGTYPE-TotalSegments-CurrentSegment
     *       -SourcePortDestPort-SourcePortDestPort-OMADM PDU
     * @param origPdu The WAP-WDP PDU segment
     * @param index Current Index while parsing the PDU.
     * @return True if OrigPdu is OmaDM Push Message which has duplicate ports.
     *         False if OrigPdu is NOT OmaDM Push Message which has duplicate ports.
     */
    private static boolean checkDuplicatePortOmadmWappush(byte[] origPdu, int index) {
        index += 4;
        byte[] omaPdu = new byte[origPdu.length - index];
        System.arraycopy(origPdu, index, omaPdu, 0, omaPdu.length);

        WspTypeDecoder pduDecoder = new WspTypeDecoder(omaPdu);
        int wspIndex = 2;

        // Process header length field
        if (pduDecoder.decodeUintvarInteger(wspIndex) == false) {
            return false;
        }

        wspIndex += pduDecoder.getDecodedDataLength(); // advance to next field

        // Process content type field
        if (pduDecoder.decodeContentType(wspIndex) == false) {
            return false;
        }

        String mimeType = pduDecoder.getValueString();
        if (mimeType != null && mimeType.equals(WspTypeDecoder.CONTENT_TYPE_B_PUSH_SYNCML_NOTI)) {
            return true;
        }
        return false;
    }

    protected UiccCardApplication getUiccCardApplication() {
        SubscriptionManager subMgr = SubscriptionManager.getInstance();
        if (subMgr == null) {
            return mUiccManager.getUiccCardApplication(AppFamily.APP_FAM_3GPP2);
        } else {
            Subscription subscriptionData = subMgr.getCurrentSubscription(mPhone.getSubscription());
            if (subscriptionData != null) {
                return  mUiccManager.getUiccCardApplication(subscriptionData.slotId,
                        AppFamily.APP_FAM_3GPP2);
            }
        }
        return null;
    }

    protected void updateIccAvailability() {
        if (mUiccManager == null ) {
            return;
        }

        UiccCardApplication newUiccApplication = getUiccCardApplication();
        if (newUiccApplication == null) return;

        if (mUiccApplication != newUiccApplication) {
            if (mUiccApplication != null) {
                Log.d(TAG, "Removing stale icc objects.");
                mIccRecords = null;
                mUiccApplication = null;
            }
            if (newUiccApplication != null) {
                Log.d(TAG, "New Uicc application found");
                mUiccApplication = newUiccApplication;
                mIccRecords = mUiccApplication.getIccRecords();
            }
        }
    }

    /*package*/ void
    updateMessageWaitingIndicator(boolean mwi) {
        updateMessageWaitingIndicator(mwi ? -1 : 0);
    }

    /*
     * This function is overloaded to send number of voicemails instead of
     * sending true/false
     */
    /* package */void updateMessageWaitingIndicator(int mwi) {
        // range check
        if (mwi < 0) {
            mwi = -1;
        } else if (mwi > 99) {
            // C.S0015-B v2, 4.5.12
            // range: 0-99
            mwi = 99;
        }
        // update voice mail count in phone
        ((PhoneBase)mPhone).setVoiceMessageCount(mwi);
        // store voice mail count in preferences
        storeVoiceMailCount();
    }

}
