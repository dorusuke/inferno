package com.inferno.os

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Routes mic and speaker to the glasses when they are paired as a Bluetooth headset. */
object Audio {
    private fun am(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    fun glassesDevice(ctx: Context): AudioDeviceInfo? =
        am(ctx).availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

    fun glassesMic(ctx: Context): AudioDeviceInfo? =
        am(ctx).getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

    /** Glasses connected for music only (no mic path), so they can't be used for talking. */
    fun outputOnlyBluetooth(ctx: Context): Boolean =
        am(ctx).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } &&
            glassesDevice(ctx) == null

    fun label(type: Int?): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "glasses (Bluetooth)"
        AudioDeviceInfo.TYPE_BUILTIN_MIC, null -> "phone mic"
        else -> "other mic"
    }

    fun connect(ctx: Context): Boolean {
        val m = am(ctx)
        val d = glassesDevice(ctx) ?: return false
        m.mode = AudioManager.MODE_IN_COMMUNICATION
        val ok = m.setCommunicationDevice(d)
        if (!ok) m.mode = AudioManager.MODE_NORMAL
        return ok
    }

    fun release(ctx: Context) {
        val m = am(ctx)
        m.clearCommunicationDevice()
        m.mode = AudioManager.MODE_NORMAL
    }
}
