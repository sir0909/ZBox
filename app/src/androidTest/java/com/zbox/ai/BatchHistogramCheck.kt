package com.zbox.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Bitmap
import android.provider.MediaStore
import android.content.ContentUris
import com.zbox.ai.ui.*
import com.zbox.ai.core.CameraStorage
import com.zbox.ai.ptp.NikonPtpDriver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/** Explicit user-authorized real-gallery multiselect and histogram check. No AI/capture/camera delete/write. */
class BatchHistogramCheck:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun node(label:String,prefix:Boolean=false):AccessibilityNodeInfo?{
        runCatching{uiAutomation.javaClass.getMethod("clearCache").invoke(uiAutomation)}
        fun find(n:AccessibilityNodeInfo?,d:Int):AccessibilityNodeInfo?{if(n==null||d>60)return null;n.refresh();val text=n.text?.toString().orEmpty()
            if(n.isVisibleToUser && (if(prefix)text.startsWith(label)else text==label))return n
            for(i in 0 until n.childCount)find(n.getChild(i),d+1)?.let{return it};return null}
        val root=uiAutomation.rootInActiveWindow?:return null;check(root.packageName?.toString()=="com.zbox.ai"){"No interaction outside target app"};return find(root,0)}
    private fun card(handle:Long):AccessibilityNodeInfo? {
        fun find(n:AccessibilityNodeInfo?,d:Int):AccessibilityNodeInfo?{if(n==null||d>60)return null;n.refresh();if(n.isVisibleToUser && n.viewIdResourceName?.endsWith("gallery-$handle")==true)return n;for(i in 0 until n.childCount)find(n.getChild(i),d+1)?.let{return it};return null}
        val root=uiAutomation.rootInActiveWindow?:return null;check(root.packageName?.toString()=="com.zbox.ai");return find(root,0)
    }
    private fun scroll(forward:Boolean):Boolean {val root=uiAutomation.rootInActiveWindow?:return false;check(root.packageName?.toString()=="com.zbox.ai")
        fun find(n:AccessibilityNodeInfo?,d:Int):AccessibilityNodeInfo?{if(n==null||d>60)return null;if(n.isScrollable)return n;for(i in 0 until n.childCount)find(n.getChild(i),d+1)?.let{return it};return null}
        val ok=find(root,0)?.performAction(if(forward)AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)?:false;SystemClock.sleep(200);return ok}
    private fun reveal(label:String,prefix:Boolean=false):AccessibilityNodeInfo {node(label,prefix)?.let{return it};repeat(20){scroll(false)};repeat(35){node(label,prefix)?.let{return it};scroll(true)};error("Cannot reveal $label")}
    private fun click(label:String,prefix:Boolean=false){var n=reveal(label,prefix);while(!n.isClickable)n=n.parent?:error("No click action");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));SystemClock.sleep(300)}
    private fun waitFor(ms:Long=90000,test:()->Boolean){val end=SystemClock.uptimeMillis()+ms;while(!test()){check(SystemClock.uptimeMillis()<end){"Device wait timed out; no retry/reconnect"};SystemClock.sleep(150)}}
    private fun sha(input:java.io.InputStream):String=input.use{val md=MessageDigest.getInstance("SHA-256");val b=ByteArray(128*1024);while(true){val n=it.read(b);if(n<0)break;md.update(b,0,n)};md.digest().joinToString(""){v->"%02x".format(v.toInt() and 255)}}
    override fun onStart(){val out=Bundle();val e=JSONObject();var phase="launch";var model:MainViewModel?=null;var old:PhoneOptions?=null;var flow:MutableStateFlow<UiState>?=null;var oldCapture:Bitmap?=null;var startedView=false;var success=false
        try {
            uiAutomation.serviceInfo=uiAutomation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS}
            val a=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(700)
            val m=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}.get(a) as MainViewModel;model=m;old=m.phone.state.value.options
            @Suppress("UNCHECKED_CAST") val f=MainViewModel::class.java.getDeclaredField("_ui").apply{isAccessible=true}.get(m) as MutableStateFlow<UiState>;flow=f;oldCapture=m.ui.value.lastCapture
            val d=MainViewModel::class.java.getDeclaredField("driver").apply{isAccessible=true}.get(m) as NikonPtpDriver
            phase="single authorized camera connection";if(!m.ui.value.isConnected)runOnMainSync{m.connectAutomatically{error("USB permission needed")}}
            waitFor{m.ui.value.isConnected && !m.ui.value.wifiConnectBusy && !m.ui.value.usbConnectBusy};e.put("model",m.ui.value.deviceInfo.model)
            check(!m.ui.value.movieRecording && !m.ui.value.liveViewActive){"Will not change ongoing recording/live-view"}
            runOnMainSync{m.selectTab(2)};waitFor{!m.ui.value.galleryLoading && m.ui.value.galleryItems.size>=2 && m.ui.value.thumbnails.size>=2}
            val items=CameraStorage.jpegBrowseItems(m.ui.value.galleryItems).filter{!it.isRaw && !it.isVideo}.take(2);check(items.size==2)
            check(node("JPEG浏览")==null && node("JPEG预览")==null)
            phase="real long-press and second selection";waitFor{card(items[0].handle)!=null};var target=card(items[0].handle)!!;while(!target.isLongClickable)target=target.parent?:error("No real long-press action")
            check(target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK));waitFor{node("已选1项 · 最多24项")!=null}
            waitFor{card(items[1].handle)!=null};var second=card(items[1].handle)!!;while(!second.isClickable)second=second.parent?:error("No second-card click");check(second.performAction(AccessibilityNodeInfo.ACTION_CLICK));waitFor{node("已选2项 · 最多24项")!=null};e.put("realLongPressSelected",2)
            check(node("批量删除")==null);check(m.phone.state.value.tasks.isEmpty())
            val cr=targetContext.contentResolver;val collection=MediaStore.Images.Media.EXTERNAL_CONTENT_URI;var lastId=0L
            cr.query(collection,arrayOf(MediaStore.MediaColumns._ID),"${MediaStore.MediaColumns.RELATIVE_PATH}=?",arrayOf("Pictures/Zbox/"),"${MediaStore.MediaColumns._ID} DESC")?.use{if(it.moveToFirst())lastId=it.getLong(0)}
            phase="batch JPEG queue";click("批量下载");click("JPEG及视频加入（2项）");waitFor{m.phone.state.value.tasks.size==2}
            check(m.phone.state.value.tasks.all{it.status=="待下载"}){"Batch enqueue unexpectedly started I/O"};e.put("batchEnqueueWaitedForStart",true)
            runOnMainSync{m.requestGalleryThumbnails(emptyList());m.phone.startQueue()}
            waitFor(180000){m.phone.state.value.tasks.size==2 && m.phone.state.value.tasks.all{it.status in listOf("已完成","失败","已暂停")}}
            check(m.phone.state.value.tasks.all{it.status=="已完成"}){"Batch download failure"};waitFor{!m.phone.cameraBusy}
            val expected=mutableListOf<String>();val sizes=mutableListOf<Long>()
            for(task in m.phone.state.value.tasks){val file=File(targetContext.cacheDir,"phone38-batch-reference-only.part");val meta=File(file.path+".meta")
                try{file.delete();meta.delete();runBlocking{d.downloadPhoneFile(task.item,file,d.burstConnectionToken!!){_,total->check(total in 1..(32L*1024*1024)){"Test reference limit32MiB"}}};expected+=sha(file.inputStream());sizes+=file.length()}
                finally{file.delete();meta.delete()}}
            val saved=mutableListOf<String>()
            cr.query(collection,arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.IS_PENDING),"${MediaStore.MediaColumns._ID}>? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",arrayOf(lastId.toString(),"Pictures/Zbox/"),null)?.use{while(it.moveToNext()){check(it.getInt(1)==0);saved+=sha(cr.openInputStream(ContentUris.withAppendedId(collection,it.getLong(0)))?:error("Saved media unavailable"))}}
            check(saved.sorted()==expected.sorted()){"Batch MediaStore exact hash mismatch"};e.put("batchMediaStoreExactHashes",JSONArray(saved)).put("batchBytes",JSONArray(sizes)).put("savedTestJpegsRetained",2)
            phase="live histogram manual toggle";runOnMainSync{m.selectTab(0);m.phone.options(old!!.copy(histogram=false));m.startLiveView()};startedView=true
            waitFor{m.ui.value.liveViewFrames>=2 && m.ui.value.liveViewBitmap!=null}
            click("取景辅助（手机处理，默认关闭）");check(node("手机水平仪（不是相机姿态）")==null)
            click("取景直方图（亮度）");check(m.phone.state.value.options.histogram);reveal("取景亮度分布");e.put("realLiveHistogramVisible",true)
            var frame:Bitmap?=null;runOnMainSync{frame=m.ui.value.liveViewBitmap!!.copy(Bitmap.Config.ARGB_8888,false);m.stopLiveView()};waitFor{!m.ui.value.liveViewActive}
            val analysis=analyzePhoneFrame(frame!!)!!.first;check(analysis.histogram.sum()==analysis.pixels);e.put("realHistogramPixels",analysis.pixels).put("realHistogramConserved",true)
            phase="manual inspection histogram";runOnMainSync{f.value=f.value.copy(lastCapture=frame)};click("检查这张（手机放大／直方图）");check(node("检查亮度分布",true)==null)
            click("显示直方图");waitFor{node("检查亮度分布",true)!=null};e.put("inspectionHistogramManual",true);click("返回取景／关闭")
            e.put("noAiRequest",true).put("noCameraCaptureDeleteParameterWrite",true);success=true
        }catch(t:Throwable){e.put("phase",phase).put("error",t.javaClass.simpleName+": "+t.message)}
        finally{if(model!=null)runOnMainSync{val m=model!!;if(old!=null)m.phone.options(old!!);if(flow!=null)flow!!.value=flow!!.value.copy(lastCapture=oldCapture);if(startedView)m.stopLiveView()}}
        targetContext.getExternalFilesDir(null)?.resolve("phone38-batch-histogram-result.json")?.writeText(e.toString(2))
        out.putString("stream","${if(success)"PASS:"else"FAIL:"} real multiselect/batch/histogram checks. EVIDENCE: $e\n");finish(if(success)Activity.RESULT_OK else Activity.RESULT_CANCELED,out)
    }
}
