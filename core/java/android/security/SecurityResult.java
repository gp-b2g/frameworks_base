/**
 * Copyright (C) 2012, Code Aurora Forum. All rights reserved.
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

package android.security;

public final class SecurityResult {
    //This class is for result code when
    // doing secure related operation
    public final static int REMOTE_ERROR = 99;
    public final static int REMOTE_NO_ERROR = 0;

    //Apply token
    public final static int APPLY_TOKEN_SUCCESS = 0;

    //Authority
    public final static int NOT_AUTHORIZED_PACKAGE = 0;
    public final static int AUTHORIZED_PACKAGE = 1;

    //RADIO OPERATION
    public final static int INVALID_HANDLER = 2;
    public final static int REGISTER_SUCCESS = 0;
    public final static int REGISTER_FAILURE = 1;

    public final static int INTERCEPT_MESSAGE_SUCCESS = 0;
    public final static int INTERCPET_MESSAGE_DUPLICATE = 1;

    public final static int SET_FIREWALL_SUCCESS = 0;
    public final static int SET_FIREWALL_INVALID_INPUT = 1;

    //POWER OPERATION
    public final static int SET_POWERMODE_SUCCESS = 0;
    public final static int SET_POWERMODE_UNSUPPORTED = 1;
    public final static int SET_POWERMODE_INIT_ERROR = 2;

    //PERMISSION OPERATION
    public final static int INVALID_PERM_TYPE = 3;
    public final static int INVALID_PKG_PERM = 1;
    public final static int REVOKE_DUPLICATE_PERM = 2;
    public final static int REVOKE_PERM_SUCCESS = 0;

    public final static int INVALID_UID_PERM = 1;
    public final static int GRANT_DUPLICATE_PERM = 2;
    public final static int GRANT_PERM_SUCCESS = 0;

    //RECEIVER OPERTAION
    public final static int INVALID_RECEVIER_ACTION = 1;
    public final static int BLOCK_ACTION_SUCCESS = 0;
    public final static int RESTORE_ACTION_SUCCESS = 0;

    //CALL OPERATION
    public final static int INVALID_CALL_BLACK_LIST = 2;
    public final static int INVALID_CALL_BLACK_ITEM = 1;
    public final static int INSERT_BLACK_LIST_SUCCESS = 0;
    public final static int DUPLICATE_BLACK_ITEM = 2;
    public final static int ADD_BLACK_ITEM_SUCCESS = 0;
    public final static int NON_EXISTED_BLACK_ITEM = 2;
    public final static int REMOVE_BLACK_ITEM_SUCCESS = 0;
}
