"""Validate the actual SDK command fields that caused the S1 calendar abort."""
from pathlib import Path
import subprocess
from host_support import PROJECT_ROOT, SDK_ROOT, extract_c


def test_sdk_g2d_fill_format_and_bounds():
    build = Path.home() / '.cache/buffer-vela/m3e-host-ui'
    build.mkdir(parents=True, exist_ok=True)
    source = SDK_ROOT / 'apps/graphics/lvgl/lvgl/src/draw/sunxi_g2d'
    (build / 'g2d_fill_production.inc').write_text(
        extract_c((source / 'lv_draw_g2d_fill.c').read_text(), 'static void _g2d_fill(') + '\n' +
        extract_c((source / 'lv_draw_g2d.c').read_text(), 'static int32_t _g2d_evaluate('))
    with (build / 'g2d-build.log').open('w') as log:
        subprocess.run(['cmake', '-S', str(PROJECT_ROOT / 'tests/fixtures/ui'), '-B', str(build),
                        '-DVELA_SDK=' + str(SDK_ROOT)], stdout=log, stderr=subprocess.STDOUT, check=True)
        subprocess.run(['cmake', '--build', str(build), '--target', 'g2d_fill_host', '--parallel', '8'],
                       stdout=log, stderr=subprocess.STDOUT, check=True)
    subprocess.run([str(build / 'g2d_fill_host')], check=True, timeout=10)
