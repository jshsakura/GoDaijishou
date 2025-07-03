package kr.opencourse.godaijishou;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Xposed 모듈 (최종 수정본)
 * LG 세컨드 런처가 실행되는 순간을 가로채 Daijishou를 실행하고,
 * 원본 런처는 즉시 스스로를 종료하여 '밖으로 나가는' 문제를 원천 차단.
 */
public class MainModule implements IXposedHookLoadPackage {

    private static final String TARGET_LAUNCHER_PACKAGE = "com.lge.secondlauncher";
    private static final String DAIJISHOU_PACKAGE = "com.magneticchen.daijishou";
    private static final String DAIJISHOU_MAIN_ACTIVITY = DAIJISHOU_PACKAGE + ".activities.MainActivity";
    private static final int SECOND_SCREEN_DISPLAY_ID = 4;

    private static boolean mIsNotifiedAboutMissingApp = false;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {

        // 오직 LG 세컨드 런처만 정확히 타겟팅
        if (!TARGET_LAUNCHER_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        // 복잡한 상태 플래그 없이, onCreate 메서드만 후킹하여 단순하고 확실하게 처리
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {

                final Activity launcherActivity = (Activity) param.thisObject;

                // 이 액티비티가 세컨드 스크린에서 실행되는지 확인
                if (launcherActivity.getDisplay() == null || launcherActivity.getDisplay().getDisplayId() != SECOND_SCREEN_DISPLAY_ID) {
                    return;
                }

                // Daijishou 설치 여부 확인
                if (!isPackageInstalled(DAIJISHOU_PACKAGE, launcherActivity.getPackageManager())) {
                    if (!mIsNotifiedAboutMissingApp) {
                        Toast.makeText(launcherActivity, "Daijishou 앱이 설치되지 않았습니다.", Toast.LENGTH_LONG).show();
                        mIsNotifiedAboutMissingApp = true;
                    }
                    // Daijishou가 없으면 런처를 종료하지 않고 그대로 실행되도록 둠
                    return;
                }

                // Daijishou 실행 로직
                Intent daijishouIntent = new Intent()
                        .setComponent(new ComponentName(DAIJISHOU_PACKAGE, DAIJISHOU_MAIN_ACTIVITY))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(SECOND_SCREEN_DISPLAY_ID);

                try {
                    launcherActivity.startActivity(daijishouIntent, options.toBundle());
                } finally {
                    // 핵심: Daijishou 실행 성공 여부와 관계없이, 원본 런처는 즉시 종료
                    launcherActivity.finish();
                }
            }
        });
    }

    private boolean isPackageInstalled(String packageName, PackageManager packageManager) {
        try {
            packageManager.getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}