/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2010-2011, Code Aurora Forum. All rights reserved.
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

import static android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE;

import java.util.ArrayList;
import java.util.HashMap;

import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.os.AsyncResult;
import android.os.Message;
import android.provider.Telephony.Sms.Intents;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface.RadioTechnologyFamily;
import com.android.internal.telephony.SmsMessageBase.TextEncodingDetails;

final class ImsSMSDispatcher extends SMSDispatcher {
    private static final String TAG = "RIL_IMS";

    private SMSDispatcher mCdmaDispatcher;
    private SMSDispatcher mGsmDispatcher;

    public ImsSMSDispatcher(PhoneBase phone, SmsStorageMonitor storageMonitor,
            SmsUsageMonitor usageMonitor) {
        super(phone, storageMonitor, usageMonitor);
        mCdmaDispatcher = new CdmaSMSDispatcher(phone, storageMonitor, usageMonitor, this);
        mGsmDispatcher = new GsmSMSDispatcher(phone, storageMonitor, usageMonitor, this);

        mCm.registerForOn(this, EVENT_RADIO_ON, null);
        mCm.registerForImsNetworkStateChanged(this, EVENT_IMS_STATE_CHANGED, null);
    }

    /* Updates the voice phoneobject when there is a change in a phone object*/
    @Override
    public void updatePhoneObject(Phone phone) {
        Log.d(TAG, "In IMS updatePhoneObject ");
        super.updatePhoneObject(phone);
        mCdmaDispatcher.updatePhoneObject(phone);
        mGsmDispatcher.updatePhoneObject(phone);
    }

    public void dispose() {
        mCm.unSetOnIccSmsFull(this);
        mCm.unregisterForOn(this);
        mCm.unregisterForImsNetworkStateChanged(this);
        super.dispose();
    }

    /**
     * Handles events coming from the phone stack. Overridden from handler.
     *
     * @param msg the message to handle
     */
    @Override
    public void handleMessage(Message msg) {
        super.handleMessage(msg);

        AsyncResult ar;

        switch (msg.what) {
        case EVENT_RADIO_ON:
        case EVENT_IMS_STATE_CHANGED: // received unsol
            mCm.getImsRegistrationState(this.obtainMessage(EVENT_IMS_STATE_DONE));
            break;

        case EVENT_IMS_STATE_DONE:
            ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                updateImsInfo(ar);
            } else {
                Log.e(TAG, "IMS State query failed!");
            }
            break;
        }
    }

    private void updateImsInfo(AsyncResult ar) {
        //If responseArray[0] is = 1, then responseArray[1] must follow,
        //with IMS SMS encoding:
        int[] responseArray = (int[])ar.result;
        if (responseArray[0] == 1) {  // IMS is registered
            Log.d(TAG, "IMS is registered!");
            mIms = true;
        } else {
            Log.d(TAG, "IMS is NOT registered!");
            mIms = false;
        }

        RadioTechnologyFamily newImsSmsEncoding =
            RadioTechnologyFamily.getRadioTechFamilyFromInt(responseArray[1]);
        mImsSmsEncoding = newImsSmsEncoding;
        if (mImsSmsEncoding.isUnknown()) {
            Log.d(TAG, "IMS encoding was unknown!");
            // failed to retrieve valid encoding info, set IMS to unregistered
            // so, SMS will use 1x instead.
            mIms = false;
        }
    }

    @Override
    protected void acknowledgeLastIncomingSms(boolean success, int result,
        Message response) {
        // EVENT_NEW_SMS is registered only by Gsm/CdmaSMSDispatcher.
        Log.d(TAG, "acknowledgeLastIncomingSms should never be called from here!");
    }

    @Override
    protected int dispatchMessage(SmsMessageBase sms) {
        // EVENT_NEW_SMS is registered only by Gsm/CdmaSMSDispatcher.
        Log.d(TAG, "dispatchMessage should never be called from here!");
        return Intents.RESULT_SMS_GENERIC_ERROR;
    }

    @Override
    protected void sendData(String destAddr, String scAddr, int destPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        if (isCdmaMo()) {
            mCdmaDispatcher.sendData(destAddr, scAddr, destPort,
                    data, sentIntent, deliveryIntent);
        } else {
            mGsmDispatcher.sendData(destAddr, scAddr, destPort,
                    data, sentIntent, deliveryIntent);
        }
    }

    @Override
    protected void sendMultipartText(String destAddr, String scAddr,
            ArrayList<String> parts, ArrayList<PendingIntent> sentIntents,
            ArrayList<PendingIntent> deliveryIntents) {
        if (isCdmaMo()) {
            mCdmaDispatcher.sendMultipartText(destAddr, scAddr,
                    parts, sentIntents, deliveryIntents);
        } else {
            mGsmDispatcher.sendMultipartText(destAddr, scAddr,
                    parts, sentIntents, deliveryIntents);
        }
    }

    @Override
    protected void sendSms(SmsTracker tracker) {
        //  sendSms is a helper function to other send functions, sendText/Data...
        //  it is not part of ISms.stub
        Log.d(TAG, "sendSms should never be called from here!");
    }

    @Override
    protected void sendText(String destAddr, String scAddr, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent) {
        Log.d(TAG, "sendText");
        if (isCdmaMo()) {
            mCdmaDispatcher.sendText(destAddr, scAddr,
                    text, sentIntent, deliveryIntent);
        } else {
            mGsmDispatcher.sendText(destAddr, scAddr,
                    text, sentIntent, deliveryIntent);
        }
    }

    protected void sendRetrySms(SmsTracker tracker) {
        String oldFormat = tracker.mFormat;

        RadioTechnologyFamily oldEncoding =
                oldFormat.equals(mCdmaDispatcher.getFormat()) ?
                    RadioTechnologyFamily.RADIO_TECH_3GPP2 :
                        RadioTechnologyFamily.RADIO_TECH_3GPP;

        // newEncoding will be based on voice technology
        RadioTechnologyFamily newEncoding =
            (Phone.PHONE_TYPE_CDMA == mPhone.getPhoneType()) ?
                    RadioTechnologyFamily.RADIO_TECH_3GPP2 :
                        RadioTechnologyFamily.RADIO_TECH_3GPP;

        // was previously sent sms encoding match with voice tech?
        if (oldEncoding.isCdma() && newEncoding.isCdma()) {
            Log.d(TAG, "old encoding matched new encoding (cdma)");
            mCdmaDispatcher.sendSms(tracker);
            return;
        }
        if (oldEncoding.isGsm() && newEncoding.isGsm()) {
            Log.d(TAG, "old encoding matched new encoding (gsm)");
            mGsmDispatcher.sendSms(tracker);
            return;
        }

        // encoding didn't match, need to re-encode.
        HashMap map = tracker.mData;

        // to re-encode, fields needed are:  scAddr, destAddr, and
        //   text if originally sent as sendText or
        //   data and destPort if originally sent as sendData.
        if (!( map.containsKey("scAddr") && map.containsKey("destAddr") &&
               ( map.containsKey("text") ||
                       (map.containsKey("data") && map.containsKey("destPort"))))) {
            // should never come here...
            Log.e(TAG, "sendRetrySms failed to re-encode per missing fields!");
            if (tracker.mSentIntent != null) {
                int error = RESULT_ERROR_GENERIC_FAILURE;
                // Done retrying; return an error to the app.
                try {
                    tracker.mSentIntent.send(mContext, error, null);
                } catch (CanceledException ex) {}
            }
            return;
        }
        String scAddr = (String)map.get("scAddr");
        String destAddr = (String)map.get("destAddr");

        SmsMessageBase.SubmitPduBase pdu = null;
        //    figure out from tracker if this was sendText/Data
        if (map.containsKey("text")) {
            Log.d(TAG, "sms failed was text");
            String text = (String)map.get("text");

            if (newEncoding.isCdma()) {
                Log.d(TAG, "old encoding (gsm) ==> new encoding (cdma)");
                pdu = com.android.internal.telephony.cdma.SmsMessage.getSubmitPdu(
                        scAddr, destAddr, text, (tracker.mDeliveryIntent != null), null);
            } else {
                Log.d(TAG, "old encoding (cdma) ==> new encoding (gsm)");
                pdu = com.android.internal.telephony.gsm.SmsMessage.getSubmitPdu(
                        scAddr, destAddr, text, (tracker.mDeliveryIntent != null), null);
            }
        } else if (map.containsKey("data")) {
            Log.d(TAG, "sms failed was data");
            byte[] data = (byte[])map.get("data");
            Integer destPort = (Integer)map.get("destPort");

            if (newEncoding.isCdma()) {
                Log.d(TAG, "old encoding (gsm) ==> new encoding (cdma)");
                pdu = com.android.internal.telephony.cdma.SmsMessage.getSubmitPdu(
                            scAddr, destAddr, destPort.intValue(), data,
                            (tracker.mDeliveryIntent != null));
            } else {
                Log.d(TAG, "old encoding (cdma) ==> new encoding (gsm)");
                pdu = com.android.internal.telephony.gsm.SmsMessage.getSubmitPdu(
                            scAddr, destAddr, destPort.intValue(), data,
                            (tracker.mDeliveryIntent != null));
            }
        }

        // replace old smsc and pdu with newly encoded ones
        map.put("smsc", pdu.encodedScAddress);
        map.put("pdu", pdu.encodedMessage);

        SMSDispatcher dispatcher = (newEncoding.isCdma()) ?
                mCdmaDispatcher : mGsmDispatcher;

        tracker.mFormat = dispatcher.getFormat();
        dispatcher.sendSms(tracker);
    }

    protected void updateIccAvailability() {
        mUiccApplication = (Phone.PHONE_TYPE_CDMA == mPhone.getPhoneType()) ?
                mCdmaDispatcher.mUiccApplication :
                        mGsmDispatcher.mUiccApplication;
    }

    /**
     * Called when a Class2 SMS is  received.
     *
     * @param ar AsyncResult passed to this function. ar.result should
     *           be representing the INDEX of SMS on SIM.
     */
    protected void handleSmsOnIcc(AsyncResult ar) {
        Log.d(TAG, "handleSmsOnIcc function is not applicable for IMS");
    }

    /**
     * Called when a SMS on SIM is retrieved.
     *
     * @param ar AsyncResult passed to this function.
     */
    protected void handleGetIccSmsDone(AsyncResult ar) {
        Log.d(TAG, "handleGetIccSmsDone function is not applicable for IMS");
    }


    @Override
    protected String getFormat() {
        // this function should be defined in Gsm/CdmaDispatcher.
        Log.d(TAG, "getEncoding should never be called from here!");
        return null;
    }

    @Override
    protected TextEncodingDetails calculateLength(CharSequence messageBody, boolean use7bitOnly) {
        Log.e(TAG, "Error! Not implemented for IMS.");
        return null;
    }

    @Override
    protected void sendNewSubmitPdu(String destinationAddress, String scAddress, String message,
            SmsHeader smsHeader, int encoding, PendingIntent sentIntent,
            PendingIntent deliveryIntent, boolean lastPart) {
        Log.e(TAG, "Error! Not implemented for IMS.");
    }

    /* Returns the ICC filehandler  */
    @Override
    IccFileHandler getIccFileHandler() {
        updateIccAvailability();
        if (mUiccApplication != null) {
            return mUiccApplication.getIccFileHandler();
        }
        return null;
    }
}
