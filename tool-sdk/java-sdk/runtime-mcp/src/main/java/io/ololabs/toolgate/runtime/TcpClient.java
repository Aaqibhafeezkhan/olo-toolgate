// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import io.ololabs.toolgate.sdk.context.TcpConnector;
import io.ololabs.toolgate.sdk.errors.PlatformError;
import io.ololabs.toolgate.sdk.errors.ToolConfigurationError;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import javax.net.SocketFactory;

/**
 * {@code context.tcp()} in standalone mode (http.md §6): connections only to the TCP destinations a tool lists, at the
 * host the configuration resolves. The socket factory serves drivers with a socket-factory option and refuses any
 * other address. Sockets close when the invocation is cancelled. As with routes, this is developer feedback, not a
 * network boundary.
 */
final class TcpClient implements TcpConnector {
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** An address a socket may connect to, with the connect timeout the deadline leaves. */
    private record Admitted(InetSocketAddress address, int timeoutMs) {}

    private final Egress egress;
    private final Set<String> allowed;
    private final StandaloneContext context;
    private final Instant deadline;

    TcpClient(Egress egress, Set<String> allowed, StandaloneContext context, Instant deadline) {
        this.egress = egress;
        this.allowed = allowed;
        this.context = context;
        this.deadline = deadline;
    }

    @Override
    public Socket connect(String destinationId) throws IOException {
        Egress.TcpTarget target = target(destinationId);
        return new ToolGateSocketFactory(target).createSocket(target.host(), target.port());
    }

    @Override
    public SocketFactory socketFactory(String destinationId) {
        return new ToolGateSocketFactory(target(destinationId));
    }

    private Egress.TcpTarget target(String destinationId) {
        if (destinationId == null || !allowed.contains(destinationId)) {
            throw new PlatformError(PlatformError.Code.ROUTE_REFUSED, "TCP destination " + destinationId + " is not declared for this tool");
        }
        Egress.TcpTarget target = egress.tcpTarget(destinationId);
        if (target == null || target.problem() != null) {
            throw new ToolConfigurationError(target == null ? "TCP destination " + destinationId + " is not declared" : target.problem());
        }
        return target;
    }

    /**
     * Checks {@code address} against the destination and the deadline, and closes the socket if the invocation is
     * cancelled. Returns the address to connect to and the connect timeout.
     */
    private Admitted admit(Socket socket, SocketAddress address, Egress.TcpTarget target) throws IOException {
        long left = Duration.between(Instant.now(), deadline).toMillis();
        if (left <= 0) throw new PlatformError(PlatformError.Code.DEADLINE_EXCEEDED, "The invocation deadline passed");
        context.onCancel(() -> {
            try {
                socket.close();
            } catch (IOException e) {
                // closing on cancellation
            }
        });
        if (!(address instanceof InetSocketAddress inet) || !allows(target, inet)) {
            socket.close();
            throw new PlatformError(PlatformError.Code.ROUTE_REFUSED, "TCP destination " + target.id() + " is " + target.host() + ":" + target.port()
                    + ", not " + address);
        }
        return new Admitted(inet.isUnresolved() ? new InetSocketAddress(inet.getHostString(), inet.getPort()) : inet,
                (int) Math.min(CONNECT_TIMEOUT.toMillis(), left));
    }

    /** Whether {@code address} is the destination: the same port and the configured host or one of its addresses. */
    static boolean allows(Egress.TcpTarget target, InetSocketAddress address) {
        if (address.getPort() != target.port()) return false;
        if (address.getHostString().equalsIgnoreCase(target.host())) return true;
        if (address.getAddress() == null) return false;
        try {
            return Arrays.asList(InetAddress.getAllByName(target.host())).contains(address.getAddress());
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** The {@code ToolGateSocketFactory} of http.md §6: sockets that connect only to one declared destination. */
    final class ToolGateSocketFactory extends SocketFactory {
        private final Egress.TcpTarget target;

        ToolGateSocketFactory(Egress.TcpTarget target) {
            this.target = target;
        }

        @Override
        public Socket createSocket() {
            return new Socket() {
                @Override
                public void connect(SocketAddress endpoint, int timeout) throws IOException {
                    Admitted admitted = admit(this, endpoint, target);
                    int bounded = admitted.timeoutMs();
                    super.connect(admitted.address(), timeout > 0 ? Math.min(timeout, bounded) : bounded);
                }
            };
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(InetSocketAddress.createUnresolved(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            Socket socket = createSocket();
            socket.bind(new InetSocketAddress(localHost, localPort));
            socket.connect(InetSocketAddress.createUnresolved(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            Socket socket = createSocket();
            socket.bind(new InetSocketAddress(localAddress, localPort));
            socket.connect(new InetSocketAddress(address, port));
            return socket;
        }
    }
}
