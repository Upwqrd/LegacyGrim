package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import xyz.reknown.fastercrystals.event.FastCrystalPlaceEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class CrystalSwapGuard implements Listener {
    private final Map<UUID, Integer> slotChanges = new ConcurrentHashMap<>();
    private final Map<UUID, Budget> budgets = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private static final long SECOND = 1_000_000_000L;
    public CrystalSwapGuard() { this(System::nanoTime); }
    public Listener fasterCrystalsListener() {
        try { FastCrystalPlaceEvent.getHandlerList(); }
        catch (NoClassDefFoundError error) { Bukkit.getLogger().warning("[GrimAC] Update FasterCrystals: FastCrystalPlaceEvent is missing; FasterCrystals placements are not rate-limited."); return new Listener() {}; }
        return new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
            public void onPlace(FastCrystalPlaceEvent event) { CrystalSwapGuard.this.onPlace(event, event.getPlayer(), event.getCrystalItem().equals(event.getPlayer().getInventory().getItemInMainHand()) ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND); }
            @EventHandler(priority = EventPriority.MONITOR)
            public void onComplete(FastCrystalPlaceEvent event) { CrystalSwapGuard.this.onComplete(event, event.getPlayer()); }
    }; }
    CrystalSwapGuard(LongSupplier clock) { this.clock = clock; }

    private static final class Budget {
        long heldSince;
        Attempt last;
    }
    private static final class Attempt {
        final Cancellable event;
        final long time;
        final double delay;
        final int rate;
        Attempt previous;
        boolean finished;
        Attempt(Cancellable event, long time, double delay, int rate, Attempt previous) {
            this.event = event; this.time = time; this.delay = delay; this.rate = rate; this.previous = previous;
        }
    }
    private Budget budget(Player player) {
        return budgets.computeIfAbsent(player.getUniqueId(), id -> {
            Budget value = new Budget(); value.heldSince = clock.getAsLong() - SECOND; return value;
        });
    }
    private void changed(Player player) {
        Budget value = budget(player);
        synchronized (value) { value.heldSince = clock.getAsLong(); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSlotChange(PlayerItemHeldEvent event) {
        if (event.getPreviousSlot() != event.getNewSlot()) {
            slotChanges.put(event.getPlayer().getUniqueId(), Bukkit.getCurrentTick());
            changed(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p && (event.getHotbarButton() == p.getInventory().getHeldItemSlot()
                || event.getClickedInventory() instanceof PlayerInventory && event.getSlot() == p.getInventory().getHeldItemSlot())) changed(p);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player p && event.getRawSlots().stream().anyMatch(slot ->
                event.getView().getInventory(slot) instanceof PlayerInventory && event.getView().convertSlot(slot) == p.getInventory().getHeldItemSlot())) changed(p);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHandSwap(PlayerSwapHandItemsEvent event) { changed(event.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        Integer changed = slotChanges.get(event.getPlayer().getUniqueId());
        if (event.willAttack() && changed != null && changed == Bukkit.getCurrentTick())
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal) || event.getPlayer() == null) return;
        onPlace(event, event.getPlayer(), event.getHand());
    }
    void onPlace(Cancellable event, Player player, EquipmentSlot hand) {
        Integer changed = slotChanges.get(player.getUniqueId());
        int elapsed = changed == null ? 1 : Bukkit.getCurrentTick() - changed;
        if (player.getInventory().getItem(hand).getType() != Material.END_CRYSTAL
                || (hand == EquipmentSlot.HAND && elapsed >= 0 && elapsed < 1
                && !GrimAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("AllowCrystalSilentSwap", false)))
            event.setCancelled(true);
        if (event.isCancelled()) return;
        Budget value = budget(player);
        var config = GrimAPI.INSTANCE.getConfigManager().getConfig();
        int normal = config.getIntElse("CrystalPlaceLimit", 6);
        int swapped = config.getIntElse("CrystalSwapPlaceLimit", 0);
        synchronized (value) {
            long now = clock.getAsLong();
            boolean swap = hand == EquipmentSlot.HAND && now - value.heldSince < SECOND;
            limitPlacement(event, player, !swap || swapped <= 0 ? normal : normal <= 0 ? swapped : Math.min(normal, swapped), now);
        }
    }

    void limitPlacement(Cancellable event, Player player, int rate, long now) {
        if (rate <= 0) return;
        Budget value = budget(player);
        synchronized (value) {
            while (value.last != null && value.last.finished && value.last.event.isCancelled()) value.last = value.last.previous;
            Attempt last = value.last;
            if (last != null && last.finished) last.previous = null;
            double period = SECOND / (double) rate;
            long elapsed = last == null ? Long.MAX_VALUE : now - last.time;
            double wait = last == null ? 0 : (last.rate == rate ? last.delay : period) - elapsed;
            if (wait <= -period) wait = 0;
            if (wait > 1 || last != null && elapsed + 1.0 < period / 2) event.setCancelled(true);
            else value.last = new Attempt(event, now, wait + period, rate, last);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onComplete(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal) || event.getPlayer() == null) return;
        onComplete(event, event.getPlayer());
    }
    void onComplete(Cancellable event, Player player) {
        Budget value = budgets.get(player.getUniqueId());
        if (value != null) synchronized (value) {
            for (Attempt attempt = value.last; attempt != null; attempt = attempt.previous)
                if (attempt.event == event) attempt.finished = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        slotChanges.remove(event.getPlayer().getUniqueId());
        budgets.remove(event.getPlayer().getUniqueId());
    }
}
