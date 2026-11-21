// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.ApplicationEvent;

/** Installation-approved, DNS-pinned HTTPS only. Never forwards a subscriber's bearer token. */
public interface ApplicationWebhookTransport {
    void validate(String endpoint);
    boolean verify(String endpoint, String subscriptionId, String challenge, byte[] secret);
    boolean deliver(String endpoint, String subscriptionId, ApplicationEvent event, byte[] secret);
}
