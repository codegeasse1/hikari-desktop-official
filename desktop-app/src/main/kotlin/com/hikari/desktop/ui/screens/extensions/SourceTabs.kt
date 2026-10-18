package com.hikari.desktop.ui.screens.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
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
import androidx.compose.ui.unit.dp
import com.hikari.desktop.ui.screens.extensions.contract.ExtensionsUiEvent
import com.hikari.desktop.ui.screens.extensions.contract.SourceRef
import kotlinx.coroutines.launch

@Composable
fun NuvioTab(viewModel: ExtensionsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    SourceTabContent(
        title = "Nuvio Providers",
        description = "JavaScript scrapers (manifest.json repos). Each installed scraper appears as a provider with TMDB catalogs.",
        urlPlaceholder = "Paste manifest URL (e.g. https://.../manifest.json) or a single .js provider URL",
        fileExtensions = listOf(".js"),
        fileTitle = "Install Provider File (.js)",
        repos = uiState.sourceReposNuvio,
        items = uiState.sourceItemsNuvio,
        emptyText = "No Nuvio providers installed. Paste a manifest URL above.",
        busy = uiState.sourceTabBusy,
        status = uiState.sourceTabStatus,
        onAddRepo = { viewModel.onEvent(ExtensionsUiEvent.OnAddSourceRepo("nuvio", it)) },
        onRemoveRepo = { viewModel.onEvent(ExtensionsUiEvent.OnRemoveSourceRepo("nuvio", it)) },
        onUninstall = { viewModel.onEvent(ExtensionsUiEvent.OnUninstallSourceItem("nuvio", it)) },
        onInstallFile = { viewModel.onEvent(ExtensionsUiEvent.OnInstallSourceFile("nuvio", it)) },
        viewModel = viewModel,
    )
}

@Composable
fun SkyStreamTab(viewModel: ExtensionsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    SourceTabContent(
        title = "SkyStream Plugins",
        description = "SkyStream .sky plugins (repo.json repos). Each installed plugin appears as a provider.",
        urlPlaceholder = "Paste repo URL (e.g. https://.../repo.json), shortcode, or a single .sky URL",
        fileExtensions = listOf(".sky"),
        fileTitle = "Install Plugin File (.sky)",
        repos = uiState.sourceReposSky,
        items = uiState.sourceItemsSky,
        emptyText = "No SkyStream plugins installed. Paste a repo URL above.",
        busy = uiState.sourceTabBusy,
        status = uiState.sourceTabStatus,
        onAddRepo = { viewModel.onEvent(ExtensionsUiEvent.OnAddSourceRepo("sky", it)) },
        onRemoveRepo = { viewModel.onEvent(ExtensionsUiEvent.OnRemoveSourceRepo("sky", it)) },
        onUninstall = { viewModel.onEvent(ExtensionsUiEvent.OnUninstallSourceItem("sky", it)) },
        onInstallFile = { viewModel.onEvent(ExtensionsUiEvent.OnInstallSourceFile("sky", it)) },
        viewModel = viewModel,
    )
}

@Composable
fun HikiTab(viewModel: ExtensionsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    SourceTabContent(
        title = "Hiki Extensions",
        description = "Hiki packages install through the same pipeline as Android plugins and appear under Installed.",
        urlPlaceholder = "Paste a .hiki package URL",
        fileExtensions = listOf(".hiki", ".cs3", ".jar"),
        fileTitle = "Install Extension File (.hiki)",
        repos = emptyList(),
        items = uiState.sourceItemsHiki,
        emptyText = "No Hiki extensions installed. Install a .hiki file below.",
        busy = uiState.sourceTabBusy,
        status = uiState.sourceTabStatus,
        onAddRepo = { viewModel.onEvent(ExtensionsUiEvent.OnAddSourceRepo("hiki", it)) },
        onRemoveRepo = {},
        onUninstall = { viewModel.onEvent(ExtensionsUiEvent.OnUninstallSourceItem("hiki", it)) },
        onInstallFile = { viewModel.onEvent(ExtensionsUiEvent.OnInstallSourceFile("hiki", it)) },
        viewModel = viewModel,
    )
}

@Composable
fun AniyomiTab(viewModel: ExtensionsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    SourceTabContent(
        title = "Anime Extensions",
        description = "Anime .apk extensions (index.min.json repos). Each source in an extension appears as a provider.",
        urlPlaceholder = "Paste repo URL (e.g. https://.../index.min.json) or a single .apk URL",
        fileExtensions = listOf(".apk"),
        fileTitle = "Install Extension File (.apk)",
        repos = uiState.sourceReposAniyomi,
        items = uiState.sourceItemsAniyomi,
        emptyText = "No anime extensions installed. Paste a repo URL above.",
        busy = uiState.sourceTabBusy,
        status = uiState.sourceTabStatus,
        onAddRepo = { viewModel.onEvent(ExtensionsUiEvent.OnAddSourceRepo("aniyomi", it)) },
        onRemoveRepo = { viewModel.onEvent(ExtensionsUiEvent.OnRemoveSourceRepo("aniyomi", it)) },
        onUninstall = { viewModel.onEvent(ExtensionsUiEvent.OnUninstallSourceItem("aniyomi", it)) },
        onInstallFile = { viewModel.onEvent(ExtensionsUiEvent.OnInstallSourceFile("aniyomi", it)) },
        viewModel = viewModel,
    )
}

@Composable
private fun SourceTabContent(
    title: String,
    description: String,
    urlPlaceholder: String,
    fileExtensions: List<String>,
    fileTitle: String,
    repos: List<String>,
    items: List<SourceRef>,
    emptyText: String,
    busy: Boolean,
    status: String,
    onAddRepo: (String) -> Unit,
    onRemoveRepo: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onInstallFile: (java.io.File) -> Unit,
    viewModel: ExtensionsViewModel,
) {
    var inputUrl by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                Text(
                    text = "Install from URL",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                                onAddRepo(inputUrl.trim())
                                inputUrl = ""
                            }
                        },
                        enabled = inputUrl.isNotBlank() && !busy,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
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
                            Text("Install")
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
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val sourceFile = com.hikari.desktop.utils.NativeFileDialog.open(
                                title = fileTitle,
                                allowedExtensions = fileExtensions,
                                category = com.hikari.desktop.utils.NativeFileDialog.Category.EXTENSIONS,
                            )
                            if (sourceFile != null && sourceFile.exists()) {
                                onInstallFile(sourceFile)
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

        if (repos.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                ),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(
                        text = "Repositories (${repos.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    repos.forEach { url ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onRemoveRepo(url) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove repository",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Installed (${items.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { onUninstall(item.id) }, shape = RoundedCornerShape(10.dp)) {
                                Text("Uninstall")
                            }
                        }
                    }
                }
            }
        }
    }
}
