// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids.Kind;
import io.ololabs.toolgate.contracts.ErrorCode;

/** Account protection never confers authority: live Team/Role membership still decides access. */
public final class BootstrapAdministrator {
    private BootstrapAdministrator() {}
    public static void validate(Store.Session tx,Directory proposed) {
        var userId=tx.bootstrapAdminUserId();
        if(userId==null)return;
        var user=proposed.entries().get(Kind.USER.id(userId));
        if(user==null||!user.enabled())throw new Failure(ErrorCode.CONFLICT,409,"The setup administrator cannot be deleted or disabled");
    }
}
