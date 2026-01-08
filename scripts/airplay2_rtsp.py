#!/usr/bin/env python3
"""
AirPlay 2 RTSP Protocol Implementation
Works with shairport-sync AirPlay 2 mode

Key Discovery: AirPlay 2 on port 7000 uses RTSP/1.0, NOT HTTP/1.1!

Usage:
    python3 airplay2_rtsp.py <host> [port]
"""

import socket
import sys
import hashlib
import os
from typing import Dict, List, Tuple, Optional

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


def tlv8_encode(items: List[Tuple[int, bytes]]) -> bytes:
    """Encode list of (type, value) tuples to TLV8 format"""
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
    """Decode TLV8 data to dictionary"""
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
    """AirPlay 2 client using RTSP protocol"""
    
    def __init__(self, host: str, port: int = 7000):
        self.host = host
        self.port = port
        self.sock = None
        self.cseq = 0
        
    def connect(self):
        """Connect to AirPlay 2 receiver"""
        print(f"Connecting to {self.host}:{self.port}...")
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.settimeout(10)
        self.sock.connect((self.host, self.port))
        print("Connected!")
        
    def disconnect(self):
        """Close connection"""
        if self.sock:
            self.sock.close()
            self.sock = None
            
    def send_rtsp(self, method: str, path: str, body: bytes = b"", 
                  content_type: str = "application/octet-stream") -> Tuple[int, Dict[str, str], bytes]:
        """Send RTSP request and return response"""
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
        
        print(f"\n{'='*60}")
        print(f">>> {method} {path} (CSeq: {self.cseq})")
        print(f"    Body: {len(body)} bytes")
        if body and len(body) < 100:
            print(f"    Hex: {body.hex()}")
        
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
        
        # Parse response
        header_end = response.index(b"\r\n\r\n")
        header_lines = response[:header_end].decode('utf-8').split("\r\n")
        
        status_line = header_lines[0]
        status_code = int(status_line.split()[1])
        
        resp_headers = {}
        for line in header_lines[1:]:
            if ":" in line:
                key, value = line.split(":", 1)
                resp_headers[key.strip()] = value.strip()
        
        resp_body = response[header_end + 4:]
        
        print(f"<<< {status_code}")
        print(f"    Body: {len(resp_body)} bytes")
        
        return status_code, resp_headers, resp_body
    
    def pair_setup_m1(self) -> Tuple[bool, bytes]:
        """
        M1: Client -> Server
        Send: State=0x01, Method=0x00
        Expect: State=0x02, Salt, PublicKey(B)
        """
        m1 = tlv8_encode([
            (TLV_STATE, bytes([0x01])),
            (TLV_METHOD, bytes([METHOD_PAIR_SETUP])),
        ])
        
        status, headers, body = self.send_rtsp("POST", "/pair-setup", m1)
        
        if status != 200:
            print(f"M1 failed: {status}")
            return False, b""
        
        decoded = tlv8_decode(body)
        print(f"\nM2 Response:")
        
        if TLV_STATE in decoded:
            print(f"  State: 0x{decoded[TLV_STATE].hex()}")
        if TLV_SALT in decoded:
            print(f"  Salt: {len(decoded[TLV_SALT])} bytes")
            print(f"        {decoded[TLV_SALT].hex()}")
        if TLV_PUBLIC_KEY in decoded:
            print(f"  PublicKey B: {len(decoded[TLV_PUBLIC_KEY])} bytes")
            print(f"        {decoded[TLV_PUBLIC_KEY][:32].hex()}...")
        if TLV_ERROR in decoded:
            print(f"  ERROR: 0x{decoded[TLV_ERROR].hex()}")
            return False, body
            
        return True, body
    
    def pair_setup_m3(self, m2_data: bytes, pin: str = "3939") -> Tuple[bool, bytes]:
        """
        M3: Client -> Server
        Need to implement SRP-6a to generate A and proof M1
        
        For now, this is a placeholder - real implementation needs:
        1. Parse salt and B from M2
        2. Generate client credentials A using SRP-6a
        3. Calculate shared secret S
        4. Generate proof M1
        5. Send State=0x03, PublicKey(A), Proof(M1)
        """
        print("\nM3 requires SRP-6a implementation...")
        print(f"PIN to use: {pin}")
        
        # TODO: Implement SRP-6a
        # For testing, we can try sending dummy data to see what error we get
        
        return False, b""


def test_pair_setup(host: str, port: int = 7000):
    """Test the pair-setup flow"""
    client = AirPlay2Client(host, port)
    
    try:
        client.connect()
        
        # M1
        success, m2_data = client.pair_setup_m1()
        if not success:
            print("Pair-Setup M1 failed")
            return
        
        print("\n✓ M1 -> M2 successful!")
        print("\nNext step: Implement SRP-6a for M3")
        
        # Would continue with M3 here...
        # success, m4_data = client.pair_setup_m3(m2_data, "3939")
        
    finally:
        client.disconnect()


def test_info(host: str, port: int = 7000):
    """Test /info endpoint"""
    client = AirPlay2Client(host, port)
    
    try:
        client.connect()
        status, headers, body = client.send_rtsp("GET", "/info", b"")
        print(f"\n/info: {status}")
        if body:
            # It's a binary plist
            print(f"Body type: {headers.get('Content-Type', 'unknown')}")
            print(f"Body preview: {body[:100]}")
    finally:
        client.disconnect()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 airplay2_rtsp.py <host> [port]")
        sys.exit(1)
    
    host = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 7000
    
    print("=" * 60)
    print("AirPlay 2 RTSP Protocol Test")
    print(f"Target: {host}:{port}")
    print("=" * 60)
    
    # Test info
    print("\n--- Testing /info ---")
    try:
        test_info(host, port)
    except Exception as e:
        print(f"Error: {e}")
    
    # Test pair-setup
    print("\n--- Testing /pair-setup ---")
    try:
        test_pair_setup(host, port)
    except Exception as e:
        print(f"Error: {e}")
