import com.luka.carplay.rgd.BAPBridge;
import de.audi.atip.interapp.combi.bap.navi.CombiBAPServiceNavi;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Exercises the shipping isInBlinkZone(int,int) consolidation directly, plus the actual
 * sendActionBlinkTick call site, to prove the new BLINK_MAX_DISTANCE_CM (30.48 m / 100 ft)
 * absolute cap now gates blinking in addition to the pre-existing BARGRAPH_BLINK_PERCENT
 * percent-of-denominator rule -- fixing the bug where a long highway denominator (1600 m)
 * let the bargraph flash as far out as ~1050 ft.
 */
public final class BlinkZoneAbsoluteCapTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    private static final class Output implements InvocationHandler {
        int value = Integer.MIN_VALUE, unit = Integer.MIN_VALUE, bar = -1;
        boolean on;
        int calls;
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("updateDistanceToNextManeuver")) {
                value = ((Integer) args[0]).intValue();
                unit = ((Integer) args[1]).intValue();
                on = ((Boolean) args[2]).booleanValue();
                bar = ((Integer) args[3]).intValue();
                calls++;
            }
            Class<?> type = method.getReturnType();
            if (type == Boolean.TYPE) return Boolean.FALSE;
            if (type == Integer.TYPE) return Integer.valueOf(0);
            return null;
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); f.set(target, value); return; }
            catch (NoSuchFieldException e) { }
        }
        throw new NoSuchFieldException(name);
    }

    public static void main(String[] args) throws Exception {
        Method isInBlinkZone = BAPBridge.class.getDeclaredMethod("isInBlinkZone", Integer.TYPE, Integer.TYPE);
        isInBlinkZone.setAccessible(true);

        // --- Direct helper checks (the single consolidated source of truth) ---

        // blink off at 61 m on the city denominator (457 m): 61*100/457 == 13% (< 20%, so the percent
        // rule alone would still blink) but 61 m is beyond the 30.48 m absolute cap.
        check(!((Boolean) isInBlinkZone.invoke(null, Integer.valueOf(61), Integer.valueOf(457))).booleanValue(),
            "blink off at 61 m city denominator");

        // blink off at 300 m on the highway denominator (1600 m): 300*100/1600 == 18.75% (< 20%,
        // so the OLD percent-only rule would have blinked here) but 300 m is far outside the new
        // absolute 30.48 m cap -- this is the regression case the fix targets.
        check(!((Boolean) isInBlinkZone.invoke(null, Integer.valueOf(300), Integer.valueOf(1600))).booleanValue(),
            "blink off at 300 m highway denominator (absolute cap overrides percent rule)");

        // blink on at 29 m (well under both the percent rule and the 30.48 m absolute cap).
        check(((Boolean) isInBlinkZone.invoke(null, Integer.valueOf(29), Integer.valueOf(1600))).booleanValue(),
            "blink on at 29 m");
        check(((Boolean) isInBlinkZone.invoke(null, Integer.valueOf(29), Integer.valueOf(457))).booleanValue(),
            "blink on at 29 m (city denominator too)");

        // off at exactly 30.48 m+ : 30 m (3000 cm < 3048 cm) stays on; 31 m (3100 cm >= 3048 cm)
        // is the first integer meter value at/above the 100 ft cap and must be off.
        check(((Boolean) isInBlinkZone.invoke(null, Integer.valueOf(30), Integer.valueOf(1600))).booleanValue(),
            "blink still on at 30 m (below 30.48 m cap)");
        check(!((Boolean) isInBlinkZone.invoke(null, Integer.valueOf(31), Integer.valueOf(1600))).booleanValue(),
            "blink off at 31 m (at/above 30.48 m cap)");

        // Boundary/invalid inputs unchanged by the new cap.
        check(!((Boolean) isInBlinkZone.invoke(null, Integer.valueOf(0), Integer.valueOf(100))).booleanValue(),
            "blink off at distM=0");
        check(!((Boolean) isInBlinkZone.invoke(null, Integer.valueOf(10), Integer.valueOf(0))).booleanValue(),
            "blink off with zero denominator");
        check(!((Boolean) isInBlinkZone.invoke(null, Integer.valueOf(50), Integer.valueOf(40))).booleanValue(),
            "blink off when distM exceeds denominator");

        // --- Integration check: the actual sendActionBlinkTick call site respects the cap ---
        Output output = new Output();
        BAPBridge bridge = new BAPBridge();
        CombiBAPServiceNavi service = (CombiBAPServiceNavi) Proxy.newProxyInstance(
            BlinkZoneAbsoluteCapTest.class.getClassLoader(),
            new Class[] {CombiBAPServiceNavi.class}, output);
        set(bridge, "appConnectorNavi", service);
        set(bridge, "initialized", Boolean.TRUE);

        Method tick = BAPBridge.class.getDeclaredMethod("sendActionBlinkTick", Integer.TYPE);
        tick.setAccessible(true);

        // 300 m / 1600 m highway case: must NOT send any FctID 18 blink tick.
        set(bridge, "actionBlinkGeneration", Integer.valueOf(1));
        set(bridge, "blinkArmed", Boolean.TRUE);
        set(bridge, "blinkDistM", Integer.valueOf(300));
        set(bridge, "blinkBargraphDenominatorM", Integer.valueOf(1600));
        set(bridge, "actionBlinkFull", Boolean.TRUE);
        check(((Boolean) tick.invoke(bridge, Integer.valueOf(1))).booleanValue(), "worker stays alive when out of blink zone");
        check(output.calls == 0, "300 m/1600 m tick must not send FctID 18 (out of absolute blink cap)");

        // 29 m case: must send a blink tick.
        set(bridge, "blinkDistM", Integer.valueOf(29));
        set(bridge, "blinkBargraphDenominatorM", Integer.valueOf(1600));
        check(((Boolean) tick.invoke(bridge, Integer.valueOf(1))).booleanValue(), "worker stays alive in blink zone");
        check(output.calls == 1, "29 m/1600 m tick must send FctID 18 (inside blink zone)");

        System.out.println("BlinkZoneAbsoluteCapTest: PASS (" + checks + " checks)");
    }
}
