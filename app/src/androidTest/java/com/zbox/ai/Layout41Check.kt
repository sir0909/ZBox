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
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.zbox.ai.ui.*
import com.zbox.ai.ptp.NikonPtpDriver
import com.zbox.ai.core.TouchFocusPolicy
import com.zbox.ai.ai.CompositionRepository
import kotlinx.coroutines.flow.MutableStateFlow
class Layout41Check:Instrumentation(){
 override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
 private fun node(label:String):AccessibilityNodeInfo?{runCatching{uiAutomation.javaClass.getMethod("clearCache").invoke(uiAutomation)}
  fun find(n:AccessibilityNodeInfo?,d:Int):AccessibilityNodeInfo?{if(n==null||d>65)return null;n.refresh();if(n.isVisibleToUser&&(n.text?.toString()==label||n.contentDescription?.toString()==label))return n;for(i in 0 until n.childCount)find(n.getChild(i),d+1)?.let{return it};return null};return find(uiAutomation.rootInActiveWindow,0)}
 private fun waitFor(test:()->Boolean){val end=SystemClock.uptimeMillis()+15000;while(!test()){check(SystemClock.uptimeMillis()<end){"UI timeout"};SystemClock.sleep(150)}}
 private fun click(label:String){var n=node(label)?:error("Missing $label");while(!n.isClickable)n=n.parent?:error("No click action");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));SystemClock.sleep(300)}
 private fun bounds(label:String)=Rect().also{node(label)!!.getBoundsInScreen(it)}
 override fun onStart(){val out=Bundle();var phase="policy";var vm:MainViewModel?=null;var options:PhoneOptions?=null
  val prefs=targetContext.getSharedPreferences("pose39",0);val had=prefs.contains("downloadPromptSeen");val old=prefs.getBoolean("downloadPromptSeen",false)
  fun restore(){if(vm!=null && options!=null){runOnMainSync{vm!!.phone.options(options!!)};val o=options!!;targetContext.getSharedPreferences("phone_tools_35",0).edit().putInt("options",(if(o.zebra)1 else 0)or(if(o.peaking)2 else 0)or(if(o.histogram)16 else 0)or(if(o.grid)32 else 0)).putInt("frame",o.frame).commit()};prefs.edit().apply{if(had)putBoolean("downloadPromptSeen",old)else remove("downloadPromptSeen")}.commit()}
  try{
   check(TouchFocusPolicy.map(10f,500f,2340f,1080f,640,424)==null)
   val center=TouchFocusPolicy.map(1170f,540f,2340f,1080f,640,424)!!;check(kotlin.math.abs(center.first-.5f)<.001f && kotlin.math.abs(center.second-.5f)<.001f)
   check(TouchFocusPolicy.map(Float.NaN,1f,2340f,1080f,640,424)==null)
   check(TouchFocusPolicy.geometry(byteArrayOf(),640,424)==null)
   check(CompositionRepository.serviceError(401,"unauthorized_client_error")!!.contains("当前客户端"))
   check(CompositionRepository.serviceError(200,"<!doctype html>","text/html")!!.contains("验证网页"))
   check(CompositionRepository.serviceError(402,"insufficient_credits")!!.contains("额度"))
   check(CompositionRepository.serviceError(200,"{}","application/json")==null)
   prefs.edit().putBoolean("downloadPromptSeen",true).commit()
   phase="inline pose";val a=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitFor{node("AI 构图")!=null}
   val m=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}.get(a)as MainViewModel;vm=m;options=m.phone.state.value.options
   @Suppress("UNCHECKED_CAST")val f=MainViewModel::class.java.getDeclaredField("_ui").apply{isAccessible=true}.get(m)as MutableStateFlow<UiState>
   runOnMainSync{m.selectTab(3)};waitFor{node("拍照姿势 / 引导话术")!=null};check(node("摄影话术")==null);click("拍照姿势 / 引导话术");waitFor{node("摄影话术")!=null};check(node("关闭参考")==null);click("摄影话术");waitFor{node("换一句")!=null}
   fun screenshot(name:String){val b=uiAutomation.takeScreenshot();targetContext.getExternalFilesDir(null)!!.resolve(name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()};screenshot("phone41-pose-inline.png")
   click("拍照姿势 / 引导话术");waitFor{node("摄影话术")==null}
   phase="fullscreen positions";val frame=Bitmap.createBitmap(640,424,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(55,85,105))}
   runOnMainSync{m.selectTab(0);m.phone.options(PhoneOptions(zebra=false,peaking=false,histogram=true,grid=false));f.value=f.value.copy(usbState=NikonPtpDriver.State.SESSION_OPEN,liveViewActive=true,liveViewBitmap=frame,exposureProgram=1,readOnlyProps=emptySet(),exposureSyncing=false)}
   waitFor{node("全屏取景")!=null};click("全屏取景");waitFor{a.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE && node("亮度直方图")!=null}
   SystemClock.sleep(600);val shot=uiAutomation.takeScreenshot();val root=Rect();uiAutomation.rootInActiveWindow.getBoundsInScreen(root);check(root.width()==shot.width && root.height()==shot.height)
   val shutter=bounds("拍照快门");check(kotlin.math.abs(shutter.exactCenterY()-root.exactCenterY())<=3f){"Shutter must be centered vertically"}
   val fitRight=(shot.width+shot.height*640f/424)/2;check(shutter.left>=fitRight.toInt()){"Shutter must sit in right natural letterbox"}
   check(bounds("退出全屏").top<root.height()/5 && bounds("姿势").top<root.height()/5 && bounds("姿势").right<root.width()/3)
   check(node("AF")==null);val hist=bounds("亮度直方图");val blue=Color.blue(shot.getPixel(hist.right-8,hist.top+8));val base=Color.blue(shot.getPixel(root.width()/2,root.height()-20));targetContext.getExternalFilesDir(null)!!.resolve("phone41-layout-debug.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)}
   check(base>0 && blue.toFloat()/base in .60f.. .76f){"Histogram backing not translucent, blue=$blue base=$base rect=$hist"};shot.recycle()
   click("曝光");waitFor{node("光圈")!=null && node("快门")!=null};check(node("收起曝光")==null);click("曝光");
   runOnMainSync{f.value=f.value.copy(pendingFocusModePoint=.4f to .3f)};waitFor{node("取消，不更改")!=null};click("取消，不更改");waitFor{m.ui.value.pendingFocusModePoint==null}
   click("退出全屏");waitFor{node("退出全屏")==null}
   phase="queue presentation only";runOnMainSync{f.value=f.value.copy(usbState=NikonPtpDriver.State.DISCONNECTED,liveViewActive=false,liveViewBitmap=null);(a as ComponentActivity).setContent{ZboxTheme{PhoneGalleryTools(m.ui.value,m,false,{},true,{})}}}
   waitFor{node("关闭队列")!=null};check(node("下载队列")!=null);val start=bounds("开始");val pause=bounds("暂停全部");val clear=bounds("清理已结束");val temp=bounds("清理临时块")
   check(kotlin.math.abs(start.centerY()-pause.centerY())<5 && kotlin.math.abs(clear.centerY()-temp.centerY())<5 && clear.top>start.bottom)
   check(node("下载队列 0（点开始才下载）")==null);screenshot("phone41-queue-ui.png")
   restore();runOnMainSync{a.finish()};out.putString("stream","PASS: code41-only policy/error classification, inline pose fold/body, fullscreen complete-fit/left-top pose+exit/right-letterbox centered shutter/no AF button, darker histogram, compact three-slider exposure, queue fixed title/two button rows. No queue actions, gallery loading, comparison/download, capture, focus or AI requests.\n");finish(Activity.RESULT_OK,out)
  }catch(t:Throwable){runCatching{restore()};out.putString("stream","FAIL Layout41Check phase=$phase: ${t.message}\n");finish(Activity.RESULT_CANCELED,out)}
 }
}
