package maxshield;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import io.netty.channel.*;
import io.netty.handler.codec.DecoderException;
import net.kyori.adventure.text.Component;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_21_R2.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class MaxShield extends JavaPlugin implements Listener {

    /* ---------- CONFIG ---------- */
    private double failRatio, moveThreshold;
    private int minSamples, maxPerIp, captchaTicks, limboY;
    private long windowMs, cooldownMs;
    private Map<String, String> msgs;

    /* ---------- STATE ----------- */
    private final Map<UUID, Boolean> challenged   = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> captchaMap   = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> ipCounts = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private boolean whitelistOn = false;
    private long lastFail = 0L;
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("antiddos")).setExecutor((s,c,l,a)->{
            if (a.length==1 && a[0].equalsIgnoreCase("off") && s.hasPermission("maxshield.toggle")){
                disableWhitelist();
                s.sendMessage(Component.text("§aEmergency whitelist disabled."));
            } else {
                s.sendMessage(Component.text("§e/antiddos off"));
            }
            return true;
        });
        Bukkit.getScheduler().runTaskTimer(this,this::evaluate,0L,100L);
        getLogger().info("MaxShield 3.2 enabled (Paper 1.21.11+).");
    }

    private void loadConfig() {
        failRatio   = getConfig().getDouble("fail-ratio", 0.5);
        minSamples  = getConfig().getInt("min-samples", 20);
        maxPerIp    = getConfig().getInt("max-per-ip", 3);
        captchaTicks= getConfig().getInt("captcha-timeout-seconds", 60) * 20;
        windowMs    = getConfig().getLong("window-seconds", 60) * 1000L;
        cooldownMs  = getConfig().getLong("cooldown-seconds", 60) * 1000L;
        limboY      = getConfig().getInt("limbo-y", 200);
        moveThreshold=getConfig().getDouble("move-threshold", 0.5);
        msgs = new HashMap<>();
        for (String key : getConfig().getConfigurationSection("messages").getKeys(false))
            msgs.put(key, Objects.requireNonNull(getConfig().getString("messages." + key)));
    }

    /* ---------- JOIN -> LIMBO ---------- */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        /* очистка на случай быстрого реконнекта */
        captchaMap.remove(uuid);
        challenged.remove(uuid);

        /* IP-лимит */
        String ip = p.getAddress().getHostString().split(":")[0];
        ipCounts.computeIfAbsent(ip, k -> new AtomicInteger(0)).incrementAndGet();
        if (ipCounts.get(ip).get() > maxPerIp) {
            p.kick(Component.text(msgs.get("ip-limit").replace("%d", String.valueOf(maxPerIp))));
            ipCounts.get(ip).decrementAndGet();
            failed.incrementAndGet();
            return;
        }

        /* Лимбо */
        Location limbo = new Location(p.getWorld(), 0, limboY, 0, 0, 0);
        p.teleportAsync(limbo);
        p.setInvulnerable(true);
        p.setSilent(true);
        p.sendMessage(Component.text(msgs.get("checking")));

        /* Челлендж + CAPTCHA сразу */
        sendChallenge(p);
        challenged.put(uuid, false);
        releasePlayer(p);
        total.incrementAndGet();
    }

    /* ---------- CAPTCHA ---------- */
    private void releasePlayer(Player p) {
        UUID uuid = p.getUniqueId();
        int captcha = random.nextInt(9000) + 1000;
        captchaMap.put(uuid, captcha);
        p.sendMessage(Component.text(msgs.get("captcha") + captcha));
        p.sendMessage(Component.text(msgs.get("captcha-time").replace("%d", String.valueOf(captchaTicks / 20))));

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && captchaMap.containsKey(uuid)) {
                p.kick(Component.text(msgs.get("timeout")));
                captchaMap.remove(uuid);
                failed.incrementAndGet();
            }
        }, captchaTicks);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        if (!captchaMap.containsKey(uuid)) return;
        e.setCancelled(true);
        try {
            int answer = Integer.parseInt(e.getMessage().trim());
            if (answer == captchaMap.get(uuid)) {
                Location spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
                p.teleportAsync(spawn);
                p.setInvulnerable(false);
                p.setSilent(false);
                captchaMap.remove(uuid);
                challenged.put(uuid, true);
                p.sendMessage(Component.text(msgs.get("success")));
                /* лог */
                String ip = p.getAddress().getHostString().split(":")[0];
                getLogger().info(p.getName() + " (" + ip + ") passed CAPTCHA.");
            } else {
                p.kick(Component.text(msgs.get("wrong")));
            }
        } catch (NumberFormatException ex) {
            p.sendMessage(Component.text(msgs.get("only-number")));
        }
    }

    /* ---------- MOVE-CHECK (конфигурируемый порог) ---------- */
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (Boolean.TRUE.equals(challenged.get(p.getUniqueId()))) return;
        if (Math.abs(e.getTo().getX() - e.getFrom().getX()) > moveThreshold ||
            Math.abs(e.getTo().getZ() - e.getFrom().getZ()) > moveThreshold ||
            e.getTo().getY() != e.getFrom().getY()) {
            p.kick(Component.text(msgs.get("move-kick")));
            failed.incrementAndGet();
        }
    }

    /* ---------- CONNECTION CLOSE ---------- */
    @EventHandler
    public void onClose(PlayerConnectionCloseEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (Boolean.FALSE.equals(challenged.get(uuid))) {
            failed.incrementAndGet();
            lastFail = System.currentTimeMillis();
        }
        challenged.remove(uuid);
        captchaMap.remove(uuid);

        String ip = e.getPlayer().getAddress().getHostString().split(":")[0];
        AtomicInteger cnt = ipCounts.get(ip);
        if (cnt != null && cnt.decrementAndGet() <= 0) ipCounts.remove(ip);
    }

    /* ---------- CHALLENGE PACKET (3 шт.) ---------- */
    private void sendChallenge(Player p) {
        Channel ch = ((CraftPlayer) p).getHandle().connection.connection.channel;
        if (ch == null) return;
        ch.pipeline().addAfter("decoder", "maxshield", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof DecoderException) {
                    failed.incrementAndGet();
                    lastFail = System.currentTimeMillis();
                    ctx.close();
                    return;
                }
                super.channelRead(ctx, msg);
            }
        });
        // три разных пакета → максимальный отсев ботов
        ((CraftPlayer) p).getHandle().connection.send(
            new ClientboundCustomPayloadPacket(new ResourceLocation("maxshield", "bot_check"), new byte[0]));
        ((CraftPlayer) p).getHandle().connection.send(
            new ClientboundCustomPayloadPacket(new ResourceLocation("minecraft", "brand"), new byte[]{'b','o','t'}));
        ((CraftPlayer) p).getHandle().connection.send(
            new ClientboundCustomPayloadPacket(new ResourceLocation("maxshield", "random"), new byte[]{1,2,3}));
    }

    /* ---------- EVALUATE WHITELIST ---------- */
    private void evaluate() {
        long now = System.currentTimeMillis();
        if (whitelistOn && now - lastFail > cooldownMs) {
            Bukkit.setWhitelist(false);
            whitelistOn = false;
            getLogger().info("Attack over – whitelist disabled.");
        }
        if (!whitelistOn && total.get() >= minSamples) {
            double ratio = (double) failed.get() / total.get();
            if (ratio > failRatio) {
                Bukkit.setWhitelist(true);
                whitelistOn = true;
                getLogger().warning(String.format("DDoS: %.0f%% failed (%d/%d) – whitelist ON.",
                    ratio * 100, failed.get(), total.get()));
            }
        }
        if (now - lastFail > windowMs) {
            total.set(0);
            failed.set(0);
        }
    }

    private void disableWhitelist() {
        Bukkit.setWhitelist(false);
        whitelistOn = false;
        total.set(0);
        failed.set(0);
        getLogger().info("Whitelist disabled by command.");
    }
}