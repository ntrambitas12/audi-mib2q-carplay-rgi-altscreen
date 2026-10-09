package com.luka.carplay.cluster;

import java.io.File;
import java.io.FileOutputStream;
import com.luka.carplay.core.ScreenModule;
import com.luka.carplay.framework.Log;

/**
 * WheelZoomBridge — Bridges MMI Virtual Cockpit steering-wheel left-roller zoom deltas
 * to Apple CarPlay secondary display (AltScreen) via /tmp/mmi-mirror-wheel-zoom.events.
 *
 * libcarplay_altscreen.so polls this file and translates event records into
 * changeMapZoomLevel requests dispatched over the CarPlay private111 control plane.
 */
public final class WheelZoomBridge {
    private static final String TAG = "WheelZoom";
    private static final String EVENT_FILE = "/tmp/mmi-mirror-wheel-zoom.events";
    private static final String LOG_FILE = "/tmp/mmi-mirror-wheel-zoom.log";
    private static final long MAX_EVENT_BYTES = 262144L;
    private static final long MAX_LOG_BYTES = 262144L;
    private static final int MAX_STEPS_PER_CALLBACK = 16;

    private static boolean haveMagnification = false;
    private static int lastMagnification = 0;
    private static int sequence = 0;
    private static int eventEpoch = 0;
    private static boolean epochQueuePrepared = false;
    private static int callbackCount = 0;
    private static int seedCount = 0;
    private static int queuedCount = 0;
    private static int ignoredNotOwnedCount = 0;

    private WheelZoomBridge() {}

    /**
     * Called on steering-wheel roller magnification change from ClusterService.onMagnificationChanged.
     * When Context 80 (CarPlay cluster) is active, computes delta steps and appends to the event queue.
     */
    public static synchronized void onMagnificationChanged(int magnification) {
        callbackCount++;
        if (!haveMagnification) {
            haveMagnification = true;
            lastMagnification = magnification;
            seedCount++;
            diag("WHEEL_ZOOM_INPUT seed=1 magnification=" + magnification + " action=NONE");
            return;
        }

        int delta = magnification - lastMagnification;
        lastMagnification = magnification;

        if (delta == 0) return;

        // Gate: only send zoom deltas while the CarPlay AltScreen map (ctx 80) is really on the cluster
        if (!ScreenModule.isAltScreenShowing()) {
            ignoredNotOwnedCount++;
            diag("WHEEL_ZOOM_INPUT magnification=" + magnification + " delta=" + delta 
                + " action=IGNORED reason=cluster_not_owned_or_audi_map" + counterSummary());
            return;
        }

        int steps = Math.min(Math.abs(delta), MAX_STEPS_PER_CALLBACK);

        int direction = (delta < 0) ? 0 : 1; // 0 = ZOOM_IN, 1 = ZOOM_OUT
        String actionStr = (direction == 0) ? "ZOOM_IN" : "ZOOM_OUT";

        if (!rotateEventQueueIfNeeded()) {
            diag("WHEEL_ZOOM_INPUT magnification=" + magnification + " delta=" + delta 
                + " action=IGNORED reason=queue_rotate_failed");
            return;
        }

        if (sequence == Integer.MAX_VALUE) {
            sequence = 0;
            eventEpoch = (eventEpoch == Integer.MAX_VALUE) ? 1 : (eventEpoch + 1);
            try {
                new File(EVENT_FILE).delete();
            } catch (Throwable t) {}
            diag("WHEEL_ZOOM_EPOCH reason=sequence_wrap epoch=" + eventEpoch + " queue=reset");
        }

        sequence++;
        boolean queued = appendEvent(sequence, direction, magnification, delta, steps);
        if (queued) {
            queuedCount++;
        }

        diag("WHEEL_ZOOM_INPUT magnification=" + magnification + " delta=" + delta 
            + " action=" + actionStr + " direction=" + direction + " seq=" + sequence 
            + " steps=" + steps + " model=OEM_STEPS_V1 publish=" + (queued ? "queued" : "dropped") 
            + counterSummary());
    }

    public static synchronized void logCarPlayLifecycle(boolean active) {
        diag("WHEEL_LIFECYCLE carplay_session=" + (active ? "1" : "0")
            + " action=OBSERVE_ONLY have_magnification=" + haveMagnification
            + " seed_count=" + seedCount + " queued_count=" + queuedCount
            + " ignored_not_owned=" + ignoredNotOwnedCount);
        if (!active) {
            reset();
        }
    }

    public static synchronized void reset() {
        haveMagnification = false;
        lastMagnification = 0;
        try {
            new File(EVENT_FILE).delete();
        } catch (Throwable t) {}
        diag("WHEEL_ZOOM_RESET queue=cleared epoch=" + eventEpoch + " sequence_preserved=" + sequence);
    }

    private static String counterSummary() {
        return " callback_count=" + callbackCount + " have_magnification=" + haveMagnification 
            + " last_magnification=" + lastMagnification + " sequence=" + sequence + " epoch=" + eventEpoch;
    }

    private static int initialEpoch() {
        return (int)(System.currentTimeMillis() & 0x7FFFFFFFL);
    }

    private static void prepareEpochQueue() {
        if (!epochQueuePrepared) {
            epochQueuePrepared = true;
            eventEpoch = initialEpoch();
            sequence = 0;
            try {
                new File(EVENT_FILE).delete();
            } catch (Throwable t) {}
            diag("WHEEL_ZOOM_EPOCH reason=java_process_start epoch=" + eventEpoch);
        }
    }

    private static boolean rotateEventQueueIfNeeded() {
        prepareEpochQueue();
        try {
            File f = new File(EVENT_FILE);
            if (f.exists() && f.length() > MAX_EVENT_BYTES) {
                int nextEpoch = (eventEpoch == Integer.MAX_VALUE) ? 1 : (eventEpoch + 1);
                if (!f.delete()) {
                    diag("WHEEL_ZOOM_QUEUE result=dropped reason=queue_size_rotate_delete_failed bytes=" 
                        + f.length() + " epoch=" + eventEpoch);
                    return false;
                }
                eventEpoch = nextEpoch;
                sequence = 0;
                diag("WHEEL_ZOOM_EPOCH reason=queue_size_rotate epoch=" + eventEpoch + " queue=reset");
            }
            return true;
        } catch (Throwable t) {
            diag("WHEEL_ZOOM_QUEUE result=dropped reason=queue_size_rotate_failed error=" + t);
            return false;
        }
    }

    private static boolean appendEvent(int seq, int dir, int mag, int d, int steps) {
        FileOutputStream fos = null;
        try {
            File f = new File(EVENT_FILE);
            if (f.exists() && f.length() > MAX_EVENT_BYTES) {
                diag("WHEEL_ZOOM_QUEUE result=dropped reason=queue_size_race bytes=" + f.length() + " seq=" + seq);
                return false;
            }
            StringBuffer sb = new StringBuffer();
            sb.append("epoch=").append(eventEpoch)
              .append(" seq=").append(seq)
              .append(" direction=").append(dir)
              .append(" magnification=").append(mag)
              .append(" delta=").append(d)
              .append(" step=0 steps=").append(steps)
              .append(" model=OEM_STEPS_V1 commit=").append(seq)
              .append("\n");
            fos = new FileOutputStream(EVENT_FILE, true);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
            fos.close();
            fos = null;
            return true;
        } catch (Throwable t) {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
            diag("WHEEL_ZOOM_QUEUE result=dropped reason=write_failed error=" + t);
            return false;
        }
    }

    private static void diag(String msg) {
        Log.i(TAG, msg);   /* INFO: lands in carplay_java.log with a boot-relative timestamp, so zoom events can be lined up with the hook and sidecar logs */
        FileOutputStream fos = null;
        try {
            File f = new File(LOG_FILE);
            if (f.exists() && f.length() > MAX_LOG_BYTES) {
                f.delete();
            }
            fos = new FileOutputStream(LOG_FILE, true);
            StringBuffer sb = new StringBuffer();
            sb.append(msg).append("\n");
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
