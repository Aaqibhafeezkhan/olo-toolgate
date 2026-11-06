// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.service;

import java.sql.*;

/** Test-only target JVM: commit claim, APPLYING and external business data, then halt before outcome recording. */
public final class ClaimCrashProcess {
    private ClaimCrashProcess(){}
    private static Connection connection()throws SQLException{
        String user=System.getenv("CLAIM_TEST_USER");
        var c=user==null?DriverManager.getConnection(System.getenv("CLAIM_TEST_URL")):
            DriverManager.getConnection(System.getenv("CLAIM_TEST_URL"),user,System.getenv("CLAIM_TEST_PASSWORD"));
        c.setAutoCommit(false);return c;
    }
    public static void main(String[] args){
        try{
            var binding=new JdbcEffectClaims.Binding("tenant",args[0],1,"auth-"+args[0],"sha256:"+"a".repeat(64));
            var claims=new JdbcEffectClaims();JdbcEffectClaims.Owner owner;
            try(var c=connection()){owner=claims.claim(c,binding,1001).owner();c.commit();}
            if(owner==null)throw new IllegalStateException();
            try(var c=connection()){if(!claims.beginApply(c,owner))throw new IllegalStateException();c.commit();}
            // Deliberately outside the claim/outcome transaction, like an independent target-owned provider commit.
            try(var c=connection();var q=c.prepareStatement("INSERT INTO external_business_receipts VALUES(?,?,?,?)")){
                q.setString(1,binding.tenant());q.setString(2,binding.effectId());q.setString(3,binding.requestDigest());
                q.setString(4,"sha256:"+"b".repeat(64));q.executeUpdate();c.commit();
            }
            Runtime.getRuntime().halt(47);
        }catch(Throwable failure){
            // Test credentials and database exception bodies never enter output.
            System.err.println("Target crash fixture failed before the required durable boundary");
            Runtime.getRuntime().halt(88);
        }
    }
}
