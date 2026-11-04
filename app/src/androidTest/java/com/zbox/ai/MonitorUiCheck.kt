package com.zbox.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zbox.ai.ptp.NikonPtpDriver
import com.zbox.ai.ui.MainViewModel
import com.zbox.ai.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/** Test-only frame injection. Never connects to a camera, presses capture/AF, or calls AI. */
class MonitorUiCheck:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun node(name:String):AccessibilityNodeInfo? {
        try{uiAutomation.javaClass.getMethod("clearCache").invoke(uiAutomation)}catch(_:ReflectiveOperationException){}
        fun find(n:AccessibilityNodeInfo?,depth:Int):AccessibilityNodeInfo? {
            if(n==null||depth>60)return null
            n.refresh()
            if(n.text?.toString()==name||n.contentDescription?.toString()==name)return n
            for(i in 0 until n.childCount)find(n.getChild(i),depth+1)?.let{return it}
            return null
        };return find(uiAutomation.rootInActiveWindow,0)
    }
    private fun button(name:String):AccessibilityNodeInfo {
        var n=node(name)?:error("Missing $name")
        while(n.className?.toString()!="android.widget.Button"&&!n.isClickable)n=n.parent?:error("Missing button parent $name")
        n.refresh();return n
    }
    private fun bounds(n:AccessibilityNodeInfo)=Rect().also{n.getBoundsInScreen(it)}
    private fun waitFor(test:()->Boolean){val end=SystemClock.uptimeMillis()+12000
        while(!test()){check(SystemClock.uptimeMillis()<end){"UI condition timed out"};SystemClock.sleep(120)}}
    private fun click(name:String){check(button(name).performAction(AccessibilityNodeInfo.ACTION_CLICK)){"Cannot click $name"};SystemClock.sleep(250)}
    override fun onStart(){val result=Bundle();var phase="launch"
        try {
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor{node("AI 构图")!=null}
            check(activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT){"Start test with phone in portrait"}
            val vm=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}.get(activity) as MainViewModel
            @Suppress("UNCHECKED_CAST") val flow=MainViewModel::class.java.getDeclaredField("_ui").apply{isAccessible=true}.get(vm) as MutableStateFlow<UiState>
            phase="aligned controls"
            val a=bounds(button("取景"));val b=bounds(button("AF"));val c=bounds(button("AI 构图"));val d=bounds(button("录像"))
            check(abs(a.centerY()-b.centerY())<=3&&abs(b.centerY()-c.centerY())<=3){"Buttons are not aligned"}
            check(abs(a.width()-b.width())<=3&&abs(b.width()-c.width())<=3){"Widths differ"}
            check(abs(a.height()-b.height())<=3&&abs(b.height()-c.height())<=3){"Heights differ"}
            check(a.right<=b.left&&b.right<=c.left&&c.right<=d.left){"Controls overlap"}
            check(abs(d.centerY()-a.centerY())<=3 && abs(d.width()-a.width())<=3 && abs(d.height()-a.height())<=3){"Movie button must share the compact row"}
            check(!button("AI 构图").isEnabled && !button("录像").isEnabled){"Disconnected actions should be disabled"}
            check(node("AI 构图 · 手动开启")==null){"Old label remains"}
            check(node("轻点单张")==null){"Left shutter explanation remains"}
            check(node("单击单张 · 长按连拍 · 松开停止")==null){"Redundant shutter instructions remain"}
            check(!button("全屏取景").isEnabled){"Fullscreen should require a live frame"}
            val shutter=bounds(node("拍照快门")?:error("Missing shutter semantics"))
            check(abs(shutter.centerX()-activity.resources.displayMetrics.widthPixels/2)<=4){"Shutter not centered"}
            val density=activity.resources.displayMetrics.density
            val upperGap=shutter.top-a.bottom
            check(upperGap>=0 && upperGap<=12*density+3){"Shutter upper gap not compact: ${upperGap/density}dp"}
            val exposure=bounds(node("曝光参数")?:error("Missing exposure title"))
            val lowerGap=exposure.top-shutter.bottom
            check(lowerGap>=0 && lowerGap<=24*density+3){"Shutter lower gap not compact: ${lowerGap/density}dp"}
            check(abs(shutter.width()/density-72)<=2 && abs(shutter.height()/density-72)<=2){"Shutter target shrank"}
            phase="mock connection and AI enable"
            val frame=Bitmap.createBitmap(900,600,Bitmap.Config.ARGB_8888)
            Canvas(frame).apply{drawColor(Color.rgb(22,45,65));val p=Paint().apply{color=Color.rgb(80,190,220);strokeWidth=5f}
                drawLine(300f,0f,300f,600f,p);drawLine(600f,0f,600f,600f,p);drawLine(0f,200f,900f,200f,p);drawLine(0f,400f,900f,400f,p)
                p.textSize=40f;drawText("UI TEST - NO CAMERA",160f,320f,p)}
            runOnMainSync{flow.value=flow.value.copy(usbState=NikonPtpDriver.State.SESSION_OPEN,liveViewActive=true,liveViewBitmap=frame)}
            SystemClock.sleep(700)
            waitFor{button("AI 构图").isEnabled}
            phase="connected manual AI open"
            click("AI 构图");waitFor{flow.value.compositionOpen}
            check(!flow.value.compositionBusy&&flow.value.compositionSnapshot==null){"Opening AI triggered work"}
            runOnMainSync{vm.clearComposition(close=true)}
            phase="mock live frame"
            runOnMainSync{flow.value=flow.value.copy(usbState=NikonPtpDriver.State.SESSION_OPEN,liveViewActive=true,liveViewBitmap=frame)}
            waitFor{button("全屏取景").isEnabled}
            phase="fullscreen enter and exit button"
            click("全屏取景")
            waitFor{activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE&&node("退出全屏")!=null}
            phase="fullscreen image semantic";waitFor{node("横屏全屏取景")!=null}
            check(node("拍照快门")!=null && node("曝光")!=null && node("姿势")!=null){"Fullscreen shooting controls missing"}
            phase="fullscreen exposure controls";click("曝光");waitFor{node("收起曝光")!=null && node("光圈")!=null}
            if(node("ISO")==null){
                var scroller=node("光圈")!!;while(!scroller.isScrollable)scroller=scroller.parent?:error("No exposure scroller")
                repeat(3){if(node("ISO")==null){scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);SystemClock.sleep(200)}}
                check(node("ISO")!=null){"ISO is not reachable within fullscreen exposure panel"}
                repeat(3){scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);SystemClock.sleep(150)}
            }
            click("收起曝光");waitFor{node("收起曝光")==null}
            val screen=uiAutomation.takeScreenshot();val rootBounds=bounds(uiAutomation.rootInActiveWindow)
            check(rootBounds.width()>=screen.width*.98 && rootBounds.height()>=screen.height*.98){"Dialog is not actually fullscreen: $rootBounds vs ${screen.width}x${screen.height}"};screen.recycle()
            phase="exit fullscreen orientation";click("退出全屏")
            waitFor{activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT&&node("退出全屏")==null}
            check(flow.value.liveViewActive&&flow.value.liveViewBitmap===frame){"Viewer changed the feed"}
            phase="back exits fullscreen"
            click("全屏取景");waitFor{activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE&&node("退出全屏")!=null};SystemClock.sleep(500)
            phase="back restores portrait"
            sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK));sendKeySync(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK))
            waitFor{activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT&&node("退出全屏")==null}
            phase="lost frame exits fullscreen"
            click("全屏取景");waitFor{activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE&&node("退出全屏")!=null};SystemClock.sleep(500)
            runOnMainSync{flow.value=flow.value.copy(usbState=NikonPtpDriver.State.DISCONNECTED,liveViewActive=false,liveViewBitmap=null)}
            waitFor{activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT&&node("退出全屏")==null}
            check(!flow.value.compositionBusy)
            runOnMainSync{activity.finish()}
            result.putString("stream","PASS: four equal-width/height aligned action buttons; disconnected AI and movie disabled; removed left and bottom shutter explanations; centered 72dp shutter with compact upper/lower gaps; AI open performs no analysis; fullscreen disabled without frame; landscape fullscreen with simulated frame; button/back exit restore portrait; lost frame exits and restores orientation. No camera commands or AI API calls.\n")
            finish(Activity.RESULT_OK,result)
        }catch(t:Throwable){
            val labels=mutableListOf<String>()
            fun dump(n:AccessibilityNodeInfo?,depth:Int){if(n==null||depth>45||labels.size>100)return;val rect=bounds(n);if(!n.text.isNullOrBlank()||!n.contentDescription.isNullOrBlank())labels.add("${n.text}/${n.contentDescription}@$rect");for(i in 0 until n.childCount)dump(n.getChild(i),depth+1)}
            dump(uiAutomation.rootInActiveWindow,0)
            runCatching{val screenshot=uiAutomation.takeScreenshot();targetContext.getExternalFilesDir(null)!!.resolve("phone39-fullscreen-failure.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()}
            result.putString("stream","FAIL phase=$phase: ${t.javaClass.simpleName}: ${t.message} LABELS: $labels\n");finish(Activity.RESULT_CANCELED,result)}
    }
}
