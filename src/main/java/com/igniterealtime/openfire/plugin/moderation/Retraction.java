package com.igniterealtime.openfire.plugin.moderation;

import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.openfire.stanzaid.StanzaIDUtil;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the two stanzas a moderation produces, and nothing else — no lookups, no I/O — so both
 * shapes can be pinned down by tests rather than by reading a live stream.
 *
 * <p>Both follow XEP-0425 §4 (version 1) exactly. Their shape is not a matter of taste: it is what
 * clients check before they act. Conversations, for one, ignores a retraction unless it is a
 * {@code groupchat} message from the room's <em>bare</em> JID carrying {@code <retract id='…'/>},
 * and then deletes the message whose stanza-id equals that id.</p>
 */
final class Retraction {

    private static final QName RETRACT = QName.get("retract", Namespaces.RETRACT);
    private static final QName RETRACTED = QName.get("retracted", Namespaces.RETRACT);
    private static final QName MODERATED = QName.get("moderated", Namespaces.MODERATE);
    private static final QName OCCUPANT_ID = QName.get("occupant-id", Namespaces.OCCUPANT_ID);

    /**
     * What survives into a tombstone. An allowlist rather than a denylist, because the point of a
     * tombstone is that the moderated content is gone: a denylist of {@code body} would keep an
     * attachment URL (OOB), an HTML body, a reply quote or an encrypted payload, and a new
     * extension added by a client next year would be kept by default. These are the envelope
     * fields only — who sent it ({@code occupant-id}, {@code muc#user}), which message it was
     * ({@code stanza-id}/{@code origin-id}), and when ({@code delay}, which also orders the join
     * history).
     */
    private static final Set<String> TOMBSTONE_KEEPS = Set.of(
        Namespaces.STANZA_ID, Namespaces.OCCUPANT_ID, Namespaces.DELAY, Namespaces.MUC_USER);

    private Retraction() {
    }

    /**
     * Who moderated, as carried in {@code <moderated/>}.
     *
     * @param occupantJid the moderator's occupant JID (room@service/nick); goes in {@code by}
     * @param occupantId  the moderator's XEP-0421 occupant-id; null if core issued none
     * @param reason      the reason the moderator gave; null for none
     */
    record ModeratedBy(JID occupantJid, String occupantId, String reason) {
    }

    /**
     * @return whether {@code message} is the one the room stamped with {@code stanzaId}. Only a
     *         stanza-id whose {@code by} is the room counts: a client may send a stanza-id of its
     *         own, and a forged one must not make its message match someone else's id.
     */
    static boolean carries(Message message, JID room, String stanzaId) {
        return stanzaId.equals(StanzaIDUtil.findFirstUniqueAndStableStanzaID(message, room.toBareJID()));
    }

    /** @return whether {@code message} sets the room subject, which is not a message to retract. */
    static boolean isSubjectChange(Message message) {
        return message.getElement().element("subject") != null;
    }

    /**
     * The tombstone that replaces a moderated message in the archive and the join history
     * (XEP-0425 §4, XEP-0424 §5.1).
     *
     * @param original the message as stored; not modified
     * @param by       who moderated it
     * @param now      when; becomes the {@code stamp}, at second precision
     * @return a new message with the original's envelope and none of its content
     */
    static Message tombstone(Message original, ModeratedBy by, Instant now) {
        Message tombstone = new Message();
        tombstone.setType(Message.Type.groupchat);
        tombstone.setFrom(original.getFrom());
        tombstone.setTo(original.getTo());
        tombstone.setID(original.getID());

        for (Element child : original.getElement().elements()) {
            if (TOMBSTONE_KEEPS.contains(child.getNamespaceURI())) {
                tombstone.getElement().add(child.createCopy());
            }
        }

        Element retracted = tombstone.getElement().addElement(RETRACTED);
        retracted.addAttribute("stamp", now.truncatedTo(ChronoUnit.SECONDS).toString());
        appendModeration(retracted, by);
        return tombstone;
    }

    /**
     * The announcement broadcast to every occupant, telling their clients to remove the message.
     *
     * <p>No {@code <body>}: XEP-0425's own example has none, and a fallback body is only safe if
     * every client recognises it as one. Conversations checks for a {@code <fallback/>} covering
     * the entire body; a client that does not would show "message deleted" as a new chat line from
     * the room — in every room, after every moderation.</p>
     *
     * @param room     the room's bare JID; the announcement must come from it, not from an occupant
     * @param stanzaId the stanza-id of the moderated message
     * @param by       who moderated it
     */
    static Message announcement(JID room, String stanzaId, ModeratedBy by) {
        Message announcement = new Message();
        announcement.setType(Message.Type.groupchat);
        announcement.setFrom(room.asBareJID());
        announcement.setID(UUID.randomUUID().toString());

        Element retract = announcement.getElement().addElement(RETRACT);
        retract.addAttribute("id", stanzaId);
        appendModeration(retract, by);
        return announcement;
    }

    /**
     * Rebuilds the announcement for a moderation from its tombstone, so it can be sent again to an
     * occupant who was offline when it happened (see {@link ModerationReplay}).
     *
     * <p>The tombstone is the durable record of the moderation: it holds the stanza-id, the
     * {@code <moderated/>} element and the reason, exactly as they were announced. The rebuilt
     * announcement carries them unchanged, plus an XEP-0203 {@code <delay/>} with the time of the
     * moderation, which tells a client that reads it that this is a replay rather than a new
     * event.</p>
     *
     * @return the announcement, or empty when {@code tombstone} is not one this plugin wrote — a
     *         plain XEP-0424 self-retraction has no {@code <moderated/>} and is not replayed
     */
    static Optional<Message> replayOf(JID room, Message tombstone) {
        Element retracted = tombstone.getElement().element(RETRACTED);
        if (retracted == null || retracted.element(MODERATED) == null) return Optional.empty();
        String stanzaId = StanzaIDUtil.findFirstUniqueAndStableStanzaID(tombstone, room.toBareJID());
        if (stanzaId == null) return Optional.empty();

        Message replay = new Message();
        replay.setType(Message.Type.groupchat);
        replay.setFrom(room.asBareJID());
        replay.setID(UUID.randomUUID().toString());

        Element retract = replay.getElement().addElement(RETRACT);
        retract.addAttribute("id", stanzaId);
        for (Element child : retracted.elements()) {
            retract.add(child.createCopy());
        }
        String stamp = retracted.attributeValue("stamp");
        if (stamp != null) {
            replay.getElement().addElement(QName.get("delay", Namespaces.DELAY))
                .addAttribute("from", room.toBareJID())
                .addAttribute("stamp", stamp);
        }
        return Optional.of(replay);
    }

    /** {@code <moderated by='…'><occupant-id id='…'/></moderated><reason>…</reason>} */
    private static void appendModeration(Element parent, ModeratedBy by) {
        Element moderated = parent.addElement(MODERATED);
        moderated.addAttribute("by", by.occupantJid().toString());
        if (by.occupantId() != null) {
            moderated.addElement(OCCUPANT_ID).addAttribute("id", by.occupantId());
        }
        if (by.reason() != null) {
            parent.addElement(QName.get("reason", Namespaces.RETRACT)).setText(by.reason());
        }
    }
}
