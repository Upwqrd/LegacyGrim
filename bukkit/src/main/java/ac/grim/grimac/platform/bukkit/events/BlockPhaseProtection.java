package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.platform.api.Platform;
import ac.grim.grimac.platform.api.scheduler.TaskHandle;
import ac.grim.grimac.platform.bukkit.entity.BukkitGrimEntity;
import ac.grim.grimac.platform.bukkit.GrimACBukkitLoaderPlugin;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import ac.grim.grimac.platform.bukkit.utils.reflection.PaperUtils;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockPhaseProtection implements Listener {
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    private static final class State {
        volatile TaskHandle task;
        long teleport;
        volatile boolean pending;
    }

    private static boolean enabled(String key) {
        return GrimAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse(key, true);
    }

    public static void start() {
        if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_17)) return;
        BlockPhaseProtection protection = new BlockPhaseProtection();
        Bukkit.getPluginManager().registerEvents(protection, GrimACBukkitLoaderPlugin.LOADER);
        Bukkit.getPluginManager().registerEvents(new EmbeddedInteractionGuard(), GrimACBukkitLoaderPlugin.LOADER);
        Bukkit.getOnlinePlayers().forEach(protection::watch);
    }

    public void watch(Player player) {
        State state = new State();
        if (states.putIfAbsent(player.getUniqueId(), state) != null) return;
        state.task = GrimAPI.INSTANCE.getScheduler().getEntityScheduler().runAtFixedRate(new BukkitGrimEntity(player),
                GrimAPI.INSTANCE.getGrimPlugin(), () -> check(player, false), null, 1, 5);
        if (state.task == null) states.remove(player.getUniqueId(), state);
        else if (states.get(player.getUniqueId()) != state) state.task.cancel();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        watch(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        State state = states.remove(event.getPlayer().getUniqueId());
        if (state != null && state.task != null) state.task.cancel();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.useItemInHand() == Event.Result.DENY || event.getItem() == null || event.getItem().getType() != Material.ENDER_PEARL
                || event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK
                || !enabled("PearlPhasePrevention")) return;
        if (closeToWall(event.getPlayer(), event.getPlayer().getEyeLocation().getDirection()))
            event.setUseItemInHand(Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl pearl && pearl.getShooter() instanceof Player player
                && enabled("PearlPhasePrevention") && closeToWall(player, pearl.getVelocity())) event.setCancelled(true);
    }

    private static boolean closeToWall(Player player, Vector direction) {
        return direction.lengthSquared() > 0 && obstructions(player, player.getLocation(), direction) == 2;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        boolean pearl = event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && enabled("PearlPhasePrevention");
        if (!pearl && !enabled("PushOutOfBlocks")) return;
        Player player = event.getPlayer();
        State state = states.get(player.getUniqueId());
        if (state == null || state.pending) return;
        long teleport = ++state.teleport;
        GrimAPI.INSTANCE.getScheduler().getEntityScheduler().execute(new BukkitGrimEntity(player), GrimAPI.INSTANCE.getGrimPlugin(),
                () -> { if (state.teleport == teleport) check(player, pearl); }, null, 1);
    }

    void check(Player player, boolean pearl) {
        State state = states.get(player.getUniqueId());
        if (state == null || state.pending || !player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR
                || !(pearl ? enabled("PearlPhasePrevention") : enabled("PushOutOfBlocks"))) return;
        Location current = player.getLocation();
        int obstruction = obstructions(player, current);
        if (obstruction != 2) return;
        Location exit = null;
        for (int up = 1; up <= 3; up++) {
            Location candidate = current.clone();
            candidate.setY(Math.floor(current.getY()) + up);
            if (obstructions(player, candidate) == 0) { exit = candidate; break; }
        }
        if (exit == null) return;
        state.pending = true;
        PaperUtils.teleportAsync(player, exit).whenComplete((success, failure) -> state.pending = false);
    }

    private static boolean readable(Location location) {
        World world = location.getWorld();
        int x = location.getBlockX() >> 4, z = location.getBlockZ() >> 4;
        return world.isChunkLoaded(x, z) && (GrimAPI.INSTANCE.getPlatform() != Platform.FOLIA || Bukkit.isOwnedByCurrentRegion(world, x, z));
    }

    static int obstructions(Player player, Location location) {
        return obstructions(player, location, null);
    }

    private static int obstructions(Player player, Location location, Vector direction) {
        Location current = player.getLocation();
        BoundingBox body = player.getBoundingBox().clone().shift(location.getX() - current.getX(),
                location.getY() - current.getY(), location.getZ() - current.getZ())
                .expand(-1.0E-5);
        if (direction != null) body.expand(0.85, 0.25, 0.85, 0.85, 0.25, 0.85);
        Vector origin = direction == null ? null : player.getEyeLocation().add(0, -0.1F, 0).toVector();
        World world = location.getWorld();
        int result = 0;
        double closest = Double.POSITIVE_INFINITY;
        for (int x = (int) Math.floor(body.getMinX()); x <= Math.floor(body.getMaxX()); x++) {
            for (int z = (int) Math.floor(body.getMinZ()); z <= Math.floor(body.getMaxZ()); z++) {
                if (!readable(new Location(world, x, location.getY(), z))) return 2;
                for (int y = Math.max(world.getMinHeight(), (int) Math.floor(body.getMinY()) - 1);
                     y <= Math.min(world.getMaxHeight() - 1, Math.floor(body.getMaxY())); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.isEmpty() || block.isLiquid() || direction != null && block.getType().hasGravity()) continue;
                    var shapes = block.getCollisionShape().getBoundingBoxes();
                    boolean solid = !block.getType().hasGravity() && shapes.size() == 1
                            && shapes.iterator().next().equals(new BoundingBox(0, 0, 0, 1, 1, 1));
                    for (BoundingBox shape : shapes) {
                        shape = shape.clone().shift(x, y, z);
                        if (!body.overlaps(shape)) continue;
                        if (direction != null) {
                            var hit = shape.expand(direction.getY() > 0 ? 0 : 0.125).rayTrace(origin, direction, 2.0);
                            if (hit == null) continue;
                            double distance = hit.getHitPosition().distanceSquared(origin);
                            if (distance > closest || distance == closest && result == 2) continue;
                            closest = distance;
                        } else if (solid) return 2;
                        result = solid ? 2 : 1;
                    }
                }
            }
        }
        return result;
    }
}
