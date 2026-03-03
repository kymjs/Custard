package com.kymjs.ai.custard.data.backup

import kotlinx.coroutines.sync.Mutex

object RoomDatabaseBackupRestoreLock {
    val mutex = Mutex()
}
