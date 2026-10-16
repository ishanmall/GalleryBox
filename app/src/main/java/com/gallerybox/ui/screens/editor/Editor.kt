@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.ui.screens.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrixColorFilter as AndroidColorMatrixFilter
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.FlipToFront
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tonality
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.gallerybox.data.EditState
import com.gallerybox.data.MediaItem
import com.gallerybox.data.StickerLayer
import com.gallerybox.data.TextLayer
import com.gallerybox.engine.ColorMatrixBuilder
import com.gallerybox.ui.screens.adaptive.DevicePosture
import com.gallerybox.ui.screens.adaptive.WindowWidthSize
import com.gallerybox.ui.screens.adaptive.rememberAdaptiveState
import com.gallerybox.viewmodel.EditorViewModel
import com.gallerybox.viewmodel.FileOperationState
import com.gallerybox.viewmodel.GalleryEvent
import com.gallerybox.viewmodel.GalleryViewModel
import com.gallerybox.viewmodel.SaveMode
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class EditorTab { ADJUST, CROP, LUT, TEXT, STICKER, TRIM }
enum class EditorMode { HOME, TOOL }

@Immutable
data class AdjustTool(
    val name: String,
    val icon: ImageVector,
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val onValueChange: (Float) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    mediaId: Long,
    galleryViewModel: GalleryViewModel = hiltViewModel(),
    editorViewModel: EditorViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val ctx = LocalContext.current

    val mediaMap by galleryViewModel.mediaMap.collectAsState()
    val rawMedia by galleryViewModel.rawMedia.collectAsState()
    val loadedEditorItem by editorViewModel.currentMediaItem.collectAsState()

    val mediaItem = remember(mediaId, mediaMap, rawMedia, loadedEditorItem) {
        mediaMap[mediaId]
            ?: rawMedia.find { it.id == mediaId }
            ?: loadedEditorItem?.takeIf { it.id == mediaId }
    }

    val editState by editorViewModel.currentEditState.collectAsState()
    val previewBitmap by editorViewModel.previewBitmap.collectAsState()
    val fileOpState by editorViewModel.fileOperationState.collectAsState()
    val canUndo by editorViewModel.canUndo.collectAsState()
    val canRedo by editorViewModel.canRedo.collectAsState()

    val isExporting = fileOpState is FileOperationState.Editing
    val isPreviewUpdating by editorViewModel.isPreviewUpdating.collectAsState()
    val isComparing by editorViewModel.isComparing.collectAsState()

    var editorMode by rememberSaveable { mutableStateOf(EditorMode.HOME) }
    var activeTab by rememberSaveable {
        mutableStateOf(if (mediaItem?.isVideo == true) EditorTab.TRIM else EditorTab.ADJUST)
    }

    var selectedLayerId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentPlayerPos by remember { mutableLongStateOf(0L) }
    var videoDuration by remember { mutableLongStateOf(1L) }
    var detectedVideoWidth by remember { mutableIntStateOf(0) }
    var detectedVideoHeight by remember { mutableIntStateOf(0) }
    var gridType by remember { mutableIntStateOf(0) }
    var showExportDialog by remember { mutableStateOf(false) }
    var seekRequest by remember { mutableStateOf<Long?>(null) }

    val lutItems by editorViewModel.lutItems.collectAsState()
    val lutCategories = remember(lutItems) { lutItems.map { it.category }.distinct() }
    var selectedLutCategory by remember { mutableStateOf<String?>(null) }

    val adaptiveState = rememberAdaptiveState()
    val isWideScreen = adaptiveState.widthSize != WindowWidthSize.COMPACT ||
            adaptiveState.isLandscape ||
            adaptiveState.posture == DevicePosture.BOOK_MODE

    LaunchedEffect(lutCategories) {
        if (selectedLutCategory == null && lutCategories.isNotEmpty()) {
            selectedLutCategory = lutCategories.first()
        }
    }

    LaunchedEffect(mediaId, mediaItem?.id) {
        if (mediaItem != null) {
            galleryViewModel.openEditor(mediaItem, editorViewModel)
        } else {
            editorViewModel.initializeEditorFromGallery(
                mediaId = mediaId,
                galleryViewModel = galleryViewModel
            )
        }
    }

    LaunchedEffect(editorViewModel) {
        editorViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.ShowToast -> {
                    Toast.makeText(ctx, event.message, Toast.LENGTH_SHORT).show()
                }
                is GalleryEvent.OperationSuccess -> {
                    galleryViewModel.forceSync()
                    delay(300)
                    galleryViewModel.closeEditor()
                    onBack()
                }
                else -> Unit
            }
        }
    }

    val handleBack = {
        when {
            selectedLayerId != null -> {
                selectedLayerId = null
            }
            editorMode == EditorMode.TOOL -> {
                editorMode = EditorMode.HOME
            }
            else -> {
                galleryViewModel.closeEditor()
                onBack()
            }
        }
    }

    BackHandler(enabled = !isExporting) {
        handleBack()
    }

    if (mediaItem == null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val resolvedMediaWidth = remember(mediaItem, previewBitmap, detectedVideoWidth) {
        when {
            mediaItem.isVideo && detectedVideoWidth > 0 -> detectedVideoWidth
            !mediaItem.isVideo && previewBitmap != null -> previewBitmap!!.width
            mediaItem.width > 0 -> mediaItem.width
            else -> 1080
        }
    }
    val resolvedMediaHeight = remember(mediaItem, previewBitmap, detectedVideoHeight) {
        when {
            mediaItem.isVideo && detectedVideoHeight > 0 -> detectedVideoHeight
            !mediaItem.isVideo && previewBitmap != null -> previewBitmap!!.height
            mediaItem.height > 0 -> mediaItem.height
            else -> 1920
        }
    }

    if (showExportDialog) {
        ExportSettingsDialog(
            isVideo = mediaItem.isVideo,
            srcWidth = resolvedMediaWidth,
            srcHeight = resolvedMediaHeight,
            onDismiss = { showExportDialog = false }
        ) { res, fps, saveMode ->
            val pixels = res.first * res.second
            val bitrate = when {
                pixels <= 1280 * 720 -> 6_000_000
                pixels <= 1920 * 1080 -> 12_000_000
                else -> 40_000_000
            }
            editorViewModel.saveMedia(
                mediaItem = mediaItem,
                saveMode = saveMode,
                targetWidth = res.first,
                targetHeight = res.second,
                targetFps = fps,
                videoBitrate = bitrate,
                exportAsSticker = false,
                galleryViewModel = galleryViewModel
            )
            showExportDialog = false
        }
    }

    // 1. TOP ACTION BAR
    val topBarBlock: @Composable () -> Unit = {
        ModernEditorTopBar(
            onBack = handleBack,
            onUndo = { editorViewModel.undo() },
            onRedo = { editorViewModel.redo() },
            canUndo = canUndo,
            canRedo = canRedo,
            onReset = { editorViewModel.resetEditor() },
            onSave = { showExportDialog = true },
            isExporting = isExporting
        )
    }

    // 2. CONTROLS BLOCK
    val controlsBlock: @Composable () -> Unit = {
        if (!isExporting) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
            ) {
                AnimatedVisibility(selectedLayerId != null) {
                    LayerControlToolbar(
                        onDelete = {
                            selectedLayerId?.let { id ->
                                editorViewModel.removeText(id)
                                editorViewModel.removeSticker(id)
                                selectedLayerId = null
                            }
                        },
                        onDuplicate = {
                            selectedLayerId?.let { id ->
                                editorViewModel.duplicateText(id)
                                editorViewModel.duplicateSticker(id)
                            }
                        },
                        onMoveUp = {
                            selectedLayerId?.let { id ->
                                editorViewModel.moveTextLayer(id, true)
                                editorViewModel.moveStickerLayer(id, true)
                            }
                        }
                    )
                }

                AnimatedContent(
                    targetState = editorMode,
                    transitionSpec = {
                        if (targetState == EditorMode.TOOL) {
                            slideInHorizontally(tween(250)) { it } + fadeIn() togetherWith
                                    slideOutHorizontally(tween(250)) { -it } + fadeOut()
                        } else {
                            slideInHorizontally(tween(250)) { -it } + fadeIn() togetherWith
                                    slideOutHorizontally(tween(250)) { it } + fadeOut()
                        }
                    },
                    label = "mode_panel"
                ) { mode ->
                    if (mode == EditorMode.HOME) {
                        LazyRow(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            val tabs = if (mediaItem.isVideo) {
                                listOf(
                                    EditorTab.TRIM to Icons.Rounded.ContentCut,
                                    EditorTab.CROP to Icons.Rounded.Crop,
                                    EditorTab.ADJUST to Icons.Rounded.Tune,
                                    EditorTab.TEXT to Icons.Rounded.TextFields,
                                    EditorTab.STICKER to Icons.Rounded.EmojiEmotions
                                )
                            } else {
                                listOf(
                                    EditorTab.ADJUST to Icons.Rounded.Tune,
                                    EditorTab.CROP to Icons.Rounded.Crop,
                                    EditorTab.LUT to Icons.Rounded.AutoAwesome,
                                    EditorTab.TEXT to Icons.Rounded.TextFields,
                                    EditorTab.STICKER to Icons.Rounded.EmojiEmotions
                                )
                            }

                            items(tabs) { (tb, ic) ->
                                TabItem(
                                    lbl = tb.name.lowercase().replaceFirstChar { it.uppercase() },
                                    ic = ic,
                                    sel = false
                                ) {
                                    activeTab = tb
                                    selectedLayerId = null
                                    editorMode = EditorMode.TOOL
                                }
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { editorMode = EditorMode.HOME }) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = "Back",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = activeTab.name.lowercase().replaceFirstChar { it.uppercase() },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }

                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight()
                                    .padding(bottom = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                when (activeTab) {
                                    EditorTab.ADJUST -> AdjustToolPanel(
                                        s = editState,
                                        isVideo = mediaItem.isVideo,
                                        onStartAdjust = { editorViewModel.beginGesture() },
                                        onEndAdjust = { editorViewModel.endGesture() }
                                    ) { editorViewModel.updateEditState(it) }

                                    EditorTab.CROP -> CropToolPanel(
                                        vm = editorViewModel,
                                        mediaWidth = resolvedMediaWidth.toFloat(),
                                        mediaHeight = resolvedMediaHeight.toFloat(),
                                        grid = gridType
                                    ) { gridType = it }

                                    EditorTab.LUT -> FilterToolPanel(
                                        vm = editorViewModel,
                                        selCat = selectedLutCategory,
                                        cats = lutCategories
                                    ) { selectedLutCategory = it }

                                    EditorTab.TEXT -> TextToolPanel(editorViewModel)

                                    EditorTab.STICKER -> StickerToolPanel(editorViewModel)

                                    EditorTab.TRIM -> TrimToolPanel(
                                        vm = editorViewModel,
                                        s = editState,
                                        exoDuration = videoDuration,
                                        currentPos = currentPlayerPos,
                                        itemUri = mediaItem.uri.toString(),
                                        galleryViewModel = galleryViewModel
                                    ) { seekRequest = it }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 3. LIVE PREVIEW CANVAS (Non-stretching Crop & Live Adjustments for Photo and Video)
    val previewBlock: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            var sc by remember { mutableFloatStateOf(1f) }
            var ox by remember { mutableFloatStateOf(0f) }
            var oy by remember { mutableFloatStateOf(0f) }

            val isCropping = activeTab == EditorTab.CROP && editorMode == EditorMode.TOOL
            val isOverlaySelected = selectedLayerId != null

            val cropRect = editState.cropRect ?: RectF(0f, 0f, 1f, 1f)
            val hasActiveCrop = !isCropping && !isComparing &&
                    (cropRect.left > 0.001f || cropRect.top > 0.001f || cropRect.right < 0.999f || cropRect.bottom < 0.999f)

            val rawW = resolvedMediaWidth.toFloat().coerceAtLeast(1f)
            val rawH = resolvedMediaHeight.toFloat().coerceAtLeast(1f)

            // When not in the Crop tab, the outer viewport adopts the cropped aspect ratio so nothing ever stretches!
            val effectiveAspect = if (hasActiveCrop) {
                ((rawW * cropRect.width().coerceAtLeast(0.05f)) / (rawH * cropRect.height().coerceAtLeast(0.05f)))
                    .takeIf { it > 0f && !it.isNaN() } ?: (rawW / rawH)
            } else {
                (rawW / rawH).takeIf { it > 0f && !it.isNaN() } ?: 1f
            }

            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .pointerInput(isCropping, isComparing, isOverlaySelected) {
                        if (!isCropping && !isComparing && !isOverlaySelected) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                sc = (sc * zoom).coerceIn(1f, 5f)
                                val maxOff = (sc - 1f) * 1500f
                                ox = (ox + pan.x).coerceIn(-maxOff, maxOff)
                                oy = (oy + pan.y).coerceIn(-maxOff, maxOff)
                            }
                        }
                    }
                    .pointerInput(isCropping, isComparing, isOverlaySelected) {
                        if (!isCropping && !isComparing && !isOverlaySelected) {
                            detectTapGestures(
                                onDoubleTap = {
                                    sc = 1f
                                    ox = 0f
                                    oy = 0f
                                },
                                onTap = { selectedLayerId = null }
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val maxWPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val maxHPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

                var fitW = maxWPx
                var fitH = maxWPx / effectiveAspect
                if (fitH > maxHPx) {
                    fitH = maxHPx
                    fitW = maxHPx * effectiveAspect
                }

                val totalRotation = if (!isComparing) {
                    editState.rotationDegrees + editState.straightenDegrees
                } else {
                    0f
                }
                val angleRad = Math.toRadians(totalRotation.toDouble())
                val cosA = abs(cos(angleRad)).toFloat()
                val sinA = abs(sin(angleRad)).toFloat()

                val rotatedW = (fitW * cosA + fitH * sinA).coerceAtLeast(1f)
                val rotatedH = (fitW * sinA + fitH * cosA).coerceAtLeast(1f)
                val fitScale = min(maxWPx / rotatedW, maxHPx / rotatedH).coerceAtMost(1f)

                val density = LocalDensity.current

                Box(
                    Modifier
                        .width(with(density) { fitW.toDp() })
                        .height(with(density) { fitH.toDp() })
                        .graphicsLayer(
                            scaleX = sc * fitScale * (if (!isComparing && editState.flipHorizontal) -1f else 1f),
                            scaleY = sc * fitScale * (if (!isComparing && editState.flipVertical) -1f else 1f),
                            rotationZ = totalRotation,
                            translationX = ox,
                            translationY = oy,
                            clip = hasActiveCrop
                        )
                        .clipToBounds()
                ) {
                    if (mediaItem.isVideo) {
                        EditorVideoPreview(
                            item = mediaItem,
                            state = editState,
                            isComp = isComparing,
                            isCropping = isCropping,
                            viewportWidthPx = fitW,
                            viewportHeightPx = fitH,
                            seekRequest = seekRequest,
                            onVideoSizeDetected = { vw, vh ->
                                if (vw > 0 && vh > 0) {
                                    detectedVideoWidth = vw
                                    detectedVideoHeight = vh
                                }
                            },
                            onDurationReady = { dur ->
                                videoDuration = dur.coerceAtLeast(1L)
                            },
                            onPos = { currentPlayerPos = it }
                        )
                    } else {
                        val displayBitmap = previewBitmap
                        if (displayBitmap != null) {
                            Canvas(Modifier.fillMaxSize()) {
                                val bmpW = displayBitmap.width
                                val bmpH = displayBitmap.height
                                if (hasActiveCrop) {
                                    val srcLeft = (cropRect.left * bmpW).roundToInt().coerceIn(0, bmpW - 1)
                                    val srcTop = (cropRect.top * bmpH).roundToInt().coerceIn(0, bmpH - 1)
                                    val srcW = (cropRect.width() * bmpW).roundToInt().coerceIn(1, bmpW - srcLeft)
                                    val srcH = (cropRect.height() * bmpH).roundToInt().coerceIn(1, bmpH - srcTop)
                                    drawImage(
                                        image = displayBitmap.asImageBitmap(),
                                        srcOffset = IntOffset(srcLeft, srcTop),
                                        srcSize = IntSize(srcW, srcH),
                                        dstOffset = IntOffset.Zero,
                                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                                    )
                                } else {
                                    drawImage(
                                        image = displayBitmap.asImageBitmap(),
                                        srcOffset = IntOffset.Zero,
                                        srcSize = IntSize(bmpW, bmpH),
                                        dstOffset = IntOffset.Zero,
                                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                                    )
                                }
                            }
                        } else {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(mediaItem.uri).build(),
                                contentDescription = "Original",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }

                    // Interactive Text and Sticker Overlays
                    if (!isComparing) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                            val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)

                            StickerOverlay(
                                state = editState,
                                parentWidth = w,
                                parentHeight = h,
                                selId = selectedLayerId,
                                onSel = { id -> selectedLayerId = id },
                                onDragStart = { editorViewModel.beginGesture() },
                                onDragEnd = { editorViewModel.endGesture() },
                                up = { id, updater ->
                                    editorViewModel.updateEditState { s ->
                                        s.copy(stickers = s.stickers.map { if (it.id == id) updater(it) else it })
                                    }
                                }
                            )

                            TextOverlay(
                                state = editState,
                                parentWidth = w,
                                parentHeight = h,
                                selId = selectedLayerId,
                                onSel = { id -> selectedLayerId = id },
                                onDragStart = { editorViewModel.beginGesture() },
                                onDragEnd = { editorViewModel.endGesture() },
                                up = { id, updater ->
                                    editorViewModel.updateEditState { s ->
                                        s.copy(textLayers = s.textLayers.map { if (it.id == id) updater(it) else it })
                                    }
                                }
                            )
                        }
                    }

                    // Interactive Crop Overlay
                    if (isCropping && !isComparing) {
                        InteractiveCropOverlay(
                            cCrop = editState.cropRect,
                            cAsp = editState.aspectRatio,
                            grid = gridType
                        ) {
                            editorViewModel.updateCropRect(it)
                        }
                    }
                }

                if (isPreviewUpdating && !isComparing && !mediaItem.isVideo) {
                    CircularProgressIndicator(
                        Modifier
                            .align(Alignment.Center)
                            .size(36.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                }
            }

            // Hold-to-Compare Button
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isComparing) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                    )
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            editorViewModel.setComparing(true)
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                            editorViewModel.setComparing(false)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Compare,
                    contentDescription = "Compare",
                    tint = if (isComparing) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // FINAL ASSEMBLY
    if (isWideScreen) {
        Row(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                topBarBlock()
                previewBlock(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            Box(
                Modifier
                    .width(360.dp)
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                controlsBlock()
            }
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { topBarBlock() },
            bottomBar = { controlsBlock() }
        ) { padding ->
            previewBlock(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }

    if (isExporting) {
        SavingOverlay(
            prg = (fileOpState as? FileOperationState.Editing)?.progress ?: 0f,
            onCancel = { editorViewModel.cancelCurrentOperation() }
        )
    }
}

@Composable
fun LayerControlToolbar(
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onMoveUp: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onMoveUp) {
            Icon(
                Icons.Rounded.FlipToFront,
                contentDescription = "Bring Forward",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDuplicate) {
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = "Duplicate",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun ModernEditorTopBar(
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean = true,
    canRedo: Boolean = true,
    onReset: () -> Unit,
    onSave: () -> Unit,
    isExporting: Boolean
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onBack, enabled = !isExporting) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Cancel",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Row {
            IconButton(onClick = onUndo, enabled = !isExporting && canUndo) {
                Icon(
                    Icons.AutoMirrored.Rounded.Undo,
                    contentDescription = "Undo",
                    tint = if (canUndo) MaterialTheme.colorScheme.onBackground
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                )
            }
            IconButton(onClick = onRedo, enabled = !isExporting && canRedo) {
                Icon(
                    Icons.AutoMirrored.Rounded.Redo,
                    contentDescription = "Redo",
                    tint = if (canRedo) MaterialTheme.colorScheme.onBackground
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onReset, enabled = !isExporting) {
                Text("Reset", color = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onSave,
                enabled = !isExporting,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                Text("Save", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/**
 * Uses a TextureView + Hardware Layer ColorMatrix filter so live video preview supports:
 * - Real-time Brightness, Contrast, Saturation, Exposure, Temp, and Tint at 60fps
 * - Non-stretching proportional crop preview (because the parent viewport matches the cropped aspect ratio)
 * - Instant Volume & Mute changes
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun EditorVideoPreview(
    item: MediaItem,
    state: EditState,
    isComp: Boolean,
    isCropping: Boolean,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    seekRequest: Long?,
    onVideoSizeDetected: (Int, Int) -> Unit,
    onDurationReady: (Long) -> Unit,
    onPos: (Long) -> Unit
) {
    val ctx = LocalContext.current
    var ctrlVis by remember { mutableStateOf(true) }
    var isPlay by remember { mutableStateOf(false) }

    val exo = remember {
        ExoPlayer.Builder(ctx).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            setSeekParameters(SeekParameters.EXACT)
        }
    }

    LaunchedEffect(item.uri) {
        exo.setMediaItem(ExoMediaItem.fromUri(item.uri))
        exo.prepare()
        exo.playWhenReady = true
        exo.play()
    }

    LaunchedEffect(seekRequest) {
        seekRequest?.let {
            runCatching { exo.pause() }
            runCatching { exo.seekTo(it) }
            onPos(it)
            ctrlVis = true
        }
    }

    // Live Audio Volume & Mute update
    LaunchedEffect(state.videoVolume, state.isMuted, isComp) {
        val normalizedVol = if (state.videoVolume > 2f) {
            (state.videoVolume / 100f).coerceIn(0f, 1f)
        } else {
            state.videoVolume.coerceIn(0f, 1f)
        }
        exo.volume = if (state.isMuted) 0f else normalizedVol
    }

    // Trim loop & playhead position tracker
    LaunchedEffect(state.trimStartMs, state.trimEndMs, isPlay) {
        while (isActive) {
            if (isPlay) {
                val pos = try {
                    exo.currentPosition
                } catch (_: IllegalStateException) {
                    return@LaunchedEffect
                }
                onPos(pos)

                if (state.trimEndMs > 0L && pos >= state.trimEndMs - 50L) {
                    try {
                        exo.seekTo(state.trimStartMs)
                    } catch (_: IllegalStateException) {
                        return@LaunchedEffect
                    }
                    if (!isPlay) onPos(state.trimStartMs)
                }

                if (pos < state.trimStartMs - 50L) {
                    try {
                        exo.seekTo(state.trimStartMs)
                    } catch (_: IllegalStateException) {
                        return@LaunchedEffect
                    }
                    if (!isPlay) onPos(state.trimStartMs)
                }
                delay(30)
            } else {
                delay(150)
            }
        }
    }

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) {
                isPlay = p
                if (p) ctrlVis = false
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    onVideoSizeDetected(videoSize.width, videoSize.height)
                }
            }

            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_READY && exo.duration > 0) {
                    onDurationReady(exo.duration)
                } else if (s == Player.STATE_ENDED) {
                    exo.seekTo(state.trimStartMs.coerceAtLeast(0L))
                    exo.play()
                }
            }

            override fun onPositionDiscontinuity(
                o: Player.PositionInfo,
                n: Player.PositionInfo,
                r: Int
            ) {
                onPos(n.positionMs)
            }
        }
        exo.addListener(l)
        onDispose {
            exo.removeListener(l)
            exo.release()
        }
    }

    val cropRect = state.cropRect ?: RectF(0f, 0f, 1f, 1f)
    val cw = cropRect.width().coerceIn(0.05f, 1f)
    val ch = cropRect.height().coerceIn(0.05f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) { detectTapGestures(onTap = { ctrlVis = !ctrlVis }) },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    val textureView = TextureView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                    addView(textureView)
                    exo.setVideoTextureView(textureView)
                    tag = textureView
                }
            },
            update = { container ->
                val textureView = container.tag as? TextureView
                if (textureView != null) {
                    // Apply real-time hardware ColorMatrix filter for Brightness, Contrast, Saturation, Exposure, Temp, Tint
                    if (isComp) {
                        container.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    } else {
                        val cm = ColorMatrixBuilder.buildAndroidColorMatrix(state)
                        val layerPaint = AndroidPaint().apply {
                            colorFilter = AndroidColorMatrixFilter(cm)
                        }
                        container.setLayerType(View.LAYER_TYPE_HARDWARE, layerPaint)
                    }

                    // Non-stretching Crop positioning:
                    // Because the outer container already has the cropped aspect ratio (fitW x fitH),
                    // the full video inside is sized to (fitW / cw) x (fitH / ch), preserving 100% original aspect ratio!
                    if (!isCropping && !isComp && (cw < 0.999f || ch < 0.999f)) {
                        val fullW = viewportWidthPx / cw
                        val fullH = viewportHeightPx / ch
                        textureView.scaleX = 1f / cw
                        textureView.scaleY = 1f / ch
                        textureView.translationX = (0.5f - cropRect.centerX()) * fullW
                        textureView.translationY = (0.5f - cropRect.centerY()) * fullH
                    } else {
                        textureView.scaleX = 1f
                        textureView.scaleY = 1f
                        textureView.translationX = 0f
                        textureView.translationY = 0f
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(ctrlVis || !isPlay, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .clickable {
                            onPos(runCatching { exo.currentPosition }.getOrDefault(0L))
                            if (isPlay) {
                                runCatching { exo.pause() }
                            } else {
                                runCatching { exo.play() }
                            }
                        }
                        .align(Alignment.Center),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Icon(
                        if (isPlay) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TabItem(lbl: String, ic: ImageVector, sel: Boolean, onClk: () -> Unit) {
    val c = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .clickable(onClick = onClk)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(ic, contentDescription = lbl, tint = c, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            lbl,
            fontSize = 11.sp,
            color = c,
            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun SavingOverlay(prg: Float, onCancel: () -> Unit = {}) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable(enabled = false, onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                progress = { prg.coerceIn(0f, 1f) },
                modifier = Modifier.size(64.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 6.dp
            )
            Spacer(Modifier.height(16.dp))
            Text("Processing...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("${(prg * 100f).roundToInt().coerceIn(0, 100)}%", color = Color.LightGray, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = onCancel,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
            ) {
                Text("Cancel", color = Color.White)
            }
        }
    }
}

/**
 * Interactive Crop Overlay — automatically snaps to selected aspect ratio (`1:1`, `4:3`, `3:4`, `16:9`, `9:16`)
 * and enforces the ratio while dragging handles.
 */
@Composable
fun InteractiveCropOverlay(
    cCrop: RectF?,
    cAsp: Float?,
    grid: Int,
    onCrop: (RectF) -> Unit
) {
    var crop by remember(cCrop, cAsp) {
        mutableStateOf(cCrop?.let { RectF(it) } ?: RectF(0.06f, 0.06f, 0.94f, 0.94f))
    }

    val d = LocalDensity.current
    val hap = LocalHapticFeedback.current

    val ho = with(d) { 16.dp.toPx() }
    val hi = with(d) { 10.dp.toPx() }
    val eh = with(d) { 8.dp.toPx() }
    val gw = with(d) { 1.5.dp.toPx() }
    val sl = with(d) { 28.dp.toPx() }

    var ah by remember { mutableIntStateOf(-1) }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(cAsp, cCrop) {
                    detectDragGestures(
                        onDragStart = { off ->
                            val nx = off.x / size.width
                            val ny = off.y / size.height
                            val sx = sl / size.width
                            val sy = sl / size.height
                            val l = crop.left
                            val t = crop.top
                            val r = crop.right
                            val b = crop.bottom
                            ah = when {
                                abs(nx - l) < sx && abs(ny - t) < sy -> 0
                                abs(nx - r) < sx && abs(ny - t) < sy -> 1
                                abs(nx - l) < sx && abs(ny - b) < sy -> 2
                                abs(nx - r) < sx && abs(ny - b) < sy -> 3
                                abs(nx - l) < sx && ny in (t - sy)..(b + sy) -> 4
                                abs(ny - t) < sy && nx in (l - sx)..(r + sx) -> 5
                                abs(nx - r) < sx && ny in (t - sy)..(b + sy) -> 6
                                abs(ny - b) < sy && nx in (l - sx)..(r + sx) -> 7
                                nx in l..r && ny in t..b -> 8
                                else -> -1
                            }
                            if (ah != -1) {
                                hap.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = { ah = -1 },
                        onDragCancel = { ah = -1 }
                    ) { ch, dr ->
                        ch.consume()
                        if (ah == -1) return@detectDragGestures

                        val dx = dr.x / size.width
                        val dy = dr.y / size.height
                        val nr = RectF(crop)

                        if (cAsp != null && ah in 0..7) {
                            val rel = cAsp / (size.width / size.height)
                            val cx = (nr.left + nr.right) / 2f
                            val cy = (nr.top + nr.bottom) / 2f

                            val deltaScale = when (ah) {
                                0 -> (-dx - dy) / 2f
                                1 -> (dx - dy) / 2f
                                2 -> (-dx + dy) / 2f
                                3 -> (dx + dy) / 2f
                                4 -> -dx
                                6 -> dx
                                5 -> -dy * rel
                                7 -> dy * rel
                                else -> 0f
                            }

                            var newW = (nr.width() + deltaScale).coerceIn(0.1f, 1f)
                            var newH = newW / rel
                            if (newH > 1f) {
                                newH = 1f
                                newW = newH * rel
                            }
                            if (newH < 0.1f) {
                                newH = 0.1f
                                newW = newH * rel
                            }

                            val clampedCx = cx.coerceIn(newW / 2f, 1f - newW / 2f)
                            val clampedCy = cy.coerceIn(newH / 2f, 1f - newH / 2f)
                            nr.set(
                                clampedCx - newW / 2f,
                                clampedCy - newH / 2f,
                                clampedCx + newW / 2f,
                                clampedCy + newH / 2f
                            )
                        } else {
                            when (ah) {
                                0 -> {
                                    nr.left = (nr.left + dx).coerceIn(0f, nr.right - 0.08f)
                                    nr.top = (nr.top + dy).coerceIn(0f, nr.bottom - 0.08f)
                                }
                                1 -> {
                                    nr.right = (nr.right + dx).coerceIn(nr.left + 0.08f, 1f)
                                    nr.top = (nr.top + dy).coerceIn(0f, nr.bottom - 0.08f)
                                }
                                2 -> {
                                    nr.left = (nr.left + dx).coerceIn(0f, nr.right - 0.08f)
                                    nr.bottom = (nr.bottom + dy).coerceIn(nr.top + 0.08f, 1f)
                                }
                                3 -> {
                                    nr.right = (nr.right + dx).coerceIn(nr.left + 0.08f, 1f)
                                    nr.bottom = (nr.bottom + dy).coerceIn(nr.top + 0.08f, 1f)
                                }
                                4 -> nr.left = (nr.left + dx).coerceIn(0f, nr.right - 0.08f)
                                5 -> nr.top = (nr.top + dy).coerceIn(0f, nr.bottom - 0.08f)
                                6 -> nr.right = (nr.right + dx).coerceIn(nr.left + 0.08f, 1f)
                                7 -> nr.bottom = (nr.bottom + dy).coerceIn(nr.top + 0.08f, 1f)
                                8 -> {
                                    val w = nr.width()
                                    val h = nr.height()
                                    val nl = (nr.left + dx).coerceIn(0f, 1f - w)
                                    val nt = (nr.top + dy).coerceIn(0f, 1f - h)
                                    nr.set(nl, nt, nl + w, nt + h)
                                }
                            }
                        }
                        crop = nr
                        onCrop(nr)
                    }
                }
        ) {
            val rw = size.width * crop.width()
            val rh = size.height * crop.height()
            val l = size.width * crop.left
            val t = size.height * crop.top
            val dim = Color.Black.copy(alpha = 0.72f)
            val gc = Color.White.copy(alpha = 0.6f)

            drawRect(dim, Offset.Zero, Size(size.width, t))
            drawRect(dim, Offset(0f, t + rh), Size(size.width, size.height - (t + rh)))
            drawRect(dim, Offset(0f, t), Size(l, rh))
            drawRect(dim, Offset(l + rw, t), Size(size.width - (l + rw), rh))

            when (grid) {
                0 -> {
                    listOf(l + rw / 3f to t, l + rw / 3f * 2f to t).forEach {
                        drawLine(gc, Offset(it.first, it.second), Offset(it.first, it.second + rh), gw)
                    }
                    listOf(l to t + rh / 3f, l to t + rh / 3f * 2f).forEach {
                        drawLine(gc, Offset(it.first, it.second), Offset(it.first + rw, it.second), gw)
                    }
                }
                1 -> {
                    listOf(l + rw * 0.25f to t, l + rw * 0.5f to t, l + rw * 0.75f to t).forEach {
                        drawLine(gc, Offset(it.first, it.second), Offset(it.first, it.second + rh), gw)
                    }
                    listOf(l to t + rh * 0.25f, l to t + rh * 0.5f, l to t + rh * 0.75f).forEach {
                        drawLine(gc, Offset(it.first, it.second), Offset(it.first + rw, it.second), gw)
                    }
                }
                2 -> {
                    listOf(l + rw * 0.382f to t, l + rw * 0.618f to t).forEach {
                        drawLine(gc, Offset(it.first, it.second), Offset(it.first, it.second + rh), gw)
                    }
                    listOf(l to t + rh * 0.382f, l to t + rh * 0.618f).forEach {
                        drawLine(gc, Offset(it.first, it.second), Offset(it.first + rw, it.second), gw)
                    }
                }
                3 -> {
                    drawLine(gc, Offset(l, t), Offset(l + rw, t + rh), gw)
                    drawLine(gc, Offset(l + rw, t), Offset(l, t + rh), gw)
                }
            }

            drawRect(Color.White, Offset(l, t), Size(rw, rh), style = Stroke(2.5.dp.toPx()))

            listOf(
                Offset(l, t + rh / 2f),
                Offset(l + rw / 2f, t),
                Offset(l + rw, t + rh / 2f),
                Offset(l + rw / 2f, t + rh)
            ).forEachIndexed { i, o ->
                val act = ah == i + 4
                drawCircle(if (act) Color(0xFF4CAF50) else Color.White, if (act) eh * 1.3f else eh, o)
                drawCircle(Color.Black, eh * 0.6f, o)
            }

            listOf(
                Offset(l, t),
                Offset(l + rw, t),
                Offset(l, t + rh),
                Offset(l + rw, t + rh)
            ).forEachIndexed { i, o ->
                drawCircle(if (ah == i) Color(0xFF4CAF50) else Color.White, if (ah == i) ho * 1.2f else ho, o)
                drawCircle(Color.Black, hi, o)
            }
        }
        if (cAsp != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    when (cAsp) {
                        1f -> "1:1"
                        4f / 3f -> "4:3"
                        3f / 4f -> "3:4"
                        16f / 9f -> "16:9"
                        9f / 16f -> "9:16"
                        else -> ""
                    },
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * THE ADJUSTMENTS MENU (Full support for both Photo and Video)
 */
@Composable
fun AdjustToolPanel(
    s: EditState,
    isVideo: Boolean,
    onStartAdjust: () -> Unit = {},
    onEndAdjust: () -> Unit = {},
    up: ((EditState) -> EditState) -> Unit
) {
    val hap = LocalHapticFeedback.current
    var unmutedVol by rememberSaveable { mutableFloatStateOf(1f) }

    val normalizedVideoVol = if (s.videoVolume > 2f) {
        (s.videoVolume / 100f).coerceIn(0f, 1f)
    } else {
        s.videoVolume.coerceIn(0f, 1f)
    }

    val photoTools = listOf(
        AdjustTool("Brightness", Icons.Rounded.BrightnessMedium, s.brightness, -1f..1f) { v -> up { it.copy(brightness = v) } },
        AdjustTool("Contrast", Icons.Rounded.Contrast, s.contrast, 0f..2f) { v -> up { it.copy(contrast = v) } },
        AdjustTool("Saturation", Icons.Rounded.ColorLens, s.saturation, 0f..2f) { v -> up { it.copy(saturation = v) } },
        AdjustTool("Exposure", Icons.Rounded.Exposure, s.exposure, -2f..2f) { v -> up { it.copy(exposure = v) } },
        AdjustTool("Highlights", Icons.Rounded.Highlight, s.highlights, -1f..1f) { v -> up { it.copy(highlights = v) } },
        AdjustTool("Shadows", Icons.Rounded.Tonality, s.shadows, -1f..1f) { v -> up { it.copy(shadows = v) } },
        AdjustTool("Temp", Icons.Rounded.Thermostat, s.temperature, -1f..1f) { v -> up { it.copy(temperature = v) } },
        AdjustTool("Tint", Icons.Rounded.Palette, s.tint, -1f..1f) { v -> up { it.copy(tint = v) } }
    )

    val videoTools = listOf(
        AdjustTool(
            name = "Volume",
            icon = if (s.isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            value = if (s.isMuted) 0f else normalizedVideoVol,
            range = 0f..1f
        ) { v ->
            up { it.copy(videoVolume = v.coerceIn(0f, 1f), isMuted = v == 0f) }
        },
        AdjustTool("Brightness", Icons.Rounded.BrightnessMedium, s.brightness, -1f..1f) { v -> up { it.copy(brightness = v) } },
        AdjustTool("Contrast", Icons.Rounded.Contrast, s.contrast, 0f..2f) { v -> up { it.copy(contrast = v) } },
        AdjustTool("Saturation", Icons.Rounded.ColorLens, s.saturation, 0f..2f) { v -> up { it.copy(saturation = v) } },
        AdjustTool("Exposure", Icons.Rounded.Exposure, s.exposure, -2f..2f) { v -> up { it.copy(exposure = v) } },
        AdjustTool("Temp", Icons.Rounded.Thermostat, s.temperature, -1f..1f) { v -> up { it.copy(temperature = v) } },
        AdjustTool("Tint", Icons.Rounded.Palette, s.tint, -1f..1f) { v -> up { it.copy(tint = v) } }
    )

    val tools = if (isVideo) videoTools else photoTools

    var sel by rememberSaveable { mutableIntStateOf(0) }
    val a = tools[sel.coerceIn(0, tools.lastIndex)]
    val dv = when (a.name) {
        "Contrast", "Saturation" -> ((a.value - 1f) * 100f).roundToInt()
        "Exposure" -> ((a.value / 2f) * 100f).roundToInt()
        else -> (a.value * 100f).roundToInt()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                a.name,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (a.name == "Volume") {
                    IconButton(onClick = {
                        if (s.isMuted) {
                            up { it.copy(isMuted = false, videoVolume = unmutedVol.coerceIn(0.1f, 1f)) }
                        } else {
                            unmutedVol = normalizedVideoVol.takeIf { it > 0f } ?: 1f
                            up { it.copy(isMuted = true, videoVolume = 0f) }
                        }
                    }) {
                        Icon(
                            if (s.isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                            contentDescription = "Mute",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    if (dv > 0 && a.name != "Volume") "+$dv" else "$dv",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }

        Slider(
            value = a.value,
            onValueChange = {
                onStartAdjust()
                a.onValueChange(it)
            },
            onValueChangeFinished = {
                onEndAdjust()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            valueRange = a.range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        LazyRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            items(tools.size) { i ->
                val tl = tools[i]
                val isSel = i == sel
                Column(
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    sel = i
                                    hap.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                onDoubleTap = {
                                    sel = i
                                    tl.onValueChange(if (tl.name in listOf("Contrast", "Saturation", "Volume")) 1f else 0f)
                                    hap.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            )
                        }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            tl.icon,
                            contentDescription = tl.name,
                            tint = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tl.name,
                        fontSize = 11.sp,
                        color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun FilterToolPanel(
    vm: EditorViewModel,
    selCat: String?,
    cats: List<String>,
    onCatSelect: (String) -> Unit
) {
    val luts by vm.lutItems.collectAsState()
    val s by vm.currentEditState.collectAsState()
    val catLuts = remember(luts, selCat) { luts.filter { it.category == selCat } }

    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(s.filterId != null) {
            Slider(
                value = s.lutIntensity,
                onValueChange = { vm.setLutIntensity(it) },
                valueRange = 0f..1f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }

        if (cats.isNotEmpty()) {
            ScrollableTabRow(
                selectedTabIndex = cats.indexOf(selCat).coerceAtLeast(0),
                containerColor = Color.Transparent,
                divider = {},
                indicator = {},
                edgePadding = 16.dp
            ) {
                cats.forEach { c ->
                    Tab(
                        selected = selCat == c,
                        onClick = { onCatSelect(c) },
                        text = {
                            Text(
                                c,
                                color = if (selCat == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selCat == c) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }

        LazyRow(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                Column(
                    Modifier
                        .width(80.dp)
                        .clickable { vm.clearLut() },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                if (s.filterId == null) 2.dp else 0.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Block, contentDescription = "None", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("None", maxLines = 1, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            items(catLuts) { l ->
                Column(
                    Modifier
                        .width(80.dp)
                        .clickable { vm.applyLut(l) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (l.thumbnail != null) {
                        AsyncImage(
                            model = l.thumbnail,
                            contentDescription = l.name,
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    if (s.filterId == l.name) 2.dp else 0.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(16.dp)
                                ),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    if (s.filterId == l.name) 2.dp else 0.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(16.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("LUT", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        l.name,
                        maxLines = 1,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (s.filterId == l.name) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * THE CROP & ROTATE SETTINGS MENU (Passes mediaWidth & mediaHeight so Aspect Ratio Chips snap immediately!)
 */
@Composable
fun CropToolPanel(
    vm: EditorViewModel,
    mediaWidth: Float,
    mediaHeight: Float,
    grid: Int,
    onGrid: (Int) -> Unit
) {
    val asp by vm.aspectRatio.collectAsState()
    val s by vm.currentEditState.collectAsState()
    val rs: List<Pair<String, Float?>> = listOf(
        "Free" to null,
        "1:1" to 1f,
        "4:3" to 4f / 3f,
        "3:4" to 3f / 4f,
        "16:9" to 16f / 9f,
        "9:16" to 9f / 16f
    )

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.rotateLeft() }) {
                Icon(Icons.AutoMirrored.Rounded.RotateLeft, contentDescription = "Rot L", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { vm.rotateRight() }) {
                Icon(Icons.AutoMirrored.Rounded.RotateRight, contentDescription = "Rot R", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { vm.toggleFlipHorizontal() }) {
                Icon(Icons.Rounded.Flip, contentDescription = "Flip H", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { vm.toggleFlipVertical() }) {
                Icon(
                    Icons.Rounded.Flip,
                    contentDescription = "Flip V",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.rotate(90f)
                )
            }
            IconButton(onClick = { onGrid((grid + 1) % 4) }) {
                Icon(Icons.Rounded.GridOn, contentDescription = "Grid", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { vm.resetCrop() }) {
                Icon(Icons.Rounded.SettingsBackupRestore, contentDescription = "Reset", tint = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.ScreenRotation,
                contentDescription = "Straighten",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(16.dp))
            Slider(
                value = s.straightenDegrees,
                onValueChange = { angle ->
                    vm.beginGesture()
                    vm.updateEditState { e -> e.copy(straightenDegrees = angle) }
                },
                onValueChangeFinished = { vm.endGesture() },
                modifier = Modifier.weight(1f),
                valueRange = -45f..45f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
            Text(
                "${s.straightenDegrees.roundToInt()}°",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                modifier = Modifier
                    .width(44.dp)
                    .padding(start = 8.dp)
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.RotateRight,
                contentDescription = "Rotate",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(16.dp))
            Slider(
                value = s.rotationDegrees,
                onValueChange = { angle ->
                    vm.beginGesture()
                    vm.updateEditState { e -> e.copy(rotationDegrees = angle) }
                },
                onValueChangeFinished = { vm.endGesture() },
                modifier = Modifier.weight(1f),
                valueRange = 0f..360f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
            Text(
                "${s.rotationDegrees.roundToInt()}°",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                modifier = Modifier
                    .width(44.dp)
                    .padding(start = 8.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        LazyRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 24.dp)
        ) {
            items(rs) { (l, r) ->
                val isSelected = if (r == null) asp == null else (asp != null && abs(asp!! - r) < 0.01f)
                FilterChip(
                    selected = isSelected,
                    onClick = { vm.setAspectRatio(r, mediaWidth, mediaHeight) },
                    label = { Text(l) },
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    }
}

@Composable
fun TextToolPanel(vm: EditorViewModel) {
    var t by remember { mutableStateOf("") }
    val cs = listOf(Color.White, Color.Black, Color.Red, Color.Yellow, Color.Green, Color.Blue, Color.Cyan, Color.Magenta)
    var sc by remember { mutableStateOf(Color.White) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = t,
            onValueChange = { t = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            placeholder = { Text("Type something...") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        LazyRow(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            items(cs) { c ->
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(
                            2.dp,
                            if (sc == c) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                            CircleShape
                        )
                        .clickable { sc = c }
                )
            }
        }

        Button(
            onClick = {
                if (t.isNotBlank()) {
                    vm.addText(t.trim(), sc.toArgb(), 50f)
                    t = ""
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("Add Text", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun StickerToolPanel(vm: EditorViewModel) {
    val sts by vm.stickerItems.collectAsState()

    val cats = remember(sts) { sts.map { it.category }.distinct() }
    var selCat by remember { mutableStateOf<String?>(null) }

    val ctx = LocalContext.current
    val il = remember {
        ImageLoader.Builder(ctx)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }

    LaunchedEffect(cats) {
        if (selCat == null && cats.isNotEmpty()) selCat = cats.first()
    }
    val cSts = remember(sts, selCat) { sts.filter { it.category == selCat } }

    Column(Modifier.fillMaxWidth()) {
        if (cats.isNotEmpty()) {
            ScrollableTabRow(
                selectedTabIndex = cats.indexOf(selCat).coerceAtLeast(0),
                containerColor = Color.Transparent,
                divider = {},
                indicator = {},
                edgePadding = 16.dp
            ) {
                cats.forEach { c ->
                    Tab(
                        selected = selCat == c,
                        onClick = { selCat = c },
                        text = {
                            Text(
                                c,
                                color = if (selCat == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selCat == c) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }

        if (selCat == "Emoji") {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(56.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(cSts) { st ->
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .clickable { vm.addSticker("", st.emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(st.emoji, fontSize = 28.sp)
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(80.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(cSts) { st ->
                    AsyncImage(
                        model = ImageRequest.Builder(ctx)
                            .data("file:///android_asset/${st.assetPath}")
                            .crossfade(true)
                            .build(),
                        contentDescription = st.name,
                        imageLoader = il,
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .clickable { vm.addSticker(st.assetPath) }
                            .padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun VideoThumbnailRow(uri: String, durationMs: Long, modifier: Modifier) {
    var frames by remember(uri) { mutableStateOf<List<Bitmap>>(emptyList()) }
    val ctx = LocalContext.current
    LaunchedEffect(uri, durationMs) {
        if (durationMs <= 0L) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ctx, Uri.parse(uri))
                val extracted = mutableListOf<Bitmap>()
                val step = (durationMs / 8L).coerceAtLeast(1L)
                for (i in 0 until 8) {
                    val timeUs = (i * step) * 1000L
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                        extracted.add(it)
                    }
                }
                frames = extracted
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
    Row(modifier = modifier) {
        if (frames.isEmpty()) {
            repeat(8) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(0.5.dp, MaterialTheme.colorScheme.surface)
                )
            }
        } else {
            frames.forEach { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

fun formatMs(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSec = safeMs / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimToolPanel(
    vm: EditorViewModel,
    s: EditState,
    exoDuration: Long,
    currentPos: Long,
    itemUri: String,
    galleryViewModel: GalleryViewModel? = null,
    onSeekReq: (Long) -> Unit
) {
    var sliderRange by remember(s.trimStartMs, s.trimEndMs, exoDuration) {
        val start = if (exoDuration > 0L) (s.trimStartMs.toFloat() / exoDuration).coerceIn(0f, 1f) else 0f
        val end = if (exoDuration > 0L && s.trimEndMs > 0L) (s.trimEndMs.toFloat() / exoDuration).coerceIn(0f, 1f) else 1f
        mutableStateOf(start..max(start, end))
    }

    val playheadPercent = if (exoDuration > 0L) (currentPos.toFloat() / exoDuration).coerceIn(0f, 1f) else 0f

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${formatMs(currentPos)} / ${formatMs(exoDuration)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        vm.exportFrame(
                            uri = Uri.parse(itemUri),
                            posMs = currentPos,
                            galleryViewModel = galleryViewModel
                        )
                    }
                ) {
                    Icon(
                        Icons.Rounded.CameraAlt,
                        contentDescription = "Capture Frame",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                TextButton(
                    onClick = {
                        vm.setTrimRange(0L, exoDuration)
                        onSeekReq(0L)
                    },
                    enabled = exoDuration > 1L,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            VideoThumbnailRow(
                uri = itemUri,
                durationMs = exoDuration,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            RangeSlider(
                value = sliderRange,
                onValueChange = { range ->
                    val oldRange = sliderRange
                    val movingStart = abs(range.start - oldRange.start) > abs(range.endInclusive - oldRange.endInclusive)

                    sliderRange = range
                    val startMs = (range.start * exoDuration).toLong()
                    val endMs = (range.endInclusive * exoDuration).toLong()
                    vm.setTrimRange(startMs, endMs)

                    if (movingStart) {
                        onSeekReq(startMs)
                    } else {
                        onSeekReq(endMs)
                    }
                },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxSize(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Black.copy(alpha = 0.6f)
                )
            )

            Canvas(Modifier.fillMaxSize()) {
                val x = playheadPercent * size.width
                drawLine(
                    color = Color.White,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx()
                )
                drawCircle(
                    color = Color.White,
                    radius = 4.dp.toPx(),
                    center = Offset(x, 0f)
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatMs(s.trimStartMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                formatMs(if (s.trimEndMs > 0L) s.trimEndMs else exoDuration),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ExportSettingsDialog(
    isVideo: Boolean,
    srcWidth: Int,
    srcHeight: Int,
    onDismiss: () -> Unit,
    onExport: (Pair<Int, Int>, Int, SaveMode) -> Unit
) {
    var saveMode by remember { mutableStateOf(SaveMode.SAVE_AS_NEW) }

    if (!isVideo) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Save Photo", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text("Choose how you want to save your edited photo:")
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { saveMode = SaveMode.SAVE_AS_NEW }
                    ) {
                        RadioButton(
                            selected = saveMode == SaveMode.SAVE_AS_NEW,
                            onClick = { saveMode = SaveMode.SAVE_AS_NEW }
                        )
                        Text("Save as New Copy")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { saveMode = SaveMode.REPLACE_ORIGINAL }
                    ) {
                        RadioButton(
                            selected = saveMode == SaveMode.REPLACE_ORIGINAL,
                            onClick = { saveMode = SaveMode.REPLACE_ORIGINAL }
                        )
                        Text("Replace Original")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { onExport(Pair(srcWidth, srcHeight), 0, saveMode) }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
        return
    }

    val safeW = srcWidth.takeIf { it > 0 } ?: 1920
    val safeH = srcHeight.takeIf { it > 0 } ?: 1080
    val isPortraitVideo = safeH > safeW
    val maxPixels = safeW * safeH

    val rs = remember(safeW, safeH) {
        val baseRes = if (isPortraitVideo) {
            listOf(
                Pair(720, 1280) to "720p",
                Pair(1080, 1920) to "1080p",
                Pair(2160, 3840) to "4K"
            )
        } else {
            listOf(
                Pair(1280, 720) to "720p",
                Pair(1920, 1080) to "1080p",
                Pair(3840, 2160) to "4K"
            )
        }
        baseRes.filter { (res, _) -> res.first * res.second <= maxPixels * 1.2f }
            .ifEmpty { listOf(baseRes.first()) }
    }

    var r by remember(rs) {
        mutableStateOf(
            rs.firstOrNull { max(it.first.first, it.first.second) <= 1920 }?.first
                ?: rs.first().first
        )
    }
    var f by remember { mutableIntStateOf(30) }
    val fs = listOf(24, 30, 60)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("Resolution", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                LazyRow(
                    Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rs) { (rv, l) ->
                        FilterChip(
                            selected = r == rv,
                            onClick = { r = rv },
                            label = { Text(l) }
                        )
                    }
                }

                Text(
                    "Frame Rate",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
                LazyRow(
                    Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(fs) { fv ->
                        FilterChip(
                            selected = f == fv,
                            onClick = { f = fv },
                            label = { Text("${fv}fps") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onExport(r, f, SaveMode.SAVE_AS_NEW) }) {
                Text("Export")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun StickerOverlay(
    state: EditState,
    parentWidth: Float,
    parentHeight: Float,
    selId: String?,
    onSel: (String) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    up: (String, (StickerLayer) -> StickerLayer) -> Unit
) {
    val ctx = LocalContext.current
    val il = remember {
        ImageLoader.Builder(ctx)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
    val d = LocalDensity.current

    state.stickers.filter { it.isVisible }.forEach { st ->
        val isSel = st.id == selId
        val b = 150f * st.scale
        val posX = st.x * parentWidth
        val posY = st.y * parentHeight

        Box(
            Modifier
                .offset {
                    IntOffset(
                        (posX - b / 2f).roundToInt(),
                        (posY - b / 2f).roundToInt()
                    )
                }
                .size(with(d) { b.toDp() })
                .pointerInput(st.id, parentWidth, parentHeight) {
                    detectDragGestures(
                        onDragStart = {
                            onSel(st.id)
                            onDragStart()
                        },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd
                    ) { change, dragAmount ->
                        change.consume()
                        up(st.id) {
                            it.copy(
                                x = (it.x + (dragAmount.x / parentWidth)).coerceIn(0f, 1f),
                                y = (it.y + (dragAmount.y / parentHeight)).coerceIn(0f, 1f)
                            )
                        }
                    }
                }
                .graphicsLayer { rotationZ = st.rotation }
                .pointerInput(st.id) { detectTapGestures { onSel(st.id) } }
        ) {
            if (st.assetPath.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data("file:///android_asset/${st.assetPath}").build(),
                    imageLoader = il,
                    contentDescription = "Sticker",
                    modifier = Modifier.fillMaxSize()
                )
            } else if (st.emoji.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = st.emoji,
                        fontSize = with(d) { (b * 0.75f).toSp() },
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (isSel) {
                Box(
                    Modifier
                        .matchParentSize()
                        .border(2.dp, Color.White)
                )

                val handleSize = 28.dp
                var dragVec by remember { mutableStateOf(Offset.Zero) }

                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = handleSize / 2, y = handleSize / 2)
                        .size(handleSize)
                        .background(Color.White, CircleShape)
                        .pointerInput(st.id) {
                            detectDragGestures(
                                onDragStart = {
                                    onDragStart()
                                    dragVec = Offset(b / 2f, b / 2f)
                                },
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragEnd
                            ) { change, dragAmount ->
                                change.consume()
                                val newVec = dragVec + dragAmount
                                val zoomDelta = (newVec.getDistance() / dragVec.getDistance())
                                    .takeIf { !it.isNaN() && it > 0f } ?: 1f
                                val rotDelta = Math.toDegrees(
                                    (atan2(newVec.y, newVec.x) - atan2(dragVec.y, dragVec.x)).toDouble()
                                ).toFloat()
                                dragVec = newVec
                                up(st.id) {
                                    it.copy(
                                        scale = (it.scale * zoomDelta).coerceIn(0.2f, 10f),
                                        rotation = (it.rotation + rotDelta) % 360f
                                    )
                                }
                            }
                        }
                ) {
                    Icon(
                        Icons.Rounded.ZoomOutMap,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TextOverlay(
    state: EditState,
    parentWidth: Float,
    parentHeight: Float,
    selId: String?,
    onSel: (String) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    up: (String, (TextLayer) -> TextLayer) -> Unit
) {
    state.textLayers.filter { it.isVisible }.forEach { t ->
        val isSel = t.id == selId
        val posX = t.x * parentWidth
        val posY = t.y * parentHeight
        var measuredWidth by remember { mutableIntStateOf(0) }
        var measuredHeight by remember { mutableIntStateOf(0) }

        Box(
            Modifier
                .offset {
                    IntOffset(
                        (posX - measuredWidth / 2f).roundToInt(),
                        (posY - measuredHeight / 2f).roundToInt()
                    )
                }
                .onGloballyPositioned {
                    measuredWidth = it.size.width
                    measuredHeight = it.size.height
                }
                .pointerInput(t.id, parentWidth, parentHeight) {
                    detectDragGestures(
                        onDragStart = {
                            onSel(t.id)
                            onDragStart()
                        },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd
                    ) { change, dragAmount ->
                        change.consume()
                        up(t.id) {
                            it.copy(
                                x = (it.x + (dragAmount.x / parentWidth)).coerceIn(0f, 1f),
                                y = (it.y + (dragAmount.y / parentHeight)).coerceIn(0f, 1f)
                            )
                        }
                    }
                }
                .graphicsLayer { rotationZ = t.rotation }
                .pointerInput(t.id) { detectTapGestures { onSel(t.id) } }
        ) {
            Text(
                text = t.text,
                color = Color(t.color).copy(alpha = t.opacity.coerceIn(0f, 1f)),
                fontSize = t.size.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )

            if (isSel && measuredWidth > 0) {
                Box(
                    Modifier
                        .matchParentSize()
                        .border(2.dp, Color.White)
                )

                val handleSize = 28.dp
                var dragVec by remember { mutableStateOf(Offset.Zero) }

                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = handleSize / 2, y = handleSize / 2)
                        .size(handleSize)
                        .background(Color.White, CircleShape)
                        .pointerInput(t.id) {
                            detectDragGestures(
                                onDragStart = {
                                    onDragStart()
                                    dragVec = Offset(measuredWidth / 2f, measuredHeight / 2f)
                                },
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragEnd
                            ) { change, dragAmount ->
                                change.consume()
                                val newVec = dragVec + dragAmount
                                val zoomDelta = (newVec.getDistance() / dragVec.getDistance())
                                    .takeIf { !it.isNaN() && it > 0f } ?: 1f
                                val rotDelta = Math.toDegrees(
                                    (atan2(newVec.y, newVec.x) - atan2(dragVec.y, dragVec.x)).toDouble()
                                ).toFloat()
                                dragVec = newVec
                                up(t.id) {
                                    it.copy(
                                        size = (it.size * zoomDelta).coerceIn(10f, 400f),
                                        rotation = (it.rotation + rotDelta) % 360f
                                    )
                                }
                            }
                        }
                ) {
                    Icon(
                        Icons.Rounded.ZoomOutMap,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }
    }
}