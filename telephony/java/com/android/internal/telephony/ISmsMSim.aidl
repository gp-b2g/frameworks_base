/*
** Copyright 2007 The Android Open Source Project
** Copyright (c) 2011 Code Aurora Forum. All rights reserved.
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.internal.telephony;

import android.app.PendingIntent;
import com.android.internal.telephony.SmsRawData;

/** Interface for applications to access the ICC phone book.
 *
 * <p>The following code snippet demonstrates a static method to
 * retrieve the ISmsMSim interface from Android:</p>
 * <pre>private static ISmsMSim getSmsInterface()
            throws DeadObjectException {
    IServiceManager sm = ServiceManagerNative.getDefault();
    ISmsMSim ss;
    ss = ISmsMSim.Stub.asInterface(sm.getService("ismsm_sim"));
    return ss;
}
 * </pre>
 */

interface ISmsMSim {
    /**
     * Retrieves all messages currently stored on ICC.
     * @param subscription the subscription id.
     * @return list of SmsRawData of all sms on ICC
     */
    List<SmsRawData> getAllMessagesFromIccEf(in int subscription);

    /**
     * Update the specified message on the ICC.
     *
     * @param messageIndex record index of message to update
     * @param newStatus new message status (STATUS_ON_ICC_READ,
     *                  STATUS_ON_ICC_UNREAD, STATUS_ON_ICC_SENT,
     *                  STATUS_ON_ICC_UNSENT, STATUS_ON_ICC_FREE)
     * @param pdu the raw PDU to store
     * @param subscription the subscription id.
     * @return success or not
     *
     */
     boolean updateMessageOnIccEf(int messageIndex, int newStatus,
             in byte[] pdu, in int subscription);

    /**
     * Copy a raw SMS PDU to the ICC.
     *
     * @param pdu the raw PDU to store
     * @param status message status (STATUS_ON_ICC_READ, STATUS_ON_ICC_UNREAD,
     *               STATUS_ON_ICC_SENT, STATUS_ON_ICC_UNSENT)
     * @param subscription the subscription id.
     * @return success or not
     *
     */
    boolean copyMessageToIccEf(int status, in byte[] pdu,
           in byte[] smsc, in int subscription);

    /**
     * Send an SMS.
     *
     * @param smsc the SMSC to send the message through, or NULL for the
     *  default SMSC
     * @param text the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is sucessfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     * @param subscription the subscription on which the SMS has to be sent.
     */
    void sendText(in String destAddr, in String scAddr, in String text,
            in PendingIntent sentIntent, in PendingIntent deliveryIntent,
            in int subscription);

    /**
     * Send a multi-part text based SMS.
     *
     * @param destinationAddress the address to send the message to
     * @param scAddress is the service center address or null to use
     *   the current default SMSC
     * @param parts an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been sent.
     *   The result code will be <code>Activity.RESULT_OK<code> for success,
     *   or one of these errors:
     *   <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *   <code>RESULT_ERROR_RADIO_OFF</code>
     *   <code>RESULT_ERROR_NULL_PDU</code>.
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been delivered
     *   to the recipient.  The raw pdu of the status report is in the
     *   extended data ("pdu").
     * @param subscription the subscription on which the SMS has to be sent.
     */
    void sendMultipartText(in String destinationAddress, in String scAddress,
            in List<String> parts, in List<PendingIntent> sentIntents,
            in List<PendingIntent> deliveryIntents, in int subscription);

    /**
     * Enable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier. Note that if two different clients enable the same
     * message identifier, they must both disable it for the device to stop
     * receiving those messages.
     *
     * @param messageIdentifier Message identifier as specified in TS 23.041
     * @param subscription for which the broadcast has to be enabled
     * @return true if successful, false otherwise
     *
     * @see #disableCellBroadcast(int)
     */
    boolean enableCellBroadcast(int messageIdentifier, in int subscription);


    /**
     * Disable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier. Note that if two different clients enable the same
     * message identifier, they must both disable it for the device to stop
     * receiving those messages.
     *
     * @param messageIdentifier Message identifier as specified in TS 23.041
     * @param subscription for which the broadcast has to be disabled
     * @return true if successful, false otherwise
     *
     * @see #enableCellBroadcast(int)
     */
    boolean disableCellBroadcast(int messageIdentifier, in int subscription);

    /**
     * Enable reception of cdma broadcast messages with the given
     * message identifier. Note that if two different clients enable the same
     * message identifier, they must both disable it for the device to stop
     * receiving those messages. All received messages will be broadcast in an
     * intent with the action "android.provider.telephony.SMS_CDMA_BROADCAST_RECEIVED".
     * Note: This call is blocking, callers may want to avoid calling it from
     * the main thread of an application.
     *
     * @param messageIdentifier Message identifier as specified in C.R1001-G
     * @param subscription for which the broadcast has to be enabled
     * @return true if successful, false otherwise
     * @see #disableCdmaBroadcast(int, int)
     */
    boolean enableCdmaBroadcast(int messageIdentifier, int subscription);

    /**
     * Disable reception of cdma broadcast messages with the given
     * message identifier. Note that if two different clients enable the same
     * message identifier, they must both disable it for the device to stop
     * receiving those messages.
     * Note: This call is blocking, callers may want to avoid calling it from
     * the main thread of an application.
     *
     * @param messageIdentifier Message identifier as specified in C.R1001-G
     * @param subscription for which the broadcast has to be disabled
     * @return true if successful, false otherwise
     *
     * @see #enableCdmaBroadcast(int, int)
     */
    boolean disableCdmaBroadcast(int messageIdentifier, int subscription);

    /**
     * Enable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier range. Note that if two different clients enable
     * a message identifier range, they must both disable it for the device
     * to stop receiving those messages.
     *
     * @param startMessageId first message identifier as specified in TS 23.041
     * @param endMessageId last message identifier as specified in TS 23.041
     * @param subscription for which the broadcast has to be enabled
     * @return true if successful, false otherwise
     *
     * @see #disableCellBroadcastRange(int, int, int)
     */
    boolean enableCellBroadcastRange(int startMessageId, int endMessageId, int subscription);

    /**
     * Disable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier range. Note that if two different clients enable
     * a message identifier range, they must both disable it for the device
     * to stop receiving those messages.
     *
     * @param startMessageId first message identifier as specified in TS 23.041
     * @param endMessageId last message identifier as specified in TS 23.041
     * @param subscription for which the broadcast has to be disabled
     * @return true if successful, false otherwise
     *
     * @see #enableCellBroadcastRange(int, int, int)
     */
    boolean disableCellBroadcastRange(int startMessageId, int endMessageId, int subscription);

    /*
     * get user prefered SMS subscription
     * @return subscription id
     */
    int getPreferredSmsSubscription();

}
