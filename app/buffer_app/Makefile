############################################################################
# Contest 2026 team 014 Buffer Vela app - Makefile
############################################################################

include $(APPDIR)/Make.defs

ifeq ($(CONFIG_LVX_USE_DEMO_CONTEST2026_014_BUFFER_APP),y)
PROGNAME  = buffer_app
PRIORITY  = 100
STACKSIZE = 32768
MODULE    = $(CONFIG_LVX_USE_DEMO_CONTEST2026_014_BUFFER_APP)

CFLAGS += ${INCDIR_PREFIX}$(APPDIR)/audioutils/alsa-lib/include
CFLAGS += ${INCDIR_PREFIX}$(APPDIR)/crypto/mbedtls/mbedtls/include

CFLAGS += ${INCDIR_PREFIX}$(APPDIR)/frameworks/connectivity/bluetooth/framework/include

CSRCS = buffer_audio.c buffer_store.c buffer_wire.c buffer_pairing.c buffer_wifi.c buffer_ble.c buffer_ble_service.c buffer_enrollment.c buffer_ui.c
MAINSRC = buffer_app_main.c
endif

include $(APPDIR)/Application.mk
