package com.danny29404711.minecartspeed;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftMinecart;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Compatibility visual sync.
 *
 * Why this exists:
 * NewMinecartBehavior is an experimental/vanilla behavior that has its own client-side interpolation path.
 * If we only replace the server-side behavior using reflection, the server can move the cart while vanilla
 * clients that did not create the cart with experimental movement may not render that movement correctly.
 *
 * Java fallback:
 * Sends normal vanilla entity movement packets every tick. It prefers relative move packets because they
 * interpolate more smoothly than hard teleporting. Teleport is only used if the cart moved too far in one tick.
 *
 * Bedrock/Geyser fallback:
 * Bedrock clients can additionally apply their own vehicle prediction when riding. For those players, this class
 * now does three things:
 * 1. Forces GeyserSession#setUsingExperimentalMinecartLogic(true), matching the server's injected behavior.
 * 2. Sends Java motion/passenger packets so Geyser's cache is updated.
 * 3. Directly calls Geyser's Bedrock entity move path so the Bedrock client receives server-authoritative cart movement.
 */
public final class MinecartVisualSyncer {

    private static final double DEFAULT_TRACKING_RANGE_BLOCKS = 128.0D;
    private static final double RELATIVE_PACKET_LIMIT_BLOCKS = 7.9D;
    private static final double MIN_SEND_DISTANCE_SQUARED = 1.0D / (4096.0D * 4096.0D);

    private final ExperimentalMinecartSpeedPlugin plugin;
    private final Map<UUID, LastState> lastStates = new HashMap<>();
    private long tickCounter;

    public MinecartVisualSyncer(ExperimentalMinecartSpeedPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Forces the next tick to send hard initial corrections again.
     * Used after a Bedrock reconnect because the Bedrock client/Geyser entity cache may have been recreated
     * while this syncer still remembers the old minecart positions from the previous session.
     */
    public void resetStateCache() {
        lastStates.clear();
    }

    public void tick() {
        tickCounter++;

        if (!plugin.forceLegacyClientSmoothSync()) {
            return;
        }

        Set<UUID> stillLoaded = new HashSet<>();

        for (World world : Bukkit.getWorlds()) {
            for (Minecart minecart : world.getEntitiesByClass(Minecart.class)) {
                if (!(minecart instanceof CraftMinecart craftMinecart)) {
                    continue;
                }
                if (!(craftMinecart.getHandle().getBehavior() instanceof ConfigurableNewMinecartBehavior)) {
                    continue;
                }
                if (!plugin.shouldControlMinecart(minecart)) {
                    continue;
                }
                // Important: do NOT limit visual sync to only fast/player-ridden carts.
                // Once a cart has been given NewMinecartBehavior on the server, vanilla clients may
                // fail to render its movement even when getMaxSpeed() returns the normal 8 blocks/s.
                // Speed limiting and visual synchronization must be separate concerns:
                // - ConfigurableNewMinecartBehavior decides whether the cart is fast.
                // - This syncer keeps every injected cart visually moving for clients.
                stillLoaded.add(minecart.getUniqueId());
                syncMinecart(minecart, craftMinecart);
            }
        }

        lastStates.keySet().removeIf(uuid -> !stillLoaded.contains(uuid));
    }

    private void syncMinecart(Minecart minecart, CraftMinecart craftMinecart) {
        var handle = craftMinecart.getHandle();
        LastState current = LastState.from(handle);
        LastState previous = lastStates.put(minecart.getUniqueId(), current);

        if (previous == null) {
            broadcastNearby(minecart, ClientboundTeleportEntityPacket.teleport(
                    handle.getId(),
                    PositionMoveRotation.of(handle),
                    Set.of(),
                    handle.onGround()
            ));
            sendBedrockExtras(minecart, craftMinecart, true, true);
            return;
        }

        double dx = current.x - previous.x;
        double dy = current.y - previous.y;
        double dz = current.z - previous.z;
        boolean rotationChanged = current.encodedYRot != previous.encodedYRot || current.encodedXRot != previous.encodedXRot;

        if (!rotationChanged && (dx * dx + dy * dy + dz * dz) < MIN_SEND_DISTANCE_SQUARED) {
            sendBedrockExtras(minecart, craftMinecart, false, false);
            return;
        }

        Packet<?> packet;
        if (Math.abs(dx) <= RELATIVE_PACKET_LIMIT_BLOCKS
                && Math.abs(dy) <= RELATIVE_PACKET_LIMIT_BLOCKS
                && Math.abs(dz) <= RELATIVE_PACKET_LIMIT_BLOCKS) {
            // Smooth path: one relative movement packet every tick.
            packet = new ClientboundMoveEntityPacket.PosRot(
                    handle.getId(),
                    encodeRelative(dx),
                    encodeRelative(dy),
                    encodeRelative(dz),
                    current.encodedYRot,
                    current.encodedXRot,
                    handle.onGround()
            );
        } else {
            // Fallback for extreme speeds. This is less smooth, so very high speeds may still visually snap.
            packet = ClientboundTeleportEntityPacket.teleport(
                    handle.getId(),
                    PositionMoveRotation.of(handle),
                    Set.of(),
                    handle.onGround()
            );
        }

        broadcastNearby(minecart, packet);
        sendBedrockExtras(minecart, craftMinecart, false, rotationChanged);
    }

    private void sendBedrockExtras(Minecart minecart, CraftMinecart craftMinecart, boolean forcePassengers, boolean hardCorrection) {
        if (!plugin.bedrockSupportEnabled() || !plugin.isGeyserHookAvailable()) {
            return;
        }

        int motionInterval = plugin.bedrockMotionSyncIntervalTicks();
        boolean sendMotion = plugin.bedrockSendExtraMotionPackets() && (motionInterval <= 1 || tickCounter % motionInterval == 0);

        int passengerInterval = plugin.bedrockResendPassengersIntervalTicks();
        boolean sendPassengers = forcePassengers || (passengerInterval > 0 && tickCounter % passengerInterval == 0);

        int directMoveInterval = plugin.bedrockDirectMoveIntervalTicks();
        boolean sendDirectMove = plugin.bedrockSendDirectMovePackets() && (directMoveInterval <= 1 || tickCounter % directMoveInterval == 0);

        if (!sendMotion && !sendPassengers && !sendDirectMove && !plugin.bedrockForceGeyserExperimentalMode()) {
            return;
        }

        var handle = craftMinecart.getHandle();

        if (sendMotion) {
            // Java packet path: lets Geyser receive velocity information.
            // When direct Bedrock movement is enabled, do not also push Java motion packets through Geyser.
            // Sending both paths can slowly accumulate disagreement and makes turns drift on Bedrock riders.
            if (!plugin.bedrockSendDirectMovePackets()) {
                broadcastNearbyBedrock(minecart, new ClientboundSetEntityMotionPacket(handle));
            }
        }

        if (sendPassengers) {
            // Safety net for vehicle/rider desync through Geyser.
            broadcastNearbyBedrock(minecart, new ClientboundSetPassengersPacket(handle));
        }

        if (sendDirectMove || plugin.bedrockForceGeyserExperimentalMode()) {
            // Bedrock packet path: force Geyser's Bedrock session/entity cache to use the server-authoritative cart position.
            syncDirectlyToBedrock(minecart, craftMinecart, sendDirectMove, hardCorrection);
        }
    }

    private void syncDirectlyToBedrock(Minecart minecart, CraftMinecart craftMinecart, boolean sendDirectMove, boolean hardCorrection) {
        double range = plugin.visualSyncTrackingRangeBlocks();
        if (range <= 0.0D) {
            range = DEFAULT_TRACKING_RANGE_BLOCKS;
        }
        double rangeSq = range * range;
        var handle = craftMinecart.getHandle();

        for (Player player : minecart.getWorld().getPlayers()) {
            if (!plugin.isBedrockPlayer(player)) {
                continue;
            }
            if (player.getLocation().distanceSquared(minecart.getLocation()) > rangeSq) {
                continue;
            }

            if (plugin.bedrockForceGeyserExperimentalMode()) {
                plugin.forceGeyserExperimentalMinecartLogic(player);
            }

            if (plugin.bedrockDisableClientVehiclePrediction() && minecart.getPassengers().contains(player)) {
                plugin.disableGeyserClientPredictedVehicle(player);
            }

            if (sendDirectMove) {
                plugin.moveBedrockEntityDirect(
                        player,
                        handle.getId(),
                        handle.getX(),
                        handle.getY(),
                        handle.getZ(),
                        handle.getYRot(),
                        handle.getXRot(),
                        handle.getYRot(),
                        handle.onGround(),
                        hardCorrection
                );
            }
        }
    }

    private void broadcastNearby(Minecart minecart, Packet<?> packet) {
        double range = plugin.visualSyncTrackingRangeBlocks();
        if (range <= 0.0D) {
            range = DEFAULT_TRACKING_RANGE_BLOCKS;
        }
        double rangeSq = range * range;

        for (Player player : minecart.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(minecart.getLocation()) > rangeSq) {
                continue;
            }
            // If we are directly moving Bedrock entities through Geyser, do not also let this Java
            // relative/teleport packet go through Geyser's normal Java->Bedrock translator for Bedrock viewers.
            // Two movement authorities are exactly what causes delayed drift, especially on curves.
            if (plugin.bedrockSupportEnabled()
                    && plugin.bedrockSendDirectMovePackets()
                    && plugin.isGeyserHookAvailable()
                    && plugin.isBedrockPlayer(player)) {
                continue;
            }
            ((CraftPlayer) player).getHandle().connection.send(packet);
        }
    }

    private void broadcastNearbyBedrock(Minecart minecart, Packet<?> packet) {
        double range = plugin.visualSyncTrackingRangeBlocks();
        if (range <= 0.0D) {
            range = DEFAULT_TRACKING_RANGE_BLOCKS;
        }
        double rangeSq = range * range;

        for (Player player : minecart.getWorld().getPlayers()) {
            if (!plugin.isBedrockPlayer(player)) {
                continue;
            }
            if (player.getLocation().distanceSquared(minecart.getLocation()) > rangeSq) {
                continue;
            }
            ((CraftPlayer) player).getHandle().connection.send(packet);
        }
    }

    private static short encodeRelative(double delta) {
        int encoded = (int) Math.round(delta * 4096.0D);
        if (encoded > Short.MAX_VALUE) {
            encoded = Short.MAX_VALUE;
        } else if (encoded < Short.MIN_VALUE) {
            encoded = Short.MIN_VALUE;
        }
        return (short) encoded;
    }

    private static byte encodeRotation(float degrees) {
        return (byte) ((int) (degrees * 256.0F / 360.0F));
    }

    private record LastState(double x, double y, double z, byte encodedYRot, byte encodedXRot) {
        static LastState from(net.minecraft.world.entity.vehicle.minecart.AbstractMinecart handle) {
            return new LastState(
                    handle.getX(),
                    handle.getY(),
                    handle.getZ(),
                    encodeRotation(handle.getYRot()),
                    encodeRotation(handle.getXRot())
            );
        }
    }
}
