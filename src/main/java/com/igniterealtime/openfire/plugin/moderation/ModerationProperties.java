package com.igniterealtime.openfire.plugin.moderation;

import org.jivesoftware.util.SystemProperty;

/**
 * The plugin's configuration: a single switch, listed by Openfire under Server Manager -> System
 * Properties. There is deliberately nothing else to configure — who may moderate is decided by the
 * room's own roles, which room owners already manage.
 */
final class ModerationProperties {

    /**
     * Must equal {@code <name>} in plugin.xml: Openfire removes a plugin's properties on unload by
     * that name, and a mismatch makes the next hot-reload fail with "A SystemProperty already
     * exists with a key of ...".
     */
    static final String PLUGIN = "Message Moderation";

    /**
     * On by default: installing the plugin is the decision to offer moderation. Moderation still
     * needs the moderator role in the room, so nobody gains a power they did not have — a moderator
     * could already kick the author; now they can also remove the message. Switching it off
     * withdraws the disco feature at once, so clients stop offering the action.
     */
    static final SystemProperty<Boolean> ENABLED = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("plugin.messagemoderation.enabled")
        .setDefaultValue(true)
        .setDynamic(true)
        .setPlugin(PLUGIN)
        .build();

    private ModerationProperties() {
    }
}
