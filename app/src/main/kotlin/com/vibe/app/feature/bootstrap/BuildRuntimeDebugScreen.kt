package com.vibe.app.feature.bootstrap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibe.build.runtime.bootstrap.BootstrapState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildRuntimeDebugScreen(
    onBack: () -> Unit,
    viewModel: BuildRuntimeDebugViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Build Runtime (debug)") })
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Manifest URL", style = MaterialTheme.typography.labelMedium)
            Text(ui.manifestUrl, style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()

            Text("Bootstrap state", style = MaterialTheme.typography.labelMedium)
            Text(ui.bootstrap.describe(), fontFamily = FontFamily.Monospace)

            if (ui.bootstrap is BootstrapState.Downloading) {
                val dl = ui.bootstrap as BootstrapState.Downloading
                LinearProgressIndicator(
                    progress = { (dl.percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = viewModel::triggerBootstrap,
                enabled = ui.bootstrap !is BootstrapState.Downloading &&
                        ui.bootstrap !is BootstrapState.Verifying &&
                        ui.bootstrap !is BootstrapState.Unpacking &&
                        ui.bootstrap !is BootstrapState.Installing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Trigger bootstrap")
            }

            HorizontalDivider()

            Text("Launch test process", style = MaterialTheme.typography.labelMedium)
            Button(
                onClick = viewModel::launchTestProcess,
                enabled = !ui.launchRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Launch toybox echo")
            }

            // Dev manifest URL override
            OutlinedTextField(
                value = ui.devOverrideUrl,
                onValueChange = viewModel::setDevOverrideUrl,
                label = { Text("Dev manifest URL override (empty → production)") },
                placeholder = { Text("http://localhost:8000/manifest.json") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "Active manifest URL: ${ui.manifestUrl}",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()

            Text("Downloaded-binary exec", style = MaterialTheme.typography.labelMedium)
            Button(
                onClick = viewModel::runHello,
                enabled = !ui.launchRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Run hello (from bootstrap)")
            }
            Button(
                onClick = viewModel::runJavaVersion,
                enabled = !ui.launchRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Run java -version")
            }
            Button(
                onClick = viewModel::runGradleVersion,
                enabled = !ui.launchRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Run gradle --version")
            }

            if (ui.launchLog.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    ui.launchLog,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) { Text("Back") }
        }
    }
}

private fun BootstrapState.describe(): String = when (this) {
    is BootstrapState.NotInstalled -> "NotInstalled"
    is BootstrapState.Downloading -> "Downloading ${componentId}: $bytesRead/$totalBytes ($percent%)"
    is BootstrapState.Verifying -> "Verifying ${componentId}"
    is BootstrapState.Unpacking -> "Unpacking ${componentId}"
    is BootstrapState.Installing -> "Installing ${componentId}"
    is BootstrapState.Ready -> "Ready ($manifestVersion)"
    is BootstrapState.Failed -> "Failed: $reason"
    is BootstrapState.Corrupted -> "Corrupted: $reason"
}
