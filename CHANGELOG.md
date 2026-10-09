# Changelog

## v1.0.0 - CarPlay map on the Virtual Cockpit

First release of this fork: the **CarPlay AltScreen map** and the **route-guidance (RGI) overlay**
working together on a real car (MHI2Q US firmware AUG22, P5087).

### Added
- CarPlay map on the cluster as soon as the iPhone connects (AltScreen by yuedizhibo and Lanye-z).
- **Hold the left steering-wheel roller for 5 s** to switch between the CarPlay map and the Audi map;
  works whenever CarPlay is connected, resets to the CarPlay map on every new connection.
- Documented: a single click of the left roller toggles the cluster route text between the next turn and the ETA.
- Two fixed cluster contexts: 80 (CarPlay map + arrows) and 81 (Audi map + arrows); no runtime edits
  of the display table, no visible flicker when switching.
- Safe fall-back to the Audi map when the CarPlay picture is not available: Java shows the CarPlay map
  only once the sidecar has really drawn a frame, and gives up after repeated drops.
- Stream supervisor with back-off (5 starts, then stop) and bounded logs, so a failing sidecar can no
  longer loop forever or fill the unit's RAM.
- One-ZIP SD-card release (`scripts/make_release.sh`) with a plain-English `READ ME FIRST.txt`,
  uninstall and log-collection folders, and the third-party licence notices.
- Log collector now also saves the AltScreen logs, state files and graphics-stack information.
- Docs: [`docs/altscreen.md`](docs/altscreen.md), updated README, install guide and steering-wheel notes.

### Fixed
- **Black CarPlay map:** the sidecar was started without the graphics environment (`GRAPHICS_ROOT` and
  friends) and crashed in EGL; it now gets the same environment as the system's own GL processes.
- Map choice no longer snaps back after a few seconds (module restarts used to reset it).
- 5-second hold works without navigation running.
- Intro clip of the AltScreen sidecar no longer plays before the first map frame (documented 1-byte
  patch applied to the staged copy only; `ALT111_KEEP_LOGO=1` keeps it).

### Known limits
- Scroll-wheel zoom of the CarPlay map only works while navigating (the iPhone ignores it on an idle map).
- A parked iPhone with no route can stop the cluster picture after ~30 s; the cluster falls back to the
  Audi map until the phone is reconnected.
- Tested on one car and firmware only.

### Credits
LuKa (RGI), yuedizhibo and Lanye-z (AltScreen), Nicholas Trambitas (integration, stabilization, packaging).
