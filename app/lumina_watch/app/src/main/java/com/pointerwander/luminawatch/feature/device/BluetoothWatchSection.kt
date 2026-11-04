package com.pointerwander.luminawatch.feature.device

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.SourceTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class NearbyWatch(val name: String, val address: String)

/**
 * 真实的 BLE 附近设备搜索。只负责发现和选择；选择后仅保存本机记录，
 * 不声称已完成配对、GATT 连接或数据同步。
 */
@Composable
fun BluetoothWatchSection(onPick: (name: String, address: String) -> Unit) {
    val colors = LocalWatchColors.current
    val context = LocalContext.current
    val manager = remember {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    val adapter: BluetoothAdapter? = manager?.adapter
    val found = remember { mutableStateMapOf<String, NearbyWatch>() }
    var scanning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var callback by remember { mutableStateOf<ScanCallback?>(null) }
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main) }

    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermissions(): Boolean = permissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun stopScan() {
        callback?.let { cb ->
            runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
        }
        callback = null
        scanning = false
    }

    fun startScan() {
        if (adapter == null) {
            message = "此设备不支持蓝牙。"
            return
        }
        if (!adapter.isEnabled) {
            message = "蓝牙未开启，请先在系统设置中打开蓝牙。"
            return
        }
        found.clear()
        message = null
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = runCatching { device.name }.getOrNull()
                    ?: result.scanRecord?.deviceName
                    ?: "未命名蓝牙设备"
                found[device.address] = NearbyWatch(name, device.address)
            }

            override fun onScanFailed(errorCode: Int) {
                message = "蓝牙搜索失败（错误码 $errorCode）。"
                scanning = false
            }
        }
        callback = cb
        runCatching { adapter.bluetoothLeScanner?.startScan(cb) }
            .onFailure { message = "无法开始蓝牙搜索：${it.message}" }
        scanning = true
        scope.launch {
            delay(10_000)
            if (scanning) stopScan()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) startScan() else message = "需要蓝牙权限才能搜索附近设备。"
    }

    DisposableEffect(Unit) {
        onDispose { stopScan() }
    }

    Column(Modifier.fillMaxWidth()) {
        Text("通过蓝牙添加", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "搜索附近的 BLE 设备。选择后只保存本机记录；真实配对、连接与数据同步尚未接入。",
            color = colors.textSecondary, fontSize = 12.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiquidGlassButton(
                if (scanning) "正在搜索…" else "搜索附近设备",
                onClick = {
                    if (adapter == null) message = "此设备不支持蓝牙。"
                    else if (!adapter.isEnabled) message = "蓝牙未开启，请先在系统设置中打开蓝牙。"
                    else if (!hasPermissions()) permissionLauncher.launch(permissions())
                    else startScan()
                },
                modifier = Modifier.weight(1f),
                enabled = !scanning,
                filled = false,
                height = 44.dp,
            )
            if (scanning) {
                LiquidGlassButton("停止", { stopScan() }, height = 44.dp)
            }
        }
        message?.let {
            Text(it, color = colors.warning, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        if (found.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                found.values.sortedBy { it.name }.forEach { watch ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            // 发起系统蓝牙配对请求；不等待/伪造 GATT 连接结果。
                            runCatching {
                                adapter?.getRemoteDevice(watch.address)?.createBond()
                            }
                            onPick(watch.name, watch.address)
                        }.padding(vertical = 10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(watch.name, color = colors.textPrimary, fontSize = 14.sp,
                                fontWeight = FontWeight.Medium)
                            Text(watch.address, color = colors.textSecondary, fontSize = 11.sp)
                        }
                        SourceTag("选择")
                    }
                }
            }
        } else if (scanning) {
            Text("正在搜索附近设备…", color = colors.textSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}
