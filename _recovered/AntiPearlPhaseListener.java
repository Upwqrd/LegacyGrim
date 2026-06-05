package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.modifications.AntiPearlPhase2b2tModifications;
import ac.grim.grimac.player.GrimPlayer;
import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * LachShield AntiPearlPhase pearl-cancel heuristic + push-out after pearl lands in blocks.
 */
public class AntiPearlPhaseListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPearlLaunch(PlayerLaunchProjectileEvent event) {
        if (!AntiPearlPhase2b2tModifications.enabled) {
            return;
        }
        if (event.getProjectile().getType() != EntityType.ENDER_PEARL) {
            return;
        }

        Player player = event.getPlayer();
        if (player.getTargetBlockExact(AntiPearlPhase2b2tModifications.pearlCancelTargetDistance) == null) {
            return;
        }
        if (player.getPitch() < AntiPearlPhase2b2tModifications.pearlCancelDownPitchLimit) {
            return;
        }
        if (!isPlayerLocationSurrounded(player.getLocation())) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (!AntiPearlPhase2b2tModifications.enabled || !AntiPearlPhase2b2tModifications.pushOnPearlLand) {
            return;
        }
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }

        GrimPlayer grimPlayer = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getPlayer().getUniqueId());
        if (grimPlayer == null || event.getTo() == null) {
            return;
        }

        grimPlayer.x = event.getTo().getX();
        grimPlayer.y = event.getTo().getY();
        grimPlayer.z = event.getTo().getZ();
        AntiPearlPhase2b2tModifications.pushOutIfPhased(grimPlayer);
    }

    private boolean isPlayerLocationSurrounded(Location location) {
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        var world = location.getWorld();
        if (world == null) {
            return false;
        }

        Location[] checks = {
                new Location(world, x + 1, y, z),
                new Location(world, x - 1, y, z),
                new Location(world, x, y, z + 1),
                new Location(world, x, y, z - 1),
        };

        for (Location check : checks) {
            Block block = check.getBlock();
            if (block.getType().isSolid() && block.getType() != Material.FARMLAND
                    && block.getType() != Material.SOUL_SAND && !isOpenablePartialBlock(block.getType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOpenablePartialBlock(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.ENDER_CHEST
                || type == Material.BARREL || type.name().endsWith("_DOOR") || type.name().endsWith("_TRAPDOOR");
    }
}
