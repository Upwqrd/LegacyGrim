package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CrystalSwapGuard implements Listener {
    private final Map<UUID, Integer> slotChanges = new ConcurrentHashMap<>();
    private final Map<UUID, Placement> placements = new ConcurrentHashMap<>();

    private record Placement(long time, EntityPlaceEvent event) {}

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSlotChange(PlayerItemHeldEvent event) {
        if (event.getPreviousSlot() != event.getNewSlot())
            slotChanges.put(event.getPlayer().getUniqueId(), Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal) || event.getPlayer() == null) return;
        Integer changed = slotChanges.get(event.getPlayer().getUniqueId());
        int elapsed = changed == null ? 1 : Bukkit.getCurrentTick() - changed;
        if (event.getPlayer().getInventory().getItem(event.getHand()).getType() != Material.END_CRYSTAL
                || (event.getHand() == EquipmentSlot.HAND && elapsed >= 0 && elapsed < 1))
            event.setCancelled(true);
        if (event.isCancelled()) return;
        int limit = GrimAPI.INSTANCE.getConfigManager().getConfig().getIntElse("CrystalPlaceLimit", 6);
        limitPlacement(event, limit, System.nanoTime());
    }

    private void limitPlacement(EntityPlaceEvent event, int limit, long now) {
        if (limit <= 0) return;
        long interval = (1_000_000_000L + limit - 1) / limit;
        placements.compute(event.getPlayer().getUniqueId(), (id, last) -> {
            boolean active = last != null && !last.event().isCancelled();
            if (active && now - last.time() < 0) { event.setCancelled(true); return last; }
            long next = active && now - last.time() <= Math.min(interval / 2, 50_000_000L) ? last.time() : now;
            return new Placement(next + interval, event);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        slotChanges.remove(event.getPlayer().getUniqueId());
        placements.remove(event.getPlayer().getUniqueId());
    }
}
