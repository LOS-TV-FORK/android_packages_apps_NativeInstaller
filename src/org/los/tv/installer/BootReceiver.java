package org.los.tv.installer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import java.io.FileWriter;

/**
 * Live-session entry point: after the user finishes the setup wizard
 * (remote paired, language chosen, device provisioned), open the installer
 * instead of leaving the user on an empty live desktop. Only in live ISO
 * sessions (ro.boot.live=true, set by the live GRUB entry).
 *
 * Every step is logged to boot.log in app-private storage so a silent
 * failure can be diagnosed from the disk image afterwards.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        blog(context, "boot_completed received");
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        new Thread(
                        () -> {
                            try {
                                watch(context);
                            } catch (Exception e) {
                                blog(context, "watch crashed: " + e);
                            }
                        })
                .start();
    }

    private void watch(Context context) {
        Shell.Result live = Shell.sh("getprop ro.boot.live");
        blog(context,
                "ro.boot.live="
                        + (live.ok() ? live.out.trim() : "ERR:" + live.err));
        if (!live.ok() || !"true".equals(live.out.trim())) {
            // Installed system, not live: hide ourselves completely
            // (launcher icon + receiver). An app may disable its own
            // components without any permission.
            blog(context, "not live, disabling");
            android.content.ComponentName self =
                    new android.content.ComponentName(
                            context, MainActivity.class);
            android.content.ComponentName recv =
                    new android.content.ComponentName(
                            context, BootReceiver.class);
            android.content.pm.PackageManager pm = context.getPackageManager();
            pm.setComponentEnabledSetting(
                    self,
                    android.content.pm.PackageManager
                            .COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(
                    recv,
                    android.content.pm.PackageManager
                            .COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
            return;
        }
        // Wait for the setup wizard (up to 30 min): either
        // device provisioned or user setup complete.
        for (int i = 0; i < 360; i++) {
            if (provisioned(context)) {
                blog(context, "provisioned, starting installer");
                break;
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        }
        try {
            Intent act = new Intent(context, MainActivity.class);
            act.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(act);
            blog(context, "startActivity sent");
        } catch (Exception e) {
            blog(context, "startActivity failed: " + e);
        }
    }

    private static boolean provisioned(Context context) {
        try {
            if (Settings.Global.getInt(
                            context.getContentResolver(),
                            Settings.Global.DEVICE_PROVISIONED,
                            0)
                    == 1) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            return Settings.Secure.getInt(
                            context.getContentResolver(),
                            Settings.Secure.USER_SETUP_COMPLETE,
                            0)
                    == 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void blog(Context context, String s) {
        try (FileWriter w =
                new FileWriter(
                        context.getFilesDir() + "/boot.log", true)) {
            w.write(s + "\n");
        } catch (Exception ignored) {
        }
    }
}
