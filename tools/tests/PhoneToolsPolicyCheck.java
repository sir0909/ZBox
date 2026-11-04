import com.zbox.ai.core.PhoneToolsPolicy;
import java.util.*;
import java.nio.*;
public class PhoneToolsPolicyCheck {
    static int checks=0;
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);checks++;}
    static byte[] desc(int code,int type,int writable,int form,long[] values){ByteBuffer b=ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);b.putShort((short)code).putShort((short)type).put((byte)writable).put(PhoneToolsPolicy.encode(type,0)).put(PhoneToolsPolicy.encode(type,values[0])).put((byte)form);
        if(form==2)b.putShort((short)(values.length-1));for(int i=1;i<values.length;i++)b.put(PhoneToolsPolicy.encode(type,values[i]));return Arrays.copyOf(b.array(),b.position());}
    public static void main(String[] args){
        for(int type=1;type<=6;type++){long[] candidates={-2147483648L,-32768,-129,-128,-1,0,1,127,128,255,256,32767,32768,65535,65536,2147483647L,2147483648L,0xffffffffL};
            for(long v:candidates)if(PhoneToolsPolicy.fits(type,v)){check(Objects.equals(PhoneToolsPolicy.scalar(PhoneToolsPolicy.encode(type,v),type),v),"Numeric roundtrip");}else{boolean rejected=false;try{PhoneToolsPolicy.encode(type,v);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Integer bounds");}}
        int[] white=new int[100];Arrays.fill(white,0xffffffff);var a=PhoneToolsPolicy.analyze(white,10,10,true,false);check(a.clipped==100,"Clipped count");check(a.histogram[255]==100,"Luma white");check(Arrays.stream(a.overlay).anyMatch(v->v!=0),"Zebra exists");
        var disabled=PhoneToolsPolicy.analyze(white,10,10,false,false);check(Arrays.stream(disabled.overlay).allMatch(v->v==0),"Disabled no effect");
        int[] edge=new int[100];for(int y=0;y<10;y++)for(int x=0;x<10;x++)edge[y*10+x]=x<5?0xff000000:0xffffffff;
        var e=PhoneToolsPolicy.analyze(edge,10,10,false,true);check(Arrays.stream(e.overlay).anyMatch(v->v!=0),"Edge effect");check(Arrays.stream(e.histogram).sum()==100,"Histogram conservation");
        for(int w:new int[]{0,-1,641,1000000}){boolean rejected=false;try{PhoneToolsPolicy.analyze(new int[1],w,1,true,true);}catch(IllegalArgumentException ex){rejected=true;}check(rejected,"Image bounds");}
        List<String> logs=Arrays.asList("InitCommand 失败 IP192.168.1.5 PIN123456 API_KEY secret serial ABCD", "OpenSession 失败 0x201E PRIVATE-CREDENTIAL", "SSID=private");String report=PhoneToolsPolicy.diagnosticStages(logs).toString();
        for(String forbidden:Arrays.asList("192.168","123456","secret","ABCD","PRIVATE","SSID"))check(!report.contains(forbidden),"Whitelisted export");check(report.contains("0X201E"),"Code retained");check(PhoneToolsPolicy.advice(PhoneToolsPolicy.diagnosticStages(logs),false).contains("旧会话"),"Session guidance");
        String k=PhoneToolsPolicy.photoKey("bodyA",1,"DSC001.JPG",123,"20260101");check(k.length()==64,"Private key length");check(!k.equals(PhoneToolsPolicy.photoKey("bodyB",1,"DSC001.JPG",123,"20260101")),"Camera separation");check(!k.equals(PhoneToolsPolicy.photoKey("bodyA",1,"DSC001.JPG",124,"20260101")),"Source separation");
        int code=0x500F;byte[] range=desc(code,4,1,1,new long[]{100,100,1600,100});var r=PhoneToolsPolicy.descriptor(range,code);check(r!=null&&r.accepts(200)&&!r.accepts(250)&&!r.accepts(1700),"Numeric range/step");
        var enumeration=PhoneToolsPolicy.descriptor(desc(code,4,1,2,new long[]{100,100,400,800}),code);check(enumeration!=null&&enumeration.accepts(400)&&!enumeration.accepts(200),"Enum validation");
        check(!PhoneToolsPolicy.descriptor(desc(code,4,0,0,new long[]{100}),code).accepts(200),"Readonly");check(PhoneToolsPolicy.descriptor(range,code+1)==null,"Descriptor identity");
        for(int len=0;len<range.length;len++)check(PhoneToolsPolicy.descriptor(Arrays.copyOf(range,len),code)==null,"Truncated descriptor");
        for(int mode=0;mode<=6;mode++){check(PhoneToolsPolicy.modeAllows(0x5007,mode)==(mode==1||mode==3),"Aperture mode");check(PhoneToolsPolicy.modeAllows(0x500D,mode)==(mode==1||mode==4),"Shutter mode");check(PhoneToolsPolicy.modeAllows(0x5010,mode)==(mode>=2&&mode<=4),"EV mode");}
        check(PhoneToolsPolicy.descriptor(desc(code,4,2,0,new long[]{100}),code)==null,"Invalid GetSet");
        check(PhoneToolsPolicy.CHUNK_BYTES==4*1024*1024,"4MiB target, not decimal MB");
        check(PhoneToolsPolicy.MIN_CHUNK_BYTES==512*1024,"Bounded compatibility floor");
        for(long remaining:new long[]{1,513,512*1024,4*1024*1024,4*1024*1024+1L,9*1024*1024+113L,0xffffffffL}){
            int count=PhoneToolsPolicy.chunkLength(remaining,PhoneToolsPolicy.CHUNK_BYTES);
            check(count>0 && count<=PhoneToolsPolicy.CHUNK_BYTES && count<=remaining,"Tail and maximum bounds");
        }
        int next=PhoneToolsPolicy.CHUNK_BYTES;for(int expected:new int[]{2*1024*1024,1024*1024,512*1024,0}){
            next=PhoneToolsPolicy.smallerReadChunk(next);check(next==expected,"Deterministic read-only reduction");}
        for(int response:new int[]{0x2001,0x2005,0x2003,0x201E,0x2007})check(!PhoneToolsPolicy.canReduceReadChunk(response,4*1024*1024,0),"No retry on success/unsupported/session/I-O failure");
        for(int response:new int[]{0x2006,0x201D}){
            check(PhoneToolsPolicy.canReduceReadChunk(response,4*1024*1024,0),"Explicit empty parameter rejection may reduce");
            check(!PhoneToolsPolicy.canReduceReadChunk(response,512*1024,0),"Never retry below minimum");
            check(!PhoneToolsPolicy.canReduceReadChunk(response,4*1024*1024,1),"Never retry partially returned data");
        }
        long remaining=9*1024*1024+113L,total=remaining;int blocks=0;long written=0;
        while(remaining>0){int n=PhoneToolsPolicy.chunkLength(remaining,PhoneToolsPolicy.CHUNK_BYTES);remaining-=n;written+=n;blocks++;}
        check(blocks==3 && written==total,"4MiB split preserves exact byte count");
        check(PhoneToolsPolicy.compareNewest("2026-11-03 12:01",1,1,"2026-11-02 22:00",9,99)<0,"Latest date always first");
        check(PhoneToolsPolicy.compareNewest("2026-11-03",2,1,"2026-11-03",1,999)<0,"Sequence tie-break descending");
        check(PhoneToolsPolicy.compareNewest("",1,Long.MAX_VALUE,"",1,1)<0,"Handle fallback without overflow");
        check(PhoneToolsPolicy.compareNewest("—",1,999,"2026-01-01",1,1)>0,"Unknown date sorted after known");
        check(PhoneToolsPolicy.compareNewest("20261103T120100",1,1,"2026-11-03 12:00:00",1,1)<0,"PTP and display dates normalize");
        System.out.println("PASS "+checks+" production phone policy checks. No camera, network or Android device used.");
    }
}
