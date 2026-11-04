############################################################################
# Contest 2026 team 014 Buffer Vela app - Makefile
############################################################################

include $(APPDIR)/Make.defs

ifeq ($(CONFIG_LVX_USE_DEMO_CONTEST2026_014_BUFFER_APP),y)
PROGNAME  = buffer_app
PRIORITY  = 100
STACKSIZE = 49152
MODULE    = $(CONFIG_LVX_USE_DEMO_CONTEST2026_014_BUFFER_APP)

CFLAGS += ${INCDIR_PREFIX}$(APPDIR)/audioutils/alsa-lib/include
CFLAGS += ${INCDIR_PREFIX}$(APPDIR)/crypto/mbedtls/mbedtls/include
CFLAGS += ${INCDIR_PREFIX}$(APPDIR)/external/libjpeg-turbo/libjpeg-turbo

CFLAGS += ${INCDIR_PREFIX}$(APPDIR)/frameworks/connectivity/bluetooth/framework/include

CSRCS = buffer_audio.c buffer_live_asr.c buffer_followup.c buffer_focus.c buffer_store.c buffer_wire.c buffer_pairing.c buffer_wifi.c buffer_ble.c buffer_ble_service.c buffer_enrollment.c buffer_oobe.c buffer_oobe_ui.c buffer_https.c buffer_agent_store.c buffer_agent_model.c buffer_agent_sync.c buffer_agent_cli.c buffer_fabric.c buffer_agent.c buffer_agent_assistant.c buffer_images.c buffer_replica.c buffer_crdt.c buffer_crdt_sync.c buffer_calendar.c buffer_calendar_ui.c buffer_coordinator.c buffer_agent_input.c buffer_assistant.c buffer_a2ui_model.c buffer_a2ui_lvgl.c buffer_ui_recipe_state.c buffer_ui.c buffer_ui_icons.c buffer_ui_artwork.c buffer_ui_decor.c buffer_motion.c buffer_ui_motion.c buffer_ui_layout.c buffer_ui_typography.c buffer_ui_controls.c buffer_ui_notifications.c buffer_ui_desktop.c buffer_ui_shell.c buffer_ui_preferences.c buffer_todo.c buffer_todo_ui.c
ifeq ($(CONFIG_BUFFER_VELA_FABRIC),y)
FABRIC_PORTABLE := $(patsubst "%",%,$(CONFIG_BUFFER_VELA_FABRIC_ROOT))
ifeq ($(strip $(FABRIC_PORTABLE)),)
BUFFER_APP_SOURCE := $(realpath $(CURDIR))
FABRIC_PORTABLE := $(realpath $(BUFFER_APP_SOURCE)/../../foundation.fabric/platform/portable)
endif
CFLAGS += ${INCDIR_PREFIX}$(FABRIC_PORTABLE)/include
VPATH += :$(FABRIC_PORTABLE)/c
CSRCS += fabric_ble_host.c fabric_pairing_host.c fabric_identity_posix.c
FABRIC_RUST_TARGET := $(patsubst "%",%,$(CONFIG_BUFFER_VELA_FABRIC_TARGET))
# Older saved configurations do not contain BUFFER_VELA_FABRIC_TARGET yet.
ifeq ($(strip $(FABRIC_RUST_TARGET)),)
ifeq ($(CONFIG_ARCH_ARM64),y)
FABRIC_RUST_TARGET := aarch64-unknown-none
else ifeq ($(CONFIG_ARCH_ARMV7A),y)
ifeq ($(CONFIG_ARCH_FPU),y)
ifneq ($(CONFIG_ARM_FPU_ABI_SOFT),y)
FABRIC_RUST_TARGET := armv7a-none-eabihf
else
FABRIC_RUST_TARGET := armv7a-none-eabi
endif
else
FABRIC_RUST_TARGET := armv7a-none-eabi
endif
else
$(error Set CONFIG_BUFFER_VELA_FABRIC_TARGET for this board)
endif
endif
FABRIC_STATIC := $(FABRIC_PORTABLE)/target/$(FABRIC_RUST_TARGET)/release/libfabric_portable.a
LDLIBS += $(FABRIC_STATIC)
# Built-in applications are archived into libapps; their LDLIBS do not reach
# the final kernel link. NuttX links external archives from apps/staging.
FABRIC_STAGED := $(APPDIR)/staging/libbuffer_fabric_portable$(LIBEXT)
all:: $(FABRIC_STAGED)

$(FABRIC_STAGED): $(FABRIC_STATIC)
	$(Q) mkdir -p $(@D)
	$(Q) cp $< $@

context:: $(FABRIC_STAGED)
endif
MAINSRC = buffer_app_main.c
BUFFER_ICAL := $(CURDIR)/third_party/libical
VPATH += :$(BUFFER_ICAL)
CFLAGS += ${INCDIR_PREFIX}$(BUFFER_ICAL) -DHAVE_CONFIG_H
CSRCS += $(notdir $(wildcard $(BUFFER_ICAL)/*.c))
CSRCS += buffer_backlight.c
CSRCS += buffer_device_settings.c
CSRCS += buffer_source_events.c
CSRCS += buffer_source_view.c buffer_source_projector.c
CSRCS += buffer_history_ui.c
CSRCS += buffer_source_envelope.c buffer_source_remote.c
CSRCS += buffer_source_index.c
CSRCS += buffer_source_wire.c
CSRCS += buffer_source_remote_blob.c
CSRCS += buffer_source_visibility.c
CSRCS += buffer_private_storage.c
endif

include $(APPDIR)/Application.mk

clean::
	$(Q) rm -f $(APPDIR)/staging/libbuffer_fabric_portable$(LIBEXT)
