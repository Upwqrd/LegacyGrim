package ac.grim.grimac.modifications;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.CollisionData;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.defaulttags.BlockTags;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;

import java.util.Locale;

/**
 * Push players out of full solid blocks only (not panes, chests, sand, gravel, anvils, etc.).
 */
public final class AntiPearlPhase2b2tModifications {

    private static final String PREFIX = "AntiPearlPhase2b2t.";

    public static boolean enabled = true;
    public static boolean pushOnMovement = false;
    public static boolean pushOnPearlLand = true;
    public static int pearlCancelTargetDistance = 3;
    public static double pearlCancelDownPitchLimit = 45.0D;
    public static double phaseAboveFeet = 0.55D;

    private AntiPearlPhase2b2tModifications() {
    }

    public static void reload(ConfigManager config) {
        enabled = config.getBooleanElse(PREFIX + "enabled", true);
        pushOnMovement = config.getBooleanElse(PREFIX + "push-on-movement", false);
        pushOnPearlLand = config.getBooleanElse(PREFIX + "push-on-pearl-land", true);
        pearlCancelTargetDistance = Math.max(1, config.getIntElse(PREFIX + "pearl-cancel-target-distance", 3));
        pearlCancelDownPitchLimit = config.getDoubleElse(PREFIX + "pearl-cancel-down-pitch-limit", 45.0D);
        phaseAboveFeet = config.getDoubleElse(PREFIX + "phase-above-feet", 0.55D);
    }

    private static String blockId(StateType type) {
        return type.getName().toString().toLowerCase(Locale.ROOT);
    }

    /** Sand, gravel, concrete powder, anvils, soul sand — no push, not treated as wall phase. */
    private static boolean isFallingOrLooseBlock(StateType type) {
        if (BlockTags.ANVIL.contains(type)) {
            return true;
        }
        String id = blockId(type);
        if (id.equals("sand") || id.equals("red_sand") || id.equals("gravel")) {
            return true;
        }
        if (id.equals("suspicious_sand") || id.equals("suspicious_gravel")) {
            return true;
        }
        if (id.equals("soul_sand") || id.equals("soul_soil")) {
            return true;
        }
        if (id.contains("concrete_powder")) {
            return true;
        }
        if (id.endsWith("_sand") && !id.contains("sandstone")) {
            return true;
        }
        if (id.equals("dragon_egg") || id.equals("scaffolding")) {
            return true;
        }
        return false;
    }

    /** Partial / non-full blocks — glass panes, chests, farmland, slabs, etc. */
    private static boolean isPartialBlock(StateType type) {
        String id = blockId(type);
        if (id.contains("glass_pane") || id.equals("iron_bars") || id.equals("chain")) {
            return true;
        }
        if (id.contains("chest") || id.equals("barrel") || id.equals("ender_chest")) {
            return true;
        }
        if (id.equals("farmland") || id.equals("dirt_path") || id.equals("grass_path")) {
            return true;
        }
        if (id.endsWith("_slab") || id.endsWith("_stairs") || id.contains("trapdoor")) {
            return true;
        }
        if (id.contains("fence") || id.contains("_wall") || id.contains("door")) {
            return true;
        }
        if (id.contains("carpet") || id.equals("snow") || id.equals("powder_snow")) {
            return true;
        }
        if (id.contains("banner") || id.contains("sign") || id.contains("button") || id.contains("pressure_plate")) {
            return true;
        }
        if (id.equals("ladder") || id.equals("vine") || id.contains("candle")) {
            return true;
        }
        return false;
    }

    private static boolean isFullBlockCollision(CollisionBox blockBox) {
        if (blockBox.isFullBlock()) {
            return true;
        }
        SimpleCollisionBox[] parts = new SimpleCollisionBox[12];
        int count = blockBox.downCast(parts);
        for (int i = 0; i < count; i++) {
            SimpleCollisionBox part = parts[i];
            double h = part.maxY - part.minY;
            double w = part.maxX - part.minX;
            double d = part.maxZ - part.minZ;
            if (h >= 0.99D && w >= 0.99D && d >= 0.99D) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFullSolidPhaseBlock(GrimPlayer player, WrappedBlockState state, int bx, int by, int bz) {
        if (state == null) {
            return false;
        }
        StateType type = state.getType();
        if (type == StateTypes.AIR || type == StateTypes.CAVE_AIR || type == StateTypes.VOID_AIR) {
            return false;
        }
        if (type == StateTypes.WATER || type == StateTypes.LAVA) {
            return false;
        }
        if (isFallingOrLooseBlock(type) || isPartialBlock(type)) {
            return false;
        }

        CollisionBox blockBox = CollisionData.getData(type)
                .getMovementCollisionBox(player, player.getClientVersion(), state, bx, by, bz);
        if (blockBox == null || blockBox.isNull() || !isFullBlockCollision(blockBox)) {
            return false;
        }
        return true;
    }

    private static boolean blockIntersectsPlayerBody(GrimPlayer player, int bx, int by, int bz) {
        WrappedBlockState state = player.compensatedWorld.getBlock(bx, by, bz);
        if (!isFullSolidPhaseBlock(player, state, bx, by, bz)) {
            return false;
        }

        SimpleCollisionBox playerBox = player.boundingBox != null
                ? player.boundingBox
                : GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);

        double bodyMinY = player.y + phaseAboveFeet;
        SimpleCollisionBox bodyBox = new SimpleCollisionBox(
                playerBox.minX, bodyMinY, playerBox.minZ,
                playerBox.maxX, playerBox.maxY, playerBox.maxZ
        );

        CollisionBox blockBox = CollisionData.getData(state.getType())
                .getMovementCollisionBox(player, player.getClientVersion(), state, bx, by, bz);
        return blockBox != null && !blockBox.isNull() && blockBox.isIntersected(bodyBox);
    }

    public static boolean isPhasedInsideBlock(GrimPlayer player) {
        if (!enabled || player.inVehicle() || player.isFlying) {
            return false;
        }

        SimpleCollisionBox bb = player.boundingBox != null
                ? player.boundingBox
                : GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);

        int minBlockX = (int) Math.floor(bb.minX);
        int maxBlockX = (int) Math.floor(bb.maxX);
        int minBlockZ = (int) Math.floor(bb.minZ);
        int maxBlockZ = (int) Math.floor(bb.maxZ);
        int minBlockY = (int) Math.floor(bb.minY);
        int maxBlockY = (int) Math.floor(bb.maxY);

        for (int x = minBlockX; x <= maxBlockX; x++) {
            for (int z = minBlockZ; z <= maxBlockZ; z++) {
                for (int y = minBlockY; y <= maxBlockY; y++) {
                    if (blockIntersectsPlayerBody(player, x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static double[] findEscapeOnBlockTop(GrimPlayer player) {
        SimpleCollisionBox bb = player.boundingBox != null
                ? player.boundingBox
                : GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);

        int minBlockX = (int) Math.floor(bb.minX);
        int maxBlockX = (int) Math.floor(bb.maxX);
        int minBlockZ = (int) Math.floor(bb.minZ);
        int maxBlockZ = (int) Math.floor(bb.maxZ);
        int minBlockY = (int) Math.floor(bb.minY);
        int maxBlockY = (int) Math.floor(bb.maxY + 0.5D);

        int highestSolidY = Integer.MIN_VALUE;
        double sumX = 0;
        double sumZ = 0;
        int count = 0;

        for (int x = minBlockX; x <= maxBlockX; x++) {
            for (int z = minBlockZ; z <= maxBlockZ; z++) {
                for (int y = minBlockY; y <= maxBlockY; y++) {
                    if (!blockIntersectsPlayerBody(player, x, y, z)) {
                        continue;
                    }
                    if (y > highestSolidY) {
                        highestSolidY = y;
                    }
                    sumX += x + 0.5D;
                    sumZ += z + 0.5D;
                    count++;
                }
            }
        }

        if (highestSolidY == Integer.MIN_VALUE) {
            return null;
        }

        return new double[]{count > 0 ? sumX / count : player.x, highestSolidY + 1.02D, count > 0 ? sumZ / count : player.z};
    }

    public static boolean isSafeOnTop(GrimPlayer player, double x, double y, double z) {
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, x, y, z);
        return !isPhasedInsideBlock(player);
    }

    public static boolean pushOutIfPhased(GrimPlayer player) {
        if (!enabled || !isPhasedInsideBlock(player)) {
            return false;
        }

        double[] escape = findEscapeOnBlockTop(player);
        if (escape != null && isSafeOnTop(player, escape[0], escape[1], escape[2])) {
            applyPushTeleport(player, escape[0], escape[1], escape[2]);
            return true;
        }

        return false;
    }

    private static void applyPushTeleport(GrimPlayer player, double x, double y, double z) {
        player.lastX = x;
        player.lastY = y;
        player.lastZ = z;
        player.x = x;
        player.y = y;
        player.z = z;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, x, y, z);
        player.getSetbackTeleportUtil().executeNonSimulatingForceResync();
    }
}
