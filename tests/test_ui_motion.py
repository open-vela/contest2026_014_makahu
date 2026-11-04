"""Real SDK LVGL offscreen objects; no emulator, device, app install or display."""
from host_support import PROJECT_ROOT, SDK_ROOT, extract_c
import os
from pathlib import Path
import subprocess


def test_ui_motion_and_renderer():
    build = Path(os.environ.get('BUFFER_UI_HOST_BUILD', Path.home() / '.cache/buffer-vela/m3e-host-ui'))
    build.mkdir(parents=True, exist_ok=True)
    source = (PROJECT_ROOT / 'app/buffer_app/buffer_ui.c').read_text()
    domain = (PROJECT_ROOT / 'app/buffer_app/buffer_app_main.c').read_text()
    (build / 'record_production.inc').write_text('\n'.join([
        extract_c(domain, 'bool buffer_card_supports_action('),
        extract_c(source, 'static void buffer_record_cancel_gesture('),
        extract_c(source, 'static void buffer_record_event('),
        extract_c(source, 'static bool buffer_detail_action_visible('),
    ]))
    with (build / 'build.log').open('w') as log:
        subprocess.run(['cmake', '-S', str(PROJECT_ROOT / 'tests/fixtures/ui'), '-B', str(build),
                        '-DVELA_SDK=' + str(SDK_ROOT)], stdout=log, stderr=subprocess.STDOUT, check=True)
        subprocess.run(['cmake', '--build', str(build), '--target', 'ui_host', 'record_host', 'oobe_host', 'home_host', 'shell_host', 'typography_host', 'notifications_host', 'desktop_host', 'recipe_host', 'history_host', 'members_host', 'ui_pages_compile', 'image_artwork_host', 'decor_host', 'icons_host', '--parallel', '8'],
                       stdout=log, stderr=subprocess.STDOUT, check=True)
    subprocess.run([str(build / 'icons_host')], check=True, timeout=20)
    subprocess.run([str(build / 'decor_host')], check=True, timeout=15)
    subprocess.run([str(build / 'image_artwork_host')], check=True, timeout=15)
    subprocess.run([str(build / 'recipe_host')], check=True, timeout=30)
    subprocess.run([str(build / 'history_host')], check=True, timeout=30)
    subprocess.run([str(build / 'members_host')], check=True, timeout=30)
    subprocess.run([str(build / 'desktop_host')], check=True, timeout=20)
    subprocess.run([str(build / 'notifications_host')], check=True, timeout=20)
    subprocess.run([str(build / 'typography_host')], check=True, timeout=20)
    subprocess.run([str(build / 'shell_host')], check=True, timeout=20)
    subprocess.run([str(build / 'ui_host')], check=True, timeout=20)

    subprocess.run([str(build / 'record_host')], check=True, timeout=20)

    subprocess.run([str(build / "oobe_host")], check=True, timeout=30)

    subprocess.run([str(build / "home_host")], check=True, timeout=30)
