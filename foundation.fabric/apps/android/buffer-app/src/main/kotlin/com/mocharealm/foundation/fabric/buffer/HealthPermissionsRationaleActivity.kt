package com.mocharealm.foundation.fabric.buffer

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Privacy explanation shown from the Health Connect permission screen. */
class HealthPermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "Buffer 只在你点击“同步健康”后写入对应的生活记录。\n" +
                    "健康数据由手机本机的 Health Connect 管理，Vela 和其他局域网设备不会直接访问或写入。"
                setPadding(48, 48, 48, 48)
            },
        )
    }
}
