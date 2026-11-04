package com.zbox.ai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Equal-width/equal-height compact controls; no changes to global button styles. */
@Composable
internal fun MonitorActionButton(text:String,onClick:()->Unit,modifier:Modifier=Modifier,
    enabled:Boolean=true,selected:Boolean=false) {
    OutlinedButton(onClick=onClick,enabled=enabled,modifier=modifier.height(44.dp),
        shape=RoundedCornerShape(10.dp),contentPadding=PaddingValues(horizontal=4.dp,vertical=0.dp),
        border=BorderStroke(1.dp,if(enabled && selected) ZColors.NikonYellow else ZColors.Outline),
        colors=ButtonDefaults.outlinedButtonColors(
            containerColor=if(selected) ZColors.NikonYellow else ZColors.Surface,
            contentColor=if(selected) Color(0xFF121212) else ZColors.TextPrimary,
            disabledContainerColor=Color(0xFF202020),disabledContentColor=ZColors.TextTertiary),
    ) { Text(text,fontSize=12.sp,fontWeight=FontWeight.Medium,maxLines=1) }
}
