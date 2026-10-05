package com.igniterealtime.openfire.plugin.moderation;

import org.xmpp.packet.PacketError;

/**
 * A moderation request that has to be answered with an XMPP error rather than a result.
 *
 * <p>Carries the {@link PacketError.Condition} to answer with, so the place that discovers what is
 * wrong is also the place that decides what the requester is told. The message is for the server
 * log only and is never sent back: the requester gets the bare condition, which is all XEP-0425
 * specifies and all a client acts on.</p>
 */
final class ModerationException extends Exception {

    private static final long serialVersionUID = 1L;

    /** An enum, so serializable as it is; {@code transient} would lose it on deserialization. */
    private final PacketError.Condition condition;

    ModerationException(PacketError.Condition condition, String logMessage) {
        super(logMessage);
        this.condition = condition;
    }

    PacketError.Condition condition() {
        return condition;
    }
}
