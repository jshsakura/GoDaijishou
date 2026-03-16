package kr.opencourse.godaijishou;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

public class SelectorActivity extends Activity {

    // 패키지명만 저장 (Activity 경로는 getLaunchIntentForPackage로 자동 해결)
    private static final String DAIJISHOU_PACKAGE = "com.magneticchen.daijishou";
    private static final String ESDE_PACKAGE = "org.es_de.frontend";
    private static final String PEGASUS_PACKAGE = "org.pegasus_frontend.android";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_selector);

        Button daijishouButton = findViewById(R.id.btn_daijishou);
        Button esdeButton = findViewById(R.id.btn_esde);
        Button pegasusButton = findViewById(R.id.btn_pegasus);
        Button stockButton = findViewById(R.id.btn_stock);

        daijishouButton.setOnClickListener(v -> selectAndFinish(getString(R.string.launcher_daijishou), DAIJISHOU_PACKAGE));
        esdeButton.setOnClickListener(v -> selectAndFinish(getString(R.string.launcher_esde), ESDE_PACKAGE));
        pegasusButton.setOnClickListener(v -> selectAndFinish(getString(R.string.launcher_pegasus), PEGASUS_PACKAGE));
        stockButton.setOnClickListener(v -> selectAndFinish(getString(R.string.launcher_stock), null));
    }

    private void selectAndFinish(String appName, String packageName) {
        if (packageName != null && !isPackageInstalled(packageName, getPackageManager())) {
            Toast.makeText(this, getString(R.string.toast_app_not_installed, appName), Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(AppPreferences.PREF_FILE_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        if (packageName != null) {
            editor.putString(AppPreferences.KEY_LAUNCHER_PACKAGE, packageName);
            editor.putString(AppPreferences.KEY_LAUNCHER_NAME, appName);
            // launcher_activity는 더 이상 저장하지 않음 (getLaunchIntentForPackage 사용)
            editor.remove(AppPreferences.KEY_LAUNCHER_ACTIVITY);
        } else {
            editor.clear();
        }
        editor.apply();

        Toast.makeText(this, getString(R.string.toast_set_as_default, appName), Toast.LENGTH_SHORT).show();

        setResult(Activity.RESULT_OK);
        finish();
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
