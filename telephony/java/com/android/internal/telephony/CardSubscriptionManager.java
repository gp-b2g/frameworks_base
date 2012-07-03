/*
 * Copyright (c) 2010-2012 Code Aurora Forum. All rights reserved.
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

import java.util.ArrayList;

import com.android.internal.telephony.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.IccCardStatus.CardState;
import com.android.internal.telephony.MSimConstants.CardUnavailableReason;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;


/**
 * Keep track of complete info of both the cards including the ICCID.
 * Includes:
 *   - Card Subscriptions info of all cards
 * Handles
 *   - Insertion of Cards
 *   - Removal of Cards
 *   - Read ICCID for new cards
 *   - SIM Refresh
 */
public class CardSubscriptionManager extends Handler {
    static final String LOG_TAG = "CardSubscriptionManager";

    /** Utility class, holds the UiccCard and corresponding ICCID */
    class CardInfo {
        private UiccCard mUiccCard;
        private boolean mReadIccIdInProgress;
        private String mIccId;
        private CardState mCardState;

        public CardInfo(UiccCard uiccCard) {
            mUiccCard = uiccCard;
            if (uiccCard !=  null) {
                mCardState = uiccCard.getCardState();
            } else {
                mCardState = null;
            }
            mIccId = null;
            mReadIccIdInProgress = false;
        }

        public UiccCard getUiccCard() {
            return mUiccCard;
        }

        public void setUiccCard(UiccCard uiccCard) {
            mUiccCard = uiccCard;
            if (mUiccCard !=  null) {
                mCardState = mUiccCard.getCardState();
                if (mCardState != CardState.CARDSTATE_PRESENT) {
                    mIccId = null;
                    mReadIccIdInProgress = false;
                }
            } else {
                mCardState = null;
                mIccId = null;
                mReadIccIdInProgress = false;
            }
        }

        public void setCardState(CardState cardState) {
            mCardState = cardState;
        }

        public CardState getCardState() {
            return mCardState;
        }

        public boolean isReadIccIdInProgress() {
            return mReadIccIdInProgress;
        }

        public void setReadIccIdInProgress(boolean read) {
            mReadIccIdInProgress = read;
        }

        public String getIccId() {
            return mIccId;
        }

        public void setIccId(String iccId) {
            mIccId = iccId;
        }

        public String toString() {
            return "[mUiccCard = " + mCardState + ", mIccId = " + mIccId
                    + ", mReadIccIdInProgress = " + mReadIccIdInProgress + "]";
        }
    }


    //***** Events
    private static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 0;
    private static final int EVENT_RADIO_ON = 1;
    private static final int EVENT_ICC_CHANGED = 2;
    private static final int EVENT_GET_ICCID_DONE = 3;
    private static final int EVENT_UPDATE_UICC_STATUS = 4;
    private static final int EVENT_SIM_REFRESH = 5;
    private static final int EVENT_RADIO_NOT_AVAILABLE = 6;
    //***** Class Variables
    private static CardSubscriptionManager sCardSubscriptionManager;

    private Context mContext;
    private CommandsInterface[] mCi;
    private UiccManager mUiccManager;
    private boolean[] mRadioOn = {false, false};
    private int mUpdateUiccStatusContext = 0;

    private RegistrantList[] mCardInfoUnavailableRegistrants;
    private RegistrantList[] mCardInfoAvailableRegistrants;
    private RegistrantList mAllCardsInfoAvailableRegistrants = new RegistrantList();

    // The subscription information of all the cards
    private SubscriptionData[] mCardSubData = null;
    private ArrayList<CardInfo> mUiccCardList = new ArrayList<CardInfo>(MSimConstants.RIL_MAX_CARDS);
    private boolean mAllCardsInfoAvailable = false;

    //***** Class Methods
    public static CardSubscriptionManager getInstance(Context context, UiccManager uiccMgr, CommandsInterface[] ci) {
        Log.d(LOG_TAG, "getInstance");
        if (sCardSubscriptionManager == null) {
            sCardSubscriptionManager = new CardSubscriptionManager(context, uiccMgr, ci);
        }
        return sCardSubscriptionManager;
    }

    public static CardSubscriptionManager getInstance() {
        return sCardSubscriptionManager;
    }

    //***** Constructor
    private CardSubscriptionManager(Context context, UiccManager uiccManager, CommandsInterface[] ci) {
        logd("Constructor - Enter");
        mContext = context;
        mCi = ci;
        mUiccManager = uiccManager;

        for (int i = 0; i < mCi.length; i++) {
            // Register for Subscription ready event for both the subscriptions.
            Integer slot = new Integer(i);
            mCi[i].registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, slot);
            //mCi[i].registerForNotAvailable(this, EVENT_RADIO_NOT_AVAILABLE, slot);
            mCi[i].registerForOn(this, EVENT_RADIO_ON, slot);

            // Register for SIM Refresh events
            mCi[i].registerForIccRefresh(this, EVENT_SIM_REFRESH, new Integer(i));
        }

        mUiccManager.registerForIccChanged(this, EVENT_ICC_CHANGED, null);

        mCardSubData = new SubscriptionData[MSimConstants.RIL_MAX_CARDS];
        mUiccCardList = new ArrayList<CardInfo>(MSimConstants.RIL_MAX_CARDS);
        for (int i = 0; i < MSimConstants.RIL_MAX_CARDS; i++) {
            mUiccCardList.add(new CardInfo(null));
        }

        mCardInfoUnavailableRegistrants = new RegistrantList[MSimConstants.RIL_MAX_CARDS];
        mCardInfoAvailableRegistrants = new RegistrantList[MSimConstants.RIL_MAX_CARDS];
        for (int i = 0; i < MSimConstants.RIL_MAX_CARDS; i++) {
            mCardInfoUnavailableRegistrants [i] = new RegistrantList();
            mCardInfoAvailableRegistrants [i] = new RegistrantList();
        }

        logd("Constructor - Exit");
    }

    @Override
    public void handleMessage(Message msg) {
        switch(msg.what) {
            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                logd("EVENT_RADIO_OFF_OR_NOT_AVAILABLE");
                if (0 == SystemProperties.getInt("persist.radio.apm_sim_not_pwdn", 0)) {
                    processRadioUnavailable((AsyncResult)msg.obj);
                }
                break;

            case EVENT_RADIO_ON:
                logd("EVENT_RADIO_ON");
                processRadioOn((AsyncResult)msg.obj);
                break;

            case EVENT_ICC_CHANGED:
                logd("EVENT_ICC_CHANGED");
                handleIccChanged((AsyncResult) msg.obj);
                break;

            case EVENT_GET_ICCID_DONE:
                logd("EVENT_READ_ICCID_DONE");
                handleGetIccIdDone((AsyncResult)msg.obj);
                break;

            case EVENT_UPDATE_UICC_STATUS:
                logd("EVENT_UPDATE_UICC_STATUS");
                onUpdateUiccStatus((Integer)msg.arg2, ((String)msg.obj));
                break;

            case EVENT_SIM_REFRESH:
                logd("EVENT_SIM_REFRESH");
                processSimRefresh((AsyncResult)msg.obj);
                break;

            default:
                break;
        }

    }

    private void processSimRefresh(AsyncResult ar) {
        if (ar.exception == null && ar.result != null) {
            IccRefreshResponse state = (IccRefreshResponse)ar.result;

            Integer slot = (Integer)ar.userObj;
            logd("processSimRefresh: slot = " + slot
                    + " refreshResult = " + state.refreshResult);

            if (state.refreshResult == IccRefreshResponse.Result.ICC_RESET
                    && (slot >= 0 && slot < MSimConstants.RIL_MAX_CARDS)) {
                resetCardInfo(slot);
                notifyCardInfoNotAvailable(slot, CardUnavailableReason.REASON_SIM_REFRESH_RESET);
            } else if (state.refreshResult == IccRefreshResponse.Result.ICC_FILE_UPDATE) {
                Intent intent = new Intent(TelephonyIntents.ACTION_SIM_REFRESH_UPDATE);
                intent.putExtra(MSimConstants.SUBSCRIPTION_KEY, slot);
                logd("Broadcasting intent ACTION_SIM_REFRESH_UPDATE " + "at slot : " + slot);
                mContext.sendBroadcast(intent,null);
            }
        } else {
            loge("processSimRefresh received without input");
        }
    }

    private void processRadioUnavailable(AsyncResult ar) {
        Integer cardIndex = (Integer)ar.userObj;

        logd("processRadioUnavailable on cardIndex = " + cardIndex);

        if (cardIndex >= 0 && cardIndex < mRadioOn.length) {
            mRadioOn[cardIndex] = false;
            resetCardInfo(cardIndex);
            // Card is not available from this slot.  Notify cards unavailable.
            notifyCardInfoNotAvailable(cardIndex, CardUnavailableReason.REASON_RADIO_UNAVAILABLE);
            // Reset the flag card info available to false, so that
            // next time it notifies all cards info available.
            mAllCardsInfoAvailable = false;
        } else {
            logd("Invalid Index!!!");
        }
    }

    private void processRadioOn(AsyncResult ar) {
        Integer cardIndex = (Integer)ar.userObj;

        logd("processRadioOn on cardIndex = " + cardIndex);

        if (cardIndex >= 0 && cardIndex < mRadioOn.length) {
            mRadioOn[cardIndex] = true;
        } else {
            logd("Invalid Index!!!");
        }
    }

    /**
     * Process the ICC_CHANGED notification.
     */
    private void handleIccChanged(AsyncResult ar) {
        boolean iccIdsAvailable = false;
        boolean cardStateChanged = false;

        logd("handleIccChanged: ENTER");

        if ((ar.exception == null) && (ar.result != null)) {
            Integer cardIndex = (Integer) ar.result;
            if (!mRadioOn[cardIndex] && isAirplaneModeCardPowerDown()) {
                logd("handleIccChanged: radio not available - EXIT");
                return;
            }
            UiccCard uiccCard = mUiccManager.getIccCards()[cardIndex];
            UiccCard card = mUiccCardList.get(cardIndex).getUiccCard();

            logd("cardIndex = " + cardIndex + " new uiccCard = "
                    + uiccCard + " old card = " + card);

            // If old card is null then update the card info
            // If no change in card state then no need to read ICCID
            if (card != null) {
                CardState oldCardState = mUiccCardList.get(cardIndex).getCardState();
                mUiccCardList.get(cardIndex).setUiccCard(uiccCard);

                logd("handleIccChanged: oldCardState = " + oldCardState);

                if (uiccCard != null) {
                    logd("handleIccChanged: new uiccCard.getCardState() = "
                            + uiccCard.getCardState());

                    // If this is a new card then we need to read the ICCID
                    // once again. Reset the ICCID and the read flag.
                    if (uiccCard.getCardState() != oldCardState) {
                        if (uiccCard.getCardState() == CardState.CARDSTATE_PRESENT) {
                            mUiccCardList.get(cardIndex).setIccId(null);
                            mUiccCardList.get(cardIndex).setReadIccIdInProgress(false);
                        }
                        cardStateChanged = true;
                    }
                } else {
                    logd("handleIccChanged: new uiccCard is NULL");
                    cardStateChanged = true;
                }
            } else if (card == null) {  // First time when gets a new uiccCard
                cardStateChanged = true;
                mUiccCardList.set(cardIndex, new CardInfo(uiccCard));
            }

            CardInfo cardInfo = mUiccCardList.get(cardIndex);
            logd("handleIccChanged: cardStateChanged = " + cardStateChanged
                    + " card info = " + cardInfo);
            // Read ICCID if it is not present otherwise update the card info
            if (cardInfo.getCardState() == CardState.CARDSTATE_PRESENT
                    && cardInfo.getIccId() == null) {
                updateIccIds(cardIndex);
            } else if (cardStateChanged) {
                updateUiccStatus(cardIndex, "ICC STATUS CHANGED");
            }
        }
        logd("handleIccChanged: EXIT");
    }

    /**
     * Return true if there is any read ICCID request is in progress otherwise false.
     */
    private boolean isIccIdAvailable(int cardIndex) {
        CardInfo cardInfo = mUiccCardList.get(cardIndex);
        if (cardInfo.getCardState() == CardState.CARDSTATE_PRESENT
                && cardInfo.getIccId() != null) {
            return true;
        }
        return false;
    }

    /** Resets the card subscriptions */
    private void resetCardInfo(int cardIndex) {
        logd("resetCardInfo(): cardIndex = " + cardIndex);
        if (cardIndex < mCardSubData.length) {
            mCardSubData[cardIndex] = null;
        }

        if (cardIndex < mUiccCardList.size()) {
            mUiccCardList.set(cardIndex, new CardInfo(null));
        }
    }

    /**
     * This issues a read ICCID request if the ICCID is not yet read for the cards.
     */
    private boolean updateIccIds(int cardIndex) {
        boolean readStarted = false;
        CardInfo cardInfo = mUiccCardList.get(cardIndex);
        // get the ICCID from the cards present.
        UiccCard uiccCard = cardInfo .getUiccCard();

        logd("updateIccIds: cardIndex = " + cardIndex
                + " cardInfo = " + cardInfo);

        // If card is present and ICCID is null, and no read ICCID
        // request is issued so far, then issue read request now.
        if (uiccCard != null
                && uiccCard.getCardState() == CardState.CARDSTATE_PRESENT
                && cardInfo.getIccId() == null
                && !cardInfo.isReadIccIdInProgress()) {
            String strCardIndex = Integer.toString(cardIndex);
            Message response = obtainMessage(EVENT_GET_ICCID_DONE, strCardIndex);
            UiccCardApplication cardApp = uiccCard.getApplication(0);
            if (cardApp != null) {
                IccFileHandler fileHandler = cardApp.getIccFileHandler();
                if (fileHandler != null) {
                    logd("updateIccIds: get ICCID for cardInfo : "
                            + cardIndex);
                    fileHandler.loadEFTransparent(IccConstants.EF_ICCID, response);
                    cardInfo.setReadIccIdInProgress(true); // ICCID read started!!!
                    readStarted = true;
                }
            }
        }

        return readStarted;
    }

    /**
     * Process the read ICCID response.
     * Update the ICCID for the corresponding card and trigger UPDATE_UICC_STATUS
     * if there is no other read ICCID in progress.
     *
     */
    synchronized private void handleGetIccIdDone(AsyncResult ar) {
        if (ar == null) {
            logd("handleGetIccIdDone: parameter is null");
            return;
        }

        byte []data = (byte[])ar.result;
        int cardIndex = 0;

        if (ar.userObj != null) {
            cardIndex = Integer.parseInt((String)ar.userObj);
        }

        logd("handleGetIccIdDone: cardIndex = " + cardIndex);

        if (!mRadioOn[cardIndex] && isAirplaneModeCardPowerDown()) {
            logd("handleGetIccIdDone: radio not available - EXIT");
            return;
        }

        String iccId = null;

        if (ar.exception != null) {
            logd("Exception in GET ICCID");
            // ICCID read failure. We may need to read the ICCID again.
            mUiccCardList.get(cardIndex).setCardState(null);
        } else {
            iccId = IccUtils.bcdToString(data, 0, data.length);
        }

        mUiccCardList.get(cardIndex).setReadIccIdInProgress(false);

        mUiccCardList.get(cardIndex).setIccId(iccId);
        logd("=============================================================");
        logd("GET ICCID DONE. ICCID of card[" + cardIndex + "] = " + iccId);
        logd("=============================================================");

        // ICCID read are completed.  Now proceed with the card processing.
        updateUiccStatus(cardIndex, "ICCID Read Done for card : " + cardIndex);
    }


    private void updateUiccStatus(Integer cardIndex, String reason) {
        mUpdateUiccStatusContext++;
        Message msg = obtainMessage(EVENT_UPDATE_UICC_STATUS, //what
                mUpdateUiccStatusContext, //arg1
                cardIndex, //arg2
                reason); //userObj
        sendMessage(msg);
    }


    /**
     *  Update the UICC status.
     */
    synchronized private void onUpdateUiccStatus(Integer cardIndex, String reason) {
        logd("onUpdateUiccStatus: cardIndex = " + cardIndex + " reason = " + reason);

        CardState cardState = null;
        CardInfo cardInfo = mUiccCardList.get(cardIndex);
        UiccCard uiccCard = null;
        boolean cardRemoved = false;
        boolean cardInserted = false;

        if (cardInfo != null) {
            uiccCard = cardInfo.getUiccCard();
        }

        if (uiccCard == null || (isAirplaneModeCardPowerDown() && mRadioOn[cardIndex] == false)) {
            logd("onUpdateUiccStatus(): mRadioOn[" + cardIndex + "] = " + mRadioOn[cardIndex]);
            logd("onUpdateUiccStatus(): NO Card!!!!! at index : " + cardIndex);
            if (mCardSubData[cardIndex] != null) {
                // Card is removed.
                cardRemoved = true;
            }
            mCardSubData[cardIndex] = null;
        } else {
            cardState = uiccCard.getCardState();

            logd("onUpdateUiccStatus(): cardIndex = " + cardIndex
                    + " cardInfo = " + cardInfo);

            int numApps = 0;
            if (cardState == CardState.CARDSTATE_PRESENT) {
                numApps = uiccCard.getNumApplications();
            }
            logd("onUpdateUiccStatus(): Number of apps : " + numApps);

            // Process only if the card is PRESENT, the ICCID is available and number of app > 0.
            if (cardState == CardState.CARDSTATE_PRESENT && cardInfo.getIccId() != null && numApps > 0) {
                logd("onUpdateUiccStatus(): mCardSubData[" + cardIndex
                        + "] = " + mCardSubData[cardIndex]);

                // Update the mCardSubData only if a new card available.
                // ie., if previous mCardSubData is null or the iccId is different.
                if (mCardSubData[cardIndex] == null ||
                        (mCardSubData[cardIndex] != null
                         && mCardSubData[cardIndex].getIccId() != cardInfo.getIccId())) {

                    logd("onUpdateUiccStatus(): New card, update card info at index = "
                        + cardIndex);

                    mCardSubData[cardIndex] = new SubscriptionData(numApps);

                    for (int appIndex = 0; appIndex < numApps; appIndex++) {
                        Subscription cardSub = mCardSubData[cardIndex].subscription[appIndex];
                        UiccCardApplication uiccCardApplication = uiccCard.getApplication(appIndex);

                        cardSub.slotId = cardIndex;
                        // Not required to set subId or subStatus.
                        //cardSub.subId = Subscription.SUBSCRIPTION_INDEX_INVALID;  // Not set the sub id
                        //cardSub.subStatus = Subscription.SubcriptionStatus.SUB_INVALID;
                        cardSub.appId = uiccCardApplication.getAid();
                        cardSub.appLabel = uiccCardApplication.getAppLabel();
                        cardSub.iccId = cardInfo.getIccId();

                        AppType type = uiccCardApplication.getType();
                        String subAppType = appTypetoString(type);
                        //Apps like ISIM etc are treated as UNKNOWN apps, to be discarded
                        if (!subAppType.equals("UNKNOWN")) {
                            cardSub.appType = subAppType;
                        } else {
                            cardSub.appType = null;
                            logd("onUpdateUiccStatus(): UNKNOWN APP");
                        }

                        // In case of MultiSIM, APPSTATE_READY should not come before selecting the subscriptions from UI.
                        // Show a warning message in this case.
                        if (uiccCardApplication.getState() == AppState.APPSTATE_READY) {
                            loge("*************************************************************************************");
                            loge("AppState of the UiccCardApplication @ cardIndex:" + cardIndex + " appIndex:" + appIndex
                                    + " is APPSTATE_READY!!!!!");
                            loge("Android expectes APPSTATE_DETECTED before selecting the subscriptions!!!!!");
                            loge("WARNING!!! Please configure the NV items properly to select the subscriptions from UI");
                            loge("*************************************************************************************");
                        }

                        fillAppIndex(cardSub, appIndex);
                    }
                    cardInserted = true;
                }
            } else {
                mCardSubData[cardIndex] = null;
                cardRemoved = true;
            }
        }

        if (cardInserted){
            notifyCardInfoAvailable(cardIndex);
        }
        if (cardRemoved){
            notifyCardInfoNotAvailable(cardIndex, CardUnavailableReason.REASON_CARD_REMOVED);
        }

        // Required to notify only once!!!
        // Notify if all card info is available.
        if (isValidCards() && !mAllCardsInfoAvailable &&
            !(!mRadioOn[cardIndex] && isAirplaneModeCardPowerDown())) {
            mAllCardsInfoAvailable = true;
            notifyAllCardsInfoAvailable();
        }
    }

    private void fillAppIndex(Subscription cardSub, int appIndex) {
        if (cardSub.appType == null) {
            cardSub.m3gppIndex = Subscription.SUBSCRIPTION_INDEX_INVALID;
            cardSub.m3gpp2Index = Subscription.SUBSCRIPTION_INDEX_INVALID;
        } else if (cardSub.appType.equals("SIM") || cardSub.appType.equals("USIM")) {
            cardSub.m3gppIndex = appIndex;
            cardSub.m3gpp2Index = Subscription.SUBSCRIPTION_INDEX_INVALID;
        } else if (cardSub.appType.equals("RUIM") || cardSub.appType.equals("CSIM")) {
            cardSub.m3gppIndex = Subscription.SUBSCRIPTION_INDEX_INVALID;
            cardSub.m3gpp2Index = appIndex;
        }
    }

    private String appTypetoString(AppType p) {
        switch(p) {
            case APPTYPE_UNKNOWN:
                {return "UNKNOWN";}
            case APPTYPE_SIM:
                {return "SIM"; }
            case APPTYPE_USIM:
                {return "USIM";}
            case APPTYPE_RUIM:
                {return "RUIM";}
            case APPTYPE_CSIM:
                {return "CSIM";}
            default:
                {return "UNKNOWN";}
        }
    }

    private void notifyAllCardsInfoAvailable() {
        mAllCardsInfoAvailableRegistrants.notifyRegistrants();
    }

    private void notifyCardInfoNotAvailable(int cardIndex, CardUnavailableReason reason) {
        mCardInfoUnavailableRegistrants[cardIndex].notifyRegistrants(
                new AsyncResult(null, reason, null));
    }

    private void notifyCardInfoAvailable(int cardIndex) {
        mCardInfoAvailableRegistrants[cardIndex].notifyRegistrants();
    }

    public void registerForAllCardsInfoAvailable(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        if (mAllCardsInfoAvailable) {
            r.notifyRegistrant();
        }
        synchronized (mAllCardsInfoAvailableRegistrants) {
            mAllCardsInfoAvailableRegistrants.add(r);
        }
    }

    public void registerForCardInfoUnavailable(int cardIndex, Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        synchronized (mCardInfoUnavailableRegistrants[cardIndex]) {
            mCardInfoUnavailableRegistrants[cardIndex].add(r);
        }
    }

    public void registerForCardInfoAvailable(int cardIndex, Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        synchronized (mCardInfoAvailableRegistrants[cardIndex]) {
            mCardInfoAvailableRegistrants[cardIndex].add(r);
        }
    }

    /**
     * Retrieves the card subscription info for card at slot cardIndex
     * @param cardIndex
     * @return card subscription data for card in cardIndex
     */
    public SubscriptionData getCardSubscriptions(int cardIndex){
        return mCardSubData[cardIndex];
    }

    /**
     * Returns true if both cards state either ABSENT, ERROR or PRESENT with a valid ICCID.
     * @return
     */
    public boolean isValidCards() {
        for (CardInfo cardInfo : mUiccCardList) {
            if (cardInfo.getUiccCard() == null
                    || (cardInfo.getCardState() == CardState.CARDSTATE_PRESENT
                        && cardInfo.getIccId() == null)) {
                return false;
            }
        }
        return true;
    }

    public boolean isCardAbsentOrError(int cardIndex) {
        CardInfo cardInfo = mUiccCardList.get(cardIndex);
        return ((cardInfo.getCardState() == CardState.CARDSTATE_ABSENT)
                || (cardInfo.getCardState() == CardState.CARDSTATE_ERROR));
    }

    public boolean isAllCardsUpdated() {
        for (int cardIndex = 0; cardIndex < MSimConstants.RIL_MAX_CARDS; cardIndex++) {
            CardInfo cardInfo = mUiccCardList.get(cardIndex);
            SubscriptionData cardSub = mCardSubData[cardIndex];

            // Return false
            //  - if card not available
            //  - if card present and iccid not available
            //  - if card Sub Data is not yet updated
            if (cardInfo.getUiccCard() == null
                    || (cardInfo.getCardState() == CardState.CARDSTATE_PRESENT
                            && cardInfo.getIccId() == null)
                    || (cardInfo.getUiccCard() != null
                            && cardSub != null
                            && cardInfo.getIccId() != cardSub.getIccId())) {
                return false;
            }
        }
        return true;
    }

    public boolean isCardInfoAvailable(int cardIndex) {
        CardInfo cardInfo = mUiccCardList.get(cardIndex);
        SubscriptionData cardSub = mCardSubData[cardIndex];

        // Return false
        //  - if card not available
        //  - if card present and iccid not available
        //  - if card Sub Data is not yet updated
        if (cardInfo.getUiccCard() == null
                || (cardInfo.getCardState() == CardState.CARDSTATE_PRESENT
                        && cardInfo.getIccId() == null)
                || (cardInfo.getUiccCard() != null
                        && cardSub != null
                        && cardInfo.getIccId() != cardSub.getIccId())) {
            return false;
        }
        return true;
    }

    private boolean isAirplaneModeCardPowerDown() {
        return 0 == SystemProperties.getInt("persist.radio.apm_sim_not_pwdn", 0);
    }

    private void logd(String string) {
        Log.d(LOG_TAG, string);
    }

    private void loge(String string) {
        Log.e(LOG_TAG, string);
    }
}
