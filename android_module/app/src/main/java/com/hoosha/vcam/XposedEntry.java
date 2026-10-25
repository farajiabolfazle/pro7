package com.hoosha.vcam;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaPlayer;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * VCam — virtual camera module.
 *
 * Architecture follows the proven android_virtual_cam approach:
 *  1. Hook CameraManager.openCamera -> grab the app's StateCallback class.
 *  2. Hook onOpened on that class -> hook the REAL CameraDevice instance
 *     class (vendor subclass friendly) and replace every capture-session
 *     surface list with a single "garbage" virtual surface we own.
 *  3. Hook CaptureRequest.Builder.addTarget to learn the app's real
 *     preview / ImageReader surfaces and redirect capture targets to the
 *     garbage surface.
 *  4. Hook CaptureRequest.Builder.build -> start playback:
 *     MediaPlayer (looping) renders the video into the preview surface,
 *     VideoToFrames decodes frames into ImageReader surfaces so photo
 *     capture keeps working.
 *  5. Camera1: fake SurfaceTexture swap + MediaPlayer into the real
 *     preview, NV21 frames fed to preview callbacks.
 *
 * Controlled by /sdcard/vcam/config.json:
 *   {"enabled": true, "video": "/sdcard/vcam/video.mp4"}
 * Falls back to <app-private>/vcam/config.json when /sdcard is unreadable.
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String SELF = "com.hoosha.vcam";
    private static final String CONFIG_MAIN = "/sdcard/vcam/config.json";

    // ---- config ----
    private static volatile boolean enabled = false;
    private static volatile String videoPath = "/sdcard/vcam/video.mp4";
    private static volatile String configError = "not read yet";
    private static android.content.Context appContext;

    // ---- camera2 state ----
    private static SurfaceTexture virtualSt;
    private static Surface virtualSurface;
    private static boolean needRecreate = true;

    private static Surface previewSurface, previewSurface1;
    private static Surface readerSurface, readerSurface1;
    private static MediaPlayer player, player1;
    private static VideoToFrames decoder, decoder1;
    private static Surface playerSurface, player1Surface, decoderSurface, decoder1Surface;
    private static CaptureRequest.Builder lastBuilder;
    private static CameraDevice.StateCallback stateCb;
    private static int imageReaderFormat = 0;

    private static final Set<Class<?>> hookedCallbacks = new HashSet<>();
    private static final Set<Class<?>> hookedDevices = new HashSet<>();

    // ---- camera1 state ----
    private static Camera originCamera;
    private static SurfaceTexture realTexture;
    private static SurfaceTexture fakeTexture;
    private static SurfaceTexture c1FakeTexture;
    private static Surface c1FakeSurface;
    private static SurfaceHolder oriHolder;
    private static MediaPlayer c1Player;
    private static boolean isHooked;
    private static VideoToFrames c1Decoder;
    private static Camera callbackCamera;
    private static final Set<Class<?>> hookedPreviewCbs = new HashSet<>();

    /** Latest decoded NV21 frame, written by VideoToFrames, read by Camera1 callbacks. */
    public static volatile byte[] dataBuffer = {0};

    // ================= config =================

    private static void reloadConfig() {
        configError = null;
        String json = readFile(new File(CONFIG_MAIN));
        if (json == null && appContext != null) {
            try {
                File alt = new File(appContext.getExternalFilesDir(null), "vcam/config.json");
                json = readFile(alt);
            } catch (Throwable ignored) {}
        }
        if (json == null) {
            enabled = false;
            configError = "config.json not readable";
            return;
        }
        enabled = json.contains("\"enabled\":true") || json.contains("\"enabled\": true");
        int i = json.indexOf("\"video\"");
        if (i >= 0) {
            int a = json.indexOf('"', json.indexOf(':', i) + 1);
            int b = json.indexOf('"', a + 1);
            if (a > 0 && b > a) videoPath = json.substring(a + 1, b);
        }
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

    private static boolean active(String pkg) {
        if (appContext != null) {
            try {
                Cursor c = appContext.getContentResolver().query(
                        Uri.parse("content://" + VcamProvider.AUTH + "/config"), null, null, null, null);
                if (c != null) {
                    if (c.moveToFirst()) {
                        enabled = c.getInt(0) == 1;
                        videoPath = c.getString(1);
                    }
                    c.close();
                    if (enabled) return true;
                    return false;
                }
            } catch (Throwable ignored) {}
        }
        reloadConfig();
        if (configError != null) {
            XposedBridge.log("vcam: " + pkg + " config: " + configError);
            return false;
        }
        if (!enabled) return false;
        if (!new File(videoPath).exists()) {
            // try app-private fallback for the video too
            if (appContext != null) {
                try {
                    File alt = new File(appContext.getExternalFilesDir(null), "vcam/" + new File(videoPath).getName());
                    if (alt.exists()) {
                        videoPath = alt.getAbsolutePath();
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
            XposedBridge.log("vcam: " + pkg + " video missing: " + videoPath);
            return false;
        }
        return true;
    }

    // ================= entry =================

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName.equals(SELF)) return;
        if (lpparam.packageName.equals("android")) return;
        final String pkg = lpparam.packageName;
        XposedBridge.log("vcam: module loaded into " + pkg);

        // ---- grab a Context for the private-dir fallback ----
        try {
            XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader,
                    "callApplicationOnCreate", android.app.Application.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                appContext = ((android.app.Application) param.args[0]).getApplicationContext();
                                File d = new File(Environment.getExternalStorageDirectory(), "vcam");
                                if (!d.exists()) d.mkdirs();
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {}

        // ---- Camera2: openCamera(String, StateCallback, Handler) ----
        try {
            XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader,
                    "openCamera", String.class, CameraDevice.StateCallback.class, Handler.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[1] == null || param.args[1] == stateCb) return;
                            if (!active(pkg)) return;
                            stateCb = (CameraDevice.StateCallback) param.args[1];
                            Class<?> cbClass = param.args[1].getClass();
                            XposedBridge.log("vcam: openCamera (handler) in " + pkg + " cb=" + cbClass.getName());
                            hookCamera2Init(cbClass, pkg);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("vcam: openCamera hook1 missing " + t);
        }

        // ---- Camera2: openCamera(String, Executor, StateCallback) (API 28+) ----
        try {
            XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader,
                    "openCamera", String.class, Executor.class, CameraDevice.StateCallback.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[2] == null || param.args[2] == stateCb) return;
                            if (!active(pkg)) return;
                            stateCb = (CameraDevice.StateCallback) param.args[2];
                            Class<?> cbClass = param.args[2].getClass();
                            XposedBridge.log("vcam: openCamera (executor) in " + pkg + " cb=" + cbClass.getName());
                            hookCamera2Init(cbClass, pkg);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("vcam: openCamera hook2 missing " + t);
        }

        // ---- learn the app's real surfaces from capture targets ----
        try {
            XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest$Builder", lpparam.classLoader,
                    "addTarget", Surface.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] == null) return;
                            if (!active(pkg)) return;
                            if (virtualSurface == null) return;
                            Surface s = (Surface) param.args[0];
                            if (s.equals(virtualSurface)) return;
                            String info = s.toString();
                            if (info.contains("Surface(name=null)") || info.contains("ImageReader")) {
                                if (readerSurface == null) {
                                    readerSurface = s;
                                } else if (!readerSurface.equals(s) && readerSurface1 == null) {
                                    readerSurface1 = s;
                                }
                            } else {
                                if (previewSurface == null) {
                                    previewSurface = s;
                                } else if (!previewSurface.equals(s) && previewSurface1 == null) {
                                    previewSurface1 = s;
                                }
                            }
                            XposedBridge.log("vcam: addTarget " + info + " -> virtual");
                            param.args[0] = virtualSurface;
                        }
                    });

            XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest$Builder", lpparam.classLoader,
                    "removeTarget", Surface.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] == null) return;
                            Surface s = (Surface) param.args[0];
                            if (s.equals(previewSurface)) previewSurface = null;
                            if (s.equals(previewSurface1)) previewSurface1 = null;
                            if (s.equals(readerSurface)) readerSurface = null;
                            if (s.equals(readerSurface1)) readerSurface1 = null;
                        }
                    });

            XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest$Builder", lpparam.classLoader,
                    "build", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject == null || param.thisObject == lastBuilder) return;
                            if (!active(pkg)) return;
                            lastBuilder = (CaptureRequest.Builder) param.thisObject;
                            XposedBridge.log("vcam: build() -> start playback");
                            startCamera2Playback();
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("vcam: CaptureRequest.Builder hooks missing " + t);
        }

        // ---- learn ImageReader format (JPEG=256) for photo decode ----
        try {
            XposedHelpers.findAndHookMethod("android.media.ImageReader", lpparam.classLoader,
                    "newInstance", int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            imageReaderFormat = (int) param.args[2];
                            XposedBridge.log("vcam: ImageReader created " + param.args[0] + "x" + param.args[1]
                                    + " fmt=" + imageReaderFormat);
                        }
                    });
        } catch (Throwable ignored) {}

        // ---- log capture failures ----
        try {
            XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraCaptureSession$CaptureCallback",
                    lpparam.classLoader, "onCaptureFailed", CameraCaptureSession.class,
                    CaptureRequest.class, CaptureFailure.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log("vcam: onCaptureFailed reason="
                                    + ((CaptureFailure) param.args[2]).getReason());
                        }
                    });
        } catch (Throwable ignored) {}

        hookCamera1(lpparam, pkg);
    }

    // ================= camera2 core =================

    private static Surface createVirtualSurface() {
        if (needRecreate) {
            if (virtualSt != null) { virtualSt.release(); virtualSt = null; }
            if (virtualSurface != null) { virtualSurface.release(); virtualSurface = null; }
            virtualSt = new SurfaceTexture(15);
            virtualSurface = new Surface(virtualSt);
            needRecreate = false;
        } else if (virtualSurface == null) {
            needRecreate = true;
            return createVirtualSurface();
        }
        XposedBridge.log("vcam: virtual surface ready " + virtualSurface);
        return virtualSurface;
    }

    private void hookCamera2Init(final Class<?> callbackClass, final String pkg) {
        synchronized (hookedCallbacks) {
            if (hookedCallbacks.contains(callbackClass)) return;
            hookedCallbacks.add(callbackClass);
        }

        XposedHelpers.findAndHookMethod(callbackClass, "onOpened", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!active(pkg)) return;
                XposedBridge.log("vcam: camera opened, (re)building virtual surface");
                needRecreate = true;
                createVirtualSurface();
                stopCamera2Playback();
                previewSurface = previewSurface1 = null;
                readerSurface = readerSurface1 = null;
                lastBuilder = null;

                Class<?> deviceClass = param.args[0].getClass();
                hookDeviceClass(deviceClass, pkg);
            }
        });

        try {
            XposedHelpers.findAndHookMethod(callbackClass, "onError", CameraDevice.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log("vcam: camera onError " + param.args[1]);
                        }
                    });
        } catch (Throwable ignored) {}
    }

    private void hookDeviceClass(Class<?> deviceClass, final String pkg) {
        synchronized (hookedDevices) {
            if (hookedDevices.contains(deviceClass)) return;
            hookedDevices.add(deviceClass);
        }
        XposedBridge.log("vcam: hooking device class " + deviceClass.getName());

        // createCaptureSession(List<Surface>, StateCallback, Handler)
        try {
            XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession",
                    List.class, CameraCaptureSession.StateCallback.class, Handler.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] == null || !active(pkg) || virtualSurface == null) return;
                            XposedBridge.log("vcam: createCaptureSession(List) -> virtual " + param.args[0]);
                            param.args[0] = Arrays.asList(virtualSurface);
                            hookSessionCallback((CameraCaptureSession.StateCallback) param.args[1]);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("vcam: no createCaptureSession(List) on " + deviceClass.getName());
        }

        // createCaptureSessionByOutputConfigurations(List, StateCallback, Handler)
        try {
            XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSessionByOutputConfigurations",
                    List.class, CameraCaptureSession.StateCallback.class, Handler.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] == null || !active(pkg) || virtualSurface == null) return;
                            XposedBridge.log("vcam: createCaptureSessionByOutputConfigurations -> virtual");
                            param.args[0] = Arrays.asList(new OutputConfiguration(virtualSurface));
                            hookSessionCallback((CameraCaptureSession.StateCallback) param.args[1]);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("vcam: no createCaptureSessionByOutputConfigurations on " + deviceClass.getName());
        }

        // createCaptureSession(SessionConfiguration) (API 28+)
        try {
            XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession",
                    SessionConfiguration.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] == null || !active(pkg) || virtualSurface == null) return;
                            XposedBridge.log("vcam: createCaptureSession(SessionConfiguration) -> virtual");
                            SessionConfiguration orig = (SessionConfiguration) param.args[0];
                            param.args[0] = new SessionConfiguration(orig.getSessionType(),
                                    Arrays.asList(new OutputConfiguration(virtualSurface)),
                                    orig.getExecutor(), orig.getStateCallback());
                            hookSessionCallback(orig.getStateCallback());
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("vcam: no createCaptureSession(SessionConfiguration) on " + deviceClass.getName());
        }

        // createConstrainedHighSpeedCaptureSession(List, StateCallback, Handler)
        try {
            XposedHelpers.findAndHookMethod(deviceClass, "createConstrainedHighSpeedCaptureSession",
                    List.class, CameraCaptureSession.StateCallback.class, Handler.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] == null || !active(pkg) || virtualSurface == null) return;
                            XposedBridge.log("vcam: createConstrainedHighSpeedCaptureSession -> virtual");
                            param.args[0] = Arrays.asList(virtualSurface);
                            hookSessionCallback((CameraCaptureSession.StateCallback) param.args[1]);
                        }
                    });
        } catch (Throwable ignored) {}

        // createReprocessableCaptureSession(InputConfiguration, List, StateCallback, Handler)
        try {
            XposedHelpers.findAndHookMethod(deviceClass, "createReprocessableCaptureSession",
                    InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class,
                    Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[1] == null || !active(pkg) || virtualSurface == null) return;
                            param.args[1] = Arrays.asList(virtualSurface);
                            hookSessionCallback((CameraCaptureSession.StateCallback) param.args[2]);
                        }
                    });
        } catch (Throwable ignored) {}
    }

    private static final Set<Class<?>> hookedSessionCbs = new HashSet<>();

    private void hookSessionCallback(CameraCaptureSession.StateCallback cb) {
        if (cb == null) return;
        Class<?> cls = cb.getClass();
        synchronized (hookedSessionCbs) {
            if (hookedSessionCbs.contains(cls)) return;
            hookedSessionCbs.add(cls);
        }
        try {
            XposedHelpers.findAndHookMethod(cls, "onConfigured", CameraCaptureSession.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log("vcam: session onConfigured");
                        }
                    });
            XposedHelpers.findAndHookMethod(cls, "onConfigureFailed", CameraCaptureSession.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log("vcam: session onConfigureFailed!");
                        }
                    });
            XposedHelpers.findAndHookMethod(cls, "onClosed", CameraCaptureSession.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log("vcam: session onClosed");
                        }
                    });
        } catch (Throwable ignored) {}
    }

    private static void stopCamera2Playback() {
        if (player != null) { try { player.release(); } catch (Throwable ignored) {} player = null; }
        if (player1 != null) { try { player1.release(); } catch (Throwable ignored) {} player1 = null; }
        if (decoder != null) { decoder.stopDecode(); decoder = null; }
        if (decoder1 != null) { decoder1.stopDecode(); decoder1 = null; }
        playerSurface = player1Surface = decoderSurface = decoder1Surface = null;
    }

    private static AssetFileDescriptor openVideoFd() {
        if (appContext == null) return null;
        try {
            return appContext.getContentResolver().openAssetFileDescriptor(
                    Uri.parse("content://" + VcamProvider.AUTH + "/video"), "r");
        } catch (Throwable t) {
            return null;
        }
    }

    private static MediaPlayer loopInto(Surface s) {
        MediaPlayer mp = new MediaPlayer();
        mp.setSurface(s);
        mp.setVolume(0, 0);
        mp.setLooping(true);
        try {
            mp.setOnPreparedListener(MediaPlayer::start);
            AssetFileDescriptor afd = openVideoFd();
            if (afd != null) {
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                try { afd.close(); } catch (Throwable ignored) {}
            } else {
                mp.setDataSource(videoPath);
            }
            mp.prepare();
        } catch (Throwable t) {
            XposedBridge.log("vcam: player error " + t);
        }
        return mp;
    }

    private static void startCamera2Playback() {
        if (readerSurface != null && (decoder == null || decoderSurface != readerSurface)) {
            if (decoder != null) { decoder.stopDecode(); decoder = null; }
            decoder = new VideoToFrames();
            decoderSurface = readerSurface;
            try {
                decoder.setSaveFrames("null",
                        imageReaderFormat == 256 ? OutputImageFormat.JPEG : OutputImageFormat.NV21);
                decoder.setSource(openVideoFd());
                decoder.setSurface(readerSurface);
                decoder.decode(videoPath);
                XposedBridge.log("vcam: decoder feeding reader surface (fmt=" + imageReaderFormat + ")");
            } catch (Throwable t) {
                XposedBridge.log("vcam: reader decoder error " + t);
            }
        }
        if (readerSurface1 != null && (decoder1 == null || decoder1Surface != readerSurface1)) {
            if (decoder1 != null) { decoder1.stopDecode(); decoder1 = null; }
            decoder1 = new VideoToFrames();
            decoder1Surface = readerSurface1;
            try {
                decoder1.setSaveFrames("null",
                        imageReaderFormat == 256 ? OutputImageFormat.JPEG : OutputImageFormat.NV21);
                decoder1.setSource(openVideoFd());
                decoder1.setSurface(readerSurface1);
                decoder1.decode(videoPath);
                XposedBridge.log("vcam: decoder1 feeding reader surface (fmt=" + imageReaderFormat + ")");
            } catch (Throwable t) {
                XposedBridge.log("vcam: reader1 decoder error " + t);
            }
        }
        if (previewSurface != null && (player == null || playerSurface != previewSurface)) {
            if (player != null) { try { player.release(); } catch (Throwable ignored) {} }
            playerSurface = previewSurface;
            player = loopInto(previewSurface);
        }
        if (previewSurface1 != null && (player1 == null || player1Surface != previewSurface1)) {
            if (player1 != null) { try { player1.release(); } catch (Throwable ignored) {} }
            player1Surface = previewSurface1;
            player1 = loopInto(previewSurface1);
        }
        XposedBridge.log("vcam: playback state preview=" + (previewSurface != null)
                + " preview1=" + (previewSurface1 != null)
                + " reader=" + (readerSurface != null)
                + " reader1=" + (readerSurface1 != null));
    }

    // ================= camera1 =================

    private void hookCamera1(XC_LoadPackage.LoadPackageParam lpparam, final String pkg) {
        // setPreviewDisplay(SurfaceHolder): swap to a fake texture, remember the real holder
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "setPreviewDisplay", SurfaceHolder.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!active(pkg)) return;
                            oriHolder = (SurfaceHolder) param.args[0];
                            if (oriHolder == null) return;
                            Camera cam = (Camera) param.thisObject;
                            if (c1FakeTexture != null) c1FakeTexture.release();
                            c1FakeTexture = new SurfaceTexture(11);
                            if (c1FakeSurface != null) c1FakeSurface.release();
                            c1FakeSurface = new Surface(c1FakeTexture);
                            isHooked = true;
                            cam.setPreviewTexture(c1FakeTexture);
                            param.setResult(null);
                            XposedBridge.log("vcam: camera1 setPreviewDisplay swapped to fake texture");
                        }
                    });
        } catch (Throwable ignored) {}

        // setPreviewTexture(SurfaceTexture): swap with our fake, remember the real one
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "setPreviewTexture", SurfaceTexture.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!active(pkg)) return;
                            if (param.args[0] == null || param.args[0] == c1FakeTexture) return;
                            if (isHooked) { isHooked = false; return; }
                            if (originCamera != null && originCamera.equals(param.thisObject)) {
                                param.args[0] = fakeTexture;
                                return;
                            }
                            originCamera = (Camera) param.thisObject;
                            realTexture = (SurfaceTexture) param.args[0];
                            if (fakeTexture != null) fakeTexture.release();
                            fakeTexture = new SurfaceTexture(10);
                            param.args[0] = fakeTexture;
                            XposedBridge.log("vcam: camera1 setPreviewTexture swapped");
                        }
                    });
        } catch (Throwable ignored) {}

        // startPreview: begin MediaPlayer playback into the real preview surface
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "startPreview", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!active(pkg)) return;
                            if (oriHolder != null && oriHolder.getSurface() != null
                                    && oriHolder.getSurface().isValid()) {
                                if (c1Player != null) { try { c1Player.release(); } catch (Throwable ignored) {} }
                                c1Player = loopInto(oriHolder.getSurface());
                                XposedBridge.log("vcam: camera1 playing into SurfaceHolder");
                            }
                            if (realTexture != null) {
                                if (player != null) { try { player.release(); } catch (Throwable ignored) {} }
                                player = loopInto(new Surface(realTexture));
                                XposedBridge.log("vcam: camera1 playing into SurfaceTexture");
                            }
                        }
                    });
        } catch (Throwable ignored) {}

        // preview callbacks: feed NV21 video frames
        XC_MethodHook cbHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] == null) return;
                hookPreviewCallback(param.args[0].getClass(), pkg);
            }
        };
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "setPreviewCallback", Camera.PreviewCallback.class, cbHook);
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "setPreviewCallbackWithBuffer", Camera.PreviewCallback.class, cbHook);
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "setOneShotPreviewCallback", Camera.PreviewCallback.class, cbHook);
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "addCallbackBuffer", byte[].class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] != null && active(pkg)) {
                                param.args[0] = new byte[((byte[]) param.args[0]).length];
                            }
                        }
                    });
        } catch (Throwable ignored) {}
    }

    private void hookPreviewCallback(final Class<?> cbClass, final String pkg) {
        synchronized (hookedPreviewCbs) {
            if (hookedPreviewCbs.contains(cbClass)) return;
            hookedPreviewCbs.add(cbClass);
        }
        XposedHelpers.findAndHookMethod(cbClass, "onPreviewFrame", byte[].class, Camera.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Camera cam = (Camera) param.args[1];
                        if (!cam.equals(callbackCamera)) {
                            callbackCamera = cam;
                            int w = cam.getParameters().getPreviewSize().width;
                            int h = cam.getParameters().getPreviewSize().height;
                            XposedBridge.log("vcam: camera1 preview callback init " + w + "x" + h
                                    + " (video must match)");
                            if (!active(pkg)) return;
                            if (c1Decoder != null) c1Decoder.stopDecode();
                            c1Decoder = new VideoToFrames();
                            try {
                                c1Decoder.setSaveFrames("", OutputImageFormat.NV21);
                                c1Decoder.decode(videoPath);
                            } catch (Throwable t) {
                                XposedBridge.log("vcam: camera1 decoder error " + t);
                            }
                        }
                        while (dataBuffer == null) { /* wait for first frame */ }
                        System.arraycopy(dataBuffer, 0, param.args[0], 0,
                                Math.min(dataBuffer.length, ((byte[]) param.args[0]).length));
                    }
                });
    }
}
