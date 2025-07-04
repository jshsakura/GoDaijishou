package kr.opencourse.godaijishou;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private SharedPreferences prefs;
    private Button selectLauncherButton;
    private TextView currentSettingText;

    private final ActivityResultLauncher<Intent> selectorLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    updateUI();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(AppPreferences.PREF_FILE_NAME, Context.MODE_PRIVATE);

        selectLauncherButton = findViewById(R.id.btn_select_launcher);
        currentSettingText = findViewById(R.id.tv_current_setting);

        selectLauncherButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SelectorActivity.class);
            selectorLauncher.launch(intent);
        });

        updateUI();
    }

    @SuppressLint("SetTextI18n")
    private void updateUI() {
        // --- (수정) 버튼 활성화/비활성화 로직 제거 ---
        String savedLauncherName = prefs.getString(AppPreferences.KEY_LAUNCHER_NAME, null);
        if (savedLauncherName != null) {
            currentSettingText.setText(getString(R.string.main_current_launcher_prefix) + savedLauncherName);
        } else {
            currentSettingText.setText(getString(R.string.main_no_launcher_set));
        }
    }
}