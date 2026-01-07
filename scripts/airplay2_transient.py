#!/usr/bin/env python3
"""
AirPlay 2 Transient Pairing - Complete Implementation

Based on pyatv (https://github.com/postlund/pyatv) and 
openairplay/airplay2-receiver implementations.

KEY INSIGHT: For transient pairing, NO pair-verify is needed!
Flow: /pair-pin-start -> M1-M4 pair-setup -> derive Control keys -> SETUP -> stream

Transient pairing only covers M1-M4. After M4, the SRP session key (K)
is used directly to derive encryption keys via HKDF-SHA512:
  - Control-Salt + Control-Write-Encryption-Key -> output key
  - Control-Salt + Control-Read-Encryption-Key -> input key

PTP TIMING (for shairport-sync AirPlay 2):
  - NQPTP is PASSIVE - it monitors PTP messages from the client IP on ports 319/320
  - We act as PTP master clock, sending Announce/Sync/Follow_Up messages
  - Shairport-sync tells NQPTP which IP to listen to via "T <ip>" command on port 9000
  - NQPTP updates shared memory with timing info that shairport-sync reads

Usage:
    sudo python3 airplay2_transient.py <host> [port]
    
NOTE: Requires root to bind to PTP ports 319/320 for proper timing.
      Without root, PTP messages are sent from ephemeral ports which NQPTP ignores.
"""

import socket
import struct
import sys
import hashlib
import secrets
import select
import time
import random
from typing import Dict, Tuple, Any, Optional
from enum import IntEnum

# Cryptography imports
try:
    from cryptography.hazmat.backends import default_backend
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
    HAS_CRYPTO = True
except ImportError:
    HAS_CRYPTO = False
    print("WARNING: cryptography library not found. Key derivation disabled.")


# ============================================================================
# TLV8 Encoding/Decoding
# ============================================================================

class TlvValue(IntEnum):
    """HAP TLV types."""
    Method = 0x00
    Identifier = 0x01
    Salt = 0x02
    PublicKey = 0x03
    Proof = 0x04
    EncryptedData = 0x05
    SeqNo = 0x06
    Error = 0x07
    Signature = 0x0A
    Flags = 0x13


class Flags(IntEnum):
    TransientPairing = 0x10


def tlv8_encode(items: Dict[int, bytes]) -> bytes:
    """Encode dict to TLV8 format."""
    result = bytearray()
    for tlv_type, value in items.items():
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
    """Decode TLV8 format to dict."""
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


# ============================================================================
# SRP-6a Implementation (AirPlay-specific with g=5)
# ============================================================================

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
    """
    inner_hash = sha512(identity + b":" + password)
    h = hashlib.sha512()
    h.update(salt)
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
    N, g, s, A, B use NATURAL byte lengths (not padded!)
    """
    H_N = sha512(int_to_bytes(N))
    H_g = sha512(int_to_bytes(g))
    H_xor = bytes(a ^ b for a, b in zip(H_N, H_g))
    H_I = sha512(identity)
    
    h = hashlib.sha512()
    h.update(H_xor)
    h.update(H_I)
    h.update(salt)
    h.update(int_to_bytes(A))
    h.update(int_to_bytes(B))
    h.update(K)
    return h.digest()


def calculate_M2(A: int, M1: bytes, K: bytes) -> bytes:
    """Server proof: M2 = H(A | M1 | K) - A uses natural byte length"""
    h = hashlib.sha512()
    h.update(int_to_bytes(A))
    h.update(M1)
    h.update(K)
    return h.digest()


class SRP6aClient:
    """SRP-6a client for AirPlay 2 pair-setup"""
    
    def __init__(self, identity: bytes, password: bytes):
        self.identity = identity
        self.password = password
        self.a = None
        self.A = None
        self.B = None
        self.salt = None
        self.S = None
        self.K = None
        self.M1 = None
        self.k = calculate_k()
        
    def generate_client_credentials(self) -> int:
        """Generate client private key a and public key A"""
        self.a = secrets.randbits(256)
        self.A = pow(g, self.a, N)
        return self.A
    
    def process_challenge(self, salt: bytes, B: int) -> Tuple[bytes, bytes]:
        """Process server challenge and generate proof M1"""
        self.salt = salt
        self.B = B
        
        if B == 0 or B % N == 0:
            raise ValueError("Invalid server public key B")
        
        if self.A is None:
            self.generate_client_credentials()
        
        u = calculate_u(self.A, self.B)
        x = calculate_x(salt, self.identity, self.password)
        
        # S = (B - k * g^x) ^ (a + u * x) mod N
        gx = pow(g, x, N)
        kgx = (self.k * gx) % N
        base = (self.B - kgx) % N
        exp = (self.a + u * x)
        self.S = pow(base, exp, N)
        
        # K = H(S) - session key
        self.K = sha512(int_to_bytes(self.S))
        
        # M1 = client proof
        self.M1 = calculate_M1(self.identity, self.salt, self.A, self.B, self.K)
        
        return int_to_bytes(self.A), self.M1
    
    def verify_server_proof(self, M2: bytes) -> bool:
        """Verify server proof M2"""
        expected_M2 = calculate_M2(self.A, self.M1, self.K)
        return M2 == expected_M2


# ============================================================================
# HKDF Key Derivation
# ============================================================================

def hkdf_expand(salt: str, info: str, shared_secret: bytes) -> bytes:
    """Derive encryption keys from shared secret using HKDF-SHA512."""
    if not HAS_CRYPTO:
        raise RuntimeError("cryptography library required for HKDF")
    
    hkdf = HKDF(
        algorithm=hashes.SHA512(),
        length=32,
        salt=salt.encode(),
        info=info.encode(),
        backend=default_backend(),
    )
    return hkdf.derive(shared_secret)


# ============================================================================
# ChaCha20-Poly1305 Encryption
# ============================================================================

class Chacha20Cipher:
    """ChaCha20-Poly1305 encryption with counter-based nonces."""
    
    def __init__(self, out_key: bytes, in_key: bytes, nonce_length: int = 8):
        if not HAS_CRYPTO:
            raise RuntimeError("cryptography library required for encryption")
        
        self._enc_out = ChaCha20Poly1305(out_key)
        self._enc_in = ChaCha20Poly1305(in_key)
        self._out_counter = 0
        self._in_counter = 0
        self._nonce_length = nonce_length
    
    def _pad_nonce(self, nonce: bytes) -> bytes:
        """Pad nonce to 12 bytes."""
        return b'\x00' * (12 - len(nonce)) + nonce
    
    def encrypt(self, data: bytes, nonce: Optional[bytes] = None, aad: Optional[bytes] = None) -> bytes:
        """Encrypt data with counter or specified nonce."""
        if nonce is None:
            nonce = self._out_counter.to_bytes(length=self._nonce_length, byteorder="little")
            self._out_counter += 1
        
        if len(nonce) != 12:
            nonce = self._pad_nonce(nonce)
        
        return self._enc_out.encrypt(nonce, data, aad)
    
    def decrypt(self, data: bytes, nonce: Optional[bytes] = None, aad: Optional[bytes] = None) -> bytes:
        """Decrypt data with counter or specified nonce."""
        if nonce is None:
            nonce = self._in_counter.to_bytes(length=self._nonce_length, byteorder="little")
            self._in_counter += 1
        
        if len(nonce) != 12:
            nonce = self._pad_nonce(nonce)
        
        return self._enc_in.decrypt(nonce, data, aad)


# ============================================================================
# HAP Session Encryption (1024-byte frames)
# ============================================================================

class HAPSession:
    """Manages cryptography for a HAP session according to HAP specification.
    
    The HAP specification mandates that data is encrypted/decrypted in blocks
    of 1024 bytes. This class takes care of that. It is designed to be
    transparent until encryption is enabled.
    """
    
    FRAME_LENGTH = 1024
    AUTH_TAG_LENGTH = 16
    
    def __init__(self):
        self._encrypted_data = b""
        self.cipher: Optional[Chacha20Cipher] = None
    
    @property
    def is_enabled(self) -> bool:
        """Return whether encryption is enabled."""
        return self.cipher is not None
    
    def enable(self, output_key: bytes, input_key: bytes):
        """Enable encryption with specified keys."""
        self.cipher = Chacha20Cipher(output_key, input_key)
    
    def encrypt(self, data: bytes) -> bytes:
        """Encrypt outgoing data in 1024-byte frames."""
        if self.cipher is None:
            return data
        
        output = b""
        while data:
            frame = data[:self.FRAME_LENGTH]
            data = data[self.FRAME_LENGTH:]
            
            # Length prefix (2 bytes, little-endian)
            length = len(frame).to_bytes(2, byteorder="little")
            # Encrypt frame with length as AAD
            encrypted = self.cipher.encrypt(frame, aad=length)
            output += length + encrypted
        
        return output
    
    def decrypt(self, data: bytes) -> bytes:
        """Decrypt incoming data from 1024-byte frames."""
        if self.cipher is None:
            return data
        
        self._encrypted_data += data
        
        output = b""
        while self._encrypted_data:
            if len(self._encrypted_data) < 2:
                break
            
            # Get length from first 2 bytes
            length_bytes = self._encrypted_data[:2]
            block_length = int.from_bytes(length_bytes, byteorder="little") + self.AUTH_TAG_LENGTH
            
            if len(self._encrypted_data) < block_length + 2:
                break
            
            # Extract and decrypt block
            block = self._encrypted_data[2:2 + block_length]
            output += self.cipher.decrypt(block, aad=length_bytes)
            
            self._encrypted_data = self._encrypted_data[2 + block_length:]
        
        return output


# ============================================================================
# AirPlay 2 RTSP Client
# ============================================================================

class AirPlay2Client:
    """AirPlay 2 RTSP client for transient pairing."""
    
    TRANSIENT_PIN = "3939"
    IDENTITY = "Pair-Setup"
    
    AIRPLAY_HEADERS = {
        "User-Agent": "AirPlay/320.20",
        "Connection": "keep-alive",
        "X-Apple-HKP": "4",
        "Content-Type": "application/octet-stream",
    }
    
    def __init__(self, host: str, port: int = 7000):
        self.host = host
        self.port = port
        self.sock = None
        self.cseq = 0
        
        # SRP state
        self.srp = None
        
        # Encryption keys
        self.output_key = None
        self.input_key = None
        self.cipher = None
        
        # HAP Session for encrypted communication
        self.hap_session: Optional[HAPSession] = None
        
        # Session state
        self.session_uuid = None
        self.audio_shared_secret = None
        self.audio_control_port = 0
        self.audio_data_port = 0
        
    def connect(self):
        """Establish TCP connection."""
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        # Enable TCP keepalive
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        self.sock.settimeout(10)
        self.sock.connect((self.host, self.port))
        print(f"Connected to {self.host}:{self.port}")
        
    def disconnect(self):
        """Close connection."""
        # Stop PTP master clock if running
        if hasattr(self, '_ptp_running') and self._ptp_running:
            self._stop_ptp_master_clock()
        
        if self.sock:
            self.sock.close()
    
    def enable_encryption(self):
        """Enable HAP session encryption using derived Control keys."""
        if self.output_key is None or self.input_key is None:
            raise RuntimeError("Keys not derived yet")
        
        self.hap_session = HAPSession()
        self.hap_session.enable(self.output_key, self.input_key)
        print("HAP session encryption enabled")
            
    def send_rtsp(self, method: str, path: str, body: bytes = b"",
                  content_type: str = "application/octet-stream",
                  extra_headers: Dict[str, str] = None) -> Tuple[int, Dict[str, str], bytes]:
        """Send RTSP request and get response."""
        self.cseq += 1
        
        headers = {
            "CSeq": str(self.cseq),
            "Host": f"{self.host}:{self.port}",
            **self.AIRPLAY_HEADERS,
            "Content-Type": content_type,
            "Content-Length": str(len(body)),
        }
        
        if extra_headers:
            headers.update(extra_headers)
        
        request_line = f"{method} {path} RTSP/1.0\r\n"
        header_lines = "".join(f"{k}: {v}\r\n" for k, v in headers.items())
        request = (request_line + header_lines + "\r\n").encode('utf-8') + body
        
        print(f"\n>>> {method} {path}")
        if body:
            print(f"    Body ({len(body)} bytes): {body[:40].hex()}{'...' if len(body) > 40 else ''}")
        
        # Encrypt request if HAP session is enabled
        if self.hap_session and self.hap_session.is_enabled:
            request = self.hap_session.encrypt(request)
            print(f"    Encrypted: {len(request)} bytes")
        
        self.sock.sendall(request)
        
        # Read response
        response = b""
        while True:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise Exception("Connection closed")
            response += chunk
            
            # If HAP encryption is enabled, we need to decrypt to check content length
            if self.hap_session and self.hap_session.is_enabled:
                try:
                    decrypted = self.hap_session.decrypt(response)
                    if b"\r\n\r\n" in decrypted:
                        header_end = decrypted.index(b"\r\n\r\n")
                        header_part = decrypted[:header_end].decode('utf-8')
                        
                        content_length = 0
                        for line in header_part.split("\r\n"):
                            if line.lower().startswith("content-length:"):
                                content_length = int(line.split(":")[1].strip())
                                break
                        
                        body_start = header_end + 4
                        if len(decrypted) >= body_start + content_length:
                            response = decrypted
                            break
                except Exception:
                    # Not enough data yet, keep reading
                    continue
            else:
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
    
    def pair_pin_start(self):
        """Start pairing."""
        print(f"\n{'='*60}")
        print("Step 1: POST /pair-pin-start")
        print(f"{'='*60}")
        
        status, headers, body = self.send_rtsp(
            "POST", "/pair-pin-start",
            content_type="application/x-apple-binary-plist"
        )
        
        if status == 200:
            print("pair-pin-start OK")
            return True
        else:
            print(f"pair-pin-start failed: {status}")
            return False
    
    def pair_setup_m1(self) -> Tuple[bytes, bytes]:
        """M1: Send transient flag, receive salt and server public key."""
        print(f"\n{'='*60}")
        print("Step 2: Pair-Setup M1 -> M2")
        print(f"{'='*60}")
        
        m1_data = tlv8_encode({
            TlvValue.Method: bytes([0x00]),
            TlvValue.SeqNo: bytes([0x01]),
            TlvValue.Flags: bytes([Flags.TransientPairing]),
        })
        
        print(f"M1 TLV: Method=0x00, SeqNo=0x01, Flags=0x10 (Transient)")
        
        status, headers, body = self.send_rtsp("POST", "/pair-setup", m1_data)
        
        if status != 200:
            raise Exception(f"M1 failed: {status}")
        
        m2 = tlv8_decode(body)
        
        if TlvValue.Error in m2:
            error = m2[TlvValue.Error][0] if m2[TlvValue.Error] else 0
            raise Exception(f"M2 error: 0x{error:02x}")
        
        salt = m2.get(TlvValue.Salt, b'')
        server_pk = m2.get(TlvValue.PublicKey, b'')
        
        print(f"M2: Salt={len(salt)} bytes, ServerPK={len(server_pk)} bytes")
        
        return salt, server_pk
    
    def pair_setup_m3(self, salt: bytes, server_pk: bytes) -> bool:
        """M3: Send client public key and proof."""
        print(f"\n{'='*60}")
        print("Step 3: Pair-Setup M3 -> M4 (SRP Exchange)")
        print(f"{'='*60}")
        
        # Create SRP client
        self.srp = SRP6aClient(
            identity=self.IDENTITY.encode('utf-8'),
            password=self.TRANSIENT_PIN.encode('utf-8')
        )
        
        # Generate credentials
        self.srp.generate_client_credentials()
        
        # Process server challenge
        B = int.from_bytes(server_pk, 'big')
        client_pk, client_proof = self.srp.process_challenge(salt, B)
        
        print(f"Client PK: {len(client_pk)} bytes")
        print(f"Client Proof (M1): {client_proof.hex()[:32]}...")
        
        m3_data = tlv8_encode({
            TlvValue.SeqNo: bytes([0x03]),
            TlvValue.PublicKey: client_pk,
            TlvValue.Proof: client_proof,
        })
        
        status, headers, body = self.send_rtsp("POST", "/pair-setup", m3_data)
        
        if status != 200:
            raise Exception(f"M3 failed: {status}")
        
        m4 = tlv8_decode(body)
        
        if TlvValue.Error in m4:
            error = m4[TlvValue.Error][0] if m4[TlvValue.Error] else 0
            raise Exception(f"M4 error: 0x{error:02x}")
        
        server_proof = m4.get(TlvValue.Proof, b'')
        print(f"Server Proof (M2): {server_proof.hex()[:32]}...")
        
        # Verify server proof
        if not self.srp.verify_server_proof(server_proof):
            raise Exception("Server proof verification failed!")
        
        print("Server proof verified ✓")
        print(f"Session key K: {self.srp.K.hex()[:32]}...")
        
        return True
    
    def derive_control_keys(self):
        """Derive Control channel encryption keys."""
        print(f"\n{'='*60}")
        print("Step 4: Derive Control Channel Keys")
        print(f"{'='*60}")
        
        if not HAS_CRYPTO:
            print("WARNING: cryptography library not available, skipping key derivation")
            return
        
        # Use the session key K directly
        shared_secret = self.srp.K
        
        self.output_key = hkdf_expand(
            "Control-Salt",
            "Control-Write-Encryption-Key",
            shared_secret
        )
        self.input_key = hkdf_expand(
            "Control-Salt",
            "Control-Read-Encryption-Key",
            shared_secret
        )
        
        print(f"Output Key: {self.output_key.hex()[:32]}...")
        print(f"Input Key: {self.input_key.hex()[:32]}...")
        
        self.cipher = Chacha20Cipher(self.output_key, self.input_key, nonce_length=8)
        print("Encryption cipher initialized")
        
        # Enable HAP session encryption for all subsequent traffic
        self.enable_encryption()
    
    def _generate_clock_id(self) -> int:
        """Generate a PTP clock ID from MAC address or random bytes."""
        # For a real implementation, use MAC address transformed to EUI-64
        # For testing, use a random 64-bit value
        if not hasattr(self, '_clock_id'):
            # Use random bytes but make it deterministic per session
            import random
            random.seed(42)  # Deterministic for debugging
            self._clock_id = random.getrandbits(64)
        return self._clock_id
    
    def _start_ptp_master_clock(self) -> int:
        """
        Start a PTP master clock daemon that sends Announce, Sync, and Follow_Up
        messages to NQPTP on ports 319/320.
        
        NQPTP is PASSIVE - it monitors PTP messages from the client IP.
        We need to act as the PTP master clock so NQPTP can establish timing.
        
        PTP Message Types (IEEE 1588):
        - 0x0B: Announce - Declares clock identity and properties
        - 0x00: Sync - First step of two-step timing  
        - 0x08: Follow_Up - Contains preciseOriginTimestamp
        
        Messages are sent at 125ms intervals (8 per second) per Apple PTP profile.
        """
        import threading
        import time
        
        self._ptp_running = True
        self._clock_id = self._generate_clock_id()
        
        # Create UDP sockets for PTP ports 319 (event) and 320 (general)
        # NQPTP requires sender_port == receiver_port (319→319, 320→320)
        # We need to use SO_REUSEPORT to share ports with NQPTP
        self._ptp_sock_319 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._ptp_sock_320 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        
        # Enable port reuse so we can share with NQPTP
        self._ptp_sock_319.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
        self._ptp_sock_320.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
        
        local_ip = self.sock.getsockname()[0]
        
        try:
            self._ptp_sock_319.bind(('', 319))
            self._ptp_sock_320.bind(('', 320))
            print(f"  [PTP] Bound to ports 319 and 320 (using SO_REUSEPORT)")
        except OSError as e:
            print(f"  [PTP] Cannot bind to 319/320: {e}")
            print(f"  [PTP] Trying ephemeral ports (PTP timing may not work for localhost)")
            self._ptp_sock_319.bind(('', 0))
            self._ptp_sock_320.bind(('', 0))
            print(f"  [PTP] Using ports {self._ptp_sock_319.getsockname()[1]} and {self._ptp_sock_320.getsockname()[1]}")
        
        target_319 = (self.host, 319)
        target_320 = (self.host, 320)
        
        def build_ptp_header(msg_type: int, length: int, seq_id: int, flags: int = 0x0008) -> bytearray:
            """Build PTP common message header (34 bytes).
            
            IEEE 1588 flagField bits:
            - Bit 1 (0x0002): twoStepFlag - set for Sync messages
            - Bit 3 (0x0008): ptpTimescale - set for all messages
            
            Standard flags:
            - ANNOUNCE/FOLLOW_UP: 0x0008 (ptpTimescale only)
            - SYNC: 0x0208 (twoStepFlag + ptpTimescale)
            """
            header = bytearray(34)
            header[0] = 0x10 | msg_type  # transportSpecific (0x1 for 802.1AS) | messageType
            header[1] = 0x02  # versionPTP = 2
            struct.pack_into('>H', header, 2, length)  # messageLength
            header[4] = 0x00  # domainNumber (0 for gPTP)
            header[5] = 0x00  # reserved
            struct.pack_into('>H', header, 6, flags)  # flags
            struct.pack_into('>Q', header, 8, 0)  # correctionField
            struct.pack_into('>I', header, 16, 0)  # reserved
            # clockIdentity (8 bytes)
            struct.pack_into('>Q', header, 20, self._clock_id)
            struct.pack_into('>H', header, 28, 1)  # sourcePortID
            struct.pack_into('>H', header, 30, seq_id)  # sequenceId
            header[32] = 0x05  # controlField - will be overwritten per message type
            header[33] = 0x00  # logMessagePeriod - will be overwritten per message type
            return header
        
        def build_announce_message(seq_id: int) -> bytes:
            """Build PTP Announce message (64 bytes).
            
            Structure (IEEE 1588-2008 Table 30):
            - Header (34 bytes)
            - originTimestamp (10 bytes)
            - currentUtcOffset (2 bytes, signed)
            - reserved (1 byte)
            - grandmasterPriority1 (1 byte) = 248 (Apple profile)
            - grandmasterClockQuality (4 bytes):
                - clockClass (1 byte) = 248
                - clockAccuracy (1 byte) = 0xFE (unknown)
                - offsetScaledLogVariance (2 bytes) = 0xFFFF
            - grandmasterPriority2 (1 byte) = 248
            - grandmasterIdentity (8 bytes)
            - stepsRemoved (2 bytes) = 0
            - timeSource (1 byte) = 0xA0 (Internal Oscillator)
            """
            # Announce uses flags=0x0008 (ptpTimescale only, no twoStep)
            header = build_ptp_header(0x0B, 64, seq_id, flags=0x0008)  # Announce = 0x0B
            header[32] = 0x05  # controlField: All Others
            header[33] = 0x00  # logMessageInterval: 0 = 1 second
            
            msg = bytearray(64)
            msg[:34] = header
            # originTimestamp (10 bytes) - zeros for Announce
            # bytes 34-43 are zeros
            
            # currentUtcOffset (2 bytes, signed) - TAI-UTC offset
            struct.pack_into('>h', msg, 44, 37)  # Current TAI-UTC offset is 37 seconds
            # reserved (1 byte) - byte 46
            msg[46] = 0x00
            # grandmasterPriority1 (1 byte)
            msg[47] = 248  # Apple PTP profile priority
            # grandmasterClockQuality (4 bytes):
            #   clockClass=248, clockAccuracy=0xFE, offsetScaledLogVariance=0xFFFF
            struct.pack_into('>I', msg, 48, 0xF8FEFFFF)
            # grandmasterPriority2 (1 byte)
            msg[52] = 248  # Apple PTP profile priority
            # grandmasterIdentity (8 bytes) - same as clock ID for us
            struct.pack_into('>Q', msg, 53, self._clock_id)
            # stepsRemoved (2 bytes) - 0 because we are the grandmaster
            struct.pack_into('>H', msg, 61, 0)
            # timeSource (1 byte)
            msg[63] = 0xA0  # Internal Oscillator (160)
            
            return bytes(msg)
        
        def build_sync_message(seq_id: int) -> bytes:
            """Build PTP Sync message (44 bytes).
            
            For two-step clocks, Sync has zeros originTimestamp.
            The precise time is sent in the following Follow_Up message.
            """
            # Sync uses flags=0x0208 (twoStepFlag + ptpTimescale)
            header = build_ptp_header(0x00, 44, seq_id, flags=0x0208)  # Sync = 0x00
            header[32] = 0x00  # controlField: Sync
            header[33] = 0xFD  # logMessageInterval: -3 = 125ms (signed byte)
            
            msg = bytearray(44)
            msg[:34] = header
            # originTimestamp (10 bytes) - zeros for two-step clock
            # Actual timestamp comes in Follow_Up message
            
            return bytes(msg)
        
        def build_follow_up_message(seq_id: int, origin_timestamp_ns: int) -> bytes:
            """Build PTP Follow_Up message (44 bytes minimum, 76 with Apple TLV).
            
            The preciseOriginTimestamp contains the actual time when the
            corresponding Sync message was transmitted.
            
            We include the Apple Organization Extension TLV which contains
            information about grandmaster clock changes.
            """
            # Follow_Up uses flags=0x0008 (ptpTimescale only, no twoStep flag)
            header = build_ptp_header(0x08, 76, seq_id, flags=0x0008)  # Follow_Up = 0x08
            header[32] = 0x02  # controlField: Follow_Up
            header[33] = 0xFD  # logMessageInterval: -3 = 125ms (signed byte)
            
            msg = bytearray(76)
            msg[:34] = header
            
            # preciseOriginTimestamp (10 bytes):
            #   secondsHi (2 bytes) + secondsLo (4 bytes) + nanoseconds (4 bytes)
            seconds = origin_timestamp_ns // 1_000_000_000
            nanoseconds = origin_timestamp_ns % 1_000_000_000
            struct.pack_into('>H', msg, 34, (seconds >> 32) & 0xFFFF)  # secondsHi
            struct.pack_into('>I', msg, 36, seconds & 0xFFFFFFFF)      # secondsLo  
            struct.pack_into('>I', msg, 40, nanoseconds)               # nanoseconds
            
            # TLV: Organization Extension (Apple)
            # This is optional but included by Apple devices
            struct.pack_into('>H', msg, 44, 0x0003)  # tlvType: ORGANIZATION_EXTENSION
            struct.pack_into('>H', msg, 46, 28)      # lengthField: bytes following
            # Apple organization ID (00:17:F2)
            msg[48] = 0x00
            msg[49] = 0x17
            msg[50] = 0xF2
            # Organization subtype (00:00:01)
            msg[51] = 0x00
            msg[52] = 0x00
            msg[53] = 0x01
            # lastGmPhaseChange (10 bytes) - offset 54-63
            # This is the phase change when becoming/changing grandmaster
            # We set to zeros as we are always the grandmaster
            # bytes 54-63 are already zeros
            
            # lastGmClockIdentity (8 bytes) - offset 64-71
            struct.pack_into('>Q', msg, 64, self._clock_id)
            # gmTimeBaseIndicator (2 bytes) - offset 72-73
            struct.pack_into('>H', msg, 72, 0)
            # scaledLastGmFreqChange (4 bytes) - offset 74-77... but we sized at 76
            # The length 28 accounts for: 3+3+10+8+2+2 = 28
            # So we need 2 more bytes for scaledLastGmFreqChange
            # Actually let's keep it at 76 bytes and adjust length to 26
            
            # Actually re-checking: TLV data = 28 bytes after tlvType+length
            # 3 (orgId) + 3 (subtype) + 10 (phase) + 8 (clock) + 2 (indicator) + 2 (freq) = 28
            # Header=34 + timestamp=10 + tlvType=2 + length=2 + data=28 = 76 ✓
            # We need to include the scaledLastGmFreqChange
            struct.pack_into('>H', msg, 74, 0)  # scaledLastGmFreqChange (just first 2 bytes)
            
            return bytes(msg)
        
        def ptp_thread():
            """Send PTP timing messages at 125ms intervals."""
            print(f"  [PTP] Master clock started, clock_id={self._clock_id:016x}")
            print(f"  [PTP] Sending to {self.host} on ports 319/320")
            
            seq_id = 0
            announce_seq = 0
            
            # Use MONOTONIC time for PTP timestamps!
            # NQPTP uses CLOCK_MONOTONIC for reception_time, so our PTP time must match
            # iPhone uses monotonic-style time (~3237 sec), not Unix epoch (~56 years)
            
            while self._ptp_running:
                try:
                    # Get current timestamp as MONOTONIC nanoseconds
                    # This matches NQPTP's CLOCK_MONOTONIC reception_time
                    now_ns = time.monotonic_ns()
                    
                    # Send Announce (every ~1 second, so every 8th message)
                    if seq_id % 8 == 0:
                        announce = build_announce_message(announce_seq)
                        self._ptp_sock_320.sendto(announce, target_320)
                        announce_seq += 1
                        if seq_id % 64 == 0:  # Every ~8 seconds, log
                            print(f"  [PTP] Sent Announce #{announce_seq}")
                    
                    # Send Sync (on port 319 event port)
                    sync = build_sync_message(seq_id)
                    self._ptp_sock_319.sendto(sync, target_319)
                    
                    # Send Follow_Up immediately after Sync (on port 320 general port)
                    # Use MONOTONIC time to match NQPTP's CLOCK_MONOTONIC
                    follow_up = build_follow_up_message(seq_id, now_ns)
                    self._ptp_sock_320.sendto(follow_up, target_320)
                    
                    seq_id += 1
                    
                    # Sleep for 125ms (8 messages per second per Apple PTP profile)
                    time.sleep(0.125)
                    
                except Exception as e:
                    if self._ptp_running:
                        print(f"  [PTP] Error: {e}")
                    break
        
        self._ptp_thread = threading.Thread(target=ptp_thread, daemon=True)
        self._ptp_thread.start()
        
        # Give PTP clock time to send several Sync/Follow_Up pairs
        # NQPTP needs multiple messages to establish clock validity
        # At 125ms intervals, 2 seconds = 16 sync pairs, should be enough
        time.sleep(2.0)
        
        print(f"  [PTP] Clock ID: {self._clock_id:016x}")
        return self._clock_id
    
    def _stop_ptp_master_clock(self):
        """Stop the PTP master clock."""
        self._ptp_running = False
        if hasattr(self, '_ptp_sock_319') and self._ptp_sock_319:
            self._ptp_sock_319.close()
        if hasattr(self, '_ptp_sock_320') and self._ptp_sock_320:
            self._ptp_sock_320.close()
        print("  [PTP] Master clock stopped")
    
    def setup_event_channel(self) -> int:
        """Setup event channel with PTP timing, returns event port.
        
        CRITICAL TIMING:
        1. First send SETUP request - this tells shairport-sync about our timing info
        2. shairport-sync then sends "T <our_ip>" to NQPTP to start monitoring us
        3. ONLY THEN start our PTP master clock - NQPTP will now receive our packets
        4. Send ANNOUNCE burst to establish clock identity
        5. Wait for NQPTP to process FOLLOW_UP and update shared memory
        """
        print(f"\n{'='*60}")
        print("Step 5: SETUP Event Channel (PTP)")
        print(f"{'='*60}")
        
        import uuid
        import plistlib
        
        session_uuid = str(uuid.uuid4()).upper()
        self.session_uuid = session_uuid
        
        # Get local IP for timing peer
        local_ip = self.sock.getsockname()[0]
        
        # Prepare PTP clock ID but DON'T start yet
        # We need to wait until after SETUP when shairport tells NQPTP to monitor us
        self._clock_id = secrets.randbits(64)
        print(f"\n  [PTP] Clock ID prepared: {self._clock_id:016x}")
        
        setup_body = {
            "deviceID": "AA:BB:CC:DD:EE:FF",
            "sessionUUID": session_uuid,
            "timingProtocol": "PTP",  # shairport-sync only supports PTP, not NTP
            "timingPeerInfo": {
                "Addresses": [local_ip],
                "ID": "AA:BB:CC:DD:EE:FF"
            },
            "groupUUID": session_uuid,
            "groupContainsGroupLeader": False,
            "isMultiSelectAirPlay": True,
            "macAddress": "AA:BB:CC:DD:EE:FF",
            "model": "iPhone14,3",
            "name": "centuryplay",
            "osBuildVersion": "20F66",
            "osName": "iPhone OS",
            "osVersion": "16.5",
            "senderSupportsRelay": False,
            "sourceVersion": "690.7.1",
            "statsCollectionEnabled": False,
        }
        
        body = plistlib.dumps(setup_body, fmt=plistlib.FMT_BINARY)
        
        # STEP 1: Send SETUP first - this triggers shairport to tell NQPTP about us
        status, headers, resp_body = self.send_rtsp(
            "SETUP", f"rtsp://{self.host}/{self.session_uuid}",
            body=body,
            content_type="application/x-apple-binary-plist"
        )
        
        if status != 200:
            raise Exception(f"SETUP failed: {status}")
        
        resp = plistlib.loads(resp_body)
        event_port = resp.get("eventPort", 0)
        print(f"Event port: {event_port}")
        
        # STEP 2: NOW start PTP master clock - NQPTP is now monitoring our IP
        print(f"\n  [PTP] Starting master clock (NQPTP should now be monitoring us)...")
        self._start_ptp_master_clock_minimal()
        
        # STEP 3: Send ANNOUNCE burst immediately - NQPTP needs to see this
        # to recognize our clock before it will process SYNC/FOLLOW_UP
        print(f"  [PTP] Sending ANNOUNCE burst to establish clock...")
        self._send_ptp_announce_burst(count=10, delay=0.05)  # More messages, faster
        
        # STEP 4: Wait for NQPTP to process and update shared memory
        # NQPTP needs:
        #   - See ANNOUNCE (establishes clock_id, grandmaster)
        #   - Receive "B" command from shairport (sets clock_is_active=true)
        #   - Process FOLLOW_UP (calculates offset)
        #   - Update shared memory with master clock info
        # This can take a few hundred milliseconds
        print(f"  [PTP] Waiting for NQPTP to update shared memory...")
        time.sleep(1.0)  # Give NQPTP time to establish clock
        
        return event_port
    
    def _start_ptp_master_clock_minimal(self):
        """Start PTP master clock thread without initial wait.
        
        Unlike _start_ptp_master_clock(), this doesn't wait 2 seconds
        because we need to control timing carefully.
        """
        import threading
        
        # Create UDP sockets for PTP
        self._ptp_sock_319 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._ptp_sock_320 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        
        # Try to use SO_REUSEPORT for compatibility
        try:
            self._ptp_sock_319.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            self._ptp_sock_320.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            # Try binding to standard PTP ports (requires root)
            try:
                self._ptp_sock_319.bind(('', 319))
                self._ptp_sock_320.bind(('', 320))
                print(f"  [PTP] Bound to ports 319 and 320 (using SO_REUSEPORT)")
            except PermissionError:
                print(f"  [PTP] Warning: Cannot bind to 319/320 (need root), using ephemeral ports")
                self._ptp_sock_319.bind(('', 0))
                self._ptp_sock_320.bind(('', 0))
        except AttributeError:
            # SO_REUSEPORT not available
            self._ptp_sock_319.bind(('', 0))
            self._ptp_sock_320.bind(('', 0))
            print(f"  [PTP] Using ephemeral ports (SO_REUSEPORT not available)")
        
        target_319 = (self.host, 319)
        target_320 = (self.host, 320)
        
        self._ptp_running = True
        
        def build_ptp_header(msg_type: int, length: int, seq_id: int, flags: int = 0x0008) -> bytearray:
            """Build PTP common message header (34 bytes)."""
            header = bytearray(34)
            header[0] = 0x10 | msg_type  # transportSpecific (0x1 for 802.1AS) | messageType
            header[1] = 0x02  # versionPTP = 2
            struct.pack_into('>H', header, 2, length)
            header[4] = 0x00  # domainNumber (0 for gPTP)
            header[5] = 0x00  # reserved
            struct.pack_into('>H', header, 6, flags)
            struct.pack_into('>Q', header, 8, 0)  # correctionField
            struct.pack_into('>I', header, 16, 0)  # reserved
            struct.pack_into('>Q', header, 20, self._clock_id)
            struct.pack_into('>H', header, 28, 1)  # sourcePortID
            struct.pack_into('>H', header, 30, seq_id)
            header[32] = 0x05  # controlField
            header[33] = 0x00  # logMessagePeriod
            return header
        
        def build_sync_message(seq_id: int) -> bytes:
            """Build PTP Sync message (44 bytes)."""
            header = build_ptp_header(0x00, 44, seq_id, flags=0x0208)  # twoStep + ptpTimescale
            header[32] = 0x00  # controlField: Sync
            header[33] = 0xFD  # logMessageInterval: -3 = 125ms
            msg = bytearray(44)
            msg[:34] = header
            return bytes(msg)
        
        def build_follow_up_message(seq_id: int, origin_timestamp_ns: int) -> bytes:
            """Build PTP Follow_Up message (76 bytes)."""
            header = build_ptp_header(0x08, 76, seq_id, flags=0x0008)  # ptpTimescale only
            header[32] = 0x02  # controlField: Follow_Up
            header[33] = 0xFD  # logMessageInterval: -3 = 125ms
            msg = bytearray(76)
            msg[:34] = header
            seconds = origin_timestamp_ns // 1_000_000_000
            nanoseconds = origin_timestamp_ns % 1_000_000_000
            struct.pack_into('>H', msg, 34, (seconds >> 32) & 0xFFFF)
            struct.pack_into('>I', msg, 36, seconds & 0xFFFFFFFF)
            struct.pack_into('>I', msg, 40, nanoseconds)
            # TLV
            struct.pack_into('>H', msg, 44, 0x0003)
            struct.pack_into('>H', msg, 46, 28)
            msg[48] = 0x00; msg[49] = 0x17; msg[50] = 0xF2
            msg[51] = 0x00; msg[52] = 0x00; msg[53] = 0x01
            struct.pack_into('>Q', msg, 64, self._clock_id)
            struct.pack_into('>H', msg, 72, 0)
            struct.pack_into('>H', msg, 74, 0)
            return bytes(msg)
        
        def build_announce_message(seq_id: int) -> bytes:
            """Build PTP Announce message (64 bytes)."""
            header = build_ptp_header(0x0B, 64, seq_id, flags=0x0008)
            header[32] = 0x05  # controlField: Other
            header[33] = 0x00  # logMessageInterval: 0 = 1 second
            msg = bytearray(64)
            msg[:34] = header
            struct.pack_into('>h', msg, 44, 37)  # currentUtcOffset
            msg[46] = 0x00
            msg[47] = 248  # grandmasterPriority1
            struct.pack_into('>I', msg, 48, 0xF8FEFFFF)  # clockQuality
            msg[52] = 248  # grandmasterPriority2
            struct.pack_into('>Q', msg, 53, self._clock_id)
            struct.pack_into('>H', msg, 61, 0)  # stepsRemoved
            msg[63] = 0xA0  # timeSource
            return bytes(msg)
        
        def ptp_thread():
            """Send PTP timing messages at 125ms intervals."""
            seq_id = 0
            announce_seq = 0
            
            while self._ptp_running:
                try:
                    now_ns = time.time_ns()
                    
                    # Send Announce every ~1 second (every 8th message)
                    if seq_id % 8 == 0:
                        announce = build_announce_message(announce_seq)
                        self._ptp_sock_320.sendto(announce, target_320)
                        announce_seq += 1
                    
                    # Send Sync
                    sync = build_sync_message(seq_id)
                    self._ptp_sock_319.sendto(sync, target_319)
                    
                    # Send Follow_Up
                    follow_up = build_follow_up_message(seq_id, now_ns)
                    self._ptp_sock_320.sendto(follow_up, target_320)
                    
                    seq_id += 1
                    time.sleep(0.125)  # 125ms interval
                    
                except Exception as e:
                    if self._ptp_running:
                        print(f"  [PTP] Error: {e}")
                    break
        
        self._ptp_thread = threading.Thread(target=ptp_thread, daemon=True)
        self._ptp_thread.start()
        print(f"  [PTP] Master clock started, clock_id={self._clock_id:016x}")
    
    def setup_audio_stream(self, control_port: int = 0) -> Tuple[int, int]:
        """Setup audio stream, returns (control_port, data_port)."""
        print(f"\n{'='*60}")
        print("Step 6: SETUP Audio Stream")
        print(f"{'='*60}")
        
        import plistlib
        
        # Generate shared key for audio encryption
        shared_secret = secrets.token_bytes(32)
        
        setup_body = {
            "streams": [
                {
                    "audioFormat": 0x40000,  # ALAC (262144) - Correct for AirPlay 2
                    "audioMode": "default",
                    "controlPort": control_port,
                    "ct": 2,  # Compression Type 2 (ALAC)
                    "isMedia": True,
                    "latencyMax": 88200,
                    "latencyMin": 11025,
                    "shk": shared_secret,
                    "spf": 352,  # Samples Per Frame
                    "sr": 44100,  # Sample rate
                    "type": 0x60,  # Audio stream
                    "supportsDynamicStreamID": True,
                    "streamConnectionID": self.cseq,
                }
            ]
        }
        
        body = plistlib.dumps(setup_body, fmt=plistlib.FMT_BINARY)
        
        status, headers, resp_body = self.send_rtsp(
            "SETUP", f"rtsp://{self.host}/{self.session_uuid}",
            body=body,
            content_type="application/x-apple-binary-plist"
        )
        
        if status != 200:
            raise Exception(f"SETUP audio failed: {status}")
        
        resp = plistlib.loads(resp_body)
        streams = resp.get("streams", [{}])
        stream = streams[0] if streams else {}
        
        ctrl_port = stream.get("controlPort", 0)
        data_port = stream.get("dataPort", 0)
        
        print(f"Control port: {ctrl_port}")
        print(f"Data port: {data_port}")
        
        self.audio_shared_secret = shared_secret
        self.audio_control_port = ctrl_port
        self.audio_data_port = data_port
        
        return ctrl_port, data_port
    
    def record(self):
        """Start the stream with RECORD."""
        print(f"\n{'='*60}")
        print("Step 7: RECORD")
        print(f"{'='*60}")
        
        status, headers, body = self.send_rtsp(
            "RECORD", f"rtsp://{self.host}/{self.session_uuid}"
        )
        
        if status != 200:
            raise Exception(f"RECORD failed: {status}")
        
        print("Stream started!")
        return True
    
    def flush(self, rtpseq: int = 0, rtptime: int = 0):
        """Send FLUSH command with RTP-Info.
        
        This is needed according to pyatv - it sends the starting sequence
        number and RTP timestamp.
        """
        print(f"\n{'='*60}")
        print("Step 8: FLUSH")
        print(f"{'='*60}")
        
        extra_headers = {
            "Range": "npt=0-",
            "RTP-Info": f"seq={rtpseq};rtptime={rtptime}"
        }
        
        status, headers, body = self.send_rtsp(
            "FLUSH", f"rtsp://{self.host}/{self.session_uuid}",
            extra_headers=extra_headers
        )
        
        if status != 200:
            print(f"FLUSH returned: {status} (non-fatal)")
        else:
            print("FLUSH OK")
        
        return True

    def set_volume(self, volume_db: float = 0.0):
        """Send SET_PARAMETER to set volume (0.0 is max, -30.0 is typical low)."""
        print(f"\n{'='*60}")
        print(f"Step 9: SET_VOLUME to {volume_db}dB")
        print(f"{'='*60}")
        
        content = f"volume: {volume_db}\r\n".encode('utf-8')
        
        status, headers, body = self.send_rtsp(
            "SET_PARAMETER", f"rtsp://{self.host}/{self.session_uuid}",
            content_type="text/parameters",
            body=content
        )
        
        if status != 200:
            print(f"SET_VOLUME failed: {status} (non-fatal)")
        else:
            print("SET_VOLUME OK")
        
        return True

    # --------------------------------------------------------------------------
    # ALAC Encoding Helper (for "Uncompressed" ALAC Frames)
    # --------------------------------------------------------------------------
    def generate_alac_payloads(self, duration: float) -> list[bytes]:
        """Generate ALAC payloads using ffmpeg via CAF file."""
        import subprocess
        import os
        import struct
        
        filename = "/tmp/airplay_test.caf"
        
        # 2. Run ffmpeg to generate sine wave -> ALAC -> CAF
        # -t duration
        # -c:a alac (encode to ALAC)
        # -f caf
        
        cmd = [
            'ffmpeg', '-y',
            '-f', 'lavfi',
            '-i', f'sine=f=440:r=44100',
            '-t', str(duration),
            '-c:a', 'alac',
            '-f', 'caf',
            filename
        ]
        
        print(f"  [FFmpeg] Generating {duration}s ALAC audio to {filename}...")
        # Capture stdout/stderr for debug if needed, but devnull is fine if we check retcode
        ret = subprocess.call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if ret != 0:
            print("  [FFmpeg] Error generating audio! Try running ffmpeg manually.")
            return []
            
        # 3. Parse CAF file
        payloads = []
        try:
            with open(filename, 'rb') as f:
                # Header
                magic = f.read(4)
                if magic != b'caff':
                    print("  [CAF] Invalid magic")
                    return []
                f.read(4) # Version + Flags
                
                data_blob = b''
                packet_sizes = []
                
                while True:
                    chunk_type = f.read(4)
                    if not chunk_type: break
                    chunk_size = struct.unpack('>q', f.read(8))[0]
                    
                    if chunk_type == b'data':
                        # Skip 4 bytes edit count
                        f.read(4)
                        data_blob = f.read(chunk_size - 4)
                    elif chunk_type == b'pakt':
                        pakt_start = f.tell()
                        num_packets = struct.unpack('>Q', f.read(8))[0]
                        f.read(16) # Valid(8) + Priming(4) + Remainder(4)
                        
                        # Read variable ints
                        # We read the rest of the chunk
                        bytes_left = chunk_size - 24
                        table_data = f.read(bytes_left)
                        
                        offset = 0
                        for _ in range(num_packets):
                            if offset >= len(table_data): break
                            val = 0
                            while True:
                                b = table_data[offset]
                                offset += 1
                                val = (val << 7) | (b & 0x7F)
                                if not (b & 0x80):
                                    break
                            packet_sizes.append(val)
                    else:
                        f.seek(chunk_size, 1) # Skip
                
                # Now slice data_blob using packet_sizes
                print(f"  [CAF] Found {len(packet_sizes)} packets in pakt, blob size {len(data_blob)}")
                
                # If no pakt found (ffmpeg streaming mode?), try to deduce?
                # But ffmpeg file output should have pakt.
                
                offset = 0
                for sz in packet_sizes:
                    if offset + sz > len(data_blob):
                        print("  [CAF] Buffer underflow during slicing!")
                        break
                    frame = data_blob[offset:offset+sz]
                    payloads.append(frame)
                    offset += sz
                    
        except Exception as e:
            print(f"  [CAF] Parsing error: {e}")
            import traceback
            traceback.print_exc()
        
        # Cleanup
        if os.path.exists(filename):
            os.remove(filename)
            
        print(f"  [CAF] Extracted {len(payloads)} ALAC frames")
        return payloads

    def send_audio_packets(self, duration_sec: float = 5.0):
        """Send generated audio packets over UDP."""
        if not hasattr(self, 'audio_data_port') or not hasattr(self, 'audio_shared_secret'):
            print("Audio stream not setup! Call setup_audio_stream() first.")
            return

        print(f"\n{'='*60}")
        print("Step 9: Stream Audio (ALAC Encrypted)")
        print(f"{'='*60}")
        
        import threading

        # Setup sockets
        audio_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        ctrl_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        
        # Start the TCP feedback listener (if not already running/needed)
        self._keep_tcp_alive = True
        def keep_tcp_alive():
            try:
                while self._keep_tcp_alive:
                    ready = select.select([self.sock], [], [], 1.0)
                    if ready[0]:
                        data = self.sock.recv(4096)
                        if not data:
                            break
            except:
                pass
        
        tcp_thread = threading.Thread(target=keep_tcp_alive, daemon=True)
        tcp_thread.start()
        
        # Helper to send Anchor Packet (Type 215/0xD7) to Control Port
        # This is the CORRECT format for AirPlay 2 as per shairport-sync rtp.c
        # Maps RTP Timestamp <-> PTP Time (Network Time)
        #
        # Packet format (28 bytes minimum):
        #   Byte 0: 0x10 bit set for sentinel (first packet indicator)
        #   Byte 1: 0xD7 (215) - packet type for anchor/timing
        #   Bytes 2-3: Sequence number (unused?)
        #   Bytes 4-7: frame_1 (uint32) - RTP timestamp with latency (77175 frames)
        #   Bytes 8-15: remote_packet_time_ns (uint64) - PTP network time in nanoseconds
        #   Bytes 16-19: frame_2 (uint32) - RTP timestamp (the frame the time refers to)
        #   Bytes 20-27: clock_id (uint64) - PTP clock ID
        #
        anchor_packet_seq = 0
        def send_anchor_packet(rtp_ts, ptp_time_ns, clock_id_bytes, is_sentinel=False):
            nonlocal anchor_packet_seq
            
            # Standard latency offset used by iPhone (77175 frames)
            LATENCY_FRAMES = 77175
            
            packet = bytearray(28)
            
            # Byte 0: 0x80 (V=2) | 0x10 if sentinel
            packet[0] = 0x80 | (0x10 if is_sentinel else 0x00)
            
            # Byte 1: Type 215 (0xD7)
            packet[1] = 0xD7
            
            # Bytes 2-3: Sequence number (big-endian)
            struct.pack_into('>H', packet, 2, anchor_packet_seq & 0xFFFF)
            anchor_packet_seq += 1
            
            # Bytes 4-7: frame_1 = frame_2 + LATENCY_FRAMES (the RTP with latency included)
            frame_2 = rtp_ts & 0xFFFFFFFF
            frame_1 = (frame_2 + LATENCY_FRAMES) & 0xFFFFFFFF
            struct.pack_into('>I', packet, 4, frame_1)
            
            # Bytes 8-15: remote_packet_time_ns (PTP time in nanoseconds, big-endian uint64)
            struct.pack_into('>Q', packet, 8, int(ptp_time_ns))
            
            # Bytes 16-19: frame_2 (the RTP timestamp the time refers to)
            struct.pack_into('>I', packet, 16, frame_2)
            
            # Bytes 20-27: clock_id (our PTP clock ID as uint64, big-endian)
            # clock_id_bytes should be the 8-byte hex clock ID we use for PTP
            if isinstance(clock_id_bytes, str):
                clock_id_int = int(clock_id_bytes, 16)
            elif isinstance(clock_id_bytes, bytes):
                clock_id_int = int.from_bytes(clock_id_bytes, 'big')
            else:
                clock_id_int = clock_id_bytes
            struct.pack_into('>Q', packet, 20, clock_id_int)
            
            ctrl_sock.sendto(packet, (self.host, self.audio_control_port))
            if is_sentinel:
                print(f"  [Anchor] Sent SENTINEL anchor: RTP={frame_2}, PTP_ns={ptp_time_ns}, ClockID={clock_id_int:016x}")

        # Sync Loop Thread
        self._keep_sync_alive = True
        def sync_loop():
            local_seq = 0
            while self._keep_sync_alive:
                now = time.time()
                # Calculate corresponding RTP time for 'now'
                # RTP = StartRTP + (Now - StartTime) * Rate
                # We need accurate mapping. 
                # self._start_rtptime corresponds to... when?
                # We didn't save a _start_walltime.
                # Let's use current estimated RTP time based on stream progress?
                # Better: Define Anchor NOW.
                
                # We need to map [Current PTP] to [Current RTP]
                # In send_audio_packets loop, we are essentially defining the mapping.
                pass
                time.sleep(1.0)
        
        # We need to run sync sending inside the send_audio_packets loop 
        # or in parallel, but using the same clock source.
        
        # Generate ALAC packets via ffmpeg
        alac_frames = self.generate_alac_payloads(duration_sec)
        
        if not alac_frames:
            print("Error: No ALAC frames generated!")
            return

        # Prepare encryption
        # Key: shared_secret from SETUP (self.audio_shared_secret)
        cipher = Chacha20Cipher(self.audio_shared_secret, self.audio_shared_secret) 
        
        # Sequencing
        # Use valid RTP sequence and timestamp
        sequence_number = getattr(self, '_start_rtpseq', random.randint(0, 65535))
        rtp_time = getattr(self, '_start_rtptime', 0) & 0xFFFFFFFF
        
        # Audio params
        sample_rate = 44100
        # ALAC frame size is typically 352 or 4096 depending on encoder.
        # ffmpeg usually does 4096 for default ALAC.
        # We should increment timestamp by the number of samples in the frame.
        # How do we know samples per frame?
        # ALAC frames are variable size in bytes, but fixed in samples usually.
        # Standard ALAC is 4096 samples per frame.
        samples_per_frame = 4096 # Standard for ALAC
        frame_duration_ns = int((samples_per_frame / sample_rate) * 1_000_000_000)  # Frame duration in nanoseconds
        
        start_time_ns = time.time_ns() - 2_000_000_000  # Pre-fill buffer (2 seconds in ns)
        encryption_counter = 0 # 64-bit nonce counter for ChaCha20
        
        print(f"Sending {len(alac_frames)} packets...")
        
        # Get PTP clock ID (stored during setup_event_channel / start_ptp_master)
        ptp_clock_id = getattr(self, '_clock_id', 0)
        
        # Get PTP session start time for session-relative calculations
        ptp_session_start = getattr(self, '_ptp_session_start_ns', time.time_ns())
        
        # Send SENTINEL anchor packet BEFORE first audio packet
        # This establishes the timing anchor for shairport-sync
        # Use MONOTONIC time (same as PTP Follow_Up timestamps)
        initial_ptp_ns = time.monotonic_ns()
        initial_rtp = rtp_time
        send_anchor_packet(initial_rtp, initial_ptp_ns, ptp_clock_id, is_sentinel=True)
        
        for i, payload in enumerate(alac_frames):
            # RTP Header
            # V=2, P=0, X=0, CC=0, M=1 (marker bit? usually 1 for audio?), PT=96 (dynamic)
            # M bit is typically set on first packet of talkspurt, or maybe specific to format.
            # shairport-sync logs showed "First packet is a sentinel packet" sometimes.
            # Let's just set M=0 after first? Or M=1 for all?
            # Wireshark traces for AirPlay usually show M=1 for ALAC?
            # Let's try 0x80 (V=2) | 0x60 (PT=96). M-bit (0x80 in second byte) = 0.
            # So second byte = 0x60 (96).
            
            # RTP header: [ V(2)|P|X|CC(4) ] [ M|PT(7) ] [ Seq (16) ] [ TS (32) ] [ SSRC (32) ]
            rtp_header = bytearray(12)
            rtp_header[0] = 0x80 # V=2
            rtp_header[1] = 0x60 # M=0, PT=96 (0x60 = 96)
            # If payload type in SETUP was 96.
            
            struct.pack_into('>H', rtp_header, 2, sequence_number)
            struct.pack_into('>I', rtp_header, 4, rtp_time)
            struct.pack_into('>I', rtp_header, 8, self.cseq) # SSRC = Active-Remote or CSeq?
            
            # Encryption
            # Nonce = 4 zero bytes + 8 byte little-endian counter
            nonce = bytearray(12) # zeros
            struct.pack_into('<Q', nonce, 4, encryption_counter) # Little Endian counter at offset 4
            
            # AAD = rtp_header[4:12] (timestamp + ssrc) - like pyatv
            aad = bytes(rtp_header[4:12])
            
            encrypted_payload = cipher.encrypt(payload, bytes(nonce), aad)
            encryption_counter += 1
            
            # Packet = Header + Encrypted Payload + Nonce (last 8 bytes? check spec)
            # pyatv: rtp + encrypted + nonce(8 bytes)
            # Confirmed by my previous reading of pyatv (Chacha20Cipher8byteNonce)
            # but wait, does pyatv append the nonce?
            # Yes, usually AirPlay appends the nonce or it's implicit?
            # Looking at previous successful code: "packet = rtp_header + encrypted + nonce_8_le"
            # Yes, append 8 byte nonce.
            
            packet = rtp_header + encrypted_payload + nonce[4:]
            
            audio_sock.sendto(packet, (self.host, self.audio_data_port))
            
            sequence_number = (sequence_number + 1) & 0xFFFF
            rtp_time = (rtp_time + samples_per_frame) & 0xFFFFFFFF
            
            # Pacing and Sync
            
            # Send Anchor packet every ~1 second (every 44100/4096≈10 packets)
            # ALAC frame is 4096 samples approx 0.09s. So every 10 frames is ~1s.
            if i % 10 == 0 and i > 0:
                # Calculate current PTP time as MONOTONIC nanoseconds
                # This matches PTP Follow_Up timestamps (same epoch)
                anchor_ptp_ns = time.monotonic_ns()
                send_anchor_packet(rtp_time, anchor_ptp_ns, ptp_clock_id, is_sentinel=False)
            
            # Pacing: calculate expected send time and wait if needed
            expected_send_time_ns = start_time_ns + int(i * frame_duration_ns * 0.995)  # Slight speedup
            now_ns = time.time_ns()
            if expected_send_time_ns > now_ns:
                time.sleep((expected_send_time_ns - now_ns) / 1_000_000_000)
                
            if i % 20 == 0:
                print(f"\r  Sent {i}/{len(alac_frames)} packets", end="", flush=True)
                
        print("\nSending Complete.")
        audio_sock.close()
        ctrl_sock.close()
    
    def transient_pair(self) -> bool:
        """Execute full transient pairing flow."""
        print("\n" + "="*60)
        print("AirPlay 2 Transient Pairing")
        print("="*60)
        print(f"Host: {self.host}:{self.port}")
        print(f"PIN: {self.TRANSIENT_PIN} (fixed for transient)")
        
        if not self.pair_pin_start():
            return False
        
        salt, server_pk = self.pair_setup_m1()
        
        if not self.pair_setup_m3(salt, server_pk):
            return False
        
        self.derive_control_keys()
        
        print("\n" + "="*60)
        print("TRANSIENT PAIRING COMPLETE!")
        print("="*60)
        
        return True
    
    def _send_ptp_announce_burst(self, count: int = 3, delay: float = 0.125):
        """Send a burst of ANNOUNCE messages to ensure NQPTP sees our clock.
        
        This is needed because NQPTP discards SYNC/FOLLOW_UP until it sees
        an ANNOUNCE from the clock it's monitoring. After SETUP, shairport
        tells NQPTP to monitor our IP, so we need to send ANNOUNCE immediately.
        """
        if not hasattr(self, '_ptp_sock_320') or not self._ptp_sock_320:
            print("  [PTP] Warning: PTP socket not available for announce burst")
            return
            
        import struct
        
        def build_ptp_header(msg_type: int, msg_length: int, seq_id: int) -> bytearray:
            """Build PTP header (34 bytes)."""
            header = bytearray(34)
            header[0] = (0x1 << 4) | msg_type  # transportSpecific=1 (802.1AS), messageType
            header[1] = 0x02  # versionPTP = 2
            struct.pack_into('>H', header, 2, msg_length)
            header[4] = 0  # domainNumber = 0 (gPTP domain)
            header[5] = 0  # reserved
            # flags (2 bytes) - offset 6-7
            header[6] = 0x00  # flags: twoStepFlag (if Sync)
            header[7] = 0x08  # flags: ptpTimescale = 1
            # correctionField (8 bytes) - offset 8-15 - zeros
            # reserved (4 bytes) - offset 16-19 - zeros
            # sourcePortIdentity (10 bytes) - offset 20-29
            struct.pack_into('>Q', header, 20, self._clock_id)
            struct.pack_into('>H', header, 28, 1)  # port number = 1
            # sequenceId (2 bytes) - offset 30-31
            struct.pack_into('>H', header, 30, seq_id)
            # controlField (1 byte) - offset 32
            header[32] = 0x05  # Other (Announce)
            # logMessageInterval (1 byte) - offset 33
            header[33] = 0xFD  # logMessagePeriod = -3 (125ms)
            return header
        
        def build_announce_message(seq_id: int) -> bytes:
            """Build PTP Announce message (64 bytes)."""
            header = build_ptp_header(0x0B, 64, seq_id)  # Announce = 0x0B
            header[32] = 0x05  # controlField for Announce
            header[33] = 0x00  # logMessageInterval for Announce
            msg = bytearray(64)
            msg[:34] = header
            # originTimestamp (10 bytes) - zeros, bytes 34-43
            # currentUtcOffset (2 bytes, signed)
            struct.pack_into('>h', msg, 44, 37)  # TAI-UTC offset
            # reserved (1 byte) - byte 46
            msg[46] = 0x00
            # grandmasterPriority1 (1 byte)
            msg[47] = 248  # Apple PTP profile priority
            # grandmasterClockQuality (4 bytes) - packed as single value:
            #   clockClass=248 (0xF8), clockAccuracy=0xFE, variance=0xFFFF
            struct.pack_into('>I', msg, 48, 0xF8FEFFFF)
            # grandmasterPriority2 (1 byte)
            msg[52] = 248  # Apple PTP profile priority
            # grandmasterIdentity (8 bytes)
            struct.pack_into('>Q', msg, 53, self._clock_id)
            # stepsRemoved (2 bytes)
            struct.pack_into('>H', msg, 61, 0)
            # timeSource (1 byte)
            msg[63] = 0xA0  # Internal Oscillator (160)
            return bytes(msg)
        
        target_320 = (self.host, 320)
        
        print(f"  [PTP] Sending ANNOUNCE burst ({count} messages) to wake up NQPTP...")
        for i in range(count):
            announce = build_announce_message(1000 + i)  # Use high seq to not conflict
            self._ptp_sock_320.sendto(announce, target_320)
            if i < count - 1:
                time.sleep(delay)
        print(f"  [PTP] ANNOUNCE burst complete")
    
    def stream_audio(self) -> bool:
        """Setup streaming after pairing."""
        print("\n" + "="*60)
        print("Setting up Audio Stream")
        print("="*60)
        
        # setup_event_channel now handles:
        # 1. Sending SETUP (which triggers shairport to tell NQPTP about us)
        # 2. Starting PTP master clock
        # 3. Sending ANNOUNCE burst
        # 4. Waiting for NQPTP to establish clock
        event_port = self.setup_event_channel()
        
        ctrl_port, data_port = self.setup_audio_stream()
        
        # Send another ANNOUNCE burst after audio stream SETUP
        # because shairport sends another "B" command here
        print("  [PTP] Sending final ANNOUNCE burst before RECORD...")
        self._send_ptp_announce_burst(count=5, delay=0.05)
        
        # Wait for NQPTP to fully establish the master clock
        # This is critical - shairport-sync will check for master clock at RECORD
        print("  [PTP] Waiting for NQPTP clock to stabilize...")
        time.sleep(1.5)
        
        self.record()
        
        # Compute starting sequence and RTP timestamp (like pyatv)
        # Random starting sequence number
        start_rtpseq = random.randint(0, 65535)
        
        # NTP to timestamp conversion (like pyatv)
        def ntp_now() -> int:
            now_us = time.time_ns() // 1000
            seconds = now_us // 1000000
            frac = now_us - seconds * 1000000
            return ((seconds + 0x83AA7E80) << 32) | int((frac << 32) // 1000000)
        
        def ntp2ts(ntp: int, rate: int) -> int:
            return int((ntp >> 16) * rate) >> 16
        
        # Audio params
        sample_rate = 44100
        head_ts = ntp2ts(ntp_now(), sample_rate)
        head_ts &= 0xFFFFFFFF
        
        # Encryption counter - SEPARATE from sequence number
        
        # Store for use in send_audio_packets
        self._start_rtpseq = start_rtpseq
        self._start_rtptime = head_ts
        
        # Send FLUSH command with RTP-Info (like pyatv does)
        self.flush(start_rtpseq, head_ts & 0xFFFFFFFF) # Apply masking here
        
        # Set Volume to 0.0dB (Max) - effectively unmutes
        self.set_volume(0.0)
        
        print("\n" + "="*60)
        print("STREAMING READY!")
        print("="*60)
        print(f"Event port: {event_port}")
        print(f"Control port: {ctrl_port}")
        print(f"Data port: {data_port}")
        print(f"Starting RTP seq: {start_rtpseq}")
        print(f"Starting RTP time: {head_ts}")
        
        # Stream test audio
        self.send_audio_packets(duration_sec=30.0)
        
        return True


def main():
    import time as time_module
    
    if len(sys.argv) < 2:
        print("Usage: python3 airplay2_transient.py <host> [port]")
        sys.exit(1)
    
    host = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 7000
    
    client = AirPlay2Client(host, port)
    
    try:
        client.connect()
        if client.transient_pair():
            client.stream_audio()
            # Keep the connection open for a bit longer to let audio play out
            print("\nKeeping connection alive for 3 more seconds...")
            time_module.sleep(3)
    except Exception as e:
        print(f"\nError: {e}")
        import traceback
        traceback.print_exc()
    finally:
        print("Disconnecting...")
        client.disconnect()


if __name__ == "__main__":
    main()
