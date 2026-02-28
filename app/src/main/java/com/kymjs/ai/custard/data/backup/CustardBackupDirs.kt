package com.kymjs.ai.custard.data.backup

import com.kymjs.ai.custard.util.CustardPaths
import java.io.File

object CustardBackupDirs {

    fun custardRootDir(): File {
        return CustardPaths.custardRootDir()
    }

    fun backupRootDir(): File {
        return ensureDir(File(custardRootDir(), "backup"))
    }

    fun rawSnapshotDir(): File {
        return ensureDir(File(backupRootDir(), "raw_snapshot"))
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

    fun characterCardsDir(): File {
        return ensureDir(File(backupRootDir(), "character_cards"))
    }

    private fun ensureDir(dir: File): File {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
