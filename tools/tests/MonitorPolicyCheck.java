import com.zbox.ai.core.MonitorPolicy;
import com.zbox.ai.data.AppSettings;
import java.util.Arrays;

/** Tests real Kotlin policy/settings classes, without Android or new test dependencies. */
public class MonitorPolicyCheck {
    private static int checks;
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    private static void rejects(Runnable action, String name) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, name);
    }
    public static void main(String[] args) {
        MonitorPolicy p = MonitorPolicy.INSTANCE;
        check(p.connectionRoute(0) == MonitorPolicy.ConnectionRoute.HOTSPOT, "no USB routes to hotspot");
        check(p.connectionRoute(1) == MonitorPolicy.ConnectionRoute.USB_SINGLE, "one USB routes to direct/permission path");
        check(p.connectionRoute(2) == MonitorPolicy.ConnectionRoute.USB_CHOOSE, "multiple USB requires selection");
        rejects(() -> p.connectionRoute(-1), "invalid device count rejected");
        check(p.getIntervals().equals(Arrays.asList(33, 63, 500)), "exactly three profiles");
        check(new AppSettings().getLiveViewIntervalMs() == 33, "default is smooth 30 fps");
        check(p.normalizeInterval(60) == 63, "old smooth 16 becomes standard");
        check(p.normalizeInterval(150) == 63, "old standard becomes new standard");
        check(p.normalizeInterval(300) == 500, "removed economy maps to extreme economy");
        check(p.normalizeInterval(500) == 500, "extreme economy retained");
        check(p.normalizeInterval(33) == 33, "smooth 30 retained");
        check(p.normalizeInterval(63) == 63, "new standard retained");
        check(p.normalizeInterval(0) == 63 && p.normalizeInterval(999) == 63, "unknown stored values normalize safely");
        check(p.intervalLabel(33).equals("流畅 · 30fps"), "smooth label");
        check(p.intervalLabel(63).equals("标准 · 16fps"), "standard label");
        check(p.intervalLabel(500).equals("省电 · 2fps"), "extreme label");
        check(p.getIntervals().stream().noneMatch(v -> p.intervalLabel(v).contains("约")), "removed words absent");
        check(p.framePeriodNanos(33) == 33333333L, "30 fps frame period");
        check(p.framePeriodNanos(63) == 62500000L, "16 fps frame period");
        check(p.framePeriodNanos(500) == 500000000L, "2 fps frame period");
        check(p.remainingDelayMs(0, 63) == 63, "empty frame gets full standard budget");
        check(p.remainingDelayMs(50000000L, 63) == 13, "transfer/decode cost deducted");
        check(p.remainingDelayMs(62500000L, 63) == 0, "exact budget has no extra sleep");
        check(p.remainingDelayMs(90000000L, 63) == 0, "slow transfer never adds a full interval");
        check(p.remainingDelayMs(10000000L, 33) == 24, "smooth frame cost deducted");
        check(p.remainingDelayMs(12000000L, 500) == 488, "extreme frame cost deducted");
        rejects(() -> p.remainingDelayMs(-1, 63), "invalid elapsed time rejected");
        System.out.println("RESULT " + checks + " checks passed");
    }
}
