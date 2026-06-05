package ac.grim.grimac.modifications;

import ac.grim.grimac.api.config.ConfigManager;

/**
 * Reloads all 2b2t.org.ru fork config-driven modifications from config.yml.
 */
public final class Fork2b2tConfig {

    private Fork2b2tConfig() {
    }

    public static void reloadAll(ConfigManager config) {
        MovementLimits2b2tModifications.reload(config);
        Strafe2b2tModifications.reload(config);
        Step2b2tModifications.reload(config);
        Boat2b2tModifications.reload(config);
        Movement2b2tModifications.reload(config);
        Elytra2b2tModifications.reload(config);
        Fly2b2tModifications.reload(config);
        Speed2b2tModifications.reload(config);
        Crystal2b2tModifications.reload(config);
        AntiPearlPhase2b2tModifications.reload(config);
        Breaking2b2tModifications.reload(config);
    }
}
