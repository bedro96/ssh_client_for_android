package com.bedro96.sshclient;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal but functional SSH client.
 *
 * <p>Implements an interactive shell session over SSH using JSch. The user
 * enters host, port, username and password, opens a shell, then types
 * commands which are written to the remote shell's stdin while the remote
 * stdout/stderr is streamed back to the on-screen terminal view.</p>
 */
public final class MainActivity extends Activity {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_OUTPUT_CHARS = 200_000;

    private EditText editHost;
    private EditText editPort;
    private EditText editUser;
    private EditText editPassword;
    private EditText editCommand;
    private Button btnConnect;
    private Button btnSend;
    private TextView txtStatus;
    private TextView txtOutput;
    private ScrollView scrollOutput;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private volatile Session session;
    private volatile ChannelShell channel;
    private volatile OutputStream remoteIn;
    private volatile Thread readerThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editHost = findViewById(R.id.editHost);
        editPort = findViewById(R.id.editPort);
        editUser = findViewById(R.id.editUser);
        editPassword = findViewById(R.id.editPassword);
        editCommand = findViewById(R.id.editCommand);
        btnConnect = findViewById(R.id.btnConnect);
        btnSend = findViewById(R.id.btnSend);
        txtStatus = findViewById(R.id.txtStatus);
        txtOutput = findViewById(R.id.txtOutput);
        scrollOutput = findViewById(R.id.scrollOutput);

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isConnected()) {
                    disconnect();
                } else {
                    connect();
                }
            }
        });

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendCommand();
            }
        });

        editCommand.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendCommand();
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    protected void onDestroy() {
        disconnect();
        io.shutdownNow();
        super.onDestroy();
    }

    private boolean isConnected() {
        Session s = session;
        return s != null && s.isConnected();
    }

    private void connect() {
        final String host = editHost.getText().toString().trim();
        final String portText = editPort.getText().toString().trim();
        final String user = editUser.getText().toString();
        final String password = editPassword.getText().toString();

        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(user)) {
            Toast.makeText(this, "Host and username are required", Toast.LENGTH_SHORT).show();
            return;
        }
        final int port;
        try {
            port = TextUtils.isEmpty(portText) ? 22 : Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid port", Toast.LENGTH_SHORT).show();
            return;
        }

        setStatus(getString(R.string.status_connecting) + " " + user + "@" + host + ":" + port);
        setConnectionInputsEnabled(false);
        btnConnect.setEnabled(false);

        io.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    JSch jsch = new JSch();
                    Session s = jsch.getSession(user, host, port);
                    s.setPassword(password);
                    Properties config = new Properties();
                    // Trust on first use; suitable for an initial client.
                    config.put("StrictHostKeyChecking", "no");
                    s.setConfig(config);
                    s.setServerAliveInterval(30_000);
                    s.connect(15_000);

                    ChannelShell ch = (ChannelShell) s.openChannel("shell");
                    ch.setPtyType("xterm");
                    final InputStream in = ch.getInputStream();
                    final OutputStream out = ch.getOutputStream();
                    ch.connect(10_000);

                    session = s;
                    channel = ch;
                    remoteIn = out;

                    startReader(in);

                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            clearOutput();
                            setStatus(getString(R.string.status_connected) + " " + user + "@" + host + ":" + port);
                            btnConnect.setEnabled(true);
                            btnConnect.setText(R.string.action_disconnect);
                            btnSend.setEnabled(true);
                            editCommand.setEnabled(true);
                            editCommand.requestFocus();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus(getString(R.string.status_error) + ": " + e.getMessage());
                            appendOutput("\n[connection failed] " + e.getMessage() + "\n");
                            setConnectionInputsEnabled(true);
                            btnConnect.setEnabled(true);
                            btnConnect.setText(R.string.action_connect);
                        }
                    });
                    cleanupSilently();
                }
            }
        });
    }

    private void startReader(final InputStream in) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[4096];
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        int n = in.read(buf);
                        if (n < 0) {
                            break;
                        }
                        if (n == 0) {
                            continue;
                        }
                        final String chunk = new String(buf, 0, n, UTF8);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                appendOutput(chunk);
                            }
                        });
                    }
                } catch (IOException ignored) {
                    // Stream closed.
                } finally {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isConnected()) {
                                // Channel ended but session still open: surface it.
                                setStatus("Remote shell closed");
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

    private void sendCommand() {
        OutputStream out = remoteIn;
        if (out == null) {
            return;
        }
        final String cmd = editCommand.getText().toString();
        editCommand.setText("");
        io.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    OutputStream o = remoteIn;
                    if (o == null) {
                        return;
                    }
                    o.write((cmd + "\n").getBytes(UTF8));
                    o.flush();
                } catch (final IOException e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            appendOutput("\n[send failed] " + e.getMessage() + "\n");
                        }
                    });
                }
            }
        });
    }

    private void disconnect() {
        setStatus(getString(R.string.status_disconnected));
        cleanupSilently();
        setConnectionInputsEnabled(true);
        btnConnect.setText(R.string.action_connect);
        btnConnect.setEnabled(true);
        btnSend.setEnabled(false);
        editCommand.setEnabled(false);
    }

    private void cleanupSilently() {
        Thread r = readerThread;
        if (r != null) {
            r.interrupt();
            readerThread = null;
        }
        ChannelShell ch = channel;
        if (ch != null) {
            try { ch.disconnect(); } catch (Exception ignored) { }
            channel = null;
        }
        Session s = session;
        if (s != null) {
            try { s.disconnect(); } catch (Exception ignored) { }
            session = null;
        }
        remoteIn = null;
    }

    private void setConnectionInputsEnabled(boolean enabled) {
        editHost.setEnabled(enabled);
        editPort.setEnabled(enabled);
        editUser.setEnabled(enabled);
        editPassword.setEnabled(enabled);
    }

    private void setStatus(CharSequence s) {
        txtStatus.setText(s);
    }

    private void appendOutput(CharSequence chunk) {
        txtOutput.append(chunk);
        CharSequence current = txtOutput.getText();
        if (current.length() > MAX_OUTPUT_CHARS) {
            // Trim from the front to keep memory bounded.
            CharSequence trimmed = current.subSequence(current.length() - MAX_OUTPUT_CHARS, current.length());
            txtOutput.setText(trimmed);
        }
        scrollOutput.post(new Runnable() {
            @Override
            public void run() {
                scrollOutput.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void clearOutput() {
        txtOutput.setText("");
    }
}
