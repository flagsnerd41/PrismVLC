package com.example.myvlc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.AttributeSet
import android.widget.SeekBar
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.content.ContextCompat

/**
 * Volume slider for the player's top-right glass pill. Wires itself to the media volume:
 * dragging changes the volume, and it follows the hardware keys and the swipe gesture.
 */
class VolumeSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var touching = false
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (!touching) sync()
        }
    }

    init {
        max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        sync()
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                touching = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                touching = false
            }
        })
    }

    private fun sync() {
        progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
        if (!registered) {
            try {
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                    ContextCompat.RECEIVER_EXPORTED
                )
                registered = true
            } catch (e: Exception) {
                // the slider still works, it just won't follow the volume keys
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (registered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
            }
            registered = false
        }
    }
}
