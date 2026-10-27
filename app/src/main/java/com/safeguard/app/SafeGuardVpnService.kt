package com.safeguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class SafeGuardVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    private val dnsAddress = byteArrayOf(10, 111, 222.toByte(), 1)
    private val upstreamAddress = byteArrayOf(185.toByte(), 228.toByte(), 168.toByte(), 168.toByte())

    private val diagnostics by lazy { getSharedPreferences("safeguard_diagnostics", MODE_PRIVATE) }
    private var floatingIcon: ImageView? = null
    private var windowManager: WindowManager? = null

    override fun onCreate() {
        super.onCreate()
        diagnostics.edit().putLong("last_service_create", System.currentTimeMillis())
            .putString("last_service_lifecycle_event", "onCreate").apply()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        diagnostics.edit().putLong("last_start", System.currentTimeMillis())
            .putLong("last_on_start_command", System.currentTimeMillis())
            .putInt("last_start_id", startId)
            .putString("last_start_flags", "flags=$flags, startId=$startId, intentNull=${intent == null}")
            .putString("last_service_lifecycle_event", "onStartCommand").apply()
        startForeground(7401, notification("מנסה להתחבר ל־VPN"))
        showFloatingIconIfAllowed()
        // START_STICKY may recreate the service with a null intent. Do not trust
        // the running flag alone: it can be stale if a worker exited unexpectedly.
        // Service callbacks run on the main thread, so this check prevents duplicate
        // workers while allowing a dead worker to be restarted.
        if (worker?.isAlive != true) {
            running.set(true)
            diagnostics.edit()
                .putLong("last_worker_launch", System.currentTimeMillis())
                .putString("worker_launch_reason", if (intent == null) "START_STICKY עם intent ריק" else "בקשת הפעלה של השירות")
                .putBoolean("vpn_worker_running", false)
                .putString("vpn_state", "עובד VPN מתחיל לפעול")
                .apply()
            worker = thread(name = "SafeGuard-DNS") { runVpn() }
        } else {
            diagnostics.edit().putLong("last_duplicate_start", System.currentTimeMillis())
                .putString("vpn_state", "התקבלה בקשת הפעלה; עובד VPN כבר פעיל").apply()
        }
        return START_STICKY
    }

    private fun notification(message: String): Notification {
        val channelId = "safeguard_protection"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channelId, "הגנת SafeGuard", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channelId) else Notification.Builder(this)
        return builder.setContentTitle("SafeGuard")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_safeguard)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(7401, notification(message))
        } catch (_: Exception) {}
    }

    private fun showFloatingIconIfAllowed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !android.provider.Settings.canDrawOverlays(this)) return
        if (floatingIcon != null) return
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val size = (44 * resources.displayMetrics.density).toInt()
            val icon = ImageView(this).apply {
                setImageResource(R.drawable.ic_safeguard)
                alpha = 0.38f
                contentDescription = "SafeGuard — סינון פעיל"
                setOnClickListener {
                    try {
                        val launch = packageManager.getLaunchIntentForPackage(packageName)
                        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (launch != null) startActivity(launch)
                    } catch (_: Exception) {}
                }
            }
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                size, size, windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                x = (8 * resources.displayMetrics.density).toInt()
                y = 0
            }
            windowManager?.addView(icon, params)
            floatingIcon = icon
        } catch (e: Exception) {
            diagnostics.edit().putLong("last_error_time", System.currentTimeMillis())
                .putString("last_error", "שגיאת סמל צף: " + e.javaClass.simpleName + ": " + (e.message ?: "ללא פרטים")).apply()
        }
    }

    private fun removeFloatingIcon() {
        try { floatingIcon?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        floatingIcon = null
        windowManager = null
    }

    /**
     * Android normally keeps a started foreground service alive when its task is
     * removed. Some device vendors aggressively stop app processes on this event.
     * Record the event and reassert the foreground-service start; START_STICKY remains
     * the fallback if Android later recreates the process.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing the activity from Recents is not itself proof that the VPN stopped.
        // Keep vpn_state intact; it describes the VPN worker, not the activity task.
        val workerAlive = worker?.isAlive == true
        val tunOpen = try { tun?.fileDescriptor?.valid() == true } catch (_: Exception) { false }
        diagnostics.edit()
            .putLong("last_task_removed", System.currentTimeMillis())
            .putString("last_task_removed_note", "SafeGuard הוסר ממסך האפליקציות האחרונות")
            .putBoolean("worker_alive_at_task_removed", workerAlive)
            .putBoolean("tun_open_at_task_removed", tunOpen)
            .putString("task_removed_snapshot", "workerAlive=" + workerAlive + ", tunOpen=" + tunOpen + ", running=" + running.get())
            .apply()

        // If the task is removed after the worker died, ask Android to deliver a
        // fresh start to this foreground service. onStartCommand owns worker creation.
        // Merely setting running=true is not enough when the previous worker is gone.
        if (!workerAlive) {
            try {
                diagnostics.edit()
                    .putLong("last_task_recovery_attempt", System.currentTimeMillis())
                    .putString("vpn_state", "מבקש הפעלה מחדש של שירות ה־VPN לאחר הסרת האפליקציה")
                    .apply()
                val restartIntent = Intent(this, SafeGuardVpnService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restartIntent)
                } else {
                    startService(restartIntent)
                }
                diagnostics.edit()
                    .putString("last_task_recovery_result", "בקשת startService נשלחה; הצלחת VPN בפועל טרם אומתה")
                    .apply()
            } catch (error: Exception) {
                diagnostics.edit()
                    .putLong("last_error_time", System.currentTimeMillis())
                    .putString("last_error", "בקשת שחזור אחרי הסרה נכשלה: ${error.javaClass.name}: ${error.message ?: "ללא פרטים"}")
                    .putString("last_error_stack", error.stackTraceToString().take(6000))
                    .putString("last_task_recovery_result", "נכשלה: ${error.javaClass.simpleName}: ${error.message ?: "ללא פרטים"}")
                    .putString("vpn_state", "בקשת שחזור עובד VPN נכשלה")
                    .apply()
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        diagnostics.edit().putLong("last_service_destroy", System.currentTimeMillis())
            .putString("last_service_lifecycle_event", "onDestroy")
            .putBoolean("last_worker_thread_alive", worker?.isAlive == true)
            .putBoolean("last_tun_valid", try { tun?.fileDescriptor?.valid() == true } catch (_: Exception) { false })
            .putBoolean("vpn_worker_running", false)
            .putString("vpn_state", "Android סיים את השירות; סוגר חיבור ותהליך עבודה").apply()
        // Closing TUN unblocks the reader if Android destroys this service instance.
        running.set(false)
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        worker?.interrupt()
        worker = null
        removeFloatingIcon()
        super.onDestroy()
    }

    private fun runVpn() {
        while (running.get()) {
            try {
                diagnostics.edit().putString("vpn_state", "מנסה ליצור חיבור VPN מול Android")
                    .putLong("last_establish_attempt", System.currentTimeMillis()).apply()
                tun = Builder()
                    .setSession("SafeGuard")
                    .setMtu(1500)
                    .addAddress("10.111.222.2", 32)
                    .addDnsServer("10.111.222.1")
                    .addRoute("10.111.222.1", 32)
                    .establish()
                val fd = tun ?: throw IllegalStateException("Android לא אישר יצירת חיבור VPN (Builder.establish החזיר null)")
                diagnostics.edit().putLong("last_establish_success", System.currentTimeMillis())
                    .putString("vpn_state", "חיבור TUN הוקם; ממתין לתעבורת DNS")
                    .putBoolean("vpn_worker_running", true).apply()
                updateNotification("סינון פעיל")
                val input = FileInputStream(fd.fileDescriptor)
                val output = FileOutputStream(fd.fileDescriptor)
                val packetBuffer = ByteArray(32767)
                while (running.get()) {
                    val length = input.read(packetBuffer)
                    if (length <= 0) continue
                    diagnostics.edit().putLong("last_heartbeat", System.currentTimeMillis())
                        .putLong("last_packet_time", System.currentTimeMillis())
                        .putInt("packets_seen", diagnostics.getInt("packets_seen", 0) + 1).apply()
                    val response = processDnsPacket(packetBuffer.copyOf(length))
                    if (response != null) synchronized(output) { output.write(response) }
                }
            } catch (error: Exception) {
                if (running.get()) {
                    updateNotification("אין חיבור VPN — מנסה להתחבר מחדש")
                    diagnostics.edit().putLong("last_error_time", System.currentTimeMillis())
                        .putString("last_error", "${error.javaClass.name}: ${error.message ?: "ללא פרטים"}")
                        .putString("last_error_stack", error.stackTraceToString().take(6000))
                        .putString("last_service_lifecycle_event", "vpn_worker_exception")
                        .putBoolean("last_worker_thread_alive", Thread.currentThread().isAlive)
                        .putBoolean("last_tun_valid", try { tun?.fileDescriptor?.valid() == true } catch (_: Exception) { false })
                        .putString("vpn_state", "שגיאה בחיבור/עיבוד VPN; מנסה להתחבר מחדש").apply()
                }
            } finally {
                try { tun?.close() } catch (_: Exception) {}
                tun = null
            }

            // נסה להתחבר מחדש במקום לכבות את ההגנה לאחר שגיאה זמנית.
            if (running.get()) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        diagnostics.edit().putLong("last_stop", System.currentTimeMillis())
            .putLong("last_worker_exit", System.currentTimeMillis())
            .putString("last_worker_exit_reason", if (running.get()) "לולאת VPN הסתיימה בזמן שדגל running עדיין true" else "דגל running הפך ל־false")
            .putBoolean("last_worker_thread_alive", Thread.currentThread().isAlive)
            .putBoolean("last_tun_valid", try { tun?.fileDescriptor?.valid() == true } catch (_: Exception) { false })
            .putBoolean("vpn_worker_running", false)
            .putString("vpn_state", "לולאת VPN הסתיימה").apply()
        updateNotification("הסינון נעצר")
        running.set(false)
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun processDnsPacket(packet: ByteArray): ByteArray? {
        if (packet.size < 28 || (packet[0].toInt() and 0xF0) != 0x40) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || packet.size < ihl + 8) return null
        if ((packet[9].toInt() and 0xff) != 17) return null
        if (!packet.copyOfRange(16, 20).contentEquals(dnsAddress)) return null
        val udpLen = u16(packet, ihl + 4)
        if (udpLen < 8 || packet.size < ihl + udpLen || u16(packet, ihl + 2) != 53) return null
        val query = packet.copyOfRange(ihl + 8, ihl + udpLen)
        diagnostics.edit().putLong("last_dns_query", System.currentTimeMillis())
            .putInt("dns_queries_seen", diagnostics.getInt("dns_queries_seen", 0) + 1).apply()
        val dnsResponse = try {
            DatagramSocket().use { socket ->
                if (!protect(socket)) {
                    recordDnsFailure("protect(socket) החזיר false; בקשת DNS לא נשלחה")
                    return null
                }
                socket.soTimeout = 3500
                val address = InetAddress.getByAddress(upstreamAddress)
                socket.send(DatagramPacket(query, query.size, address, 53))
                diagnostics.edit().putLong("last_dns_send", System.currentTimeMillis())
                    .putInt("dns_queries_sent", diagnostics.getInt("dns_queries_sent", 0) + 1).apply()
                val bytes = ByteArray(4096)
                val reply = DatagramPacket(bytes, bytes.size)
                socket.receive(reply)
                diagnostics.edit().putLong("last_dns_response", System.currentTimeMillis())
                    .putInt("dns_responses_received", diagnostics.getInt("dns_responses_received", 0) + 1)
                    .putInt("last_dns_response_bytes", reply.length).apply()
                bytes.copyOf(reply.length)
            }
        } catch (_: SocketTimeoutException) {
            diagnostics.edit().putInt("dns_timeouts", diagnostics.getInt("dns_timeouts", 0) + 1).apply()
            recordDnsFailure("SocketTimeoutException: שרת DNS לא החזיר תשובה תוך 3500ms")
            return null
        } catch (error: Exception) {
            diagnostics.edit().putInt("dns_errors", diagnostics.getInt("dns_errors", 0) + 1).apply()
            recordDnsFailure("${error.javaClass.name}: ${error.message ?: "ללא הודעה"}")
            return null
        }
        return buildIpv4UdpResponse(packet, ihl, dnsResponse)
    }

    /**
     * Store transport errors without recording queried domains or browsing history.
     * This makes the diagnostic report actionable while keeping it privacy-preserving.
     */
    private fun recordDnsFailure(message: String) {
        diagnostics.edit()
            .putLong("last_dns_error_time", System.currentTimeMillis())
            .putString("last_dns_error", message.take(500))
            .apply()
    }

    private fun buildIpv4UdpResponse(request: ByteArray, ihl: Int, dns: ByteArray): ByteArray {
        val srcIp = request.copyOfRange(12, 16)
        val dstIp = request.copyOfRange(16, 20)
        val srcPort = u16(request, ihl)
        val totalLength = 28 + dns.size
        val out = ByteArray(totalLength)
        out[0] = 0x45
        out[1] = 0
        put16(out, 2, totalLength)
        put16(out, 4, 0)
        put16(out, 6, 0x4000)
        out[8] = 64
        out[9] = 17
        System.arraycopy(dstIp, 0, out, 12, 4)
        System.arraycopy(srcIp, 0, out, 16, 4)
        put16(out, 10, checksum(out, 0, 20))
        put16(out, 20, 53)
        put16(out, 22, srcPort)
        put16(out, 24, 8 + dns.size)
        put16(out, 26, 0)
        System.arraycopy(dns, 0, out, 28, dns.size)
        val pseudo = ByteArray(12 + 8 + dns.size)
        System.arraycopy(out, 12, pseudo, 0, 8)
        pseudo[9] = 17
        put16(pseudo, 10, 8 + dns.size)
        System.arraycopy(out, 20, pseudo, 12, 8 + dns.size)
        var udpChecksum = checksum(pseudo, 0, pseudo.size)
        if (udpChecksum == 0) udpChecksum = 0xffff
        put16(out, 26, udpChecksum)
        return out
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value shr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += (((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)).toLong()
            i += 2
        }
        if (i < end) sum += ((bytes[i].toInt() and 0xff) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return sum.inv().toInt() and 0xffff
    }

    override fun onRevoke() {
        diagnostics.edit().putLong("last_stop", System.currentTimeMillis())
            .putBoolean("vpn_worker_running", false)
            .putString("vpn_state", "Android ביטל את הרשאת VPN (onRevoke)").apply()
        running.set(false)
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        removeFloatingIcon()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }
}