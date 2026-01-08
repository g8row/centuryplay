#!/usr/bin/env python3
"""
PTP (Precision Time Protocol) Timing Implementation for AirPlay 2

This script implements the PTP timing protocol used by AirPlay 2 senders.
When streaming to an AirPlay 2 receiver:
- The SENDER (this app) acts as the PTP MASTER clock
- The RECEIVER's NQPTP daemon listens and calculates offsets

PTP uses IEEE 1588-2008 with Apple Vendor Profile modifications.

Reference: NQPTP source code (github.com/mikebrady/nqptp)
"""

import socket
import struct
import time
import threading
import secrets
from dataclasses import dataclass
from typing import Optional, Tuple
from enum import IntEnum


class PTPMessageType(IntEnum):
    """PTP Message Types (IEEE 1588-2008)"""
    SYNC = 0x00
    DELAY_REQ = 0x01
    FOLLOW_UP = 0x08
    DELAY_RESP = 0x09
    ANNOUNCE = 0x0B
    SIGNALING = 0x0C
    MANAGEMENT = 0x0D


class PTPControlField(IntEnum):
    """PTP Control Field values"""
    SYNC = 0x00
    DELAY_REQ = 0x01
    FOLLOW_UP = 0x02
    DELAY_RESP = 0x03
    MANAGEMENT = 0x04
    ALL_OTHERS = 0x05


@dataclass
class PTPTimestamp:
    """PTP Timestamp: 48-bit seconds + 32-bit nanoseconds"""
    seconds_msb: int  # Upper 16 bits of seconds (usually 0)
    seconds_lsb: int  # Lower 32 bits of seconds
    nanoseconds: int  # Nanoseconds (0-999,999,999)
    
    @classmethod
    def now(cls) -> 'PTPTimestamp':
        """Create timestamp from current time"""
        t = time.time()
        sec = int(t)
        nsec = int((t - sec) * 1e9)
        return cls(
            seconds_msb=(sec >> 32) & 0xFFFF,
            seconds_lsb=sec & 0xFFFFFFFF,
            nanoseconds=nsec
        )
    
    def to_bytes(self) -> bytes:
        """Pack timestamp into 10 bytes"""
        return struct.pack('>HIL', self.seconds_msb, self.seconds_lsb, self.nanoseconds)
    
    @classmethod
    def from_bytes(cls, data: bytes) -> 'PTPTimestamp':
        """Unpack timestamp from 10 bytes"""
        msb, lsb, ns = struct.unpack('>HIL', data[:10])
        return cls(msb, lsb, ns)
    
    def to_nanoseconds(self) -> int:
        """Convert to total nanoseconds since epoch"""
        sec = (self.seconds_msb << 32) | self.seconds_lsb
        return sec * 1_000_000_000 + self.nanoseconds


@dataclass
class PTPClockIdentity:
    """
    PTP Clock Identity (EUI-64 format)
    
    Derived from MAC address by inserting FF:FE in the middle.
    Example: MAC 11:22:33:44:55:66 -> Clock ID 11:22:33:FF:FE:44:55:66
    """
    identity: bytes  # 8 bytes
    
    @classmethod
    def from_mac(cls, mac: bytes) -> 'PTPClockIdentity':
        """Create EUI-64 clock identity from 6-byte MAC address"""
        if len(mac) != 6:
            raise ValueError("MAC address must be 6 bytes")
        # Insert FF:FE in the middle
        return cls(mac[:3] + b'\xFF\xFE' + mac[3:])
    
    @classmethod
    def random(cls) -> 'PTPClockIdentity':
        """Generate a random clock identity"""
        return cls(secrets.token_bytes(8))
    
    def to_bytes(self) -> bytes:
        return self.identity
    
    def __str__(self) -> str:
        return ':'.join(f'{b:02X}' for b in self.identity)


@dataclass
class PTPPortIdentity:
    """PTP Port Identity = Clock Identity + Port Number"""
    clock_identity: PTPClockIdentity
    port_number: int  # 16-bit port number (usually 1)
    
    def to_bytes(self) -> bytes:
        return self.clock_identity.to_bytes() + struct.pack('>H', self.port_number)
    
    @classmethod
    def from_bytes(cls, data: bytes) -> 'PTPPortIdentity':
        clock_id = PTPClockIdentity(data[:8])
        port_num = struct.unpack('>H', data[8:10])[0]
        return cls(clock_id, port_num)


class PTPHeader:
    """
    PTP Common Message Header (34 bytes)
    
    Format:
      0: transportSpecific (4 bits) | messageType (4 bits)
      1: versionPTP (4 bits) | reserved (4 bits)
      2-3: messageLength (16 bits)
      4: domainNumber (8 bits)
      5: reserved (8 bits)
      6-7: flagField (16 bits)
      8-15: correctionField (64 bits)
      16-19: reserved (32 bits)
      20-29: sourcePortIdentity (80 bits)
      30-31: sequenceId (16 bits)
      32: controlField (8 bits)
      33: logMessageInterval (8 bits, signed)
    """
    HEADER_SIZE = 34
    
    def __init__(
        self,
        message_type: PTPMessageType,
        source_port_identity: PTPPortIdentity,
        sequence_id: int,
        message_length: int,
        control_field: int = PTPControlField.ALL_OTHERS,
        log_message_interval: int = 0,
        domain_number: int = 0,
        flags: int = 0,
        correction_field: int = 0,
        transport_specific: int = 0,
    ):
        self.transport_specific = transport_specific
        self.message_type = message_type
        self.version_ptp = 2  # PTP v2
        self.message_length = message_length
        self.domain_number = domain_number
        self.flags = flags
        self.correction_field = correction_field
        self.source_port_identity = source_port_identity
        self.sequence_id = sequence_id
        self.control_field = control_field
        self.log_message_interval = log_message_interval
    
    def to_bytes(self) -> bytes:
        return struct.pack(
            '>BBHBB H Q L 10s H b b',
            (self.transport_specific << 4) | (self.message_type & 0x0F),
            (self.version_ptp << 4),
            self.message_length,
            self.domain_number,
            0,  # reserved
            self.flags,
            self.correction_field,
            0,  # reserved
            self.source_port_identity.to_bytes(),
            self.sequence_id,
            self.control_field,
            self.log_message_interval,
        )
    
    @classmethod
    def from_bytes(cls, data: bytes) -> 'PTPHeader':
        (byte0, byte1, msg_len, domain, _, flags, correction, _, 
         port_id_bytes, seq_id, control, log_interval) = struct.unpack(
            '>BBHBB H Q L 10s H b b', data[:cls.HEADER_SIZE]
        )
        
        return cls(
            message_type=PTPMessageType(byte0 & 0x0F),
            source_port_identity=PTPPortIdentity.from_bytes(port_id_bytes),
            sequence_id=seq_id,
            message_length=msg_len,
            control_field=control,
            log_message_interval=log_interval,
            domain_number=domain,
            flags=flags,
            correction_field=correction,
            transport_specific=(byte0 >> 4) & 0x0F,
        )


class PTPAnnounceMessage:
    """
    PTP Announce Message
    
    Announces this clock to the network. Other clocks use this to determine
    the best master clock (BMCA - Best Master Clock Algorithm).
    
    Apple PTP Profile uses:
    - priority1 = 248
    - priority2 = 248
    - clockClass = 248
    - clockAccuracy = 0xFE (unknown)
    - logAnnounceInterval = 0 (1 second)
    """
    
    def __init__(
        self,
        source_port_identity: PTPPortIdentity,
        sequence_id: int,
        grandmaster_identity: Optional[PTPClockIdentity] = None,
        grandmaster_priority1: int = 248,
        grandmaster_priority2: int = 248,
        grandmaster_clock_class: int = 248,
        grandmaster_clock_accuracy: int = 0xFE,
        grandmaster_clock_variance: int = 0xFFFF,
        steps_removed: int = 0,
        time_source: int = 0xA0,  # Internal oscillator
        current_utc_offset: int = 37,  # TAI - UTC offset (leap seconds)
    ):
        self.source_port_identity = source_port_identity
        self.sequence_id = sequence_id
        self.grandmaster_identity = grandmaster_identity or source_port_identity.clock_identity
        self.grandmaster_priority1 = grandmaster_priority1
        self.grandmaster_priority2 = grandmaster_priority2
        self.grandmaster_clock_class = grandmaster_clock_class
        self.grandmaster_clock_accuracy = grandmaster_clock_accuracy
        self.grandmaster_clock_variance = grandmaster_clock_variance
        self.steps_removed = steps_removed
        self.time_source = time_source
        self.current_utc_offset = current_utc_offset
    
    def to_bytes(self) -> bytes:
        # Header (34 bytes)
        header = PTPHeader(
            message_type=PTPMessageType.ANNOUNCE,
            source_port_identity=self.source_port_identity,
            sequence_id=self.sequence_id,
            message_length=64,  # 34 header + 10 timestamp + 20 announce data
            control_field=PTPControlField.ALL_OTHERS,
            log_message_interval=0,  # 1 second announce interval
            flags=0x0008,  # PTP_TIMESCALE flag
        ).to_bytes()
        
        # Origin timestamp (10 bytes) - all zeros for announce
        origin_timestamp = PTPTimestamp(0, 0, 0).to_bytes()
        
        # Announce specific data (20 bytes)
        announce_data = struct.pack(
            '>hH BBH H 8s B B',
            self.current_utc_offset,  # currentUtcOffset (signed 16-bit)
            0,  # reserved
            self.grandmaster_priority1,
            self.grandmaster_clock_class,
            (self.grandmaster_clock_accuracy << 8) | ((self.grandmaster_clock_variance >> 8) & 0xFF),
            self.grandmaster_clock_variance & 0xFF,
            self.grandmaster_identity.to_bytes(),
            self.steps_removed,
            self.time_source,
        )
        
        # Actually, let me restructure this properly
        # Announce payload after header:
        # - originTimestamp (10 bytes)
        # - currentUtcOffset (2 bytes, signed)
        # - reserved (1 byte)
        # - grandmasterPriority1 (1 byte)
        # - grandmasterClockQuality (4 bytes)
        # - grandmasterPriority2 (1 byte)
        # - grandmasterIdentity (8 bytes)
        # - stepsRemoved (2 bytes)
        # - timeSource (1 byte)
        
        announce_payload = struct.pack(
            '>10s hx B BBH B 8s H B',
            origin_timestamp,  # 10 bytes
            self.current_utc_offset,  # 2 bytes signed + 1 reserved
            self.grandmaster_priority1,  # 1 byte
            self.grandmaster_clock_class,  # 1 byte
            self.grandmaster_clock_accuracy,  # 1 byte
            self.grandmaster_clock_variance,  # 2 bytes
            self.grandmaster_priority2,  # 1 byte
            self.grandmaster_identity.to_bytes(),  # 8 bytes
            self.steps_removed,  # 2 bytes
            self.time_source,  # 1 byte
        )
        
        return header + announce_payload[10:]  # Skip the timestamp we already packed in header... 
        # Actually let's just return header + origin_timestamp + the rest


class PTPSyncMessage:
    """
    PTP Sync Message
    
    Sent by the master to start a time synchronization.
    For two-step clocks (like Apple's), this is followed by a Follow_Up message
    containing the precise transmit timestamp.
    """
    
    def __init__(
        self,
        source_port_identity: PTPPortIdentity,
        sequence_id: int,
        origin_timestamp: Optional[PTPTimestamp] = None,
        log_sync_interval: int = -3,  # Apple uses 125ms (2^-3 seconds)
    ):
        self.source_port_identity = source_port_identity
        self.sequence_id = sequence_id
        self.origin_timestamp = origin_timestamp or PTPTimestamp(0, 0, 0)
        self.log_sync_interval = log_sync_interval
    
    def to_bytes(self) -> bytes:
        header = PTPHeader(
            message_type=PTPMessageType.SYNC,
            source_port_identity=self.source_port_identity,
            sequence_id=self.sequence_id,
            message_length=44,  # 34 header + 10 timestamp
            control_field=PTPControlField.SYNC,
            log_message_interval=self.log_sync_interval,
            flags=0x0208,  # TWO_STEP | PTP_TIMESCALE
        ).to_bytes()
        
        return header + self.origin_timestamp.to_bytes()


class PTPFollowUpMessage:
    """
    PTP Follow_Up Message
    
    Sent after a Sync message to provide the precise transmit timestamp.
    This is part of the "two-step" clock mechanism.
    """
    
    def __init__(
        self,
        source_port_identity: PTPPortIdentity,
        sequence_id: int,  # Must match the Sync message
        precise_origin_timestamp: PTPTimestamp,
        log_sync_interval: int = -3,
    ):
        self.source_port_identity = source_port_identity
        self.sequence_id = sequence_id
        self.precise_origin_timestamp = precise_origin_timestamp
        self.log_sync_interval = log_sync_interval
    
    def to_bytes(self) -> bytes:
        header = PTPHeader(
            message_type=PTPMessageType.FOLLOW_UP,
            source_port_identity=self.source_port_identity,
            sequence_id=self.sequence_id,
            message_length=44,  # 34 header + 10 timestamp
            control_field=PTPControlField.FOLLOW_UP,
            log_message_interval=self.log_sync_interval,
            flags=0x0008,  # PTP_TIMESCALE
        ).to_bytes()
        
        return header + self.precise_origin_timestamp.to_bytes()


class PTPMasterClock:
    """
    PTP Master Clock implementation for AirPlay 2
    
    Sends periodic Announce, Sync, and Follow_Up messages to receivers.
    This makes the sender act as the timing master.
    """
    
    # Standard PTP ports
    EVENT_PORT = 319   # For Sync, Delay_Req, etc. (event messages)
    GENERAL_PORT = 320  # For Announce, Follow_Up, etc. (general messages)
    
    # Multicast addresses
    MULTICAST_ADDR_V4 = '224.0.1.129'  # PTP primary multicast
    
    def __init__(
        self,
        clock_identity: Optional[PTPClockIdentity] = None,
        interface: Optional[str] = None,
    ):
        self.clock_identity = clock_identity or PTPClockIdentity.random()
        self.port_identity = PTPPortIdentity(self.clock_identity, 1)
        self.interface = interface
        
        self.announce_sequence = 0
        self.sync_sequence = 0
        
        self._running = False
        self._event_socket: Optional[socket.socket] = None
        self._general_socket: Optional[socket.socket] = None
        self._thread: Optional[threading.Thread] = None
        
        # Target IP (unicast mode for AirPlay)
        self._target_ip: Optional[str] = None
    
    def _create_socket(self, port: int) -> socket.socket:
        """Create a UDP socket bound to the specified port"""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        # Don't bind to specific port when sending unicast
        sock.bind(('', 0))
        return sock
    
    def start(self, target_ip: str):
        """Start sending PTP messages to the target AirPlay receiver"""
        self._target_ip = target_ip
        self._running = True
        
        self._event_socket = self._create_socket(0)
        self._general_socket = self._create_socket(0)
        
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        
        print(f"PTP Master Clock started: {self.clock_identity}")
        print(f"Sending timing to: {target_ip}")
    
    def stop(self):
        """Stop the PTP master clock"""
        self._running = False
        if self._thread:
            self._thread.join(timeout=1.0)
        if self._event_socket:
            self._event_socket.close()
        if self._general_socket:
            self._general_socket.close()
    
    def _send_announce(self):
        """Send an Announce message"""
        msg = PTPAnnounceMessage(
            source_port_identity=self.port_identity,
            sequence_id=self.announce_sequence,
        )
        # Note: Announce goes to general port
        data = self._build_announce_packet()
        self._general_socket.sendto(data, (self._target_ip, self.GENERAL_PORT))
        self.announce_sequence = (self.announce_sequence + 1) & 0xFFFF
    
    def _send_sync_follow_up(self):
        """Send a Sync message followed by Follow_Up"""
        seq = self.sync_sequence
        
        # Capture timestamp just before sending
        t1 = PTPTimestamp.now()
        
        # Send Sync (event message)
        sync_msg = PTPSyncMessage(
            source_port_identity=self.port_identity,
            sequence_id=seq,
            origin_timestamp=PTPTimestamp(0, 0, 0),  # Placeholder for two-step
        )
        self._event_socket.sendto(sync_msg.to_bytes(), (self._target_ip, self.EVENT_PORT))
        
        # Capture precise transmit timestamp
        t1_precise = PTPTimestamp.now()
        
        # Send Follow_Up with precise timestamp (general message)
        follow_up_msg = PTPFollowUpMessage(
            source_port_identity=self.port_identity,
            sequence_id=seq,
            precise_origin_timestamp=t1_precise,
        )
        self._general_socket.sendto(follow_up_msg.to_bytes(), (self._target_ip, self.GENERAL_PORT))
        
        self.sync_sequence = (self.sync_sequence + 1) & 0xFFFF
    
    def _build_announce_packet(self) -> bytes:
        """Build a complete Announce packet"""
        # Header
        header = PTPHeader(
            message_type=PTPMessageType.ANNOUNCE,
            source_port_identity=self.port_identity,
            sequence_id=self.announce_sequence,
            message_length=64,
            control_field=PTPControlField.ALL_OTHERS,
            log_message_interval=0,  # 1 second
            flags=0x0008,  # PTP_TIMESCALE
        ).to_bytes()
        
        # Origin timestamp (all zeros for announce)
        origin_ts = bytes(10)
        
        # Announce-specific fields
        # currentUtcOffset (2 bytes, signed)
        # reserved (1 byte)
        # grandmasterPriority1 (1 byte)
        # grandmasterClockQuality (4 bytes):
        #   - clockClass (1 byte)
        #   - clockAccuracy (1 byte)
        #   - offsetScaledLogVariance (2 bytes)
        # grandmasterPriority2 (1 byte)
        # grandmasterIdentity (8 bytes)
        # stepsRemoved (2 bytes)
        # timeSource (1 byte)
        
        announce_fields = struct.pack(
            '>hx B B B H B 8s H B',
            37,   # currentUtcOffset (TAI - UTC)
            248,  # grandmasterPriority1 (Apple profile)
            248,  # clockClass
            0xFE, # clockAccuracy (unknown)
            0xFFFF,  # offsetScaledLogVariance
            248,  # grandmasterPriority2 (Apple profile)
            self.clock_identity.to_bytes(),
            0,    # stepsRemoved
            0xA0, # timeSource (internal oscillator)
        )
        
        return header + origin_ts + announce_fields
    
    def _run(self):
        """Main loop - send periodic PTP messages"""
        announce_interval = 1.0  # 1 second (2^0)
        sync_interval = 0.125    # 125ms (2^-3) - Apple profile
        
        last_announce = 0
        last_sync = 0
        
        while self._running:
            now = time.time()
            
            # Send Announce every second
            if now - last_announce >= announce_interval:
                self._send_announce()
                last_announce = now
            
            # Send Sync/Follow_Up every 125ms
            if now - last_sync >= sync_interval:
                self._send_sync_follow_up()
                last_sync = now
            
            # Sleep a bit to not burn CPU
            time.sleep(0.01)


def test_ptp_master():
    """Test the PTP master clock"""
    import sys
    
    if len(sys.argv) < 2:
        print("Usage: python ptp_timing.py <receiver_ip>")
        print("Example: python ptp_timing.py 192.168.1.76")
        return
    
    target_ip = sys.argv[1]
    
    # Create a random clock identity (in real use, derive from device MAC)
    clock_id = PTPClockIdentity.random()
    
    master = PTPMasterClock(clock_identity=clock_id)
    master.start(target_ip)
    
    try:
        print("\nSending PTP timing messages. Press Ctrl+C to stop.\n")
        while True:
            time.sleep(1)
            print(f"Announce seq: {master.announce_sequence}, Sync seq: {master.sync_sequence}")
    except KeyboardInterrupt:
        print("\nStopping...")
    finally:
        master.stop()


if __name__ == '__main__':
    test_ptp_master()
