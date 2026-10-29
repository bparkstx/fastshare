#!/usr/bin/env python3
"""Writes a tiny RAR 4.x archive that stores two files without compression (for the extraction tests).

Layout (all little endian): marker, main header, one file header + data per file, end-of-archive block.
Header CRC is the low 16 bits of CRC32 over the header bytes after the CRC field.
"""
import struct, sys, zlib

def hdr(body):
    return struct.pack("<H", zlib.crc32(body) & 0xFFFF) + body

def file_block(name, data, dos_time=0x5A2F6000):
    name_b = name.encode()
    crc = zlib.crc32(data) & 0xFFFFFFFF
    head_size = 32 + len(name_b)
    body = struct.pack("<BHH", 0x74, 0x8000, head_size)          # type, flags (has ADD_SIZE), size
    body += struct.pack("<II", len(data), len(data))               # packed size, unpacked size
    body += struct.pack("<BIIBBHI", 3, crc, dos_time, 20, 0x30, len(name_b), 0x81A4)  # host OS unix, crc, time, version, method=store, name len, attrs
    body += name_b
    return hdr(body) + data

out = b"Rar!\x1a\x07\x00"
out += hdr(struct.pack("<BHHHI", 0x73, 0, 13, 0, 0))              # main header
out += file_block("hello.txt", b"hello from rar\n")
out += file_block("docs/readme.md", b"# readme\nsecond file\n")
out += bytes.fromhex("c43d7b00400700")                              # end of archive
open(sys.argv[1], "wb").write(out)
print("wrote", sys.argv[1], len(out), "bytes")
