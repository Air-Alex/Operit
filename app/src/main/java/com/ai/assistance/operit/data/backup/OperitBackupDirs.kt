package com.ai.assistance.operit.data.backup

import android.os.Environment
import java.io.File

object OperitBackupDirs {

    fun operitRootDir(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Operit"
        )
    }

    fun backupRootDir(): File {
        return ensureDir(File(operitRootDir(), "backup"))
    }

    fun roomDbDir(): File {
        return ensureDir(File(backupRootDir(), "room_db"))
    }

    fun chatDir(): File {
        return ensureDir(File(backupRootDir(), "chat"))
    }

    fun memoryDir(): File {
        return ensureDir(File(backupRootDir(), "memory"))
    }

    fun modelConfigDir(): File {
        return ensureDir(File(backupRootDir(), "model_config"))
    }

    private fun ensureDir(dir: File): File {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
