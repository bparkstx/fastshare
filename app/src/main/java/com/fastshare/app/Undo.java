package com.fastshare.app;

/** Something the user can take back from the "Undo" button on a snackbar. */
interface Undo {
    /** Reverses the action (runs on a background thread). Returns a short message for the user. */
    String run() throws Exception;
}
