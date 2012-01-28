/*
 * Copyright (C) 2011-2012, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Code Aurora nor
 *       the names of its contributors may be used to endorse or promote
 *       products derived from this software without specific prior written
 *       permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package com.android.server;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ILinkSocketMessageHandler;
import android.net.LinkCapabilities;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Package-private implementation of the ILinkManager interface.
 * Provides basic LinkManager functionality, tailored specifically for QoS APIs
 */
class LinkManager implements ILinkManager {
    private static final boolean DBG = true;
    private static final String TAG = "LinkManager";
    private Context mContext;
    private ConnectivityService mConnectivityService;
    private Map<Integer,LinkSocketInfo> mActiveLinks;
    private QosManager qosManager;
    private static int mSocketId = 0;

    /**
     * Unique integer generator. Under rare scenarios, if the first assigned socketId value
     * is not released, and the integer value roles over to max_value , it will result in
     * duplicate assignments of socket ids.
     */
    synchronized private static int getNextSocketId() {
        if (mSocketId == Integer.MAX_VALUE) mSocketId = 1;
        return mSocketId++;
    }

    /**
     * Storage for LinkSocket-related data.
     */
    private class LinkSocketInfo {
        public int id; // Socket ID
        public boolean isQosRole;
        public LinkCapabilities capabilities;

        public LinkSocketInfo( int id, LinkCapabilities capabilities) {
            this.id =id;
            this.capabilities = capabilities;
            parseRole();
        }

        private void parseRole() {
            String roleString = (capabilities == null) ?
                                "invalidString" : capabilities.get(LinkCapabilities.Key.RW_ROLE);
            if(roleString.startsWith("qos") || roleString.startsWith("QOS"))
                isQosRole=true;
            else
                isQosRole=false;
            return;
        }
    }

    /**
     *Construtor
     */
    public LinkManager(Context context, ConnectivityService cs, QosManager qosManager) {
        if (DBG) Log.v(TAG, "LinkManager constructor");
        mContext = context;
        mConnectivityService = cs;
        this.qosManager = qosManager;
        mActiveLinks = new ConcurrentHashMap<Integer, LinkSocketInfo>();
    }


    /**
     * LinkSocket APIs -Specifically implemented for QoS Roles only.
     */
    public int requestLink(LinkCapabilities capabilities, String remoteIPAddress, IBinder binder) {
        int id = getNextSocketId();
        if (DBG) Log.v(TAG, "requestLink capabilities: " + capabilities);
        LinkSocketInfo info = new LinkSocketInfo(id, capabilities);
        if(info.isQosRole) {
            mActiveLinks.put(new Integer(info.id), info);
            try {
                return qosManager.requestLink(id,capabilities,remoteIPAddress, binder);
            } catch (Exception e) {
                mActiveLinks.remove(new Integer(id));
                Log.e(TAG, "Qos request link failed due to exception");
                e.printStackTrace();
            }
        } else {
            Log.e(TAG, capabilities.get(LinkCapabilities.Key.RW_ROLE)+ "is unknown Qos Role");
        }
        return -1;
        }

    public void releaseLink(int id) {
        if (DBG) Log.v(TAG, "releaseLink id: " + id);
        LinkSocketInfo removed = null;
        try {
            removed = mActiveLinks.remove(new Integer(id));
            if ( removed == null) {
                Log.e(TAG, "Trying to release link on unregistered releaseLink id");
            } else {
                qosManager.releaseQos(id);
            }
        } catch (Exception e) {
            Log.e(TAG, "Got exception while releasing link");
            e.printStackTrace();
            return;
        }
        return;
    }

    public boolean requestQoS(int id, int localPort, String localAddress) {
        if (DBG) Log.v(TAG, "requestQoS id:" +id);
        try {
            if(mActiveLinks.containsKey(new Integer(id)) &&
                            mActiveLinks.get(new Integer(id)).isQosRole) {
                return qosManager.requestQoS(id, localPort, localAddress);
            } else {
                Log.e(TAG, "Trying to requestQos with invalid registration id");
            }
        } catch (Exception e) {
            Log.e(TAG, "Got exception while requesting qos registration");
            e.printStackTrace();
        }
        return false;
    }

    public boolean resumeQoS(int id) {
        if (DBG) Log.v(TAG, "resumeQoS id:" +id);
        try {
            if(mActiveLinks.containsKey(new Integer(id)) &&
                            mActiveLinks.get(new Integer(id)).isQosRole) {
                return qosManager.resumeQoS(id);
            } else {
                Log.e(TAG, "Trying to resumeQos with invalid registration id");
            }
        } catch (Exception e) {
            Log.e(TAG, "Got exception while resuming qos registration");
            e.printStackTrace();
        }
        return false;
    }

    public boolean suspendQoS(int id) {
        if (DBG) Log.v(TAG, "suspendQoS id:" +id);
        try {
            if(mActiveLinks.containsKey(new Integer(id)) &&
                        mActiveLinks.get(new Integer(id)).isQosRole) {
                return qosManager.suspendQoS(id);
            } else {
                Log.e(TAG, "Trying to suspendQos with invalid registration id");
            }
        } catch (Exception e) {
            Log.e(TAG, "Got exception while suspending qos registration");
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Minimum available forward link (download) bandwidth for the socket. This value is
     * in kilobits per second (kbps).
     */
    public String getMinAvailableForwardBandwidth(int id) {
       if (DBG) Log.v(TAG, "getMinAvailableForwardBandwidth id:" +id);
       try {
           if(mActiveLinks.containsKey(new Integer(id)) &&
                           mActiveLinks.get(new Integer(id)).isQosRole) {
               return qosManager.getMinAvailableForwardBandwidth(id);
           } else {
               Log.e(TAG, "Trying to getMinAvailableForwardBandwidth with invalid registration id");
           }
       } catch (Exception e) {
           Log.e(TAG, "Got exception while requesting bandwidth");
           e.printStackTrace();
       }
       return null;
    }

    /**
     * Maximum available forward link (download) bandwidth for the socket. This value is
     * in kilobits per second (kbps).
     */
    public String getMaxAvailableForwardBandwidth(int id) {
       if (DBG) Log.v(TAG, "getMaxAvailableForwardBandwidth id:" +id);
       try {
           if(mActiveLinks.containsKey(new Integer(id)) &&
                           mActiveLinks.get(new Integer(id)).isQosRole) {
               return qosManager.getMaxAvailableForwardBandwidth(id);
           } else {
               Log.e(TAG, "Trying to getMaxAvailableForwardBandwidth with invalid registration id");
           }
       } catch (Exception e) {
           Log.e(TAG, "Got exception while requesting bandwidth");
           e.printStackTrace();
       }
       return null;
    }

    /**
     * Minimum available reverse link (upload) bandwidth for the socket. This value is
     * in kilobits per second (kbps).
     */
    public String getMinAvailableReverseBandwidth(int id) {
       if (DBG) Log.v(TAG, "getMinAvailableReverseBandwidth id:" +id);
       try {
           if(mActiveLinks.containsKey(new Integer(id)) &&
                           mActiveLinks.get(new Integer(id)).isQosRole) {
               return qosManager.getMinAvailableReverseBandwidth(id);
           } else {
               Log.e(TAG, "Trying to getMinAvailableReverseBandwidth with invalid registration id");
           }
       } catch (Exception e) {
           Log.e(TAG, "Got exception while requesting bandwidth");
           e.printStackTrace();
       }
       return null;
    }

    /**
     * Maximum available reverse link (upload) bandwidth for the socket. This value is
     * in kilobits per second (kbps).
     */
    public String getMaxAvailableReverseBandwidth(int id) {
       if (DBG) Log.v(TAG, "getMaxAvailableReverseBandwidth id:" +id);
       try {
           if(mActiveLinks.containsKey(new Integer(id)) &&
                           mActiveLinks.get(new Integer(id)).isQosRole) {
               return qosManager.getMaxAvailableReverseBandwidth(id);
           } else {
               Log.e(TAG, "Trying to getMaxAvailableReverseBandwidth with invalid registration id");
           }
       } catch (Exception e) {
           Log.e(TAG, "Got exception while requesting bandwidth");
           e.printStackTrace();
       }
       return null;
    }

    /**
     * Current estimated downlink latency of the socket, in milliseconds.
     */
    public String getCurrentFwdLatency(int id) {
       if (DBG) Log.v(TAG, "getCurrentForwardLatency id:" +id);
       try {
           if(mActiveLinks.containsKey(new Integer(id)) &&
                           mActiveLinks.get(new Integer(id)).isQosRole) {
               return qosManager.getCurrentFwdLatency(new Integer(id));
           } else {
               Log.e(TAG, "Trying to getCurrentFwdLatency with invalid registration id");
           }
       } catch (Exception e) {
           Log.e(TAG, "Got exception while requesting latency");
           e.printStackTrace();
       }
       return null;
    }

    /**
     * Current estimated uplink latency of the socket, in milliseconds.
     */
    public String getCurrentRevLatency(int id) {
       if (DBG) Log.v(TAG, "getCurrentReverseLatency id:" +id);
       try {
           if(mActiveLinks.containsKey(new Integer(id)) &&
                           mActiveLinks.get(new Integer(id)).isQosRole) {
               return qosManager.getCurrentRevLatency(new Integer(id));
           } else {
               Log.e(TAG, "Trying to getCurrentReverseLatency with invalid registration id");
           }
       } catch (Exception e) {
           Log.e(TAG, "Got exception while requesting latency");
           e.printStackTrace();
       }
       return null;
    }

    /**
     * An integer representing the network type.
     * @param id LinkSocket ID
     * @see ConnectivityManager
     */
    public int getNetworkType(int id) {
        int netType = -1;
        LinkSocketInfo info = mActiveLinks.get(id);
        if (info != null) {
            if (DBG) Log.d(TAG, "Not supported method");
        } else {
            Log.e(TAG, "Trying to getNetworkType with invalid registration id: " + id);
        }
        return netType;
    }

    /**
     * Current Quality of Service state of the socket
     */
    public String getQosState(int id) {
       if (DBG) Log.v(TAG, "getQosState id:" +id);
       try {
           if(mActiveLinks.containsKey(new Integer(id)) &&
                           mActiveLinks.get(new Integer(id)).isQosRole) {
               return qosManager.getQosState(id);
           } else {
               Log.e(TAG, "Trying to getQosState with invalid registration id");
           }
       } catch (Exception e) {
           Log.e(TAG, "Got exception while requesting latency");
           e.printStackTrace();
       }
       return LinkCapabilities.QosStatus.QOS_STATE_INACTIVE;
    }

    /**
     * Deregister Qos Socket from link manager
     */
    public boolean removeQosRegistration(int id) {
        if (DBG) Log.v(TAG, "removeQosRegistration id:" +id);
        if(mActiveLinks.containsKey(new Integer(id)) && mActiveLinks.get(new Integer(id)).isQosRole)
        {
            try {
                mActiveLinks.remove(new Integer(id));
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Got exception while removing registration");
                e.printStackTrace();
            }
        } else {
            Log.e(TAG, "Trying to removeQosRegistration with invalid registration id");
        }
        return false;
    }

}
