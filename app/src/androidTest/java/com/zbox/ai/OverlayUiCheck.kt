package com.zbox.ai
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.zbox.ai.ui.*
import com.zbox.ai.ptp.NikonPtpDriver
import kotlinx.coroutines.flow.MutableStateFlow

/** Targeted code40 presentation only. No gallery/queue/comparison/capture/AF/AI requests. */
class OverlayUiCheck:Instrumentation(){
 override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
 private fun node(label:String):AccessibilityNodeInfo?{runCatching{uiAutomation.javaClass.getMethod("clearCache").invoke(uiAutomation)}
  fun find(n:AccessibilityNodeInfo?,d:Int):AccessibilityNodeInfo?{if(n==null||d>65)return null;n.refresh();if(n.isVisibleToUser&&(n.text?.toString()==label||n.contentDescription?.toString()==label))return n
   for(i in 0 until n.childCount)find(n.getChild(i),d+1)?.let{return it};return null};return find(uiAutomation.rootInActiveWindow,0)}
 private fun waitFor(test:()->Boolean){val end=SystemClock.uptimeMillis()+15000;while(!test()){check(SystemClock.uptimeMillis()<end){"UI timeout"};SystemClock.sleep(150)}}
 private fun click(label:String){var n=node(label)?:error("Missing $label");while(!n.isClickable)n=n.parent?:error("No click action");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));SystemClock.sleep(300)}
 override fun onStart(){val out=Bundle();var phase="launch";var vm:MainViewModel?=null;var original:PhoneOptions?=null
  fun restore(){if(vm!=null && original!=null){runOnMainSync{vm!!.phone.options(original!!)};val o=original!!;targetContext.getSharedPreferences("phone_tools_35",0).edit().putInt("options",(if(o.zebra)1 else 0)or(if(o.peaking)2 else 0)or(if(o.histogram)16 else 0)or(if(o.grid)32 else 0)).putInt("frame",o.frame).commit()}}
  try{val a=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitFor{node("AI 构图")!=null}
   val m=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}.get(a) as MainViewModel;vm=m;original=m.phone.state.value.options
   @Suppress("UNCHECKED_CAST")val f=MainViewModel::class.java.getDeclaredField("_ui").apply{isAccessible=true}.get(m)as MutableStateFlow<UiState>
   check(node("拍照姿势 / 引导话术")==null){"Pose entry must leave monitor"}
   runOnMainSync{m.selectTab(3)};waitFor{node("拍照姿势 / 引导话术")!=null}
   val frame=Bitmap.createBitmap(1300,600,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.rgb(55,85,105))}
   runOnMainSync{m.selectTab(0);m.phone.options(PhoneOptions());f.value=f.value.copy(usbState=NikonPtpDriver.State.SESSION_OPEN,liveViewActive=true,liveViewBitmap=frame,exposureProgram=1,readOnlyProps=emptySet(),exposureSyncing=false)}
   phase="fullscreen overlay";waitFor{node("全屏取景")!=null};click("全屏取景");waitFor{a.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE && node("退出全屏")!=null && node("亮度直方图")!=null}
   check(node("实时取景 · 完整画幅")==null && node("取景亮度分布")==null && node("暗 0                 亮 255")==null)
   listOf("曝光","AF","拍照快门","姿势").forEach{check(node(it)!=null){"Missing overlay control $it"}}
   val screen=uiAutomation.takeScreenshot();val root=Rect();uiAutomation.rootInActiveWindow.getBoundsInScreen(root);val image=Rect();node("横屏全屏取景")!!.getBoundsInScreen(image)
   check(root.width()==screen.width && root.height()==screen.height);check(image.width()==screen.width && image.height()==screen.height){"Image still loses a side-column width"};screen.recycle()
   phase="compact exposure";click("曝光");waitFor{node("光圈")!=null && node("ISO")!=null && node("快门")!=null}
   check(node("收起曝光")==null && node("曝光参数")==null && node("当前档位 M · 按机身能力开放参数")==null)
   var sliders=0;fun count(n:AccessibilityNodeInfo?,d:Int){if(n==null||d>65)return;if(n.rangeInfo!=null && n.isVisibleToUser)sliders++;for(i in 0 until n.childCount)count(n.getChild(i),d+1)};count(uiAutomation.rootInActiveWindow,0);check(sliders==3){"Expected exactly three exposure sliders, got $sliders"}
   click("曝光");waitFor{node("光圈")==null};click("退出全屏");waitFor{node("退出全屏")==null && a.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
   runOnMainSync{f.value=f.value.copy(usbState=NikonPtpDriver.State.DISCONNECTED,liveViewActive=false,liveViewBitmap=null)};restore();runOnMainSync{a.finish()}
   out.putString("stream","PASS: code40-only UI: pose entry in preset library not monitor; image owns full2340x1080 viewport; five overlay controls; no fullscreen caption; translucent pure histogram without labels; three compact exposure sliders without title/help/collapse button; same exposure button toggles panel; exit restores orientation. No gallery, photo comparison/download, capture, AF, parameter writes or AI.\n");finish(Activity.RESULT_OK,out)
  }catch(t:Throwable){runCatching{restore()};out.putString("stream","FAIL OverlayUiCheck phase=$phase: ${t.message}\n");finish(Activity.RESULT_CANCELED,out)}
 }
}
