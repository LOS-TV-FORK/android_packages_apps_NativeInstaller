package org.los.tv.installer;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Command runner. The live flow needs no privileges at all: the job file
 * lives in world-writable /data/local/tmp and the engine is an init
 * service. su is kept only for the root self-check.
 */
public final class Shell {
    private Shell() {}

    public static final class Result {
        public final int code;
        public final String out;
        public final String err;

        Result(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }

        public boolean ok() {
            return code == 0;
        }
    }

    public interface Listener {
        void onOutput(String line);
    }

    public static Result su(String cmd) {
        return exec(new String[] {"su"}, cmd, null);
    }

    public static Result su(String cmd, Listener listener) {
        return exec(new String[] {"su"}, cmd, listener);
    }

    /** Run a command as the app user (no su). */
    public static Result sh(String cmd) {
        return exec(new String[] {"/system/bin/sh", "-c", cmd}, null, null);
    }

    private static Result exec(String[] argv, String stdinCmd, Listener listener) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int code = -1;
        try {
            Process p = Runtime.getRuntime().exec(argv);
            if (stdinCmd != null) {
                DataOutputStream stdin = new DataOutputStream(p.getOutputStream());
                stdin.writeBytes(stdinCmd + "\nexit $?\n");
                stdin.flush();
            }
            BufferedReader stdout =
                    new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader stderr =
                    new BufferedReader(new InputStreamReader(p.getErrorStream()));
            String line;
            while ((line = stdout.readLine()) != null) {
                out.append(line).append('\n');
                if (listener != null) {
                    listener.onOutput(line);
                }
            }
            while ((line = stderr.readLine()) != null) {
                err.append(line).append('\n');
            }
            code = p.waitFor();
        } catch (Exception e) {
            err.append(e.toString());
        }
        return new Result(code, out.toString(), err.toString());
    }

    public static boolean writeFile(String path, String content) {
        try (FileOutputStream f = new FileOutputStream(path)) {
            f.write(content.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String readFile(String path) {
        try (FileInputStream f = new FileInputStream(path)) {
            byte[] buf = new byte[65536];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = f.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean haveRoot() {
        return su("id -u").out.trim().equals("0");
    }
}
