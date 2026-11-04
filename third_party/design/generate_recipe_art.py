"""Build the native recipe photo from the user's SVG embedded image."""
from pathlib import Path
import base64
import hashlib
import io
import json
import xml.etree.ElementTree as ET
from PIL import Image, ImageOps

root = Path(__file__).resolve().parents[2]
source = root / '.validation/design/Assistant Session Recipe Full.svg'
svg = ET.parse(source).getroot()
embedded = next(node for node in svg.iter() if node.tag.endswith('}image'))
uri = embedded.get('{http://www.w3.org/1999/xlink}href') or embedded.get('href')
original = base64.b64decode(uri.split(',', 1)[1])
photo = ImageOps.fit(Image.open(io.BytesIO(original)).convert('RGB'), (116, 106))
pixels = bytearray()
raw = photo.tobytes()
for offset in range(0, len(raw), 3):
    red, green, blue = raw[offset:offset+3]
    value = ((red >> 3) << 11) | ((green >> 2) << 5) | (blue >> 3)
    pixels.extend((value & 255, value >> 8))
lines = ['/* Generated from the user SVG embedded recipe photo; no baked UI/border. */',
         'static const unsigned char recipe_kelp_pork_pixels[]={']
for offset in range(0, len(pixels), 32):
    lines.append(' ' + ','.join(str(value) for value in pixels[offset:offset+32]) + ',')
lines += ['};',
          'static const lv_image_dsc_t recipe_kelp_pork_art={.header={.magic=LV_IMAGE_HEADER_MAGIC,.cf=LV_COLOR_FORMAT_RGB565,.w=116,.h=106,.stride=232},.data_size=sizeof(recipe_kelp_pork_pixels),.data=recipe_kelp_pork_pixels};',
          'static const lv_image_dsc_t *ui_recipe_art(const char *title)',
          '{return title&&!strcmp(title,"海带红烧肉")?&recipe_kelp_pork_art:NULL;}']
(root/'app/buffer_app/buffer_ui_recipe_art.inc').write_text('\n'.join(lines)+'\n')
manifest_path=root/'third_party/design/sources.json'
manifest=json.loads(manifest_path.read_text()) if manifest_path.exists() else {'assets':[]}
manifest['assets']=[item for item in manifest['assets'] if item['key']!='recipe/kelp-braised-pork']+[{
    'key':'recipe/kelp-braised-pork', 'title':'海带红烧肉',
    'source':str(source.relative_to(root)), 'sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
    'embedded_sha256':hashlib.sha256(original).hexdigest(),
    'conversion':'Center crop embedded photograph to 116x106, RGB565; all borders/radii rendered by LVGL',
    'user_provided':True}]
manifest['assets'].sort(key=lambda item:item['key'])
manifest_path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
