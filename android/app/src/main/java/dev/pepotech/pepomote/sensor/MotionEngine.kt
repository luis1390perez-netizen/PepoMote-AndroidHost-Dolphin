package dev.pepotech.pepomote.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.InputDevice
import android.view.Surface
import dev.pepotech.pepomote.control.ButtonState
import dev.pepotech.pepomote.net.PmpCodec

/** Qué emula este móvil: decide qué lleva cada paquete INPUT. */
enum class SenderKind {
    /** Wiimote (puntero / Dolphin): 72 bytes, sin stick. */
    WIIMOTE,

    /** Nunchuk: 72 bytes, stick en los bytes 6-7 (FLAG_STICK_VALID). */
    NUNCHUK,

    /**
     * GamePad / Pro Controller de Wii U (Cemu): 80 bytes con el bloque de
     * extensión (stick derecho + táctil) y los sensores remapeados al marco
     * del mando apaisado ([Frame]).
     */
    GAMEPAD
}

/**
 * Sensores a máxima frecuencia. La cadencia de envío la marca el gyro:
 * un paquete INPUT por muestra de gyro (tope natural del hardware).
 */
class MotionEngine(
    context: Context,
    private val sessionId: Int,
    kind: SenderKind = SenderKind.WIIMOTE,
    private val onPacket: (ByteArray) -> Unit
) : SensorEventListener {

    @Volatile
    var kind: SenderKind = kind

    @Volatile
    var rotation: Int = Surface.ROTATION_0

    private val systemSensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var activeSensorManager: SensorManager = systemSensorManager

    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private val thread = HandlerThread("pepomote-sensors").apply { start() }
    private val handler = Handler(thread.looper)

    private val quat = floatArrayOf(1f, 0f, 0f, 0f) // w, x, y, z
    private val gyro = FloatArray(3)
    private val accel = FloatArray(3)

    private val quatOut = FloatArray(4)
    private val gyroOut = FloatArray(3)
    private val accelOut = FloatArray(3)

    // Amplificador x18 para que el giroscopio mueva rápido el puntero en Mario Galaxy
    private val gyroAmplifier = 18.0f

    private var seq = 0
    private var hasRotationVector = false
    private var lastSendNs = 0L
    private val gyroSum = FloatArray(3)
    private var gyroSumNs = 0L
    private var lastGyroNs = 0L

    private var batteryPct = 100
    private var batteryReadAtMs = 0L

    @Volatile
    var lastSensorHz = 0f
        private set
    private var hzWindowStartNs = 0L
    private var hzCount = 0

    private val scratch = FloatArray(4)

    private fun findBestSensorManager(): SensorManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (id in InputDevice.getDeviceIds()) {
                val dev = InputDevice.getDevice(id) ?: continue
                if (dev.name.contains("Wireless Controller", ignoreCase = true) ||
                    dev.name.contains("Touchpad", ignoreCase = true) ||
                    dev.name.contains("Pro Controller", ignoreCase = true) ||
                    dev.name.contains("G6", ignoreCase = true) ||
                    dev.name.contains("Gamepad", ignoreCase = true)
                ) {
                    val sm = dev.sensorManager
                    val g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                    val a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                    if (g != null || a != null) {
                        return sm
                    }
                }
            }
        }
        return systemSensorManager
    }

    fun start() {
        activeSensorManager = findBestSensorManager()
        val gyroSensor = activeSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: systemSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accelSensor = activeSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: systemSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val rotSensor = activeSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        hasRotationVector = rotSensor != null

        gyroSensor?.let {
            activeSensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
        }
        accelSensor?.let {
            activeSensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
        }
        rotSensor?.let {
            activeSensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
        }
    }

    fun stop() {
        try {
            activeSensorManager.unregisterListener(this)
        } catch (_: Exception) {}
        try {
            systemSensorManager.unregisterListener(this)
        } catch (_: Exception) {}
        thread.quitSafely()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getQuaternionFromVector(scratch, event.values)
                quat[0] = scratch[0]; quat[1] = scratch[1]
                quat[2] = scratch[2]; quat[3] = scratch[3]
            }

            Sensor.TYPE_ACCELEROMETER -> {
                accel[0] = event.values[0]
                accel[1] = event.values[1]
                accel[2] = event.values[2]
            }

            Sensor.TYPE_GYROSCOPE -> {
                trackHz(event.timestamp)
                val gx = event.values[0] * gyroAmplifier
                val gy = event.values[1] * gyroAmplifier
                val gz = event.values[2] * gyroAmplifier

                val gap = event.timestamp - lastGyroNs
                if (lastGyroNs != 0L && gap in 1..50_000_000L) {
                    gyroSum[0] += gx * gap
                    gyroSum[1] += gy * gap
                    gyroSum[2] += gz * gap
                    gyroSumNs += gap
                } else {
                    gyroSum.fill(0f); gyroSumNs = 0L
                }
                lastGyroNs = event.timestamp

                if (event.timestamp - lastSendNs >= 3_900_000L) {
                    lastSendNs = event.timestamp
                    if (gyroSumNs > 0L) {
                        for (i in 0..2) gyro[i] = gyroSum[i] / gyroSumNs
                    } else {
                        gyro[0] = gx
                        gyro[1] = gy
                        gyro[2] = gz
                    }
                    gyroSum.fill(0f); gyroSumNs = 0L
                    sendPacket(event.timestamp)
                }
            }
        }
    }

    private fun sendPacket(tSensorNs: Long) {
        seq++
        val quatFlag = if (hasRotationVector) PmpCodec.FLAG_QUAT_VALID else 0
        val packet = when (kind) {
            SenderKind.GAMEPAD -> {
                val rot = rotation
                Frame.remapQuat(quat, rot, quatOut)
                Frame.remapGyro(gyro, rot, gyroOut)
                Frame.remapAccel(accel, rot, accelOut)
                val touch = ButtonState.touch()
                PmpCodec.encodeInput(
                    sessionId = sessionId,
                    seq = seq,
                    tSensorUs = tSensorNs / 1000,
                    quat = quatOut,
                    gyro = gyroOut,
                    accel = accelOut,
                    buttons = ButtonState.current(),
                    recenterCount = ButtonState.recenterCount(),
                    batteryPct = battery(),
                    touchScrollDy = ButtonState.drainScroll(),
                    flags = quatFlag or PmpCodec.FLAG_STICK_VALID or PmpCodec.FLAG_EXT or
                        (if (touch.down) PmpCodec.FLAG_TOUCH else 0),
                    stickX = ButtonState.stickX(),
                    stickY = ButtonState.stickY(),
                    stick2X = ButtonState.stickRX(),
                    stick2Y = ButtonState.stickRY(),
                    touchX = touch.x,
                    touchY = touch.y
                )
            }

            SenderKind.NUNCHUK -> PmpCodec.encodeInput(
                sessionId = sessionId,
                seq = seq,
                tSensorUs = tSensorNs / 1000,
                quat = quat,
                gyro = gyro,
                accel = accel,
                buttons = ButtonState.current(),
                recenterCount = ButtonState.recenterCount(),
                batteryPct = battery(),
                touchScrollDy = ButtonState.drainScroll(),
                flags = quatFlag or PmpCodec.FLAG_STICK_VALID,
                stickX = ButtonState.stickX(),
                stickY = ButtonState.stickY()
            )

            SenderKind.WIIMOTE -> PmpCodec.encodeInput(
                sessionId = sessionId,
                seq = seq,
                tSensorUs = tSensorNs / 1000,
                quat = quat,
                gyro = gyro,
                accel = accel,
                buttons = ButtonState.current(),
                recenterCount = ButtonState.recenterCount(),
                batteryPct = battery(),
                touchScrollDy = ButtonState.drainScroll(),
                flags = quatFlag
            )
        }
        onPacket(packet)
    }

    private fun trackHz(tNs: Long) {
        if (hzWindowStartNs == 0L) hzWindowStartNs = tNs
        hzCount++
        val elapsed = tNs - hzWindowStartNs
        if (elapsed > 1_000_000_000L) {
            lastSensorHz = hzCount * 1e9f / elapsed
            hzWindowStartNs = tNs
            hzCount = 0
        }
    }

    private fun battery(): Int {
        val now = SystemClock.elapsedRealtime()
        if (now - batteryReadAtMs > 5000) {
            batteryReadAtMs = now
            val pct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 0..100) batteryPct = pct
        }
        return batteryPct
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
