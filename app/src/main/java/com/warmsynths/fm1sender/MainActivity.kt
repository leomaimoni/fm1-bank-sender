package com.warmsynths.fm1sender

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.midi.MidiDeviceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class BankFile(val uri: Uri, val name: String)

private const val ACTION_USB_PERMISSION = "com.warmsynths.fm1sender.USB_PERMISSION"
private const val CRASH_PREFS = "crash_log"
private const val CRASH_KEY = "last_crash"

class MainActivity : ComponentActivity() {

    private lateinit var midiHelper: MidiUsbHelper
    private lateinit var usbManager: UsbManager

    private var pendingUsbPermissionCallback: ((Boolean) -> Unit)? = null

    private var banks by mutableStateOf<List<BankFile>>(emptyList())
    private var usbDevices by mutableStateOf<List<MidiDeviceInfo>>(emptyList())
    private var connectedDeviceName by mutableStateOf<String?>(null)
    private var statusMessage by mutableStateOf("Selecione os bancos e conecte o FM-1 pelo cabo USB.")
    private var isSending by mutableStateOf(false)
    private var lastCrashLog by mutableStateOf<String?>(null)

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                if (intent.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val callback = pendingUsbPermissionCallback
                pendingUsbPermissionCallback = null
                callback?.invoke(granted)
            } catch (e: Throwable) {
                statusMessage = "Erro ao processar permissão: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newOnes = uris.map { uri ->
                val name = queryFileName(uri) ?: uri.lastPathSegment ?: "banco.syx"
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) { /* alguns provedores não suportam, tudo bem */ }
                BankFile(uri, name)
            }
            val existingUris = banks.map { it.uri }.toSet()
            banks = banks + newOnes.filter { it.uri !in existingUris }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Rede de segurança: se o app crashar em algum momento, guardamos o stack trace
        // em disco para conseguirmos mostrar na tela na próxima abertura — sem precisar
        // de Android Studio/logcat para depurar.
        val crashPrefs = getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
        val previousCrash = crashPrefs.getString(CRASH_KEY, null)
        if (previousCrash != null) {
            crashPrefs.edit().remove(CRASH_KEY).apply()
        }
        val existingHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashPrefs.edit().putString(CRASH_KEY, throwable.stackTraceToString()).apply()
            } catch (_: Throwable) { /* nada mais a fazer aqui */ }
            existingHandler?.uncaughtException(thread, throwable)
        }

        super.onCreate(savedInstanceState)
        lastCrashLog = previousCrash

        midiHelper = MidiUsbHelper(this)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        try {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(usbPermissionReceiver, filter)
            }
        } catch (e: Throwable) {
            statusMessage = "Erro ao registrar receiver USB: ${e.message}"
        }

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
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) { }
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

    /**
     * Tenta achar o UsbDevice físico por trás de um MidiDeviceInfo, para podermos
     * checar/pedir a permissão de acesso USB antes de abrir a porta MIDI.
     * Qualquer falha aqui apenas retorna null (o app segue tentando conectar direto).
     */
    private fun findUsbDeviceFor(deviceInfo: MidiDeviceInfo): UsbDevice? {
        return try {
            val direct: UsbDevice? = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    deviceInfo.properties.getParcelable(
                        MidiDeviceInfo.PROPERTY_USB_DEVICE, UsbDevice::class.java
                    )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    @Suppress("DEPRECATION")
                    deviceInfo.properties.getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE)
                else -> null
            }
            if (direct != null) return direct

            val candidates = usbManager.deviceList.values.toList()
            if (candidates.size == 1) return candidates.first()

            val product = deviceInfo.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            val name = deviceInfo.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            candidates.firstOrNull { it.productName == product || it.productName == name }
        } catch (e: Throwable) {
            statusMessage = "Aviso: não consegui identificar o UsbDevice (${e.message}). Tentando mesmo assim..."
            null
        }
    }

    private fun requestUsbPermission(usbDevice: UsbDevice, callback: (Boolean) -> Unit) {
        try {
            pendingUsbPermissionCallback = callback
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
            usbManager.requestPermission(usbDevice, permissionIntent)
        } catch (e: Throwable) {
            statusMessage = "Erro ao pedir permissão USB: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun connectTo(device: MidiDeviceInfo) {
        try {
            val label = device.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: device.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
                ?: "Dispositivo USB"

            val usbDevice = findUsbDeviceFor(device)

            if (usbDevice != null && !usbManager.hasPermission(usbDevice)) {
                statusMessage = "Pedindo permissão de acesso ao USB... aceite o diálogo que vai aparecer."
                requestUsbPermission(usbDevice) { granted ->
                    if (granted) {
                        openMidiDevice(device, label)
                    } else {
                        statusMessage = "Permissão USB negada. Toque em Conectar de novo e aceite a permissão desta vez."
                    }
                }
            } else {
                openMidiDevice(device, label)
            }
        } catch (e: Throwable) {
            statusMessage = "Erro ao conectar: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun openMidiDevice(device: MidiDeviceInfo, label: String) {
        try {
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
        } catch (e: Throwable) {
            statusMessage = "Erro ao abrir dispositivo MIDI: ${e.javaClass.simpleName}: ${e.message}"
        }
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

            lastCrashLog?.let { crash ->
                Spacer(Modifier.height(8.dp))
                Card {
                    Column(
                        modifier = Modifier
                            .padding(8.dp)
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "O app fechou sozinho da última vez. Detalhes (tire um print e me envie):",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(crash, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { lastCrashLog = null }) {
                    Text("Dispensar")
                }
            }

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
