package kr.opencourse.godaijishou;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Fingerprint sensor off (system_server). The Wing's sensor sits under the top screen, which
 * stays off here; yet apps keep offering fingerprint (passkeys, biometric prompts) because the
 * system says a sensor exists. With the option on, the system says there is none:
 *   - PackageManager: no android.hardware.fingerprint feature (apps that check it);
 *   - FingerprintService: no hardware, nothing enrolled, no sensors (BiometricPrompt,
 *     BiometricManager.canAuthenticate, the lock screen).
 * Passkeys and logins then fall back to the screen lock (PIN / pattern) on the bottom screen.
 * Nothing is deleted: turning the option off brings the sensor back after a reboot.
 */
final class FingerprintHook {

    private static final String FEATURE = "android.hardware.fingerprint";
    private static final String SERVICE = "com.android.server.biometrics.sensors.fingerprint.FingerprintService";
    private static final String PMS = "com.android.server.pm.PackageManagerService";

    private FingerprintHook() {
    }

    static void install(ClassLoader cl) {
        hookFeature(cl);
        hookService(cl);
    }

    private static boolean off() {
        return SystemSettings.fingerprintOff;
    }

    private static void hookFeature(ClassLoader cl) {
        XC_MethodHook has = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (off() && param.args.length > 0 && FEATURE.equals(param.args[0])) {
                    param.setResult(false);
                }
            }
        };
        XC_MethodHook list = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!off()) return;
                Object r = param.getResult();
                try {
                    if (r instanceof List) {
                        List<Object> copy = new ArrayList<>((List<?>) r);
                        copy.removeIf(FingerprintHook::isFingerprintFeature);
                        param.setResult(copy);
                    } else if (r != null && r.getClass().getSimpleName().equals("ParceledListSlice")) {
                        List<?> items = (List<?>) XposedHelpers.callMethod(r, "getList");
                        List<Object> copy = new ArrayList<>(items);
                        copy.removeIf(FingerprintHook::isFingerprintFeature);
                        param.setResult(XposedHelpers.newInstance(r.getClass(), copy));
                    }
                } catch (Throwable ignored) {
                    ModuleLog.w(ignored);
                }
            }
        };
        for (String cls : new String[]{PMS, PMS + "$IPackageManagerImpl", "com.android.server.pm.IPackageManagerBase",
                "com.android.server.pm.ComputerEngine"}) {
            try {
                Class<?> c = XposedHelpers.findClass(cls, cl);
                XposedBridge.hookAllMethods(c, "hasSystemFeature", has);
                XposedBridge.hookAllMethods(c, "getSystemAvailableFeatures", list);
            } catch (Throwable ignored) {
                ModuleLog.w(ignored);
                // Not on this build: the others cover it.
            }
        }
    }

    private static boolean isFingerprintFeature(Object info) {
        try {
            return FEATURE.equals(XposedHelpers.getObjectField(info, "name"));
        } catch (Throwable t) {
            ModuleLog.w(t);
            return false;
        }
    }

    // Every class in FingerprintService (the binder wrapper is an inner class whose name
    // differs between builds): answer "no hardware, nothing enrolled, no sensors".
    private static void hookService(ClassLoader cl) {
        Class<?> service;
        try {
            service = XposedHelpers.findClass(SERVICE, cl);
        } catch (Throwable t) {
            ModuleLog.w(t);
            return;
        }
        List<Class<?>> classes = new ArrayList<>();
        classes.add(service);
        Collections.addAll(classes, service.getDeclaredClasses());
        for (Class<?> c : classes) {
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                Class<?> type = m.getReturnType();
                Object answer;
                if ((name.equals("isHardwareDetected") || name.equals("hasEnrolledFingerprints")
                        || name.equals("isHardwareDetectedDeprecated")
                        || name.equals("hasEnrolledFingerprintsDeprecated")) && type == boolean.class) {
                    answer = false;
                } else if (name.equals("getSensorPropertiesInternal") && List.class.isAssignableFrom(type)) {
                    answer = new ArrayList<>();
                } else {
                    continue;
                }
                final Object result = answer;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (off()) param.setResult(result instanceof List ? new ArrayList<>() : result);
                        }
                    });
                } catch (Throwable ignored) {
                    ModuleLog.w(ignored);
                }
            }
        }
    }
}
