package dev.kortex.myinfo.topics.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.design.Amber
import dev.kortex.design.Grotesk
import dev.kortex.design.Mono
import dev.kortex.design.Sunken
import dev.kortex.design.Synapse
import dev.kortex.myinfo.topics.domain.model.ItemType

// Type scale of the Topics mocks (Figma: Topics).
internal val CardTitleStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp)
internal val BodyStyle = TextStyle(fontFamily = Grotesk, fontSize = 13.sp, lineHeight = 19.sp)
internal val RowTitleStyle = TextStyle(fontFamily = Grotesk, fontSize = 14.sp, lineHeight = 20.sp)
internal val HeroTitleStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp)
internal val HeroBodyStyle = TextStyle(fontFamily = Grotesk, fontSize = 14.sp, lineHeight = 22.sp)
internal val ButtonStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
internal val TrayLabelStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp)

/** Uppercase telemetry: ages, counts, section labels. */
internal val MetaStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.sp)
internal val ChipStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp)
internal val BadgeStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.9.sp)

internal val CardShape = RoundedCornerShape(16.dp)
internal val RowShape = RoundedCornerShape(12.dp)

/** How item types are listed wherever the user picks among them (Figma: Topics 1g). */
internal val SectionOrder = listOf(
    ItemType.Note, ItemType.Image, ItemType.Doc, ItemType.Link, ItemType.Article, ItemType.Video, ItemType.Email, ItemType.Bill,
)

/** Three-letter code of an item type, as its badges show it. */
internal val ItemType.badge: String
    get() = when (this) {
        ItemType.Note -> "NTE"
        ItemType.Link -> "LNK"
        ItemType.Article -> "ART"
        ItemType.Video -> "VID"
        ItemType.Doc -> "DOC"
        ItemType.Image -> "IMG"
        ItemType.Bill -> "BIL"
        ItemType.Email -> "EML"
    }

/** What a screen reader calls a type, where the badge shows [badge]. */
internal val ItemType.spokenName: String
    get() = when (this) {
        ItemType.Note -> "Note"
        ItemType.Link -> "Link"
        ItemType.Article -> "Article"
        ItemType.Video -> "Video"
        ItemType.Doc -> "Document"
        ItemType.Image -> "Image"
        ItemType.Bill -> "Bill"
        ItemType.Email -> "Email"
    }

/** Money is amber everywhere; every other type wears the accent. */
internal val ItemType.accent get() = if (this == ItemType.Bill) Amber else Synapse

/** A type code on a recessed square (Figma: Topics 1a, 1g). Read aloud as the type's name, not its code. */
@Composable
internal fun TypeBadge(type: ItemType, modifier: Modifier = Modifier, size: Dp = 34.dp, cornerRadius: Dp = 8.dp) {
    Box(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = type.spokenName }
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(Sunken),
        contentAlignment = Alignment.Center,
    ) {
        Text(type.badge, style = BadgeStyle.copy(letterSpacing = 0.sp), color = type.accent)
    }
}
