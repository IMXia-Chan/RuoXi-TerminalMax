package com.example.touchpad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **蓝牙（BLE）** —— 扫附近设备、连上去、读一个特征值、写一个特征值。
 *
 * 2026-11-07 他选了「MQTT 和蓝牙两个都做」。
 *
 * ## ★ 先说清楚它做不到什么
 *
 * BLE 本身**没有通用语义**：每个厂家的设备都自定义了自己的特征值编号和字节格式。
 * 一条「开灯」在 A 家是 `01`，在 B 家是 `f1a2 0300`。所以我们能做的是
 * **一把通用螺丝刀**：扫到、连上、看有哪些服务、读出来、写进去。
 * **不做「按设备品牌开灯」那种库** —— 那是每接一个设备就要写一份的东西，
 * 写十份也只是十份，而不是「支持蓝牙设备」。
 *
 * ## ★ 两次权限
 *
 * Android 12 起扫描和连接是**两个独立的运行时权限**。第一次用会弹系统的框；
 * 他点了拒绝，我们就如实说「没拿到权限」，**不假装成「附近没有设备」**。
 *
 * ## ★ 一次一连接
 *
 * 和 MQTT 一样：每次调用开一次连接，做完关掉。GATT 是异步的（读、写都要等回调），
 * 一条连接要是挂着不放，下一次调用就得排队等前一回的锁 —— 而那个等待看起来
 * 和「设备没响应」一模一样。
 */
data class BleDevice(val name: String, val address: String, val rssi: Int)

/** 一个服务下的一项特征值。 */
data class BleChar(
    val service: String,
    val uuid: String,
    val props: String,
)

object BleMath {

    const val ID_PREFIX = "ble:"

    fun isBle(handId: String): Boolean = handId.startsWith(ID_PREFIX)

    /**
     * 把用户写的编号认成一个 UUID。
     *
     * 蓝牙的标准短号可以简写成 4 位（`180f`）或 8 位；自定义的一般给完整的
     * `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`。**三种都认** —— 面板上让他填
     * 一串 36 位的编号，错一个字符就是「连上了但找不到服务」，很难查。
     */
    fun uuidOf(text: String): UUID? {
        val t = text.trim().lowercase().replace(" ", "")
        if (t.isEmpty()) return null
        val expanded = when {
            t.length == 4 && isHex(t) -> "0000$t-0000-1000-8000-00805f9b34fb"
            t.length == 8 && isHex(t) -> "$t-0000-1000-8000-00805f9b34fb"
            t.length == 32 && isHex(t) ->
                "${t.substring(0, 8)}-${t.substring(8, 12)}-${t.substring(12, 16)}-" +
                    "${t.substring(16, 20)}-${t.substring(20)}"
            else -> t
        }
        return runCatching { UUID.fromString(expanded) }.getOrNull()
    }

    private fun isHex(s: String) = s.all { it in '0'..'9' || it in 'a'..'f' }

    /** 短号就短写（`180f`），自定义的写完整。面板上和回执里都用它。 */
    fun name(u: UUID): String {
        val s = u.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000"))
            s.substring(4, 8) else s
    }

    /**
     * 他写的字节：「`01`」「`01 02`」「`0x01,0x02`」都认。
     * → null = 不是合法的十六进制（奇数位 / 有非法字符）。
     */
    fun hexToBytes(text: String): ByteArray? {
        var t = text.trim().lowercase()
            .replace("0x", "").replace(" ", "")
            .replace(",", "").replace(":", "").replace("-", "")
        if (t.isEmpty()) return byteArrayOf()
        if (t.length % 2 != 0 || !isHex(t)) return null
        val out = ByteArray(t.length / 2)
        for (i in out.indices) {
            out[i] = t.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun bytesToHex(b: ByteArray): String =
        b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * 给模型看的那一段：能读成文字就给文字，不能就给十六进制。
     *
     * ★ **两边都给**。只给文字的话，`01 00 0a` 这种会被读成控制字符，
     *   在上下文里变成一串看不见的东西；只给十六进制的话，型号字符串
     *   （`BT-TH-01`）要她自己去翻译。
     */
    fun describe(b: ByteArray): String {
        if (b.isEmpty()) return "（空）"
        val hex = bytesToHex(b)
        // ★ 判据是「**每一个**字节都是可见 ASCII（或换行/制表）」，而且至少一半
        //   在 0x20–0x7E。只看「过滤之后还剩几个字」的话，`01 00 0a` 会剩下一个
        //   换行符，被当成文字给出 —— 屏幕上出现一对空引号，看起来像读到了空值。
        val allPrintable = b.all {
            val i = it.toInt() and 0xFF
            i in 0x20..0x7E || i == 0x09 || i == 0x0A || i == 0x0D
        }
        val visible = b.count { (it.toInt() and 0xFF) in 0x20..0x7E }
        return if (allPrintable && visible * 2 >= b.size) {
            "「${b.toString(Charsets.US_ASCII)}」（$hex）"
        } else hex
    }

    /** `BluetoothGattCharacteristic` 的属性位 → 几个词。 */
    fun props(p: Int): String {
        val out = ArrayList<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) out.add("读")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) out.add("写")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) out.add("写(不等回执)")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) out.add("通知")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) out.add("指示")
        return if (out.isEmpty()) "无" else out.joinToString("、")
    }

    /** GATT 的状态码 → 人话。**不许只回一个数字** —— 用户没法据此改任何东西。 */
    fun statusText(code: Int): String = when (code) {
        0 -> "成功"
        1 -> "未知失败"
        2 -> "读失败"
        3 -> "写失败"
        4 -> "通知没开起来"
        5 -> "这个操作不允许"
        6 -> "参数不对"
        7 -> "设备那边没准备好"
        8 -> "队列满了"
        133 -> "连不上（设备被别的手机连着 / 太远了 / 地址变了）"
        137 -> "设备主动断开了"
        else -> "状态码 $code"
    }
}

object BleClient {

    @Volatile private var appContext: Context? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    fun contextOrNull(): Context? = appContext

    fun canScan(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31)
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun canConnect(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31)
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else true

    fun supported(ctx: Context): Boolean {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        return mgr.adapter != null
    }

    fun enabled(ctx: Context): Boolean {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        return mgr.adapter?.isEnabled == true
    }

    // ------------------------------------------------------------------
    // 扫
    // ------------------------------------------------------------------

    /**
     * 扫一段时间，把看到的设备收下来（按信号强度从强到弱）。
     *
     * ★ 扫描是**持续回调**，不是一次请求一个响应 —— 所以这里睡满时间再收。
     *   睡多久由调用方给（1–20 秒），到点就停，**不会一直扫下去**。
     */
    @SuppressLint("MissingPermission")
    fun scan(ctx: Context, seconds: Int, maxDevices: Int = 20): List<BleDevice> {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw IllegalStateException("这台机器没有蓝牙服务。")
        val adapter: BluetoothAdapter = mgr.adapter
            ?: throw IllegalStateException("这台机器没有蓝牙适配器。")
        if (!adapter.isEnabled) throw IllegalStateException("蓝牙没开。")
        val scanner = adapter.bluetoothLeScanner
            ?: throw IllegalStateException("这台机器不支持蓝牙扫描。")

        val found = LinkedHashMap<String, BleDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val r = result ?: return
                val dev = r.device ?: return
                val name = try {
                    dev.name?.trim().orEmpty()
                } catch (_: SecurityException) {
                    ""
                }
                // ★ 没有名字的也留着 —— 很多设备要连上之后才报名，先删掉就等于
                //   「扫不到旁边那个东西」。地址本身是有用的（能连）。
                found[dev.address] = BleDevice(name, dev.address, r.rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                // 不抛异常（回调线程里抛了也没人接），让下面按「一个都没扫到」返回。
            }
        }

        scanner.startScan(cb)
        try {
            Thread.sleep(seconds.coerceIn(1, 20) * 1000L)
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
        return found.values.sortedByDescending { it.rssi }.take(maxDevices)
    }

    // ------------------------------------------------------------------
    // 连：服务、读、写 —— 都走同一段「连上 → 做事 → 断开」
    // ------------------------------------------------------------------

    private class Conn(val gatt: BluetoothGatt) {
        val connected = CountDownLatch(1)
        val services = CountDownLatch(1)
        @Volatile var connStatus = -1
        val readDone = CountDownLatch(1)
        @Volatile var readStatus = -1
        @Volatile var readValue: ByteArray? = null
        val writeDone = CountDownLatch(1)
        @Volatile var writeStatus = -1
    }

    /** 连一次，做点事，无论成败都断开。 */
    private fun <T> withGatt(address: String, timeoutSec: Long = 12, body: (Conn) -> T): T {
        val ctx = appContext ?: throw IllegalStateException("蓝牙还没初始化。")
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw IllegalStateException("这台机器没有蓝牙服务。")
        val adapter = mgr.adapter ?: throw IllegalStateException("这台机器没有蓝牙适配器。")
        if (!adapter.isEnabled) throw IllegalStateException("蓝牙没开。")
        val device = adapter.getRemoteDevice(address)

        val holder = arrayOfNulls<Conn>(1)
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val c = holder[0] ?: return
                c.connStatus = if (newState == BluetoothGatt.STATE_CONNECTED) status else 133
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    c.connected.countDown()
                    runCatching { gatt.discoverServices() }
                } else {
                    c.connected.countDown()
                    c.services.countDown()
                    c.readDone.countDown()
                    c.writeDone.countDown()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                holder[0]?.services?.countDown()
            }

            // ★ API 33 才有带 value 的那个重载。旧的重载在 33 上**不再被调用**，
            //   只实现旧的 = 在新系统上永远等不到读到的值，而表现是「读超时」。
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                val c = holder[0] ?: return
                c.readStatus = status
                c.readValue = value.copyOf()
                c.readDone.countDown()
            }

            @Deprecated("API 33 起换成带 value 的重载")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (Build.VERSION.SDK_INT >= 33) return
                val c = holder[0] ?: return
                c.readStatus = status
                @Suppress("DEPRECATION")
                c.readValue = characteristic.value?.copyOf()
                c.readDone.countDown()
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                val c = holder[0] ?: return
                c.writeStatus = status
                c.writeDone.countDown()
            }
        }

        @SuppressLint("MissingPermission")
        val gatt = device.connectGatt(ctx, false, cb, BluetoothDevice_TRANSPORT_LE)
            ?: throw IllegalStateException("系统不让我们连这台设备。")
        val c = Conn(gatt)
        holder[0] = c
        try {
            if (!c.connected.await(timeoutSec, TimeUnit.SECONDS) || c.connStatus != 0) {
                throw IllegalStateException(
                    "连不上这台设备（" + BleMath.statusText(if (c.connStatus < 0) 133 else c.connStatus) + "）。"
                )
            }
            if (!c.services.await(timeoutSec, TimeUnit.SECONDS)) {
                throw IllegalStateException("连上了，但拿不到它的服务清单（超时）。")
            }
            return body(c)
        } finally {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    private const val BluetoothDevice_TRANSPORT_LE = 2

    /** 连上看一眼：它有哪些服务、哪些特征值。 */
    @SuppressLint("MissingPermission")
    fun services(address: String): List<BleChar> = withGatt(address) { c ->
        val out = ArrayList<BleChar>()
        for (s in c.gatt.services.orEmpty()) {
            for (ch in s.characteristics.orEmpty()) {
                out.add(
                    BleChar(
                        service = BleMath.name(s.uuid),
                        uuid = BleMath.name(ch.uuid),
                        props = BleMath.props(ch.properties),
                    )
                )
            }
        }
        out
    }

    @SuppressLint("MissingPermission")
    fun read(address: String, service: String, characteristic: String): ByteArray {
        val svc = BleMath.uuidOf(service) ?: throw IllegalArgumentException("服务编号认不出来：$service")
        val chr = BleMath.uuidOf(characteristic)
            ?: throw IllegalArgumentException("特征值编号认不出来：$characteristic")
        return withGatt(address) { c ->
            val ch = findChar(c.gatt, svc, chr) ?: throw IllegalStateException("这台设备上没有这个特征值。")
            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                throw IllegalStateException("这个特征值不能直接读（它的属性是：${BleMath.props(ch.properties)}）。")
            }
            if (!c.gatt.readCharacteristic(ch)) {
                throw IllegalStateException("系统没有接受这次读请求。")
            }
            if (!c.readDone.await(8, TimeUnit.SECONDS)) throw IllegalStateException("等它回数据超时了。")
            if (c.readStatus != 0) throw IllegalStateException("读失败：" + BleMath.statusText(c.readStatus))
            c.readValue ?: ByteArray(0)
        }
    }

    @SuppressLint("MissingPermission")
    fun write(address: String, service: String, characteristic: String, bytes: ByteArray): String {
        val svc = BleMath.uuidOf(service) ?: throw IllegalArgumentException("服务编号认不出来：$service")
        val chr = BleMath.uuidOf(characteristic)
            ?: throw IllegalArgumentException("特征值编号认不出来：$characteristic")
        return withGatt(address) { c ->
            val ch = findChar(c.gatt, svc, chr) ?: throw IllegalStateException("这台设备上没有这个特征值。")
            val canWriteWithAck = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            val canWriteNoAck =
                ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            if (!canWriteWithAck && !canWriteNoAck) {
                throw IllegalStateException("这个特征值不能写（它的属性是：${BleMath.props(ch.properties)}）。")
            }
            val type = if (canWriteWithAck)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            val ok = if (Build.VERSION.SDK_INT >= 33) {
                c.gatt.writeCharacteristic(ch, bytes, type) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = type
                    ch.value = bytes
                    c.gatt.writeCharacteristic(ch)
                }
            }
            if (!ok) throw IllegalStateException("系统没有接受这次写请求。")
            // 不等回执的那种写成之后本来就没有回调 —— 别傻等。
            if (type == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                if (!c.writeDone.await(8, TimeUnit.SECONDS)) throw IllegalStateException("等它确认写入超时了。")
                if (c.writeStatus != 0) throw IllegalStateException("写失败：" + BleMath.statusText(c.writeStatus))
            }
            "已写入 ${BleMath.bytesToHex(bytes)}。"
        }
    }

    private fun findChar(
        gatt: BluetoothGatt,
        service: UUID,
        characteristic: UUID,
    ): BluetoothGattCharacteristic? =
        gatt.getService(service)?.getCharacteristic(characteristic)
}

/**
 * 蓝牙交给她当工具。
 *
 * ★ 和 MQTT / MCP / 云端那几只一样走 `KIND_CLOUD` 这条总闸。
 * ★ 它**不是**一只「每台设备一个」的手，而是**一只**手带四个工具 ——
 *   因为设备是「当场扫出来的」，不是「事先配置好的」。这一点和 MQTT 相反。
 */
object BleHand {

    const val HAND_ID = "ble:adapter"

    fun hands(): List<Hand> = listOf(
        Hand(
            id = HAND_ID,
            kind = HandMath.KIND_CLOUD,
            name = "蓝牙",
            version = "1",
            fetchedAtMs = System.currentTimeMillis(),
            tools = listOf(
                HandTool(
                    name = "scan",
                    desc = "扫附近的蓝牙设备，返回名字、地址和信号强度。第一次用会要权限。",
                    params = listOf(HandParam("seconds", "扫几秒，1 到 20，不填按 6")),
                    required = emptyList(),
                    exec = null,
                ),
                HandTool(
                    name = "services",
                    desc = "连上一台设备，看它有哪些服务号和特征值（以及每个能不能读、能不能写）。",
                    params = listOf(HandParam("address", "设备的地址，比如 AA:BB:CC:DD:EE:FF")),
                    required = listOf("address"),
                    exec = null,
                ),
                HandTool(
                    name = "read",
                    desc = "读一个特征值，返回它的字节（能读成文字的会一起给）。",
                    params = listOf(
                        HandParam("address", "设备地址"),
                        HandParam("service", "服务号，短号或完整 UUID"),
                        HandParam("characteristic", "特征值编号，短号或完整 UUID"),
                    ),
                    required = listOf("address", "service", "characteristic"),
                    exec = null,
                ),
                HandTool(
                    name = "write",
                    desc = "往一个特征值写一段字节。字节用十六进制写，比如「01 00」。",
                    params = listOf(
                        HandParam("address", "设备地址"),
                        HandParam("service", "服务号"),
                        HandParam("characteristic", "特征值编号"),
                        HandParam("data", "要写的字节，十六进制，比如 01 00"),
                    ),
                    required = listOf("address", "service", "characteristic", "data"),
                    exec = null,
                ),
            ),
            locks = mapOf(
                "scan" to emptyList(),
                // ★ 连一台设备要占住那台设备（同一台不能被两件事同时连）。
                "services" to listOf("ble-connect"),
                "read" to listOf("ble-connect"),
                "write" to listOf("ble-connect"),
            ),
        )
    )

    private const val REQ = 7_421
    @Volatile private var waiting: CountDownLatch? = null

    /** 权限框的结果由 [ConMarnActivity.onRequestPermissionsResult] 转发过来。 */
    fun onResult(requestCode: Int): Boolean {
        if (requestCode != REQ) return false
        waiting?.countDown()
        return true
    }

    fun exec(hand: Hand, toolName: String, args: JSONObject): JSONObject {
        val ctx = BleClient.contextOrNull() ?: return fail("蓝牙没初始化。")
        if (!BleClient.supported(ctx)) return fail("这台机器没有蓝牙。")
        if (!BleClient.enabled(ctx)) return fail("蓝牙没开。让他把蓝牙打开再说。")

        // ★ 权限要**当场要**，而且要等到他按完 —— 见 CalHand.askBlocking 同一种写法。
        val need = when (toolName) {
            "scan" -> !BleClient.canScan(ctx)
            else -> !BleClient.canConnect(ctx) || !BleClient.canScan(ctx)
        }
        if (need) {
            val act = SelfHand.hostActivity?.get()
            if (act == null || act.isFinishing) {
                return fail("用蓝牙要先给权限，而现在没有界面能弹那个框（打开她的房间再说一次）。")
            }
            if (!askBlocking(act)) {
                return fail("没拿到蓝牙权限（他点了拒绝，或者系统那个框没弹出来）。")
            }
        }

        return try {
            when (toolName) {
                "scan" -> {
                    val secs = args.optString("seconds").toIntOrNull() ?: 6
                    val list = BleClient.scan(ctx, secs)
                    if (list.isEmpty()) {
                        JSONObject().put("ok", true)
                            .put("data", "$secs 秒内没扫到设备。可能是附近确实没有，也可能是扫描被系统限了。")
                    } else {
                        val text = list.joinToString("\n") {
                            "${it.name.ifBlank { "(没有名字)" }} · ${it.address} · 信号 ${it.rssi}"
                        }
                        JSONObject().put("ok", true).put("data", text)
                    }
                }
                "services" -> {
                    val address = args.optString("address").trim()
                    if (address.isEmpty()) return fail("还缺参数：address。")
                    val list = BleClient.services(address)
                    if (list.isEmpty()) {
                        JSONObject().put("ok", true).put("data", "连上了，但它没有报出任何服务。")
                    } else {
                        val text = list.joinToString("\n") {
                            "服务 ${it.service} · 特征值 ${it.uuid} · ${it.props}"
                        }
                        JSONObject().put("ok", true).put("data", text)
                    }
                }
                "read" -> {
                    val bytes = BleClient.read(
                        args.optString("address").trim(),
                        args.optString("service"),
                        args.optString("characteristic"),
                    )
                    JSONObject().put("ok", true).put("data", BleMath.describe(bytes))
                }
                "write" -> {
                    val bytes = BleMath.hexToBytes(args.optString("data"))
                        ?: return fail("data 不是合法的十六进制（要两位一组，比如 01 0a）。")
                    val r = BleClient.write(
                        args.optString("address").trim(),
                        args.optString("service"),
                        args.optString("characteristic"),
                        bytes,
                    )
                    JSONObject().put("ok", true).put("data", r)
                }
                else -> fail("「${hand.name}」上没有「$toolName」这个工具。")
            }
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 面板上的「扫描一次」也要权限，走同一条路。 */
    fun askGrant(activity: Activity): Boolean = askBlocking(activity)

    /** 弹权限框，等到他按完（最多 60 秒）。和 [CalHand.askBlocking] 同一个理由。 */
    private fun askBlocking(activity: Activity, seconds: Long = 60): Boolean {
        synchronized(this) {
            if (waiting != null) return false
            waiting = CountDownLatch(1)
        }
        val latch = waiting ?: return false
        val perms = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        activity.runOnUiThread {
            runCatching { activity.requestPermissions(perms, REQ) }
                .onFailure { synchronized(this) { waiting = null }; latch.countDown() }
        }
        val ok = runCatching { latch.await(seconds, TimeUnit.SECONDS) }.getOrDefault(false)
        synchronized(this) { waiting = null }
        return ok && BleClient.canScan(activity) && BleClient.canConnect(activity)
    }

    private fun fail(why: String): JSONObject = JSONObject().put("ok", false).put("error", why)
}
