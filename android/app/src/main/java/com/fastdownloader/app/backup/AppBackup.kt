package com.fastdownloader.app.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import com.fastdownloader.app.browser.Passwords
import java.io.File

/**
 * Backup to the user's Google account, so a new phone signed in to the same account gets the bookmarks, history,
 * tabs, settings and saved passwords back. Which files go is in res/xml/data_extraction_rules.xml (backup_rules.xml
 * before Android 12); Android only backs them up when the backup is end-to-end encrypted with the screen lock.
 *
 * The password vault on the phone is encrypted with a key that never leaves this phone, so it can't be restored
 * as it is. While a backup runs, the passwords are written out for it (passwords.restore) and removed right after;
 * on the new phone [Passwords.init] encrypts them again with that phone's own key.
 */
class AppBackup : BackupAgent() {
    override fun onFullBackup(data: FullBackupDataOutput) {
        Passwords.init(this)
        val out = File(filesDir, Passwords.RESTORE_FILE)
        try {
            Passwords.writeForBackup(out)
            super.onFullBackup(data)
        } finally {
            out.delete()
        }
    }

    override fun onRestoreFinished() {
        Passwords.restoreFromBackup(this)
    }

    // Key/value backup isn't used: everything goes through the full backup above.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit
}
