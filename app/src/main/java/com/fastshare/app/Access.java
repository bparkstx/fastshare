package com.fastshare.app;

import android.os.Environment;

/** Whether the user has granted "All files access", which the file manager needs. */
final class Access {
    private Access() { }

    /** Tests can force the answer. */
    static volatile Boolean testOverride;

    static boolean allFiles() {
        Boolean forced = testOverride;
        return forced != null ? forced : Environment.isExternalStorageManager();
    }
}
