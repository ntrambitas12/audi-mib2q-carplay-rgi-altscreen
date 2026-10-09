=====================================================================
 CarPlay map + turn arrows on the Audi Virtual Cockpit      (MHI2Q)
 Version @VERSION@        packaged @DATE@
=====================================================================

WHAT YOU GET
------------
 * The full CarPlay map on your Virtual Cockpit (instrument cluster),
   next to the speedometer, as soon as your iPhone connects.
 * Turn-by-turn arrows, lane guidance and distance from CarPlay
   navigation, drawn right in the cluster (plus on the head-up display
   if your car has one).
 * Hold the LEFT steering-wheel roller for 5 seconds to switch between
   the CarPlay map and the normal Audi map. Hold again to switch back.
   The turn arrows work on both maps.
 * Click the left roller ONCE while navigating to see your arrival time
   (ETA) instead of the next turn; click again to go back.

This is a fan project. It is NOT made by Audi or Apple.
USE AT YOUR OWN RISK. It changes software on your car's infotainment
unit. Read "IF SOMETHING GOES WRONG" at the bottom BEFORE you start.


WHAT YOU NEED
-------------
 1. An Audi with the MHI2Q infotainment (MMI "MIB2 High") and a fully
    DIGITAL Virtual Cockpit. Analog instrument clusters do NOT work.
 2. An iPhone using wired CarPlay (USB cable).
 3. An SD card (FAT32, 32 GB or smaller works best) that ALREADY has
    "M.I.B. - More Incredible Bash" set up on it. M.I.B. is a separate
    free tool; search for "M.I.B. More Incredible Bash Mibsolution"
    and follow its instructions first. You know it is ready when the
    green engineering menu in your car shows an "M.I.B." entry.
 4. About 15 minutes, and the car parked with the ignition on
    (engine running is best so the battery does not drain).

Tested so far on ONE car: MHI2Q US firmware "AUG22" (P5087). Other
firmware versions should work but are not tested yet.


INSTALL
-------
 STEP 1  On your computer, UNZIP this download. You get a folder with
         a "mod" folder, an "EXTRAS" folder and some text files.

 STEP 2  Open the SD card on your computer. Copy EVERYTHING from the
         unzipped folder onto the SD card, at the TOP level of the
         card - not inside another folder.
         If your computer asks about merging folders or replacing files,
         answer YES / Replace.

         When you are done, the card should look like this:

             (SD card)
              |-- mod
              |    |-- custom.sh
              |    |-- command.sh
              |    `-- carplay
              |         `-- (about 15 files - do not touch them)
              |-- EXTRAS
              |-- READ ME FIRST.txt
              `-- (the M.I.B. files that were already there)

 STEP 3  Eject the card safely and put it in the car's SD slot
         (the same slot you used for M.I.B.).

 STEP 4  In the car: unplug your iPhone from the USB port.

 STEP 5  Open the green engineering menu the same way you did when you
         set up M.I.B., then go to:

             M.I.B.  ->  Advanced Settings  ->  Run Custom Script

         (On older M.I.B. versions this entry may be called
          "Run individual script".)

 STEP 6  Wait. The screen prints lines of text for about a minute and
         ends with:

             DONE (install). Reboot the HU to load.

         If you see "FAILED", or the text stops early, go to "IF
         SOMETHING GOES WRONG" below. Do NOT reboot yet.

 STEP 7  Restart the infotainment the NORMAL way: switch the ignition
         off, wait until the MMI screen has gone completely dark (about
         a minute), then switch it on again.
         Do NOT use the forced-reset button combination right after
         installing - it can corrupt the files that were just copied.

 STEP 8  Plug in your iPhone. CarPlay starts as usual. After about 5 to
         10 seconds the cluster switches to the CarPlay map.
         Start navigation in Apple Maps (or Google Maps) and the turn
         arrows appear near each turn.

 Done. You can leave the SD card in the car or take it out.


USING IT
--------
 * Switch maps: hold the left steering-wheel roller (the one you press
   to confirm) for 5 seconds while CarPlay is connected. The
   instrument cluster changes between the CarPlay map and the Audi map.
   It always starts on the CarPlay map after you plug the phone in.
 * Press the same roller ONCE (a quick click) while navigating: the
   route text line on the cluster switches between the NEXT TURN /
   street name and your ARRIVAL TIME (ETA). It goes back to the street
   name by itself after about 20 seconds. Click again to switch
   manually.
 * Zooming the map with the roller only works while you are navigating
   (the iPhone ignores zoom on an idle map).


GOOD TO KNOW
------------
 * The CarPlay map takes 5 to 10 seconds to appear after the phone
   connects. Until then you see the normal Audi map.
 * If you leave the iPhone parked with no route for about half a minute,
   the iPhone may stop sending the cluster picture. The cluster then
   falls back to the Audi map until you reconnect the phone. While
   driving or navigating this does not happen.
 * Apple Maps and Google Maps send turn guidance. Waze does not.
 * A small "free and open source, resale prohibited" mark may drift
   across the CarPlay map. It belongs to the AltScreen authors and is
   there on purpose.


IF SOMETHING GOES WRONG
-----------------------
 UNINSTALL (puts everything back to how it was):
   1. On your computer open  EXTRAS\UNINSTALL\mod  and copy the two
      files in it (custom.sh and command.sh) into the "mod" folder on
      the SD card. Replace the files that are there.
   2. In the car run the same menu entry again:
         M.I.B. -> Advanced Settings -> Run Custom Script
   3. Restart the infotainment (STEP 7).
   The install keeps a backup of the two settings files it edits; the
   uninstall puts them back.

 INSTALL AGAIN or UPDATE:
   copy  EXTRAS\INSTALL\mod  over the card's "mod" folder (replace),
   copy the new "mod\carplay" files, and run the menu entry again.

 SAVE LOGS to report a problem:
   1. Copy  EXTRAS\COLLECT_LOGS\mod  over the card's "mod" folder.
   2. Use CarPlay for a few minutes, reproduce the problem.
   3. BEFORE restarting the car, run the menu entry again.
   4. The card now has a folder  carplay_logs\001  (002, 003 ...).
      Put it in a ZIP and attach it to a bug report on GitHub.
   Logs are kept in memory and vanish at every restart, so save them
   first.

 USB or phone will not connect: try another cable or USB port, restart
 the phone, then restart the infotainment (STEP 7).

 Still stuck: open an issue on GitHub with your car, firmware version
 and the logs from above.


CREDITS AND LICENSES
--------------------
 See the LICENSES folder. In short:
  * CarPlay route guidance and cluster overlay: LuKa (@LuKa_dev)
  * CarPlay AltScreen map: yuedizhibo and Lanye-z
  * Integration and stabilization: Nicholas Trambitas (@ntrambitas12)
 Free for personal, NON-COMMERCIAL use. Do not sell it or charge for
 installing it.
