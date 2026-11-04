package com.zbox.ai
import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import org.json.JSONObject
/** No credential, no image, no completion request, no retries. */
class NetworkReachabilityCheck:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){val out=Bundle();val e=JSONObject();val t=SystemClock.elapsedRealtime()
        try{val c=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).connectTimeout(10,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()
            c.newCall(Request.Builder().url("https://api.gemai.cc/v1/models").head().build()).execute().use{e.put("httpStatus",it.code)}
        }catch(t:Throwable){e.put("exceptionClass",t.javaClass.simpleName).put("causeClass",t.cause?.javaClass?.simpleName?:"").put("reason",t.message?.take(160)?:"")}
        e.put("elapsedMs",SystemClock.elapsedRealtime()-t).put("credentialsSent",false).put("imageSent",false).put("completionRequested",false)
        out.putString("stream","PASS: unauthenticated reachability probe completed; this is not AI success. EVIDENCE: $e\n");finish(Activity.RESULT_OK,out)
    }
}
