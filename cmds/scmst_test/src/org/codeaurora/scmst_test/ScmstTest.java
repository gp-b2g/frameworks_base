/*
 * Copyright (c) 2010, Code Aurora Forum. All rights reserved.
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

package org.codeaurora.scmst_test;

import android.media.AudioSystem;

public class ScmstTest {
    public static void main(String[] args) {
        if (args.length < 1) {
            usage();
            System.exit(1);
        }

        Byte cp = new Byte(Byte.decode(args[0]).byteValue());
        AudioSystem.setParameters("scmst_cp_header="+cp.toString());
        System.exit(0);
    }

    /* Print usage information */
    private static void usage() {
        System.err.println("scmst_test CP_BYTE");
        System.err.println("   CP_BYTE - SCMS-T CP header byte to use (decimal)");
        System.err.println("             0: Copy allowed");
        System.err.println("             2: Copy prohibited");
        System.err.println("             3: Copy once");
    }
}
