#!/usr/bin/env python3
"""Build the book's original pixel materials (no image libraries or generated art required)."""
import pathlib
import random
import struct
import zlib


def encode_png(width, height, pixels):
    def chunk(tag, data):
        return struct.pack('!I', len(data)) + tag + data + struct.pack('!I', zlib.crc32(tag + data))

    raw = b''.join(b'\0' + pixels[y * width * 4:(y + 1) * width * 4] for y in range(height))
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', width, height, 8, 6, 0, 0, 0))
    return png + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')


def build():
    rng = random.Random(721946)
    size = 256
    pixels = bytearray(size * size * 4)

    def pixel(x, y, color):
        start = (y * size + x) * 4
        pixels[start:start + 4] = bytes((*color, 255))

    # The middle of each nine-slice is deliberately quiet; grain never competes with text.
    for y in range(128):
        for x in range(128):
            edge = min(x, y, 127 - x, 127 - y)
            wear = max(0, 6 - edge) * 2
            grain = rng.choices([-2, -1, 0, 1, 2], [1, 3, 18, 3, 1])[0]
            pixel(x, y, (242 - wear + grain, 230 - wear + grain, 201 - wear + grain))
    # Three bindings, sampled from one atlas: royal oxblood, field-ledger green, working walnut.
    for ox, oy, base in [(128, 0, (76, 33, 38)), (192, 0, (35, 65, 52)), (128, 64, (68, 44, 32))]:
        for y in range(64):
            for x in range(64):
                edge = min(x, y, 63 - x, 63 - y)
                grain = rng.choice([-2, 0, 0, 0, 2])
                shade = -14 if edge < 2 else 5 if edge == 3 else 0
                pixel(ox + x, oy + y, tuple(c + grain + shade for c in base))
    # Small repeatable leather control tiles. White text stays readable even at the brightest grain.
    # Inactive controls are flat; selected controls get a separate green tile, never the disabled tile.
    for state, base in enumerate([(79, 55, 46), (102, 67, 52), (91, 84, 74), (38, 73, 60)]):
        for y in range(32):
            for x in range(64):
                edge = min(x, y, 63 - x, 31 - y)
                grain = rng.choice([-1, 0, 0, 0, 1])
                color = tuple(c + grain for c in base)
                if edge == 0:
                    color = (150, 120, 75) if state != 2 else (125, 113, 94)
                elif edge == 1 and state != 2:
                    color = tuple(c + (17 if y < 2 or x < 2 else -17) for c in base)
                pixel(state * 64 + x, 128 + y, color)

    png = encode_png(size, size, pixels)
    textures = pathlib.Path(__file__).resolve().parents[2] / 'src/main/resources/assets/mcakingdoms/textures'
    target = textures / 'gui/book/materials.png'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(png)
    print(f'{target}: {len(png)} bytes')

    # Native 16px closed books: diagonal covers, two visible page edges and a lower leather lip.
    # Each cover has its own ornament: a royal crown, or a compass over a leather ledger strap.
    covers = {
        'book_of_kingdoms': [
            '                ',
            '        ooo     ',
            '      oohhdo    ',
            '    oohhcccdo   ',
            '  oohhgcGcgddo  ',
            'oohhhcgggggcddo ',
            'ohhccccGGgccgcdo',
            'oocccccccccccoq ',
            'oqPccccccccgqPqo',
            'odqPcccccoqGPqdo',
            ' odqPccoqPPqdsoo',
            '  odqPPPPqdsoo  ',
            '   odqPqdsoo    ',
            '    odrsoo      ',
            '     oro        ',
            '                ',
        ],
        'village_ledger': [
            '                ',
            '        ooo     ',
            '      oohhdo    ',
            '    oohhgccdo   ',
            '  oohhcgrgctdo  ',
            'oohhccgPGPgcddo ',
            'ohhccccgPgccccdo',
            'ooccccccgccccoq ',
            'oqPcccctcccGqPqo',
            'odqPcctccoqgPqdo',
            ' odqPccoqPPqdsoo',
            '  odqPPPPqdsoo  ',
            '   odqPqdsoo    ',
            '    odssoo      ',
            '     ooo        ',
            '                ',
        ],
    }
    themes = {
        'book_of_kingdoms': {'c': (121, 45, 65), 'h': (173, 83, 101), 'd': (83, 33, 48), 's': (61, 30, 39)},
        'village_ledger': {'c': (51, 89, 72), 'h': (104, 142, 113), 'd': (34, 62, 51), 's': (26, 43, 37)},
    }
    for name, cover in covers.items():
        palette = {' ': (0, 0, 0, 0), 'o': (40, 24, 28, 255), 'g': (196, 148, 65, 255),
                   'G': (249, 214, 130, 255), 'P': (255, 243, 216, 255), 'q': (160, 147, 126, 255),
                   'r': (173, 52, 66, 255), 't': (113, 77, 48, 255)}
        palette.update({key: (*color, 255) for key, color in themes[name].items()})
        assert len(cover) == 16 and all(len(row) == 16 for row in cover), name
        item = bytearray(channel for row in cover for char in row for channel in palette[char])
        target = textures / 'item' / f'{name}.png'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(encode_png(16, 16, item))
        print(f'{target}: {target.stat().st_size} bytes')


if __name__ == '__main__':
    build()
