package com.zbox.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import com.zbox.ai.ai.CompositionRepository
import com.zbox.ai.core.CompositionAdvice
import com.zbox.ai.core.CompositionPolicy
import com.zbox.ai.ui.MainViewModel
import com.zbox.ai.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Offline instrumentation only: no external API request, real credential use or camera command. */
class CompositionCheck: Instrumentation() {
    private var debug: (() -> String)? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments);start() }
    private fun node(text:String):AccessibilityNodeInfo? {
        try { uiAutomation.javaClass.getMethod("clearCache").invoke(uiAutomation) } catch(_:ReflectiveOperationException) { }
        fun find(n:AccessibilityNodeInfo?,depth:Int):AccessibilityNodeInfo? {
            if(n==null||depth>60)return null
            n.refresh()
            if(n.text?.toString()==text && n.isVisibleToUser) {
                val rect=android.graphics.Rect();n.getBoundsInScreen(rect)
                val height=targetContext.resources.displayMetrics.heightPixels
                if(!rect.isEmpty && rect.centerY()>height*.06 && rect.centerY()<height*.92)return n
            }
            for(i in 0 until n.childCount)find(n.getChild(i),depth+1)?.let{return it}
            return null
        };return find(uiAutomation.rootInActiveWindow,0)
    }
    private fun waitFor(test:()->Boolean) {
        val end=SystemClock.uptimeMillis()+10000
        while(!test()){check(SystemClock.uptimeMillis()<end){"UI timed out"};SystemClock.sleep(100)}
    }
    private fun shell(command:String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun scroll(forward:Boolean):Boolean {
        fun scrollable(n:AccessibilityNodeInfo?,depth:Int):AccessibilityNodeInfo? {
            if(n==null || depth>60)return null
            if(n.isScrollable && n.packageName?.toString()=="com.zbox.ai")return n
            for(i in 0 until n.childCount)scrollable(n.getChild(i),depth+1)?.let{return it}
            return null
        }
        val root=uiAutomation.rootInActiveWindow?:return false
        check(root.packageName?.toString()=="com.zbox.ai"){"Refuse gestures outside target application"}
        val n=scrollable(root,0)?:return false
        val ok=n.performAction(if(forward)AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        SystemClock.sleep(400)
        return ok
    }
    private fun bring(text:String) {
        if(node(text)!=null)return
        repeat(4){scroll(false)}
        repeat(15){if(node(text)!=null)return;scroll(true)}
        val labels=mutableListOf<String>()
        fun collect(n:AccessibilityNodeInfo?,depth:Int) { if(n==null||depth>60)return
            n.text?.toString()?.takeIf{it.isNotBlank()}?.let{labels.add(it.take(60))}
            for(i in 0 until n.childCount)collect(n.getChild(i),depth+1)
        };collect(uiAutomation.rootInActiveWindow,0)
        error("Cannot reveal $text; state="+debug?.invoke()+"; visible="+labels.take(60).joinToString(" | "))
    }
    private fun click(text:String) {
        bring(text)
        SystemClock.sleep(1600) // let scroll flings settle before hit-testing or clicking
        bring(text)
        var n=node(text)?:error("Missing $text")
        while(!n.isClickable)n=n.parent?:error("No clickable parent: $text")
        if(!n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val rect=android.graphics.Rect();n.getBoundsInScreen(rect)
            check(!rect.isEmpty){"Empty click bounds: $text"}
            shell("input tap ${rect.centerX()} ${rect.centerY()}")
        }
        SystemClock.sleep(200)
    }
    override fun onStart() {
        val result=Bundle();var phase="parse"
        try {
            fun payload(content:String)=JSONObject().put("usage",JSONObject().put("prompt_tokens",123).put("completion_tokens",45))
                .put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content",content)))).toString()
            val good="""{"summary":"向右小幅移动","moves":["稍微压低机位"],"tips":["避开边缘杂物"],"crop":{"left":0.1,"top":0.1,"right":0.9,"bottom":0.9},"subject":null}"""
            val parsed=CompositionRepository.parse(payload(good));check(parsed.advice!=null&&parsed.promptTokens==123L)
            check(CompositionRepository.parse(payload(good.replace("0.9","1.9"))).advice==null)
            check(CompositionRepository.parse(payload("not json")).promptTokens==123L)
            check(CompositionRepository.parse("bad response").advice==null)
            phase="launch"
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor { node("AI 构图")!=null }
            val vf=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}
            val vm=vf.get(activity) as MainViewModel
            val uf=MainViewModel::class.java.getDeclaredField("_ui").apply{isAccessible=true}
            @Suppress("UNCHECKED_CAST") val flow=uf.get(vm) as MutableStateFlow<UiState>
            debug={"open=${flow.value.compositionOpen},snapshot=${flow.value.compositionSnapshot!=null},advice=${flow.value.compositionAdvice!=null}"}
            check(!flow.value.compositionOpen&&!flow.value.compositionBusy)
            phase="manual open"
            runOnMainSync{vm.openComposition()};waitFor{flow.value.compositionOpen};bring("分析当前画面")
            SystemClock.sleep(300);check(!flow.value.compositionBusy&&flow.value.compositionSnapshot==null)
            phase="no camera preflight"
            click("分析当前画面");waitFor{flow.value.compositionError.isNotBlank()}
            check(!flow.value.compositionBusy&&flow.value.compositionSnapshot==null)
            click("清除建议");waitFor{flow.value.compositionError.isBlank()}
            phase="snapshot overlay and crop"
            val snapshot=Bitmap.createBitmap(640,426,Bitmap.Config.ARGB_8888)
            val advice=CompositionAdvice("向右小幅移动",listOf("稍微压低机位"),listOf("避开边缘杂物"),CompositionPolicy.box(.1,.1,.9,.9)!!,null)
            runOnMainSync{flow.value=flow.value.copy(compositionSnapshot=snapshot,compositionAdvice=advice)}
            SystemClock.sleep(300);check(flow.value.compositionOpen&&flow.value.compositionAdvice!=null){"Snapshot state unexpectedly cleared"};bring("预览推荐构图");phase="show crop";click("预览推荐构图")
            SystemClock.sleep(300);bring("裁切示意 · 未改变镜头变焦，也不改变实际照片范围")
            phase="clear and close"
            phase="clear result";click("清除建议");phase="clear result "+debug?.invoke();waitFor{flow.value.compositionAdvice==null&&flow.value.compositionSnapshot==null}
            phase="close panel";click("关闭");waitFor{!flow.value.compositionOpen}
            phase="reopen panel";runOnMainSync{vm.openComposition()};waitFor{flow.value.compositionOpen}
            check(flow.value.compositionAdvice==null&&!flow.value.compositionBusy)
            runOnMainSync{activity.finish()};waitFor{!flow.value.compositionOpen}
            result.putString("stream","PASS: parser bounds/usage, default off, manual panel open sends no request, no-camera preflight, snapshot/crop UI, clear, close, reopen and lifecycle clear. No cloud API or camera operation executed.\n")
            finish(Activity.RESULT_OK,result)
        } catch(t:Throwable) {
            try { val image=uiAutomation.takeScreenshot(); java.io.File(targetContext.getExternalFilesDir(null),"composition-ui.jpg").outputStream().use { image.compress(Bitmap.CompressFormat.JPEG,70,it) } } catch(_:Exception) { }
            result.putString("stream","FAIL phase=$phase: ${t.javaClass.simpleName}: ${t.message}; final=${debug?.invoke()}\n")
            finish(Activity.RESULT_CANCELED,result)
        }
    }
}
