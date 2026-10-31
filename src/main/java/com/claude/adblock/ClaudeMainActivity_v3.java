package com.claude.adblock;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class ClaudeMainActivity_v3 extends Activity {

    private static final int REQ_VPN = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView stats;
    private Button toggle;
    private Button update;
    private EditText allowlist;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("News AdBlock");

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(status);

        stats = new TextView(this);
        stats.setGravity(Gravity.CENTER_HORIZONTAL);
        stats.setPadding(0, dp(8), 0, dp(16));
        col.addView(stats);

        toggle = new Button(this);
        toggle.setOnClickListener(v -> onToggle());
        col.addView(toggle);

        Switch popups = new Switch(this);
        popups.setText("Block pop-up ad networks (login pop-ups always allowed)");
        popups.setPadding(0, dp(12), 0, dp(12));
        popups.setChecked(ClaudeBlocklist_v2.isPopupBlockingOn(this));
        popups.setOnCheckedChangeListener((b, on) -> {
            ClaudeBlocklist_v2.setPopupBlocking(this, on);
            refresh();
        });
        col.addView(popups);

        update = new Button(this);
        update.setText("Update blocklists");
        update.setOnClickListener(v -> onUpdate());
        col.addView(update);

        Button vpnSettings = new Button(this);
        vpnSettings.setText("Open VPN settings (turn on Always-on)");
        vpnSettings.setOnClickListener(v -> {
            try {
                startActivity(new Intent("android.net.vpn.SETTINGS"));
            } catch (Exception e) {
                Toast.makeText(this, "VPN settings not available", Toast.LENGTH_SHORT).show();
            }
        });
        col.addView(vpnSettings);

        TextView allowLabel = new TextView(this);
        allowLabel.setText("\nNever block these domains (one per line). Use this if a site breaks:");
        col.addView(allowLabel);

        allowlist = new EditText(this);
        allowlist.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        allowlist.setMinLines(3);
        allowlist.setGravity(Gravity.TOP | Gravity.START);
        allowlist.setHint("example.com");
        allowlist.setText(ClaudeBlocklist_v2.getAllowlistText(this));
        col.addView(allowlist);

        Button save = new Button(this);
        save.setText("Save allowlist");
        save.setOnClickListener(v -> {
            ClaudeBlocklist_v2.setAllowlistText(this, allowlist.getText().toString());
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        });
        col.addView(save);

        TextView help = new TextView(this);
        help.setPadding(0, dp(16), 0, 0);
        help.setText("How it works: while on, every app's DNS lookups (including the pages "
                + "Google News opens) are checked against a list of ad and tracker domains. "
                + "Ad domains are blocked; nothing else is routed through this app.\n\n"
                + "Pop-ups: sites that open pop-up or redirect ads are blocked. Sign-in "
                + "services (Google, Apple, Microsoft, Facebook, and the login systems "
                + "news sites use) are never blocked, so login pop-ups still open. "
                + "For extra protection, also set Chrome → Settings → Site settings → "
                + "Pop-ups and redirects to Blocked. Chrome still allows pop-ups you "
                + "open by tapping, such as a \"Sign in\" button.\n\n"
                + "For it to work:\n"
                + "• Settings → Network → Private DNS must be Off or Automatic "
                + "(not a provider hostname).\n"
                + "• In Chrome → Settings → Privacy → Use secure DNS, choose "
                + "\"With your current service provider\" or turn it off.\n"
                + "• Turn on Always-on VPN for News AdBlock so it survives restarts.");
        col.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);

        if (ClaudeBlocklist_v2.adCount() == 0) {
            new Thread(() -> ClaudeBlocklist_v2.load(getApplicationContext())).start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private void refresh() {
        boolean on = ClaudeAdBlockVpnService_v3.running;
        status.setText(on ? "Ad blocking ON" : "Ad blocking OFF");
        status.setTextColor(on ? Color.rgb(0x1E, 0x6B, 0x52) : Color.rgb(0x9A, 0x2A, 0x2A));
        toggle.setText(on ? "Turn off" : "Turn on");
        stats.setText(String.format(java.util.Locale.US,
                "%,d ad domains · %,d pop-up domains on blocklists\n"
                        + "Since start: %,d ads and %,d pop-ups blocked\n"
                        + "%,d login lookups let through · %,d lookups total",
                ClaudeBlocklist_v2.adCount(),
                ClaudeBlocklist_v2.popupCount(),
                ClaudeAdBlockVpnService_v3.blockedCount.get(),
                ClaudeAdBlockVpnService_v3.popupBlockedCount.get(),
                ClaudeAdBlockVpnService_v3.loginAllowedCount.get(),
                ClaudeAdBlockVpnService_v3.totalCount.get()));
    }

    private void onToggle() {
        if (ClaudeAdBlockVpnService_v3.running) {
            startService(new Intent(this, ClaudeAdBlockVpnService_v3.class)
                    .setAction(ClaudeAdBlockVpnService_v3.ACTION_STOP));
            handler.postDelayed(this::refresh, 300);
            return;
        }
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            startActivityForResult(prepare, REQ_VPN); // one-time system consent dialog
        } else {
            onActivityResult(REQ_VPN, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            startService(new Intent(this, ClaudeAdBlockVpnService_v3.class));
            handler.postDelayed(this::refresh, 500);
        } else if (requestCode == REQ_VPN) {
            Toast.makeText(this, "Permission is needed to block ads", Toast.LENGTH_LONG).show();
        }
    }

    private void onUpdate() {
        update.setEnabled(false);
        update.setText("Downloading…");
        new Thread(() -> {
            String msg;
            try {
                ClaudeBlocklist_v2.download(getApplicationContext());
                msg = String.format(java.util.Locale.US,
                        "Updated: %,d ad and %,d pop-up domains",
                        ClaudeBlocklist_v2.adCount(), ClaudeBlocklist_v2.popupCount());
            } catch (Exception e) {
                msg = "Update failed: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                update.setEnabled(true);
                update.setText("Update blocklists");
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                refresh();
            });
        }).start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
