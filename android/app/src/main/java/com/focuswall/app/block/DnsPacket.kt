package com.focuswall.app.block

/** Минимальный разбор IPv4/UDP-пакетов с DNS-запросами из tun-интерфейса. */
object DnsPacket {
    class Query(
        private val srcIp: ByteArray,
        private val dstIp: ByteArray,
        private val srcPort: Int,
        private val dstPort: Int,
        /** Сам DNS-запрос (полезная нагрузка UDP). */
        val dns: ByteArray,
        /** Запрашиваемое имя в нижнем регистре, например "www.youtube.com". */
        val name: String,
    ) {
        /** Собирает IP-пакет с ответом: адреса и порты меняются местами. */
        fun reply(payload: ByteArray): ByteArray {
            val total = 20 + 8 + payload.size
            val p = ByteArray(total)
            p[0] = 0x45
            p[2] = (total shr 8).toByte(); p[3] = total.toByte()
            p[6] = 0x40 // не фрагментировать
            p[8] = 64 // TTL
            p[9] = 17 // UDP
            System.arraycopy(dstIp, 0, p, 12, 4)
            System.arraycopy(srcIp, 0, p, 16, 4)
            val sum = checksum(p, 0, 20)
            p[10] = (sum shr 8).toByte(); p[11] = sum.toByte()
            p[20] = (dstPort shr 8).toByte(); p[21] = dstPort.toByte()
            p[22] = (srcPort shr 8).toByte(); p[23] = srcPort.toByte()
            val udpLen = 8 + payload.size
            p[24] = (udpLen shr 8).toByte(); p[25] = udpLen.toByte()
            // Контрольная сумма UDP в IPv4 необязательна, оставляем 0.
            System.arraycopy(payload, 0, p, 28, payload.size)
            return p
        }
    }

    fun parse(b: ByteArray, n: Int): Query? {
        if (n < 28 || (b[0].toInt() shr 4) != 4) return null
        val ihl = (b[0].toInt() and 0x0F) * 4
        if (b[9].toInt() != 17 || n < ihl + 8 + 12) return null
        val srcPort = u16(b, ihl)
        val dstPort = u16(b, ihl + 2)
        if (dstPort != 53) return null
        val udpLen = u16(b, ihl + 4)
        val start = ihl + 8
        val end = minOf(n, ihl + udpLen)
        if (end - start < 12) return null
        val dns = b.copyOfRange(start, end)
        val name = qname(dns) ?: return null
        return Query(b.copyOfRange(12, 16), b.copyOfRange(16, 20), srcPort, dstPort, dns, name)
    }

    /** Ответ «домен не существует» на тот же вопрос. */
    fun nxdomain(q: ByteArray): ByteArray {
        val qEnd = questionEnd(q) ?: q.size
        val r = q.copyOf(qEnd)
        r[2] = (0x80 or (q[2].toInt() and 0x79)).toByte() // QR=1, тот же opcode и RD
        r[3] = 0x83.toByte() // RA=1, RCODE=3 (NXDOMAIN)
        for (i in 6 until 12) r[i] = 0 // нет ответов и дополнительных записей
        return r
    }

    private fun qname(dns: ByteArray): String? {
        val sb = StringBuilder()
        var i = 12
        while (i < dns.size) {
            val len = dns[i].toInt() and 0xFF
            if (len == 0) return sb.toString().lowercase()
            if (len > 63 || i + 1 + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, i + 1, len, Charsets.US_ASCII))
            i += 1 + len
        }
        return null
    }

    private fun questionEnd(dns: ByteArray): Int? {
        var i = 12
        while (i < dns.size) {
            val len = dns[i].toInt() and 0xFF
            if (len == 0) return (i + 1 + 4).takeIf { it <= dns.size }
            i += 1 + len
        }
        return null
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i < off + len) {
            sum += u16(b, i)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
