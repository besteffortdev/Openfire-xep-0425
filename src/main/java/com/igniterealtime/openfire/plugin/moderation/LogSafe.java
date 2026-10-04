package com.igniterealtime.openfire.plugin.moderation;

/**
 * Renders client-supplied strings safe to write into {@code openfire.log} (CERT IDS03-J, "do not log
 * unsanitized user input").
 *
 * <p>Ported unchanged in contract from the sister SAMessage and federation plugins, so the house
 * convention stays one helper with one meaning. The concern is log forging: a log line is
 * newline-delimited, so a value containing a line break can append what reads as a second, genuine
 * entry. Here the untrusted value is the stanza-id a moderation request names. It is an XML
 * attribute, so a literal newline is already folded to a space by attribute-value normalization —
 * but a {@code &#10;} character reference survives that and becomes a real newline.</p>
 *
 * <p>Every ISO control character becomes {@code ?} and the result is capped. Substitution rather
 * than deletion is deliberate: a stripped value looks legitimate, a {@code ?} looks wrong on sight.</p>
 *
 * <p>Only for values that crossed the client boundary. A JID Openfire parsed, a room name and a
 * nickname Openfire accepted are clean by construction.</p>
 */
final class LogSafe {

    /** Long enough for any real stanza-id, short enough that a hostile one cannot bloat the log. */
    private static final int MAX_CHARS = 300;

    private static final String TRUNCATION_MARKER = "…[truncated]";

    private LogSafe() {
    }

    /**
     * @param value an untrusted string; may be null
     * @return a single-line, length-capped rendering safe to pass as an SLF4J {@code {}} argument.
     *         Null becomes {@code "null"}, matching what SLF4J would have printed anyway.
     */
    static String text(String value) {
        if (value == null) return "null";
        boolean truncated = value.length() > MAX_CHARS;
        String head = truncated ? value.substring(0, MAX_CHARS) : value;

        StringBuilder sb = null;                 // allocate only when there is something to change
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (Character.isISOControl(c)) {
                if (sb == null) sb = new StringBuilder(head.length() + 12).append(head, 0, i);
                sb.append('?');
            } else if (sb != null) {
                sb.append(c);
            }
        }
        String clean = sb == null ? head : sb.toString();
        return truncated ? clean + TRUNCATION_MARKER : clean;
    }
}
