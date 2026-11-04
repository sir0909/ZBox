package com.zbox.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.content.ContentUris
import com.zbox.ai.core.CameraStorage
import com.zbox.ai.core.PhoneToolsPolicy
import com.zbox.ai.ptp.NikonPtpDriver
import com.zbox.ai.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/** Run only with explicit user authorization. Read existing media; never capture/delete/write camera parameters. */
class CameraReadCheck:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private fun waitFor(ms:Long=90000,test:()->Boolean){val end=SystemClock.uptimeMillis()+ms;while(!test()){check(SystemClock.uptimeMillis()<end){"Camera wait timed out; no reconnect attempted"};SystemClock.sleep(200)}}
    private fun digest(input:java.io.InputStream):String=input.use{val md=MessageDigest.getInstance("SHA-256");val b=ByteArray(128*1024);while(true){val n=it.read(b);if(n<0)break;md.update(b,0,n)};md.digest().joinToString(""){v->"%02x".format(v.toInt() and 255)}}
    override fun onStart(){val result=Bundle();val evidence=JSONObject();var phase="launch";var model:MainViewModel?=null
        try {
            val a=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(1000)
            val vm=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}.get(a) as MainViewModel;model=vm
            val d=MainViewModel::class.java.getDeclaredField("driver").apply{isAccessible=true}.get(vm) as NikonPtpDriver
            phase="single authorized connection attempt"
            if(!vm.ui.value.isConnected)runOnMainSync{vm.connectAutomatically{error("USB permission required; no bypass")}}
            waitFor{vm.ui.value.isConnected || (vm.ui.value.hotspotCameras.size>1 && !vm.ui.value.hotspotScanBusy)}
            check(vm.ui.value.isConnected){"Multiple cameras require user selection"}
            evidence.put("model",vm.ui.value.deviceInfo.model);evidence.put("connected",true)
            check(!vm.ui.value.movieRecording && !vm.ui.value.liveViewActive){"Busy camera; test will not change recording/live-view state"}
            phase="read gallery"
            runOnMainSync{vm.selectTab(2)};waitFor{!vm.ui.value.galleryLoading && vm.ui.value.galleryItems.isNotEmpty()}
            val items=CameraStorage.jpegBrowseItems(vm.ui.value.galleryItems)
            evidence.put("visibleCards",items.size);evidence.put("pairedCards",items.count{it.companionHandle!=null});evidence.put("rawOnlyCards",items.count{it.isRaw})
            waitFor(30000){vm.ui.value.thumbnails.isNotEmpty()};evidence.put("thumbnailRead",true)
            runOnMainSync{vm.requestGalleryThumbnails(emptyList());vm.selectTab(0)};SystemClock.sleep(500)
            val candidates=items.filter{!it.isVideo}.take(8);val selected=mutableListOf<CameraStorage.Item>();val skips=JSONArray()
            runBlocking {
                for(item in candidates){if(selected.any{!it.isRaw} && selected.any{it.isRaw})break
                    val handles=listOfNotNull(item.handle,item.companionHandle)
                    for(handle in handles){val fresh=d.getObjectInfo(handle).getOrNull()
                        val source=fresh ?: if(!item.filenameVerified) {
                            skips.put("STA ObjectInfo locked: original name unavailable, explicitly test disclosed generated name")
                            if(handle==item.handle)item else item.copy(handle=handle,filename=item.companionFilename?:error("No RAW name"),sizeBytes=0,companionHandle=null)
                        } else {skips.put("ObjectInfo unavailable on non-STA source");continue}
                        if(source.sizeBytes>32L*1024*1024){skips.put("Skipped >32MiB test file");continue}
                        if(!source.isVideo && selected.none{it.isRaw==source.isRaw})selected.add(source)
                    }
                }
            }
            evidence.put("downloadSkips",skips);val downloads=JSONArray();evidence.put("downloads",downloads)
            for(item in selected.take(2)){
                phase="bounded reference read and explicit queue save"
                val cache=File(targetContext.cacheDir,"phone36-camera-reference-only.part");val meta=File(cache.path+".meta")
                try {
                    cache.delete();meta.delete();val verified=runBlocking{d.downloadPhoneFile(item,cache,d.burstConnectionToken!!){_,total->check(total in 1..(32L*1024*1024)){"Read-test 32MiB size bound; no data read"}}}
                    val expectedHash=digest(cache.inputStream());check(cache.length()==verified.sizeBytes)
                    val head=cache.inputStream().use{val h=ByteArray(4);check(it.read(h)==4);h}
                    if(item.isRaw)check((head[0]==0x49.toByte() && head[1]==0x49.toByte()) || (head[0]==0x4d.toByte() && head[1]==0x4d.toByte())){"RAW must remain TIFF-family original"}
                    else check(head[0]==0xff.toByte() && head[1]==0xd8.toByte()){"JPEG must remain actual JPEG original"}
                    val cr=targetContext.contentResolver;val collection=MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    var lastId=0L;cr.query(collection,arrayOf(MediaStore.MediaColumns._ID),"${MediaStore.MediaColumns.RELATIVE_PATH}=?",arrayOf("Pictures/Zbox/"),"${MediaStore.MediaColumns._ID} DESC")?.use{if(it.moveToFirst())lastId=it.getLong(0)}
                    val before=vm.phone.state.value.tasks.map{it.id}.toSet()
                    runOnMainSync{vm.phone.enqueue(item,item.isRaw);vm.phone.startQueue()}
                    waitFor(180000){vm.phone.state.value.tasks.any{it.id !in before && it.status in listOf("已完成","失败","已暂停")}}
                    val task=vm.phone.state.value.tasks.first{it.id !in before};check(task.status=="已完成"){"Queue failed: ${task.message}"}
                    val record=JSONObject().put("raw",item.isRaw).put("bytes",verified.sizeBytes).put("sha256",expectedHash).put("nameHash",PhoneToolsPolicy.hash(verified.filename)).put("originalNameVerified",verified.filenameVerified)
                    var checked=false
                    cr.query(collection,arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.IS_PENDING),"${MediaStore.MediaColumns._ID}>? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",arrayOf(lastId.toString(),"Pictures/Zbox/"),"${MediaStore.MediaColumns._ID} DESC")?.use{cursor->while(cursor.moveToNext()){
                        val uri=ContentUris.withAppendedId(collection,cursor.getLong(0));val hash=digest(cr.openInputStream(uri)?:error("Saved media unreadable"))
                        if(hash==expectedHash){check(cursor.getString(1)==verified.filename && cursor.getLong(2)==verified.sizeBytes && cursor.getInt(3)==0){"Saved metadata mismatch"};checked=true;break}
                    }}
                    check(checked){"Saved media exact bytes not verified"};record.put("mediaStoreExactMatch",true);downloads.put(record)
                }finally{cache.delete();meta.delete()}
            }
            evidence.put("noCaptureDeleteParameterWrite",true);evidence.put("savedTestDownloadsRetained",true)
            targetContext.getExternalFilesDir(null)?.resolve("phone36-camera-read-result.json")?.writeText(evidence.toString(2))
            result.putString("stream","PASS: authorized real-camera gallery/thumbnail read. Exact-byte MediaStore downloads verified: ${downloads.length()}; unverified formats explicitly skipped. No capture/delete/parameter write/hotspot change/speed test. EVIDENCE: $evidence\n");finish(Activity.RESULT_OK,result)
        }catch(t:Throwable){evidence.put("phase",phase).put("error",t.message?:t.javaClass.simpleName);targetContext.getExternalFilesDir(null)?.resolve("phone36-camera-read-result.json")?.writeText(evidence.toString(2));result.putString("stream","FAIL CameraReadCheck phase=$phase: ${t.javaClass.simpleName}: ${t.message}\n");finish(Activity.RESULT_CANCELED,result)}
    }
}
