/*
 * Copyright (C) 2010-2012, Code Aurora Forum. All rights reserved.
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

package android.net;

import android.net.LinkProperties;
import android.net.LinkCapabilities;

/** @hide
 * Interface that reports connectivity status to a LinkSocket
 */

interface ILinkSocketMessageHandler
{
    /*
     * Notify a LinkSocket that a link is available
     *
     *  LinkProperties should be filled in with the following values:
     *    setInterface(NetworkInterface iface); // iface to use
     *    addAddress(InetAddress address); // address to bind to
     *    addDns(InetAddress dns); // DNS addresses the iface is using
     *    setGateway(InetAddress gateway); // gateway address
     *    setHttpProxy(ProxyProperties proxy); // not sure about this one
     *  only addAddress is needed for LinkSocket to work
     */
    void onLinkAvail(in LinkProperties properties);

    /*
     * Notify a LinkSocket that no link is available
     *
     *  Reason codes:
     *    0: Unknown failure
     *    1: All networks are down
     *    2: Networks are available, but none meet requirements
     */
    void onGetLinkFailure(int reason);

    /*
     * Notify a LinkSocket that a better link might be available
     */
    void onBetterLinkAvail();

    /*
     * Notify a LinkSocket that their link has been lost
     */
    void onLinkLost();

    /*
     * We need to discuss these methods
     */
    void onCapabilitiesChanged(in LinkCapabilities changedCapabilities);
}
