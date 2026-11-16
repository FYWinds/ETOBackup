package fyi.fyw.etobackup

import fyi.fyw.etobackup.api.Usage
import java.time.Instant

object BackupService {
    @Volatile
    var lastSnapshot: Instant? = null
        private set

    @Volatile
    var usage: Usage? = null
        private set
}
