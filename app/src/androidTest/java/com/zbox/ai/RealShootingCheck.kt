package com.zbox.ai
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.zbox.ai.ui.*
import com.zbox.ai.ai.ProviderAccount
import com.zbox.ai.ai.LlmProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** User explicitly approved one real shutter press, retained. Marker forbids silently taking a second shot. */
class RealShootingCheck:Instrumentation(){
    private var key="";private var model="gpt-5.4-mini"
    override fun onCreate(arguments:Bundle?){key=arguments?.getString("tempApiKey").orEmpty();model=arguments?.getString("visionModel")?:model;super.onCreate(arguments);start()}
    private fun node(label:String):AccessibilityNodeInfo?{runCatching{uiAutomation.javaClass.getMethod("clearCache").invoke(uiAutomation)}
        fun find(n:AccessibilityNodeInfo?,d:Int):AccessibilityNodeInfo?{if(n==null||d>65)return null;n.refresh();if(n.isVisibleToUser&&(n.text?.toString()==label||n.contentDescription?.toString()==label))return n
            for(i in 0 until n.childCount)find(n.getChild(i),d+1)?.let{return it};return null};return find(uiAutomation.rootInActiveWindow,0)}
    private fun waitFor(ms:Long=90000,test:()->Boolean){val end=SystemClock.uptimeMillis()+ms;while(!test()){check(SystemClock.uptimeMillis()<end){"Timed out; no automatic retry/reconnect"};SystemClock.sleep(150)}}
    private fun click(label:String){var n=node(label)?:error("Missing $label");while(!n.isClickable)n=n.parent?:error("No click action $label");check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));SystemClock.sleep(350)}
    override fun onStart(){val out=Bundle();val e=JSONObject();var phase="launch";var vm:MainViewModel?=null;var flow:MutableStateFlow<UiState>?=null;var originalAccounts:List<ProviderAccount>?=null;var originalActive="";var startedView=false
        val captureMarker=File(targetContext.filesDir,"code39-one-shot-marker.json");val aiMarker=File(targetContext.filesDir,"code39-one-ai-marker.json")
        try{val a=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(700)
            val m=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}.get(a) as MainViewModel;vm=m
            @Suppress("UNCHECKED_CAST") val f=MainViewModel::class.java.getDeclaredField("_ui").apply{isAccessible=true}.get(m) as MutableStateFlow<UiState>;flow=f
            // Final acceptance explicitly requested all four assists enabled on this phone.
            runOnMainSync{m.phone.options(PhoneOptions())}
            targetContext.getSharedPreferences("phone_tools_35",0).edit().putInt("options",51).commit()
            e.put("allFourAssistOptionsEnabled",m.phone.state.value.options.zebra && m.phone.state.value.options.peaking && m.phone.state.value.options.histogram && m.phone.state.value.options.grid)
            phase="one explicit discovery";if(!m.ui.value.isConnected)runOnMainSync{m.connectAutomatically{error("USB permission needed; not bypassed")}}
            waitFor{m.ui.value.isConnected && !m.ui.value.exposureSyncing && !m.ui.value.wifiConnectBusy && !m.ui.value.usbConnectBusy};e.put("model",m.ui.value.deviceInfo.model)
            check(!m.ui.value.movieRecording && !m.ui.value.capturing && !m.phone.cameraBusy)
            if(!m.ui.value.liveViewActive){startedView=true;runOnMainSync{m.startLiveView()}}
            waitFor{m.ui.value.liveViewBitmap!=null && m.ui.value.liveViewFrames>=2};e.put("liveFrame",JSONArray(listOf(m.ui.value.liveViewBitmap!!.width,m.ui.value.liveViewBitmap!!.height)))
            phase="real immersive fullscreen";waitFor{node("全屏取景")!=null};click("全屏取景")
            waitFor{a.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE && node("退出全屏")!=null && node("拍照快门")!=null};SystemClock.sleep(700)
            val screenshot=uiAutomation.takeScreenshot();val rect=Rect();uiAutomation.rootInActiveWindow.getBoundsInScreen(rect)
            check(rect.width()>=screenshot.width*.98 && rect.height()>=screenshot.height*.98){"Not truly fullscreen: $rect"}
            targetContext.getExternalFilesDir(null)!!.resolve("phone39-fullscreen-real.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
            e.put("fullscreenPixels",JSONArray(listOf(screenshot.width,screenshot.height))).put("fullscreenBounds",rect.flattenToString());screenshot.recycle()
            click("曝光");waitFor{node("收起曝光")!=null && node("光圈")!=null}
            e.put("fullscreenExposurePanelReachable",true).put("exposureParameterWritesExecuted",false)
            click("收起曝光");waitFor{node("收起曝光")==null}
            phase="single authorized shutter; no retry"
            if(captureMarker.isFile){val previous=JSONObject(captureMarker.readText());check(previous.optBoolean("confirmed")){"A shutter was already attempted; no permission for another"};e.put("captureEvidenceReused",true).put("capture",previous)}
            else{captureMarker.writeText(JSONObject().put("attempted",true).put("confirmed",false).toString())
                val before=m.ui.value.lastCaptureHandle;click("拍照快门")
                waitFor(180000){!m.ui.value.capturing && m.ui.value.lastCaptureHandle!=before && m.ui.value.lastCapture!=null}
                waitFor{m.ui.value.liveViewActive && m.ui.value.liveViewBitmap!=null}
                check(node("退出全屏")!=null){"Single shot exited fullscreen"}
                val shot=JSONObject().put("attempted",true).put("confirmed",true).put("shutterPresses",1).put("newCardHandle",m.ui.value.lastCaptureHandle)
                    .put("captureMsg",m.ui.value.captureMsg).put("captureRetainedOnCamera",true).put("liveViewResumedInFullscreen",true)
                captureMarker.writeText(shot.toString());e.put("capture",shot)
            }
            click("退出全屏");waitFor{node("退出全屏")==null && a.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
            phase="one manual real-frame AI request"
            if(key.isNotBlank()){
                check(!aiMarker.exists()){"An AI request has already been attempted; no silent retry"}
                originalAccounts=m.ui.value.accounts;originalActive=m.ui.value.activeProviderId
                val account=ProviderAccount(providerId=LlmProvider.CUSTOM_ID,apiKey=key,modelOverride=model,customBaseUrl="https://api.gemai.cc/v1",customSupportsVision=true)
                runOnMainSync{f.value=f.value.copy(accounts=f.value.accounts.filterNot{it.providerId==LlmProvider.CUSTOM_ID}+account,activeProviderId=LlmProvider.CUSTOM_ID);m.openComposition()}
                aiMarker.writeText(JSONObject().put("attempted",true).put("model",model).put("success",false).toString())
                runOnMainSync{m.analyzeComposition()};waitFor(75000){!m.ui.value.compositionBusy}
                val u=m.ui.value;val result=JSONObject().put("attempted",true).put("attemptLimit",1).put("model",model).put("baseUrl","https://api.gemai.cc/v1")
                    .put("success",u.compositionAdvice!=null).put("error",u.compositionError.replace(key,"[REDACTED]"))
                u.compositionSnapshot?.let{result.put("actualSnapshotPixels",JSONArray(listOf(it.width,it.height)))}
                u.compositionAdvice?.let{result.put("summary",it.summary)};aiMarker.writeText(result.toString());e.put("ai",result)
            }else e.put("aiExecuted",false)
            e.put("temporaryCredentialPersisted",false).put("cameraDeleteOrVideoExecuted",false).put("fullLibraryDownloaded",false)
            targetContext.getExternalFilesDir(null)!!.resolve("phone39-shooting-result.json").writeText(e.toString(2))
            out.putString("stream","PASS: real Z30 fullscreen geometry, explicit exposure panel, one authorized retained photo and liveview resume. AI outcome is separately reported, never assumed PASS. EVIDENCE: $e\n");finish(Activity.RESULT_OK,out)
        }catch(t:Throwable){out.putString("stream","FAIL RealShootingCheck phase=$phase: ${t.message?.replace(key.takeIf{it.isNotEmpty()}?:"__NO_KEY__","[REDACTED]")} EVIDENCE: $e\n");finish(Activity.RESULT_CANCELED,out)}
        finally{key="";if(vm!=null)runOnMainSync{val m=vm!!;m.clearComposition(true);if(originalAccounts!=null && flow!=null)flow!!.value=flow!!.value.copy(accounts=originalAccounts!!,activeProviderId=originalActive);if(startedView)m.stopLiveView()}}
    }
}
