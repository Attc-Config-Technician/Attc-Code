package com.attc.opencode;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

/**
 * Read-only bridge that lets another app open a file from the guest home.
 *
 * <p>The files live under the app's private storage, so a {@code file://} URI
 * would be blocked on modern Android. This provider serves the same bytes over
 * {@code content://com.attc.opencode.files/file?path=...} and is granted to the
 * chosen viewer through {@code FLAG_GRANT_READ_URI_PERMISSION}. It is not
 * exported and every path is checked against the guest home and the shared
 * {@code ATTC OPENCODE} folder before it is opened.</p>
 */
public final class FileBridgeProvider extends ContentProvider {

    static final String AUTHORITY = "com.attc.opencode.files";

    private static final String[] TEXT_EXTENSIONS = {
            "c", "cc", "cfg", "conf", "cpp", "css", "go", "h", "hpp", "html",
            "ini", "java", "js", "jsonc", "kt", "log", "md", "py", "rs", "sh",
            "toml", "ts", "txt", "xml", "yaml", "yml",
    };

    static Uri uriFor(File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("file")
                .appendQueryParameter("path", file.getAbsolutePath())
                .build();
    }

    static String mimeOf(File file) {
        String name = file.getName().toLowerCase(Locale.ENGLISH);
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(dot + 1));
            if (mime != null) {
                return mime;
            }
            String ext = name.substring(dot + 1);
            for (String text : TEXT_EXTENSIONS) {
                if (text.equals(ext)) {
                    return "text/plain";
                }
            }
        }
        return "application/octet-stream";
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        File file = resolve(uri);
        if (file == null || !file.isFile()) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        File file = resolve(uri);
        return file == null ? null : mimeOf(file);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        if (file == null) {
            return null;
        }
        String[] columns = projection != null ? projection
                : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                row[i] = file.length();
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        return 0;
    }

    /** Resolves the query parameter and rejects anything outside the two roots. */
    private File resolve(Uri uri) {
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        String path = uri.getQueryParameter("path");
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            File file = new File(path);
            String canonical = file.getCanonicalPath();
            String home = new File(ctx.getFilesDir(), "ubuntu/root").getCanonicalPath();
            String storage = new File(Setup.STORAGE_DIR).getCanonicalPath();
            if (canonical.startsWith(home + File.separator)
                    || canonical.startsWith(storage + File.separator)) {
                return file;
            }
        } catch (Exception ignored) {
            // Unreadable or malformed path: deny it.
        }
        return null;
    }
}
