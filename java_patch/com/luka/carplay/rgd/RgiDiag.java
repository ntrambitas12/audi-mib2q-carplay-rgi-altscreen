/*
 * RGI-DIAG: debug-only diagnostics for the route-guidance renderer/context lifecycle.
 *
 * Everything that is only a diagnostic lives here - the transition-edge state AND the
 * log text.  BAPBridge reaches it exclusively through
 *
 *     if (BuildFlags.DIAG) { RgiDiag.xxx(...); }
 *
 * BuildFlags is generated at build time (scripts/build_java.sh).  With
 * CARPLAY_RELEASE=1 it is `DIAG = false`, javac folds every call site away, and the
 * build removes this class from the jar and fails if it (or the "RGI-DIAG" text)
 * survives anywhere.  See docs/deploy/install.md, "Debug vs release builds".
 *
 * The log lines are byte-identical to the ones BAPBridge used to write itself (same
 * "BAPBridge" tag).  Callers pass in the BAPBridge state a line needs, so nothing here
 * reaches into BAPBridge.  State is touched only from BAPBridge.update() and the
 * renderer send path, which are serialized by the callers exactly as before.
 *
 * Copyright (c) 2026 LuKa (@LuKa_dev)
 */
package com.luka.carplay.rgd;

import com.luka.carplay.framework.Log;

public final class RgiDiag {

    private static final String TAG = "BAPBridge";

    /* Transition-edge state for the RGI-DIAG logs (formerly BAPBridge fields). */
    private static boolean diagRerouting;
    private static boolean diagClearedAwaitingManeuver;
    private static boolean diagBlankWarned;

    private RgiDiag() { }

    /** Logs once per reroute ENTER/EXIT edge. */
    public static void rerouteEdge(boolean isRerouting, long gen, int routeState,
                                   long stateGen, boolean inApproachZone,
                                   boolean presentationActive) {
        if (isRerouting != diagRerouting) {
            diagRerouting = isRerouting;
            Log.i(TAG, "RGI-DIAG reroute " + (isRerouting ? "ENTER" : "EXIT")
                + " gen=" + gen + " routeState=" + routeState
                + " stateGen=" + stateGen
                + " inZone=" + inApproachZone
                + " presentation=" + presentationActive);
        }
    }

    /** Renderer CLEAR sent after a successful approach-zone exit close. */
    public static void clearAfterExitClose(long gen, boolean isRerouting,
                                           boolean explicitClear, boolean shouldClearManeuver) {
        diagClearedAwaitingManeuver = true;
        Log.i(TAG, "RGI-DIAG renderer CLEAR site=exit-close-ok gen=" + gen
            + " rerouting=" + isRerouting + " explicit=" + explicitClear
            + " shouldClear=" + shouldClearManeuver);
    }

    /** Renderer CLEAR sent from the tail of update(). */
    public static void clearAtTail(long gen, boolean nowApproach, boolean isRerouting,
                                   boolean explicitClear, boolean shouldClearManeuver,
                                   boolean presentationActive, boolean bapPresentationActive) {
        diagClearedAwaitingManeuver = true;
        Log.i(TAG, "RGI-DIAG renderer CLEAR site=tail gen=" + gen
            + " approach=" + nowApproach + " rerouting=" + isRerouting
            + " explicit=" + explicitClear + " shouldClear=" + shouldClearManeuver
            + " presentation=" + presentationActive
            + " bapPresentation=" + bapPresentationActive);
    }

    /* Presentation open + valid maneuver but the renderer is still blank after a CLEAR.
     * Logged once per clear (black-pill signature). */
    public static void blankAfterClearCheck(long gen, int dirty, boolean approachChanged,
                                            boolean inApproachZone, int lastCrIdx,
                                            boolean presentationActive) {
        if (diagClearedAwaitingManeuver && !diagBlankWarned && presentationActive) {
            diagBlankWarned = true;
            Log.w(TAG, "RGI-DIAG pill open but renderer BLANK after CLEAR: gen=" + gen
                + " dirty=0x" + Integer.toHexString(dirty) + " approachChanged=" + approachChanged
                + " inZone=" + inApproachZone + " lastCrIdx=" + lastCrIdx);
        }
    }

    /** A maneuver reached the renderer; ends the "awaiting maneuver" interval. */
    public static void maneuverSent(long gen, int firstIdx, int ver, int icon,
                                    boolean presentationActive) {
        if (diagClearedAwaitingManeuver) {
            diagClearedAwaitingManeuver = false;
            diagBlankWarned = false;
            Log.i(TAG, "RGI-DIAG MANEUVER sent after CLEAR gen=" + gen
                + " idx=" + firstIdx + " ver=" + ver + " icon=" + icon
                + " presentation=" + presentationActive);
        }
    }
}
