package dev.pepotech.pepomote.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pepotech.pepomote.R
import dev.pepotech.pepomote.net.DsuServer
import dev.pepotech.pepomote.service.DsuState
import dev.pepotech.pepomote.ui.theme.PepoColors

/**
 * Modo «Dolphin en Android»: este móvil sirve DSU y Dolphin corre en otro
 * dispositivo. Aquí se ve la IP a la que tiene que apuntar Dolphin, los dos
 * INI que hay que dejarle (que no podemos escribir nosotros: la carpeta de
 * Dolphin es almacenamiento privado de su app) y, lo más importante, si
 * Dolphin ya está leyendo de nosotros.
 */
@Composable
fun DolphinAndroidScreen(
    onPlay: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by DsuState.flow.collectAsState()
    val serving = state as? DsuState.Ui.Serving
    val ip = serving?.ip ?: DsuServer.localIp()
    val clients = serving?.clients ?: 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PepoColors.Background)
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.dsu_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        // --- estado: la IP y si Dolphin nos está leyendo
        InfoCard {
            when {
                state is DsuState.Ui.Failed ->
                    Text(
                        stringResource(R.string.dsu_error, (state as DsuState.Ui.Failed).error),
                        style = MaterialTheme.typography.bodyMedium
                    )

                ip == null ->
                    Text(stringResource(R.string.dsu_no_ip), style = MaterialTheme.typography.bodyMedium)

                else -> {
                    Text(
                        stringResource(R.string.dsu_serving, ip, DsuServer.PORT),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (clients > 0) stringResource(R.string.dsu_connected, clients)
                        else stringResource(R.string.dsu_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (clients > 0) PepoColors.Ok else PepoColors.Blue
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = if (serving == null) onStart else onStop,
                colors = ButtonDefaults.buttonColors(containerColor = PepoColors.Blue)
            ) {
                Text(stringResource(if (serving == null) R.string.dsu_start else R.string.dsu_stop))
            }
            if (serving != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onPlay) { Text(stringResource(R.string.dsu_play)) }
            }
        }

        // --- lo que hay que dejarle a Dolphin, una vez
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.dsu_setup_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.dsu_setup_intro), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Mono(stringResource(R.string.dsu_setup_path))

        Spacer(Modifier.height(16.dp))
        FileCard(
            name = "DSUClient.ini",
            body = DsuServer.dsuClientIni(ip ?: "0.0.0.0"),
            context = context
        )
        Spacer(Modifier.height(12.dp))
        FileCard(name = "WiimoteNew.ini", body = DsuServer.wiimoteIni(), context = context)

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.dsu_setup_then), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.dsu_ip_warning), style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) { Text(stringResource(R.string.back), color = PepoColors.TextDim) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = PepoColors.Card),
        border = BorderStroke(1.5.dp, PepoColors.CardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) { content() }
    }
}

/** Un archivo listo para copiar: su contenido tal cual, y los dos botones. */
@Composable
private fun FileCard(name: String, body: String, context: Context) {
    InfoCard {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Mono(body)
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = { copy(context, name, body) }) {
                Text(stringResource(R.string.dsu_copy))
            }
            TextButton(onClick = { share(context, name, body) }) {
                Text(stringResource(R.string.dsu_share))
            }
        }
    }
}

/**
 * Monoespaciado y con scroll horizontal propio: son rutas y líneas de INI
 * que no se pueden partir sin dejar de servir para copiarlas.
 */
@Composable
private fun Mono(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    )
}

private fun copy(context: Context, label: String, body: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText(label, body))
    // Android 13+ enseña su propio aviso de «copiado»; antes, nada
    if (android.os.Build.VERSION.SDK_INT < 33) {
        android.widget.Toast.makeText(context, R.string.dsu_copied, android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: Context, name: String, body: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, name)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(Intent.createChooser(intent, name))
}
