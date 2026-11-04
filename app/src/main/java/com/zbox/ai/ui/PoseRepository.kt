package com.zbox.ai.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal data class PoseItem(val name:String,val code:String,val sha:String="",val bytes:Long=0,val own:Boolean=false)
internal data class PoseState(val catalog:List<PoseItem> = emptyList(),val own:List<PoseItem> = emptyList(),val favorites:Set<String> = emptySet(),val ready:Boolean=false,
    val installed:Boolean=false,val downloading:Boolean=false,val done:Int=0,val message:String="",val definitions:Map<String,Map<String,String>> = emptyMap())
/** Only metadata ships in APK. Network downloads can ONLY start from the consent button. */
internal class PoseRepository(private val app:Application,private val scope:CoroutineScope,private val storeName:String="pose39"){
    private val prefs=app.getSharedPreferences(storeName,0)
    private val dir=File(app.filesDir,storeName);private val library=File(dir,"library");private val ownDir=File(dir,"own")
    private val mutable=MutableStateFlow(PoseState());val state=mutable.asStateFlow();private var job:Job?=null
    init{scope.launch(Dispatchers.IO){runCatching{
        val json=JSONObject(app.assets.open("pose-catalog.json").bufferedReader().use{it.readText()});val a=json.getJSONArray("items")
        val items=(0 until a.length()).map{val o=a.getJSONObject(it);PoseItem(o.getString("name"),o.getString("code"),o.getString("sha"),o.getLong("bytes"))}
        val defs=json.getJSONObject("definitions");val definitions=defs.keys().asSequence().associateWith{k->val values=defs.getJSONObject(k);values.keys().asSequence().associateWith{values.getString(it)}}
        val own=ownDir.listFiles().orEmpty().filter{it.name.matches(Regex("[a-f0-9-]+\\.jpg"))}.sortedBy{it.name}.map{PoseItem(it.name,"zzzzzzzzzzzz",own=true)}
        mutable.update{it.copy(catalog=items,own=own,favorites=prefs.getStringSet("favorites",emptySet()).orEmpty().toSet(),ready=true,installed=File(library,"complete").readTextOrEmpty()==COMMIT,definitions=definitions)}
    }.onFailure{mutable.update{it.copy(ready=true,message="姿势目录加载失败，可稍后重开")}}}}
    private fun File.readTextOrEmpty()=runCatching{readText()}.getOrDefault("")
    fun shouldAskDownload()=!prefs.getBoolean("downloadPromptSeen",false)
    fun acknowledgeDownloadPrompt(){prefs.edit().putBoolean("downloadPromptSeen",true).apply()}
    fun favorite(item:PoseItem){val key=key(item);val now=mutable.value.favorites;val next=if(key in now)now-key else now+key
        mutable.update{it.copy(favorites=next)};prefs.edit().putStringSet("favorites",next).apply()}
    fun key(item:PoseItem)=(if(item.own)"own:" else "poze:")+item.name
    fun imageFile(item:PoseItem)=File(if(item.own)ownDir else library,item.name)
    fun load(item:PoseItem):Bitmap? {val f=imageFile(item);if(!f.isFile)return null
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(f.path,bounds);if(bounds.outWidth<=0 || bounds.outHeight<=0)return null
        var sample=1;while(bounds.outWidth/sample>1600 || bounds.outHeight/sample>1600)sample*=2
        return BitmapFactory.decodeFile(f.path,BitmapFactory.Options().apply{inSampleSize=sample})}
    fun cancelDownload(){job?.cancel();mutable.update{it.copy(message="正在取消；已核验图片保留，下次同意下载后可重试")}}
    fun downloadAfterConsent(){if(job!=null || !state.value.ready || state.value.installed)return
        val task=scope.launch(Dispatchers.IO,start=CoroutineStart.LAZY){var connection:HttpURLConnection?=null
            try{library.mkdirs();val expected=state.value.catalog.associateBy{it.name}
                val verified=expected.values.filter{validFile(imageFile(it),it)}.map{it.name}.toMutableSet()
                val remaining=expected.values.filter{it.name !in verified}.sumOf{it.bytes}
                check(StatFs(library.path).availableBytes>remaining+24L*1024*1024){"手机空间不足，请至少预留140MiB"}
                mutable.update{it.copy(downloading=true,done=verified.size,message="连接GitHub固定版本图库；仅本次同意后下载，不调用AI")}
                connection=(URL("https://codeload.github.com/zslongf-hash/poze/zip/$COMMIT").openConnection() as HttpURLConnection).apply{connectTimeout=15000;readTimeout=30000;setRequestProperty("User-Agent","Zbox-pose39")}
                check(connection!!.responseCode==200){"图库服务HTTP ${connection!!.responseCode}"}
                var expanded=0L;var entryCount=0
                ZipInputStream(connection!!.inputStream.buffered()).use{zip->while(true){currentCoroutineContext().ensureActive();val e=zip.nextEntry?:break;entryCount++;check(entryCount<=6000){"归档项目异常"}
                    val prefix="poze-$COMMIT/assets/images/pose_samples/"
                    if(!e.isDirectory && e.name.startsWith(prefix)){
                        val name=e.name.removePrefix(prefix);val item=expected[name]
                        if(item!=null && name !in verified){check(name==File(name).name && !name.contains('\\')){"非法图片路径"}
                            val tmp=File(library,"$name.part")
                            try{tmp.outputStream().use{output->val b=ByteArray(32768);var nbytes=0L;while(true){currentCoroutineContext().ensureActive();val n=zip.read(b);if(n<0)break;nbytes+=n;expanded+=n;check(nbytes<=item.bytes && nbytes<=8L*1024*1024 && expanded<=140L*1024*1024){"图片大小超出目录声明"};output.write(b,0,n)}}
                                check(validFile(tmp,item)){"图片校验失败，未采用文件"};check(tmp.renameTo(imageFile(item))){"无法保存图库图片"};verified.add(name)
                                mutable.update{it.copy(done=verified.size,message="正在下载并核验 ${verified.size}/${expected.size} 张")}
                            }finally{tmp.delete()}
                        }
                    }
                    val drain=ByteArray(32768)
                    while(true){currentCoroutineContext().ensureActive();val n=zip.read(drain);if(n<0)break;expanded+=n;check(expanded<=160L*1024*1024){"归档展开超出限制"}}
                    zip.closeEntry()
                }}
                check(verified.size==expected.size){"图库不完整，请手动重试"};File(library,"complete").writeText(COMMIT)
                mutable.update{it.copy(installed=true,message="4155张图库已核验，可离线使用")}
            }catch(c:CancellationException){mutable.update{it.copy(message="下载已取消；下次需再次同意，不会后台自动重试")};throw c}
            catch(t:Exception){mutable.update{it.copy(message="下载未完成：${t.message?.take(120)}。已核验部分保留，请手动重试。")}}
            finally{connection?.disconnect();mutable.update{it.copy(downloading=false)};job=null}
        };job=task;task.start()
    }
    internal fun validFile(file:File,item:PoseItem):Boolean {if(!file.isFile || file.length()!=item.bytes)return false
        val digest=MessageDigest.getInstance("SHA-1");digest.update("blob ${item.bytes}\u0000".toByteArray())
        file.inputStream().use{input->val b=ByteArray(32768);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}==item.sha}
    fun importReferences(uris:List<Uri>){scope.launch(Dispatchers.IO){ownDir.mkdirs();var ok=0;var failed=0
        for(uri in uris.take(24)){if(state.value.own.size>=200){failed++;continue};var tmp:File?=null
            try{val bytes=app.contentResolver.openInputStream(uri)?.use{input->val out=java.io.ByteArrayOutputStream();val b=ByteArray(32768);while(true){val n=input.read(b);if(n<0)break;check(out.size()+n<=32*1024*1024){"单图超过32MiB"};out.write(b,0,n)};out.toByteArray()}?:error("无法读取")
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds);check(bounds.outWidth>0 && bounds.outHeight>0){"不支持的图片"}
                var sample=1;while(bounds.outWidth/sample>1600 || bounds.outHeight/sample>1600)sample*=2
                val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?:error("图片解码失败")
                val name=java.util.UUID.randomUUID().toString()+".jpg";tmp=File(ownDir,"$name.part")
                try{tmp.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,88,it))}}finally{bitmap.recycle()}
                check(tmp.renameTo(File(ownDir,name)));mutable.update{it.copy(own=it.own+PoseItem(name,"zzzzzzzzzzzz",own=true))};ok++
            }catch(c:CancellationException){throw c}catch(_:Exception){failed++}finally{tmp?.delete()}
        };mutable.update{it.copy(message="已添加 $ok 张自己的参考图${if(failed>0)"；$failed 张未添加（格式/大小或200张上限）"else""}。只复制到手机本地，不上传。")}
    }}
    companion object {const val COMMIT="827ce3e424d0598bd7238ecc0c0a43ca362c7927"}
}
