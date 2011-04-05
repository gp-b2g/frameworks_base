/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Sms.Intents;
import android.telephony.CellLocation;
import android.telephony.ServiceState;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsCbMessage;
import android.telephony.SmsManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface.RadioTechnologyFamily;
import com.android.internal.telephony.SMSDispatcher.SmsTracker;
import com.android.internal.telephony.SmsMessageBase.TextEncodingDetails;
import com.android.internal.telephony.UiccManager.AppFamily;
import com.android.internal.telephony.gsm.SmsCbHeader;
import com.android.internal.telephony.gsm.UsimDataDownloadHandler;
import com.android.internal.telephony.gsm.SmsMessage;
import com.android.internal.telephony.Subscription;
import com.android.internal.telephony.SubscriptionManager;
import com.android.internal.telephony.gsm.UsimServiceTable;

import java.util.HashMap;
import java.util.Iterator;

import static android.telephony.SmsMessage.MessageClass;

final class GsmSMSDispatcher extends SMSDispatcher {
    private static final String TAG = "GSM";
    private ImsSMSDispatcher mImsSMSDispatcher;

    /** Status report received */
    private static final int EVENT_NEW_SMS_STATUS_REPORT = 100;

    /** New broadcast SMS */
    private static final int EVENT_NEW_BROADCAST_SMS = 101;

    /** Result of writing SM to UICC (when SMS-PP service is not available). */
    private static final int EVENT_WRITE_SMS_COMPLETE = 102;

    /** Handler for SMS-PP data download messages to UICC. */
    private final UsimDataDownloadHandler mDataDownloadHandler;

    public GsmSMSDispatcher(PhoneBase phone, SmsStorageMonitor storageMonitor,
            SmsUsageMonitor usageMonitor, ImsSMSDispatcher imsSMSDispatcher) {
        super(phone, storageMonitor, usageMonitor);
        mImsSMSDispatcher = imsSMSDispatcher;
        mDataDownloadHandler = new UsimDataDownloadHandler(mCm);
        mCm.setOnNewGsmSms(this, EVENT_NEW_SMS, null);
        mCm.setOnSmsOnSim(this, EVENT_SMS_ON_ICC, null);
        mCm.setOnSmsStatus(this, EVENT_NEW_SMS_STATUS_REPORT, null);
        mCm.setOnNewGsmBroadcastSms(this, EVENT_NEW_BROADCAST_SMS, null);
        Log.d(TAG, "GsmSMSDispatcher created");
    }

    @Override
    public void dispose() {
        mCm.unSetOnNewGsmSms(this);
        mCm.unSetOnSmsOnSim(this);
        mCm.unSetOnSmsStatus(this);
        mCm.unSetOnNewGsmBroadcastSms(this);
        super.dispose();
    }

    @Override
    protected String getFormat() {
        return android.telephony.SmsMessage.FORMAT_3GPP;
    }

    /**
     * Handles 3GPP format-specific events coming from the phone stack.
     * Other events are handled by {@link SMSDispatcher#handleMessage}.
     *
     * @param msg the message to handle
     */
    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
        case EVENT_NEW_SMS_STATUS_REPORT:
            handleStatusReport((AsyncResult) msg.obj);
            break;

        case EVENT_NEW_BROADCAST_SMS:
            handleBroadcastSms((AsyncResult)msg.obj);
            break;

        case EVENT_WRITE_SMS_COMPLETE:
            AsyncResult ar = (AsyncResult) msg.obj;
            if (ar.exception == null) {
                Log.d(TAG, "Successfully wrote SMS-PP message to UICC");
                mCm.acknowledgeLastIncomingGsmSms(true, 0, null);
            } else {
                Log.d(TAG, "Failed to write SMS-PP message to UICC", ar.exception);
                mCm.acknowledgeLastIncomingGsmSms(false,
                        CommandsInterface.GSM_SMS_FAIL_CAUSE_UNSPECIFIED_ERROR, null);
            }
            break;

        default:
            super.handleMessage(msg);
        }
    }

    /**
     * Called when a status report is received.  This should correspond to
     * a previously successful SEND.
     *
     * @param ar AsyncResult passed into the message handler.  ar.result should
     *           be a String representing the status report PDU, as ASCII hex.
     */
    private void handleStatusReport(AsyncResult ar) {
        String pduString = (String) ar.result;
        SmsMessage sms = SmsMessage.newFromCDS(pduString);

        if (sms != null) {
            int tpStatus = sms.getStatus();
            int messageRef = sms.messageRef;
            for (int i = 0, count = deliveryPendingList.size(); i < count; i++) {
                SmsTracker tracker = deliveryPendingList.get(i);
                if (tracker.mMessageRef == messageRef) {
                    // Found it.  Remove from list and broadcast.
                    if(tpStatus >= Sms.STATUS_FAILED || tpStatus < Sms.STATUS_PENDING ) {
                       deliveryPendingList.remove(i);
                    }
                    PendingIntent intent = tracker.mDeliveryIntent;
                    Intent fillIn = new Intent();
                    fillIn.putExtra("pdu", IccUtils.hexStringToBytes(pduString));
                    fillIn.putExtra("format", android.telephony.SmsMessage.FORMAT_3GPP);
                    try {
                        intent.send(mContext, Activity.RESULT_OK, fillIn);
                    } catch (CanceledException ex) {}

                    // Only expect to see one tracker matching this messageref
                    break;
                }
            }
        }
        acknowledgeLastIncomingSms(true, Intents.RESULT_SMS_HANDLED, null);
    }

    /** {@inheritDoc} */
    @Override
    protected int dispatchMessage(SmsMessageBase smsb) {

        // If sms is null, means there was a parsing error.
        if (smsb == null) {
            Log.e(TAG, "dispatchMessage: message is null");
            return Intents.RESULT_SMS_GENERIC_ERROR;
        }

        SmsMessage sms = (SmsMessage) smsb;

        if (sms.isTypeZero()) {
            // As per 3GPP TS 23.040 9.2.3.9, Type Zero messages should not be
            // Displayed/Stored/Notified. They should only be acknowledged.
            Log.d(TAG, "Received short message type 0, Don't display or store it. Send Ack");
            return Intents.RESULT_SMS_HANDLED;
        }

        // Send SMS-PP data download messages to UICC. See 3GPP TS 31.111 section 7.1.1.
        if (sms.isUsimDataDownload()) {
            UsimServiceTable ust = mPhone.getUsimServiceTable();
            // If we receive an SMS-PP message before the UsimServiceTable has been loaded,
            // assume that the data download service is not present. This is very unlikely to
            // happen because the IMS connection will not be established until after the ISIM
            // records have been loaded, after the USIM service table has been loaded.
            if (ust != null && ust.isAvailable(
                    UsimServiceTable.UsimService.DATA_DL_VIA_SMS_PP)) {
                Log.d(TAG, "Received SMS-PP data download, sending to UICC.");
                return mDataDownloadHandler.startDataDownload(sms);
            } else {
                Log.d(TAG, "DATA_DL_VIA_SMS_PP service not available, storing message to UICC.");
                String smsc = IccUtils.bytesToHexString(
                        PhoneNumberUtils.networkPortionToCalledPartyBCDWithLength(
                                sms.getServiceCenterAddress()));
                mCm.writeSmsToSim(SmsManager.STATUS_ON_ICC_UNREAD, smsc,
                        IccUtils.bytesToHexString(sms.getPdu()),
                        obtainMessage(EVENT_WRITE_SMS_COMPLETE));
                return Activity.RESULT_OK;  // acknowledge after response from write to USIM
            }
        }

        if (mSmsReceiveDisabled) {
            // Device doesn't support SMS service,
            Log.d(TAG, "Received short message on device which doesn't support "
                    + "SMS service. Ignored.");
            return Intents.RESULT_SMS_HANDLED;
        }

        // Special case the message waiting indicator messages
        boolean handled = false;
        if (sms.isMWISetMessage()) {
            updateMessageWaitingIndicator(sms.getNumOfVoicemails());
            handled = sms.isMwiDontStore();
            if (false) {
                Log.d(TAG, "Received voice mail indicator set SMS shouldStore=" + !handled);
            }
        } else if (sms.isMWIClearMessage()) {
            updateMessageWaitingIndicator(0);
            handled = sms.isMwiDontStore();
            if (false) {
                Log.d(TAG, "Received voice mail indicator clear SMS shouldStore=" + !handled);
            }
        }

        if (handled) {
            return Intents.RESULT_SMS_HANDLED;
        }

        if (!mStorageMonitor.isStorageAvailable() &&
                sms.getMessageClass() != MessageClass.CLASS_0) {
            // It's a storable message and there's no storage available.  Bail.
            // (See TS 23.038 for a description of class 0 messages.)
            return Intents.RESULT_SMS_OUT_OF_MEMORY;
        }

        return dispatchNormalMessage(smsb);
    }

    /** {@inheritDoc} */
    @Override
    protected void sendData(String destAddr, String scAddr, int destPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        SmsMessage.SubmitPdu pdu = SmsMessage.getSubmitPdu(
                scAddr, destAddr, destPort, data, (deliveryIntent != null));
        if (pdu != null) {
        HashMap map =  SmsTrackerMapFactory(destAddr, scAddr,destPort,
                 data, pdu);
        SmsTracker tracker = SmsTrackerFactory(map, sentIntent,
                deliveryIntent, getFormat());
        sendRawPdu(tracker);

        } else {
            Log.e(TAG, "GsmSMSDispatcher.sendData(): getSubmitPdu() returned null");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void sendText(String destAddr, String scAddr, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent) {
        SmsMessage.SubmitPdu pdu = SmsMessage.getSubmitPdu(
                scAddr, destAddr, text, (deliveryIntent != null));
        if (pdu != null) {
         HashMap map =  SmsTrackerMapFactory(destAddr, scAddr,
                text, pdu);
        SmsTracker tracker = SmsTrackerFactory(map, sentIntent,
                deliveryIntent, getFormat());
        sendRawPdu(tracker);

        } else {
            Log.e(TAG, "GsmSMSDispatcher.sendText(): getSubmitPdu() returned null");
        }
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
        SmsMessage.SubmitPdu pdu = SmsMessage.getSubmitPdu(scAddress, destinationAddress,
                message, deliveryIntent != null, SmsHeader.toByteArray(smsHeader),
                encoding, smsHeader.languageTable, smsHeader.languageShiftTable);
        if (pdu != null) {
        HashMap map =  SmsTrackerMapFactory(destinationAddress, scAddress,
                message, pdu);
        SmsTracker tracker = SmsTrackerFactory(map, sentIntent,
                deliveryIntent, getFormat());
        sendRawPdu(tracker);

        } else {
            Log.e(TAG, "GsmSMSDispatcher.sendNewSubmitPdu(): getSubmitPdu() returned null");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void sendSms(SmsTracker tracker) {
        HashMap<String, Object> map = tracker.mData;

        byte smsc[] = (byte[]) map.get("smsc");
        byte pdu[] = (byte[]) map.get("pdu");

        Message reply = obtainMessage(EVENT_SEND_SMS_COMPLETE, tracker);

        Log.d(TAG, "sendSms: "
                +" isIms()="+isIms()
                +" mRetryCount="+tracker.mRetryCount
                +" mImsRetry="+tracker.mImsRetry
                +" mMessageRef="+tracker.mMessageRef
                +" SS=" +mPhone.getServiceState().getState());

        if (0 == tracker.mImsRetry && !isIms()) {
            mCm.sendSMS(IccUtils.bytesToHexString(smsc),
                    IccUtils.bytesToHexString(pdu), reply);
        } else {
            mCm.sendImsGsmSms(IccUtils.bytesToHexString(smsc),
                    IccUtils.bytesToHexString(pdu), tracker.mImsRetry,
                    tracker.mMessageRef, reply);
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
        mCm.acknowledgeLastIncomingGsmSms(success, resultToCause(result), response);
    }

    private static int resultToCause(int rc) {
        switch (rc) {
            case Activity.RESULT_OK:
            case Intents.RESULT_SMS_HANDLED:
                // Cause code is ignored on success.
                return 0;
            case Intents.RESULT_SMS_OUT_OF_MEMORY:
                return CommandsInterface.GSM_SMS_FAIL_CAUSE_MEMORY_CAPACITY_EXCEEDED;
            case Intents.RESULT_SMS_GENERIC_ERROR:
            default:
                return CommandsInterface.GSM_SMS_FAIL_CAUSE_UNSPECIFIED_ERROR;
        }
    }

    protected UiccCardApplication getUiccCardApplication() {
        SubscriptionManager subMgr = SubscriptionManager.getInstance();
        if (subMgr == null) {
            return mUiccManager.getUiccCardApplication(AppFamily.APP_FAM_3GPP);
        } else {
            Subscription subscriptionData = subMgr.getCurrentSubscription(mPhone.getSubscription());
            if (subscriptionData != null) {
                return  mUiccManager.getUiccCardApplication(subscriptionData.slotId,
                        AppFamily.APP_FAM_3GPP);
            }
        }
        return null;
    }

    protected void updateIccAvailability() {
        if (mUiccManager == null ) {
            return;
        }

        UiccCardApplication newUiccApplication = getUiccCardApplication();
        if (newUiccApplication == null) {
            return;
        }

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

    /**
     * Holds all info about a message page needed to assemble a complete
     * concatenated message
     */
    private static final class SmsCbConcatInfo {
        private final SmsCbHeader mHeader;

        private final String mPlmn;

        private final int mLac;

        private final int mCid;

        public SmsCbConcatInfo(SmsCbHeader header, String plmn, int lac, int cid) {
            mHeader = header;
            mPlmn = plmn;
            mLac = lac;
            mCid = cid;
        }

        @Override
        public int hashCode() {
            return mHeader.messageIdentifier * 31 + mHeader.updateNumber;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof SmsCbConcatInfo) {
                SmsCbConcatInfo other = (SmsCbConcatInfo)obj;

                // Two pages match if all header attributes (except the page
                // index) are identical, and both pages belong to the same
                // location (which is also determined by the scope parameter)
                if (mHeader.geographicalScope == other.mHeader.geographicalScope
                        && mHeader.messageCode == other.mHeader.messageCode
                        && mHeader.updateNumber == other.mHeader.updateNumber
                        && mHeader.messageIdentifier == other.mHeader.messageIdentifier
                        && mHeader.dataCodingScheme == other.mHeader.dataCodingScheme
                        && mHeader.nrOfPages == other.mHeader.nrOfPages) {
                    return matchesLocation(other.mPlmn, other.mLac, other.mCid);
                }
            }

            return false;
        }

        /**
         * Checks if this concatenation info matches the given location. The
         * granularity of the match depends on the geographical scope.
         *
         * @param plmn PLMN
         * @param lac Location area code
         * @param cid Cell ID
         * @return true if matching, false otherwise
         */
        public boolean matchesLocation(String plmn, int lac, int cid) {
            switch (mHeader.geographicalScope) {
                case SmsCbMessage.GEOGRAPHICAL_SCOPE_CELL_WIDE:
                case SmsCbMessage.GEOGRAPHICAL_SCOPE_CELL_WIDE_IMMEDIATE:
                    if (mCid != cid) {
                        return false;
                    }
                    // deliberate fall-through
                case SmsCbMessage.GEOGRAPHICAL_SCOPE_LA_WIDE:
                    if (mLac != lac) {
                        return false;
                    }
                    // deliberate fall-through
                case SmsCbMessage.GEOGRAPHICAL_SCOPE_PLMN_WIDE:
                    return mPlmn != null && mPlmn.equals(plmn);
            }

            return false;
        }
    }

    // This map holds incomplete concatenated messages waiting for assembly
    private final HashMap<SmsCbConcatInfo, byte[][]> mSmsCbPageMap =
            new HashMap<SmsCbConcatInfo, byte[][]>();

    /**
     * Handle 3GPP format SMS-CB message.
     * @param ar the AsyncResult containing the received PDUs
     */
    private void handleBroadcastSms(AsyncResult ar) {
        try {
            byte[] receivedPdu = (byte[])ar.result;

            if (false) {
                for (int i = 0; i < receivedPdu.length; i += 8) {
                    StringBuilder sb = new StringBuilder("SMS CB pdu data: ");
                    for (int j = i; j < i + 8 && j < receivedPdu.length; j++) {
                        int b = receivedPdu[j] & 0xff;
                        if (b < 0x10) {
                            sb.append('0');
                        }
                        sb.append(Integer.toHexString(b)).append(' ');
                    }
                    Log.d(TAG, sb.toString());
                }
            }

            SmsCbHeader header = new SmsCbHeader(receivedPdu);
            String plmn = SystemProperties.get(TelephonyProperties.PROPERTY_OPERATOR_NUMERIC);
            int lac = -1;
            int cid = -1;
            CellLocation cl = mPhone.getCellLocation();
            if (cl instanceof GsmCellLocation) {
                GsmCellLocation cellLocation = (GsmCellLocation)cl;
                lac = cellLocation.getLac();
                cid = cellLocation.getCid();
            }

            byte[][] pdus;
            if (header.nrOfPages > 1) {
                // Multi-page message
                SmsCbConcatInfo concatInfo = new SmsCbConcatInfo(header, plmn, lac, cid);

                // Try to find other pages of the same message
                pdus = mSmsCbPageMap.get(concatInfo);

                if (pdus == null) {
                    // This it the first page of this message, make room for all
                    // pages and keep until complete
                    pdus = new byte[header.nrOfPages][];

                    mSmsCbPageMap.put(concatInfo, pdus);
                }

                // Page parameter is one-based
                pdus[header.pageIndex - 1] = receivedPdu;

                for (int i = 0; i < pdus.length; i++) {
                    if (pdus[i] == null) {
                        // Still missing pages, exit
                        return;
                    }
                }

                // Message complete, remove and dispatch
                mSmsCbPageMap.remove(concatInfo);
            } else {
                // Single page message
                pdus = new byte[1][];
                pdus[0] = receivedPdu;
            }

            boolean isEmergencyMessage = SmsCbHeader.isEmergencyMessage(header.messageIdentifier);

            dispatchBroadcastPdus(pdus, isEmergencyMessage);

            // Remove messages that are out of scope to prevent the map from
            // growing indefinitely, containing incomplete messages that were
            // never assembled
            Iterator<SmsCbConcatInfo> iter = mSmsCbPageMap.keySet().iterator();

            while (iter.hasNext()) {
                SmsCbConcatInfo info = iter.next();

                if (!info.matchesLocation(plmn, lac, cid)) {
                    iter.remove();
                }
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Error in decoding SMS CB pdu", e);
        }
    }

    protected void dispatchBroadcastPdus(byte[][] pdus, boolean isEmergencyMessage) {
        if (isEmergencyMessage) {
            Intent broadcastIntent = new Intent(Intents.SMS_EMERGENCY_CB_RECEIVED_ACTION);
            broadcastIntent.putExtra("pdus", pdus);
            broadcastIntent.putExtra(MSimConstants.SUBSCRIPTION_KEY,
                  mPhone.getSubscription());
            Log.d(TAG, "Dispatching " + pdus.length + " emergency SMS CB pdus");
            dispatch(broadcastIntent, RECEIVE_EMERGENCY_BROADCAST_PERMISSION);
        } else {
            Intent broadcastIntent = new Intent(Intents.SMS_CB_RECEIVED_ACTION);
            broadcastIntent.putExtra("pdus", pdus);
            broadcastIntent.putExtra(MSimConstants.SUBSCRIPTION_KEY,
                  mPhone.getSubscription());
            Log.d(TAG, "Dispatching " + pdus.length + " SMS CB pdus");
            dispatch(broadcastIntent, RECEIVE_SMS_PERMISSION);
        }
    }

    /*
     * Called when a Class2 SMS is  received.
     *
     * @param ar AsyncResult passed to this function. "ar.result" should
     *           be representing the INDEX of SMS on SIM.
     */
    protected void handleSmsOnIcc(AsyncResult ar) {
        int[] index = (int[])ar.result;

        if (ar.exception != null || index.length != 1) {
            Log.e(TAG, " Error on SMS_ON_SIM with exp "
                   + ar.exception + " length " + index.length);
        } else {
            Log.d(TAG, "READ EF_SMS RECORD index=" + index[0]);
            mUiccApplication.getIccFileHandler().loadEFLinearFixed(IccConstants.EF_SMS,index[0],
                   obtainMessage(EVENT_GET_ICC_SMS_DONE));
        }
    }


    /**
     * Called when a SMS on SIM is retrieved.
     *
     * @param ar AsyncResult passed to this function.
     */
    protected void handleGetIccSmsDone(AsyncResult ar) {
        byte[] ba;

        if (ar.exception == null) {
            ba = (byte[])ar.result;
            if (ba[0] != 0)
                Log.d(TAG, "status : " + ba[0]);

            // 3GPP TS 51.011 v5.0.0 (20011-12)  10.5.3
            // 3 == "received by MS from network; message to be read"
            if (ba[0] == 3) {
                int n = ba.length;

                // Note: Data may include trailing FF's.  That's OK; message
                // should still parse correctly.
                byte[] pdu = new byte[n - 1];
                System.arraycopy(ba, 1, pdu, 0, n - 1);
                SmsMessage message = SmsMessage.createFromPdu(pdu);
                dispatchMessage((SmsMessageBase) message);
            }
        } else {
            Log.e(TAG, "Error on GET_SMS with exp "
                    + ar.exception);
        }
    }

    /* package */void updateMessageWaitingIndicator(int mwi) {
        Message onComplete;
        // range check
        if (mwi < 0) {
            mwi = -1;
        } else if (mwi > 0xff) {
            // TS 23.040 9.2.3.24.2
            // "The value 255 shall be taken to mean 255 or greater"
            mwi = 0xff;
        }
        // update voice mail count in GsmPhone
        ((PhoneBase)mPhone).setVoiceMessageCount(mwi);
        // store voice mail count in SIM/preferences
        if (mIccRecords != null) {
            onComplete = obtainMessage(EVENT_UPDATE_ICC_MWI);
            mIccRecords.setVoiceMessageWaiting(1, mwi, onComplete);
        } else {
            Log.d(TAG, "SIM Records not found, MWI not updated");
        }
    }
}
