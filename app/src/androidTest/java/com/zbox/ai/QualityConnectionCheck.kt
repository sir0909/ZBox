package com.zbox.ai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.zbox.ai.core.CameraStorage
import com.zbox.ai.ptp.NikonPtpDriver
import com.zbox.ai.ptp.HexLog
import com.zbox.ai.ui.MainViewModel
import com.zbox.ai.ui.UiState
import com.zbox.ai.ai.ProviderAccount
import com.zbox.ai.ai.LlmProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray

/** Explicit user-authorized real-camera test. A temporary credential is an instrumentation argument only. */
class QualityConnectionCheck:Instrumentation(){
    private var temporaryKey=""
    override fun onCreate(arguments:Bundle?){temporaryKey=arguments?.getString("tempApiKey").orEmpty();super.onCreate(arguments);start()}
    private fun waitFor(ms:Long=90000,test:()->Boolean){val end=SystemClock.uptimeMillis()+ms;while(!test()){check(SystemClock.uptimeMillis()<end){"Real-device wait timed out; no automatic reconnect"};SystemClock.sleep(150)}}
    override fun onStart(){val out=Bundle();val evidence=JSONObject();var phase="launch";var vm:MainViewModel?=null;var flow:MutableStateFlow<UiState>?=null;var oldAccounts:List<ProviderAccount>?=null;var oldActive="";var startedView=false
        try{
            val a=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(800)
            val m=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}.get(a) as MainViewModel;vm=m
            @Suppress("UNCHECKED_CAST") val f=MainViewModel::class.java.getDeclaredField("_ui").apply{isAccessible=true}.get(m) as MutableStateFlow<UiState>;flow=f
            val d=MainViewModel::class.java.getDeclaredField("driver").apply{isAccessible=true}.get(m) as NikonPtpDriver
            phase="single explicit discovery and connection";val start=SystemClock.elapsedRealtime();val already=m.ui.value.isConnected
            if(!already)runOnMainSync{m.connectAutomatically{error("USB permission requires user; not bypassed")}}
            waitFor{m.ui.value.isConnected};evidence.put("alreadyConnected",already).put("toSessionMs",SystemClock.elapsedRealtime()-start)
            waitFor{!m.ui.value.wifiConnectBusy && !m.ui.value.usbConnectBusy && !m.ui.value.exposureSyncing};evidence.put("readyMs",SystemClock.elapsedRealtime()-start)
            evidence.put("model",m.ui.value.deviceInfo.model)
            val stages=HexLog.entries.value.filter{it.title=="连接发现"}.takeLast(1).map{it.note.replace(Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b"),"[IP]")};evidence.put("discovery",JSONArray(stages))
            phase="real gallery and naming capability";runOnMainSync{m.selectTab(2)};waitFor{!m.ui.value.galleryLoading && m.ui.value.galleryItems.isNotEmpty()}
            val selected=CameraStorage.jpegBrowseItems(m.ui.value.galleryItems).filter{!it.isRaw && !it.isVideo}.take(2);check(selected.size==2){"Need two existing JPEG photos; no capture performed"}
            evidence.put("getObjectPropValueAdvertised",0x9803 in m.ui.value.deviceInfo.supportedOperations)
            runBlocking{val info=d.getObjectInfo(selected[0].handle);evidence.put("objectInfoSucceeded",info.isSuccess)
                if(0x9803 in m.ui.value.deviceInfo.supportedOperations){val r=d.transact(0x9803,intArrayOf(selected[0].handle.toInt(),0xDC07),null,true,expectedConnection=d.burstConnectionToken);evidence.put("objectFilenamePropertyResponse",r.responseCode).put("objectFilenamePropertyBytes",r.data?.size?:0)}}
            runOnMainSync{m.requestGalleryThumbnails(emptyList());m.selectTab(0);m.phone.clearCompare();selected.forEach{m.phone.compare(it)}}
            waitFor{!m.ui.value.galleryLoading && !m.ui.value.galleryPreviewBusy && !m.ui.value.exposureSyncing}
            phase="real comparison 2048";runOnMainSync{m.phone.loadComparePreview()}
            waitFor(180000){m.phone.state.value.compareImages.size==2 || m.phone.state.value.compareStatus.startsWith("高清对比失败")};check(m.phone.state.value.compareImages.size==2){m.phone.state.value.compareStatus}
            val low=selected.map{item->m.phone.state.value.compareImages.getValue(item.handle).let{maxOf(it.width,it.height)}}
            evidence.put("compare2048Edges",JSONArray(low));check(low.all{it>800}){"Selected images did not exceed thumbnail resolution"}
            phase="real comparison 4096";runOnMainSync{m.phone.loadComparePreview(4096)}
            waitFor(180000){!m.phone.state.value.compareLoading};val high=selected.map{item->m.phone.state.value.compareImages.getValue(item.handle).let{maxOf(it.width,it.height)}}
            evidence.put("compare4096Edges",JSONArray(high));check(high.zip(low).all{it.first>it.second}){"4096 request did not add actual decoded detail"}
            check(m.phone.state.value.tasks.isEmpty()){"Comparison must not enqueue downloads"};evidence.put("comparisonUsesActualDecodedImages",true)
            runOnMainSync{m.phone.clearCompare()}
            if(temporaryKey.isNotBlank()){
                phase="real live-view frame for one manual AI request";oldAccounts=m.ui.value.accounts;oldActive=m.ui.value.activeProviderId
                check(!m.ui.value.liveViewActive && !m.ui.value.movieRecording){"Will not alter existing recording/live-view"}
                startedView=true;runOnMainSync{m.startLiveView()};waitFor{m.ui.value.liveViewBitmap!=null && m.ui.value.liveViewFrames>=2}
                val beforeFrame=m.ui.value.liveViewBitmap!!;evidence.put("liveFrame",JSONArray(listOf(beforeFrame.width,beforeFrame.height)))
                val account=ProviderAccount(providerId=LlmProvider.CUSTOM_ID,apiKey=temporaryKey,modelOverride="glm-5.3-flash",customBaseUrl="https://api.hcnsec.cn/v1",customSupportsVision=true)
                runOnMainSync{f.value=f.value.copy(accounts=f.value.accounts.filterNot{it.providerId==LlmProvider.CUSTOM_ID}+account,activeProviderId=LlmProvider.CUSTOM_ID);m.openComposition()}
                phase="single manual AI analyze";runOnMainSync{m.analyzeComposition()}
                waitFor(65000){!m.ui.value.compositionBusy}
                val state=m.ui.value;evidence.put("aiAttemptLimit",1).put("aiAdviceParsed",state.compositionAdvice!=null).put("aiError",state.compositionError)
                state.compositionAdvice?.let{evidence.put("aiSummary",it.summary)}
                check(state.compositionAdvice!=null || state.compositionError.isNotBlank()){"No AI outcome"}
            }else evidence.put("aiExecuted",false)
            evidence.put("noCaptureDeleteParameterApply",true).put("temporaryKeyPersisted",false)
            out.putString("stream","PASS: real Z30 connection and decoded comparison verification. AI result is reported separately, not assumed successful. EVIDENCE: $evidence\n")
            targetContext.getExternalFilesDir(null)?.resolve("phone37-quality-result.json")?.writeText(evidence.toString(2));finish(Activity.RESULT_OK,out)
        }catch(t:Throwable){out.putString("stream","FAIL QualityConnectionCheck phase=$phase: ${t.javaClass.simpleName}: ${t.message?.replace(temporaryKey.takeIf{it.isNotEmpty()}?:"__NO_KEY__","[REDACTED]")} EVIDENCE: $evidence\n");finish(Activity.RESULT_CANCELED,out)}
        finally{temporaryKey="";if(vm!=null)runOnMainSync{val m=vm!!;m.clearComposition(true);m.phone.clearCompare();if(oldAccounts!=null && flow!=null)flow!!.value=flow!!.value.copy(accounts=oldAccounts!!,activeProviderId=oldActive);if(startedView)m.stopLiveView()}}
    }
}
