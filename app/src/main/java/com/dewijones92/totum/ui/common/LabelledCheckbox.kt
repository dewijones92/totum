package com.dewijones92.totum.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LabelledCheckbox(checked: Boolean, label: String, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable { onChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}
