package com.zbox.ai.ui
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** Camera-only blurred preview, never screenshots of keys/settings. Bounded to 256px, updated at most 1Hz. */
internal fun blurCameraPreview(source:Bitmap):Bitmap{
 val scale=minOf(1f,256f/maxOf(source.width,source.height));val b=Bitmap.createScaledBitmap(source,maxOf(1,(source.width*scale).toInt()),maxOf(1,(source.height*scale).toInt()),true)
 val w=b.width;val h=b.height;val src=IntArray(w*h);b.getPixels(src,0,w,0,0,w,h);if(b!==source)b.recycle();val tmp=IntArray(src.size);val dst=IntArray(src.size);val radius=5
 fun average(input:IntArray,output:IntArray,horizontal:Boolean){for(y in 0 until h)for(x in 0 until w){var r=0;var g=0;var bl=0
  for(d in -radius..radius){val xx=if(horizontal)(x+d).coerceIn(0,w-1)else x;val yy=if(horizontal)y else(y+d).coerceIn(0,h-1);val c=input[yy*w+xx];r+=c ushr 16 and 255;g+=c ushr 8 and 255;bl+=c and 255}
  val n=radius*2+1;output[y*w+x]=(255 shl 24)or((r/n)shl 16)or((g/n)shl 8)or(bl/n)
 }}
 average(src,tmp,true);average(tmp,dst,false);return Bitmap.createBitmap(dst,w,h,Bitmap.Config.ARGB_8888)
}
@Composable internal fun GlassCameraSheet(frame:Bitmap,onClose:()->Unit,bottom:Boolean=false,compact:Boolean=false,anchorBottom:Boolean=bottom,widthFraction:Float?=null,content:@Composable ColumnScope.()->Unit){
 BackHandler{onClose()};val latest by rememberUpdatedState(frame)
 val blurred by produceState<Bitmap?>(null){while(isActive){value=withContext(Dispatchers.Default){runCatching{blurCameraPreview(latest)}.getOrNull()};delay(1000)}}
 BoxWithConstraints(Modifier.fillMaxSize().testTag("camera-glass-overlay")){
  val density=LocalDensity.current;val viewportW=with(density){maxWidth.toPx()};val viewportH=with(density){maxHeight.toPx()}
  Box(Modifier.matchParentSize().background(Color.Black.copy(alpha=.08f)).clickable(onClick=onClose))
  val width=if(bottom)minOf(680.dp,maxWidth-32.dp)else minOf(480.dp,maxWidth*(widthFraction?:.65f))
  var origin by remember{mutableStateOf(Offset.Zero)}
  Box(Modifier.align(if(anchorBottom)Alignment.BottomCenter else Alignment.Center)
   .padding(if(bottom)PaddingValues(horizontal=16.dp,vertical=12.dp)else PaddingValues(horizontal=16.dp,vertical=12.dp))
   .width(width).then(if(compact)Modifier.heightIn(max=maxHeight*(if(bottom).60f else .96f)) else Modifier.fillMaxHeight(if(bottom).60f else .96f)).clip(RoundedCornerShape(18.dp))
   .border(1.dp,Color.White.copy(alpha=.16f),RoundedCornerShape(18.dp))
   .onGloballyPositioned{origin=it.positionInRoot()}.clickable(onClick={}).testTag("camera-glass-panel").semantics{contentDescription="取景磨砂浮层"}){
    Canvas(Modifier.matchParentSize()){
     blurred?.let{b->val fit=minOf(viewportW/frame.width,viewportH/frame.height);val w=frame.width*fit;val h=frame.height*fit
      drawImage(b.asImageBitmap(),dstOffset=IntOffset(((viewportW-w)/2-origin.x).roundToInt(),((viewportH-h)/2-origin.y).roundToInt()),dstSize=IntSize(w.roundToInt(),h.roundToInt()))}
     drawRect(Color(0xff11151b).copy(alpha=.57f))
    }
    Column((if(compact)Modifier.fillMaxWidth() else Modifier.fillMaxSize()).padding(10.dp),content=content)
  }
 }
}
