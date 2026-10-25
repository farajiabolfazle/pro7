package com.hoosha.vcam;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Minimal control UI: pick a video from storage, copy it to
 * /sdcard/vcam/video.mp4 and enable injection by writing config.json.
 * The same can be done from the Windows controller over ADB.
 */
public class MainActivity extends Activity {

    private static final int PICK = 42;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setText("VCam Injector\nConfig: /sdcard/vcam/config.json");

        Button pick = new Button(this);
        pick.setText("Select video");
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("video/*");
            startActivityForResult(i, PICK);
        });

        Button on = new Button(this);
        on.setText("Enable injection");
        on.setOnClickListener(v -> writeConfig(true));

        Button off = new Button(this);
        off.setText("Disable injection");
        off.setOnClickListener(v -> writeConfig(false));

        root.addView(status);
        root.addView(pick);
        root.addView(on);
        root.addView(off);
        setContentView(root);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), "vcam");
                dir.mkdirs();
                File out = new File(dir, "video.mp4");
                InputStream in = getContentResolver().openInputStream(uri);
                OutputStream os = new FileOutputStream(out);
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                in.close(); os.close();
                status.setText("Video saved: " + out.getAbsolutePath());
            } catch (Exception e) {
                Toast.makeText(this, "Copy failed: " + e, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void writeConfig(boolean enabled) {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "vcam");
            dir.mkdirs();
            File f = new File(dir, "config.json");
            FileOutputStream os = new FileOutputStream(f);
            os.write(("{\"enabled\":" + enabled +
                    ",\"video\":\"/sdcard/vcam/video.mp4\"}").getBytes());
            os.close();
            status.setText("Injection " + (enabled ? "ENABLED" : "DISABLED") +
                    "\nForce-stop the target app to apply.");
        } catch (Exception e) {
            Toast.makeText(this, "Config failed: " + e, Toast.LENGTH_LONG).show();
        }
    }
}
