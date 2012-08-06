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


import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.telephony.ServiceState;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Config;
import android.util.Log;
import android.provider.Settings;
import android.content.Context;
import android.os.RemoteCallbackList;
import android.os.RemoteException;


public class VideoPhoneInterfaceManager extends IVideoTelephony.Stub {
    private static final boolean DEBUG = true; //Config.DEBUG;
    private static final String TAG = "VideoPhoneInterfaceManager";

    //add by b178
    final public RemoteCallbackList<IVideoTelephonyListener> mCallbacks = new RemoteCallbackList<IVideoTelephonyListener>();
    private final static int VT_PHONE_STATE_CHANGED = 0;
    private final static int VT_PHONE_NEW_RINGING_CONNECTION = 1;
    private final static int VT_PHONE_DISCONNECTED = 2;

    public static final int VIDEOCALL_STATE_IDLE = 0;
    public static final int VIDEOCALL_STATE_RINGING = 1;
    public static final int VIDEOCALL_STATE_OFFHOOK = 2;
    public static final int VIDEOCALL_STATE_ACTIVE = 3;
    public static final int VIDEOCALL_STATE_DISCONNECTED = 4;

    public static final int VIDEOCALL_RESULT_DISCONNECTED_NO_ANSWER = 10;
    public static final int VIDEOCALL_RESULT_DISCONNECTED_BUSY = 11;
    public static final int VIDEOCALL_RESULT_DISCONNECTED_INVALID_NUMBER = 12;
    public static final int VIDEOCALL_RESULT_DISCONNECTED_INCOMING_REJECTED = 13;
    public static final int VIDEOCALL_RESULT_DISCONNECTED_POWER_OFF = 14;
    public static final int VIDEOCALL_RESULT_DISCONNECTED_OUT_OF_SERVICE = 15;
    public static final int VIDEOCALL_RESULT_DISCONNECTED_UNASSIGNED_NUMBER = 16;
    public static final int VIDEOCALL_RESULT_FALLBACK_88 = 17;
    public static final int VIDEOCALL_RESULT_FALLBACK_47 = 18;
    public static final int VIDEOCALL_RESULT_FALLBACK_57 = 19;
    public static final int VIDEOCALL_RESULT_FALLBACK_58 = 20;
    public static final int VIDEOCALL_RESULT_DISCONNECTED_NUMBER_CHANGED = 21;
    public static final int VIDEOCALL_RESULT_CONNECTED = 22;
    public static final int VIDEOCALL_RESULT_DISCONNECTED = 23;
    public static final int VIDEOCALL_RESULT_DISCONNECTED_LOST_SIGNAL = 24;
    public static final int VIDEOCALL_RESULT_NORMAL_UNSPECIFIED = 25;
    public static final int VIDEOCALL_RESULT_PROTOCOL_ERROR_UNSPECIFIED = 26;
    public static final int VIDEOCALL_RESULT_BEARER_NOT_SUPPORTED_65 = 27;  //bearer side not support vt
    public static final int VIDEOCALL_RESULT_BEARER_NOT_SUPPORTED_79 = 28;  //bearer side not support vt
    public static final int VIDEOCALL_RESULT_NO_USER_RESPONDING = 29;
    public static final int VIDEOCALL_RESULT_NETWORK_CONGESTION = 30;
    public static final int VIDEOCALL_RESULT_DISCONNECTED_INCOMING_MISSED = 31;
    public static final int VIDEOCALL_RESULT_DISCONNECTED_LCOAL_PHONE_OUT_OF_3G_SERVICE = 32; // Local walk out of 3G service after VT call is connected.
    public static final int VIDEOCALL_RESULT_NORMAL = 33;


    public static final int VIDEOCALL_RESULT_CALL_EXCEPTION = -1;
    public static final int VIDEOCALL_RESULT_ENDCALL_EXCEPTION = -2;
    public static final int VIDEOCALL_RESULT_FALLBACK_EXCEPTION = -3;
    public static final int VIDEOCALL_RESULT_ANSWERCALL_EXCEPTION = -4;
    public static final int VIDEOCALL_RESULT_REJECTCALL_EXCEPTION = -5;

    private Connection mConnection = null;
    private final Object mLock = new Object();

//    private Call mForegroundCall;  

    Phone mPhone;

    public VideoPhoneInterfaceManager( Phone phone) {
        mPhone = phone;
        mPhone.registerForPreciseCallStateChanged(mHandler , VT_PHONE_STATE_CHANGED, null);
        mPhone.registerForRingbackTone(mHandler , VT_PHONE_STATE_CHANGED, null);
        mPhone.registerForNewRingingConnection(mHandler , VT_PHONE_NEW_RINGING_CONNECTION, null);
        //mPhone.registerForIncomingRing(mHandler , VT_PHONE_NEW_RINGING_CONNECTION, null);
        mPhone.registerForDisconnect(mHandler, VT_PHONE_DISCONNECTED,null);

//        mForegroundCall = mPhone.getForegroundCall();
        publish();
    }

    private void publish() {
        ServiceManager.addService("videophone", this); 
        //need to update commands/binder/servicemanage.c accordingly
    }
    
    public boolean getIccFdnEnabled() {
        return mPhone.getIccCard().getIccFdnEnabled();
    }

    public void call(String number) {
        if(DEBUG) Log.v(TAG,"call: " + number);
        Log.d(TAG,"calling....");
        
        //need to check emergecy number?
        //Todo
        if(mPhone.getRingingCall().isRinging() ||
            mPhone.getForegroundCall().getState().isAlive() ||
            mPhone.getBackgroundCall().getState().isAlive()) {
            Log.e(TAG, "call is not in idle state, cannot dial");
            onException(VIDEOCALL_RESULT_CALL_EXCEPTION);
            return;
        }


        try {
            mConnection = mPhone.dialVideoCall(number);
        } catch (Exception ex) {
            onException(VIDEOCALL_RESULT_CALL_EXCEPTION);
            Log.e(TAG, "Call hangup: caught " + ex, ex);
        }

        if(mConnection == null) {
            if(DEBUG) Log.v(TAG,"Warning: mConnection is null after dial");
            onException(VIDEOCALL_RESULT_CALL_EXCEPTION);
        }
    }
    
    public void endCall() {
        if(DEBUG) Log.v(TAG, " endCall() mConnection="+mConnection);
        try {
        	if(null == mConnection) {
        		Log.d(TAG, " connection is null");
        		return;
        	}
            mPhone.endVideoCall();
        } catch (Exception ex) {
        onException(VIDEOCALL_RESULT_ENDCALL_EXCEPTION);
            Log.e(TAG, "Call hangup: caught " + ex, ex);
        }
    }
    
    public void fallBack(){
        if(DEBUG) Log.v(TAG, " fall back Call() ");
        try {
            mPhone.requestFallback();
        } catch (Exception ex) {
            onException(VIDEOCALL_RESULT_FALLBACK_EXCEPTION);
            Log.e(TAG, "Call hangup: caught " + ex, ex);
        }
    }
    
    public void answerCall(){       
        if(DEBUG) Log.v(TAG, " acceptCall() ");
        try {
            mPhone.acceptCallVT();
        } catch (Exception ex) {
            onException(VIDEOCALL_RESULT_ANSWERCALL_EXCEPTION);
            Log.e(TAG, "Call hangup: caught " + ex, ex);
        }
    }
    
    public void rejectCall(){
        if(DEBUG) Log.v(TAG, " rejectCall() ");
        try {
        	mPhone.rejectCallVT();
        } catch (CallStateException ex) {
            onException(VIDEOCALL_RESULT_REJECTCALL_EXCEPTION);
            Log.e(TAG, "Call hangup: caught " + ex, ex);
        }
    }
    
    public void startDtmf(char c) {
    	if(DEBUG) Log.v(TAG, " startDtmf() with c "+c);
        try {
        	mPhone.startDtmf(c);
        } catch (Exception ex) {            
            Log.e(TAG, "startDtmf: caught " + ex, ex);
        }
    }
    
    public void stopDtmf() {
    	if(DEBUG) Log.v(TAG, " stopDtmf()...");
        try {
        	mPhone.stopDtmf();
        } catch (Exception ex) {            
            Log.e(TAG, "stopDtmf: caught " + ex, ex);
        }
    }
    
    public  void unregisterListener(IVideoTelephonyListener l){
        if(DEBUG) Log.v(TAG, " unregisterListener: "+l);
        synchronized (mLock) {        
            if (l != null)
                mCallbacks.unregister(l);
        }
    }
    
    public void registerListener(IVideoTelephonyListener l){
        if(DEBUG) Log.v(TAG, "enter register l = " + l);
        synchronized (mLock) {        
            if (l != null) 
                mCallbacks.register(l);
        }
    }

    private void onException(int exception){
        synchronized (mLock) {        
            final int N = mCallbacks.beginBroadcast();
            for (int i = 0; i < N; i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onVTCallResult(exception);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing
                    // the dead object for us.
                    Log.e(TAG, "Error to broadcast phone stated changed.");
                }
            }
            mCallbacks.finishBroadcast();
        }
    }
    
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if(DEBUG) Log.v(TAG, "handleMessage: msg.what="+msg.what);
            switch (msg.what) {
            case VT_PHONE_STATE_CHANGED:
            	Log.d(TAG,"VT_PHONE_STATE_CHANGED");
                Connection conn = mPhone.getForegroundCall().getEarliestConnection();
                if(conn != null && conn.isVoice()){
                    if(DEBUG) Log.v(TAG,"not video call, return");
                    return;
                }

                Call.State cs = mPhone.getForegroundCall().getState() ;
                Log.d(TAG,"VT Call State = "+cs);

                if (cs == Call.State.ACTIVE) {
                    synchronized (mLock) {        
                    final int N = mCallbacks.beginBroadcast();
                    for(int i = 0; i < N; i++){
                        try {
                            mCallbacks.getBroadcastItem(i).onVTCallResult(VIDEOCALL_RESULT_CONNECTED);
                        }catch (Exception e){
                            Log.e(TAG, "Error: in state cactive callback");
                        }
                    }
                    mCallbacks.finishBroadcast();
                    }
                    return;
                }

                int vtstate;
                Phone.State s = mPhone.getState();
                if(DEBUG) Log.v(TAG, "video call is in "+ s +" state");
                if (s == Phone.State.IDLE) {
                    mConnection = null;
                    vtstate = VIDEOCALL_STATE_IDLE;
                    Intent intent = new Intent("CallNotifier.CallEnd");
                    mPhone.getContext().sendBroadcast(intent);
                    Log.e(TAG, "Notification home end call.");
                } else if (s == Phone.State.RINGING) {
                    vtstate = VIDEOCALL_STATE_RINGING;
                } else {
                    vtstate = VIDEOCALL_STATE_OFFHOOK;
                }

                synchronized (mLock) {        
                final int N = mCallbacks.beginBroadcast();
                for (int i = 0; i < N; i++) {
                    try {
                        mCallbacks.getBroadcastItem(i).onVTStateChanged(vtstate);
                    } catch (RemoteException e) {
                        // The RemoteCallbackList will take care of removing
                        // the dead object for us.
                        Log.e(TAG, "Error to broadcast phone stated changed.");
                    }
                }
                mCallbacks.finishBroadcast();
                }
                break;

            case VT_PHONE_NEW_RINGING_CONNECTION:
                AsyncResult ar = (AsyncResult)msg.obj;
                Connection c = (Connection)ar.result;
                Log.e(TAG, "Incoming call is ringing.");
                Log.d(TAG, " handleMessage (VT_PHONE_NEW_RINGING_CONNECTION)");
                if(!c.isVoice()){
                    //to do
                    //notify app new incoming video call
                    mConnection = c;
                    Intent i = new Intent("com.borqs.videocall.action.Call");
                    i.putExtra("call_number_key", c.getAddress());
                    mPhone.getContext().sendBroadcast(i);
                }
                break;
            case VT_PHONE_DISCONNECTED:
                AsyncResult ar1 = (AsyncResult)msg.obj;
                Connection c1 = (Connection)ar1.result;

                if(DEBUG) Log.v(TAG, "debug ------------------------------ c1:" + c1 + " mConnection:" + mConnection);

                if(c1 == null)
                {
                    if(DEBUG) Log.v(TAG, "debug ------------------------------ c1 is null");
                    if(mConnection != null)
                    {
                        sendVTCallResult(VIDEOCALL_RESULT_DISCONNECTED);
                        mConnection  = null;
                        break;
                    }
                    else
                    {
                        break;
                    }
                }
                else
                {
                    if(c1.isVoice())
                    {
                    	 //if(com.android.internal.telephony.RIL.DEBUG || Config.DEBUG) {
                        Log.w(TAG, "Warning: got disconnect info from non-video call");
                    	 //}
                        break;
                    }
                    Connection.DisconnectCause cause = c1.getDisconnectCause();
                    onDisconnected(cause);
                    mConnection = null;

                    break;
                }
            }
        }
    };
    
    private void onDisconnected(Connection.DisconnectCause cause){
        Log.e(TAG, "+++++++++++++++++++++++++++++++++++++++++ cause = " + cause);
        if(cause == Connection.DisconnectCause.INCOMING_MISSED)
           sendVTCallResult(VIDEOCALL_RESULT_DISCONNECTED_INCOMING_MISSED);

        else if(cause == Connection.DisconnectCause.NORMAL)
            sendVTCallResult(VIDEOCALL_RESULT_NORMAL);

       else if(cause == Connection.DisconnectCause.LOCAL_PHONE_OUT_OF_3G_Service)
            sendVTCallResult(VIDEOCALL_RESULT_DISCONNECTED_LCOAL_PHONE_OUT_OF_3G_SERVICE);

       else if(cause == Connection.DisconnectCause.USER_ALERTING_NO_ANSWER)
            sendVTCallResult(VIDEOCALL_RESULT_DISCONNECTED_NO_ANSWER);

        else if(cause == Connection.DisconnectCause.BUSY)
            sendVTCallResult(VIDEOCALL_RESULT_DISCONNECTED_BUSY);

        else if(cause == Connection.DisconnectCause.INVALID_NUMBER)
            sendVTCallResult(VIDEOCALL_RESULT_DISCONNECTED_INVALID_NUMBER);

        else if(cause == Connection.DisconnectCause.INCOMING_REJECTED)
            sendVTCallResult(VIDEOCALL_RESULT_DISCONNECTED_INCOMING_REJECTED);

        else if(cause == Connection.DisconnectCause.POWER_OFF)
            sendVTCallResult(VIDEOCALL_RESULT_DISCONNECTED_POWER_OFF);

        else if(cause == Connection.DisconnectCause.OUT_OF_SERVICE)
            sendVTCallResult(VIDEOCALL_RESULT_DISCONNECTED_OUT_OF_SERVICE);

        else if(cause == Connection.DisconnectCause.UNASSIGNED_NUMBER || cause == Connection.DisconnectCause.UNOBTAINABLE_NUMBER)
            sendVTCallResult(VIDEOCALL_RESULT_DISCONNECTED_UNASSIGNED_NUMBER);

        else if(cause == Connection.DisconnectCause.INCOMPATIBILITY)
            sendVTCallResult(VIDEOCALL_RESULT_FALLBACK_88);

        else if(cause == Connection.DisconnectCause.RESOURCE_UNAVAIL)
            sendVTCallResult(VIDEOCALL_RESULT_FALLBACK_47);

        else if(cause == Connection.DisconnectCause.BEARER_NOT_AUTHORIZATION)
            sendVTCallResult(VIDEOCALL_RESULT_FALLBACK_57);

        else if(cause == Connection.DisconnectCause.BEARER_NOT_AVAIL)
            sendVTCallResult(VIDEOCALL_RESULT_FALLBACK_58);

        else if(cause == Connection.DisconnectCause.CONGESTION)
            sendVTCallResult(VIDEOCALL_RESULT_NETWORK_CONGESTION);

        else if(cause == Connection.DisconnectCause.LOST_SIGNAL)
            sendVTCallResult(VIDEOCALL_RESULT_DISCONNECTED_LOST_SIGNAL);

        else if(cause == Connection.DisconnectCause.NUMBER_CHANGED)
            sendVTCallResult(VIDEOCALL_RESULT_DISCONNECTED_NUMBER_CHANGED);

        else if(cause == Connection.DisconnectCause.OUT_OF_3G_SERVICE)
            sendVTCallResult(VIDEOCALL_RESULT_DISCONNECTED_OUT_OF_SERVICE);

        else if(cause == Connection.DisconnectCause.NORMAL_UNSPECIFIED)
            sendVTCallResult(VIDEOCALL_RESULT_NORMAL_UNSPECIFIED);

        else if(cause == Connection.DisconnectCause.PROTOCOL_ERROR_UNSPECIFIED)
            sendVTCallResult(VIDEOCALL_RESULT_PROTOCOL_ERROR_UNSPECIFIED);

        else if(cause == Connection.DisconnectCause.BEARER_NOT_SUPPORTED_65)
            sendVTCallResult(VIDEOCALL_RESULT_BEARER_NOT_SUPPORTED_65);

        else if(cause == Connection.DisconnectCause.BEARER_NOT_SUPPORTED_79)
            sendVTCallResult(VIDEOCALL_RESULT_BEARER_NOT_SUPPORTED_79);

        else if(cause == Connection.DisconnectCause.NO_USER_RESPONDING)
            sendVTCallResult(VIDEOCALL_RESULT_NO_USER_RESPONDING);
        
        else
            sendVTCallResult(VIDEOCALL_RESULT_DISCONNECTED);
    }

    private void sendVTCallResult(int result){
        synchronized (mLock) {        
        final int N = mCallbacks.beginBroadcast();
        if(N == 0)
        {
            //N is 0 means, currently no video call app registered stopVTCall intent receiver.
            //Sometimes, when system is busy, error code returned from network may be handled
            //before com.borqs.videocall.action.Call is received by receiver, although intent com.borqs.videocall.action.Call
            //has been sent out. 
            Log.e(TAG, "sendVTCallResult, send intent com.borqs.videocall.action.StopVTCall");
            Intent intent = new Intent("com.borqs.videocall.action.StopVTCall", null);
            mPhone.getContext().sendBroadcast(intent);
        }

        for(int i = 0; i < N; i++)
        {
            try {
                mCallbacks.getBroadcastItem(i).onVTCallResult(result);
            }catch (Exception e){
                Log.e(TAG, "Error: when sendVTCallResult: "+result);
            }
        }
        mCallbacks.finishBroadcast();
        }
    }
    
    public boolean isVtIdle() {    	
    	Connection conn = mPhone.getForegroundCall().getEarliestConnection();
        if(conn != null && conn.isVoice()){        	
            if(DEBUG) Log.v(TAG,"not video call, return");
            return true;
        }
        Phone.State s = mPhone.getState();        
		return (mPhone.getState() == Phone.State.IDLE);
    }
}

