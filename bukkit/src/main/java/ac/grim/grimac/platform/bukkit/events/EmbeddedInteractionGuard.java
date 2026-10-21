package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.platform.api.Platform;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.util.BoundingBox;

/** Reject use actions inside full cubes; partial collision shapes and floor contact are allowed. */
public final class EmbeddedInteractionGuard implements Listener {
    static boolean blocked(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) return false;
        var world = player.getWorld();
        var body = player.getBoundingBox().clone().expand(-1.0E-5);
        for (int x = (int) Math.floor(body.getMinX()); x <= Math.floor(body.getMaxX()); x++) {
            for (int z = (int) Math.floor(body.getMinZ()); z <= Math.floor(body.getMaxZ()); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)
                        || GrimAPI.INSTANCE.getPlatform() == Platform.FOLIA && !Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4)) return true;
                for (int y = Math.max(world.getMinHeight(), (int) Math.floor(body.getMinY()));
                     y <= Math.min(world.getMaxHeight() - 1, Math.floor(body.getMaxY())); y++) {
                    var block = world.getBlockAt(x, y, z);
                    if (block.isEmpty() || block.isLiquid() || block.getType().hasGravity()) continue;
                    var shapes = block.getCollisionShape().getBoundingBoxes();
                    if (shapes.size() != 1) continue;
                    var shape = shapes.iterator().next();
                    // Occlusion/solid flags also include partial blocks or exclude full glass cubes.
                    if (shape.equals(new BoundingBox(0, 0, 0, 1, 1, 1)) && body.overlaps(shape.clone().shift(x, y, z))) return true;
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if ((event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) && blocked(event.getPlayer())) {
            var item = event.getItem();
            if (item == null || !item.getType().isEdible()) event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntity(PlayerInteractEntityEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAtEntity(PlayerInteractAtEntityEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null && blocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player && blocked(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        var type = event.getItem().getType();
        if (!type.isEdible() && blocked(event.getPlayer())) event.setCancelled(true);
    }
}
