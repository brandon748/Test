package com.claude.adblock;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A local "VPN" that only captures DNS. Android sends every app's DNS lookups
 * (including Chrome Custom Tabs opened by Google News) to a fake DNS server
 * inside this tunnel. Ad/tracker domains get "no such domain"; everything else
 * is forwarded to a real DNS server. No other traffic passes through the app.
 */
public class ClaudeAdBlockVpnService_v2 extends VpnService {

    static final String ACTION_STOP = "com.claude.adblock.STOP";
    private static final String TAG = "ClaudeAdBlock";

    private static final String TUN_ADDRESS = "10.111.222.1";
    private static final String FAKE_DNS = "10.111.222.2";
    private static final String[] UPSTREAM_DNS = {"1.1.1.1", "8.8.8.8", "9.9.9.9"};

    static volatile boolean running = false;
    static final AtomicLong blockedCount = new AtomicLong();
    static final AtomicLong totalCount = new AtomicLong();

    private ParcelFileDescriptor tun;
    private FileDescriptor wakeRead;
    private FileDescriptor wakeWrite;
    private Thread loop;
    private ExecutorService pool;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) startVpn();
        return START_STICKY;
    }

    private synchronized void startVpn() {
        ClaudeBlocklist.load(this);
        Builder b = new Builder()
                .setSession("News AdBlock")
                .addAddress(TUN_ADDRESS, 32)
                .addDnsServer(FAKE_DNS)
                .addRoute(FAKE_DNS, 32) // only DNS to the fake server enters the tunnel
                .setBlocking(false);
        try {
            b.addDisallowedApplication(getPackageName()); // our own downloads go direct
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
        Intent open = new Intent(this, ClaudeMainActivity_v2.class);
        b.setConfigureIntent(PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        try {
            tun = b.establish();
            if (tun == null) { // permission revoked
                stopSelf();
                return;
            }
            FileDescriptor[] pipe = Os.pipe();
            wakeRead = pipe[0];
            wakeWrite = pipe[1];
        } catch (Exception e) {
            Log.e(TAG, "Could not start VPN", e);
            shutdown();
            stopSelf();
            return;
        }
        pool = Executors.newFixedThreadPool(16);
        running = true;
        final ParcelFileDescriptor myTun = tun;
        final FileDescriptor myWake = wakeRead;
        loop = new Thread(() -> runLoop(myTun, myWake), "ClaudeAdBlockLoop");
        loop.start();
    }

    private void runLoop(ParcelFileDescriptor tunPfd, FileDescriptor wake) {
        FileDescriptor fd = tunPfd.getFileDescriptor();
        FileOutputStream out = new FileOutputStream(fd);
        byte[] buf = new byte[32767];
        StructPollfd pTun = new StructPollfd();
        pTun.fd = fd;
        pTun.events = (short) OsConstants.POLLIN;
        StructPollfd pWake = new StructPollfd();
        pWake.fd = wake;
        pWake.events = (short) OsConstants.POLLIN;
        StructPollfd[] fds = {pTun, pWake};
        try {
            while (!Thread.currentThread().isInterrupted()) {
                pTun.revents = 0;
                pWake.revents = 0;
                Os.poll(fds, -1);
                if (pWake.revents != 0) break; // stop requested
                if ((pTun.revents & OsConstants.POLLIN) == 0) continue;
                int n;
                try {
                    n = Os.read(fd, buf, 0, buf.length);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EAGAIN) continue;
                    throw e;
                }
                if (n <= 0) continue;
                handlePacket(buf, n, out);
            }
        } catch (ErrnoException | java.io.InterruptedIOException | RuntimeException e) {
            Log.w(TAG, "VPN loop ended", e);
        }
    }

    private void handlePacket(byte[] buf, int len, FileOutputStream out) {
        ClaudeDnsPacket.Udp udp = ClaudeDnsPacket.parseIpv4Udp(buf, len);
        if (udp == null || udp.dstPort != 53) return;
        String name = ClaudeDnsPacket.queryName(udp.payload);
        if (name == null) return;
        totalCount.incrementAndGet();
        if (ClaudeBlocklist.isBlocked(name)) {
            blockedCount.incrementAndGet();
            byte[] reply = ClaudeDnsPacket.nxdomain(udp.payload);
            write(out, ClaudeDnsPacket.buildReply(udp, reply, reply.length));
            return;
        }
        ExecutorService p = pool;
        if (p == null) return;
        try {
            p.execute(() -> forward(udp, out));
        } catch (RuntimeException ignored) {
            // pool shut down while stopping
        }
    }

    private void forward(ClaudeDnsPacket.Udp udp, FileOutputStream out) {
        for (String server : UPSTREAM_DNS) {
            try (DatagramSocket s = new DatagramSocket()) {
                protect(s); // keep this socket outside the VPN
                s.setSoTimeout(4000);
                InetAddress addr = InetAddress.getByName(server);
                s.send(new DatagramPacket(udp.payload, udp.payload.length, addr, 53));
                byte[] rb = new byte[4096];
                DatagramPacket resp = new DatagramPacket(rb, rb.length);
                s.receive(resp);
                write(out, ClaudeDnsPacket.buildReply(udp, rb, resp.getLength()));
                return;
            } catch (IOException e) {
                // try the next upstream server
            }
        }
    }

    private static void write(FileOutputStream out, byte[] pkt) {
        synchronized (out) {
            try {
                out.write(pkt);
            } catch (IOException ignored) {
                // tunnel closed or momentarily full; the app will retry the lookup
            }
        }
    }

    private synchronized void shutdown() {
        running = false;
        if (wakeWrite != null) {
            try {
                Os.write(wakeWrite, new byte[]{1}, 0, 1);
            } catch (Exception ignored) {
            }
        }
        if (loop != null) {
            loop.interrupt();
            try {
                loop.join(2000);
            } catch (InterruptedException ignored) {
            }
            loop = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        closeQuietly(wakeRead);
        closeQuietly(wakeWrite);
        wakeRead = wakeWrite = null;
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
            }
            tun = null;
        }
    }

    private static void closeQuietly(FileDescriptor fd) {
        if (fd == null) return;
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
        }
    }

    @Override
    public void onRevoke() {
        shutdown();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }
}
