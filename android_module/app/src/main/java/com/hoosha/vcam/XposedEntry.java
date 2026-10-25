package com.hoosha.vcam;

import android.media.ImageFormat;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.io.File;
import java.io.FileReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed entry point. Hooks camera session creation inside the target app,
 * REPLACES every output Surface with a drained dummy (so the real camera
 * frames go nowhere), and feeds frames decoded from /sdcard/vcam/video.mp4
 * into the app's ORIGINAL surfaces (preview, recorder, ...).
 * Controlled by /sdcard/vcam/config.json:
 *   {"enabled": true, "video": "/sdcard/vcam/video.mp4"}
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String SELF = "com.hoosha.vcam";
    private static final String CONFIG = "/sdcard/vcam/config.json";

    private static volatile boolean enabled = false;
    private static volatile String videoPath = "/sdcard/vcam/video.mp4";
    private static volatile String lastConfigError = null;

    /** Dummy consumers that swallow the real camera frames, per package. */
    private static final Map<String, List<ImageReader>> DUMMIES = new ConcurrentHashMap<>();
    private static HandlerThread drainThread;
    private static Handler drainHandler;

    private static void reloadConfig() {
        lastConfigError = null;
        try {
            File f = new File(CONFIG);
            if (!f.exists()) {
                enabled = false;
                lastConfigError = "config.json not found";
                return;
            }
            StringBuilder sb = new StringBuilder();
            FileReader r = new FileReader(f);
            char[] buf = new char[512];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();
            String json = sb.toString();
            enabled = json.contains("\"enabled\":true") || json.contains("\"enabled\": true");
            int i = json.indexOf("\"video\"");
            if (i >= 0) {
                int a = json.indexOf('"', json.indexOf(':', i) + 1);
                int b = json.indexOf('"', a + 1);
                if (a > 0 && b > a) videoPath = json.substring(a + 1, b);
            }
        } catch (Throwable t) {
            enabled = false;
            lastConfigError = String.valueOf(t);
        }
    }

    private static void logConfigState(String pkg) {
        if (lastConfigError != null) {
            XposedBridge.log("vcam: " + pkg + " config ERROR: " + lastConfigError);
        } else {
            XposedBridge.log("vcam: " + pkg + " config enabled=" + enabled + " video=" + videoPath);
        }
    }

    private static synchronized void ensureDrainThread() {
        if (drainThread != null) return;
        drainThread = new HandlerThread("vcam-drain");
        drainThread.start();
        drainHandler = new Handler(drainThread.getLooper());
    }

    /** Create a dummy consumer surface; real camera frames land here and are discarded. */
    private static Surface makeDummy(String pkg) {
        try {
            ensureDrainThread();
            ImageReader reader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 4);
            reader.setOnImageAvailableListener(r -> {
                try {
                    android.media.Image img = r.acquireLatestImage();
                    if (img != null) img.close();
                } catch (Throwable ignored) {}
            }, drainHandler);
            DUMMIES.computeIfAbsent(pkg, k -> new CopyOnWriteArrayList<>()).add(reader);
            return reader.getSurface();
        } catch (Throwable t) {
            XposedBridge.log("vcam: dummy create failed " + t);
            return null;
        }
    }

    private static void releaseDummies(String pkg) {
        List<ImageReader> list = DUMMIES.remove(pkg);
        if (list == null) return;
        for (ImageReader r : list) {
            try { r.close(); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName.equals(SELF)) return;
        if (lpparam.packageName.equals("android")) return;

        XposedBridge.log("vcam: module loaded into " + lpparam.packageName);

        // ---- Camera2: CameraDeviceImpl.createCaptureSession(SessionConfiguration) ----
        try {
            XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.impl.CameraDeviceImpl",
                    lpparam.classLoader,
                    "createCaptureSession",
                    "android.hardware.camera2.params.SessionConfiguration",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log("vcam: createCaptureSession(SessionConfiguration) fired in " + lpparam.packageName);
                            reloadConfig();
                            logConfigState(lpparam.packageName);
                            if (!enabled) return;
                            try {
                                Object config = param.args[0];
                                @SuppressWarnings("unchecked")
                                List<Object> outConfigs =
                                        (List<Object>) XposedHelpers.callMethod(config, "getOutputConfigurations");
                                XposedBridge.log("vcam: " + outConfigs.size() + " output surfaces");
                                releaseDummies(lpparam.packageName);
                                for (Object oc : outConfigs) {
                                    Surface orig = (Surface) XposedHelpers.callMethod(oc, "getSurface");
                                    if (orig == null) {
                                        XposedBridge.log("vcam: skipping null (deferred) surface");
                                        continue;
                                    }
                                    Surface dummy = makeDummy(lpparam.packageName);
                                    if (dummy == null) continue;
                                    // swap the surface inside OutputConfiguration
                                    try {
                                        XposedHelpers.setObjectField(oc, "mSurface", dummy);
                                    } catch (Throwable t) {
                                        XposedBridge.log("vcam: swap mSurface failed " + t);
                                        continue;
                                    }
                                    try {
                                        @SuppressWarnings("unchecked")
                                        List<Surface> shared =
                                                (List<Surface>) XposedHelpers.getObjectField(oc, "mSurfaces");
                                        if (shared != null) {
                                            for (int i = 0; i < shared.size(); i++) shared.set(i, dummy);
                                        }
                                    } catch (Throwable ignored) {}
                                    XposedBridge.log("vcam: swapped surface, injecting video into original " + orig);
                                    VideoInjector.attach(lpparam.packageName, orig, videoPath);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("vcam: session hook error " + t);
                            }
                        }
                    });
            XposedBridge.log("vcam: SessionConfiguration hook installed in " + lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("vcam: camera2 SessionConfiguration hook missing " + t);
        }

        // ---- Camera2 legacy: createCaptureSession(List<Surface>, StateCallback, Handler) ----
        try {
            XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.impl.CameraDeviceImpl",
                    lpparam.classLoader,
                    "createCaptureSession",
                    List.class,
                    "android.hardware.camera2.CameraCaptureSession$StateCallback",
                    "android.os.Handler",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log("vcam: createCaptureSession(List) fired in " + lpparam.packageName);
                            reloadConfig();
                            logConfigState(lpparam.packageName);
                            if (!enabled) return;
                            try {
                                @SuppressWarnings("unchecked")
                                List<Surface> outputs = (List<Surface>) param.args[0];
                                releaseDummies(lpparam.packageName);
                                for (int i = 0; i < outputs.size(); i++) {
                                    Surface orig = outputs.get(i);
                                    if (orig == null) continue;
                                    Surface dummy = makeDummy(lpparam.packageName);
                                    if (dummy != null) {
                                        try {
                                            outputs.set(i, dummy);
                                        } catch (Throwable t) {
                                            XposedBridge.log("vcam: list swap failed " + t);
                                            continue;
                                        }
                                    } else continue;
                                    XposedBridge.log("vcam: swapped surface, injecting video into original " + orig);
                                    VideoInjector.attach(lpparam.packageName, orig, videoPath);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("vcam: legacy session hook error " + t);
                            }
                        }
                    });
            XposedBridge.log("vcam: legacy List hook installed in " + lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("vcam: camera2 legacy hook missing " + t);
        }

        // ---- Stop rendering when the session closes ----
        try {
            XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.impl.CameraCaptureSessionImpl",
                    lpparam.classLoader,
                    "close",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            VideoInjector.detachAll(lpparam.packageName);
                            releaseDummies(lpparam.packageName);
                        }
                    });
        } catch (Throwable ignored) {}

        // ---- Camera1 (legacy apps): swap the preview display surface ----
        try {
            XposedHelpers.findAndHookMethod(
                    "android.hardware.Camera",
                    lpparam.classLoader,
                    "setPreviewDisplay",
                    "android.view.SurfaceHolder",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log("vcam: camera1 setPreviewDisplay fired in " + lpparam.packageName);
                            reloadConfig();
                            logConfigState(lpparam.packageName);
                            if (!enabled || param.args[0] == null) return;
                            try {
                                Object holder = param.args[0];
                                Surface orig = (Surface) XposedHelpers.callMethod(holder, "getSurface");
                                if (orig == null) return;
                                // Camera1 writes straight into the holder's surface, which we
                                // cannot swap here; just attach the injector to it as well.
                                VideoInjector.attach(lpparam.packageName, orig, videoPath);
                            } catch (Throwable t) {
                                XposedBridge.log("vcam: camera1 hook error " + t);
                            }
                        }
                    });
        } catch (Throwable ignored) {}
    }
}
