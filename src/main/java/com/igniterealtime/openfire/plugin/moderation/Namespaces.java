package com.igniterealtime.openfire.plugin.moderation;

/**
 * The XML namespaces this plugin speaks, in one place so that every class means the same version.
 *
 * <p>Only version 1 of moderation is implemented. It is what Conversations sends (its
 * {@code Namespace.MODERATION} is {@code urn:xmpp:message-moderate:1}, the only moderation
 * namespace it knows), and version 0 used the deprecated XEP-0422 Fastening wrapper, which nothing
 * this plugin is meant for still requires.</p>
 */
final class Namespaces {

    /** XEP-0425 Moderated Message Retraction, version 1 (XEP revision 0.3.0 and later). */
    static final String MODERATE = "urn:xmpp:message-moderate:1";

    /** XEP-0424 Message Retraction — the action a {@code <moderate/>} request carries. */
    static final String RETRACT = "urn:xmpp:message-retract:1";

    /** XEP-0421 occupant-ids, issued by Openfire core since 5.0.5. */
    static final String OCCUPANT_ID = "urn:xmpp:occupant-id:0";

    /** XEP-0359 stanza-id / origin-id. */
    static final String STANZA_ID = "urn:xmpp:sid:0";

    /** XEP-0203 delayed delivery — what orders the room's join history. */
    static final String DELAY = "urn:xmpp:delay";

    /** XEP-0045 MUC user extension, carried by history entries in non-anonymous rooms. */
    static final String MUC_USER = "http://jabber.org/protocol/muc#user";

    private Namespaces() {
    }
}
