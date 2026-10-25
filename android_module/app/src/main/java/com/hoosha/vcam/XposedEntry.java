package com.hoosha.vcam;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed entry point — virtual camera (technique proven by android_virtual_cam).
 *
 * Instead of mutating hidden fields, we build brand-new OutputConfiguration /
 * SessionConfiguration objects (public API) that point to dummy drained
 * surfaces, and hand those to the camera. The app's ORIGINAL surfaces are
 * then free for us: VideoInjector renders the video into them.
 * CaptureRequest.Builder.addTarget is hooked to redirect capture targets
 * from the original surfaces to their dummy twins.
 *
 * Controlled by /sdcard/vcam/config.json:
 *   {"enabled": true, "video": "/sdcard/vcam/video.mp4"}
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String SELF = "com.hoosha.vcam";
    private static final String CONFIG = "/sdcard/vcam/config.json";

    private static volatile boolean enabled = false;
    private static volatile String videoPath = "/sdcard/vcam/video.mp4";
    private static volatile String lastConfigError = null;

    /** orig surface -> dummy surface (per-session swap map). */
    private static final Map<Surface, Surface> SWAP = new ConcurrentHashMap<>();
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
            List<ImageReader> list = DUMMIES.get(pkg);
            if (list == null) {
                list = new CopyOnWriteArrayList<>();
                DUMMIES.put(pkg, list);
            }
            list.add(reader);
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

    /**
     * Replace each OutputConfiguration that has a surface with a NEW
     * OutputConfiguration wrapping a dummy surface. Originals are remembered
     * in SWAP and get the injected video.
     */
    private static List<OutputConfiguration> replaceConfigs(String pkg, List<OutputConfiguration> orig) {
        List<OutputConfiguration> out = new ArrayList<>();
        releaseDummies(pkg);
        SWAP.clear();
        for (OutputConfiguration oc : orig) {
            Surface s = null;
            try {
                s = oc.getSurface();
            } catch (Throwable ignored) {}
            if (s == null) {
                XposedBridge.log("vcam: keeping deferred/null-surface config as-is");
                out.add(oc);
                continue;
            }
            Surface dummy = makeDummy(pkg);
            if (dummy == null) {
                out.add(oc);
                continue;
            }
            SWAP.put(s, dummy);
            out.add(new OutputConfiguration(dummy));
            XposedBridge.log("vcam: replaced output surface " + s + " -> dummy");
            VideoInjector.attach(pkg, s, videoPath);
        }
        return out;
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName.equals(SELF)) return;
        if (lpparam.packageName.equals("android")) return;

        XposedBridge.log("vcam: module loaded into " + lpparam.packageName);

        // ---- Camera2: createCaptureSession(SessionConfiguration) ----
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
                                SessionConfiguration config = (SessionConfiguration) param.args[0];
                                List<OutputConfiguration> replaced =
                                        replaceConfigs(lpparam.packageName, config.getOutputConfigurations());
                                Executor executor = config.getExecutor();
                                CameraCaptureSession.StateCallback cb = config.getStateCallback();
                                param.args[0] = new SessionConfiguration(
                                        config.getSessionType(), replaced, executor, cb);
                                XposedBridge.log("vcam: SessionConfiguration replaced with dummy surfaces");
                            } catch (Throwable t) {
                                XposedBridge.log("vcam: session replace error");
                                XposedBridge.log(t);
                            }
                        }
                    });
            XposedBridge.log("vcam: SessionConfiguration hook installed in " + lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("vcam: camera2 SessionConfiguration hook missing " + t);
        }

        // ---- Camera2 internal: createCaptureSessionByOutputConfigurations(List) ----
        try {
            XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.impl.CameraDeviceImpl",
                    lpparam.classLoader,
                    "createCaptureSessionByOutputConfigurations",
                    List.class,
                    "android.hardware.camera2.CameraCaptureSession$StateCallback",
                    "android.os.Handler",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log("vcam: createCaptureSessionByOutputConfigurations fired in " + lpparam.packageName);
                            reloadConfig();
                            logConfigState(lpparam.packageName);
                            if (!enabled) return;
                            try {
                                @SuppressWarnings("unchecked")
                                List<OutputConfiguration> configs = (List<OutputConfiguration>) param.args[0];
                                param.args[0] = replaceConfigs(lpparam.packageName, configs);
                                XposedBridge.log("vcam: output configurations replaced with dummy surfaces");
                            } catch (Throwable t) {
                                XposedBridge.log("vcam: byOutputConfigurations replace error");
                                XposedBridge.log(t);
                            }
                        }
                    });
            XposedBridge.log("vcam: byOutputConfigurations hook installed in " + lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("vcam: byOutputConfigurations hook missing " + t);
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
                                List<Surface> replaced = new ArrayList<>();
                                releaseDummies(lpparam.packageName);
                                SWAP.clear();
                                for (Surface s : outputs) {
                                    if (s == null) {
                                        replaced.add(null);
                                        continue;
                                    }
                                    Surface dummy = makeDummy(lpparam.packageName);
                                    if (dummy == null) {
                                        replaced.add(s);
                                        continue;
                                    }
                                    SWAP.put(s, dummy);
                                    replaced.add(dummy);
                                    XposedBridge.log("vcam: replaced list surface " + s + " -> dummy");
                                    VideoInjector.attach(lpparam.packageName, s, videoPath);
                                }
                                param.args[0] = replaced;
                            } catch (Throwable t) {
                                XposedBridge.log("vcam: legacy session replace error");
                                XposedBridge.log(t);
                            }
                        }
                    });
            XposedBridge.log("vcam: legacy List hook installed in " + lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("vcam: camera2 legacy hook missing " + t);
        }

        // ---- Redirect capture targets: orig surface -> dummy twin ----
        try {
            XC_MethodHook targetHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Surface s = (Surface) param.args[0];
                    if (s == null) return;
                    Surface d = SWAP.get(s);
                    if (d != null) {
                        param.args[0] = d;
                        XposedBridge.log("vcam: capture target redirected to dummy");
                    }
                }
            };
            XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.CaptureRequest$Builder",
                    lpparam.classLoader,
                    "addTarget",
                    Surface.class,
                    targetHook);
            XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.CaptureRequest$Builder",
                    lpparam.classLoader,
                    "removeTarget",
                    Surface.class,
                    targetHook);
            XposedBridge.log("vcam: CaptureRequest.Builder target hooks installed in " + lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("vcam: CaptureRequest.Builder hooks missing " + t);
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
                            SWAP.clear();
                        }
                    });
        } catch (Throwable ignored) {}

        // ---- Camera1 (legacy apps) ----
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
                                VideoInjector.attach(lpparam.packageName, orig, videoPath);
                            } catch (Throwable t) {
                                XposedBridge.log("vcam: camera1 hook error " + t);
                            }
                        }
                    });
        } catch (Throwable ignored) {}
    }
}
