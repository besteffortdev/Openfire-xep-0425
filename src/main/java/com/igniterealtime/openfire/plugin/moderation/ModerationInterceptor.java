package com.igniterealtime.openfire.plugin.moderation;

import org.dom4j.Element;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;

import java.util.Optional;
import java.util.UUID;

/**
 * Picks XEP-0425 requests out of the IQ stream before Openfire's MUC service sees them.
 *
 * <h2>Why an interceptor</h2>
 * Openfire has no extension point for an IQ addressed to a <em>room</em>. Plugin IQ handlers
 * registered with {@code MultiUserChatService.addIQHandler} are only consulted for IQs to the
 * service itself; an IQ to {@code room@service} with a namespace core does not know is answered
 * with an error inside {@code MultiUserChatServiceImpl}, with nothing to hook in between.
 *
 * <h2>Why the request is rewritten rather than rejected</h2>
 * Throwing {@code PacketRejectedException} would stop core from seeing the request, but
 * {@code IQRouter} answers every rejected IQ with an error of its own — so the moderator would get
 * that error <em>and</em> this plugin's result for the same id. Instead the request is
 * {@linkplain #consume consumed}: turned, in place, into an IQ <em>result</em> to the bare room JID,
 * which core's MUC service drops without a reply ("Silently ignoring an IQ response sent to the
 * room"). {@code IQRouter} reads the stanza only after interceptors have run, so what it routes is
 * the consumed form. The moderator's one answer then comes from {@link ModerationHandler}.
 */
final class ModerationInterceptor implements PacketInterceptor {

    private static final Logger Log = LoggerFactory.getLogger(ModerationInterceptor.class);

    private final ModerationHandler handler;

    ModerationInterceptor(ModerationHandler handler) {
        this.handler = handler;
    }

    @Override
    public void interceptPacket(Packet packet, Session session, boolean incoming, boolean processed) {
        if (!incoming || processed || !(packet instanceof IQ iq)) return;

        // The cheap test first: this runs for every IQ on the server, and nearly none are ours.
        Element child = iq.getChildElement();
        if (child == null || !ModerationRequest.MODERATE.equals(child.getQName())) return;

        try {
            if (!ModerationProperties.ENABLED.getValue() || !isLocalMucService(iq.getTo())) return;
            handle(iq);
        } catch (Exception e) {
            // An exception escaping here reaches core's interceptor loop. Leave the request
            // unconsumed so core still answers it, with its own error.
            Log.error("Could not intercept moderation request from {}", iq.getFrom(), e);
        }
    }

    /**
     * The request is handed on <em>before</em> it is consumed, never after. Neither depends on the
     * IQ once the request is parsed, so the order costs nothing — and it is what makes the
     * fallback in {@link #interceptPacket} true: if handing on throws, the IQ is still intact and
     * core answers it, rather than the moderator being left with no answer at all.
     */
    private void handle(IQ iq) {
        JID to = iq.getTo();
        JID from = iq.getFrom();
        String id = iq.getID();
        Optional<ModerationRequest> request;
        try {
            request = ModerationRequest.parse(iq);
        } catch (ModerationException e) {
            Log.debug("Malformed moderation request from {}: {}", from, e.getMessage());
            ModerationHandler.sendError(to.asBareJID(), from, id, e.condition());
            consume(iq);
            return;
        }
        if (request.isEmpty()) return;     // e.g. addressed to an occupant, not the room
        handler.submit(request.get());
        consume(iq);
    }

    private static boolean isLocalMucService(JID to) {
        return to != null
            && XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(to) != null;
    }

    /**
     * Neutralises the request in place: core then sees an IQ result to the room and drops it.
     * The id is replaced too, so the result cannot match an {@code IQResultListener} waiting on
     * the client-chosen id anywhere in the server.
     */
    private static void consume(IQ iq) {
        iq.setType(IQ.Type.result);
        iq.setID("moderation-consumed-" + UUID.randomUUID());
        Element child = iq.getChildElement();
        if (child != null) child.detach();
    }
}
