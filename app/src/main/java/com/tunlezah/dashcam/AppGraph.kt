package com.tunlezah.dashcam

import android.content.Context

/**
 * Hand-wired dependency graph. Kept deliberately simple instead of a DI
 * framework: the object graph is small, construction order is explicit and
 * this keeps build times and APK size down on the low-end target.
 */
class AppGraph(private val context: Context)
