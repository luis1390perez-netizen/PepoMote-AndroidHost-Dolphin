package dev.pepotech.pepomote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.view.InputDevice
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.concurrent.thread
import kotlin.math.PI

class LocalDsuGamepadService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "pepomote_local_dsu"
        const val DSU_PORT = 26760
        private const val DSU_PROTOCOL_VERSION: Short = 1001
        private const val MSG_TYPE_PORTS = 0x100001
        private const val MSG_TYPE_DATA = 0x100002

        @Volatile var virtualButtonsMask1: Byte = 0
        @Volatile var virtualButtonsMask2: Byte = 0
        @Volatile var leftStickX: Byte = 128.toByte()
        @Volatile var leftStickY: Byte = 128.toByte()
        @Volatile var rightStickX: Byte = 128.toByte()
        @Volatile var rightStickY: Byte = 128.toByte()
        @Volatile var shakeBoostG: Float = 0f
    }

    private var running = false
    private var udpSocket: DatagramSocket? = null
    private var activeSensorManager: SensorManager? = null

    @Volatile private var accelX = 0f
    @Volatile private var accelY = -1f
    @Volatile private var accelZ = 0f

    @Volatile private var gyroPitch = 0f
    @Volatile private var gyroYaw = 0f
    @Volatile private var gyroRoll = 0f

    // Amplificador x18 para que el giroscopio del mando G6 mueva rápido el puntero en Mario Galaxy
    var gyroAmplifier = 18.0f

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        attachControllerSensors()
        startDsuUdpServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        attachControllerSensors()
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "PepoMote Puente DSU Local (127.0.0.1)",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(chan)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("PepoMote DSU Activo (127.0.0.1:26760)")
            .setContentText("Giroscopio del mando G6 conectado a Dolphin")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()

        startForeground(1001, notification)
    }

    fun attachControllerSensors() {
        var foundExternal = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (id in InputDevice.getDeviceIds()) {
                val dev = InputDevice.getDevice(id) ?: continue
                if (dev.name.contains("Wireless Controller", ignoreCase = true) ||
                    dev.name.contains("Touchpad", ignoreCase = true) ||
                    dev.name.contains("Pro Controller", ignoreCase = true)
                ) {
                    val sm = dev.sensorManager
                    val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                    val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                    if (gyro != null || accel != null) {
                        activeSensorManager?.unregisterListener(this)
                        activeSensorManager = sm
                        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
                        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
                        foundExternal = true
                        break
                    }
                }
            }
        }

        if (!foundExternal) {
            val sysSm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            activeSensorManager?.unregisterListener(this)
            activeSensorManager = sysSm
            sysSm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                sysSm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            sysSm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sysSm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val g = 9.80665f
                val boost = shakeBoostG
                accelX = -(event.values[0] / g) + boost
                accelY = -(event.values[2] / g) + boost
                accelZ = (event.values[1] / g) + boost
            }
            Sensor.TYPE_GYROSCOPE -> {
                val radToDeg = (180.0 / PI).toFloat() * gyroAmplifier
                gyroPitch = event.values[0] * radToDeg
                gyroYaw = -event.values[2] * radToDeg
                gyroRoll = event.values[1] * radToDeg
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startDsuUdpServer() {
        if (running) return
        running = true
        thread(start = true, name = "PepoMote-LocalDSU") {
            try {
                val socket = DatagramSocket(DSU_PORT, InetAddress.getByName("127.0.0.1"))
                socket.soTimeout = 2000
                udpSocket = socket

                var clientAddress: InetAddress? = null
                var clientPort: Int = 0
                var lastSubscriberTime = 0L
                var packetCounter = 0

                thread(start = true, name = "PepoMote-DSU-Sender") {
                    while (running) {
                        val addr = clientAddress
                        if (addr != null && (System.currentTimeMillis() - lastSubscriberTime) < 10000) {
                            val dataPacket = buildControllerDataResponse(packetCounter++)
                            try {
                                socket.send(DatagramPacket(dataPacket, dataPacket.size, addr, clientPort))
                            } catch (_: Exception) {}
                        }
                        Thread.sleep(10)
                    }
                }

                val recvBuf = ByteArray(1024)
                while (running) {
                    try {
                        val pkt = DatagramPacket(recvBuf, recvBuf.size)
                        socket.receive(pkt)
                        if (pkt.length >= 20 &&
                            recvBuf[0] == 'D'.code.toByte() &&
                            recvBuf[1] == 'S'.code.toByte() &&
                            recvBuf[2] == 'U'.code.toByte() &&
                            recvBuf[3] == 'C'.code.toByte()
                        ) {
                            val bb = ByteBuffer.wrap(recvBuf, 0, pkt.length).order(ByteOrder.LITTLE_ENDIAN)
                            val msgType = bb.getInt(16)
                            when (msgType) {
                                MSG_TYPE_PORTS -> {
                                    val resp = buildSlotInfoResponse(0)
                                    socket.send(DatagramPacket(resp, resp.size, pkt.address, pkt.port))
                                }
                                MSG_TYPE_DATA -> {
                                    clientAddress = pkt.address
                                    clientPort = pkt.port
                                    lastSubscriberTime = System.currentTimeMillis()
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun buildSlotInfoResponse(slot: Int): ByteArray {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        writeHeader(buf, 12, MSG_TYPE_PORTS)
        buf.put(slot.toByte())
        buf.put(2.toByte())
        buf.put(2.toByte())
        buf.put(2.toByte())
        buf.put(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), slot.toByte()))
        buf.put(0x05.toByte())
        buf.put(0.toByte())
        finalizeCrc(buf)
        return buf.array()
    }

    private fun buildControllerDataResponse(counter: Int): ByteArray {
        val buf = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN)
        writeHeader(buf, 80, MSG_TYPE_DATA)
        buf.put(0.toByte())
        buf.put(2.toByte())
        buf.put(2.toByte())
        buf.put(2.toByte())
        buf.put(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0x00))
        buf.put(0x05.toByte())
        buf.put(1.toByte())
        buf.putInt(counter)
        buf.put(virtualButtonsMask1)
        buf.put(virtualButtonsMask2)
        buf.put(0.toByte())
        buf.put(0.toByte())
        buf.put(leftStickX)
        buf.put(leftStickY)
        buf.put(rightStickX)
        buf.put(rightStickY)
        for (i in 0 until 24) buf.put(0.toByte())
        buf.putLong(System.nanoTime() / 1000L)
        buf.putFloat(accelX)
        buf.putFloat(accelY)
        buf.putFloat(accelZ)
        buf.putFloat(gyroPitch)
        buf.putFloat(gyroYaw)
        buf.putFloat(gyroRoll)
        finalizeCrc(buf)
        return buf.array()
    }

    private fun writeHeader(buf: ByteBuffer, payloadLen: Int, msgType: Int) {
        buf.put('D'.code.toByte())
        buf.put('S'.code.toByte())
        buf.put('U'.code.toByte())
        buf.put('S'.code.toByte())
        buf.putShort(DSU_PROTOCOL_VERSION)
        buf.putShort((payloadLen + 4).toShort())
        buf.putInt(0)
        buf.putInt(0x5045504F)
        buf.putInt(msgType)
    }

    private fun finalizeCrc(buf: ByteBuffer) {
        val bytes = buf.array()
        val crc = CRC32()
        crc.update(bytes, 0, bytes.size)
        buf.putInt(8, crc.value.toInt())
    }

    override fun onDestroy() {
        running = false
        udpSocket?.close()
        activeSensorManager?.unregisterListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
