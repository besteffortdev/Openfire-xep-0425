package com.igniterealtime.openfire.plugin.moderation;

import org.dom4j.Element;
import org.dom4j.QName;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One XEP-0425 request, as read off the wire:
 *
 * <pre>{@code
 * <iq type='set' to='room@conference.example' id='abc'>
 *   <moderate xmlns='urn:xmpp:message-moderate:1' id='{stanza-id}'>
 *     <retract xmlns='urn:xmpp:message-retract:1'/>
 *     <reason>optional free text</reason>
 *   </moderate>
 * </iq>
 * }</pre>
 *
 * <p>This is the trust boundary. Everything in it except {@code from} — which Openfire stamps from
 * the authenticated session — is whatever the client chose to send, so {@link #parse} validates
 * every field it keeps. Authorization is <em>not</em> decided here: whether the sender may moderate
 * depends on their current role in the room, which only {@link ModerationHandler} can look up.</p>
 *
 * @param room      the room's bare JID, which the request was addressed to
 * @param requester the full JID of the sender, as Openfire authenticated it
 * @param iqId      the request's id, echoed in the reply; may be null if the client sent none
 * @param stanzaId  the XEP-0359 stanza-id of the message to retract, as the room assigned it
 * @param reason    the moderator's reason, trimmed and capped; null when none was given
 */
record ModerationRequest(JID room, JID requester, String iqId, String stanzaId, String reason) {

    static final QName MODERATE = QName.get("moderate", Namespaces.MODERATE);

    private static final QName RETRACT = QName.get("retract", Namespaces.RETRACT);
    private static final QName REASON = QName.get("reason", Namespaces.MODERATE);

    /**
     * What a stanza-id may look like. Openfire's own are UUIDs; this is wider than that so a
     * stanza-id minted elsewhere still works, but it deliberately excludes every character XML
     * would escape ({@code & < > " '}). That is what lets {@link ConversationLog} find the row with
     * a plain {@code LIKE} on the serialized stanza: an id from this set is spelled the same in the
     * request and in the stored XML. An id outside it cannot be one Openfire issued anyway.
     */
    private static final Pattern STANZA_ID = Pattern.compile("[A-Za-z0-9._~:@+=/-]{1,128}");

    /** A reason is shown to every occupant; a moderator does not need more than this to explain. */
    static final int MAX_REASON_CHARS = 1000;

    /**
     * @param iq any IQ; the interceptor calls this on every one headed for a room
     * @return the request, or empty when the IQ is not a moderation request at all — the common
     *         case, which must cost nothing and change nothing
     * @throws ModerationException when it <em>is</em> one but is malformed: the client is owed a
     *                             {@code bad-request} rather than core's "not implemented"
     */
    static Optional<ModerationRequest> parse(IQ iq) throws ModerationException {
        if (iq.getType() != IQ.Type.set) return Optional.empty();
        Element moderate = iq.getChildElement();
        if (moderate == null || !MODERATE.equals(moderate.getQName())) return Optional.empty();

        JID to = iq.getTo();
        JID from = iq.getFrom();
        // A full JID is an occupant, not the room: an IQ to room/nick is a private IQ that core
        // relays to that occupant, and none of this plugin's business.
        if (to == null || to.getNode() == null || to.getResource() != null || from == null) {
            return Optional.empty();
        }

        String stanzaId = moderate.attributeValue("id");
        if (stanzaId == null || stanzaId.isEmpty()) {
            throw new ModerationException(PacketError.Condition.bad_request,
                "moderation request without a stanza-id");
        }
        if (!STANZA_ID.matcher(stanzaId).matches()) {
            // Not malformed XML, just an id this room cannot have issued: answer as the spec does
            // for any id it cannot find.
            throw new ModerationException(PacketError.Condition.item_not_found,
                "moderation request names an id the room cannot have issued: "
                    + LogSafe.text(stanzaId));
        }
        if (moderate.element(RETRACT) == null) {
            // Retraction is the only action XEP-0425 defines. A request without it asks for
            // something this server does not know how to do.
            throw new ModerationException(PacketError.Condition.feature_not_implemented,
                "moderation request carries no <retract/> action");
        }

        return Optional.of(new ModerationRequest(to.asBareJID(), from, iq.getID(), stanzaId,
            reasonOf(moderate)));
    }

    /**
     * The cap is applied without splitting a surrogate pair: a cut through an emoji would leave a
     * lone surrogate, which is not a character XML can carry, and the reason is both broadcast to
     * every occupant and stored in the tombstone.
     */
    private static String reasonOf(Element moderate) {
        Element reason = moderate.element(REASON);
        if (reason == null) return null;
        String text = reason.getTextTrim();
        if (text.isEmpty()) return null;
        if (text.length() <= MAX_REASON_CHARS) return text;
        int end = Character.isHighSurrogate(text.charAt(MAX_REASON_CHARS - 1))
            ? MAX_REASON_CHARS - 1 : MAX_REASON_CHARS;
        return text.substring(0, end);
    }
}
