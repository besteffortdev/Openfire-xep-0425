package com.igniterealtime.openfire.plugin.moderation;

import com.igniterealtime.openfire.plugin.moderation.Retraction.ModeratedBy;
import org.dom4j.Element;
import org.dom4j.QName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two stanzas a moderation emits to XEP-0425 §4, and to what Conversations checks before
 * it acts on them ({@code ModerationManager.handleRetraction}).
 */
class RetractionTest {

    private static final JID ROOM = new JID("ops@conference.example.org");
    private static final String STANZA_ID = "5f1c2e1a-8f0e-4c55-9b1d-2a7e6c1d0f3b";
    private static final ModeratedBy BY = new ModeratedBy(
        new JID("ops@conference.example.org/alice"), "aliceoccupantid", "spam");
    private static final Instant NOW = Instant.parse("2026-10-04T20:00:00.123Z");

    /** A history entry as core stores it: body, room-issued stanza-id, occupant-id, delay. */
    static Message original() {
        Message m = new Message();
        m.setType(Message.Type.groupchat);
        m.setFrom(new JID("ops@conference.example.org/bob"));
        m.setID("client-id-1");
        m.setBody("buy cheap watches http://spam.example");
        m.getElement().addElement("stanza-id", Namespaces.STANZA_ID)
            .addAttribute("id", STANZA_ID).addAttribute("by", ROOM.toBareJID());
        m.getElement().addElement("occupant-id", Namespaces.OCCUPANT_ID).addAttribute("id", "bobid");
        m.getElement().addElement("delay", Namespaces.DELAY).addAttribute("stamp", "2026-10-04T19:59:00Z");
        m.getElement().addElement("x", "jabber:x:oob").addElement("url").setText("http://spam.example/a.png");
        m.getElement().addElement("html", "http://jabber.org/protocol/xhtml-im").addElement("body");
        return m;
    }

    private static Element child(Message m, String name, String ns) {
        return m.getElement().element(QName.get(name, ns));
    }

    @Test
    @DisplayName("the announcement is what Conversations acts on: groupchat, from the bare room, retract id")
    void announcementMatchesWhatClientsCheck() {
        Message a = Retraction.announcement(ROOM, STANZA_ID, BY);

        assertEquals(Message.Type.groupchat, a.getType());
        assertEquals(ROOM, a.getFrom());
        assertNull(a.getFrom().getResource(), "Conversations ignores a retraction from a full JID");
        Element retract = child(a, "retract", Namespaces.RETRACT);
        assertNotNull(retract);
        assertEquals(STANZA_ID, retract.attributeValue("id"));
        assertNotNull(a.getID());
    }

    @Test
    @DisplayName("the announcement says who moderated, by occupant JID and occupant-id, and why")
    void announcementNamesModerator() {
        Element retract = child(Retraction.announcement(ROOM, STANZA_ID, BY), "retract", Namespaces.RETRACT);
        Element moderated = retract.element(QName.get("moderated", Namespaces.MODERATE));

        assertEquals("ops@conference.example.org/alice", moderated.attributeValue("by"));
        assertEquals("aliceoccupantid",
            moderated.element(QName.get("occupant-id", Namespaces.OCCUPANT_ID)).attributeValue("id"));
        assertEquals("spam", retract.element(QName.get("reason", Namespaces.RETRACT)).getText());
    }

    @Test
    @DisplayName("the announcement has no body, so no client shows it as a new chat line")
    void announcementHasNoBody() {
        assertNull(Retraction.announcement(ROOM, STANZA_ID, BY).getBody());
    }

    @Test
    @DisplayName("no reason given: no empty <reason/> element")
    void noReasonNoElement() {
        Message a = Retraction.announcement(ROOM, STANZA_ID, new ModeratedBy(BY.occupantJid(), null, null));
        Element retract = child(a, "retract", Namespaces.RETRACT);

        assertNull(retract.element(QName.get("reason", Namespaces.RETRACT)));
        assertNull(retract.element(QName.get("moderated", Namespaces.MODERATE))
            .element(QName.get("occupant-id", Namespaces.OCCUPANT_ID)));
    }

    @Test
    @DisplayName("the tombstone keeps nothing of the content: no body, no attachment, no HTML")
    void tombstoneDropsContent() {
        Message t = Retraction.tombstone(original(), BY, NOW);

        assertNull(t.getBody());
        assertNull(child(t, "x", "jabber:x:oob"), "attachment URL survived");
        assertNull(child(t, "html", "http://jabber.org/protocol/xhtml-im"), "HTML body survived");
        assertFalse(t.toXML().contains("spam.example"), "moderated content survived: " + t.toXML());
    }

    @Test
    @DisplayName("the tombstone keeps the envelope: sender, ids, occupant-id and the delay that orders history")
    void tombstoneKeepsEnvelope() {
        Message original = original();
        Message t = Retraction.tombstone(original, BY, NOW);

        assertEquals(original.getFrom(), t.getFrom());
        assertEquals(original.getID(), t.getID());
        assertTrue(Retraction.carries(t, ROOM, STANZA_ID), "tombstone lost its stanza-id");
        assertEquals("bobid", child(t, "occupant-id", Namespaces.OCCUPANT_ID).attributeValue("id"));
        assertEquals("2026-10-04T19:59:00Z", child(t, "delay", Namespaces.DELAY).attributeValue("stamp"));
    }

    @Test
    @DisplayName("the tombstone is marked retracted, with a stamp and the moderation")
    void tombstoneIsMarked() {
        Element retracted = child(Retraction.tombstone(original(), BY, NOW), "retracted", Namespaces.RETRACT);

        assertEquals("2026-10-04T20:00:00Z", retracted.attributeValue("stamp"));
        assertEquals("ops@conference.example.org/alice",
            retracted.element(QName.get("moderated", Namespaces.MODERATE)).attributeValue("by"));
    }

    @Test
    @DisplayName("building a tombstone does not modify the stored original")
    void tombstoneLeavesOriginalAlone() {
        Message original = original();
        String before = original.toXML();
        Retraction.tombstone(original, BY, NOW);

        assertEquals(before, original.toXML());
    }

    @Test
    @DisplayName("only the room's own stanza-id identifies a message, never one a client forged")
    void carriesOnlyRoomStanzaId() {
        Message forged = new Message();
        forged.setFrom(new JID("ops@conference.example.org/mallory"));
        forged.getElement().addElement("stanza-id", Namespaces.STANZA_ID)
            .addAttribute("id", STANZA_ID).addAttribute("by", "mallory@example.org");

        assertFalse(Retraction.carries(forged, ROOM, STANZA_ID));
        assertTrue(Retraction.carries(original(), ROOM, STANZA_ID));
        assertFalse(Retraction.carries(original(), new JID("other@conference.example.org"), STANZA_ID),
            "a stanza-id from one room must not match a request in another");
    }

    @Test
    @DisplayName("a subject change is recognised, so it is never tombstoned")
    void recognisesSubjectChange() {
        Message subject = new Message();
        subject.setSubject("New topic");

        assertTrue(Retraction.isSubjectChange(subject));
        assertFalse(Retraction.isSubjectChange(original()));
    }
}
