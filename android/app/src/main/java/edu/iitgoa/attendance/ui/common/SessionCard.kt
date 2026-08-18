package edu.iitgoa.attendance.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import edu.iitgoa.attendance.data.remote.SessionDto
import edu.iitgoa.attendance.ui.formatDate
import edu.iitgoa.attendance.ui.formatSlot
import edu.iitgoa.attendance.ui.theme.statusColors

/**
 * One session, styled by whether it is happening now.
 *
 * The live treatment keys off the server's `is_open`, never a local clock
 * comparison — the same value the server uses when it decides whether to accept
 * attendance, so the badge can never promise something the API will refuse.
 */
@Composable
fun SessionCard(
    session: SessionDto,
    modifier: Modifier = Modifier,
    showCourseName: Boolean = false,
    showDate: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val status = statusColors
    val live = session.isOpen

    val colors = if (live) {
        CardDefaults.cardColors(
            containerColor = status.liveContainer,
            contentColor = status.onLiveContainer,
        )
    } else {
        CardDefaults.cardColors()
    }

    val shape = RoundedCornerShape(12.dp)
    val cardModifier = modifier
        .fillMaxWidth()
        .then(
            if (live) Modifier.border(2.dp, status.live, shape) else Modifier
        )

    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp)) {
            if (live) {
                LiveBadge()
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (showCourseName) {
                        Text(
                            session.courseName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        formatSlot(session.startTime, session.endTime),
                        style = if (showCourseName) {
                            MaterialTheme.typography.bodyMedium
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                    )
                    if (showDate) {
                        Text(
                            formatDate(session.date),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (trailing != null) {
                    trailing()
                } else {
                    SessionStatusChip(session)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaLine(
                    icon = Icons.Default.Place,
                    text = "within ${session.radiusM.toInt()} m",
                )
                session.presentCount?.let {
                    Spacer(Modifier.width(16.dp))
                    MetaLine(icon = Icons.Default.Groups, text = "$it present")
                }
                if (session.repeatsRegularly) {
                    Spacer(Modifier.width(16.dp))
                    MetaLine(icon = Icons.Default.Repeat, text = session.repeat)
                }
            }
        }
    }

    if (onClick != null) {
        Card(onClick = onClick, modifier = cardModifier, shape = shape, colors = colors) {
            content()
        }
    } else {
        Card(modifier = cardModifier, shape = shape, colors = colors) { content() }
    }
}

/** A slow pulse, so "live" reads at a glance without being a distraction. */
@Composable
private fun LiveBadge() {
    val status = statusColors
    val transition = rememberInfiniteTransition(label = "live")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(status.live)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(pulse)
                .clip(CircleShape)
                .background(status.onLive)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "HAPPENING NOW",
            style = MaterialTheme.typography.labelSmall,
            color = status.onLive,
        )
    }
}

@Composable
private fun SessionStatusChip(session: SessionDto) {
    val status = statusColors
    // presence is null for teachers, who have no attendance of their own.
    val presence = session.presence ?: return

    val present = session.isPresent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Icon(
            if (present) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = presence,
            tint = if (present) status.present else status.absent,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (present) "Present" else "Absent",
            style = MaterialTheme.typography.labelMedium,
            color = if (present) status.present else status.absent,
        )
    }
}

@Composable
private fun MetaLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}
