# openvela SDK patch series

The patches target more than one Git repository; do not apply the entire series
from one directory. Patches 0001–0003 target the Bluetooth repositories noted
in `tools/README.md`. The LittleFS patches have two source paths:

## SDK's standalone LittleFS Git repository

When the SDK has the independent repository at
`nuttx/fs/littlefs/littlefs`, apply 0004 there with `git -C` (it does not apply
from the NuttX root):

```sh
git -C /home/simon/vela/nuttx/fs/littlefs/littlefs apply --check \
  /home/simon/vela/contest2026_014_makahu/patches/openvela/0004-littlefs-split-directory-path.patch
git -C /home/simon/vela/nuttx/fs/littlefs/littlefs apply \
  /home/simon/vela/contest2026_014_makahu/patches/openvela/0004-littlefs-split-directory-path.patch
```

Then apply 0009 in that same independent repository. It fixes the existing
`lfs_file_getattr` / `lfs_file_setattr` wrappers used by the VFS change: lock
release on every completed call, correct trace names, stored attribute size
return, zero-fill and `LFS_ERR_NOATTR` for a missing attribute, and the
`attr_max` size check. 0004 contains the split-directory raw-path fix;
0009 contains only the later wrapper fixes.

```sh
git -C /home/simon/vela/nuttx/fs/littlefs/littlefs apply --check \
  /home/simon/vela/contest2026_014_makahu/patches/openvela/0009-littlefs-handle-attr-wrapper-fixes.patch
git -C /home/simon/vela/nuttx/fs/littlefs/littlefs apply \
  /home/simon/vela/contest2026_014_makahu/patches/openvela/0009-littlefs-handle-attr-wrapper-fixes.patch
```

## NuttX root and downloaded-source fallback

Apply 0005–0008 from the NuttX repository root, in numeric order. 0005 updates
the fallback patch with the split-directory path fix; 0006 serializes VFS file
metadata operations; 0007 switches `fstat` / `fchstat` to attributes on the
open file handle. 0008 extends `fs/littlefs/lfs_getpath.patch` with attribute
wrappers and declarations for a *future downloaded LittleFS source*. It does
not update the independent Git source above, so it cannot replace 0009.

```sh
cd /home/simon/vela/nuttx
git apply --check /home/simon/vela/contest2026_014_makahu/patches/openvela/0005-littlefs-download-path.patch
git apply /home/simon/vela/contest2026_014_makahu/patches/openvela/0005-littlefs-download-path.patch
git apply --check /home/simon/vela/contest2026_014_makahu/patches/openvela/0006-littlefs-serialize-file-metadata.patch
git apply /home/simon/vela/contest2026_014_makahu/patches/openvela/0006-littlefs-serialize-file-metadata.patch
git apply --check /home/simon/vela/contest2026_014_makahu/patches/openvela/0007-littlefs-handle-metadata-vfs.patch
git apply /home/simon/vela/contest2026_014_makahu/patches/openvela/0007-littlefs-handle-metadata-vfs.patch
git apply --check /home/simon/vela/contest2026_014_makahu/patches/openvela/0008-littlefs-file-handle-attributes.patch
git apply /home/simon/vela/contest2026_014_makahu/patches/openvela/0008-littlefs-file-handle-attributes.patch
```

Thus the standalone-source path is 0004 + 0009 in the LittleFS Git repository,
plus 0006 + 0007 in NuttX. The downloaded-source fallback is 0005 + 0008 in
NuttX, plus 0006 + 0007. Do not apply 0005/0008 as a substitute for 0004/0009
when building from the standalone LittleFS repository.

The NuttX Make fallback fetches LittleFS 2.5.1 and its CMake fallback fetches
2.4.0. The updated fallback patch was applied to both clean releases and the
handle-attribute regression was compiled and run against each. The checked-in
host test, `python3 -m pytest tests/test_sdk_littlefs.py -k handle-attributes`, targets the
LittleFS source in the active SDK checkout.

The regression uses a RAM flash device with `LFS_THREADSAFE` enabled. It checks
balanced API locking on success and error, missing-attribute behavior,
metadata through a split directory and rename, unlink persistence, and remount
recovery.

## Gemini S1 ILI9341 backlight (0010–0012)

0010 targets the `vendor/allwinnertech` Git root. It adds an opt-in R528
ILI9341 driver profile using PD20 / PWM4 (the SoC's default pin mapping) at
50 kHz, and propagates PWM configuration errors instead of always returning
success. The profile is disabled when sys_config overrides PWM pin routing.

0011 targets the `nuttx` Git root. The generic ILI9341 LCD driver validates the
requested level, propagates board backlight errors and preserves its previous
reported level when the board rejects a write. It calls the backlight callback
for explicit power-off as well as for positive brightness levels.

0012 targets the `vendor/allwinnertech` Git root after 0010 and the existing
Buffer application integration. It only adds the three S1 configuration values
for the PWM profile, LCD_MAXPOWER=255 and BUFFER_VELA_NIGHT_DIM; it deliberately
excludes the board configuration's other pre-existing worktree changes.

The application uses `/dev/lcd0` with LCDDEVIO_GETPOWER/SETPOWER. It never dims
to zero, records the prior level before writing, and restores on leaving sleep
mode unless another writer has changed the level. The Sunxi framebuffer's
stored set_bright/get_bright values are not used as proof of physical dimming.

These three patches are already reflected in this workspace's SDK sources and
S1 defconfig. They have only received static code review; no patch-apply check,
configuration generation, build or hardware operation was run for this change.
Existing generated configurations and binaries have not been refreshed.

## Gemini S1 private NAND storage (0013–0014)

0013 targets the `nuttx` Git root after the LittleFS attribute patches above.
It adds strict Dhara map recovery for private storage, logical page geometry,
durable map flush, cache/geometry/range checks and complete-page I/O handling.
LittleFS VFS rejects short reads and writes. The existing Dhara registration
entry point retains its compatibility behavior; private storage uses the new
explicit recovery entry point.

0014 targets the `vendor/allwinnertech` Git root after 0010–0012 and the
Buffer application integration. It propagates NAND I/O/flush/partition
registration failures and adds an independent `buffer-private` board profile
based on the current ILI9341 `nsh_minidisplay` configuration. The corresponding
factory partition table reserves a raw 64 MiB `bufpriv` before `usrdata`.
The pack script explicitly selects that table with `-m buffer-private-factory`
and labels the output accordingly. Ordinary packing keeps the original table.
This option belongs to `pack_img.sh`; the envsetup `pack -m` wrapper still
means dump mode.

**The new layout moves usrdata. It is a factory profile, not an in-place
update for an existing device.** Buffer's normal startup never formats it;
the local provisioning command only accepts the fixed, fully erased partition.
See [the storage review](../../docs/finals/34-s1-private-storage.md) for the
source paths, initialization contract and remaining hardware acceptance.

These patches were generated from this phase's before/after source snapshots,
excluding unrelated existing SDK changes. No patch-apply check, configuration
generation, build, pack, device provisioning or hardware verification was run.

## S1 discovery and reboot fixes (0015–0020)

Apply 0015, 0017 and 0019 at the NuttX root; 0016 and 0018 at
`vendor/allwinnertech`; 0020 at `frameworks/connectivity/bluetooth`.
0015 preserves the conservative Dhara geometry across resume. 0016 starts
KVDB, creates the local IPC directory and supplies Bluetooth task stack
headroom. 0017 reserves HCI for firmware loading and returns EAGAIN for empty
nonblocking reads. 0018 checks and bounds firmware command I/O and releases
HCI on completion. 0019 moves escaped frames off thread stacks and replays
unacknowledged frames with their original sequence numbers. 0020 waits for
firmware ownership to be released before enabling the host controller.

Targeted host regressions: `test_sdk_dhara_reboot.py`,
`test_sdk_hci_exclusive.py`, `test_sdk_h5_transport.py`,
`test_sdk_realtek_download.py`, and `test_sdk_littlefs.py`.

0021 targets `vendor/allwinnertech` and lets Buffer restore its private WAPI
profile and start NTP after successful DHCP. It excludes the factory Wi-Fi
shell script and early NTP startup only when the Buffer application is enabled.

0022–0025 apply at `apps`: bounded G2D fill formats/cache spans, NTP without
an ICMP prerequisite, prepared capture buffer cleanup, and image coordinates
relative to partial draw buffers. 0026 applies at `vendor/allwinnertech`:
audio queue locks exclude PCM reads and upper callbacks; full wakeup queues
preserve ownership of accepted buffers. Host regressions are
`test_sdk_g2d_fill.py`, `test_sdk_ntp_bootstrap.py`,
`test_sdk_pcm_capture_close.py`, `test_sdk_g2d_image.py`, and
`test_s1_audio_queue_lock.py`.
