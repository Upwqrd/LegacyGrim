package ac.grim.grimac.modifications;

import ac.grim.grimac.api.config.ConfigManager;

/**
 * Reloads all 2b2t.org.ru fork config-driven modifications from config.yml.
 */
public final class Fork2b2tConfig {

    private Fork2b2tConfig() {
    }

    public static void reloadAll(ConfigManager config) {
        Crystal2b2tModifications.reload(config);
        AntiPearlPhase2b2tModifications.reload(config);
        Movement2b2tModifications.reload(config);
        Fly2b2tModifications.reload(config);
    }
}
