/*
 * ScreenModule — instrument-cluster (LVDS2 / terminal 1) CONTEXT MANAGER.
 *
 * Owns the CarPlay cluster contexts and selects between three, all declared at init:
 *
 *   dc[80] = {98 maneuver, 101/102 KDK backing, 3  CarPlay AltScreen video}  - CarPlay map on screen
 *   dc[81] = {98 maneuver, 101/102 KDK backing, 33 stock native map}          - Audi map + RGI overlay
 *   dc[74] = stock cluster                                                    - otherwise
 *
 * The maneuver overlay (displayable 98, maneuver_render, transparent when idle) and the KDK backings
 * ride on top of either map, so the RGI works in both 80 and 81.  Which map is shown is a real
 * context change (80 <-> 81); the context tables are never edited at runtime because they are
 * declared to the native compositor once, at init, in DisplayManagerMIB2High.
 * getMappedInternalContext is identity on MIB2High, so switchContext(n) lands on exactly the
 * declared context n.
 *
 * Every new CarPlay session leaves the cluster on stock (74).  In CarPlay map mode we take ctx 80 as
 * soon as the sidecar has really presented a frame (/tmp/mmi-mirror-basevideo.ready); a turn approach
 * before that (or in Audi map mode) uses ctx 81.  We drop back to 74 once VC withdraws KDK visibility
 * (Fct44) after guidance ends, and on disconnect.  A 5 s hold of the left roller flips between the
 * CarPlay map mode and the Audi map mode while a CarPlay session is connected.
 *
 * There is exactly ONE persistent worker for the module lifetime and it is the SOLE
 * caller of DisplayManager.switchContext/setUpdateRate.  Single writer => two
 * switches can never race the bounce+settle, and no stale per-session worker can
 * exist.  start()/stop() only publish the desired context; the worker converges the
 * cluster to it.
 */
package com.luka.carplay.core;

import com.luka.carplay.framework.Log;

import de.audi.atip.hmi.view.IDisplayManager;
import de.audi.tghu.fwhmi.IDisplayManagerKombiControl;

public final class ScreenModule implements Module {

    private static final String TAG = "Screen";

    public static final int TERMINAL_CLUSTER  = 1;    /* LVDS2 */
    public static final int CTX_CLUSTER       = 80;   /* CarPlay AltScreen: {98 maneuver, 101/102 backing, 3 CarPlay video} */
    public static final int CTX_CLUSTER_AUDI  = 81;   /* Audi map + RGI:    {98 maneuver, 101/102 backing, 33 stock map} */
    public static final int CTX_STOCK_CLUSTER = 74;
    private static boolean isClusterCtx(int c) { return c == CTX_CLUSTER || c == CTX_CLUSTER_AUDI; }
    private static final int CTX_BOUNCE       = 72;   /* kombi map — never ours; forces a real ctx change */
    private static final int BOUNCE_SLEEP_MS  = 180;  /* preContextSwitchHook settle (proven driver) */
    private static final long CONTEXT_RECONCILE_MS = 250L;
    private static final int CLUSTER_FPS      = 30;   /* cluster encoder rate; MOST/encoder may cap below this */
    private static final int KOMBI_TYPE_G24   = 4;

    private static final Object LOCK = new Object();
    private IDisplayManager dm;                  /* current DisplayManager (guarded by LOCK); stable across sessions */
    private Thread worker;                        /* the ONE persistent switch worker (guarded by LOCK) */
    private static volatile Thread contextWriterThread;

    /* true while the cluster is on OUR context (set AFTER the physical switch completes). */
    private static volatile boolean clusterActive = false;
    private static volatile boolean platformSupported = true;
    private boolean enabled;

    /**
     * True from start() (BEFORE any switch) until stop() — i.e. this returns our *intent* to own the
     * cluster for the whole session, not the applied state.  CombiMapController's View-pin reads THIS:
     * pinning on the applied state leaves a ~180ms window during the first switch where a View press
     * could steal terminal 1 to the stock map (worker then thinks it still owns ctx → stuck).
     * Intent-based pin closes that window from t0. */
    public static boolean isConnected() { return platformSupported && connected; }

    /** DisplayManagerMIB2High uses this to distinguish our serialized 72/80/74
     * writes from stock screen-controller requests while CarPlay owns terminal 1. */
    public static boolean isClusterContextWriterThread() {
        return Thread.currentThread() == contextWriterThread;
    }

    static boolean isPlatformSupported(FrameworkRef fw) {
        try { return fw != null && fw.framework() != null
            && fw.framework().getKombiType() != KOMBI_TYPE_G24; }
        catch (Throwable t) { return true; }  /* only an explicit G24 value disables the feature */
    }

    /* desiredCtx = target published by setRouteActive()/setPresentationActive()/stop();
     * currentCtx = what the worker last applied. Both guarded by LOCK; the worker switches whenever they differ.
     * desiredCtx truth table:
     *   !connected                                                          -> 74 (stock)
     *   connected, routeActive=1, presentationActive=0 (cruising)           -> 74 (stock cluster: speedometer opening closed)
     *   connected, routeActive=1, presentationActive=1 (approaching turn)   -> 80 (CarPlay map ready) / 81 (Audi map)
     *   connected, routeActive=1, presentationActive=0 (approach exit)      -> 74 (immediate drop to stock cluster)
     *   connected, routeActive=0, navHidePending=1 (route end, KDK visible) -> 80/81 (hold until Fct44 withdrawal)
     *   connected, routeActive=0, navHidePending=0 (route end, KDK hidden)  -> 74 (stock cluster)
     *   connected, CarPlay map mode, sidecar presenting (any nav state)     -> 80 */
    private static int desiredCtx = CTX_STOCK_CLUSTER;
    private static int currentCtx = -1;
    private static volatile boolean connected = false;
    private static volatile boolean routeActive = false;
    private static volatile boolean presentationActive = false;
    private static volatile boolean navActive = false;
    private static volatile boolean rgdActive = false;
    private static boolean navHidePending = false;
    private static boolean rebindPending = false;
    private static String rebindReason = "";

    public interface RebindListener {
        void onClusterContextRebindRequested(String reason);
    }
    private static RebindListener rebindListener = null;

    public static void setRebindListener(RebindListener listener) {
        synchronized (LOCK) {
            rebindListener = listener;
        }
    }

    /** Request a physical 72->80 context acquisition even when desiredCtx == currentCtx == 80.
     *  Does not alter navActive or desiredCtx. Wakes the worker to re-run applySwitch(). */
    public static void requestClusterContextRebind(String reason) {
        RebindListener listener = null;
        synchronized (LOCK) {
            rebindPending = true;
            rebindReason = reason != null ? reason : "";
            Log.i(TAG, "rebindPending set (reason=" + rebindReason + ")");
            listener = rebindListener;
            LOCK.notifyAll();
        }
        if (listener != null) {
            listener.onClusterContextRebindRequested(reason);
        }
    }

    /* ------------------------------------------------------------
     * Map Mode Cycling (press-and-hold left steering wheel roller >= 5s)
     * Cycle between CarPlay Maps (default) and Stock Audi Maps (backup).
     * ------------------------------------------------------------ */
    public static final int MAP_MODE_CARPLAY      = 0;  // Default: CarPlay AltScreen map (displayable 3)
    public static final int MAP_MODE_AUDI_BACKUP  = 1;  // Secondary: Stock Audi onboard map (displayable 33)

    private static volatile int activeMapMode = MAP_MODE_CARPLAY;

    public static boolean isCarPlayMapActive() {
        return activeMapMode == MAP_MODE_CARPLAY;
    }

    public static int getActiveMapMode() {
        return activeMapMode;
    }

    public static void onSteeringWheelLongPressed() {
        if (!connected) {
            Log.i(TAG, "long press ignored: CarPlay session not connected");
            return;
        }
        Log.i(TAG, "steering wheel long-press received");
        cycleMapMode();
    }

    private static volatile boolean streamReadyCached = false;  /* worker poll only; read under LOCK */
    private static int streamReadyPolls = 0;                    /* poll bookkeeping (guarded by LOCK) */
    /* Flap guard (guarded by LOCK): if the sidecar keeps losing "ready" (crash loop), stop offering the CarPlay
     * map for the rest of this phone connection and stay on the stock Audi map, which is the RGI-safe state. */
    private static final int READY_DROPS_BEFORE_LATCH = 3;
    private static final long READY_DROP_WINDOW_MS = 60000L;
    private static boolean streamLatchedOff = false;
    private static int readyDrops = 0;
    private static long readyDropWindowStart = 0L;
    /* true only while ctx 80 (the CarPlay AltScreen composition) is the applied cluster context. */
    private static volatile boolean altScreenShowing = false;

    /** True while the CarPlay AltScreen map is actually on the cluster (ctx 80 applied). */
    public static boolean isAltScreenShowing() { return altScreenShowing; }

    /** Flip between the CarPlay map mode and the Audi map mode.  Only meaningful while a CarPlay
     *  session is connected.  Both maps are already-declared contexts (80 / 81), so this is a plain
     *  context change decided by recomputeDesiredCtxLocked(); nothing is edited at runtime. */
    public static void cycleMapMode() {
        /* One fresh look at the sidecar when entering CarPlay mode: Audi mode does not poll, so the
         * cached flag may be stale.  File I/O, hence outside LOCK. */
        boolean fresh = isAltScreenStreamReady();
        synchronized (LOCK) {
            if (!connected) return;
            int nextMode = (activeMapMode == MAP_MODE_CARPLAY) ? MAP_MODE_AUDI_BACKUP : MAP_MODE_CARPLAY;
            if (nextMode == MAP_MODE_CARPLAY) {
                streamReadyCached = fresh && !streamLatchedOff;
                streamReadyPolls = 0;
            }
            activeMapMode = nextMode;
            Log.i(TAG, "Map mode cycled -> " + (nextMode == MAP_MODE_CARPLAY
                ? "CARPLAY (alt screen, ready=" + streamReadyCached + ")" : "AUDI_BACKUP (stock Audi map)"));
            recomputeDesiredCtxLocked();
            LOCK.notifyAll();
        }
    }

    /**
     * Called when CarPlay session deactivates: resets active map mode to CarPlay so the NEXT
     * phone connection starts on the CarPlay second screen.
     */
    public static void onCarPlayDisconnected() {
        synchronized (LOCK) {
            activeMapMode = MAP_MODE_CARPLAY;
        }
        Log.i(TAG, "CarPlay disconnected: reset activeMapMode -> CARPLAY");
    }

    private static void recomputeDesiredCtxLocked() {
        int prev = desiredCtx;
        navActive = (routeActive && presentationActive) || navHidePending;
        if (!connected) {
            desiredCtx = CTX_STOCK_CLUSTER;
        } else if (activeMapMode == MAP_MODE_CARPLAY && streamReadyCached) {
            /* The sidecar is presenting: the CarPlay map owns the cluster all the time (cruising and
             * navigating); the RGI planes in ctx 80 ride on top of it. */
            desiredCtx = CTX_CLUSTER;
        } else {
            /* Audi map mode, or CarPlay mode while the sidecar is not (yet) presenting: stock cluster
             * while cruising, Audi map + RGI overlay (ctx 81) during a turn approach. */
            desiredCtx = navActive ? CTX_CLUSTER_AUDI : CTX_STOCK_CLUSTER;
        }
        if (desiredCtx != prev) {
            String why;
            if (!connected) {
                why = "session disconnected";
            } else if (activeMapMode == MAP_MODE_CARPLAY) {
                why = "carplay-mode (streamReady=" + streamReadyCached + " navActive=" + navActive + ")";
            } else {
                why = "audi-backup-mode (navActive=" + navActive + ")";
            }
            Log.i(TAG, "desiredCtx changed " + prev + " -> " + desiredCtx + " (why=" + why + ")");
        }
    }

    /** Recompute desiredCtx from connected/routeActive/presentationActive and wake the worker. Caller must NOT hold LOCK. */
    private static void republish() {
        synchronized (LOCK) {
            recomputeDesiredCtxLocked();
            LOCK.notifyAll();
        }
    }

    /** Route lifecycle gate owned by RouteGuidance / BAP session.
     *  On route end, retains the composition only if VC KDK is visible (Fct44) until withdrawal. */
    public static void setRouteActive(boolean active) {
        boolean switchPending;
        synchronized (LOCK) {
            if (!active) {
                boolean wasActive = navActive;
                navHidePending = wasActive
                    && com.luka.carplay.cluster.ClusterLayerController.isKdkVisible();
                routeActive = false;
                presentationActive = false;
                rebindPending = false;
                rebindReason = "";
            } else {
                routeActive = true;
                navHidePending = false;
            }
            recomputeDesiredCtxLocked();
            switchPending = (desiredCtx != currentCtx);
            LOCK.notifyAll();
        }
        if (!switchPending) {
            com.luka.carplay.cluster.ClusterLayerController.reapply();
        }
    }

    /** Unconditional lifecycle rollback: immediately clears routeActive, presentationActive,
     *  navHidePending, and rebindPending, forcing desiredCtx back to stock (74).
     *  Unlike setRouteActive(false), this NEVER triggers route-end KDK-hold semantics
     *  (navHidePending remains false even if KDK is visible). */
    public static void rollbackRouteLifecycle() {
        boolean switchPending;
        synchronized (LOCK) {
            routeActive = false;
            presentationActive = false;
            navHidePending = false;
            rebindPending = false;
            rebindReason = "";
            recomputeDesiredCtxLocked();
            switchPending = (desiredCtx != currentCtx);
            LOCK.notifyAll();
        }
        if (!switchPending) {
            com.luka.carplay.cluster.ClusterLayerController.reapply();
        }
    }

    /** Dynamic maneuver presentation gate owned by BAPBridge approach monitoring.
     *  Active only within approach threshold (<= 305m / <= 1600m) with confirmed frame-ready renderer.
     *  Cruising / approach exit immediately returns desiredCtx to 74 and NEVER latches navHidePending.
     *  Presentation strictly depends on route activity; activation when route is inactive is rejected. */
    public static void setPresentationActive(boolean active) {
        boolean switchPending;
        synchronized (LOCK) {
            if (active && !routeActive) {
                return;
            }
            presentationActive = active;
            if (!active) {
                rebindPending = false;
                rebindReason = "";
            }
            recomputeDesiredCtxLocked();
            switchPending = (desiredCtx != currentCtx);
            LOCK.notifyAll();
        }
        if (!switchPending) {
            com.luka.carplay.cluster.ClusterLayerController.reapply();
        }
    }

    /** Combined route/presentation setter for legacy callers. */
    public static void setNavActive(boolean active) {
        if (active) {
            setRouteActive(true);
            setPresentationActive(true);
        } else {
            setRouteActive(false);
        }
    }

    /** Called after the layer controller has applied the received Fct44 visibility.
     *  A View fade-out must not release context while the route remains active. */
    public static void onVcKdkVisibility(boolean visible) {
        boolean release = false;
        boolean switchPending = false;
        synchronized (LOCK) {
            if (!visible && navHidePending) {
                navHidePending = false;
                rebindPending = false;
                rebindReason = "";
                release = true;
                recomputeDesiredCtxLocked();
                switchPending = (desiredCtx != currentCtx);
                LOCK.notifyAll();
            }
        }
        if (release && !switchPending) {
            com.luka.carplay.cluster.ClusterLayerController.reapply();
        }
    }

    /** The cluster-layer visibility gate read by CombiMapController and ClusterLayerController.
     *  True whenever CarPlay layers are permitted to be visible on terminal 1. */
    public static boolean isNavActive() {
        return navActive;
    }

    public static boolean isPresentationActive() {
        return presentationActive;
    }

    public static boolean isNavHidePending() {
        return navHidePending;
    }

    public static boolean isClusterActive() {
        return clusterActive;
    }

    public static int getDesiredCtx() {
        synchronized (LOCK) {
            return desiredCtx;
        }
    }

    public static int getCurrentCtx() {
        synchronized (LOCK) {
            return currentCtx;
        }
    }

    public static boolean isRouteActive() {
        return routeActive;
    }

    public static boolean isRgdActive() { return rgdActive; }

    public static void setRgdActive(boolean active) { rgdActive = active; }

    /* ------------------------------------------------------------
     * Cluster map view size (Audi View button / NAV_VIEW_SIZE_CHOICE).
     * There is no CarPlay video to resize on this branch; the flag drives only LOCAL geometry —
     * which KDK stage (popup/in-tube) and which native map plane (33/58) the maneuver overlay must
     * follow.  No hook command is sent.
     * ------------------------------------------------------------ */
    public static final int VIEWAREA_FULLSCREEN  = 0;
    public static final int VIEWAREA_SMALLSCREEN = 1;
    private static volatile boolean smallScreenViewArea = false;
    private static volatile ViewAreaModeListener viewAreaModeListener;

    /** Lightweight notification for consumers whose cluster presentation differs by view area. */
    public interface ViewAreaModeListener {
        void onViewAreaModeChanged(int mode);
    }

    public static boolean isSmallScreenViewArea() { return smallScreenViewArea; }

    public static void setViewAreaModeListener(ViewAreaModeListener listener) {
        viewAreaModeListener = listener;
    }

    public static void clearViewAreaModeListener(ViewAreaModeListener listener) {
        if (viewAreaModeListener == listener) viewAreaModeListener = null;
    }

    /** Called by the stock NAV_VIEW_SIZE_CHOICE model: value 0=fullscreen, value 1=smallscreen. */
    public static void setViewAreaMode(int mode) {
        boolean small = (mode == VIEWAREA_SMALLSCREEN);
        if (smallScreenViewArea == small) return;
        smallScreenViewArea = small;
        Log.i(TAG, "view-area changed: mode=" + mode + " (" + (small ? "SMALLSCREEN" : "FULLSCREEN") + ")");
        publishHmiState(small ? 1 : 0, small ? "SPORT" : "CLASSIC", "view-area-change");
        ViewAreaModeListener listener = viewAreaModeListener;
        if (listener != null) {
            try { listener.onViewAreaModeChanged(small ? VIEWAREA_SMALLSCREEN : VIEWAREA_FULLSCREEN); }
            catch (Throwable t) { Log.w(TAG, "viewArea listener failed: " + t); }
        }
    }

    /* ------------------------------------------------------------
     * Route-info toggle (steering-wheel OK press).
     * Flips the cluster route-info text line between the next turn-to street and the
     * trip summary (ETA / arrival clock + remaining).  Driven by SteeringWheelInputModule.
     * ------------------------------------------------------------ */

    /** Route-info toggle seam driven by the raw MFW left-roller press listener. */
    public interface InfoModeListener {
        void onInfoModeToggle();
    }
    private static volatile InfoModeListener infoModeListener;

    public static void setInfoModeListener(InfoModeListener listener) {
        infoModeListener = listener;
    }

    public static void clearInfoModeListener(InfoModeListener listener) {
        if (infoModeListener == listener) infoModeListener = null;
    }

    /** Raw DSI key 40 (left steering-wheel roller press).  SteeringWheelInputModule already gates
     *  this callback to the confirmed VC map tab; the toggle is meaningful only with active RGI. */
    public static void onSteeringWheelOkPressed() {
        Log.i(TAG, "steering wheel short-press received (connected=" + isConnected() + " rgdActive=" + isRgdActive() + ")");
        if (!isConnected() || !isRgdActive()) return;
        InfoModeListener listener = infoModeListener;
        if (listener == null) return;
        listener.onInfoModeToggle();
    }

    public String name() { return "screen"; }

    public boolean start(FrameworkRef fw) {
        Log.i(TAG, "start() called (activeMapMode=" + activeMapMode + ")");
        if (fw == null || !fw.isReady() || fw.framework() == null) return false;
        if (!isPlatformSupported(fw)) {
            platformSupported = false;
            enabled = false;
            Log.w(TAG, "disabled: G24 cluster has no ctx 80 maneuver composition");
            return true;
        }
        platformSupported = true;
        enabled = true;

        IDisplayManager d = null;
        try {
            if (fw.framework().getHMIService() != null) {
                d = fw.framework().getHMIService().getDisplayManager();
            }
        } catch (Throwable t) {
        }
        if (d == null) return false;                 /* DM not up yet → retry */
        if (d instanceof IDisplayManagerKombiControl) {
            com.luka.carplay.cluster.ClusterLayerController.bind(
                (IDisplayManagerKombiControl)d, TERMINAL_CLUSTER);
        }

        /* Publish the new session target before a new worker can observe currentCtx=-1. */
        synchronized (LOCK) {
            /* A replaced DisplayManager invalidates the cached currentCtx — reset so the worker
             * re-applies the desired ctx to the new one.  (In practice the same object each session.) */
            if (dm != d) { dm = d; currentCtx = -1; }
            connected = true;
            routeActive = false;
            presentationActive = false;
            navHidePending = false;
            rgdActive = false;
            clusterActive = false;
            altScreenShowing = false;
            streamReadyCached = false;
            streamReadyPolls = 0;
            streamLatchedOff = false;
            readyDrops = 0;
            readyDropWindowStart = 0L;
            recomputeDesiredCtxLocked();
        }
        synchronized (LOCK) {
            /* Create the single persistent worker once; recreate only if it never started or died.
             * Assign the field ONLY after start() succeeds so a throw leaves worker==null for retry. */
            if (worker == null || !worker.isAlive()) {
                Thread w = new Thread(new Runnable() { public void run() { switchLoop(); } }, "carplay-cluster-switch");
                w.setDaemon(true);
                w.start();
                worker = w;
            }
            LOCK.notifyAll();
        }
        Log.i(TAG, "ready (DisplayManager acquired, session reset -> ctx 74)");
        return true;
    }

    public void stop() {
        if (!enabled) return;
        enabled = false;
        /* Disconnect: publish stock (74); the single persistent worker restores it.  We deliberately
         * do NOT kill the worker or null dm — the worker being the sole always-live DM writer is what
         * makes stale-worker races impossible (no per-session worker to outlive its session). */
        synchronized (LOCK) {
            connected = false;
            routeActive = false;
            presentationActive = false;
            navHidePending = false;
            rgdActive = false;
            rebindPending = false;
            rebindReason = "";
            clusterActive = false;
            altScreenShowing = false;
            streamReadyCached = false;
            streamReadyPolls = 0;
            streamLatchedOff = false;
            readyDrops = 0;
            readyDropWindowStart = 0L;
        }
        publishClusterOwnershipState(CTX_STOCK_CLUSTER, false, false);
        republish();
    }

    /* ============================================================
     * Switch worker — the single serialized DM writer.
     * ============================================================ */

    /** Fold one stream-ready poll sample into the cached flag.  Caller must hold LOCK.
     *  The signal is the sidecar's own first-present file, so it is applied immediately in both directions;
     *  repeated losses latch the CarPlay map off for the connection (flap guard). */
    private static void applyStreamPollLocked(boolean ready) {
        boolean prev = streamReadyCached;
        if (streamLatchedOff) ready = false;
        streamReadyCached = ready;
        streamReadyPolls = 0;
        if (streamReadyCached == prev) return;
        if (prev && !streamReadyCached && !streamLatchedOff) {
            long now = System.currentTimeMillis();
            if (now - readyDropWindowStart > READY_DROP_WINDOW_MS) {
                readyDropWindowStart = now;
                readyDrops = 0;
            }
            readyDrops++;
            if (readyDrops >= READY_DROPS_BEFORE_LATCH) {
                streamLatchedOff = true;
                Log.w(TAG, "CarPlay map latched OFF for this connection: sidecar lost ready " + readyDrops
                    + "x in " + (READY_DROP_WINDOW_MS / 1000L) + "s; staying on the stock Audi map");
            }
        }
        int prevDesired = desiredCtx;
        recomputeDesiredCtxLocked();
        Log.i(TAG, "CarPlay stream readiness " + prev + " -> " + streamReadyCached
            + " desiredCtx " + prevDesired + " -> " + desiredCtx);
    }

    private void switchLoop() {
        contextWriterThread = Thread.currentThread();
        boolean havePoll = false;      /* a stream-ready sample read outside LOCK, waiting to be folded in */
        boolean polled = false;
        while (true) {
            int target = 0; IDisplayManager d = null; boolean reconcileOnly = false; boolean pollNow = false;
            synchronized (LOCK) {
                while (dm == null) {
                    try { LOCK.wait(); } catch (InterruptedException e) { /* persistent worker */ }
                }
                boolean skipWait = havePoll;
                if (havePoll) {
                    havePoll = false;
                    if (connected && activeMapMode == MAP_MODE_CARPLAY) applyStreamPollLocked(polled);
                }
                if (isClusterCtx(desiredCtx) && rebindPending) {
                    rebindPending = false;
                    Log.i(TAG, "forcing cluster context " + desiredCtx + " rebind (reason=" + rebindReason + ")");
                } else if (desiredCtx == currentCtx) {
                    if (!skipWait) {
                        try {
                            if (isClusterCtx(desiredCtx))
                                LOCK.wait(CONTEXT_RECONCILE_MS);
                            else if (connected && activeMapMode == MAP_MODE_CARPLAY)
                                LOCK.wait(CONTEXT_RECONCILE_MS);
                            else
                                LOCK.wait();
                        } catch (InterruptedException e) { /* persistent worker */ }
                        if (dm == null) continue;
                        /* Read the stream marker outside LOCK, then come back and fold it in. */
                        pollNow = (connected && activeMapMode == MAP_MODE_CARPLAY);
                    }
                    if (!pollNow) {
                        if (desiredCtx != currentCtx) continue;
                        if (isClusterCtx(desiredCtx) && rebindPending) {
                            rebindPending = false;
                            Log.i(TAG, "forcing cluster context " + desiredCtx + " rebind (reason=" + rebindReason + ")");
                        } else {
                            if (!isClusterCtx(desiredCtx)) continue;
                            reconcileOnly = true;
                        }
                    }
                }
                target = desiredCtx; d = dm;
            }
            if (pollNow) {
                polled = isAltScreenStreamReady();
                havePoll = true;
                continue;
            }
            if (reconcileOnly) {
                int actual;
                try { actual = d.getCurrentContextID(TERMINAL_CLUSTER); }
                catch (Throwable t) {
                    Log.w(TAG, "context reconcile read failed: " + t);
                    continue;
                }
                if (actual != target) {
                    Log.i(TAG, "reconcile correction actual=" + actual + " desired=" + target);
                    boolean retry = false;
                    synchronized (LOCK) {
                        if (dm == d && desiredCtx == target && currentCtx == target) {
                            currentCtx = -1;
                            clusterActive = false;
                            altScreenShowing = false;
                            retry = true;
                            LOCK.notifyAll();
                        }
                    }
                    if (retry)
                        Log.w(TAG, "physical context drift actual=" + actual
                            + " desired=" + target + " -> reconcile");
                }
                continue;
            }
            applySwitch(target, d);
        }
    }

    /** Perform ONE context switch (bounce + settle + select).  Only this thread ever writes the DM,
     *  so there is no cross-worker race; the loop re-runs if desiredCtx changed during the settle.
     *  Note: a stop()/start() landing in the tiny window between the post-sleep recheck and the
     *  switchContext write can still cause ONE transient physical write before the next loop restores
     *  the newly-desired ctx — it is self-healing.  Closing it fully needs LOCK held across a DSI IPC
     *  call → deadlock risk, not worth it for a cosmetic transient on connect/disconnect. */
    private void applySwitch(int ctx, IDisplayManager d) {
        try {
            if (ctx != CTX_STOCK_CLUSTER) {
                int prevCtx;
                synchronized (LOCK) { prevCtx = currentCtx; }
                /* 80 <-> 81 while the cluster is already ours: the MOST encoder is on, so a plain context
                 * change is enough and the throwaway stock-map bounce (a visible flash) is skipped. */
                boolean direct = isClusterCtx(prevCtx) && isClusterCtx(ctx) && prevCtx != ctx;
                if (!direct) {
                    /* Coming from stock (74) or an unknown state (-1): the MOST encoder is off, so the grab
                     * of the cluster needs a real context change via a throwaway ctx (72) + settle before
                     * switchContext(n) will re-point the encoder. */
                    int bounce = (ctx != CTX_BOUNCE) ? CTX_BOUNCE : CTX_STOCK_CLUSTER;
                    d.switchContext(bounce, TERMINAL_CLUSTER, null);
                    try { Thread.sleep(BOUNCE_SLEEP_MS); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    /* Coalesce: if the desired target or the DM changed during the settle, abandon THIS
                     * switch (cluster is on the bounce ctx) and let the loop apply the latest desired. */
                    synchronized (LOCK) {
                        if (dm != d || desiredCtx != ctx) {
                            currentCtx = -1;
                            clusterActive = false;
                            altScreenShowing = false;
                            Log.i(TAG, "switch(" + ctx + ") superseded during bounce → " + desiredCtx);
                            return;
                        }
                    }
                }
                d.switchContext(ctx, TERMINAL_CLUSTER, null);
                boolean stillValid;
                synchronized (LOCK) {
                    stillValid = (dm == d && desiredCtx == ctx);
                    if (stillValid) {
                        currentCtx = ctx;
                        clusterActive = true;
                        altScreenShowing = (ctx == CTX_CLUSTER);
                    } else {
                        currentCtx = -1;
                        clusterActive = false;
                        altScreenShowing = false;
                        Log.i(TAG, "switch(" + ctx + ") superseded at write → " + desiredCtx);
                    }
                }
                if (stillValid) {
                    d.setUpdateRate(TERMINAL_CLUSTER, CLUSTER_FPS);   /* (idempotent when already running) */
                    com.luka.carplay.cluster.ClusterLayerController.reapply();
                }
            } else {
                /* Preserve the stop-before-switch ordering, but never leave terminal 1
                 * parked at 0 FPS. On this A5/MHI2Q the stock
                 * KOMBI_KDK_VIA_DISPLAYABLES branch bypasses CombiMapController's
                 * optional 10/1/0 updateFrameRate() path, so 10 is not an authoritative
                 * restore value here. Return the terminal to the same full 30 Hz rate
                 * used by the live cluster encoder; stock may change it later if it has
                 * an applicable producer. */
                if (!connected) {
                    d.setUpdateRate(TERMINAL_CLUSTER, 0);
                    try {
                        d.switchContext(CTX_STOCK_CLUSTER, TERMINAL_CLUSTER, null);
                    } finally {
                        d.setUpdateRate(TERMINAL_CLUSTER, CLUSTER_FPS);
                    }
                } else {
                    d.switchContext(CTX_STOCK_CLUSTER, TERMINAL_CLUSTER, null);
                }
                synchronized (LOCK) {
                    if (dm == d) {
                        currentCtx = (desiredCtx == ctx) ? ctx : -1;
                    }
                    clusterActive = false;
                    altScreenShowing = false;
                }
                com.luka.carplay.cluster.ClusterLayerController.reapply();
            }
            boolean session;
            synchronized (LOCK) { session = connected; }
            publishClusterOwnershipState(ctx, clusterActive && ctx == CTX_CLUSTER, session);
            Log.i(TAG, "cluster -> ctx " + ctx + " (active=" + clusterActive + ")");
        } catch (Throwable t) {
            Log.w(TAG, "switch(" + ctx + ") failed: " + t);
            boolean session;
            synchronized (LOCK) {
                currentCtx = -1;
                clusterActive = false;
                altScreenShowing = false;
                session = connected;
            }
            publishClusterOwnershipState(CTX_STOCK_CLUSTER, false, session);
            /* Throttle the retry: the bounce write and the stock path have no settle sleep, so a
             * persistently-throwing switchContext would otherwise hot-spin (busy loop + log flood). */
            try { Thread.sleep(BOUNCE_SLEEP_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    private static final Object STATE_FILE_LOCK = new Object();
    private static final String CLUSTER_STATE_FILE = "/tmp/mmi-mirror-cluster-ownership.state";
    private static final String HMI_STATE_FILE = "/tmp/mmi-mirror-hmi.state";
    /* Written by carplay-alt111-mirror-display only after its first successful present into displayable 3
     * and deleted by start_vehicle.sh on every sidecar (re)start/stop: the one signal that the CarPlay map
     * is really on screen.  It is NOT the supervisor demand file /tmp/mmi-mirror-active, which is raised
     * before the sidecar even starts and would make Java select an empty displayable 3. */
    private static final String BASE_READY_FILE = "/tmp/mmi-mirror-basevideo.ready";

    private static boolean clusterRenameWarned = false;   /* guarded by STATE_FILE_LOCK */
    private static boolean hmiRenameWarned = false;       /* guarded by STATE_FILE_LOCK */

    /**
     * True iff the AltScreen sidecar has actually presented a frame (see BASE_READY_FILE).
     * Does file I/O: never call while holding LOCK.
     */
    public static boolean isAltScreenStreamReady() {
        try {
            return new java.io.File(BASE_READY_FILE).exists();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void publishClusterOwnershipState(int ctx, boolean owned, boolean sessionActive) {
        synchronized (STATE_FILE_LOCK) {
            try {
                java.io.File target = new java.io.File(CLUSTER_STATE_FILE);
                java.io.File tmp = new java.io.File(CLUSTER_STATE_FILE + ".tmp");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
                StringBuffer sb = new StringBuffer();
                sb.append("version=1\n")
                  .append("carplay_session=").append(sessionActive ? "1" : "0").append("\n")
                  .append("cluster_owned=").append((sessionActive && owned) ? "1" : "0").append("\n")
                  .append("ownership_intent=").append(sessionActive ? "1" : "0").append("\n")
                  .append("composite_applied=").append((sessionActive && owned) ? "1" : "0").append("\n")
                  .append("context=").append(ctx).append("\n")
                  .append("timestamp_ms=").append(System.currentTimeMillis()).append("\n");
                byte[] bytes = sb.toString().getBytes("UTF-8");
                fos.write(bytes);
                fos.flush();
                fos.close();
                if (target.exists()) {
                    target.delete();
                }
                if (!tmp.renameTo(target)) {
                    // Fallback if filesystem rename fails: write directly to target
                    java.io.FileOutputStream fosTarget = new java.io.FileOutputStream(target);
                    fosTarget.write(bytes);
                    fosTarget.flush();
                    fosTarget.close();
                    tmp.delete();
                }
            } catch (Throwable t) {
                if (!clusterRenameWarned) {
                    clusterRenameWarned = true;
                    Log.w(TAG, "cluster ownership state write failed: " + CLUSTER_STATE_FILE + " (" + t.getMessage() + ")");
                }
            }
        }
    }

    private static void publishHmiState(int layout, String layoutName, String reason) {
        synchronized (STATE_FILE_LOCK) {
            try {
                java.io.File target = new java.io.File(HMI_STATE_FILE);
                java.io.File tmp = new java.io.File(HMI_STATE_FILE + ".tmp");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
                StringBuffer sb = new StringBuffer();
                sb.append("layout=").append(layout).append("\n")
                  .append("layout_name=").append(layoutName).append("\n")
                  .append("small_stage_dx=0\n")
                  .append("small_stage_dy=0\n")
                  .append("timestamp_ms=").append(System.currentTimeMillis()).append("\n")
                  .append("reason=").append(reason).append("\n");
                byte[] bytes = sb.toString().getBytes("UTF-8");
                fos.write(bytes);
                fos.flush();
                fos.close();
                if (target.exists()) {
                    target.delete();
                }
                if (!tmp.renameTo(target)) {
                    java.io.FileOutputStream fosTarget = new java.io.FileOutputStream(target);
                    fosTarget.write(bytes);
                    fosTarget.flush();
                    fosTarget.close();
                    tmp.delete();
                }
            } catch (Throwable t) {
                if (!hmiRenameWarned) {
                    hmiRenameWarned = true;
                    Log.w(TAG, "hmi state write failed: " + HMI_STATE_FILE + " (" + t.getMessage() + ")");
                }
            }
        }
    }
}
