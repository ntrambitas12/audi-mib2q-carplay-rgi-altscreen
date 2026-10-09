# Third-party notices and credits

This project builds on the work of several people. Please keep these credits with any copy you share.

## This fork: AltScreen integration and stabilization

**Nicholas Trambitas ([@ntrambitas12](https://github.com/ntrambitas12))** brought the CarPlay
AltScreen map and the route-guidance (RGI) cluster overlay together in one package and made it stable
on a real car: the context-switching between the CarPlay map and the Audi map (including the
5-second steering-wheel hold), the sidecar launch fix that finally put video on the cluster, the
stream supervisor and fall-back behaviour, the logging and diagnostics tooling, and the one-ZIP
SD-card packaging and documentation.

## CarPlay route guidance and cluster integration (RGI)

Original project: [luka-dev/mib2q-carplay-rgi](https://github.com/luka-dev/mib2q-carplay-rgi) by
LuKa (@LuKa_dev) - the iAP2 route-guidance decoding, the maneuver renderer, the Virtual Cockpit
integration, the HUD output and the install tooling. This repository is a fork of that project.
It carries no top-level licence file of its own; the original author's copyright headers in the
source files apply.

## CarPlay AltScreen (the CarPlay map on the Virtual Cockpit)

The **CarPlay map on the cluster** comes from the AltScreen runtime by
**[yuedizhibo](https://github.com/yuedizhibo)** and **[Lanye-z](https://github.com/Lanye-z)**
("MHI2Q-CarPlay-AltScreen", V3.5Fix2) and the sister project
[MHI2Q-CarPlay-MMI-Mirror](https://github.com/Lanye-z/MHI2Q-CarPlay-MMI-Mirror).
Their research and protocol work made this possible; this project only integrates it. They credit
[LIVI](https://github.com/f-io/LIVI) as a research reference for the CarPlay cluster protocol.

| Files in this repository | Origin | Licence |
|---|---|---|
| `deploy/altscreen/libcarplay_altscreen.so` | AltScreen V3.5Fix2 | [PolyForm Noncommercial 1.0.0](licenses/ALTSCREEN-PolyForm-Noncommercial-1.0.0.txt) |
| `deploy/altscreen/carplay-alt111-mirror-display`, `start_vehicle.sh`, `stop_vehicle.sh`, `stream_supervisor.sh`, `BUILD_INFO.txt` | AltScreen V3.5Fix2 | PolyForm Noncommercial 1.0.0 for the scripts; the sidecar runtime has its own [Unlicense / public-domain notice](licenses/MMI-MIRROR-runtime-Unlicense.txt) |

**What this means for you**

- **Non-commercial use only.** Use it, change it and share it freely, but you may not sell it or use
  it commercially. The authors say so plainly: shared free of charge, **reselling is prohibited**.
- The licence text and the notice below must stay with every copy of those files:

  `Required Notice: Copyright 2026 yuedizhibo`

- Some binaries are shipped **modified**, by exact byte patches that are documented and reproducible.
  The originals are in this repository unchanged; the patches are applied only when the SD-card
  package is staged:

  | Patch | What it changes | Script | Default |
  |---|---|---|---|
  | Skip the startup logo | 1 byte in `carplay-alt111-mirror-display` (the embedded intro clip's header) so the intro does not play before the first map frame | [`scripts/patch_altscreen_logo.sh`](scripts/patch_altscreen_logo.sh) | on (`ALT111_KEEP_LOGO=1` turns it off) |
  | Lift the zoom frame gate | 1 byte in `libcarplay_altscreen.so` | [`scripts/patch_altscreen_zoom.sh`](scripts/patch_altscreen_zoom.sh) | off (`ALT111_PATCH_ZOOM=1` turns it on; had no visible effect on the test car) |

- The AltScreen runtime may draw a small drifting "free and open source, resale prohibited"
  watermark over the map. That is the authors' own anti-resale mark and is **not** removed.

## Other

- The QNX 6.5 cross-toolchain used to build is [luka-dev/qnx65-armv7-toolchain](https://github.com/luka-dev/qnx65-armv7-toolchain).
- **M.I.B. - More Incredible Bash** (the SD-card tool this package installs through) is a separate
  project by its own authors; it is not included here.
- `toolchain/qnx65-abi/` contains QNX Screen ABI headers used only for cross-compilation.
- "Audi", "CarPlay", "Apple", "iPhone", "MMI" and "Virtual Cockpit" are trademarks of their
  respective owners. This project is independent and not affiliated with or endorsed by them.
