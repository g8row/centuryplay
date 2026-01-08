#!/usr/bin/env python3
"""
NQPTP Shared Memory Monitor

This script reads the NQPTP shared memory interface to monitor
the clock synchronization state. This helps debug PTP timing.

The shared memory structure (from nqptp-shm-structures.h):
- version: uint16 (should be 10 = NQPTP_SHM_STRUCTURES_VERSION)
- main: shm_structure_set
- secondary: shm_structure_set (copy for atomic reads)

Each shm_structure_set contains:
- master_clock_id: uint64 (EUI-64 clock identity)
- local_time: uint64 (time when offset was calculated)
- local_to_master_time_offset: uint64 (add to local time to get master time)
- master_clock_start_time: uint64 (when master clock became master)

Usage:
    python nqptp_monitor.py
    
Requires access to /dev/shm/nqptp (run on the machine running NQPTP)
"""

import mmap
import struct
import time
import sys
import os
from dataclasses import dataclass
from typing import Optional

NQPTP_INTERFACE_NAME = "/nqptp"
NQPTP_SHM_PATH = "/dev/shm" + NQPTP_INTERFACE_NAME
NQPTP_SHM_STRUCTURES_VERSION = 10

# Control port for sending commands to NQPTP
NQPTP_CONTROL_PORT = 9000


@dataclass
class ShmStructureSet:
    """One copy of the shared memory timing data"""
    master_clock_id: int
    local_time: int
    local_to_master_time_offset: int
    master_clock_start_time: int
    
    @classmethod
    def from_bytes(cls, data: bytes) -> 'ShmStructureSet':
        """Unpack from 32 bytes"""
        (master_clock_id, local_time, 
         local_to_master_offset, master_start_time) = struct.unpack('<QQQQ', data[:32])
        return cls(
            master_clock_id=master_clock_id,
            local_time=local_time,
            local_to_master_time_offset=local_to_master_offset,
            master_clock_start_time=master_start_time,
        )
    
    def clock_id_str(self) -> str:
        """Format clock ID as hex string (EUI-64)"""
        if self.master_clock_id == 0:
            return "(none)"
        return ':'.join(f'{(self.master_clock_id >> (56 - i*8)) & 0xFF:02X}' for i in range(8))
    
    def format_time(self, ns: int) -> str:
        """Format nanoseconds as human-readable time"""
        if ns == 0:
            return "(none)"
        sec = ns / 1e9
        return f"{sec:.6f}s"


@dataclass
class NqptpShm:
    """Complete NQPTP shared memory structure"""
    version: int
    main: ShmStructureSet
    secondary: ShmStructureSet
    
    @classmethod
    def from_bytes(cls, data: bytes) -> 'NqptpShm':
        """Unpack from shared memory"""
        # Version is uint16 at offset 0
        version = struct.unpack('<H', data[0:2])[0]
        
        # Padding to align to 8 bytes, then main structure at offset 8
        # Actually looking at the struct, it should be:
        # uint16_t version (2 bytes)
        # 6 bytes padding to align to 8 bytes
        # main (32 bytes)
        # secondary (32 bytes)
        # Total: 2 + 6 + 32 + 32 = 72 bytes
        
        main = ShmStructureSet.from_bytes(data[8:40])
        secondary = ShmStructureSet.from_bytes(data[40:72])
        
        return cls(version=version, main=main, secondary=secondary)
    
    def is_valid(self) -> bool:
        """Check if both copies match (atomic read)"""
        return (
            self.main.master_clock_id == self.secondary.master_clock_id and
            self.main.local_time == self.secondary.local_time and
            self.main.local_to_master_time_offset == self.secondary.local_to_master_time_offset and
            self.main.master_clock_start_time == self.secondary.master_clock_start_time
        )


class NqptpMonitor:
    """Monitor NQPTP shared memory"""
    
    def __init__(self, shm_path: str = NQPTP_SHM_PATH):
        self.shm_path = shm_path
        self._fd: Optional[int] = None
        self._mm: Optional[mmap.mmap] = None
    
    def open(self):
        """Open the shared memory region"""
        try:
            self._fd = os.open(self.shm_path, os.O_RDONLY)
            self._mm = mmap.mmap(self._fd, 72, access=mmap.ACCESS_READ)
        except FileNotFoundError:
            raise FileNotFoundError(
                f"NQPTP shared memory not found at {self.shm_path}. "
                "Is NQPTP running?"
            )
        except PermissionError:
            raise PermissionError(
                f"Cannot read {self.shm_path}. "
                "Try running with sudo or check permissions."
            )
    
    def close(self):
        """Close the shared memory region"""
        if self._mm:
            self._mm.close()
        if self._fd is not None:
            os.close(self._fd)
    
    def read(self) -> NqptpShm:
        """Read current state from shared memory"""
        if not self._mm:
            raise RuntimeError("Shared memory not open")
        
        self._mm.seek(0)
        data = self._mm.read(72)
        return NqptpShm.from_bytes(data)
    
    def __enter__(self):
        self.open()
        return self
    
    def __exit__(self, *args):
        self.close()


def send_control_message(message: str, host: str = 'localhost', port: int = NQPTP_CONTROL_PORT):
    """Send a control message to NQPTP"""
    import socket
    
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.sendto(message.encode('utf-8'), (host, port))
        print(f"Sent control message: {repr(message)}")
    finally:
        sock.close()


def format_offset_ms(offset_ns: int) -> str:
    """Format offset in milliseconds"""
    return f"{offset_ns / 1e6:+.3f}ms"


def main():
    """Monitor NQPTP shared memory"""
    print("NQPTP Shared Memory Monitor")
    print("=" * 60)
    
    try:
        with NqptpMonitor() as monitor:
            # Check version
            shm = monitor.read()
            if shm.version != NQPTP_SHM_STRUCTURES_VERSION:
                print(f"Warning: Version mismatch. Expected {NQPTP_SHM_STRUCTURES_VERSION}, got {shm.version}")
            else:
                print(f"NQPTP version: {shm.version}")
            
            print("\nMonitoring... (Ctrl+C to stop)\n")
            
            last_clock_id = 0
            last_offset = 0
            
            while True:
                shm = monitor.read()
                
                # Check for atomic read validity
                valid = "✓" if shm.is_valid() else "✗"
                
                # Get values from main (could use secondary if needed)
                clock_id = shm.main.master_clock_id
                local_time = shm.main.local_time
                offset = shm.main.local_to_master_time_offset
                start_time = shm.main.master_clock_start_time
                
                # Detect clock changes
                if clock_id != last_clock_id:
                    if clock_id != 0:
                        print(f"[NEW CLOCK] Master: {shm.main.clock_id_str()}")
                    else:
                        print("[CLOCK LOST] No master clock")
                    last_clock_id = clock_id
                
                if clock_id != 0:
                    # Calculate offset drift
                    offset_drift = offset - last_offset if last_offset != 0 else 0
                    last_offset = offset
                    
                    # Convert to readable format
                    now = time.time_ns()
                    age_ms = (now - local_time) / 1e6 if local_time > 0 else 0
                    
                    print(
                        f"{valid} Clock: {shm.main.clock_id_str()} | "
                        f"Offset: {format_offset_ms(offset)} | "
                        f"Drift: {offset_drift:+d}ns | "
                        f"Sample age: {age_ms:.1f}ms"
                    )
                else:
                    print("(waiting for clock...)")
                
                time.sleep(0.5)
                
    except FileNotFoundError as e:
        print(f"Error: {e}")
        print("\nNQPTP doesn't appear to be running.")
        print("Start NQPTP first, then run this monitor.")
        return 1
    except KeyboardInterrupt:
        print("\nStopped.")
        return 0


def test_control():
    """Test sending control messages to NQPTP"""
    import sys
    
    if len(sys.argv) < 2:
        print("Usage: python nqptp_monitor.py control <command>")
        print("Commands:")
        print("  T <ip>    - Start timing with <ip> as the clock source")
        print("  T         - Stop timing (clear peer list)")
        print("  B         - Signal play beginning")
        print("  E         - Signal play ended")
        print("  P         - Signal play paused")
        return
    
    command = ' '.join(sys.argv[2:])
    send_control_message(command)


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == 'control':
        test_control()
    else:
        sys.exit(main())
