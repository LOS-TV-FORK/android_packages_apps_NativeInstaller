package org.los.tv.installer;

import java.util.ArrayList;
import java.util.List;

/**
 * Install options tree, ported 1:1 from aaropa options.yaml
 * (groups -&gt; subgroups -&gt; options). Each option contributes a
 * kernel-cmdline/init-env token (e.g. "HWACCEL=0") to android.cfg CMDLINE.
 * distinct = single choice inside the (sub)group, editable = value suffix.
 */
public final class Options {
    private Options() {}

    public static final class Opt {
        public final String name;
        public final String token;
        public final boolean editable;
        public final String def;

        Opt(String name, String token) {
            this(name, token, false, "");
        }

        Opt(String name, String token, boolean editable, String def) {
            this.name = name;
            this.token = token;
            this.editable = editable;
            this.def = def;
        }
    }

    public static final class Group {
        public final String name;
        public final boolean distinct;
        public final List<Group> subs = new ArrayList<>();
        public final List<Opt> opts = new ArrayList<>();

        Group(String name) {
            this(name, false);
        }

        Group(String name, boolean distinct) {
            this.name = name;
            this.distinct = distinct;
        }

        Group sub(String name) {
            return sub(name, false);
        }

        Group sub(String name, boolean distinct) {
            Group g = new Group(name, distinct);
            subs.add(g);
            return g;
        }

        Group opt(String name, String token) {
            opts.add(new Opt(name, token));
            return this;
        }

        Group opt(String name, String token, String def) {
            opts.add(new Opt(name, token, true, def));
            return this;
        }
    }

    public static List<Group> all() {
        List<Group> g = new ArrayList<>();

        Group logging = new Group("Logging & Debugging");
        logging.sub("No logging").opt("Quiet mode", "quiet");
        logging.sub("Virtual console").opt("Enable virtual console",
                "androidboot.enable_console=1");
        Group dbg = logging.sub("Debug shell", true);
        dbg.opt("Debug mode 1", "DEBUG=1");
        dbg.opt("Debug mode 2", "DEBUG=2");
        logging.sub("VSOCK", true).opt("Enable VSOCK Debug", "DEBUG_VSOCK=1");
        logging.sub("ADB", true).opt("Enable Insecure ADB",
                "androidboot.insecure_adb=1");
        g.add(logging);

        Group media = new Group("Media codecs");
        media.sub("Codec2 Codecs").opt("Disable Codec2", "CODEC2_LEVEL=0");
        media.sub("OMX Codecs").opt("Disable YUV420 planar for OMX",
                "OMX_NO_YUV420=1");
        Group ff = media.sub("FFMPEG Codecs");
        ff.opt("Set FFMPEG Codec2 as default", "FFMPEG_CODEC2_PREFER=1");
        ff.opt("Enable DRM Prime Handle on FFMPEG Codec2",
                "FFMPEG_CODEC2_DRM=1");
        ff.opt("Enable FFMPEG OMX", "FFMPEG_OMX_CODEC=1");
        Group ffm = media.sub("Miscellaneous for FFMPEG");
        ffm.opt("Enable logging for FFMPEG codecs", "FFMPEG_CODEC_LOG=1");
        ffm.opt("Disable hardware acceleration on FFMPEG codecs",
                "FFMPEG_HWACCEL_DISABLE=1");
        g.add(media);

        Group disk = new Group("Disks & partitions");
        Group ntfs = disk.sub("NTFS options", true);
        ntfs.opt("Boot with NTFS3 on a NTFS partition", "BOOT_USE_NTFS3=1");
        ntfs.opt("Set NTFS3 as default driver on vold to mount NTFS",
                "VOLD_USE_NTFS3=1");
        ntfs.opt("Boot & Set NTFS3 as default on vold", "USE_NTFS3=1");
        Group dmisc = disk.sub("Miscellaneous");
        dmisc.opt("Disable SDCardFS/ESDFS bind mounting", "SDCARDFS_DISABLE=1");
        dmisc.opt("Mount all internal partitions", "INTERNAL_MOUNT=1");
        g.add(disk);

        g.add(new Group("Networking").opt("Enable virtual wifi", "VIRT_WIFI=1"));

        Group sens = new Group("Sensors");
        sens.opt("Force kbd sensors", "SENSORS_FORCE_KBDSENSOR=1");
        sens.opt("Set surfaceflinger hardware rotation",
                "SET_SF_ROTATION=true");
        sens.opt("Forced orientation", "SET_OVERRIDE_FORCED_ORIENT=true");
        sens.opt("Delay sensors load by seconds", "SENSORS_DELAY_INIT=", "5");
        g.add(sens);

        Group batt = new Group("Battery");
        batt.opt("Set fake battery level", "SET_FAKE_BATTERY_LEVEL=", "50 ");
        batt.opt("Set fake charging status", "SET_FAKE_CHARGING_STATUS=1");
        batt.opt("Set fake battery info by AOSP", "androidboot.fake_battery=1");
        g.add(batt);

        Group power = new Group("Power");
        power.opt("Set default sleep state", "SLEEP_STATE=", "mem ");
        power.opt("Turn off non-boot CPUs when suspend",
                "POWER_NONBOOT_CPU_OFF=1");
        power.opt("Force max cstate level to 2 for Intel CPUs",
                "intel_idle.max_cstate=2");
        g.add(power);

        Group audio = new Group("Audio", true);
        audio.opt("Set default audio HAL to x86", "AUDIO_PRIMARY=x86");
        audio.opt("Set default audio HAL to Project Celadon",
                "AUDIO_PRIMARY=x86_celadon");
        g.add(audio);

        Group bt = new Group("Bluetooth");
        bt.opt("Use btlinux Bluetooth HAL instead", "BTLINUX_HAL=1");
        bt.opt("Disable Bluetooth BLE completely", "BT_BLE_DISABLE=1");
        bt.opt("Disable BLE vendor capabilities", "BT_BLE_NO_VENDORCAPS=1");
        bt.opt("Set default Bluetooth UART port", "BTUART_PORT=", "Port...");
        g.add(bt);

        Group gfx = new Group("Graphics");
        Group hw = gfx.sub("Hardware acceleration", true);
        hw.opt("Disable hardware acceleration", "HWACCEL=0");
        hw.opt("Disable video driver loading (also disable HWACCEL)",
                "nomodeset");
        Group egl = gfx.sub("EGL", true);
        Group mesa = egl.sub("Mesa EGL", true);
        mesa.opt("Set EGL to Mesa", "EGL=mesa");
        mesa.opt("Force Mesa EGL to use llvmpipe",
                "EGL=mesa MESA_LLVMPIPE=1");
        mesa.opt("Force Mesa EGL to use Zink", "EGL=mesa MESA_ZINK=1");
        egl.sub("ANGLE EGL", true).opt("Set EGL to ANGLE", "EGL=angle");
        Group gles = gfx.sub("OpenGLES", true);
        gles.opt("Force OpenGLES version to 2.0", "FORCE_GLES=2.0");
        gles.opt("Force OpenGLES version to 3.0", "FORCE_GLES=3.0");
        gles.opt("Force OpenGLES version to 3.1", "FORCE_GLES=3.1");
        gles.opt("Force OpenGLES version to 3.2", "FORCE_GLES=3.2");
        gfx.sub("RenderEngine", true).opt(
                "Force default RenderEngine backend to skiagl",
                "FORCE_RENDERENGINE=skiagl");
        Group hwcHidl = gfx.sub("HWC HIDL", true);
        hwcHidl.opt("Set default HWC HIDL interface to v2.1",
                "HWC_HIDL=default-2.1");
        hwcHidl.opt("Set default HWC HIDL interface to v2.4",
                "HWC_HIDL=default-2.4");
        hwcHidl.opt("Set default HWC HIDL interface to drmfb",
                "HWC_HIDL=drmfb");
        Group hwc = gfx.sub("HWC", true);
        hwc.opt("Set default HWC to drm", "HWC=drm");
        hwc.opt("Set default HWC to drm_celadon", "HWC=drm_celadon");
        hwc.opt("Set default HWC to drm_minigbm", "HWC=drm_minigbm");
        hwc.opt("Set default HWC to drm_minigbm_celadon",
                "HWC=drm_minigbm_celadon");
        Group gr = gfx.sub("Gralloc", true);
        gr.opt("Set default Gralloc to gbm", "GRALLOC=gbm");
        gr.opt("Set default Gralloc to gbm_hack", "GRALLOC=gbm_hack");
        gr.opt("Set default Gralloc to minigbm", "GRALLOC=minigbm");
        gr.opt("Set default Gralloc to minigbm_gbm_mesa",
                "GRALLOC=minigbm_gbm_mesa");
        gr.opt("Set default Gralloc to minigbm_arcvm", "GRALLOC=minigbm_arcvm");
        Group vk = gfx.sub("Vulkan", true);
        vk.opt("Set default Vulkan HAL to lvp", "VULKAN=lvp");
        vk.opt("Set default Vulkan HAL to pastel", "VULKAN=pastel");
        Group cel = gfx.sub("Celadon-specific");
        cel.opt("Enable multi-plane in HWC", "MULTI_PLANE=1");
        cel.opt("Number of multi-plane in HWC", "MULTI_PLANE_NUM=", "2");
        cel.opt("Support all display modes", "HWC_PREFER_MODE=0");
        cel.opt("Specify Connector ID", "CONNECTOR_ID=", "1");
        cel.opt("Specify Mode ID", "MODE_ID=", "1");
        cel.opt("Enable multi refresh rate for the system",
                "MULTI_REFRESH_RATE=1");
        Group misc = gfx.sub("Miscellaneous");
        misc.opt("Force resolution on vmwgfx driver",
                "vmwgfx.force_resolution=", "1280x720");
        misc.opt("Force resolution on virtio-gpu driver",
                "virtio-gpu.force_resolution=", "1280x720");
        misc.opt("Disable nouveau driver for Nvidia GPUs",
                "nouveau.modeset=0");
        g.add(gfx);

        Group rec = new Group("Recovery");
        rec.opt("ADB port", "DEBUG_NET_PORT=", "5555 ");
        rec.opt("Force default color format", "androidboot.pixel_format=",
                "RGBX_8888");
        g.add(rec);

        g.add(new Group("Camera").opt("Enable emulated camera",
                "EMULATED_CAMERA=1"));

        Group m = new Group("Miscellaneous");
        m.opt("Set default DPI", "DPI=", "136 ");
        m.opt("Force disable Setup Wizard", "SETUPWIZARD=0");
        m.opt("Enable PC Mode", "PC_MODE=1");
        m.opt("Enable HPE Mode", "HPE=1");
        m.opt("Disable x86 syscall hardening", "syscall_hardening=off");
        g.add(m);

        return g;
    }
}
