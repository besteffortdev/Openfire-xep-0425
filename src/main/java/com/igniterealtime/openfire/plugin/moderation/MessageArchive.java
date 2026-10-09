package com.igniterealtime.openfire.plugin.moderation;

import com.igniterealtime.openfire.plugin.moderation.ConversationLog.Entry;
import org.dom4j.Element;
import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.stanzaid.StanzaIDUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Monitoring plugin's own archive: its {@code ofMessageArchive} table, which holds a second
 * copy of every room message and is what Monitoring serves MUC MAM from by default. Only with
 * {@code conversation.database.use-openfire-tables} set does it read core's
 * {@link ConversationLog} instead, and that is off unless an administrator turns it on.
 *
 * <p>Rewriting only core's copy therefore left the text in MAM: a client that cleared its local
 * history and fetched the room again got the moderated message back. Both copies are now rewritten
 * the same way — {@code body} to NULL, {@code stanza} to a tombstone — and Monitoring, which keeps
 * rows where {@code stanza IS NOT NULL OR body IS NOT NULL} and builds the result from
 * {@code stanza} whenever it is set, serves the tombstone in the original's place.</p>
 *
 * <p>Monitoring is a separate plugin with its own class loader, so it is reached only through its
 * table, by plain SQL, and through one reflective call for {@link #availabilityETA}. The table is
 * used whenever Monitoring's schema is installed, whether or not the plugin is loaded right now:
 * the text must not stay in the database for the next time it is.</p>
 *
 * <p>Verified against Monitoring 2.8.0: the {@code INSERT} in {@code ConversationManager}, and the
 * stanza-id lookup and page query in {@code MucMamPersistenceManager} and
 * {@code PaginatedMucMessageDatabaseQuery}, which this class mirrors.</p>
 */
final class MessageArchive {

    private static final Logger Log = LoggerFactory.getLogger(MessageArchive.class);

    /**
     * Monitoring's canonical plugin name, which is also the {@code databaseKey} its schema is
     * registered under in {@code ofVersion}.
     */
    private static final String MONITORING = "monitoring";

    private static final String INSTALLED = "SELECT version FROM ofVersion WHERE name=?";

    /**
     * Rows of this room's public messages whose stanza mentions the id; the match is confirmed by
     * parsing, as in {@link ConversationLog#find}. {@code isPMforJID IS NULL} leaves out private
     * messages sent through the room, which Monitoring files under the same {@code toJID} and
     * which are not the room's to moderate. The table has no key: {@code messageID} is nullable,
     * and a row without one can be neither paged to by MAM nor addressed here.
     */
    private static final String FIND =
        "SELECT messageID, stanza FROM ofMessageArchive "
            + "WHERE toJID=? AND isPMforJID IS NULL AND messageID IS NOT NULL AND stanza LIKE ? ESCAPE '!'";

    private static final String TOMBSTONE =
        "UPDATE ofMessageArchive SET body=NULL, stanza=? WHERE toJID=? AND messageID=? AND isPMforJID IS NULL";

    private MessageArchive() {
    }

    /**
     * @return whether Monitoring's tables exist
     * @throws SQLException if the database cannot be read
     */
    static boolean installed() throws SQLException {
        try (Connection con = DbConnectionManager.getConnection();
             PreparedStatement ps = con.prepareStatement(INSTALLED)) {
            ps.setString(1, MONITORING);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * @param room     the room's JID, which must be the {@code by} of the matching stanza-id
     * @param stanzaId the id to look for; already validated by {@link ModerationRequest}
     * @return every archived copy the room stamped with that id; normally one, empty if Monitoring
     *         did not archive the message (room archiving off for this room, or stanzas not kept)
     * @throws SQLException if the database cannot be read
     */
    static List<Entry> find(JID room, String stanzaId) throws SQLException {
        List<Entry> found = new ArrayList<>();
        try (Connection con = DbConnectionManager.getConnection();
             PreparedStatement ps = con.prepareStatement(FIND)) {
            ps.setString(1, room.toBareJID());
            ps.setString(2, "%" + ConversationLog.likeEscape(stanzaId) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Optional<Message> stanza = ConversationLog.parse(DbConnectionManager.getLargeTextField(rs, 2));
                    if (stanza.isPresent() && Retraction.carries(stanza.get(), room, stanzaId)) {
                        found.add(new Entry(rs.getLong(1), stanza.get()));
                    }
                }
            }
        }
        return found;
    }

    /**
     * Replaces the row's content with {@code tombstone}.
     *
     * @return whether a row was updated
     * @throws SQLException if the database cannot be written
     */
    static boolean replace(JID room, long messageId, Message tombstone) throws SQLException {
        try (Connection con = DbConnectionManager.getConnection();
             PreparedStatement ps = con.prepareStatement(TOMBSTONE)) {
            ps.setString(1, tombstone.toXML());
            ps.setString(2, room.toBareJID());
            ps.setLong(3, messageId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * How long until Monitoring has written everything it received up to {@code instant}.
     * Monitoring queues its writes like core does, and its own MAM handler waits on this same call
     * before it queries.
     *
     * @return the wait, or null when there is nothing to wait for: Monitoring is not loaded on this
     *         node, or its API has changed — logged, and the lookup then runs without waiting
     */
    static Duration availabilityETA(Instant instant) {
        Optional<Plugin> monitoring =
            XMPPServer.getInstance().getPluginManager().getPluginByCanonicalName(MONITORING);
        if (monitoring.isEmpty()) return null;
        try {
            Object manager = monitoring.get().getClass().getMethod("getConversationManager").invoke(monitoring.get());
            if (manager == null) return null;
            return (Duration) manager.getClass().getMethod("availabilityETA", Instant.class).invoke(manager, instant);
        } catch (ReflectiveOperationException | ClassCastException e) {
            Log.warn("Could not ask Monitoring how far its archive has got; looking up without waiting", e);
            return null;
        }
    }

    /**
     * Rewrites Monitoring's copy of every message this plugin has already moderated, for the
     * moderations made before this class existed and any whose copy was not yet written when the
     * moderation ran. Each gets the same {@code <retracted/>} as core's tombstone, so it is the
     * same record of the same moderation; a copy already tombstoned is left alone, which makes
     * this safe to run on every start and on every node of a cluster at once.
     *
     * @return how many rows were rewritten
     * @throws SQLException if the database cannot be read or written
     */
    static int backfill() throws SQLException {
        if (!installed()) return 0;
        int rewritten = 0;
        for (Message tombstone : ConversationLog.allModerations()) {
            Element retracted = Retraction.moderationOf(tombstone).orElse(null);
            // Core stores the message as the room sent it, from room@service/nick.
            JID room = tombstone.getFrom() == null ? null : tombstone.getFrom().asBareJID();
            if (retracted == null || room == null) continue;
            String stanzaId = StanzaIDUtil.findFirstUniqueAndStableStanzaID(tombstone, room.toBareJID());
            if (stanzaId == null) continue;
            for (Entry copy : find(room, stanzaId)) {
                if (Retraction.moderationOf(copy.stanza()).isEmpty()
                        && replace(room, copy.messageId(), Retraction.tombstone(copy.stanza(), retracted))) {
                    rewritten++;
                }
            }
        }
        return rewritten;
    }
}
