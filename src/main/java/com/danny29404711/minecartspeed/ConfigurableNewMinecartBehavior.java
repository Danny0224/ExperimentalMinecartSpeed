package com.danny29404711.minecartspeed;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import org.bukkit.entity.Player;

/**
 * Mojang-mapped vanilla experimental behavior.
 *
 * We intentionally do not reimplement rail physics here. NewMinecartBehavior keeps the official
 * experimental logic for rail stepping, slopes, powered rails, collisions, and interpolation.
 * The only customized part is the max speed returned to that vanilla behavior.
 */
public final class ConfigurableNewMinecartBehavior extends NewMinecartBehavior {

    private final AbstractMinecart minecart;
    private final ExperimentalMinecartSpeedPlugin plugin;

    public ConfigurableNewMinecartBehavior(AbstractMinecart minecart, ExperimentalMinecartSpeedPlugin plugin) {
        super(minecart);
        this.minecart = minecart;
        this.plugin = plugin;
    }

    @Override
    public double getMaxSpeed(ServerLevel level) {
        if (plugin.speedOnlyWhenPlayerRiding() && !hasPlayerPassenger()) {
            return plugin.normalMaxSpeedBlocksPerTick();
        }

        // Safety fallback, disabled by default. If Bedrock clients cannot be fixed by extra motion packets,
        // this can make Bedrock-ridden carts use the original speed while Java-ridden carts still go fast.
        if (plugin.bedrockFallbackOriginalSpeedForBedrockRiders() && hasBedrockPlayerPassenger()) {
            return plugin.normalMaxSpeedBlocksPerTick();
        }

        return plugin.fastMaxSpeedBlocksPerTick();
    }

    public boolean hasPlayerPassenger() {
        for (Entity passenger : minecart.getPassengers()) {
            if (passenger instanceof ServerPlayer) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBedrockPlayerPassenger() {
        for (Entity passenger : minecart.getPassengers()) {
            if (passenger instanceof ServerPlayer serverPlayer) {
                Player bukkitPlayer = serverPlayer.getBukkitEntity();
                if (plugin.isBedrockPlayer(bukkitPlayer)) {
                    return true;
                }
            }
        }
        return false;
    }
}
