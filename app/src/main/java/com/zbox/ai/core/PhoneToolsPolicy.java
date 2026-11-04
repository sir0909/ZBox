package com.zbox.ai.core;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** Actual production phone-only policy, also executable by the existing desktop JDK. */
public final class PhoneToolsPolicy {
    private PhoneToolsPolicy() {}
    public static final int MAX_QUEUE=24, CHUNK_BYTES=4*1024*1024, MIN_CHUNK_BYTES=512*1024;
    public static int chunkLength(long remaining,int target){
        if(remaining<=0 || target<MIN_CHUNK_BYTES || target>CHUNK_BYTES)throw new IllegalArgumentException("Invalid chunk bounds");
        return (int)Math.min(remaining,(long)target);
    }
    public static int smallerReadChunk(int current){for(int next:new int[]{2*1024*1024,1024*1024,MIN_CHUNK_BYTES})if(next<current)return next;return 0;}
    public static boolean canReduceReadChunk(int responseCode,int requested,int dataBytes){
        return (responseCode==0x2006 || responseCode==0x201D) && requested>MIN_CHUNK_BYTES && dataBytes==0;
    }
    private static String timeKey(String value){String digits=value==null?"":value.replaceAll("[^0-9]","");return digits.length()>=8?digits:"";}
    /** Compare newest first; missing dates fall back to sequence and handle, without overflow. */
    public static int compareNewest(String dateA,long sequenceA,long handleA,String dateB,long sequenceB,long handleB){
        int time=timeKey(dateB).compareTo(timeKey(dateA));if(time!=0)return time;
        int seq=Long.compare(sequenceB,sequenceA);return seq!=0?seq:Long.compare(handleB,handleA);
    }
    public static final class Analysis {
        public final int[] histogram,overlay; public final int clipped,pixels;
        Analysis(int[] h,int[] o,int c,int p){histogram=h;overlay=o;clipped=c;pixels=p;}
    }
    public static Analysis analyze(int[] argb,int w,int h,boolean zebra,boolean peaking){
        if(w<1||h<1||w>640||h>640||argb==null||argb.length!=w*h)throw new IllegalArgumentException("Invalid image bounds");
        int[] luma=new int[argb.length],hist=new int[256],overlay=new int[argb.length];int clipped=0;
        for(int i=0;i<argb.length;i++){int c=argb[i],y=(((c>>>16)&255)*77+((c>>>8)&255)*150+(c&255)*29)>>>8;luma[i]=y;hist[y]++;if(y>=245)clipped++;}
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=y*w+x;
            if(zebra&&luma[i]>=245&&(x+y)%9<3)overlay[i]=0xC0FFE040;
            if(peaking&&x>0&&x<w-1&&y>0&&y<h-1&&Math.abs(luma[i+1]-luma[i-1])+Math.abs(luma[i+w]-luma[i-w])>=80)overlay[i]=0xD000E5FF;
        }
        return new Analysis(hist,overlay,clipped,argb.length);
    }
    public static String hash(String text){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte v:d)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    public static String photoKey(String camera,long storage,String name,long size,String time){return hash(camera+"|"+storage+"|"+name+"|"+size+"|"+time);}
    /** Whitelist stage/code vocabulary only. Never forward arbitrary log text. */
    public static List<String> diagnosticStages(List<String> lines){String[] stages={"CommandTCP","CommandSettle","InitCommand","EventTCP","EventSettle","InitEvent","OpenSession","GetDeviceInfo"};LinkedHashSet<String> out=new LinkedHashSet<>();
        for(int i=Math.max(0,lines.size()-180);i<lines.size();i++){String line=lines.get(i),stage=null;for(String s:stages)if(line.contains(s)){stage=s;break;}if(stage==null)continue;
            Matcher m=Pattern.compile("0x[0-9a-fA-F]{4}\\b").matcher(line);String code=m.find()?m.group().toUpperCase(Locale.ROOT):"";
            out.add((stage+" "+(line.contains("失败")||line.contains("拒绝")||line.contains("FAILED")?"失败":"记录")+" "+code).trim());
        }List<String> result=new ArrayList<>(out);return new ArrayList<>(result.subList(Math.max(0,result.size()-12),result.size()));
    }
    public static String advice(List<String> stages,boolean connected){
        if(connected)return "会话已建立；操作码通告不代表功能实测。首次电脑配置才需要主机注册。";
        for(String s:stages)if(s.contains("201E"))return "相机报告旧会话；在机身结束旧连接后手动重试，不抢占或自动重放。";
        for(String s:stages)if(s.startsWith("InitCommand")&&s.contains("失败"))return "命令初始化失败；核对当前连接模式、机身提示及其它客户端占用。";
        for(String s:stages)if(s.startsWith("InitEvent")&&s.contains("失败"))return "事件通道失败；不要视为完整拍摄连接，保存阶段报告。";
        for(String s:stages)if(s.startsWith("OpenSession")&&s.contains("失败"))return "相机拒绝会话；核对当前菜单/固件及机身确认提示。";
        return "先确认相机实际加入同一热点/局域网，再扫描一次；不必重置机身或更换主机身份。";
    }
    public static final class NumericDesc {
        public final int type;public final boolean writable;public final long current;public final Long min,max,step;public final List<Long> choices;
        NumericDesc(int t,boolean w,long c,Long lo,Long hi,Long s,List<Long> ch){type=t;writable=w;current=c;min=lo;max=hi;step=s;choices=Collections.unmodifiableList(ch);}
        public boolean accepts(long v){if(!writable||!fits(type,v))return false;if(!choices.isEmpty())return choices.contains(v);if(min!=null&&max!=null)return v>=min&&v<=max&&(step==null||step<=0||(v-min)%step==0);return true;}
    }
    public static int width(int type){switch(type){case 1:case 2:return 1;case 3:case 4:return 2;case 5:case 6:return 4;default:return 0;}}
    public static boolean fits(int type,long v){switch(type){case 1:return v>=-128&&v<=127;case 2:return v>=0&&v<=255;case 3:return v>=-32768&&v<=32767;case 4:return v>=0&&v<=65535;case 5:return v>=Integer.MIN_VALUE&&v<=Integer.MAX_VALUE;case 6:return v>=0&&v<=0xffffffffL;default:return false;}}
    public static byte[] encode(int type,long value){if(!fits(type,value))throw new IllegalArgumentException("Out of range");byte[] a=new byte[width(type)];for(int i=0;i<a.length;i++)a[i]=(byte)(value>>>(i*8));return a;}
    public static Long scalar(byte[] bytes,int type){int n=width(type);if(n==0||bytes==null||bytes.length<n)return null;long v=0;for(int i=0;i<n;i++)v|=(bytes[i]&255L)<<(i*8);if(type%2==1&&(v&(1L<<(n*8-1)))!=0)v-=1L<<(n*8);return v;}
    private static long read(ByteBuffer b,int type){byte[] a=new byte[width(type)];b.get(a);return Objects.requireNonNull(scalar(a,type));}
    public static NumericDesc descriptor(byte[] bytes,int expectedCode){try{
        if(bytes==null||bytes.length<8)return null;ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);if((b.getShort()&65535)!=expectedCode)return null;
        int type=b.getShort()&65535,getSet=b.get()&255,n=width(type);if(n==0||getSet>1||b.remaining()<2*n+1)return null;
        read(b,type);long current=read(b,type);int form=b.get()&255;Long min=null,max=null,step=null;List<Long> choices=new ArrayList<>();
        if(form==1){if(b.remaining()<3*n)return null;min=read(b,type);max=read(b,type);step=read(b,type);if(min>max||step<0)return null;}
        else if(form==2){if(b.remaining()<2)return null;int count=b.getShort()&65535;if(count<1||count>512||b.remaining()<count*n)return null;for(int i=0;i<count;i++)choices.add(read(b,type));}
        else if(form!=0)return null;
        return new NumericDesc(type,getSet==1,current,min,max,step,choices);
    }catch(RuntimeException e){return null;}}
    public static boolean modeAllows(int code,int mode){if(mode<1||mode>4)return false;switch(code){case 0x5007:return mode==1||mode==3;case 0x500D:return mode==1||mode==4;case 0x5010:return mode!=1;default:return true;}}
}
