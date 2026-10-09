package com.igniterealtime.openfire.plugin.moderation;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.io.SAXReader;
import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.util.StringUtils;
import org.xml.sax.SAXException;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * The room's archive in core: the {@code ofMucConversationLog} table, which join history is
 * reloaded from after a restart, and which the Monitoring plugin serves MUC MAM from <em>only</em>
 * when {@code conversation.database.use-openfire-tables} is true. By default Monitoring serves MAM
 * from its own copy instead, which {@link MessageArchive} rewrites.
 *
 * <p>There is no table of this plugin's own and nothing to install. A moderation rewrites the
 * moderated row in place — {@code body} to NULL, {@code stanza} to the tombstone — and leaves
 * everything else as it was, so the row keeps its {@code messageID} (MAM paging), {@code logTime}
 * and {@code nickname}. Monitoring builds the MAM result from {@code stanza} whenever it is set and
 * does not filter on {@code body}, so a client paging through the archive gets the tombstone in the
 * original's place; and full-text search, which matches on {@code body}, no longer finds the text.
 * The tombstones are also this plugin's durable record of every moderation, which the join replay
 * and {@link MessageArchive#backfill} read back.</p>
 *
 * <p>The SQL is plain enough to run unchanged on every database Openfire supports. {@code ESCAPE}
 * is standard SQL. The stanza is written with plain {@code setString} and read back with
 * {@link DbConnectionManager#getLargeTextField}, exactly as core's own insert into and Monitoring's
 * reads from this table do.</p>
 */
final class ConversationLog {

    /**
     * Rows of this room whose stanza mentions the id. The match is confirmed by parsing — see
     * {@link #find} — so this only has to be selective, not exact. It is the same lookup the
     * Monitoring plugin does to resolve a MAM {@code after}/{@code before} id.
     */
    private static final String FIND =
        "SELECT messageID, stanza FROM ofMucConversationLog "
            + "WHERE roomID=? AND messageID IS NOT NULL AND stanza LIKE ? ESCAPE '!'";

    private static final String TOMBSTONE =
        "UPDATE ofMucConversationLog SET body=NULL, stanza=? WHERE roomID=? AND messageID=?";

    /**
     * The room's tombstones, newest first. {@code nickname IS NOT NULL} keeps out the rows core
     * logs for the room's own announcements, and {@code body IS NULL} keeps out every ordinary
     * message, so the {@code LIKE} only has to look at a handful of rows. {@code logTime} is a
     * zero-padded millisecond string, bound the way core binds it in its own history query.
     */
    private static final String RECENT_TOMBSTONES =
        "SELECT stanza FROM ofMucConversationLog "
            + "WHERE roomID=? AND logTime>? AND nickname IS NOT NULL AND body IS NULL "
            + "AND stanza LIKE ? ESCAPE '!' ORDER BY logTime DESC";

    /**
     * Every room's tombstones, for {@link MessageArchive#backfill}. Matched on the moderation
     * namespace rather than the retraction one, so only this plugin's own tombstones are read.
     */
    private static final String ALL_MODERATIONS =
        "SELECT stanza FROM ofMucConversationLog "
            + "WHERE nickname IS NOT NULL AND body IS NULL AND stanza LIKE ? ESCAPE '!'";

    private static final char LIKE_ESCAPE = '!';

    /**
     * @param messageId the row's key within the room
     * @param stanza    the message as archived
     */
    record Entry(long messageId, Message stanza) {
    }

    private ConversationLog() {
    }

    /**
     * @param roomId   {@code MUCRoom.getID()}
     * @param room     the room's JID, which must be the {@code by} of the matching stanza-id
     * @param stanzaId the id to look for; already validated by {@link ModerationRequest}
     * @return the archived message the room stamped with that id, if it was logged
     * @throws SQLException if the database cannot be read
     */
    static Optional<Entry> find(long roomId, JID room, String stanzaId) throws SQLException {
        try (Connection con = DbConnectionManager.getConnection();
             PreparedStatement ps = con.prepareStatement(FIND)) {
            ps.setLong(1, roomId);
            ps.setString(2, "%" + likeEscape(stanzaId) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String xml = DbConnectionManager.getLargeTextField(rs, 2);
                    Optional<Message> stanza = parse(xml);
                    // A body or a reply quote can contain the id too; only the room's own
                    // stanza-id on the message itself is a match.
                    if (stanza.isPresent() && Retraction.carries(stanza.get(), room, stanzaId)) {
                        return Optional.of(new Entry(rs.getLong(1), stanza.get()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Replaces the row's content with {@code tombstone}.
     *
     * @return whether a row was updated
     * @throws SQLException if the database cannot be written
     */
    static boolean replace(long roomId, long messageId, Message tombstone) throws SQLException {
        try (Connection con = DbConnectionManager.getConnection();
             PreparedStatement ps = con.prepareStatement(TOMBSTONE)) {
            ps.setString(1, tombstone.toXML());
            ps.setLong(2, roomId);
            ps.setLong(3, messageId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * @param roomId {@code MUCRoom.getID()}
     * @param since  only messages logged after this; the age of the <em>message</em>, not of the
     *               moderation, because that is what the table is indexed and ordered by
     * @param max    at most this many, the most recent
     * @return the tombstones, oldest first — the order they happened in
     * @throws SQLException if the database cannot be read
     */
    static List<Message> recentTombstones(long roomId, Date since, int max) throws SQLException {
        List<Message> tombstones = new ArrayList<>();
        try (Connection con = DbConnectionManager.getConnection();
             PreparedStatement ps = con.prepareStatement(RECENT_TOMBSTONES)) {
            ps.setMaxRows(max);
            ps.setLong(1, roomId);
            ps.setString(2, StringUtils.dateToMillis(since));
            ps.setString(3, "%" + likeEscape(Namespaces.RETRACT) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parse(DbConnectionManager.getLargeTextField(rs, 1)).ifPresent(tombstones::add);
                }
            }
        }
        Collections.reverse(tombstones);
        return tombstones;
    }

    /**
     * @return the tombstone of every moderation this plugin has made, in every room, in no
     *         particular order
     * @throws SQLException if the database cannot be read
     */
    static List<Message> allModerations() throws SQLException {
        List<Message> tombstones = new ArrayList<>();
        try (Connection con = DbConnectionManager.getConnection();
             PreparedStatement ps = con.prepareStatement(ALL_MODERATIONS)) {
            ps.setString(1, "%" + likeEscape(Namespaces.MODERATE) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parse(DbConnectionManager.getLargeTextField(rs, 1)).ifPresent(tombstones::add);
                }
            }
        }
        return tombstones;
    }

    /** Makes {@code value} match only itself inside a {@code LIKE ... ESCAPE '!'} pattern. */
    static String likeEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 4);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) sb.append(LIKE_ESCAPE);
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * @return the stored stanza, or empty if the column holds nothing parseable — an old row from
     *         before Openfire stored stanzas, which also cannot carry a stanza-id
     */
    static Optional<Message> parse(String xml) {
        if (xml == null || xml.isBlank()) return Optional.empty();
        try {
            Document doc = hardenedReader().read(new StringReader(xml));
            return Optional.of(new Message(doc.getRootElement(), true));
        } catch (DocumentException | SAXException e) {
            return Optional.empty();
        }
    }

    /**
     * The column was written by Openfire, but its content is message text from any occupant, so
     * it gets the same XXE hardening as anything off the wire (CERT IDS17-J): no DOCTYPE at all,
     * which rules out external entities and entity expansion together.
     */
    private static SAXReader hardenedReader() throws SAXException {
        SAXReader reader = new SAXReader();
        reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return reader;
    }
}
