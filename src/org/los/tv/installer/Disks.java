package org.los.tv.installer;

import java.util.ArrayList;
import java.util.List;

/** Block device enumeration via /sys/block (no lsblk needed). */
public final class Disks {
    private Disks() {}

    public static final class Disk {
        public final String dev;
        public final String model;
        public final long bytes;
        public final boolean removable;
        private String mLabel;

        Disk(String dev, String model, long bytes, boolean removable) {
            this.dev = dev;
            this.model = model;
            this.bytes = bytes;
            this.removable = removable;
        }

        public String label(android.content.Context ctx) {
            if (mLabel == null) {
                String gb =
                        String.format(
                                ctx.getString(R.string.disk_gb), bytes / 1e9);
                mLabel =
                        dev + "  " + model + "  " + gb
                                + (removable
                                        ? ctx.getString(R.string.disk_usb)
                                        : "");
            }
            return mLabel;
        }
    }

    public static List<Disk> list() {
        List<Disk> out = new ArrayList<>();
        Shell.Result r = Shell.sh("ls /sys/block/");
        if (!r.ok()) {
            return out;
        }
        for (String name : r.out.split("\\s+")) {
            name = name.trim();
            if (name.isEmpty()
                    || name.startsWith("loop")
                    || name.startsWith("ram")
                    || name.startsWith("dm-")) {
                continue;
            }
            String base = "/sys/block/" + name;
            String sizeS = cat(base + "/size").trim();
            long sectors;
            try {
                sectors = Long.parseLong(sizeS);
            } catch (NumberFormatException e) {
                continue;
            }
            String model = cat(base + "/device/model").trim();
            if (model.isEmpty()) {
                model = name;
            }
            String rem = cat(base + "/removable").trim();
            out.add(
                    new Disk(
                            "/dev/block/" + name,
                            model,
                            sectors * 512L,
                            "1".equals(rem)));
        }
        return out;
    }

    private static String cat(String path) {
        Shell.Result r = Shell.sh("cat " + path + " 2>/dev/null");
        return r.ok() ? r.out : "";
    }
}
