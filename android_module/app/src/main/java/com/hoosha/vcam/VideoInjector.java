package com.hoosha.vcam;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XposedBridge;

/**
 * Decodes the configured MP4 with MediaCodec into an external OES texture and
 * renders it, looping, into every Surface the target app registered as a
 * camera output (preview, MediaRecorder/MediaCodec encoder, ImageReader).
 *
 * Live control: a watcher thread polls /sdcard/vcam/control.json every 300 ms:
 *   {"playing": true, "position_ms": 12345, "speed": 1.0,
 *    "video": "/sdcard/vcam/video.mp4"}
 * - playing=false freezes on the last frame (recording continues)
 * - position_ms far from the current pts triggers a seek
 * - speed scales frame pacing 0.25x - 4x
 */
public class VideoInjector implements SurfaceTexture.OnFrameAvailableListener {

    private static final Map<String, VideoInjector> ACTIVE = new ConcurrentHashMap<>();
    private static final String CONTROL = "/sdcard/vcam/control.json";

    /** Shared live-control state, updated by the watcher thread. */
    static class Ctrl {
        static volatile boolean playing = true;
        static volatile float speed = 1f;
        static volatile long positionMs = -1;
        static volatile String video = null;
    }

    private static volatile boolean watcherStarted = false;

    public static void attach(String pkg, Surface target, String videoPath) {
        if (target == null || !target.isValid()) return;
        startWatcher();
        VideoInjector inj = ACTIVE.get(pkg);
        if (inj == null) {
            inj = new VideoInjector(videoPath);
            ACTIVE.put(pkg, inj);
            new Thread(inj::run, "vcam-render").start();
        }
        inj.addTarget(target);
    }

    public static void detachAll(String pkg) {
        VideoInjector inj = ACTIVE.remove(pkg);
        if (inj != null) inj.stop();
    }

    private static void startWatcher() {
        if (watcherStarted) return;
        watcherStarted = true;
        new Thread(() -> {
            while (true) {
                try {
                    java.io.File f = new java.io.File(CONTROL);
                    if (f.exists()) {
                        StringBuilder sb = new StringBuilder();
                        java.io.FileReader r = new java.io.FileReader(f);
                        char[] b = new char[512];
                        int n;
                        while ((n = r.read(b)) > 0) sb.append(b, 0, n);
                        r.close();
                        org.json.JSONObject o = new org.json.JSONObject(sb.toString());
                        Ctrl.playing = o.optBoolean("playing", true);
                        Ctrl.speed = (float) o.optDouble("speed", 1.0);
                        Ctrl.positionMs = o.optLong("position_ms", -1);
                        String v = o.optString("video", "");
                        if (!v.isEmpty()) Ctrl.video = v;
                    }
                } catch (Throwable ignored) {}
                try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            }
        }, "vcam-ctrl").start();
    }

    // ------------------------------------------------------------------

    private final String videoPath;
    private final List<Surface> targets = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;

    private VideoInjector(String videoPath) { this.videoPath = videoPath; }
    private void addTarget(Surface s) { targets.add(s); }
    private void stop() { running = false; }

    // --- decode-side texture ---
    private SurfaceTexture decodeSt;
    private final Object frameLock = new Object();
    private boolean frameAvailable;
    private boolean haveFrame;
    private final float[] texMatrix = new float[16];

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        synchronized (frameLock) { frameAvailable = true; frameLock.notify(); }
    }

    private void run() {
        int texId = createOesTexture();
        decodeSt = new SurfaceTexture(texId);
        decodeSt.setOnFrameAvailableListener(this);
        Surface decodeSurface = new Surface(decodeSt);

        Thread decoder = new Thread(() -> decodeLoop(decodeSurface), "vcam-decode");
        decoder.start();

        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(display, ver, 0, ver, 1);
        int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0);
        EGLContext ctx = EGL14.eglCreateContext(display, configs[0],
                EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);

        int prog = buildProgram();
        Map<Surface, EGLSurface> windows = new ConcurrentHashMap<>();

        while (running) {
            synchronized (frameLock) {
                if (!frameAvailable) {
                    try { frameLock.wait(33); } catch (InterruptedException ignored) {}
                }
                frameAvailable = false;
            }
            try {
                decodeSt.updateTexImage();
                decodeSt.getTransformMatrix(texMatrix);
                haveFrame = true;
            } catch (RuntimeException e) {
                // no new frame: keep last texture (paused) or wait for first
            }
            if (!haveFrame) continue;

            long nowNs = System.nanoTime();
            for (Surface target : targets) {
                if (!target.isValid()) { targets.remove(target); continue; }
                try {
                    EGLSurface win = windows.get(target);
                    if (win == null) {
                        win = EGL14.eglCreateWindowSurface(display, configs[0],
                                target, new int[]{EGL14.EGL_NONE}, 0);
                        if (win == EGL14.EGL_NO_SURFACE) continue;
                        windows.put(target, win);
                    }
                    EGL14.eglMakeCurrent(display, win, win, ctx);
                    drawFrame(prog, texId, texMatrix);
                    EGLExt.eglPresentationTimeANDROID(display, win, nowNs);
                    EGL14.eglSwapBuffers(display, win);
                } catch (Throwable t) {
                    XposedBridge.log("vcam: render error " + t);
                }
            }
        }

        for (EGLSurface w : windows.values()) EGL14.eglDestroySurface(display, w);
        EGL14.eglDestroyContext(display, ctx);
        EGL14.eglTerminate(display);
        decoder.interrupt();
    }

    private void decodeLoop(Surface out) {
        while (running) {
            String path = Ctrl.video != null ? Ctrl.video : videoPath;
            MediaExtractor ex = new MediaExtractor();
            MediaCodec dec = null;
            try {
                ex.setDataSource(path);
                int track = -1;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) { track = i; break; }
                }
                if (track < 0) { ex.release(); return; }
                ex.selectTrack(track);
                MediaFormat fmt = ex.getTrackFormat(track);
                dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
                dec.configure(fmt, out, null, 0);
                dec.start();

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean eos = false, feeding = true;
                long ptsBase = -1, wallBase = 0, curPtsMs = 0;

                while (running && !eos) {
                    // pause: hold the last frame, decoder idles
                    if (!Ctrl.playing) { Thread.sleep(60); continue; }

                    // live seek when requested position drifts from current pts
                    long want = Ctrl.positionMs;
                    if (want >= 0 && Math.abs(want - curPtsMs) > 1500) {
                        dec.flush();
                        ex.seekTo(want * 1000, MediaExtractor.SEEK_TO_PREV_SYNC);
                        ptsBase = -1;
                        feeding = true;
                        curPtsMs = want;
                        continue;
                    }

                    // video file swapped from the controller
                    if (Ctrl.video != null && !Ctrl.video.equals(path)) break;

                    if (feeding) {
                        int in = dec.dequeueInputBuffer(10000);
                        if (in >= 0) {
                            ByteBuffer buf = dec.getInputBuffer(in);
                            int size = ex.readSampleData(buf, 0);
                            if (size < 0) {
                                dec.queueInputBuffer(in, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                feeding = false;
                            } else {
                                dec.queueInputBuffer(in, 0, size,
                                        ex.getSampleTime(), 0);
                                ex.advance();
                            }
                        }
                    }

                    int outIdx = dec.dequeueOutputBuffer(info, 10000);
                    if (outIdx >= 0) {
                        long pts = info.presentationTimeUs;
                        curPtsMs = pts / 1000;
                        if (ptsBase < 0) { ptsBase = pts; wallBase = System.nanoTime(); }
                        float sp = Math.max(Ctrl.speed, 0.05f);
                        long target = wallBase + (long) ((pts - ptsBase) * 1000 / sp);
                        long sleepMs = (target - System.nanoTime()) / 1000000;
                        if (sleepMs > 0) Thread.sleep(Math.min(sleepMs, 2000));
                        dec.releaseOutputBuffer(outIdx, info.size > 0);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                            eos = true;
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("vcam: decode error " + t);
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            } finally {
                if (dec != null) {
                    try { dec.stop(); dec.release(); } catch (Throwable ignored) {}
                }
                ex.release();
            }
            // loop the video
        }
    }

    // --- minimal GL helpers ---

    private static final String VS =
            "uniform mat4 uTex;\nattribute vec4 aPos;\nattribute vec4 aTexPos;\n" +
            "varying vec2 vTex;\n" +
            "void main(){ gl_Position=aPos; vTex=(uTex*aTexPos).xy; }";
    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\nuniform samplerExternalOES sTex;\n" +
            "void main(){ gl_FragColor=texture2D(sTex,vTex); }";

    private static final float[] QUAD =
            {-1,-1, 0,0,  1,-1, 1,0,  -1,1, 0,1,  1,1, 1,1};

    private void drawFrame(int prog, int texId, float[] mat) {
        FloatBuffer vb = ByteBuffer.allocateDirect(QUAD.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vb.put(QUAD).position(0);
        GLES20.glUseProgram(prog);
        int aPos = GLES20.glGetAttribLocation(prog, "aPos");
        int aTex = GLES20.glGetAttribLocation(prog, "aTexPos");
        int uTex = GLES20.glGetUniformLocation(prog, "uTex");
        vb.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vb);
        vb.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vb);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glUniformMatrix4fv(uTex, 1, false, mat, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static int buildProgram() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VS);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private static int createOesTexture() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        return t[0];
    }
}
