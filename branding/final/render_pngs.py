"""Export only the Play Store icon: full-square, opaque 512px RGBA sRGB PNG.

Requires Pillow and Chromium/Edge. Use --check to compare a fresh render with
the committed PNG without overwriting it. Android resources are never touched.
"""
import argparse
import hashlib
import io
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from PIL import Image, PngImagePlugin

FINAL_DIR = Path(__file__).resolve().parent
EDGE_EXE = os.environ.get('CABINRTA_BROWSER', r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe')
OUTPUT = FINAL_DIR / 'ic_launcher-playstore-512.png'


def verify_png(data):
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('Not a PNG')
    width, height, depth, color_type = struct.unpack('>IIBB', data[16:26])
    if (width, height, depth, color_type) != (512, 512, 8, 6):
        raise ValueError('Expected 512x512, 8-bit RGBA PNG (color type 6)')
    if len(data) > 1024 * 1024:
        raise ValueError('Play Store icon exceeds 1 MiB')
    with Image.open(io.BytesIO(data)) as image:
        image.load()
        if image.mode != 'RGBA' or image.getchannel('A').getextrema() != (255, 255):
            raise ValueError('All pixels must have alpha 255')
        if image.info.get('srgb') != 0:
            raise ValueError('Expected explicit sRGB perceptual rendering intent')
        corners = [image.getpixel(p) for p in [(0, 0), (511, 0), (0, 511), (511, 511)]]
        if any(p[:3] == (0, 0, 0) for p in corners):
            raise ValueError('Black corner padding is forbidden')
    return {'dimensions': [width, height], 'bit_depth': depth, 'color_type': color_type,
            'alpha_range': [255, 255], 'sRGB_intent': 0, 'corners_RGBA': corners,
            'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def render():
    source = (FINAL_DIR / 'cabinrta-master.svg').read_text(encoding='utf-8')
    svg = ET.fromstring(source)
    background = svg.find('{http://www.w3.org/2000/svg}rect')
    if svg.get('viewBox') != '0 0 512 512' or background is None:
        raise ValueError('Missing 512x512 master/background')
    if any(background.get(k, '0') != '0' for k in ['rx', 'ry', 'x', 'y']):
        raise ValueError('Background must cover the square with no corner rounding')
    if (background.get('width'), background.get('height'), background.get('fill')) != ('512', '512', 'url(#master-bg)'):
        raise ValueError('Expected full-square master gradient background')
    html = f'''<!DOCTYPE html><html><head><meta charset="utf-8"><style>
    * {{ margin:0; padding:0; }}
    html,body {{ width:512px; height:512px; overflow:hidden; }}
    svg {{ width:512px; height:512px; display:block; }}
    </style></head><body>{source}</body></html>'''
    with tempfile.TemporaryDirectory(prefix='cabinrta-play-') as temp:
        directory = Path(temp)
        wrapper = directory / 'icon.html'
        screenshot = directory / 'icon.png'
        wrapper.write_text(html, encoding='utf-8')
        subprocess.run([EDGE_EXE, '--headless', '--disable-gpu', '--no-first-run',
                        '--no-default-browser-check', '--force-color-profile=srgb',
                        '--force-device-scale-factor=1', '--hide-scrollbars',
                        '--window-size=512,512', '--virtual-time-budget=1000',
                        f'--user-data-dir={directory / "profile"}',
                        f'--screenshot={screenshot}', wrapper.as_uri()],
                       check=True, capture_output=True, timeout=60)
        with Image.open(screenshot) as image:
            if image.size != (512, 512):
                raise ValueError(f'Unexpected screenshot dimensions: {image.size}')
            rgba = image.convert('RGBA')
            if rgba.getchannel('A').getextrema() != (255, 255):
                raise ValueError('Master SVG did not render fully opaque')
            # Retain four 8-bit channels with explicit sRGB color-space metadata.
            metadata = PngImagePlugin.PngInfo()
            metadata.add(b'sRGB', b'\x00')
            encoded = io.BytesIO()
            rgba.save(encoded, format='PNG', optimize=True, pnginfo=metadata)
    data = encoded.getvalue()
    verify_png(data)
    return data


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Fail if regeneration differs from the committed PNG.')
    args = parser.parse_args()
    result = render()
    if args.check:
        if result != OUTPUT.read_bytes():
            raise SystemExit('Regeneration differs from the committed Play Store PNG')
        print('Regeneration is byte-for-byte identical.')
    else:
        OUTPUT.write_bytes(result)
    print(verify_png(result))
