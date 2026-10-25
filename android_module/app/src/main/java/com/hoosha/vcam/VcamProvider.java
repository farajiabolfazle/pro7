package com.hoosha.vcam;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileReader;

/**
 * Serves config + the replacement video to hooked apps.
 * Runs inside the MODULE app's process, which has All Files Access,
 * so target apps (Chrome, Open Camera, ...) can read the video
 * without any storage permission — bypasses scoped-storage EACCES.
 *
 *   content://com.hoosha.vcam.provider/config  -> query(): enabled, video path
 *   content://com.hoosha.vcam.provider/video   -> openAssetFile(): fd of the video
 */
public class VcamProvider extends ContentProvider {

    public static final String AUTH = "com.hoosha.vcam.provider";

    @Override
    public boolean onCreate() {
        return true;
    }

    private static String readFile(File f) {
        try {
            if (!f.exists()) return null;
            StringBuilder sb = new StringBuilder();
            FileReader r = new FileReader(f);
            char[] buf = new char[512];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        int enabled = 0;
        String video = "/sdcard/vcam/video.mp4";
        String json = readFile(new File("/sdcard/vcam/config.json"));
        if (json != null) {
            if (json.contains("\"enabled\":true") || json.contains("\"enabled\": true")) enabled = 1;
            int i = json.indexOf("\"video\"");
            if (i >= 0) {
                int x = json.indexOf('"', json.indexOf(':', i) + 1);
                int y = json.indexOf('"', x + 1);
                if (x > 0 && y > x) video = json.substring(x + 1, y);
            }
        }
        MatrixCursor c = new MatrixCursor(new String[]{"enabled", "video"});
        c.addRow(new Object[]{enabled, video});
        return c;
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) {
        String path = "/sdcard/vcam/video.mp4";
        String json = readFile(new File("/sdcard/vcam/config.json"));
        if (json != null) {
            int i = json.indexOf("\"video\"");
            if (i >= 0) {
                int x = json.indexOf('"', json.indexOf(':', i) + 1);
                int y = json.indexOf('"', x + 1);
                if (x > 0 && y > x) path = json.substring(x + 1, y);
            }
        }
        try {
            File f = new File(path);
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            return new AssetFileDescriptor(pfd, 0, f.length());
        } catch (Throwable t) {
            return null;
        }
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
