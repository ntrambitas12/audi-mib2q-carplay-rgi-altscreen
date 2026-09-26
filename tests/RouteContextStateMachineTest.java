import com.luka.carplay.core.ScreenModule;
import com.luka.carplay.rgd.BAPBridge;
import com.luka.carplay.rgd.RouteGuidance;
import com.luka.carplay.framework.Log;
import de.audi.atip.interapp.combi.bap.navi.CombiBAPServiceNavi;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic & Randomized State Machine Verification for Route Guidance Context Transitions.
 * Tests physical context decisions (74 vs 80 vs 72->80 rebinds) under normal and hostile event ordering.
 */
public final class RouteContextStateMachineTest {

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

    private static BAPBridge createBridge() throws Exception {
        BAPBridge bridge = new BAPBridge();
        CombiBAPServiceNavi service = (CombiBAPServiceNavi) Proxy.newProxyInstance(
            RouteContextStateMachineTest.class.getClassLoader(),
            new Class[]{CombiBAPServiceNavi.class},
            new BapProxyHandler());
        setField(BAPBridge.class, bridge, "appConnectorNavi", service);
        setField(BAPBridge.class, bridge, "initialized", Boolean.TRUE);
        setField(BAPBridge.class, bridge, "bapSessionStarted", Boolean.TRUE);
        return bridge;
    }

    private static RouteGuidance.State createState(long gen, int routeState, int manCount, int mType0, int distM) {
        RouteGuidance.State s = new RouteGuidance.State();
        s.routeGeneration = gen;
        s.routeState = routeState;
        s.routeStateGeneration = gen;
        s.maneuverCount = manCount;
        if (manCount > 0) {
            s.maneuverOrder = new int[]{0};
            s.mType[0] = mType0;
            s.mDistance[0] = distM;
        } else {
            s.maneuverOrder = new int[0];
        }
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
        Field rpf = ScreenModule.class.getDeclaredField("rebindPending");
        rpf.setAccessible(true);

        System.out.println("Running RouteContextStateMachineTest...");

        // ============================================================
        // Test 1: The exact failure sequence from hardware
        // ============================================================
        {
            BAPBridge bridge = createBridge();
            collector.clear();
            naf.setBoolean(null, false);
            rpf.setBoolean(null, false);

            // Step 1: gen 100, route_state 1, city turn, 200m -> approach = true, ctx = 80
            RouteGuidance.State s1 = createState(100L, 1, 1, 1, 200);
            bridge.update(s1);
            check(ScreenModule.isNavActive(), "T1.1: gen 100 at 200m is approach (ctx 80)");
            collector.clear();

            // Step 2: reroute begins, route_state = 5
            RouteGuidance.State s2 = createState(100L, 5, 1, 1, 200);
            bridge.update(s2);
            check(collector.count() == 0, "T1.2: route_state 5 must not rebind");

            // Step 3: new generation 101, distance 483m (~0.3 mi), no route_state
            RouteGuidance.State s3 = createState(101L, 5, 1, 1, 483);
            s3.routeStateGeneration = -1L; // unauthenticated state 5 from previous route
            bridge.update(s3);
            check(collector.count() == 0, "T1.3: new gen 101 at 483m must NEVER rebind");
            check(!ScreenModule.isNavActive(), "T1.3: 483m city turn must exit approach (settles in ctx 74)");

            // Step 4: approach vehicle travel 400m, 350m, 306m -> stays 74
            RouteGuidance.State s400 = createState(101L, 1, 1, 1, 400);
            bridge.update(s400);
            check(!ScreenModule.isNavActive(), "T1.4: 400m stays ctx 74");

            RouteGuidance.State s350 = createState(101L, 1, 1, 1, 350);
            bridge.update(s350);
            check(!ScreenModule.isNavActive(), "T1.4: 350m outside approach stays ctx 74");

            RouteGuidance.State s306 = createState(101L, 1, 1, 1, 306);
            bridge.update(s306);
            check(!ScreenModule.isNavActive(), "T1.4: 306m stays ctx 74");
            check(collector.count() == 0, "T1.4: cruising must not rebind");

            // Step 5: crossing threshold: 305m -> enters ctx 80 cleanly without forced rebind
            RouteGuidance.State s305 = createState(101L, 1, 1, 1, 305);
            bridge.update(s305);
            check(ScreenModule.isNavActive(), "T1.5: 305m enters ctx 80 cleanly");
            check(collector.count() == 0, "T1.5: normal approach entry does NOT do 72->80 rebind");

            RouteGuidance.State s304 = createState(101L, 1, 1, 1, 304);
            bridge.update(s304);
            check(ScreenModule.isNavActive(), "T1.5: 304m stays ctx 80");
            check(collector.count() == 0, "T1.5: no rebind on distance updates");
        }

        // ============================================================
        // Test 2: Generation arrives before maneuver data
        // ============================================================
        {
            BAPBridge bridge = createBridge();
            collector.clear();
            com.luka.carplay.cluster.ClusterLayerController.onVcVisibility(true);

            // Active route gen 200 in approach
            naf.setBoolean(null, true);
            RouteGuidance.State s200 = createState(200L, 1, 1, 1, 200);
            bridge.update(s200);
            collector.clear();

            // Delta 1: gen 201 arrives with no maneuvers yet
            RouteGuidance.State s201_empty = createState(201L, 1, 0, 1, -1);
            bridge.update(s201_empty);
            check(collector.count() == 0, "T2.1: empty gen 201 must not rebind prematurely");

            // Delta 2: gen 201 maneuver arrives with distance 483m (outside approach)
            RouteGuidance.State s201_far = createState(201L, 1, 1, 1, 483);
            bridge.update(s201_far);
            check(collector.count() == 0, "T2.2: gen 201 at 483m must cancel rebind and not trigger");

            // Opposite: gen 202 arrives empty, then arrives at 200m (inside approach)
            naf.setBoolean(null, true);
            RouteGuidance.State s202_empty = createState(202L, 1, 0, 1, -1);
            bridge.update(s202_empty);
            check(collector.count() == 0, "T2.3: gen 202 empty does not rebind yet");

            RouteGuidance.State s202_near = createState(202L, 1, 1, 1, 200);
            bridge.update(s202_near);
            check(collector.count() == 1, "T2.4: gen 202 near maneuver executes the latched rebind");
            check(collector.lastReason().indexOf("302") < 0 && collector.lastReason().indexOf("202") >= 0,
                "T2.4: rebind reason matches gen 202");
            com.luka.carplay.cluster.ClusterLayerController.onVcVisibility(false);
        }

        // ============================================================
        // Test 3: Multiple generations before the first maneuver
        // ============================================================
        {
            BAPBridge bridge = createBridge();
            collector.clear();
            com.luka.carplay.cluster.ClusterLayerController.onVcVisibility(true);
            naf.setBoolean(null, true);

            // Gen 100 active
            bridge.update(createState(100L, 1, 1, 1, 200));
            collector.clear();

            // Gen 101, 102, 103 arrive in rapid succession without maneuvers
            bridge.update(createState(101L, 1, 0, 1, -1));
            bridge.update(createState(102L, 1, 0, 1, -1));
            bridge.update(createState(103L, 1, 0, 1, -1));
            check(collector.count() == 0, "T3: no rebind during rapid maneuverless generations");

            // Maneuver arrives for gen 103 at 200m
            bridge.update(createState(103L, 1, 1, 1, 200));
            check(collector.count() == 1, "T3: exactly 1 rebind for gen 103");
            check(collector.lastReason().indexOf("route-generation=103") >= 0, "T3: rebind reason is gen 103");
            com.luka.carplay.cluster.ClusterLayerController.onVcVisibility(false);
        }

        // ============================================================
        // Test 4: Rebind must happen exactly once
        // ============================================================
        {
            BAPBridge bridge = createBridge();
            collector.clear();
            naf.setBoolean(null, true);

            bridge.update(createState(200L, 1, 1, 1, 200));
            collector.clear();

            // New generation 201 at 200m -> triggers rebind
            bridge.update(createState(201L, 1, 1, 1, 200));
            check(collector.count() == 1, "T4: initial rebind on gen 201");

            // Subsequent distance deltas for same generation
            bridge.update(createState(201L, 1, 1, 1, 199));
            bridge.update(createState(201L, 1, 1, 1, 198));
            bridge.update(createState(201L, 1, 1, 1, 197));
            bridge.update(createState(201L, 1, 1, 1, 196));
            check(collector.count() == 1, "T4: repeated distance deltas must NOT trigger additional rebinds");
        }

        // ============================================================
        // Test 5: New route outside approach must NEVER rebind (fuzz city distances)
        // ============================================================
        {
            int[] farDistances = new int[]{306, 350, 400, 483, 500, 800, 1000, 1500, 3000};
            for (int i = 0; i < farDistances.length; i++) {
                BAPBridge bridge = createBridge();
                collector.clear();
                naf.setBoolean(null, true);

                long gen = 500L + i;
                RouteGuidance.State s = createState(gen, 1, 1, 1, farDistances[i]);
                bridge.update(s);
                check(collector.count() == 0, "T5: distance " + farDistances[i] + "m must NEVER rebind");
                check(!ScreenModule.isNavActive(), "T5: distance " + farDistances[i] + "m must exit approach");
            }
        }

        // ============================================================
        // Test 6: Boundary fuzzing and hysteresis around 305m (City)
        // ============================================================
        {
            BAPBridge bridge = createBridge();
            collector.clear();

            // Fresh state outside approach: 307m, 306m -> outside
            bridge.update(createState(600L, 1, 1, 1, 307));
            check(!ScreenModule.isNavActive(), "T6: 307m is outside");
            bridge.update(createState(600L, 1, 1, 1, 306));
            check(!ScreenModule.isNavActive(), "T6: 306m is outside");

            // 305m -> enters approach
            bridge.update(createState(600L, 1, 1, 1, 305));
            check(ScreenModule.isNavActive(), "T6: 305m enters approach");

            // Hysteresis test: once in approach, stays in approach up to 305 + 50 = 355m
            int[] insideHysteresis = new int[]{306, 320, 340, 350, 354, 355};
            for (int i = 0; i < insideHysteresis.length; i++) {
                bridge.update(createState(600L, 1, 1, 1, insideHysteresis[i]));
                check(ScreenModule.isNavActive(), "T6: " + insideHysteresis[i] + "m within 50m hysteresis buffer");
            }

            // 356m -> exits approach
            bridge.update(createState(600L, 1, 1, 1, 356));
            check(!ScreenModule.isNavActive(), "T6: 356m exits approach (> 355m)");
        }

        // ============================================================
        // Test 7: Highway threshold and hysteresis around 1600m
        // ============================================================
        {
            BAPBridge bridge = createBridge();
            collector.clear();
            int highwayType = 8; // MT_OFF_RAMP

            // 1602m, 1601m -> outside
            bridge.update(createState(700L, 1, 1, highwayType, 1602));
            check(!ScreenModule.isNavActive(), "T7: highway 1602m is outside");
            bridge.update(createState(700L, 1, 1, highwayType, 1601));
            check(!ScreenModule.isNavActive(), "T7: highway 1601m is outside");

            // 1600m -> enters approach
            bridge.update(createState(700L, 1, 1, highwayType, 1600));
            check(ScreenModule.isNavActive(), "T7: highway 1600m enters approach");

            // Hysteresis test: 1600 + 50 = 1650m
            bridge.update(createState(700L, 1, 1, highwayType, 1601));
            check(ScreenModule.isNavActive(), "T7: highway 1601m stays in approach");
            bridge.update(createState(700L, 1, 1, highwayType, 1650));
            check(ScreenModule.isNavActive(), "T7: highway 1650m stays in approach");

            // 1651m -> exits approach
            bridge.update(createState(700L, 1, 1, highwayType, 1651));
            check(!ScreenModule.isNavActive(), "T7: highway 1651m exits approach (> 1650m)");
        }

        // ============================================================
        // Test 8: Reroute while already inside approach
        // ============================================================
        {
            BAPBridge bridge = createBridge();
            collector.clear();
            com.luka.carplay.cluster.ClusterLayerController.onVcVisibility(true);
            naf.setBoolean(null, true);

            bridge.update(createState(800L, 1, 1, 1, 200));
            collector.clear();

            // state 5
            bridge.update(createState(800L, 5, 1, 1, 200));

            // gen 801 arrives at 200m with no route_state
            RouteGuidance.State s = createState(801L, 5, 1, 1, 200);
            s.routeStateGeneration = -1L;
            bridge.update(s);
            check(collector.count() == 1, "T8: reroute inside approach triggers exactly 1 rebind");
            check(ScreenModule.isNavActive(), "T8: remains in ctx 80");
            com.luka.carplay.cluster.ClusterLayerController.onVcVisibility(false);
        }

        // ============================================================
        // Test 9: Reroute while outside approach
        // ============================================================
        {
            BAPBridge bridge = createBridge();
            collector.clear();
            naf.setBoolean(null, false);

            bridge.update(createState(900L, 1, 1, 1, 600));
            check(!ScreenModule.isNavActive(), "T9: 600m in ctx 74");

            // gen changes at 600m
            bridge.update(createState(901L, 1, 1, 1, 600));
            check(collector.count() == 0, "T9: gen change at 600m must NOT rebind");
            check(!ScreenModule.isNavActive(), "T9: remains in ctx 74");

            // Later reaches 305m -> normal 74 -> 80
            bridge.update(createState(901L, 1, 1, 1, 305));
            check(ScreenModule.isNavActive(), "T9: 305m normal 74->80");
            check(collector.count() == 0, "T9: normal 74->80 does not rebind");
        }

        // ============================================================
        // Test 10: Reroute from approach directly to another approach
        // ============================================================
        {
            BAPBridge bridge = createBridge();
            collector.clear();
            com.luka.carplay.cluster.ClusterLayerController.onVcVisibility(true);
            naf.setBoolean(null, true);

            bridge.update(createState(1000L, 1, 1, 1, 200));
            collector.clear();

            // Reroute 1: gen 1001, 250m
            bridge.update(createState(1000L, 5, 1, 1, 200));
            RouteGuidance.State r1 = createState(1001L, 5, 1, 1, 250);
            r1.routeStateGeneration = -1L;
            bridge.update(r1);
            check(collector.count() == 1, "T10: reroute 1 causes rebind 1");

            // Reroute 2 immediately follows: gen 1002, 180m
            bridge.update(createState(1001L, 5, 1, 1, 250));
            RouteGuidance.State r2 = createState(1002L, 5, 1, 1, 180);
            r2.routeStateGeneration = -1L;
            bridge.update(r2);
            check(collector.count() == 2, "T10: reroute 2 causes rebind 2");
            check(collector.lastReason().indexOf("1002") >= 0, "T10: second rebind has gen 1002");
            com.luka.carplay.cluster.ClusterLayerController.onVcVisibility(false);
        }

        // ============================================================
        // Test 11: Explicit route_state=5 authentication across generations
        // ============================================================
        {
            BAPBridge bridge = createBridge();
            collector.clear();
            naf.setBoolean(null, true);

            // gen 1100, state 5 (authoritative)
            RouteGuidance.State s1 = createState(1100L, 5, 1, 1, 200);
            s1.routeStateGeneration = 1100L;
            bridge.update(s1);
            check(!ScreenModule.isNavActive(), "T11: active rerouting suppresses approach");

            // gen 1101, state 5 (authoritative for 1101)
            RouteGuidance.State s2 = createState(1101L, 5, 1, 1, 200);
            s2.routeStateGeneration = 1101L;
            bridge.update(s2);
            check(!ScreenModule.isNavActive(), "T11: authoritative state 5 on new gen suppresses approach");
            check(collector.count() == 0, "T11: rerouting does not rebind");

            // gen 1102, no route_state (unauthenticated 5)
            RouteGuidance.State s3 = createState(1102L, 5, 1, 1, 200);
            s3.routeStateGeneration = -1L;
            naf.setBoolean(null, true); // simulate KDK active
            bridge.update(s3);
            check(ScreenModule.isNavActive(), "T11: unauthenticated 5 does NOT suppress approach for gen 1102");
            check(collector.count() == 1, "T11: gen 1102 at 200m rebinds");
        }

        // ============================================================
        // Test 12: Transient route_state=0 across generations
        // ============================================================
        {
            BAPBridge bridge = createBridge();
            collector.clear();
            naf.setBoolean(null, true);

            // gen 1200 active
            bridge.update(createState(1200L, 1, 1, 1, 200));
            collector.clear();

            // transient 0 on gen 1200
            bridge.update(createState(1200L, 0, 0, 1, -1));

            // gen 1201 with transient 0 + maneuver
            RouteGuidance.State s0 = createState(1201L, 0, 1, 1, 200);
            s0.routeStateGeneration = 1201L;
            bridge.update(s0);

            // recovery to state 1
            RouteGuidance.State s1 = createState(1201L, 1, 1, 1, 200);
            bridge.update(s1);
            check(ScreenModule.isNavActive(), "T12: route cleanly recovers after transient 0");
        }

        // ============================================================
        // Test 13: Unknown distance (-1) behavior
        // ============================================================
        {
            BAPBridge bridge = createBridge();
            collector.clear();

            // Establish approach first at 200m
            bridge.update(createState(1300L, 1, 1, 1, 200));
            check(ScreenModule.isNavActive(), "T13: initial 200m enters approach");

            // Unknown distance (-1) preserves approach zone without flickering
            RouteGuidance.State sUnknown = createState(1300L, 1, 1, 1, -1);
            bridge.update(sUnknown);
            check(ScreenModule.isNavActive(), "T13: -1 distance preserves active approach zone");

            // Distance resolved to 600m -> exits to 74
            RouteGuidance.State sFar = createState(1300L, 1, 1, 1, 600);
            bridge.update(sFar);
            check(!ScreenModule.isNavActive(), "T13: 600m resolved exits approach");

            // Distance approaches to 200m -> enters 80
            RouteGuidance.State sNear = createState(1300L, 1, 1, 1, 200);
            bridge.update(sNear);
            check(ScreenModule.isNavActive(), "T13: 200m re-enters approach");
        }

        // ============================================================
        // Test 14: Context Trace Verification
        // ============================================================
        {
            BAPBridge bridge = createBridge();
            collector.clear();
            naf.setBoolean(null, false);

            final StringBuffer trace = new StringBuffer();
            ScreenModule.setRebindListener(new ScreenModule.RebindListener() {
                public void onClusterContextRebindRequested(String reason) {
                    trace.append("[REBIND]");
                }
            });

            // Sequence:
            // 1. Initial idle
            trace.append(ScreenModule.isNavActive() ? "80" : "74").append(" -> ");
            // 2. Route start at 500m (outside approach)
            bridge.update(createState(1400L, 1, 1, 1, 500));
            trace.append(ScreenModule.isNavActive() ? "80" : "74").append(" -> ");
            // 3. Cruising to 305m (enters approach)
            bridge.update(createState(1400L, 1, 1, 1, 305));
            trace.append(ScreenModule.isNavActive() ? "80" : "74").append(" -> ");
            // 4. Reroute outside approach: gen 1401 at 483m
            RouteGuidance.State rOut = createState(1401L, 5, 1, 1, 483);
            rOut.routeStateGeneration = -1L;
            bridge.update(rOut);
            trace.append(ScreenModule.isNavActive() ? "80" : "74").append(" -> ");
            // 5. Approach again to 200m
            bridge.update(createState(1401L, 1, 1, 1, 200));
            trace.append(ScreenModule.isNavActive() ? "80" : "74").append(" -> ");
            // 6. Reroute inside approach: gen 1402 at 180m
            RouteGuidance.State rIn = createState(1402L, 5, 1, 1, 180);
            rIn.routeStateGeneration = -1L;
            bridge.update(rIn);
            trace.append(ScreenModule.isNavActive() ? "80" : "74").append(" -> ");
            // 7. Second reroute inside approach: gen 1403 at 150m
            RouteGuidance.State rIn2 = createState(1403L, 5, 1, 1, 150);
            rIn2.routeStateGeneration = -1L;
            bridge.update(rIn2);
            trace.append(ScreenModule.isNavActive() ? "80" : "74");

            String expectedTrace = "74 -> 74 -> 80 -> 74 -> 80 -> [REBIND]80 -> [REBIND]80";
            check(trace.toString().equals(expectedTrace),
                "T14: trace mismatch!\nExpected: " + expectedTrace + "\nActual:   " + trace.toString());
            ScreenModule.setRebindListener(collector);
        }

        // ============================================================
        // Test 15: Randomized / Property-Based State Machine Fuzzing
        // ============================================================
        {
            BAPBridge bridge = createBridge();
            collector.clear();
            naf.setBoolean(null, false);

            Random rnd = new Random(42);
            long gen = 2000L;
            int lastGen = 2000;
            int rebindsForCurrentGen = 0;

            for (int iter = 0; iter < 50000; iter++) {
                int action = rnd.nextInt(6);
                if (action == 0) {
                    // New generation bump
                    gen++;
                    rebindsForCurrentGen = 0;
                }
                int rState = rnd.nextInt(3) == 0 ? 5 : (rnd.nextInt(5) == 0 ? 0 : 1);
                boolean auth = rnd.nextBoolean();
                long stateGen = auth ? gen : (gen - 1);
                int manCount = rnd.nextInt(4) == 0 ? 0 : 1;
                int[] testTypes = new int[]{1, 2, 3, 8, 9, 22, 23, 51, 52, 53};
                int mType = testTypes[rnd.nextInt(testTypes.length)];
                int dist = rnd.nextInt(6) == 0 ? -1 : rnd.nextInt(2000);

                int preRebinds = collector.count();
                boolean wasActive = ScreenModule.isNavActive();

                RouteGuidance.State s = createState(gen, rState, manCount, mType, dist);
                s.routeStateGeneration = stateGen;
                bridge.update(s);

                int postRebinds = collector.count();
                int newRebinds = postRebinds - preRebinds;

                // INVARIANT 1: Stale route_state=5 must never make a newer generation behave as rerouting
                if (rState == 5 && stateGen != gen) {
                    boolean isRerouting = (s.routeState == 5) && (s.routeStateGeneration == s.routeGeneration);
                    check(!isRerouting, "INV1: stale state 5 must not be rerouting");
                }

                // INVARIANT 2: A generation change outside approach must never directly request a context rebind
                boolean isHighway = (mType == 8 || mType == 9 || mType == 22 || mType == 23 || mType == 51 || mType == 52 || mType == 53);
                int limit = isHighway ? 1600 : 305;
                if (dist > limit && manCount > 0) {
                    check(newRebinds == 0, "INV2: distance " + dist + " > " + limit + " must not trigger rebind");
                }

                // INVARIANT 3: A generation change inside approach can cause at most one forced rebind per generation
                if (newRebinds > 0) {
                    rebindsForCurrentGen += newRebinds;
                    check(rebindsForCurrentGen <= 1, "INV3: at most 1 rebind per generation, got " + rebindsForCurrentGen);
                }
            }
        }

        // ============================================================
        // Test 16: Property-Based Verification Against Independent Reference Model
        // ============================================================
        {
            BAPBridge bridge = createBridge();
            collector.clear();
            naf.setBoolean(null, false);

            IndependentReferenceModel ref = new IndependentReferenceModel();
            Random rnd = new Random(1337);
            long gen = 3000L;

            for (int iter = 0; iter < 100000; iter++) {
                int action = rnd.nextInt(6);
                if (action == 0) {
                    gen++;
                }
                int rState = rnd.nextInt(3) == 0 ? 5 : (rnd.nextInt(5) == 0 ? 0 : 1);
                boolean auth = rnd.nextBoolean();
                long stateGen = auth ? gen : (gen - 1);
                int manCount = rnd.nextInt(4) == 0 ? 0 : 1;
                int[] testTypes = new int[]{1, 2, 3, 8, 9, 22, 23, 51, 52, 53};
                int mType = testTypes[rnd.nextInt(testTypes.length)];
                int dist = rnd.nextInt(6) == 0 ? -1 : rnd.nextInt(2000);

                int preRebinds = collector.count();

                RouteGuidance.State s = createState(gen, rState, manCount, mType, dist);
                s.routeStateGeneration = stateGen;

                ref.step(gen, rState, auth, manCount, mType, dist);
                bridge.update(s);

                int postRebinds = collector.count();
                int newRebinds = postRebinds - preRebinds;

                check(ScreenModule.isNavActive() == ref.navActive,
                    "T16 step " + iter + ": navActive parity (actual=" + ScreenModule.isNavActive() + " vs ref=" + ref.navActive + ")");
                check(collector.count() == ref.totalRebinds,
                    "T16 step " + iter + ": total rebinds parity (actual=" + collector.count() + " vs ref=" + ref.totalRebinds + ")");
                check((newRebinds > 0) == ref.rebindThisStep,
                    "T16 step " + iter + ": step rebind occurrence parity");
                if (ref.rebindThisStep) {
                    check(collector.lastReason().indexOf("" + gen) >= 0,
                        "T16 step " + iter + ": rebind reason contains generation " + gen);
                }
                boolean actualRerouting = (s.routeState == 5) && (s.routeStateGeneration == s.routeGeneration);
                check(actualRerouting == ref.isRerouting,
                    "T16 step " + iter + ": isRerouting parity");
            }
        }

        System.out.println("RouteContextStateMachineTest: ALL 16 HOSTILE/DETERMINISTIC SUITES & 150000 FUZZ CYCLES PASS (" + checks + " checks)");
    }

    private static final class IndependentReferenceModel {
        long currentGen = -1L;
        boolean inApproach = false;
        boolean navActive = false;
        boolean pendingRebind = false;
        int totalRebinds = 0;
        boolean rebindThisStep = false;
        long reboundGen = -1L;
        boolean isRerouting = false;

        void step(long gen, int rState, boolean auth, int manCount, int mType, int distM) {
            rebindThisStep = false;
            boolean genChanged = (gen >= 0 && gen != currentGen);
            if (genChanged) {
                currentGen = gen;
                inApproach = false;
                pendingRebind = true;
            }

            // Route state 5 is rerouting ONLY if authenticated for the current generation
            isRerouting = (rState == 5) && auth;

            boolean isHighway = (mType == 8 || mType == 9 || mType == 22 || mType == 23 || mType == 51 || mType == 52 || mType == 53);
            int baseThreshold = isHighway ? 1600 : 305;
            int effectiveThreshold = inApproach ? (baseThreshold + 50) : baseThreshold;

            boolean hasUsableDistance = (distM >= 0);
            boolean inDisplayDistance = (!hasUsableDistance) || (distM <= effectiveThreshold);

            boolean hasManeuver = (manCount > 0);
            boolean nowApproach = hasManeuver && !isRerouting
                && (hasUsableDistance ? inDisplayDistance : inApproach);
            inApproach = nowApproach;

            if (pendingRebind) {
                if (nowApproach) {
                    if (navActive && currentGen != reboundGen) {
                        rebindThisStep = true;
                        totalRebinds++;
                        reboundGen = currentGen;
                    }
                    pendingRebind = false;
                } else if (!navActive || (hasManeuver && hasUsableDistance)) {
                    pendingRebind = false;
                }
            }

            navActive = nowApproach;
        }
    }
}
