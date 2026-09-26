import com.luka.carplay.core.ScreenModule;
import com.luka.carplay.framework.Log;
import com.luka.carplay.rgd.BAPBridge;
import com.luka.carplay.rgd.RendererMapper;
import com.luka.carplay.rgd.RendererServer;
import com.luka.carplay.rgd.RouteGuidance;
import de.audi.atip.interapp.combi.bap.navi.CombiBAPServiceNavi;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Layer 5: Visual Renderer, Physical Context, and Route Lifecycle Integration Tests.
 *
 * Tests the interaction between BAPBridge, RendererServer, ScreenModule, and RouteGuidance:
 * - Renderer clear and re-render during reroutes
 * - Stale FRAME_READY rejection across reroutes
 * - Renderer failure and self-healing recovery
 * - Highway <-> City threshold transitions without generation changes
 * - Same-index version updates inside approach zone
 * - 100 consecutive rapid route sessions
 * - Disconnect / reconnect state isolation with identical generation IDs
 */
public final class RouteContextRendererLifecycleTest {

    private static int checks = 0;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) {
            throw new AssertionError("CHECK FAILED: " + label);
        }
    }

    private static final class TestRebindCollector implements ScreenModule.RebindListener {
        final List reasons = new ArrayList();

        public void onClusterContextRebindRequested(String reason) {
            reasons.add(reason != null ? reason : "");
        }

        int count() { return reasons.size(); }
        void clear() { reasons.clear(); }
    }

    private static final class MockRenderer extends RendererServer {
        int clears = 0;
        int maneuvers = 0;
        int progressCalls = 0;
        int lastIcon = -1;
        int lastExitAngle = 0;
        boolean failNextSend = false;

        public boolean sendClear() {
            clears++;
            return true;
        }

        public boolean sendBapProgressManeuver(int icon, int direction, int exitAngle,
                int drivingSide, int[] junctionAngles, int level, int mode, int perspective,
                boolean refresh, boolean snapToRoad, int progressState) {
            if (failNextSend) {
                return false;
            }
            maneuvers++;
            lastIcon = icon;
            lastExitAngle = exitAngle;
            return true;
        }

        public boolean sendProgress(int level, int mode, int progressState) {
            if (failNextSend) {
                return false;
            }
            progressCalls++;
            return true;
        }
    }

    private static final class BapProxyHandler implements InvocationHandler {
        public Object invoke(Object proxy, Method method, Object[] args) {
            Class type = method.getReturnType();
            if (type == Boolean.TYPE) return Boolean.FALSE;
            if (type == Integer.TYPE) return new Integer(0);
            return null;
        }
    }

    private static void setField(Class clazz, Object target, String name, Object val) throws Exception {
        Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, val);
    }

    private static BAPBridge createBridge(MockRenderer renderer) throws Exception {
        BAPBridge bridge = new BAPBridge();
        CombiBAPServiceNavi service = (CombiBAPServiceNavi) Proxy.newProxyInstance(
            RouteContextRendererLifecycleTest.class.getClassLoader(),
            new Class[]{CombiBAPServiceNavi.class},
            new BapProxyHandler());
        setField(BAPBridge.class, bridge, "appConnectorNavi", service);
        setField(BAPBridge.class, bridge, "initialized", Boolean.TRUE);
        setField(BAPBridge.class, bridge, "bapSessionStarted", Boolean.TRUE);
        if (renderer != null) {
            setField(BAPBridge.class, bridge, "rendererClient", renderer);
            setField(BAPBridge.class, bridge, "customRendererStarted", Boolean.TRUE);
        }
        return bridge;
    }

    private static RouteGuidance.State createState(long gen, int routeState, long stateGen,
                                                  int mType, int angle, int distM, int ver) {
        RouteGuidance.State s = new RouteGuidance.State();
        s.routeGeneration = gen;
        s.routeState = routeState;
        s.routeStateGeneration = stateGen;
        s.maneuverCount = 1;
        s.maneuverOrder = new int[]{0};
        s.mType[0] = mType;
        s.mTurnAngle[0] = angle;
        s.mTurnAnglePresent[0] = (angle != -1);
        s.mJunctionType[0] = 0; // intersection
        s.mDistance[0] = distM;
        s.mVer[0] = ver;
        s.distManeuverM = distM;
        s.markAllDirtyForReplay();
        return s;
    }

    public static void main(String[] args) throws Exception {
        Log.setLevel(-1);

        TestRebindCollector collector = new TestRebindCollector();
        ScreenModule.setRebindListener(collector);

        Field naf = ScreenModule.class.getDeclaredField("navActive");
        naf.setAccessible(true);
        Field cnf = ScreenModule.class.getDeclaredField("connected");
        cnf.setAccessible(true);
        cnf.setBoolean(null, true);

        // ============================================================
        // Test 1: Full Visual & Context Lifecycle
        // ============================================================
        {
            MockRenderer renderer = new MockRenderer();
            BAPBridge bridge = createBridge(renderer);
            collector.clear();
            naf.setBoolean(null, false);

            // 1. Cruising outside approach at 500m -> ctx 74, renderer not active
            RouteGuidance.State sFar = createState(100L, 1, 100L, 1, -90, 500, 1);
            bridge.update(sFar);
            check(!ScreenModule.isNavActive(), "T1.1: cruising at 500m outside approach");
            check(renderer.maneuvers == 0, "T1.1: no maneuver sent to renderer outside approach");

            // 2. Approach zone entry at 200m -> ctx 80, renderer receives left turn
            RouteGuidance.State sNear = createState(100L, 1, 100L, 1, -90, 200, 1);
            bridge.update(sNear);
            check(ScreenModule.isNavActive(), "T1.2: enters approach at 200m");
            check(renderer.maneuvers == 1, "T1.2: renderer received maneuver");
            check(renderer.lastExitAngle < 0, "T1.2: left turn exit angle");

            // 3. Reroute occurs -> drops to ctx 74, renderer sent clear
            RouteGuidance.State sReroute = createState(100L, 5, 100L, 1, -90, 200, 1);
            bridge.update(sReroute);
            check(!ScreenModule.isNavActive(), "T1.3: reroute exits approach");
            check(renderer.clears == 1, "T1.3: renderer received sendClear()");

            // 4. New route at 483m (~0.3 mi) -> unauthenticated state, stays 74
            RouteGuidance.State sNewFar = createState(101L, 5, -1L, 2, 90, 483, 1);
            bridge.update(sNewFar);
            check(!ScreenModule.isNavActive(), "T1.4: 483m stays in ctx 74");
            check(renderer.clears == 1, "T1.4: renderer still in clear state");

            // 5. New route enters approach at 200m -> ctx 80, renderer receives right turn
            RouteGuidance.State sNewNear = createState(101L, 1, 101L, 2, 90, 200, 1);
            bridge.update(sNewNear);
            check(ScreenModule.isNavActive(), "T1.5: 200m enters approach");
            check(renderer.maneuvers == 2, "T1.5: renderer received new maneuver");
            check(renderer.lastExitAngle > 0, "T1.5: right turn exit angle painted");
        }

        // ============================================================
        // Test 2: Stale FRAME_READY Rejection Across Reroute
        // ============================================================
        {
            RendererServer realServer = new RendererServer();
            setField(RendererServer.class, realServer, "running", Boolean.TRUE);
            setField(RendererServer.class, realServer, "out", new java.io.ByteArrayOutputStream());

            Method onEvt = RendererServer.class.getDeclaredMethod("handleRendererEvent",
                new Class[]{java.net.Socket.class, Byte.TYPE});
            onEvt.setAccessible(true);
            Field frameReadyF = RendererServer.class.getDeclaredField("frameReady");
            frameReadyF.setAccessible(true);

            // Establish frame ready on active route
            onEvt.invoke(realServer, new Object[]{null, new Byte((byte) 0x82)}); // EVT_FRAME_READY
            check(frameReadyF.getBoolean(realServer), "T2: initial frameReady is true");

            // Reroute triggers sendClear()
            realServer.sendClear();
            check(!frameReadyF.getBoolean(realServer), "T2: sendClear resets frameReady to false");

            // Stale EVT_FRAME_READY arrives from the old frame while clear is pending
            onEvt.invoke(realServer, new Object[]{null, new Byte((byte) 0x82)});
            check(!frameReadyF.getBoolean(realServer), "T2: stale FRAME_READY rejected while clear pending!");

            // EVT_FRAME_CLEARED acknowledges clear
            onEvt.invoke(realServer, new Object[]{null, new Byte((byte) 0x83)}); // EVT_FRAME_CLEARED
            check(!frameReadyF.getBoolean(realServer), "T2: frameCleared complete");

            // Now new route's FRAME_READY arrives and is accepted
            onEvt.invoke(realServer, new Object[]{null, new Byte((byte) 0x82)});
            check(frameReadyF.getBoolean(realServer), "T2: new route FRAME_READY accepted");
        }

        // ============================================================
        // Test 3: Renderer Send Failure and Self-Healing Recovery
        // ============================================================
        {
            MockRenderer renderer = new MockRenderer();
            BAPBridge bridge = createBridge(renderer);
            collector.clear();
            naf.setBoolean(null, false);

            // Simulate broken pipe on renderer
            renderer.failNextSend = true;
            RouteGuidance.State sNear = createState(200L, 1, 200L, 1, -90, 200, 1);
            bridge.update(sNear);

            // Renderer send failed
            check(renderer.maneuvers == 0, "T3: send failed as simulated");

            // Link heals, next delta replays
            renderer.failNextSend = false;
            sNear.markAllDirtyForReplay();
            bridge.update(sNear);
            check(renderer.maneuvers == 1, "T3: replayed maneuver delivered to recovered renderer");
            check(renderer.lastExitAngle < 0, "T3: correct maneuver geometry restored");
        }

        // ============================================================
        // Test 4: Primary Maneuver Update Inside Approach (Version Change)
        // ============================================================
        {
            MockRenderer renderer = new MockRenderer();
            BAPBridge bridge = createBridge(renderer);
            collector.clear();
            naf.setBoolean(null, true); // Active in 80

            // Initial left turn at 200m (version 1)
            RouteGuidance.State s1 = createState(300L, 1, 300L, 1, -90, 200, 1);
            bridge.update(s1);
            check(renderer.maneuvers == 1, "T4.1: first maneuver rendered");
            check(renderer.lastExitAngle < 0, "T4.1: left turn exit angle");
            check(collector.count() == 1, "T4.1: generation 300 entry executes its one rebind");

            // Consecutive turn at 180m: right turn with version 2 (same generation 300)
            RouteGuidance.State s2 = createState(300L, 1, 300L, 2, 90, 180, 2);
            bridge.update(s2);
            check(renderer.maneuvers == 2, "T4.2: updated maneuver rendered");
            check(renderer.lastExitAngle > 0, "T4.2: right turn exit angle updated");
            check(collector.count() == 1, "T4.2: ZERO additional context rebinds for same-route maneuver update");
        }

        // ============================================================
        // Test 5: Highway <-> City Maneuver Replacement at 1000m
        // ============================================================
        {
            MockRenderer renderer = new MockRenderer();
            BAPBridge bridge = createBridge(renderer);
            collector.clear();
            naf.setBoolean(null, false);

            // 1. Highway ramp at 1000m -> within 1600m threshold -> approaches in 80
            RouteGuidance.State sHighway = createState(400L, 1, 400L, 8, 45, 1000, 1); // MT_OFF_RAMP (8)
            bridge.update(sHighway);
            check(ScreenModule.isNavActive(), "T5.1: 1000m highway ramp enters approach");

            // 2. Primary maneuver becomes city turn at 1000m -> outside 305m city threshold -> exits to 74
            RouteGuidance.State sCity = createState(400L, 1, 400L, 1, -90, 1000, 2); // MT_LEFT_TURN (1)
            bridge.update(sCity);
            check(!ScreenModule.isNavActive(), "T5.2: 1000m city turn exits approach (drops to 74)");

            // 3. Cruising reaches 305m -> enters city approach
            RouteGuidance.State sCityNear = createState(400L, 1, 400L, 1, -90, 305, 2);
            bridge.update(sCityNear);
            check(ScreenModule.isNavActive(), "T5.3: 305m city turn re-enters approach");
        }

        // ============================================================
        // Test 6: 100 Consecutive Rapid Route Sessions
        // ============================================================
        {
            MockRenderer renderer = new MockRenderer();
            BAPBridge bridge = createBridge(renderer);

            for (int session = 0; session < 100; session++) {
                collector.clear();
                naf.setBoolean(null, false);
                long gen = 1000L + session;

                // Route start + approach
                RouteGuidance.State sStart = createState(gen, 1, gen, 1, -90, 200, 1);
                bridge.update(sStart);
                check(ScreenModule.isNavActive(), "T6: session " + session + " start enters approach");

                // Reroute
                RouteGuidance.State sReroute = createState(gen, 5, gen, 1, -90, 200, 1);
                bridge.update(sReroute);
                check(!ScreenModule.isNavActive(), "T6: session " + session + " reroute exits approach");

                // Route end
                bridge.onRouteEnd();
                check(!ScreenModule.isNavActive(), "T6: session " + session + " route end inactive");
            }
        }

        // ============================================================
        // Test 7: Disconnect / Reconnect with Identical Generation ID
        // ============================================================
        {
            MockRenderer renderer = new MockRenderer();
            BAPBridge bridge = createBridge(renderer);
            collector.clear();
            naf.setBoolean(null, false);

            // Session 1: gen 500 in approach
            RouteGuidance.State s1 = createState(500L, 1, 500L, 1, -90, 200, 1);
            bridge.update(s1);
            check(ScreenModule.isNavActive(), "T7: session 1 enters approach");

            // Disconnect
            bridge.onStop();
            bridge.onShutdown();
            ScreenModule.setNavActive(false);

            // Session 2 connects with identical gen 500 at 200m
            bridge.onStart();
            collector.clear();
            RouteGuidance.State s2 = createState(500L, 1, 500L, 1, -90, 200, 1);
            bridge.update(s2);

            // Must enter approach cleanly without stale state from session 1
            check(ScreenModule.isNavActive(), "T7: session 2 connects cleanly at gen 500");
            check(collector.count() == 0, "T7: clean 74->80 entry, no stale rebind from session 1");
        }

        System.out.println("RouteContextRendererLifecycleTest: ALL 7 RENDERER/LIFECYCLE SUITES PASS (" + checks + " checks)");
    }
}
