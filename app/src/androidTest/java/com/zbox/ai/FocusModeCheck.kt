package com.zbox.ai
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.zbox.ai.ui.MainViewModel
import com.zbox.ai.ptp.NikonPtpDriver
import org.json.JSONObject
import org.json.JSONArray
class FocusModeCheck:Instrumentation(){
 override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
 private fun waitFor(test:()->Boolean){val end=SystemClock.uptimeMillis()+90000;while(!test()){check(SystemClock.uptimeMillis()<end){"No auto reconnect"};SystemClock.sleep(200)}}
 override fun onStart(){val out=Bundle();var vm:MainViewModel?=null
  try{val a=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(700)
   val m=MainActivity::class.java.getDeclaredField("viewModelRef").apply{isAccessible=true}.get(a) as MainViewModel;vm=m
   if(!m.ui.value.isConnected)runOnMainSync{m.connectAutomatically{error("USB permission needed")}}
   waitFor{m.ui.value.isConnected && !m.ui.value.wifiConnectBusy && !m.ui.value.usbConnectBusy && !m.ui.value.exposureSyncing}
   runOnMainSync{m.startLiveView()};waitFor{m.ui.value.liveViewFrames>=3 && m.ui.value.liveViewBitmap!=null}
   val driver=MainViewModel::class.java.getDeclaredField("driver").apply{isAccessible=true}.get(m)as NikonPtpDriver
   val h=driver.lastLiveViewHeader;val words=JSONArray();for(i in 0 until h.size-1 step 2)words.put(JSONObject().put("offset",i).put("u16BE",((h[i].toInt()and 255)shl 8)or(h[i+1].toInt()and 255)))
   val e=JSONObject().put("model",m.ui.value.deviceInfo.model).put("changeAfAreaAdvertised",0x9205 in m.ui.value.deviceInfo.supportedOperations).put("afDriveAdvertised",0x90c1 in m.ui.value.deviceInfo.supportedOperations).put("previewWidth",m.ui.value.liveViewBitmap!!.width).put("previewHeight",m.ui.value.liveViewBitmap!!.height).put("headerBytes",h.size).put("headerU16BE",words).put("cameraFocusWrites",0).put("cameraShutterPresses",0).put("cameraCardReads",0)
   val props=JSONObject();for(code in listOf(0xd05d,0xd061,0x500a,0x501c)){
    val data=kotlinx.coroutines.runBlocking{driver.getDevicePropDesc(code)}
    props.put("0x%04X".format(code),if(data.isSuccess)data.getOrThrow().take(96).joinToString(""){"%02x".format(it.toInt()and 255)}else data.exceptionOrNull()?.message)
   };e.put("afModeDescriptorsHex",props)
   runOnMainSync{m.stopLiveView()};out.putString("stream","PASS: read-only focus geometry probe EVIDENCE: $e\n");finish(Activity.RESULT_OK,out)
  }catch(t:Throwable){runCatching{runOnMainSync{vm?.stopLiveView()}};out.putString("stream","FAIL FocusModeCheck: ${t.message}\n");finish(Activity.RESULT_CANCELED,out)}
 }
}
