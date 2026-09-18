package dev.kortex.myinfo.topics.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import dev.kortex.design.Void
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.ui.common.LocalThumbnail
import dev.kortex.myinfo.topics.ui.common.MetaStyle
import dev.kortex.myinfo.topics.ui.common.TypeBadge
import dev.kortex.myinfo.topics.ui.common.accent
import dev.kortex.myinfo.topics.ui.common.ageLabel
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ItemShape)
            .background(Panel)
            .border(1.dp, Edge, ItemShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        media(item)?.let { (path, corner) -> MediaHeader(path, corner) }
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ItemBody(item)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(typeLabel(item), style = MetaStyle, color = typeColor(item), modifier = Modifier.weight(1f))
                Text(ageLabel(item.addedAtMillis, nowMillis), style = MetaStyle, color = Muted)
            }
        }
    }
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

private fun typeLabel(item: TopicItem): String = when (item) {
    is TopicItem.Note -> "NOTE"
    is TopicItem.Link -> "LINK"
    is TopicItem.Article -> listOfNotNull("ARTICLE", item.readingMinutes?.let { "$it MIN" }, if (item.read) null else "UNREAD").joinToString(" · ")
    is TopicItem.Video -> if (item.watched) "VIDEO · WATCHED" else "VIDEO"
    is TopicItem.Doc -> listOfNotNull("DOC", item.pageCount?.let { if (it == 1) "1 PAGE" else "$it PAGES" }).joinToString(" · ")
    is TopicItem.Image -> "IMAGE"
    is TopicItem.Bill -> if (item.paid) "BILL · PAID" else "BILL · DUE"
}

private fun typeColor(item: TopicItem) = when {
    item is TopicItem.Bill && !item.paid -> Alarm
    item is TopicItem.Bill -> Amber
    else -> item.type.accent
}

private val ItemShape = RoundedCornerShape(14.dp)
private val MEDIA_HEIGHT = 130.dp
private val TitleStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 19.5.sp)
private val NoteStyle = TextStyle(fontFamily = Grotesk, fontSize = 14.sp, lineHeight = 21.sp)
