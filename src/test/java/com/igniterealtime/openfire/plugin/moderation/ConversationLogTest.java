package com.igniterealtime.openfire.plugin.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parts of the archive and history lookups that do not need a database or a running server. */
class ConversationLogTest {

    private static final JID ROOM = new JID("ops@conference.example.org");
    private static final String STANZA_ID = "5f1c2e1a-8f0e-4c55-9b1d-2a7e6c1d0f3b";

    @Test
    @DisplayName("LIKE wildcards in an id match only themselves")
    void escapesLikeWildcards() {
        assertEquals("a!_b", ConversationLog.likeEscape("a_b"));
        assertEquals("a!%b", ConversationLog.likeEscape("a%b"));
        assertEquals("a!!b", ConversationLog.likeEscape("a!b"));
        assertEquals(STANZA_ID, ConversationLog.likeEscape(STANZA_ID));
    }

    @Test
    @DisplayName("a stored stanza parses back to the message, still identifiable by its stanza-id")
    void parsesStoredStanza() {
        String stored = RetractionTest.original().toXML();
        Optional<Message> parsed = ConversationLog.parse(stored);

        assertTrue(parsed.isPresent());
        assertTrue(Retraction.carries(parsed.get(), ROOM, STANZA_ID));
    }

    @Test
    @DisplayName("an empty or broken stanza column is skipped, not an error")
    void skipsUnparseable() {
        assertFalse(ConversationLog.parse(null).isPresent());
        assertFalse(ConversationLog.parse("  ").isPresent());
        assertFalse(ConversationLog.parse("<message><body>unterminated").isPresent());
    }

    @Test
    @DisplayName("a DOCTYPE in the stored text is refused, so no entity is ever resolved (XXE)")
    void refusesDoctype() {
        String xxe = "<?xml version='1.0'?><!DOCTYPE m [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"
            + "<message><body>&x;</body></message>";

        assertFalse(ConversationLog.parse(xxe).isPresent());
    }

    @Test
    @DisplayName("history lookup finds the entry by the room's stanza-id, and nothing else")
    void findsInHistory() {
        Message target = RetractionTest.original();
        Message other = new Message();
        other.setBody("mentions " + STANZA_ID + " in text only");

        assertEquals(Optional.of(target),
            RoomHistory.find(List.of(other, target).iterator(), ROOM, STANZA_ID));
        assertEquals(Optional.empty(), RoomHistory.find(List.of(other).iterator(), ROOM, STANZA_ID));
    }
}
