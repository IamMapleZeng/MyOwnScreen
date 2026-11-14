// Adapted from LSPosed/DisableFlagSecure under GPL-3.0.
package com.myownscreen;

import android.annotation.SuppressLint;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.Log;
import android.view.SurfaceControl;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

import io.github.libxposed.api.XposedModule;

@SuppressLint({"PrivateApi", "BlockedPrivateApi"})
public final class MyOwnScreen extends XposedModule {
    private static final String TAG = "MyOwnScreen";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String SCREENSHOT = "com.miui.screenshot";
    private static final String RECORDER = "com.miui.screenrecorder";

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        ClassLoader loader = param.getClassLoader();
        log(Log.INFO, TAG, "Installing system hooks for Android API " + Build.VERSION.SDK_INT);
        install("Window deoptimization", false, () -> deoptimizeSystemServer(loader));
        install("Secure windows", false, () -> hookWindowState(loader));
        install("Screen capture", false, () -> hookScreenCapture(loader));
        install("Display control", false, () -> hookDisplayControl(loader));
        install("Recording virtual display", false, () -> hookVirtualDisplayAdapter(loader));
        install("Device policy capture check", true, () -> hookDevicePolicy(loader));
        if (Build.VERSION.SDK_INT >= 34) {
            install("HyperOS capture check", false, () -> hookHyperOS(loader));
            install("Screenshot observers", false, () -> hookActivityTaskManagerService(loader));
        } else {
            install("Secure capture permission", false, () -> hookActivityManagerService(loader));
        }
        if (Build.VERSION.SDK_INT >= 35) {
            install("Recording observers", false, () -> hookWindowManagerService(loader));
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        switch (param.getPackageName()) {
            case SYSTEM_UI, SCREENSHOT, RECORDER ->
                install("Screen capture in " + param.getPackageName(), false,
                        () -> hookScreenCapture(param.getClassLoader()));
        }
    }

    @FunctionalInterface
    private interface HookSetup {
        void run() throws ReflectiveOperationException;
    }

    private void install(String name, boolean optional, HookSetup setup) {
        try {
            setup.run();
            log(Log.INFO, TAG, "Hook ready: " + name);
        } catch (ClassNotFoundException | NoSuchMethodException error) {
            log(optional ? Log.INFO : Log.WARN, TAG, "Hook unavailable: " + name, error);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "Hook failed: " + name, error);
        }
    }

    private void hookDevicePolicy(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> cache = loader.loadClass("com.android.server.devicepolicy.DevicePolicyCacheImpl");
        Method method = cache.getDeclaredMethod("isScreenCaptureAllowed", int.class);
        hook(method).intercept(chain -> true);
    }

    private void deoptimizeSystemServer(ClassLoader classLoader) throws ClassNotFoundException {
        deoptimizeMethods(
                classLoader.loadClass("com.android.server.wm.WindowStateAnimator"),
                "createSurfaceLocked");

        deoptimizeMethods(
                classLoader.loadClass("com.android.server.wm.WindowManagerService"),
                "relayoutWindow");

        for (int i = 0; i < 20; i++) {
            try {
                var clazz = classLoader.loadClass("com.android.server.wm.RootWindowContainer$$ExternalSyntheticLambda" + i);
                if (BiConsumer.class.isAssignableFrom(clazz)) {
                    deoptimizeMethods(clazz, "accept");
                }
            } catch (ClassNotFoundException ignored) {
            }
            try {
                var clazz = classLoader.loadClass("com.android.server.wm.DisplayContent$" + i);
                if (BiPredicate.class.isAssignableFrom(clazz)) {
                    deoptimizeMethods(clazz, "test");
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
    }

    private void deoptimizeMethods(Class<?> clazz, String... names) {
        var list = Arrays.asList(names);
        Arrays.stream(clazz.getDeclaredMethods())
                .filter(method -> list.contains(method.getName()))
                .forEach(this::deoptimize);
    }

    private void hookWindowState(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var windowStateClazz = classLoader.loadClass("com.android.server.wm.WindowState");
        var systemServerCl = windowStateClazz.getClassLoader();
        var isSecureLockedMethod = windowStateClazz.getDeclaredMethod("isSecureLocked");
        hook(isSecureLockedMethod).intercept(chain -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
                var match = walker.walk(frames -> frames
                        .anyMatch(frame -> frame.getDeclaringClass() != null &&
                                frame.getDeclaringClass().getClassLoader() == systemServerCl &&
                                (frame.getMethodName().equals("setInitialSurfaceControlProperties") ||
                                        frame.getMethodName().equals("createSurfaceLocked"))));
                if (match) return chain.proceed();
            } else {
                var stackTrace = new Throwable().getStackTrace();
                for (var frame : stackTrace) {
                    var name = frame.getMethodName();
                    try {
                        if ((name.equals("setInitialSurfaceControlProperties") ||
                                name.equals("createSurfaceLocked")) &&
                                classLoader.loadClass(frame.getClassName()).getClassLoader() == systemServerCl) {
                            return chain.proceed();
                        }
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
            return false;
        });
    }

    private void hookScreenCapture(ClassLoader classLoader) throws ReflectiveOperationException {
        Class<?> screenCaptureClazz;
        try {
            screenCaptureClazz = classLoader.loadClass("android.window.ScreenCaptureInternal");
        } catch (ClassNotFoundException ignored) {
            screenCaptureClazz = Build.VERSION.SDK_INT >= 34
                    ? classLoader.loadClass("android.window.ScreenCapture") : SurfaceControl.class;
        }
        var captureArgsClass = classLoader.loadClass(screenCaptureClazz.getName() + "$CaptureArgs");
        java.lang.reflect.Field field;
        try {
            field = captureArgsClass.getDeclaredField("mSecureContentPolicy");
        } catch (NoSuchFieldException ignored) {
            field = captureArgsClass.getDeclaredField("mCaptureSecureLayers");
        }
        final var capturePolicy = field;
        capturePolicy.setAccessible(true);
        Hooker hooker = chain -> {
            Object captureArgs = chain.getArg(0);
            if (captureArgs != null) {
                if (capturePolicy.getType() == int.class) {
                    capturePolicy.setInt(captureArgs, 1);
                } else {
                    capturePolicy.setBoolean(captureArgs, true);
                }
            }
            return chain.proceed();
        };
        hookMethods(screenCaptureClazz, hooker, "nativeCaptureDisplay");
        hookMethods(screenCaptureClazz, hooker, "nativeCaptureLayers");
    }

    private void hookDisplayControl(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var displayControlClazz = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ?
                classLoader.loadClass("com.android.server.display.DisplayControl") :
                SurfaceControl.class;
        var systemServerCl = displayControlClazz.getClassLoader();
        var method = displayControlClazz.getDeclaredMethod(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ?
                        "createVirtualDisplay" :
                        "createDisplay", String.class, boolean.class);
        hook(method).intercept(chain -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var stackTrace = new Throwable().getStackTrace();
                for (var frame : stackTrace) {
                    var name = frame.getMethodName();
                    try {
                        if (name.equals("createVirtualDisplayLocked") &&
                                classLoader.loadClass(frame.getClassName()).getClassLoader() == systemServerCl) {
                            return chain.proceed();
                        }
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
            var args = chain.getArgs().toArray();
            args[1] = true;
            return chain.proceed(args);
        });
    }

    private void hookVirtualDisplayAdapter(ClassLoader loader) throws ReflectiveOperationException {
        var adapter = loader.loadClass("com.android.server.display.VirtualDisplayAdapter");
        int installed = 0;
        for (Method method : adapter.getDeclaredMethods()) {
            if (!method.getName().equals("createVirtualDisplayLocked")) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length < 5 || types[2] != int.class
                    || !types[1].getName().equals("android.media.projection.IMediaProjection")) continue;
            int index = 3;
            while (index < types.length && types[index] != int.class) index++;
            if (index == types.length) continue;
            int flagIndex = index;
            hook(method).intercept(chain -> {
                int caller = (int) chain.getArg(2);
                if (caller >= 10000 && chain.getArg(1) == null) return chain.proceed();
                var args = chain.getArgs().toArray();
                args[flagIndex] = (int) args[flagIndex] | DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
                return chain.proceed(args);
            });
            installed++;
        }
        if (installed == 0) throw new NoSuchMethodException("createVirtualDisplayLocked");
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void hookActivityTaskManagerService(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var activityTaskManagerServiceClazz = classLoader.loadClass("com.android.server.wm.ActivityTaskManagerService");
        var iBinderClazz = classLoader.loadClass("android.os.IBinder");
        var iScreenCaptureObserverClazz = classLoader.loadClass("android.app.IScreenCaptureObserver");
        var method = activityTaskManagerServiceClazz.getDeclaredMethod("registerScreenCaptureObserver", iBinderClazz, iScreenCaptureObserverClazz);
        hook(method).intercept(chain -> null);
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private void hookWindowManagerService(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var windowManagerServiceClazz = classLoader.loadClass("com.android.server.wm.WindowManagerService");
        var iScreenRecordingCallbackClazz = classLoader.loadClass("android.window.IScreenRecordingCallback");
        var method = windowManagerServiceClazz.getDeclaredMethod("registerScreenRecordingCallback", iScreenRecordingCallbackClazz);
        hook(method).intercept(chain -> false);
    }

    private void hookActivityManagerService(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var activityTaskManagerServiceClazz = classLoader.loadClass("com.android.server.am.ActivityManagerService");
        var method = activityTaskManagerServiceClazz.getDeclaredMethod("checkPermission", String.class, int.class, int.class);
        hook(method).intercept(chain -> {
            var permission = chain.getArg(0);
            if ("android.permission.CAPTURE_BLACKOUT_CONTENT".equals(permission)) {
                var args = chain.getArgs().toArray();
                args[0] = "android.permission.READ_FRAME_BUFFER";
                return chain.proceed(args);
            }
            return chain.proceed();
        });
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void hookHyperOS(ClassLoader classLoader) throws ReflectiveOperationException {
        var windowManagerServiceImplClazz = classLoader.loadClass("com.android.server.wm.WindowManagerServiceImpl");
        var methods = Arrays.stream(windowManagerServiceImplClazz.getDeclaredMethods())
                .filter(method -> method.getName().equals("notAllowCaptureDisplay")
                        && method.getReturnType() == boolean.class)
                .collect(java.util.stream.Collectors.toList());
        if (methods.isEmpty()) throw new NoSuchMethodException("notAllowCaptureDisplay");
        methods.forEach(method -> hook(method).intercept(chain -> false));
    }

    private void hookMethods(Class<?> type, Hooker interceptor, String name) throws NoSuchMethodException {
        var methods = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .collect(java.util.stream.Collectors.toList());
        if (methods.isEmpty()) throw new NoSuchMethodException(type.getName() + "." + name);
        methods.forEach(method -> hook(method).intercept(interceptor));
    }
}
