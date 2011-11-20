/*
 * Copyright (c) 2011, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *    * Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 *    * Neither the name of Code Aurora nor
 *      the names of its contributors may be used to endorse or promote
 *      products derived from this software without specific prior written
 *      permission.
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
 */

package android.hardware.fmradio;

   /** @hide
    *  The interface that provides the applications to get callback
    *  for asynchronous events.
    *  An Adapter that implements this interface needs to be used to
    *  register to receive the callbacks using {@link
    *  #FmTransmitter}.
    *  <p>
    *  Application has to register for these callbacks by sending
    *  a valid instance of TransmitterCallbacks's adapter while
    *  instantiating the FMTransmitter.
    *
    *  @see #FmTransmitter
    */
   public interface FmTransmitterCallbacks
   {
      /**
       *  The callback indicates that the transmitter is tuned to a
       *  new frequency Typically received after setStation.
       */
      public void onTuneStatusChange(int freq);

      /**
       * The callback to indicate to the application that the FM
       * driver can accept additional groups even though all groups
       * may not have been passed to the FM transmitter.
       *
       * Application can start to send down the remaining groups
       * to the available group buffers upon recieving this callback.
       */
      public void onRDSGroupsAvailable();
      /**
       * The callback will indicate the succesful completion of #transmitRdsGroups.
       * This indicates that the FM driver has a complete buffer to transmit the
       * RDS/RBDS data to the Application. Application can free to switch between continuous or
       * non-continuous RDS/RBDS group transmissions.
       */
      public void onRDSGroupsComplete();
     /**
       * The callback will indicate the succesful completion of #transmitRdsContGroups.
       * This indicates that the FM driver has a complete buffer to transmit the
       * RDS/RBDS data to the Application. Application can free to switch between continuous or
       * non-continuous RDS/RBDS group transmissions.
       *
       */
      public void onContRDSGroupsComplete();
   };
