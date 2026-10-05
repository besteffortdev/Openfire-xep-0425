package com.igniterealtime.openfire.plugin.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the log-forging defence (CERT IDS03-J). Ported from the sister SAMessage plugin's suite,
 * as {@link LogSafe} itself was, so the one helper is held to the one contract in every repo.
 *
 * <p>The property that matters is not the exact substitution character but the invariant: whatever
 * a client puts in a stanza-id, the result occupies exactly one log line and carries no terminal
 * escape. These cases are written as the attacks rather than as the implementation.</p>
 */
class LogSafeTest {

    /** ESC, as an ANSI sequence would start. */
    private static final String ESC = "\u001B";

    @Test
    @DisplayName("a newline in a stanza-id cannot forge a second log line")
    void foldsNewlines() {
        String forged = "abc\nSECURITY: alice@example.org granted owner in ops@conference";
        String safe = LogSafe.text(forged);

        assertFalse(safe.contains("\n"), "a forged line break survived: " + safe);
        assertEquals("abc?SECURITY: alice@example.org granted owner in ops@conference", safe);
    }

    @Test
    @DisplayName("CR, tab and every other ISO control is folded too")
    void foldsEveryControlCharacter() {
        assertEquals("a?b", LogSafe.text("a\rb"));
        assertEquals("a?b", LogSafe.text("a\tb"));
        assertEquals("a??b", LogSafe.text("a\r\nb"));
        assertEquals("a?b", LogSafe.text("a\u0000b"));
        assertEquals("a?b", LogSafe.text("a\u0007b"));
        assertEquals("a?b", LogSafe.text("a\u001Bb"));
    }

    @Test
    @DisplayName("an ANSI escape cannot reach an operator's terminal")
    void stripsAnsiEscapes() {
        String safe = LogSafe.text(ESC + "[31mabc" + ESC + "[0m");

        assertFalse(safe.contains(ESC), "an escape byte survived");
        assertEquals("?[31mabc?[0m", safe);
    }

    @Test
    @DisplayName("one hostile request cannot become an unbounded log entry")
    void capsLength() {
        String safe = LogSafe.text("A".repeat(10_000));

        assertTrue(safe.length() < 400, "not capped: " + safe.length() + " chars");
        assertTrue(safe.endsWith("…[truncated]"), "truncation is not announced");
        assertTrue(safe.startsWith("A".repeat(300)));
    }

    @Test
    @DisplayName("clean text is passed through untouched, and without copying")
    void leavesCleanTextAlone() {
        String clean = "5f1c2e1a-8f0e-4c55-9b1d-2a7e6c1d0f3b";

        // Same instance, not merely equal: the common case must not allocate.
        assertSame(clean, LogSafe.text(clean));
    }

    @Test
    @DisplayName("null renders as SLF4J would have rendered it")
    void handlesNull() {
        assertEquals("null", LogSafe.text(null));
    }

    @Test
    @DisplayName("unicode that is not a control character is preserved")
    void keepsRealText() {
        assertEquals("Équipe Rouge — modération", LogSafe.text("Équipe Rouge — modération"));
    }

    @Test
    @DisplayName("the one place a client's stanza-id reaches the log is sanitised there")
    void hostileStanzaIdReachesTheLogOnOneLine() {
        // What a &#10; character reference in the id attribute decodes to.
        IQ iq = new IQ(IQ.Type.set, "mod-1");
        iq.setTo(new JID("ops@conference.example.org"));
        iq.setFrom(new JID("mallory@example.org/x"));
        iq.setChildElement("moderate", Namespaces.MODERATE)
            .addAttribute("id", "x\nSECURITY: forged")
            .addElement("retract", Namespaces.RETRACT);

        ModerationException e = assertThrows(ModerationException.class, () -> ModerationRequest.parse(iq));

        assertFalse(e.getMessage().contains("\n"), "a forged line break reached the log: " + e.getMessage());
    }
}
