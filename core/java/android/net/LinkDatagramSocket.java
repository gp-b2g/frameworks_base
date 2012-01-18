/* Copyright (c) 2011-2012, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package android.net;

import android.content.Context;
import android.net.LinkCapabilities.Role;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.DatagramSocketImplFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * An extension of Java datagram sockets. It provides datagram socket for both endpoints of a connection
 * with a way to manage wireless connections and needs for socket[s].
 * <p>
 * Example Code: <br>
 * <code>
 * LinkDatagramSocket sock = new LinkDatagramSocket(instanceOfLinkSocketNotifier); <br>
 * LinkCapabilities needs = LinkCapabilities.createNeedsMap(LinkCapabilities.Role.CONVERSATIONAL);
 * <p>
 * //create desired needs <br>
 * needs.put(LinkCapabilities.Key.RW_DESIRED_FWD_BW, "1000"); <br>
 * needs.put (LinkCapabilities.Key.RW_DEST_IP_ADDRESSES, "192.168.20.85" );<br>
 * needs.put (LinkCapabilities.Key.RW_DEST_PORTS, "16884, 16886" ); <p>
 * sock.setNeededCapabilities(needs); // set the capabilities <br>
 * DatagramPacket pkt = new DatagramPacket(byte[] data, int length); <br>
 * sock.send(pkt); <br>
 * sock.close(); <br>
 * </code>
 *
 * @see LinkCapabilities
 * @see LinkSocketNotifier
 * @see DatagramSocket
 * @hide
 */
public class LinkDatagramSocket extends DatagramSocket {
    // Public error codes
    /** Link Failure: Unknown failure */
    public static final int ERROR_UNKNOWN = 0;
    /** Link Failure: All networks are down */
    public static final int ERROR_ALL_NETWORKS_DOWN = 1;
    /** Link Failure: Networks are available, but none meet requirements */
    public static final int ERROR_NETWORKS_FAIL_REQUIREMENTS = 2;


    // Debugging
    private final static String TAG = "LinkDatagramSocket";
    private final static boolean DBG = true;

    // Flags
    private boolean isWaitingForResponse = false;  // waiting for ConnectivityServices?
    private final static int NOT_SET = -1;

    // Members
    protected int mNetSelectTimeout = 30000;             // network selection timeout
    private IConnectivityManager mService = null;        // handle to ConnectivityService
    private LinkCapabilities mNeededCapabilities = null; // requested capabilities
    private LinkProperties mProperties = null;           // socket properties
    private LinkSocketNotifier mNotifier = null;         // callback to application
    private String mHostname = null;                     // destination hostname
    private int mBindPort = 0;                           // local bind port
    private int mId = NOT_SET;                           // unique ID

    private MessageHandler mMsgHandler = new MessageHandler(); // messages from ConnectivityServices
    private Handler mHandler = null;                           // message handler
    private MessageLoop mMsgLoop = new MessageLoop();          // message loop

    /**
     * Default Constructor
     *
     * @throws SocketException
     *             if an error occurs while creating or binding the socket.
     */
    public LinkDatagramSocket() throws SocketException {
        super(false);
        if (DBG) Log.v(TAG, "LinkDatagramSocket() EX");
        constructor(null, 0);
    }

    /**
     * Creates a new unconnected link datagram socket.
     *
     * @param notifier a reference to a class that implements
     *            {@code LinkSocketNotifier}
     */
    public LinkDatagramSocket(LinkSocketNotifier notifier) throws SocketException {
        super(false);
        if (DBG) Log.v(TAG, "LinkDatagramSocket(notifier) EX");
        constructor(notifier, 0);
    }

    /**
     * Constructs a link datagram socket which is bound to the specific port
     * {@code aPort} on the localhost. Valid values for {@code aPort} are
     * between 0 and 65535 inclusive. The bind will be delayed until link
     * selection.
     *
     * @param aPort
     *            the port to bind on the localhost.
     * @throws SocketException
     *             if an error occurs while creating or binding the socket.
     */
    public LinkDatagramSocket(int aPort) throws SocketException {
        super(false);
        if (DBG) Log.v(TAG, "LinkDatagramSocket(aPort) EX");
        constructor(null, aPort);
    }

    /**
     * Constructs a link datagram socket which is bound to the specific port
     * {@code aPort} on the localhost. Valid values for {@code aPort} are
     * between 0 and 65535 inclusive. The bind will be delayed until link
     * selection.
     *
     * @param aPort
     *            the port to bind on the localhost.
     *
     * @param notifier a reference to a class that implements
     *            {@code LinkSocketNotifier}
     *
     * @throws SocketException
     *             if an error occurs while creating or binding the socket.
     */
    public LinkDatagramSocket(int aPort, LinkSocketNotifier notifier) throws SocketException {
        super(false);
        if (DBG) Log.v(TAG, "LinkDatagramSocket(aPort, notifier) EX");
        constructor(notifier, aPort);
    }

    /**
     * Creates a new unconnected socket with the same notifier and needs map as
     * the source LinkDatagramSocket.
     *
     * @param source
     */
    public LinkDatagramSocket(LinkDatagramSocket source) throws SocketException {
        super(false);
        if (DBG) Log.v(TAG, "LinkDatagramSocket(source) EX");
        constructor(source.mNotifier, 0);
        setNeededCapabilities(source.getNeededCapabilities());
    }

    /**
     * Returns properties of the link this socket is bound to.
     *
     * @return the {@code LinkProperties} for the socket
     */
    public LinkProperties getLinkProperties() {
        if (DBG) Log.v(TAG, "getLinkProperties() EX");
        return new LinkProperties(mProperties);
    }

    /**
     * Set the {@code LinkCapabilities} needed for this socket. If the socket is
     * already connected the request is ignored and {@code false} will be
     * returned. A needs map can be created via the {@code createNeedsMap} static
     * method.
     *
     * @param needs the needs of the socket
     * @return {@code true} if needs are successfully set, {@code false}
     *         otherwise
     */
    public boolean setNeededCapabilities(LinkCapabilities needs) {
        if (DBG) Log.v(TAG, "setNeededCapabilities(needs) EX");

        // if mProperties is set, it is too late to set needs
        if (mProperties != null) return false;

        mNeededCapabilities = needs;
        mNeededCapabilities.put(LinkCapabilities.Key.RO_TRANSPORT_PROTO_TYPE,"udp");
        if (mNotifier == null) {
            // if mNotifier is null, then we cannot send notifications so we
            // might as well disable them
            mNeededCapabilities.put(LinkCapabilities.Key.RW_DISABLE_NOTIFICATIONS, "true");
        }
        return true;
    }

    /**
     * @return the LinkCapabilites set by setNeededCapabilities, empty if none
     *         has been set
     */
    public LinkCapabilities getNeededCapabilities() {
        if (DBG) Log.v(TAG, "getNeededCapabilities() EX");
        return new LinkCapabilities(mNeededCapabilities);
    }

    /**
     * @return a LinkCapabilities object containing the READ ONLY capabilities
     *         of the LinkDatagramSocket
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
                    LinkCapabilities.Key.RO_QOS_STATE,
                    LinkCapabilities.Key.RO_TRANSPORT_PROTO_TYPE
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
     * Returns this LinkDatagramSockets set of capabilities, filtered according to the
     * given {@code Set}. Capabilities in the Set but not available from the
     * link will not be reported in the results. Capabilities of the link but
     * not listed in the Set will also not be reported in the results.
     *
     * @param capability_keys {@code Set} of capabilities requested
     * @return the filtered {@code LinkCapabilities} of this LinkDatagramSocket, may be
     *         empty
     */
    public LinkCapabilities getCapabilities(Set<Integer> capability_keys) {
        if (DBG) Log.v(TAG, "getCapabilities(capability_keys) EX");
        LinkCapabilities cap = null;
        int[] keys = new int[capability_keys.size()];

        // convert capability_keys into an int array
        Iterator<Integer> it = capability_keys.iterator();
        for (int i = 0; it.hasNext(); i++) keys[i] = it.next();

        // send the request
        try {
            cap = mService.requestCapabilities(mId, keys);
        } catch (RemoteException ex) {
            Log.d(TAG,
                    "LinkDatagramSocket was unable to get capabilities from ConnectivityService");
        }
        return cap;
    }

    /**
     * Provide the set of capabilities the application is interested in tracking
     * for this LinkDatagramSocket.
     *
     * @param capabilities a {@code Set} of capabilities to track
     */
    public void setTrackedCapabilities(Set<Integer> capabilities) {
        if (DBG) Log.v(TAG, "setTrackedCapabilities(capabilities) EX");
        // This feature is not implemented yet.
    }


    /**
     * This method is not available in this release.
     *
     * @return the {@code LinkCapabilities} that are tracked, empty if
     *         none has been set.
     */
    public Set<Integer> getTrackedCapabilities() {
        if (DBG) Log.v(TAG, "getTrackedCapabilities() EX");
        return new HashSet<Integer>();
    }

    /**
     * @deprecated LinkDatagramSocket will automatically pick the optimum interface to
     *             bind to
     * @param localAddr the specific address and port on the local machine to
     *            bind to
     * @throws IOException always as this method is deprecated for LinkDatagramSocket
     */
    @Override
    @Deprecated
    public void bind(SocketAddress localAddr) throws UnsupportedOperationException {
        if (DBG) Log.v(TAG, "bind(localAddr) EX");
        throw new UnsupportedOperationException(
                "LinkDatagamSocket will automatically bind to the optimum interface.");
    }

    /**
     * Connects this socket to the given remote host address and port specified
     * by dstName and dstPort. Network selection happens during connect and may
     * take 30 seconds.
     *
     * @param dstName the address of the remote host to connect to
     * @param dstPort the port to connect to on the remote host
     * @throws UnknownHostException if the given dstName is invalid
     * @throws IOException if the socket is already connected or an error occurs
     *             while connecting or if required needs are set but not granted
     *             by the network.
     */
    public void connect(String dstName, int dstPort) throws UnknownHostException, IOException {
        if (DBG) Log.v(TAG, "connect(dstName, dstPort) EX");
        if (dstName == null) {
            throw new IllegalArgumentException("dstName == null");
        }

        // save the addresses for future use
        mHostname = dstName;
        performNetworkSelection(mNetSelectTimeout);

        super.connect(new InetSocketAddress(InetAddress.getByName(dstName), dstPort));
    }

    /**
     * Connects this socket to the same remote host address and port as the
     * source LinkDatagramSocket.
     *
     * @param source the LinkDatagramSocket from which to get the destination
     * @throws UnknownHostException if the given dstName is invalid
     * @throws IOException if the socket is already connected or an error occurs
     *             while connecting
     */
    public void connect(LinkDatagramSocket source) throws UnknownHostException, IOException {
        if (DBG) Log.v(TAG, "connect(source) EX");
        connect(source.getHostname(), source.getPort());
    }

    /**
     * @see java.net.DatagramSocket#connect(java.net.InetAddress, int)
     */
    @Override
    public void connect(InetAddress address, int port) {
        if (DBG) Log.v(TAG, "connect(address, port) EX");

        try {
            connect(address.getHostAddress(), port);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("UnknownHostException: " + e.toString());
        } catch (IOException e) {
            throw new IllegalArgumentException("IOException: " + e.toString());
        }
    }

    /**
     * @see java.net.DatagramSocket#connect(java.net.SocketAddress)
     */
    @Override
    public void connect(SocketAddress peer) throws SocketException {
        if (DBG) Log.v(TAG, "connect(peer) EX");
        if (peer == null) {
            throw new IllegalArgumentException("peer == null");
        }

        if (!(peer instanceof InetSocketAddress)) {
            throw new IllegalArgumentException("peer not an InetSocketAddress: " +
                    peer.getClass());
        }

        InetSocketAddress isa = (InetSocketAddress) peer;
        connect(isa.getAddress(), isa.getPort());
    }

    /**
     * Receives a packet from this socket and stores it in the argument pack.
     * All fields of pack must be set according to the data received. If the
     * received data is longer than the packet buffer size it is truncated. This
     * method blocks until a packet is received or a timeout has expired. If a
     * security manager exists, its checkAccept method determines whether or not
     * a packet is discarded. Any packets from unacceptable origins are silently
     * discarded.
     *
     * Note: Network selection happens during send if the socket is
     * unconnected and may take up to 30 seconds.
     */
    @Override
    public synchronized void receive(DatagramPacket pack) throws IOException {
        if (DBG) Log.v(TAG, "receive(pack) EX");
        performNetworkSelection(mNetSelectTimeout);
        super.receive(pack);
    }

    /**
     * Sends a packet over this socket. The packet must satisfy the security
     * policy before it may be sent. If a security manager is installed, this
     * method checks whether it is allowed to send this packet to the specified
     * address.
     *
     * Note: Network selection happens during send if the socket is
     * unconnected and may take up to 30 seconds.
     *
     * @param pack
     *            the {@code DatagramPacket} which has to be sent.
     * @throws IOException
     *                if an error occurs while sending the packet or if required
     *                needs are set but not granted by the network.
     */
    @Override
    public void send(DatagramPacket pack) throws IOException {
        if (DBG) Log.v(TAG, "send(pack) EX");
        InetAddress dstAddress;

        // If user didn't call the connect method before calling this method
        // then may be user specified the InetAddress while creating
        // DatagramPacket
        if ((dstAddress = getInetAddress()) == null) {
            dstAddress = pack.getAddress();
        }

        if (dstAddress == null) {
            throw new NullPointerException("Destination address is null");
        }

        // save the addresses for future use
        mHostname = dstAddress.getHostAddress();
        performNetworkSelection(mNetSelectTimeout);
        super.send(pack);
    }

    /**
     * Suspends the QoS if user requested for QoS specific roles like {@code
     * QOS_VIDEO_TELEPHONY}. The response to the request is recieved by {@code
     * LinkSocketNotifier.onCapabilitiesChanged}. If the request is successful
     * the state of QoS as reported in {@code LinkCapabilities.Key.RO_QOS_STATE}
     * would be {@code LinkCapabilities.QosStatus.QOS_STATE_SUSPENDED}.
     *
     * @return true if the request to suspend QoS is sent successfully, false
     *         otherwise
     */
    public boolean suspendQoS() {
        if (mId == NOT_SET) { // Nothing to suspend
            Log.e(TAG, "Cannot suspend QoS as link not available");
            return false;
        }
        try {
            return mService.suspendQoS(mId);
        } catch (RemoteException re) {
            if (DBG)
                Log.w(TAG, "suspendQoS experienced remote exception: " + re);
            return false;
        }
    }

    /**
     * Resumes the QoS if user requested for QoS specific roles like {@code
     * QOS_VIDEO_TELEPHONY} and the QoS is in suspended state by call to {@code
     * suspendQos} method. The response to the request is sent via {@code
     * LinkSocketNotifier.onCapabilitiesChanged}. If the request is successful
     * the state of QoS as reported in {@code LinkCapabilities.Key.RO_QOS_STATE}
     * would be {@code LinkCapabilities.QosStatus.QOS_STATE_ACTIVE}.
     *
     * @return true if the request to resume QoS is sent successfully, false
     *         otherwise
     */
    public boolean resumeQoS() {
        if (mId == NOT_SET) { // Nothing to resume
            Log.e(TAG, "Cannot resume QoS as link not available");
            return false;
        }
        try {
            return mService.resumeQoS(mId);
        } catch (RemoteException re) {
            if (DBG)
                Log.w(TAG, "resumeQoS experienced remote exception: " + re);
            return false;
        }
    }

    /**
     * Closes the LinkDatagramSocket and tears down associated QoS reservations
     */
    @Override
    public void close() {
        if (DBG) Log.v(TAG, "close() EX");
        mMsgLoop.quit(); // disable callbacks
        releaseLink();
        super.close();
    }

    /**
     * Sets the socket implementation factory. This may only be invoked once
     * over the lifetime of the application. This factory is used to create
     * a new link datagram socket implementation. If a security manager is set its
     * method {@code checkSetFactory()} is called to check if the operation is
     * allowed. An {@code UnsupportedOperationException} is always thrown for this operation
     *
     * @deprecated PlainDatagramSocketImpl is always used
     *
     * @param fac
     *            the socket factory to use.
     * @throws UnsupportedOperationException always
     */
    @Deprecated
    public static synchronized void setDatagramSocketImplFactory(DatagramSocketImplFactory fac)
    throws IOException, UnsupportedOperationException {
        if (DBG) Log.v(TAG, "setDatagramSocketImplFactory(fac) EX Deprecated");
        throw new UnsupportedOperationException("This method is deprecated, "
                + "LinkDatagramSocket will automatically manage which factory to use");
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
                return "LinkDatagramSocket id:none unconnected";
            } else {
                return "LinkDatagramSocket id:" + mId + " unconnected";
            }
        } else {
            return "LinkDatagramSocket id:" + mId + " addr:" + super.getInetAddress()
                + " port:" + super.getPort() + " local_port:" + super.getLocalPort();
        }
    }

    protected synchronized void performNetworkSelection(int timeout) throws IOException {
        if (DBG) Log.v(TAG, "performNetworkSelection(timeout) EX");
        if (mProperties != null) return; // cannot perform network selection twice
        /*
         * Currently RW_ALLOWED_NETWORKS and RW_PROHIBITED_NETWORKS are not
         * implemented. If either of these keys are in use, throw an
         * IOException.
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
         * 1. request a network link
         * 2. figure out which address to bind to
         * 3. bind to the given address
         */

        // request link
        synchronized (this) {
            if (mId == NOT_SET) {
                try {
                    if (DBG) Log.v(TAG, "sending requestLink()");
                    isWaitingForResponse = true;
                    mId = mService.requestLink(mNeededCapabilities, mHostname, mMsgHandler);
                    while (isWaitingForResponse) {
                        if (DBG) Log.v(TAG, "Blocking: waiting for response");
                        wait(timeout);
                        /*
                         * if 'timeout' time passes, wait() will stop
                         * blocking just as if it was interrupted or
                         * received a notification, and the while loop will
                         * begin again. We need track time, and throw and
                         * exception if appropriate.
                         */
                        timeout -= (int) (Calendar.getInstance().getTimeInMillis()
                                - start.getTimeInMillis());
                        if (timeout <= 0) {
                            releaseLink();
                            throw new IOException("Socket timed out during link acquisition.");
                        }
                        if (DBG) Log.v(TAG, "Blocking: received notification or timeout");
                    }
                    if (DBG) Log.v(TAG, "Blocking: done");
                } catch (InterruptedException ex) {
                    releaseLink();
                    throw new IOException("ConnectivityService failed to respond to request");
                } catch (RemoteException ex) {
                    releaseLink();
                    throw new IOException("LinkSocket was unable to acquire a new network link");
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

        // bind
        if (DBG) Log.v(TAG, "attempting to bind: " + bindAddress + " port: " + mBindPort);
        try {
            super.bind(new InetSocketAddress(bindAddress, mBindPort));
        } catch (SocketException se) {
            releaseLink();
            throw new IOException("Desired source port is already consumed: " + mBindPort);
        }
        mBindPort = getLocalPort();
        if (DBG) Log.v(TAG, "bind successful: " + getLocalSocketAddress() + ":" + mBindPort);

        // Do QOS request for all sockets. Check return value to validate if
        // socket can get QoS service. Ignoring for now.
        try {
            if (mService.requestQoS(mId, mBindPort, bindAddress.getHostAddress()) != true) {
                throw new IOException("LinkSocket was unable to request for QoS");
            }
        } catch (RemoteException re) {
            throw new IOException("LinkSocket was unable to request for QoS, " + re.toString());
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

    private void constructor(LinkSocketNotifier notifier, int bindPort) {
        if (DBG) Log.v(TAG, "constructor(notifier) EX");

        mMsgLoop.start(); // start up message processing thread

        mNotifier = notifier;
        mBindPort = bindPort;
        setNeededCapabilities(LinkCapabilities.createNeedsMap(Role.DEFAULT));
        mNeededCapabilities.put(LinkCapabilities.Key.RO_TRANSPORT_PROTO_TYPE,"udp");

        IBinder binder = ServiceManager.getService(Context.CONNECTIVITY_SERVICE);
        mService = IConnectivityManager.Stub.asInterface(binder);
    }

    private void releaseLink() {
        if (mId == NOT_SET) return; // nothing to release
        if (DBG) Log.v(TAG, "releasing link");
        try {
            mService.releaseLink(mId);
        } catch (RemoteException ex) {
            Log.w(TAG, "LinkDatagramSocket was unable relinquish the current network link. " + ex);
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
                            Log.d(TAG, "LinkDatagramSocket received an unknown message type");
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
             * remove once CND is updated to the new architecture
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
             * remove once CND is updated to the new architecture
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
}
