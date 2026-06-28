package com.danny29404711.minecartspeed;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartBehavior;
import org.bukkit.craftbukkit.entity.CraftMinecart;
import org.bukkit.entity.Minecart;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class MinecartBehaviorInjector {

    private final ExperimentalMinecartSpeedPlugin plugin;
    private final Field behaviorField;

    public MinecartBehaviorInjector(ExperimentalMinecartSpeedPlugin plugin) {
        this.plugin = plugin;
        this.behaviorField = findBehaviorField();
        this.behaviorField.setAccessible(true);
    }

    public boolean inject(Minecart bukkitMinecart) {
        if (!(bukkitMinecart instanceof CraftMinecart craftMinecart)) {
            return false;
        }

        AbstractMinecart handle = craftMinecart.getHandle();

        // Do not alter excluded carts by default. This includes utility minecarts and,
        // when control-only-player-ridden-minecarts is true, empty or mob-ridden normal minecarts.
        // Redstone farms often use normal minecarts as mob carriers, and even tiny movement
        // controller changes can break pickup/transport timing.
        if (!plugin.shouldControlMinecart(bukkitMinecart)) {
            restoreVanillaBehaviorIfNeeded(handle, bukkitMinecart);
            return false;
        }

        MinecartBehavior current = handle.getBehavior();

        if (current instanceof ConfigurableNewMinecartBehavior) {
            return false;
        }

        try {
            behaviorField.set(handle, new ConfigurableNewMinecartBehavior(handle, plugin));
            return true;
        } catch (IllegalAccessException ex) {
            plugin.getLogger().warning("Could not replace minecart behavior: " + ex.getMessage());
            return false;
        }
    }

    private void restoreVanillaBehaviorIfNeeded(AbstractMinecart handle, Minecart bukkitMinecart) {
        if (!(handle.getBehavior() instanceof ConfigurableNewMinecartBehavior)) {
            return;
        }

        try {
            MinecartBehavior vanillaBehavior = createOldMinecartBehavior(handle);
            behaviorField.set(handle, vanillaBehavior);
            if (plugin.bedrockDebugLog()) {
                plugin.getLogger().info("Restored vanilla behavior for excluded minecart " + bukkitMinecart.getType() + " at " + bukkitMinecart.getLocation());
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not restore vanilla behavior for excluded minecart " + bukkitMinecart.getType()
                    + ". Fully restart the server after updating the plugin if hopper minecarts were already affected. Cause: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private static MinecartBehavior createOldMinecartBehavior(AbstractMinecart handle) throws Exception {
        Class<?> oldBehaviorClass = Class.forName("net.minecraft.world.entity.vehicle.minecart.OldMinecartBehavior");
        Constructor<?> constructor = oldBehaviorClass.getDeclaredConstructor(AbstractMinecart.class);
        constructor.setAccessible(true);
        return (MinecartBehavior) constructor.newInstance(handle);
    }

    private static Field findBehaviorField() {
        for (Field field : AbstractMinecart.class.getDeclaredFields()) {
            if (MinecartBehavior.class.isAssignableFrom(field.getType())) {
                // In 26.1.2 this is a private final MinecartBehavior field.
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                return field;
            }
        }
        throw new IllegalStateException("Could not find AbstractMinecart MinecartBehavior field. This server version may have changed minecart internals.");
    }
}
