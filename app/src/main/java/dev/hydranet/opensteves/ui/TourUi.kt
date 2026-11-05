package dev.hydranet.opensteves.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
internal fun TourThumbnail(file: File?, modifier: Modifier = Modifier) {
    val bitmap = rememberBitmap(file, 512).image
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.AccountBalance, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SectionTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface) {
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            labels.forEachIndexed { index, label ->
                val active = selected == index
                Column(Modifier.weight(1f).height(48.dp).selectable(active, role = Role.Tab, onClick = { onSelect(index) })) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) colors.primary else colors.onSurfaceVariant)
                    }
                    HorizontalDivider(thickness = 2.dp, color = if (active) colors.primary else colors.outlineVariant)
                }
            }
        }
    }
}

@Composable
internal fun ZoomableMap(file: File?, title: String) {
    val loaded = rememberBitmap(file, 4096)
    val bitmap = loaded.image
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    if (file == null) { EmptyState(Icons.Default.Map, "No map for this audio", "Maps are included with walking tours."); return }
    if (loaded.loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    if (bitmap == null) { EmptyState(Icons.Default.Map, "Could not display this map", "Delete and download the tour again to restore its map."); return }
    Box(Modifier.fillMaxSize().background(Color.White).padding(12.dp)) {
        Box(Modifier.fillMaxSize().clipToBounds().pointerInput(file) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 8f)
                val limitX = size.width * (scale - 1) / 2f
                val limitY = size.height * (scale - 1) / 2f
                offset = Offset((offset.x + pan.x).coerceIn(-limitX, limitX), (offset.y + pan.y).coerceIn(-limitY, limitY))
            }
        }, contentAlignment = Alignment.Center) {
            Image(bitmap, "Map of $title", Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
        }
        Surface(Modifier.align(Alignment.BottomEnd).padding(4.dp), shape = RoundedCornerShape(6.dp), tonalElevation = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scale = (scale / 1.5f).coerceAtLeast(1f); offset = Offset.Zero }) { Icon(Icons.Default.Remove, "Zoom out") }
                TextButton(shape = MaterialTheme.shapes.small, onClick = { scale = 1f; offset = Offset.Zero }) { Text("Fit") }
                IconButton(onClick = { scale = (scale * 1.5f).coerceAtMost(8f) }) { Icon(Icons.Default.Add, "Zoom in") }
            }
        }
    }
}

private data class LoadedBitmap(val image: ImageBitmap? = null, val loading: Boolean = true)

@Composable
private fun rememberBitmap(file: File?, maxDimension: Int): LoadedBitmap {
    val bitmap by produceState(LoadedBitmap(), file?.absolutePath, maxDimension) {
        value = LoadedBitmap()
        val image = withContext(Dispatchers.IO) {
            if (file == null) null else runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
        value = LoadedBitmap(image, false)
    }
    return bitmap
}

@Composable
internal fun EmptyState(icon: ImageVector, title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

// The multiplication sign (U+00D7) carries wide side bearings in the default font, which reads as a stray gap.
internal fun speedLabel(speed: Float): String = "${speed}x"

internal fun bytes(value: Long): String = if (value >= 1_000_000_000) String.format(Locale.getDefault(), "%.1f GB", value / 1_000_000_000.0) else String.format(Locale.getDefault(), "%.1f MB", value / 1_000_000.0)
internal fun time(value: Long): String { val seconds = value.coerceAtLeast(0) / 1000; return if (seconds >= 3600) String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) else String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60) }
