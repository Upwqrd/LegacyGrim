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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CrystalSwapGuard implements Listener {
    private final Map<UUID, Integer> slotChanges = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Placement>> placements = new ConcurrentHashMap<>();

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
            event.setCancelled(true); // Native crystal cancellation also resyncs inventory without consuming the item.
        if (event.isCancelled()) return;
        int limit = GrimAPI.INSTANCE.getConfigManager().getConfig().getIntElse("CrystalPlaceLimit", 7);
        if (limit <= 0) return;
        Deque<Placement> budget = placements.computeIfAbsent(event.getPlayer().getUniqueId(), id -> new ArrayDeque<>());
        synchronized (budget) {
            long now = System.nanoTime();
            budget.removeIf(p -> now - p.time() >= 1_000_000_000L || p.event().isCancelled());
            if (budget.size() >= limit) event.setCancelled(true);
            else budget.addLast(new Placement(now, event));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        slotChanges.remove(event.getPlayer().getUniqueId());
        placements.remove(event.getPlayer().getUniqueId());
    }
}
