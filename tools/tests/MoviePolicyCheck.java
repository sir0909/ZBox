import com.zbox.ai.ptp.MoviePolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class MoviePolicyCheck {
    static int checks;
    static void check(boolean b, String name) { if (!b) throw new AssertionError(name); checks++; System.out.println("PASS " + name); }
    static void reject(Runnable r, String name) { boolean bad=false; try {r.run();} catch (IllegalArgumentException | IllegalStateException expected) {bad=true;} check(bad,name); }
    static byte[] descriptor(int form, byte[] domain) {
        ByteBuffer b=ByteBuffer.allocate(8+domain.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short)0xD1A6).putShort((short)2).put((byte)1).put((byte)0).put((byte)0).put((byte)form).put(domain);
        return b.array();
    }
    public static void main(String[] args) {
        MoviePolicy p=MoviePolicy.INSTANCE;
        byte[] e=descriptor(2,new byte[]{2,0,0,1});
        check(p.permitsU8(e,0xD1A6,0),"photo selector advertised");
        check(p.permitsU8(e,0xD1A6,1),"movie selector advertised");
        check(!p.permitsU8(e,0xD1A6,2),"unadvertised selector refused");
        byte[] ro=e.clone();ro[4]=0;check(!p.permitsU8(ro,0xD1A6,1),"readonly mode not forced");
        byte[] type=e.clone();type[2]=4;reject(()->p.permitsU8(type,0xD1A6,1),"wrong descriptor type rejected");
        reject(()->p.permitsU8(e,0xD1F0,1),"wrong property rejected");
        reject(()->p.permitsU8(Arrays.copyOf(e,11),0xD1A6,1),"truncated enumeration rejected");
        reject(()->p.permitsU8(new byte[7],0xD1A6,1),"short header rejected");
        reject(()->p.permitsU8(e,0xD1A6,256),"out of range byte rejected");
        byte[] range=descriptor(1,new byte[]{2,8,2});
        check(p.permitsU8(range,0xD1A6,6),"range step accepted");
        check(!p.permitsU8(range,0xD1A6,5),"invalid range step refused");
        check(!p.permitsU8(range,0xD1A6,1),"range lower bound honored");
        reject(()->p.permitsU8(descriptor(1,new byte[]{2,8,0}),0xD1A6,2),"zero range step rejected");
        reject(()->p.permitsU8(descriptor(2,new byte[]{0,0}),0xD1A6,1),"empty enum rejected");
        check(p.prohibitReason(0).isEmpty(),"zero prohibition is ready");
        check(p.prohibitReason(1).contains("未插存储卡"),"missing card explained");
        check(p.prohibitReason(8).contains("已满"),"full card explained");
        check(p.prohibitReason(0x4000).contains("应用模式"),"observed Z30 application-mode failure explained");
        check(p.prohibitReason(0x2000).contains("录像模式"),"physical selector failure explained");
        check(p.prohibitReason(9).contains("未插存储卡") && p.prohibitReason(9).contains("已满"),"combined prohibitions preserved");
        check(p.prohibitReason(0x80000000L).contains("80000000"),"unknown bits retained for diagnosis");
        System.out.println("RESULT "+checks+" checks passed");
    }
}
