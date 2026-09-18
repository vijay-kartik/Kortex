package dev.kortex.myinfo.topics.ui.detail

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.Grotesk
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Sunken
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.done
import dev.kortex.myinfo.topics.ui.common.LocalThumbnail
import dev.kortex.myinfo.topics.ui.common.MetaStyle
import dev.kortex.myinfo.topics.ui.common.TypeBadge
import dev.kortex.myinfo.topics.ui.common.accent
import dev.kortex.myinfo.topics.ui.common.ageLabel
import dev.kortex.myinfo.topics.ui.common.dueLabel
import dev.kortex.myinfo.topics.ui.common.formatDate
import dev.kortex.myinfo.topics.ui.common.formatDuration
import dev.kortex.myinfo.topics.ui.common.formatMoney
import dev.kortex.myinfo.topics.ui.common.hostOf

/**
 * One item in a topic's feed (Figma: Topics 1b). Anything with a picture leads with it; the rest
 * are text. Every card ends with its type and age.
 */
@Composable
internal fun TopicItemCard(
    item: TopicItem,
    nowMillis: Long,
    onClick: (() -> Unit)?,
    onSetDone: (Boolean) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    /** Selection mode (Figma: Topics 1e): tapping picks out instead of opening. */
    selecting: Boolean = false,
    selected: Boolean = false,
) {
    val view = LocalView.current
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnClick by rememberUpdatedState(onClick)
    // Held aside: inside the semantics block, `selected` is the property being set, not this flag.
    val isSelected = selected
    val shape = ItemShape
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isSelected) SynapseDim else Panel)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) Synapse else Edge,
                shape = shape,
            )
            .pointerInput(selecting) {
                detectTapGestures(
                    onLongPress = {
                        if (!selecting) {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            currentOnLongPress()
                        }
                    },
                    // In selection mode every card is tappable, even ones with nowhere to open.
                    onTap = { if (selecting) currentOnLongPress() else currentOnClick?.invoke() },
                )
            }
            .semantics {
                if (selecting) {
                    // Qualified: the bare name is this composable's parameter, not the property.
                    this.selected = isSelected
                    onClick(label = if (isSelected) "Deselect item" else "Select item") {
                        currentOnLongPress()
                        true
                    }
                } else {
                    currentOnClick?.let { open ->
                        onClick(label = "Open item") {
                            open()
                            true
                        }
                    }
                    onLongClick(label = "Select item") {
                        currentOnLongPress()
                        true
                    }
                }
            },
    ) {
        media(item)?.let { (path, corner) -> MediaHeader(path, corner) }
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ItemBody(item)
            if (item is TopicItem.Bill) BillDates(item, nowMillis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(typeLabel(item), style = MetaStyle, color = typeColor(item), modifier = Modifier.weight(1f))
                if (item.pinned) {
                    Text("PINNED", style = MetaStyle, color = Synapse, modifier = Modifier.padding(end = 10.dp))
                }
                // A tap in selection mode picks the card out, so the chip would be unreachable anyway.
                if (!selecting) {
                    item.done?.let { done -> DoneChip(item, done, onSetDone, Modifier.padding(end = 10.dp)) }
                }
                Text(ageLabel(item.addedAtMillis, nowMillis), style = MetaStyle, color = Muted)
            }
        }
    }
}

/**
 * Ticks the item off where it sits (Figma: Topics 1b). Unticked it is an outline the user is
 * meant to act on; ticked it recedes, still tappable so a mistake can be undone.
 */
@Composable
private fun DoneChip(item: TopicItem, done: Boolean, onSetDone: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val accent = if (item is TopicItem.Bill) Amber else Synapse
    Text(
        if (done) "${doneWord(item)} ✓" else doneAction(item),
        style = MetaStyle.copy(letterSpacing = 0.5.sp),
        color = if (done) accent else Muted,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .then(if (done) Modifier.background(Sunken) else Modifier.border(1.dp, Edge, shape))
            .clickable(
                onClickLabel = if (done) "Mark not ${doneWord(item).lowercase()}" else doneAction(item),
                role = Role.Checkbox,
            ) { onSetDone(!done) }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

/** "ISSUED 3 FEB · DUE IN 4 DAYS", once a bill has either date. */
@Composable
private fun BillDates(bill: TopicItem.Bill, nowMillis: Long) {
    val issued = bill.issuedAtMillis?.let { "ISSUED ${formatDate(it, nowMillis)}" }
    // A paid bill's deadline is history, so it reads as a plain date rather than a countdown.
    val due = bill.dueAtMillis?.let { if (bill.paid) "DUE ${formatDate(it, nowMillis)}" else dueLabel(it, nowMillis) }
    if (issued == null && due == null) return
    val overdue = !bill.paid && bill.dueAtMillis != null && bill.dueAtMillis < nowMillis
    Text(
        listOfNotNull(issued, due).joinToString(" · "),
        style = MetaStyle.copy(letterSpacing = 0.5.sp),
        color = if (overdue) Alarm else Muted,
    )
}

@Composable
private fun ItemBody(item: TopicItem) {
    when (item) {
        is TopicItem.Note -> Text(item.text, style = NoteStyle, color = InkSoft)
        is TopicItem.Video -> Title(item.link.title)
        is TopicItem.Link -> LinkBody(item.link.title, item.link.url)
        is TopicItem.Article -> LinkBody(item.link.title, item.link.url)
        is TopicItem.Doc -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TypeBadge(ItemType.Doc)
            Title(item.title, Modifier.weight(1f))
        }
        is TopicItem.Image -> item.caption?.let { Text(it, style = NoteStyle, color = InkSoft) }
        is TopicItem.Bill -> Row(verticalAlignment = Alignment.CenterVertically) {
            Title(item.title, Modifier.weight(1f))
            Text(
                formatMoney(item.amount),
                style = MetaStyle.copy(fontSize = 12.sp, letterSpacing = 0.sp),
                color = if (item.paid) InkSoft else Alarm,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun LinkBody(title: String, url: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Title(title.ifBlank { url })
        Text(hostOf(url), style = MetaStyle.copy(letterSpacing = 0.sp), color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Title(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitleStyle, color = Ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** A 130dp picture across the top, with [corner] (a video's length) on it when there is one. */
@Composable
private fun MediaHeader(path: String, corner: String?) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(MEDIA_HEIGHT)
            .background(Sunken),
    ) {
        LocalThumbnail(path, Modifier.fillMaxSize())
        if (corner != null) {
            Text(
                corner,
                style = MetaStyle.copy(letterSpacing = 0.sp),
                color = Ink,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 11.dp, vertical = 9.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Void)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/** The picture an item leads with, and what goes in its corner. */
private fun media(item: TopicItem): Pair<String, String?>? = when (item) {
    is TopicItem.Video -> item.link.thumbnailPath?.let { it to item.durationSeconds?.let(::formatDuration) }
    is TopicItem.Link -> item.link.thumbnailPath?.let { it to null }
    is TopicItem.Article -> item.link.thumbnailPath?.let { it to null }
    is TopicItem.Image -> item.file.path to null
    is TopicItem.Note, is TopicItem.Doc, is TopicItem.Bill -> null
}

/** The type and what's worth knowing about it; whether it's done with is the chip's job. */
private fun typeLabel(item: TopicItem): String = when (item) {
    is TopicItem.Note -> "NOTE"
    is TopicItem.Link -> "LINK"
    is TopicItem.Article -> listOfNotNull("ARTICLE", item.readingMinutes?.let { "$it MIN" }).joinToString(" · ")
    // The length rides on the thumbnail when there is one; without one this is the only place for it.
    is TopicItem.Video -> listOfNotNull("VIDEO", item.durationSeconds?.takeIf { item.link.thumbnailPath == null }?.let(::formatDuration))
        .joinToString(" · ")
    is TopicItem.Doc -> listOfNotNull("DOC", item.pageCount?.let { if (it == 1) "1 PAGE" else "$it PAGES" }).joinToString(" · ")
    is TopicItem.Image -> "IMAGE"
    is TopicItem.Bill -> if (item.file != null) "BILL · INVOICE" else "BILL"
}

/** What being done means for this item, as the chip says it once and as an action. */
private fun doneWord(item: TopicItem): String = when (item) {
    is TopicItem.Article -> "READ"
    is TopicItem.Video -> "WATCHED"
    else -> "PAID"
}

private fun doneAction(item: TopicItem): String = "MARK ${doneWord(item)}"

private fun typeColor(item: TopicItem) = when {
    item is TopicItem.Bill && !item.paid -> Alarm
    item is TopicItem.Bill -> Amber
    else -> item.type.accent
}

private val ItemShape = RoundedCornerShape(14.dp)
private val MEDIA_HEIGHT = 130.dp
private val TitleStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 19.5.sp)
private val NoteStyle = TextStyle(fontFamily = Grotesk, fontSize = 14.sp, lineHeight = 21.sp)
