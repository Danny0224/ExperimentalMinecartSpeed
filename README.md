# ExperimentalMinecartSpeed 26.1.2 - Geyser Reconnect Fix

This build keeps Java Edition visual sync working, but changes the Bedrock/Geyser path to avoid two competing movement authorities.

## Why this build exists

The previous Geyser bridge could look correct right after server startup, then drift on curves or feel speed-limited after running for a while. The likely cause is that Bedrock viewers were receiving both:

1. Java relative movement packets translated by Geyser, and
2. Direct Bedrock/Geyser entity movement from the plugin.

On straight rails this can look acceptable, but on turns the two paths disagree and Bedrock local vehicle prediction can clamp the ridden minecart toward vanilla speed.

## Main changes

- Bedrock viewers no longer receive the plugin's Java fallback move packets when direct Bedrock movement is enabled.
- Extra Java motion packets are disabled by default for Bedrock when direct movement is enabled.
- Direct Geyser entity movement remains every tick.
- Geyser experimental minecart mode is still forced for Bedrock sessions.
- Client-predicted vehicle mode is still disabled while a Bedrock player is riding a plugin-controlled minecart.

## Recommended config for Bedrock testing

```yaml
max-speed-blocks-per-second: 16.0
speed-only-when-player-riding: true
normal-speed-blocks-per-second: 8.0
force-legacy-client-smooth-sync: true

bedrock-support:
  enabled: true
  force-geyser-experimental-minecart-mode: true
  disable-client-vehicle-prediction-while-riding: true
  send-direct-bedrock-move-packets: true
  direct-move-interval-ticks: 1
  send-extra-motion-packets: false
  resend-passengers-interval-ticks: 20
  fallback-original-speed-for-bedrock-riders: false
  debug-log: true
```

If Bedrock still feels speed-limited after this, the remaining limit is likely inside Bedrock's ridden-vehicle prediction or Geyser's internal vehicle translator. At that point the stable fallback is `fallback-original-speed-for-bedrock-riders: true`, or a real Geyser fork/extension that changes the Bedrock vehicle translator.

## Build

Requires Java 25 and Paper 26.1.2 userdev.

```bash
gradle clean build
```

The jar will be in `build/libs/`.


## Redstone / hopper minecart safety

Version 1.0.6 adds `inject-only-rideable-minecarts: true` by default.
With this enabled, hopper minecarts, chest minecarts, furnace minecarts, TNT minecarts, command-block minecarts, and spawner minecarts are left on vanilla behavior.

This matters because hopper-minecart item sorters depend on exact pickup timing and rail alignment. Replacing their movement controller with experimental minecart physics can break distribution systems even when the configured fast speed only applies to player-ridden carts.

After updating from an older build that already injected hopper minecarts, do a full server restart instead of a plugin reload.


## Mob / redstone minecart safety

This build adds `control-only-player-ridden-minecarts: true` by default.

With this enabled, the plugin only injects `NewMinecartBehavior` while a real player is riding a normal minecart. Normal minecarts carrying mobs, animals, villagers, or used by redstone machines stay vanilla. On player exit, the plugin clamps leftover speed and restores the old minecart behavior.

Recommended defaults for survival/redstone servers:

```yaml
inject-only-rideable-minecarts: true
control-only-player-ridden-minecarts: true
speed-only-when-player-riding: true
```
