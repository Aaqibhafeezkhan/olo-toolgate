// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

/** Exact DNS/IP host ownership; no wildcard, user info, forwarded headers or port is a hostname. */
public final class TenantHosts {
    private TenantHosts() {}
    public static String normalize(String value) {
        if(value==null||value.isBlank()||!value.equals(value.strip())||value.length()>253||value.contains("/")||value.contains("@")||value.contains(","))throw new IllegalArgumentException("Invalid tenant host");
        if(value.contains(":")){
            if(!value.matches("[a-fA-F0-9:]+"))throw new IllegalArgumentException("Invalid IP host");
            try{return java.net.InetAddress.getByName(value).getHostAddress();}catch(java.net.UnknownHostException invalid){throw new IllegalArgumentException("Invalid IP host");}
        }
        String host=java.net.IDN.toASCII(value,java.net.IDN.USE_STD3_ASCII_RULES).toLowerCase(java.util.Locale.ROOT);
        if(host.endsWith("."))host=host.substring(0,host.length()-1);
        if(host.isEmpty()||host.length()>253)throw new IllegalArgumentException("Invalid tenant host");
        for(String label:host.split("\\.",-1))if(!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))throw new IllegalArgumentException("Invalid DNS host");
        if(host.matches("[0-9.]+")){
            var octets=host.split("\\.",-1);if(octets.length!=4)throw new IllegalArgumentException("Ambiguous IP host");
            for(var octet:octets)if(octet.length()>3||octet.length()>1&&octet.charAt(0)=='0'||Integer.parseInt(octet)>255)throw new IllegalArgumentException("Invalid IP host");
        }
        return host;
    }
}
