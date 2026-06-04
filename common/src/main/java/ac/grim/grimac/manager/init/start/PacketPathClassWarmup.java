package ac.grim.grimac.manager.init.start;

import ac.grim.grimac.utils.anticheat.LogUtil;

/**
 * Canvas/Folia: warm packet-path classes on the main thread after the plugin is enabled.
 * Must not load Bukkit platform classes (e.g. {@code BukkitPlatformPlayer} static init registers events).
 */
public final class PacketPathClassWarmup implements StartableInitable {

    private static final String[] SAFE_CLASSES = {
            "ac.grim.grimac.events.packets.CheckManagerListener",
            "ac.grim.grimac.utils.anticheat.update.BlockPlace",
            "ac.grim.grimac.utils.data.BlockPlaceSnapshot",
            "ac.grim.grimac.utils.math.TrigHandler",
            "ac.grim.grimac.utils.collisions.CollisionData",
            "ac.grim.grimac.modifications.MovementLimits2b2tModifications",
            "ac.grim.grimac.modifications.Speed2b2tModifications",
            "ac.grim.grimac.modifications.Breaking2b2tModifications",
    };

    private static volatile boolean warmed;

    @Override
    public void start() {
        if (warmed) {
            return;
        }
        warmed = true;

        ClassLoader loader = PacketPathClassWarmup.class.getClassLoader();
        int loaded = 0;
        for (String name : SAFE_CLASSES) {
            try {
                Class.forName(name, true, loader);
                loaded++;
            } catch (Throwable ignored) {
                // First real packet use will retry
            }
        }
        LogUtil.info("Packet-path class warmup: " + loaded + "/" + SAFE_CLASSES.length + " loaded.");
    }
}
