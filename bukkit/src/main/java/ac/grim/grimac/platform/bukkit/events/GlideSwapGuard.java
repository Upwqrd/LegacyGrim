package ac.grim.grimac.platform.bukkit.events;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GlideSwapGuard implements Listener {
    private final Map<UUID, Integer> equippedSince = new ConcurrentHashMap<>();

    private static boolean hasElytra(Player player) {
        var chest = player.getInventory().getChestplate();
        return chest != null && chest.getType() == Material.ELYTRA;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        initialize(event.getPlayer());
    }

    public void initialize(Player player) {
        if (hasElytra(player)) equippedSince.put(player.getUniqueId(), Bukkit.getCurrentTick() - 2);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmor(PlayerArmorChangeEvent event) {
        if (event.getSlotType() != PlayerArmorChangeEvent.SlotType.CHEST) return;
        if (event.getNewItem().getType() == Material.ELYTRA)
            equippedSince.putIfAbsent(event.getPlayer().getUniqueId(), Bukkit.getCurrentTick());
        else equippedSince.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && event.getSlotType() == InventoryType.SlotType.ARMOR
                && event.getSlot() == 38) equippedSince.put(player.getUniqueId(), Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!event.isGliding() || !(event.getEntity() instanceof Player player) || !hasElytra(player)) return;
        int tick = Bukkit.getCurrentTick();
        Integer since = equippedSince.get(player.getUniqueId());
        if (since == null || tick - since >= 0 && tick - since < 2) {
            event.setCancelled(true); // Folia sends the stopped-gliding metadata when the start is cancelled.
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        equippedSince.remove(event.getPlayer().getUniqueId());
    }
}
