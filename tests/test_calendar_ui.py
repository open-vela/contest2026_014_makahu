"""Production calendar and LVGL with sanitizers; no simulator or user data."""
import os
import subprocess
from pathlib import Path
from host_support import PROJECT_ROOT, SDK_ROOT


def test_calendar_month_day_and_sync_projection(tmp_path):
    build = Path(os.environ.get('BUFFER_UI_HOST_BUILD', Path.home() / '.cache/buffer-vela/m3e-host-ui'))
    build.mkdir(parents=True, exist_ok=True)
    with (tmp_path / 'build.log').open('w') as log:
        subprocess.run(['cmake', '-S', str(PROJECT_ROOT / 'tests/fixtures/ui'), '-B', str(build),
                        '-DVELA_SDK=' + str(SDK_ROOT), '-DCALENDAR_TEST_DATA=' + str(tmp_path / 'calendar')],
                       stdout=log, stderr=subprocess.STDOUT, check=True)
        subprocess.run(['cmake', '--build', str(build), '--target', 'calendar_host', '--parallel', '8'],
                       stdout=log, stderr=subprocess.STDOUT, check=True)
    subprocess.run([str(build / 'calendar_host')], check=True, timeout=30)
