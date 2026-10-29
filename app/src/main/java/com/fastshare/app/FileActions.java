package com.fastshare.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Hands files to other apps. */
final class FileActions {
    private FileActions() { }

    static String mimeFor(File f) {
        String ext = FileOps.extension(f.getName());
        String m = ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : FileOps.mimeOfName(f.getName());
    }

    static Uri uriFor(Activity a, File f) {
        return FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", f);
    }

    static void open(Activity a, File f) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uriFor(a, f), mimeFor(f))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, "No app on this phone can open " + f.getName(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, "Couldn't open " + f.getName(), Toast.LENGTH_LONG).show();
        }
    }

    /** Opens the normal Android share sheet (WhatsApp, Drive, email...). Folders are skipped. */
    static void share(Activity a, List<File> files) {
        ArrayList<Uri> uris = new ArrayList<>();
        String type = null;
        for (File f : files) {
            if (!f.isFile()) continue;
            uris.add(uriFor(a, f));
            String m = mimeFor(f);
            if (type == null) type = m;
            else if (!type.equals(m)) type = "*/*";
        }
        if (uris.isEmpty()) {
            Toast.makeText(a, "Folders can't be shared this way. Use Send to iPhone instead.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        i.setType(type == null ? "*/*" : type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(i, "Share"));
    }
}
