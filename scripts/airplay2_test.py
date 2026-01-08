#!/usr/bin/env python3
"""
AirPlay 2 Protocol Testing Script
Tests pair-setup against shairport-sync

Usage:
    python3 airplay2_test.py <host> [port]
    
Example:
    python3 airplay2_test.py 192.168.1.220 7000
"""

import socket
import sys
import struct
from typing import Dict, List, Tuple, Optional

# TLV8 Types for Pair-Setup (HomeKit Accessory Protocol)
TLV_METHOD = 0x00
TLV_IDENTIFIER = 0x01  
TLV_SALT = 0x02
TLV_PUBLIC_KEY = 0x03
TLV_PROOF = 0x04
TLV_ENCRYPTED_DATA = 0x05
TLV_STATE = 0x06
TLV_ERROR = 0x07
TLV_RETRY_DELAY = 0x08
TLV_CERTIFICATE = 0x09
TLV_SIGNATURE = 0x0A
TLV_PERMISSIONS = 0x0B
TLV_FRAGMENT_DATA = 0x0C
TLV_FRAGMENT_LAST = 0x0D
TLV_FLAGS = 0x13
TLV_SEPARATOR = 0xFF

# Methods
METHOD_PAIR_SETUP = 0x00
METHOD_PAIR_SETUP_AUTH = 0x01
METHOD_PAIR_VERIFY = 0x02

# Errors
ERROR_UNKNOWN = 0x01
ERROR_AUTHENTICATION = 0x02
ERROR_BACKOFF = 0x03
ERROR_MAX_PEERS = 0x04
ERROR_MAX_TRIES = 0x05
ERROR_UNAVAILABLE = 0x06
ERROR_BUSY = 0x07


def tlv8_encode(items: List[Tuple[int, bytes]]) -> bytes:
    """Encode list of (type, value) tuples to TLV8 format"""
    result = bytearray()
    for tlv_type, value in items:
        if isinstance(value, int):
            value = bytes([value])
        elif isinstance(value, str):
            value = value.encode('utf-8')
        
        # TLV8 allows max 255 bytes per chunk
        offset = 0
        while offset < len(value):
            chunk = value[offset:offset + 255]
            result.append(tlv_type)
            result.append(len(chunk))
            result.extend(chunk)
            offset += 255
        
        # Handle empty values
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
        
        # Concatenate fragments of same type
        if tlv_type in result:
            result[tlv_type] += value
        else:
            result[tlv_type] = value
    
    return result


def send_http_request(sock: socket.socket, method: str, path: str, body: bytes, 
                      content_type: str = "application/octet-stream",
                      host: str = "localhost", port: int = 7000) -> Tuple[int, Dict[str, str], bytes]:
    """Send HTTP request and return (status_code, headers, body)"""
    
    headers = [
        f"{method} {path} HTTP/1.1",
        f"Host: {host}:{port}",
        f"Content-Type: {content_type}",
        f"Content-Length: {len(body)}",
        "Connection: keep-alive",
        "User-Agent: AirPlay/670.6.4",
        "",
        ""
    ]
    
    request = "\r\n".join(headers).encode('utf-8') + body
    
    print(f"\n{'='*60}")
    print(f"REQUEST: {method} {path}")
    print(f"Content-Type: {content_type}")
    print(f"Body ({len(body)} bytes): {body.hex()}")
    print(f"{'='*60}")
    
    sock.sendall(request)
    
    # Read response
    response = b""
    while True:
        chunk = sock.recv(4096)
        if not chunk:
            break
        response += chunk
        
        # Check if we have complete headers
        if b"\r\n\r\n" in response:
            header_end = response.index(b"\r\n\r\n")
            header_part = response[:header_end].decode('utf-8')
            
            # Parse content-length
            content_length = 0
            for line in header_part.split("\r\n"):
                if line.lower().startswith("content-length:"):
                    content_length = int(line.split(":")[1].strip())
                    break
            
            # Check if we have full body
            body_start = header_end + 4
            if len(response) >= body_start + content_length:
                break
    
    # Parse response
    header_end = response.index(b"\r\n\r\n")
    header_lines = response[:header_end].decode('utf-8').split("\r\n")
    
    # Status code
    status_line = header_lines[0]
    status_code = int(status_line.split()[1])
    
    # Headers
    resp_headers = {}
    for line in header_lines[1:]:
        if ":" in line:
            key, value = line.split(":", 1)
            resp_headers[key.strip()] = value.strip()
    
    # Body
    resp_body = response[header_end + 4:]
    
    print(f"\nRESPONSE: {status_code}")
    print(f"Headers: {resp_headers}")
    print(f"Body ({len(resp_body)} bytes): {resp_body.hex()}")
    
    if resp_body:
        decoded = tlv8_decode(resp_body)
        print(f"TLV8 Decoded:")
        for k, v in decoded.items():
            print(f"  Type 0x{k:02X}: {v.hex()} ({len(v)} bytes)")
    
    return status_code, resp_headers, resp_body


def test_pair_setup_m1(host: str, port: int = 7000):
    """Test M1 of pair-setup"""
    
    print(f"\nConnecting to {host}:{port}...")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((host, port))
    print("Connected!")
    
    # M1: State=0x01, Method=0x00 (Pair-Setup)
    # This is the simplest form that should work
    m1_tlv = tlv8_encode([
        (TLV_STATE, bytes([0x01])),
        (TLV_METHOD, bytes([METHOD_PAIR_SETUP])),
    ])
    
    print(f"\nM1 TLV8: {m1_tlv.hex()}")
    print(f"  State: 0x01 (M1)")
    print(f"  Method: 0x00 (Pair-Setup)")
    
    status, headers, body = send_http_request(
        sock, "POST", "/pair-setup", m1_tlv,
        content_type="application/octet-stream",
        host=host, port=port
    )
    
    if status == 200:
        print("\n✓ M1 SUCCESS!")
        decoded = tlv8_decode(body)
        if TLV_STATE in decoded:
            print(f"  Server State: 0x{decoded[TLV_STATE].hex()}")
        if TLV_SALT in decoded:
            print(f"  Salt: {decoded[TLV_SALT].hex()} ({len(decoded[TLV_SALT])} bytes)")
        if TLV_PUBLIC_KEY in decoded:
            print(f"  Public Key B: {len(decoded[TLV_PUBLIC_KEY])} bytes")
    else:
        print(f"\n✗ M1 FAILED with status {status}")
        if body:
            decoded = tlv8_decode(body)
            if TLV_ERROR in decoded:
                error_code = decoded[TLV_ERROR][0] if decoded[TLV_ERROR] else 0
                print(f"  Error Code: 0x{error_code:02X}")
    
    sock.close()
    return status == 200


def test_info_endpoint(host: str, port: int = 7000):
    """Test /info endpoint to see what's available"""
    
    print(f"\nTesting /info endpoint...")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((host, port))
    
    status, headers, body = send_http_request(
        sock, "GET", "/info", b"",
        content_type="application/x-apple-binary-plist",
        host=host, port=port
    )
    
    print(f"\n/info response: {status}")
    if body:
        # Try to decode as plist or just show raw
        print(f"Body (raw): {body[:200]}...")
    
    sock.close()


def test_with_different_content_types(host: str, port: int = 7000):
    """Try different content types to see what shairport accepts"""
    
    content_types = [
        "application/octet-stream",
        "application/x-apple-binary-plist",
        "application/pairing+tlv8",
    ]
    
    m1_tlv = tlv8_encode([
        (TLV_STATE, bytes([0x01])),
        (TLV_METHOD, bytes([METHOD_PAIR_SETUP])),
    ])
    
    for ct in content_types:
        print(f"\n{'='*60}")
        print(f"Testing Content-Type: {ct}")
        print(f"{'='*60}")
        
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(5)
            sock.connect((host, port))
            
            status, headers, body = send_http_request(
                sock, "POST", "/pair-setup", m1_tlv,
                content_type=ct,
                host=host, port=port
            )
            
            print(f"Result: {status}")
            sock.close()
        except Exception as e:
            print(f"Error: {e}")


def test_rtsp_style(host: str, port: int = 7000):
    """Try RTSP-style request (shairport might expect this)"""
    
    print(f"\n{'='*60}")
    print("Testing RTSP-style request")
    print(f"{'='*60}")
    
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((host, port))
    
    m1_tlv = tlv8_encode([
        (TLV_STATE, bytes([0x01])),
        (TLV_METHOD, bytes([METHOD_PAIR_SETUP])),
    ])
    
    # RTSP-style with CSeq
    headers = [
        "POST /pair-setup RTSP/1.0",
        f"CSeq: 1",
        f"Host: {host}:{port}",
        f"Content-Type: application/octet-stream",
        f"Content-Length: {len(m1_tlv)}",
        "",
        ""
    ]
    
    request = "\r\n".join(headers).encode('utf-8') + m1_tlv
    print(f"Request:\n{headers}")
    
    sock.sendall(request)
    
    response = sock.recv(4096)
    print(f"Response: {response}")
    
    sock.close()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 airplay2_test.py <host> [port]")
        sys.exit(1)
    
    host = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 7000
    
    print(f"AirPlay 2 Protocol Test")
    print(f"Target: {host}:{port}")
    
    # Test info endpoint first
    try:
        test_info_endpoint(host, port)
    except Exception as e:
        print(f"Info test error: {e}")
    
    # Test M1 with standard HTTP
    try:
        test_pair_setup_m1(host, port)
    except Exception as e:
        print(f"M1 test error: {e}")
    
    # Try different content types
    try:
        test_with_different_content_types(host, port)
    except Exception as e:
        print(f"Content-type test error: {e}")
    
    # Try RTSP style
    try:
        test_rtsp_style(host, port)
    except Exception as e:
        print(f"RTSP test error: {e}")
