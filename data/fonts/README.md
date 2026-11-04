# Device MiSans font dependency

Buffer uses the existing MiSans theme. The firmware requires FreeType and a Chinese-capable font file. The public SDK font at `vendor/openvela/boards/vela/resource/font/MiSans-Regular.ttf` can be deployed to the device at `/data/fonts/MiSans-Regular.ttf`; a variable font already at `/data/fonts/MiSansVF.ttf` remains preferred. The code also checks `/etc/fonts/MiSans-Regular.ttf` and `/system/fonts/MiSans-Regular.ttf` for firmware-managed installations.

For a prepared filesystem staging directory, run `python3 app/buffer_app/deploy_font.py --sdk-root /home/simon/vela --data-root /path/to/staged/data`. This only copies an existing SDK font, using its original distribution terms; it does not format, mount or flash media. The platform image/deployment owner must package the staged file on persistent storage or a read-only system partition. Restart the application after installation.

Missing fonts are reported explicitly and the OOBE switches to readable English diagnostics. A Montserrat fallback is not treated as Chinese rendering success. Simulator acceptance must include an actual rendered screenshot with MiSans installed.
