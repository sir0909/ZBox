package com.zbox.ai
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zbox.ai.ui.MainViewModel
import com.zbox.ai.ptp.NikonPtpDriver
import com.zbox.ai.core.TouchFocusPolicy
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
/** One real touch-focus chain only; durable marker prevents an accidental rerun. No capture/AI/card reads. */
class SingleAreaFocusCheck:Instrumentation(){
 override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
 private fun waitFor(ms:Long=90000,test:()->Boolean){val end=SystemClock.uptimeMillis()+ms;while(!test()){check(SystemClock.uptimeMillis()<end){"Timed out; no auto reconnect/retry"};SystemClock.sleep(150)}}
 private fun node(label:String):AccessibilityNodeInfo?{runCatching{uiAutomation.javaClass.getMethod("clearCache").invoke(uiAutomation)}
  fun find(n:AccessibilityNodeInfo?,d:Int):AccessibilityNodeInfo?{if(n==null||d>65)return null;n.refresh();if(n.isVisibleToUser&&(n.text?.toString()==label||n.contentDescription?.toString()==label))return n;for(i in 0 until n.childCount)find(n.getChild(i),d+1)?.let{return it};return null};return find(uiAutomation.rootInActiveWindow,0)}
 private fun click(label:String){var n=node(label)?:error("Missing $label");while(!n.isClickable)n=n.parent?:error("No click action");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));SystemClock.sleep(300)}
 override fun onStart(){val out=Bundle();val e=JSONObject();var vm:MainViewModel?=null;var driver:NikonPtpDriver?=null;var old:TouchFocusPolicy.Geometry?=null;var token:Any?=null;var attempted=false;var restored=false;var originalMode=0x8011
  val marker=targetContext.filesDir.resolve("code41-single-area-focus-marker.json")
  fun restore(){if(attempted && !restored && old!=null && driver!=null){val g=old!!;val ok=runBlocking{driver!!.changeAfArea(g.afX,g.afY,token)}.isSuccess;val modeRestored=runBlocking{driver!!.setTouchAfMode(originalMode,token)}.isSuccess;e.put("originalAfAreaRestoreAcknowledged",ok).put("originalAfModeRestoreVerified",modeRestored);restored=ok && modeRestored}}
  try{check(!marker.exists()){ "Real focus already attempted. Do not repeat; inspect saved evidence." }
   val a=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(700)
   val m=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}.get(a) as MainViewModel;vm=m
   if(!m.ui.value.isConnected)runOnMainSync{m.connectAutomatically{error("USB permission needed")}}
   waitFor{m.ui.value.isConnected && !m.ui.value.wifiConnectBusy && !m.ui.value.usbConnectBusy && !m.ui.value.exposureSyncing}
   runOnMainSync{m.startLiveView()};waitFor{m.ui.value.liveViewFrames>=3 && m.ui.value.liveViewBitmap!=null}
   val d=MainViewModel::class.java.getDeclaredField("driver").apply{isAccessible=true}.get(m)as NikonPtpDriver;driver=d;token=d.burstConnectionToken
   val frame=m.ui.value.liveViewBitmap!!;val g=TouchFocusPolicy.geometry(d.lastLiveViewHeader,frame.width,frame.height)?:error("Unverified geometry; will not focus");old=g
   val originalDescriptor=runBlocking{d.getDevicePropDesc(0xd05d)}.getOrThrow();originalMode=TouchFocusPolicy.afModeDescriptor(originalDescriptor)!!.current;e.put("originalAfMode",originalMode)
   check(0x9205 in m.ui.value.deviceInfo.supportedOperations);waitFor{node("全屏取景")!=null};click("全屏取景");waitFor{node("退出全屏")!=null && node("亮度直方图")!=null};SystemClock.sleep(600)
   val rect=Rect();uiAutomation.rootInActiveWindow.getBoundsInScreen(rect);val fit=minOf(rect.width().toFloat()/frame.width,rect.height().toFloat()/frame.height)
   val w=frame.width*fit;val h=frame.height*fit;val nx=.50f;val ny=.35f;val x=(rect.width()-w)/2+w*nx;val y=(rect.height()-h)/2+h*ny
   val expected=TouchFocusPolicy.target(g,nx,ny)!!;val before=m.ui.value.touchFocusRequest
   e.put("source","real fullscreen image touch, not calling ViewModel directly").put("framePixels",JSONArray(listOf(frame.width,frame.height))).put("afCoordinateDomain",JSONArray(listOf(g.width,g.height))).put("oldAfCenter",JSONArray(listOf(g.afX,g.afY))).put("requestedAfCenter",JSONArray(listOf(expected.first,expected.second))).put("fullscreenBounds",rect.flattenToString())
   marker.writeText(e.put("attempted",true).toString());attempted=true
   val time=SystemClock.uptimeMillis();check(uiAutomation.injectInputEvent(MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,x,y,0),true));check(uiAutomation.injectInputEvent(MotionEvent.obtain(time,time+70,MotionEvent.ACTION_UP,x,y,0),true))
   waitFor(10000){node("切换为单点并对焦")!=null};click("切换为单点并对焦")
   waitFor(25000){m.ui.value.touchFocusRequest!=before && !m.ui.value.touchFocusBusy && node("切换为单点并对焦")==null}
   val frames=m.ui.value.liveViewFrames;waitFor(10000){m.ui.value.liveViewFrames>=frames+3}
   val next=TouchFocusPolicy.geometry(d.lastLiveViewHeader,frame.width,frame.height)?:error("No geometry readback")
   e.put("actualAfCenter",JSONArray(listOf(next.afX,next.afY))).put("focusMessage",m.ui.value.touchFocusMessage).put("afDriveTransactions",1)
   val near=kotlin.math.abs(next.afX-expected.first)<=g.afWidth/2 && kotlin.math.abs(next.afY-expected.second)<=g.afHeight/2
   val moved=kotlin.math.abs(next.afX-g.afX)>32 || kotlin.math.abs(next.afY-g.afY)>32
   e.put("afAreaReadbackNearRequested",near).put("afAreaActuallyMoved",moved)
   check(near && moved && m.ui.value.touchFocusMoved){"AF area readback mismatch: $e"};check(m.ui.value.touchFocusMessage.contains("AF已触发")){"Area moved but AF failed: $e"}
   restore();check(restored);val n=m.ui.value.liveViewFrames;waitFor(10000){m.ui.value.liveViewFrames>n+2};val back=TouchFocusPolicy.geometry(d.lastLiveViewHeader,frame.width,frame.height)!!
   e.put("restoredAfCenter",JSONArray(listOf(back.afX,back.afY)));e.put("autoAreaMayReselectAfterRestore",originalMode==0x8011);if(originalMode!=0x8011)check(kotlin.math.abs(back.afX-g.afX)<32 && kotlin.math.abs(back.afY-g.afY)<32)
   fun screenshot(name:String){val b=uiAutomation.takeScreenshot();targetContext.getExternalFilesDir(null)!!.resolve(name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
   screenshot("phone41-fullscreen-real.png");click("曝光");waitFor{node("光圈")!=null && node("快门")!=null};SystemClock.sleep(500);screenshot("phone41-exposure-real.png");click("曝光");click("退出全屏");waitFor{node("退出全屏")==null};runOnMainSync{m.stopLiveView()}
   e.put("shutterPresses",0).put("cameraCardReads",0).put("photoComparisonTests",0).put("photoDownloadTests",0).put("apiRequests",0).put("opticalFocusConvergenceConfirmed",false)
   marker.writeText(e.toString(2));targetContext.getExternalFilesDir(null)!!.resolve("phone41-touch-focus-result.json").writeText(e.toString(2));out.putString("stream","PASS: real touch changed AF region with frame-header readback + AF acknowledgement; original AF region restored. No shutter, photo comparison/download or API. EVIDENCE: $e\n");finish(Activity.RESULT_OK,out)
  }catch(t:Throwable){runCatching{restore()};runCatching{runOnMainSync{vm?.stopLiveView()}};if(attempted)marker.writeText(e.put("failure",t.message).toString(2));out.putString("stream","FAIL SingleAreaFocusCheck: ${t.message} EVIDENCE: $e\n");finish(Activity.RESULT_CANCELED,out)}
 }
}
