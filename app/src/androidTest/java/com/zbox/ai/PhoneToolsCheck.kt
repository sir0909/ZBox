package com.zbox.ai

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import com.zbox.ai.core.CameraStorage
import com.zbox.ai.core.PhoneToolsPolicy
import com.zbox.ai.ptp.NikonPtpDriver
import com.zbox.ai.ui.*
import kotlinx.coroutines.*

/** Pending Android execution. Uses synthetic pixels and a separate test-only preferences file. */
class PhoneToolsCheck:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){val result=Bundle();val app=targetContext.applicationContext as Application;val pref="phone_tools_38_instrumentation_only";var count=0
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        fun verify(ok:Boolean,text:String){check(ok){text};count++}
        try {app.deleteSharedPreferences(pref)
            val frame=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);frame.eraseColor(android.graphics.Color.WHITE)
            val analysis=analyzePhoneFrame(frame,true,false)?:error("Bitmap analysis failed")
            verify(analysis.first.clipped==10000,"Synthetic white highlight count");verify(analysis.first.histogram.sum()==10000,"Histogram conservation")
            verify(analysis.second.width==100 && analysis.second.height==100,"Overlay shape")
            val large=Bitmap.createBitmap(1500,1000,Bitmap.Config.ARGB_8888)
            val small=analyzePhoneFrame(large)?:error("Downsampling failed");verify(small.second.width<=320 && small.second.height<=320,"Analysis CPU bound")
            verify(!large.isRecycled && !frame.isRecycled,"Analysis recycled source")
            var state=UiState(deviceInfo=NikonPtpDriver.DeviceInfo(model="NIKON Z 50II",serial="TEST-SERIAL-NEVER-EXPORT",firmwareVersion="1.00"))
            val driver=NikonPtpDriver(app)
            val work=PhoneWorkbench(app,scope,driver,{state},{f->state=f(state)},preferencesName=pref)
            verify(work.state.value.options==PhoneOptions(),"Fresh tools use v39 enabled defaults")
            val report=work.report();verify(!report.contains("TEST-SERIAL"),"Serial leaked to report");verify(!report.contains("192.168."),"Raw endpoint leaked")
            val item=CameraStorage.Item(1,1,0x3801,"TEST.JPG",100,100,100,"2026-01-01",false)
            verify(UiState().galleryFilter==1,"Default JPEG browsing")
            val raw=item.copy(handle=2,format=0xB103,filename="TEST.NEF")
            val paired=CameraStorage.jpegBrowseItems(listOf(raw,item))
            verify(paired.size==1 && paired[0].handle==1L && paired[0].companionHandle==2L,"One JPEG card with actual RAW handle")
            verify(CameraStorage.jpegBrowseItems(listOf(raw)).single().isRaw,"RAW-only must not invent JPEG original")
            verify(CameraStorage.jpegBrowseItems(listOf(item,raw.copy(storageId=2))).size==2,"No cross-card pairing")
            verify(CameraStorage.jpegBrowseItems(listOf(item.copy(parentHandle=1),raw.copy(parentHandle=2))).size==2,"No cross-folder pairing")
            verify(CameraStorage.jpegBrowseItems(listOf(item,raw,raw.copy(handle=3))).size==3,"Ambiguous RAW cannot pair")
            verify(CameraStorage.jpegBrowseItems(listOf(item.copy(capturedAt="",sortCapturedAt=""),raw.copy(capturedAt="",sortCapturedAt=""))).size==2,"No identity-free pair")
            val earlier=item.copy(handle=9,sortCapturedAt="20260101T120001")
            val later=item.copy(handle=8,sortCapturedAt="20260101T120002")
            verify(CameraStorage.jpegBrowseItems(listOf(earlier,later)).first().handle==8L,"Seconds retained for newest-first ordering")
            work.toggleMark(item,1);verify(work.mark(item)==1,"Local favorite")
            work.toggleMark(item,1);verify(work.mark(item)==0,"Unfavorite")
            work.toggleMark(item,2);verify(work.mark(item)==2,"Local pending selection")
            val stored=app.getSharedPreferences(pref,0).getString("marks","").orEmpty()
            verify(!stored.contains("TEST-SERIAL") && !stored.contains("TEST.JPG"),"Persistent photo key must be hashed")
            work.compare(item);work.compare(item.copy(handle=2,filename="B.JPG"));work.compare(item.copy(handle=3,filename="C.JPG"))
            verify(work.state.value.compare.size==2,"Comparison must stay bounded")
            work.clearCompare();verify(work.state.value.compare.isEmpty(),"Clear comparison")
            work.compare(item.copy(filename="MOVIE.MOV"));verify(work.state.value.compare.isEmpty(),"Video comparison rejected")
            work.enqueue(item);work.startQueue();verify(work.state.value.tasks.isEmpty() && !work.cameraBusy,"Disconnected queue must not create I/O")
            verify(work.state.value.options.zebra && work.state.value.options.peaking && work.state.value.options.histogram && work.state.value.options.grid,"All four assist defaults enabled")
            verify(PhoneWorkbench::class.java.declaredMethods.none{it.name in listOf("saveParameters","previewParameters","applyParameters")},"Removed quick parameter write path")
            state=state.copy(usbState=NikonPtpDriver.State.SESSION_OPEN,paramsLoaded=true)
            val ba=item.copy(handle=70,filename="BATCH-A.JPG",companionHandle=71,companionFilename="BATCH-A.NEF")
            val br=ba.copy(handle=71,filename="BATCH-A.NEF",companionHandle=null,companionFilename=null)
            val bv=item.copy(handle=72,filename="BATCH.MOV")
            verify(work.batchEligible(listOf(ba,br,bv),false).size==2,"JPEG batch skips RAW-only and keeps video")
            verify(work.batchEligible(listOf(ba,br,bv),true).size==2,"RAW batch deduplicates actual companion handle")
            verify(work.batchEligible(listOf(ba.copy(companionFilename=null)),true).isEmpty(),"Unknown RAW filename not batch eligible")
            verify(work.enqueueBatch(listOf(ba,bv),false)==0 && work.state.value.tasks.isEmpty(),"Disconnected batch must not enqueue or perform I/O")
            work.markBatch(listOf(ba,bv),1);work.markBatch(listOf(ba,bv),1)
            verify(work.mark(ba)==1 && work.mark(bv)==1,"Batch favorite is idempotent, not toggle")
            work.markBatch(listOf(ba,bv),2);verify(work.mark(ba)==2 && work.mark(bv)==2,"Batch pending selection")
            val legacyPref=pref+"_legacy";app.getSharedPreferences(legacyPref,0).edit().putInt("options",4).putInt("frame",2).commit()
            try{val migrated=PhoneWorkbench(app,scope,driver,{state},{f->state=f(state)},preferencesName=legacyPref)
                verify(migrated.state.value.options.histogram && migrated.state.value.options.grid && migrated.state.value.options.frame==2,"Old level bit ignored, other options retained")
                migrated.options(migrated.state.value.options.copy(histogram=true))
                verify(app.getSharedPreferences(legacyPref,0).getInt("options",0)==51,"Histogram uses new bit and removes obsolete sensor bit")
            }finally{app.deleteSharedPreferences(legacyPref)}
            work.options(PhoneOptions(zebra=true,frame=2,histogram=true))
            val restored=PhoneWorkbench(app,scope,driver,{state},{f->state=f(state)},preferencesName=pref)
            verify(restored.state.value.options.zebra && restored.state.value.options.frame==2 && restored.state.value.options.histogram,"Local options persist")
            work.stopSession();verify(!work.cameraBusy,"Stopped local work")
            result.putString("stream","PASS: $count phone Android checks: synthetic analysis/CPU bound, no source recycling, test-only local options/hashed marks, bounded comparison, no disconnected I/O, removed parameter plan paths and all-on assist migration. No camera, AI, real MediaStore save or user preferences modified.\n")
            finish(Activity.RESULT_OK,result)
        }catch(t:Throwable){result.putString("stream","FAIL PhoneToolsCheck: ${t.javaClass.simpleName}: ${t.message}\n");finish(Activity.RESULT_CANCELED,result)}
        finally {scope.cancel();app.deleteSharedPreferences(pref)}
    }
}
