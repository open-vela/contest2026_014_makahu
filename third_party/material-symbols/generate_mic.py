"""Render the pinned official mic.svg to a 24px LVGL A8 image (librsvg + cairo)."""
from pathlib import Path
import ctypes as c
root = Path(__file__).resolve().parent
svg = (root / 'mic.svg').read_bytes()
r = c.CDLL('librsvg-2.so.2'); cairo = c.CDLL('libcairo.so.2'); obj = c.CDLL('libgobject-2.0.so.0')
r.rsvg_handle_new_from_data.argtypes = [c.c_char_p,c.c_size_t,c.c_void_p]; r.rsvg_handle_new_from_data.restype = c.c_void_p
r.rsvg_handle_render_cairo.argtypes = [c.c_void_p,c.c_void_p]; r.rsvg_handle_render_cairo.restype = c.c_int
cairo.cairo_image_surface_create.argtypes = [c.c_int,c.c_int,c.c_int]; cairo.cairo_image_surface_create.restype = c.c_void_p
cairo.cairo_create.argtypes = [c.c_void_p]; cairo.cairo_create.restype = c.c_void_p
cairo.cairo_surface_flush.argtypes = [c.c_void_p]
cairo.cairo_image_surface_get_data.argtypes = [c.c_void_p]; cairo.cairo_image_surface_get_data.restype = c.POINTER(c.c_ubyte)
cairo.cairo_image_surface_get_stride.argtypes = [c.c_void_p]; cairo.cairo_image_surface_get_stride.restype = c.c_int
cairo.cairo_destroy.argtypes = [c.c_void_p]; cairo.cairo_surface_destroy.argtypes = [c.c_void_p]; obj.g_object_unref.argtypes = [c.c_void_p]
handle = r.rsvg_handle_new_from_data(svg,len(svg),None); assert handle
surface = cairo.cairo_image_surface_create(0,24,24); cr = cairo.cairo_create(surface)
assert r.rsvg_handle_render_cairo(handle,cr)
cairo.cairo_surface_flush(surface)
data = cairo.cairo_image_surface_get_data(surface); stride = cairo.cairo_image_surface_get_stride(surface)
alpha = [data[y*stride+x*4+3] for y in range(24) for x in range(24)]
text = '/* Generated from official Material Symbols Rounded mic; Apache-2.0. */\n'
text += '#ifndef BUFFER_UI_ICONS_H\n#define BUFFER_UI_ICONS_H\n#include <lvgl/lvgl.h>\nstatic const uint8_t buffer_mic_alpha[] = {\n'
text += '\n'.join('  '+','.join(str(v) for v in alpha[y*24:(y+1)*24])+',' for y in range(24))
text += '\n};\nstatic const lv_image_dsc_t buffer_mic_icon = {\n .header = {.magic=LV_IMAGE_HEADER_MAGIC, .cf=LV_COLOR_FORMAT_A8, .w=24, .h=24, .stride=24},\n .data_size=sizeof(buffer_mic_alpha), .data=buffer_mic_alpha\n};\n#endif\n'
(root.parent.parent/'app/buffer_app/buffer_ui_icons.h').write_text(text)
cairo.cairo_destroy(cr); cairo.cairo_surface_destroy(surface); obj.g_object_unref(handle)
