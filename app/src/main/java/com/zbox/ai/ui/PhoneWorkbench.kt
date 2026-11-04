package com.zbox.ai.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.content.ContentValues
import com.zbox.ai.core.CameraStorage
import com.zbox.ai.core.PhoneToolsPolicy as Policy
import com.zbox.ai.ptp.NikonPtpDriver
import com.zbox.ai.ptp.HexLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class PhoneOptions(val zebra:Boolean=true,val peaking:Boolean=true,val frame:Int=0,val histogram:Boolean=true,val grid:Boolean=true)
internal data class PhoneTask(val id:String,val item:CameraStorage.Item,val camera:String,val status:String="待下载",val bytes:Long=0,val total:Long=0,val message:String="")
internal data class PhoneState(val options:PhoneOptions=PhoneOptions(),val marks:Map<String,Int> = emptyMap(),val compare:List<CameraStorage.Item> = emptyList(),
    val compareImages:Map<Long,Bitmap> = emptyMap(),val compareLoading:Boolean=false,val compareStatus:String="",
    val tasks:List<PhoneTask> = emptyList(),val message:String="")

/** All processing/storage is local. Camera I/O occurs only after an explicit queue start or apply confirmation. */
internal class PhoneWorkbench(private val app:Application,private val scope:CoroutineScope,private val driver:NikonPtpDriver,
    private val ui:()->UiState,private val update:((UiState)->UiState)->Unit,private val workPending:()->Boolean={false},preferencesName:String="phone_tools_35") {
    private val prefs=app.getSharedPreferences(preferencesName,0)
    private val mutable=MutableStateFlow(PhoneState())
    val state:StateFlow<PhoneState> = mutable.asStateFlow()
    private var worker:Job?=null
    private var compareJob:Job?=null
    private val sessionMarkKeys=mutableSetOf<String>()
    val cameraBusy:Boolean get()=worker!=null || compareJob!=null
    private fun message(s:String){mutable.update{it.copy(message=s)}}
    init {runCatching {
        // One-time v39 default migration; obsolete level/auto-inspect bits never activate features.
        if(!prefs.getBoolean("assist39",false))prefs.edit().putInt("options",1 or 2 or 16 or 32).putBoolean("assist39",true).apply()
        val flags=prefs.getInt("options",1 or 2 or 16 or 32)
        val marks=JSONObject(prefs.getString("marks","{}")!!);val map=mutableMapOf<String,Int>()
        marks.keys().asSequence().take(1000).forEach{map[it]=marks.optInt(it).coerceIn(0,2)}
        mutable.value=PhoneState(options=PhoneOptions(zebra=flags and 1!=0,peaking=flags and 2!=0,frame=prefs.getInt("frame",0).coerceIn(0,3),histogram=flags and 16!=0,grid=flags and 32!=0),marks=map)
    }}
    fun options(value:PhoneOptions){mutable.update{it.copy(options=value)};prefs.edit().putInt("options",(if(value.zebra)1 else 0) or (if(value.peaking)2 else 0) or (if(value.histogram)16 else 0) or (if(value.grid)32 else 0)).putInt("frame",value.frame.coerceIn(0,3)).apply()}
    private fun cameraKey():String {val i=ui().deviceInfo;return if(i.serial.isNotBlank())Policy.hash("${i.manufacturer}|${i.model}|${i.serial}") else "session-${System.identityHashCode(driver.burstConnectionToken)}"}
    fun key(item:CameraStorage.Item)=Policy.photoKey(cameraKey(),item.storageId,item.filename,item.sizeBytes,item.capturedAt)
    fun mark(item:CameraStorage.Item)=state.value.marks[key(item)]?:0
    fun toggleMark(item:CameraStorage.Item,value:Int){val k=key(item);if(ui().deviceInfo.serial.isBlank())sessionMarkKeys.add(k);val m=state.value.marks.toMutableMap();if(m[k]==value)m.remove(k)else m[k]=value.coerceIn(1,2)
        while(m.size>1000)m.remove(m.keys.first());mutable.update{it.copy(marks=m)};val o=JSONObject();m.filterKeys{it !in sessionMarkKeys}.forEach{(a,b)->o.put(a,b)}
        // Serial-less keys are salted by connection and are not exported; harmless stale hashes are bounded.
        prefs.edit().putString("marks",o.toString()).apply()}
    fun compare(item:CameraStorage.Item){if(item.isVideo){message("视频不能进行静态双图比较");return};val old=state.value.compare
        if(old.size>=2 && old.none{it.handle==item.handle}){message("最多选择两张，请先取消一张");return}
        cancelCompareRead();mutable.update{it.copy(compare=if(old.any{p->p.handle==item.handle})old.filterNot{p->p.handle==item.handle}else old+item,compareImages=emptyMap(),compareStatus="")}}
    fun cancelCompareRead(){compareJob?.cancel();compareJob=null;mutable.update{it.copy(compareLoading=false)}}
    fun clearCompare(){cancelCompareRead();mutable.update{it.copy(compare=emptyList(),compareImages=emptyMap(),compareStatus="")}}
    /** Explicitly opening comparison authorizes photo reads, never AI or MediaStore saves. */
    fun loadComparePreview(maxEdge:Int=2048){
        require(maxEdge==2048 || maxEdge==4096)
        if(state.value.compare.size!=2)return
        if(cameraBusy || !idle()){mutable.update{it.copy(compareStatus="请连接相机并结束取景/下载/相册读取后，再读取高清对比")};return}
        val selected=state.value.compare.toList();val handles=selected.map{it.handle};val token=driver.burstConnectionToken?:return
        if(maxEdge==2048 && handles.all{it in state.value.compareImages})return
        val job=scope.launch(start=CoroutineStart.LAZY){
            try{mutable.update{it.copy(compareLoading=true,compareStatus="正在读取两张解码预览（最长边目标${maxEdge}，不会调用AI或保存相册）")}
                val images=linkedMapOf<Long,Bitmap>()
                for(item in selected){ensureActive();val file=File(app.cacheDir,"phone-compare-${java.util.UUID.randomUUID()}.part")
                    try{withContext(Dispatchers.IO){driver.downloadPhoneFile(item,file,token){_,total->check(total in 1..(64L*1024*1024)){"对比只读文件上限64MiB"}}}
                        ensureActive();val bitmap=withContext(Dispatchers.Default){decodeCompareFile(file,item.isRaw,maxEdge)}
                        check(bitmap!=null){"未找到可解码预览；RAW原像素需要RAW解码器，不用缩略图冒充"};images[item.handle]=bitmap
                    }finally{file.delete();File(file.path+".meta").delete()}}
                ensureActive();check(token===driver.burstConnectionToken && state.value.compare.map{it.handle}==handles){"会话或对比选择已变化，丢弃旧结果"}
                mutable.update{it.copy(compareImages=images,compareStatus="已读取高清解码预览；RAW使用内嵌JPEG，均非传感器RAW原像素")}
            }catch(c:CancellationException){throw c}catch(t:Exception){mutable.update{it.copy(compareStatus="高清对比失败：${t.message?.take(100)}；保留已有预览，可手动重试")}}
            finally{if(compareJob===coroutineContext[Job]){compareJob=null;mutable.update{it.copy(compareLoading=false)}}}
        };compareJob=job;job.start()
    }
    private fun decodeCompareFile(file:File,raw:Boolean,maxEdge:Int):Bitmap? {
        var data:ByteArray?=null
        if(raw){val head=file.inputStream().use{val b=ByteArray(minOf(file.length(),256L*1024).toInt());var n=0;while(n<b.size){val got=it.read(b,n,b.size-n);if(got<0)break;n+=got};b.copyOf(n)}
            val range=com.zbox.ai.ptp.NikonStaDirectRead.parseHead(head).embeddedJpegs
                .filter{it.first>=0 && it.last<file.length() && it.last-it.first+1 in 1..(16L*1024*1024)}.maxByOrNull{it.last-it.first}?:return null
            data=ByteArray((range.last-range.first+1).toInt());java.io.RandomAccessFile(file,"r").use{it.seek(range.first);it.readFully(data)}}
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        if(data==null)BitmapFactory.decodeFile(file.path,bounds)else BitmapFactory.decodeByteArray(data,0,data.size,bounds)
        if(bounds.outWidth<=0 || bounds.outHeight<=0)return null
        var sample=1;while(bounds.outWidth/sample>maxEdge || bounds.outHeight/sample>maxEdge)sample*=2
        val options=BitmapFactory.Options().apply{inSampleSize=sample}
        return if(data==null)BitmapFactory.decodeFile(file.path,options)else BitmapFactory.decodeByteArray(data,0,data.size,options)
    }
    fun report():String {
        val u=ui();val stages=Policy.diagnosticStages(HexLog.entries.value.map{it.title+" "+it.note})
        val canonical=u.deviceInfo.model.uppercase().replace(Regex("\\s+"),"")
        val safeModel=canonical.takeIf{it.matches(Regex("(?:NIKON)?(?:Z(?:30|50|5|6|7|8|9)(?:II|III)?|ZF|ZFC|D[0-9]{2,4}[SX]?)"))}?:"未识别/已隐藏"
        val firmware=u.deviceInfo.firmwareVersion.takeIf{it.matches(Regex("[A-Za-z]? ?[0-9]{1,2}\\.[0-9]{1,3}(?:\\.[0-9]{1,3})?"))}?:"未知/已隐藏"
        return "Zbox 1.0.0 · 手机端脱敏报告\nAndroid ${Build.VERSION.SDK_INT}\n型号 $safeModel\n固件 $firmware\n状态 ${u.usbState}\n发现候选 ${u.hotspotCameras.size}\n操作码通告 ${u.deviceInfo.supportedOperations.size} 项（不是实测证明）\n"+
            (if(stages.isEmpty())"尚无阶段记录" else stages.joinToString("\n"))+"\n建议：${Policy.advice(stages,u.isConnected)}\n不含原始日志、IP、SSID、GUID、序列号、PIN、API配置或照片。"
    }
    fun enqueue(item:CameraStorage.Item,raw:Boolean=false){if(!driver.isOpen){message("请先连接相机");return};if(state.value.tasks.size>=Policy.MAX_QUEUE){message("队列最多24项，请清理已结束任务");return}
        if(raw && !item.isRaw && item.companionHandle==null){message("未找到对应RAW文件，不会自动转换或下载其它格式");return}
        if(raw && !item.isRaw && item.companionFilename.isNullOrBlank()){message("RAW文件名尚未核实，请刷新相册；未加入队列");return}
        val source=if(raw && !item.isRaw && item.companionHandle!=null)item.copy(handle=item.companionHandle,filename=item.companionFilename?:item.filename,sizeBytes=0,companionHandle=null)else item
        val camera=cameraKey();if(state.value.tasks.any{it.camera==camera && it.item.handle==source.handle && it.status in listOf("待下载","下载中","保存中")}){message("该文件已在队列");return}
        mutable.update{it.copy(tasks=it.tasks+PhoneTask(java.util.UUID.randomUUID().toString(),source,camera),message="已加入队列，点开始才读取相机")}}
    /** Idempotent batch marking; never toggles an already-favorite photo off. */
    fun markBatch(items:List<CameraStorage.Item>,value:Int){require(value in 1..2);if(items.size>Policy.MAX_QUEUE){message("一次最多选择24项");return}
        val m=state.value.marks.toMutableMap();for(item in items){val k=key(item);if(ui().deviceInfo.serial.isBlank())sessionMarkKeys.add(k);m[k]=value}
        while(m.size>1000)m.remove(m.keys.first());mutable.update{it.copy(marks=m,message="已在手机标记${items.size}项；未修改相机")}
        val o=JSONObject();m.filterKeys{it !in sessionMarkKeys}.forEach{(a,b)->o.put(a,b)};prefs.edit().putString("marks",o.toString()).apply()}
    fun batchEligible(items:List<CameraStorage.Item>,raw:Boolean):List<CameraStorage.Item> = items.filter { item ->
        item.isVideo || if(raw)item.isRaw || (item.companionHandle!=null && !item.companionFilename.isNullOrBlank())
        else item.filename.endsWith(".jpg",true)||item.filename.endsWith(".jpeg",true)
    }.distinctBy{if(raw && !it.isRaw && !it.isVideo)it.companionHandle else it.handle}
    /** All-or-nothing capacity admission, then normal enqueue guards; does not start camera I/O. */
    fun enqueueBatch(items:List<CameraStorage.Item>,raw:Boolean):Int {
        if(!driver.isOpen){message("请先连接相机；未加入批量队列");return 0}
        val camera=cameraKey();val active=state.value.tasks.filter{it.camera==camera && it.status in listOf("待下载","下载中","保存中")}.map{it.item.handle}.toSet()
        val eligible=batchEligible(items,raw);val targets=eligible.filter{(if(raw && !it.isRaw && !it.isVideo)it.companionHandle else it.handle) !in active}
        if(targets.isEmpty()){message("没有可新增的所选格式文件，或文件已在队列；不转换格式");return 0}
        if(state.value.tasks.size+targets.size>Policy.MAX_QUEUE){message("队列总上限24项，当前剩余${Policy.MAX_QUEUE-state.value.tasks.size}项；本批未加入，请减少选择或清理已结束任务");return 0}
        targets.forEach{enqueue(it,raw && !it.isVideo)};message("本批加入${targets.size}项；不含所选格式的${items.size-eligible.size}项跳过。点队列开始才下载")
        return targets.size
    }
    private fun setTask(id:String,f:(PhoneTask)->PhoneTask){mutable.update{it.copy(tasks=it.tasks.map{v->if(v.id==id)f(v)else v})}}
    fun clearTemporaryFiles(){if(cameraBusy)return;app.cacheDir.listFiles()?.filter{it.name.matches(Regex("phone-queue-[a-f0-9-]+\\.part(?:\\.meta)?"))}?.forEach{it.delete()};message("已清理APP队列临时块；未删除手机相册或相机照片，重试会从头读取")}
    fun clearFinished(){if(cameraBusy)return;val keep=state.value.tasks.filter{it.status in listOf("待下载","下载中","保存中")};val remove=state.value.tasks.filterNot{it in keep};remove.forEach{File(app.cacheDir,"phone-queue-${it.id}.part").delete();File(app.cacheDir,"phone-queue-${it.id}.part.meta").delete()};mutable.update{it.copy(tasks=keep)}}
    fun retry(id:String){if(cameraBusy)return;setTask(id){it.copy(status="待下载",message="请手动开始；会重新验证文件身份")}}
    fun cancel(id:String){val t=state.value.tasks.firstOrNull{it.id==id}?:return
        if(t.status in listOf("下载中","保存中")){pause();setTask(id){it.copy(status="已取消",message="等当前块返回后停止；不继续保存")}}
        else setTask(id){it.copy(status="已取消")}}
    fun pause(){worker?.cancel();state.value.tasks.filter{it.status in listOf("待下载","下载中","保存中")}.forEach{t->setTask(t.id){it.copy(status="已暂停",message="已请求停止；手动重试才继续，不自动连接")}}}
    fun stopSession(){pause();clearCompare()}
    private fun idle():Boolean {val u=ui();return !u.cameraMenuBusy && !workPending() && driver.isOpen && !u.liveViewActive && !u.capturing && !u.burstActive && !u.movieBusy && !u.movieRecording && !u.pairingBusy && !u.writing && !u.exposureSyncing && !u.galleryLoading && !u.galleryPreviewBusy && u.downloadingHandle==0L}
    fun startQueue(){if(cameraBusy)return;if(!idle()){message("请连接相机并停止取景、拍摄、配对、参数同步和相册读取后再开始");return}
        worker=scope.launch(start=CoroutineStart.LAZY) {
            try {while(isActive){val task=state.value.tasks.firstOrNull{it.status=="待下载"}?:break
                if(task.camera!=cameraKey()){setTask(task.id){it.copy(status="失败",message="不是原相机/会话，请从当前相册重新加入")};continue}
                if(!idle()){message("相机忙，剩余任务暂停；不会自动恢复");break}
                val token=driver.burstConnectionToken?:break
                val file=File(app.cacheDir,"phone-queue-${task.id}.part")
                update{it.copy(downloadingHandle=task.item.handle)};setTask(task.id){it.copy(status="下载中",message="默认4MiB分块读取；取消在当前块返回后生效")}
                try {
                    val downloaded=withContext(Dispatchers.IO){driver.downloadPhoneFile(task.item,file,token){done,total->setTask(task.id){it.copy(bytes=done,total=total)}}}
                    ensureActive();check(token===driver.burstConnectionToken){"会话已更换"};setTask(task.id){it.copy(item=downloaded,status="保存中")}
                    withContext(Dispatchers.IO){saveFile(downloaded,file){location->setTask(task.id){it.copy(status="已完成",message=location)}}};ensureActive()
                }catch(c:CancellationException){throw c}catch(t:Exception){setTask(task.id){it.copy(status="失败",message=t.message?.take(140)?:"读取失败")}}
                finally{if(state.value.tasks.firstOrNull{it.id==task.id}?.status=="已完成"){file.delete();File(file.path+".meta").delete()};update{it.copy(downloadingHandle=0L)}}
            }}finally{update{it.copy(downloadingHandle=0L)};state.value.tasks.filter{it.status in listOf("下载中","保存中")}.forEach{t->setTask(t.id){it.copy(status="已暂停",message="未完成；手动重试，不自动连接")}};worker=null}
        }
        worker?.start()
    }
    private suspend fun saveFile(item:CameraStorage.Item,file:File,onCommitted:(String)->Unit) {
        val filename=item.filename.substringAfterLast('/').substringAfterLast('\\').take(120).ifBlank{"camera-file"}
        if(Build.VERSION.SDK_INT<29){val dir=app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?:app.filesDir
            dir.mkdirs();val target=File(dir,"${System.currentTimeMillis()}-$filename");val pending=File(dir,target.name+".pending")
            try{file.inputStream().use{input->pending.outputStream().use{out->copyCancellable(input,out)}};currentCoroutineContext().ensureActive()
                withContext(NonCancellable){check(pending.renameTo(target)){"无法完成保存"};onCommitted("APP专用下载目录（卸载会删除）：${target.name}")}}
            finally{pending.delete()};return}
        val cr=app.contentResolver;val video=item.isVideo
        val mime=when{item.isRaw->when(filename.substringAfterLast('.').lowercase(java.util.Locale.ROOT)){"dng"->"image/x-adobe-dng";"nrw"->"image/x-nikon-nrw";else->"image/x-nikon-nef"};video->if(filename.endsWith(".mov",true))"video/quicktime"else"video/mp4";else->"image/jpeg"}
        val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,filename);put(MediaStore.MediaColumns.MIME_TYPE,mime);put(MediaStore.MediaColumns.RELATIVE_PATH,(if(video)"Movies"else"Pictures")+"/Zbox");put(MediaStore.MediaColumns.IS_PENDING,1)}
        val uri=cr.insert(if(video)MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error("手机存储无法创建文件")
        var committed=false
        try {file.inputStream().use{input->(cr.openOutputStream(uri)?:error("无法打开输出文件")).use{out->copyCancellable(input,out)}}
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable){check(cr.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)>0){"无法完成媒体保存"};committed=true;onCommitted("已保存手机相册")}}
        catch(t:Throwable){if(!committed)runCatching{cr.delete(uri,null,null)};throw t}
    }
    private suspend fun copyCancellable(input:java.io.InputStream,out:java.io.OutputStream){val b=ByteArray(128*1024);while(true){currentCoroutineContext().ensureActive();val n=input.read(b);if(n<0)break;out.write(b,0,n)}}
}
