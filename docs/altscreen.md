---
title: CarPlay AltScreen - the CarPlay map on the Virtual Cockpit
tags: [altscreen, cluster, context, deploy, verified]
status: verified-on-car
sources:
  - code: java_patch/com/luka/carplay/core/ScreenModule.java
  - code: java_patch/de/audi/tghu/fwhmi/DisplayManagerMIB2High.java
  - code: java_patch/com/luka/carplay/core/SteeringWheelInputModule.java
  - code: deploy/altscreen/start_vehicle.sh, stream_supervisor.sh
  - vehicle: MHI2Q US firmware AUG22 (P5087), logs 010-017
---

# CarPlay AltScreen - the CarPlay map on the Virtual Cockpit

The CarPlay map itself comes from the **AltScreen runtime by yuedizhibo and Lanye-z** (third-party
binaries, non-commercial licence - see [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)). This
repository integrates it with the route-guidance overlay: it decides *when* the cluster shows the CarPlay
map, keeps the turn arrows working on top of it, and falls back to the Audi map whenever the CarPlay picture
is not there.

## 🧭 Data path

```text
iPhone  --(private "stream 111", H.264)-->  dio_manager + libcarplay_altscreen.so (preloaded hook)
   -> shared memory /carplay111_h264 -> stock decoder -> /carplay111_decoded
   -> carplay-alt111-mirror-display (sidecar, GLES) draws into displayable 3
   -> cluster context 80  { 98 maneuver, 101/102 KDK backing, 3 CarPlay video }
```

| Part | Where | Job |
| --- | --- | --- |
| `libcarplay_altscreen.so` | `LD_PRELOAD` of `dio_manager` (`carplay_startup.sh`) | negotiates the cluster stream with the phone, taps and decodes it, forwards wheel zoom |
| `stream_supervisor.sh` | started by `carplay_monitor.sh` | starts the sidecar once the stream is valid, stops it when the stream ends; back-off and give-up after 5 failed starts |
| `start_vehicle.sh` / `stop_vehicle.sh` | called by the supervisor | launch / stop the sidecar with the right environment |
| `carplay-alt111-mirror-display` | sidecar process | renders the decoded frames to displayable 3, writes `/tmp/mmi-mirror-basevideo.ready` after its first successful present |
| `ScreenModule` (Java) | in the HMI VM | the only writer of cluster contexts; chooses 74 / 80 / 81 (below) |

## 🎛️ Which context is on the cluster

Three contexts, all **declared once at init** in `DisplayManagerMIB2High`:

| Context | Contents | Used when |
| --- | --- | --- |
| **80** | `{98, 101, 102, 3}` - arrows over the **CarPlay map** | CarPlay map mode and the sidecar is presenting (cruising *and* navigating) |
| **81** | `{98, 101, 102, 33}` - the same arrows over the **Audi map** | a turn approach in Audi map mode, or before the sidecar presents |
| **74** | stock cluster | everything else, and after disconnect |

The turn arrow and its black backing plate (101/102, the "pill") are shown by `ClusterLayerController` only
while a turn is being presented and the Virtual Cockpit reports the maneuver visible. 80 <-> 81 is a plain context change; 74 -> 80/81 goes through a short hop via the stock map
(context 72) so the cluster encoder is re-acquired.

**Why two contexts and not one with a swapped map?** An early version edited `dc[80]` at runtime to swap
displayable 3 for 33. The compositor only reads the table at init, so the swap never took effect: the screen
flashed the stock map and snapped back to CarPlay. Two fixed contexts fixed it.

**What shows the CarPlay map.** Java does not trust the supervisor (it raises a "demand" file *before* the
sidecar even starts); it waits for `/tmp/mmi-mirror-basevideo.ready`, written only after the sidecar has really
drawn a frame. If the sidecar keeps losing that file (3 drops within 60 s) the CarPlay map is switched off for
the rest of the connection. That gives a safe fall-back to the Audi map in every failure case.

**The 5-second hold.** `SteeringWheelInputModule` times the left roller; at 5 s it calls
`ScreenModule.cycleMapMode()`, which flips CarPlay / Audi map mode. It needs a connected CarPlay session and
nothing else (this car has no combi manager to report the VC tab, so it must not depend on navigation). The mode
resets to CarPlay when the phone disconnects.

## 🖥️ The sidecar needs the system's graphics environment

The sidecar is started by `carplay_monitor.sh`, so it inherits `dio_manager`'s bare environment. Qualcomm's
`egl14.so` reads `graphics.conf` from `$GRAPHICS_ROOT`; without it the EGL init falls into a default-driver path
that crashes (and a workaround for that crash only turned it into `eglCreateContext` -> `EGL_BAD_ALLOC`).
`maneuver_render` only works because the monitor sets `GRAPHICS_ROOT` for it. `start_vehicle.sh` therefore
exports `GRAPHICS_ROOT=/proc/boot/`, `QC_GFX_CONF_DIR`, `ADRENO`, `DISPLAY_CATALOG_PATH` and `IPL_CONFIG_DIR`, the
values the screen process and the HMI have on the unit. **If you see a black cluster with the sidecar crashing in
EGL, check the `GFX_ENV` line at the top of `/tmp/altscreen_mirror.log`.**

## ⏱️ Zoom

The wheel zoom is forwarded by `WheelZoomBridge` (Java) through `/tmp/mmi-mirror-wheel-zoom.events` to the hook,
which sends `changeMapZoomLevel` to the phone. The hook only sends a step while decoded frames are fresh, and
the iPhone only streams frames when the map changes, so on a parked, route-less map the library logs
`WHEEL_ZOOM_FRAME_STALL` and drops the steps. Lifting that gate (`scripts/patch_altscreen_zoom.sh`, off by
default) made no visible difference: the phone ignores zoom while idle. **Zoom works while navigating.**

## 🔍 Troubleshooting from the logs

Run `logging_MoreIncredibleBash` right after the problem (before a restart; `/tmp` is RAM).

| Symptom | Look at | Likely cause |
| --- | --- | --- |
| Cluster never shows the CarPlay map | `altscreen_mirror.log`: `DISPLAYABLE3_FIRST_PRESENT result=OK`? | sidecar failed to start (EGL / `GFX_ENV`) |
| CarPlay map vanishes after ~30 s, parked | `altscreen_hook.log`: `STREAM_111_PROCESSFRAMES_RETURN rc=-6722` | phone sent no frames (idle map), stream timed out; reconnect |
| Map toggle seems to do nothing | `carplay_java.log`: `Map mode cycled`, `desiredCtx changed` | sidecar not presenting (stays on Audi map) |
| Phone will not connect, `dio_manager` restarts every ~15 s | `sloginfo_mmx.txt`: many `EHCI - Error on Control Transfer` | USB host trouble; other cable / port, restart |
| Pill stays on the cluster | `carplay_java.log`: `ClusterLayers apply ... opacity=` | layer visibility, see [kdk-geometry](cluster/kdk-geometry.md) |

## 🩹 Binary patches in the SD-card package

The staging script applies two documented one-byte patches to the staged *copies* of the third-party
binaries; the repository keeps the originals. See [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).

| Patch | Default |
| --- | --- |
| Skip the embedded intro clip (`patch_altscreen_logo.sh`) | on; `ALT111_KEEP_LOGO=1` turns it off |
| Lift the zoom frame gate (`patch_altscreen_zoom.sh`) | off; `ALT111_PATCH_ZOOM=1` turns it on |
