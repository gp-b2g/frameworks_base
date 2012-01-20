/* Copyright (c) 2011 Code Aurora Forum. All rights reserved.
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

/** {@hide}
 * This is an interface that the applications need to implement. An app
 * This interface will be used by CnE to notify apps of various events
 * related to connectivity.
 */
public interface FmcNotifier {
    /* modified or add new entry? Do the same to FMC_STATUS_STR */
    public final static int FMC_STATUS_ENABLED         = 0;  // fmc enabled
    public final static int FMC_STATUS_CLOSED          = 1;  // fmc closed
    public final static int FMC_STATUS_INITIALIZED     = 2;  // requested start from oem
    public final static int FMC_STATUS_SHUTTING_DOWN   = 3;  // requested stop from oem
    public final static int FMC_STATUS_NOT_YET_STARTED = 4;  // requested stop having been start
    public final static int FMC_STATUS_FAILURE         = 5;  // any unknown failure or memory allocation
    public final static int FMC_STATUS_NOT_AVAIL       = 6;  // fmc not available(check persist)
    public final static int FMC_STATUS_DS_NOT_AVAIL    = 7;  // data server not available
    public final static int FMC_STATUS_RETRIED         = 8;  // out of coverage
    public final static int FMC_STATUS_REGISTRATION_SUCCESS = 9;  // successful registration
    public final static int FMC_STATUS_MAX             = 10;

    public final static String[] FMC_STATUS_STR = {
        "Enabled",
        "Closed",
        "Initialized...",
        "Shutting down...",
        "Has not started",
        "Failure",
        "Fmc not available",
        "DS not available",
        "OoC - retry...",
        "Registration success",
        "Undefined FMC Status"
    };

    /** {@hide}
     * This function notifies the calling function whether FMC was
     * successfully enable, disable, stop, failure...
     */
    public void onFmcStatus(int status);

}
