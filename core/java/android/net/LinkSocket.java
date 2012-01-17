/*
 * Copyright (C) 2010 The Android Open Source Project
 * Copyright (C) 2010-2012, Code Aurora Forum. All rights reserved.
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

package android.net;

import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.net.LinkSocketNotifier;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/*
 * TODO: Capability tracking features are missing.
 */

/**
 * An extension of Java sockets. It provides a client-side TCP socket with a way to manage
 * wireless connections.
 * <p>
 * Example Code: <br>
 * <code>
 * LinkSocket sock = new LinkSocket(instanceOfLinkSocketNotifier); <br>
 * LinkCapabilities capabilities = LinkCapabilities.createNeedsMap(LinkCapabilities.Role.WEB_BROSWER); <br>
 * capabilities.put(LinkCapabilities.Key.RW_DESIRED_FWD_BW, "1000"); // set 1Mbps down <br>
 * sock.setNeededCapabilities(capabilities); // set the capabilities <br>
 * sock.connect("www.google.com", 80); // Google's HTTP server <br>
 * sock.close(); <br>
 * </code>
 *
 * @see LinkSocketNotifier
 * @hide
 */
public class LinkSocket extends Socket {
    // Public error codes
    /** Link Failure: Unknown failure */
    public static final int ERROR_UNKNOWN = 0;
    /** Link Failure: All networks are down */
    public static final int ERROR_ALL_NETWORKS_DOWN = 1;
    /** Link Failure: Networks are available, but none meet requirements */
    public static final int ERROR_NETWORKS_FAIL_REQUIREMENTS = 2;

    // Debugging
    private final static String TAG = "LinkSocket";
    private final static boolean DBG = true;

    // Flags
    private boolean isWaitingForResponse = false;  // waiting for ConnectivityServices?
    private final static int NOT_SET = -1;

    // Members
    private IConnectivityManager mService = null;        // handle to ConnectivityService
    private LinkCapabilities mNeededCapabilities = null; // requested capabilities
    private LinkProperties mProperties = null;           // socket properties
    private LinkSocketNotifier mNotifier = null;         // callback to application
    private String mHostname = null;                     // destination hostname
    private int mId = NOT_SET;                           // unique ID

    private MessageHandler mMsgHandler = new MessageHandler(); // messages from ConnectivityServices
    private Handler mHandler = null;                           // message handler
    private MessageLoop mMsgLoop = new MessageLoop();          // message loop

    /**
     * Default constructor
     */
    public LinkSocket() {
        super();
        if (DBG) Log.v(TAG, "LinkSocket() EX");
        constructor(null);
    }

    /**
     * Creates a new unconnected socket.
     *
     * @param notifier a reference to a class that implements
     *            {@code LinkSocketNotifier}
     */
    public LinkSocket(LinkSocketNotifier notifier) {
        super();
        if (DBG) Log.v(TAG, "LinkSocket(notifier) EX");
        constructor(notifier);
    }

    /**
     * Creates a new unconnected socket using the given proxy type.
     *
     * @param notifier a reference to a class that implements
     *            {@code LinkSocketNotifier}
     * @param proxy the specified proxy for this socket
     * @throws IllegalArgumentException if the argument proxy is null or of an
     *             invalid type.
     * @throws SecurityException if a security manager exists and it denies the
     *             permission to connect to the given proxy.
     */
    public LinkSocket(LinkSocketNotifier notifier, Proxy proxy) {
        super(proxy);
        if (DBG) Log.v(TAG, "LinkSocket(notifier, proxy) EX");
        constructor(notifier);
    }

    /**
     * Creates a new unconnected socket with the same notifier and needs map as
     * the source LinkSocket.
     *
     * @param source
     */
    public LinkSocket(LinkSocket source) {
        super();
        if (DBG) Log.v(TAG, "LinkSocket(source) EX");
        constructor(source.mNotifier);
        setNeededCapabilities(source.getNeededCapabilities());
    }

    /**
     * @return the {@code LinkProperties} for the socket
     */
    public LinkProperties getLinkProperties() {
        if (DBG) Log.v(TAG, "LinkProperties() EX");
        return new LinkProperties(mProperties);
    }

    /**
     * Set the {@code LinkCapabilies} needed for this socket.  If the socket is already connected
     * or is a duplicate socket the request is ignored and {@code false} will
     * be returned. A needs map can be created via the {@code createNeedsMap} static
     * method.
     * @param needs the needs of the socket
     * @return {@code true} if needs are successfully set, {@code false} otherwise
     */
    public boolean setNeededCapabilities(LinkCapabilities needs) {
        if (DBG) Log.v(TAG, "setNeeds() EX");

        // if mProperties is set, it is too late to set needs
        if (mProperties != null) return false;

        mNeededCapabilities = needs;
        mNeededCapabilities.put(LinkCapabilities.Key.RO_TRANSPORT_PROTO_TYPE,"tcp");
        if (mNotifier == null) {
            // if mNotifier is null, then we cannot send notifications so we
            // might as well disable them
            mNeededCapabilities.put(LinkCapabilities.Key.RW_DISABLE_NOTIFICATIONS, "true");
        }
        return true;
    }

    /**
     * @return the LinkCapabilites set by setNeededCapabilities, empty if none has been set
     */
    public LinkCapabilities getNeededCapabilities() {
        if (DBG) Log.v(TAG, "getNeeds() EX");
        return new LinkCapabilities(mNeededCapabilities);
    }

    /**
     * @return a LinkCapabilities object containing the READ ONLY capabilities
     *         of the LinkSocket
     */
    public LinkCapabilities getCapabilities() {
        if (DBG) Log.v(TAG, "getCapabilities() EX");
        LinkCapabilities cap = null;
        try {
            // place all capabilities in an int array
            final int[] keys = new int[] {
                    LinkCapabilities.Key.RO_MIN_AVAILABLE_FWD_BW,
                    LinkCapabilities.Key.RO_MAX_AVAILABLE_FWD_BW,
                    LinkCapabilities.Key.RO_MIN_AVAILABLE_REV_BW,
                    LinkCapabilities.Key.RO_MAX_AVAILABLE_REV_BW,
                    LinkCapabilities.Key.RO_CURRENT_FWD_LATENCY,
                    LinkCapabilities.Key.RO_CURRENT_REV_LATENCY,
                    LinkCapabilities.Key.RO_BOUND_INTERFACE,
                    LinkCapabilities.Key.RO_NETWORK_TYPE,
                    LinkCapabilities.Key.RO_PHYSICAL_INTERFACE,
                    LinkCapabilities.Key.RO_CARRIER_ROLE,
                    LinkCapabilities.Key.RO_QOS_STATE
            };
            cap = mService.requestCapabilities(mId, keys);
        } catch (RemoteException ex) {
            Log.d(TAG, "LinkSocket was unable to get capabilities from ConnectivityService");
            // should not return a null reference
            cap = new LinkCapabilities();
        }
        return cap;
    }

    /**
     * Returns this LinkSockets set of capabilities, filtered according to
     * the given {@code Set}.  Capabilities in the Set but not available from
     * the link will not be reported in the results.  Capabilities of the link
     * but not listed in the Set will also not be reported in the results.
     *
     * @param capabilities {@code Set} of capabilities requested
     * @return the filtered {@code LinkCapabilities} of this LinkSocket, may be empty
     */
    public LinkCapabilities getCapabilities(Set<Integer> capability_keys) {
        if (DBG) Log.v(TAG, "getCapabilities(capabilities) EX");
        LinkCapabilities cap = null;
        int[] keys = new int[capability_keys.size()];

        // convert capability_keys into an int array
        Iterator<Integer> it = capability_keys.iterator();
        for (int i = 0; it.hasNext(); i++) keys[i] = it.next();

        // send the request
        try {
            cap = mService.requestCapabilities(mId, keys);
        } catch (RemoteException ex) {
            Log.d(TAG, "LinkSocket was unable to get capabilities from ConnectivityService");
        }
        return cap;
    }

    /**
     * Provide the set of capabilities the application is interested in tracking
     * for this LinkSocket.
     *
     * @param capabilities a {@code Set} of capabilities to track
     */
    public void setTrackedCapabilities(Set<Integer> capabilities) {
        if (DBG) Log.v(TAG, "setTrackedCapabilities(capabilities) EX");
        // This feature is not implemented yet.
    }

    /**
     * @return the {@code LinkCapabilities} that are tracked, empty if none has been set.
     */
    public Set<Integer> getTrackedCapabilities() {
        if (DBG) Log.v(TAG, "getTrackedCapabilities() EX");
        // This feature is not implemented yet.
        return new HashSet<Integer>();
    }

    /**
     * Connects this socket to the given remote host address and port specified
     * by dstName and dstPort.
     *
     * @param dstName the address of the remote host to connect to
     * @param dstPort the port to connect to on the remote host
     * @param timeout the timeout value in milliseconds or 0 for infinite timeout
     * @throws UnknownHostException if the given dstName is invalid
     * @throws IOException if the socket is already connected or an error occurs
     *                     while connecting
     * @throws SocketTimeoutException if the timeout fires
     */
    public void connect(String dstName, int dstPort, int timeout) throws UnknownHostException,
            IOException, SocketTimeoutException {
        if (DBG) Log.v(TAG, "connect(dstName, dstPort, timeout) EX");

        if (dstName == null) {
            throw new UnknownHostException("destination address is not set");
        }
        if (dstPort < 0) {
            throw new UnknownHostException("destination port is not set");
        }

        /*
         * Currently RW_ALLOWED_NETWORKS and RW_PROHIBITED_NETWORKS are not
         * implemented. If either of these keys are in use, throw an
         * IOException.
         *
         * TODO: implement RW_ALLOWED_NETWORKS and RW_PROHIBITED_NETWORKS in
         * next release.
         */
        if (mNeededCapabilities.containsKey(LinkCapabilities.Key.RW_ALLOWED_NETWORKS)
                || mNeededCapabilities.containsKey(LinkCapabilities.Key.RW_PROHIBITED_NETWORKS)) {
            throw new IOException("RW_ALLOWED_NETWORKS and RW_PROHIBITED_NETWORKS" +
                    " are not supported at this time");
        }

        // save the current time for timeouts
        Calendar start = Calendar.getInstance();

        // make sure message processing thread is ready before we try to connect
        while (mHandler == null) Thread.yield();

        /*
         * Steps:
         * 1. save the destination address for future use
         * 2. request a network link
         * 3. figure out which address to bind to
         * 4. bind to the given address
         * 5. connect
         */

        // save the addresses for future use
        mHostname = dstName;

        // get need a network connection
        synchronized (this) {
            if (mId == NOT_SET) {
                try {
                    isWaitingForResponse = true;
                    if (DBG) Log.v(TAG, "sending requestLink()");
                    mId = mService.requestLink(mNeededCapabilities, mHostname, mMsgHandler);
                    if (DBG) Log.v(TAG, "Blocking: waiting for response");
                    while (isWaitingForResponse) {
                        if (timeout == 0) {
                            wait();
                        } else {
                            wait(timeout);
                            /*
                             * if 'timeout' time passes, wait() will stop
                             * blocking just as if it was interrupted or
                             * received a notification, and the while loop will
                             * begin again. We need track time, and throw and
                             * exception if appropriate. This also reduces the
                             * amount of time socket.connect() will wait based
                             * on how long it took to acquire the link.
                             */
                            timeout -= (int) (Calendar.getInstance().getTimeInMillis()
                                    - start.getTimeInMillis());
                            if (timeout <= 0) {
                                releaseLink();
                                throw new SocketTimeoutException(
                                        "Socket timed out during link acquisition.");
                            }
                        }
                        if (DBG) Log.v(TAG, "Blocking: received notification or timeout");
                    }
                    if (DBG) Log.v(TAG, "Blocking: done");
                } catch (InterruptedException ex) {
                    Log.d(TAG, "ConnectivityService failed to respond to request.");
                    releaseLink();
                } catch (RemoteException ex) {
                    Log.w(TAG, "LinkSocket was unable to acquire a new network link. " + ex);
                    releaseLink();
                }
            }
        }

        // if mProperties is still null, we couldn't get a network
        if (mProperties == null) {
            releaseLink();
            throw new IOException("Unable to find a network that meets requirements.");
        }

        // get first address from mProperties
        Collection<InetAddress> addresses = mProperties.getAddresses();
        if (addresses == null || addresses.isEmpty()) {
            releaseLink();
            throw new IOException("No valid address to bind to");
        }
        InetAddress bindAddress = null;
        for (InetAddress address : addresses) {
            bindAddress = address;
            break;
        }

        // bind and connect
        if (DBG) Log.v(TAG, "attempting to bind: " + bindAddress);
        super.bind(new InetSocketAddress(bindAddress, 0));
        if (DBG) Log.v(TAG, "bind successful: " + getLocalSocketAddress());
        if (DBG) Log.v(TAG, "attempting to connect: " + mHostname + ":" + super.getPort());
        //request Qos for all sockets regardless of role type, service will
        //handle this request appropriately
        try {
            mService.requestQoS(mId, super.getLocalPort(), bindAddress.getHostAddress());
        } catch (RemoteException re) {
            if (DBG) Log.v(TAG,"requestQoS experienced remote exception: " + re);
        }
        super.connect(new InetSocketAddress(dstName, dstPort), timeout);
        if (DBG) Log.v(TAG, "connect successful: " + getInetAddress() + ":" + super.getPort());
    }

    /**
     * Connects this socket to the given remote host address and port specified
     * by dstName and dstPort.
     *
     * @param dstName the address of the remote host to connect to
     * @param dstPort the port to connect to on the remote host
     * @throws UnknownHostException if the given dstName is invalid
     * @throws IOException if the socket is already connected or an error occurs
     *                     while connecting
     */
    public void connect(String dstName, int dstPort) throws UnknownHostException, IOException {
        if (DBG) Log.v(TAG, "connect(dstName, dstPort) EX");
        connect(dstName, dstPort, 0);
    }

    /**
     * Connects this socket to the same remote host address and port as the
     * source LinkSocket.
     *
     * @param source the LinkSocket from which to get the destination
     * @param timeout the timeout value in milliseconds or 0 for infinite
     *            timeout
     * @throws UnknownHostException if the given dstName is invalid
     * @throws IOException if the socket is already connected or an error occurs
     *             while connecting
     */
    public void connect(LinkSocket source, int timeout) throws UnknownHostException, IOException {
        if (DBG) Log.v(TAG, "connect(source) EX");
        connect(source.getHostname(), source.getPort(), timeout);
    }

    /**
     * Connects this socket to the same remote host address and port as the
     * source LinkSocket.
     *
     * @param source the LinkSocket from which to get the destination
     * @throws UnknownHostException if the given dstName is invalid
     * @throws IOException if the socket is already connected or an error occurs
     *             while connecting
     */
    public void connect(LinkSocket source) throws UnknownHostException, IOException {
        if (DBG) Log.v(TAG, "connect(source) EX");
        connect(source.getHostname(), source.getPort(), 0);
    }

    /**
     * Connects this socket to the given remote host address and port specified
     * by the SocketAddress with the specified timeout.
     *
     * @deprecated Use {@link LinkSocket#connect(String, int, int)} instead.
     *             Using this method will result in an IllegalArgumentException.
     * @param remoteAddr the address and port of the remote host to connect to
     * @param timeout the timeout value in milliseconds or 0 for an infinite timeout
     * @throws IllegalArgumentException always
     */
    @Override
    @Deprecated
    public void connect(SocketAddress remoteAddr, int timeout) throws IOException,
    SocketTimeoutException, IllegalArgumentException {
        if (DBG) Log.v(TAG, "connect(remoteAddr, timeout) EX DEPRECATED");
        throw new IllegalArgumentException("connect(remoteAddr, timeout) is deprecated");
    }

    /**
     * Connects this socket to the given remote host address and port specified
     * by the SocketAddress. Network selection happens during connect and may
     * take 30 seconds.
     *
     * @deprecated Use {@link LinkSocket#connect(String, int)} instead. Using
     *             this method will result in an IllegalArgumentException.
     * @param remoteAddr the address and port of the remote host to connect to.
     * @throws IllegalArgumentException always
     */
    @Override
    @Deprecated
    public void connect(SocketAddress remoteAddr) throws IOException, IllegalArgumentException {
        if (DBG) Log.v(TAG, "connect(remoteAddr) EX DEPRECATED");
        throw new IllegalArgumentException("connect(remoteAddr) is deprecated");
    }

    /**
     * Closes the socket. It is not possible to reconnect or re-bind to this
     * socket thereafter which means a new socket instance has to be created.
     *
     * @throws IOException if an error occurs while closing the socket
     */
    @Override
    public synchronized void close() throws IOException {
        if (DBG) Log.v(TAG, "close() EX");
        mMsgLoop.quit(); // disable callbacks
        releaseLink();
        super.close();
    }

    /**
     * @deprecated LinkSocket will automatically pick the optimum interface to
     *             bind to
     * @param localAddr the specific address and port on the local machine to
     *            bind to
     * @throws IOException always as this method is deprecated for LinkSocket
     */
    @Override
    @Deprecated
    public void bind(SocketAddress localAddr) throws UnsupportedOperationException {
        if (DBG) Log.v(TAG, "bind(localAddr) EX throws Exception");
        throw new UnsupportedOperationException("bind is deprecated for LinkSocket");
    }

    /**
     * Gets the host name of the target host this socket is connected to.
     *
     * @return the host name of the connected target host, or {@code null} if
     *         this socket is not yet connected.
     */
    public String getHostname() {
        return mHostname;
    }

     /**
     * Gets the port number of the target host this socket is connected to.
     *
     * @return the port number of the connected target host or 0 if this socket
     *         is not yet connected.
     */
    public int getPort() {
        return super.getPort();
    }

    @Override
    public String toString() {
        if (this.isConnected() == false) {
            if (mId == NOT_SET) {
                return "LinkSocket id:none unconnected";
            } else {
                return "LinkSocket id:" + mId + " unconnected";
            }
        } else {
            return "LinkSocket id:" + mId + " addr:" + super.getInetAddress()
                + " port:" + super.getPort() + " local_port:" + super.getLocalPort();
        }
    }

    /*
     * Handles callbacks from ConnectivityServices
     */
    private class MessageHandler extends ILinkSocketMessageHandler.Stub {

        // Flags
        private final static int ON_LINK_AVAIL = 0;
        private final static int ON_GET_LINK_FAILURE = 1;
        private final static int ON_BETTER_LINK_AVAIL = 2;
        private final static int ON_LINK_LOST = 3;
        private final static int ON_CAPABILITIES_CHANGED = 4;

        public void onLinkAvail(LinkProperties properties) {
            if (DBG) Log.v(TAG, "CallbackHandler.onLinkAvail(properties) EX");
            mHandler.sendMessage(mHandler.obtainMessage(ON_LINK_AVAIL, properties));
        }

        public void onGetLinkFailure(int reason) {
            if (DBG) Log.v(TAG, "CallbackHandler.onGetLinkFailure(reason) EX");
            mHandler.sendMessage(mHandler.obtainMessage(ON_GET_LINK_FAILURE, reason));
        }

        public void onBetterLinkAvail() {
            if (DBG) Log.v(TAG, "CallbackHandler.onBetterLinkAvail(properties) EX");
            mHandler.sendMessage(mHandler.obtainMessage(ON_BETTER_LINK_AVAIL));
        }

        public void onLinkLost() {
            if (DBG) Log.v(TAG, "CallbackHandler.onLinkLost() EX");
            mHandler.sendMessage(mHandler.obtainMessage(ON_LINK_LOST));
        }

        public void onCapabilitiesChanged(LinkCapabilities changedCapabilities) {
            if (DBG) Log.v(TAG, "CallbackHandler.onCapabilitiesChanged(changedCapabilities) EX");
            mHandler.sendMessage(
                    mHandler.obtainMessage(ON_CAPABILITIES_CHANGED, changedCapabilities));
        }
    }

    private void constructor(LinkSocketNotifier notifier) {
        if (DBG) Log.v(TAG, "constructor(notifier, proxy) EX");

        mMsgLoop.start(); // start up message processing thread

        mNotifier = notifier;
        setNeededCapabilities(LinkCapabilities.createNeedsMap(LinkCapabilities.Role.DEFAULT));
        mNeededCapabilities.put(LinkCapabilities.Key.RO_TRANSPORT_PROTO_TYPE,"tcp");

        IBinder binder = ServiceManager.getService(Context.CONNECTIVITY_SERVICE);
        mService = IConnectivityManager.Stub.asInterface(binder);
    }

    private void releaseLink() {
        if (mId == NOT_SET) return; // nothing to release
        if (DBG) Log.v(TAG, "releasing link");
        try {
            mService.releaseLink(mId);
        } catch (RemoteException ex) {
            Log.w(TAG, "LinkSocket was unable relinquish the current network link. " + ex);
        }
        mId = NOT_SET;
    }

    /*
     * Handle messages from CallbackHandler
     */
    private class MessageLoop extends Thread {
        public void run() {
            Looper.prepare();
            mHandler = new Handler() {
                public void handleMessage(Message msg) {
                    if (DBG) Log.v(TAG, "handleMessage(msg) EX");
                    switch (msg.what) {
                        case MessageHandler.ON_LINK_AVAIL:
                            callbackOnLinkAvail((LinkProperties) msg.obj);
                            break;
                        case MessageHandler.ON_GET_LINK_FAILURE:
                            callbackOnGetLinkFailure((Integer) msg.obj);
                            break;
                        case MessageHandler.ON_BETTER_LINK_AVAIL:
                            callbackOnBetterLinkAvail();
                            break;
                        case MessageHandler.ON_LINK_LOST:
                            callbackOnLinkLost();
                            break;
                        case MessageHandler.ON_CAPABILITIES_CHANGED:
                            callbackOnCapabilitiesChanged((LinkCapabilities) msg.obj);
                            break;
                        default:
                            Log.d(TAG, "LinkSocket received an unknown message type");
                    }
                }
            };
            Looper.loop();
        }

        public void quit() {
            if (mHandler != null) mHandler.getLooper().quit();
        }
    }

    private void callbackOnLinkAvail(LinkProperties properties) {
        if (DBG) Log.v(TAG, "onLinkAvail(properties) EX");

        if (mProperties != null) {
            /*
             * this is an unexpected onLinkAvail(), it probably should be a
             * onBetterLinkAvail() so we're going to call that
             *
             * TODO: remove once CND is updated to the new architecture
             */

            callbackOnBetterLinkAvail();
            return;
        }

        // ConnectivityService found a link!
        mProperties = properties;

        // stop blocking
        isWaitingForResponse = false;
        synchronized (this) {
            notifyAll();
        }
    }

    private void callbackOnGetLinkFailure(int reason) {
        if (DBG) Log.v(TAG, "onGetLinkFailure(reason) EX");

        if (mProperties != null) {
            /*
             * this is an unexpected onGetLinkFailure, it probably should just
             * be ignored
             *
             * TODO: remove once CND is updated to the new architecture
             */
            return;
        }

        // implied releaseLink()
        mId = NOT_SET;

        // stop blocking
        isWaitingForResponse = false;
        synchronized (this) {
            notifyAll();
        }
    }

    private void callbackOnBetterLinkAvail() {
        if (DBG) Log.v(TAG, "onBetterLinkAvail() EX");
        if (mNotifier == null) return;

        // are notifications disabled?
        String notify = mNeededCapabilities.get(LinkCapabilities.Key.RW_DISABLE_NOTIFICATIONS);
        if (notify != null && notify.equalsIgnoreCase("true")) return;

        mNotifier.onBetterLinkAvailable(this);
    }

    private void callbackOnLinkLost() {
        if (DBG) Log.v(TAG, "onLinkLost() EX");
        if (mNotifier == null) return;

        // are notifications disabled?
        String notify = mNeededCapabilities.get(LinkCapabilities.Key.RW_DISABLE_NOTIFICATIONS);
        if (notify != null && notify.equalsIgnoreCase("true")) return;

        mNotifier.onLinkLost(this);
    }

    private void callbackOnCapabilitiesChanged(LinkCapabilities changedCapabilities) {
        if (DBG) Log.v(TAG, "onCapabilitiesChanged(changedCapabilities) EX");
        if (mNotifier == null) return;

        // are notifications disabled?
        String notify = mNeededCapabilities.get(LinkCapabilities.Key.RW_DISABLE_NOTIFICATIONS);
        if (notify != null && notify.equalsIgnoreCase("true")) return;

        mNotifier.onCapabilitiesChanged(this, changedCapabilities);
    }

    /**
     * Request that a new LinkSocket be created using a different radio
     * (such as WiFi or 3G) than the current LinkSocket.  If a different
     * radio is available a call back will be made via {@code onBetterLinkAvail}.
     * If unable to find a better radio, application will be notified via
     * {@code onNewLinkUnavailable}
     * @see LinkSocketNotifier#onBetterLinkAvailable(LinkSocket, LinkSocket)
     * @param linkRequestReason reason for requesting a new link.
     */
    public void requestNewLink(LinkRequestReason linkRequestReason) {
        if (DBG) log("requestNewLink(linkRequestReason) EX");
    }

    /**
     * Reason codes an application can specify when requesting for a new link.
     * TODO: need better documentation
     */
    public static final class LinkRequestReason {
        /** No constructor */
        private LinkRequestReason() {}

        /** This link is working properly */
        public static final int LINK_PROBLEM_NONE = 0;
        /** This link has an unknown issue */
        public static final int LINK_PROBLEM_UNKNOWN = 1;
    }

    /**
     * Debug logging
     */
    protected static void log(String s) {
        Log.d(TAG, s);
    }
}
