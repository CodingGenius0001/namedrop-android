package tech.kwik.core.server.impl;

import tech.kwik.core.QuicConnection;

/**
 * DapTap: what kwik 0.10.3 keeps package-private about a server connection. Lives in kwik's package so Java's
 * protected access reaches the sender. Re-check when kwik is upgraded.
 */
public final class DapTapKwik {
    private DapTapKwik() {}

    /** Bytes sent but not yet acknowledged by the peer, or -1 if this isn't a kwik server connection. */
    public static long bytesInFlight(QuicConnection connection) {
        if (!(connection instanceof ServerConnectionImpl)) return -1;
        try {
            return ((ServerConnectionImpl) connection).getSender().getCongestionController().getBytesInFlight();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
