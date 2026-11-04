package com.zbox.ai.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zbox.ai.core.PhoneToolsPolicy as Policy
import com.zbox.ai.ptpip.NikonConnectionPolicy
import kotlinx.coroutines.*
import kotlin.math.*

@Composable private fun FoldPhone(title:String,content:@Composable ()->Unit){var open by remember{mutableStateOf(false)}
    CollapsibleSectionHeader(title,open,{open=!open});if(open)Column(Modifier.fillMaxWidth().background(ZColors.Surface).padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){content()}}
@Composable private fun PhoneToggle(label:String,value:Boolean,change:(Boolean)->Unit){Row(Modifier.fillMaxWidth().toggleable(value=value,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=change),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),fontSize=11.sp,color=ZColors.TextPrimary);Switch(value,null)}}

@Composable internal fun PhoneConnectionDiagnostics(ui:UiState,vm:MainViewModel){val ctx=LocalContext.current
    FoldPhone("连接诊断与机型能力（手机本地）"){
        Text(vm.phone.report(),fontSize=10.sp,color=ZColors.TextSecondary)
        ZOutlineButton("复制脱敏报告",onClick={(ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("连接诊断",vm.phone.report()))})
        if(ui.liveViewFrames>0 && ui.liveViewActive)Text("本次取景已成功取帧；不代表其它机身功能已验证",fontSize=10.sp,color=ZColors.NikonYellow)
        if(ui.thumbnails.isNotEmpty())Text("本次已获得缩略图预览",fontSize=10.sp,color=ZColors.NikonYellow)
        Text("不会主动重连/探测。下列为通告或门禁，不等于本轮实机验证。",fontSize=10.sp,color=ZColors.TextTertiary)
        val ops=ui.deviceInfo.supportedOperations
        listOf("设备信息" to 0x1001,"照片缩略图" to 0x100A,"标准分块下载" to 0x101B,"取景" to 0x9201,"录像" to 0x920A).forEach{(name,op)->Text("$name：${if(op in ops)"相机已通告／待实测" else "未通告，不能据此认定支持或不支持"}",fontSize=10.sp,color=ZColors.TextSecondary)}
        Text("Z30旧预设写入：${if(ui.isConnected && NikonConnectionPolicy.permitsLegacyPictureControlWrite(ui.deviceInfo.model))"Z30门禁允许；仍需原写入确认"else"安全禁用"}",fontSize=10.sp,color=ZColors.TextSecondary)
    }

}

@Composable internal fun PhoneAssistSettings(vm:MainViewModel){val p by vm.phone.state.collectAsState()
    FoldPhone("取景辅助（手机处理）"){
        PhoneToggle("九宫格参考线",p.options.grid){vm.phone.options(p.options.copy(grid=it))}
        PhoneToggle("高光斑马纹（预览亮度≥245）",p.options.zebra){vm.phone.options(p.options.copy(zebra=it))}
        PhoneToggle("峰值边缘提示（非机身合焦确认）",p.options.peaking){vm.phone.options(p.options.copy(peaking=it))}
        PhoneToggle("取景直方图（亮度）",p.options.histogram){vm.phone.options(p.options.copy(histogram=it))}
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf("无框","16:9","1:1","4:5").forEachIndexed{i,label->ZOutlineButton(label,onClick={vm.phone.options(p.options.copy(frame=i))})}}
        Text("四项辅助首次默认开启，之后记住你的选择。画幅框不裁原片；处理最高每秒4次、最长边320；直方图基于回传预览，非RAW曝光。",fontSize=10.sp,color=ZColors.TextTertiary)
    }
}

@Composable internal fun PhoneAssistOverlay(frame:Bitmap?,work:PhoneWorkbench,compactHistogram:Boolean=false){val p by work.state.collectAsState();val options=p.options
    var histogram by remember{mutableStateOf<Policy.Analysis?>(null)}
    var overlay by remember{mutableStateOf<Bitmap?>(null)};val latest by rememberUpdatedState(frame)
    LaunchedEffect(options.zebra,options.peaking,options.histogram){overlay=null;histogram=null;if(options.zebra || options.peaking || options.histogram){var old:Bitmap?=null
        while(isActive){val f=latest;if(f==null){overlay=null;histogram=null;old=null};if(f!=null && f!==old){old=f;val result=withContext(Dispatchers.Default){analyzePhoneFrame(f,options.zebra,options.peaking)};overlay=result?.second;histogram=result?.first};delay(250)}}}
    if(frame!=null && (options.zebra || options.peaking))overlay?.let{Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
    if(options.frame!=0 && frame!=null)Canvas(Modifier.fillMaxSize()){
        val fit=min(size.width/frame.width,size.height/frame.height);val w=frame.width*fit;val h=frame.height*fit
        val ratio=when(options.frame){1->16f/9;2->1f;else->4f/5};val rw=min(w,h*ratio);val rh=rw/ratio
        drawRect(Color.White.copy(alpha=.85f),Offset((size.width-rw)/2,(size.height-rh)/2),Size(rw,rh),style=androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))}
    if(options.grid && frame!=null)Canvas(Modifier.fillMaxSize()){
        val fit=min(size.width/frame.width,size.height/frame.height);val w=frame.width*fit;val h=frame.height*fit
        val left=(size.width-w)/2;val top=(size.height-h)/2
        for(i in 1..2){val x=left+w*i/3;val y=top+h*i/3
            drawLine(Color.Black.copy(alpha=.45f),Offset(x,top),Offset(x,top+h),3.dp.toPx());drawLine(Color.White.copy(alpha=.7f),Offset(x,top),Offset(x,top+h),1.dp.toPx())
            drawLine(Color.Black.copy(alpha=.45f),Offset(left,y),Offset(left+w,y),3.dp.toPx());drawLine(Color.White.copy(alpha=.7f),Offset(left,y),Offset(left+w,y),1.dp.toPx())}
    }
    if(options.histogram && frame!=null)histogram?.let{h->BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomStart){
        val side=if(compactHistogram)maxOf(0.dp,(maxWidth-maxHeight*(frame.width.toFloat()/frame.height))/2)else 0.dp
        val bottom=if(compactHistogram)maxOf(0.dp,(maxHeight-maxWidth/(frame.width.toFloat()/frame.height))/2)else 0.dp
        PhoneHistogram(h,"取景亮度分布",Modifier.padding(start=side,bottom=bottom).width(if(compactHistogram)224.dp else 168.dp).padding(8.dp),compact=compactHistogram)}}
}
@Composable private fun PhoneHistogram(h:Policy.Analysis,title:String,modifier:Modifier=Modifier,compact:Boolean=false){
    if(compact){Canvas(modifier.background(Color.Black.copy(alpha=.32f)).padding(6.dp).fillMaxWidth().height(64.dp).semantics{contentDescription="亮度直方图"}){
        val top=h.histogram.maxOrNull()?.coerceAtLeast(1)?:1
        for(i in 0..255){val x=i*size.width/256;drawLine(Color.White.copy(alpha=.85f),Offset(x,size.height),Offset(x,size.height-h.histogram[i].toFloat()/top*size.height),size.width/256)}
    };return}
    Column(modifier.background(Color.Black.copy(alpha=.78f)).padding(4.dp)){
        Text(title,color=Color.White,fontSize=10.sp)
        Canvas(Modifier.fillMaxWidth().height(48.dp)){val top=h.histogram.maxOrNull()?.coerceAtLeast(1)?:1
            for(i in 0..255){val x=i*size.width/256;drawLine(Color.LightGray,Offset(x,size.height),Offset(x,size.height-h.histogram[i].toFloat()/top*size.height),size.width/256)}}
        Text("暗 0                 亮 255",color=Color.LightGray,fontSize=9.sp)
        Text("高亮 %.1f%% · 当前画面，非RAW".format(h.clipped*100.0/h.pixels),color=Color.LightGray,fontSize=9.sp)
    }
}
internal fun analyzePhoneFrame(frame:Bitmap,zebra:Boolean=false,peaking:Boolean=false):Pair<Policy.Analysis,Bitmap>?=runCatching{
    val scale=minOf(1f,320f/maxOf(frame.width,frame.height));val small=Bitmap.createScaledBitmap(frame,maxOf(1,(frame.width*scale).toInt()),maxOf(1,(frame.height*scale).toInt()),true)
    val pixels=IntArray(small.width*small.height);small.getPixels(pixels,0,small.width,0,0,small.width,small.height)
    val a=Policy.analyze(pixels,small.width,small.height,zebra,peaking);val out=Bitmap.createBitmap(a.overlay,small.width,small.height,Bitmap.Config.ARGB_8888)
    if(small!==frame)small.recycle();a to out
}.getOrNull()

@Composable internal fun PhoneInspection(ui:UiState,work:PhoneWorkbench){val p by work.state.collectAsState();var inspect by remember{mutableStateOf(false)}
    if(ui.lastCapture!=null){ZOutlineButton("检查这张（手机放大／直方图）",onClick={inspect=true});if(inspect)InspectDialog(ui.lastCapture,"回传JPEG／内嵌预览，非原片100%像素验证"){inspect=false}}
}
@Composable private fun InspectDialog(bitmap:Bitmap,label:String,close:()->Unit){var hist by remember{mutableStateOf<Policy.Analysis?>(null)};var zebra by remember{mutableStateOf(false)};var showHistogram by remember{mutableStateOf(false)};var analysisImage by remember{mutableStateOf<Bitmap?>(null)}
    LaunchedEffect(bitmap,zebra,showHistogram){hist=null;analysisImage=null;if(zebra || showHistogram){val a=withContext(Dispatchers.Default){analyzePhoneFrame(bitmap,zebra,false)};hist=a?.first;analysisImage=a?.second}}
    var zoom by remember{mutableFloatStateOf(1f)};var pan by remember{mutableStateOf(Offset.Zero)}
    Dialog(close,properties=DialogProperties(usePlatformDefaultWidth=false)){Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).background(Color(0xff141414)).padding(12.dp)){
        Text(label,color=Color.White,fontSize=11.sp);ZoomPhoneImage(bitmap,zoom,pan,{z,o->zoom=z;pan=o},Modifier.weight(1f),analysisImage.takeIf{zebra})
        Text("双击2倍／还原，双指缩放1–5倍；缩略图放大不代表原片细节",color=Color.LightGray,fontSize=10.sp)
        if(showHistogram)hist?.let{PhoneHistogram(it,"检查亮度分布（当前图像/内嵌预览）",Modifier.fillMaxWidth())}
        PhoneToggle("显示直方图",showHistogram){showHistogram=it}
        PhoneToggle("高光提示",zebra){zebra=it};TextButton(onClick=close){Text("返回取景／关闭")}
    }}
}
@Composable private fun ZoomPhoneImage(bitmap:Bitmap,zoom:Float,pan:Offset,change:(Float,Offset)->Unit,modifier:Modifier=Modifier,overlay:Bitmap?=null){
    BoxWithConstraints(modifier.fillMaxWidth().background(Color.Black).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))){val density=androidx.compose.ui.platform.LocalDensity.current;val w=with(density){maxWidth.toPx()};val h=with(density){maxHeight.toPx()};val liveZoom by rememberUpdatedState(zoom);val livePan by rememberUpdatedState(pan)
        val gestures=Modifier.fillMaxSize().pointerInput(bitmap){detectTransformGestures{_,move,z,_->val nz=(liveZoom*z).coerceIn(1f,5f);val limit=(nz-1)/2
            change(nz,Offset((livePan.x+move.x/w).coerceIn(-limit,limit),(livePan.y+move.y/h).coerceIn(-limit,limit)))}}
            .pointerInput(bitmap){detectTapGestures(onDoubleTap={change(if(liveZoom>1f)1f else 2f,Offset.Zero)})}
        Box(gestures){val transform=Modifier.fillMaxSize().graphicsLayer(scaleX=zoom,scaleY=zoom,translationX=pan.x*w,translationY=pan.y*h)
            Image(bitmap.asImageBitmap(),"手机检查预览",transform,contentScale=ContentScale.Fit);overlay?.let{Image(it.asImageBitmap(),null,transform,contentScale=ContentScale.Fit)}}
    }
}

@Composable internal fun PhoneGalleryBar(vm:MainViewModel,onCompare:()->Unit,onQueue:()->Unit){val p by vm.phone.state.collectAsState()
    CompactGalleryAction("对比已选${p.compare.size}/2",p.compare.size==2){onCompare();vm.phone.loadComparePreview()}
    CompactGalleryAction("下载队列",true,onQueue)
}
@Composable private fun CompactGalleryAction(label:String,enabled:Boolean,onClick:()->Unit){Box(Modifier
    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(ZColors.SurfaceHigh)
    .clickable(enabled=enabled,role=androidx.compose.ui.semantics.Role.Button,onClick=onClick).padding(horizontal=6.dp,vertical=8.dp)){
    Text(label,fontSize=11.sp,color=if(enabled)ZColors.TextPrimary else ZColors.TextTertiary)}}
@Composable internal fun PhoneGalleryTools(ui:UiState,vm:MainViewModel,compareOpen:Boolean,onCloseCompare:()->Unit,queueOpen:Boolean,onCloseQueue:()->Unit){val p by vm.phone.state.collectAsState()
    if(queueOpen)AlertDialog(onDismissRequest=onCloseQueue,title={Text("下载队列")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button(onClick={vm.phone.startQueue()},enabled=ui.isConnected && !vm.phone.cameraBusy && p.tasks.any{it.status=="待下载"},modifier=Modifier.weight(1f).heightIn(min=44.dp),contentPadding=PaddingValues(8.dp)){Text("开始",fontSize=12.sp,maxLines=1)}
            OutlinedButton(onClick={vm.phone.pause()},modifier=Modifier.weight(1f).heightIn(min=44.dp),contentPadding=PaddingValues(8.dp)){Text("暂停全部",fontSize=12.sp,maxLines=1)}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(onClick={vm.phone.clearFinished()},enabled=!vm.phone.cameraBusy,modifier=Modifier.weight(1f).heightIn(min=44.dp),contentPadding=PaddingValues(6.dp)){Text("清理已结束",fontSize=12.sp,maxLines=1)}
            OutlinedButton(onClick={vm.phone.clearTemporaryFiles()},enabled=!vm.phone.cameraBusy,modifier=Modifier.weight(1f).heightIn(min=44.dp),contentPadding=PaddingValues(6.dp)){Text("清理临时块",fontSize=12.sp,maxLines=1)}
        }
        if(p.tasks.isEmpty())Box(Modifier.fillMaxWidth().height(80.dp),contentAlignment=Alignment.Center){Text("暂无下载任务",fontSize=12.sp,color=ZColors.TextTertiary)}
        p.tasks.forEach{t->Column(Modifier.fillMaxWidth().background(ZColors.SurfaceHigh,androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Text(t.item.filename,fontSize=12.sp,color=ZColors.TextPrimary,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("${t.status} · ${t.bytes/1024}/${t.total/1024}KiB",fontSize=10.sp,color=ZColors.TextSecondary)
            if(t.message.isNotBlank() && t.status in listOf("失败","已暂停","已取消"))Text(t.message,fontSize=10.sp,color=ZColors.TextSecondary)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){if(t.status in listOf("失败","已暂停","已取消"))ZOutlineButton("重试此项",onClick={vm.phone.retry(t.id)},enabled=!vm.phone.cameraBusy)
                if(t.status in listOf("待下载","下载中","保存中"))ZOutlineButton("取消此项",onClick={vm.phone.cancel(t.id)})}
        }}
    }},confirmButton={TextButton(onClick=onCloseQueue){Text("关闭队列")}})
    if(p.message.isNotBlank())Text(p.message,fontSize=10.sp,color=ZColors.TextSecondary)
    if(compareOpen && p.compare.size==2){
        val images by produceState<List<Bitmap?>>(listOf(null,null),p.compare,p.compareImages,ui.thumbnails){value=withContext(Dispatchers.Default){p.compare.map{item->p.compareImages[item.handle]?:ui.thumbnails[item.handle]?.let{data->
            val b=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(data,0,data.size,b);var sample=1;while(b.outWidth/sample>800 || b.outHeight/sample>800)sample*=2
            BitmapFactory.decodeByteArray(data,0,data.size,BitmapFactory.Options().apply{inSampleSize=sample})}}}}
        var zoom by remember{mutableFloatStateOf(1f)};var pan by remember{mutableStateOf(Offset.Zero)}
        Dialog({onCloseCompare();vm.phone.cancelCompareRead()},properties=DialogProperties(usePlatformDefaultWidth=false)){Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).background(Color(0xff151515)).padding(10.dp)){
            Text(if(p.compareImages.size==2)"双图同步缩放／位移 · 高清解码预览"else"双图同步缩放／位移 · 当前缓存缩略图，不是原片像素",fontSize=11.sp,color=Color.White)
            Text(p.compareStatus.ifBlank{"当前只有缩略图；连接相机后可读取高清。放大缩略图不会增加细节。"},fontSize=10.sp,color=Color.Yellow)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                TextButton(onClick={vm.phone.loadComparePreview()},enabled=!p.compareLoading){Text("读取高清2048")}
                TextButton(onClick={vm.phone.loadComparePreview(4096)},enabled=!p.compareLoading){Text("读取细节4096（较慢）")}
                if(p.compareLoading)TextButton(onClick={vm.phone.cancelCompareRead()}){Text("取消读取")}}

            p.compare.forEachIndexed{i,item->Text(item.filename,color=Color.White,fontSize=10.sp)
                val bitmap=images.getOrNull(i);if(bitmap!=null)Text("${bitmap.width}×${bitmap.height}${if(item.isRaw)" · RAW内嵌JPEG"else" · JPEG解码预览"}",color=Color.LightGray,fontSize=9.sp)
                if(bitmap!=null)ZoomPhoneImage(bitmap,zoom,pan,{z,o->zoom=z;pan=o},Modifier.weight(1f))else Box(Modifier.weight(1f)){Text("未缓存预览，请先浏览缩略图；不会自动读取相机",color=Color.Gray)}}
            TextButton(onClick={vm.phone.clearCompare();onCloseCompare()}){Text("清空对比")};TextButton(onClick={zoom=1f;pan=Offset.Zero}){Text("还原")};TextButton(onClick={onCloseCompare();vm.phone.cancelCompareRead()}){Text("关闭对比")}
        }}
    }
}
@Composable internal fun PhonePhotoActions(item:com.zbox.ai.core.CameraStorage.Item,vm:MainViewModel){val p by vm.phone.state.collectAsState()
    Text("手机标记（不改机身文件）",fontSize=10.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
        ZOutlineButton(if(vm.phone.mark(item)==1)"取消收藏"else"收藏",onClick={vm.phone.toggleMark(item,1)})
        ZOutlineButton(if(vm.phone.mark(item)==2)"取消待选"else"待选",onClick={vm.phone.toggleMark(item,2)})
        ZOutlineButton(if(p.compare.any{it.handle==item.handle})"取消对比"else"加入对比",onClick={vm.phone.compare(item)},enabled=!item.isVideo)}
}
