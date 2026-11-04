package com.zbox.ai.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.produceState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zbox.ai.core.CameraStorage

/**
 * Tab「相册」：机内照片管理。
 *
 * 只要缩略图能出来，用户就能在手机上挑片，不必掏 SD 卡；
 * 下载走 MediaStore，不申请存储权限也能落到系统相册。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun GalleryScreen(ui: UiState, vm: MainViewModel) {
    val phone by vm.phone.state.collectAsState()
    val items = remember(ui.galleryItems, ui.galleryFilter,phone.marks,ui.deviceInfo) {
        val browsable=CameraStorage.jpegBrowseItems(ui.galleryItems)
        when (ui.galleryFilter) {
            1 -> browsable.filter { !it.isVideo }
            2 -> browsable.filter { it.isVideo }
            4 -> browsable.filter { vm.phone.mark(it)==1 }
            5 -> browsable.filter { vm.phone.mark(it)==2 }
            else -> browsable
        }
    }
    var selected by remember { mutableStateOf<CameraStorage.Item?>(null) }
    var selectionMode by remember{mutableStateOf(false)}
    var bulkHandles by remember{mutableStateOf<Set<Long>>(emptySet())}
    var batchDialog by remember{mutableStateOf(false)}
    var compareOpen by remember{mutableStateOf(false)}
    var queueOpen by remember{mutableStateOf(false)}
    val bulkItems=items.filter{it.handle in bulkHandles}
    LaunchedEffect(ui.isConnected,ui.deviceInfo.serial){selectionMode=false;bulkHandles=emptySet();batchDialog=false}
    LaunchedEffect(items.map{it.handle}){bulkHandles=bulkHandles.intersect(items.map{it.handle}.toSet())}
    fun toggleSelection(item:CameraStorage.Item){bulkHandles=if(item.handle in bulkHandles)bulkHandles-item.handle else if(bulkHandles.size<24)bulkHandles+item.handle else bulkHandles}
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, items, ui.galleryFilter) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged().collect { visible ->
                val first = ((visible.minOrNull() ?: 0) - 6).coerceAtLeast(0)
                val end = ((visible.maxOrNull() ?: 0) + 7).coerceAtMost(items.size)
                vm.requestGalleryThumbnails(if (first < end) items.subList(first, end).map { it.handle } else emptyList())
                if (visible.isNotEmpty() && (visible.maxOrNull() ?: 0) >= items.size - 6) vm.loadMoreGallery()
            }
    }
    LaunchedEffect(ui.galleryFilter, ui.galleryItems.size, ui.galleryHasMore, ui.galleryLoading, items.isEmpty()) {
        if (ui.galleryFilter in 1..2 && items.isEmpty() && ui.galleryHasMore && !ui.galleryLoading) {
            vm.loadMoreGallery()
        }
    }
    LaunchedEffect(ui.galleryFilter) { gridState.scrollToItem(0) }

    Column(modifier = Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}.padding(horizontal = 12.dp, vertical = 8.dp)) {
        SectionTitle("机身相册") {
            Chip(
                text = if (ui.isConnected) "${items.size} 项" else "未连接",
                color = if (ui.isConnected) ZColors.Success else ZColors.TextTertiary,
            )
        }

        // 工具条
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZPrimaryButton(
                text = if (ui.galleryLoading) "读取中 ${ui.galleryProgress}" else "刷新相册",
                onClick = { vm.refreshGallery() },
                modifier = Modifier.weight(1f),
                enabled = ui.isConnected && !ui.galleryLoading,
                loading = ui.galleryLoading,
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf(0 to "全部",1 to "照片",2 to "视频",4 to "收藏",5 to "待选").forEach { (f, label) ->
                val sel = ui.galleryFilter == f
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (sel) ZColors.NikonYellow else ZColors.SurfaceHigh)
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                        .pointerInputTap { selectionMode=false;bulkHandles=emptySet();vm.setGalleryFilter(if(ui.galleryFilter==f)1 else f) },
                ) {
                    Text(
                        label,
                        color = if (sel) Color(0xFF121212) else ZColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            PhoneGalleryBar(vm,{compareOpen=true},{queueOpen=true})
        }

        if(selectionMode){
            Text("已选${bulkItems.size}项 · 最多24项",fontSize=11.sp,color=ZColors.NikonYellow)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                ZOutlineButton("选已加载（最多24）",onClick={bulkHandles=items.take(24).map{it.handle}.toSet()})
                ZOutlineButton("清空选择",onClick={bulkHandles=emptySet()})
                ZOutlineButton("退出多选",onClick={selectionMode=false;bulkHandles=emptySet()})}
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                ZOutlineButton("批量下载",onClick={batchDialog=true},enabled=bulkItems.isNotEmpty() && ui.isConnected)
                ZOutlineButton("批量收藏",onClick={vm.phone.markBatch(bulkItems,1)},enabled=bulkItems.isNotEmpty())
                ZOutlineButton("批量待选",onClick={vm.phone.markBatch(bulkItems,2)},enabled=bulkItems.isNotEmpty())}
        }
        PhoneGalleryTools(ui,vm,compareOpen,{compareOpen=false},queueOpen,{queueOpen=false})
        if(items.isEmpty() && ui.galleryHasMore && ui.galleryFilter !in 1..2)ZOutlineButton("当前筛选无结果，继续加载相册",onClick={vm.loadMoreGallery()},enabled=!ui.galleryLoading)
        else if(items.isEmpty() && ui.galleryHasMore && ui.galleryFilter in 1..2)Text("正在继续读取文件名并筛选…",fontSize=10.sp,color=ZColors.TextSecondary)
        if (ui.downloadMsg.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                ui.downloadMsg,
                color = ZColors.TextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "最新在前 · 长按多选（最多24项） · 每批 ${ui.galleryLimit} 张",
            color = ZColors.TextTertiary,
            fontSize = 9.sp,
            lineHeight = 13.sp,
        )
        Spacer(Modifier.height(8.dp))

        if (!ui.isConnected) {
            EmptyHint("先连接相机，再刷新机身列表")
        } else if (items.isEmpty() && !ui.galleryLoading) {
            EmptyHint("机身里还没有可读取的文件\n（或点上面「刷新机身列表」）")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items, key = { it.handle }) { item ->
                    Box {
                        GalleryCell(item=item,thumb=ui.thumbnails[item.handle],isSelected=item.handle in bulkHandles,
                            onClick={if(selectionMode)toggleSelection(item)else selected=item},
                            onLongClick={selectionMode=true;toggleSelection(item)})
                        if(item.handle in bulkHandles)Text("✓",Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha=.8f)).padding(4.dp),color=ZColors.NikonYellow)
                        val mark=vm.phone.mark(item)
                        if(mark!=0)Text(if(mark==1)"★ 收藏"else"待选",Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha=.7f)).padding(3.dp),fontSize=9.sp,color=ZColors.NikonYellow)
                    }
                }
                if (ui.galleryHasMore) item(span = { GridItemSpan(maxLineSpan) }) {
                    ZPrimaryButton(text = if (ui.galleryLoading) "读取中…" else "加载更早的照片",
                        onClick = { vm.loadMoreGallery() }, enabled = !ui.galleryLoading,
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    if(batchDialog) androidx.compose.material3.AlertDialog(onDismissRequest={batchDialog=false},title={Text("批量加入下载队列")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text("只加入实际存在的所选格式，缺少该格式的条目跳过；视频保留原文件。队列总上限24项，容量不足则本批不加入。",fontSize=11.sp)
        if(bulkItems.any{!it.filenameVerified})Text("部分STA名称未核实，将用相册显示的生成名称保存；选择格式即确认。",fontSize=11.sp,color=ZColors.NikonYellow)
        val jpeg=vm.phone.batchEligible(bulkItems,false).size;val raw=vm.phone.batchEligible(bulkItems,true).size
        ZOutlineButton("JPEG及视频加入（${jpeg}项）",onClick={if(vm.phone.enqueueBatch(bulkItems,false)>0){batchDialog=false;selectionMode=false;bulkHandles=emptySet()}},enabled=ui.isConnected && jpeg>0)
        ZOutlineButton("RAW及视频加入（${raw}项）",onClick={if(vm.phone.enqueueBatch(bulkItems,true)>0){batchDialog=false;selectionMode=false;bulkHandles=emptySet()}},enabled=ui.isConnected && raw>0)
        Text("加入后仍需点击下载队列的开始；不转换格式、不批量删除。",fontSize=10.sp)
        if(phone.message.isNotBlank())Text(phone.message,fontSize=10.sp)
    }},confirmButton={TextButton(onClick={batchDialog=false}){Text("取消")}})
    selected?.let { item ->
        ItemSheet(item = items.firstOrNull { it.handle == item.handle } ?: item,
            ui = ui, vm = vm, onDismiss = { selected = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCell(item:CameraStorage.Item,thumb:ByteArray?,isSelected:Boolean,onClick:()->Unit,onLongClick:()->Unit) {
    val bmp by produceState<android.graphics.Bitmap?>(null, thumb) {
        value = withContext(Dispatchers.Default) {
            thumb?.let { data -> runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 480 || bounds.outHeight / sample > 480) sample *= 2
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull() }
        }
    }
    val renderedBitmap = bmp
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(ZColors.Surface)
            .border(if(isSelected)2.dp else 0.dp,if(isSelected)ZColors.NikonYellow else Color.Transparent,RoundedCornerShape(6.dp))
            .testTag("gallery-${item.handle}")
            .combinedClickable(onClick=onClick,onLongClick=onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        if (renderedBitmap != null) {
            Image(
                bitmap = renderedBitmap.asImageBitmap(),
                contentDescription = item.filename,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                if (item.isVideo) "▶" else "…",
                color = ZColors.TextTertiary,
                fontSize = 18.sp,
            )
        }
        // 底部信息条
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                item.baseName.take(16),
                color = ZColors.TextPrimary,
                fontSize = 8.sp,
                maxLines = 1,
                fontFamily = FontFamily.Monospace,
            )
            // 0x9434 不提供真实文件大小：未知时不显示大小
            if (item.sizeBytes > 0) {
                Text(
                    item.displaySize,
                    color = ZColors.TextTertiary,
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun ItemSheet(
    item: CameraStorage.Item,
    ui: UiState,
    vm: MainViewModel,
    onDismiss: () -> Unit,
) {
    var chooseFormat by remember(item.handle){mutableStateOf(false)}
    if(chooseFormat && !item.isVideo) {
        androidx.compose.material3.AlertDialog(
        onDismissRequest={chooseFormat=false},
        title={Text("选择下载格式")},
        text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text("只下载机身实际存在的文件；JPEG预览不等于有JPEG原文件。加入后仍需点队列开始。",fontSize=11.sp)
            if(!item.filenameVerified)Text("当前STA模式不提供原文件名：将按相册显示的生成名称保存，不保证与卡内原名相同；格式和文件字节不转换。选择下方格式即确认使用此名称。",fontSize=11.sp,color=ZColors.NikonYellow)
            ZOutlineButton("JPEG加入队列",onClick={vm.downloadItem(item,false);chooseFormat=false;onDismiss()},
                enabled=ui.isConnected && (item.filename.endsWith(".jpg",true)||item.filename.endsWith(".jpeg",true)))
            ZOutlineButton("RAW原片加入队列",onClick={vm.downloadItem(item,true);chooseFormat=false;onDismiss()},
                enabled=ui.isConnected && (item.isRaw || item.companionHandle!=null))
            if(item.isRaw)Text("此条目只有RAW文件，显示的是内嵌JPEG预览；不会把预览伪装成JPEG原片。",fontSize=10.sp)
            if(!item.isRaw && item.companionHandle==null)Text("本条目未找到对应RAW，RAW选项不可用。",fontSize=10.sp)
            if(!item.isRaw && !item.filename.endsWith(".jpg",true) && !item.filename.endsWith(".jpeg",true))
                ZOutlineButton("原文件加入队列（${item.filename.substringAfterLast('.',"未知格式")}）",onClick={vm.downloadItem(item);chooseFormat=false;onDismiss()},enabled=ui.isConnected)
        }},confirmButton={TextButton(onClick={chooseFormat=false}){Text("取消")}})
        return
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZColors.SurfaceHigh,
        titleContentColor = ZColors.TextPrimary,
        textContentColor = ZColors.TextSecondary,
        title = { Text(item.filename, fontSize = 13.sp, fontFamily = FontFamily.Monospace) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val preview = ui.galleryPreviewBitmap.takeIf { ui.galleryPreviewHandle == item.handle }
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = item.filename,
                        modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                PhonePhotoActions(item,vm)
                Text("分辨率 ${item.resolution}", fontSize = 11.sp)
                if (item.sizeBytes > 0) Text("大小 ${item.displaySize}", fontSize = 11.sp)
                Text("拍摄时间 ${item.capturedAt}", fontSize = 11.sp)
                if(item.companionHandle!=null)Text("可下载JPEG或对应RAW：${item.companionFilename.orEmpty()}",fontSize=11.sp,color=ZColors.TextSecondary)
                if(!item.filenameVerified)Text("STA显示名为生成名称，未核实卡内原名；下载前会再次提示。",fontSize=11.sp,color=ZColors.NikonYellow)
                if(item.isRaw)Text("当前为RAW内嵌JPEG预览，下载时保留RAW原文件。",fontSize=10.sp,color=ZColors.TextSecondary)
                if (ui.galleryPreviewHandle == item.handle && ui.galleryPreviewError.isNotBlank()) {
                    Text(ui.galleryPreviewError, color = if (preview == null && !ui.galleryPreviewBusy) ZColors.Danger else ZColors.TextTertiary, fontSize = 9.sp)
                }
                ZOutlineButton(
                    text = if (ui.galleryPreviewBusy && ui.galleryPreviewHandle == item.handle) "读取中…" else "读取照片预览",
                    onClick = { vm.loadGalleryPreview(item) },
                    enabled = !ui.galleryPreviewBusy,
                )
                if (ui.downloadingHandle == item.handle) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = ZColors.NikonYellow,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("下载中…", fontSize = 11.sp, color = ZColors.NikonYellow)
                    }
                }
            }
        },
        confirmButton = {
            ZPrimaryButton(
                text = if(item.isVideo) "视频加入下载队列" else "选择下载格式",
                onClick = { if(item.isVideo){vm.downloadItem(item);onDismiss()}else chooseFormat=true },
                enabled = ui.isConnected,
            )
        },
        dismissButton = {
            ZOutlineButton(
                text = "删除",
                onClick = { vm.deleteItem(item); onDismiss() },
                enabled = ui.isConnected,
            )
        },
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ZColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = ZColors.TextTertiary,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** 小工具：指尖就够的点击。 */
private fun Modifier.pointerInputTap(onTap: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) }
)


private fun fmtBytes(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / 1024.0 / 1024 / 1024)
    b >= 1024L * 1024 -> "%.0f MB".format(b / 1024.0 / 1024)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
