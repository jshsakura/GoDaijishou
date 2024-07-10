import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MainModule implements IXposedHookLoadPackage {

    private static final String SECOND_LAUNCHER_PACKAGE = "com.lge.secondlauncher";
    private static final String DAIJISHOU_PACKAGE = "com.magneticchen.daijishou";

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(SECOND_LAUNCHER_PACKAGE)) {
            XposedBridge.log("Hooked into Second Launcher: " + lpparam.packageName);

            // MainActivity 클래스의 onCreate 메서드를 후킹
            XposedHelpers.findAndHookMethod("com.lge.secondlauncher.MainActivity", lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log("MainActivity onCreate hooked in " + lpparam.packageName);

                    // com.magneticchen.daijishou 앱 실행 Intent 생성
                    Intent daijishouIntent = new Intent();
                    daijishouIntent.setComponent(new ComponentName(DAIJISHOU_PACKAGE, DAIJISHOU_PACKAGE + ".MainActivity"));
                    daijishouIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    // 현재 액티비티에서 com.magneticchen.daijishou 앱 실행
                    Object activity = param.thisObject;
                    if (activity instanceof android.app.Activity) {
                        ((android.app.Activity) activity).startActivity(daijishouIntent);
                        XposedBridge.log("Started Daijishou app from Second Launcher");
                    }
                }
            });
        }
    }
}
