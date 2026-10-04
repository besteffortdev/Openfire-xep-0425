package com.igniterealtime.openfire.plugin.moderation;

import org.jivesoftware.openfire.muc.HistoryStrategy;
import org.jivesoftware.util.CacheableOptional;
import org.jivesoftware.util.cache.Cache;
import org.jivesoftware.util.cache.CacheFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.function.UnaryOperator;

/**
 * The room's join history — the last N messages a client is sent when it enters the room.
 *
 * <p>Openfire keeps it in the clustered cache {@value #CACHE_NAME}, keyed by room JID, and the only
 * public API on top of it ({@code MUCRoomHistory}) can append, read a copy, or purge everything.
 * Replacing one entry therefore means going to the cache directly. That is done the way core's own
 * {@code HistoryStrategy.addMessage} does it — read-modify-write under {@link Cache#getLock} for
 * the room — so a message that arrives while the history is being rewritten is neither lost nor
 * duplicated, and on a cluster the rewritten value is what every node sees.</p>
 *
 * <p>This is the one place the plugin depends on something below Openfire's public API: the cache
 * name and its value type. Both are identical in 5.0.5 and 5.1.0. If a later release changes
 * either, {@link #replace} finds no matching entry and the moderation still succeeds everywhere
 * else; {@link ModerationHandler} logs that the history was not rewritten, which is the signal to
 * revisit this class.</p>
 */
final class RoomHistory {

    /** Core's name for the cache {@code HistoryStrategy} keeps the join history in. */
    static final String CACHE_NAME = "MUC History";

    private RoomHistory() {
    }

    /** @return the first history entry the room stamped with {@code stanzaId}, if any */
    static Optional<Message> find(Iterator<Message> history, JID room, String stanzaId) {
        while (history.hasNext()) {
            Message message = history.next();
            if (Retraction.carries(message, room, stanzaId)) return Optional.of(message);
        }
        return Optional.empty();
    }

    /**
     * Replaces the history entry carrying {@code stanzaId} with {@code rewrite} of it.
     *
     * @return whether an entry was replaced. False when the room's history is not loaded into the
     *         cache at all, which is fine: it is then loaded from the archive on next use, and the
     *         archive has already been tombstoned.
     */
    static boolean replace(JID room, String stanzaId, UnaryOperator<Message> rewrite) {
        Cache<JID, CacheableOptional<HistoryStrategy.Messages>> cache = CacheFactory.createCache(CACHE_NAME);
        JID key = room.asBareJID();
        Lock lock = cache.getLock(key);
        lock.lock();
        try {
            CacheableOptional<HistoryStrategy.Messages> entry = cache.get(key);
            if (entry == null || entry.isAbsent()) return false;

            // Rebuilt in the original order with Type.all, which appends without trimming: the
            // history keeps exactly the entries it had, one of them replaced.
            HistoryStrategy.Messages rewritten = new HistoryStrategy.Messages();
            boolean replaced = false;
            for (Message message : entry.get().asCollection()) {
                boolean target = !replaced && Retraction.carries(message, key, stanzaId);
                rewritten.add(target ? rewrite.apply(message) : message, HistoryStrategy.Type.all, 0);
                replaced |= target;
            }
            if (replaced) cache.put(key, CacheableOptional.of(rewritten));
            return replaced;
        } finally {
            lock.unlock();
        }
    }
}
