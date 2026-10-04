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
    private final Consumer<Boolean> onEnabledChange = ModerationPlugin::advertise;

    @Override
    public void initializePlugin(PluginManager manager, File pluginDirectory) {
        replay = new ModerationReplay();
        handler = new ModerationHandler(replay);
        interceptor = new ModerationInterceptor(handler);
        InterceptorManager.getInstance().addInterceptor(interceptor);
        MUCEventDispatcher.addListener(replay);
        ModerationProperties.ENABLED.addListener(onEnabledChange);
        advertise(ModerationProperties.ENABLED.getValue());
        Log.info("Message Moderation started (XEP-0425, {}); enabled: {}",
            Namespaces.MODERATE, ModerationProperties.ENABLED.getValue());
    }

    @Override
    public void destroyPlugin() {
        // Each step guarded on its own: one failing must not leave the next undone, least of all
        // the interceptor removal, which would keep consuming requests with no handler behind it.
        try {
            ModerationProperties.ENABLED.removeListener(onEnabledChange);
        } catch (Exception e) {
            Log.warn("Could not remove the property listener", e);
        }
        if (interceptor != null) {
            try {
                InterceptorManager.getInstance().removeInterceptor(interceptor);
            } catch (Exception e) {
                Log.warn("Could not remove the moderation interceptor", e);
            }
            interceptor = null;
        }
        try {
            advertise(false);
        } catch (Exception e) {
            Log.warn("Could not withdraw the moderation disco feature", e);
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
