package com.mocharealm.foundation.fabric.buffer.domain

/** Truncate visible text without leaving half of a UTF-16 surrogate pair. */
internal fun String.takeCodePoints(count: Int): String {
    require(count >= 0)
    return substring(0, offsetByCodePoints(0, minOf(count, codePointCount(0, length))))
}
