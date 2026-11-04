package com.zbox.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.zbox.ai.ai.LlmProvider
import com.zbox.ai.ai.ProviderAccount
import com.zbox.ai.ui.MainViewModel
import com.zbox.ai.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow

/** Offline only. Injects a blank test provider into memory, never reads/saves real credentials. */
class CollapsedUiCheck:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun node(name:String):AccessibilityNodeInfo? {
        try{uiAutomation.javaClass.getMethod("clearCache").invoke(uiAutomation)}catch(_:ReflectiveOperationException){}
        fun find(n:AccessibilityNodeInfo?,depth:Int):AccessibilityNodeInfo? {
            if(n==null||depth>60)return null
            n.refresh()
            val label=n.text?.toString() ?: n.contentDescription?.toString()
            if(label==name || (name=="内置预设" && label?.startsWith("内置预设（")==true))return n
            for(i in 0 until n.childCount)find(n.getChild(i),depth+1)?.let{return it}
            return null
        };return find(uiAutomation.rootInActiveWindow,0)
    }
    private fun visible(name:String):Boolean {
        val n=node(name)?:return false;val r=Rect();n.getBoundsInScreen(r)
        val h=targetContext.resources.displayMetrics.heightPixels
        return n.isVisibleToUser&&!r.isEmpty&&r.centerY()>h*.12&&r.centerY()<h*.86
    }
    private fun shell(s:String){android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(s)).use{it.readBytes()}}
    private fun scroll(forward:Boolean){val d=targetContext.resources.displayMetrics
        val from=(d.heightPixels*(if(forward).8 else .3)).toInt();val to=(d.heightPixels*(if(forward).3 else .8)).toInt()
        shell("input swipe ${d.widthPixels/2} $from ${d.widthPixels/2} $to 220");SystemClock.sleep(350)}
    private fun bring(name:String){if(visible(name))return;repeat(4){scroll(false)}
        repeat(14){if(visible(name))return;scroll(true)};error("Cannot reveal requested control: $name")}
    private fun click(name:String){bring(name);SystemClock.sleep(450);bring(name)
        var n=node(name)?:error("Missing requested control")
        while(!n.isClickable)n=n.parent?:error("No clickable parent for $name")
        check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)){"Click failed: $name"};SystemClock.sleep(300)}
    private fun waitFor(test:()->Boolean){val end=SystemClock.uptimeMillis()+10000
        while(!test()){check(SystemClock.uptimeMillis()<end){"UI condition timed out"};SystemClock.sleep(100)}}
    override fun onStart(){val result=Bundle();var phase="launch"
        try{
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor{node("取景")!=null}
            val vm=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}.get(activity) as MainViewModel
            @Suppress("UNCHECKED_CAST") val flow=MainViewModel::class.java.getDeclaredField("_ui").apply{isAccessible=true}.get(vm) as MutableStateFlow<UiState>
            phase="preset defaults"
            runOnMainSync{flow.value=flow.value.copy(accounts=listOf(ProviderAccount(LlmProvider.CUSTOM_ID)),activeProviderId=LlmProvider.CUSTOM_ID,aiPanelExpanded=false);vm.selectTab(3)}
            waitFor{node("配置")!=null&&node("配方快传码")!=null&&node("内置预设")!=null}
            check(node("第三方 OpenAI 兼容供应商")==null){"Provider child visible while collapsed"}
            check(node("生成快传码")==null && node("粘贴别人的快传码")==null){"Share body not collapsed"}
            check(node("搜索名称 / 场景 / 说明")==null && node("按热门度排序")==null){"Built-in body not collapsed"}
            phase="provider configuration toggles"
            click("配置");waitFor{flow.value.aiPanelExpanded&&node("第三方 OpenAI 兼容供应商")!=null}
            click("收起");waitFor{!flow.value.aiPanelExpanded&&node("第三方 OpenAI 兼容供应商")==null}
            check(!flow.value.compositionBusy&&!flow.value.analyzing){"Opening configuration triggered AI work"}
            phase="share header toggles"
            click("配方快传码");waitFor{node("生成快传码")!=null}
            check(flow.value.shareCode.isBlank()){ "Opening share unexpectedly generated a code" }
            click("配方快传码");waitFor{node("生成快传码")==null}
            phase="built-in header toggles"
            click("内置预设");waitFor{node("搜索名称 / 场景 / 说明")!=null}
            click("内置预设");waitFor{node("搜索名称 / 场景 / 说明")==null}
            phase="advanced header toggles"
            runOnMainSync{vm.openSettings(true)}
            bring("高级")
            check(node("展开高级选项")==null&&node("收起高级选项")==null){"Redundant advanced row remains"}
            check(node("持续写入日志文件")==null){"Advanced must start collapsed"}
            click("高级");bring("持续写入日志文件")
            click("高级");waitFor{node("持续写入日志文件")==null}
            runOnMainSync{vm.openSettings(false);activity.finish()}
            result.putString("stream","PASS: providers/share/built-in start collapsed; provider child hidden until Configure; headers expand and collapse share/built-in/advanced; redundant advanced row removed. No camera, cloud, preset apply/import, code generation or persistent credential changes.\n")
            finish(Activity.RESULT_OK,result)
        }catch(t:Throwable){result.putString("stream","FAIL phase=$phase: ${t.javaClass.simpleName}: ${t.message}\n");finish(Activity.RESULT_CANCELED,result)}
    }
}
