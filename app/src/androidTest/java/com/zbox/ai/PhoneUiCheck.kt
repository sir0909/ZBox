package com.zbox.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.zbox.ai.core.CameraStorage
import com.zbox.ai.ptp.NikonPtpDriver
import com.zbox.ai.ui.*
import kotlinx.coroutines.flow.MutableStateFlow

/** UI-only synthetic images. Never clicks capture, AF, analyze, download start, delete or apply. */
class PhoneUiCheck:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun node(label:String,prefix:Boolean=false):AccessibilityNodeInfo? {
        runCatching{uiAutomation.javaClass.getMethod("clearCache").invoke(uiAutomation)}
        fun find(n:AccessibilityNodeInfo?,depth:Int):AccessibilityNodeInfo?{if(n==null||depth>70)return null;n.refresh()
            val t=n.text?.toString().orEmpty();if((if(prefix)t.startsWith(label)else t==label)&&n.isVisibleToUser)return n
            for(i in 0 until n.childCount)find(n.getChild(i),depth+1)?.let{return it};return null}
        return find(uiAutomation.rootInActiveWindow,0)
    }
    private fun scroll(forward:Boolean):Boolean {
        fun find(n:AccessibilityNodeInfo?,depth:Int):AccessibilityNodeInfo?{if(n==null||depth>70)return null
            if(n.isScrollable && n.packageName?.toString()=="com.zbox.ai")return n
            for(i in 0 until n.childCount)find(n.getChild(i),depth+1)?.let{return it};return null}
        val root=uiAutomation.rootInActiveWindow?:return false;check(root.packageName?.toString()=="com.zbox.ai"){"No interaction outside target app"}
        val n=find(root,0)?:return false;val ok=n.performAction(if(forward)AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);SystemClock.sleep(250);return ok
    }
    private fun reveal(label:String,prefix:Boolean=false):AccessibilityNodeInfo {
        node(label,prefix)?.let{return it};repeat(20){scroll(false)};repeat(35){node(label,prefix)?.let{return it};if(!scroll(true))return@repeat}
        error("Cannot reveal $label")
    }
    private fun click(label:String,prefix:Boolean=false){var n=reveal(label,prefix);while(!n.isClickable)n=n.parent?:error("No clickable parent $label")
        check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)){"Click failed $label"};SystemClock.sleep(350)}
    private fun waitFor(test:()->Boolean){val end=SystemClock.uptimeMillis()+10000;while(!test()){check(SystemClock.uptimeMillis()<end){"UI timeout"};SystemClock.sleep(150)}}
    override fun onStart(){val out=Bundle();var phase="launch";var vm:MainViewModel?=null;var original:PhoneOptions?=null
        val posePrefs=targetContext.getSharedPreferences("pose39",0);val hadPrompt=posePrefs.contains("downloadPromptSeen");val oldPrompt=posePrefs.getBoolean("downloadPromptSeen",false)
        val wordPrefs=targetContext.getSharedPreferences("pose_words39",0);val oldWords=wordPrefs.getStringSet("favorites",emptySet()).orEmpty().toSet()
        fun restoreBeforeFinish(){
            if(hadPrompt)posePrefs.edit().putBoolean("downloadPromptSeen",oldPrompt).commit()else posePrefs.edit().remove("downloadPromptSeen").commit()
            wordPrefs.edit().putStringSet("favorites",oldWords).commit()
            if(original!=null && vm!=null)runOnMainSync{vm!!.phone.options(original!!);vm!!.phone.clearCompare()}
            original?.let{o->targetContext.getSharedPreferences("phone_tools_35",0).edit().putInt("options",(if(o.zebra)1 else 0) or (if(o.peaking)2 else 0) or (if(o.histogram)16 else 0) or (if(o.grid)32 else 0)).putInt("frame",o.frame).commit()}
        }
        try {
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitFor{node("AI 构图")!=null}
            val model=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}.get(activity) as MainViewModel;vm=model;original=model.phone.state.value.options
            @Suppress("UNCHECKED_CAST") val flow=MainViewModel::class.java.getDeclaredField("_ui").apply{isAccessible=true}.get(model) as MutableStateFlow<UiState>
            phase="diagnostics only under advanced"
            check(node("连接诊断与机型能力（手机本地）")==null){"Diagnostics leaked into monitor"}
            runOnMainSync{model.openSettings(true)};waitFor{node("设置")!=null}
            click("高级");click("连接诊断与机型能力（手机本地）");reveal("复制脱敏报告")
            check(!model.phone.report().contains("192.168.")){"Unsafe report"}
            click("连接诊断与机型能力（手机本地）")
            runOnMainSync{model.openSettings(false)};waitFor{node("AI 构图")!=null}
            phase="assist options and synthetic overlay"
            runOnMainSync{model.openSettings(true)};click("取景辅助（手机处理）");reveal("高光斑马纹（预览亮度≥245）");reveal("取景直方图（亮度）");check(node("手机水平仪（不是相机姿态）")==null)
            reveal("九宫格参考线");check(node("拍完自动打开本地检查视图")==null)
            runOnMainSync{model.openSettings(false)}
            val bitmap=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.WHITE)}
            runOnMainSync{model.phone.options(PhoneOptions(zebra=true,peaking=true,histogram=true,frame=2));flow.value=flow.value.copy(usbState=NikonPtpDriver.State.SESSION_OPEN,liveViewActive=true,liveViewBitmap=bitmap)}
            reveal("取景亮度分布");check(!bitmap.isRecycled)
            runOnMainSync{model.phone.options(PhoneOptions(zebra=false,peaking=false,histogram=false,grid=false))}
            phase="inspection dialog"
            runOnMainSync{flow.value=flow.value.copy(lastCapture=bitmap,liveViewActive=false,liveViewBitmap=null)}
            click("检查这张（手机放大／直方图）");waitFor{node("返回取景／关闭")!=null}
            check(node("双击2倍／还原，双指缩放1–5倍；缩略图放大不代表原片细节")!=null)
            check(node("检查亮度分布",true)==null){"Inspection histogram must default hidden"}
            click("显示直方图");waitFor{node("检查亮度分布",true)!=null}
            click("显示直方图");waitFor{node("检查亮度分布",true)==null}
            click("返回取景／关闭")
            phase="removed parameter and pairing entries"
            check(node("参数快捷方案（保存在手机）")==null && node("首次主机配对（仅配置向导使用）")==null)
            phase="pose reference, consent and original words without downloading"
            click("拍照姿势 / 引导话术")
            if(!hadPrompt || !oldPrompt){waitFor{node("是否下载姿势图库？")!=null};click("暂不下载，用自己的图")}
            reveal("添加自己的参考图");check(!model.poses.state.value.downloading)
            click("摄影话术");waitFor{node(PoseWords.groups.getValue("夸赞鼓励").first())!=null}
            click("换一句");waitFor{node(PoseWords.groups.getValue("夸赞鼓励").first())==null}
            click("动作引导");waitFor{PoseWords.groups.getValue("动作引导").any{node(it)!=null}}
            click("收藏这句话");waitFor{node("取消话术收藏")!=null};click("取消话术收藏")
            click("关闭参考");waitFor{node("关闭参考")==null}
            runOnMainSync{model.selectTab(3)}
            phase="local library and batch write collapsed by default"
            reveal("本地预设库（",true);check(node("导入与管理")==null)
            click("本地预设库（",true);reveal("导入与管理");click("本地预设库（",true)
            click("批量刷入");reveal("把当前配方刷入所选 3 个槽位");click("批量刷入")
            phase="gallery compare and queue"
            val a=CameraStorage.Item(10001,1,0x3801,"PHONE-UI-A.JPG",100,600,400,"2026-01-01",false,companionHandle=10003,companionFilename="PHONE-UI-A.NEF",filenameVerified=false)
            val b=a.copy(handle=10002,filename="PHONE-UI-B.JPG",companionHandle=null,companionFilename=null)
            val bytes=java.io.ByteArrayOutputStream().apply{bitmap.compress(Bitmap.CompressFormat.JPEG,70,this)}.toByteArray()
            runOnMainSync{model.phone.clearCompare();model.phone.compare(a);model.phone.compare(b)
                flow.value=flow.value.copy(selectedTab=2,galleryItems=listOf(a,b),thumbnails=mapOf(a.handle to bytes,b.handle to bytes),galleryLoading=false,galleryHasMore=false)}
            waitFor{node("对比已选2/2")!=null}
            check(flow.value.galleryFilter==1){"Gallery must default to JPEG browsing"}
            check(node("RAW")==null && node("JPEG浏览")==null && node("排序：",true)==null){"Removed RAW or sort control remains"}
            click("对比已选2/2")
            waitFor{node("双图同步缩放／位移 · 当前缓存缩略图，不是原片像素")!=null};click("还原");click("关闭对比")
            waitFor{node("关闭对比")==null};SystemClock.sleep(500)
            phase="long press multi-select and batch dialog"
            var longTarget=reveal("PHONE-UI-A",true);while(!longTarget.isLongClickable)longTarget=longTarget.parent?:error("No long-clickable card")
            check(longTarget.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK));waitFor{node("已选1项 · 最多24项")!=null}
            click("PHONE-UI-B",true);waitFor{node("已选2项 · 最多24项")!=null}
            check(node("批量收藏")!=null && node("批量待选")!=null && node("批量删除")==null)
            click("批量下载");waitFor{node("JPEG及视频加入（2项）")!=null && node("RAW及视频加入（1项）")!=null}
            check(model.phone.state.value.tasks.isEmpty()){"Batch dialog must not enqueue without choice"}
            click("取消");click("退出多选")
            phase="format selection and generated-name disclosure"
            val card=reveal("PHONE-UI-A",true);val bounds=android.graphics.Rect();card.getBoundsInScreen(bounds)
            check(uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.zbox.ai" && !bounds.isEmpty){"Unsafe card tap target"}
            uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
            phase="card tap opens detail (${bounds.flattenToString()})"
            waitFor{node("选择下载格式")!=null}
            phase="detail opens format dialog";click("选择下载格式")
            waitFor{node("JPEG加入队列")!=null && node("RAW原片加入队列")!=null}
            check(node("当前STA模式不提供原文件名",true)!=null){"Generated name was not disclosed"}
            check(model.phone.state.value.tasks.isEmpty()){"Format dialog enqueued without explicit selection"}
            phase="cancel format dialog";click("取消")
            check(uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.zbox.ai")
            waitFor{node("JPEG加入队列")==null && node("选择下载格式")!=null};SystemClock.sleep(500)
            uiAutomation.executeShellCommand("input keyevent 4").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
            phase="back closes detail";waitFor{node("选择下载格式")==null}
            click("下载队列 0");reveal("暂停全部");reveal("清理临时块");click("关闭队列")
            check(model.phone.state.value.tasks.isEmpty()){ "UI test must not start any download"}
            runOnMainSync{model.phone.clearCompare();model.phone.options(original!!);flow.value=flow.value.copy(usbState=NikonPtpDriver.State.DISCONNECTED,selectedTab=0,liveViewActive=false,liveViewBitmap=null,lastCapture=null);activity.finish()}
            restoreBeforeFinish()
            out.putString("stream","PASS: diagnostics and settings-only assists reachable; removed parameter/pairing/auto-inspection, pose consent/60-word categories/local favorites, collapsed preset library and batch write; synthetic overlay toggles, inspection and histogram dialog, two cached-image comparison with reset/close, manual live/inspection histograms, long-press multiselect and batch format dialog, format selection and STA generated-name disclosure, queue controls reachable without starting I/O. Original phone options restored. No camera commands, AI, clipboard overwrite, file saving/deletion or parameter apply.\n");finish(Activity.RESULT_OK,out)
        }catch(t:Throwable){
            val labels=mutableListOf<String>()
            fun dump(n:AccessibilityNodeInfo?,depth:Int){if(n==null||depth>40||labels.size>100)return;if(!n.text.isNullOrBlank()){val r=android.graphics.Rect();n.getBoundsInScreen(r);labels.add("${n.text}@$r")};for(i in 0 until n.childCount)dump(n.getChild(i),depth+1)}
            if(uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.zbox.ai")dump(uiAutomation.rootInActiveWindow,0)
            runCatching{restoreBeforeFinish()}
            out.putString("stream","FAIL PhoneUiCheck phase=$phase: ${t.javaClass.simpleName}: ${t.message} targetAppLabels=$labels\n");finish(Activity.RESULT_CANCELED,out)}
        finally{if(hadPrompt)posePrefs.edit().putBoolean("downloadPromptSeen",oldPrompt).apply()else posePrefs.edit().remove("downloadPromptSeen").apply()
            wordPrefs.edit().putStringSet("favorites",oldWords).apply()
            if(original!=null && vm!=null)runOnMainSync{vm!!.phone.options(original!!);vm!!.phone.clearCompare()}
            original?.let{o->targetContext.getSharedPreferences("phone_tools_35",0).edit().putInt("options",(if(o.zebra)1 else 0) or (if(o.peaking)2 else 0) or (if(o.histogram)16 else 0) or (if(o.grid)32 else 0)).putInt("frame",o.frame).commit()}}
    }
}
