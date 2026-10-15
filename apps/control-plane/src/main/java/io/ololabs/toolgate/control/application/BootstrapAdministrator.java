// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.ololabs.toolgate.contracts.ControlIdentityBinding;

/** Account protection never confers authority: live Team/Role membership still decides access. */
public final class BootstrapAdministrator {
    private BootstrapAdministrator() {}
    public static void validate(Store.Session tx,Directory before,Directory proposed,Codec codec) {
        var userId=tx.bootstrapAdminUserId();
        var access=new ManagementAccess(codec);
        if(userId!=null){
            var user=proposed.entries().get(Kind.USER.id(userId));
            if(user==null||!user.enabled())throw conflict("The login created during setup cannot be deleted or disabled");
            if(!access.superAdministrator(proposed,userId))throw conflict("The login created during setup must retain Super Admin authority");
            if(hasIdentity(before,userId,codec)&&!hasIdentity(proposed,userId,codec))throw conflict("The login created during setup must retain an enabled sign-in identity");
            return;
        }
        // Initial reviewed installation creates its graph atomically. Existing administration
        // cannot be removed through an alternative writer, configuration import or bulk change.
        if(hasAdministrator(before,access)&&!hasAdministrator(proposed,access))throw conflict("At least one enabled Super Admin must remain");
        if(hasSignInAdministrator(before,access,codec)&&!hasSignInAdministrator(proposed,access,codec))throw conflict("At least one enabled Super Admin must retain a sign-in identity");
    }
    private static boolean hasAdministrator(Directory directory,ManagementAccess access){return directory.entries().values().stream().filter(e->e.id().kind()==Kind.USER&&e.enabled()).anyMatch(e->access.superAdministrator(directory,e.id().value()));}
    private static boolean hasSignInAdministrator(Directory directory,ManagementAccess access,Codec codec){return directory.entries().values().stream().filter(e->e.id().kind()==Kind.USER&&e.enabled()).anyMatch(e->access.superAdministrator(directory,e.id().value())&&hasIdentity(directory,e.id().value(),codec));}
    private static boolean hasIdentity(Directory directory,String user,Codec codec){return directory.entries().values().stream().filter(e->e.id().kind()==Kind.IDENTITY_BINDING&&e.enabled()).anyMatch(e->codec.model(e.document(),ControlIdentityBinding.class).userId().equals(user));}
    private static Failure conflict(String message){return new Failure(ErrorCode.CONFLICT,409,message);}
}
