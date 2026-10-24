#!/usr/bin/env python3
"""Builds an AVI whose MP3 is a byte stream cut across frame boundaries, as old XviD rips are.

ffmpeg writes one MP3 frame per chunk, which every player handles; files muxed by older tools
store MP3 as a byte stream (nBlockAlign 1, dwSampleSize 1) in chunks that straddle frames, often
behind a zeroed first frame. Media3's AVI extractor hands each chunk to the decoder whole, and
Android's MP3 decoder rejects it (report 0.1.709, Da Ali G Show AVIs). This makes a copyright-free
file of that shape: ffmpeg's test pattern and a sine tone, re-chunked.

    tools/avi/unaligned-mp3-avi.py OUT.avi [SECONDS]
"""
import os
import struct
import subprocess
import sys
import tempfile

CHUNK_SIZES = [784, 356, 724, 357, 685, 365, 825, 419, 831, 842, 417, 835, 414, 837, 417, 837]
LEADING_ZEROS = 417
BYTES_PER_SECOND = 16_000
FPS = 25


def chunks(data, start, end):
    pos = start
    while pos < end - 8:
        cid = data[pos:pos + 4]
        size = struct.unpack('<I', data[pos + 4:pos + 8])[0]
        if cid == b'LIST':
            yield cid, data[pos + 8:pos + 12], pos
            pos += 12
            continue
        yield cid, size, pos
        pos += 8 + size + (size & 1)


def riff(fourcc, payload):
    pad = b'\0' if len(payload) & 1 else b''
    return fourcc + struct.pack('<I', len(payload)) + payload + pad


def main(out, seconds):
    with tempfile.TemporaryDirectory() as tmp:
        clean = os.path.join(tmp, 'clean.avi')
        subprocess.run(
            ['ffmpeg', '-hide_banner', '-v', 'error', '-y',
             '-f', 'lavfi', '-i', f'testsrc=size=160x120:rate={FPS}',
             '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100',
             '-ac', '2', '-t', str(seconds), '-c:v', 'mpeg4', '-q:v', '12',
             '-c:a', 'libmp3lame', '-b:a', '128k', '-ar', '44100', clean],
            check=True,
        )
        data = open(clean, 'rb').read()

    hdrl_at = data.index(b'hdrl') - 8
    hdrl_size = struct.unpack('<I', data[hdrl_at + 4:hdrl_at + 8])[0]
    hdrl = bytearray(data[hdrl_at:hdrl_at + 8 + hdrl_size])
    movi_at = data.index(b'movi') - 8
    movi_end = movi_at + 8 + struct.unpack('<I', data[movi_at + 4:movi_at + 8])[0]

    video, audio = [], b''
    for cid, size, pos in chunks(data, movi_at + 12, movi_end):
        if cid == b'LIST':
            continue
        body = data[pos + 8:pos + 8 + size]
        if cid[2:] in (b'dc', b'db'):
            video.append((cid, body))
        elif cid[2:] == b'wb':
            audio += body
    keyframes = set()
    idx_at = data.find(b'idx1', movi_end)
    n_video = 0
    for i in range(struct.unpack('<I', data[idx_at + 4:idx_at + 8])[0] // 16):
        cid, flags, _, _ = struct.unpack('<4sIII', data[idx_at + 8 + i * 16:idx_at + 24 + i * 16])
        if cid[2:] in (b'dc', b'db'):
            if flags & 0x10:
                keyframes.add(n_video)
            n_video += 1

    stream = bytes(LEADING_ZEROS) + audio
    strh = hdrl.index(b'auds') - 8
    struct.pack_into('<I', hdrl, strh + 8 + 20, 1)
    struct.pack_into('<I', hdrl, strh + 8 + 24, BYTES_PER_SECOND)
    struct.pack_into('<I', hdrl, strh + 8 + 32, len(stream))
    struct.pack_into('<I', hdrl, strh + 8 + 44, 1)
    strf = hdrl.index(b'strf', strh)
    struct.pack_into('<I', hdrl, strf + 8 + 8, BYTES_PER_SECOND)
    struct.pack_into('<H', hdrl, strf + 8 + 12, 1)

    movi = bytearray(b'movi')
    index = []
    sent = 0
    sizes = iter(CHUNK_SIZES * (len(stream) // 300 + 1))

    def add(cid, body, flags):
        index.append(struct.pack('<4sIII', cid, flags, len(movi), len(body)))
        movi.extend(riff(cid, body))

    for i, (cid, body) in enumerate(video):
        add(cid, body, 0x10 if i in keyframes else 0)
        while sent < len(stream) and sent < (i + 1) * BYTES_PER_SECOND // FPS + LEADING_ZEROS:
            piece = stream[sent:sent + next(sizes)]
            add(b'01wb', piece, 0x10)
            sent += len(piece)
    while sent < len(stream):
        piece = stream[sent:sent + next(sizes)]
        add(b'01wb', piece, 0x10)
        sent += len(piece)

    body = b'AVI ' + bytes(hdrl) + riff(b'LIST', bytes(movi)) + riff(b'idx1', b''.join(index))
    with open(out, 'wb') as f:
        f.write(b'RIFF' + struct.pack('<I', len(body)) + body)
    print(f'{out}: {len(video)} video frames, {len(stream)} audio bytes in {sent and len(index) - len(video)} chunks, '
          f'{LEADING_ZEROS} leading zero bytes, {os.path.getsize(out)} bytes')


if __name__ == '__main__':
    main(sys.argv[1], float(sys.argv[2]) if len(sys.argv) > 2 else 6)
