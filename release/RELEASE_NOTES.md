## CarPlay map + turn arrows on the Audi Virtual Cockpit - @VERSION@

**Download `MHI2Q-CarPlay-AltScreen-RGI_@VERSION@.zip` below, unzip it, copy everything inside onto
your M.I.B. SD card, and follow `READ ME FIRST.txt`.** No computer skills needed beyond copying files.

### What you get
- The **full CarPlay map on the Virtual Cockpit**, next to the speedometer, as soon as the iPhone connects.
- **Turn-by-turn arrows, lane guidance and distance** from CarPlay navigation, drawn on the cluster
  and on the head-up display.
- **Hold the left steering-wheel roller for 5 seconds** to switch between the CarPlay map and the Audi
  map (the turn arrows work on both).
- **Click the left roller once** while navigating to switch the route text between the **next turn / street**
  and your **arrival time (ETA)**; it goes back by itself after about 20 seconds.
- Safe fall-back: if the CarPlay picture is not available you simply keep the normal Audi map.
- Uninstall and log-collection tools in `EXTRAS/` (no computer needed beyond copying one folder).

### Needs
An Audi with **MHI2Q** infotainment and a **digital Virtual Cockpit**, an iPhone with wired CarPlay, and
an SD card with **M.I.B. (More Incredible Bash)** already set up. Tested on one car: MHI2Q US firmware
AUG22 (P5087); other versions are untested.

### Known limits
- Scroll-wheel zoom of the CarPlay map only works while navigating (the iPhone ignores it on an idle map).
- A parked iPhone with no route can stop sending the cluster picture after about 30 s; the cluster then
  falls back to the Audi map until the phone is reconnected.
- Fan project, not affiliated with Audi or Apple. **Use at your own risk.** Non-commercial use only.

### Credits
Route guidance and cluster overlay: **LuKa** ([luka-dev/mib2q-carplay-rgi](https://github.com/luka-dev/mib2q-carplay-rgi)).
CarPlay AltScreen map: **yuedizhibo** and **Lanye-z**. Integration and stabilization: **Nicholas Trambitas**
([@ntrambitas12](https://github.com/ntrambitas12)). Full notices and licences are in the `LICENSES` folder.

`SHA256` of the ZIP is attached for verification.
