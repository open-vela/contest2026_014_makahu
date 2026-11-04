"""Generate native-size LVGL A8 variants from pinned official Rounded SVGs."""
from pathlib import Path
import ctypes as c
import json, hashlib, struct, zlib
root=Path(__file__).resolve().parent
r=c.CDLL('librsvg-2.so.2');cairo=c.CDLL('libcairo.so.2');obj=c.CDLL('libgobject-2.0.so.0')
r.rsvg_handle_new_from_data.argtypes=[c.c_char_p,c.c_size_t,c.c_void_p];r.rsvg_handle_new_from_data.restype=c.c_void_p
r.rsvg_handle_render_cairo.argtypes=[c.c_void_p,c.c_void_p];r.rsvg_handle_render_cairo.restype=c.c_int
cairo.cairo_image_surface_create.argtypes=[c.c_int,c.c_int,c.c_int];cairo.cairo_image_surface_create.restype=c.c_void_p
cairo.cairo_create.argtypes=[c.c_void_p];cairo.cairo_create.restype=c.c_void_p
cairo.cairo_surface_flush.argtypes=[c.c_void_p]
cairo.cairo_image_surface_get_data.argtypes=[c.c_void_p];cairo.cairo_image_surface_get_data.restype=c.POINTER(c.c_ubyte)
cairo.cairo_image_surface_get_stride.argtypes=[c.c_void_p];cairo.cairo_image_surface_get_stride.restype=c.c_int
cairo.cairo_scale.argtypes=[c.c_void_p,c.c_double,c.c_double]
cairo.cairo_destroy.argtypes=[c.c_void_p];cairo.cairo_surface_destroy.argtypes=[c.c_void_p];obj.g_object_unref.argtypes=[c.c_void_p]
def rle(data):
    """LVGL byte RLE: repeat control or 1..127 literal bytes, lossless."""
    out=bytearray();i=0
    while i<len(data):
        count=1
        while i+count<len(data) and data[i+count]==data[i] and count<127:count+=1
        if count>=3:out.extend((count,data[i]));i+=count
        else:
            start=i;i+=count
            while i<len(data) and i-start<127:
                count=1
                while i+count<len(data) and data[i+count]==data[i] and count<127:count+=1
                if count>=3:break
                i+=min(count,127-(i-start))
            out.append(0x80|(i-start));out.extend(data[start:i])
    return bytes(out)

names={"mic":"mic","more_horiz":"more","checklist":"checklist","calendar_month":"calendar","history":"history","settings":"settings","wifi":"wifi","warning":"warning","check_circle":"ready","check_box_outline_blank":"unchecked","pending":"pending","chevron_left":"previous","chevron_right":"next","arrow_upward":"up","arrow_downward":"down","arrow_back":"back","close":"close"}
names.update({name:name for name in ('devices_off','autorenew','devices_other','animation','sync','devices','motion_photos_on','bluetooth','pause','play_arrow','restart_alt','check_box')})
minimum,maximum=12,64
header="""/* Generated from pinned Material Symbols Rounded SVGs. Apache-2.0. */
#ifndef BUFFER_UI_ICONS_H
#define BUFFER_UI_ICONS_H
#include <lvgl/lvgl.h>
#include "buffer_ui_theme.h"
struct buffer_ui_symbol_s;
struct buffer_ui_symbol_variant_s { lv_image_dsc_t image; const struct buffer_ui_symbol_s *symbol; };
struct buffer_ui_symbol_s { const struct buffer_ui_symbol_variant_s *variants; unsigned count; };
const lv_image_dsc_t *buffer_ui_icon_source(const lv_image_dsc_t *source,int pixels);
"""
source='/* Generated lossless RLE native-size A8 masks; SDK decoder/cache owns decoded pixels. */\n#include "buffer_ui_icons.h"\n#if !LV_USE_RLE || !LV_BIN_DECODER_RAM_LOAD\n#error Enable LV_USE_RLE and LV_BIN_DECODER_RAM_LOAD for Buffer icons\n#endif\n'
checks=['/* Generated CRCs of the original uncompressed SVG alpha masks. */']
check_rows=[]
raw_bytes=stored_bytes=0
compressed=set()
for entry in json.loads((root/'sources.json').read_text())["icons"]:
    svg=(root/(entry['name']+'.svg')).read_bytes();name=names[entry['name']]
    assert hashlib.sha256(svg).hexdigest()==entry['sha256']
    handle=r.rsvg_handle_new_from_data(svg,len(svg),None);assert handle
    header+=f'extern const struct buffer_ui_symbol_variant_s buffer_{name}_variants[];\n#define buffer_{name}_icon (buffer_{name}_variants[{24-minimum}].image)\n'
    source+=f'/* {entry["source"]} */\nstatic const struct buffer_ui_symbol_s buffer_{name}_symbol;\n'
    for pixels in range(minimum,maximum+1):
        surface=cairo.cairo_image_surface_create(0,pixels,pixels);cr=cairo.cairo_create(surface)
        cairo.cairo_scale(cr,pixels/24.0,pixels/24.0)
        assert r.rsvg_handle_render_cairo(handle,cr);cairo.cairo_surface_flush(surface)
        data=cairo.cairo_image_surface_get_data(surface);stride=cairo.cairo_image_surface_get_stride(surface)
        alpha=[data[y*stride+x*4+3] for y in range(pixels) for x in range(pixels)]
        raw=bytes(alpha);packed=rle(raw)
        stored=struct.pack('<III',1,len(packed),len(raw))+packed
        if len(stored)>=len(raw):stored=raw
        else:compressed.add((name,pixels))
        raw_bytes+=len(raw);stored_bytes+=len(stored)
        check_rows.append(f' {{&buffer_{name}_variants[{pixels-minimum}].image,0x{zlib.crc32(raw):08x}u}},')
        source+=f'static const uint8_t buffer_{name}_{pixels}_alpha[] = {{\n'
        source+='\n'.join(' '+','.join(map(str,stored[i:i+32]))+',' for i in range(0,len(stored),32))+'\n};\n'
        cairo.cairo_destroy(cr);cairo.cairo_surface_destroy(surface)
    source+=f'const struct buffer_ui_symbol_variant_s buffer_{name}_variants[] = {{\n'
    for pixels in range(minimum,maximum+1):
        flags='LV_IMAGE_FLAGS_USER1|LV_IMAGE_FLAGS_COMPRESSED' if (name,pixels) in compressed else 'LV_IMAGE_FLAGS_USER1'
        source+=f' {{.image={{.header={{.magic=LV_IMAGE_HEADER_MAGIC,.cf=LV_COLOR_FORMAT_A8,.flags={flags},.w={pixels},.h={pixels},.stride={pixels}}},.data_size=sizeof(buffer_{name}_{pixels}_alpha),.data=buffer_{name}_{pixels}_alpha}},.symbol=&buffer_{name}_symbol}},\n'
    source+=f'}};\nstatic const struct buffer_ui_symbol_s buffer_{name}_symbol={{buffer_{name}_variants,{maximum-minimum+1}}};\n'
    obj.g_object_unref(handle)
source+="""
const lv_image_dsc_t *buffer_ui_icon_source(const lv_image_dsc_t *source,int pixels)
{
 if(!source||!(source->header.flags&LV_IMAGE_FLAGS_USER1))return source;
 const struct buffer_ui_symbol_variant_s *variant=(const void *)source;
 const struct buffer_ui_symbol_s *symbol=variant->symbol;
 int index=pixels-(int)symbol->variants[0].image.header.w;
 if(index<0)index=0;
 if((unsigned)index>=symbol->count)index=(int)symbol->count-1;
 return &symbol->variants[index].image;
}
"""
header+=(root/'icon_helpers.inc').read_text()+"\n#endif\n"
app=root.parent.parent/'app/buffer_app'
(app/'buffer_ui_icons.h').write_text(header)
(app/'buffer_ui_icons.c').write_text(source)
print(f'Generated {len(names)} symbols, native sizes {minimum}..{maximum}px, one shared asset unit')

(root.parent.parent/'tests/fixtures/ui/icon_alpha_checks.inc').write_text('\n'.join(checks+['static const struct icon_alpha_check checks[] = {']+check_rows+['};'])+'\n')
print(f'Lossless alpha storage: {raw_bytes} -> {stored_bytes} bytes, saved {raw_bytes-stored_bytes}')
