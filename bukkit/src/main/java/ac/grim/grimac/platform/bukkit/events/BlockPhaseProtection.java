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
import org.bukkit.FluidCollisionMode;
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

/** Server collision shapes, checked only on the player's owning region. */
public final class BlockPhaseProtection implements Listener {
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    private static final class State {
        TaskHandle task;
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
        Bukkit.getOnlinePlayers().forEach(protection::watch);
    }

    public void watch(Player player) {
        State state = new State();
        if (states.putIfAbsent(player.getUniqueId(), state) != null) return;
        state.task = GrimAPI.INSTANCE.getScheduler().getEntityScheduler().runAtFixedRate(new BukkitGrimEntity(player),
                GrimAPI.INSTANCE.getGrimPlugin(), () -> check(player, false), null, 1, 5);
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
        Location eye = player.getEyeLocation();
        return obstructions(player, player.getLocation()) != 0 || direction.lengthSquared() > 0
                && (!readable(eye.clone().add(direction.clone().normalize().multiply(0.75)))
                || eye.getWorld().rayTraceBlocks(eye, direction.clone().normalize(), 0.75, FluidCollisionMode.NEVER, true) != null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        boolean pearl = event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && enabled("PearlPhasePrevention");
        if (!pearl && !enabled("PushOutOfBlocks")) return;
        Player player = event.getPlayer();
        State state = states.get(player.getUniqueId());
        if (state == null || state.pending) return;
        long teleport = ++state.teleport;
        // A pearl may cross regions: inspect the destination after arrival on the entity scheduler.
        GrimAPI.INSTANCE.getScheduler().getEntityScheduler().execute(new BukkitGrimEntity(player), GrimAPI.INSTANCE.getGrimPlugin(),
                () -> { if (state.teleport == teleport) check(player, pearl); }, null, 1);
    }

    void check(Player player, boolean pearl) {
        State state = states.get(player.getUniqueId());
        if (state == null || state.pending || !player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR
                || !(pearl ? enabled("PearlPhasePrevention") : enabled("PushOutOfBlocks"))) return;
        Location current = player.getLocation();
        int obstruction = obstructions(player, current);
        if (obstruction == 0) return;
        if (!pearl && obstruction == 1) return; // Gravity blocks are deliberately ignored by PushOutOfBlocks.
        Location exit = null;
        // Only lift at the arrival position. A roof requiring more than two blocks is left untouched.
        for (int up = 1; up <= 2; up++) {
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

    // 0 = clear, 1 = gravity blocks only, 2 = solid obstruction or unavailable region. Never loads chunks.
    static int obstructions(Player player, Location location) {
        Location current = player.getLocation();
        BoundingBox body = player.getBoundingBox().clone().shift(location.getX() - current.getX(),
                location.getY() - current.getY(), location.getZ() - current.getZ()).expand(-1.0E-5);
        World world = location.getWorld();
        int result = 0;
        for (int x = (int) Math.floor(body.getMinX()); x <= Math.floor(body.getMaxX()); x++) {
            for (int z = (int) Math.floor(body.getMinZ()); z <= Math.floor(body.getMaxZ()); z++) {
                if (!readable(new Location(world, x, location.getY(), z))) return 2;
                // Fences and walls can extend above their owning block.
                for (int y = Math.max(world.getMinHeight(), (int) Math.floor(body.getMinY()) - 1);
                     y <= Math.min(world.getMaxHeight() - 1, Math.floor(body.getMaxY())); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.isEmpty() || block.isLiquid()) continue;
                    for (BoundingBox shape : block.getCollisionShape().getBoundingBoxes()) {
                        if (!body.overlaps(shape.clone().shift(x, y, z))) continue;
                        if (!block.getType().hasGravity()) return 2;
                        result = 1;
                    }
                }
            }
        }
        return result;
    }
}
