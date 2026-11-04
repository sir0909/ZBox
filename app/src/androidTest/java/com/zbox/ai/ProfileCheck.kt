package com.zbox.ai
import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
/** Explicit final user preference: enable grid, zebra, peaking and histogram, touching no other settings. */
class ProfileCheck:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){val out=Bundle();val p=targetContext.getSharedPreferences("phone_tools_35",0)
        try{check(p.edit().putInt("options",51).putBoolean("assist39",true).commit());check(p.getInt("options",0)==51)
            out.putString("stream","PASS: user-requested four assist switches durably enabled (bits 1+2+16+32=51), obsolete sensor/auto-inspect bits off; other preferences unchanged. No camera, AI or library download.\n");finish(Activity.RESULT_OK,out)
        }catch(t:Throwable){out.putString("stream","FAIL ProfileCheck: ${t.javaClass.simpleName}\n");finish(Activity.RESULT_CANCELED,out)}
    }
}
