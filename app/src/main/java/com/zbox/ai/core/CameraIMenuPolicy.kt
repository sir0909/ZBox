package com.zbox.ai.core

/** Original scalar/string descriptor codec. No guessed types or writable values. */
object CameraIMenuPolicy{
 data class Value(val number:Long?=null,val text:String?=null)
 data class Descriptor(val code:Int,val type:Int,val writable:Boolean,val current:Value,val values:List<Value>)
 data class Definition(val id:String,val title:String,val readCode:Int,val writeCode:Int=readCode)
 val definitions=listOf(
  Definition("picture","优化校准",0xd200),Definition("quality","图像品质",0x5004),Definition("flash","闪光模式",0x500c),Definition("connection","连接状态",0,0),Definition("release","释放模式",0x5013),Definition("area","AF区域",0xd05d),
  Definition("wb","白平衡",0x5005),Definition("size","图像尺寸",0x5003),Definition("meter","测光",0x500b),Definition("adl","动态D-Lighting",0xd14e),Definition("vr","减震",0xd138),Definition("focus","对焦模式",0xd061))
 private class Reader(val b:ByteArray){var p=0
  fun u(n:Int):Long{require(p+n<=b.size);var v=0L;repeat(n){v=v or((b[p++].toLong()and 255)shl(it*8))};return v}
  fun value(type:Int):Value{
   if(type==0xffff){val count=u(1).toInt();require(count in 0..128 && p+count*2<=b.size);if(count==0)return Value(text="");val bytes=b.copyOfRange(p,p+count*2);p+=count*2;require(bytes[bytes.lastIndex].toInt()==0 && bytes[bytes.lastIndex-1].toInt()==0);return Value(text=bytes.copyOfRange(0,bytes.size-2).toString(Charsets.UTF_16LE))}
   val n=when(type){1,2->1;3,4->2;5,6->4;else->error("unsupported scalar")};var value=u(n)
   if(type in listOf(1,3,5) && value and(1L shl(n*8-1))!=0L)value-=1L shl(n*8)
   return Value(number=value)
  }
 }
 fun descriptor(bytes:ByteArray,expectedCode:Int):Descriptor?=runCatching{
  require(bytes.size<=16384);val r=Reader(bytes);val code=r.u(2).toInt();require(code==expectedCode);val type=r.u(2).toInt();val write=r.u(1);require(write in 0..1)
  r.value(type);val current=r.value(type);val form=r.u(1).toInt();val values=when(form){0->emptyList();1->{repeat(3){r.value(type)};emptyList()};2->{val count=r.u(2).toInt();require(count in 0..128);(0 until count).map{r.value(type)}};else->error("unknown form")}
  require(r.p==bytes.size);Descriptor(code,type,write==1L,current,values.distinct())
 }.getOrNull()
 fun value(bytes:ByteArray,type:Int):Value?=runCatching{val r=Reader(bytes);r.value(type).also{require(r.p==bytes.size)}}.getOrNull()
 fun encode(d:Descriptor,value:Value):ByteArray?=runCatching{
  require(d.writable && value in d.values)
  if(d.type==0xffff){val s=value.text?:error("string required");require(s.length<128 && !s.contains('\u0000'));val bytes=(s+'\u0000').toByteArray(Charsets.UTF_16LE);byteArrayOf((s.length+1).toByte())+bytes}
  else{val n=when(d.type){1,2->1;3,4->2;5,6->4;else->error("type")};val v=value.number?:error("number required");val signed=d.type in listOf(1,3,5);require(if(signed)v>=-(1L shl(n*8-1)) && v<(1L shl(n*8-1))else v>=0 && v<(1L shl(n*8)));ByteArray(n){(v shr(it*8)).toByte()}}
 }.getOrNull()
 /** Display order is independent of the wire enumeration; never changes values sent. */
 fun orderedValues(code:Int,descriptor:Descriptor,model:String):List<Value>{
  if(!model.replace(" ","").equals("Z30",true))return descriptor.values
  val order=when(code){0x5013->listOf(1L,0x8010L,2L,0x8019L,0x8011L);0xd061->listOf(5L,0L,1L,2L,4L);0xd14e->listOf(0xffffL,5L,4L,3L,2L,0L);0xd138->listOf(1L,2L,0L);else->emptyList()}
  return if(order.isEmpty())descriptor.values else descriptor.values.sortedBy{order.indexOf(it.number).takeIf{it>=0}?:Int.MAX_VALUE}
 }
 fun label(code:Int,value:Value?,descriptor:Descriptor?=null,model:String=""):String{
  val z30=model.replace(" ","").equals("Z30",ignoreCase=true)

  if(value==null)return "未读取";value.text?.let{return it.replace('x','×').take(40)};val n=value.number?:return "—"
  if(z30){
   val specific:Map<Long,String> = when(code){
    0x500c->mapOf(2L to "闪光灯关闭",4L to "防红眼",0x8010L to "补充闪光",0x8011L to "慢同步",0x8012L to "后帘同步",0x8013L to "慢同步+红眼")
    0x5013->mapOf(1L to "单张拍摄",0x8010L to "低速连拍",2L to "高速连拍",0x8019L to "高速连拍(延长)",0x8011L to "自拍")
    0xd05d->mapOf(0x8017L to "微点AF",0x8010L to "单点AF",2L to "动态区域AF",0x8018L to "宽区域AF(S)",0x8019L to "宽区域AF(L)",0x801aL to "宽区域AF(L-人物)",0x801bL to "宽区域AF(L-动物)",0x8011L to "自动区域AF",0x8020L to "自动区域AF(人物)",0x8021L to "自动区域AF(动物)")
    0xd14e->mapOf(0L to "关闭",2L to "低",3L to "标准",4L to "高",5L to "高+",0xffffL to "自动")
    0xd138->mapOf(0L to "关闭",1L to "Normal（标准）",2L to "Sport（运动）")
    0xd061->mapOf(5L to "AF-A（自动开关）",0L to "AF-S（单次AF）",1L to "AF-C（连续AF）",2L to "AF-F（全时AF）",4L to "MF（手动对焦）")
    else->emptyMap()
   }
   specific[n]?.let{return it}
   if(code==0xd200 && n in 101..120)return listOf("梦幻","清晨","流行","星期天","低沉","戏剧","静寂","漂白","忧郁","纯净","牛仔布","玩具","棕褐色","蓝色","红色","粉色","木炭","石墨","双色","黑碳")[(n-101).toInt()]
  }
  val map:Map<Long,String> = when(code){
   0xd200,0xd201->mapOf(1L to "标准",2L to "自然",3L to "鲜艳",4L to "单色",5L to "人像",6L to "风景",7L to "平面",8L to "自动")
   0x5004->if(descriptor?.type==2 && descriptor.values.mapNotNull{it.number}.toSet()==setOf(0L,2L,4L,7L,8L,10L,12L))mapOf(0L to "JPEG基本",2L to "JPEG标准",4L to "JPEG精细",7L to "RAW",8L to "RAW+基本",10L to "RAW+标准",12L to "RAW+精细")else emptyMap() // Z30 descriptor fingerprint + documented modern Nikon quality table; unknown table stays hex.
   0x5005->mapOf(1L to "手动",2L to "自动",4L to "晴天",5L to "荧光灯",6L to "白炽灯",7L to "闪光灯",0x8010L to "阴天",0x8011L to "阴影",0x8012L to "色温",0x8013L to "预设",0x8016L to "自然光自动")
   0x500b->mapOf(1L to "平均",2L to "中央重点",3L to "矩阵",4L to "点测光",0x8010L to "亮部重点测光")
   0x500c->mapOf(1L to "自动",2L to "关闭",3L to "补光",4L to "防红眼",5L to "防红眼补光",6L to "外部同步",0x8010L to "自动",0x8011L to "自动慢同步",0x8012L to "后帘慢同步",0x8013L to "防红眼慢同步")
   0x5013->mapOf(1L to "单张",2L to "连续",3L to "间隔",0x8010L to "低速连拍",0x8011L to "自拍")
   0xd05d->mapOf(2L to "动态区域",0x8010L to "单点AF",0x8011L to "自动区域AF",0x8018L to "宽区域(S)",0x8019L to "宽区域(L)")
   0xd061->mapOf(0L to "AF-S",1L to "AF-C",2L to "AF-F",3L to "MF(固定)",4L to "MF")
   0xd14e->when(descriptor?.type){1->mapOf(0L to "自动",1L to "关闭",2L to "低",3L to "标准",4L to "高",5L to "极高");2->mapOf(0L to "极高",1L to "高",2L to "标准",3L to "低",4L to "关闭",5L to "自动");else->emptyMap()}
   0xd138->emptyMap() // Label only after model-specific enum verification.
   else->emptyMap()
  }
  return map[n]?:if(code in listOf(0xd200,0xd201) && n in 0xc9..0xd1)"C-${n-0xc9+1}"else "0x%04X".format(n)
 }
}
