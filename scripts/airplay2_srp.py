#!/usr/bin/env python3
"""
AirPlay 2 Pair-Setup with Correct SRP-6a Implementation

Key findings from shairport-sync source:
- k = H(PAD(N) | PAD(g)) - uses PADDED hashing
- u = H(PAD(A) | PAD(B)) - uses PADDED hashing
- x = H_ns(salt, H(I:P)) - H_ns uses natural byte length of salt
- M = H(H(N) XOR H(g) | H(I) | s | A | B | K) - s, A, B use natural byte lengths

Usage:
    python3 airplay2_srp.py <host> [port]
"""

import socket
import sys
import hashlib
import secrets
from typing import Dict, Tuple

# TLV8 Types (HomeKit Accessory Protocol)
TLV_METHOD = 0x00
TLV_IDENTIFIER = 0x01
TLV_SALT = 0x02
TLV_PUBLIC_KEY = 0x03
TLV_PROOF = 0x04
TLV_ENCRYPTED_DATA = 0x05
TLV_STATE = 0x06
TLV_ERROR = 0x07
TLV_SIGNATURE = 0x0A
TLV_FLAGS = 0x13

METHOD_PAIR_SETUP = 0x00
FLAGS_TRANSIENT = 0x10

# SRP-6a Parameters - 3072-bit group from RFC 5054
N_HEX = (
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B"
    "139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485"
    "B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1F"
    "E649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23"
    "DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32"
    "905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF69558"
    "17183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521"
    "ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D7"
    "1E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B1817"
    "7B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82"
    "D120A93AD2CAFFFFFFFFFFFFFFFF"
)
N = int(N_HEX, 16)
g = 5  # Apple uses g=5
N_BYTES = 384  # 3072 bits = 384 bytes


def sha512(data: bytes) -> bytes:
    return hashlib.sha512(data).digest()


def int_to_bytes(x: int) -> bytes:
    """Convert integer to bytes with natural length (no leading zeros)"""
    if x == 0:
        return b'\x00'
    length = (x.bit_length() + 7) // 8
    return x.to_bytes(length, 'big')


def pad_to_n(x: int) -> bytes:
    """Pad integer to N's byte length (384 bytes for 3072-bit)"""
    return x.to_bytes(N_BYTES, 'big')


def calculate_k() -> int:
    """k = H(PAD(N) | PAD(g)) - RFC 5054"""
    h = hashlib.sha512()
    h.update(pad_to_n(N))
    h.update(pad_to_n(g))
    return int.from_bytes(h.digest(), 'big')


def calculate_x(salt: bytes, identity: bytes, password: bytes) -> int:
    """
    x = H_ns(salt, H(I | ":" | P))
    where H_ns(n, bytes) = H(n_bytes | bytes) using natural byte length of salt
    
    From shairport: salt is stored as bnum, H_ns uses bnum_num_bytes(salt)
    Since salt comes from TLV as raw bytes, we use it directly
    """
    inner_hash = sha512(identity + b":" + password)
    
    # H_ns: concatenate salt bytes with inner hash, then hash
    h = hashlib.sha512()
    h.update(salt)  # Salt uses natural byte length (as received from TLV)
    h.update(inner_hash)
    
    return int.from_bytes(h.digest(), 'big')


def calculate_u(A: int, B: int) -> int:
    """u = H(PAD(A) | PAD(B)) - uses PADDED hashing"""
    h = hashlib.sha512()
    h.update(pad_to_n(A))
    h.update(pad_to_n(B))
    return int.from_bytes(h.digest(), 'big')


def calculate_M1(identity: bytes, salt: bytes, A: int, B: int, K: bytes) -> bytes:
    """
    M = H(H(N) XOR H(g) | H(I) | s | A | B | K)
    
    IMPORTANT: N, g, s, A, B use NATURAL byte lengths (not padded!)
    
    From shairport:
    - hash_num(ng->N, H_N) uses bnum_num_bytes(N)
    - hash_num(ng->g, H_g) uses bnum_num_bytes(g)  <- g=5 is just 1 byte!
    - update_hash_n(s) uses bnum_num_bytes(s)
    - update_hash_n(A) uses bnum_num_bytes(A)
    - update_hash_n(B) uses bnum_num_bytes(B)
    """
    # H(N) - natural byte length (384 bytes for 3072-bit N)
    H_N = sha512(int_to_bytes(N))
    
    # H(g) - natural byte length (1 byte for g=5)
    H_g = sha512(int_to_bytes(g))
    
    # H(N) XOR H(g)
    H_xor = bytes(a ^ b for a, b in zip(H_N, H_g))
    
    # H(I) - identity as string bytes
    H_I = sha512(identity)
    
    # M = H(H_xor | H_I | s | A | B | K)
    h = hashlib.sha512()
    h.update(H_xor)
    h.update(H_I)
    h.update(salt)              # Natural byte length (as received)
    h.update(int_to_bytes(A))   # Natural byte length
    h.update(int_to_bytes(B))   # Natural byte length
    h.update(K)
    
    return h.digest()


def tlv8_encode(items: list) -> bytes:
    """Encode list of (type, value) tuples to TLV8"""
    result = bytearray()
    for tlv_type, value in items:
        if isinstance(value, int):
            value = bytes([value])
        elif isinstance(value, str):
            value = value.encode('utf-8')
        
        offset = 0
        while offset < len(value):
            chunk = value[offset:offset + 255]
            result.append(tlv_type)
            result.append(len(chunk))
            result.extend(chunk)
            offset += 255
        
        if len(value) == 0:
            result.append(tlv_type)
            result.append(0)
    
    return bytes(result)


def tlv8_decode(data: bytes) -> Dict[int, bytes]:
    """Decode TLV8 data, concatenating fragments"""
    result = {}
    offset = 0
    
    while offset < len(data):
        if offset + 2 > len(data):
            break
        tlv_type = data[offset]
        length = data[offset + 1]
        offset += 2
        
        if offset + length > len(data):
            break
        value = data[offset:offset + length]
        offset += length
        
        if tlv_type in result:
            result[tlv_type] += value
        else:
            result[tlv_type] = value
    
    return result


class SRP6aClient:
    """SRP-6a client for AirPlay 2 pair-setup"""
    
    def __init__(self, identity: bytes, password: bytes):
        self.identity = identity
        self.password = password
        self.a = None  # Private key
        self.A = None  # Public key
        self.B = None  # Server public key
        self.salt = None
        self.S = None  # Shared secret
        self.K = None  # Session key
        self.M1 = None  # Client proof
        
        # Pre-calculate k
        self.k = calculate_k()
        print(f"k = {hex(self.k)[:40]}...")
        
    def generate_client_credentials(self) -> int:
        """Generate client private key a and public key A"""
        self.a = secrets.randbits(256)
        self.A = pow(g, self.a, N)
        print(f"a = {hex(self.a)[:40]}...")
        print(f"A = {len(int_to_bytes(self.A))} bytes")
        return self.A
    
    def process_challenge(self, salt: bytes, B: int) -> Tuple[bytes, bytes]:
        """Process server challenge and generate proof M1"""
        self.salt = salt
        self.B = B
        
        if B == 0 or B % N == 0:
            raise ValueError("Invalid server public key B")
        
        if self.A is None:
            self.generate_client_credentials()
        
        # u = H(PAD(A) | PAD(B))
        u = calculate_u(self.A, self.B)
        print(f"u = {hex(u)[:40]}...")
        
        # x = H_ns(salt, H(I:P))
        x = calculate_x(salt, self.identity, self.password)
        print(f"x = {hex(x)[:40]}...")
        
        # S = (B - k * g^x) ^ (a + u * x) mod N
        gx = pow(g, x, N)
        kgx = (self.k * gx) % N
        base = (B - kgx) % N
        exp = (self.a + u * x)
        self.S = pow(base, exp, N)
        print(f"S = {hex(self.S)[:40]}...")
        
        # K = H(S) - session key, using natural byte length
        self.K = sha512(int_to_bytes(self.S))
        print(f"K = {self.K.hex()[:32]}...")
        
        # M1 = client proof
        self.M1 = calculate_M1(self.identity, self.salt, self.A, self.B, self.K)
        print(f"M1 = {self.M1.hex()[:32]}...")
        
        return int_to_bytes(self.A), self.M1


class AirPlay2Client:
    def __init__(self, host: str, port: int = 7000):
        self.host = host
        self.port = port
        self.sock = None
        self.cseq = 0
        
    def connect(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.settimeout(10)
        self.sock.connect((self.host, self.port))
        print(f"Connected to {self.host}:{self.port}")
        
    def disconnect(self):
        if self.sock:
            self.sock.close()
            
    def send_rtsp(self, method: str, path: str, body: bytes = b"",
                  content_type: str = "application/octet-stream") -> Tuple[int, Dict[str, str], bytes]:
        """Send RTSP/1.0 request"""
        self.cseq += 1
        
        headers = [
            f"{method} {path} RTSP/1.0",
            f"CSeq: {self.cseq}",
            f"Host: {self.host}:{self.port}",
            f"Content-Type: {content_type}",
            f"Content-Length: {len(body)}",
            "",
            ""
        ]
        
        request = "\r\n".join(headers).encode('utf-8') + body
        
        print(f"\n>>> {method} {path}")
        print(f"    Body ({len(body)} bytes): {body[:60].hex()}{'...' if len(body) > 60 else ''}")
        
        self.sock.sendall(request)
        
        # Read response
        response = b""
        while True:
            chunk = self.sock.recv(4096)
            if not chunk:
                break
            response += chunk
            
            if b"\r\n\r\n" in response:
                header_end = response.index(b"\r\n\r\n")
                header_part = response[:header_end].decode('utf-8')
                
                content_length = 0
                for line in header_part.split("\r\n"):
                    if line.lower().startswith("content-length:"):
                        content_length = int(line.split(":")[1].strip())
                        break
                
                body_start = header_end + 4
                if len(response) >= body_start + content_length:
                    break
        
        header_end = response.index(b"\r\n\r\n")
        header_lines = response[:header_end].decode('utf-8').split("\r\n")
        
        status_code = int(header_lines[0].split()[1])
        
        resp_headers = {}
        for line in header_lines[1:]:
            if ":" in line:
                key, value = line.split(":", 1)
                resp_headers[key.strip()] = value.strip()
        
        resp_body = response[header_end + 4:]
        
        print(f"<<< {status_code}, Body: {len(resp_body)} bytes")
        
        return status_code, resp_headers, resp_body


def pair_setup(client: AirPlay2Client, transient: bool = True):
    """Complete pair-setup flow with SRP-6a"""
    pin = "3939" if transient else "0000"
    identity = b"Pair-Setup"  # From shairport: #define USERNAME "Pair-Setup"
    password = pin.encode('utf-8')
    
    print(f"\n{'='*60}")
    print(f"SRP-6a Pair-Setup (transient={transient}, pin={pin})")
    print(f"Identity: {identity.decode()}")
    print(f"{'='*60}")
    
    srp = SRP6aClient(identity, password)
    
    # ========== M1: Start Pair-Setup ==========
    print(f"\n{'='*60}")
    print("M1: Start Pair-Setup")
    print(f"{'='*60}")
    
    m1_items = [
        (TLV_STATE, bytes([0x01])),
        (TLV_METHOD, bytes([METHOD_PAIR_SETUP])),
    ]
    if transient:
        m1_items.append((TLV_FLAGS, bytes([FLAGS_TRANSIENT])))
    
    m1_data = tlv8_encode(m1_items)
    print(f"M1 TLV8: {m1_data.hex()}")
    
    status, headers, body = client.send_rtsp("POST", "/pair-setup", m1_data)
    
    if status != 200:
        print(f"M1 failed: {status}")
        return False
    
    # Parse M2 response
    m2 = tlv8_decode(body)
    print(f"M2 TLV types: {list(hex(t) for t in m2.keys())}")
    
    if TLV_ERROR in m2:
        print(f"M2 ERROR: {m2[TLV_ERROR].hex()}")
        return False
    
    state = m2.get(TLV_STATE, b'')[0] if TLV_STATE in m2 else 0
    if state != 0x02:
        print(f"Expected state 0x02, got {hex(state)}")
        return False
    
    salt = m2.get(TLV_SALT, b'')
    B_bytes = m2.get(TLV_PUBLIC_KEY, b'')
    
    print(f"Salt ({len(salt)} bytes): {salt.hex()}")
    print(f"B ({len(B_bytes)} bytes)")
    
    B = int.from_bytes(B_bytes, 'big')
    
    # ========== M3: Client Proof ==========
    print(f"\n{'='*60}")
    print("M3: Generate and send client proof")
    print(f"{'='*60}")
    
    A_bytes, M1_proof = srp.process_challenge(salt, B)
    
    print(f"Sending A ({len(A_bytes)} bytes)")
    print(f"Sending M1 ({len(M1_proof)} bytes)")
    
    m3_items = [
        (TLV_STATE, bytes([0x03])),
        (TLV_PUBLIC_KEY, A_bytes),
        (TLV_PROOF, M1_proof),
    ]
    m3_data = tlv8_encode(m3_items)
    
    status, headers, body = client.send_rtsp("POST", "/pair-setup", m3_data)
    
    if status != 200:
        print(f"M3 failed: {status}")
        return False
    
    # Parse M4 response
    m4 = tlv8_decode(body)
    print(f"M4 TLV types: {list(hex(t) for t in m4.keys())}")
    
    if TLV_ERROR in m4:
        error_code = m4[TLV_ERROR][0] if m4[TLV_ERROR] else 0
        error_names = {
            0x01: "Unknown",
            0x02: "Authentication",
            0x03: "Backoff",
            0x04: "MaxPeers",
            0x05: "MaxTries",
            0x06: "Unavailable",
            0x07: "Busy",
        }
        print(f"M4 ERROR: 0x{error_code:02x} ({error_names.get(error_code, 'Unknown')})")
        return False
    
    state = m4.get(TLV_STATE, b'')[0] if TLV_STATE in m4 else 0
    if state != 0x04:
        print(f"Expected state 0x04, got {hex(state)}")
        return False
    
    M2_proof = m4.get(TLV_PROOF, b'')
    print(f"M2 proof ({len(M2_proof)} bytes): {M2_proof.hex()[:32]}...")
    
    print("\n✓ SRP authentication successful!")
    
    # For transient pairing, we're done (no M5/M6 key exchange needed)
    if transient:
        print("✓ Transient pairing complete - ready to stream!")
        return True
    
    # Non-transient would continue with M5/M6...
    return True


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 airplay2_srp.py <host> [port]")
        sys.exit(1)
    
    host = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 7000
    
    client = AirPlay2Client(host, port)
    
    try:
        client.connect()
        success = pair_setup(client, transient=True)
        print(f"\nResult: {'SUCCESS' if success else 'FAILED'}")
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
    finally:
        client.disconnect()


if __name__ == "__main__":
    main()
