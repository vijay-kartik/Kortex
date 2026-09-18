package dev.kortex.myinfo.topics.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Ink
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Sunken
import dev.kortex.myinfo.topics.domain.model.BillSummary
import dev.kortex.myinfo.topics.ui.common.CardTitleStyle
import dev.kortex.myinfo.topics.ui.common.MetaStyle
import dev.kortex.myinfo.topics.ui.common.dueLabel
import dev.kortex.myinfo.topics.ui.common.formatMoney

/**
 * What a topic's bills add up to, above its feed (Figma: Topics 1b). It leads with what's still
 * owed, since that's the number worth acting on; once everything is paid it says so instead.
 */
@Composable
internal fun BillsCard(bills: BillSummary, nowMillis: Long, modifier: Modifier = Modifier) {
    val settled = bills.outstanding.isEmpty()
    val overdue = bills.nextDueAtMillis != null && bills.nextDueAtMillis < nowMillis
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(BillsShape)
            .background(Panel)
            .border(1.dp, if (overdue) Alarm else Amber.copy(alpha = BORDER_ALPHA), BillsShape)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (settled) "BILLS · ALL PAID" else "BILLS · OUTSTANDING", style = MetaStyle, color = if (settled) Muted else Amber, modifier = Modifier.weight(1f).semantics { heading() })
            Text("${bills.paid.done}/${bills.paid.total} PAID", style = MetaStyle, color = Muted)
        }
        Text(
            if (settled) "Nothing left to pay" else bills.outstanding.joinToString(" · ") { formatMoney(it) },
            style = CardTitleStyle.copy(fontSize = if (settled) 15.sp else 19.sp),
            color = if (settled) Muted else Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        PaidBar(done = bills.paid.done, total = bills.paid.total)
        bills.nextDueAtMillis?.let { due ->
            Text(dueLabel(due, nowMillis), style = MetaStyle.copy(letterSpacing = 0.8.sp), color = if (overdue) Alarm else Muted)
        }
    }
}

@Composable
private fun PaidBar(done: Int, total: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Sunken),
    ) {
        if (done > 0) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(done.toFloat() / total)
                    .background(Amber),
            )
        }
    }
}

private val BillsShape = RoundedCornerShape(14.dp)

/** Amber at full strength is the accent, not a border; the card's outline only hints at it. */
private const val BORDER_ALPHA = 0.45f
