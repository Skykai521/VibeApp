package com.vibe.app.presentation.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibe.app.R
import com.vibe.app.feature.agent.AgentPlan
import com.vibe.app.feature.agent.PlanStepStatus

@Composable
fun PlanBubble(
    plan: AgentPlan,
    isLive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val completedCount = plan.steps.count { it.status == PlanStepStatus.COMPLETED }
    val totalCount = plan.steps.size
    val progress = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(12.dp)
            .animateContentSize(),
    ) {
        // Header row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "\uD83D\uDCCB",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.plan_header, completedCount, totalCount),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (isLive && completedCount < totalCount) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                )
            }
        }

        // Progress bar
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        // Plan summary
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = plan.summary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // Step list
        Spacer(modifier = Modifier.height(6.dp))
        plan.steps.forEach { step ->
            val (icon, textColor) = when (step.status) {
                PlanStepStatus.COMPLETED -> "\u2705" to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                PlanStepStatus.IN_PROGRESS -> "\uD83D\uDD04" to MaterialTheme.colorScheme.primary
                PlanStepStatus.FAILED -> "\u274C" to MaterialTheme.colorScheme.error
                PlanStepStatus.SKIPPED -> "\u23ED\uFE0F" to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                PlanStepStatus.PENDING -> "\u2B1C" to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            }

            Row(
                modifier = Modifier.padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = icon,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(20.dp),
                )
                Text(
                    text = "${step.id}. ${step.description}",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
