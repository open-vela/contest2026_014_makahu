package com.mocharealm.foundation.fabric.buffer.domain

/** One vocabulary shared by proposals, confirmation UI, and weekly reflection. */
internal object LifeLogCategories {
    val labels = linkedMapOf(
        "nutrition" to "饮食", "hydration" to "饮水", "exercise" to "运动",
        "sleep" to "睡眠", "mood" to "情绪", "learning" to "学习/专注",
        "mindfulness" to "静心练习", "symptom" to "身体感受", "travel" to "旅行/地点",
    )
    val exportable = setOf("nutrition", "hydration", "exercise", "sleep", "mindfulness")
}
