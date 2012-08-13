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

/** @hide */
interface IPermissionToken {
    /**
     * Called when the service wants to revoke a permission on package.
     */
    int revokePackagePermission(String permission, String packageName, int type);
    /**
      * Called when the service wants to grant a permission on package.
      */
    int grantPackagePermission(String permission, String packageName);
    /**
      * Called when the service wants to revoke a permission on uid.
      */
    int revokeUidPermission(String permission, int uid, int type);
    /**
      * Called when the service wants to grant a permission on uid.
      */
    int grantUidPermission(String permission, int uid);
    /**
     * Called when the service wants to revoke permission list on package.
     */
    int revokePackagePermissionList(in List<String> permissionList, String packageName, int type);
    /**
      * Called when the service wants to grant permission list on package.
      */
    int grantPackagePermissionList(in List<String> permissionList, String packageName);
    /**
      * Called when the service wants to revoke permission list on uid.
      */
    int revokeUidPermissionList(in List<String> permissionList, int uid, int type);
    /**
      * Called when the service wants to grant permission list on uid.
      */
    int grantUidPermissionList(in List<String> permissionList, int uid);
    /**
      * Called when the permission control service should be enabled.
      */
    void onEnablePermissionControl();
    /**
      * Called when the permission control service should be disabled.
      */
    void onDisablePermissionControl();
}

