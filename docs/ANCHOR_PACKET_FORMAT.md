# AirPlay 2 Anchor Packet Format (Type 215/0xD7)

## Discovered from shairport-sync rtp.c source analysis

The AirPlay 2 protocol uses **Type 215 (0xD7)** packets on the **Control Port** (UDP) to establish timing anchors that map RTP timestamps to PTP network time. This is critical for synchronized audio playback.

## Packet Structure (28 bytes)

```
Offset  Size    Field                   Description
------  ----    -----                   -----------
0       1       Flags                   0x80 (V=2) | 0x10 (sentinel for first packet)
1       1       Type                    0xD7 (215) - anchor/timing packet
2       2       Sequence Number         Big-endian uint16
4       4       frame_1                 RTP timestamp + latency (77175 frames)
8       8       remote_packet_time_ns   PTP network time in nanoseconds (big-endian uint64)
16      4       frame_2                 RTP timestamp (the frame the time refers to)
20      8       clock_id                PTP clock ID (big-endian uint64)
```

## Key Points

1. **Sentinel Packet**: First anchor packet MUST have bit 0x10 set in byte 0
2. **Latency**: frame_1 = frame_2 + 77175 frames (standard AirPlay latency)
3. **Clock ID**: Must match the clock ID used in PTP ANNOUNCE messages
4. **PTP Time**: Network time in nanoseconds, not seconds
5. **Frequency**: iPhone sends anchor packets periodically (~1 second intervals)

## Python Implementation

```python
def send_anchor_packet(rtp_ts, ptp_time_ns, clock_id, is_sentinel=False):
    LATENCY_FRAMES = 77175
    
    packet = bytearray(28)
    packet[0] = 0x80 | (0x10 if is_sentinel else 0x00)
    packet[1] = 0xD7  # Type 215
    
    struct.pack_into('>H', packet, 2, seq_no)
    
    frame_2 = rtp_ts & 0xFFFFFFFF
    frame_1 = (frame_2 + LATENCY_FRAMES) & 0xFFFFFFFF
    
    struct.pack_into('>I', packet, 4, frame_1)
    struct.pack_into('>Q', packet, 8, int(ptp_time_ns))
    struct.pack_into('>I', packet, 16, frame_2)
    struct.pack_into('>Q', packet, 20, clock_id)
    
    ctrl_sock.sendto(packet, (host, control_port))
```

## Verification

When correctly implemented, shairport-sync logs should show:
- `set_ptp_anchor_info: clock: XXXX, rtptime: YYYY, networktime: ZZZZ`
- `Check packet from buffer N, timestamp T, X.X seconds ahead`

The "seconds ahead" value should be reasonable (0.1-1.0 seconds), not thousands of seconds.

## Source

Decoded from: https://github.com/mikebrady/shairport-sync/blob/master/rtp.c
- rtp_ap2_control_receiver() function
- case 215 (0xD7) - anchoring announcement
