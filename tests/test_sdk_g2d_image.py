"""Execute actual image dispatch against partial buffers with nonzero origins."""
from pathlib import Path
import subprocess
from host_support import SDK_ROOT, CC, extract_c


def test_image_coordinates_are_local_to_partial_draw_buffer(tmp_path: Path):
    source = (SDK_ROOT / 'apps/graphics/lvgl/lvgl/src/draw/sunxi_g2d/lv_draw_g2d_img.c').read_text()
    harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#define LV_OPA_MIN 0
#define LV_OPA_MAX 255
#define LV_SCALE_NONE 256
#define LV_COLOR_FORMAT_ARGB8888 4
#define LV_COLOR_FORMAT_IS_NV(cf) ((void)(cf),false)
#define LV_RESULT_OK 0
#define LV_LOG_ERROR(...) ((void)0)
#define LV_LOG_WARN(...) ((void)0)
typedef int lv_opa_t;
typedef int lv_color_format_t;
typedef int lv_result_t;
typedef struct {int x1,y1,x2,y2;} lv_area_t;
typedef struct {struct {int cf,w,h;} header;} lv_draw_buf_t;
typedef struct {lv_draw_buf_t *draw_buf;lv_area_t buf_area;} lv_layer_t;
typedef struct {lv_layer_t *target_layer;const lv_area_t *clip_area;} lv_draw_unit_t;
typedef struct {int opa,rotation,scale_x,scale_y,pivot;const void *src;} lv_draw_image_dsc_t;
typedef struct {lv_draw_buf_t *decoded;} lv_image_decoder_dsc_t;
static lv_draw_buf_t source;
static unsigned blits;
static void lv_area_copy(lv_area_t *d,const lv_area_t *s){*d=*s;}
static int lv_area_get_width(const lv_area_t *a){return a->x2-a->x1+1;}
static int lv_area_get_height(const lv_area_t *a){return a->y2-a->y1+1;}
static void lv_area_move(lv_area_t *a,int x,int y){a->x1+=x;a->x2+=x;a->y1+=y;a->y2+=y;}
static bool _lv_area_intersect(lv_area_t *r,const lv_area_t *a,const lv_area_t *b){
 *r=(lv_area_t){a->x1>b->x1?a->x1:b->x1,a->y1>b->y1?a->y1:b->y1,a->x2<b->x2?a->x2:b->x2,a->y2<b->y2?a->y2:b->y2};return r->x1<=r->x2&&r->y1<=r->y2;}
static void _lv_image_buf_get_transformed_area(lv_area_t *a,int w,int h,int r,int x,int y,const int *p)
{(void)r;(void)x;(void)y;(void)p;*a=(lv_area_t){0,0,w-1,h-1};}
static int lv_image_decoder_open(lv_image_decoder_dsc_t *d,const void *s,const void *a)
{(void)s;(void)a;d->decoded=&source;return 0;}
static void lv_image_decoder_close(lv_image_decoder_dsc_t *d){(void)d;}
static void check(lv_draw_buf_t *d,const lv_area_t *a,const lv_area_t *s){
 assert(a->x1>=0&&a->y1>=0&&a->x2<d->header.w&&a->y2<d->header.h);
 assert(s->x1>=0&&s->y1>=0&&s->x2<source.header.w&&s->y2<source.header.h);blits++;}
static void _g2d_blit_no_alpha(lv_draw_buf_t *d,const lv_area_t *a,const lv_draw_buf_t *s,const lv_area_t *sa,const lv_draw_image_dsc_t *i,const lv_area_t *c,const lv_area_t *b)
{(void)s;(void)i;(void)c;(void)b;check(d,a,sa);}
static void _g2d_blit_transform(lv_draw_buf_t *d,const lv_area_t *a,const lv_draw_buf_t *s,const lv_area_t *sa,const lv_draw_image_dsc_t *i,const lv_area_t *c,const lv_area_t *b)
{(void)s;(void)i;(void)c;(void)b;check(d,a,sa);}
static void _g2d_blit(lv_draw_buf_t *d,const lv_area_t *a,const lv_draw_buf_t *s,const lv_area_t *sa,lv_opa_t opa)
{(void)s;(void)opa;check(d,a,sa);}
'''
    harness += extract_c(source, 'void lv_draw_g2d_img(')
    harness += r'''
int main(void){
 source.header.w=160;source.header.h=40;
 for(int alpha=0;alpha<2;alpha++)for(int x=0;x<=30;x+=30){
  source.header.cf=alpha?4:2;
  lv_draw_buf_t dest={{2,320,20}};
  lv_layer_t layer={&dest,{x,200,x+319,219}};
  lv_area_t clip=layer.buf_area,coords={100,200,259,239};
  lv_draw_unit_t unit={&layer,&clip};
  lv_draw_image_dsc_t d={255,0,256,256,0,&source};
  lv_draw_g2d_img(&unit,&d,&coords);
 }
 assert(blits==4);return 0;
}
'''
    file = tmp_path / 'image.c'; file.write_text(harness)
    binary = tmp_path / 'image'
    subprocess.run([*CC, '-Wall', '-Wextra', '-Werror', '-fsanitize=address,undefined',
                    str(file), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True, timeout=10)


def test_image_padded_pitch_and_cache_span():
    from host_support import PROJECT_ROOT
    build = Path.home() / '.cache/buffer-vela/m3e-host-ui'
    build.mkdir(parents=True, exist_ok=True)
    source = (SDK_ROOT / 'apps/graphics/lvgl/lvgl/src/draw/sunxi_g2d/lv_draw_g2d_img.c').read_text()
    (build / 'g2d_image_production.inc').write_text('\n'.join(extract_c(source, signature) for signature in (
        'static inline void _get_scale_clip_area(', 'static void _g2d_blit_no_alpha(', 'static void _g2d_blit(')))
    with (build / 'g2d-image-build.log').open('w') as log:
        subprocess.run(['cmake', '-S', str(PROJECT_ROOT / 'tests/fixtures/ui'), '-B', str(build),
                        '-DVELA_SDK=' + str(SDK_ROOT)], stdout=log, stderr=subprocess.STDOUT, check=True)
        subprocess.run(['cmake', '--build', str(build), '--target', 'g2d_image_host', '--parallel', '8'],
                       stdout=log, stderr=subprocess.STDOUT, check=True)
    subprocess.run([str(build / 'g2d_image_host')], check=True, timeout=10)
