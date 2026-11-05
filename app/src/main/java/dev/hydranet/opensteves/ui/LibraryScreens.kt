@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.hydranet.opensteves.ui

import android.text.Html
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hydranet.opensteves.data.DownloadStatus
import dev.hydranet.opensteves.data.InstalledTour
import dev.hydranet.opensteves.data.Tour
import dev.hydranet.opensteves.data.TourRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun displayTitle(tour: Tour): String = tour.title.substringAfter(" — ")

internal fun tourLocation(tour: Tour): String =
    if (" — " in tour.title) tour.title.substringBefore(" — ") else tour.region

private fun duration(tour: Tour): String {
    val text = tour.details.substringBefore('|').trim()
    return Regex("^(\\d+)\\s*m(?:in(?:s)?)?$", RegexOption.IGNORE_CASE).matchEntire(text)
        ?.let { "${it.groupValues[1]} min" } ?: text
}

@Composable
internal fun ExploreScreen(
    tours: List<Tour>,
    installed: Map<Int, InstalledTour>,
    downloads: Map<Int, DownloadStatus>,
    refreshing: Boolean,
    repository: TourRepository,
    currentId: Int?,
    onSelect: (Tour) -> Unit,
    onDownload: (Tour) -> Unit,
    onCancel: (Int) -> Unit,
    onPlay: (Tour) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var walkingOnly by rememberSaveable { mutableStateOf(true) }
    var region by rememberSaveable { mutableStateOf<String?>(null) }
    var regionsOpen by remember { mutableStateOf(false) }
    val regions = remember(tours) { tours.map { it.region }.distinct().sorted() }
    val catalog = remember(tours) { tours.associateBy { it.id } }
    val downloaded = remember(catalog, installed) {
        installed.values.map { catalog[it.tour.id] ?: it.tour }
            .sortedWith(compareBy({ tourLocation(it) }, { displayTitle(it) }))
    }
    val listState = remember { LazyListState() }
    LaunchedEffect(downloaded.isNotEmpty()) {
        // Downloaded tours can arrive after the catalog and change the first visible item's position.
        listState.requestScrollToItem(0)
    }
    val pending = downloads.filterKeys { !installed.containsKey(it) && catalog.containsKey(it) }
    val filtered = remember(tours, query, walkingOnly, region, installed, pending.keys) {
        val term = query.trim()
        tours.filter { tour ->
            !installed.containsKey(tour.id) && !pending.containsKey(tour.id) &&
                (!walkingOnly || tour.type == "Walking tour") &&
                (region == null || tour.region == region) &&
                (tour.title.contains(term, true) || tour.region.contains(term, true))
        }
    }
    val grouped = remember(filtered) { filtered.groupBy { it.region to tourLocation(it) } }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        if (downloaded.isNotEmpty()) {
            item(key = "downloaded-heading") {
                LibrarySection("Downloaded (${downloaded.size})", bytes(installed.values.sumOf { it.sizeBytes }))
            }
            items(downloaded, key = { it.id }) { tour ->
                CatalogRow(tour, installed[tour.id], downloads[tour.id], repository,
                    onSelect = { onSelect(tour) }, onDownload = { onDownload(tour) }, onCancel = { onCancel(tour.id) },
                    onPlay = { onPlay(tour) }, current = currentId == tour.id)
            }
        }
        if (pending.isNotEmpty()) {
            item(key = "pending-heading") { LibrarySection("Downloads") }
            pending.forEach { (id, status) ->
                item(key = id) {
                    val tour = catalog.getValue(id)
                    CatalogRow(tour, null, status, repository,
                        onSelect = { onSelect(tour) }, onDownload = { onDownload(tour) }, onCancel = { onCancel(id) },
                        onPlay = { onPlay(tour) })
                    status.error?.let { error ->
                        Text(error, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (downloaded.isNotEmpty() || pending.isNotEmpty()) item(key = "discover-heading") { LibrarySection("Discover tours") }
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                singleLine = true,
                placeholder = { Text("Search cities and tours") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, "Clear search")
                    }
                },
                shape = RoundedCornerShape(6.dp),
            )
        }
        item(key = "filters") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = walkingOnly,
                    onClick = { walkingOnly = !walkingOnly },
                    label = { Text(if (walkingOnly) "Walking tours" else "All audio") },
                    leadingIcon = {
                        if (walkingOnly) Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                    },
                    shape = RoundedCornerShape(4.dp),
                )
                Box {
                    FilterChip(
                        selected = region != null,
                        onClick = { regionsOpen = true },
                        label = { Text(region ?: "All regions", maxLines = 1) },
                        trailingIcon = { Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp)) },
                        shape = RoundedCornerShape(4.dp),
                    )
                    DropdownMenu(expanded = regionsOpen, onDismissRequest = { regionsOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("All regions") },
                            onClick = { region = null; regionsOpen = false },
                        )
                        regions.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item) },
                                onClick = { region = item; regionsOpen = false },
                            )
                        }
                    }
                }
            }
        }
        if (refreshing) item(key = "refreshing") {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
        }
        if (filtered.isEmpty() && !refreshing && (tours.isEmpty() || query.isNotBlank() || region != null || installed.isEmpty())) item(key = "empty") {
            LibraryEmptyState(
                Icons.Default.TravelExplore,
                if (tours.isEmpty()) "No tours available" else "No matching tours",
                if (tours.isEmpty()) "Connect to the internet and refresh the catalog." else "Try another city or region.",
            )
        }
        grouped.forEach { (place, groupTours) ->
            item(key = "location-${place.first}-${place.second}") {
                LibrarySection(place.second, "${groupTours.size} ${if (groupTours.size == 1) "tour" else "tours"}")
            }
            items(groupTours, key = { it.id }) { tour ->
                CatalogRow(
                    tour, installed[tour.id], downloads[tour.id], repository,
                    onSelect = { onSelect(tour) },
                    onDownload = { onDownload(tour) },
                    onCancel = { onCancel(tour.id) },
                    onPlay = { onPlay(tour) },
                )
            }
        }
    }
}

@Composable
private fun CatalogRow(
    tour: Tour,
    installed: InstalledTour?,
    download: DownloadStatus?,
    repository: TourRepository,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onPlay: () -> Unit,
    current: Boolean = false,
) {
    val updating = installed != null && installed.tour.version < tour.version
    val downloading = download != null && download.error == null
    val status = when {
        downloading -> "Downloading ${download.progress.coerceIn(0, 100)}%"
        download?.error != null -> "Download failed"
        updating -> "Update available"
        installed != null -> "${bytes(installed.sizeBytes)} / ${if (current) "Current tour" else "Downloaded"}"
        else -> bytes(tour.bytes)
    }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryArtwork(tour, installed, repository, Modifier.size(44.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(displayTitle(tour), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(duration(tour), status).filter(String::isNotBlank).joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (download?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                downloading -> IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel download") }
                installed == null || updating -> IconButton(onClick = onDownload) {
                    Icon(if (download?.error != null) Icons.Default.Refresh else Icons.Default.Download, "Download ${displayTitle(tour)}", tint = MaterialTheme.colorScheme.primary)
                }
                else -> IconButton(onClick = onPlay) {
                    Icon(Icons.Default.PlayArrow, "Start ${displayTitle(tour)}", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (downloading) LinearProgressIndicator(
            progress = { download.progress.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth().padding(start = 78.dp, end = 20.dp).height(2.dp),
        )
    }
}

@Composable
internal fun TourInfoScreen(
    tour: Tour,
    repository: TourRepository,
    installed: InstalledTour?,
    download: DownloadStatus?,
    currentId: Int?,
    sessionActive: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onPlay: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val downloading = download != null && download.error == null
    val updateAvailable = installed != null && installed.tour.version < tour.version
    val loaded = currentId == tour.id
    val canPlay = !downloading && (!sessionActive || loaded)
    val description = remember(tour.description) { Html.fromHtml(tour.description, Html.FROM_HTML_MODE_COMPACT).toString().trim() }
    val assets by produceState(emptyList<Pair<String, ImageVector>>(), tour.id, installed?.directoryName) {
        value = if (installed == null) emptyList() else withContext(Dispatchers.IO) {
            buildList {
                if (repository.mapFile(tour.id) != null) add("Map" to Icons.Default.Map)
                if (repository.transcriptFile(tour.id) != null) add("Transcript" to Icons.AutoMirrored.Filled.Subject)
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(key = "artwork") { LibraryArtwork(tour, installed, repository, Modifier.size(if (installed != null) 132.dp else 72.dp)) }
        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tourLocation(tour), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(displayTitle(tour), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
                Text(listOf(tour.type, duration(tour), bytes(tour.bytes)).filter(String::isNotBlank).joinToString(" / "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (installed != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Available offline", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Button(shape = MaterialTheme.shapes.small, onClick = if (loaded) onOpenPlayer else onPlay, enabled = canPlay, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(if (loaded) "Back to tour" else "Start tour")
                    }
                    if (sessionActive && !loaded) Text("Leave the session to play a different tour.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (downloading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (installed == null) "Downloading" else "Updating", style = MaterialTheme.typography.titleSmall)
                        Text("${download.progress.coerceIn(0, 100)}%", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                    LinearProgressIndicator(progress = { download.progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                    TextButton(shape = MaterialTheme.shapes.small, onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel download") }
                } else if (installed == null) {
                    Button(shape = MaterialTheme.shapes.small, onClick = onDownload, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(if (download?.error != null) "Retry download / ${bytes(tour.bytes)}" else "Download tour / ${bytes(tour.bytes)}")
                    }
                } else if (updateAvailable) {
                    TextButton(shape = MaterialTheme.shapes.small, onClick = onDownload, enabled = !loaded, modifier = Modifier.fillMaxWidth()) { Text("Download update / ${bytes(tour.bytes)}") }
                    if (loaded) Text("Stop this tour before updating its download.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                download?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
        if (description.isNotEmpty()) item(key = "description") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("About this tour", style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (installed != null) item(key = "assets") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Included in the download", style = MaterialTheme.typography.titleMedium)
                (listOf("Audio tour" to Icons.Default.Headphones) + assets).forEach { (label, icon) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryArtwork(tour: Tour, installed: InstalledTour?, repository: TourRepository, modifier: Modifier) {
    val file by produceState<File?>(null, tour.id, installed?.directoryName) {
        value = if (installed == null) null else withContext(Dispatchers.IO) { repository.imageFile(tour.id) }
    }
    TourThumbnail(file, modifier)
}

@Composable
private fun LibrarySection(title: String, meta: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        meta?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun LibraryEmptyState(icon: ImageVector, title: String, description: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
