package com.bedro96.sshclient;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Interactive SSH client with saved host profiles, identity-file key auth, a
 * special-key toolbar and a single terminal area (typed keystrokes are streamed
 * straight to the remote shell, which echoes them back).
 */
public final class MainActivity extends Activity {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_OUTPUT_CHARS = 200_000;
    private static final int REQ_IMPORT_KEY = 1001;
    private static final String PREFS = "profiles";
    private static final String KEY_PROFILES = "list";
    private static final String KEY_DIR = "identity_keys";

    private EditText editHost;
    private EditText editPort;
    private EditText editUser;
    private EditText editPassword;
    private TextView txtIdentity;
    private Spinner spinnerProfiles;
    private Button btnConnect;
    private Button btnSave;
    private Button btnDelete;
    private Button btnImportKey;
    private TextView txtStatus;
    private EditText txtOutput;
    private ScrollView scrollOutput;
    private View keyToolbar;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private volatile Session session;
    private volatile ChannelShell channel;
    private volatile OutputStream remoteIn;
    private volatile Thread readerThread;

    private final List<JSONObject> profiles = new ArrayList<>();
    private ArrayAdapter<String> profileAdapter;
    private String identityPath;
    private float terminalSize = 13f;

    /** True while we programmatically reset the terminal text, to suppress echo. */
    private boolean suppressTextWatcher;
    /** Authoritative terminal text as produced by the remote shell. */
    private final StringBuilder termBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editHost = findViewById(R.id.editHost);
        editPort = findViewById(R.id.editPort);
        editUser = findViewById(R.id.editUser);
        editPassword = findViewById(R.id.editPassword);
        txtIdentity = findViewById(R.id.txtIdentity);
        spinnerProfiles = findViewById(R.id.spinnerProfiles);
        btnConnect = findViewById(R.id.btnConnect);
        btnSave = findViewById(R.id.btnSave);
        btnDelete = findViewById(R.id.btnDelete);
        btnImportKey = findViewById(R.id.btnImportKey);
        txtStatus = findViewById(R.id.txtStatus);
        txtOutput = findViewById(R.id.txtOutput);
        scrollOutput = findViewById(R.id.scrollOutput);
        keyToolbar = findViewById(R.id.keyToolbar);

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (isConnected()) { disconnect(); } else { connect(); }
            }
        });
        btnImportKey.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickIdentityFile(); }
        });
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveProfile(); }
        });
        btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { deleteProfile(); }
        });

        setupProfiles();
        wireKeyToolbar();
        wireTerminalInput();
        setTerminalSize(terminalSize);
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

    // ---------------------------------------------------------------- Profiles

    private void setupProfiles() {
        loadProfiles();
        profileAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, profileTitles());
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProfiles.setAdapter(profileAdapter);
        spinnerProfiles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos > 0) { applyProfile(profiles.get(pos - 1)); }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
    }

    private List<String> profileTitles() {
        List<String> t = new ArrayList<>();
        t.add(getString(R.string.profile_none));
        for (JSONObject p : profiles) { t.add(p.optString("name", p.optString("host"))); }
        return t;
    }

    private void refreshProfileSpinner() {
        profileAdapter.clear();
        profileAdapter.addAll(profileTitles());
        profileAdapter.notifyDataSetChanged();
    }

    private void loadProfiles() {
        profiles.clear();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PROFILES, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) { profiles.add(arr.getJSONObject(i)); }
        } catch (Exception ignored) { }
    }

    private void persistProfiles() {
        JSONArray arr = new JSONArray();
        for (JSONObject p : profiles) { arr.put(p); }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_PROFILES, arr.toString()).apply();
    }

    private void applyProfile(JSONObject p) {
        editHost.setText(p.optString("host"));
        editPort.setText(p.optString("port", "22"));
        editUser.setText(p.optString("user"));
        editPassword.setText(p.optString("password"));
        identityPath = p.optString("identity", "");
        if (TextUtils.isEmpty(identityPath)) {
            identityPath = null;
            txtIdentity.setText(R.string.identity_none);
        } else {
            txtIdentity.setText(new File(identityPath).getName());
        }
    }

    private void saveProfile() {
        String host = editHost.getText().toString().trim();
        String user = editUser.getText().toString().trim();
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(user)) {
            Toast.makeText(this, "Host and username are required", Toast.LENGTH_SHORT).show();
            return;
        }
        JSONObject p = new JSONObject();
        try {
            p.put("name", user + "@" + host);
            p.put("host", host);
            p.put("port", editPort.getText().toString().trim());
            p.put("user", user);
            p.put("password", editPassword.getText().toString());
            p.put("identity", identityPath == null ? "" : identityPath);
        } catch (Exception ignored) { return; }
        // Replace an existing profile with the same name.
        int existing = -1;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).optString("name").equals(p.optString("name"))) { existing = i; break; }
        }
        if (existing >= 0) { profiles.set(existing, p); } else { profiles.add(p); }
        persistProfiles();
        refreshProfileSpinner();
        Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show();
    }

    private void deleteProfile() {
        int pos = spinnerProfiles.getSelectedItemPosition();
        if (pos <= 0) { return; }
        profiles.remove(pos - 1);
        persistProfiles();
        refreshProfileSpinner();
        spinnerProfiles.setSelection(0);
        Toast.makeText(this, "Profile deleted", Toast.LENGTH_SHORT).show();
    }

    // ----------------------------------------------------------- Identity file

    private void pickIdentityFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT_KEY);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_IMPORT_KEY && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (!"content".equals(uri.getScheme())) {
                Toast.makeText(this, "Unsupported file source", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                File dir = new File(getFilesDir(), KEY_DIR);
                if (!dir.exists()) { dir.mkdirs(); }
                String name = "id_" + java.util.UUID.randomUUID();
                File dest = new File(dir, name);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); }
                }
                identityPath = dest.getAbsolutePath();
                txtIdentity.setText(dest.getName());
                Toast.makeText(this, "Identity file imported", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------------------------------------------------------------- Connect

    private void connect() {
        final String host = editHost.getText().toString().trim();
        final String portText = editPort.getText().toString().trim();
        final String user = editUser.getText().toString();
        final String password = editPassword.getText().toString();
        final String idFile = identityPath;

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
            @Override public void run() {
                try {
                    JSch jsch = new JSch();
                    if (!TextUtils.isEmpty(idFile)) {
                        try {
                            // Always load the key first without a passphrase so a
                            // login password does not interfere with publickey auth.
                            jsch.addIdentity(idFile);
                        } catch (JSchException keyError) {
                            // If the key is encrypted, retry with the entered value
                            // as passphrase.
                            if (!TextUtils.isEmpty(password)) {
                                jsch.addIdentity(idFile, password);
                            } else {
                                throw keyError;
                            }
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
                    ch.setPtyType("xterm");
                    final InputStream in = ch.getInputStream();
                    final OutputStream out = ch.getOutputStream();
                    ch.connect(10_000);

                    session = s;
                    channel = ch;
                    remoteIn = out;
                    startReader(in);

                    ui.post(new Runnable() {
                        @Override public void run() {
                            clearOutput();
                            setStatus(getString(R.string.status_connected) + " " + user + "@" + host + ":" + port);
                            btnConnect.setEnabled(true);
                            btnConnect.setText(R.string.action_disconnect);
                            keyToolbar.setVisibility(View.VISIBLE);
                            txtOutput.setEnabled(true);
                            txtOutput.requestFocus();
                        }
                    });
                } catch (final Exception e) {
                    final String detail = describeConnectError(e, idFile);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            setStatus(getString(R.string.status_error) + ": " + detail);
                            appendOutput("\n[connection failed] " + detail + "\n");
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

    private String describeConnectError(Exception e, String idFile) {
        String msg = e.getMessage();
        if (msg == null) { msg = e.toString(); }
        String lowerCaseMsg = msg.toLowerCase();
        if (lowerCaseMsg.contains("auth fail") || lowerCaseMsg.contains("auth cancel")) {
            if (!TextUtils.isEmpty(idFile)) {
                return msg + " — the server rejected the identity key. Check that the"
                        + " key is in the server's authorized_keys, and if the key is"
                        + " passphrase-protected enter the passphrase in the password"
                        + " field.";
            }
            return msg + " — check the username and password.";
        }
        return msg;
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
                        final String chunk = new String(buf, 0, n, UTF8);
                        ui.post(new Runnable() {
                            @Override public void run() { appendOutput(chunk); }
                        });
                    }
                } catch (IOException ignored) {
                } finally {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (isConnected()) { setStatus("Remote shell closed"); }
                        }
                    });
                }
            }
        }, "ssh-reader");
        t.setDaemon(true);
        readerThread = t;
        t.start();
    }

    private void disconnect() {
        setStatus(getString(R.string.status_disconnected));
        cleanupSilently();
        setConnectionInputsEnabled(true);
        btnConnect.setText(R.string.action_connect);
        btnConnect.setEnabled(true);
        keyToolbar.setVisibility(View.GONE);
        txtOutput.setEnabled(false);
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

    // --------------------------------------------------------- Terminal input

    private void wireTerminalInput() {
        txtOutput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppressTextWatcher) { return; }
                if (count > before) {
                    // Characters typed or pasted: forward them to the remote shell.
                    String typed = s.subSequence(start + before, start + count).toString();
                    sendRaw(typed.getBytes(UTF8));
                } else if (before > count) {
                    // A delete/backspace: forward one DEL so the remote handles it.
                    sendRaw(new byte[] {0x7f});
                }
                // Remote echoes everything; revert local edit to the server buffer.
                restoreBuffer();
            }
            @Override public void afterTextChanged(Editable e) { }
        });
    }

    private void restoreBuffer() {
        suppressTextWatcher = true;
        txtOutput.setText(termBuffer.toString());
        txtOutput.setSelection(txtOutput.getText().length());
        suppressTextWatcher = false;
    }

    private void sendRaw(final byte[] bytes) {
        if (remoteIn == null) { return; }
        io.submit(new Runnable() {
            @Override public void run() {
                try {
                    OutputStream o = remoteIn;
                    if (o == null) { return; }
                    o.write(bytes);
                    o.flush();
                } catch (final IOException e) {
                    ui.post(new Runnable() {
                        @Override public void run() { appendOutput("\n[send failed] " + e.getMessage() + "\n"); }
                    });
                }
            }
        });
    }

    private void wireKeyToolbar() {
        bindKey(R.id.keyEsc, new byte[] {0x1b});
        bindKey(R.id.keyTab, new byte[] {0x09});
        bindKey(R.id.keyCtrlC, new byte[] {0x03});
        bindKey(R.id.keyCtrlD, new byte[] {0x04});
        bindKey(R.id.keyUp, new byte[] {0x1b, '[', 'A'});
        bindKey(R.id.keyDown, new byte[] {0x1b, '[', 'B'});
        bindKey(R.id.keyRight, new byte[] {0x1b, '[', 'C'});
        bindKey(R.id.keyLeft, new byte[] {0x1b, '[', 'D'});
        bindKey(R.id.keyCtrlA, new byte[] {0x01});
        bindKey(R.id.keyCtrlE, new byte[] {0x05});
        bindKey(R.id.keyCtrlB, new byte[] {0x02});
        bindKey(R.id.keyCtrlZ, new byte[] {0x1a});
        findViewById(R.id.keyFontUp).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setTerminalSize(terminalSize + 1f); }
        });
        findViewById(R.id.keyFontDown).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setTerminalSize(terminalSize - 1f); }
        });
    }

    private void bindKey(int id, final byte[] seq) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendRaw(seq); }
        });
    }

    private void setTerminalSize(float sp) {
        terminalSize = Math.max(8f, Math.min(28f, sp));
        txtOutput.setTextSize(terminalSize);
    }

    // ---------------------------------------------------------------- Helpers

    private void setConnectionInputsEnabled(boolean enabled) {
        editHost.setEnabled(enabled);
        editPort.setEnabled(enabled);
        editUser.setEnabled(enabled);
        editPassword.setEnabled(enabled);
        spinnerProfiles.setEnabled(enabled);
        btnSave.setEnabled(enabled);
        btnDelete.setEnabled(enabled);
        btnImportKey.setEnabled(enabled);
    }

    private void setStatus(CharSequence s) { txtStatus.setText(s); }

    private void appendOutput(CharSequence chunk) {
        termBuffer.append(chunk);
        if (termBuffer.length() > MAX_OUTPUT_CHARS) {
            termBuffer.delete(0, termBuffer.length() - MAX_OUTPUT_CHARS);
        }
        suppressTextWatcher = true;
        txtOutput.setText(termBuffer.toString());
        txtOutput.setSelection(txtOutput.getText().length());
        suppressTextWatcher = false;
        scrollOutput.post(new Runnable() {
            @Override public void run() { scrollOutput.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private void clearOutput() {
        termBuffer.setLength(0);
        suppressTextWatcher = true;
        txtOutput.setText("");
        suppressTextWatcher = false;
    }
}
