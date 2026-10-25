package com.hoosha.vcam;

import android.view.Surface;

import java.io.File;
import java.io.FileReader;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed entry point. Hooks camera session creation inside the target app
 * and feeds frames decoded from /sdcard/vcam/video.mp4 into every output
 * Surface the camera app hands to the camera (preview, ImageReader,
 * MediaRecorder). Controlled by /sdcard/vcam/config.json:
 *   {"enabled": true, "video": "/sdcard/vcam/video.mp4"}
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String SELF = "com.hoosha.vcam";
    private static final String CONFIG = "/sdcard/vcam/config.json";

    private static volatile boolean enabled = false;
    private static volatile String videoPath = "/sdcard/vcam/video.mp4";

    private static void reloadConfig() {
        try {
            File f = new File(CONFIG);
            if (!f.exists()) { enabled = false; return; }
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
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName.equals(SELF)) return;
        if (lpparam.packageName.equals("android")) return;

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
                            reloadConfig();
                            if (!enabled) return;
                            try {
                                Object config = param.args[0];
                                @SuppressWarnings("unchecked")
                                List<Object> outConfigs =
                                        (List<Object>) XposedHelpers.callMethod(config, "getOutputConfigurations");
                                for (Object oc : outConfigs) {
                                    Surface s = (Surface) XposedHelpers.callMethod(oc, "getSurface");
                                    VideoInjector.attach(lpparam.packageName, s, videoPath);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("vcam: session hook error " + t);
                            }
                        }
                    });
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
                            reloadConfig();
                            if (!enabled) return;
                            try {
                                @SuppressWarnings("unchecked")
                                List<Surface> outputs = (List<Surface>) param.args[0];
                                for (Surface s : outputs)
                                    VideoInjector.attach(lpparam.packageName, s, videoPath);
                            } catch (Throwable t) {
                                XposedBridge.log("vcam: legacy session hook error " + t);
                            }
                        }
                    });
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
                        }
                    });
        } catch (Throwable ignored) {}

        // ---- Camera1 (legacy apps): render into the preview display surface ----
        try {
            XposedHelpers.findAndHookMethod(
                    "android.hardware.Camera",
                    lpparam.classLoader,
                    "setPreviewDisplay",
                    "android.view.SurfaceHolder",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            reloadConfig();
                            if (!enabled || param.args[0] == null) return;
                            try {
                                Object holder = param.args[0];
                                Surface s = (Surface) XposedHelpers.callMethod(holder, "getSurface");
                                VideoInjector.attach(lpparam.packageName, s, videoPath);
                            } catch (Throwable t) {
                                XposedBridge.log("vcam: camera1 hook error " + t);
                            }
                        }
                    });
        } catch (Throwable ignored) {}
    }
}
