package com.fixlocal.android.data.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.ui.graphics.vector.ImageVector

enum class AssistantTask(
    val label: String,
    val description: String,
    val icon: ImageVector
) {
    REWRITE("Rewrite", "Proofread grammar, spelling & tone", Icons.Outlined.AutoFixHigh),
    TRANSLATE("Translate", "On-device bilingual translation", Icons.Outlined.Translate),
    SUMMARIZE("Summarize", "Key takeaways & bullet points", Icons.Outlined.Summarize),
    CONTINUE("Continue", "Smart drafting & thought completion", Icons.Outlined.EditNote)
}
