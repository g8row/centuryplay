package com.airplay.streamer.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.airplay.streamer.R
import com.airplay.streamer.discovery.AirPlayDevice
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

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
        private val card: MaterialCardView = itemView as MaterialCardView
        private val speakerName: TextView = itemView.findViewById(R.id.speakerName)
        private val speakerAddress: TextView = itemView.findViewById(R.id.speakerAddress)
        private val protocolBadge: TextView = itemView.findViewById(R.id.protocolChip)

        fun bind(item: SpeakerItem, onConnectClick: (AirPlayDevice) -> Unit) {
            speakerName.text = item.device.displayName
            speakerAddress.text = item.device.host
            
            // Show protocol version based on port
            val isV2 = item.device.port == 7000
            protocolBadge.text = if (isV2) "v2" else "v1"

            // Get Material colors
            val colorPrimary = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorPrimary)
            val density = itemView.resources.displayMetrics.density
            
            // Set stroke immediately without animation (prevents RecyclerView crash on rapid tapping)
            val targetStrokeWidth = if (item.isConnected) (3 * density).toInt() else 0
            
            card.strokeColor = colorPrimary
            card.setStrokeColor(ColorStateList.valueOf(colorPrimary))
            
            // Set stroke width immediately (no animation to prevent RecyclerView conflicts)
            card.strokeWidth = targetStrokeWidth
            
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
