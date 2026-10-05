package com.igniterealtime.openfire.plugin.moderation;

import org.dom4j.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request parser is the trust boundary, so these cases are the requests a client can send:
 * exactly what Conversations sends, and the ways a hostile or broken client can get it wrong.
 */
class ModerationRequestTest {

    private static final JID ROOM = new JID("ops@conference.example.org");
    private static final JID MODERATOR = new JID("alice@example.org/phone");
    private static final String STANZA_ID = "5f1c2e1a-8f0e-4c55-9b1d-2a7e6c1d0f3b";

    /** The request as Conversations' ModerationManager builds it: no reason, retract action. */
    private static IQ request(JID to, String stanzaId) {
        IQ iq = new IQ(IQ.Type.set, "mod-1");
        iq.setTo(to);
        iq.setFrom(MODERATOR);
        Element moderate = iq.setChildElement("moderate", Namespaces.MODERATE);
        if (stanzaId != null) moderate.addAttribute("id", stanzaId);
        moderate.addElement("retract", Namespaces.RETRACT);
        return iq;
    }

    @Test
    @DisplayName("Conversations' request parses into room, requester, id and stanza-id")
    void parsesConversationsRequest() throws ModerationException {
        ModerationRequest r = ModerationRequest.parse(request(ROOM, STANZA_ID)).orElseThrow();

        assertEquals(ROOM, r.room());
        assertEquals(MODERATOR, r.requester());
        assertEquals("mod-1", r.iqId());
        assertEquals(STANZA_ID, r.stanzaId());
        assertNull(r.reason());
    }

    @Test
    @DisplayName("a reason is kept, trimmed")
    void keepsReason() throws ModerationException {
        IQ iq = request(ROOM, STANZA_ID);
        iq.getChildElement().addElement("reason").setText("  spam  ");

        assertEquals("spam", ModerationRequest.parse(iq).orElseThrow().reason());
    }

    @Test
    @DisplayName("a reason cannot be used to push an unbounded text to every occupant")
    void capsReason() throws ModerationException {
        IQ iq = request(ROOM, STANZA_ID);
        iq.getChildElement().addElement("reason").setText("x".repeat(50_000));

        assertEquals(ModerationRequest.MAX_REASON_CHARS,
            ModerationRequest.parse(iq).orElseThrow().reason().length());
    }

    @Test
    @DisplayName("capping a reason never cuts an emoji in half")
    void capsReasonOnACharacterBoundary() throws ModerationException {
        // 999 chars, then an emoji whose two halves straddle the cap.
        IQ iq = request(ROOM, STANZA_ID);
        iq.getChildElement().addElement("reason")
            .setText("x".repeat(ModerationRequest.MAX_REASON_CHARS - 1) + "😀 and more");

        String reason = ModerationRequest.parse(iq).orElseThrow().reason();

        assertEquals(ModerationRequest.MAX_REASON_CHARS - 1, reason.length());
        assertFalse(Character.isHighSurrogate(reason.charAt(reason.length() - 1)),
            "the cap left a lone surrogate, which no XML stanza can carry");
    }

    @Test
    @DisplayName("ordinary IQs are not ours and cost nothing")
    void ignoresOtherIqs() throws ModerationException {
        IQ ping = new IQ(IQ.Type.get, "p");
        ping.setTo(ROOM);
        ping.setFrom(MODERATOR);
        ping.setChildElement("ping", "urn:xmpp:ping");

        assertEquals(Optional.empty(), ModerationRequest.parse(ping));
    }

    @Test
    @DisplayName("a get, or the deprecated version-0 namespace, is not a request")
    void ignoresWrongTypeAndVersion() throws ModerationException {
        IQ get = request(ROOM, STANZA_ID);
        get.setType(IQ.Type.get);
        assertEquals(Optional.empty(), ModerationRequest.parse(get));

        IQ v0 = new IQ(IQ.Type.set, "v0");
        v0.setTo(ROOM);
        v0.setFrom(MODERATOR);
        v0.setChildElement("moderate", "urn:xmpp:message-moderate:0");
        assertEquals(Optional.empty(), ModerationRequest.parse(v0));
    }

    @Test
    @DisplayName("an IQ to room/nick is a private IQ for that occupant, not a moderation")
    void ignoresIqToAnOccupant() throws ModerationException {
        assertEquals(Optional.empty(),
            ModerationRequest.parse(request(new JID("ops@conference.example.org/bob"), STANZA_ID)));
    }

    @Test
    @DisplayName("a request that names no message is a bad request")
    void rejectsMissingId() {
        ModerationException e = assertThrows(ModerationException.class,
            () -> ModerationRequest.parse(request(ROOM, null)));
        assertEquals(PacketError.Condition.bad_request, e.condition());
    }

    @Test
    @DisplayName("an id no room can have issued is item-not-found, and never reaches SQL")
    void rejectsHostileId() {
        for (String hostile : new String[] {
                "x' OR '1'='1", "%", "a\"b", "<x/>", "a&b", "id\nSECURITY: forged", "x".repeat(200)}) {
            ModerationException e = assertThrows(ModerationException.class,
                () -> ModerationRequest.parse(request(ROOM, hostile)), hostile);
            assertEquals(PacketError.Condition.item_not_found, e.condition(), hostile);
            assertTrue(!e.getMessage().contains("\n"), "log line forged by: " + hostile);
        }
    }

    @Test
    @DisplayName("a request without the retract action asks for something not implemented")
    void rejectsUnknownAction() {
        IQ iq = request(ROOM, STANZA_ID);
        iq.getChildElement().elements().forEach(Element::detach);

        ModerationException e = assertThrows(ModerationException.class, () -> ModerationRequest.parse(iq));
        assertEquals(PacketError.Condition.feature_not_implemented, e.condition());
    }
}
