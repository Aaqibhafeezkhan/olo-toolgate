// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.context;

import java.io.IOException;
import java.net.Socket;
import javax.net.SocketFactory;

/** Connections to declared TCP destinations. */
public interface TcpConnector {
    Socket connect(String destinationId) throws IOException;
    SocketFactory socketFactory(String destinationId);
}
