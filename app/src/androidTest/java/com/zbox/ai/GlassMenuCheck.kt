package com.zbox.ai
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import com.zbox.ai.ui.*
import com.zbox.ai.ptp.NikonPtpDriver
import com.zbox.ai.core.CameraIMenuPolicy as P
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
class GlassMenuCheck:Instrumentation(){
 override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
 private fun node(label:String):AccessibilityNodeInfo?{runCatching{uiAutomation.javaClass.getMethod("clearCache").invoke(uiAutomation)};fun find(n:AccessibilityNodeInfo?,d:Int):AccessibilityNodeInfo?{if(n==null||d>65)return null;n.refresh();if(n.isVisibleToUser&&(n.text?.toString()==label||n.contentDescription?.toString()==label))return n;for(i in 0 until n.childCount)find(n.getChild(i),d+1)?.let{return it};return null};return find(uiAutomation.rootInActiveWindow,0)}
 private fun waitFor(test:()->Boolean){val end=SystemClock.uptimeMillis()+15000;while(!test()){check(SystemClock.uptimeMillis()<end){"UI timeout"};SystemClock.sleep(150)}}
 private fun click(label:String){var n=node(label)?:error("Missing $label");while(!n.isClickable)n=n.parent?:error("No click action");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));SystemClock.sleep(350)}
 private fun bounds(label:String)=Rect().also{node(label)!!.getBoundsInScreen(it)}
 private fun tap(x:Float,y:Float){val t=SystemClock.uptimeMillis();sendPointerSync(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0));sendPointerSync(MotionEvent.obtain(t,t+90,MotionEvent.ACTION_UP,x,y,0));SystemClock.sleep(400)}
 private fun policy(){
  fun desc(code:Int,type:Int,w:Int,current:Long,values:List<Long>):ByteArray{val b=ByteArrayOutputStream();fun u(v:Long,n:Int){repeat(n){b.write((v shr(it*8)).toInt())}};u(code.toLong(),2);u(type.toLong(),2);u(w.toLong(),1);val n=when(type){1,2->1;3,4->2;5,6->4;else->1};u(current,n);u(current,n);u(2,1);u(values.size.toLong(),2);values.forEach{u(it,n)};return b.toByteArray()}
  val d=P.descriptor(desc(0xd05d,4,1,0x8011,listOf(0x8010,0x8011)),0xd05d)!!;check(d.current.number==0x8011L);check(P.encode(d,P.Value(number=0x8010))!!.contentEquals(byteArrayOf(0x10,0x80.toByte())))
  check(P.encode(d,P.Value(number=0x7777))==null);check(P.encode(d.copy(writable=false),d.current)==null)
  for(type in 1..6){val value=if(type%2==1)-1L else 127L;val dd=P.descriptor(desc(0x5004,type,1,value,listOf(value)),0x5004)!!;val bytes=P.encode(dd,dd.current)!!;check(P.value(bytes,type)==dd.current);check(P.value(bytes+byteArrayOf(0),type)==null)}
  val raw=desc(0xd05d,4,1,0x8011,listOf(0x8010,0x8011));for(i in raw.indices)check(P.descriptor(raw.copyOf(i),0xd05d)==null);check(P.descriptor(raw,0xd061)==null);check(P.descriptor(raw+byteArrayOf(1),0xd05d)==null)
  check(P.descriptor(desc(0x5004,8,1,1,listOf(1)),0x5004)==null);check(P.descriptor(desc(0x5004,2,2,1,listOf(1)),0x5004)==null)
  val sd=P.Descriptor(0x5003,0xffff,true,P.Value(text="5568x3712"),listOf(P.Value(text="5568x3712"),P.Value(text="")));val encoded=P.encode(sd,sd.current)!!;check(P.value(encoded,0xffff)==sd.current);check(P.encode(sd,sd.values[1])!!.contentEquals(byteArrayOf(1,0,0)));check(P.label(0xd061,P.Value(number=5))=="0x0005");check(P.label(0xd05d,P.Value(number=0x8020))=="0x8020")
  val full=byteArrayOf(3,0x50,0xff.toByte(),0xff.toByte(),1)+encoded+encoded+byteArrayOf(2,1,0)+encoded;check(P.descriptor(full,0x5003)?.current==sd.current)
  val checker=Bitmap.createBitmap(512,320,Bitmap.Config.ARGB_8888);for(y in 0 until 320)for(x in 0 until 512)checker.setPixel(x,y,if((x/8+y/8)%2==0)Color.WHITE else Color.BLACK);val blurred=blurCameraPreview(checker);check(maxOf(blurred.width,blurred.height)<=256);val v=Color.blue(blurred.getPixel(blurred.width/2,blurred.height/2));check(v in 70..185);checker.recycle();blurred.recycle()
 }
 override fun onStart(){val out=Bundle();var phase="codec";var m:MainViewModel?=null;var oldOptions:PhoneOptions?=null
  val prefs=targetContext.getSharedPreferences("pose39",0);val had=prefs.contains("downloadPromptSeen");val old=prefs.getBoolean("downloadPromptSeen",false)
  fun restore(){prefs.edit().apply{if(had)putBoolean("downloadPromptSeen",old)else remove("downloadPromptSeen")}.commit();if(m!=null && oldOptions!=null){runOnMainSync{m!!.phone.options(oldOptions!!)};val o=oldOptions!!;targetContext.getSharedPreferences("phone_tools_35",0).edit().putInt("options",(if(o.zebra)1 else 0)or(if(o.peaking)2 else 0)or(if(o.histogram)16 else 0)or(if(o.grid)32 else 0)).putInt("frame",o.frame).commit()}}
  try{policy();prefs.edit().putBoolean("downloadPromptSeen",true).commit();val a=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitFor{node("AI 构图")!=null}
   val vm=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}.get(a)as MainViewModel;m=vm;oldOptions=vm.phone.state.value.options
   val driver=MainViewModel::class.java.getDeclaredField("driver").apply{isAccessible=true}.get(vm)as NikonPtpDriver;check(!driver.isOpen){"UI-only test must not connect"}
   @Suppress("UNCHECKED_CAST")val f=MainViewModel::class.java.getDeclaredField("_ui").apply{isAccessible=true}.get(vm)as MutableStateFlow<UiState>
   val frame=Bitmap.createBitmap(640,424,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(60,100,160))}
   runOnMainSync{vm.phone.options(PhoneOptions(zebra=false,peaking=false,histogram=false,grid=false));f.value=f.value.copy(usbState=NikonPtpDriver.State.SESSION_OPEN,liveViewActive=true,liveViewBitmap=frame)}
   phase="vertical controls";waitFor{node("全屏取景")!=null};click("全屏取景");waitFor{a.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE && node("i")!=null};SystemClock.sleep(600)
   val exit=bounds("退出全屏");val pose=bounds("姿势");val root=Rect();uiAutomation.rootInActiveWindow.getBoundsInScreen(root);check(pose.top>=exit.bottom && kotlin.math.abs(pose.centerX()-exit.centerX())<=3);check(bounds("i").top>root.height()*.75 && bounds("i").left>root.width()*.8);check(node("AF")==null)
   phase="glass";val before=uiAutomation.takeScreenshot();click("姿势");waitFor{node("关闭参考")!=null};SystemClock.sleep(1200);val after=uiAutomation.takeScreenshot();val x=(root.width()*.8).toInt();val y=(root.height()*.88).toInt();val ratio=Color.blue(after.getPixel(x,y)).toFloat()/Color.blue(before.getPixel(x,y));check(ratio in .35f.. .75f){"Glass must show camera color, ratio=$ratio"};before.recycle();after.recycle()
   sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitFor{node("关闭参考")==null};check(node("退出全屏")!=null)
   click("姿势");waitFor{node("关闭参考")!=null};tap(root.width()*.08f,root.height()*.65f);waitFor{node("关闭参考")==null};check(node("退出全屏")!=null)
   phase="menu presentation no writes";val entries=P.definitions.map{d->val c=P.Value(number=if(d.id=="area")0x8011 else 1);val desc=if(d.id=="connection")null else P.Descriptor(d.writeCode,4,d.id!="picture",c,if(d.id=="area")listOf(P.Value(number=0x8010),c)else listOf(c,P.Value(number=2)));CameraMenuEntry(d,c,desc,"测试数据")}
   runOnMainSync{f.value=f.value.copy(cameraMenuEntries=entries,cameraMenuMessage="UI测试数据 · 相机未连接")};click("i");waitFor{node("相机快捷菜单 · i")!=null};P.definitions.forEach{check(node(it.title)!=null)}
   click("AF区域");waitFor{node("确认修改")!=null};check(node("单点AF")!=null);click("单点AF");check(node("确认修改")!=null);check(f.value.cameraMenuEntries.first{it.definition.id=="area"}.current?.number==0x8011L)
   click("返回菜单");waitFor{node("相机快捷菜单 · i")!=null};click("关闭菜单");waitFor{node("相机快捷菜单 · i")==null};check(node("退出全屏")!=null);check(!driver.isOpen);click("退出全屏");restore();runOnMainSync{a.finish()};out.putString("stream","PASS: code42 bounded typed descriptor codec/invalid values/strings/read-only gates/CPU blur, vertically stacked upper-left controls/lower-right i, camera-color glass, Back/outside dismissal, 12 real-menu template tiles and AF editor selection WITHOUT confirmation. No camera connection, property writes, AF, shutter, downloads or AI.\n");finish(Activity.RESULT_OK,out)
  }catch(t:Throwable){runCatching{restore()};out.putString("stream","FAIL GlassMenuCheck phase=$phase: ${t.message}\n");finish(Activity.RESULT_CANCELED,out)}
 }
}
