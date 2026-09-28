/*
 * AndroidAutoNavTap -- a PASSIVE tap on the stock Android Auto DSI turn-by-turn
 * events.  It registers itself as an ADDITIONAL org.dsi.ifc.androidauto2.
 * DSIAndroidAuto2Listener alongside whatever stock listener(s) are already
 * registered (org.dsi.ifc.androidauto2.DSIAndroidAuto2 / the Audi proxy and
 * AndroidAuto2NavHandler are untouched -- this class never calls anything on
 * them and never requests/grants AA nav focus).  It forwards NOWHERE: turn
 * events are recorded into a bounded in-memory ring and periodically
 * rewritten (never appended) to /tmp/aa_nav_tap.log for offline inspection.
 *
 * Registration precedent: com.luka.carplay.core.SteeringWheelInputModule
 * registers a THIRD DSIKeyPanelListener via
 *   fw.serviceManager().registerDSIListener(instance, ifaceName, listener)
 * alongside the stock keyboard service and TerminalMode's own listener --
 * DSI listener registration is additive, not exclusive.  This class mirrors
 * that exact mechanism for DSIAndroidAuto2Listener.  The DSI instance number
 * for AndroidAuto2 is UNVERIFIED; 0 is used because it is the instance every
 * other DSI listener registration in this codebase uses (DSIKeyPanel,
 * DSICarplay).  If a real unit shows no callbacks at all, this is the first
 * thing to re-check (see the on-car verification steps in the change notes).
 *
 * Argument NAMES for updateNavigationNextTurnEvent/-Distance/navFocusRequest-
 * Notification are UNVERIFIED (javap gives types only).  Every raw int is
 * recorded and labelled tentatively (a1, a2, ... plus a best-guess name) so
 * the log itself is the source of truth once real callbacks are observed.
 *
 * Isolation has two independent halves, one at runtime and one at build time --
 * either alone is enough to guarantee CarPlay is unaffected by AndroidAuto2 being
 * missing; together they cover both ways this class's source can be encountered.
 *   - RUNTIME: nothing else in java_patch references this class by name. It is
 *     loaded and started only via Class.forName(...).getMethod("start",...) from
 *     TerminalModeBapCombi.init(), wrapped in try/catch(Throwable). On a firmware
 *     image missing org.dsi.ifc.androidauto2, Class.forName itself throws
 *     ClassNotFoundException (this class's own bytecode references those types in
 *     its `implements` clause and method signatures) or a later NoClassDefFoundError
 *     during verification -- either way it is caught there and never touches any
 *     CarPlay class.
 *   - BUILD TIME: scripts/build_java.sh compiles every java_patch source in one
 *     javac call against the stock jar, so a stock jar that altogether lacks
 *     org/dsi/ifc/androidauto2/DSIAndroidAuto2Listener.class would fail that whole
 *     compile with unresolved-symbol errors from *this* file, not a caught
 *     exception -- the runtime guarantee above cannot help at compile time. The
 *     script pre-checks the stock jar for that class and, if it is absent, excludes
 *     java_patch/com/luka/carplay/aa/ from the file list before invoking javac,
 *     printing "AA nav tap skipped: stock JAR lacks androidauto2". A build against
 *     such a jar therefore produces a carplay_hook.jar with no com/luka/carplay/aa
 *     classes at all; TerminalModeBapCombi's reflective Class.forName then always
 *     takes the ClassNotFoundException path described above, so this is safe by
 *     construction, not merely by coincidence of what happens to be present.
 *
 * Copyright (c) 2026 @ntrambitas2
 */
package com.luka.carplay.aa;

import com.luka.carplay.framework.Log;

import de.audi.app.terminalmode.IContext;
import de.audi.app.terminalmode.osgi.IServiceManager;

import org.dsi.ifc.androidauto2.CallState;
import org.dsi.ifc.androidauto2.DSIAndroidAuto2Listener;
import org.dsi.ifc.androidauto2.PlaybackInfo;
import org.dsi.ifc.androidauto2.TelephonyState;
import org.dsi.ifc.androidauto2.TrackData;
import org.dsi.ifc.global.ResourceLocator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;

public final class AndroidAutoNavTap implements DSIAndroidAuto2Listener {
    private static final String TAG = "AaNavTap";
    private static final String VERSION = "1";

    /** Master compile-time kill switch. */
    static final boolean ENABLED = true;

    /** Runtime off-switch: if present before an attempt, that attempt is skipped.
     *  Mutable (not final) only so a test can point it at a throwaway file; the
     *  default below is what production always uses. */
    private static final String DEFAULT_OFF_SWITCH_PATH = "/mnt/app/aa_nav_tap_off";
    private static String offSwitchPath = DEFAULT_OFF_SWITCH_PATH;

    /* DSI instance -- see class comment: unverified for AndroidAuto2, 0 by precedent. */
    private static final int DSI_INSTANCE = 0;

    private static final int CAPACITY = 256;
    private static final int ROAD_MAX = 128;
    private static final int LINE_MAX = 300;
    private static final long WRITE_INTERVAL_MS = 5000L;
    private static final int MAX_WRITE_FAILURES = 5;

    private static final int KIND_TURN = 0;
    private static final int KIND_DIST = 1;
    private static final int KIND_FOCUS = 2;

    /* ---- registration + retry state. The fields below are only ever mutated while
     * holding START_LOCK, and every hold of START_LOCK is a short, non-blocking
     * snapshot-or-publish step -- NEVER a span that includes calling registrar.attempt()
     * itself (that call happens fully unlocked; see attemptRegistration()). This is what
     * lets start() stay cheap and keeps callbacks (which never touch START_LOCK at all,
     * only RING_LOCK) from ever contending with a registration attempt in progress. ---- */
    private static final Object START_LOCK = new Object();
    private static boolean startCalled;
    private static boolean registered;
    private static Object registration;
    private static Object heldContext;
    private static long startTimeMillis;
    private static long firstAttemptAtMillis;
    private static long nextAttemptAtMillis;
    private static int attempts;
    private static boolean warnedPhase1;
    private static boolean warnedPhase2;
    private static boolean capLogged;
    private static Registrar registrar = new DefaultRegistrar();

    /* Fast phase: retry every 5 s for the first 2 minutes (matches the shared thread's
     * own 5 s tick, so every phase-1 tick is a retry attempt). Slow phase after that:
     * every 60 s (every 12th tick), forever, at negligible cost. A hard cap backstops
     * both phases: 2000 attempts at up to 60 s apart is well over a day of retrying
     * before the tap gives up (the writer keeps running regardless). */
    private static final long RETRY_FAST_MS = WRITE_INTERVAL_MS;
    private static final long RETRY_SLOW_MS = 60000L;
    private static final long RETRY_PHASE_MS = 120000L;
    private static final int DEFAULT_MAX_TOTAL_ATTEMPTS = 2000;
    private static int maxTotalAttempts = DEFAULT_MAX_TOTAL_ATTEMPTS;

    /** Performs one registration attempt. The real (default) implementation talks to the
     *  stock DSI service manager; tests inject a fake to drive the retry/backoff logic
     *  deterministically without any firmware types.
     *
     *  Success semantics mirror SteeringWheelInputModule's own registerDSIListener call
     *  (com.luka.carplay.core.SteeringWheelInputModule.java), which never null-checks the
     *  return value -- it treats "returned without throwing" as success, full stop, and
     *  stores whatever came back. attempt() does the same: returning normally (ANY value,
     *  including null) means success and that value is stored as-is; there is no separate
     *  "not ready" return value because there must be no ambiguity between "really
     *  registered, with a null handle" and "not ready". Throwing Registrar.NotReady means
     *  "not ready yet, try again later" (context/service manager not up); throwing anything
     *  else is treated the same way for retry purposes, just logged with its own message. */
    interface Registrar {
        /** Distinct from any other Throwable only for its log message; both mean retry later. */
        final class NotReady extends Exception {
            NotReady(String why) { super(why); }
        }

        Object attempt(Object contextObj) throws Exception;
    }

    /* Package-private (not private) so tests/AndroidAutoNavTapTest.java, in this same
     * package, can unit-test it directly (construct one, call attempt() a few times with a
     * fake IContext/IServiceManager) without going through start()/the worker thread at
     * all -- see its "same tap instance across attempts" test. */
    static final class DefaultRegistrar implements Registrar {
        /* ONE preallocated instance, reused for the life of the process. Never
         * `new AndroidAutoNavTap()` per attempt -- registerDSIListener must see the same
         * listener object every time, and after success this is never called again at all. */
        private final AndroidAutoNavTap tap = new AndroidAutoNavTap();

        public Object attempt(Object contextObj) throws Exception {
            if (!(contextObj instanceof IContext)) {
                throw new NotReady("start() context is not an IContext: " + contextObj);
            }
            IContext ctx = (IContext) contextObj;
            IServiceManager sm = ctx.getServiceManager();
            if (sm == null) {
                throw new NotReady("no IServiceManager available yet");
            }
            return sm.registerDSIListener(DSI_INSTANCE, DSIAndroidAuto2Listener.class.getName(), tap);
        }
    }

    /* ---- ring buffer: preallocated fixed-capacity slots, overwritten oldest-first ---- */
    private static final Object RING_LOCK = new Object();
    private static final Slot[] RING = new Slot[CAPACITY];
    private static long totalSeen;
    private static boolean dirty;

    static {
        for (int i = 0; i < CAPACITY; i++) RING[i] = new Slot();
    }

    /* ---- shared writer/retry thread state ---- */
    private static Thread writerThread;
    private static volatile boolean writerEnabled = true;
    private static int consecutiveFailures;
    private static String logPath = "/tmp/aa_nav_tap.log";

    private static final class Slot {
        long seq;
        long ts;
        int kind;
        int a1, a2, a3, a4, a5;
        String road;
    }

    public AndroidAutoNavTap() { }

    /** Reflective entry point: Class.forName(...).getMethod("start", new Class[]{Object.class}).
     *  contextObj is the stock de.audi.app.terminalmode.IContext passed by the caller as Object
     *  so the caller never needs to import anything from this package.
     *
     *  IContext choice: this holds exactly the IContext TerminalModeBapCombi.init() passes --
     *  the same object CarPlayApp.startTransport() immediately builds its always-on FrameworkRef
     *  from a few lines earlier in that same init(), so it is valid immediately, not something
     *  that "becomes ready" later. What is NOT guaranteed ready at that moment is the underlying
     *  AndroidAuto2 DSI service publication itself -- registerDSIListener can throw or return
     *  null before that service is up, mirroring how SteeringWheelInputModule's DSIKeyPanel
     *  lookup can fail early and needs CarPlayApp's own retry loop. There is no service-handle
     *  probe for AndroidAuto2 in the verified API (unlike FrameworkRef.getServiceHandle for
     *  DSIKeyPanel), so this retries the registration call itself rather than a handle lookup.
     *  A "fresher" context is deliberately not fetched from CarPlayApp/FrameworkRef: that would
     *  couple this always-on, connection-independent tap to the CarPlay activate/deactivate
     *  lifecycle it is explicitly independent of, and neither CarPlayApp nor the Module retry
     *  daemon may be modified or depended on here.
     *
     *  Idempotent: startCalled is set exactly once and gates everything below it, so a repeated
     *  call never re-registers or spawns a second thread.
     *
     *  Non-blocking by construction: this method never calls the registrar and never does I/O
     *  itself (the off-switch file check happens inside attemptRegistration(), off this thread).
     *  It only stores fields and starts the shared worker thread, which performs the FIRST
     *  registration attempt immediately at thread start (nextAttemptAtMillis is set to "now",
     *  so the worker's very first action -- before its first sleep -- is already due), then
     *  keeps retrying on the 5 s / 2 min / 60 s schedule described on attemptRegistration(). */
    public static void start(Object contextObj) {
        if (!ENABLED) return;
        synchronized (START_LOCK) {
            if (startCalled) return;
            startCalled = true;
            heldContext = contextObj;
            firstAttemptAtMillis = System.currentTimeMillis();
            nextAttemptAtMillis = firstAttemptAtMillis;
            ensureWriterStartedLocked();
        }
    }

    /** One registration attempt, called only from the shared worker thread (its first action,
     *  and again from maybeRetryTick()) -- never from start() or any caller thread. Holds
     *  START_LOCK only for the cheap snapshot-and-check at the top and the publish-the-result
     *  step at the bottom; the registrar.attempt() call itself, which may block or be slow, runs
     *  fully unlocked in between. This is what keeps callbacks (RING_LOCK only) and any other
     *  START_LOCK user from ever contending with a registration attempt in progress. */
    private static void attemptRegistration() {
        Object contextSnapshot;
        synchronized (START_LOCK) {
            if (registered) return;
            if (attempts >= maxTotalAttempts) return; /* cap already reached; see below */
            if (new File(offSwitchPath).exists()) return; /* kill switch checked before every attempt */
            attempts++;
            contextSnapshot = heldContext;
        }

        Object handle = null;
        boolean success = false;
        Throwable failure = null;
        try {
            handle = registrar.attempt(contextSnapshot);
            success = true; /* returned without throwing == success, mirrors SteeringWheelInputModule;
                              * ANY return value, including null, is a valid handle, stored as-is. */
        } catch (Throwable t) {
            failure = t;
        }

        synchronized (START_LOCK) {
            if (registered) return; /* only the worker thread calls this in production, but stay safe */
            if (success) {
                registration = handle;
                registered = true;
                startTimeMillis = System.currentTimeMillis();
                Log.i(TAG, "registered v" + VERSION + " instance=" + DSI_INSTANCE
                    + " after " + attempts + " attempt(s)");
                return;
            }
            warnOncePerPhaseLocked(failure);
            if (attempts >= maxTotalAttempts && !capLogged) {
                capLogged = true;
                Log.w(TAG, "giving up after " + attempts
                    + " attempt(s) (cap reached); the writer keeps running");
            }
        }
    }

    /** At most one Log.w for the whole fast phase, and at most one more for the whole slow
     *  phase -- never one per attempt. Caller must hold START_LOCK. */
    private static void warnOncePerPhaseLocked(Throwable failure) {
        long elapsed = System.currentTimeMillis() - firstAttemptAtMillis;
        String cause = failure == null ? "service not ready" : String.valueOf(failure);
        if (elapsed < RETRY_PHASE_MS) {
            if (warnedPhase1) return;
            warnedPhase1 = true;
            Log.w(TAG, "not registered after " + attempts + " attempt(s); retrying every "
                + (RETRY_FAST_MS / 1000L) + "s: " + cause);
        } else {
            if (warnedPhase2) return;
            warnedPhase2 = true;
            Log.w(TAG, "still not registered after " + attempts + " attempt(s); retrying every "
                + (RETRY_SLOW_MS / 1000L) + "s: " + cause);
        }
    }

    /** Called from the shared thread's tick. The "is it due yet" decision is a cheap,
     *  non-blocking, locked check; the actual attempt (if due) always runs unlocked via
     *  attemptRegistration(). Cheap no-op once registered or before start(). */
    private static void maybeRetryTick() {
        boolean due;
        synchronized (START_LOCK) {
            if (!startCalled || registered) {
                due = false;
            } else {
                long now = System.currentTimeMillis();
                if (now < nextAttemptAtMillis) {
                    due = false;
                } else {
                    long elapsed = now - firstAttemptAtMillis;
                    long interval = elapsed < RETRY_PHASE_MS ? RETRY_FAST_MS : RETRY_SLOW_MS;
                    nextAttemptAtMillis = now + interval;
                    due = true;
                }
            }
        }
        if (due) attemptRegistration();
    }

    /* ================= DSIAndroidAuto2Listener: 3 recorded, 14 no-op ================= */

    public void navFocusRequestNotification(int a1, int a2) {
        try { record(KIND_FOCUS, a1, a2, 0, 0, 0, null); }
        catch (Throwable t) { /* O(1), non-blocking, must never affect the DSI dispatch thread */ }
    }

    public void updateNavigationNextTurnEvent(String road, int turnSide, int event,
                                              int turnAngle, int turnNumber, int validFlag) {
        try {
            String r = road;
            if (r != null && r.length() > ROAD_MAX) r = r.substring(0, ROAD_MAX);
            record(KIND_TURN, turnSide, event, turnAngle, turnNumber, validFlag, r);
        } catch (Throwable t) { }
    }

    public void updateNavigationNextTurnDistance(int distanceMeters, int timeSeconds, int validFlag) {
        try { record(KIND_DIST, distanceMeters, timeSeconds, validFlag, 0, 0, null); }
        catch (Throwable t) { }
    }

    /* Non-nav: empty by design (passive tap only touches the 3 nav callbacks above). */
    public void videoFocusRequestNotification(int a, int b) { }
    public void videoAvailable(boolean a, int b) { }
    public void audioFocusRequestNotification(int a, int b) { }
    public void audioAvailable(int a, boolean b, int c) { }
    public void voiceSessionNotification(int a, int b) { }
    public void microphoneRequestNotification(int a, int b) { }
    public void updateCallState(CallState[] a, int b) { }
    public void updateTelephonyState(TelephonyState a, int b) { }
    public void updateNowPlayingData(TrackData a, int b) { }
    public void updatePlaybackState(PlaybackInfo a, int b) { }
    public void updatePlayposition(int a, int b) { }
    public void updateCoverArtUrl(ResourceLocator a, int b) { }
    public void setExternalDestination(double a, double b, String c, String d, int e) { }
    public void bluetoothPairingRequest(String a, int b) { }

    /* ================= ring buffer ================= */

    private static void record(int kind, int a1, int a2, int a3, int a4, int a5, String road) {
        synchronized (RING_LOCK) {
            int idx = (int) (totalSeen % CAPACITY);
            Slot s = RING[idx];
            s.seq = totalSeen;
            s.ts = System.currentTimeMillis();
            s.kind = kind;
            s.a1 = a1; s.a2 = a2; s.a3 = a3; s.a4 = a4; s.a5 = a5;
            s.road = road;
            totalSeen++;
            dirty = true;
        }
    }

    /* ================= shared writer + registration-retry thread =================
     * One daemon MIN_PRIORITY thread does both jobs: the FIRST registration attempt runs
     * immediately when this thread starts (start() itself never calls the registrar -- see
     * attemptRegistration()'s doc), before the thread ever sleeps. After that it ticks every
     * 5 s: retry a not-yet-successful registration (its own internal cadence backs off to
     * 60 s after 2 minutes; see maybeRetryTick()), and flush the ring to disk when dirty.
     * When there is nothing to do for either job the tick is just an idle wake-sleep,
     * never a busy loop. */

    private static void ensureWriterStartedLocked() {
        if (writerThread != null && writerThread.isAlive()) return;
        Thread t = new Thread(new Runnable() {
            public void run() { writerLoop(); }
        }, "aa-nav-tap-worker");
        t.setDaemon(true);
        try {
            t.setPriority(Thread.MIN_PRIORITY);
        } catch (Throwable ignored) { }
        try {
            t.start();
            writerThread = t;
        } catch (Throwable x) {
            writerThread = null;
            Log.w(TAG, "worker thread start failed: " + x);
        }
    }

    private static void writerLoop() {
        /* First registration attempt happens right here, before the first sleep -- this is
         * what makes it "immediate at thread start" rather than waiting up to 5 s for the
         * first tick. A no-op if already registered (there is nothing to attempt) or if
         * ENABLED/the off-switch/the cap say not to. */
        attemptRegistration();

        while (true) {
            try { Thread.sleep(WRITE_INTERVAL_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }

            maybeRetryTick();

            if (!writerEnabled) continue;

            boolean localDirty;
            synchronized (RING_LOCK) {
                localDirty = dirty;
                dirty = false;
            }
            if (!localDirty) continue;

            try {
                writeSnapshot();
                consecutiveFailures = 0;
            } catch (Throwable t) {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_WRITE_FAILURES) {
                    writerEnabled = false;
                    Log.w(TAG, "disabling file writer after " + consecutiveFailures
                        + " consecutive failures: " + t);
                }
            }
        }
    }

    /* Snapshot the ring under lock (cheap: primitive fields + road string refs only),
     * then do all file I/O outside the lock. Oldest-to-newest order. */
    private static void writeSnapshot() throws Exception {
        long seen;
        long dropped;
        int count;
        long[] seq; long[] ts; int[] kind;
        int[] a1; int[] a2; int[] a3; int[] a4; int[] a5;
        String[] road;

        synchronized (RING_LOCK) {
            seen = totalSeen;
            dropped = seen > CAPACITY ? seen - CAPACITY : 0;
            count = seen < CAPACITY ? (int) seen : CAPACITY;
            seq = new long[count]; ts = new long[count]; kind = new int[count];
            a1 = new int[count]; a2 = new int[count]; a3 = new int[count];
            a4 = new int[count]; a5 = new int[count]; road = new String[count];

            /* oldest slot is (seen - count) .. newest is (seen - 1) */
            long first = seen - count;
            for (int i = 0; i < count; i++) {
                int idx = (int) ((first + i) % CAPACITY);
                Slot s = RING[idx];
                seq[i] = s.seq; ts[i] = s.ts; kind[i] = s.kind;
                a1[i] = s.a1; a2[i] = s.a2; a3[i] = s.a3; a4[i] = s.a4; a5[i] = s.a5;
                road[i] = s.road;
            }
        }

        StringBuffer buf = new StringBuffer(64 + count * 96);
        buf.append("# aa_nav_tap v").append(VERSION)
           .append(" start=").append(startTimeMillis)
           .append(" events=").append(seen)
           .append(" dropped=").append(dropped)
           .append('\n');
        for (int i = 0; i < count; i++) {
            appendLine(buf, seq[i], ts[i], kind[i], a1[i], a2[i], a3[i], a4[i], a5[i], road[i]);
        }

        File tmp = new File(logPath + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp, false);
        PrintStream ps = new PrintStream(fos);
        try {
            ps.print(buf.toString());
        } finally {
            ps.close();
        }
        File target = new File(logPath);
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) {
                /* Never leave the .tmp behind: it would otherwise linger forever since this
                 * method always rewrites, never appends, so nothing else will ever clean it up. */
                try { tmp.delete(); } catch (Throwable ignored) { }
                throw new java.io.IOException("rename failed: " + tmp + " -> " + target);
            }
        }
    }

    private static void appendLine(StringBuffer buf, long seq, long ts, int kind,
                                   int a1, int a2, int a3, int a4, int a5, String road) {
        StringBuffer line = new StringBuffer(LINE_MAX);
        line.append('#').append(seq).append(" t=").append(ts).append(' ');
        if (kind == KIND_TURN) {
            line.append("TURN side=").append(turnSideName(a1)).append('(').append(a1).append(')')
                .append(" event=").append(turnEventName(a2)).append('(').append(a2).append(')')
                .append(" a3(turnAngle?)=").append(a3)
                .append(" a4(turnNumber?)=").append(a4)
                .append(" valid=").append(a5)
                .append(" road=\"").append(road == null ? "" : road).append('"');
        } else if (kind == KIND_DIST) {
            line.append("DIST a1(distanceMeters?)=").append(a1)
                .append(" a2(timeSeconds?)=").append(a2)
                .append(" valid=").append(a3);
        } else if (kind == KIND_FOCUS) {
            line.append("FOCUS a1=").append(a1).append(" a2=").append(a2);
        } else {
            line.append("UNKNOWN kind=").append(kind);
        }
        String s = line.toString();
        if (s.length() > LINE_MAX) s = s.substring(0, LINE_MAX);
        buf.append(s).append('\n');
    }

    private static String turnSideName(int v) {
        switch (v) {
            case 0: return "UNSPECIFIED";
            case 1: return "LEFT";
            case 2: return "RIGHT";
            default: return "UNKNOWN";
        }
    }

    private static String turnEventName(int v) {
        switch (v) {
            case 0: return "UNKNOWN";
            case 1: return "DEPART";
            case 2: return "NAME_CHANGE";
            case 3: return "SLIGHT_TURN";
            case 4: return "TURN";
            case 5: return "SHARP_TURN";
            case 6: return "U_TURN";
            case 7: return "ON_RAMP";
            case 8: return "OFF_RAMP";
            case 9: return "FORK";
            case 10: return "MERGE";
            case 11: return "ROUNDABOUT_ENTER";
            case 12: return "ROUNDABOUT_EXIT";
            case 13: return "ROUNDABOUT_ENTER_AND_EXIT";
            case 14: return "STRAIGHT";
            case 16: return "FERRY_BOAT";
            case 17: return "FERRY_TRAIN";
            case 19: return "DESTINATION";
            default: return "UNKNOWN";
        }
    }

    /* ================= package-private test hooks (tests/AndroidAutoNavTapTest.java,
     * declared in this same package -- never used by production code) ================= */

    static void setLogPathForTest(String path) { logPath = path; }

    static void flushForTest() throws Exception { writeSnapshot(); }

    static int consecutiveFailuresForTest() { return consecutiveFailures; }

    static boolean writerEnabledForTest() { return writerEnabled; }

    static long totalSeenForTest() {
        synchronized (RING_LOCK) { return totalSeen; }
    }

    static long droppedForTest() {
        synchronized (RING_LOCK) { return totalSeen > CAPACITY ? totalSeen - CAPACITY : 0; }
    }

    /* Note: writeSnapshot() failures increment/observe consecutiveFailures only through the
     * background writerLoop(); the test drives the same failure-counting logic deterministically
     * via this helper instead of racing the real 5s daemon. */
    static void simulateWriteAttemptForTest() {
        try {
            writeSnapshot();
            consecutiveFailures = 0;
        } catch (Throwable t) {
            consecutiveFailures++;
            if (consecutiveFailures >= MAX_WRITE_FAILURES) writerEnabled = false;
        }
    }

    /** Replaces the real DSI registrar with a fake so the retry/backoff logic in start()/
     *  maybeRetryTick() can be driven without any firmware types. */
    static void setRegistrarForTest(Registrar r) { registrar = r; }

    /** Points the runtime off-switch check at a throwaway path instead of /mnt/app/.... */
    static void setOffSwitchPathForTest(String path) { offSwitchPath = path; }

    /** Forces exactly one registration attempt right now, bypassing nextAttemptAtMillis --
     *  the real background tick still runs on its own schedule regardless. Calls
     *  attemptRegistration() directly (the real, unlocked-during-the-call path), never a
     *  locked shortcut, so this exercises exactly the same code the worker thread runs. */
    static void forceRetryAttemptForTest() {
        attemptRegistration();
    }

    /** Caps total attempts so the "hard cap stops retrying" test does not need 2000 real
     *  forced calls to observe it. */
    static void setMaxTotalAttemptsForTest(int n) { maxTotalAttempts = n; }

    static boolean registeredForTest() {
        synchronized (START_LOCK) { return registered; }
    }

    static int attemptsForTest() {
        synchronized (START_LOCK) { return attempts; }
    }

    /** The one shared daemon thread (writer + retry). Compare by identity across calls to
     *  confirm start()/retries never spawn a second one. */
    static Thread writerThreadForTest() { return writerThread; }

    static void resetForTest() {
        synchronized (RING_LOCK) {
            totalSeen = 0;
            dirty = false;
            for (int i = 0; i < CAPACITY; i++) {
                Slot s = RING[i];
                s.seq = 0; s.ts = 0; s.kind = 0;
                s.a1 = 0; s.a2 = 0; s.a3 = 0; s.a4 = 0; s.a5 = 0; s.road = null;
            }
        }
        consecutiveFailures = 0;
        writerEnabled = true;
        logPath = "/tmp/aa_nav_tap.log";

        /* Deliberately does NOT touch writerThread: it is a real daemon thread that cannot be
         * un-started, and nulling the field here while it is still alive would make the next
         * start() spawn a genuine second thread, which is exactly what these tests must not do.
         * The shared thread is harmless to leave running between test cases (it just re-reads
         * the fields below on its own 5 s tick). */
        synchronized (START_LOCK) {
            startCalled = false;
            registered = false;
            registration = null;
            heldContext = null;
            firstAttemptAtMillis = 0L;
            nextAttemptAtMillis = 0L;
            attempts = 0;
            warnedPhase1 = false;
            warnedPhase2 = false;
            registrar = new DefaultRegistrar();
        }
        offSwitchPath = DEFAULT_OFF_SWITCH_PATH;
    }
}
