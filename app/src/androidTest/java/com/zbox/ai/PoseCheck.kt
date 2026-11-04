package com.zbox.ai
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.zbox.ai.ui.*
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest

/** Offline/local synthetic references only: does not start the network library download or read user photos. */
class PoseCheck:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){val out=Bundle();val app=targetContext.applicationContext as Application;val name="pose39_test_only";val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);var count=0
        fun verify(ok:Boolean,label:String){check(ok){label};count++}
        fun waitFor(test:()->Boolean){val end=SystemClock.uptimeMillis()+15000;while(!test()){check(SystemClock.uptimeMillis()<end);SystemClock.sleep(50)}}
        val root=File(app.filesDir,name);val input=File(app.cacheDir,"pose39-test-source.jpg")
        try{app.deleteSharedPreferences(name);check(!root.exists()){"Test-only folder exists; refuse to overwrite"};val repo=PoseRepository(app,scope,name);waitFor{repo.state.value.ready}
            val s=repo.state.value;verify(s.catalog.size==4155,"Pinned metadata count");verify(s.catalog.map{it.name}.toSet().size==4155,"No duplicate files")
            verify(s.catalog.sumOf{it.bytes}==112015633L,"Pinned image total bytes");verify(s.catalog.all{it.name.matches(Regex("[0-9]{4}-[a-z]{12}\\.jpg"))},"Safe filenames")
            verify(s.catalog.all{it.code.length==12 && it.sha.matches(Regex("[a-f0-9]{40}"))},"Tags and git hashes")
            verify(s.definitions.size==12,"12 filter dimensions");verify(s.catalog.all{i->i.code.mapIndexed{index,c->s.definitions[listOf("shot_size","composition","angle","pose","action","emotion","clothing","hair","color","season","scene","style")[index]]!!.containsKey(c.toString())}.all{it}},"Every code has a label")
            verify(!s.installed && !s.downloading && !root.exists(),"Init never downloads or creates image library")
            verify(repo.shouldAskDownload(),"First use requests consent");repo.acknowledgeDownloadPrompt();verify(!repo.shouldAskDownload(),"Declined prompt does not repeat automatically")
            val item=s.catalog.first();repo.favorite(item);verify(repo.key(item) in repo.state.value.favorites,"Favorite on phone");repo.favorite(item);verify(repo.state.value.favorites.isEmpty(),"Unfavorite")
            verify(repo.load(item)==null,"Missing image not fetched implicitly")
            root.mkdirs();val raw=File(root,"hash-test");val bytes="test-only-not-a-photo".toByteArray();raw.writeBytes(bytes)
            val digest=MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".toByteArray()+bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
            verify(repo.validFile(raw,PoseItem("hash-test","zzzzzzzzzzzz",digest,bytes.size.toLong())),"Git blob hash accepted")
            verify(!repo.validFile(raw,PoseItem("hash-test","zzzzzzzzzzzz","0".repeat(40),bytes.size.toLong())),"Wrong hash rejected")
            val bitmap=Bitmap.createBitmap(20,10,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.BLUE)};input.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle()
            repo.importReferences(listOf(Uri.fromFile(input)));waitFor{repo.state.value.own.size==1}
            val own=repo.state.value.own.first();verify(own.own && repo.imageFile(own).isFile,"Own reference copied locally")
            val decoded=repo.load(own);verify(decoded?.width==20 && decoded.height==10,"Own reference bounded decode");decoded?.recycle()
            verify(!repo.state.value.downloading && !File(root,"library").exists(),"Import never downloads or uploads")
            verify(PoseWords.groups.size==4 && PoseWords.groups.values.sumOf{it.size}==60,"60 original phrases in four categories")
            verify(PoseWords.groups.values.flatten().distinct().size==60,"No duplicate phrases")
            val lines=PoseWords.groups.values.first();verify(PoseWords.next(lines,lines.first())!=lines.first(),"Random avoids immediate repeat")
            out.putString("stream","PASS: $count local pose checks: pinned 4155-entry catalog, 12 tag dimensions, safe names/git hashes, no implicit image/network fetch, consent state, favorites, synthetic own-image import/decode, 60 original phrases. Full 107MiB library NOT downloaded; no camera or AI.\n");finish(Activity.RESULT_OK,out)
        }catch(t:Throwable){out.putString("stream","FAIL PoseCheck: ${t.javaClass.simpleName}: ${t.message}\n");finish(Activity.RESULT_CANCELED,out)}
        finally{scope.cancel();root.deleteRecursively();input.delete();app.deleteSharedPreferences(name)}
    }
}
