package com.zbox.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One accessible, full-width toggle; collapsed bodies are not composed. */
@Composable
internal fun CollapsibleSectionHeader(title:String, expanded:Boolean, onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp)
        .clickable(role=Role.Button, onClickLabel=if(expanded) "收起" else "展开", onClick=onClick)
        .semantics { stateDescription=if(expanded) "已展开" else "已收起" }
        .padding(vertical=8.dp), verticalAlignment=Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color=ZColors.TextSecondary, fontSize=12.sp,
            fontWeight=FontWeight.Bold)
        Icon(if(expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription=null, tint=ZColors.TextTertiary, modifier=Modifier.size(20.dp))
    }
}
