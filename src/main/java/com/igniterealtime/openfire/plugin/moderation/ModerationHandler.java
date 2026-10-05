package com.igniterealtime.openfire.plugin.moderation;

import com.igniterealtime.openfire.plugin.moderation.Retraction.ModeratedBy;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.archive.Archiver;
import org.jivesoftware.openfire.muc.MUCOccupant;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.muc.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.PacketError;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Carries out a moderation request: authorize, find the message, tombstone it in the archive and
 * the join history, tell the room, answer the moderator.
 *
 * <h2>Why off the calling thread</h2>
 * The request is intercepted on the thread reading the moderator's connection, and the message it
 * names may not be in the database yet: core logs room messages in batches through an
 * {@link Archiver}, so a moderator quick to react can name a message that is still in that queue.
 * Waiting for it there would stall everything else that connection sends. So requests are queued
 * to a single worker thread, which also means two moderations of the same room never interleave.
 *
 * <h2>Order of effects</h2>
 * The archive is rewritten first, then the history, then the room is told, then the moderator gets
 * the result. Every step that can fail comes before the announcement, so a failure leaves clients
 * still showing the message — wrong, but visibly so, and the moderator gets an error and can retry
 * — rather than clients deleting a message the server still serves to the next joiner.
 */
final class ModerationHandler {

    private static final Logger Log = LoggerFactory.getLogger(ModerationHandler.class);

    /**
     * Longest to wait for core's conversation log to reach the request. The batch interval is
     * about a second by default; this allows for a busy server without leaving a moderator's
     * client waiting past its own IQ timeout (Conversations' is 30 s).
     */
    private static final Duration MAX_ARCHIVE_WAIT = Duration.ofSeconds(10);

    private static final long ARCHIVE_POLL_MILLIS = 250;

    /**
     * Requests waiting for the worker. Moderation is rare and quick, so a full queue means
     * something is wrong — a stuck database, or a client sending in a loop — and the right answer
     * is to refuse with resource-constraint rather than queue without bound.
     */
    private static final int QUEUE_CAPACITY = 64;

    /**
     * Longest {@link #shutdown} waits for a moderation in progress. A plugin's classes must not
     * keep running after Openfire has unloaded it, but an unload must not hang on a stuck database
     * either.
     */
    static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(5);

    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
        task -> {
            Thread thread = new Thread(task, "message-moderation");
            thread.setDaemon(true);
            return thread;
        });

    /** Told about every moderation, so occupants who are offline now get it when they rejoin. */
    private final ModerationReplay replay;

    ModerationHandler(ModerationReplay replay) {
        this.replay = replay;
    }

    /** Queues {@code request}; it is always answered, whether or not it can be carried out. */
    void submit(ModerationRequest request) {
        Instant received = Instant.now();
        try {
            worker.execute(() -> run(request, received));
        } catch (RejectedExecutionException e) {
            Log.warn("Moderation queue full; refusing request from {} in {}",
                request.requester(), request.room());
            sendError(request.room(), request.requester(), request.iqId(),
                PacketError.Condition.resource_constraint);
        }
    }

    void shutdown() {
        stop(worker, "Moderation worker");
    }

    /**
     * Stops {@code executor} and waits up to {@link #SHUTDOWN_WAIT} for its task in progress.
     * Shared with {@link ModerationReplay}, so the plugin's two threads end the same way.
     */
    static void stop(ExecutorService executor, String name) {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                Log.warn("{} did not stop within {} s", name, SHUTDOWN_WAIT.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The barrier: whatever happens, the requester gets exactly one answer and the worker survives
     * to take the next request. This is the broad catch that has to stay broad.
     */
    private void run(ModerationRequest request, Instant received) {
        try {
            moderate(request, received);
            sendResult(request);
        } catch (ModerationException e) {
            Log.info("Refused moderation request from {} in {}: {}",
                request.requester(), request.room(), e.getMessage());
            sendError(request.room(), request.requester(), request.iqId(), e.condition());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(request.room(), request.requester(), request.iqId(),
                PacketError.Condition.service_unavailable);
        } catch (Exception e) {
            Log.error("Moderation request from {} in {} failed", request.requester(), request.room(), e);
            sendError(request.room(), request.requester(), request.iqId(),
                PacketError.Condition.internal_server_error);
        }
    }

    private void moderate(ModerationRequest request, Instant received)
            throws ModerationException, SQLException, InterruptedException {
        JID roomJid = request.room();
        String stanzaId = request.stanzaId();

        MultiUserChatService service =
            XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(roomJid);
        MUCRoom room = service == null ? null : service.getChatRoom(roomJid.getNode());
        if (room == null) {
            throw new ModerationException(PacketError.Condition.item_not_found, "no such room");
        }

        // Authorization: the role the sender holds in this room right now. Not affiliation — an
        // owner who has been demoted to participant for the session is not a moderator, and a
        // moderator by role need not be an owner or admin.
        MUCOccupant moderator = room.getOccupantByFullJID(request.requester());
        if (moderator == null) {
            throw new ModerationException(PacketError.Condition.forbidden, "requester is not in the room");
        }
        if (moderator.getRole() != Role.moderator) {
            throw new ModerationException(PacketError.Condition.forbidden,
                "requester's role is " + moderator.getRole() + ", not moderator");
        }

        Optional<Message> inHistory =
            RoomHistory.find(room.getRoomHistory().getMessageHistory(), roomJid, stanzaId);
        Optional<ConversationLog.Entry> inArchive = Optional.empty();
        if (room.isLogEnabled()) {
            awaitArchive(service, received);
            inArchive = ConversationLog.find(room.getID(), roomJid, stanzaId);
        }

        Message original = inArchive.map(ConversationLog.Entry::stanza).or(() -> inHistory)
            .orElseThrow(() -> new ModerationException(PacketError.Condition.item_not_found,
                "no message with stanza-id " + stanzaId + " in history or archive"));
        if (Retraction.isSubjectChange(original)) {
            throw new ModerationException(PacketError.Condition.not_allowed,
                "stanza-id " + stanzaId + " is a subject change");
        }

        ModeratedBy by = new ModeratedBy(moderator.getOccupantJID(), moderator.getOccupantId(),
            request.reason());
        Instant now = Instant.now();

        boolean archived = false;
        if (inArchive.isPresent()) {
            ConversationLog.Entry entry = inArchive.get();
            archived = ConversationLog.replace(room.getID(), entry.messageId(),
                Retraction.tombstone(entry.stanza(), by, now));
        }
        boolean history = inHistory.isPresent()
            && RoomHistory.replace(roomJid, stanzaId, m -> Retraction.tombstone(m, by, now));
        if (inHistory.isPresent() && !history) {
            // Found by reading the history but not where RoomHistory writes it: the cache layout
            // has moved under us. Everything else still happened; new joiners will see the text.
            Log.warn("Message {} in {} is in the join history but the history could not be "
                + "rewritten; see RoomHistory", stanzaId, roomJid);
        }

        room.broadcast(Retraction.announcement(roomJid, stanzaId, by));
        replay.forget(roomJid);

        // Who and where, never what: the moderated text stays out of the log (ACM 1.6).
        Log.info("{} moderated message {} from {} in {} (archive: {}, history: {})",
            moderator.getOccupantJID().getResource(), stanzaId,
            original.getFrom() == null ? null : original.getFrom().getResource(), roomJid,
            archived ? "tombstoned" : "not logged", history ? "tombstoned" : "not present");
    }

    /**
     * Waits until core's conversation log has stored everything up to {@code received}, or
     * {@link #MAX_ARCHIVE_WAIT} passes. On timeout it returns anyway: the lookup that follows
     * then finds the message in the history alone, or answers item-not-found, which a moderator
     * can retry — better than holding their request open indefinitely.
     */
    private static void awaitArchive(MultiUserChatService service, Instant received)
            throws InterruptedException {
        Archiver<?> archiver = service.getArchiver();
        Instant deadline = Instant.now().plus(MAX_ARCHIVE_WAIT);
        while (true) {
            Duration eta = archiver.availabilityETA(received);
            if (eta == null || eta.isZero() || eta.isNegative()) return;
            if (Instant.now().isAfter(deadline)) {
                Log.warn("Conversation log still {} ms behind after {} s; looking up anyway",
                    eta.toMillis(), MAX_ARCHIVE_WAIT.toSeconds());
                return;
            }
            Thread.sleep(Math.min(eta.toMillis() + 1, ARCHIVE_POLL_MILLIS));
        }
    }

    private static void sendResult(ModerationRequest request) {
        IQ result = new IQ(IQ.Type.result, request.iqId());
        result.setFrom(request.room());
        result.setTo(request.requester());
        XMPPServer.getInstance().getPacketRouter().route(result);
    }

    /**
     * Answers a request with an error. Static, because the interceptor also answers malformed
     * requests with it before a {@link ModerationRequest} exists.
     */
    static void sendError(JID room, JID requester, String iqId, PacketError.Condition condition) {
        IQ error = new IQ(IQ.Type.error, iqId);
        error.setFrom(room);
        error.setTo(requester);
        error.setError(condition);
        XMPPServer.getInstance().getPacketRouter().route(error);
    }
}
