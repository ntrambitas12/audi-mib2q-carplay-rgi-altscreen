---
title: Steering-wheel roller - zoom & route-info toggle
tags: [input, steering-wheel, verified]
status: verified-source
sources:
  - code: java_patch/com/luka/carplay/core/SteeringWheelInputModule.java
  - code: java_patch/de/audi/tghu/navi/app/cluster/ScreenCombiBAPListener.java
  - code: java_patch/de/audi/app/terminalmode/dsi/carplay/CarplayDSILifecycleController.java
  - code: java_patch/com/luka/carplay/core/ScreenModule.java
---

# Steering-wheel roller - zoom & route-info toggle

The left MFW roller has two distinct axes: **rotation** (scroll) and **press** (click).

## 📋 Context

> MFW roller -> **rotation** = CarPlay AltScreen map zoom (via `WheelZoomBridge`) - **press** = cluster route-info toggle (Next Street ↔ ETA / trip summary) ->
> [bap-fctids](../rgd/bap-fctids.md) FctID 19 -> [rgd-activation](../rgd/rgd-activation.md).

## 🔄 Rotation (scroll) -> CarPlay AltScreen Zoom

The roller rotation is delivered to `ClusterService.onMagnificationChanged(int i)`.
On the `altScreen` branch:
1. `WheelZoomBridge.onMagnificationChanged(i)` computes the delta steps and direction (`0` = Zoom In, `1` = Zoom Out).
2. When CarPlay owns the cluster (Context 80), it appends formatted events to `/tmp/mmi-mirror-wheel-zoom.events`.
3. `libcarplay_altscreen.so` polls this event queue and dispatches native `changeMapZoomLevel` commands to Apple CarPlay over the secondary screen control plane.
4. When cruising or outside Context 80, the zoom steps fall through to stock.

## ⚙️ Press (Short Click vs Press-and-Hold)

The raw MFW roller press (DSI key 40, `KEY_MFW_ROLLER_LEFT`) supports two distinct actions:

### 1. ⏱️ Short Click (< 5s): Route-Info Toggle (Next Street ↔ ETA)
A quick click toggles the cluster route-info text line between the **next turn-to street** (phase 0) and the **trip summary** (ETA / arrival clock + remaining, phase 1). Phase 1 falls back to phase 0 by itself after 20 s.
`SteeringWheelInputModule` suppresses the collapsed `DDS_SELECT` copy to prevent accidental selection on the CarPlay center screen.

### 2. 🔄 Press-and-Hold (>= 5 s, `LONG_PRESS_DURATION_MS`): CarPlay map <-> Audi map
Holding the left roller for 5 seconds flips `ScreenModule`'s map mode:
- **CarPlay map mode (default after every connection):** cluster context 80 (`{98, 101, 102, 3}`) once the
  AltScreen sidecar is presenting; the turn arrows ride on top of it.
- **Audi map mode:** stock cluster 74 while cruising, context 81 (`{98, 101, 102, 33}`: the same arrows over
  the Audi map) during a turn approach.

The hold only needs a **connected CarPlay session**. It does not need route guidance or a VC map tab (this
car has no combi manager to report the tab), so it works with no navigation running, and does nothing when no
phone is connected. The mode resets to CarPlay on disconnect. Details: [altscreen](../altscreen.md).
Rotation (scroll) zooms the CarPlay map only while it is on screen (context 80) and while navigating, otherwise
the stock map as without CarPlay.

```mermaid
flowchart LR
    accTitle: Steering-wheel OK press routing
    accDescr: Raw key 40 is marked collapsed-select, its DDS_SELECT copy is suppressed and on the map tab it toggles the route-info phase. Centre DDS key 16 passes through as a CarPlay select.
    k40["raw key 40 press"] --> mark["mark collapsed-select<br/>(SteeringWheelInputModule)"]
    mark --> sup["updateKey: suppress the<br/>MFW DDS_SELECT copy"]
    mark --> tog["on map tab -><br/>ScreenModule.onSteeringWheelOkPressed"]
    tog --> rg["RouteGuidance: desiredInfoPhase ^= 1"]
    k16["centre DDS key 16"] --> sel["DDS_SELECT -> CarPlay select<br/>(never marked, passes through)"]
```
