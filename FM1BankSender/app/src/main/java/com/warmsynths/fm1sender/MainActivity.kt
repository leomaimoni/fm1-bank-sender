package com.warmsynths.fm1sender

import android.media.midi.MidiDeviceInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class BankFile(val uri: Uri, val name: String)

class MainActivity : ComponentActivity() {

    private lateinit var midiHelper: MidiUsbHelper

    private var banks by mutableStateOf<List<BankFile>>(emptyList())
    private var usbDevices by mutableStateOf<List<MidiDeviceInfo>>(emptyList())
    private var connectedDeviceName by mutableStateOf<String?>(null)
    private var statusMessage by mutableStateOf("Selecione os bancos e conecte o FM-1 pelo cabo USB.")
    private var isSending by mutableStateOf(false)

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newOnes = uris.mapNotNull { uri ->
                val name = queryFileName(uri) ?: uri.lastPathSegment ?: "banco.syx"
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) { /* alguns provedores não suportam, tudo bem */ }
                BankFile(uri, name)
            }
            // evita duplicados
            val existingUris = banks.map { it.uri }.toSet()
            banks = banks + newOnes.filter { it.uri !in existingUris }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        midiHelper = MidiUsbHelper(this)
        refreshUsbDevices()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUsbDevices()
    }

    override fun onDestroy() {
        midiHelper.close()
        super.onDestroy()
    }

    private fun refreshUsbDevices() {
        usbDevices = midiHelper.listUsbDevices()
    }

    private fun queryFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun connectTo(device: MidiDeviceInfo) {
        val label = device.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: device.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "Dispositivo USB"
        statusMessage = "Conectando a $label..."
        midiHelper.connect(
            device,
            onConnected = {
                connectedDeviceName = label
                statusMessage = "Conectado a $label. Pronto para enviar."
            },
            onError = { err ->
                connectedDeviceName = null
                statusMessage = err
            }
        )
    }

    private fun sendBank(bank: BankFile) {
        if (!midiHelper.isConnected) {
            statusMessage = "Conecte o FM-1 antes de enviar."
            return
        }
        isSending = true
        statusMessage = "Enviando ${bank.name}..."
        try {
            val bytes = contentResolver.openInputStream(bank.uri)?.use { it.readBytes() }
            if (bytes == null) {
                statusMessage = "Não foi possível ler o arquivo."
                isSending = false
                return
            }
            midiHelper.sendSysEx(bytes) { success, message ->
                statusMessage = message
                isSending = false
            }
        } catch (e: Exception) {
            statusMessage = "Erro ao ler o arquivo: ${e.message}"
            isSending = false
        }
    }

    @Composable
    private fun AppScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                "FM1 Bank Sender",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))

            Text("Dispositivo MIDI (USB)", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            if (usbDevices.isEmpty()) {
                Text("Nenhum dispositivo USB-MIDI encontrado. Verifique o cabo OTG e ligue o FM-1.")
            } else {
                usbDevices.forEach { device ->
                    val label = device.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                        ?: device.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
                        ?: "Dispositivo USB #${device.id}"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Text(label, modifier = Modifier.weight(1f))
                        Button(onClick = { connectTo(device) }) {
                            Text(if (connectedDeviceName == label) "Reconectar" else "Conectar")
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { refreshUsbDevices() }) {
                Text("Atualizar lista de dispositivos")
            }

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bancos (.syx)", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Button(onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                    Text("Adicionar arquivos")
                }
            }
            Spacer(Modifier.height(8.dp))

            if (banks.isEmpty()) {
                Text("Nenhum banco adicionado ainda. Toque em \"Adicionar arquivos\" e escolha seus .syx.")
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(banks) { bank ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Text(bank.name, modifier = Modifier.weight(1f))
                            Button(
                                onClick = { sendBank(bank) },
                                enabled = !isSending && midiHelper.isConnected
                            ) {
                                Text("Enviar")
                            }
                        }
                    }
                }
            }
        }
    }
}
