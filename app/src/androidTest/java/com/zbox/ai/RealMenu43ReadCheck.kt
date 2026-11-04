package com.zbox.ai
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.zbox.ai.ui.MainViewModel
import com.zbox.ai.ptp.HexLog
import com.zbox.ai.ptp.HexLogEntry
import com.zbox.ai.core.CameraIMenuPolicy as P
import org.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.ConcurrentHashMap
class RealMenu43ReadCheck:Instrumentation(){
 override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
 private fun waitFor(test:()->Boolean){val end=SystemClock.uptimeMillis()+90000;while(!test()){check(SystemClock.uptimeMillis()<end){"Timed out"};SystemClock.sleep(150)}}
 private fun node(label:String):AccessibilityNodeInfo?{runCatching{uiAutomation.javaClass.getMethod("clearCache").invoke(uiAutomation)};fun find(n:AccessibilityNodeInfo?,d:Int):AccessibilityNodeInfo?{if(n==null||d>65)return null;n.refresh();if(n.isVisibleToUser&&(n.text?.toString()==label||n.contentDescription?.toString()==label))return n;for(i in 0 until n.childCount)find(n.getChild(i),d+1)?.let{return it};return null};return find(uiAutomation.rootInActiveWindow,0)}
 private fun click(label:String){var n=node(label)?:error("Missing $label");while(!n.isClickable)n=n.parent?:error("No click action");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));SystemClock.sleep(400)}
 override fun onStart(){val out=Bundle();var vm:MainViewModel?=null;val auditScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
  try{val a=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(700)
   val m=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}.get(a)as MainViewModel;vm=m
   if(!m.ui.value.isConnected)runOnMainSync{m.connectAutomatically{error("USB permission needed")}}
   waitFor{m.ui.value.isConnected && !m.ui.value.wifiConnectBusy && !m.ui.value.usbConnectBusy && !m.ui.value.exposureSyncing}
   runOnMainSync{m.startLiveView()};waitFor{m.ui.value.liveViewFrames>=3 && m.ui.value.liveViewBitmap!=null};waitFor{node("全屏取景")!=null};click("全屏取景");waitFor{a.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE && node("菜单")!=null}
   fun screenshot(name:String){SystemClock.sleep(1200);val b=uiAutomation.takeScreenshot();targetContext.getExternalFilesDir(null)!!.resolve(name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
   screenshot("phone43-fullscreen-real.png")
   val seq=HexLog.entries.value.lastOrNull()?.seq?:0L
   val observed=ConcurrentHashMap<Long,String>();val audit=auditScope.launch{HexLog.entries.collect{logs->logs.filter{it.seq>seq && (it.direction==HexLogEntry.Direction.TX || (it.title=="PTP/IP" && it.note.startsWith("→ ")))}.forEach{observed[it.seq]=it.title+" "+it.note}}}
   click("菜单");waitFor{!m.ui.value.cameraMenuBusy && m.ui.value.cameraMenuEntries.any{it.current!=null}}
   check(m.ui.value.cameraMenuEntries.size==12);check(node("AF区域")!=null && node("对焦模式")!=null);screenshot("phone43-imenu-real.png")
   val entries=JSONArray();m.ui.value.cameraMenuEntries.forEach{e->entries.put(JSONObject().put("id",e.definition.id).put("title",e.definition.title).put("readCode","0x%04X".format(e.definition.readCode)).put("writeCode","0x%04X".format(e.definition.writeCode)).put("type",e.descriptor?.type).put("writable",e.descriptor?.writable?:false).put("enabled",e.editable).put("currentNumber",e.current?.number).put("currentText",e.current?.text).put("currentLabel",P.label(e.definition.readCode,e.current,e.descriptor,m.ui.value.deviceInfo.model)).put("enumNumbers",JSONArray(e.descriptor?.values?.map{it.number?:it.text}.orEmpty())).put("reason",e.reason))}
   check(node("相机快捷菜单 · i")==null && node("关闭菜单")==null && node("确认修改")==null)
   val picture=m.ui.value.cameraMenuEntries.first{it.definition.id=="picture"};check(picture.editable && picture.descriptor?.code==0xd200)
   for((title,label) in listOf("释放模式" to "高速连拍(延长)","AF区域" to "自动区域AF(动物)","动态D-Lighting" to "高+","减震" to "Sport（运动）","对焦模式" to "AF-A（自动开关）","优化校准" to "标准")){
    click(title);waitFor{node(label)!=null};check(node("确认修改")==null);if(title=="释放模式")screenshot("phone43-release-mode-real.png");click("返回");waitFor{node("AF区域")!=null && node("返回")==null}
   }
   sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitFor{node("AF区域")==null};val frames=m.ui.value.liveViewFrames;waitFor{m.ui.value.liveViewFrames>frames+2}
   click("姿势");waitFor{node("关闭参考")!=null};screenshot("phone43-pose-glass-real.png");click("关闭参考");waitFor{node("关闭参考")==null}
   SystemClock.sleep(150);audit.cancel();val forbidden=observed.values.filter{title->listOf("0x1016","0x90C1","0x9205","0x100E","0x9207","0x90C0","0x1009","0x101B","0x9431","0x9530","→ SetDevicePropValue ","→ NikonChangeAfArea ","→ InitiateCapture ","→ GetObject ","→ NikonGetPartialObjectEx ","→ NikonGetPartialImage ").any{title.contains(it)}};check(observed.size>=12 && observed.values.any{it.contains("GetDevicePropDesc") || it.contains("0x1014")}){"No actual menu request evidence captured"};check(forbidden.isEmpty()){"Forbidden commands: $forbidden"}

   val evidence=JSONObject().put("model",m.ui.value.deviceInfo.model).put("entries",entries).put("forbiddenTransactions",forbidden.size).put("observedTxCount",observed.size).put("auditType","continuous USB TX + PTP/IP operation observer, not only final ring buffer").put("menuReadOnly",true).put("physicalParameterWritesTested",false).put("editableCount",m.ui.value.cameraMenuEntries.count{it.editable}).put("physicalAfDrives",0).put("physicalShutterPresses",0).put("aiRequests",0).put("liveViewResumedAfterRead",true).put("message",m.ui.value.cameraMenuMessage)
   targetContext.getExternalFilesDir(null)!!.resolve("phone43-real-menu-evidence.json").writeText(evidence.toString(2));click("退出全屏");runOnMainSync{m.stopLiveView()};out.putString("stream","PASS: code43 real camera READ ONLY menu + glass/vertical controls screenshots; no parameter writes, AF, shutter, card downloads or AI. EVIDENCE: $evidence\n");auditScope.cancel();finish(Activity.RESULT_OK,out)
  }catch(t:Throwable){runCatching{runOnMainSync{vm?.stopLiveView()}};out.putString("stream","FAIL RealMenu43ReadCheck: ${t.message}\n");auditScope.cancel();finish(Activity.RESULT_CANCELED,out)}
 }
}
