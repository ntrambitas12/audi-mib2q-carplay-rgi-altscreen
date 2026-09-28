package com.luka.carplay.aa;

import de.audi.app.terminalmode.IContext;
import de.audi.app.terminalmode.osgi.IServiceManager;
import org.osgi.framework.ServiceRegistration;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Host test of the real AndroidAutoNavTap (ring buffer + rewrite-only file writer +
 * no-op non-nav callbacks), plus a static-reference audit over java_patch/ so no
 * other class defeats the reflective load-isolation this tap depends on.
 *
 * Declared in the tap's own package (com.luka.carplay.aa) -- like
 * com.luka.carplay.rgd.LaneGuidanceLifecycleTest is declared in com.luka.carplay.rgd --
 * so it can reach the package-private test hooks directly, without exposing them to
 * production callers.
 *
 * Never opens a real DSI service: the registration tests inject a fake Registrar
 * (see AndroidAutoNavTap.setRegistrarForTest), so start() only ever exercises the
 * retry/backoff/idempotency/non-blocking logic, never firmware types (the one
 * exception is testDefaultRegistrarReusesSameTapInstance, which unit-tests the real
 * DefaultRegistrar directly against a fake IContext/IServiceManager built from the
 * same minimal stubs gen_stubs.py generates for the static check).
 *
 * Thread-order dependency, by design: start() now only starts the shared worker
 * thread and returns -- the FIRST registration attempt happens asynchronously, on
 * that thread, immediately at thread start. Only the FIRST test in this file to call
 * start() actually creates that thread and observes that immediate attempt; every
 * later start()-calling test finds the thread already alive (idempotent: no second
 * thread, no automatic attempt) and drives attempts entirely through
 * forceRetryAttemptForTest(), which calls the exact same attemptRegistration() method
 * the worker thread uses. testStartIsNonBlockingAndRegistrarRunsOnWorkerThread is
 * therefore ordered first among the start()-calling tests, specifically so it is the
 * one that observes the real thread-creation + immediate-attempt behavior. All tests
 * run in well under the 5 s tick interval, so the real background tick never fires
 * during this process; the JVM exits before it would.
 */
public final class AndroidAutoNavTapTest {
    private static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }

    public static void main(String[] args) throws Exception {
        testRingOverflow();
        testFileRewriteBounded();
        testNullAndHugeRoadNeverThrow();
        testNonNavMethodsAreNoOps();
        testWriterDisablesAfterRepeatedFailures();
        testTmpCleanupOnRenameFailure();
        testDefaultRegistrarReusesSameTapInstance();
        testStartIsNonBlockingAndRegistrarRunsOnWorkerThread();
        testNullHandleSuccessRegistersOnce();
        testDoubleStartIsIdempotent();
        testKillSwitchPreventsAnyAttempt();
        testAttemptCapStopsRetrying();
        testStaticReferenceAudit();
        System.out.println("AndroidAutoNavTapTest: PASS");
    }

    /* ---- ring overflow: 1000 events -> exactly 256 retained, newest kept, dropped correct ---- */
    private static void testRingOverflow() throws Exception {
        AndroidAutoNavTap.resetForTest();
        File dir = mkTempDir();
        File log = new File(dir, "ring.log");
        AndroidAutoNavTap.setLogPathForTest(log.getAbsolutePath());

        AndroidAutoNavTap tap = new AndroidAutoNavTap();
        for (int i = 0; i < 1000; i++) {
            tap.updateNavigationNextTurnEvent("Road " + i, 1, 4, i, i, 1);
        }
        check(AndroidAutoNavTap.totalSeenForTest() == 1000, "totalSeen should be 1000, was "
            + AndroidAutoNavTap.totalSeenForTest());
        check(AndroidAutoNavTap.droppedForTest() == 1000 - 256, "dropped should be 744, was "
            + AndroidAutoNavTap.droppedForTest());

        AndroidAutoNavTap.flushForTest();
        List lines = readLines(log);
        // header + exactly 256 records
        check(lines.size() == 257, "expected header + 256 records, got " + lines.size());
        String firstRecord = (String) lines.get(1);
        String lastRecord = (String) lines.get(lines.size() - 1);
        check(firstRecord.startsWith("#744 "), "oldest retained record should be #744, was: " + firstRecord);
        check(lastRecord.startsWith("#999 "), "newest record should be #999, was: " + lastRecord);
        check(lastRecord.indexOf("road=\"Road 999\"") >= 0, "newest record lost its payload: " + lastRecord);

        rmTempDir(dir);
    }

    /* ---- file rewrite is bounded: <=257 lines and under a byte cap after 1000 events ---- */
    private static void testFileRewriteBounded() throws Exception {
        AndroidAutoNavTap.resetForTest();
        File dir = mkTempDir();
        File log = new File(dir, "bounded.log");
        AndroidAutoNavTap.setLogPathForTest(log.getAbsolutePath());

        AndroidAutoNavTap tap = new AndroidAutoNavTap();
        for (int i = 0; i < 1000; i++) {
            tap.updateNavigationNextTurnDistance(i, i, 1);
        }
        AndroidAutoNavTap.flushForTest();

        List lines = readLines(log);
        check(lines.size() <= 257, "file should have <= 257 lines, got " + lines.size());
        long bytes = log.length();
        // header (<300B) + up to 256 lines each capped at 300 chars -> well under 100000 bytes.
        check(bytes < 100000L, "file should stay under the byte cap, was " + bytes + " bytes");
        // no stray .tmp file left behind after a successful rewrite
        check(!new File(log.getAbsolutePath() + ".tmp").exists(), ".tmp file must not survive a successful rewrite");

        rmTempDir(dir);
    }

    /* ---- callbacks never throw on null road or a huge string ---- */
    private static void testNullAndHugeRoadNeverThrow() throws Exception {
        AndroidAutoNavTap.resetForTest();
        File dir = mkTempDir();
        AndroidAutoNavTap.setLogPathForTest(new File(dir, "huge.log").getAbsolutePath());

        AndroidAutoNavTap tap = new AndroidAutoNavTap();
        tap.updateNavigationNextTurnEvent(null, 1, 4, 0, 0, 1);
        StringBuffer huge = new StringBuffer(10000);
        for (int i = 0; i < 10000; i++) huge.append('x');
        tap.updateNavigationNextTurnEvent(huge.toString(), 2, 8, 5, 1, 1);

        check(AndroidAutoNavTap.totalSeenForTest() == 2, "both calls should have recorded, got "
            + AndroidAutoNavTap.totalSeenForTest());

        AndroidAutoNavTap.flushForTest();
        File log = new File(dir, "huge.log");
        List out = readLines(log);
        for (int i = 0; i < out.size(); i++) {
            String line = (String) out.get(i);
            check(line.length() <= 300, "every line must stay under the 300-char cap, was "
                + line.length() + ": " + line.substring(0, Math.min(60, line.length())));
        }

        rmTempDir(dir);
    }

    /* ---- the 14 non-nav methods are no-ops: no exception, no ring growth ---- */
    private static void testNonNavMethodsAreNoOps() throws Exception {
        AndroidAutoNavTap.resetForTest();
        AndroidAutoNavTap tap = new AndroidAutoNavTap();

        tap.videoFocusRequestNotification(1, 1);
        tap.videoAvailable(true, 1);
        tap.audioFocusRequestNotification(1, 1);
        tap.audioAvailable(1, true, 1);
        tap.voiceSessionNotification(1, 1);
        tap.microphoneRequestNotification(1, 1);
        tap.updateCallState(null, 1);
        tap.updateTelephonyState(null, 1);
        tap.updateNowPlayingData(null, 1);
        tap.updatePlaybackState(null, 1);
        tap.updatePlayposition(1, 1);
        tap.updateCoverArtUrl(null, 1);
        tap.setExternalDestination(1.0, 2.0, "a", "b", 1);
        tap.bluetoothPairingRequest("addr", 1);

        check(AndroidAutoNavTap.totalSeenForTest() == 0,
            "non-nav callbacks must never touch the ring, totalSeen=" + AndroidAutoNavTap.totalSeenForTest());
    }

    /* ---- writer disables itself after MAX_WRITE_FAILURES consecutive failures ---- */
    private static void testWriterDisablesAfterRepeatedFailures() throws Exception {
        AndroidAutoNavTap.resetForTest();
        // A path whose PARENT directory does not exist: FileOutputStream on the .tmp
        // sibling will fail every time, deterministically, without touching real /tmp.
        File dir = mkTempDir();
        File missingParent = new File(dir, "no/such/dir/aa_nav_tap.log");
        AndroidAutoNavTap.setLogPathForTest(missingParent.getAbsolutePath());

        AndroidAutoNavTap tap = new AndroidAutoNavTap();
        tap.updateNavigationNextTurnDistance(1, 1, 1); // ensure there is something to (fail to) write

        check(AndroidAutoNavTap.writerEnabledForTest(), "writer should start enabled");
        for (int i = 0; i < 5; i++) {
            AndroidAutoNavTap.simulateWriteAttemptForTest();
        }
        check(AndroidAutoNavTap.consecutiveFailuresForTest() >= 5,
            "expected >= 5 consecutive failures, got " + AndroidAutoNavTap.consecutiveFailuresForTest());
        check(!AndroidAutoNavTap.writerEnabledForTest(), "writer should disable itself after repeated failures");

        rmTempDir(dir);
    }

    /* ---- LOW fix: a rename that fails twice must not leave the .tmp file behind.
     * Forcing this deterministically: renaming a regular file onto an existing
     * NON-EMPTY directory always fails on both POSIX (EISDIR) and Windows, and
     * File.delete() on that same non-empty directory also fails (Java requires an
     * empty directory), so the second renameTo() attempt fails too -- the real
     * "both attempts failed" path this fix targets. ---- */
    private static void testTmpCleanupOnRenameFailure() throws Exception {
        AndroidAutoNavTap.resetForTest();
        File dir = mkTempDir();
        File target = new File(dir, "blocked.log");
        check(target.mkdir(), "setup: could not create blocking directory");
        check(new File(target, "blocker.txt").createNewFile(), "setup: could not create blocker file");
        AndroidAutoNavTap.setLogPathForTest(target.getAbsolutePath());

        AndroidAutoNavTap tap = new AndroidAutoNavTap();
        tap.updateNavigationNextTurnDistance(1, 1, 1);
        AndroidAutoNavTap.simulateWriteAttemptForTest();

        File tmp = new File(target.getAbsolutePath() + ".tmp");
        check(!tmp.exists(), "the .tmp file must not linger after a failed rename, found: " + tmp);
        check(AndroidAutoNavTap.consecutiveFailuresForTest() == 1,
            "the rename failure should count as exactly one write failure, got "
                + AndroidAutoNavTap.consecutiveFailuresForTest());

        rmTempDir(dir);
    }

    /* ---- MED-HIGH fix (c): DefaultRegistrar must register ONE preallocated tap instance,
     * the same one every attempt, never `new AndroidAutoNavTap()` per call. Unit-tests
     * DefaultRegistrar directly (package-private, so reachable from this same-package
     * test) against a fake IContext/IServiceManager -- no start()/thread involved at all,
     * so this test needs no ordering relative to the others. Also exercises the new
     * success semantics: registerDSIListener returning null is stored as-is (mirrors
     * SteeringWheelInputModule, which never null-checks that same call). ---- */
    private static void testDefaultRegistrarReusesSameTapInstance() throws Exception {
        final Object[] seenListeners = new Object[3];
        final int[] n = {0};
        IServiceManager fakeServiceManager = new IServiceManager() {
            public ServiceRegistration registerDSIListener(int instance, String ifaceName, Object listener) {
                seenListeners[n[0]] = listener;
                n[0]++;
                return null; /* success with a null handle -- must still count as success */
            }
        };
        IContext fakeContext = new IContext() {
            public IServiceManager getServiceManager() { return fakeServiceManager; }
        };

        AndroidAutoNavTap.DefaultRegistrar dr = new AndroidAutoNavTap.DefaultRegistrar();
        Object h1 = dr.attempt(fakeContext);
        Object h2 = dr.attempt(fakeContext);
        Object h3 = dr.attempt(fakeContext);

        check(h1 == null && h2 == null && h3 == null,
            "a null return from registerDSIListener must be returned as-is (success, null handle)");
        check(n[0] == 3, "expected exactly 3 calls into the fake service manager, got " + n[0]);
        check(seenListeners[0] != null, "the listener passed to registerDSIListener must not be null");
        check(seenListeners[0] == seenListeners[1] && seenListeners[1] == seenListeners[2],
            "DefaultRegistrar must register the SAME preallocated tap instance on every attempt, saw "
                + seenListeners[0] + ", " + seenListeners[1] + ", " + seenListeners[2]);
    }

    /* ---- HIGH fix (1): start() must do only cheap, non-blocking work and return
     * promptly; the FIRST registration attempt happens on the shared worker thread,
     * not the caller. A registrar that blocks on a latch lets this test observe, from
     * the calling (test) thread: start() returns immediately without invoking the
     * registrar itself; the registrar runs on a different thread; and a callback
     * (updateNavigationNextTurnEvent) still records into the ring while that
     * registration attempt is still blocked -- callbacks never contend with a
     * registration in progress. MUST run first among the start()-calling tests (see
     * the class comment): it is the one whose start() call actually creates the shared
     * thread and therefore triggers its immediate first attempt. ---- */
    private static void testStartIsNonBlockingAndRegistrarRunsOnWorkerThread() throws Exception {
        AndroidAutoNavTap.resetForTest();
        final Thread testThread = Thread.currentThread();
        final CountDownLatch registrarEntered = new CountDownLatch(1);
        final CountDownLatch releaseRegistrar = new CountDownLatch(1);
        final Thread[] registrarThread = new Thread[1];
        final boolean[] calledOnTestThread = {false};

        AndroidAutoNavTap.setRegistrarForTest(new AndroidAutoNavTap.Registrar() {
            public Object attempt(Object contextObj) throws Exception {
                registrarThread[0] = Thread.currentThread();
                if (Thread.currentThread() == testThread) calledOnTestThread[0] = true;
                registrarEntered.countDown();
                releaseRegistrar.await(5, TimeUnit.SECONDS);
                return "OK";
            }
        });

        long t0 = System.currentTimeMillis();
        AndroidAutoNavTap.start(new Object());
        long elapsed = System.currentTimeMillis() - t0;
        check(elapsed < 1000L, "start() must return promptly (non-blocking), took " + elapsed + "ms");
        check(!calledOnTestThread[0], "start() must not invoke the registrar on the calling thread");

        check(registrarEntered.await(5, TimeUnit.SECONDS),
            "the worker thread should have entered the registrar within 5s of start()");
        check(registrarThread[0] != null && registrarThread[0] != testThread,
            "the registrar must run on the shared worker thread, not the caller of start()");
        check(!AndroidAutoNavTap.registeredForTest(), "must not be registered while the attempt is still blocked");

        // While the registration attempt is blocked in the registrar, a callback on this
        // (the test/calling) thread must still record -- it must never contend with, or wait
        // on, a registration attempt in progress.
        AndroidAutoNavTap tap = new AndroidAutoNavTap();
        long before = AndroidAutoNavTap.totalSeenForTest();
        tap.updateNavigationNextTurnEvent("Blocked Rd", 1, 4, 0, 0, 1);
        check(AndroidAutoNavTap.totalSeenForTest() == before + 1,
            "a callback must still record while a registration attempt is blocked in the registrar");

        releaseRegistrar.countDown();
        long deadline = System.currentTimeMillis() + 5000L;
        while (!AndroidAutoNavTap.registeredForTest() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        check(AndroidAutoNavTap.registeredForTest(), "should register once the blocked registrar call returns");
    }

    /* ---- MED-HIGH fix (2): success = the registrar call returned without throwing,
     * whatever it returned -- a null handle is still a successful registration, is
     * stored as-is, and the registrar must never be called again afterward, across
     * any number of further (forced) retry ticks. By the time this test runs the
     * shared thread already exists (from the previous test) and is idling, so start()
     * here does not itself trigger an attempt -- every attempt in this test is driven
     * explicitly via forceRetryAttemptForTest(), deterministically, from this thread. ---- */
    private static void testNullHandleSuccessRegistersOnce() throws Exception {
        AndroidAutoNavTap.resetForTest();
        final int[] calls = {0};
        AndroidAutoNavTap.setRegistrarForTest(new AndroidAutoNavTap.Registrar() {
            public Object attempt(Object contextObj) {
                synchronized (calls) { calls[0]++; }
                return null; /* success with a null handle */
            }
        });

        AndroidAutoNavTap.start(new Object());
        check(!AndroidAutoNavTap.registeredForTest(), "must not be registered before any attempt has run");

        AndroidAutoNavTap.forceRetryAttemptForTest();
        check(AndroidAutoNavTap.registeredForTest(), "a null-returning (non-throwing) attempt must count as success");
        int callsAfterFirst;
        synchronized (calls) { callsAfterFirst = calls[0]; }
        check(callsAfterFirst == 1, "the registrar must be called exactly once, got " + callsAfterFirst);

        for (int i = 0; i < 5; i++) AndroidAutoNavTap.forceRetryAttemptForTest();
        int callsAfterForced;
        synchronized (calls) { callsAfterForced = calls[0]; }
        check(callsAfterForced == 1, "must never call the registrar again after a (null-handle) success, calls="
            + callsAfterForced);
    }

    /* ---- HIGH fix: a second start() call must never double-register or spawn a
     * second thread. ---- */
    private static void testDoubleStartIsIdempotent() throws Exception {
        AndroidAutoNavTap.resetForTest();
        final int[] calls = {0};
        AndroidAutoNavTap.setRegistrarForTest(new AndroidAutoNavTap.Registrar() {
            public Object attempt(Object contextObj) { calls[0]++; return "OK"; }
        });
        Thread before = AndroidAutoNavTap.writerThreadForTest();

        AndroidAutoNavTap.start(new Object());
        check(!AndroidAutoNavTap.registeredForTest(),
            "start() itself must never call the registrar (the shared thread already exists by now)");
        check(calls[0] == 0, "start() must not attempt registration on the calling thread, calls=" + calls[0]);

        AndroidAutoNavTap.forceRetryAttemptForTest();
        check(AndroidAutoNavTap.registeredForTest(), "should register after one forced attempt");
        check(calls[0] == 1, "expected exactly one registration attempt, got " + calls[0]);

        AndroidAutoNavTap.start(new Object()); // second call: startCalled already true, must no-op
        AndroidAutoNavTap.forceRetryAttemptForTest(); // already registered: must also no-op
        check(calls[0] == 1, "neither a second start() nor a post-success retry may call the registrar again, calls="
            + calls[0]);
        check(AndroidAutoNavTap.attemptsForTest() == 1,
            "the attempt counter must not advance past the one successful attempt, got "
                + AndroidAutoNavTap.attemptsForTest());

        Thread after = AndroidAutoNavTap.writerThreadForTest();
        check(before != null && before == after, "a second start() must not spawn a second thread");
    }

    /* ---- HIGH fix: the off-switch is checked before every attempt, not only the
     * first -- neither the worker thread's automatic attempt nor a forced retry may
     * ever reach the registrar while it is present. ---- */
    private static void testKillSwitchPreventsAnyAttempt() throws Exception {
        AndroidAutoNavTap.resetForTest();
        File dir = mkTempDir();
        File offSwitch = new File(dir, "aa_nav_tap_off");
        check(offSwitch.createNewFile(), "setup: could not create fake off-switch file");
        AndroidAutoNavTap.setOffSwitchPathForTest(offSwitch.getAbsolutePath());

        final int[] calls = {0};
        AndroidAutoNavTap.setRegistrarForTest(new AndroidAutoNavTap.Registrar() {
            public Object attempt(Object contextObj) { calls[0]++; return "OK"; }
        });

        AndroidAutoNavTap.start(new Object());
        check(calls[0] == 0, "the off-switch must prevent the registrar from ever being called, calls=" + calls[0]);
        check(!AndroidAutoNavTap.registeredForTest(), "must not register while the off-switch is present");

        AndroidAutoNavTap.forceRetryAttemptForTest();
        check(calls[0] == 0, "the off-switch must still be checked on every retry, calls=" + calls[0]);
        check(!AndroidAutoNavTap.registeredForTest(), "must still not be registered");

        rmTempDir(dir);
    }

    /* ---- MED-HIGH fix: a hard cap backstops the retry schedule. Overrides the cap to a
     * small number via a test hook so this does not need thousands of real attempts. ---- */
    private static void testAttemptCapStopsRetrying() throws Exception {
        AndroidAutoNavTap.resetForTest();
        AndroidAutoNavTap.setMaxTotalAttemptsForTest(3);
        final int[] calls = {0};
        AndroidAutoNavTap.setRegistrarForTest(new AndroidAutoNavTap.Registrar() {
            public Object attempt(Object contextObj) throws Exception {
                synchronized (calls) { calls[0]++; }
                throw new AndroidAutoNavTap.Registrar.NotReady("never ready, for the cap test");
            }
        });

        AndroidAutoNavTap.start(new Object());
        for (int i = 0; i < 10; i++) AndroidAutoNavTap.forceRetryAttemptForTest();

        check(AndroidAutoNavTap.attemptsForTest() == 3,
            "attempts must stop exactly at the cap, got " + AndroidAutoNavTap.attemptsForTest());
        int callsSeen;
        synchronized (calls) { callsSeen = calls[0]; }
        check(callsSeen == 3, "the registrar must never be called past the cap, calls=" + callsSeen);
        check(!AndroidAutoNavTap.registeredForTest(), "must remain unregistered when the cap is hit without success");
        check(AndroidAutoNavTap.writerEnabledForTest(), "the writer must keep running after the cap is reached");
    }

    /* ---- static-reference audit: nothing in java_patch outside com/luka/carplay/aa
     *      references AndroidAutoNavTap except through the reflective Class.forName
     *      string. A direct import/reference would defeat the load isolation that
     *      keeps a firmware image missing org.dsi.ifc.androidauto2 from affecting
     *      CarPlay class loading. ---- */
    private static void testStaticReferenceAudit() throws Exception {
        File javaPatch = findJavaPatchDir();
        if (javaPatch == null) {
            System.out.println("AndroidAutoNavTapTest: static-reference audit SKIPPED (java_patch/ not found from "
                + new File(".").getAbsolutePath() + ")");
            return;
        }
        List offenders = new ArrayList();
        scan(javaPatch, offenders);
        check(offenders.isEmpty(), "files outside com/luka/carplay/aa must reference AndroidAutoNavTap only "
            + "through Class.forName(\"com.luka.carplay.aa.AndroidAutoNavTap\"), offenders: " + offenders);
    }

    private static void scan(File dir, List offenders) throws Exception {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (int i = 0; i < children.length; i++) {
            File f = children[i];
            if (f.isDirectory()) { scan(f, offenders); continue; }
            if (!f.getName().endsWith(".java")) continue;
            String path = f.getAbsolutePath().replace('\\', '/');
            boolean inTapPackage = path.indexOf("/com/luka/carplay/aa/") >= 0;
            if (inTapPackage) continue;
            // Doc comments are allowed to name the class (this file's own header does,
            // and so does TerminalModeBapCombi's registration comment); only CODE
            // references outside the quoted reflective string are offenses.
            String code = stripComments(readFile(f));
            int idx = code.indexOf("AndroidAutoNavTap");
            while (idx >= 0) {
                int lineStart = code.lastIndexOf('\n', idx) + 1;
                int lineEnd = code.indexOf('\n', idx);
                if (lineEnd < 0) lineEnd = code.length();
                String line = code.substring(lineStart, lineEnd);
                if (line.indexOf("\"com.luka.carplay.aa.AndroidAutoNavTap\"") < 0) {
                    offenders.add(f.getAbsolutePath() + ": " + line.trim());
                }
                idx = code.indexOf("AndroidAutoNavTap", idx + 1);
            }
        }
    }

    private static File findJavaPatchDir() {
        String override = System.getenv("PROJECT_DIR");
        if (override != null) {
            File d = new File(override, "java_patch");
            if (d.isDirectory()) return d;
        }
        File dir = new File(System.getProperty("user.dir", "."));
        for (int i = 0; i < 6 && dir != null; i++) {
            File candidate = new File(dir, "java_patch");
            if (candidate.isDirectory()) return candidate;
            dir = dir.getParentFile();
        }
        return null;
    }

    /* ---- small file helpers (host-only; never used by production code) ---- */
    private static File mkTempDir() throws Exception {
        File base = File.createTempFile("aa_nav_tap_test", "");
        base.delete();
        base.mkdirs();
        return base;
    }

    private static void rmTempDir(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (int i = 0; i < children.length; i++) rmTempDir(children[i]);
        }
        dir.delete();
    }

    private static List readLines(File f) throws Exception {
        List out = new ArrayList();
        BufferedReader r = new BufferedReader(new FileReader(f));
        try {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        } finally {
            r.close();
        }
        return out;
    }

    /* Strip // line comments and /* block comments *  /  (not string-literal aware,
     * but this codebase does not put "AndroidAutoNavTap" inside a string literal
     * except the one reflective Class.forName call, which this audit is checking for). */
    private static String stripComments(String text) {
        StringBuffer out = new StringBuffer(text.length());
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                while (i < n && text.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
                    if (text.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String readFile(File f) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(f));
        StringBuffer b = new StringBuffer((int) f.length());
        try {
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
        } finally {
            r.close();
        }
        return b.toString();
    }
}
