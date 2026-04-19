package com.tencent.shadow.coding.java_build_config;

/**
 * Inlined replacement for Shadow's {@code coding/java-build-config} module's
 * generated BuildConfig. Originally produced by a tiny Groovy task that
 * reflected `project.VERSION_NAME` into a Java string constant. The
 * value is read at runtime by {@code PluginContainerActivity} to detect
 * loader/runtime version mismatch between host and plugin processes.
 *
 * Bumped explicitly any time we want to force-restart all plugin processes
 * (e.g. after a host-side runtime change that the plugin Activities can't
 * survive).
 */
public final class BuildConfig {
    public static final String VERSION_NAME = "vibeapp-shadow-1";

    private BuildConfig() {}
}
