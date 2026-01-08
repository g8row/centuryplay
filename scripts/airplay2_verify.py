#!/usr/bin/env python3
"""
AirPlay 2 Pair-Verify Test

After transient pair-setup completes, we need pair-verify to establish
the encrypted control channel.

Pair-Verify Flow:
1. V1: Client sends State=0x01 + X25519 ephemeral public key (32 bytes)
2. V2: Server sends State=0x02 + X25519 ephemeral public key + encrypted data
3. V3: Client sends State=0x03 + encrypted signature
4. V4: Server confirms State=0x04

Usage:
    python3 airplay2_verify.py <host> [port]
"""

import socket
import sys
import hashlib
import os
from typing import Dict, Tuple

# Try to import cryptography for X25519
try:
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
    from cryptography.hazmat.primitives import serialization
    HAS_CRYPTO = True
except ImportError:
    HAS_CRYPTO = False
    print("Warning: cryptography not installed, using dummy keys")

# TLV8 Types
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


def tlv8_encode(items: list) -> bytes:
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
        self.cseq += 1
        
        headers = [
            f"{method} {path} RTSP/1.0",
            f"CSeq: {self.cseq}",
            f"Host: {self.host}:{self.port}",
            f"User-Agent: AirPlay/320.20",
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


def pair_verify(client: AirPlay2Client):
    """
    Pair-Verify: Establish encrypted control channel
    
    V1: Client sends X25519 ephemeral public key
    V2: Server responds with encrypted challenge
    V3: Client sends encrypted response
    V4: Server confirms
    """
    print(f"\n{'='*60}")
    print("Pair-Verify: Establishing encrypted channel")
    print(f"{'='*60}")
    
    # Generate X25519 ephemeral keypair
    if HAS_CRYPTO:
        private_key = X25519PrivateKey.generate()
        public_key_bytes = private_key.public_key().public_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PublicFormat.Raw
        )
    else:
        # Dummy 32-byte key for testing
        public_key_bytes = os.urandom(32)
    
    print(f"Client ephemeral public key: {public_key_bytes.hex()[:32]}...")
    
    # V1: Send ephemeral public key
    v1_items = [
        (TLV_STATE, bytes([0x01])),
        (TLV_PUBLIC_KEY, public_key_bytes),
    ]
    v1_data = tlv8_encode(v1_items)
    print(f"V1 TLV8: {v1_data.hex()}")
    
    status, headers, body = client.send_rtsp("POST", "/pair-verify", v1_data)
    
    if status != 200:
        print(f"V1 failed: {status}")
        return False
    
    # Parse V2 response
    v2 = tlv8_decode(body)
    print(f"V2 TLV types: {list(hex(t) for t in v2.keys())}")
    
    if TLV_ERROR in v2:
        error_code = v2[TLV_ERROR][0] if v2[TLV_ERROR] else 0
        print(f"V2 ERROR: 0x{error_code:02x}")
        return False
    
    state = v2.get(TLV_STATE, b'')[0] if TLV_STATE in v2 else 0
    if state != 0x02:
        print(f"Expected state 0x02, got {hex(state)}")
    
    server_pk = v2.get(TLV_PUBLIC_KEY, b'')
    encrypted_data = v2.get(TLV_ENCRYPTED_DATA, b'')
    
    print(f"Server ephemeral public key ({len(server_pk)} bytes): {server_pk.hex()[:32]}...")
    print(f"Encrypted data ({len(encrypted_data)} bytes): {encrypted_data.hex()[:32]}...")
    
    # For full implementation, we would:
    # 1. Compute shared secret: X25519(our_private, server_public)
    # 2. Derive key: HKDF-SHA512(shared_secret, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
    # 3. Decrypt the encrypted_data using ChaCha20-Poly1305
    # 4. Verify server signature
    # 5. Send V3 with our encrypted signature
    
    print("\n⚠ Full pair-verify not implemented yet - need to:")
    print("  1. Compute X25519 shared secret")
    print("  2. Derive encryption key with HKDF")
    print("  3. Decrypt server data and verify signature")
    print("  4. Send encrypted client signature")
    
    return True


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 airplay2_verify.py <host> [port]")
        sys.exit(1)
    
    host = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 7000
    
    client = AirPlay2Client(host, port)
    
    try:
        client.connect()
        
        # First do pair-setup (transient) - inline to use our client
        from airplay2_srp import SRP6aClient, tlv8_encode as srp_tlv8_encode, tlv8_decode as srp_tlv8_decode
        
        pin = "3939"
        identity = b"Pair-Setup"
        password = pin.encode('utf-8')
        
        print(f"\n{'='*60}")
        print(f"SRP-6a Pair-Setup (transient=True, pin={pin})")
        print(f"{'='*60}")
        
        srp = SRP6aClient(identity, password)
        
        # M1
        m1_items = [
            (TLV_STATE, bytes([0x01])),
            (TLV_METHOD, bytes([METHOD_PAIR_SETUP])),
            (TLV_FLAGS, bytes([FLAGS_TRANSIENT])),
        ]
        m1_data = tlv8_encode(m1_items)
        status, headers, body = client.send_rtsp("POST", "/pair-setup", m1_data)
        
        if status != 200:
            raise Exception(f"M1 failed: {status}")
        
        m2 = tlv8_decode(body)
        salt = m2.get(TLV_SALT, b'')
        B_bytes = m2.get(TLV_PUBLIC_KEY, b'')
        B = int.from_bytes(B_bytes, 'big')
        
        print(f"M2: Salt={len(salt)} bytes, B={len(B_bytes)} bytes")
        
        # M3
        A_bytes, M1_proof = srp.process_challenge(salt, B)
        
        m3_items = [
            (TLV_STATE, bytes([0x03])),
            (TLV_PUBLIC_KEY, A_bytes),
            (TLV_PROOF, M1_proof),
        ]
        m3_data = tlv8_encode(m3_items)
        status, headers, body = client.send_rtsp("POST", "/pair-setup", m3_data)
        
        if status != 200:
            raise Exception(f"M3 failed: {status}")
        
        m4 = tlv8_decode(body)
        if TLV_ERROR in m4:
            raise Exception(f"M4 error: {m4[TLV_ERROR].hex()}")
        
        print("✓ Pair-Setup successful!")
        
        # Now do pair-verify on same connection
        pair_verify(client)
        
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
    finally:
        client.disconnect()


if __name__ == "__main__":
    main()
