# AirPlay 2 PTP Timing Investigation

> **Status**: Active Investigation
> **Last Updated**: January 2026

This document tracks the investigation into PTP (Precision Time Protocol) timing issues that prevent AirPlay 2 audio from playing correctly.

## Table of Contents

1. [Overview](#overview)
2. [NQPTP Architecture](#nqptp-architecture)
3. [PTP Packet Requirements](#ptp-packet-requirements)
4. [Known Issues](#known-issues)
5. [Proposed Fixes](#proposed-fixes)
6. [Test Results](#test-results)

---

## Overview

AirPlay 2 uses PTP (Precision Time Protocol, IEEE 1588) for time synchronization instead of NTP. The **sender** (our app) acts as the PTP **master clock**, and the receiver's NQPTP daemon monitors our clock to synchronize playback.

### Current State

| Component | Status |
|-----------|--------|
| SRP-6a Transient Pairing | ✅ Working |
| HAP Session Encryption | ✅ Working |
| SETUP (Event Channel) | ✅ Working |
| SETUP (Audio Stream) | ✅ Working |
| RECORD | ✅ Working |
| **PTP Timing** | ❌ **Issues** |
| Audio Playback | ❌ Not working (no timing) |

---

## NQPTP Architecture

NQPTP (Not Quite PTP) is a companion daemon that provides timing information to shairport-sync.

### How NQPTP Works

```
┌─────────────────┐                    ┌─────────────────┐
│   AirPlay 2     │   UDP 319/320      │     NQPTP       │
│   Sender (us)   │ ─────────────────► │   (receiver)    │
│                 │  PTP messages      │                 │
└─────────────────┘                    └────────┬────────┘
                                                │
                                       Shared Memory
                                       (/dev/shm/nqptp)
                                                │
                                       ┌────────┴────────┐
                                       │  shairport-sync │
                                       │   (receiver)    │
                                       └─────────────────┘
```

### NQPTP Message Flow

1. **shairport-sync → NQPTP** (UDP port 9000): `"T <sender_ip>"` command
   - Tells NQPTP to start monitoring PTP messages from our IP
   
2. **Sender → NQPTP** (UDP ports 319/320): PTP messages
   - ANNOUNCE: Declares our clock identity
   - SYNC: Starts two-step timing exchange
   - FOLLOW_UP: Contains precise origin timestamp

3. **NQPTP → Shared Memory**: Updates timing offset
   - shairport-sync reads this to synchronize audio playback

### Critical NQPTP Behavior

From `nqptp-message-handlers.c`:

```c
void handle_sync(...) {
  if (clock_private_info->clock_id == 0) {
    debug(2, "Sync received before announcement -- discarded.");
  } else {
    // Process sync...
  }
}

void handle_follow_up(...) {
  if (clock_private_info->clock_id == 0) {
    debug(2, "Follow_Up received before announcement -- discarded.");
  } else {
    // Process follow_up...
  }
}
```

**Key insight**: NQPTP **discards SYNC and FOLLOW_UP** until it sees an ANNOUNCE message that establishes the clock identity!

---

## PTP Packet Requirements

### PTP Common Header (34 bytes)

From NQPTP's `nqptp-ptp-definitions.h`:

```c
struct ptp_common_message_header {
  uint8_t transportSpecificAndMessageID; // Bits [7:4]=transportSpecific, [3:0]=messageType
  uint8_t reservedAndVersionPTP;         // Bits [7:4]=reserved, [3:0]=versionPTP (0x02)
  uint16_t messageLength;                // Total message length
  uint8_t domainNumber;                  // 0 for gPTP
  uint8_t reserved_b;                    // 0
  uint16_t flags;                        // See flags below
  uint64_t correctionField;              // Fixed-point nanoseconds (divide by 2^16)
  uint32_t reserved_l;                   // 0
  uint8_t clockIdentity[8];              // EUI-64 clock identity
  uint16_t sourcePortID;                 // Usually 1
  uint16_t sequenceId;                   // Increments per message type
  uint8_t controlField;                  // Depends on message type
  uint8_t logMessagePeriod;              // Signed, log2 of seconds
};
```

### Field Details

| Field | Value for Apple PTP | Notes |
|-------|---------------------|-------|
| transportSpecific | `0x1` | 802.1AS (gPTP) profile |
| messageType | varies | 0x00=Sync, 0x08=Follow_Up, 0x0B=Announce |
| versionPTP | `0x02` | PTP v2 |
| domainNumber | `0x00` | gPTP domain |
| flags | see below | Important! |
| correctionField | `0` or actual | Usually 0 for us |
| clockIdentity | 8 bytes | EUI-64 from MAC or random |
| sourcePortID | `1` | Port number |
| controlField | see table | Varies by message |
| logMessagePeriod | see table | Varies by message |

### Flags Field

| Bit | Name | Value for our use |
|-----|------|-------------------|
| 10 | PTP_TIMESCALE | 1 |
| 9 | TIME_TRACEABLE | 0 |
| 8 | FREQ_TRACEABLE | 0 |
| 7 | UTC_OFFSET_VALID | 0 |
| 6 | LEAP_61 | 0 |
| 5 | LEAP_59 | 0 |
| 4 | CURRENT_UTC_OFFSET_VALID | 0 |
| 1 | TWO_STEP | 1 (for Sync only) |
| 0 | LI_61 | 0 |

Common combinations:
- **SYNC**: `0x0208` (TWO_STEP | PTP_TIMESCALE)
- **FOLLOW_UP**: `0x0008` (PTP_TIMESCALE only)
- **ANNOUNCE**: `0x0008` (PTP_TIMESCALE only)

### Control Field Values

| Message | controlField |
|---------|--------------|
| Sync | 0x00 |
| Delay_Req | 0x01 |
| Follow_Up | 0x02 |
| Delay_Resp | 0x03 |
| Management | 0x04 |
| All Others | 0x05 |

### logMessagePeriod Values (Apple PTP Profile)

| Message | logMessagePeriod | Meaning |
|---------|------------------|---------|
| Announce | 0x00 | 2^0 = 1 second |
| Sync | 0xFD (-3) | 2^-3 = 125ms |
| Follow_Up | 0xFD (-3) | 2^-3 = 125ms |

### Message-Specific Fields

#### ANNOUNCE (Total: 64 bytes)

```
Header (34 bytes) +
originTimestamp (10 bytes) +
currentUtcOffset (2 bytes, signed) +
reserved (1 byte) +
grandmasterPriority1 (1 byte) = 248 +
grandmasterClockQuality (4 bytes):
  - clockClass (1 byte) = 248
  - clockAccuracy (1 byte) = 0xFE
  - offsetScaledLogVariance (2 bytes) = 0xFFFF +
grandmasterPriority2 (1 byte) = 248 +
grandmasterIdentity (8 bytes) = our clock ID +
stepsRemoved (2 bytes) = 0 +
timeSource (1 byte) = 0xA0 (internal oscillator)
```

#### SYNC (Total: 44 bytes)

```
Header (34 bytes) +
originTimestamp (10 bytes) = zeros for two-step
```

#### FOLLOW_UP (Total: 44+ bytes)

```
Header (34 bytes) +
preciseOriginTimestamp (10 bytes):
  - secondsHi (2 bytes) +
  - secondsLo (4 bytes) +
  - nanoseconds (4 bytes) +
[Optional TLVs]
```

### Timestamp Format (10 bytes)

```
+-------+-------+-------+-------+-------+-------+-------+-------+-------+-------+
| Sec_Hi (16 bits)      | Seconds_Low (32 bits)          | Nanoseconds (32 bits) |
+-------+-------+-------+-------+-------+-------+-------+-------+-------+-------+
     bytes 0-1                bytes 2-5                       bytes 6-9
```

All values are **big-endian** (network byte order).

---

## Known Issues

### Issue 1: Source Port Not 319/320

**Problem**: The current `airplay2_transient.py` binds to ephemeral ports instead of 319/320.

**Why it matters**: NQPTP may filter by source port. While testing shows this isn't strictly required, it could cause issues.

**Evidence**:
```python
# Current code (may work but non-standard):
self._ptp_sock_319.bind(('', 0))  # Ephemeral port

# Correct approach (requires root):
self._ptp_sock_319.bind(('', 319))
```

### Issue 2: ANNOUNCE Timing

**Problem**: ANNOUNCE messages may not be reaching NQPTP before SYNC/FOLLOW_UP.

**Why it matters**: Per NQPTP source code:
```c
if (clock_private_info->clock_id == 0) {
  debug(2, "Sync received before announcement -- discarded.");
}
```

**Evidence**: Looking at `airplay2_transient.py`:
```python
# PTP clock starts before SETUP, but NQPTP isn't listening yet
clock_id = self._start_ptp_master_clock()

# SETUP happens here - THIS tells shairport to tell NQPTP to listen
status, headers, resp_body = self.send_rtsp("SETUP", ...)

# ANNOUNCE burst comes AFTER setup - correct timing
self._send_ptp_announce_burst(count=5, delay=0.05)
```

The logic looks correct, but need to verify ANNOUNCE is properly formatted.

### Issue 3: Header Format Errors

**Problem**: The PTP header may have incorrect values.

**Possible issues**:
1. `transportSpecific` field wrong (should be 0x1 for Apple/gPTP)
2. `flags` field wrong 
3. `controlField` values incorrect
4. Byte ordering issues

**Verification needed**: Compare actual packets vs expected format.

### Issue 4: preciseOriginTimestamp Calculation

**Problem**: The timestamp in FOLLOW_UP may be incorrect.

**Expected format**: Current time as 48-bit seconds + 32-bit nanoseconds since PTP epoch (1970-01-01).

**Current implementation** in `airplay2_transient.py`:
```python
now_ns = time.time_ns()
seconds = now_ns // 1_000_000_000
nanoseconds = now_ns % 1_000_000_000
struct.pack_into('>H', msg, 34, (seconds >> 32) & 0xFFFF)  # seconds_hi
struct.pack_into('>I', msg, 36, seconds & 0xFFFFFFFF)      # seconds_lo
struct.pack_into('>I', msg, 40, nanoseconds)               # nanoseconds
```

This looks correct for the Unix epoch (which PTP uses for the PTP profile used by Apple).

---

## Proposed Fixes

### Fix 1: Verify ANNOUNCE Format

Compare our ANNOUNCE packets against the expected structure:

```python
def build_announce_message(seq_id: int) -> bytes:
    """Build PTP Announce message (64 bytes)."""
    msg = bytearray(64)
    
    # Header (34 bytes)
    msg[0] = 0x1B  # transportSpecific=0x1, messageType=0x0B (Announce)
    msg[1] = 0x02  # versionPTP=2
    struct.pack_into('>H', msg, 2, 64)  # messageLength
    msg[4] = 0x00  # domainNumber (gPTP)
    msg[5] = 0x00  # reserved
    struct.pack_into('>H', msg, 6, 0x0008)  # flags: PTP_TIMESCALE
    struct.pack_into('>Q', msg, 8, 0)  # correctionField
    struct.pack_into('>I', msg, 16, 0)  # reserved
    struct.pack_into('>Q', msg, 20, clock_id)  # clockIdentity
    struct.pack_into('>H', msg, 28, 1)  # sourcePortID
    struct.pack_into('>H', msg, 30, seq_id)  # sequenceId
    msg[32] = 0x05  # controlField (Other)
    msg[33] = 0x00  # logMessagePeriod (1 second)
    
    # originTimestamp (10 bytes) - zeros
    # Already zeros
    
    # Announce fields (20 bytes)
    struct.pack_into('>h', msg, 44, 37)  # currentUtcOffset
    msg[46] = 0x00  # reserved
    msg[47] = 248  # grandmasterPriority1 (Apple profile)
    msg[48] = 248  # clockClass
    msg[49] = 0xFE  # clockAccuracy (unknown)
    struct.pack_into('>H', msg, 50, 0xFFFF)  # offsetScaledLogVariance
    msg[52] = 248  # grandmasterPriority2
    struct.pack_into('>Q', msg, 53, clock_id)  # grandmasterIdentity
    struct.pack_into('>H', msg, 61, 0)  # stepsRemoved
    msg[63] = 0xA0  # timeSource (internal oscillator)
    
    return bytes(msg)
```

### Fix 2: Add Detailed Debug Logging

Add packet hex dumps to debug:

```python
def dump_ptp_packet(name: str, packet: bytes):
    print(f"\n=== {name} ===")
    print(f"Length: {len(packet)} bytes")
    print(f"TransportSpecific: {(packet[0] >> 4) & 0xF:#x}")
    print(f"MessageType: {packet[0] & 0xF:#x}")
    print(f"VersionPTP: {packet[1] & 0xF}")
    print(f"MessageLength: {struct.unpack('>H', packet[2:4])[0]}")
    print(f"Flags: {struct.unpack('>H', packet[6:8])[0]:#06x}")
    clock_id = struct.unpack('>Q', packet[20:28])[0]
    print(f"ClockIdentity: {clock_id:#018x}")
    print(f"SequenceId: {struct.unpack('>H', packet[30:32])[0]}")
    print(f"ControlField: {packet[32]}")
    print(f"LogMessagePeriod: {struct.unpack('b', bytes([packet[33]]))[0]}")
    print(f"Hex: {packet.hex()}")
```

### Fix 3: Run NQPTP with Verbose Logging

On the receiver machine:

```bash
# Stop existing nqptp
sudo pkill nqptp

# Run with maximum verbosity, logging to file
sudo nqptp -vvv 2>&1 | tee /tmp/nqptp.log
```

Watch for:
- `"announcement seen from ..."` - Clock recognized
- `"Sync received before announcement -- discarded."` - Problem!
- `"FOLLOWUP from ..."` - Follow_Up processing

### Fix 4: Verify Port 9000 "T" Command

Before we send PTP packets, shairport-sync tells NQPTP our IP via:
```
T 192.168.1.76
```

This happens automatically in the SETUP handler. Verify with:

```bash
# On receiver, watch UDP port 9000
sudo tcpdump -i any port 9000 -X
```

---

## Applied Fixes

### Fix 1: Corrected PTP Flags Field (DONE ✓)

**Before**: `flags = 0x0408` (incorrect bits)

**After**:
- SYNC: `flags = 0x0208` (twoStepFlag 0x0200 + ptpTimescale 0x0008)
- ANNOUNCE/FOLLOW_UP: `flags = 0x0008` (ptpTimescale only)

**IEEE 1588 Flag Bits**:
| Bit | Value | Name |
|-----|-------|------|
| 1 | 0x0002 | twoStepFlag |
| 3 | 0x0008 | ptpTimescale |

The old value `0x0408` was setting bit 10 (0x0400) which is incorrect.

### Fix 2: Corrected grandmasterClockQuality (DONE ✓)

**Before**: `0xF8FE436A` (incorrect variance)

**After**: `0xF8FEFFFF`

Breakdown:
- clockClass = 0xF8 (248) - Apple PTP profile
- clockAccuracy = 0xFE (254) - Unknown accuracy
- offsetScaledLogVariance = 0xFFFF - Unknown variance

### Fix 3: Made ANNOUNCE Burst Consistent (DONE ✓)

The `_send_ptp_announce_burst()` function had different `grandmasterClockQuality` formatting.
Now both the main PTP loop and burst function use identical packet format.

---

## Test Results

### Test 1: After PTP Flags Fix (2026-01-06)

**Script**: `airplay2_transient.py` with fixed PTP flags
**Result**: ✅ **SUCCESS**

**Python Script Output**:
```
============================================================
TRANSIENT PAIRING COMPLETE!
============================================================
...
  [PTP] Bound to ports 319 and 320 (using SO_REUSEPORT)
  [PTP] Master clock started, clock_id=1c80317fa3b1799d
...
  [PTP] ANNOUNCE burst complete
...
STREAMING READY!
...
  Sent 1s...
  Sent 2s...
  Sent 3s...
  Sent 4s...
  Sent 5s...
Sent 625 frames (625 packets)
```

**NQPTP Output** (key lines):
```
FOLLOWUP from 1c80317fa3b1799d, 192.168.1.76.
Clock 1c80317fa3b1799d, grandmaster 1c80317fa3b1799d. Offset: 18882ba965e48ef4...
```

**shairport-sync Output** (key lines):
```
"player.c:489" Connection 2: synced by first packet, seqno 0.
"player.c:173" Hammerton Decoder used on encrypted audio.
```

**Observations**:
- ✅ Pairing: Success (M1-M4 transient)
- ✅ SETUP (Event/Audio): Success
- ✅ PTP Timing: NQPTP tracking our clock ID correctly!
- ✅ Audio: Packets received and decoded
- ⚠️ Note: "No NQPTP master clock" appears initially but then syncs

---

## References

- [NQPTP Source](https://github.com/mikebrady/nqptp) - Timing daemon
- [shairport-sync Source](https://github.com/mikebrady/shairport-sync) - AirPlay receiver
- [IEEE 1588-2008](https://standards.ieee.org/standard/1588-2008.html) - PTP standard
- [IEEE 802.1AS](https://1.ieee802.org/tsn/802-1as/) - gPTP profile
- [pyatv](https://github.com/postlund/pyatv) - Python AirPlay 2 implementation
