package com.zbox.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun PoseReferenceEntry(repo:PoseRepository){var expanded by remember{mutableStateOf(false)}
    CollapsibleSectionHeader("拍照姿势 / 引导话术",expanded,{expanded=!expanded})
    if(expanded)PoseReferenceContent(repo,embedded=true,onClose={})
}
@Composable internal fun PoseReferenceDialog(repo:PoseRepository,backdrop:android.graphics.Bitmap?=null,onClose:()->Unit){
    if(backdrop!=null){GlassCameraSheet(backdrop,onClose){PoseReferenceContent(repo,embedded=false,onClose=onClose,glass=true)};return}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){PoseReferenceContent(repo,embedded=false,onClose=onClose)}
}
@Composable private fun PoseReferenceContent(repo:PoseRepository,embedded:Boolean,onClose:()->Unit,glass:Boolean=false){val state by repo.state.collectAsState();val context=LocalContext.current
    var consent by remember{mutableStateOf(repo.shouldAskDownload() && !state.installed && !state.downloading)}
    var tab by remember{mutableStateOf(0)};var source by remember{mutableStateOf("全部")};var favorites by remember{mutableStateOf(false)}
    var filtersOpen by remember{mutableStateOf(false)};var filters by remember{mutableStateOf<Map<Int,Char>>(emptyMap())}
    var selected by remember{mutableStateOf("")};var group by remember{mutableStateOf("夸赞鼓励")};var line by remember{mutableStateOf(PoseWords.groups.getValue("夸赞鼓励").first())}
    val localPrefs=remember{context.getSharedPreferences("pose_words39",0)}
    var savedWords by remember{mutableStateOf(localPrefs.getStringSet("favorites",emptySet()).orEmpty().toSet())}
    var customWords by remember{mutableStateOf(localPrefs.getStringSet("custom",emptySet()).orEmpty().toSet())}
    var customDraft by remember{mutableStateOf("")}
    fun phraseChoices(name:String):List<String> = when(name){"话术收藏"->savedWords.toList();"我的话术"->customWords.toList();else->PoseWords.groups[name].orEmpty()}
    fun emptyPhrase(name:String)=if(name=="我的话术")"还没有添加自己的话术" else "还没有收藏话术"
    fun selectPhraseGroup(name:String){group=name;val choices=phraseChoices(name);line=if(choices.isEmpty())emptyPhrase(name)else PoseWords.next(choices,line)}
    fun addCustomPhrase(){val phrase=customDraft.trim().take(180);if(phrase.isNotEmpty()){customWords=(customWords+phrase).toSet();localPrefs.edit().putStringSet("custom",customWords).apply();group="我的话术";line=phrase;customDraft=""}}
    fun deleteCurrentCustomPhrase(){customWords=customWords-line;localPrefs.edit().putStringSet("custom",customWords).apply();line=customWords.firstOrNull()?:emptyPhrase("我的话术")}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()){repo.importReferences(it)}
    val items=remember(state.catalog,state.own,state.installed,state.favorites,source,favorites,filters){
        ((if(state.installed && source!="我的")state.catalog else emptyList())+(if(source!="图库")state.own else emptyList()))
            .filter{(!favorites || repo.key(it) in state.favorites) && (it.own || filters.all{(i,c)->it.code.getOrNull(i)==c})}}
    val index=items.indexOfFirst{repo.key(it)==selected}.coerceAtLeast(0);val current=items.getOrNull(index)
    val bitmap by produceState<android.graphics.Bitmap?>(null,current){value=null;value=withContext(Dispatchers.IO){current?.let{repo.load(it)}}}
    val labels=listOf("景别","构图","角度","姿态","动作","情绪","服装","发型","颜色","季节","场景","风格")
    val fields=listOf("shot_size","composition","angle","pose","action","emotion","clothing","hair","color","season","scene","style")
    Column((if(embedded)Modifier.fillMaxWidth()else Modifier.fillMaxWidth().fillMaxHeight(.96f)).background(if(glass)androidx.compose.ui.graphics.Color.Transparent else ZColors.SurfaceHigh).padding(if(embedded)8.dp else 12.dp)){
            if(!embedded && !glass)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("拍照姿势 / 引导话术",color=ZColors.TextPrimary,modifier=Modifier.weight(1f));TextButton(onClick=onClose){Text("×")}}
        if(glass){
            Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Column(Modifier.weight(.42f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    ZOutlineButton(if(tab==0)"✓ 姿势参考"else"姿势参考",onClick={tab=0},modifier=Modifier.fillMaxWidth())
                    ZOutlineButton(if(tab==1)"✓ 摄影话术"else"摄影话术",onClick={tab=1},modifier=Modifier.fillMaxWidth())
                    if(tab==0){
                        Text("参考与收藏只在手机本地，不读取相机、不调用AI。自己的图片不会上传。",fontSize=10.sp,color=ZColors.TextSecondary)
                        ZOutlineButton("添加自己的参考图",onClick={picker.launch("image/*")},modifier=Modifier.fillMaxWidth())
                        if(!state.installed)ZOutlineButton(if(state.downloading)"下载中 ${state.done}/4155"else"下载姿势图库",onClick={consent=true},enabled=state.ready && !state.downloading,modifier=Modifier.fillMaxWidth())
                        if(state.downloading)ZOutlineButton("取消下载",onClick={repo.cancelDownload()},modifier=Modifier.fillMaxWidth())
                        listOf("全部","图库","我的").forEach{label->ZOutlineButton(if(source==label)"✓ $label"else label,onClick={source=label;selected=""},modifier=Modifier.fillMaxWidth())}
                        ZOutlineButton(if(favorites)"✓ 只看收藏"else"只看收藏",onClick={favorites=!favorites;selected=""},modifier=Modifier.fillMaxWidth())
                        CollapsibleSectionHeader("姿势筛选（12类）",filtersOpen,{filtersOpen=!filtersOpen})
                        if(filtersOpen){
                            fields.forEachIndexed{i,key->var menu by remember{mutableStateOf(false)}
                                val values=state.definitions[key].orEmpty();Box(Modifier.fillMaxWidth()){
                                    ZOutlineButton("${labels[i]}：${filters[i]?.toString()?.let{values[it]}?:"全部"}",onClick={menu=true},modifier=Modifier.fillMaxWidth())
                                    DropdownMenu(menu,{menu=false}){DropdownMenuItem(text={Text("全部")},onClick={filters=filters-i;menu=false;selected=""})
                                        values.forEach{(code,name)->DropdownMenuItem(text={Text(name)},onClick={filters=filters+(i to code.first());menu=false;selected=""})}}
                                }
                            }
                            ZOutlineButton("清空姿势筛选",onClick={filters=emptyMap();selected=""},modifier=Modifier.fillMaxWidth())
                        }
                        if(current!=null){
                            ZOutlineButton("上一张",onClick={selected=repo.key(items[(index-1+items.size)%items.size])},modifier=Modifier.fillMaxWidth())
                            ZOutlineButton("随机姿势",onClick={selected=repo.key(items.filter{repo.key(it)!=repo.key(current)}.randomOrNull()?:current)},modifier=Modifier.fillMaxWidth())
                            ZOutlineButton("下一张",onClick={selected=repo.key(items[(index+1)%items.size])},modifier=Modifier.fillMaxWidth())
                            ZOutlineButton(if(repo.key(current) in state.favorites)"取消姿势收藏"else "收藏姿势",onClick={repo.favorite(current)},modifier=Modifier.fillMaxWidth())
                        }
                        if(state.message.isNotBlank())Text(state.message,fontSize=11.sp,color=ZColors.NikonYellow)
                        Text("Poze ©2025 · MIT · 固定版本827ce3e。图库仅在你同意后下载，参考人物照片不表示可商用或获得肖像授权。",fontSize=9.sp,color=ZColors.TextTertiary)
                    }else{
                        Text("先征求被摄者意愿；动作小幅、站稳、不疼。话术为本地原创整理，不是AI实时生成，也不是小程序数据库。",fontSize=11.sp,color=ZColors.TextSecondary)
                        PoseWords.groups.keys.forEach{k->ZOutlineButton(if(group==k)"✓ $k"else k,onClick={selectPhraseGroup(k)},modifier=Modifier.fillMaxWidth())}
                        ZOutlineButton(if(group=="话术收藏")"✓ 话术收藏"else"话术收藏",onClick={selectPhraseGroup("话术收藏")},modifier=Modifier.fillMaxWidth())
                        ZOutlineButton(if(group=="我的话术")"✓ 我的自定义话术"else"我的自定义话术",onClick={selectPhraseGroup("我的话术")},modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(value=customDraft,onValueChange={customDraft=it.take(180)},label={Text("写一句自己的引导话术")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        ZOutlineButton("保存到我的话术",onClick={addCustomPhrase()},enabled=customDraft.isNotBlank(),modifier=Modifier.fillMaxWidth())
                        ZOutlineButton("换一句",onClick={val choices=phraseChoices(group);line=if(choices.isEmpty())emptyPhrase(group)else PoseWords.next(choices,line)},modifier=Modifier.fillMaxWidth())
                        ZOutlineButton(if(line in savedWords)"取消话术收藏"else "收藏这句话",onClick={savedWords=if(line in savedWords)savedWords-line else savedWords+line;localPrefs.edit().putStringSet("favorites",savedWords).apply()},enabled=line!=emptyPhrase(group),modifier=Modifier.fillMaxWidth())
                        if(group=="我的话术" && line in customWords)ZOutlineButton("删除这句自定义话术",onClick={deleteCurrentCustomPhrase()},modifier=Modifier.fillMaxWidth())
                        ZOutlineButton("复制话术",onClick={(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("摄影话术",line))},modifier=Modifier.fillMaxWidth())
                    }
                }
                Column(Modifier.weight(.58f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    if(tab==0){
                        if(current!=null){
                            Text("${index+1}/${items.size} · ${if(current.own)"自己的参考图（不按上游标签筛选）"else "Poze离线参考"}",fontSize=10.sp,color=ZColors.TextSecondary)
                            bitmap?.let{Image(it.asImageBitmap(),"拍照姿势参考图",Modifier.fillMaxWidth().weight(1f),contentScale=ContentScale.Fit)}
                                ?:Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center){Text("参考图片暂不可用",color=ZColors.TextSecondary)}
                            if(!current.own)Text(current.code.mapIndexedNotNull{i,c->state.definitions[fields[i]]?.get(c.toString())}.joinToString(" · "),fontSize=10.sp,color=ZColors.TextSecondary,maxLines=3)
                        }else Box(Modifier.fillMaxSize().background(ZColors.Surface),contentAlignment=Alignment.Center){Text(if(state.installed || state.own.isNotEmpty())"当前筛选没有参考图，可清空筛选或添加自己的图片。"else "图库未下载。可先添加自己的参考图，话术无需下载即可使用。",color=ZColors.TextSecondary)}
                    }else{
                        Text(line,color=ZColors.TextPrimary,fontSize=22.sp,modifier=Modifier.fillMaxWidth().weight(1f).background(ZColors.Surface).padding(16.dp))
                        Text("一次只给一个小任务，动作到位后再引导下一步；反馈具体、不评价身材、不强迫微笑或接触。",fontSize=11.sp,color=ZColors.TextSecondary)
                    }
                }
            }
        }else{
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){ZOutlineButton("姿势参考",onClick={tab=0});ZOutlineButton("摄影话术",onClick={tab=1})}
            Column(if(embedded)Modifier.fillMaxWidth()else Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                if(tab==0){
                    Text("参考与收藏只在手机本地，不读取相机、不调用AI。自己的图片不会上传。",fontSize=10.sp,color=ZColors.TextSecondary)
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        ZOutlineButton("添加自己的参考图",onClick={picker.launch("image/*")})
                        if(!state.installed)ZOutlineButton(if(state.downloading)"下载中 ${state.done}/4155"else "下载姿势图库",onClick={consent=true},enabled=state.ready && !state.downloading)
                        if(state.downloading)ZOutlineButton("取消下载",onClick={repo.cancelDownload()})
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        listOf("全部","图库","我的").forEach{label->ZOutlineButton(if(source==label)"✓ $label"else label,onClick={source=label;selected=""})}
                        ZOutlineButton(if(favorites)"✓ 只看收藏"else "只看收藏",onClick={favorites=!favorites;selected=""})
                    }
                    CollapsibleSectionHeader("姿势筛选（12类）",filtersOpen,{filtersOpen=!filtersOpen})
                    if(filtersOpen){fields.forEachIndexed{i,key->var menu by remember{mutableStateOf(false)}
                        val values=state.definitions[key].orEmpty();Box{
                            ZOutlineButton("${labels[i]}：${filters[i]?.toString()?.let{values[it]}?:"全部"}",onClick={menu=true})
                            DropdownMenu(menu,{menu=false}){DropdownMenuItem(text={Text("全部")},onClick={filters=filters-i;menu=false;selected=""})
                                values.forEach{(code,name)->DropdownMenuItem(text={Text(name)},onClick={filters=filters+(i to code.first());menu=false;selected=""})}}
                        }}
                        ZOutlineButton("清空姿势筛选",onClick={filters=emptyMap();selected=""})}
                    if(current!=null){
                        Text("${index+1}/${items.size} · ${if(current.own)"自己的参考图（不按上游标签筛选）"else "Poze离线参考"}",fontSize=10.sp,color=ZColors.TextSecondary)
                        bitmap?.let{Image(it.asImageBitmap(),"拍照姿势参考图",Modifier.fillMaxWidth().height(290.dp),contentScale=ContentScale.Fit)}?:Text("参考图片暂不可用",color=ZColors.TextSecondary)
                        if(!current.own)Text(current.code.mapIndexedNotNull{i,c->state.definitions[fields[i]]?.get(c.toString())}.joinToString(" · "),fontSize=10.sp,color=ZColors.TextSecondary)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                            ZOutlineButton("上一张",onClick={selected=repo.key(items[(index-1+items.size)%items.size])})
                            ZOutlineButton("随机姿势",onClick={selected=repo.key(items.filter{repo.key(it)!=repo.key(current)}.randomOrNull()?:current)})
                            ZOutlineButton("下一张",onClick={selected=repo.key(items[(index+1)%items.size])})
                            ZOutlineButton(if(repo.key(current) in state.favorites)"取消姿势收藏"else "收藏姿势",onClick={repo.favorite(current)})}
                    }else Text(if(state.installed || state.own.isNotEmpty())"当前筛选没有参考图，可清空筛选或添加自己的图片。"else "图库未下载。可先添加自己的参考图，话术无需下载即可使用。",color=ZColors.TextSecondary)
                    if(state.message.isNotBlank())Text(state.message,fontSize=11.sp,color=ZColors.NikonYellow)
                    Text("Poze ©2025 · MIT · 固定版本827ce3e。图库仅在你同意后下载，参考人物照片不表示可商用或获得肖像授权。",fontSize=9.sp,color=ZColors.TextTertiary)
                }else{
                    Text("先征求被摄者意愿；动作小幅、站稳、不疼。话术为本地原创整理，不是AI实时生成，也不是小程序数据库。",fontSize=11.sp,color=ZColors.TextSecondary)
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        PoseWords.groups.keys.forEach{k->ZOutlineButton(if(group==k)"✓ $k"else k,onClick={selectPhraseGroup(k)})}
                        ZOutlineButton(if(group=="话术收藏")"✓ 话术收藏"else"话术收藏",onClick={selectPhraseGroup("话术收藏")})
                        ZOutlineButton(if(group=="我的话术")"✓ 我的自定义话术"else"我的自定义话术",onClick={selectPhraseGroup("我的话术")})}
                    OutlinedTextField(value=customDraft,onValueChange={customDraft=it.take(180)},label={Text("写一句自己的引导话术")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    ZOutlineButton("保存到我的话术",onClick={addCustomPhrase()},enabled=customDraft.isNotBlank(),modifier=Modifier.fillMaxWidth())
                    Text(line,color=ZColors.TextPrimary,fontSize=22.sp,modifier=Modifier.fillMaxWidth().background(ZColors.Surface).padding(16.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        ZOutlineButton("换一句",onClick={val choices=phraseChoices(group);line=if(choices.isEmpty())emptyPhrase(group)else PoseWords.next(choices,line)})
                        ZOutlineButton(if(line in savedWords)"取消话术收藏"else "收藏这句话",onClick={savedWords=if(line in savedWords)savedWords-line else savedWords+line;localPrefs.edit().putStringSet("favorites",savedWords).apply()},enabled=line!=emptyPhrase(group))
                        ZOutlineButton("复制话术",onClick={(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("摄影话术",line))})}
                    if(group=="我的话术" && line in customWords)ZOutlineButton("删除这句自定义话术",onClick={deleteCurrentCustomPhrase()})
                    Text("一次只给一个小任务，动作到位后再引导下一步；反馈具体、不评价身材、不强迫微笑或接触。",fontSize=11.sp,color=ZColors.TextSecondary)
                }
            }
        }
    }
    if(consent && !state.installed)AlertDialog(onDismissRequest={consent=false;repo.acknowledgeDownloadPrompt()},title={Text("是否下载姿势图库？")},
        text={Text("4155张参考图约107MiB，通过GitHub下载固定版本归档（另含少量项目文件），建议Wi-Fi并预留140MiB。下载后可离线筛选/随机/收藏。\n\n不下载也能导入自己的图片、使用话术。没有自动下载或自动重试；关闭参考窗口后已同意的下载会继续，可重新进入取消。")},
        confirmButton={TextButton(onClick={consent=false;repo.acknowledgeDownloadPrompt();repo.downloadAfterConsent()},enabled=state.ready && !state.downloading){Text("同意并下载图库")}},
        dismissButton={TextButton(onClick={consent=false;repo.acknowledgeDownloadPrompt()}){Text("暂不下载，用自己的图")}})
}
