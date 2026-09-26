package com.airplay.streamer.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.airplay.streamer.R
import com.airplay.streamer.discovery.AirPlayDevice
import com.airplay.streamer.engine.SpeakerState
import com.airplay.streamer.engine.SpeakerStatus
import com.airplay.streamer.engine.Transport
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.slider.Slider

/** One row: a discovered speaker plus its live session state, if it is part of the stream. */
data class SpeakerRow(
    val device: AirPlayDevice,
    val state: SpeakerState?,
    val transport: Transport,
) {
    val supported: Boolean get() = transport != Transport.UNSUPPORTED
    val active: Boolean get() = state != null && state.status != SpeakerStatus.FAILED
}

class SpeakerAdapter(
    private val onClick: (SpeakerRow) -> Unit,
    private val onLongClick: (SpeakerRow) -> Unit,
    private val onVolume: (SpeakerRow, Float) -> Unit,
) : ListAdapter<SpeakerRow, SpeakerAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_speaker, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_STATE }) holder.bindState(getItem(position))
        else holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView as MaterialCardView
        private val name: TextView = itemView.findViewById(R.id.speakerName)
        private val subtitle: TextView = itemView.findViewById(R.id.speakerSubtitle)
        private val icon: ImageView = itemView.findViewById(R.id.speakerIcon)
        private val iconFrame: View = itemView.findViewById(R.id.iconFrame)
        private val progress: CircularProgressIndicator = itemView.findViewById(R.id.speakerProgress)
        private val stateIcon: ImageView = itemView.findViewById(R.id.speakerStateIcon)
        private val volumeRow: LinearLayout = itemView.findViewById(R.id.volumeRow)
        private val volume: Slider = itemView.findViewById(R.id.speakerVolume)
        private var current: SpeakerRow? = null
        private var dragging = false

        init {
            card.setOnClickListener { current?.let(onClick) }
            card.setOnLongClickListener { current?.let(onLongClick); true }
            volume.addOnChangeListener { _, value, fromUser ->
                if (fromUser) current?.let { onVolume(it, value) }
            }
            volume.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) { dragging = true }
                override fun onStopTrackingTouch(slider: Slider) { dragging = false }
            })
        }

        fun bind(row: SpeakerRow) {
            current = row
            name.text = row.device.displayName.lowercase()
            icon.setImageResource(iconFor(row.device))
            bindState(row)
        }

        fun bindState(row: SpeakerRow) {
            current = row
            val ctx = itemView.context
            val st = row.state
            val active = row.active
            val primary = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorPrimary)
            card.strokeWidth = if (active) (2 * ctx.resources.displayMetrics.density).toInt() else 0
            card.strokeColor = primary
            card.setCardBackgroundColor(
                MaterialColors.getColor(
                    itemView,
                    if (active) com.google.android.material.R.attr.colorSecondaryContainer
                    else com.google.android.material.R.attr.colorSurfaceContainerHigh
                )
            )
            itemView.alpha = if (row.supported) 1f else 0.5f

            subtitle.text = when (st?.status) {
                SpeakerStatus.CONNECTING -> ctx.getString(R.string.speaker_status_connecting)
                SpeakerStatus.RECONNECTING -> ctx.getString(R.string.speaker_status_reconnecting)
                SpeakerStatus.PLAYING -> listOfNotNull(
                    ctx.getString(R.string.speaker_status_playing),
                    if (st.stats.contains("PCM")) null else ctx.getString(R.string.lossless),
                    if (st.transport == Transport.AIRPLAY2) "airplay 2" else null,
                ).joinToString(" · ")
                SpeakerStatus.NEEDS_PASSWORD -> ctx.getString(R.string.speaker_status_password)
                SpeakerStatus.NEEDS_PIN -> ctx.getString(R.string.speaker_status_pin)
                SpeakerStatus.NEEDS_ACCEPT -> ctx.getString(R.string.speaker_status_accept)
                SpeakerStatus.FAILED -> st.message ?: "failed"
                null -> if (!row.supported) ctx.getString(R.string.speaker_unsupported) else describe(row)
            }

            val busy = st?.status == SpeakerStatus.CONNECTING || st?.status == SpeakerStatus.RECONNECTING
            progress.visibility = if (busy) View.VISIBLE else View.GONE
            val stateRes = when (st?.status) {
                SpeakerStatus.PLAYING -> R.drawable.ic_check_circle
                SpeakerStatus.NEEDS_PASSWORD, SpeakerStatus.NEEDS_PIN -> R.drawable.ic_lock
                SpeakerStatus.NEEDS_ACCEPT, SpeakerStatus.FAILED -> R.drawable.ic_warning
                else -> 0
            }
            stateIcon.visibility = if (stateRes != 0 && !busy) View.VISIBLE else View.GONE
            if (stateRes != 0) {
                stateIcon.setImageResource(stateRes)
                val tint = if (st?.status == SpeakerStatus.PLAYING) primary
                else MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorError)
                stateIcon.imageTintList = ColorStateList.valueOf(tint)
            }

            val showVolume = st != null && (st.status == SpeakerStatus.PLAYING || busy)
            volumeRow.visibility = if (showVolume) View.VISIBLE else View.GONE
            if (showVolume && !dragging) volume.value = st!!.volume.coerceIn(0f, 1f)
            iconFrame.isActivated = active
        }

        private fun describe(row: SpeakerRow): String {
            val model = row.device.features["am"] ?: row.device.features["model"]
            val kind = when (row.transport) {
                Transport.AIRPLAY2 -> "airplay 2"
                else -> "airplay"
            }
            return listOfNotNull(prettyModel(model), kind, row.device.host).joinToString(" · ")
        }
    }

    companion object {
        private const val PAYLOAD_STATE = "state"

        fun iconFor(device: AirPlayDevice): Int {
            val model = (device.features["am"] ?: device.features["model"] ?: "").lowercase()
            return when {
                model.startsWith("appletv") -> R.drawable.ic_tv
                model.startsWith("audioaccessory") -> R.drawable.ic_homepod
                model.startsWith("macbook") || model.startsWith("imac") || model.startsWith("mac") -> R.drawable.ic_computer
                else -> R.drawable.ic_speaker
            }
        }

        fun prettyModel(model: String?): String? {
            val m = model?.lowercase() ?: return null
            return when {
                m.startsWith("appletv") -> "apple tv"
                m.startsWith("audioaccessory1") -> "homepod"
                m.startsWith("audioaccessory5") -> "homepod mini"
                m.startsWith("audioaccessory6") -> "homepod"
                m.startsWith("airport") -> "airport express"
                m.startsWith("macbook") -> "macbook"
                m.startsWith("mac") || m.startsWith("imac") -> "mac"
                m.contains("shairport") -> "shairport-sync"
                m.contains("sonos") -> "sonos"
                else -> null
            }
        }

        private val Diff = object : DiffUtil.ItemCallback<SpeakerRow>() {
            override fun areItemsTheSame(a: SpeakerRow, b: SpeakerRow) = a.device.identity == b.device.identity
            override fun areContentsTheSame(a: SpeakerRow, b: SpeakerRow) = a == b
            override fun getChangePayload(a: SpeakerRow, b: SpeakerRow): Any? =
                if (a.device == b.device && a.transport == b.transport) PAYLOAD_STATE else null
        }
    }
}
