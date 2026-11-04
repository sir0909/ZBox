import com.zbox.ai.ptp.NativeBurstProtocol;
import com.zbox.ai.ptp.NativeBurstRequest;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Executed against the ACTUAL compiled Kotlin classes; no Android/JUnit/download required. */
public class NativeBurstProtocolCheck {
    private static int checks;
    private static byte[] hex(String s) {
        String[] a = s.split(" "); byte[] b = new byte[a.length];
        for (int i = 0; i < a.length; i++) b[i] = (byte)Integer.parseInt(a[i], 16);
        return b;
    }
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    private static void rejects(Runnable action, String name) {
        boolean rejected = false;
        try { action.run(); } catch (RuntimeException expected) { rejected = true; }
        check(rejected, name);
    }
    public static void main(String[] args) {
        NativeBurstProtocol p = NativeBurstProtocol.INSTANCE;
        byte[] mode = hex("13 50 04 00 01 01 00 02 00 02 05 00 01 00 02 00 10 80 11 80 19 80");
        byte[] count = hex("18 50 04 00 01 01 00 01 00 01 01 00 FF FF 01 00");
        NativeBurstProtocol.U16Prop m = p.descriptor(mode, 0x5013);
        NativeBurstProtocol.U16Prop c = p.descriptor(count, 0x5018);
        check(m.getWritable() && m.getCurrent() == 2 && m.getChoices().size() == 5, "real Z30 mode descriptor");
        check(m.allows(0x8019) && !m.allows(0x8018), "advertised enums only");
        check(c.frameLimit(100) == 100, "camera maximum honored");
        check(c.frameLimit(30) == 30, "lower camera maximum honored");
        check(c.frameLimit(null) == 100, "fallback never requests UINT16 unlimited burst");
        byte[] readonly = mode.clone(); readonly[4] = 0;
        check(!p.descriptor(readonly, 0x5013).getWritable(), "readonly descriptor");
        rejects(() -> p.descriptor(Arrays.copyOf(mode, 9), 0x5013), "truncated descriptor rejected");
        rejects(() -> p.descriptor(mode, 0x5018), "wrong property rejected");
        byte[] wrongType = mode.clone(); wrongType[2] = 2;
        rejects(() -> p.descriptor(wrongType, 0x5013), "non UINT16 rejected");
        byte[] enumOverrun = mode.clone(); enumOverrun[10] = 30;
        rejects(() -> p.descriptor(enumOverrun, 0x5013), "enum overrun rejected");
        byte[] zeroStep = count.clone(); zeroStep[14] = 0;
        rejects(() -> p.descriptor(zeroStep, 0x5018), "zero range step rejected");
        byte[] reversed = count.clone(); reversed[10] = 2; reversed[12] = 1; reversed[13] = 0;
        rejects(() -> p.descriptor(reversed, 0x5018), "reversed range rejected");
        rejects(() -> c.frameLimit(1), "single frame ceiling not advertised as burst");
        byte[] odd = count.clone(); odd[10] = 1; odd[12] = 100; odd[13] = 0; odd[14] = 3;
        check(p.descriptor(odd, 0x5018).frameLimit(99) == 97, "range step respected");
        ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short)100).putInt(10).putShort((short)100).put((byte)0);
        b.putShort((short)0).putInt(2).putShort((short)0x9207).putShort((short)0x920C);
        byte[] device = b.array();
        check(p.operations(device).containsAll(Arrays.asList(0x9207, 0x920C)), "DeviceInfo operations decoded");
        rejects(() -> p.operations(Arrays.copyOf(device, 18)), "truncated operations rejected");
        byte[] tooMany = device.clone(); ByteBuffer.wrap(tooMany).order(ByteOrder.LITTLE_ENDIAN).putInt(11, 2049);
        rejects(() -> p.operations(tooMany), "oversized operation list rejected");
        NativeBurstRequest request = new NativeBurstRequest();
        check(!request.getHeld(), "new request cannot fire");
        request.begin(); check(request.getHeld(), "long press arms request");
        request.release(true); check(!request.getHeld(), "release during setup prevents firing");
        request.begin(); request.release(false); request.release(true);
        check(!request.getHeld() && !request.getMayResumeLiveView(), "lifecycle cancellation is sticky");
        request.begin(); check(request.getHeld() && request.getMayResumeLiveView(), "new gesture resets resume policy");
        request.release(true); request.release(true); check(!request.getHeld(), "repeated release idempotent");
        System.out.println("RESULT " + checks + " checks passed");
    }
}
