package moe.mcns.BiliMax.sync

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 手机网卡地址发现。
 *
 * 旧实现只取系统枚举到的第一个非环回 IPv4，多网卡（WiFi/热点/蜂窝/虚拟网卡并存）
 * 时经常拿到蜂窝或虚拟网卡地址，手表无法访问，表现为「无法获取 IP 地址」。
 *
 * 新实现枚举所有 UP 状态的物理 IPv4 地址：
 *  1. 按网卡名排除蜂窝（rmnet/ccmni/pdp）与虚拟网卡（tun/tap/vpn/dummy/ppp/clat）；
 *  2. 按网卡类型打分（wlan > 热点 ap/softap > eth > 其它）；
 *  3. 按网段加分（192.168/10/172.16-31 私网优先）；
 *  4. 排序去重后输出候选列表，交 [TranscodeServer] 组成候选基址下发给手表，
 *     手表端再对候选基址做可达性探测择优——手机枚举不准也不影响最终连通。
 */
object NetInfo {

    private const val TAG = "BiliMax-NetInfo"

    data class Candidate(val ip: String, val score: Int, val iface: String)

    /** 打分最高的一个地址（兼容旧调用点；无可用地址返回 null） */
    fun lanIpv4(): String? = candidates().firstOrNull()?.ip

    fun reportedIpv4(): String? = lanIpv4() ?: candidates(includeBlocked = true).firstOrNull()?.ip

    /** 全部候选 IPv4，按得分从高到低、已去重 */
    fun candidateIps(): List<String> = candidates().map { it.ip }.distinct()

    private fun candidates(includeBlocked: Boolean = false): List<Candidate> {
        val out = mutableListOf<Candidate>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in ifaces.toList()) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.orEmpty().lowercase()
                val display = nif.displayName.orEmpty().lowercase()
                val sig = "$name $display"
                if (!includeBlocked && isBlockedIface(sig)) continue
                val ifaceScore = scoreIface(sig)
                for (addr in nif.inetAddresses.toList()) {
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isMulticastAddress) continue
                    val ip = addr.hostAddress ?: continue
                    out.add(Candidate(ip, ifaceScore + scoreSubnet(ip), name.ifBlank { display }))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "枚举网卡失败: ${t.message}")
        }
        return out.sortedByDescending { it.score }
    }

    /** 蜂窝与虚拟网卡特征名（手机流量地址手表不可达，桥接/代理虚拟地址不可对外） */
    private fun isBlockedIface(sig: String): Boolean =
        sig.contains("rmnet") || sig.contains("ccmni") || sig.contains("pdp") ||
            sig.contains("tun") || sig.contains("tap") || sig.contains("ppp") ||
            sig.contains("dummy") || sig.contains("vpn") || sig.contains("clat")

    private fun scoreIface(sig: String): Int = when {
        sig.contains("softap") || sig.contains("ap_") || sig.contains("/ap") -> 95
        sig.contains("wlan") -> 100
        sig.contains("eth") -> 80
        else -> 40
    }

    private fun scoreSubnet(ip: String): Int = when {
        ip.startsWith("192.168.") -> 10
        ip.startsWith("10.") -> 8
        ip.startsWith("172.") -> {
            val seg = ip.split(".").getOrNull(1)?.toIntOrNull() ?: 0
            if (seg in 16..31) 8 else 0
        }
        else -> 0
    }
}
