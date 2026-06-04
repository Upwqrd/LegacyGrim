package ac.grim.grimac.modifications;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;

/**
 * 2b2t.org.ru fork: crystal helpers (rate limit removed).
 */
public final class Crystal2b2tModifications {

    private static final String PREFIX = "Crystal2b2t.";

    public static boolean enabled = false;

    private Crystal2b2tModifications() {
    }

    public static void reload(ConfigManager config) {
        enabled = config.getBooleanElse(PREFIX + "enabled", false);
    }

    public static boolean isEndCrystalPlace(BlockPlace place) {
        if (place == null) {
            return false;
        }
        return place.itemStack != null && place.itemStack.getType() == ItemTypes.END_CRYSTAL;
    }

    public static boolean isEndCrystalItem(ItemStack stack) {
        return stack != null && stack.getType() == ItemTypes.END_CRYSTAL;
    }

    public static void markCrystalPlaceTick(GrimPlayer player) {
        player.packetStateData.crystalPlaceThisTick = true;
    }

    public static void clearCrystalPlaceTick(GrimPlayer player) {
        player.packetStateData.crystalPlaceThisTick = false;
    }
}
