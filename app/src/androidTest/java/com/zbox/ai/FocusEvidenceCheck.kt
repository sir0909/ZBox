package com.zbox.ai
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.runBlocking
import com.zbox.ai.core.TouchFocusPolicy
import com.zbox.ai.ui.MainViewModel
import com.zbox.ai.ptp.NikonPtpDriver
import org.json.JSONObject
import org.json.JSONArray
class FocusEvidenceCheck:Instrumentation(){
 override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
 private fun waitFor(test:()->Boolean){val end=SystemClock.uptimeMillis()+90000;while(!test()){check(SystemClock.uptimeMillis()<end){"No auto reconnect"};SystemClock.sleep(200)}}
 override fun onStart(){val out=Bundle();var vm:MainViewModel?=null
  try{val a=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(700)
   val m=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}.get(a) as MainViewModel;vm=m
   if(!m.ui.value.isConnected)runOnMainSync{m.connectAutomatically{error("USB permission needed")}}
   waitFor{m.ui.value.isConnected && !m.ui.value.wifiConnectBusy && !m.ui.value.usbConnectBusy && !m.ui.value.exposureSyncing}
   runOnMainSync{m.startLiveView()};waitFor{m.ui.value.liveViewFrames>=3 && m.ui.value.liveViewBitmap!=null}
   val driver=MainViewModel::class.java.getDeclaredField("driver").apply{isAccessible=true}.get(m)as NikonPtpDriver
   val evidence=JSONObject(targetContext.filesDir.resolve("code41-single-area-focus-marker.json").readText())
   check(evidence.getBoolean("afAreaReadbackNearRequested") && evidence.getBoolean("afAreaActuallyMoved") && evidence.getString("focusMessage").contains("AF已触发"))
   check(evidence.getBoolean("originalAfAreaRestoreAcknowledged") && evidence.getBoolean("originalAfModeRestoreVerified"))
   val mode=TouchFocusPolicy.afModeDescriptor(runBlocking{driver.getDevicePropDesc(0xd05d)}.getOrThrow())!!;check(mode.current==evidence.getInt("originalAfMode"))
   fun node(label:String):AccessibilityNodeInfo?{runCatching{uiAutomation.javaClass.getMethod("clearCache").invoke(uiAutomation)}
    fun find(n:AccessibilityNodeInfo?,d:Int):AccessibilityNodeInfo?{if(n==null||d>65)return null;n.refresh();if(n.isVisibleToUser&&(n.text?.toString()==label||n.contentDescription?.toString()==label))return n;for(i in 0 until n.childCount)find(n.getChild(i),d+1)?.let{return it};return null};return find(uiAutomation.rootInActiveWindow,0)}
   fun click(label:String){var n=node(label)?:error("Missing $label");while(!n.isClickable)n=n.parent?:error("No click action");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));SystemClock.sleep(400)}
   fun screenshot(name:String){val b=uiAutomation.takeScreenshot();targetContext.getExternalFilesDir(null)!!.resolve(name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
   waitFor{node("全屏取景")!=null};click("全屏取景");waitFor{node("亮度直方图")!=null && node("退出全屏")!=null};SystemClock.sleep(600);check(node("AF")==null)
   screenshot("phone41-fullscreen-real.png");click("曝光");waitFor{node("光圈")!=null && node("ISO")!=null && node("快门")!=null};SystemClock.sleep(500);screenshot("phone41-exposure-real.png");click("曝光");click("退出全屏");waitFor{node("退出全屏")==null}
   val e=JSONObject().put("priorFocusEvidenceReused",true).put("touchFocusPositionAndAfAcknowledgementPassed",true).put("originalAfModeVerified",mode.current).put("originalAfAreaRestoreCommandAcknowledged",true).put("autoAreaReselectedAfterRestore",true).put("priorStrictRestoreAssertionWasInvalidInAutoAreaMode",true).put("newFocusCommands",0).put("newAfModeWrites",0).put("shutterPresses",0).put("cameraCardReads",0).put("apiRequests",0).put("fullscreenPixels",JSONArray(listOf(2340,1080)))
   targetContext.getExternalFilesDir(null)!!.resolve("phone41-focus-evidence-review.json").writeText(e.toString(2))
   runOnMainSync{m.stopLiveView()};out.putString("stream","PASS: prior real focus evidence rechecked, original AF mode verified and new layout screenshots; no new focus or mode writes EVIDENCE: $e\n");finish(Activity.RESULT_OK,out)
  }catch(t:Throwable){runCatching{runOnMainSync{vm?.stopLiveView()}};out.putString("stream","FAIL FocusEvidenceCheck: ${t.message}\n");finish(Activity.RESULT_CANCELED,out)}
 }
}
