#!/usr/bin/env python3
"""Read NQPTP shared memory to check clock offset and timing info."""

import mmap
import struct
import os

# NQPTP shared memory structure (from nqptp-shm-structures.h)
# struct shm_structure {
#     uint16_t version;  // offset 0
#     // shm_structure_set main:
#     uint64_t master_clock_id;             // offset 2
#     uint64_t local_time;                  // offset 10
#     uint64_t local_to_master_time_offset; // offset 18
#     uint64_t master_clock_start_time;     // offset 26
#     // shm_structure_set secondary:
#     uint64_t master_clock_id_2;           // offset 34
#     uint64_t local_time_2;                // offset 42
#     uint64_t local_to_master_time_offset_2; // offset 50
#     uint64_t master_clock_start_time_2;   // offset 58
# };
# Total size: 66 bytes

NQPTP_SHM_NAME = "/nqptp"
SHM_SIZE = 66

def read_nqptp_shm():
    try:
        fd = os.open(f"/dev/shm{NQPTP_SHM_NAME}", os.O_RDONLY)
        mm = mmap.mmap(fd, SHM_SIZE, mmap.MAP_SHARED, mmap.PROT_READ)
        
        # Read version
        version = struct.unpack('<H', mm[0:2])[0]
        
        # Read main set
        master_clock_id = struct.unpack('<Q', mm[2:10])[0]
        local_time = struct.unpack('<Q', mm[10:18])[0]
        offset = struct.unpack('<q', mm[18:26])[0]  # signed!
        start_time = struct.unpack('<Q', mm[26:34])[0]
        
        # Read secondary set (should match main for valid read)
        master_clock_id_2 = struct.unpack('<Q', mm[34:42])[0]
        local_time_2 = struct.unpack('<Q', mm[42:50])[0]
        offset_2 = struct.unpack('<q', mm[50:58])[0]
        start_time_2 = struct.unpack('<Q', mm[58:66])[0]
        
        mm.close()
        os.close(fd)
        
        print(f"NQPTP Shared Memory (version {version}):")
        print(f"  master_clock_id:             0x{master_clock_id:016x}")
        print(f"  local_time:                  {local_time} ns ({local_time / 1e9:.3f} sec)")
        print(f"  local_to_master_time_offset: {offset} ns ({offset / 1e9:.3f} sec)")
        print(f"  master_clock_start_time:     {start_time} ns ({start_time / 1e9:.3f} sec)")
        print()
        
        if master_clock_id == master_clock_id_2:
            print("  [Valid read - main and secondary match]")
        else:
            print("  [WARNING: main and secondary don't match - read may be inconsistent]")
        
        # Calculate what master time would be now
        import time
        now_ns = time.time_ns()
        if local_time > 0:
            time_since_sample = now_ns - local_time
            print(f"\n  Time since last sample: {time_since_sample / 1e9:.3f} sec")
            
            master_now = now_ns + offset
            print(f"  Calculated master time now: {master_now} ns")
            print(f"  vs actual Unix time.time_ns(): {now_ns} ns")
            print(f"  Difference: {(master_now - now_ns) / 1e9:.3f} sec")
        
        return {
            'version': version,
            'clock_id': master_clock_id,
            'local_time': local_time,
            'offset': offset,
            'start_time': start_time
        }
        
    except FileNotFoundError:
        print("NQPTP shared memory not found. Is NQPTP running?")
        return None
    except Exception as e:
        print(f"Error reading NQPTP shared memory: {e}")
        return None

if __name__ == "__main__":
    read_nqptp_shm()
