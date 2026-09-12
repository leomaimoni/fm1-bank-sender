package com.warmsynths.fm1sender

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper

/**
 * Encapsula a comunicação com dispositivos MIDI conectados via USB.
 *
 * O Android já reconhece automaticamente qualquer periférico MIDI
 * classe-compliant plugado via USB-OTG e o expõe através do MidiManager,
 * então não é preciso lidar com UsbManager/UsbDevice diretamente.
 */
class MidiUsbHelper(context: Context) {

    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var openDevice: MidiDevice? = null
    private var inputPort: MidiInputPort? = null

    /** Lista os dispositivos MIDI USB atualmente conectados. */
    fun listUsbDevices(): List<MidiDeviceInfo> {
        return midiManager.devices.filter { it.type == MidiDeviceInfo.TYPE_USB }
    }

    /**
     * Abre o dispositivo escolhido e guarda a primeira porta de entrada dele
     * (a porta pela qual NÓS enviamos dados PARA o instrumento).
     */
    fun connect(
        deviceInfo: MidiDeviceInfo,
        onConnected: () -> Unit,
        onError: (String) -> Unit
    ) {
        close()
        midiManager.openDevice(deviceInfo, { device ->
            if (device == null) {
                onError("Não foi possível abrir o dispositivo. Verifique o cabo OTG e se o FM-1 está ligado.")
                return@openDevice
            }
            val portInfo = deviceInfo.ports.firstOrNull {
                it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT
            }
            if (portInfo == null) {
                onError("O dispositivo não expõe uma porta de entrada MIDI.")
                return@openDevice
            }
            val port = device.openInputPort(portInfo.portNumber)
            if (port == null) {
                onError("Não foi possível abrir a porta MIDI de entrada.")
                return@openDevice
            }
            openDevice = device
            inputPort = port
            onConnected()
        }, mainHandler)
    }

    val isConnected: Boolean
        get() = inputPort != null

    /**
     * Envia os bytes brutos do arquivo .syx (incluindo os bytes 0xF0 ... 0xF7)
     * para o dispositivo conectado.
     */
    fun sendSysEx(bytes: ByteArray, onResult: (Boolean, String) -> Unit) {
        val port = inputPort
        if (port == null) {
            onResult(false, "Nenhum dispositivo MIDI conectado.")
            return
        }
        if (bytes.isEmpty() || bytes.first() != 0xF0.toByte() || bytes.last() != 0xF7.toByte()) {
            onResult(false, "O arquivo selecionado não parece ser um SysEx válido (deve começar com F0 e terminar com F7).")
            return
        }
        try {
            // Envia em blocos para não estourar o buffer interno do driver USB-MIDI.
            val chunkSize = 512
            var offset = 0
            while (offset < bytes.size) {
                val len = minOf(chunkSize, bytes.size - offset)
                port.send(bytes, offset, len)
                offset += len
                // Pequena pausa ajuda alguns adaptadores/OTGs a não perderem bytes.
                Thread.sleep(2)
            }
            onResult(true, "Banco enviado (${bytes.size} bytes).")
        } catch (e: Exception) {
            onResult(false, "Erro ao enviar: ${e.message}")
        }
    }

    fun close() {
        inputPort?.close()
        openDevice?.close()
        inputPort = null
        openDevice = null
    }
}
