package com.igniterealtime.openfire.plugin.moderation;

import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The plugin's lifecycle, where a server is not needed to see the failure that matters.
 */
class ModerationPluginTest {

    @Test
    @DisplayName("a start that fails part-way leaves no interceptor behind")
    void failedStartIsRolledBack() {
        // With no running server, ModerationProperties cannot initialize (building a SystemProperty
        // needs the plugin manager), so the start fails with an Error after the interceptor and
        // the join listener are registered. That is the partial start Openfire never cleans up,
        // since it does not call destroyPlugin for a plugin whose initialization threw — and the
        // same class failing again inside the rollback is what the first version of it missed.
        ModerationPlugin plugin = new ModerationPlugin();

        Throwable failure = assertThrows(Throwable.class, () -> plugin.initializePlugin(null, null));
        assertInstanceOf(LinkageError.class, failure, "not the partial start this test is about");

        assertFalse(InterceptorManager.getInstance().getInterceptors().stream()
                .anyMatch(ModerationInterceptor.class::isInstance),
            "the interceptor outlived a failed start, and would consume requests until a restart");
    }
}
