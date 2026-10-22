package ac.grim.grimac.platform.bukkit.events;

import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockPlaceLimiter implements Listener {
    private final Map<UUID, Budget> budgets = new ConcurrentHashMap<>();

    private static final class Budget {
        int tick;
        final List<Attempt> attempts = new ArrayList<>(2);
    }

    private static final class Attempt {
        final BlockPlaceEvent event;
        boolean finished;

        Attempt(BlockPlaceEvent event) {
            this.event = event;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (ItemTypes.getByName(event.getItemInHand().getType().getKey().toString()).getPlacedType() == null) return;
        Budget budget = budgets.computeIfAbsent(event.getPlayer().getUniqueId(), id -> new Budget());
        synchronized (budget) {
            int tick = Bukkit.getCurrentTick();
            if (budget.tick != tick) {
                budget.tick = tick;
                budget.attempts.clear();
            }
            budget.attempts.removeIf(a -> a.finished && (a.event.isCancelled() || !a.event.canBuild()));
            if (budget.attempts.size() >= 2) {
                event.setCancelled(true);
            } else {
                budget.attempts.add(new Attempt(event));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onComplete(BlockPlaceEvent event) {
        Budget budget = budgets.get(event.getPlayer().getUniqueId());
        if (budget == null) return;
        synchronized (budget) {
            for (Attempt attempt : budget.attempts) if (attempt.event == event) attempt.finished = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        budgets.remove(event.getPlayer().getUniqueId());
    }
}
