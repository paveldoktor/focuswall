package com.focuswall.app.block

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.focuswall.app.core.PolicyMode
import com.focuswall.app.core.Rules
import com.focuswall.app.data.Store
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Локальный «VPN», через который идут только DNS-запросы. Трафик никуда не уходит:
 * запросы к заблокированным доменам получают ответ «такого сайта нет», остальные пересылаются в обычный DNS.
 *
 * В режиме белого списка фильтр DNS не включается: разрешённым приложениям (например, банку) нужны
 * свои серверы. Там сайты в браузере закрывает служба специальных возможностей.
 */
class DnsVpnService : VpnService() {
    @Volatile private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (tun == null) startTunnel()
        return START_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun startTunnel() {
        val fd = Builder()
            .setSession("FocusWall")
            .addAddress(TUN_ADDRESS, 32)
            .addDnsServer(DNS_ADDRESS)
            .addRoute(DNS_ADDRESS, 32)
            .setBlocking(true)
            .establish() ?: return
        tun = fd
        running = true
        reader = Thread({ loop(fd) }, "focuswall-dns").also { it.start() }
    }

    private fun stopTunnel() {
        running = false
        reader?.interrupt()
        runCatching { tun?.close() }
        tun = null
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        val store = Store.get(this)
        var policy = store.sitePolicy()
        var policyAt = System.currentTimeMillis()
        while (running) {
            val n = try {
                input.read(buf)
            } catch (e: Exception) {
                break
            }
            if (n <= 0) continue
            val query = DnsPacket.parse(buf, n) ?: continue
            if (System.currentTimeMillis() - policyAt > 15_000) {
                policy = store.sitePolicy()
                policyAt = System.currentTimeMillis()
            }
            val blocked = policy.mode == PolicyMode.BLOCK && Rules.isHostBlocked(query.name, policy)
            if (blocked) {
                write(output, query.reply(DnsPacket.nxdomain(query.dns)))
            } else {
                pool.execute { forward(query, output) }
            }
        }
    }

    private fun forward(query: DnsPacket.Query, output: FileOutputStream) {
        for (server in UPSTREAM) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket)
                    socket.soTimeout = 4000
                    socket.send(DatagramPacket(query.dns, query.dns.size, InetAddress.getByName(server), 53))
                    val resp = ByteArray(4096)
                    val p = DatagramPacket(resp, resp.size)
                    socket.receive(p)
                    write(output, query.reply(resp.copyOf(p.length)))
                }
                return
            } catch (e: Exception) {
                Log.d(TAG, "upstream $server failed: ${e.message}")
            }
        }
    }

    private fun write(output: FileOutputStream, packet: ByteArray) {
        synchronized(output) { runCatching { output.write(packet) } }
    }

    companion object {
        private const val TAG = "FocusWallDns"
        private const val TUN_ADDRESS = "10.111.0.2"
        private const val DNS_ADDRESS = "10.111.0.1"
        private val UPSTREAM = listOf("1.1.1.1", "8.8.8.8")
        private const val ACTION_STOP = "stop"

        @Volatile var running = false
            private set

        /** Запускает фильтр, если пользователь его включил и уже дал согласие на VPN. */
        fun ensureRunning(context: Context) {
            if (running || !Store.get(context).vpnEnabled) return
            if (prepare(context) != null) return
            runCatching { context.startService(Intent(context, DnsVpnService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, DnsVpnService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
