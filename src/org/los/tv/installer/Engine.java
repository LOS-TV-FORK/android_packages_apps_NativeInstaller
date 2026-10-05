package org.los.tv.installer;

/**
 * Install client: submits a job for the init engine service
 * (/vendor/etc/installer/engine.sh, genuine root, no su) and tails its
 * progress. All user-visible strings come from resources (en + ru).
 */
public final class Engine {
    private Engine() {}

    public static final class Options {
        public String disk = "";
        public String mode = "disk"; // "disk" (wipe) or "part" (format existing)
        public boolean dataImg;
        public int dataSizeMb; // 0 = max
        public String extra = "";
    }

    public interface Log {
        void line(String s);
    }

    public interface Res {
        String get(int id, Object... args);
    }

    public static void run(
            android.content.Context ctx, Options o, Log log, Res res) {
        if (o.disk == null || o.disk.isEmpty()) {
            log.line(res.get(R.string.err_no_disk));
            return;
        }
        // Job files live in our own data dir (always writable, no su).
        // The init engine service (root) picks them up from there.
        String dir = ctx.getFilesDir().getAbsolutePath();
        String jobPath = dir + "/installer.job";
        String statusPath = dir + "/installer.status";
        String logPath = dir + "/installer.log";
        // Submit the job for the init engine service.
        // /data/local/tmp is world-writable: plain file writes, no su.
        StringBuilder job = new StringBuilder();
        job.append("disk=").append(o.disk).append("\n");
        job.append("mode=").append(o.mode).append("\n");
        job.append("data_img=").append(o.dataImg ? "1" : "0").append("\n");
        job.append("data_size=").append(o.dataSizeMb).append("\n");
        if (o.extra != null && !o.extra.isEmpty()) {
            job.append("extra=").append(o.extra).append("\n");
        }
        
        if (!Shell.writeFile(jobPath, job.toString())
                || !Shell.writeFile(statusPath, "idle\n")
                || !Shell.writeFile(logPath, "")
                || !Shell.writeFile(dir + "/installer-debug.log", "")) {
            log.line(res.get(R.string.err_job_write));
            return;
        }
        log.line(res.get(R.string.job_sent));
        poll(statusPath, logPath, log, res);
    }

    private static void poll(
            String statusPath, String logPath, Log log, Res res) {
        String last = "";
        // Watcher polls every 5s; install takes minutes. 360 x 5s = 30 min.
        for (int i = 0; i < 360; i++) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
            String status = Shell.readFile(statusPath);
            String full = Shell.readFile(logPath);
            if (status == null) {
                continue;
            }
            status = status.trim();
            if (full == null) {
                full = "";
            }
            if (full.length() > last.length() && full.startsWith(last)) {
                String delta = full.substring(last.length());
                for (String line : delta.split("\n")) {
                    if (!line.isEmpty()) {
                        log.line(tr(line, res));
                    }
                }
                last = full;
            } else if (!full.equals(last)) {
                last = full;
            }
            if ("done".equals(status) || "error".equals(status)) {
                log.line(
                        res.get(
                                R.string.status_done,
                                res.get(
                                        "done".equals(status)
                                                ? R.string.status_ok
                                                : R.string.status_error)));
                return;
            }
        }
        log.line(res.get(R.string.err_timeout));
    }

    /** Translate engine protocol lines, pass tool output through raw. */
    private static String tr(String line, Res res) {
        String[] p = line.split(" ", 3);
        if (p.length == 0) {
            return line;
        }
        switch (p[0]) {
            case "OK":
                return res.get(okKey(p.length > 1 ? p[1] : ""));
            case "WARN":
                if (p.length > 1 && "noesp".equals(p[1])) {
                    return res.get(R.string.inst_warn_noesp);
                }
                if (p.length > 2 && "efiorder".equals(p[1])) {
                    return res.get(R.string.inst_warn_efiorder, p[2]);
                }
                return line;
            case "ERR":
                if (p.length > 2) {
                    return res.get(errKey(p[1]), p[2]);
                } else if (p.length > 1) {
                    return res.get(errKey(p[1]));
                }
                return line;
            case "TARGET":
                if (p.length > 2) {
                    return res.get(R.string.inst_target, p[1], p[2]);
                }
                return line;
            case "SRC":
                if (p.length > 1) {
                    return res.get(R.string.inst_src, p[1]);
                }
                return line;
            case "PART":
                if (p.length > 2) {
                    return res.get(R.string.inst_part, p[1], p[2]);
                }
                return line;
            case "UUID":
                if (p.length > 1) {
                    return res.get(R.string.inst_uuid, p[1]);
                }
                return line;
            case "WINDOWS":
                if (p.length > 1) {
                    return res.get(R.string.inst_windows, p[1]);
                }
                return line;
            default:
                return line;
        }
    }

    private static int okKey(String step) {
        switch (step) {
            case "slots":
                return R.string.inst_ok_slots;
            case "fstab":
                return R.string.inst_ok_fstab;
            case "bootcfg":
                return R.string.inst_ok_bootcfg;
            case "uefi":
                return R.string.inst_ok_uefi;
            case "efiboot":
                return R.string.inst_ok_efiboot;
            case "bioscfg":
                return R.string.inst_ok_bioscfg;
            case "grub":
                return R.string.inst_ok_grub;
            case "done":
                return R.string.inst_ok_done;
            default:
                return R.string.inst_ok_done;
        }
    }

    private static int errKey(String code) {
        switch (code) {
            case "no_disk":
                return R.string.inst_err_no_disk;
            case "no_media":
                return R.string.inst_err_no_media;
            case "same_disk":
                return R.string.inst_err_same_disk;
            case "chmod_tools":
                return R.string.inst_err_chmod_tools;
            case "gpt_new":
                return R.string.inst_err_gpt_new;
            case "esp_part":
                return R.string.inst_err_esp_part;
            case "system_part":
                return R.string.inst_err_system_part;
            case "mkfs_esp":
                return R.string.inst_err_mkfs_esp;
            case "mkfs":
                return R.string.inst_err_mkfs;
            case "mount":
                return R.string.inst_err_mount;
            case "copy_system":
                return R.string.inst_err_copy_system;
            case "extend_system":
                return R.string.inst_err_extend_system;
            case "copy_kernel":
                return R.string.inst_err_copy_kernel;
            case "copy_initrd":
                return R.string.inst_err_copy_initrd;
            case "copy_recovery":
                return R.string.inst_err_copy_recovery;
            case "misc_img":
                return R.string.inst_err_misc_img;
            case "data_img":
                return R.string.inst_err_data_img;
            case "mkfs_data":
                return R.string.inst_err_mkfs_data;
            case "mount_esp":
                return R.string.inst_err_mount_esp;
            case "efi_bin":
                return R.string.inst_err_efi_bin;
            case "efi_mods":
                return R.string.inst_err_efi_mods;
            default:
                return R.string.inst_err_mount;
        }
    }
}
