package com.ai.assistance.operit.core.tools.agent

import android.content.Context

/**
 * Lightweight controller to talk to the Shower server running locally on the device.
 *
 * Responsibilities:
 * - Maintain a single Binder connection to the Shower service
 * - Send simple text commands: CREATE_DISPLAY, LAUNCH_APP, TAP, KEY, TOUCH_*
 * - Parse log messages to discover the virtual display id created by Shower.
 *
 * NOTE: Binary video frames are currently ignored; screenshots are captured via screencap -d later.
 */
object ShowerController {

    private val core get() = com.ai.assistance.showerclient.ShowerController

    fun getDisplayId(): Int? = core.getDisplayId()

    fun getVideoSize(): Pair<Int, Int>? = core.getVideoSize()

    fun setBinaryHandler(handler: ((ByteArray) -> Unit)?) = core.setBinaryHandler(handler)

    suspend fun requestScreenshot(timeoutMs: Long = 3000L): ByteArray? =
        core.requestScreenshot(timeoutMs)

    suspend fun ensureDisplay(
        context: Context,
        width: Int,
        height: Int,
        dpi: Int,
        bitrateKbps: Int? = null,
    ): Boolean = core.ensureDisplay(context, width, height, dpi, bitrateKbps)

    suspend fun launchApp(packageName: String): Boolean =
        core.launchApp(packageName)

    suspend fun tap(x: Int, y: Int): Boolean =
        core.tap(x, y)

    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = 300L,
    ): Boolean = core.swipe(startX, startY, endX, endY, durationMs)

    suspend fun touchDown(x: Int, y: Int): Boolean =
        core.touchDown(x, y)

    suspend fun touchMove(x: Int, y: Int): Boolean =
        core.touchMove(x, y)

    suspend fun touchUp(x: Int, y: Int): Boolean =
        core.touchUp(x, y)

    fun shutdown() = core.shutdown()

    suspend fun key(keyCode: Int): Boolean =
        core.key(keyCode)
}
