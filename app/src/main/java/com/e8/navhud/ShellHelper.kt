package com.e8.navhud

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Executes privileged/system shell commands on the head unit with output capture and timeout.
 */
object ShellHelper {
    private const val TAG = "NavHUD"
    private const val EXEC_ERROR = "\u0000EXEC_ERROR\u0000"
    private val SU_PATHS = arrayOf("su", "/system/xbin/su", "/system/bin/su", "/sbin/su")

    fun exec(cmd: String): String {
        for (suPath in SU_PATHS) {
            val result = execInternal(arrayOf(suPath, "-c", cmd))
            if (result != EXEC_ERROR) {
                return result
            }
        }
        return ""
    }

    fun execSh(cmd: String): String {
        val result = execInternal(arrayOf("/system/bin/sh", "-c", cmd))
        return if (result == EXEC_ERROR) "" else result
    }

    private fun execInternal(args: Array<String>): String {
        return try {
            val process = Runtime.getRuntime().exec(args)
            val errBuf = StringBuilder()
            val errThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).useLines { lines ->
                        lines.forEach { line -> errBuf.append(line).append('\n') }
                    }
                } catch (_: Exception) {
                }
            }
            errThread.start()

            val stdout = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { it.readText() }
            errThread.join(10_000L)
            if (!process.waitFor(10L, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
            (stdout + errBuf.toString()).trim()
        } catch (e: Exception) {
            Log.d(TAG, "exec: ${e.message}")
            EXEC_ERROR
        }
    }
}
