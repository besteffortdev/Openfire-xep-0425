package com.igniterealtime.openfire.plugin.moderation;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.muc.MUCEventListener;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Re-sends recent moderations to an occupant as they join, so a client that was offline when a
 * message was moderated still removes its copy.
 *
 * <h2>Why this is needed</h2>
 * The announcement only reaches the occupants in the room at that moment. A phone that had already
 * downloaded the message and then went offline is not one of them, and catching up does not help
 * it: Conversations acts on a retraction <em>only</em> when it arrives as a live message
 * ({@code MessageParser} checks the outer stanza for {@code <retract/>}, which a MAM result never
 * is), and joins with {@code maxstanzas='0'} when the room has MAM, so it gets no join history
 * either. The one thing it does act on is a live retraction after it has rejoined — which is what
 * this sends.
 *
 * <h2>Where the moderations come from</h2>
 * The tombstones {@link ModerationHandler} already wrote to the archive. Each one is the durable
 * record of a moderation (stanza-id, moderator, reason, time), so there is no separate table, the
 * replay survives a restart, and on a cluster every node sees the same list. A room with logging
 * off has no archive and nothing is replayed in it.
 *
 * <h2>What it costs</h2>
 * Every join triggers a replay, and mobile clients rejoin on every reconnect. So the list is
 * cached per room for {@link #CACHE_TTL} and dropped as soon as the room has a new moderation, and
 * a replay is at most {@link #MAX_REPLAYED} small stanzas. A client that never had the message
 * ignores the retraction ("Message not found"), so sending it to everyone who joins is harmless.
 */
final class ModerationReplay implements MUCEventListener {

    private static final Logger Log = LoggerFactory.getLogger(ModerationReplay.class);

    /**
     * {@code occupantJoined} fires from {@code MUCRoom.addOccupant}, before core has sent the
     * joiner its own presence. Waiting lets the join complete first, so the client already has the
     * room open when the retractions arrive.
     */
    private static final Duration JOIN_DELAY = Duration.ofSeconds(2);

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    /** The most recent moderations replayed per join. */
    static final int MAX_REPLAYED = 100;

    /** Joins waiting for their replay; past this a reconnect storm is shed rather than queued. */
    private static final int MAX_PENDING = 1000;

    private record Cached(List<Message> replays, Instant expires) {
    }

    private final Map<JID, Cached> cache = new ConcurrentHashMap<>();

    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "message-moderation-replay");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Drops the cached list for {@code room}; called after every moderation. A replay already
     * building its list at that instant can still cache the old one, for at most
     * {@link #CACHE_TTL} — and only for joins in that window, whose clients were in the room for
     * the live announcement or never had the message.
     */
    void forget(JID room) {
        cache.remove(room.asBareJID());
    }

    /** Pending replays are dropped; one already sending is given {@link ModerationHandler#SHUTDOWN_WAIT}. */
    void shutdown() {
        ModerationHandler.stop(scheduler, "Moderation replay");
        cache.clear();
    }

    @Override
    public void occupantJoined(JID roomJID, JID user, String nickname) {
        if (!ModerationProperties.ENABLED.getValue() || ModerationProperties.REPLAY_DAYS.getValue() <= 0) {
            return;
        }
        if (scheduler.getQueue().size() >= MAX_PENDING) {
            Log.debug("Replay queue full; not replaying moderations in {} to {}", roomJID, user);
            return;
        }
        scheduler.schedule(() -> replay(roomJID, user), JOIN_DELAY.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** The barrier for the scheduled task: one failed replay must not cancel the next. */
    private void replay(JID roomJid, JID user) {
        try {
            List<Message> replays = replaysFor(roomJid);
            for (Message replay : replays) {
                Message copy = replay.createCopy();
                copy.setTo(user);
                XMPPServer.getInstance().getPacketRouter().route(copy);
            }
            if (!replays.isEmpty()) {
                Log.debug("Replayed {} moderation(s) in {} to {}", replays.size(), roomJid, user);
            }
        } catch (Exception e) {
            Log.warn("Could not replay moderations in {} to {}", roomJid, user, e);
        }
    }

    private List<Message> replaysFor(JID roomJid) throws SQLException {
        JID key = roomJid.asBareJID();
        Instant now = Instant.now();
        Cached cached = cache.get(key);
        if (cached != null && now.isBefore(cached.expires())) return cached.replays();

        List<Message> replays = new ArrayList<>();
        MultiUserChatService service =
            XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(key);
        MUCRoom room = service == null ? null : service.getChatRoom(key.getNode());
        if (room != null && room.isLogEnabled()) {
            Date since = Date.from(now.minus(Duration.ofDays(ModerationProperties.REPLAY_DAYS.getValue())));
            for (Message tombstone : ConversationLog.recentTombstones(room.getID(), since, MAX_REPLAYED)) {
                Retraction.replayOf(key, tombstone).ifPresent(replays::add);
            }
        }
        List<Message> immutable = List.copyOf(replays);
        cache.put(key, new Cached(immutable, now.plus(CACHE_TTL)));
        return immutable;
    }

    // ── events this plugin has no use for ────────────────────────────────────────────────────────────

    @Override
    public void roomCreated(long roomID, JID roomJID) {
    }

    @Override
    public void roomDestroyed(long roomID, JID roomJID) {
        forget(roomJID);
    }

    @Override
    public void roomClearChatHistory(long roomID, JID roomJID) {
        forget(roomJID);
    }

    @Override
    public void occupantLeft(JID roomJID, JID user, String nickname) {
    }

    @Override
    public void occupantNickKicked(JID roomJID, String nickname) {
    }

    @Override
    public void nicknameChanged(JID roomJID, JID user, String oldNickname, String newNickname) {
    }

    @Override
    public void messageReceived(JID roomJID, JID user, String nickname, Message message) {
    }

    @Override
    public void privateMessageRecieved(JID toJID, JID fromJID, Message message) {
    }

    @Override
    public void roomSubjectChanged(JID roomJID, JID user, String newSubject) {
    }
}
