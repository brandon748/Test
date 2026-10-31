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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides what to do with each DNS lookup. Holds the ad list, the pop-up ad
 * network list, the built-in login allowlist and the user's own allowlist.
 */
final class ClaudeBlocklist_v2 {

    enum Verdict { ALLOW, LOGIN_ALLOWED, BLOCK_AD, BLOCK_POPUP }

    static final String ADS_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    static final String POPUPS_URL =
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/popupads-onlydomains.txt";

    private static final String ADS_ASSET = "hosts-claude.txt";
    private static final String POPUPS_ASSET = "popups-claude.txt";
    private static final String ADS_FILE = "hosts-claude-downloaded.txt";
    private static final String POPUPS_FILE = "popups-claude-downloaded.txt";

    private static final String PREFS = "claude_adblock";
    private static final String KEY_ALLOW = "allowlist";
    private static final String KEY_POPUPS = "block_popups";

    /**
     * Sign-in services that open login pop-ups. These and all their subdomains
     * are never blocked, even if a blocklist contains them.
     */
    private static final Set<String> LOGIN_DOMAINS = new HashSet<>(Arrays.asList(
            // Google
            "accounts.google.com", "accounts.youtube.com", "myaccount.google.com",
            "oauth2.googleapis.com", "accounts.gstatic.com", "signin.google",
            // Apple
            "appleid.apple.com", "idmsa.apple.com", "appleid.cdn-apple.com",
            // Microsoft
            "login.microsoftonline.com", "login.live.com", "login.microsoft.com",
            "login.windows.net", "account.live.com", "aadcdn.msauth.net",
            "aadcdn.msftauth.net", "logincdn.msauth.net",
            // Social sign-in
            "www.facebook.com", "m.facebook.com", "api.twitter.com", "api.x.com",
            "twitter.com", "x.com", "www.linkedin.com", "github.com",
            "login.yahoo.com", "api.login.yahoo.com", "accounts.spotify.com",
            "www.amazon.com", "na.account.amazon.com", "www.paypal.com",
            // Identity providers used by news sites for subscriber logins
            "okta.com", "oktacdn.com", "auth0.com", "onelogin.com", "id.atlassian.com",
            "amazoncognito.com", "id.tinypass.com", "buy.tinypass.com", "piano.io",
            "gigya.com", "cdns.gigya.com", "accounts.gigya.com", "socialize.gigya.com",
            "janraincapture.com", "loginradius.com", "hub.loginradius.com",
            "zephr.com", "cognito-idp.us-east-1.amazonaws.com", "duosecurity.com",
            "login.salesforce.com", "steamcommunity.com", "disqus.com"));

    /** A subdomain starting with one of these words is treated as a login page. */
    private static final Set<String> LOGIN_LABELS = new HashSet<>(Arrays.asList(
            "login", "logon", "signin", "sign-in", "auth", "oauth", "oauth2", "sso",
            "accounts", "account", "myaccount", "identity", "idp"));

    private static volatile Set<String> ads = Collections.emptySet();
    private static volatile Set<String> popups = Collections.emptySet();
    private static volatile Set<String> allowed = Collections.emptySet();
    private static volatile boolean blockPopups = true;

    private ClaudeBlocklist_v2() {}

    static int adCount() {
        return ads.size();
    }

    static int popupCount() {
        return popups.size();
    }

    static synchronized void load(Context ctx) {
        ads = loadList(ctx, ADS_FILE, ADS_ASSET);
        popups = loadList(ctx, POPUPS_FILE, POPUPS_ASSET);
        allowed = parseAllowlist(getAllowlistText(ctx));
        blockPopups = prefs(ctx).getBoolean(KEY_POPUPS, true);
    }

    /** Downloads the latest copy of both lists. Run off the main thread. */
    static void download(Context ctx) throws IOException {
        downloadOne(ctx, ADS_URL, ADS_FILE, 1000);
        downloadOne(ctx, POPUPS_URL, POPUPS_FILE, 100);
        load(ctx);
    }

    static boolean isPopupBlockingOn(Context ctx) {
        return prefs(ctx).getBoolean(KEY_POPUPS, true);
    }

    static void setPopupBlocking(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_POPUPS, on).apply();
        blockPopups = on;
    }

    static String getAllowlistText(Context ctx) {
        return prefs(ctx).getString(KEY_ALLOW, "");
    }

    static void setAllowlistText(Context ctx, String text) {
        prefs(ctx).edit().putString(KEY_ALLOW, text).apply();
        allowed = parseAllowlist(text);
    }

    static Verdict check(String name) {
        if (name == null || name.isEmpty()) return Verdict.ALLOW;
        if (matches(allowed, name)) return Verdict.ALLOW;
        boolean ad = matches(ads, name);
        boolean popup = blockPopups && matches(popups, name);
        if (!ad && !popup) return Verdict.ALLOW;
        if (isLogin(name)) return Verdict.LOGIN_ALLOWED;
        return ad ? Verdict.BLOCK_AD : Verdict.BLOCK_POPUP;
    }

    static boolean isLogin(String name) {
        if (matches(LOGIN_DOMAINS, name)) return true;
        int dot = name.indexOf('.');
        // Only subdomains count ("login.example.com"), never a bare "login.com".
        if (dot <= 0 || name.indexOf('.', dot + 1) < 0) return false;
        return LOGIN_LABELS.contains(name.substring(0, dot));
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

    private static Set<String> loadList(Context ctx, String fileName, String assetName) {
        Set<String> set = new HashSet<>(100_000);
        File f = new File(ctx.getFilesDir(), fileName);
        if (f.length() > 0) {
            try (InputStream in = new FileInputStream(f)) {
                parse(in, set);
                if (!set.isEmpty()) return set;
            } catch (IOException ignored) {
                set.clear();
            }
        }
        try (InputStream in = ctx.getAssets().open(assetName)) {
            parse(in, set);
        } catch (IOException ignored) {
            // Not bundled; "Update blocklists" will fetch it.
        }
        return set;
    }

    private static void downloadOne(Context ctx, String url, String fileName, int minEntries)
            throws IOException {
        File tmp = new File(ctx.getFilesDir(), fileName + ".tmp");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
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
        if (check.size() < minEntries) {
            tmp.delete();
            throw new IOException("Downloaded list looks wrong (" + check.size() + " entries)");
        }
        if (!tmp.renameTo(new File(ctx.getFilesDir(), fileName))) {
            throw new IOException("Could not save list");
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
            if (domain.startsWith("*.")) domain = domain.substring(2);
            if (domain.equals("localhost") || domain.equals("0.0.0.0")
                    || domain.endsWith(".localdomain") || domain.equals("local")
                    || domain.equals("broadcasthost") || domain.indexOf('.') < 0) continue;
            out.add(domain);
        }
    }
}
