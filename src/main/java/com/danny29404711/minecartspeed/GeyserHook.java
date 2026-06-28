package com.danny29404711.minecartspeed;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Optional Geyser bridge.
 *
 * This class intentionally uses reflection instead of a compile-time dependency.
 * The plugin can still start on Java-only servers that do not have Geyser installed.
 *
 * Why reflection is used here:
 * - The public Geyser API can tell us whether a player is a Bedrock player.
 * - The actual vehicle/minecart compatibility bits live on GeyserSession/internal entity classes.
 * - By reflecting these methods, the plugin can run without shading Geyser or Cloudburst classes.
 */
public final class GeyserHook {

    private final ExperimentalMinecartSpeedPlugin plugin;
    private boolean available;
    private Object geyserApi;
    private Method isBedrockPlayerMethod;
    private Method connectionByUuidMethod;
    private Method vector3fFromMethod;
    // Do not treat this as a long-lived player flag. A Bedrock reconnect creates a new GeyserSession
    // for the same UUID, so session flags must be re-applied after login.
    // We keep only gamerule speed caching and clear it on quit/join.
    private final Map<UUID, Double> lastSentMinecartSpeed = new HashMap<>();

    public GeyserHook(ExperimentalMinecartSpeedPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.available = false;
        this.geyserApi = null;
        this.isBedrockPlayerMethod = null;
        this.connectionByUuidMethod = null;
        this.vector3fFromMethod = null;
        this.lastSentMinecartSpeed.clear();

        if (!plugin.bedrockSupportEnabled() || !plugin.bedrockDetectGeyserPlayers()) {
            return;
        }

        try {
            Class<?> geyserApiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Method apiMethod = geyserApiClass.getMethod("api");
            Object api = apiMethod.invoke(null);
            if (api == null) {
                if (plugin.bedrockDebugLog()) {
                    plugin.getLogger().warning("GeyserApi.api() returned null. Geyser may not be enabled yet.");
                }
                return;
            }

            Method isBedrock = geyserApiClass.getMethod("isBedrockPlayer", UUID.class);
            Method connectionByUuid = geyserApiClass.getMethod("connectionByUuid", UUID.class);

            Class<?> vector3fClass = Class.forName("org.cloudburstmc.math.vector.Vector3f");
            Method vectorFrom = vector3fClass.getMethod("from", float.class, float.class, float.class);

            this.geyserApi = api;
            this.isBedrockPlayerMethod = isBedrock;
            this.connectionByUuidMethod = connectionByUuid;
            this.vector3fFromMethod = vectorFrom;
            this.available = true;
            plugin.getLogger().info("Geyser API detected. Bedrock experimental minecart bridge is available.");
        } catch (Throwable throwable) {
            if (plugin.bedrockDebugLog()) {
                plugin.getLogger().warning("Geyser API was not detected or could not be initialized: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isBedrockPlayer(Player player) {
        if (!available || player == null) {
            return false;
        }

        try {
            Object result = isBedrockPlayerMethod.invoke(geyserApi, player.getUniqueId());
            return Boolean.TRUE.equals(result);
        } catch (Throwable throwable) {
            if (plugin.bedrockDebugLog()) {
                plugin.getLogger().warning("Could not check whether " + player.getName() + " is a Bedrock player: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
            return false;
        }
    }

    /**
     * Enables Geyser's internal experimental minecart path for one Bedrock session.
     *
     * Geyser has a session flag named setUsingExperimentalMinecartLogic(boolean). In a vanilla experimental world,
     * Geyser learns this from the Java server. Our Paper plugin injects NewMinecartBehavior manually, so Geyser does
     * not necessarily know the session should use the new minecart translator path. This method forces that flag on.
     */
    public boolean forceExperimentalMinecartLogic(Player player) {
        Object connection = connection(player);
        if (connection == null) {
            return false;
        }
        // IMPORTANT: call this every sync tick while relevant. Do not cache by UUID.
        // A Bedrock reconnect creates a fresh GeyserSession for the same UUID; if we skip this setter
        // because the UUID was seen before, the new session falls back to normal minecart logic and
        // the rider sees a frozen/original-speed cart even though the server and Java clients are moving.
        boolean ok = invokeBooleanSetter(connection, "setUsingExperimentalMinecartLogic", true);

        // Also try to send the gamerule name to the Bedrock client through Geyser, if supported.
        // Unknown gamerules are ignored by clients; this is harmless and useful for versions that recognize it.
        double speed = plugin.maxSpeedBlocksPerSecond();
        Double lastSpeed = lastSentMinecartSpeed.get(player.getUniqueId());
        if (lastSpeed == null || Math.abs(lastSpeed - speed) > 0.0001D) {
            trySendGameRule(connection, "minecartMaxSpeed", speed);
            lastSentMinecartSpeed.put(player.getUniqueId(), speed);
        }
        return ok;
    }

    /**
     * Bedrock clients report IN_CLIENT_PREDICTED_IN_VEHICLE while riding. That prediction is exactly what makes
     * high-speed minecarts look original-speed on Bedrock. This setter is overwritten by Geyser each input tick,
     * so we call it every sync tick while a Bedrock player is riding a plugin-controlled minecart.
     */
    public boolean disableClientPredictedVehicle(Player player) {
        Object connection = connection(player);
        if (connection == null) {
            return false;
        }
        return invokeBooleanSetter(connection, "setInClientPredictedVehicle", false);
    }

    /**
     * Directly updates the Bedrock-side entity in Geyser and sends a MoveEntityAbsolutePacket to the Bedrock client.
     *
     * This bypasses the Java->Geyser translator for the final visible movement. We still let Geyser receive the Java
     * entity packets so its cache stays fresh, but this direct move is sent afterwards as the visual authority.
     */
    public boolean moveBedrockEntity(Player viewer,
                                     int javaEntityId,
                                     double x,
                                     double y,
                                     double z,
                                     float yaw,
                                     float pitch,
                                     float headYaw,
                                     boolean onGround,
                                     boolean teleported) {
        Object connection = connection(viewer);
        if (connection == null) {
            return false;
        }

        try {
            Method entityByJavaId = connection.getClass().getMethod("entityByJavaId", int.class);
            Object futureObject = entityByJavaId.invoke(connection, javaEntityId);
            Object geyserEntity = null;
            if (futureObject instanceof CompletableFuture<?> future) {
                geyserEntity = future.getNow(null);
            } else if (futureObject != null) {
                geyserEntity = futureObject;
            }

            if (geyserEntity == null) {
                return false;
            }

            Object vector = vector3fFromMethod.invoke(null, (float) x, (float) y, (float) z);

            // Preferred: internal Entity#moveAbsoluteRaw(Vector3f, yaw, pitch, headYaw, onGround, teleported)
            Method moveAbsoluteRaw = findMethod(geyserEntity.getClass(), "moveAbsoluteRaw", 6);
            if (moveAbsoluteRaw != null) {
                moveAbsoluteRaw.invoke(geyserEntity, vector, yaw, pitch, headYaw, onGround, teleported);
                return true;
            }

            // Fallback: internal Entity#moveAbsolute(Vector3f, yaw, pitch, headYaw, onGround, teleported)
            Method moveAbsolute = findMethod(geyserEntity.getClass(), "moveAbsolute", 6);
            if (moveAbsolute != null) {
                moveAbsolute.invoke(geyserEntity, vector, yaw, pitch, headYaw, onGround, teleported);
                return true;
            }

            // Fallback for older signatures: moveAbsolute(Vector3f, yaw, pitch, onGround, teleported)
            Method moveAbsolute5 = findMethod(geyserEntity.getClass(), "moveAbsolute", 5);
            if (moveAbsolute5 != null) {
                moveAbsolute5.invoke(geyserEntity, vector, yaw, pitch, onGround, teleported);
                return true;
            }
        } catch (Throwable throwable) {
            if (plugin.bedrockDebugLog()) {
                plugin.getLogger().warning("Could not directly move Bedrock entity " + javaEntityId + " for " + viewer.getName() + ": " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
        }
        return false;
    }

    /**
     * Clears per-player bridge cache when a Bedrock player disconnects/reconnects.
     * Geyser recreates the session object, so all session-local compatibility flags must be resent.
     */
    public void resetPlayer(UUID uuid) {
        if (uuid == null) {
            return;
        }
        lastSentMinecartSpeed.remove(uuid);
    }

    private Object connection(Player player) {
        if (!available || player == null) {
            return null;
        }
        try {
            return connectionByUuidMethod.invoke(geyserApi, player.getUniqueId());
        } catch (Throwable throwable) {
            if (plugin.bedrockDebugLog()) {
                plugin.getLogger().warning("Could not get Geyser connection for " + player.getName() + ": " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
            return null;
        }
    }

    private boolean invokeBooleanSetter(Object target, String methodName, boolean value) {
        try {
            Method method = target.getClass().getMethod(methodName, boolean.class);
            method.invoke(target, value);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (Throwable throwable) {
            if (plugin.bedrockDebugLog()) {
                plugin.getLogger().warning("Could not invoke " + methodName + " on Geyser session: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
            return false;
        }
    }

    private void trySendGameRule(Object connection, String rule, Object value) {
        try {
            Method method = connection.getClass().getMethod("sendGameRule", String.class, Object.class);
            method.invoke(connection, rule, value);
        } catch (Throwable ignored) {
            // Optional only.
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
