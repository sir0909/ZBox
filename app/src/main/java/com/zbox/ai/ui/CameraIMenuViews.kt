package com.zbox.ai.ui
import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zbox.ai.core.CameraIMenuPolicy as P
data class CameraMenuEntry(val definition:P.Definition,val current:P.Value?=null,val descriptor:P.Descriptor?=null,val reason:String="未读取"){
 val editable:Boolean get()=descriptor?.let{it.writable && it.values.isNotEmpty() && it.type in listOf(1,2,3,4,5,6,0xffff)}==true
}
@Composable internal fun CameraIMenuSheet(frame:Bitmap,ui:UiState,vm:MainViewModel,onSelection:((String,P.Value)->Boolean)?=null,compact:Boolean=false,landscapePanel:Boolean=false,onClose:()->Unit){
 var selected by remember{mutableStateOf<String?>(null)};var submitting by remember{mutableStateOf(false)}
 val entry=ui.cameraMenuEntries.firstOrNull{it.definition.id==selected}
 LaunchedEffect(ui.cameraMenuBusy,ui.cameraMenuMessage){if(submitting && !ui.cameraMenuBusy){submitting=false;if(ui.cameraMenuMessage.startsWith("已修改"))selected=null}}
 GlassCameraSheet(frame,onClose,bottom=selected==null && !landscapePanel,compact=compact,anchorBottom=!compact && selected==null,widthFraction=if(landscapePanel).48f else null){
  if(entry!=null)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
   TextButton(onClick={selected=null},enabled=!ui.cameraMenuBusy && !submitting){Text("返回")}
   Text(entry.definition.title,Modifier.weight(1f),color=Color.White,fontSize=15.sp)
  }
  if(ui.cameraMenuBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
  if(entry==null){
   Column((if(compact)Modifier.heightIn(max=480.dp) else Modifier.weight(1f)).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
    ui.cameraMenuEntries.chunked(if(landscapePanel)4 else 6).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
     row.forEach{item->val enabled=item.editable && !ui.cameraMenuBusy && ui.isConnected
      Column(Modifier.weight(1f).height(if(landscapePanel)58.dp else 76.dp).background(Color.White.copy(alpha=if(enabled).10f else .035f),RoundedCornerShape(8.dp))
       .border(1.dp,Color.White.copy(alpha=if(enabled).20f else .08f),RoundedCornerShape(8.dp))
       .clickable(enabled=enabled){selected=item.definition.id}.padding(6.dp),verticalArrangement=Arrangement.Center){
       Text(item.definition.title,color=Color.White.copy(alpha=.72f),fontSize=if(landscapePanel)9.sp else 10.sp,maxLines=1)
       Text(if(item.definition.id=="connection")if(ui.isConnected)"已连接"else"未连接"else if(item.current==null)if(ui.cameraMenuBusy)"读取中"else"不可用"else P.label(item.definition.readCode,item.current,item.descriptor,ui.deviceInfo.model).let{if(it.startsWith("0x"))"机身扩展设置"else it},color=Color.White.copy(alpha=if(enabled).95f else .5f),fontSize=if(landscapePanel)11.sp else 12.sp,maxLines=2)
       if(!item.editable && (item.current!=null || item.definition.id=="connection"))Text("只读",fontSize=9.sp,color=Color.White.copy(alpha=.4f))
      }
     }
    }}
   }
  }else{
   val choices=entry.descriptor?.let{P.orderedValues(entry.definition.readCode,it,ui.deviceInfo.model)}.orEmpty()
   Column((if(compact)Modifier.heightIn(max=480.dp) else Modifier.weight(1f)).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
    choices.chunked(2).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){row.forEach{option->
      val label=P.label(entry.definition.readCode,option,entry.descriptor,ui.deviceInfo.model)
      OutlinedButton(onClick={
       if(!submitting && !ui.cameraMenuBusy){if(option==entry.current)selected=null else{submitting=true;if(!(onSelection?:vm::applyCameraMenu).invoke(entry.definition.id,option))submitting=false}}
      },enabled=!ui.cameraMenuBusy && !submitting && !label.startsWith("0x"),modifier=Modifier.weight(1f),border=BorderStroke(1.dp,if(entry.current==option)ZColors.NikonYellow else Color.White.copy(alpha=.2f))){Text(if(label.startsWith("0x"))"暂不支持此选项"else label,color=if(entry.current==option)ZColors.NikonYellow else Color.White,fontSize=12.sp)}
    }}}
   }
  }
  if(ui.cameraMenuMessage.startsWith("修改未确认") || ui.cameraMenuMessage.startsWith("菜单读取未完成"))Text(ui.cameraMenuMessage,color=ZColors.Danger,fontSize=11.sp,maxLines=2)
 }
}
