// SettingsProvider.java
package kr.opencourse.godaijishou;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.util.Objects;

public class SettingsProvider extends ContentProvider {

    // 다른 앱에서 이 Provider를 찾기 위한 고유 주소(Authority)
    private static final String AUTHORITY = "kr.opencourse.godaijishou.provider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/settings");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        SharedPreferences prefs = Objects.requireNonNull(getContext()).getSharedPreferences(AppPreferences.PREF_FILE_NAME, Context.MODE_PRIVATE);

        // SharedPreferences에서 설정값 읽기
        String targetPackage = prefs.getString(AppPreferences.KEY_LAUNCHER_PACKAGE, null);
        String targetActivity = prefs.getString(AppPreferences.KEY_LAUNCHER_ACTIVITY, null);

        // Cursor에 담아서 결과 반환
        MatrixCursor cursor = new MatrixCursor(new String[]{
                AppPreferences.KEY_LAUNCHER_PACKAGE,
                AppPreferences.KEY_LAUNCHER_ACTIVITY
        });
        cursor.addRow(new Object[]{targetPackage, targetActivity});
        return cursor;
    }

    // 아래 메서드들은 이 예제에서 필요 없으므로 비워둡니다.
    @Override
    public String getType(@NonNull Uri uri) { return null; }
    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) { return null; }
    @Override
    public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}