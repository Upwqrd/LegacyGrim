package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.RegisteredListener;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public final class FoodMultitask implements Listener {
    private static final String HANDLER = "grim_food_multitask";
    private final JavaPlugin plugin;
    private final Class<?> packet, placement, slot;
    private final java.lang.invoke.MethodHandle ensureThread;
    private final Method handle, level, bukkit, itemHand, getHand, getItem, inventory, flag;
    private final Field listenerPlayer;
    private final Method hasFood, isEmpty;
    private final Object foodComponent;
    private final ThreadLocal<Snapshot> current = new ThreadLocal<>();
    private final Field[] state;
    private final Method configuration;
    private final Field limiter, limits;
    private volatile boolean stopped;
    private final Set<Channel> channels = ConcurrentHashMap.newKeySet();

    public static void start(JavaPlugin plugin) {
        if (!GrimAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("FoodMultitask", false)) return;
        try {
            FoodMultitask module = new FoodMultitask(plugin);
            Bukkit.getPluginManager().registerEvents(module, plugin);
            for (Player player : Bukkit.getOnlinePlayers())
                player.getScheduler().run(plugin, task -> module.attach(player), null);
            plugin.getLogger().info("FoodMultitask enabled without a core patch or Java agent.");
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "FoodMultitask is unsupported by this server", error);
        }
    }

    private FoodMultitask(JavaPlugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        packet = Class.forName("net.minecraft.network.protocol.Packet");
        placement = Class.forName("net.minecraft.network.protocol.game.ServerboundUseItemOnPacket");
        slot = Class.forName("net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket");
        Class<?> player = Class.forName("net.minecraft.server.level.ServerPlayer");
        Class<?> living = Class.forName("net.minecraft.world.entity.LivingEntity");
        Class<?> hand = Class.forName("net.minecraft.world.InteractionHand");
        ensureThread = java.lang.invoke.MethodHandles.publicLookup().unreflect(Class.forName("net.minecraft.network.protocol.PacketUtils").getMethod("ensureRunningOnSameThread",
                packet, Class.forName("net.minecraft.network.PacketListener"), Class.forName("net.minecraft.server.level.ServerLevel")));
        handle = packet.getMethod("handle", Class.forName("net.minecraft.network.PacketListener"));
        level = player.getMethod("level");
        bukkit = player.getMethod("getBukkitEntity");
        itemHand = player.getMethod("getItemInHand", hand);
        Method handAccessor;
        try { handAccessor = placement.getMethod("getHand"); }
        catch (NoSuchMethodException ignored) { handAccessor = placement.getMethod("hand"); }
        getHand = handAccessor;
        inventory = player.getMethod("getInventory");
        getItem = inventory.getReturnType().getMethod("getItem", int.class);
        flag = living.getMethod("setLivingEntityFlag", int.class, boolean.class);
        listenerPlayer = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl").getField("player");
        state = new Field[4];
        String[] names = {"useItem", "useItemRemaining", "eatStartTime", "totalEatTimeTicks"};
        for (int i = 0; i < names.length; i++) {
            state[i] = living.getDeclaredField(names[i]);
            state[i].setAccessible(true);
        }
        hasFood = state[0].getType().getMethod("has", Class.forName("net.minecraft.core.component.DataComponentType"));
        isEmpty = state[0].getType().getMethod("isEmpty");
        foodComponent = Class.forName("net.minecraft.core.component.DataComponents").getField("FOOD").get(null);
        Class<?> config = Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
        configuration = config.getMethod("get");
        limiter = config.getField("packetLimiter");
        limits = limiter.getType().getField("overrides");
        if (limited(placement) || limited(slot))
            throw new ReflectiveOperationException("Custom placement/slot packet limits require a separate adapter");
    }

    private boolean limited(Class<?> type) throws ReflectiveOperationException {
        return ((java.util.Map<?, ?>) limits.get(limiter.get(configuration.invoke(null)))).containsKey(type);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { attach(event.getPlayer()); }

    private void attach(Player player) {
        try {
            Object nms = player.getClass().getMethod("getHandle").invoke(player);
            Object listener = nms.getClass().getField("connection").get(nms);
            Object connection = listener.getClass().getField("connection").get(listener);
            Channel channel = (Channel) connection.getClass().getField("channel").get(connection);
            channel.eventLoop().execute(() -> {
                if (stopped || !channel.isOpen() || channel.pipeline().get(HANDLER) != null) return;
                channel.pipeline().addBefore("packet_handler", HANDLER, new ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
                        if ((placement.isInstance(message) || slot.isInstance(message)) && !limited(message.getClass()))
                            message = wrap(message);
                        ctx.fireChannelRead(message);
                    }
                    @Override public void handlerRemoved(ChannelHandlerContext ctx) { channels.remove(ctx.channel()); }
                });
                channels.add(channel);
                if (stopped) channel.pipeline().remove(HANDLER);
            });
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Cannot attach FoodMultitask", error);
        }
    }

    private Object wrap(Object original) {
        return Proxy.newProxyInstance(packet.getClassLoader(), new Class<?>[]{packet}, (proxy, method, args) -> {
            if (!method.getName().equals("handle")) return invoke(method, original, args);
            Object nms = listenerPlayer.get(args[0]);
            ensureThread.invoke(proxy, args[0], invoke(level, nms));
            if (stopped) return invoke(handle, original, args);
            Player player = (Player) invoke(bukkit, nms);
            Snapshot saved = null;
            if (player.hasActiveItem() && (boolean) invoke(hasFood, state[0].get(nms), foodComponent)) {
                Object food = state[0].get(nms);
                boolean keep = slot.isInstance(original) && player.getActiveItemHand() == EquipmentSlot.HAND;
                if (placement.isInstance(original)) {
                    Object hand = invoke(getHand, original);
                    Material action = player.getInventory().getItem(hand.toString().equals("MAIN_HAND")
                            ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND).getType();
                    keep = invoke(itemHand, nms, hand) != food && ((action.isBlock() && !action.isAir()) || action == Material.END_CRYSTAL);
                }
                if (keep) {
                    saved = new Snapshot(nms, player);
                    if (placement.isInstance(original)) order(PlayerInteractEvent.getHandlerList(), EventPriority.LOWEST, true);
                    else order(PlayerItemHeldEvent.getHandlerList(), EventPriority.MONITOR, false);
                }
            }
            Snapshot previous = current.get();
            current.set(saved);
            try { return invoke(handle, original, args); }
            finally {
                try { if (saved != null && !saved.restored) saved.restore(); }
                finally { if (previous == null) current.remove(); else current.set(previous); }
            }
        });
    }

    private void order(HandlerList handlers, EventPriority priority, boolean first) {
        synchronized (handlers) {
            ArrayList<RegisteredListener> group = new ArrayList<>();
            RegisteredListener own = null;
            for (RegisteredListener listener : handlers.getRegisteredListeners()) {
                if (listener.getPriority() != priority) continue;
                group.add(listener);
                if (listener.getListener() == this) own = listener;
            }
            if (own == null || group.get(first ? 0 : group.size() - 1) == own) return;
            // Keep other plugins' relative order; only move our state restoration/observation.
            for (RegisteredListener listener : group) handlers.unregister(listener);
            if (first) handlers.register(own);
            for (RegisteredListener listener : group) if (listener != own) handlers.register(listener);
            if (!first) handlers.register(own);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void interact(PlayerInteractEvent event) throws Throwable {
        Snapshot saved = current.get();
        if (saved != null && !saved.restored && saved.player == event.getPlayer()) saved.restore();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void held(PlayerItemHeldEvent event) throws IllegalAccessException {
        Snapshot saved = current.get();
        if (saved != null && saved.player == event.getPlayer()
                && (!saved.player.hasActiveItem() || state[0].get(saved.nms) != saved.values[0])) saved.restored = true;
    }

    private final class Snapshot {
        final Object nms;
        final Player player;
        final Object[] values = new Object[state.length];
        boolean restored;
        Snapshot(Object nms, Player player) throws IllegalAccessException {
            this.nms = nms; this.player = player;
            for (int i = 0; i < state.length; i++) values[i] = state[i].get(nms);
        }
        void restore() throws Throwable {
            restored = true;
            if (player.hasActiveItem() || player.isDead() || (boolean) invoke(isEmpty, values[0])) return;
            Object inv = invoke(inventory, nms);
            for (int i = 0; i < 41; i++) {
                if (invoke(getItem, inv, i) != values[0]) continue;
                for (int j = 0; j < state.length; j++) state[j].set(nms, values[j]);
                invoke(flag, nms, 1, true);
                break;
            }
        }
    }

    private static Object invoke(Method method, Object owner, Object... args) throws Throwable {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }

    @EventHandler
    public void disable(PluginDisableEvent event) {
        if (event.getPlugin() != plugin) return;
        stopped = true;
        for (Channel channel : channels) channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(HANDLER) != null) channel.pipeline().remove(HANDLER);
        });
        channels.clear();
    }
}
