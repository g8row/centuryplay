package com.airplay.streamer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.airplay.streamer.R
import com.airplay.streamer.discovery.AirPlayDevice
import com.google.android.material.chip.Chip

class SpeakerAdapter(
    private val onConnectClick: (AirPlayDevice) -> Unit
) : ListAdapter<SpeakerAdapter.SpeakerItem, SpeakerAdapter.ViewHolder>(SpeakerDiffCallback()) {

    data class SpeakerItem(
        val device: AirPlayDevice,
        val isConnected: Boolean = false
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_speaker, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, onConnectClick)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val speakerName: TextView = itemView.findViewById(R.id.speakerName)
        private val speakerAddress: TextView = itemView.findViewById(R.id.speakerAddress)
        private val connectButton: Button = itemView.findViewById(R.id.connectButton)
        private val statusIndicator: View = itemView.findViewById(R.id.statusIndicator)
        private val protocolChip: Chip = itemView.findViewById(R.id.protocolChip)

        fun bind(item: SpeakerItem, onConnectClick: (AirPlayDevice) -> Unit) {
            speakerName.text = item.device.displayName
            speakerAddress.text = item.device.host
            
            // Show protocol version based on port
            val isV2 = item.device.port == 7000
            protocolChip.text = if (isV2) "v2" else "v1"

            if (item.isConnected) {
                connectButton.text = itemView.context.getString(R.string.disconnect)
                statusIndicator.setBackgroundResource(R.drawable.indicator_dot_expressive)
            } else {
                connectButton.text = itemView.context.getString(R.string.connect)
                statusIndicator.setBackgroundResource(R.drawable.indicator_disconnected)
            }

            connectButton.setOnClickListener {
                onConnectClick(item.device)
            }
            
            // Make entire card clickable
            itemView.setOnClickListener {
                onConnectClick(item.device)
            }
        }
    }

    class SpeakerDiffCallback : DiffUtil.ItemCallback<SpeakerItem>() {
        override fun areItemsTheSame(oldItem: SpeakerItem, newItem: SpeakerItem): Boolean {
            return oldItem.device.host == newItem.device.host && 
                   oldItem.device.port == newItem.device.port
        }

        override fun areContentsTheSame(oldItem: SpeakerItem, newItem: SpeakerItem): Boolean {
            return oldItem == newItem
        }
    }
}
