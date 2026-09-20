# Contest 2026 team 014 Buffer Vela app
if(CONFIG_LVX_USE_DEMO_CONTEST2026_014_BUFFER_APP)
  nuttx_add_application(
    NAME buffer_app
    SRCS buffer_app_main.c buffer_audio.c buffer_store.c buffer_wire.c buffer_pairing.c buffer_wifi.c buffer_ble.c buffer_ble_service.c buffer_enrollment.c buffer_ui.c
    STACKSIZE 32768
    INCLUDE_DIRECTORIES
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${NUTTX_APPS_DIR}/frameworks/connectivity/bluetooth/framework/include
    ${NUTTX_APPS_DIR}/audioutils/alsa-lib/include)
endif()
