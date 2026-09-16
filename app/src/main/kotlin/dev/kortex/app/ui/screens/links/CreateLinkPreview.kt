package dev.kortex.app.ui.screens.links

import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.app.R
import dev.kortex.app.ui.Alarm
import dev.kortex.app.ui.Edge
import dev.kortex.app.ui.Grotesk
import dev.kortex.app.ui.Ink
import dev.kortex.app.ui.Mono
import dev.kortex.app.ui.Muted
import dev.kortex.app.ui.Panel
import dev.kortex.app.ui.Synapse
import dev.kortex.app.ui.SynapseDim

/**
 * The card as it will appear in Links, filled in as the page is read (Figma: New link / Preview).
 * The card itself is the progress indicator; the status beside PREVIEW only speaks while something
 * is loading or has failed, and Hide / Show image lets the user pick the link icon instead.
 */
@Composable
internal fun LinkPreviewSection(
    url: String,
    domain: String,
    title: String,
    phase: PageReadPhase,
    image: PreviewImage,
    selectedTags: List<String>,
    imageHidden: Boolean,
    onImageHiddenChange: (Boolean) -> Unit,
    onRetryImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    fun setHidden(hidden: Boolean) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        // Showing an image that never arrived means trying again.
        if (!hidden && image == PreviewImage.Failed) onRetryImage()
        onImageHiddenChange(hidden)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            // Held at the action pill's height so the card doesn't shift when actions come and go.
            modifier = Modifier.fillMaxWidth().heightIn(min = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "PREVIEW",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.5.sp),
                color = Muted,
            )
            PreviewStatus(status = previewStatus(domain, phase, image, imageHidden), modifier = Modifier.padding(start = 4.dp).weight(1f))
            PreviewActions(
                actions = previewActions(image, imageHidden),
                onHide = { setHidden(true) },
                onShow = { setHidden(false) },
                onRetry = onRetryImage,
            )
        }
        PreviewCard(url = url, domain = domain, title = title, phase = phase, image = image, imageHidden = imageHidden, selectedTags = selectedTags)
        PreviewCaption(caption = previewCaption(domain, phase, image, imageHidden))
    }
}

// ── Card ──────────────────────────────────────────────────────────

@Composable
private fun PreviewCard(
    url: String,
    domain: String,
    title: String,
    phase: PageReadPhase,
    image: PreviewImage,
    imageHidden: Boolean,
    selectedTags: List<String>,
) {
    val readingPage = phase == PageReadPhase.Idle || phase == PageReadPhase.ReadingPage
    val thumbnail = when (image) {
        PreviewImage.Unknown -> if (readingPage) ThumbnailContent.ReadingPage else ThumbnailContent.Glyph
        PreviewImage.None -> ThumbnailContent.Glyph
        is PreviewImage.Loading -> ThumbnailContent.LoadingImage(image.fraction)
        is PreviewImage.Ready -> ThumbnailContent.Image(image.path)
        PreviewImage.Failed -> ThumbnailContent.Failed
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LinkCardShape)
            .background(Panel)
            .border(1.dp, Edge, LinkCardShape)
            .padding(LinkCardInset),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LinkThumbnail(content = thumbnail, hidden = imageHidden && image.canHide)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Until there's a title of either kind, a bar stands in for it; the text crossfades in.
            val shownTitle = title.ifBlank { if (readingPage) null else domain }
            AnimatedContent(
                targetState = shownTitle,
                contentKey = { it == null },
                transitionSpec = { fadeIn(tween(TITLE_FADE_MS)) togetherWith fadeOut(tween(TITLE_FADE_MS)) },
                label = "preview title",
            ) { text ->
                if (text == null) {
                    Box(Modifier.height(24.dp), contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.width(180.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Edge))
                    }
                } else {
                    Text(text, style = LinkTitleStyle, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(url, style = LinkUrlStyle, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (phase) {
                    PageReadPhase.Idle, PageReadPhase.ReadingPage -> buildAnnotatedString { append("WAITING FOR PAGE") }
                    PageReadPhase.SuggestingTags -> buildAnnotatedString { append("SUGGESTING TAGS…") }
                    PageReadPhase.Done -> buildAnnotatedString {
                        if (selectedTags.isNotEmpty()) {
                            withStyle(SpanStyle(color = Synapse)) { append(selectedTags.joinToString(" · ").uppercase()) }
                            append(" · ")
                        }
                        append("JUST NOW")
                    }
                },
                style = LinkMetaStyle,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Only an image that arrived, or one that failed, has anything to hide. */
private val PreviewImage.canHide get() = this is PreviewImage.Ready || this == PreviewImage.Failed

// ── Status, actions, caption ──────────────────────────────────────

private data class StatusLine(val text: String, val color: Color)

private fun previewStatus(domain: String, phase: PageReadPhase, image: PreviewImage, imageHidden: Boolean): StatusLine? = when {
    phase == PageReadPhase.Idle || phase == PageReadPhase.ReadingPage -> StatusLine("Reading $domain…", Synapse)
    image is PreviewImage.Loading -> StatusLine("Loading image…", Synapse)
    image == PreviewImage.Failed && !imageHidden -> StatusLine("Image failed", Alarm)
    else -> null
}

@Composable
private fun PreviewStatus(status: StatusLine?, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = status,
        transitionSpec = { fadeIn(tween(STATUS_FADE_MS)) togetherWith fadeOut(tween(STATUS_FADE_MS)) },
        modifier = modifier,
        label = "preview status",
    ) { shown ->
        if (shown != null) {
            Text(
                shown.text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
                color = shown.color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private enum class ActionSet { None, Hide, Show, RetryOrHide }

private fun previewActions(image: PreviewImage, imageHidden: Boolean): ActionSet = when {
    !image.canHide -> ActionSet.None
    imageHidden -> ActionSet.Show
    image == PreviewImage.Failed -> ActionSet.RetryOrHide
    else -> ActionSet.Hide
}

@Composable
private fun PreviewActions(actions: ActionSet, onHide: () -> Unit, onShow: () -> Unit, onRetry: () -> Unit) {
    AnimatedContent(
        targetState = actions,
        transitionSpec = { fadeIn(tween(ACTION_FADE_MS)) togetherWith fadeOut(tween(ACTION_FADE_MS)) },
        contentAlignment = Alignment.CenterEnd,
        label = "preview actions",
    ) { shown ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (shown) {
                ActionSet.None -> Unit
                ActionSet.Hide -> PreviewAction(R.drawable.ic_eye_off, "Hide image", onHide)
                ActionSet.Show -> PreviewAction(R.drawable.ic_eye, "Show image", onShow, emphasized = true)
                ActionSet.RetryOrHide -> {
                    PreviewAction(R.drawable.ic_retry, "Retry", onRetry)
                    PreviewAction(R.drawable.ic_eye_off, "Hide image", onHide)
                }
            }
        }
    }
}

/** Pill button. [emphasized] fills it, for Show image — the way back from a choice the user made. */
@Composable
private fun PreviewAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit, emphasized: Boolean = false) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (emphasized) SynapseDim else Color.Transparent)
            .border(1.dp, if (emphasized) Synapse.copy(alpha = 0.5f) else Edge, shape)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Synapse, modifier = Modifier.size(16.dp))
        Text(
            label,
            style = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
            color = Synapse,
        )
    }
}

private data class CaptionLine(@DrawableRes val icon: Int?, val text: String, val color: Color)

private fun previewCaption(domain: String, phase: PageReadPhase, image: PreviewImage, imageHidden: Boolean): CaptionLine? = when {
    imageHidden && image == PreviewImage.Failed -> CaptionLine(R.drawable.ic_info, "Image hidden · Show image tries loading it again", Muted)
    imageHidden && image is PreviewImage.Ready -> CaptionLine(R.drawable.ic_info, "Image hidden · the link icon is used instead", Muted)
    image is PreviewImage.Ready -> CaptionLine(null, "og:image · $domain · ${image.width}×${image.height}", Muted)
    image == PreviewImage.Failed -> CaptionLine(R.drawable.ic_alert, "Couldn’t load image · retry or hide it", Alarm)
    image == PreviewImage.None && phase != PageReadPhase.Idle && phase != PageReadPhase.ReadingPage ->
        CaptionLine(R.drawable.ic_info, "No image on this page · the link icon is used", Muted)
    else -> null
}

@Composable
private fun PreviewCaption(caption: CaptionLine?) {
    AnimatedContent(
        targetState = caption,
        transitionSpec = { fadeIn(tween(CAPTION_FADE_MS)) togetherWith fadeOut(tween(CAPTION_FADE_MS)) },
        label = "preview caption",
    ) { shown ->
        if (shown != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (shown.icon != null) {
                    Icon(painterResource(shown.icon), contentDescription = null, tint = shown.color, modifier = Modifier.size(13.dp))
                }
                Text(
                    shown.text,
                    style = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
                    color = shown.color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val TITLE_FADE_MS = 150
private const val STATUS_FADE_MS = 150
private const val ACTION_FADE_MS = 150
private const val CAPTION_FADE_MS = 200
