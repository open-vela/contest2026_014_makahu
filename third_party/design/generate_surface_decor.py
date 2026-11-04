"""Compile the user's SVG background gradients into static native RGB565 fills.

Only background paint is sampled; layout, glyphs, text and interaction stay
native LVGL. Full reference resolution avoids runtime rasterization/allocation.
"""
from pathlib import Path
import hashlib
import json
import math
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
patterns = [('session', 'Assistant Session Timer.svg'), ('listening', 'Assistant Listening.svg')]
lines = ['/* Generated from user SVG gradients by generate_surface_decor.py. */']
metadata = []
bayer = ((0, 8, 2, 10), (12, 4, 14, 6), (3, 11, 1, 9), (15, 7, 13, 5))
for name, file in patterns:
    source = root / '.validation/design' / file
    svg = ET.parse(source).getroot()
    gradients = []
    for element in svg.iter():
        kind = element.tag.split('}')[-1]
        if kind == 'linearGradient':
            gradients.append(('linear',))
        elif kind == 'radialGradient':
            transform = element.get('gradientTransform')
            values = [float(value) for value in re.findall(r'-?\d+(?:\.\d+)?', transform)]
            gradients.append(('radial', *values))
    assert len(gradients) == 3
    pixels = bytearray()
    for y in range(203):
        for x in range(320):
            color = [246.0, 250.0, 246.0]
            for gradient in gradients:
                if gradient[0] == 'linear':
                    alpha = 0.1 * (y + 0.5) / 203
                else:
                    _, cx, cy, angle, sx, sy = gradient
                    angle = math.radians(angle)
                    dx, dy = x + 0.5 - cx, y + 0.5 - cy
                    px = (dx * math.cos(angle) + dy * math.sin(angle)) / sx
                    py = (-dx * math.sin(angle) + dy * math.cos(angle)) / sy
                    alpha = 0.4 * max(0, 1 - math.hypot(px, py))
                color = [current * (1 - alpha) + dark * alpha for current, dark in zip(color, (57, 101, 82))]
            threshold = (bayer[y % 4][x % 4] + 0.5) / 16
            red, green, blue = [min(level, int(value * level / 255 + threshold)) for value, level in zip(color, (31, 63, 31))]
            value = (red << 11) | (green << 5) | blue
            pixels.extend((value & 255, value >> 8))
    lines.append(f'_Alignas(64) static const unsigned char decor_{name}_pixels[]={{')
    for offset in range(0, len(pixels), 32):
        lines.append(' ' + ','.join(map(str, pixels[offset:offset+32])) + ',')
    lines += ['};', f'static const lv_image_dsc_t decor_{name}={{.header={{.magic=LV_IMAGE_HEADER_MAGIC,.cf=LV_COLOR_FORMAT_RGB565,.w=320,.h=203,.stride=640}},.data_size=sizeof(decor_{name}_pixels),.data=decor_{name}_pixels}};']
    metadata.append({'key': 'surface/'+name, 'source': str(source.relative_to(root)), 'sha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'conversion': 'SVG linear + two transformed radial gradients composited at 320x203; ordered-dither RGB565; native LVGL draw-image scaling', 'user_provided': True})
# Fill only pixels outside the ordinary large-radius corner. A full solid
# patch would double-blend the overlap during opacity transitions.
for name in ('request', 'reply', 'continuation'):
    mask = bytearray()
    for y in range(64):
        for x in range(64):
            covered = 0
            for sy in range(4):
                for sx in range(4):
                    px, py = x+(sx+0.5)/4, y+(sy+0.5)/4
                    if name == 'reply': px = 64-px
                    elif name == 'continuation': px, py = 64-px, 64-py
                    small = 64*3/20
                    desired = not (px>64-small and py>64-small and (px-(64-small))**2+(py-(64-small))**2>small**2)
                    covered += desired and px*px+py*py>64*64
            mask.append(round(covered*255/16))
    lines.append(f'_Alignas(64) static const unsigned char corner_{name}_pixels[]={{')
    for offset in range(0, len(mask), 32):
        lines.append(' '+','.join(map(str,mask[offset:offset+32]))+',')
    lines += ['};', f'static const lv_image_dsc_t corner_{name}={{.header={{.magic=LV_IMAGE_HEADER_MAGIC,.cf=LV_COLOR_FORMAT_A8,.w=64,.h=64,.stride=64}},.data_size=sizeof(corner_{name}_pixels),.data=corner_{name}_pixels}};']
(root/'app/buffer_app/buffer_ui_decor_assets.inc').write_text('\n'.join(lines)+'\n')
p = root/'third_party/design/sources.json'
existing = json.loads(p.read_text())
keys = {item['key'] for item in metadata}
existing['assets'] = [item for item in existing['assets'] if item['key'] not in keys] + metadata
existing['assets'].sort(key=lambda item:item['key'])
p.write_text(json.dumps(existing, ensure_ascii=False, indent=2)+'\n')
