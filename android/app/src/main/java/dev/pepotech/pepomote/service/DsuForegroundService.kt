package dev.pepotech.pepomote.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.pepotech.pepomote.MainActivity
import dev.pepotech.pepomote.R
import dev.pepotech.pepomote.control.ButtonState
import dev.pepotech.pepomote.control.LocaleHelper
import dev.pepotech.pepomote.net.DsuServer
import dev.pepotech.pepomote.net.PartyGuest
import dev.pepotech.pepomote.net.PartyHost
import dev.pepotech.pepomote.net.PartyStore
import dev.pepotech.pepomote.sensor.MotionEngine
import dev.pepotech.pepomote.sensor.SenderKind

/**
 * Modo «Dolphin en Android»: este móvil es el mando Y el servidor DSU, y
 * Dolphin corre en OTRO dispositivo que se conecta por la Wi-Fi.
 *
 * La misma vida que [LinkForegroundService] —wakelock, WifiLock de baja
 * latencia, notificación en marcha y nada de zombis al cerrar la app— pero
 * sin nada de red PMP: los paquetes de [MotionEngine] van directos al
 * servidor DSU nativo por JNI, sin salir del proceso. Lo que sale a la red
 * son los PadData que manda el lado nativo.
 */
class DsuForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "dsu"
        private const val NOTIF_ID = 2
        private const val ACTION_STOP = "dev.pepotech.pepomote.STOP_DSU"

        /** Cada cuánto se releen los clientes DSU para el HUD. */
        private const val POLL_MS = 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DsuForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DsuForegroundService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var motion: MotionEngine? = null
    private var partyHost: PartyHost? = null
    private var partyGuest: PartyGuest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var poll: Runnable? = null
    private var lastClients = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Dos motores de sensores a la vez no: si había enlace con un PC, se
        // corta. Este modo no lo necesita para nada.
        LinkForegroundService.stop(this)

        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(getString(R.string.dsu_notif_serving)),
            if (Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )

        // El puerto se abre una vez por proceso; un segundo start() lo
        // encuentra abierto y sigue. Si no se pudo, no hay nada que mandar.
        val error = DsuServer.start()
        if (error != null) {
            DsuState.publish(DsuState.Ui.Failed(error))
            stopSelf()
            return START_NOT_STICKY
        }

        // Parar siempre la party anterior antes de crear otra: onStartCommand
        // puede reentrar sobre el mismo servicio vivo, y sin esto el bind del
        // 26761 fallaría, el fallo se tragaría en silencio y la party
        // quedaría muerta con el QR aún en pantalla.
        partyHost?.stop()
        partyHost = PartyHost().also { it.start() }
        partyGuest?.stop()
        partyGuest = PartyStore.last(this)?.let { invite ->
            PartyGuest(invite.host, invite.port).also { it.start() }
        }

        if (wakeLock?.isHeld != true) acquireLocks()
        ButtonState.reset()
        startMotion()
        startPolling()
        return START_NOT_STICKY
    }

    /** Cerrar/deslizar la app de recientes = dejar de mandar. Nada de zombis. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Sin sesión PMP no hay `session_id` que poner en el paquete: va a 0. Al
     * lado nativo no le importa (no hay sesiones que distinguir), y el
     * paquete sigue siendo un INPUT válido de 72 bytes.
     */
    private fun startMotion() {
        motion?.stop()
        val engine = MotionEngine(this, 0, SenderKind.WIIMOTE) { packet ->
            DsuServer.push(packet)
        }
        motion = engine
        // Las pantallas del mando leen el motor de aquí, venga de donde venga
        LinkState.motion = engine
        engine.start()
    }

    private fun startPolling() {
        cancelPolling()
        val ip = DsuServer.localIp()
        val task = object : Runnable {
            override fun run() {
                val clients = DsuServer.clients()
                DsuState.publish(DsuState.Ui.Serving(ip, DsuServer.PORT, clients))
                DsuState.guests.value = partyHost?.guests() ?: emptyList()
                DsuState.joinedAs.value = partyGuest?.player?.value
                DsuState.partyFull.value = partyGuest?.full?.value ?: false
                // La notificación solo se toca cuando cambia de verdad
                if (clients != lastClients) {
                    lastClients = clients
                    updateNotification(
                        if (clients > 0) getString(R.string.dsu_notif_connected)
                        else getString(R.string.dsu_notif_serving)
                    )
                }
                handler.postDelayed(this, POLL_MS)
            }
        }
        poll = task
        handler.post(task)
    }

    private fun cancelPolling() {
        poll?.let { handler.removeCallbacks(it) }
        poll = null
    }

    override fun onDestroy() {
        cancelPolling()
        motion?.stop()
        motion = null
        LinkState.motion = null
        partyHost?.stop()
        partyHost = null
        partyGuest?.stop()
        partyGuest = null
        // Si no se limpian, la pantalla seguiría enseñando la lista de
        // invitados y escondiendo los INI después de parar.
        DsuState.guests.value = emptyList()
        DsuState.joinedAs.value = null
        DsuState.partyFull.value = false
        wakeLock?.release()
        wifiLock?.release()
        // El puerto sigue abierto (el servidor nativo vive lo que el
        // proceso), pero ya no se le empuja nada: para Dolphin el pad se
        // queda sin muestras y lo da por desconectado en 1 s.
        DsuState.publish(DsuState.Ui.Off)
        super.onDestroy()
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pepomote:dsu").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L) // tope 4 h por seguridad
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "pepomote:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Canal propio: este modo no es «enlace con el PC», es justo lo contrario
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.dsu_card_title), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): android.app.Notification {
        val pi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = android.app.PendingIntent.getService(
            this, 1,
            Intent(this, DsuForegroundService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pepomote_glyph)
            .setContentTitle("PepoMote")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(0, getString(R.string.notif_disconnect), stopPi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
