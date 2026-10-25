package com.hoosha.vcam;

import android.graphics.ImageFormat;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
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
     * Reflectively find the Surface field inside an OutputConfiguration and
     * replace it with our dummy. Works regardless of vendor field renames.
     * Also rewrites any List<Surface> fields (shared surfaces).
     */
    private static boolean swapOutputSurface(Object oc, Surface dummy) {
        boolean swapped = false;
        try {
            Class<?> cls = oc.getClass();
            StringBuilder names = new StringBuilder();
            for (Field f : cls.getDeclaredFields()) {
                names.append(f.getName()).append("(").append(f.getType().getSimpleName()).append(") ");
            }
            XposedBridge.log("vcam: OutputConfiguration fields: " + names);

            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    if (Surface.class.isAssignableFrom(f.getType())) {
                        Object cur = f.get(oc);
                        if (cur != null) {
                            f.set(oc, dummy);
                            XposedBridge.log("vcam: swapped field " + f.getName());
                            swapped = true;
                        }
                    } else if (List.class.isAssignableFrom(f.getType())) {
                        Object val = f.get(oc);
                        if (val instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<Object> l = (List<Object>) val;
                            boolean hasSurface = false;
                            for (Object o : l) if (o instanceof Surface) { hasSurface = true; break; }
                            if (hasSurface) {
                                for (int i = 0; i < l.size(); i++) l.set(i, dummy);
                                XposedBridge.log("vcam: rewrote list field " + f.getName());
                            }
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log("vcam: field " + f.getName() + " swap error " + t);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("vcam: reflective swap failed");
            XposedBridge.log(t);
        }
        return swapped;
    }

    /** Swap all output surfaces of a session config with dummies and attach the injector. */
    private static void injectIntoConfigs(String pkg, List<?> outConfigs) {
        XposedBridge.log("vcam: " + outConfigs.size() + " output surfaces");
        releaseDummies(pkg);
        for (Object oc : outConfigs) {
            Surface orig = null;
            try {
                orig = (Surface) XposedHelpers.callMethod(oc, "getSurface");
            } catch (Throwable t) {
                XposedBridge.log("vcam: getSurface failed " + t);
            }
            if (orig == null) {
                XposedBridge.log("vcam: skipping null (deferred) surface");
                continue;
            }
            Surface dummy = makeDummy(pkg);
            if (dummy == null) continue;
            if (!swapOutputSurface(oc, dummy)) {
                XposedBridge.log("vcam: could NOT swap surface " + orig + " — camera will keep writing to it");
            }
            XposedBridge.log("vcam: injecting video into original surface " + orig);
            VideoInjector.attach(pkg, orig, videoPath);
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
                                injectIntoConfigs(lpparam.packageName, outConfigs);
                            } catch (Throwable t) {
                                XposedBridge.log("vcam: session hook error");
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
                                List<Object> outConfigs = (List<Object>) param.args[0];
                                injectIntoConfigs(lpparam.packageName, outConfigs);
                            } catch (Throwable t) {
                                XposedBridge.log("vcam: byOutputConfigurations hook error");
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
                                releaseDummies(lpparam.packageName);
                                for (int i = 0; i < outputs.size(); i++) {
                                    Surface orig = outputs.get(i);
                                    if (orig == null) continue;
                                    Surface dummy = makeDummy(lpparam.packageName);
                                    if (dummy == null) continue;
                                    try {
                                        outputs.set(i, dummy);
                                    } catch (Throwable t) {
                                        XposedBridge.log("vcam: list swap failed " + t);
                                        continue;
                                    }
                                    XposedBridge.log("vcam: swapped list surface, injecting video into original " + orig);
                                    VideoInjector.attach(lpparam.packageName, orig, videoPath);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("vcam: legacy session hook error");
                                XposedBridge.log(t);
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
