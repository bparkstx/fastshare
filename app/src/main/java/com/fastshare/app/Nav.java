package com.fastshare.app;

import java.io.File;

/** Lets another screen ask the Files tab to open a folder or category once the user comes back to it. */
final class Nav {
    private Nav() { }

    static volatile File dir;
    static volatile MediaQuery.Category category;

    static boolean pending() {
        return dir != null || category != null;
    }
}
