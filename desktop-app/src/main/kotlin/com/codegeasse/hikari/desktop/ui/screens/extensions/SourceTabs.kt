package com.codegeasse.hikari.desktop.ui.screens.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codegeasse.hikari.desktop.ui.screens.extensions.contract.ExtensionsUiEvent
import com.codegeasse.hikari.desktop.ui.screens.extensions.contract.SourceEntry
import com.codegeasse.hikari.desktop.ui.screens.extensions.contract.SourceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun NuvioTab(viewModel: ExtensionsViewModel) {
    SourceBrowserTab(
        kind = "nuvio",
        title = "Nuvio Providers",
        description = "JavaScript scrapers (manifest.json repos). Open a repo to browse and install single providers.",
        urlPlaceholder = "Paste manifest URL (e.g. https://.../manifest.json)",
        fileExtensions = listOf(".js"),
        fileTitle = "Install Provider File (.js)",
        viewModel = viewModel,
    )
}

@Composable
fun SkyStreamTab(viewModel: ExtensionsViewModel) {
    SourceBrowserTab(
        kind = "sky",
        title = "SkyStream Plugins",
        description = "SkyStream .sky plugins (repo.json repos). Open a repo to browse and install single plugins.",
        urlPlaceholder = "Paste repo URL (e.g. https://.../repo.json) or shortcode",
        fileExtensions = listOf(".sky"),
        fileTitle = "Install Plugin File (.sky)",
        viewModel = viewModel,
    )
}

@Composable
fun HikiTab(viewModel: ExtensionsViewModel) {
    SourceBrowserTab(
        kind = "hiki",
        title = "Hiki Extensions",
        description = "Hiki .hiki packages. Open the repo to browse and install single extensions.",
        urlPlaceholder = "Paste a repo URL (e.g. https://.../repo.json)",
        fileExtensions = listOf(".hiki", ".cs3", ".jar"),
        fileTitle = "Install Extension File (.hiki)",
        viewModel = viewModel,
    )
}

@Composable
fun AniyomiTab(viewModel: ExtensionsViewModel) {
    SourceBrowserTab(
        kind = "aniyomi",
        title = "Anime Extensions",
        description = "Anime .apk extensions (index.min.json repos). Open a repo to browse and install single extensions.",
        urlPlaceholder = "Paste repo URL (e.g. https://.../index.min.json)",
        fileExtensions = listOf(".apk"),
        fileTitle = "Install Extension File (.apk)",
        viewModel = viewModel,
    )
}

@Composable
private fun SourceBrowserTab(
    kind: String,
    title: String,
    description: String,
    urlPlaceholder: String,
    fileExtensions: List<String>,
    fileTitle: String,
    viewModel: ExtensionsViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val repos = when (kind) {
        "nuvio" -> uiState.sourceReposNuvio
        "sky" -> uiState.sourceReposSky
        "hiki" -> uiState.sourceReposHiki
        else -> uiState.sourceReposAniyomi
    }
    val browse = when (kind) {
        "nuvio" -> uiState.sourceBrowseNuvio
        "sky" -> uiState.sourceBrowseSky
        "hiki" -> uiState.sourceBrowseHiki
        else -> uiState.sourceBrowseAniyomi
    }
    val items = when (kind) {
        "nuvio" -> uiState.sourceItemsNuvio
        "sky" -> uiState.sourceItemsSky
        "hiki" -> uiState.sourceItemsHiki
        else -> uiState.sourceItemsAniyomi
    }
    val inspected = uiState.inspectedSource?.takeIf { it.first == kind }?.second
    if (inspected != null) {
        SourceRepoDetail(
            kind = kind,
            repoUrl = inspected,
            repoName = uiState.sourceRepoNames[inspected] ?: inspected,
            entries = browse[inspected] ?: emptyList(),
            busy = uiState.sourceTabBusy || uiState.sourceBrowseBusy,
            viewModel = viewModel,
        )
    } else {
        SourceRepoList(
            kind = kind,
            title = title,
            description = description,
            urlPlaceholder = urlPlaceholder,
            fileExtensions = fileExtensions,
            fileTitle = fileTitle,
            repos = repos,
            repoNames = uiState.sourceRepoNames,
            browse = browse,
            items = items,
            busy = uiState.sourceTabBusy,
            browseBusy = uiState.sourceBrowseBusy,
            status = uiState.sourceTabStatus,
            viewModel = viewModel,
        )
    }
}

@Composable
private fun SourceRepoList(
    kind: String,
    title: String,
    description: String,
    urlPlaceholder: String,
    fileExtensions: List<String>,
    fileTitle: String,
    repos: List<String>,
    repoNames: Map<String, String>,
    browse: Map<String, List<SourceEntry>>,
    items: List<SourceRef>,
    busy: Boolean,
    browseBusy: Boolean,
    status: String,
    viewModel: ExtensionsViewModel,
) {
    var inputUrl by remember(kind) { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.onEvent(ExtensionsUiEvent.OnRefreshSourceRepos) }) {
                    if (browseBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh repos")
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            ),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(urlPlaceholder) },
                        shape = RoundedCornerShape(12.dp),
                        enabled = !busy,
                    )
                    Button(
                        onClick = {
                            if (inputUrl.isNotBlank()) {
                                viewModel.onEvent(ExtensionsUiEvent.OnAddSourceRepo(kind, inputUrl.trim()))
                                inputUrl = ""
                            }
                        },
                        enabled = inputUrl.isNotBlank() && !busy,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add")
                        }
                    }
                }
                if (status.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            val sourceFile = com.codegeasse.hikari.desktop.utils.NativeFileDialog.open(
                                title = fileTitle,
                                allowedExtensions = fileExtensions,
                                category = com.codegeasse.hikari.desktop.utils.NativeFileDialog.Category.EXTENSIONS,
                            )
                            if (sourceFile != null && sourceFile.exists()) {
                                viewModel.onEvent(ExtensionsUiEvent.OnInstallSourceFile(kind, sourceFile))
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(imageVector = Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Install from file")
                }
            }
        }

        Text(
            text = "Saved Repositories (${repos.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (repos.isEmpty()) {
            Text(
                text = "No repositories yet. Paste a repo URL above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repos.forEach { url ->
                    val entries = browse[url] ?: emptyList()
                    val installedCount = entries.count { it.installed }
                    Card(
                        onClick = { viewModel.onEvent(ExtensionsUiEvent.OnInspectSourceRepo(kind, url)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RepoAvatarBox(repoNames[url] ?: url)
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    repoNames[url] ?: url,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    if (browse.containsKey(url)) "${entries.size} available • $installedCount installed • Click to inspect"
                                    else "Click to inspect",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            IconButton(onClick = { viewModel.onEvent(ExtensionsUiEvent.OnRemoveSourceRepo(kind, url)) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = "Installed (${items.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "Nothing installed. Open a repo above to browse.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            OutlinedButton(
                                onClick = { viewModel.onEvent(ExtensionsUiEvent.OnUninstallSourceItem(kind, item.id)) },
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text("Uninstall")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRepoDetail(
    kind: String,
    repoUrl: String,
    repoName: String,
    entries: List<SourceEntry>,
    busy: Boolean,
    viewModel: ExtensionsViewModel,
) {
    var query by remember(kind, repoUrl) { mutableStateOf("") }
    val filtered = remember(entries, query) {
        if (query.isBlank()) entries
        else entries.filter {
            it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { viewModel.onEvent(ExtensionsUiEvent.OnInspectSourceRepo(kind, null)) },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("All Repositories")
                }
                Text(
                    text = repoName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        "${entries.size} available",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search inside $repoName...") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
        )
        Box(modifier = Modifier.fillMaxSize()) {
            if (filtered.isEmpty()) {
                Text(
                    text = if (entries.isEmpty()) "No entries loaded. Pull refresh on the repo list and reopen." else "No entries match.",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 340.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (entry.description.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = entry.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = if (entry.version.isNotBlank()) "v${entry.version}" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Button(
                                        onClick = {
                                            viewModel.onEvent(ExtensionsUiEvent.OnInstallSourceEntry(kind, repoUrl, entry.id))
                                        },
                                        enabled = !entry.installed && !busy,
                                        shape = RoundedCornerShape(10.dp),
                                    ) {
                                        Text(if (entry.installed) "Installed" else "Install")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
