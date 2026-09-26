import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.cluster.ClusterLayerController;
import com.luka.carplay.core.ScreenModule;
import com.luka.carplay.framework.Log;
import com.luka.carplay.rgd.BAPBridge;
import com.luka.carplay.rgd.RendererServer;
import com.luka.carplay.rgd.RouteGuidance;
import de.audi.atip.interapp.combi.bap.navi.CombiBAPServiceNavi;
import de.audi.atip.hmi.view.IDisplayManager;
import de.audi.tghu.fwhmi.IDisplayManagerKombiControl;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end integration tests connecting raw hook/iOS string deltas through:
 *   CarplayBus.parseText()
 *     -> RouteGuidance.parse()
 *     -> RouteGuidance.State dirty bits
 *     -> BAPBridge.update()
 *     -> ScreenModule (74 vs 80 context + rebinds)
 *     -> ClusterLayerController (opacity)
 *     -> RendererServer (maneuvers and clears)
 */
public final class RouteContextDeltaIntegrationTest {

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

        int count() {
            return reasons.size();
        }

        void clear() {
            reasons.clear();
        }

        String lastReason() {
            return reasons.isEmpty() ? null : (String) reasons.get(reasons.size() - 1);
        }
    }

    private static final class MockRenderer extends RendererServer {
        int clears = 0;
        int maneuvers = 0;
        int lastIcon = -1;
        int lastDirection = -1;
        int lastExitAngle = 0;
        int progressCalls = 0;

        public boolean sendClear() {
            clears++;
            return true;
        }

        public boolean sendBapProgressManeuver(int icon, int direction, int exitAngle,
                int drivingSide, int[] junctionAngles, int level, int mode, int perspective,
                boolean refresh, boolean snapToRoad, int progressState) {
            maneuvers++;
            lastIcon = icon;
            lastDirection = direction;
            lastExitAngle = exitAngle;
            return true;
        }

        public boolean sendProgress(int level, int mode, int progressState) {
            progressCalls++;
            return true;
        }
    }

    private static final class MockDisplayManagerHandler implements InvocationHandler {
        final Map opacity = new HashMap();

        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("setOpacity")) {
                opacity.put(args[0], args[2]);
            }
            Class type = method.getReturnType();
            if (type == Boolean.TYPE) return Boolean.FALSE;
            if (type == Integer.TYPE) return Integer.valueOf(0);
            return null;
        }

        int opacity(int id) {
            Integer val = (Integer) opacity.get(Integer.valueOf(id));
            return val != null ? val.intValue() : 0;
        }
    }

    private static final class BapProxyHandler implements InvocationHandler {
        public Object invoke(Object proxy, Method method, Object[] args) {
            Class type = method.getReturnType();
            if (type == Boolean.TYPE) return Boolean.FALSE;
            if (type == Integer.TYPE) return Integer.valueOf(0);
            return null;
        }
    }

    private static void setField(Class clazz, Object target, String name, Object val) throws Exception {
        Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, val);
    }

    private static Object getField(Class clazz, Object target, String name) throws Exception {
        Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static BAPBridge createBridge(MockRenderer renderer) throws Exception {
        BAPBridge bridge = new BAPBridge();
        CombiBAPServiceNavi service = (CombiBAPServiceNavi) Proxy.newProxyInstance(
            RouteContextDeltaIntegrationTest.class.getClassLoader(),
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

    private static void feed(RouteGuidance rg, Method parseMethod, RouteGuidance.State state,
                             BAPBridge bridge, String text) throws Exception {
        byte[] bytes = text.getBytes("UTF-8");
        CarplayBus.Data d = CarplayBus.parseText(bytes, bytes.length);
        parseMethod.invoke(rg, new Object[]{d});
        boolean published = bridge.update(state);
        if (published) {
            state.clearDirty();
        }
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

        Method parseMethod = RouteGuidance.class.getDeclaredMethod("parse", new Class[]{CarplayBus.Data.class});
        parseMethod.setAccessible(true);

        MockDisplayManagerHandler dmHandler = new MockDisplayManagerHandler();
        IDisplayManagerKombiControl dm = (IDisplayManagerKombiControl) Proxy.newProxyInstance(
            RouteContextDeltaIntegrationTest.class.getClassLoader(),
            new Class[]{IDisplayManagerKombiControl.class},
            dmHandler);
        ClusterLayerController.bind(dm, 1);
        ClusterLayerController.onVcPresentation(true);
        ClusterLayerController.onVcVisibility(false); // No KDK hide delay in standard context tests

        // ============================================================
        // Suite 1: Fragmented Reroute Sequence A (Delayed maneuver delivery, 0.3 mi / 483 m)
        // ============================================================
        {
            MockRenderer renderer = new MockRenderer();
            BAPBridge bridge = createBridge(renderer);
            RouteGuidance rg = new RouteGuidance();
            RouteGuidance.State state = (RouteGuidance.State) getField(RouteGuidance.class, rg, "state");
            collector.clear();
            naf.setBoolean(null, false);

            // Step 1: Initial active route at 200m
            feed(rg, parseMethod, state, bridge,
                "source_supports_rg:n:1\n" +
                "route_generation:n:100\n" +
                "route_state:n:1\n" +
                "maneuver_count:n:1\n" +
                "maneuver_list:s:0\n" +
                "m0_type:n:1\n" +
                "dist_maneuver_m:n:200\n");
            check(ScreenModule.isNavActive(), "S1.1: 200m enters approach (ctx 80)");
            check(collector.count() == 0, "S1.1: initial entry is clean 74->80, no rebind");
            check(renderer.maneuvers >= 1, "S1.1: renderer received maneuver");

            // Step 2: Route state 5 (rerouting begins)
            feed(rg, parseMethod, state, bridge, "route_state:n:5\n");
            check(!ScreenModule.isNavActive(), "S1.2: rerouting suppresses navActive (drops to 74)");
            check(renderer.clears >= 1, "S1.2: rerouting sent clear to renderer");
            check(collector.count() == 0, "S1.2: reroute entry triggers no rebind");

            // Step 3: New generation 101 arrives in isolated delta (no maneuver or distance yet)
            feed(rg, parseMethod, state, bridge, "route_generation:n:101\n");
            check(state.routeGeneration == 101, "S1.3: generation bumped to 101");
            check(state.routeState == 5, "S1.3: numeric routeState retained 5");
            check(state.routeStateGeneration == -1L, "S1.3: routeStateGeneration invalidated to -1");
            check(!ScreenModule.isNavActive(), "S1.3: still 74");
            check(collector.count() == 0, "S1.3: no premature rebind without maneuver");

            // Step 4..6: Fragmented maneuver headers arrive
            feed(rg, parseMethod, state, bridge, "maneuver_count:n:1\n");
            feed(rg, parseMethod, state, bridge, "maneuver_list:s:0\n");
            feed(rg, parseMethod, state, bridge, "m0_type:n:1\n");
            check(collector.count() == 0, "S1.4: fragmented parts trigger no rebind");

            // Step 7: Distance 483m (~0.3 mi) arrives -> resolved OUTSIDE approach
            feed(rg, parseMethod, state, bridge, "dist_maneuver_m:n:483\n");
            check(!ScreenModule.isNavActive(), "S1.7: 483m stays in ctx 74");
            check(collector.count() == 0, "S1.7: 483m cancels pending rebind permanently, zero rebinds");

            // Step 8: Travel toward turn outside approach: 400m, 350m, 306m
            feed(rg, parseMethod, state, bridge, "dist_maneuver_m:n:400\n");
            check(!ScreenModule.isNavActive() && collector.count() == 0, "S1.8: 400m stays 74, no rebind");
            feed(rg, parseMethod, state, bridge, "dist_maneuver_m:n:350\n");
            check(!ScreenModule.isNavActive() && collector.count() == 0, "S1.8: 350m stays 74, no rebind");
            feed(rg, parseMethod, state, bridge, "dist_maneuver_m:n:306\n");
            check(!ScreenModule.isNavActive() && collector.count() == 0, "S1.8: 306m stays 74, no rebind");

            // Step 9: Reach 305m (city approach threshold)
            feed(rg, parseMethod, state, bridge, "dist_maneuver_m:n:305\n");
            check(ScreenModule.isNavActive(), "S1.9: 305m enters approach (ctx 80)");
            check(collector.count() == 0, "S1.9: clean 74->80 acquisition, ZERO forced rebinds!");
        }

        // ============================================================
        // Suite 2: Fragmented Reroute Sequence B (Inverted field ordering, 200 m approach)
        // ============================================================
        {
            MockRenderer renderer = new MockRenderer();
            BAPBridge bridge = createBridge(renderer);
            RouteGuidance rg = new RouteGuidance();
            RouteGuidance.State state = (RouteGuidance.State) getField(RouteGuidance.class, rg, "state");
            collector.clear();
            ClusterLayerController.onVcVisibility(true);
            naf.setBoolean(null, true); // Vehicle currently in ctx 80 with KDK visible

            // Establish baseline generation 101 at 200m
            feed(rg, parseMethod, state, bridge,
                "route_generation:n:101\n" +
                "route_state:n:1\n" +
                "maneuver_count:n:1\n" +
                "maneuver_list:s:0\n" +
                "m0_type:n:1\n" +
                "dist_maneuver_m:n:200\n");
            collector.clear();

            // Reverse order for generation 102:
            // 1. Generation arrives
            feed(rg, parseMethod, state, bridge, "route_generation:n:102\n");
            check(collector.count() == 0, "S2.1: generation alone does not rebind");

            // 2. Maneuver type
            feed(rg, parseMethod, state, bridge, "m0_type:n:1\n");
            check(collector.count() == 0, "S2.2: type alone does not rebind");

            // 3. Maneuver list
            feed(rg, parseMethod, state, bridge, "maneuver_list:s:0\n");
            check(collector.count() == 0, "S2.3: list alone does not rebind");

            // 4. Distance 200m completes primary maneuver criteria
            feed(rg, parseMethod, state, bridge, "dist_maneuver_m:n:200\n");
            check(collector.count() == 1, "S2.4: distance completes approach criteria -> exactly 1 rebind");
            check(collector.lastReason().indexOf("102") >= 0, "S2.4: rebind reason identifies gen 102");

            // 5. Maneuver count arrives -> must not re-trigger rebind
            feed(rg, parseMethod, state, bridge, "maneuver_count:n:1\n");
            check(collector.count() == 1, "S2.5: subsequent count delta does NOT re-trigger rebind");
            ClusterLayerController.onVcVisibility(false);
        }

        // ============================================================
        // Suite 3: 0.3-Mile Boundary Hysteresis with Unit Equivalence
        // ============================================================
        {
            BAPBridge bridge = createBridge(null);
            RouteGuidance rg = new RouteGuidance();
            RouteGuidance.State state = (RouteGuidance.State) getField(RouteGuidance.class, rg, "state");
            collector.clear();
            naf.setBoolean(null, false);

            feed(rg, parseMethod, state, bridge,
                "route_generation:n:110\n" +
                "route_state:n:1\n" +
                "maneuver_count:n:1\n" +
                "maneuver_list:s:0\n" +
                "m0_type:n:1\n" +
                "dist_maneuver_m:n:484\n");
            check(!ScreenModule.isNavActive(), "S3: 484m outside approach");

            feed(rg, parseMethod, state, bridge, "dist_maneuver_m:n:483\n");
            check(!ScreenModule.isNavActive(), "S3: 483m (~0.30 mi) outside approach");

            feed(rg, parseMethod, state, bridge, "dist_maneuver_m:n:482\n");
            check(!ScreenModule.isNavActive(), "S3: 482m outside approach");

            feed(rg, parseMethod, state, bridge, "dist_maneuver_m:n:306\n");
            check(!ScreenModule.isNavActive(), "S3: 306m just outside city threshold");

            feed(rg, parseMethod, state, bridge, "dist_maneuver_m:n:305\n");
            check(ScreenModule.isNavActive(), "S3: 305m (1000 ft) enters city approach");

            // Hysteresis buffer is 50m -> stays active up to 355m
            feed(rg, parseMethod, state, bridge, "dist_maneuver_m:n:355\n");
            check(ScreenModule.isNavActive(), "S3: 355m retained by 50m hysteresis");

            feed(rg, parseMethod, state, bridge, "dist_maneuver_m:n:356\n");
            check(!ScreenModule.isNavActive(), "S3: 356m exits city approach");
        }

        // ============================================================
        // Suite 4: Stale Route-State Across Multiple Generations
        // ============================================================
        {
            BAPBridge bridge = createBridge(null);
            RouteGuidance rg = new RouteGuidance();
            RouteGuidance.State state = (RouteGuidance.State) getField(RouteGuidance.class, rg, "state");
            collector.clear();
            naf.setBoolean(null, true);

            // Gen 200: state 5
            feed(rg, parseMethod, state, bridge,
                "route_generation:n:200\nroute_state:n:5\nmaneuver_count:n:1\nmaneuver_list:s:0\nm0_type:n:1\ndist_maneuver_m:n:200\n");
            check(!ScreenModule.isNavActive(), "S4: gen 200 state 5 is rerouting");

            // Gen 201: no route_state, 483m
            feed(rg, parseMethod, state, bridge,
                "route_generation:n:201\nmaneuver_count:n:1\nmaneuver_list:s:0\nm0_type:n:1\ndist_maneuver_m:n:483\n");
            check(state.routeState == 5 && state.routeStateGeneration == -1L, "S4: gen 201 unauthenticated state");
            check(!ScreenModule.isNavActive(), "S4: gen 201 outside approach (74)");

            // Gen 202: no route_state, 200m -> approach + rebind
            feed(rg, parseMethod, state, bridge,
                "route_generation:n:202\nmaneuver_count:n:1\nmaneuver_list:s:0\nm0_type:n:1\ndist_maneuver_m:n:200\n");
            check(state.routeState == 5 && state.routeStateGeneration == -1L, "S4: gen 202 unauthenticated state");
            check(ScreenModule.isNavActive(), "S4: gen 202 inside approach (80)");

            // Gen 203: explicit state 5, 200m -> authoritative rerouting
            feed(rg, parseMethod, state, bridge,
                "route_generation:n:203\nroute_state:n:5\nmaneuver_count:n:1\nmaneuver_list:s:0\nm0_type:n:1\ndist_maneuver_m:n:200\n");
            check(state.routeState == 5 && state.routeStateGeneration == 203L, "S4: gen 203 authenticated state 5");
            check(!ScreenModule.isNavActive(), "S4: gen 203 explicit rerouting suppresses navActive");

            // Gen 204: no route_state, 200m -> unauthenticated state, normal approach
            feed(rg, parseMethod, state, bridge,
                "route_generation:n:204\nmaneuver_count:n:1\nmaneuver_list:s:0\nm0_type:n:1\ndist_maneuver_m:n:200\n");
            check(state.routeState == 5 && state.routeStateGeneration == -1L, "S4: gen 204 unauthenticated state");
            check(ScreenModule.isNavActive(), "S4: gen 204 recovers to active approach");
        }

        // ============================================================
        // Suite 5: Absurd Repeated Event Jitter ("One Rebind Per Generation")
        // ============================================================
        {
            BAPBridge bridge = createBridge(null);
            RouteGuidance rg = new RouteGuidance();
            RouteGuidance.State state = (RouteGuidance.State) getField(RouteGuidance.class, rg, "state");
            collector.clear();
            naf.setBoolean(null, true);

            feed(rg, parseMethod, state, bridge,
                "route_generation:n:210\nroute_state:n:1\nmaneuver_count:n:1\nmaneuver_list:s:0\nm0_type:n:1\ndist_maneuver_m:n:200\n");
            collector.clear();

            // Reroute to gen 211 at 200m
            feed(rg, parseMethod, state, bridge,
                "route_generation:n:211\nmaneuver_count:n:1\nmaneuver_list:s:0\nm0_type:n:1\ndist_maneuver_m:n:200\n");
            check(collector.count() == 1, "S5: initial reroute rebind fires");

            // Send 30 rapid distance fluctuations
            int[] jitters = new int[]{199, 200, 198, 250, 201, 180, 220, 150, 305, 304, 200, 190, 185, 180, 175, 170};
            for (int i = 0; i < jitters.length; i++) {
                feed(rg, parseMethod, state, bridge, "dist_maneuver_m:n:" + jitters[i] + "\n");
                check(collector.count() == 1, "S5: jitter distance " + jitters[i] + " must NOT re-arm rebind");
            }

            // New generation 212 at 200m
            feed(rg, parseMethod, state, bridge,
                "route_generation:n:212\nmaneuver_count:n:1\nmaneuver_list:s:0\nm0_type:n:1\ndist_maneuver_m:n:200\n");
            check(collector.count() == 2, "S5: generation bump to 212 triggers second rebind");
        }

        // ============================================================
        // Suite 6: Explicit Pending-Rebind Cancellation Latch
        // ============================================================
        {
            BAPBridge bridge = createBridge(null);
            RouteGuidance rg = new RouteGuidance();
            RouteGuidance.State state = (RouteGuidance.State) getField(RouteGuidance.class, rg, "state");
            collector.clear();
            naf.setBoolean(null, true);

            // Establish gen 300 active in 80
            feed(rg, parseMethod, state, bridge,
                "route_generation:n:300\nroute_state:n:1\nmaneuver_count:n:1\nmaneuver_list:s:0\nm0_type:n:1\ndist_maneuver_m:n:200\n");
            collector.clear();

            // Gen 301 arrives without maneuver -> pending rebind latched
            feed(rg, parseMethod, state, bridge, "route_generation:n:301\n");
            check(collector.count() == 0, "S6: gen 301 alone does not rebind yet");

            // Distance 483m arrives -> outside approach -> pending rebind cancelled
            feed(rg, parseMethod, state, bridge,
                "maneuver_count:n:1\nmaneuver_list:s:0\nm0_type:n:1\ndist_maneuver_m:n:483\n");
            check(!ScreenModule.isNavActive(), "S6: drops to 74");
            check(collector.count() == 0, "S6: 483m cancels pending rebind");

            // Travel to 200m -> enters approach normally via setNavActive(true)
            feed(rg, parseMethod, state, bridge, "dist_maneuver_m:n:200\n");
            check(ScreenModule.isNavActive(), "S6: enters approach at 200m");
            check(collector.count() == 0, "S6: stale pending rebind did NOT fire! Count remains 0.");
        }

        // ============================================================
        // Suite 7: Combined Renderer / Context Lifecycle Integration
        // ============================================================
        {
            MockRenderer renderer = new MockRenderer();
            BAPBridge bridge = createBridge(renderer);
            RouteGuidance rg = new RouteGuidance();
            RouteGuidance.State state = (RouteGuidance.State) getField(RouteGuidance.class, rg, "state");
            collector.clear();
            naf.setBoolean(null, false);

            // 1. Enter approach at 200m (left turn)
            feed(rg, parseMethod, state, bridge,
                "route_generation:n:400\nroute_state:n:1\nmaneuver_count:n:1\nmaneuver_list:s:0\nm0_type:n:1\nm0_turn_angle:n:-90\nm0_junction_type:n:0\ndist_maneuver_m:n:200\n");
            check(ScreenModule.isNavActive(), "S7.1: approach active");
            check(renderer.maneuvers == 1, "S7.1: renderer received maneuver");
            check(renderer.lastIcon == 2, "S7.1: ICON_TURN");
            check(renderer.lastExitAngle < 0, "S7.1: left turn negative exit angle");

            // 2. Reroute occurs
            int preClears = renderer.clears;
            feed(rg, parseMethod, state, bridge, "route_state:n:5\n");
            check(!ScreenModule.isNavActive(), "S7.2: reroute left approach");
            check(renderer.clears > preClears, "S7.2: reroute invoked sendClear() on renderer");

            // 3. New route outside approach (483m) with right turn
            feed(rg, parseMethod, state, bridge,
                "route_generation:n:401\nmaneuver_count:n:1\nmaneuver_list:s:0\nm0_type:n:2\nm0_turn_angle:n:90\nm0_junction_type:n:0\ndist_maneuver_m:n:483\n");
            check(!ScreenModule.isNavActive(), "S7.3: 483m stays 74");

            // 4. Approach at 200m with new maneuver (right turn)
            feed(rg, parseMethod, state, bridge, "dist_maneuver_m:n:200\n");
            check(ScreenModule.isNavActive(), "S7.4: enters approach");
            check(renderer.maneuvers >= 2, "S7.4: renderer received new maneuver");
            check(renderer.lastIcon == 2, "S7.4: ICON_TURN");
            check(renderer.lastExitAngle > 0, "S7.4: new maneuver right turn positive exit angle (not stale left turn)");
        }

        // ============================================================
        // Suite 8: ScreenModule Concurrency & Inter-Route Races
        // ============================================================
        {
            Field rpf = ScreenModule.class.getDeclaredField("rebindPending");
            rpf.setAccessible(true);
            Field rrf = ScreenModule.class.getDeclaredField("rebindReason");
            rrf.setAccessible(true);

            // Test 8.1: Request Coalescing
            ScreenModule.requestClusterContextRebind("Reason-A");
            check(rpf.getBoolean(null), "S8.1: rebindPending is true after request A");
            check("Reason-A".equals(rrf.get(null)), "S8.1: rebindReason is Reason-A");

            ScreenModule.requestClusterContextRebind("Reason-B");
            check(rpf.getBoolean(null), "S8.1: rebindPending remains true after request B");
            check("Reason-B".equals(rrf.get(null)), "S8.1: rebindReason updated to Reason-B");

            // Test 8.2: Inter-Route Leakage Prevention on Route End
            ScreenModule.setNavActive(false);
            check(!rpf.getBoolean(null), "S8.2: setNavActive(false) cleared rebindPending");
            check("".equals(rrf.get(null)), "S8.2: setNavActive(false) cleared rebindReason");

            // Test 8.3: In-Flight Bounce Race (Concurrent Request Non-Swallowing)
            final List switches = new ArrayList();
            final boolean[] requestedInFlight = new boolean[]{false};
            IDisplayManager mockDm = (IDisplayManager) Proxy.newProxyInstance(
                RouteContextDeltaIntegrationTest.class.getClassLoader(),
                new Class[]{IDisplayManager.class, IDisplayManagerKombiControl.class},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if (method.getName().equals("switchContext")) {
                            int ctx = ((Integer) args[0]).intValue();
                            switches.add(new Integer(ctx));
                            if (ctx == 72 && !requestedInFlight[0]) {
                                requestedInFlight[0] = true;
                                ScreenModule.requestClusterContextRebind("Reason-In-Flight-B");
                            }
                        }
                        Class type = method.getReturnType();
                        if (type == Boolean.TYPE) return Boolean.FALSE;
                        if (type == Integer.TYPE) return Integer.valueOf(0);
                        return null;
                    }
                });

            Field dmf = ScreenModule.class.getDeclaredField("dm");
            dmf.setAccessible(true);
            Field desCf = ScreenModule.class.getDeclaredField("desiredCtx");
            desCf.setAccessible(true);
            Field curCf = ScreenModule.class.getDeclaredField("currentCtx");
            curCf.setAccessible(true);
            Field lockF = ScreenModule.class.getDeclaredField("LOCK");
            lockF.setAccessible(true);
            Object lockObj = lockF.get(null);

            // Set up active cluster session with our mockDm
            final ScreenModule sm = new ScreenModule();
            synchronized (lockObj) {
                dmf.set(sm, mockDm);
                desCf.setInt(null, 80);
                curCf.setInt(null, 80);
            }

            // Start worker thread
            Field wf = ScreenModule.class.getDeclaredField("worker");
            wf.setAccessible(true);
            final Method swLoop = ScreenModule.class.getDeclaredMethod("switchLoop", new Class[0]);
            swLoop.setAccessible(true);
            Thread worker = new Thread(new Runnable() {
                public void run() {
                    try { swLoop.invoke(sm, new Object[0]); }
                    catch (Exception ignored) {}
                }
            }, "test-cluster-switch");
            worker.setDaemon(true);
            worker.start();
            wf.set(sm, worker);

            // Issue rebind A
            ScreenModule.requestClusterContextRebind("Reason-A");

            // Wait up to 1000ms for both bounces (A and in-flight B) to execute
            long deadline = System.currentTimeMillis() + 1000;
            while (switches.size() < 4 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }

            // Verify both bounces occurred: [72, 80, 72, 80]
            check(switches.size() >= 4, "S8.3: both rebinds executed (switches=" + switches + ")");
            check(((Integer) switches.get(0)).intValue() == 72 && ((Integer) switches.get(1)).intValue() == 80,
                "S8.3: first rebind executed 72->80");
            check(((Integer) switches.get(2)).intValue() == 72 && ((Integer) switches.get(3)).intValue() == 80,
                "S8.3: second in-flight rebind was NOT swallowed, executed 72->80");

            // Clean up: reset dm
            synchronized (lockObj) {
                dmf.set(sm, null);
                desCf.setInt(null, 74);
                curCf.setInt(null, 74);
            }
        }

        System.out.println("RouteContextDeltaIntegrationTest: ALL 8 END-TO-END SUITES PASS (" + checks + " checks)");
    }
}
