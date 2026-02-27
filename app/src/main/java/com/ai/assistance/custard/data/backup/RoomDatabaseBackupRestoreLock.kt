package com.ai.assistance.custard.data.backup

import kotlinx.coroutines.sync.Mutex

object RoomDatabaseBackupRestoreLock {
    val mutex = Mutex()
}
