/* ------------------------------------------------------------------
 * Copyright (C) 2012 BORQS Software Solutions Pvt Ltd. 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */

package com.android.internal.telephony;

import android.text.TextUtils;
import android.util.Config;

import android.content.Context;
import android.net.Uri;
import android.provider.Contacts;
import android.provider.ContactsContract;
import android.provider.ContactsContract.PhoneLookup;

import com.android.internal.telephony.CallerInfoAsyncQuery.OnQueryCompleteListener;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import android.util.Log;
import android.os.SystemProperties;
import java.util.Vector;
/**
 * 
 * @author michelle
 *this class is just a wrapper class around CallerInfoAsyncQuery,it can automatically
 *query the contact from both sim card and phone;
 */
	public class ContactAsyncQuery {
	
	 private static final boolean DBG = false;
	 private static final String TAG = "ContactQuery";
	 private static final boolean QUERY_SIM_FIRST = false;
	
	 private Vector<ListenerInfo> mClientListeners = new Vector<ListenerInfo>();
	 //private ContactAsyncQuery mQuery;
	 private boolean mQueryOnAll = false;
	
	 //private int mToken;
	 private Context mQueryContext;
	 private String mNumber;
	 //the cookie is the one client used to query,maybe different with the one
	 //when it call addQueryListener
	 private Object mQueryCookie;
	
	 private class ListenerInfo {
	     public OnQueryCompleteListener mListener;
	     public Object mCookie;
	
	     public ListenerInfo(OnQueryCompleteListener listener,Object cookie) {
	         mListener = listener;
	         mCookie = cookie;
	     }
	
	     public String toString() {
	         return "listener:"+ mListener + " cookie:" + mCookie;
	     }
	 }
	
	 public ContactAsyncQuery() {
	
	 }
	 /**
	  * Factory method to start query with a number
	  */
	 public CallerInfoAsyncQuery startQuery(int token, Context context, String number, 
	         OnQueryCompleteListener listener, Object cookie) {
	
	     mQueryContext = context;
	     mNumber = number;
	     mQueryCookie = cookie;
	     addQueryListener(listener,cookie);
	    // if (QUERY_SIM_FIRST)  // always be false , so we comment this 
	    //     return queryInSIM(token,mQueryContext,mNumber,mListener,mQueryCookie);
	    // else 
	         return queryInPhone(token,mQueryContext,mNumber,mListener,mQueryCookie);
	 }
	
	 /**
	  * Method to add listeners to a currently running query
	  */
	 public void addQueryListener(OnQueryCompleteListener listener,Object cookie) {
	     if (listener == null || cookie == null)
	         return;
	
	     ListenerInfo info = new ListenerInfo(listener,cookie);
	     mClientListeners.add(info);
	 }
	
	 private CallerInfoAsyncQuery.OnQueryCompleteListener mListener = new 
	     CallerInfoAsyncQuery.OnQueryCompleteListener() {
	
	         public void onQueryComplete(int token, Object cookie, CallerInfo ci) {
	
	                    log("[onQueryComplete] get result:" + ci);
	                    if (!TextUtils.isEmpty(ci.name))
	                            log("succeed in match from:" + ci.contactRefUri
	                                            + " notify client listener");
	                    else
	                            log("failed in match from:" + ci.contactRefUri
	                                            + " notify client listener");
	
	                    // anyway notify user
	                    notifyClients(token, cookie, ci);
	              }
	     };
	/*
	 private CallerInfoAsyncQuery queryInSIM(int token, Context context, String number, 
	         OnQueryCompleteListener listener, Object cookie) {
	     //Uri contactRef = Uri.withAppendedPath(
	     //ContactsExt.SimCardContacts.CONTENT_URI_NUMBER_FILTER, 
	     //number);
	     return CallerInfoAsyncQuery.startQuery(
	             token, 
	             context, 
	             PhoneLookup.CONTENT_FILTER_URI,
	             number,
	             listener, 
	             cookie);
	+
	 }
	 /*
	 private CallerInfoAsyncQuery queryInUSIM(int token, Context context, String number, 
	         OnQueryCompleteListener listener, Object cookie) {
	     //Uri contactRef = Uri.withAppendedPath(
	     //ContactsExt.SimCardContacts.CONTENT_URI_NUMBER_FILTER, 
	     //number);
	     log("queryInUSIM, number is "+number);
	     return CallerInfoAsyncQuery.startQuery(
	             token, 
	             context, 
	             ContactsExt.USimCardContacts.CONTENT_URI_NUMBER_FILTER,
	             number,
	             listener, 
	             cookie);
	 }
	+*/
	 private CallerInfoAsyncQuery queryInPhone(int token, Context context, String number, 
	         OnQueryCompleteListener listener, Object cookie) {
	     //Uri contactRef = Uri.withAppendedPath(
	     //Contacts.Phones.CONTENT_FILTER_URL, 
	     //number);
	     return startQuery(
	             token,
	             context,
	            // Contacts.Phones.CONTENT_FILTER_URL,
	             //ContactsContract.PhoneLookup.CONTENT_FILTER_URI, 
	             number,
	             listener,
	             cookie);
	 }
	
	 private void notifyClients(int token,Object cookie,CallerInfo ci) {
	     for (ListenerInfo listenerInfo : mClientListeners) {
	         log("[notifyClients] notify:" + listenerInfo + "with callerinfo:" + ci);
	         listenerInfo.mListener.onQueryComplete(token, listenerInfo.mCookie, ci);
	     }
	 }
	
	 private void log (String msg) {
	     if(DBG) 
	         if(Config.DEBUG == true) Log.d(TAG,msg);
	 }
}
