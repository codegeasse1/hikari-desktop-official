package com.codegeasse.hikari.desktop.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codegeasse.hikari.desktop.AppConfig
import com.codegeasse.hikari.desktop.utils.appScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsAbout() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsGroupCard(title = "Hikari Desktop") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Version ${AppConfig.APP_VERSION}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "A desktop-native media center with support for extensions, Stremio addons, Nuvio scrapers, SkyStream plugins, Aniyomi extensions, IPTV playlists and Hiki sources. For support and bug reports, use our Discord community below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        SettingsGroupCard(title = "Community & Support") {
            SettingsNavigationItem(
                label = "Community Discord",
                subtitle = "Join Discord to report bugs or any issue.",
                onClick = { openUrl("https://discord.gg/cU6Fr7Wx4") },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            SettingsNavigationItem(
                label = "Source Code",
                subtitle = "View repository, report issues, and inspect release builds.",
                onClick = { openUrl("https://github.com/codegeasse1/hikari-desktop-official") },
            )
        }

        SettingsGroupCard(title = "Legal Notice & Disclaimer") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Gavel,
                                contentDescription = "Legal Notice",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "IMPORTANT LEGAL NOTICE — PLEASE READ CAREFULLY",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 0.5.sp,
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                        Text(
                            text = "1. Pure Browser Shell & Zero Bundled Media\n" +
                                "This software is strictly an open-source, media-neutral desktop browser shell and video player. It does NOT host, own, create, index, scrape, transmit, or distribute any media files, video streams, torrents, or content of any kind. Out of the box, this application contains no media catalog, no streaming repositories, and no content.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                        )

                        Text(
                            text = "2. User-Provided Extensions & Third-Party Repositories\n" +
                                "All plugins, extensions, addon manifests, external links, and media sources loaded into this application are configured and added entirely by the user at their own sole discretion. The developers and contributors do not author, maintain, verify, control, or endorse any external repositories, plugins, or third-party streaming sources.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                        )

                        Text(
                            text = "3. Sole User Responsibility & Legal Compliance\n" +
                                "Users are exclusively and solely responsible for their use of this software. You must ensure that your access, playback, or usage of any media or third-party services complies fully with all applicable local, state, national, and international laws, copyright regulations, and intellectual property rights in your jurisdiction.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                        )

                        Text(
                            text = "4. \"As-Is\" Software & Limitation of Liability\n" +
                                "This software is provided \"AS IS\", without warranty of any kind, express or implied. Under no circumstances shall the authors, maintainers, or copyright holders be held liable for any claim, damages, copyright infringement, or legal consequences arising from the use, installation, or misuse of this software.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
    }
}

private fun openUrl(url: String) {
    appScope.launch(Dispatchers.IO) {
        try {
            val uri = java.net.URI(url)
            val desktop = java.awt.Desktop.getDesktop()
            desktop.browse(uri)
        } catch (e: Exception) {
            com.codegeasse.hikari.common.logging.AppLogger.e("Error opening link $url", e)
        }
    }
}

@Composable
fun SettingsAboutScreen() {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(top = 16.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsAbout()
    }
}

@Composable
fun SettingsAboutAndUpdates(viewModel: SettingsViewModel) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(top = 16.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsUpdates(viewModel = viewModel)
        SettingsAbout()
    }
}
