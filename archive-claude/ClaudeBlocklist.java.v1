package com.claude.adblock;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Holds the set of blocked domains and the user's allowlist. Thread-safe for readers. */
final class ClaudeBlocklist {

    static final String LIST_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    private static final String ASSET_NAME = "hosts-claude.txt";
    private static final String DOWNLOADED_NAME = "hosts-claude-downloaded.txt";
    private static final String PREFS = "claude_adblock";
    private static final String KEY_ALLOW = "allowlist";

    private static volatile Set<String> blocked = Collections.emptySet();
    private static volatile Set<String> allowed = Collections.emptySet();

    private ClaudeBlocklist() {}

    static int size() {
        return blocked.size();
    }

    /** Loads the downloaded list if present, else the list bundled in the APK. */
    static synchronized void load(Context ctx) {
        Set<String> set = new HashSet<>(200_000);
        File downloaded = new File(ctx.getFilesDir(), DOWNLOADED_NAME);
        boolean ok = false;
        if (downloaded.length() > 0) {
            try (InputStream in = new FileInputStream(downloaded)) {
                parse(in, set);
                ok = !set.isEmpty();
            } catch (IOException ignored) {
                set.clear();
            }
        }
        if (!ok) {
            try (InputStream in = ctx.getAssets().open(ASSET_NAME)) {
                parse(in, set);
            } catch (IOException ignored) {
                // No bundled list; the user can tap "Update blocklist".
            }
        }
        blocked = set;
        allowed = parseAllowlist(getAllowlistText(ctx));
    }

    /** Downloads the latest list, replacing the stored copy. Run off the main thread. */
    static void download(Context ctx) throws IOException {
        File tmp = new File(ctx.getFilesDir(), DOWNLOADED_NAME + ".tmp");
        HttpURLConnection conn = (HttpURLConnection) new URL(LIST_URL).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        try {
            if (conn.getResponseCode() != 200) {
                throw new IOException("HTTP " + conn.getResponseCode());
            }
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
        Set<String> check = new HashSet<>();
        try (InputStream in = new FileInputStream(tmp)) {
            parse(in, check);
        }
        if (check.size() < 1000) {
            tmp.delete();
            throw new IOException("Downloaded list looks wrong (" + check.size() + " entries)");
        }
        if (!tmp.renameTo(new File(ctx.getFilesDir(), DOWNLOADED_NAME))) {
            throw new IOException("Could not save list");
        }
        load(ctx);
    }

    static String getAllowlistText(Context ctx) {
        return prefs(ctx).getString(KEY_ALLOW, "");
    }

    static void setAllowlistText(Context ctx, String text) {
        prefs(ctx).edit().putString(KEY_ALLOW, text).apply();
        allowed = parseAllowlist(text);
    }

    /** True if the name or any parent domain is blocked and not allowlisted. */
    static boolean isBlocked(String name) {
        if (name == null || name.isEmpty()) return false;
        Set<String> a = allowed;
        Set<String> b = blocked;
        if (matches(a, name)) return false;
        return matches(b, name);
    }

    private static boolean matches(Set<String> set, String name) {
        if (set.isEmpty()) return false;
        String d = name;
        while (true) {
            if (set.contains(d)) return true;
            int dot = d.indexOf('.');
            if (dot < 0 || dot == d.length() - 1) return false;
            d = d.substring(dot + 1);
            if (d.indexOf('.') < 0) return false; // never match a bare TLD
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Set<String> parseAllowlist(String text) {
        Set<String> set = new HashSet<>();
        for (String line : text.split("[\\s,]+")) {
            String d = line.trim().toLowerCase(Locale.ROOT);
            if (d.startsWith("*.")) d = d.substring(2);
            if (!d.isEmpty()) set.add(d);
        }
        return set;
    }

    /** Accepts hosts-file format ("0.0.0.0 ads.example.com") or one domain per line. */
    private static void parse(InputStream in, Set<String> out) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            String domain;
            if (parts.length >= 2) {
                String ip = parts[0];
                if (!ip.equals("0.0.0.0") && !ip.equals("127.0.0.1") && !ip.equals("::")
                        && !ip.equals("::1")) continue;
                domain = parts[1];
            } else {
                domain = parts[0];
            }
            domain = domain.toLowerCase(Locale.ROOT);
            if (domain.equals("localhost") || domain.equals("0.0.0.0")
                    || domain.endsWith(".localdomain") || domain.equals("local")
                    || domain.equals("broadcasthost") || domain.indexOf('.') < 0) continue;
            out.add(domain);
        }
    }
}
