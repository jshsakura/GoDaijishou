package kr.opencourse.godaijishou;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * The installer's answer to SelfUpdate's session (not exported: only our PendingIntent).
 *
 * Asked for the person's OK (the first update from here, see SelfUpdate) it shows the
 * system's prompt. On success the app is replaced, which ends this process: nothing to do.
 * A failure or a cancel (also a "don't install" on Google Play Protect's scan prompt) says so
 * with the installer's own reason, and the update pill goes back to "Update"; the old
 * version stays.
 */
public class SelfUpdateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!SelfUpdate.ACTION_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            } catch (Throwable t) {
                if (!SelfUpdate.failed(R.string.update_err_install, null)) {
                    Toast.makeText(context, R.string.update_err_install, Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) return;
        int message = status == PackageInstaller.STATUS_FAILURE_ABORTED
                ? R.string.update_cancelled : R.string.update_err_install;
        String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (SelfUpdate.failed(message, detail)) return;
        Toast.makeText(context, SelfUpdate.describe(context, message, detail), Toast.LENGTH_LONG).show();
    }
}
