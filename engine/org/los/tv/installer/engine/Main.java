package org.los.tv.installer.engine;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native installer engine, runs as an init service (UID 0, genuine root,
 * no su/Magisk). Reads one job file, executes the install sequence
 * (ported from the aaropa Calamares exec modules), writes machine-readable
 * status + log for the UI app (it translates codes via resources).
 *
 * Job file keys: disk, mode (disk|part), data_img (0|1), data_size (MB),
 * nomodeset (0|1), nouveau_blacklist (0|1).
 */
public final class Main {
    // App-private dir (init service runs as root, sees everything).
    private static final String BASE = "/data/data/org.los.tv.installer/files/";
    private static final String WORK = "/data/local/tmp/installer-work/";
    private static final String PRE = "/system/etc/installer/grub";

    private static FileWriter sLog;
    private static FileWriter sDbg;
    // Short failure classification for the on-screen log tail.
    private static String sVerdict = "UNKNOWN";

    public static void main(String[] args) {
        try {
            run();
        } catch (Exception e) {
            log("ERR mount");
            status("error");
            close();
        }
    }

    private static void run() throws Exception {
        Map<String, String> job = readJob(BASE + "installer.job");
        new java.io.File(BASE + "installer.job").delete();
        status("running");
        sLog = new FileWriter(BASE + "installer.log", false);
        sDbg = new FileWriter(BASE + "installer-debug.log", false);

        String disk = job.get("disk");
        String mode = job.getOrDefault("mode", "disk");
        if ("scan".equals(job.get("task"))) {
            doScan();
            status("done");
            close();
            return;
        }
        log("TARGET " + disk + " " + mode);
        if (disk == null || disk.isEmpty()) {
            fail("no_disk");
            return;
        }

        // --- 1. Payload: live ISO contents ---
        // First resolve the boot media the same way initrd did: kernel
        // cmdline ROOT=LABEL=...|UUID=...|/dev/... plus SRC=<dir>. This is
        // the device the live system actually booted from, so it cannot
        // miss (blind partition scans do miss on real HW: already-mounted
        // media, odd fstypes, nested layouts).
        // Candidate partitions come from /proc/partitions.
        String src = null;
        String sysimg = null;
        String srcDev = null;
        String[] viaBoot = findViaCmdline();
        if (viaBoot != null) {
            src = viaBoot[0];
            sysimg = viaBoot[1];
            srcDev = viaBoot[2];
        }
        // Ventoy sticks: the ISO is a file on the exFAT data partition
        // (vold mounts it under /mnt/media_rw) and/or a device-mapper
        // image (/dev/mapper/ventoy). The boot ROOT= label does not exist
        // at runtime, so the cmdline path above cannot work there.
        if (src == null) {
            String[] viaVentoy = findViaVentoy();
            if (viaVentoy != null) {
                src = viaVentoy[0];
                sysimg = viaVentoy[1];
                srcDev = viaVentoy[2];
            }
        }
        if (src == null) {
            src = findIn(new String[] {"/mnt", "/cdrom", "/boot"});
        }
        if (src != null && sysimg == null) {
            sysimg = pick(src, new String[] {"system.efs", "system.sfs"});
        }
        if (src == null) {
            try {
                BufferedReader mounts =
                        new BufferedReader(
                                new FileReader("/proc/mounts"));
                String mline;
                while ((mline = mounts.readLine()) != null) {
                    String[] f = mline.split("\\s+");
                    if (f.length >= 3 && f[2].equals("iso9660")) {
                        logD("DBG iso9660 mount: " + f[0] + " on " + f[1]);
                        String p = pick(f[1],
                                new String[] {"system.efs", "system.sfs"});
                        if (p != null) {
                            src = f[1];
                            sysimg = p;
                            srcDev = f[0];
                            break;
                        }
                        logD("DBG no payload on " + f[1]);
                    }
                }
                mounts.close();
            } catch (Exception ignored) {
            }
        }
        if (src == null) {
            new java.io.File(WORK + "isomnt").mkdirs();
            // Drop any stale mount from a previous run first.
            execQuiet(new String[] {"umount", "-l", WORK + "isomnt"});
            for (String d : partitions()) {
                String t =
                        out(new String[] {"blkid", "-s", "TYPE", "-o",
                                "value", d}).trim().toLowerCase(
                                        java.util.Locale.US);
                logD("DBG trying " + d + " type='" + t + "'");
                boolean mounted = false;
                // Filesystem type from blkid first (Android mount needs -t).
                if (!t.isEmpty()) {
                    mounted =
                            execQuiet(new String[] {"mount", "-t", t, "-o",
                                            "ro", d, WORK + "isomnt"}) == 0;
                }
                // Fallback list for odd layouts (flashed USB sticks etc).
                String[] guess = {"iso9660", "ext4", "vfat", "exfat", "ntfs"};
                for (int gi = 0; !mounted && gi < guess.length; gi++) {
                    if (guess[gi].equals(t)) {
                        continue;
                    }
                    mounted =
                            execQuiet(new String[] {"mount", "-t", guess[gi],
                                            "-o", "ro", d,
                                            WORK + "isomnt"}) == 0;
                }
                if (!mounted) {
                    logD("DBG mount failed: " + d);
                    continue;
                }
                String p = pick(WORK + "isomnt",
                        new String[] {"system.efs", "system.sfs"});
                if (p != null) {
                    src = WORK + "isomnt";
                    sysimg = p;
                    srcDev = d;
                    break;
                }
                logD("DBG no payload on " + d);
                execQuiet(new String[] {"umount", WORK + "isomnt"});
            }
        }
        if (src == null || sysimg == null) {
            dumpMediaState();
            fail("no_media");
            return;
        }
        log("SRC " + src);
        // Payload companions (kernel, initrd.img, ramdisk-recovery.img)
        // live next to the payload file, which may sit nested
        // (Ventoy/extracted trees) rather than at the mount root.
        String payDir = new java.io.File(sysimg).getParent();
        if (payDir == null) {
            payDir = src;
        }
        log("DBG paydir: " + payDir);
        // system.efs/sfs is a WRAPPER around the real ext4 system.img
        // (like Calamares ota+make-ab): mount it ro and install the
        // inner image, never the wrapper itself. A wrapper mounted -r
        // forces its inner loop read-only and the installed system can
        // never remount rw (/sbin, Magisk).
        String sysSrc = unwrapPayload(sysimg);
        if (sysSrc == null) {
            sysSrc = sysimg;
        }
        log("DBG syssrc: " + sysSrc);
        // Never install onto the media we booted from.
        if (srcDev != null && sameDisk(disk, srcDev)) {
            fail("same_disk");
            return;
        }

        // --- 2. Partition / format ---
        String target;
        String esp;
        String tools = PRE + "/../tools";
        String pbin = WORK + "bin/";
        new java.io.File(pbin).mkdirs();
        cp(tools + "/bin/parted", pbin + "parted");
        cp(tools + "/bin/mkfs.vfat", pbin + "mkfs.vfat");
        chmod755(pbin + "parted");
        chmod755(pbin + "mkfs.vfat");
        String penv = "LD_LIBRARY_PATH=" + tools + "/lib";
        if ("disk".equals(mode)) {
            if (execEnv(penv, new String[] {pbin + "parted", "-s", "-a",
                            "optimal", disk, "mklabel", "gpt"}) != 0) {
                fail("gpt_new");
                return;
            }
            if (execEnv(penv, new String[] {pbin + "parted", "-s", "-a",
                            "optimal", disk, "mkpart", "ESP", "fat32", "1MiB",
                            "513MiB"}) != 0) {
                fail("esp_part");
                return;
            }
            execEnv(penv, new String[] {pbin + "parted", "-s", disk, "set",
                    "1", "esp", "on"});
            if (execEnv(penv, new String[] {pbin + "parted", "-s", "-a",
                            "optimal", disk, "mkpart", "system", "ext4",
                            "513MiB", "100%"}) != 0) {
                fail("system_part");
                return;
            }
            target = part(disk, 2);
            esp = part(disk, 1);
            // Like aaropa: plain dosfstools mkfs.vfat, default sizing
            // (it picks a FAT32-valid geometry itself).
            if (execEnv(penv, new String[] {pbin + "mkfs.vfat",
                            esp}) != 0) {
                fail("mkfs_esp");
                return;
            }
        } else if ("alongside".equals(mode)) {
            String[] ae = alongside(disk, job, pbin, penv,
                    minSystemMb(sysSrc, payDir));
            if (ae == null) {
                return;
            }
            target = ae[0];
            esp = ae[1];
        } else {
            target = disk;
            esp = findEsp(disk);
        }
        log("PART " + target + " " + (esp == null ? "none" : esp));
        log("PCT 10");
        if (exec(new String[] {"mkfs.ext4", "-F", "-L", "system",
                        target}) != 0) {
            fail("mkfs");
            return;
        }
        String mnt = WORK + "mnt";
        new java.io.File(mnt).mkdirs();
        if (exec(new String[] {"mount", "-t", "ext4", target, mnt}) != 0) {
            fail("mount");
            return;
        }

        // --- 3. ota: A/B slots from the single ISO payload ---
        // Like Calamares ota+make-ab: slot A gets the image, slot B
        // stays an empty sparse placeholder for the future OTA (which
        // fills it and flips the slot). Copying payload into B only
        // wastes disk and install time.
        for (String slot : new String[] {"a", "b"}) {
            if ("a".equals(slot)) {
                if (exec(new String[] {"cp", sysSrc,
                                mnt + "/system_" + slot + ".img"}) != 0) {
                    failArg("copy_system", slot);
                    return;
                }
            } else {
                // Empty sparse slot, same shape, no content.
                new java.io.File(mnt + "/system_" + slot + ".img")
                        .delete();
            }
            // Extend to 8G sparse (dd seek writes nothing).
            if (exec(new String[] {"dd", "if=/dev/zero",
                            "of=" + mnt + "/system_" + slot + ".img", "bs=1M",
                            "count=0", "seek=8192"}) != 0) {
                failArg("extend_system", slot);
                return;
            }
            if ("b".equals(slot)) {
                // OTA reserve: empty 50M sparse placeholder, the updater
                // fills kernel_b on the next OTA package.
                if (exec(new String[] {"dd", "if=/dev/zero",
                                "of=" + mnt + "/kernel_" + slot, "bs=1M",
                                "count=0", "seek=50"}) != 0) {
                    failArg("copy_kernel", slot);
                    return;
                }
            } else if (exec(new String[] {"cp", payDir + "/kernel",
                            mnt + "/kernel_" + slot}) != 0) {
                failArg("copy_kernel", slot);
                return;
            }
            if (exec(new String[] {"cp", payDir + "/initrd.img",
                            mnt + "/initrd_" + slot + ".img"}) != 0) {
                failArg("copy_initrd", slot);
                return;
            }
            if (new java.io.File(payDir + "/ramdisk-recovery.img").exists()
                    && exec(new String[] {"cp",
                                    payDir + "/ramdisk-recovery.img",
                                    mnt + "/recovery_" + slot + ".img"})
                            != 0) {
                failArg("copy_recovery", slot);
                return;
            }
        }
        log("OK slots");
        log("PCT 40");
        execQuiet(new String[] {"umount", WORK + "paymnt"});

        // --- 4. gen-img: misc + data ---
        new java.io.File(mnt + "/data").mkdirs();
        new java.io.File(mnt + "/boot").mkdirs();
        if (exec(new String[] {"dd", "if=/dev/zero",
                        "of=" + mnt + "/misc.img", "bs=1M",
                        "count=10"}) != 0) {
            fail("misc_img");
            return;
        }
        if ("1".equals(job.get("data_img"))) {
            int size = 4096;
            try {
                int want = Integer.parseInt(job.getOrDefault("data_size", "0"));
                if (want > 0) {
                    size = want;
                }
            } catch (NumberFormatException ignored) {
            }
            if (exec(new String[] {"dd", "if=/dev/zero",
                            "of=" + mnt + "/data.img", "bs=1M",
                            "count=" + size}) != 0) {
                fail("data_img");
                return;
            }
            if (exec(new String[] {"mkfs.ext4", "-F", "-L", "userdata",
                            mnt + "/data.img"}) != 0) {
                fail("mkfs_data");
                return;
            }
            new java.io.File(mnt + "/data").delete();
        }

        // --- 5. gen-fstab ---
        StringBuilder fs = new StringBuilder();
        fs.append("# fstab.android: static file system information.\n");
        fs.append("# FORMAT=0.2\n");
        fs.append("$FS/system$SLOT.img\t\t\t\t\t\tsystem$SLOT\n");
        fs.append("$FS/kernel$SLOT\t\t\t\t\t\t\tkernel$SLOT\n");
        fs.append("$FS/initrd$SLOT.img\t\t\t\t\t\tinitrd$SLOT\n");
        fs.append("$FS/recovery$SLOT.img\t\t\t\t\trecovery$SLOT\n");
        fs.append("$FS/misc.img\t\t\t\t\t\t\tmisc\n");
        fs.append("$FS/boot bootloader\n");
        if ("1".equals(job.get("data_img"))) {
            fs.append("$FS/data.img userdata ext4 defaults defaults\n");
        } else {
            fs.append("$FS/data userdata\n");
        }
        write(mnt + "/fstab.android", fs.toString());
        log("OK fstab");
        log("PCT 60");

        // --- 6. bootcfg ---
        StringBuilder cmd = new StringBuilder("usbcore.autosuspend=-1 quiet");
        String extra = job.get("extra");
        if (extra != null && !extra.trim().isEmpty()) {
            // Option tokens from the options tree (aaropa options.yaml).
            cmd.append(' ').append(extra.trim());
        }
        cmd.append(" androidboot.insecure_adb=1");
        new java.io.File(mnt + "/boot/grub").mkdirs();
        write(mnt + "/boot/grub/android.cfg",
                "SLOT=_a\nCMDLINE='" + cmd + "'\nMODE=normal\n");
        write(mnt + "/cmdline.txt", cmd + "\n");
        log("OK bootcfg");
        log("PCT 65");

        // --- 7. GRUB UEFI ---
        String uuid = out(new String[] {"blkid", "-s", "UUID", "-o", "value",
                target});
        log("UUID " + uuid);
        // Foreign OSes come with the job (scan screen detected once);
        // never re-scan mounts inside the install itself.
        List<String> oses = new ArrayList<>();
        String oslist = job.getOrDefault("oslist", "");
        if (!oslist.isEmpty()) {
            for (String e : oslist.split(";;")) {
                e = e.trim();
                if (!e.isEmpty()) {
                    oses.add(e);
                }
            }
        }
        if (esp != null) {
            String espMnt = WORK + "esp";
            new java.io.File(espMnt).mkdirs();
            if (exec(new String[] {"mount", "-t", "vfat", esp,
                            espMnt}) != 0) {
                fail("mount_esp");
                return;
            }
            new java.io.File(espMnt + "/EFI/BOOT").mkdirs();
            new java.io.File(espMnt + "/EFI/LOS").mkdirs();
            if (exec(new String[] {"cp", PRE + "/efi/BOOTX64.EFI",
                            espMnt + "/EFI/BOOT/"}) != 0) {
                fail("efi_bin");
                return;
            }
            if (execGlob(PRE + "/efi/*.mod", espMnt + "/EFI/LOS/") != 0) {
                fail("efi_mods");
                return;
            }
            write(espMnt + "/EFI/BOOT/grub.cfg",
                    "set timeout=10\nset default=0\n"
                            + "search --no-floppy --fs-uuid --set=root " + uuid
                            + "\nconfigfile /boot/grub/grub.cfg\n");
            exec(new String[] {"umount", espMnt});
            log("OK uefi");
            registerUefiBoot(disk, esp, mode);
            log("PCT 85");
        } else {
            fail("no_esp");
            return;
        }

        // --- 8. grub.cfg on system partition, mirrors aaropa 10_blissos:
        // slots/mode/cmdline come from android.cfg (OTA/bootctrl can switch
        // them), entries are thin. Recovery + Advanced are submenus.
        StringBuilder grub = new StringBuilder();
        grub.append("set timeout=10\nset default=0\n\n");
        grub.append("source ($root)/boot/grub/android.cfg\n");
        grub.append("export SLOT\n");
        grub.append("export CMDLINE\n");
        grub.append("export MODE\n\n");
        grub.append("menuentry 'LineageOS TV' {\n");
        grub.append("  search --no-floppy --fs-uuid --set=root ").append(uuid)
                .append("\n");
        grub.append("  linux /kernel$SLOT $CMDLINE androidboot.slot_suffix=$SLOT androidboot.mode=$MODE"
                + " ROOT=UUID=").append(uuid).append("\n");
        grub.append("  initrd /initrd$SLOT.img\n}\n");
        grub.append(
                "submenu 'Recovery modes for LineageOS TV' --class recovery {\n");
        grub.append("  menuentry 'Recovery Mode' {\n");
        grub.append("    search --no-floppy --fs-uuid --set=root ").append(uuid)
                .append("\n");
        grub.append(
                "    linux /kernel$SLOT $CMDLINE androidboot.slot_suffix=$SLOT androidboot.mode=recovery"
                + " androidboot.force_normal_boot=0 ROOT=UUID=").append(uuid)
                .append("\n");
        grub.append("    initrd /initrd$SLOT.img\n  }\n");
        grub.append("  menuentry 'Recovery Mode - Debug mode' {\n");
        grub.append("    search --no-floppy --fs-uuid --set=root ").append(uuid)
                .append("\n");
        grub.append(
                "    linux /kernel$SLOT $CMDLINE androidboot.slot_suffix=$SLOT androidboot.mode=recovery"
                + " androidboot.force_normal_boot=0"
                + " DEBUG=2 androidboot.enable_console=1 ROOT=UUID=")
                .append(uuid).append("\n");
        grub.append("    initrd /initrd$SLOT.img\n  }\n");
        grub.append("}\n");
        grub.append(
                "submenu 'Advanced options for LineageOS TV' --class submenu {\n");
        grub.append("  menuentry 'Debug mode' {\n");
        grub.append("    search --no-floppy --fs-uuid --set=root ").append(uuid)
                .append("\n");
        grub.append("    linux /kernel$SLOT $CMDLINE androidboot.slot_suffix=$SLOT androidboot.mode=$MODE"
                + " DEBUG=2 androidboot.enable_console=1 ROOT=UUID=")
                .append(uuid).append("\n");
        grub.append("    initrd /initrd$SLOT.img\n  }\n");
        grub.append("  menuentry 'No Modeset' {\n");
        grub.append("    search --no-floppy --fs-uuid --set=root ").append(uuid)
                .append("\n");
        grub.append("    linux /kernel$SLOT $CMDLINE androidboot.slot_suffix=$SLOT androidboot.mode=$MODE"
                + " nomodeset ROOT=UUID=").append(uuid).append("\n");
        grub.append("    initrd /initrd$SLOT.img\n  }\n");
        grub.append("  menuentry 'No hwaccel' {\n");
        grub.append("    search --no-floppy --fs-uuid --set=root ").append(uuid)
                .append("\n");
        grub.append("    linux /kernel$SLOT $CMDLINE androidboot.slot_suffix=$SLOT androidboot.mode=$MODE"
                + " HWACCEL=0 ROOT=UUID=").append(uuid).append("\n");
        grub.append("    initrd /initrd$SLOT.img\n  }\n");
        grub.append("}\n");
        appendOsEntries(grub, oses);
        grub.append("submenu 'Power options' {\n");
        grub.append("  menuentry 'Reboot' { reboot }\n");
        grub.append("  menuentry 'Poweroff' { halt }\n");
        grub.append("  menuentry 'UEFI firmware settings' { fwsetup }\n");
        grub.append("}\n");
        write(mnt + "/boot/grub/grub.cfg", grub.toString());
        new java.io.File(mnt + "/boot/grub/i386-pc").mkdirs();
        execGlob(PRE + "/bios/*.mod", mnt + "/boot/grub/i386-pc/");
        log("OK bioscfg");
        log("PCT 95");

        exec(new String[] {"umount", mnt});
        log("OK done");
        log("PCT 100");
        status("done");
        close();
    }

    // ---------- helpers ----------

    private static Map<String, String> readJob(String path) throws Exception {
        Map<String, String> m = new HashMap<>();
        BufferedReader r = new BufferedReader(new FileReader(path));
        String line;
        while ((line = r.readLine()) != null) {
            int i = line.indexOf('=');
            if (i > 0) {
                m.put(line.substring(0, i).trim(),
                        line.substring(i + 1).trim());
            }
        }
        r.close();
        return m;
    }

    private static String findIn(String[] dirs) {
        for (String d : dirs) {
            if (pick(d, new String[] {"system.efs", "system.sfs"})
                    != null) {
                return d;
            }
        }
        return null;
    }

    private static String pick(String dir, String[] names) {
        for (String n : names) {
            if (new java.io.File(dir + "/" + n).exists()) {
                return dir + "/" + n;
            }
        }
        // Nested layouts (Ventoy/Rufus/extracted trees): search two levels.
        // A hit counts only if the file really exists (tool error text
        // must never become a path).
        for (String n : names) {
            String hit =
                    out(new String[] {"find", dir, "-maxdepth", "3", "-name",
                            n});
            hit = hit.trim();
            if (!hit.isEmpty()) {
                String first = hit.split("\\s+")[0];
                logD("pick find -> " + first);
                if (new java.io.File(first).exists()) {
                    return first;
                }
            }
        }
        return null;
    }

    /**
     * Resolve the boot media exactly like initrd did: kernel cmdline
     * ROOT=LABEL=...|UUID=...|/dev/... plus SRC=<dir>. Returns
     * {mountpoint, payload, device} or null. Falls back to loop backing
     * files (/sys/block/loopN/loop/backing_file) which point at the live
     * image the running system came from.
     */
    private static String[] findViaCmdline() {
        String[] names = {"system.efs", "system.sfs"};
        try {
            BufferedReader c =
                    new BufferedReader(new FileReader("/proc/cmdline"));
            String cmd = c.readLine();
            c.close();
            if (cmd == null) {
                sVerdict = "NOROOT (no cmdline)";
                return null;
            }
            log("DBG cmdline: " + cmd.trim());
            logD("DBG cmdline: " + cmd.trim());
            String root = null;
            String srcSub = null;
            for (String tok : cmd.trim().split("\\s+")) {
                if (tok.startsWith("ROOT=")) {
                    root = tok.substring(5);
                } else if (tok.startsWith("SRC=")) {
                    srcSub = tok.substring(4);
                }
            }
            log("DBG boot ROOT=" + root + " SRC=" + srcSub);
            if (root == null || root.isEmpty()) {
                sVerdict = "NOROOT (no ROOT= in cmdline)";
            }
            if (root != null && !root.isEmpty()) {
                String dev = null;
                if (root.startsWith("LABEL=")) {
                    dev = out(new String[] {"blkid", "-L",
                            root.substring(6)}).trim();
                    log("DBG blkid -L " + root.substring(6) + " -> '"
                            + dev + "'");
                } else if (root.startsWith("UUID=")) {
                    dev = out(new String[] {"blkid", "-U",
                            root.substring(5)}).trim();
                    log("DBG blkid -U " + root.substring(5) + " -> '"
                            + dev + "'");
                } else {
                    dev = root;
                }
                if (dev != null && !dev.isEmpty()) {
                    String mnt = mountRo(dev);
                    log("DBG mountRo " + dev + " -> '" + mnt + "'");
                    if (mnt != null) {
                        if (srcSub != null && !srcSub.isEmpty()) {
                            String p = pick(mnt + "/" + srcSub, names);
                            if (p != null) {
                                log("SRC via cmdline ROOT=" + root
                                        + " SRC=" + srcSub);
                                return new String[] {
                                        mnt + "/" + srcSub, p, dev};
                            }
                            logD("DBG no payload in " + mnt + "/"
                                    + srcSub);
                        }
                        String p = pick(mnt, names);
                        if (p != null) {
                            log("SRC via cmdline ROOT=" + root);
                            return new String[] {mnt, p, dev};
                        }
                        sVerdict = "NOPAYLOAD on " + dev + " at " + mnt;
                        log("DBG verdict: media mounted but no "
                                + "system.efs/sfs");
                        log("DBG top of " + mnt + ": " + topList(mnt));
                        log("DBG top of " + mnt + "/" + srcSub + ": "
                                + topList(mnt + "/" + srcSub));
                    } else {
                        sVerdict = "MOUNTFAIL " + dev;
                    }
                } else {
                    sVerdict = "UNRESOLVED " + root;
                    logD("DBG ROOT unresolved, trying loop backing files");
                }
            }
        } catch (Exception ignored) {
        }
        // Loop backing files: the live system image we booted from.
        try {
            String[] loops = new java.io.File("/sys/block").list();
            if (loops != null) {
                for (String l : loops) {
                    if (!l.startsWith("loop")) {
                        continue;
                    }
                    String bf = readFirst(
                            "/sys/block/" + l + "/loop/backing_file");
                    if (bf == null || bf.isEmpty()
                            || bf.contains("(deleted)")) {
                        continue;
                    }
                    logD("DBG loop " + l + " backing: " + bf);
                    java.io.File f = new java.io.File(bf);
                    for (int up = 0; up < 3 && f != null; up++) {
                        f = f.getParentFile();
                        if (f == null) {
                            break;
                        }
                        String p = pick(f.getAbsolutePath(), names);
                        if (p != null) {
                            log("SRC via loop " + l + " backing " + bf);
                            // Device unknown here; same-disk guard below
                            // is skipped (null srcDev).
                            return new String[] {
                                    f.getAbsolutePath(), p, null};
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Ventoy sticks: the ISO lives as a file on the exFAT data partition
     * (vold mounts it under /mnt/media_rw/&lt;uuid&gt;) and/or as a
     * device-mapper image. Returns {mountpoint, payload, null} or null.
     */
    private static String[] findViaVentoy() {
        String[] names = {"system.efs", "system.sfs"};
        // 1. ISO files on USB/media partitions (vold: public volumes
        // under /mnt/media_rw, adopted/private under /mnt/expand).
        // Fallback path (e.g. memdisk boot with no device mapping):
        // plain trial, first ISO with payload wins.
        try {
            String[] roots = {"/mnt/media_rw", "/mnt/expand"};
            for (String root : roots) {
                java.io.File rw = new java.io.File(root);
                String[] vols = rw.list();
                if (vols == null) {
                    continue;
                }
                for (String v : vols) {
                    String hit = out(new String[] {"find",
                            root + "/" + v, "-maxdepth", "3",
                            "-iname", "*.iso"});
                    for (String iso : hit.split("\\s+")) {
                        iso = iso.trim();
                        if (iso.isEmpty()) {
                            continue;
                        }
                        log("DBG ventoy iso: " + iso);
                        String mnt = mountIsoLoop(iso);
                        if (mnt == null) {
                            continue;
                        }
                        String p = pick(mnt, names);
                        if (p != null) {
                            log("SRC via ventoy iso " + iso);
                            return new String[] {mnt, p, null};
                        }
                        logD("DBG no payload in " + iso);
                        execQuiet(new String[] {"umount", mnt});
                    }
                }
            }
        } catch (Exception e) {
            logD("DBG ventoy iso scan: " + e);
        }
        // 2. Device-mapper images (Ventoy-mapped ISO etc).
        // Candidates from /dev/block/dm-* and /dev/mapper/* alike:
        // node names differ per device/Ventoy mode, payload decides.
        try {
            List<String> dmCands = new ArrayList<>();
            String[] blkDevs = new java.io.File("/dev/block").list();
            if (blkDevs != null) {
                for (String n : blkDevs) {
                    if (n.startsWith("dm-")) {
                        dmCands.add("/dev/block/" + n);
                    }
                }
            }
            String[] mapDevs = new java.io.File("/dev/mapper").list();
            if (mapDevs != null) {
                for (String n : mapDevs) {
                    if (!n.equals("control")) {
                        dmCands.add("/dev/mapper/" + n);
                    }
                }
            }
            for (String d : dmCands) {
                String mnt = mountRo(d);
                logD("DBG ventoy dm " + d + " -> '" + mnt + "'");
                if (mnt == null
                        || mnt.equals(WORK + "isomnt")) {
                    // mountRo reused isomnt: check then release.
                    String p = mnt == null ? null : pick(mnt, names);
                    if (p != null) {
                        log("SRC via dm " + d);
                        return new String[] {mnt, p, d};
                    }
                    if (mnt != null) {
                        execQuiet(new String[] {"umount", mnt});
                    }
                } else {
                    // Already mounted elsewhere (e.g. /boot): check
                    // in place, never unmount someone else's mount.
                    String p = pick(mnt, names);
                    if (p != null) {
                        log("SRC via dm " + d + " at " + mnt);
                        return new String[] {mnt, p, d};
                    }
                }
            }
        } catch (Exception e) {
            logD("DBG ventoy dm scan: " + e);
        }
        sVerdict = "NOVENTOY (no ISO file on USB, no dm payload)";
        return null;
    }

    /**
     * Register our bootloader in NVRAM and put it first in BootOrder
     * (what the Calamares UEFI module did). Non-fatal: without it the
     * firmware may keep booting the USB stick first.
     */
    private static void registerUefiBoot(
            String disk, String esp, String mode) {
        // efivars must be mounted for reads AND writes.
        execQuiet(new String[] {"mount", "-t", "efivarfs", "none",
                "/sys/firmware/efi/efivars"});
        // efibootmgr cannot handle /dev/block/* paths ("Could not prepare
        // Boot variable"): symlink the classic /dev/<base> node and use it.
        String efiDisk = disk;
        if (disk.startsWith("/dev/block/")) {
            String base = new java.io.File(disk).getName();
            execQuiet(
                    new String[] {"ln", "-sf", disk, "/dev/" + base});
            efiDisk = "/dev/" + base;
        }
        // ESP partition number: we create it as 1 in disk mode, else
        // parse the trailing digits (sda1 -> 1, nvme0n1p2 -> 2).
        String num = "1";
        if (!"disk".equals(mode) && esp != null) {
            String base = new java.io.File(esp).getName();
            String digits = base.replaceFirst("^.*?(\\d+)$", "$1");
            if (!digits.isEmpty() && digits.matches("\\d+")) {
                num = digits;
            }
        }
        // Drop our stale entries first: every run must leave exactly
        // one LineageOS record, or the firmware menu rots with dups.
        String verbose = outLines(new String[] {"efibootmgr", "-v"});
        for (String line : verbose.split("\n")) {
            line = line.trim();
            if (line.matches("(?i)boot[0-9a-f]{4}\\*?\\s+LineageOS.*")) {
                String stale =
                        line.substring(4, 8).toUpperCase(
                                java.util.Locale.US);
                logD("DBG removing stale LineageOS " + stale);
                execQuiet(new String[] {"efibootmgr", "-b", stale, "-B"});
            }
        }
        String create = out(new String[] {"efibootmgr", "-c", "-d", efiDisk,
                "-p", num, "-L", "LineageOS", "-l",
                "\\EFI\\BOOT\\BOOTX64.EFI"});
        logD("DBG efibootmgr -c: " + create);
        String bootnum = null;
        for (String tok : create.split("\\s+")) {
            if (tok.matches("(?i)boot[0-9a-f]{4}\\*?")) {
                bootnum = tok.replaceAll("(?i)^boot|\\*$", "");
                break;
            }
        }
        // Fallback: newest entry = max BootXXXX in verbose listing.
        if (bootnum == null) {
            String verbose = out(new String[] {"efibootmgr", "-v"});
            int max = -1;
            for (String line : verbose.split("\n")) {
                line = line.trim();
                if (line.matches("(?i)boot[0-9a-f]{4}\\*?.*")) {
                    try {
                        int n = Integer.parseInt(
                                line.substring(4, 8), 16);
                        if (n > max) {
                            max = n;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            if (max >= 0) {
                bootnum = String.format("%04X", max);
            }
        }
        if (bootnum == null) {
            fail("efi_create");
            return;
        }
        String order = "";
        String cur = out(new String[] {"efibootmgr"});
        for (String line : cur.split("\n")) {
            line = line.trim();
            if (line.startsWith("BootOrder:")) {
                order = line.substring(10).trim();
                break;
            }
        }
        List<String> seq = new ArrayList<>();
        seq.add(bootnum.toUpperCase(java.util.Locale.US));
        for (String b : order.split(",")) {
            b = b.trim().toUpperCase(java.util.Locale.US);
            if (!b.isEmpty() && !seq.contains(b)) {
                seq.add(b);
            }
        }
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < seq.size(); i++) {
            if (i > 0) {
                csv.append(",");
            }
            csv.append(seq.get(i));
        }
        if (exec(new String[] {"efibootmgr", "-o", csv.toString()})
                != 0) {
            fail("efi_order");
            return;
        }
        // Read back: some firmwares (or a booted Windows) rewrite the
        // order behind our back. Trust NVRAM, not the exit code.
        String back = outLines(new String[] {"efibootmgr"});
        String first = "";
        for (String line : back.split("\n")) {
            line = line.trim();
            if (line.startsWith("BootOrder:")) {
                String[] parts = line.substring(10).trim().split(",");
                if (parts.length > 0) {
                    first = parts[0].trim().toUpperCase(
                            java.util.Locale.US);
                }
                break;
            }
        }
        if (bootnum.toUpperCase(java.util.Locale.US).equals(first)) {
            log("OK efiboot");
        } else {
            logD("DBG BootOrder readback: " + first);
            fail("efi_order");
        }
    }

    /** Mount an ISO file ro via loop. Returns mountpoint or null. */
    private static String mountIsoLoop(String iso) {
        new java.io.File(WORK + "isomnt").mkdirs();
        execQuiet(new String[] {"umount", "-l", WORK + "isomnt"});
        if (execQuiet(new String[] {"mount", "-o", "loop,ro", iso,
                        WORK + "isomnt"}) == 0) {
            return WORK + "isomnt";
        }
        logD("DBG mount -o loop failed for " + iso + ", trying losetup");
        String loop = out(new String[] {"losetup", "-f"}).trim();
        if (loop.isEmpty()) {
            return null;
        }
        loop = loop.split("\\s+")[0];
        if (execQuiet(new String[] {"losetup", loop, iso}) != 0) {
            return null;
        }
        if (execQuiet(new String[] {"mount", "-t", "iso9660", "-o", "ro",
                        loop, WORK + "isomnt"}) == 0) {
            return WORK + "isomnt";
        }
        execQuiet(new String[] {"losetup", "-d", loop});
        return null;
    }

    /**
     * Payload wrappers (system.efs erofs / system.sfs squashfs) contain
     * the real ext4 system.img inside. Mount the wrapper ro and return
     * the inner image path; null when the payload IS the image.
     * The mount stays up until the slots are copied (released below).
     */
    private static String unwrapPayload(String sysimg) {
        String pmnt = WORK + "paymnt";
        new java.io.File(pmnt).mkdirs();
        execQuiet(new String[] {"umount", "-l", pmnt});
        String[] guess = {"erofs", "squashfs", "ext4", "iso9660"};
        for (String t : guess) {
            if (execQuiet(new String[] {"mount", "-t", t, "-o", "ro",
                            sysimg, pmnt}) != 0) {
                continue;
            }
            String inner = pick(pmnt, new String[] {"system.img"});
            if (inner != null) {
                log("DBG unwrapped " + sysimg + " -> " + inner);
                return inner;
            }
            execQuiet(new String[] {"umount", pmnt});
            // Mounted but no inner image: payload is used as-is.
            logD("DBG payload has no inner system.img (" + t + ")");
            return null;
        }
        logD("DBG payload not mountable, using as-is");
        return null;
    }

    /** Top-level names of dir (max 12), for the on-screen verdict. */
    private static String topList(String dir) {
        try {
            String[] names = new java.io.File(dir).list();
            if (names == null) {
                return "(unreadable)";
            }
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < names.length && i < 12; i++) {
                if (i > 0) {
                    b.append(", ");
                }
                b.append(names[i]);
            }
            if (names.length > 12) {
                b.append(", ...(").append(names.length).append(")");
            }
            return b.toString();
        } catch (Exception e) {
            return "(err)";
        }
    }

    /**
     * Last-resort diagnostics: partition table with blkid labels/types plus
     * the live mount table. Verbose part goes to installer-debug.log
     * (file only); on screen only the short verdict stays visible.
     */
    private static void dumpMediaState() {
        log("DBG verdict: " + sVerdict);
        logD("DBG verdict: " + sVerdict);
        logD("DBG --- partitions ---");
        for (String d : partitions()) {
            String info = out(new String[] {"blkid", d}).trim();
            logD("DBG " + d + " :: " + info);
        }
        logD("DBG --- mounts ---");
        try {
            BufferedReader mounts =
                    new BufferedReader(new FileReader("/proc/mounts"));
            String mline;
            while ((mline = mounts.readLine()) != null) {
                logD("DBG mnt: " + mline);
            }
            mounts.close();
        } catch (Exception ignored) {
        }
    }

    private static String readFirst(String path) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(path));
            String s = r.readLine();
            r.close();
            return s == null ? null : s.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** Mount dev ro, reusing an existing mount if present. */
    /** ROOT=LABEL= value of the booted media (to skip it while probing). */
    private static String bootLabel() {
        try {
            BufferedReader c =
                    new BufferedReader(new FileReader("/proc/cmdline"));
            String cmd = c.readLine();
            c.close();
            if (cmd != null) {
                for (String tok : cmd.trim().split("\\s+")) {
                    if (tok.startsWith("ROOT=LABEL=")) {
                        return tok.substring(11);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Read-only scan: geometry from ONE parted call per disk (no mounts),
     * OS probes mount only promising fstypes once (os-prober style).
     * Seconds for a whole disk.
     */
    private static void doScan() {
        log("SCAN begin");
        String tools = PRE + "/../tools";
        String pbin = WORK + "bin/";
        new java.io.File(pbin).mkdirs();
        try {
            cp(tools + "/bin/parted", pbin + "parted");
            chmod755(pbin + "parted");
        } catch (Exception e) {
            log("DBG parted setup: " + e);
        }
        String penv = "LD_LIBRARY_PATH=" + tools + "/lib";
        java.util.Set<String> disks = new java.util.TreeSet<>();
        for (String p : partitions()) {
            String base = new java.io.File(p).getName();
            String d = base.replaceFirst("p?\\d+$", "");
            if (d.endsWith("p")) {
                d = d.substring(0, d.length() - 1);
            }
            if (!d.isEmpty() && !d.startsWith("loop") && !d.startsWith("ram")
                    && !d.startsWith("dm-")) {
                disks.add(d);
            }
        }
        for (String d : disks) {
            String disk = "/dev/block/" + d;
            long diskMb = diskSizeMb(d);
            log("DISK " + disk + " " + diskMb);
            String print = execParted(pbin, penv, disk);
            if (print.isEmpty()) {
                logD("DBG no parted output for " + disk);
                continue;
            }
            boolean sawFree = false;
            for (String line : print.split("\n")) {
                line = line.trim();
                String[] f = line.split("\\s+");
                if (f.length < 3) {
                    continue;
                }
                if (line.contains("Free Space")) {
                    int freeIdx = -1;
                    for (int i = 0; i < f.length; i++) {
                        if (f[i].equals("Free")) {
                            freeIdx = i;
                            break;
                        }
                    }
                    if (freeIdx >= 3) {
                        try {
                            long s = (long) parseMib(f[freeIdx - 3]);
                            long e = (long) parseMib(f[freeIdx - 2]);
                            log("FREE " + disk + " " + s + " " + e);
                            sawFree = true;
                        } catch (Exception ignored) {
                        }
                    }
                    continue;
                }
                int num;
                try {
                    num = Integer.parseInt(f[0]);
                } catch (Exception e) {
                    continue;
                }
                try {
                    long s = (long) parseMib(f[1]);
                    long e = (long) parseMib(f[2]);
                    String fstype = f.length > 4 ? f[4] : "";
                    String dev = part(disk, num);
                    String label = out(new String[] {"blkid", "-s", "LABEL",
                            "-o", "value", dev}).trim().replaceAll(
                                    "\\s+", "_");
                    log("PART " + dev + " " + s + " " + e + " " + fstype
                            + " " + label);
                } catch (Exception ignored) {
                }
            }
            if (!sawFree) {
                // This parted prints no "Free Space" rows: derive gaps
                // from the partition geometry itself.
                for (double[] g : freeGaps(print, diskMb)) {
                    log("FREE " + disk + " " + ((long) g[0]) + " "
                            + ((long) g[1]));
                }
            }
        }
        detectOses();
        payloadMin();
        log("SCAN end");
    }

    /**
     * Payload minimum for the alongside divider: inner system image (or
     * the file itself) + kernel + initrd + recovery + buffer. Reuses
     * only already-mounted media (no sweep): the live system booted
     * from it, so it is usually right here.
     */
    private static void payloadMin() {
        // Cheap sweeps first: findViaCmdline mounts every partition and
        // costs ~25s, so it stays a fallback for media the sweep misses.
        // (Loop-booted payloads like this box stay unresolved here;
        // the install path resolves them with its full media sweep and
        // enforces the minimum itself.)
        String[] roots = {"/mnt", "/cdrom", "/boot"};
        try {
            BufferedReader mounts =
                    new BufferedReader(new FileReader("/proc/mounts"));
            String mline;
            while ((mline = mounts.readLine()) != null) {
                String[] f = mline.split("\\s+");
                if (f.length >= 3 && f[2].equals("iso9660")) {
                    String p = pick(f[1],
                            new String[] {"system.efs", "system.sfs"});
                    if (p != null) {
                        log("MIN " + payloadMinFor(p));
                        mounts.close();
                        return;
                    }
                }
            }
            mounts.close();
        } catch (Exception ignored) {
        }
        for (String r : roots) {
            String p = pick(r, new String[] {"system.efs", "system.sfs"});
            if (p != null) {
                log("MIN " + payloadMinFor(p));
                return;
            }
        }
    }

    private static long payloadMinFor(String sysimg) {
        java.io.File base = new java.io.File(sysimg);
        String dir = base.getParent();
        if (dir == null) {
            dir = "";
        }
        // Inner image when the payload is a wrapper, else the file.
        long sys = fileMb(sysimg);
        String inner = null;
        String pmnt = WORK + "paymnt";
        new java.io.File(pmnt).mkdirs();
        execQuiet(new String[] {"umount", "-l", pmnt});
        for (String t : new String[] {"erofs", "squashfs"}) {
            if (execQuiet(new String[] {"mount", "-t", t, "-o", "ro",
                            sysimg, pmnt}) == 0) {
                String hit = pick(pmnt, new String[] {"system.img"});
                execQuiet(new String[] {"umount", pmnt});
                if (hit != null) {
                    inner = hit;
                }
                break;
            }
        }
        if (inner != null) {
            sys = fileMb(inner);
        }
        long total = sys;
        total += fileMb(dir + "/kernel");
        total += fileMb(dir + "/initrd.img");
        total += fileMb(dir + "/ramdisk-recovery.img");
        total += 512;
        return Math.max(total, 1024);
    }

    private static long diskSizeMb(String base) {
        try {
            BufferedReader r = new BufferedReader(
                    new FileReader("/sys/block/" + base + "/size"));
            long sectors = Long.parseLong(r.readLine().trim());
            r.close();
            return sectors / 2048;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String execParted(String pbin, String penv, String disk) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    pbin + "parted", "-s", disk, "unit", "MiB", "print");
            java.util.Map<String, String> e = pb.environment();
            e.put("LD_LIBRARY_PATH",
                    penv.replaceFirst("^LD_LIBRARY_PATH=", ""));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try {
                p.getOutputStream().close();
            } catch (Exception ignored) {
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return "";
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static double parseMib(String s) {
        return Double.parseDouble(s.replace("MiB", ""));
    }

    /**
     * Foreign-OS scan mirroring os-prober probes (efi/20microsoft,
     * 20macosx, 90linux-distro) plus an Android-x86 probe os-prober
     * lacks. Each partition mounted at most once, by blkid type;
     * swap/crypto/LVM/optical/boot media never mounted.
     */
    private static List<String> detectOses() {
        List<String> found = new ArrayList<>();
        String bootLbl = bootLabel();
        new java.io.File(WORK + "scan").mkdirs();
        execQuiet(new String[] {"umount", "-l", WORK + "scan"});
        for (String d : partitions()) {
            String t = out(new String[] {"blkid", "-s", "TYPE", "-o",
                    "value", d}).trim().toLowerCase(java.util.Locale.US);
            if (t.contains("iso9660") || t.contains("udf")) {
                continue;
            }
            if (t.contains("swap") || t.contains("crypto")
                    || t.contains("LVM2") || t.contains("raid")
                    || t.contains("zfs") || t.contains("pmem")) {
                continue;
            }
            if (bootLbl != null && !bootLbl.isEmpty()) {
                String lbl = out(new String[] {"blkid", "-s", "LABEL",
                        "-o", "value", d}).trim();
                if (bootLbl.equals(lbl)) {
                    continue;
                }
            }
            boolean ok = false;
            if (!t.isEmpty()) {
                ok = execQuiet(new String[] {"mount", "-t", t, "-o", "ro",
                        d, WORK + "scan"}) == 0;
            }
            if (!ok) {
                ok = execQuiet(new String[] {"mount", "-o", "ro", d,
                        WORK + "scan"}) == 0;
            }
            if (!ok) {
                continue;
            }
            String hit = classifyOs(WORK + "scan", t);
            // Used space for the shrink math (df on the ro mount:
            // instant, any filesystem).
            long usedMb = diskUsedMb(WORK + "scan");
            if (usedMb >= 0) {
                log("USE " + d + " " + usedMb);
            }
            execQuiet(new String[] {"umount", WORK + "scan"});
            if (hit != null) {
                String line = hit + "|" + d;
                log("OS " + line);
                found.add(line);
            }
        }
        return found;
    }

    /** Used MiB on dev via a throwaway ro mount, or -1 when unknown. */
    private static long mountDfUsed(String dev, String fstype) {
        String mnt = WORK + "measure";
        try {
            new java.io.File(mnt).mkdirs();
            execQuiet(new String[] {"umount", "-l", mnt});
            boolean ok = false;
            if (fstype != null && !fstype.isEmpty()) {
                ok = execQuiet(new String[] {"mount", "-t", fstype, "-o",
                        "ro", dev, mnt}) == 0;
            }
            if (!ok) {
                ok = execQuiet(new String[] {"mount", "-o", "ro", dev,
                        mnt}) == 0;
            }
            if (!ok) {
                return -1;
            }
            long used = diskUsedMb(mnt);
            execQuiet(new String[] {"umount", mnt});
            return used;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Used MiB on a mounted fs via statvfs (df), or -1. */
    private static long diskUsedMb(String mnt) {        String o = outLines(new String[] {"df", mnt});
        for (String line : o.split("\n")) {
            line = line.trim();
            if (line.startsWith("/dev/") || line.startsWith("overlay")
                    || line.startsWith("tmpfs")) {
                String[] f = line.split("\\s+");
                // Filesystem 1K-blocks Used Available Use% Mounted on
                if (f.length >= 4) {
                    try {
                        long totalKb = Long.parseLong(f[1]);
                        long availKb = Long.parseLong(f[3]);
                        return (totalKb - availKb) / 1024;
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return -1;
    }

    /** os-prober-faithful classification of a ro mount. */
    private static String classifyOs(String mnt, String fstype) {
        java.io.File m = new java.io.File(mnt);
        // Windows EFI: EFI/<vendor>/Boot/{bcd AND bootmgfw.efi}.
        String efi = ciDir(m, "EFI");
        if (efi != null) {
            String[] vendors = new java.io.File(m, efi).list();
            if (vendors != null) {
                for (String v : vendors) {
                    if (v.equalsIgnoreCase("boot")) {
                        continue;
                    }
                    String boot = ciPath(m, efi + "/" + v + "/Boot");
                    if (boot != null && ciExists(m, boot + "/bcd")
                            && ciExists(m, boot + "/bootmgfw.efi")) {
                        return "win|Windows|\\"
                                + boot.replace('/', '\\') + "\\"
                                + ciName(m, boot + "/bootmgfw.efi");
                    }
                }
            }
        }
        // Windows BIOS: ntfs + System32.
        if ((fstype.contains("ntfs") || fstype.contains("exfat"))
                && ciExists(m, "Windows/System32")) {
            return "win|Windows|";
        }
        // macOS: hfsplus + mach_kernel, nothing else.
        if (fstype.contains("hfs") && ciExists(m, "mach_kernel")) {
            String ver = readPlistVersion(m);
            String sys = ciPath(m, "System/Library/CoreServices");
            return "macos|" + (ver == null ? "macOS" : ver) + "|"
                    + (sys == null ? ""
                            : "\\" + sys.replace('/', '\\') + "\\boot.efi");
        }
        // Linux: root proof first, then os-release.
        if (hasLinuxRoot(m)) {
            String[] names = readOsRelease(m, "etc/os-release,usr/lib/os-release");
            if (names != null) {
                return "linux|" + names[1] + "|";
            }
        }
        // Android-x86: slot images + kernel aside.
        String[] names = m.list();
        if (names != null) {
            boolean sys = false;
            boolean kern = false;
            for (String n : names) {
                String ln = n.toLowerCase(java.util.Locale.US);
                if ((ln.startsWith("system_") || ln.equals("system.img"))
                        && (ln.endsWith(".img") || ln.endsWith(".efs")
                                || ln.endsWith(".sfs"))) {
                    sys = true;
                }
                if (ln.startsWith("kernel")) {
                    kern = true;
                }
            }
            if (sys && kern) {
                String[] bp = readOsRelease(m, "build.prop");
                return "android|" + (bp == null ? "Android-x86" : bp[0]) + "|";
            }
        }
        return null;
    }

    private static String ciName(java.io.File base, String sub) {
        int slash = sub.lastIndexOf('/');
        String dir = slash < 0 ? "" : sub.substring(0, slash);
        String want = slash < 0 ? sub : sub.substring(slash + 1);
        java.io.File parent =
                dir.isEmpty() ? base : new java.io.File(base, dir);
        String[] kids = parent.list();
        if (kids == null) {
            return null;
        }
        for (String k : kids) {
            if (k.equalsIgnoreCase(want)) {
                return k;
            }
        }
        return null;
    }

    private static boolean ciExists(java.io.File base, String sub) {
        return ciPath(base, sub) != null;
    }

    private static String ciPath(java.io.File base, String sub) {
        java.io.File cur = base;
        for (String part : sub.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            String[] kids = cur.list();
            if (kids == null) {
                return null;
            }
            boolean ok = false;
            for (String k : kids) {
                if (k.equalsIgnoreCase(part)) {
                    cur = new java.io.File(cur, k);
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return null;
            }
        }
        String abs = cur.getAbsolutePath();
        String root = base.getAbsolutePath();
        return abs.substring(root.length() + 1);
    }

    private static String ciDir(java.io.File base, String name) {
        String hit = ciPath(base, name);
        if (hit != null && new java.io.File(base, hit).isDirectory()) {
            return hit;
        }
        return null;
    }

    private static boolean hasLinuxRoot(java.io.File m) {
        String[] libs = m.list();
        if (libs != null) {
            boolean boot = false;
            boolean ld = false;
            for (String k : libs) {
                if (k.equalsIgnoreCase("boot")) {
                    boot = true;
                }
                if (k.toLowerCase(java.util.Locale.US).startsWith("lib")) {
                    String[] inner = new java.io.File(m, k).list();
                    if (inner != null) {
                        for (String f : inner) {
                            if (f.startsWith("ld-") && f.endsWith(".so")) {
                                ld = true;
                                break;
                            }
                        }
                    }
                }
                if (boot && ld) {
                    return true;
                }
            }
        }
        String[] inner = new java.io.File(m, "usr/lib").list();
        if (inner != null) {
            for (String f : inner) {
                if (f.startsWith("ld-") && f.endsWith(".so")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String readPlistVersion(java.io.File m) {
        java.io.File plist = new java.io.File(m,
                "System/Library/CoreServices/SystemVersion.plist");
        if (!plist.exists()) {
            return null;
        }
        try {
            BufferedReader r = new BufferedReader(new FileReader(plist));
            String line;
            String wantName = null;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.contains("ProductName")) {
                    String next = r.readLine();
                    if (next != null) {
                        wantName = next.replaceAll(".*<string>(.*)</string>.*",
                                "$1").trim();
                    }
                }
                if (line.contains("ProductVersion") && wantName != null) {
                    String next = r.readLine();
                    r.close();
                    if (next != null) {
                        return (wantName + " " + next.replaceAll(
                                ".*<string>(.*)</string>.*", "$1").trim())
                                .trim();
                    }
                    return wantName;
                }
            }
            r.close();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String[] readOsRelease(java.io.File mnt, String files) {
        for (String f : files.split(",")) {
            java.io.File file = new java.io.File(mnt, f.trim());
            if (!file.exists()) {
                continue;
            }
            try {
                String name = null;
                String pretty = null;
                BufferedReader r = new BufferedReader(new FileReader(file));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("NAME=") && name == null) {
                        name = unquote(line.substring(5));
                    } else if (line.startsWith("PRETTY_NAME=")
                            && pretty == null) {
                        pretty = unquote(line.substring(12));
                    } else if (line.startsWith("ro.lineage.version=")) {
                        String v = line.substring(19).trim();
                        if (!v.isEmpty()) {
                            name = "LineageOS";
                            pretty = "LineageOS " + v;
                        }
                    } else if (line.startsWith("ro.bliss.version=")) {
                        String v = line.substring(17).trim();
                        if (!v.isEmpty()) {
                            name = "BlissOS";
                            pretty = "BlissOS " + v;
                        }
                    }
                }
                r.close();
                if (pretty != null) {
                    return new String[] {name == null ? pretty : name, pretty};
                }
                if (name != null) {
                    return new String[] {name, name};
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String unquote(String v) {
        v = v.trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() > 1) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String mountRo(String dev) {
        new java.io.File(WORK + "isomnt").mkdirs();
        String base = dev.substring(dev.lastIndexOf('/') + 1);
        try {
            BufferedReader mounts =
                    new BufferedReader(new FileReader("/proc/mounts"));
            String mline;
            while ((mline = mounts.readLine()) != null) {
                String[] f = mline.split("\\s+");
                if (f.length >= 2
                        && (f[0].equals(dev)
                                || f[0].equals("/dev/block/" + base))) {
                    mounts.close();
                    return f[1];
                }
            }
            mounts.close();
        } catch (Exception ignored) {
        }
        String[] cands = {dev, "/dev/block/" + base};
        for (String d : cands) {
            if (!new java.io.File(d).exists()) {
                continue;
            }
            String t = out(new String[] {"blkid", "-s", "TYPE", "-o",
                    "value", d}).trim().toLowerCase(java.util.Locale.US);
            boolean ok = false;
            if (!t.isEmpty()) {
                ok = execQuiet(new String[] {"mount", "-t", t, "-o", "ro",
                        d, WORK + "isomnt"}) == 0;
            }
            // Explicit fstype sweep: blkid may stay silent (dm images)
            // and bare `mount` without -t does not always autodetect.
            String[] guess = {
                    "iso9660", "ext4", "erofs", "squashfs", "vfat",
                    "exfat", "ntfs"};
            for (int gi = 0; !ok && gi < guess.length; gi++) {
                if (guess[gi].equals(t)) {
                    continue;
                }
                ok = execQuiet(new String[] {"mount", "-t", guess[gi],
                        "-o", "ro", d, WORK + "isomnt"}) == 0;
            }
            if (!ok) {
                ok = execQuiet(new String[] {"mount", "-o", "ro", d,
                        WORK + "isomnt"}) == 0;
            }
            if (ok) {
                return WORK + "isomnt";
            }
        }
        return null;
    }

    /** All disk/partition/optical names from /proc/partitions. */
    private static List<String> partitions() {
        List<String> out = new ArrayList<>();
        try {
            BufferedReader r =
                    new BufferedReader(new FileReader("/proc/partitions"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("major")) {
                    continue;
                }
                String[] f = line.split("\\s+");
                if (f.length < 4) {
                    continue;
                }
                String name = f[3];
                if (name.startsWith("loop") || name.startsWith("ram")
                        || name.startsWith("dm-")) {
                    continue;
                }
                out.add("/dev/block/" + name);
            }
            r.close();
        } catch (Exception ignored) {
        }
        return out;
    }

    /** True when both paths sit on the same physical disk. */
    private static boolean sameDisk(String a, String b) {
        String da = new java.io.File(a).getName();
        String db = new java.io.File(b).getName();
        // Strip partition suffix: sda1 -> sda, nvme0n1p2 -> nvme0n1.
        da = da.replaceFirst("p?\\d+$", "");
        db = db.replaceFirst("p?\\d+$", "");
        if (da.endsWith("p")) {
            da = da.substring(0, da.length() - 1);
        }
        if (db.endsWith("p")) {
            db = db.substring(0, db.length() - 1);
        }
        return !da.isEmpty() && da.equals(db);
    }

    private static String part(String disk, int n) {
        if (disk.matches(".*(mmcblk|nvme|loop).*")
                || disk.matches(".*[0-9]")) {
            return disk + "p" + n;
        }
        return disk + n;
    }

    private static String findEsp(String disk) {
        java.io.File dev = new java.io.File("/dev/block");
        String[] names = dev.list();
        if (names == null) {
            return null;
        }
        String base = new java.io.File(disk).getName();
        for (String n : names) {
            if (!n.startsWith(base) || n.equals(base)) {
                continue;
            }
            String p = "/dev/block/" + n;
            if (out(new String[] {"blkid", "-s", "TYPE", "-o", "value",
                            p}).contains("vfat")) {
                return p;
            }
        }
        return null;
    }

    /** blkid UUID of a partition (for GRUB search). */
    private static String uuidOf(String part) {
        return out(new String[] {"blkid", "-s", "UUID", "-o", "value",
                part}).trim();
    }

    /**
     * GRUB entries for detected foreign OSes, mirroring stock
     * 30_os-prober: EFI chainload by UUID, BIOS Windows via +1,
     * other Android installs via their grub.cfg.
     */
    private static void appendOsEntries(StringBuilder grub, List<String> oses) {
        for (String e : oses) {
            // type|label|loader|part
            String[] f = e.split("\\|", -1);
            if (f.length < 4) {
                continue;
            }
            String type = f[0];
            String label = f[1].replace("'", "");
            String loader = f[2].replace('\\', '/');
            String part = f[3];
            if ("android".equals(type)) {
                String puuid = uuidOf(part);
                if (puuid.isEmpty()) {
                    continue;
                }
                grub.append("menuentry '").append(label).append(" (Android)' {\n");
                grub.append("  insmod part_msdos\n  insmod part_gpt\n");
                grub.append("  insmod ext2\n  insmod configfile\n");
                grub.append("  search --no-floppy --fs-uuid --set=root ")
                        .append(puuid).append("\n");
                grub.append("  configfile /boot/grub/grub.cfg\n}\n");
            } else if ("win".equals(type) && loader.isEmpty()) {
                String puuid = uuidOf(part);
                if (puuid.isEmpty()) {
                    continue;
                }
                grub.append("menuentry '").append(label).append("' {\n");
                grub.append("  insmod part_msdos\n  insmod part_gpt\n");
                grub.append("  insmod chain\n");
                grub.append("  search --no-floppy --fs-uuid --set=root ")
                        .append(puuid).append("\n");
                grub.append("  drivemap -s (hd0) ${root}\n");
                grub.append("  chainloader +1\n}\n");
            } else if (!loader.isEmpty()) {
                String puuid = uuidOf(part);
                if (puuid.isEmpty()) {
                    continue;
                }
                grub.append("menuentry '").append(label).append("' {\n");
                grub.append("  insmod part_msdos\n  insmod part_gpt\n");
                grub.append(
                        "  insmod fat\n  insmod exfat\n  insmod ntfs\n  insmod ext2\n  insmod hfsplus\n");
                grub.append("  insmod chain\n");
                grub.append("  search --no-floppy --fs-uuid --set=root ")
                        .append(puuid).append("\n");
                grub.append("  chainloader ").append(loader).append("\n}\n");
            } else {
                logD("DBG os without loader skipped: " + e);
            }
        }
    }

    /** Minimum slot size MB from real payload files + buffer. */
    private static long minSystemMb(String sysSrc, String payDir) {
        long total = 0;
        total += fileMb(sysSrc);
        total += fileMb(payDir + "/kernel");
        total += fileMb(payDir + "/initrd.img");
        total += fileMb(payDir + "/ramdisk-recovery.img");
        total += 512;
        return Math.max(total, 1024);
    }

    private static long fileMb(String path) {
        try {
            long len = new java.io.File(path).length();
            return (len + 1048575) / 1048576;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String gb(long mb) {
        if (mb >= 1024) {
            return (mb / 1024) + "G";
        }
        return mb + "M";
    }

    /**
     * Install-alongside: never wipes the disk (no mklabel). Either takes
     * a free-space segment or shrinks apart (ext4 via e2fsck+resize2fs,
     * fat via parted, ntfs via ntfsresize) and creates the system
     * partition in the freed tail. Reuses an existing ESP or creates one.
     */
    private static String[] alongside(
            String disk, Map<String, String> job, String pbin, String penv,
            long minMb) {
        int wantMb = 0;
        try {
            wantMb = Integer.parseInt(job.getOrDefault("asize", "0"));
        } catch (Exception ignored) {
        }
        String apart = job.getOrDefault("apart", "").trim();
        String print = execParted(pbin, penv, disk);
        if (print.isEmpty()) {
            fail("part_parse");
            return null;
        }
        double diskMb =
                diskSizeMb(new java.io.File(disk).getName());
        String target = null;
        if (apart.isEmpty()) {
            double[] seg = largestFree(print, diskMb, minMb);
            try {
                long pin = Long.parseLong(job.getOrDefault("afree", "0"));
                if (pin > 0) {
                    double[] pinned = segmentWith(print, diskMb, pin);
                    if (pinned != null) {
                        seg = pinned;
                    }
                }
            } catch (Exception ignored) {
            }
            if (seg == null) {
                failArg("no_space", gb(wantMb > 0 ? wantMb : minMb));
                return null;
            }
            if (wantMb > 0 && wantMb > seg[1] - seg[0]) {
                log("DBG alongside clamped " + gb(wantMb) + " -> "
                        + gb((long) (seg[1] - seg[0])));
            }
            double start = seg[0];
            double end = wantMb > 0 ? start + wantMb : seg[1];
            if (end > seg[1]) {
                end = seg[1];
            }
            if (end - start < minMb) {
                failArg("too_small", gb(minMb));
                return null;
            }
            java.util.Set<String> before = new java.util.HashSet<>(partitions());
            if (execEnv(penv, new String[] {pbin + "parted", "-s", "-a",
                            "optimal", disk, "mkpart", "system", "ext4",
                            mib(start), mib(end)}) != 0) {
                fail("system_part");
                return null;
            }
            target = newNode(before);
            if (target == null) {
                fail("system_part");
                return null;
            }
        } else {
            double[] info = partInfo(print, apart);
            if (info == null) {
                fail("part_parse");
                return null;
            }
            double len = info[2] - info[1];
            // Capacity first: adjacent hole + what the partition itself
            // can give. Clamp, never fail — only a target that cannot
            // even hold the minimum is a real error. The clamped total
            // flows downstream (mkpart and everything after).
            double holeSize = 0;
            double holeEnd = info[2];
            double[] hole = largestFree(print, diskMb, minMb);
            if (hole != null && Math.abs(hole[0] - info[2]) < 2) {
                holeSize = hole[1] - hole[0];
                holeEnd = hole[1];
            }
            String fstype = out(new String[] {"blkid", "-s", "TYPE", "-o",
                    "value", apart}).trim().toLowerCase(java.util.Locale.US);
            double shrinkable;
            long usedMb = mountDfUsed(apart, fstype);
            if (usedMb >= 0) {
                shrinkable = len - usedMb - 512;
            } else {
                shrinkable = len - 1024;
            }
            if (shrinkable < 0) {
                shrinkable = 0;
            }
            double capacity = holeSize + shrinkable;
            double wantTotal = wantMb > 0 ? wantMb : len / 2;
            double total = Math.min(wantTotal, capacity);
            if (total < minMb) {
                failArg("too_small", gb(minMb));
                return null;
            }
            if (total < wantTotal) {
                log("DBG alongside clamped " + gb((long) wantTotal)
                        + " -> " + gb((long) total));
            }
            double need = total - holeSize;
            if (need < 0) {
                need = 0;
            }
            double newEnd = info[2] - need;
            if (need <= 0) {
                logD("alongside reusing free tail, no shrink");
            } else if (fstype.contains("ext4")) {
                if (exec(new String[] {"e2fsck", "-f", "-y", apart}) > 2) {
                    fail("shrink_fsck");
                    return null;
                }
                long freeMb = extFreeMb(apart);
                if (freeMb >= 0 && (len - need) > (len - freeMb + 512)) {
                    failArg("no_space", gb((long) need));
                    return null;
                }
                if (exec(new String[] {"resize2fs", apart,
                                ((long) (len - need)) + "M"}) != 0) {
                    fail("shrink_fs");
                    return null;
                }
                if (execEnv(penv, new String[] {pbin + "parted",
                                disk, "resizepart",
                                String.valueOf((int) info[0]),
                                mib(newEnd)}) != 0) {
                    fail("shrink_part");
                    return null;
                }
            } else if (fstype.contains("ntfs")) {
                // Self-healing: dry run first (writes nothing). If the
                // volume is dirty, repair it in place and re-verify —
                // the user never sees shrink_fsck. Only a volume that
                // survives repair still fails, and that one is truly
                // unfixable from here.
                if (execT(null, new String[] {"ntfsresize", "-n", "-s",
                                ((long) (len - need)) + "M",
                                apart}, true, TOOL_TIMEOUT_MIN) != 0) {
                    logD("dirty ntfs, repairing " + apart);
                    if (execT(null, new String[] {"fsck.ntfs", "-b", "-d",
                                    apart}, true, TOOL_TIMEOUT_MIN) != 0
                            || execT(null, new String[] {"ntfsresize",
                                            "-n", "-s",
                                            ((long) (len - need)) + "M",
                                            apart},
                                    true, TOOL_TIMEOUT_MIN) != 0) {
                        fail("shrink_fsck");
                        return null;
                    }
                }
                if (execT(null, new String[] {"ntfsresize", "-f", "-s",
                                ((long) (len - need)) + "M",
                                apart}, true, 30) != 0) {
                    fail("shrink_fs");
                    return null;
                }
                if (execEnv(penv, new String[] {pbin + "parted",
                                disk, "resizepart",
                                String.valueOf((int) info[0]),
                                mib(newEnd)}) != 0) {
                    fail("shrink_part");
                    return null;
                }
            } else if (fstype.contains("fat") || fstype.contains("vfat")) {
                if (execEnv(penv, new String[] {pbin + "parted",
                                disk, "resizepart",
                                String.valueOf((int) info[0]),
                                mib(newEnd)}) != 0) {
                    fail("shrink_part");
                    return null;
                }
            } else {
                log("DBG shrink unsupported fs " + fstype + " on " + apart);
                fail("shrink_unsupported");
                return null;
            }
            java.util.Set<String> before = new java.util.HashSet<>(partitions());
            if (execEnv(penv, new String[] {pbin + "parted", "-s", "-a",
                            "optimal", disk, "mkpart", "system", "ext4",
                            mib(newEnd), mib(holeEnd)}) != 0) {
                fail("system_part");
                return null;
            }
            target = newNode(before);
            if (target == null) {
                fail("system_part");
                return null;
            }
        }
        String esp = findEsp(disk);
        if (esp == null) {
                String repr = execParted(pbin, penv, disk);
                double[] seg = largestFree(repr, diskMb, 513);
            if (seg != null && seg[1] - seg[0] >= 513) {
                java.util.Set<String> before =
                        new java.util.HashSet<>(partitions());
                if (execEnv(penv, new String[] {pbin + "parted", "-s", "-a",
                                "optimal", disk, "mkpart", "ESP", "fat32",
                                mib(seg[0]), mib(seg[0] + 513)}) == 0) {
                    esp = newNode(before);
                    if (esp != null) {
                        String num = esp.replaceAll("^.*?(\\d+)$", "$1");
                        execEnv(penv, new String[] {pbin + "parted", "-s",
                                disk, "set", num, "esp", "on"});
                        execEnv(penv, new String[] {pbin + "mkfs.vfat", esp});
                    }
                }
            }
            if (esp == null) {
                log("WARN noesp");
            }
        }
        log("DBG alongside target=" + target + " esp=" + esp);
        return new String[] {target, esp};
    }

    private static String mib(double v) {
        return ((long) v) + "MiB";
    }

    /** Largest free segment [start, end] MiB with size >= minMb, or null. */
    /** Unpartitioned gaps [start, end] MiB from parted print + disk
     * size. Never trusts "Free Space" rows: some parted builds omit
     * them entirely (observed: 17G tail invisible). */
    private static java.util.List<double[]> freeGaps(
            String print, double diskMb) {
        java.util.List<double[]> parts = new java.util.ArrayList<>();
        for (String line : print.split("\n")) {
            line = line.trim();
            String[] f = line.split("\\s+");
            if (f.length < 3) {
                continue;
            }
            try {
                Integer.parseInt(f[0]);
            } catch (Exception e) {
                continue;
            }
            try {
                parts.add(
                        new double[] {parseMib(f[1]), parseMib(f[2])});
            } catch (Exception ignored) {
            }
        }
        parts.sort((a, b) -> Double.compare(a[0], b[0]));
        java.util.List<double[]> gaps = new java.util.ArrayList<>();
        double prev = 1;
        for (double[] p : parts) {
            if (p[0] > prev) {
                gaps.add(new double[] {prev, p[0]});
            }
            if (p[1] > prev) {
                prev = p[1];
            }
        }
        if (diskMb > prev) {
            gaps.add(new double[] {prev, diskMb});
        }
        return gaps;
    }

    private static double[] largestFree(
            String print, double diskMb, double minMb) {
        double[] best = null;
        for (double[] g : freeGaps(print, diskMb)) {
            double size = g[1] - g[0];
            if (size >= minMb && (best == null || size > (best[1] - best[0]))) {
                best = g;
            }
        }
        return best;
    }

    /** Free segment [start, end] containing pointMb, or null. */
    private static double[] segmentWith(
            String print, double diskMb, long pointMb) {
        for (double[] g : freeGaps(print, diskMb)) {
            if (pointMb >= g[0] && pointMb <= g[1]) {
                return g;
            }
        }
        return null;
    }

    /** [num, start, end] MiB for a partition node, or null. */
    private static double[] partInfo(String print, String apart) {
        String base = new java.io.File(apart).getName();
        String digits = base.replaceFirst("^.*?(\\d+)$", "$1");
        int want;
        try {
            want = Integer.parseInt(digits);
        } catch (Exception e) {
            return null;
        }
        for (String line : print.split("\n")) {
            line = line.trim();
            String[] f = line.split("\\s+");
            if (f.length < 4) {
                continue;
            }
            int num;
            try {
                num = Integer.parseInt(f[0]);
            } catch (Exception e) {
                continue;
            }
            if (num != want) {
                continue;
            }
            try {
                return new double[] {num, parseMib(f[1]), parseMib(f[2])};
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /** Free MiB on ext4 via dumpe2fs, or -1. */
    private static long extFreeMb(String part) {
        String o = out(new String[] {"dumpe2fs", "-h", part});
        long free = -1;
        long blksz = 4096;
        for (String line : o.split("\n")) {
            line = line.trim();
            if (line.startsWith("Free blocks:")) {
                try {
                    free = Long.parseLong(line.split(":")[1].trim());
                } catch (Exception ignored) {
                }
            } else if (line.startsWith("Block size:")) {
                try {
                    blksz = Long.parseLong(line.split(":")[1].trim());
                } catch (Exception ignored) {
                }
            }
        }
        if (free < 0) {
            return -1;
        }
        return free * blksz / 1048576;
    }

    /** Partition node that appeared vs before-set, or null. */
    private static String newNode(java.util.Set<String> before) {
        for (String d : partitions()) {
            if (!before.contains(d)) {
                return d;
            }
        }
        return null;
    }

    private static String findWindows() {
        java.io.File scan = new java.io.File(WORK + "scan");
        scan.mkdirs();
        java.io.File dev = new java.io.File("/dev/block");
        String[] names = dev.list();
        if (names == null) {
            return "";
        }
        for (String n : names) {
            String p = "/dev/block/" + n;
            String t =
                    out(new String[] {"blkid", "-s", "TYPE", "-o", "value",
                            p});
            if (!t.contains("ntfs") && !t.contains("vfat")) {
                continue;
            }
            if (exec(new String[] {"mount", "-t", t.trim(), "-o", "ro", p,
                            WORK + "scan"}) != 0) {
                continue;
            }
            boolean hit =
                    new java.io.File(WORK
                                    + "scan/EFI/Microsoft/Boot/bootmgfw.efi")
                            .exists();
            exec(new String[] {"umount", WORK + "scan"});
            if (hit) {
                return p;
            }
        }
        return "";
    }

    private static void cp(String a, String b) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(a);
        java.io.OutputStream out = new java.io.FileOutputStream(b);
        byte[] buf = new byte[1048576];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        out.close();
    }

    private static void chmod755(String p) throws Exception {
        // Octal 0755 = rwxr-xr-x.
        java.nio.file.Files.setPosixFilePermissions(java.nio.file.Paths.get(p),
                java.util.EnumSet.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission
                                .OWNER_EXECUTE,
                        java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                        java.nio.file.attribute.PosixFilePermission
                                .GROUP_EXECUTE,
                        java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                        java.nio.file.attribute.PosixFilePermission
                                .OTHERS_EXECUTE));
    }

    private static void write(String path, String s) throws Exception {
        FileWriter w = new FileWriter(path, false);
        w.write(s);
        w.close();
    }

    private static int exec(String[] argv) {
        return execEnv(null, argv, true);
    }

    private static int execQuiet(String[] argv) {
        return execEnv(null, argv, false);
    }

    private static int execEnv(String env, String[] argv) {
        return execT(env, argv, true, TOOL_TIMEOUT_MIN);
    }

    /**
     * Bounded tool run. Stdin is answered ("Yes") and closed: a tool
     * parked on a confirmation prompt proceeds (parted demands the
     * full word Yes — same as Calamares/KPMcore feeds it) or aborts
     * on EOF instead of deadlocking us (we read its stdout, it reads
     * our stdin — mutual pipe wait, observed as a 50-minute ntfsresize
     * wedge blocking the watcher loop). Hard timeout kills stragglers.
     * Returns 124 on timeout, 127 on spawn failure.
     */
    private static final long TOOL_TIMEOUT_MIN = 10;

    private static int execT(
            String env, String[] argv, boolean noisy, long timeoutMin) {
        try {
            // Calamares/KPMcore parity: parted reads confirmations from
            // /dev/tty when it has one and ignores piped stdin, aborting
            // headless runs (observed: resizepart "are you sure" -> EOF
            // -> ERR shrink_part). The flag forces it onto stdin, where
            // our "Yes" below lands. NOTE: resizepart must NOT use -s:
            // script mode marks the shrink exception UNHANDLED by design
            // (parted source), so no answer can ever satisfy it — proven
            // on-device: -s+Yes fails, no--s+Yes shrinks.
            if (argv.length > 0 && argv[0].endsWith("parted")) {
                String[] withTty = new String[argv.length + 1];
                withTty[0] = argv[0];
                withTty[1] = "---pretend-input-tty";
                System.arraycopy(argv, 1, withTty, 2, argv.length - 1);
                argv = withTty;
            }
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            if (env != null) {
                int i = env.indexOf('=');
                pb.environment().put(env.substring(0, i),
                        env.substring(i + 1));
            }
            Process p = pb.start();
            try {
                p.getOutputStream().write("Yes\n".getBytes("UTF-8"));
                p.getOutputStream().close();
            } catch (Exception ignored) {
            }
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (noisy) {
                    logRaw(line);
                }
            }
            if (!p.waitFor(
                    timeoutMin, java.util.concurrent.TimeUnit.MINUTES)) {
                p.destroyForcibly();
                logRaw("exec timeout " + timeoutMin + "m: " + argv[0]);
                return 124;
            }
            return p.exitValue();
        } catch (Exception e) {
            if (noisy) {
                logRaw("exec failed: " + e);
            }
            return 127;
        }
    }

    private static int execEnv(String env, String[] argv, boolean noisy) {
        return execT(env, argv, noisy, TOOL_TIMEOUT_MIN);
    }

    private static int execGlob(String pattern, String dest) {
        java.io.File dir =
                new java.io.File(pattern.substring(0, pattern.indexOf('*')));
        String[] names = dir.list();
        if (names == null) {
            return 1;
        }
        for (String n : names) {
            if (!n.endsWith(".mod")) {
                continue;
            }
            try {
                cp(dir.getAbsolutePath() + "/" + n, dest + "/" + n);
            } catch (Exception e) {
                return 1;
            }
        }
        return 0;
    }

    private static String out(String[] argv) {
        return outRaw(argv, '\n');
    }

    private static String outLines(String[] argv) {
        return outRaw(argv, '\n');
    }

    private static String outRaw(String[] argv, char sep) {
        try {
            final Process p = new ProcessBuilder(argv).start();
            try {
                p.getOutputStream().close();
            } catch (Exception ignored) {
            }
            // Drain stderr aside: a chatty tool must never block on a
            // full pipe, and its text must never pollute stdout parsing
            // (observed: merged find error became a bogus path "find:").
            Thread drain = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    while (p.getErrorStream().read(buf) >= 0) {
                    }
                } catch (Exception ignored) {
                }
            });
            drain.start();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append(sep);
            }
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return "";
            }
            drain.join(5000);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void log(String s) {
        logRaw(s);
    }

    private static synchronized void logRaw(String s) {
        if (sLog == null) {
            return;
        }
        try {
            sLog.write(s + "\n");
            sLog.flush();
        } catch (Exception ignored) {
        }
        // Mirror to logcat: `logcat -s NativeInstaller`, survives in
        // bugreports even if the UI photo cuts the screen log.
        try {
            String m = s.replace('\n', ' ');
            if (m.length() > 1000) {
                m = m.substring(0, 1000);
            }
            Runtime.getRuntime().exec(
                    new String[] {"log", "-t", "NativeInstaller", m});
        } catch (Exception ignored) {
        }
    }

    /** Verbose trace: debug file only, never on screen. */
    private static synchronized void logD(String s) {
        if (sDbg == null) {
            return;
        }
        try {
            sDbg.write(s + "\n");
            sDbg.flush();
        } catch (Exception ignored) {
        }
    }

    private static void status(String s) {
        try {
            FileWriter w = new FileWriter(BASE + "installer.status", false);
            w.write(s + "\n");
            w.close();
        } catch (Exception ignored) {
        }
    }

    private static void fail(String code) {
        log("ERR " + code);
        status("error");
        close();
        System.exit(1);
    }

    private static void failArg(String code, String arg) {
        log("ERR " + code + " " + arg);
        status("error");
        close();
        System.exit(1);
    }

    private static void close() {
        try {
            if (sLog != null) {
                sLog.close();
            }
            if (sDbg != null) {
                sDbg.close();
            }
        } catch (Exception ignored) {
        }
        // Release our own mounts (lazy: never hang the exit path).
        // The live system runs from its own loop mounts, not these.
        for (String m : new String[] {"mnt", "scan", "isomnt", "paymnt"}) {
            try {
                new ProcessBuilder(
                        new String[] {"umount", "-l", WORK + m}).start()
                        .waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        fixOwnership();
    }

    /**
     * Hand the job files back to the app. The engine runs as root and
     * recreates them root-owned, which then blocks the app from
     * resubmitting (it cannot truncate root-owned files). Best effort.
     * The job file itself is never touched (it may hold the next job).
     */
    private static void fixOwnership() {
        try {
            String id =
                    out(new String[] {"stat", "-c", "%u:%g", BASE}).trim()
                            .split(" ")[0];
            if (!id.matches("[0-9]+:[0-9]+")) {
                return;
            }
            execQuiet(new String[] {"chown", id, BASE + "installer.status",
                    BASE + "installer.log", BASE + "installer-debug.log"});
        } catch (Exception ignored) {
        }
    }
}
