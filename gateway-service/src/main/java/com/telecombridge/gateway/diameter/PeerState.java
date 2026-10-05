package com.telecombridge.gateway.diameter;

/** Simplified RFC 6733 section 5.6 peer state machine, initiator side. */
public enum PeerState {
    /** No transport connection; a reconnect may be scheduled. */
    CLOSED,
    /** TCP connect in progress. */
    CONNECTING,
    /** Connected, CER sent, waiting for CEA. */
    WAIT_CEA,
    /** Capabilities exchanged, application traffic allowed. */
    OPEN,
    /** DPR sent or shutdown in progress; no new requests accepted. */
    CLOSING
}
