package com.igniterealtime.openfire.plugin.moderation;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.muc.MUCEventDispatcher;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.function.Consumer;

/**
 * Openfire plugin entry point for XEP-0425 Moderated Message Retraction.
 *
 * <p>Owns the lifecycle and nothing else: the interceptor that picks requests out of the stream,
 * the handler that carries them out, the join listener that re-sends recent moderations to
 * occupants who were offline, and the {@code urn:xmpp:message-moderate:1} disco feature on
 * every MUC service, which is what makes clients offer the action at all. Core lists a service's
 * extra features on its rooms too, so one entry per service covers every room on it.</p>
 *
 * <p>A MUC service created after the plugin loaded does not advertise the feature until the next
 * plugin reload or server restart. Requests to it still work; clients just do not offer them.</p>
 */
public class ModerationPlugin implements Plugin {

    private static final Logger Log = LoggerFactory.getLogger(ModerationPlugin.class);

    private ModerationReplay replay;
    private ModerationHandler handler;
    private ModerationInterceptor interceptor;
    /**
     * Called by Openfire whenever an administrator changes the switch, on Openfire's thread: an
     * exception escaping here would surface in the property change rather than in this plugin.
     */
    private final Consumer<Boolean> onEnabledChange = enabled -> {
        try {
            advertise(enabled);
        } catch (Exception e) {
            Log.warn("Could not update the moderation disco feature to enabled={}", enabled, e);
        }
    };

    /**
     * Registers everything, or nothing. If a step fails, the steps before it are undone before the
     * failure is passed on: Openfire does not call {@link #destroyPlugin} for a plugin whose
     * initialization threw, so without this a registered interceptor would go on consuming
     * moderation requests, with nobody able to unload it short of a restart (CERT ERR03-J).
     */
    @Override
    public void initializePlugin(PluginManager manager, File pluginDirectory) {
        try {
            replay = new ModerationReplay();
            handler = new ModerationHandler(replay);
            handler.backfillMonitoring();
            interceptor = new ModerationInterceptor(handler);
            InterceptorManager.getInstance().addInterceptor(interceptor);
            MUCEventDispatcher.addListener(replay);
            ModerationProperties.ENABLED.addListener(onEnabledChange);
            advertise(ModerationProperties.ENABLED.getValue());
        } catch (Throwable failure) {
            // Rethrown unchanged, so nothing is swallowed. Error is included on purpose: a
            // NoClassDefFoundError from an incompatible Openfire is the likeliest way to get here.
            try {
                destroyPlugin();
            } catch (Throwable rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
        Log.info("Message Moderation started (XEP-0425, {}); enabled: {}",
            Namespaces.MODERATE, ModerationProperties.ENABLED.getValue());
    }

    /**
     * Also the rollback for a failed {@link #initializePlugin}, so every step tolerates the part
     * of the plugin it undoes never having been set up.
     */
    @Override
    public void destroyPlugin() {
        // Each step guarded on its own: one failing must not leave the next undone. The interceptor
        // goes first because it is the one that does harm if left behind — it would keep consuming
        // requests with no handler behind it. The steps that touch ModerationProperties come last
        // and also tolerate a LinkageError: if that class never initialized, which is one way a
        // start can fail, there is nothing of it to undo, and the error must not end the cleanup.
        if (interceptor != null) {
            try {
                InterceptorManager.getInstance().removeInterceptor(interceptor);
            } catch (Exception e) {
                Log.warn("Could not remove the moderation interceptor", e);
            }
            interceptor = null;
        }
        if (handler != null) {
            handler.shutdown();
            handler = null;
        }
        if (replay != null) {
            try {
                MUCEventDispatcher.removeListener(replay);
            } catch (Exception e) {
                Log.warn("Could not remove the join listener", e);
            }
            replay.shutdown();
            replay = null;
        }
        try {
            ModerationProperties.ENABLED.removeListener(onEnabledChange);
        } catch (Exception | LinkageError e) {
            Log.warn("Could not remove the property listener", e);
        }
        try {
            advertise(false);
        } catch (Exception e) {
            Log.warn("Could not withdraw the moderation disco feature", e);
        }
    }

    /**
     * Adds or withdraws the disco feature on every MUC service. Always removes first: core keeps
     * extra features in a list, so adding twice would advertise the feature twice.
     */
    private static void advertise(Boolean enabled) {
        for (MultiUserChatService service :
                XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServices()) {
            service.removeExtraFeature(Namespaces.MODERATE);
            if (Boolean.TRUE.equals(enabled)) service.addExtraFeature(Namespaces.MODERATE);
        }
    }
}
