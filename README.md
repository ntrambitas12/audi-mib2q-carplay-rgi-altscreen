# MHI2Q CarPlay cluster integration

**The full CarPlay map and turn-by-turn arrows on the Audi Virtual Cockpit** (MHI2Q infotainment).
This fork combines the route-guidance (RGI) cluster overlay with the **CarPlay AltScreen** map and
makes the two work together reliably. Plug in the iPhone and the CarPlay map appears on the cluster; the
turn arrows come up near every turn; **hold the left steering-wheel roller for 5 seconds** to switch
between the CarPlay map and the normal Audi map.

(Based on MHI2Q firmware, but may need rebuild for different versions.)

**Disclaimer:** Use at your own risk. These patches modify firmware binaries and system configurations on your infotainment unit. Always back up all original files before making any changes. The authors are not responsible for any damage, bricked devices, or warranty issues resulting from use of these patches.

## ⬇️ Download and install

**No programming needed - it is copying files to an SD card.**

1. Download the newest `MHI2Q-CarPlay-AltScreen-RGI_<version>.zip` from the
   **[Releases page](https://github.com/ntrambitas12/mib2q-carplay-rgi/releases/latest)**.
2. Unzip it and copy **everything inside** onto the top level of your **M.I.B. SD card**
   (a FAT32 card that already has **M.I.B. - More Incredible Bash** set up, a separate free tool from the
   Mibsolution project - search for it; `READ ME FIRST.txt` explains what you need). Say *yes* if asked
   to merge or replace.
3. In the car: **green menu -> M.I.B. -> Advanced Settings -> Run Custom Script** (older M.I.B.:
   *Run individual script*). Wait for `DONE (install). Reboot the HU to load.`
4. Restart the infotainment normally (ignition off, wait until the screen is dark, ignition on),
   plug in the iPhone - after 5-10 seconds the cluster shows the CarPlay map.

Everything is explained step by step in **`READ ME FIRST.txt`** inside the ZIP, including
**uninstall** and **how to save logs** (`EXTRAS/` folder). To undo it, copy `EXTRAS/UNINSTALL/mod`
over the card's `mod` folder and run the same menu entry again.

**Needs:** an Audi with **MHI2Q** infotainment and a **digital Virtual Cockpit**, an iPhone with wired
CarPlay, and an SD card with M.I.B. **Tested on one car:** MHI2Q US firmware AUG22 (P5087) - other
versions are untested. **Use at your own risk**; this is a fan project, not affiliated with Audi or Apple.

### Using it

| You do | What happens |
| --- | --- |
| Plug in the iPhone | The cluster starts on the normal Audi map, then switches to the CarPlay map after 5-10 s |
| Start navigation in Apple Maps / Google Maps | Turn arrows, lane guidance and distance appear near each turn, on the CarPlay map |
| **Hold the left roller 5 s** | Switches between the CarPlay map and the Audi map (arrows work on both). Hold again to switch back |
| **Click the left roller once** (while navigating) | Switches the route text line between the **next turn / street** and your **arrival time (ETA)**; returns to the street by itself after about 20 s |
| Unplug the phone | Back to the stock cluster; the next connection starts on the CarPlay map again |

### Known limits

- **Scroll-wheel zoom** of the CarPlay map only works while navigating. The iPhone ignores zoom commands
  on an idle (route-less) map; a patch that lifts the library's own gate in front of it made no
  difference on the test car ([details](docs/altscreen.md#-zoom)).
- A **parked iPhone with no route** may stop sending the cluster picture after about 30 s; the cluster
  then falls back to the Audi map until the phone is reconnected. Navigating or driving keeps it alive.
- The CarPlay map needs **5-10 s** to appear after the phone connects.
- Waze does not send turn guidance; Apple Maps and Google Maps do.
- Rare USB enumeration trouble (the phone will not connect) is a head-unit USB issue, not this patch:
  try another cable or port and restart the unit.

## 🖼️ Gallery

<p align="center">
  <img src="assets/gallery/maneuver_demo.gif" width="90%" /><br />
  <sub>Cluster maneuver renderer driven through a demo route</sub>
</p>

**Virtual Cockpit: route guidance from the maneuver renderer**

<p align="center">
  <img src="assets/gallery/vc_day_nav.jpeg" height="200" />
  <img src="assets/gallery/vc_night_nav.jpeg" height="200" />
</p>
<p align="center">
  <img src="assets/gallery/vc_full_map.jpeg" height="200" />
  <img src="assets/gallery/vc_lane_guidance.jpeg" height="200" />
</p>

**Audi front PDC no longer hides CarPlay** · **Cover art on the cluster**

<p align="center">
  <img src="assets/gallery/pdc_over_carplay.jpeg" width="45%" />
  <img src="assets/gallery/cover_art.jpeg" width="45%" />
</p>

**Head-up display**

<p align="center">
  <img src="assets/gallery/IMG_0623.jpeg" width="30%" />
  <img src="assets/gallery/IMG_6302.jpeg" width="30%" />
  <img src="assets/gallery/IMG_0599.jpeg" width="30%" />
</p>

## 📍 Contents

- [Download and install](#-download-and-install)
- [Gallery](#-gallery)
- [Features](#-features)
- [Repository layout](#-repository-layout)
- [Build](#-build)
- [Deployment](#-deployment)
- [Logging](#-logging)
- [Documentation](#-documentation)
- [Help wanted](#-help-wanted)
- [References](#-references)

## ✨ Features

There is nothing to switch on: plug in the iPhone and CarPlay starts as usual; the cluster
features below follow it automatically.

### CarPlay on the Virtual Cockpit

- **The full CarPlay map on the cluster (AltScreen).** As soon as the iPhone connects, the Virtual
  Cockpit shows the live CarPlay map next to the speedometer (it takes 5-10 s to appear; until then you
  keep the normal Audi map). [How it works](docs/altscreen.md).
- **Switch maps from the steering wheel.** **Hold the left roller for 5 seconds** to flip between the
  CarPlay map and the normal Audi map, and hold again to flip back. It works any time CarPlay is
  connected (navigation or not), the turn arrows work on both maps, and every new connection starts
  on the CarPlay map again.
- **Safe fall-back.** The CarPlay map is only shown once its video is really on screen. If it is not
  available, stops, or keeps failing, the cluster stays on (or returns to) the normal Audi map, so the
  car is never left with a blank cluster. A failing video process backs off and gives up instead of
  looping.
- **No intro clip.** The AltScreen runtime's startup logo no longer plays before the first map frame
  (documented one-byte patch applied to the packaged copy only, see
  [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)).

### Turn-by-turn navigation (RGI)

- **Turn arrows on the cluster.** During CarPlay navigation the Virtual Cockpit shows a 3D maneuver
  arrow (on the CarPlay map, or on the Audi map in Audi mode) that fills as the turn approaches and
  blinks just before it, with **lane arrows** under it. The cluster also shows distance to the turn,
  arrival time and remaining distance. Needs an app that
  sends CarPlay route guidance: Apple Maps and Google Maps do, AMap does with its CarPlay guidance
  setting on, Waze does not
  ([details](docs/rgd/rgd-activation.md#-which-navigation-apps-send-route-guidance)).
- **Route text in the Virtual Cockpit.** A text line names the exit sign or the next road (the current
  road when there is nothing else); long names scroll ([details](docs/rgd/vc-route-text.md)).
  **Click the left roller once** to switch it between the **next turn / street** and your **arrival time
  (ETA) and time left**; it returns to the street by itself after 20 s.
- **Head-up display.** The same maneuver icons, lane arrows and distance appear on the HUD.
- **Scroll-wheel zoom.** Turning the left roller zooms the CarPlay map while you navigate (the iPhone
  ignores zoom on an idle map), and the stock map as always in Audi mode.

### And more

- **Cover art on the cluster.** The now-playing album art shows on the cluster media screen.
- **Parking popups no longer hide CarPlay.** When the Audi front PDC / parking view pops up beside it,
  CarPlay stays on screen instead of being replaced ([details](docs/hmi/pdc-small-stage.md)).
- **MMI touchpad -> DPAD bridging** so finger drags navigate CarPlay menus.

### Easy to install and to remove

- **One ZIP, no tools.** Download it from the [Releases page](https://github.com/ntrambitas12/mib2q-carplay-rgi/releases/latest),
  copy it onto the M.I.B. SD card and run one menu entry. Plain-English `READ ME FIRST.txt` included.
- **Uninstall and log collection built in.** The `EXTRAS/` folder swaps in an uninstaller (restores the
  two settings files the installer backed up) or a log collector that saves everything needed for a bug
  report to the card.
- **Safe installer.** It checks the whole release is on the card before writing anything, copies files
  atomically, keeps a stock backup of each setting it edits and never stops processes or reboots.

## 🗂️ Repository layout

| Path | Purpose |
| --- | --- |
| `hook/` | Shipping native `libcarplay_hook.so` source |
| `java_patch/` | The only supported Java patch source |
| `java_resources/` | Resources packed into the jar (VC glyph-width / Unicode table `vc-text.bin`) |
| `maneuver_render/` | GLES maneuver overlay renderer (C, plus the C++11 `scene/` engine) |
| `common/` | Shared renderer code: QNX Screen surface, GL program-binary cache, log timestamps |
| `deploy/smartphone_integrator/` | Runtime scripts and child-process configuration for the HU |
| `deploy/altscreen/` | CarPlay AltScreen runtime (third-party binaries by yuedizhibo / Lanye-z) and its launch / supervisor scripts |
| `release/`, `licenses/` | Text of the end-user ZIP (`READ ME FIRST`, release notes) and the third-party licence texts |
| `install_MoreIncredibleBash/`, `uninstall_MoreIncredibleBash/`, `logging_MoreIncredibleBash/` | M.I.B. custom scripts that install / remove a staged release / collect logs |
| `scripts/` | Docker build entry points (Java / hook / renderer) and host test runners |
| `tests/` | Host tests (C, Java, Python) for the hook, Java bridge and renderer |
| `toolchain/qnx65-abi/` | QNX Screen ABI headers used only for cross-compilation |
| `docs/` | Markdown knowledge base (also opens in Obsidian) - validated RE + implementation notes (open [`docs/INDEX.md`](docs/INDEX.md)) |
| `assets/` | Screenshots and visual reference material |
| `build/` | Canonical deployable artifacts |

Raw unit logs and generated class trees are intentionally kept outside Git.

## 🔧 Build

Native code needs the QNX 6.5 ARMv7 cross-toolchain image from
[luka-dev/qnx65-armv7-toolchain](https://github.com/luka-dev/qnx65-armv7-toolchain). Build it once:

```sh
git clone https://github.com/luka-dev/qnx65-armv7-toolchain
cd qnx65-armv7-toolchain
./host-scripts/qnx-run.sh build        # qnx65-armv7-toolchain:latest (GCC 8.5)
```

Then run from this repository's root:

```sh
./scripts/build_java.sh        # → build/carplay_hook.jar
./scripts/build_hook.sh        # → build/libcarplay_hook.so
./scripts/build_renderers.sh   # → build/maneuver_render
```

All three build in Docker - no host toolchain required. The Java patch compiles in a pinned
`eclipse-temurin:8` container (against the stock jar + OSGi libs under `../../Tools/jxe2jar`; the
scripts expect the author's `out/MU1316-final.jar`, so if your own stock jar is named or located
differently, adjust the path in `scripts/build_java.sh` and the test scripts); the two
native builds use the `qnx65-armv7-toolchain` image and synthesize their import stubs, so the resulting
ELF binds the unit's real Screen/EGL/GLES libraries at runtime. The renderer's C++ scene engine is
built with that image's `g++` and must not pull in the C++ runtime; the hook build rejects any dynamic
export beyond its five interposers. There are no Java variants.

There is one hook image: logging is always compiled in, WARN/ERROR by default, INFO with the
`carplay_verbose` marker (see [Logging](#-logging)). The only build-time switch is for debugging:

```sh
./scripts/build_hook.sh                        # production image
LOG_RGD_PACKET_RAW=1 ./scripts/build_hook.sh   # + raw RGD packet hex dumps
```

### Tests

Host-only, no unit needed:

```sh
./scripts/run_tests.sh            # C + shell: RGD parser, bus, cover art, shader cache, installer, supervisor
./scripts/test_route_info.sh      # Java route-guidance / BAP bridge against the stock interfaces
./scripts/test_java_transports.sh # Java bus + renderer sockets, touchpad
./scripts/test_maneuver_native.sh # renderer engine + lanes (macOS, ASan/UBSan)
```

### Making the release ZIP

```sh
./scripts/make_release.sh v1.0.0 --build            # compile, stage, patch, zip -> dist/release/
./scripts/make_release.sh v1.0.0 --payload DIR      # reuse the 3 compiled files from DIR (e.g. a tested card's mod/carplay)
./scripts/make_release.sh v1.0.0 --publish          # ...and create the GitHub release with the ZIP attached
```

`make_release.sh` stages the SD-card payload, applies the documented AltScreen byte patches to the
staged copies only ([`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)), refuses a stub jar or CRLF
scripts, and zips the result with the licences and the plain-English guide.

The Java suites need the stock MU1316 jar and JDK under `../../Tools/jxe2jar`. Full toolchain,
threading, boot and the complete test list live in the knowledge base - see
[`docs/architecture.md`](docs/architecture.md).

## 🚀 Deployment

**Compatibility.** The patch is not limited to US, EU or CN units, nor to one MU train: it is
meant for any MHI2Q MU firmware (developed on MU1316). What matters is:

- a fully digital instrument cluster (Audi virtual cockpit); cars with an analog cluster are not
  supported;
- preferably, the latest firmware available for the unit, flashed before installing the patch.

With both in place it should almost certainly work, as long as nothing went wrong during the
install itself.

A release is eight RGI files, five AltScreen files and two config edits; nothing stock is replaced and no
firewall profile is touched:

| On-unit path | Files |
| --- | --- |
| `/mnt/app/root/hooks/` | `libcarplay_hook.so`, `maneuver_render` (from `build/`), `flag_atlas.rgba` (from `maneuver_render/resources/`), `carplay_startup.sh`, `carplay_monitor.sh`, `carplay_processes.sh`, `carplay_cleanup.sh` (from `deploy/smartphone_integrator/`) |
| `/mnt/app/eso/hmi/lsd/jars/` | `carplay_hook.jar` (from `build/`) |
| `/mnt/app/root/carplay-altscreen/` | AltScreen: `lib/libcarplay_altscreen.so` (preloaded into `dio_manager`), `bin/mirror/carplay-alt111-mirror-display`, `start_vehicle.sh`, `stop_vehicle.sh`, `stream_supervisor.sh` (from `deploy/altscreen/`; all-or-nothing, optional) |
| `/mnt/system/etc/eso/production/smartphone_integrator.json` | `children.carplay` replaced by [`carplay_child.json`](deploy/smartphone_integrator/carplay_child.json) |
| `/mnt/system/etc/eso/production/dio_manager.json` | `MessagesSentByAccessory` += `0x5200`, `0x5203`; `MessagesReceivedFromDevice` += `0x5201`, `0x5202`, `0x5204` |

Both the `dio_manager.json` IDs and the hook's runtime Identify patch are required: without the IDs
iOS sends route guidance and the SDK silently drops it.

**With M.I.B. (recommended).** Copy `install_MoreIncredibleBash/` to the M.I.B. SD card and drop
**all assets of a release** straight into `mod/carplay/` (the eight files above plus
`carplay_child.json`; no folders needed), then run **GEM -> M.I.B. -> Advanced Settings -> Run Custom Script** (**Run individual script** on
M.I.B. release zips up to V3.7.1) with CarPlay disconnected. `custom.sh` checks that the whole
release is on the card (a partial copy stops before anything is written), copies it with atomic
renames, patches both configs in place and keeps a `.carplay-stock` backup of each; it never stops
processes or reboots. It also deletes M.I.B.'s NavActiveIgnore jar, which breaks CarPlay's app
state. To remove everything, run `uninstall_MoreIncredibleBash/` the same way.

**Manually** (no M.I.B.; needs a root shell on the unit over SSH or Telnet). `mount -uw /mnt/app` and `/mnt/system`, copy the files, back up and
edit the two configs as text (`dio_manager.json` has `##` comment lines - no JSON tools).

The step-by-step guide for both - the SD layout, installer output and warnings, the exact SI child and
`dio_manager.json` lines, verification greps, uninstall and the SSH traps - is
[`docs/deploy/install.md`](docs/deploy/install.md).

**Reboot.** Disconnect CarPlay, run `sync` and wait a few seconds, then reboot normally: a forced
reboot (the MMI button combo) right after copying can leave the files truncated or missing. The jar is
on j9's boot classpath, so it only loads after a full restart. On boot `smartphone_integrator` launches
everything; check `/tmp/carplay_hook.log` and `/tmp/carplay_java.log` (see [Logging](#-logging)).

Exact ownership rules, the `LD_PRELOAD`/env constraints and the MU1316 QNX-compat audit are in
[`deploy/smartphone_integrator/README.md`](deploy/smartphone_integrator/README.md).

## 📝 Logging

Everything logs to `/tmp` on the unit:

| File | Source |
| --- | --- |
| `/tmp/carplay_hook.log` | native hook (inside `dio_manager`) |
| `/tmp/carplay_java.log` | Java patch (bounded + rotated, `.1` = previous) |
| `/tmp/maneuver_render.log` | cluster maneuver renderer |
| `/tmp/carplay_wrapper.log` | startup wrapper and renderer monitor |

By default only warnings and errors are recorded. To capture **everything** (lift hook and Java to
`INFO`), drop a marker file on the unit - no rebuild needed:

```sh
touch /mnt/app/carplay_verbose        # survives reboot; /tmp/carplay_verbose does not
```

Hook and Java read the marker at every CarPlay session start, so it takes effect on the next phone
connect - no reboot. Remove the marker to return to the quiet default. Logs reset on reboot, so pull
them before restarting.

For raw route-guidance packet dumps, rebuild the hook with `LOG_RGD_PACKET_RAW=1` (see [Build](#-build)).

**No shell? Use M.I.B.** Copy `logging_MoreIncredibleBash/` to the card and run it like the installer.
Each run saves everything to `<card>/carplay_logs/NNN/` and then creates `/tmp/carplay_verbose`: run it
once, reconnect the phone and drive with CarPlay, run it again - the second folder holds the verbose
session. Attach that folder to a bug report.

## 📚 Documentation

`docs/` is a Markdown knowledge base (also opens in Obsidian) - one note per topic, each fact validated against code /
firmware / iOS binary. Start at [`docs/INDEX.md`](docs/INDEX.md): architecture & threading, the hook
and bus, route guidance (TLV → BAP → cluster, lanes, route text), cluster compositing and the maneuver
renderer, input, deploy/connect, build & host tests, the reverse-engineering references, and a
per-note verification status.

## 🤝 Help wanted

PRs are welcome - bug fixes, new maneuver cases, docs, on-car test reports.

**Reporting a bad maneuver icon.** The iAP2→BAP mapping covers all 54 CarPlay maneuver types but has
only been exercised on a limited set of real routes. A snippet of `/tmp/carplay_hook.log` from the
moment plus a note on what was expected helps a lot. The hook logs unrecognised route-guidance messages
as `[HOOK] Unknown 0x52xx msgid=0xNNNN dir=IN len=N` followed by a hex dump - that line is the best
starting point when iOS sends a maneuver type we don't handle yet.

## 🔗 References

**Authors and credits** (full notices and licences in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)):

- Route guidance and cluster overlay: **LuKa** ([luka-dev/mib2q-carplay-rgi](https://github.com/luka-dev/mib2q-carplay-rgi))
- CarPlay AltScreen map: **yuedizhibo** and **Lanye-z** (non-commercial licence; resale prohibited)
- AltScreen integration and stabilization, packaging: **Nicholas Trambitas** ([@ntrambitas12](https://github.com/ntrambitas12))

Thanks for the prior work and knowledge that helped figure this out.

- https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering
- https://github.com/EthanArbuckle/iPhone18-3_26.1_23B85_Restore
- https://github.com/adi961/mib2-android-auto-vc
- [@fifthBro](https://t.me/fifthBro)
