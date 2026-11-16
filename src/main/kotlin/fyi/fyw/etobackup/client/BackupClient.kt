package fyi.fyw.etobackup.client

import fyi.fyw.etobackup.Manifest
import fyi.fyw.etobackup.api.Picture
import fyi.fyw.etobackup.api.State
import java.time.Instant

object BackupClient {
    const val ACTION_NOW = 0
    const val ACTION_ROLLBACK = 1
    const val ACTION_UNDO = 2
    const val ACTION_CANCEL = 3

    var available = false
        private set
    var state: State? = null
        private set
    var stateTime = 0L
        private set
    var notice = ""
        private set

    fun rollbackAnnounced(): Boolean = false

    fun requestList() {}

    fun act(action: Int, name: String = "") {}

    fun listen(listener: Runnable): AutoCloseable = AutoCloseable {}

    fun loadPicture(name: String, maxWidth: Int, then: (Picture?) -> Unit) = then(null)

    fun snapshotTime(name: String): Instant? = Manifest.timeOf(name)
}
