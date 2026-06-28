package com.danny29404711.minecartspeed;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.vehicle.VehicleUpdateEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ExperimentalMinecartSpeedPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final double MIN_SPEED_BPS = 0.1D;
    private static final double MAX_SPEED_BPS = 1000.0D;

    private MinecartBehaviorInjector injector;
    private MinecartVisualSyncer visualSyncer;
    private GeyserHook geyserHook;
    private double maxSpeedBlocksPerSecond;
    private double normalMaxSpeedBlocksPerSecond;
    private boolean speedOnlyWhenPlayerRiding;
    private boolean clampEmptyCartVelocityOnExit;
    private boolean injectOnlyRideableMinecarts;
    private boolean controlOnlyPlayerRiddenMinecarts;
    private boolean injectOnVehicleUpdate;
    private boolean logInjectionCounts;
    private boolean forceLegacyClientSmoothSync;
    private double visualSyncTrackingRangeBlocks;
    private boolean bedrockSupportEnabled;
    private boolean bedrockDetectGeyserPlayers;
    private boolean bedrockSendExtraMotionPackets;
    private boolean bedrockForceGeyserExperimentalMode;
    private boolean bedrockDisableClientVehiclePrediction;
    private boolean bedrockSendDirectMovePackets;
    private int bedrockDirectMoveIntervalTicks;
    private int bedrockMotionSyncIntervalTicks;
    private int bedrockResendPassengersIntervalTicks;
    private boolean bedrockFallbackOriginalSpeedForBedrockRiders;
    private boolean bedrockDebugLog;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        this.injector = new MinecartBehaviorInjector(this);
        this.geyserHook = new GeyserHook(this);
        this.visualSyncer = new MinecartVisualSyncer(this);

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("mcspeed") != null) {
            getCommand("mcspeed").setExecutor(this);
            getCommand("mcspeed").setTabCompleter(this);
        }

        if (getConfig().getBoolean("inject-existing-minecarts-on-startup", true)) {
            Bukkit.getScheduler().runTask(this, () -> {
                int count = injectLoadedMinecarts();
                if (logInjectionCounts) {
                    getLogger().info("Injected experimental behavior into " + count + " loaded minecart(s).");
                }
            });
        }

        Bukkit.getScheduler().runTaskTimer(this, () -> visualSyncer.tick(), 1L, 1L);

        long interval = Math.max(20L, getConfig().getLong("scan-interval-ticks", 100L));
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            int count = injectLoadedMinecarts();
            if (logInjectionCounts && count > 0) {
                getLogger().info("Injected experimental behavior into " + count + " minecart(s) during periodic scan.");
            }
        }, interval, interval);

        getLogger().info("Enabled. Player-ridden minecart max speed = " + maxSpeedBlocksPerSecond + " blocks/s (normal = " + normalMaxSpeedBlocksPerSecond + " blocks/s).");
    }

    public void reloadSettings() {
        reloadConfig();
        this.maxSpeedBlocksPerSecond = clamp(
                getConfig().getDouble("max-speed-blocks-per-second", 16.0D),
                MIN_SPEED_BPS,
                MAX_SPEED_BPS
        );
        this.normalMaxSpeedBlocksPerSecond = clamp(
                getConfig().getDouble("normal-speed-blocks-per-second", 8.0D),
                MIN_SPEED_BPS,
                MAX_SPEED_BPS
        );
        this.speedOnlyWhenPlayerRiding = getConfig().getBoolean("speed-only-when-player-riding", true);
        this.clampEmptyCartVelocityOnExit = getConfig().getBoolean("clamp-empty-cart-velocity-on-exit", true);
        this.injectOnlyRideableMinecarts = getConfig().getBoolean("inject-only-rideable-minecarts", true);
        this.controlOnlyPlayerRiddenMinecarts = getConfig().getBoolean("control-only-player-ridden-minecarts", true);
        this.injectOnVehicleUpdate = getConfig().getBoolean("inject-on-vehicle-update", true);
        this.logInjectionCounts = getConfig().getBoolean("log-injection-counts", true);
        this.forceLegacyClientSmoothSync = getConfig().getBoolean("force-legacy-client-smooth-sync", true);
        this.visualSyncTrackingRangeBlocks = getConfig().getDouble("visual-sync-tracking-range-blocks", 128.0D);

        this.bedrockSupportEnabled = getConfig().getBoolean("bedrock-support.enabled", true);
        this.bedrockDetectGeyserPlayers = getConfig().getBoolean("bedrock-support.detect-geyser-players", true);
        this.bedrockSendExtraMotionPackets = getConfig().getBoolean("bedrock-support.send-extra-motion-packets", true);
        this.bedrockForceGeyserExperimentalMode = getConfig().getBoolean("bedrock-support.force-geyser-experimental-minecart-mode", true);
        this.bedrockDisableClientVehiclePrediction = getConfig().getBoolean("bedrock-support.disable-client-vehicle-prediction-while-riding", true);
        this.bedrockSendDirectMovePackets = getConfig().getBoolean("bedrock-support.send-direct-bedrock-move-packets", true);
        this.bedrockDirectMoveIntervalTicks = Math.max(1, getConfig().getInt("bedrock-support.direct-move-interval-ticks", 1));
        this.bedrockMotionSyncIntervalTicks = Math.max(1, getConfig().getInt("bedrock-support.motion-sync-interval-ticks", 1));
        this.bedrockResendPassengersIntervalTicks = Math.max(0, getConfig().getInt("bedrock-support.resend-passengers-interval-ticks", 20));
        this.bedrockFallbackOriginalSpeedForBedrockRiders = getConfig().getBoolean("bedrock-support.fallback-original-speed-for-bedrock-riders", false);
        this.bedrockDebugLog = getConfig().getBoolean("bedrock-support.debug-log", false);

        if (geyserHook != null) {
            geyserHook.reload();
        }
    }

    public double maxSpeedBlocksPerSecond() {
        return maxSpeedBlocksPerSecond;
    }

    public double normalMaxSpeedBlocksPerSecond() {
        return normalMaxSpeedBlocksPerSecond;
    }

    public boolean speedOnlyWhenPlayerRiding() {
        return speedOnlyWhenPlayerRiding;
    }

    public double fastMaxSpeedBlocksPerTick() {
        return maxSpeedBlocksPerSecond / 20.0D;
    }

    public double normalMaxSpeedBlocksPerTick() {
        return normalMaxSpeedBlocksPerSecond / 20.0D;
    }

    /** Compatibility alias for older code paths. */
    public double maxSpeedBlocksPerTick() {
        return fastMaxSpeedBlocksPerTick();
    }

    /**
     * True when this plugin is allowed to replace the cart's vanilla behavior.
     *
     * IMPORTANT: hopper/chest/furnace/TNT/command/spawner minecarts are utility minecarts.
     * They are often used inside redstone farms and item sorters. Replacing their movement
     * controller can change hopper pickup timing and rail alignment, so by default we only
     * control the normal rideable minecart entity type.
     *
     * Even normal rideable minecarts are often used as mob carriers in redstone farms.
     * Therefore control-only-player-ridden-minecarts defaults to true: empty carts and
     * mob-ridden carts stay completely vanilla. The plugin injects NewMinecartBehavior
     * only while at least one Player is riding the cart.
     */
    public boolean shouldControlMinecart(Minecart minecart) {
        if (injectOnlyRideableMinecarts && minecart.getType() != EntityType.MINECART) {
            return false;
        }
        if (controlOnlyPlayerRiddenMinecarts && !hasPlayerPassenger(minecart)) {
            return false;
        }
        return true;
    }

    public boolean shouldUseFastSpeed(Minecart minecart) {
        return shouldControlMinecart(minecart) && (!speedOnlyWhenPlayerRiding || hasPlayerPassenger(minecart));
    }

    public boolean injectOnlyRideableMinecarts() {
        return injectOnlyRideableMinecarts;
    }

    public boolean controlOnlyPlayerRiddenMinecarts() {
        return controlOnlyPlayerRiddenMinecarts;
    }

    public boolean hasPlayerPassenger(Minecart minecart) {
        for (Entity passenger : minecart.getPassengers()) {
            if (passenger instanceof Player) {
                return true;
            }
        }
        return false;
    }

    public boolean forceLegacyClientSmoothSync() {
        return forceLegacyClientSmoothSync;
    }

    public double visualSyncTrackingRangeBlocks() {
        return visualSyncTrackingRangeBlocks;
    }

    public boolean bedrockSupportEnabled() {
        return bedrockSupportEnabled;
    }

    public boolean bedrockDetectGeyserPlayers() {
        return bedrockDetectGeyserPlayers;
    }

    public boolean bedrockSendExtraMotionPackets() {
        return bedrockSendExtraMotionPackets;
    }

    public boolean bedrockForceGeyserExperimentalMode() {
        return bedrockForceGeyserExperimentalMode;
    }

    public boolean bedrockDisableClientVehiclePrediction() {
        return bedrockDisableClientVehiclePrediction;
    }

    public boolean bedrockSendDirectMovePackets() {
        return bedrockSendDirectMovePackets;
    }

    public int bedrockDirectMoveIntervalTicks() {
        return bedrockDirectMoveIntervalTicks;
    }

    public int bedrockMotionSyncIntervalTicks() {
        return bedrockMotionSyncIntervalTicks;
    }

    public int bedrockResendPassengersIntervalTicks() {
        return bedrockResendPassengersIntervalTicks;
    }

    public boolean bedrockFallbackOriginalSpeedForBedrockRiders() {
        return bedrockFallbackOriginalSpeedForBedrockRiders;
    }

    public boolean bedrockDebugLog() {
        return bedrockDebugLog;
    }

    public boolean isBedrockPlayer(Player player) {
        return bedrockSupportEnabled && geyserHook != null && geyserHook.isBedrockPlayer(player);
    }

    public boolean isGeyserHookAvailable() {
        return geyserHook != null && geyserHook.isAvailable();
    }

    public boolean forceGeyserExperimentalMinecartLogic(Player player) {
        return geyserHook != null && geyserHook.forceExperimentalMinecartLogic(player);
    }

    public boolean disableGeyserClientPredictedVehicle(Player player) {
        return geyserHook != null && geyserHook.disableClientPredictedVehicle(player);
    }

    public boolean moveBedrockEntityDirect(Player viewer, int javaEntityId, double x, double y, double z, float yaw, float pitch, float headYaw, boolean onGround, boolean teleported) {
        return geyserHook != null && geyserHook.moveBedrockEntity(viewer, javaEntityId, x, y, z, yaw, pitch, headYaw, onGround, teleported);
    }

    public void setMaxSpeedBlocksPerSecond(double value) {
        this.maxSpeedBlocksPerSecond = clamp(value, MIN_SPEED_BPS, MAX_SPEED_BPS);
        getConfig().set("max-speed-blocks-per-second", this.maxSpeedBlocksPerSecond);
        saveConfig();
    }

    public boolean injectMinecart(Minecart minecart) {
        return injector.inject(minecart);
    }

    public int injectLoadedMinecarts() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Minecart minecart : world.getEntitiesByClass(Minecart.class)) {
                if (injectMinecart(minecart)) {
                    count++;
                }
            }
        }
        return count;
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleCreate(VehicleCreateEvent event) {
        if (event.getVehicle() instanceof Minecart minecart) {
            Bukkit.getScheduler().runTask(this, () -> injectMinecart(minecart));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleUpdate(VehicleUpdateEvent event) {
        if (!injectOnVehicleUpdate) {
            return;
        }
        if (event.getVehicle() instanceof Minecart minecart) {
            injectMinecart(minecart);
        }
    }


    @EventHandler(ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!(event.getVehicle() instanceof Minecart minecart)) {
            return;
        }
        // Wait one tick so Bukkit/NMS passenger lists have already been updated.
        Bukkit.getScheduler().runTask(this, () -> injectMinecart(minecart));
    }


    @EventHandler(ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (!(event.getVehicle() instanceof Minecart minecart)) {
            return;
        }

        // Wait one tick so the passenger has already been removed. If there is no
        // player passenger anymore, restore vanilla behavior. This keeps mob carts,
        // redstone carrier carts, and empty carts from staying on NewMinecartBehavior.
        Bukkit.getScheduler().runTask(this, () -> {
            if (!hasPlayerPassenger(minecart)) {
                if (clampEmptyCartVelocityOnExit) {
                    clampVelocityToNormalSpeed(minecart);
                }
                injectMinecart(minecart); // will restore vanilla behavior when shouldControlMinecart() is false
            }
        });
    }

    private void clampVelocityToNormalSpeed(Minecart minecart) {
        var velocity = minecart.getVelocity();
        double speed = velocity.length();
        double limit = normalMaxSpeedBlocksPerTick();
        if (speed > limit && speed > 0.0D) {
            minecart.setVelocity(velocity.multiply(limit / speed));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Bukkit.getScheduler().runTask(this, () -> {
            for (Entity entity : event.getChunk().getEntities()) {
                if (entity instanceof Minecart minecart) {
                    injectMinecart(minecart);
                }
            }
        });
    }


    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Bedrock reconnect creates a new GeyserSession, but this plugin's in-memory caches survive.
        // Reset and send hard corrections a few times because Geyser may not have created all Bedrock
        // entity mappings during the very first join tick.
        resetBedrockSessionState(event.getPlayer(), 1L);
        resetBedrockSessionState(event.getPlayer(), 10L);
        resetBedrockSessionState(event.getPlayer(), 40L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (geyserHook != null) {
            geyserHook.resetPlayer(event.getPlayer().getUniqueId());
        }
    }

    private void resetBedrockSessionState(Player player, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (geyserHook != null) {
                geyserHook.resetPlayer(player.getUniqueId());
            }
            if (visualSyncer != null) {
                visualSyncer.resetStateCache();
            }
            injectLoadedMinecarts();
            if (bedrockDebugLog && isBedrockPlayer(player)) {
                getLogger().info("Reset Bedrock minecart bridge state for reconnecting player " + player.getName() + " after " + delayTicks + " tick(s).");
            }
        }, delayTicks);
    }

    private void forceBedrockResyncNow() {
        if (geyserHook != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                geyserHook.resetPlayer(player.getUniqueId());
            }
        }
        if (visualSyncer != null) {
            visualSyncer.resetStateCache();
        }
        injectLoadedMinecarts();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            sender.sendMessage("§aExperimentalMinecartSpeed");
            sender.sendMessage("§7玩家乘坐最大速度：§e" + format(maxSpeedBlocksPerSecond) + " §7blocks/s §8(" + format(fastMaxSpeedBlocksPerTick()) + " blocks/tick)");
            sender.sendMessage("§7無玩家乘坐速度：§e" + format(normalMaxSpeedBlocksPerSecond) + " §7blocks/s §8(" + format(normalMaxSpeedBlocksPerTick()) + " blocks/tick)");
            sender.sendMessage("§7只加速玩家乘坐礦車：§e" + (speedOnlyWhenPlayerRiding ? "開啟" : "關閉"));
            sender.sendMessage("§7只處理普通可乘坐礦車：§e" + (injectOnlyRideableMinecarts ? "開啟" : "關閉") + " §8(漏斗/箱子等功能礦車保持原版)");
            sender.sendMessage("§7只控制玩家乘坐中的礦車：§e" + (controlOnlyPlayerRiddenMinecarts ? "開啟" : "關閉") + " §8(空車/怪物載具保持原版)");
            sender.sendMessage("§7此插件使用 vanilla experimental NewMinecartBehavior，只依乘客狀態覆寫 getMaxSpeed().");
            sender.sendMessage("§7視覺同步：§e" + (forceLegacyClientSmoothSync ? "每 tick 平滑同步" : "關閉"));
            sender.sendMessage("§7Bedrock/Geyser 額外 motion 同步：§e" + (bedrockSupportEnabled && bedrockSendExtraMotionPackets ? "開啟" : "關閉") + " §8(Geyser API: " + (isGeyserHookAvailable() ? "可用" : "未偵測") + ")");
            sender.sendMessage("§7Bedrock 直接移動橋接：§e" + (bedrockSupportEnabled && bedrockSendDirectMovePackets ? "開啟" : "關閉") + " §8(強制實驗礦車: " + (bedrockForceGeyserExperimentalMode ? "開" : "關") + ")");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadSettings();
            int count = injectLoadedMinecarts();
            sender.sendMessage("§a設定已重新載入。速度：§e" + format(maxSpeedBlocksPerSecond) + " §ablocks/s；重新檢查礦車：§e" + count);
            return true;
        }

        if (args[0].equalsIgnoreCase("resync")) {
            forceBedrockResyncNow();
            sender.sendMessage("§a已重置 Bedrock/Geyser 礦車同步快取，下一 tick 會重新送硬校正。此指令可用來測試登出重登後的不同步問題。");
            return true;
        }

        if (args[0].equalsIgnoreCase("set")) {
            if (args.length < 2) {
                sender.sendMessage("§c用法：/" + label + " set <blocks-per-second>");
                return true;
            }

            double value;
            try {
                value = Double.parseDouble(args[1]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("§c速度必須是數字，例如：/" + label + " set 24");
                return true;
            }

            setMaxSpeedBlocksPerSecond(value);
            int count = injectLoadedMinecarts();
            sender.sendMessage("§a玩家乘坐礦車最大速度已設為：§e" + format(maxSpeedBlocksPerSecond) + " §ablocks/s §8(" + format(fastMaxSpeedBlocksPerTick()) + " blocks/tick)");
            sender.sendMessage("§7已重新檢查載入中的礦車：§e" + count);
            return true;
        }

        sender.sendMessage("§c用法：/" + label + " <info|set|reload|resync>");
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String label,
                                                @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("info", "set", "reload", "resync"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return filter(List.of("8", "16", "24", "32", "64"), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                result.add(value);
            }
        }
        return result;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
