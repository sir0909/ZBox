package com.zbox.ai.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CompositionPanel(ui: UiState, vm: MainViewModel) {
    if (!ui.compositionOpen) return
    var cropPreview by remember(ui.compositionAdvice, ui.compositionSnapshot) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical=8.dp).background(Color(0xFF15212A)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
            Text("AI 构图 / 机位建议", color=Color.White, fontSize=16.sp)
            TextButton(onClick={ vm.clearComposition(close=true) }) { Text("关闭") }
        }
        Text("仅点分析才发送一张缩小快照到当前 AI 供应商。每次点击一次请求，失败不自动重试。",fontSize=12.sp,color=Color(0xFFB9CDD9))
        Text("当前：${ui.activeAccount.provider.displayName} / ${ui.activeAccount.model}",fontSize=11.sp,color=Color(0xFFB9CDD9))
        Row {
            TextButton(onClick=vm::analyzeComposition, enabled=!ui.compositionBusy) {
                Text(if(ui.compositionBusy) "分析中…" else if(ui.compositionSnapshot==null) "分析当前画面" else "重新分析")
            }
            TextButton(onClick={ vm.clearComposition() }) { Text(if(ui.compositionBusy) "取消请求" else "清除建议") }
        }
        if (ui.compositionBusy) Text("正在分析本次快照；取消不能保证服务端不计费。",color=Color(0xFFFFD37A),fontSize=12.sp)
        if (ui.compositionError.isNotBlank()) Text(ui.compositionError,color=Color(0xFFFFBC9B),fontSize=12.sp)
        val snapshot=ui.compositionSnapshot
        val advice=ui.compositionAdvice
        if(snapshot!=null) {
            Text("分析快照 · 非实时跟踪；移动后请手动重新分析",fontSize=11.sp,color=Color(0xFFB9CDD9))
            Box(Modifier.fillMaxWidth().aspectRatio(snapshot.width.toFloat()/snapshot.height).background(Color.Black)) {
                Image(snapshot.asImageBitmap(),"本次分析快照",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                if(advice!=null) Canvas(Modifier.fillMaxSize()) {
                    fun drawBox(b:com.zbox.ai.core.CompositionBox,color:Color) {
                        drawRect(color,Offset(b.left*size.width,b.top*size.height),
                            Size((b.right-b.left)*size.width,(b.bottom-b.top)*size.height),style=Stroke(2.dp.toPx()))
                    }
                    advice.subject?.let { drawBox(it,Color(0xFF67C7FF)) }
                    drawBox(advice.crop,Color(0xFFFFD35F))
                }
            }
            if(advice!=null) {
                Text("黄框：推荐裁切构图；蓝框：主体（如能识别）",fontSize=11.sp,color=Color(0xFFB9CDD9))
                Text(advice.summary,color=Color.White,fontSize=14.sp)
                advice.moves.forEach { Text("机位 → $it",color=Color(0xFFBFE4F2),fontSize=13.sp) }
                advice.tips.forEach { Text("拍法 · $it",color=Color(0xFFBFE4F2),fontSize=13.sp) }
                Text("单张画面无法确认画外环境或精确距离，请留意周围安全。",fontSize=11.sp,color=Color(0xFFFFD37A))
                TextButton(onClick={cropPreview=!cropPreview}) { Text(if(cropPreview) "收起裁切示意" else "预览推荐构图") }
                if(cropPreview) {
                    val cropped=remember(snapshot,advice.crop) {
                        val b=advice.crop
                        val x=(b.left*snapshot.width).toInt().coerceIn(0,snapshot.width-1)
                        val y=(b.top*snapshot.height).toInt().coerceIn(0,snapshot.height-1)
                        val w=((b.right-b.left)*snapshot.width).toInt().coerceIn(1,snapshot.width-x)
                        val h=((b.bottom-b.top)*snapshot.height).toInt().coerceIn(1,snapshot.height-y)
                        Bitmap.createBitmap(snapshot,x,y,w,h)
                    }
                    Text("裁切示意 · 未改变镜头变焦，也不改变实际照片范围",fontSize=11.sp,color=Color(0xFFFFD37A))
                    Image(cropped.asImageBitmap(),"推荐构图裁切示意",Modifier.fillMaxWidth().aspectRatio(cropped.width.toFloat()/cropped.height),contentScale=ContentScale.Fit)
                }
            }
        }
    }
}
