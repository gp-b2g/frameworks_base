/*
 * Copyright (C) 2009 Borqs Inc.
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

import android.os.Bundle;
import com.android.internal.telephony.IVideoTelephonyListener;


interface IVideoTelephony {
    
    /**
     * Query Fdn.
     */
    boolean getIccFdnEnabled();
    
    /**
     * Place a video call to the numer.
     * @param number the number to be called.
     */
    void call(String number/*, CallDetails callDetails*/);
    

    /**
     * End call or go to the Home screen
     *
     * @return whether it hung up
     */
    void endCall();
    

    /**
     * fall back a video call to voice call
     *
     * @return whether it hung up
     */
    void fallBack();
    
    /**
     * answer video call
     */
    void answerCall(/*CallDetails.CallType callType*/);
    
    /**
     * reject video call
     */
    void rejectCall();
    
    /* to start the DTMF tone c is the character what you are pressing*/
    void startDtmf(char c);
    
    void stopDtmf();
    
    /**
     * Check if the video phone is idle.
     * @return true if the video phone state is IDLE.
     */
    boolean isVtIdle();
    
     
    void registerListener(IVideoTelephonyListener l);
    void unregisterListener(IVideoTelephonyListener l);
}
