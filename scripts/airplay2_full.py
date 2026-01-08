#!/usr/bin/env python3
"""
AirPlay 2 Full Pair-Setup Implementation

This script implements the complete SRP-6a pair-setup flow for AirPlay 2.
Uses RTSP/1.0 protocol (NOT HTTP!) on port 7000.

Usage:
    python3 airplay2_full.py <host> [port]
"""

import socket
import sys
import hashlib
import os
import secrets
from typing import Dict, Tuple, Optional

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

# SRP-6a Parameters (Apple uses g=5, NOT g=2!)
# 3072-bit group (since B is 384 bytes = 3072 bits)
# From RFC 5054
N_HEX = (
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
    "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
    "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
    "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
    "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
    "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33"
    "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7"
    "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864"
    "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2"
    "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"
)
N = int(N_HEX, 16)
g = 5  # Apple uses g=5!
k = None  # Will be calculated
N_BYTES = 384  # 3072 bits = 384 bytes


def sha512(data: bytes) -> bytes:
    return hashlib.sha512(data).digest()


def sha512_int(*args) -> int:
    """Hash integers/bytes and return as integer"""
    h = hashlib.sha512()
    for arg in args:
        if isinstance(arg, int):
            # Convert to bytes, removing leading zero if present
            b = arg.to_bytes((arg.bit_length() + 7) // 8, 'big')
            h.update(b)
        else:
            h.update(arg)
    return int.from_bytes(h.digest(), 'big')


def pad_to_n(x: int) -> bytes:
    """Pad integer to N's byte length (384 bytes for 3072-bit)"""
    return x.to_bytes(N_BYTES, 'big')


def calculate_k() -> int:
    """k = H(N | PAD(g))"""
    return sha512_int(pad_to_n(N), pad_to_n(g))


def calculate_x(salt: bytes, identity: bytes, password: bytes) -> int:
    """x = H(salt | H(identity | ":" | password))"""
    inner = sha512(identity + b":" + password)
    return sha512_int(salt, inner)


def calculate_u(A: int, B: int) -> int:
    """u = H(PAD(A) | PAD(B))"""
    return sha512_int(pad_to_n(A), pad_to_n(B))


def calculate_M1(A: int, B: int, S: int, identity: bytes, salt: bytes) -> bytes:
    """
    M1 = H(H(N) XOR H(g) | H(I) | s | A | B | K)
    Where K = H(S)
    """
    # H(N) XOR H(g)
    h_n = sha512(pad_to_n(N))
    h_g = sha512(pad_to_n(g))
    h_xor = bytes(a ^ b for a, b in zip(h_n, h_g))
    
    # H(I)
    h_i = sha512(identity)
    
    # K = H(S)
    K = sha512(pad_to_n(S))
    
    # M1 = H(h_xor | h_i | salt | PAD(A) | PAD(B) | K)
    h = hashlib.sha512()
    h.update(h_xor)
    h.update(h_i)
    h.update(salt)
    h.update(pad_to_n(A))
    h.update(pad_to_n(B))
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
    """Decode TLV8 data"""
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
        
        # Concatenate fragments
        if tlv_type in result:
            result[tlv_type] += value
        else:
            result[tlv_type] = value
    
    return result


class SRP6aClient:
    """SRP-6a client implementation for AirPlay 2"""
    
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
        
        global k
        k = calculate_k()
        print(f"SRP k = {k:064x}...")
        
    def generate_client_credentials(self) -> int:
        """Generate client private key a and public key A"""
        # a is random 256-bit number
        self.a = secrets.randbits(256)
        
        # A = g^a mod N
        self.A = pow(g, self.a, N)
        
        print(f"Generated A ({len(pad_to_n(self.A))} bytes)")
        return self.A
    
    def process_challenge(self, salt: bytes, B: int) -> Tuple[bytes, bytes]:
        """
        Process server challenge (salt, B) and generate proof M1
        Returns (A_bytes, M1)
        """
        self.salt = salt
        self.B = B
        
        # Check B != 0 and B % N != 0
        if B == 0 or B % N == 0:
            raise ValueError("Invalid server public key B")
        
        # Generate A if not done
        if self.A is None:
            self.generate_client_credentials()
        
        # u = H(PAD(A) | PAD(B))
        u = calculate_u(self.A, self.B)
        if u == 0:
            raise ValueError("Invalid u value")
        
        print(f"u = {u:064x}...")
        
        # x = H(salt | H(I | ":" | P))
        x = calculate_x(salt, self.identity, self.password)
        print(f"x = {x:064x}...")
        
        # S = (B - k * g^x) ^ (a + u * x) mod N
        # Need to handle negative numbers in modular arithmetic
        kgx = (k * pow(g, x, N)) % N
        base = (B - kgx) % N
        exp = (self.a + u * x)
        self.S = pow(base, exp, N)
        
        print(f"S = {self.S:064x}...")
        
        # K = H(S) - session key
        self.K = sha512(pad_to_n(self.S))
        print(f"K = {self.K.hex()[:32]}...")
        
        # M1 = client proof
        self.M1 = calculate_M1(self.A, self.B, self.S, self.identity, salt)
        print(f"M1 = {self.M1.hex()[:32]}...")
        
        return pad_to_n(self.A), self.M1


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
        """Send RTSP request"""
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
        print(f"    Body ({len(body)} bytes): {body.hex()}")
        
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
    """
    Complete pair-setup flow with SRP-6a
    """
    pin = "3939" if transient else "0000"
    identity = b"Pair-Setup"
    password = pin.encode('utf-8')
    
    srp = SRP6aClient(identity, password)
    
    # ========== M1: Start Pair-Setup ==========
    print("\n" + "="*60)
    print("M1: Start Pair-Setup")
    print("="*60)
    
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
        if body:
            decoded = tlv8_decode(body)
            if TLV_ERROR in decoded:
                print(f"Error: {decoded[TLV_ERROR].hex()}")
        return False
    
    # Parse M2
    m2 = tlv8_decode(body)
    print(f"\nM2 received:")
    for k, v in m2.items():
        name = {0x02: "SALT", 0x03: "PUBLIC_KEY", 0x06: "STATE", 0x07: "ERROR"}.get(k, f"0x{k:02X}")
        if len(v) > 32:
            print(f"  {name}: {len(v)} bytes")
        else:
            print(f"  {name}: {v.hex()}")
    
    if TLV_ERROR in m2:
        print(f"Server error: {m2[TLV_ERROR].hex()}")
        return False
    
    salt = m2.get(TLV_SALT)
    B_bytes = m2.get(TLV_PUBLIC_KEY)
    
    if not salt or not B_bytes:
        print("Missing salt or public key in M2")
        return False
    
    B = int.from_bytes(B_bytes, 'big')
    print(f"\nSalt: {salt.hex()}")
    print(f"B: {len(B_bytes)} bytes")
    
    # ========== M3: Client Proof ==========
    print("\n" + "="*60)
    print("M3: Client Proof")
    print("="*60)
    
    A_bytes, M1_proof = srp.process_challenge(salt, B)
    
    # M3: State=0x03, PublicKey(A), Proof(M1)
    m3_items = [
        (TLV_STATE, bytes([0x03])),
        (TLV_PUBLIC_KEY, A_bytes),
        (TLV_PROOF, M1_proof),
    ]
    m3_data = tlv8_encode(m3_items)
    
    print(f"M3 TLV8: {len(m3_data)} bytes")
    print(f"  A: {len(A_bytes)} bytes")
    print(f"  M1 proof: {M1_proof.hex()[:32]}...")
    
    status, headers, body = client.send_rtsp("POST", "/pair-setup", m3_data)
    
    if status != 200:
        print(f"M3 failed: {status}")
        if body:
            decoded = tlv8_decode(body)
            print(f"Response: {decoded}")
            if TLV_ERROR in decoded:
                err = decoded[TLV_ERROR][0] if decoded[TLV_ERROR] else 0
                errors = {
                    0x01: "Unknown",
                    0x02: "Authentication (wrong PIN?)",
                    0x03: "Backoff",
                    0x04: "MaxPeers",
                    0x05: "MaxTries",
                    0x06: "Unavailable",
                    0x07: "Busy"
                }
                print(f"Error: {errors.get(err, f'0x{err:02X}')}")
        return False
    
    # Parse M4
    m4 = tlv8_decode(body)
    print(f"\nM4 received:")
    for k, v in m4.items():
        name = {0x04: "PROOF", 0x06: "STATE", 0x07: "ERROR"}.get(k, f"0x{k:02X}")
        print(f"  {name}: {v.hex()[:32]}...")
    
    if TLV_ERROR in m4:
        print(f"Server error in M4: {m4[TLV_ERROR].hex()}")
        return False
    
    M2_proof = m4.get(TLV_PROOF)
    if M2_proof:
        print(f"\n✓ Pair-Setup M3->M4 SUCCESS!")
        print(f"  Server proof M2: {M2_proof.hex()[:32]}...")
        
        # For transient pairing, we're done at M4
        # For normal pairing, M5/M6 exchange Ed25519 keys
        if transient:
            print("\n✓ Transient pairing complete!")
            print(f"  Session key K: {srp.K.hex()[:32]}...")
            return True
    
    return True


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 airplay2_full.py <host> [port]")
        sys.exit(1)
    
    host = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 7000
    
    print("="*60)
    print("AirPlay 2 Full Pair-Setup")
    print(f"Target: {host}:{port}")
    print("="*60)
    
    client = AirPlay2Client(host, port)
    
    try:
        client.connect()
        success = pair_setup(client, transient=True)
        
        if success:
            print("\n" + "="*60)
            print("PAIRING SUCCESSFUL!")
            print("="*60)
        else:
            print("\n" + "="*60)
            print("PAIRING FAILED")
            print("="*60)
            
    except Exception as e:
        print(f"\nError: {e}")
        import traceback
        traceback.print_exc()
    finally:
        client.disconnect()
