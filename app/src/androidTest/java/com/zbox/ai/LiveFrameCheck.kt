package com.zbox.ai
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.zbox.ai.ui.*
import com.zbox.ai.ai.CompositionRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
/** One real live-view snapshot only. No camera-card reads/downloads, shutter, AF, parameter writes or API requests. */
class LiveFrameCheck:Instrumentation(){
 override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
 private fun node(label:String):AccessibilityNodeInfo?{runCatching{uiAutomation.javaClass.getMethod("clearCache").invoke(uiAutomation)}
  fun find(n:AccessibilityNodeInfo?,d:Int):AccessibilityNodeInfo?{if(n==null||d>65)return null;n.refresh();if(n.isVisibleToUser&&(n.text?.toString()==label||n.contentDescription?.toString()==label))return n;for(i in 0 until n.childCount)find(n.getChild(i),d+1)?.let{return it};return null};return find(uiAutomation.rootInActiveWindow,0)}
 private fun waitFor(ms:Long=90000,test:()->Boolean){val end=SystemClock.uptimeMillis()+ms;while(!test()){check(SystemClock.uptimeMillis()<end){"Timed out; no auto reconnect"};SystemClock.sleep(150)}}
 private fun click(label:String){var n=node(label)?:error("Missing $label");while(!n.isClickable)n=n.parent?:error("No click action");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));SystemClock.sleep(300)}
 override fun onStart(){val out=Bundle();val e=JSONObject();var vm:MainViewModel?=null;var started=false
  fun stop(){if(started && vm!=null)runOnMainSync{vm!!.stopLiveView()}}
  try{val a=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(700)
   val m=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}.get(a)as MainViewModel;vm=m
   if(!m.ui.value.isConnected)runOnMainSync{m.connectAutomatically{error("USB permission required")}}
   waitFor{m.ui.value.isConnected && !m.ui.value.wifiConnectBusy && !m.ui.value.usbConnectBusy && !m.ui.value.exposureSyncing}
   check(!m.ui.value.capturing && !m.ui.value.movieRecording && !m.phone.cameraBusy)
   if(!m.ui.value.liveViewActive){started=true;runOnMainSync{m.startLiveView()}}
   waitFor{m.ui.value.liveViewBitmap!=null && m.ui.value.liveViewFrames>=3}
   val snapshot=runBlocking{CompositionRepository.snapshot(m.ui.value.liveViewBitmap!!)}
   val dir=targetContext.getExternalFilesDir(null)!!;if(!dir.resolve("phone40-ai-frame.jpg").exists())dir.resolve("phone40-ai-frame.jpg").outputStream().use{check(snapshot.compress(Bitmap.CompressFormat.JPEG,65,it))}else e.put("existingAiPreviewRetained",true)
   e.put("source","current real live-view frame, not a card photo").put("model",m.ui.value.deviceInfo.model).put("framePixels",JSONArray(listOf(snapshot.width,snapshot.height)));snapshot.recycle()
   waitFor{node("全屏取景")!=null};click("全屏取景");waitFor{node("退出全屏")!=null && node("亮度直方图")!=null}
   SystemClock.sleep(800)
   fun screenshot(name:String){val s=uiAutomation.takeScreenshot();val rect=Rect();uiAutomation.rootInActiveWindow.getBoundsInScreen(rect);check(rect.width()==s.width && rect.height()==s.height);dir.resolve(name).outputStream().use{s.compress(Bitmap.CompressFormat.PNG,100,it)};e.put("fullscreenBounds",rect.flattenToString());s.recycle()}
   screenshot("phone40-fullscreen.png");click("曝光");waitFor{node("光圈")!=null && node("ISO")!=null && node("快门")!=null};check(node("收起曝光")==null);screenshot("phone40-exposure.png");click("曝光");click("退出全屏");waitFor{node("退出全屏")==null}
   stop();e.put("cameraCardReads",0).put("cameraShutterPresses",0).put("photoCompareTests",0).put("photoDownloadTests",0).put("apiRequests",0)
   dir.resolve("phone40-live-frame-result.json").writeText(e.toString(2));out.putString("stream","PASS: current real Z30 live-frame export and code40 overlay/exposure screenshots only. EVIDENCE: $e\n");finish(Activity.RESULT_OK,out)
  }catch(t:Throwable){runCatching{stop()};out.putString("stream","FAIL LiveFrameCheck: ${t.message} EVIDENCE: $e\n");finish(Activity.RESULT_CANCELED,out)}
 }
}
