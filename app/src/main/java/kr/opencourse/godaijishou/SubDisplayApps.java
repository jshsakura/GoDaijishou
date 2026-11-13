package kr.opencourse.godaijishou;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;

import java.util.Map;

import de.robv.android.xposed.XposedHelpers;

/**
 * New apps may open on the second screen right away (runs in system_server).
 *
 * LG keeps a per-app "can be launched on the second screen" list
 * (/data/system/users/0/wing_canbe_launched_user_list.xml). An app missing from it gets LG's
 * "open on the second screen?" dialog first, so every new install had to be allowed by hand.
 * On a fresh install (not an update) of an app with a launcher icon, this adds it through
 * LG's own call, the one its settings screen uses: ActivityTaskManagerServiceEx
 * .updateLaunchedOnSubDisplayPackageByUser(pkg, true). That call refuses apps LG restricts
 * from the second screen and writes the list itself. Apps already in the list (allowed or
 * turned off by the user) are left alone.
 */
final class SubDisplayApps {

    private SubDisplayApps() {
    }

    static void install(Context context, Handler handler) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                if (intent.getData() == null) return;
                String pkg = intent.getData().getSchemeSpecificPart();
                try {
                    allow(context, pkg);
                } catch (Throwable t) {
                    ModuleLog.w(t); // LG firmware without the list: nothing to do
                }
            }
        }, filter, null, handler);
    }

    private static void allow(Context context, String pkg) throws Exception {
        if (pkg == null || !hasLauncherIcon(context, pkg)) return;
        // In system_server this is the service object itself (a local binder).
        Object atms = XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityTaskManager"), "getService");
        Object ex = XposedHelpers.getObjectField(atms, "mMultiDisplayEx");
        Map<?, ?> user = (Map<?, ?>) XposedHelpers.getObjectField(ex, "WING_WHITE_LIST_USER");
        if (user.containsKey(pkg)) return; // the user's own choice
        XposedHelpers.callMethod(atms, "updateLaunchedOnSubDisplayPackageByUser", pkg, true);
    }

    // Also before the first unlock, when the app's (credential-encrypted) activities don't
    // resolve by default: an install then would otherwise look like one without an icon.
    private static boolean hasLauncherIcon(Context context, String pkg) {
        Intent launcher = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(pkg);
        int flags = android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE
                | android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        return !context.getPackageManager().queryIntentActivities(launcher, flags).isEmpty();
    }
}
