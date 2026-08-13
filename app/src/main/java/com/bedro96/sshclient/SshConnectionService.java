package com.bedro96.sshclient;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Identity;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the SSH session, the remote-shell reader thread and the emulated
 * terminal buffer, independently of {@link MainActivity}'s lifecycle.
 *
 * Without this, the connection lived directly on the Activity: as soon as the
 * app lost foreground focus (switching apps, screen lock, recents) the process
 * became eligible for background execution limits and low-memory eviction,
 * which silently dropped the socket and forced the user to reconnect every
 * time they switched back. Running as a foreground service keeps the process
 * — and the live connection and scrollback it holds — alive across those
 * transitions; the Activity only attaches as a listener to receive updates.
 */
public final class SshConnectionService extends Service {

    interface Listener {
        void onStatusChanged(String status);
        void onConnected(String host, int port, String user);
        void onConnectFailed(String detail);
        void onDisconnected();
        void onRemoteShellClosed();
        void onScreenUpdated();
        void onSendFailed(String detail);
    }

    private static final String CHANNEL_ID = "ssh_connection";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder binder = new LocalBinder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final TerminalAnsiProcessor ansiProcessor = new TerminalAnsiProcessor();
    private final TerminalScreen terminalScreen = new TerminalScreen();

    private volatile Session session;
    private volatile ChannelShell channel;
    private volatile OutputStream remoteIn;
    private volatile Thread readerThread;
    private volatile Listener listener;
    private volatile String statusText = "";
    private volatile int lastCols = TerminalScreen.DEFAULT_COLS;
    private volatile int lastRows = TerminalScreen.DEFAULT_ROWS;

    final class LocalBinder extends Binder {
        SshConnectionService getService() { return SshConnectionService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        cleanupSilently();
        io.shutdownNow();
        super.onDestroy();
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isConnected() {
        Session s = session;
        return s != null && s.isConnected();
    }

    String getStatusText() {
        return statusText;
    }

    TerminalScreen.Snapshot snapshot(int maxChars) {
        return terminalScreen.snapshot(maxChars);
    }

    /** Appends a locally-generated message (e.g. a connection error) to the terminal buffer. */
    void appendLocalMessage(String text) {
        terminalScreen.append(text);
        notifyScreenUpdated();
    }

    void resizeTerminal(int cols, int rows) {
        lastCols = cols;
        lastRows = rows;
        terminalScreen.resize(rows, cols);
        notifyScreenUpdated();
        final ChannelShell ch = channel;
        if (ch != null && ch.isConnected()) {
            io.submit(new Runnable() {
                @Override public void run() { ch.setPtySize(cols, rows, 0, 0); }
            });
        }
    }

    void connect(final String host, final int port, final String user, final String password,
            final String idFile, final int cols, final int rows) {
        lastCols = cols;
        lastRows = rows;
        setStatus("Connecting to " + user + "@" + host + ":" + port);
        io.submit(new Runnable() {
            @Override public void run() {
                try {
                    // Ed25519 identity keys (id_ed25519) only work on Android via
                    // the Bouncy Castle EdDSA classes; the JDK15+ implementation
                    // jsch prefers is stripped out by Android's dex packaging.
                    SshKeyAuth.configureEdDSAForAndroid();
                    JSch jsch = new JSch();
                    if (!TextUtils.isEmpty(idFile)) {
                        // The entered password doubles as the key passphrase so that
                        // passphrase-protected private keys can be decrypted. JSch
                        // ignores the passphrase for keys that are not encrypted.
                        Identity identity = JschEd25519Support.addIdentity(jsch, idFile, password);
                        if (JschEd25519Support.isEncrypted(identity)) {
                            if (TextUtils.isEmpty(password)) {
                                throw new JSchException("Identity key is passphrase-protected. Enter the passphrase in the password field.");
                            }
                            throw new JSchException("Unable to decrypt the identity key. Check the passphrase in the password field.");
                        }
                    }
                    Session s = jsch.getSession(user, host, port);
                    if (!TextUtils.isEmpty(password)) { s.setPassword(password); }
                    Properties config = new Properties();
                    config.put("StrictHostKeyChecking", "no");
                    // Try the imported key first, then fall back to password-based
                    // methods so a publickey failure does not abort the login.
                    config.put("PreferredAuthentications",
                            "publickey,keyboard-interactive,password");
                    s.setConfig(config);
                    s.setServerAliveInterval(30_000);
                    s.connect(15_000);

                    ChannelShell ch = (ChannelShell) s.openChannel("shell");
                    ch.setPtyType("xterm-256color", cols, rows, 0, 0);
                    final InputStream in = ch.getInputStream();
                    final OutputStream out = ch.getOutputStream();
                    ch.connect(10_000);

                    session = s;
                    channel = ch;
                    remoteIn = out;
                    terminalScreen.reset();
                    ansiProcessor.reset();
                    startReader(in);
                    startForegroundNotification(user, host, port);

                    ui.post(new Runnable() {
                        @Override public void run() {
                            setStatus("Connected " + user + "@" + host + ":" + port);
                            notifyScreenUpdated();
                            Listener l = listener;
                            if (l != null) { l.onConnected(host, port, user); }
                        }
                    });
                } catch (final Exception e) {
                    final String detail = describeConnectError(e, idFile);
                    cleanupSilently();
                    ui.post(new Runnable() {
                        @Override public void run() {
                            setStatus("Error: " + detail);
                            Listener l = listener;
                            if (l != null) { l.onConnectFailed(detail); }
                        }
                    });
                }
            }
        });
    }

    void disconnect() {
        setStatus("Disconnected");
        cleanupSilently();
        stopForeground(true);
        Listener l = listener;
        if (l != null) { l.onDisconnected(); }
    }

    void sendRaw(final byte[] bytes) {
        final OutputStream remote = remoteIn;
        if (remote == null) { return; }
        io.submit(new Runnable() {
            @Override public void run() {
                try {
                    remote.write(bytes);
                    remote.flush();
                } catch (final IOException e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Listener l = listener;
                            if (l != null) { l.onSendFailed(e.getMessage()); }
                        }
                    });
                }
            }
        });
    }

    private void setStatus(String s) {
        statusText = s;
        ui.post(new Runnable() {
            @Override public void run() {
                Listener l = listener;
                if (l != null) { l.onStatusChanged(statusText); }
            }
        });
    }

    private void notifyScreenUpdated() {
        Listener l = listener;
        if (l != null) { l.onScreenUpdated(); }
    }

    private void startReader(final InputStream in) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[4096];
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        int n = in.read(buf);
                        if (n < 0) { break; }
                        if (n == 0) { continue; }
                        final byte[] chunk = Arrays.copyOf(buf, n);
                        ansiProcessor.process(chunk, 0, chunk.length, new TerminalAnsiProcessor.SegmentConsumer() {
                            @Override public void accept(String text, boolean bold, Integer foregroundRgb, Integer backgroundRgb) {
                                terminalScreen.append(text, bold, foregroundRgb, backgroundRgb);
                            }
                        });
                        ui.post(new Runnable() {
                            @Override public void run() { notifyScreenUpdated(); }
                        });
                    }
                } catch (IOException ignored) {
                } finally {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (isConnected()) {
                                setStatus("Remote shell closed");
                                Listener l = listener;
                                if (l != null) { l.onRemoteShellClosed(); }
                            }
                        }
                    });
                }
            }
        }, "ssh-reader");
        t.setDaemon(true);
        readerThread = t;
        t.start();
    }

    private void cleanupSilently() {
        Thread r = readerThread;
        if (r != null) { r.interrupt(); readerThread = null; }
        ChannelShell ch = channel;
        if (ch != null) { try { ch.disconnect(); } catch (Exception ignored) { } channel = null; }
        Session s = session;
        if (s != null) { try { s.disconnect(); } catch (Exception ignored) { } session = null; }
        remoteIn = null;
    }

    private String describeConnectError(Exception e, String idFile) {
        String msg = e.getMessage();
        if (msg == null) { msg = e.toString(); }
        String lowerCaseMsg = msg.toLowerCase();
        if (lowerCaseMsg.contains("passphrase") || lowerCaseMsg.contains("decrypt the identity key")) {
            return msg;
        }
        if (lowerCaseMsg.contains("auth fail") || lowerCaseMsg.contains("auth cancel")) {
            if (!TextUtils.isEmpty(idFile)) {
                return msg + " — the server rejected the identity key. Confirm the"
                        + " matching public key is in the server's ~/.ssh/authorized_keys."
                        + " Leave the password field empty for a key with no passphrase;"
                        + " only enter the passphrase there if the key is encrypted.";
            }
            return msg + " — check the username and password.";
        }
        return msg;
    }

    private void startForegroundNotification(String user, String host, int port) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel notifChannel = new NotificationChannel(
                        CHANNEL_ID, "SSH connection", NotificationManager.IMPORTANCE_LOW);
                    notifChannel.setDescription("Keeps the SSH session connected while the app is in the background.");
                    nm.createNotificationChannel(notifChannel);
            }
        }
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, tapIntent, flags);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Connected " + user + "@" + host + ":" + port)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }
}
