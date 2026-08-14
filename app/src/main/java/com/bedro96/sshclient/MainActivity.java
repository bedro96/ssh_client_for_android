package com.bedro96.sshclient;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Interactive SSH client with saved host profiles, identity-file key auth, a
 * special-key toolbar and a single terminal area (typed keystrokes are streamed
 * straight to the remote shell, which echoes them back).
 *
 * The connection itself, the reader thread and the terminal buffer live in
 * {@link SshConnectionService}, a bound foreground service: this Activity is
 * only a thin, replaceable UI over it, so switching away from the app (or even
 * this Activity being recreated) never touches the live session.
 */
public final class MainActivity extends Activity implements SshConnectionService.Listener {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_OUTPUT_CHARS = 200_000;
    private static final int REQ_IMPORT_KEY = 1001;
    private static final int REQ_POST_NOTIFICATIONS = 1002;
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
    private View panelConnection;
    private View keyToolbar;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private SshConnectionService sshService;
    private boolean boundToService;

    private final List<JSONObject> profiles = new ArrayList<>();
    private ArrayAdapter<String> profileAdapter;
    private String identityPath;
    private float terminalSize = 13f;

    /** True while we programmatically reset the terminal text, to suppress echo. */
    private boolean suppressTextWatcher;
    /** Tracks whether the current hardware Tab keypress was consumed by the terminal. */
    private final TerminalInputHandler.KeyState terminalKeyState = new TerminalInputHandler.KeyState();
    private int terminalRows = TerminalScreen.DEFAULT_ROWS;
    private int terminalCols = TerminalScreen.DEFAULT_COLS;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            sshService = ((SshConnectionService.LocalBinder) binder).getService();
            sshService.setListener(MainActivity.this);
            onServiceBound();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            sshService = null;
        }
    };

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
        panelConnection = findViewById(R.id.panelConnection);
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
        wireTerminalViewport();
        applyTerminalTypeface();
        setTerminalSize(terminalSize);
        txtOutput.setCursorVisible(false);
        requestNotificationPermissionIfNeeded();

        bindService(new Intent(this, SshConnectionService.class), serviceConnection, Context.BIND_AUTO_CREATE);
        boundToService = true;
    }

    @Override
    protected void onDestroy() {
        if (boundToService) {
            if (sshService != null) { sshService.setListener(null); }
            unbindService(serviceConnection);
            boundToService = false;
        }
        super.onDestroy();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, REQ_POST_NOTIFICATIONS);
            }
        }
    }

    /** Called once the binding to the always-alive connection service is ready. */
    private void onServiceBound() {
        if (sshService.isConnected()) {
            setStatus(sshService.getStatusText());
            btnConnect.setEnabled(true);
            btnConnect.setText(R.string.action_disconnect);
            setConnectionPanelCollapsed(true);
            keyToolbar.setVisibility(View.VISIBLE);
            txtOutput.setEnabled(true);
            updateTerminalGeometry();
            renderTermBuffer();
        } else {
            String status = sshService.getStatusText();
            if (!TextUtils.isEmpty(status)) { setStatus(status); }
        }
    }

    private boolean isConnected() {
        return sshService != null && sshService.isConnected();
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
                restrictToOwner(dir);
                String name = "id_" + java.util.UUID.randomUUID();
                File dest = new File(dir, name);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); }
                }
                // Mimic `ssh -i`, which requires the private key file to be
                // readable only by its owner (mode 0600); a world/group readable
                // key is otherwise rejected as an unprotected private key.
                restrictToOwner(dest);
                identityPath = dest.getAbsolutePath();
                txtIdentity.setText(dest.getName());
                Toast.makeText(this, "Identity file imported", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Tightens a file or directory so only the owner can read and write it,
     * matching the 0600/0700 permissions OpenSSH expects for identity files
     * passed via {@code ssh -i}. Best-effort: failures are ignored because
     * app-private storage is already restricted to this app's UID.
     */
    private static void restrictToOwner(File f) {
        // Drop all permissions for group/other, then grant owner-only access.
        f.setReadable(false, false);
        f.setWritable(false, false);
        f.setExecutable(false, false);
        f.setReadable(true, true);
        f.setWritable(true, true);
        if (f.isDirectory()) {
            // Directories need the owner execute bit to be traversable.
            f.setExecutable(true, true);
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
        if (sshService == null) {
            Toast.makeText(this, "Connection service not ready yet, try again", Toast.LENGTH_SHORT).show();
            return;
        }

        setStatus(getString(R.string.status_connecting) + " " + user + "@" + host + ":" + port);
        setConnectionInputsEnabled(false);
        btnConnect.setEnabled(false);
        sshService.connect(host, port, user, password, idFile, terminalCols, terminalRows);
    }

    @Override public void onConnected(String host, int port, String user) {
        clearOutput();
        setStatus(getString(R.string.status_connected) + " " + user + "@" + host + ":" + port);
        btnConnect.setEnabled(true);
        btnConnect.setText(R.string.action_disconnect);
        setConnectionPanelCollapsed(true);
        keyToolbar.setVisibility(View.VISIBLE);
        txtOutput.setEnabled(true);
        txtOutput.requestFocus();
        updateTerminalGeometry();
    }

    @Override public void onConnectFailed(String detail) {
        setStatus(getString(R.string.status_error) + ": " + detail);
        appendOutput("\n[connection failed] " + detail + "\n");
        setConnectionInputsEnabled(true);
        btnConnect.setEnabled(true);
        btnConnect.setText(R.string.action_connect);
    }

    @Override public void onDisconnected() {
        setConnectionInputsEnabled(true);
        btnConnect.setText(R.string.action_connect);
        btnConnect.setEnabled(true);
        setConnectionPanelCollapsed(false);
        keyToolbar.setVisibility(View.GONE);
        txtOutput.setEnabled(false);
        txtOutput.setCursorVisible(false);
    }

    @Override public void onRemoteShellClosed() {
        setStatus("Remote shell closed");
    }

    @Override public void onScreenUpdated() {
        renderTermBuffer();
        scrollOutput.post(new Runnable() {
            @Override public void run() { scrollOutput.fullScroll(View.FOCUS_DOWN); }
        });
    }

    @Override public void onSendFailed(String detail) {
        appendOutput("\n[send failed] " + detail + "\n");
    }

    @Override public void onStatusChanged(final String status) {
        setStatus(status);
    }

    private void disconnect() {
        if (sshService != null) { sshService.disconnect(); }
    }


    // --------------------------------------------------------- Terminal input

    private void wireTerminalInput() {
        final TerminalInputHandler.Sender terminalSender = new TerminalInputHandler.Sender() {
            @Override public void send(byte[] bytes) { sendRaw(bytes); }
        };
        txtOutput.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (TerminalInputHandler.handleEscapeKeyAction(event.getAction(), keyCode,
                        terminalSender)) {
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_TAB) {
                    return TerminalInputHandler.handleTabKeyAction(event.getAction(),
                            event.isShiftPressed(), terminalKeyState, terminalSender);
                }
                if (TerminalInputHandler.arrowKeySequence(keyCode) != null) {
                    return TerminalInputHandler.handleArrowKeyAction(event.getAction(),
                            keyCode, terminalSender);
                }
                if (TerminalInputHandler.handleCtrlKeyAction(event.getAction(), keyCode,
                        event.isCtrlPressed(), terminalSender)) {
                    return true;
                }
                return false;
            }
        });
        txtOutput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppressTextWatcher) { return; }
                if (count > before) {
                    // Characters typed or pasted: forward them to the remote shell.
                    String typed = s.subSequence(start + before, start + count).toString();
                    TerminalInputHandler.handleTypedText(typed, terminalSender);
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

    private void wireTerminalViewport() {
        View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (left == oldLeft && top == oldTop && right == oldRight && bottom == oldBottom) {
                    return;
                }
                updateTerminalGeometry();
            }
        };
        txtOutput.addOnLayoutChangeListener(listener);
        scrollOutput.addOnLayoutChangeListener(listener);
        txtOutput.post(new Runnable() {
            @Override public void run() { updateTerminalGeometry(); }
        });
    }

    private void restoreBuffer() {
        renderTermBuffer();
    }

    /**
     * Renders the authoritative terminal buffer into the output view, placing the
     * caret (Android's native blinking cursor) at the tracked terminal cursor so
     * interactive CLIs that redraw a line in place show a cursor in the right spot.
     */
    private void renderTermBuffer() {
        if (sshService == null) { return; }
        TerminalScreen.Snapshot snapshot = sshService.snapshot(MAX_OUTPUT_CHARS);
        SpannableStringBuilder rendered = new SpannableStringBuilder(snapshot.text);
        for (TerminalScreen.StyleRun run : snapshot.runs) {
            if (run.end <= run.start) { continue; }
            if (run.bold) {
                rendered.setSpan(new StyleSpan(Typeface.BOLD),
                        run.start, run.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (run.foregroundRgb != null) {
                rendered.setSpan(new ForegroundColorSpan(0xff000000 | run.foregroundRgb),
                        run.start, run.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (run.backgroundRgb != null) {
                rendered.setSpan(new BackgroundColorSpan(0xff000000 | run.backgroundRgb),
                        run.start, run.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        suppressTextWatcher = true;
        txtOutput.setText(rendered, TextView.BufferType.SPANNABLE);
        txtOutput.setSelection(Math.max(0, Math.min(snapshot.cursorIndex, txtOutput.getText().length())));
        txtOutput.setCursorVisible(snapshot.cursorVisible && txtOutput.isEnabled());
        suppressTextWatcher = false;
    }

    private void sendRaw(final byte[] bytes) {
        if (sshService != null) { sshService.sendRaw(bytes); }
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

    private void applyTerminalTypeface() {
        // Android's built-in "monospace" family lacks box-drawing/line glyphs
        // (U+2500-U+257F etc.), so those characters fall back to a proportional
        // font that is wider than the ASCII cell. That breaks the fixed grid the
        // terminal relies on: full-width rows overflow and soft-wrap into "a line
        // and a half", and box borders no longer align with their contents.
        // DejaVu Sans Mono renders every glyph (ASCII and box-drawing alike) at a
        // single uniform advance, restoring a true monospace grid.
        try {
            Typeface mono = Typeface.createFromAsset(getAssets(), "fonts/DejaVuSansMono.ttf");
            if (mono != null) {
                txtOutput.setTypeface(mono);
            }
        } catch (RuntimeException ignored) {
            // Fall back to the platform monospace family if the asset is missing.
        }
        // A terminal grid is authoritative for line breaks: every screen row is
        // already emitted as its own line, so the view must never soft-wrap a row
        // onto a second display line.
        txtOutput.setHorizontallyScrolling(true);
    }

    private void setTerminalSize(float sp) {
        terminalSize = Math.max(8f, Math.min(28f, sp));
        txtOutput.setTextSize(terminalSize);
        txtOutput.post(new Runnable() {
            @Override public void run() { updateTerminalGeometry(); }
        });
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

    private void setConnectionPanelCollapsed(boolean collapsed) {
        panelConnection.setVisibility(collapsed ? View.GONE : View.VISIBLE);
    }

    private void setStatus(CharSequence s) { txtStatus.setText(s); }

    private void appendOutput(CharSequence chunk) {
        if (chunk == null || chunk.length() == 0 || sshService == null) { return; }
        sshService.appendLocalMessage(chunk.toString());
        renderTermBuffer();
        scrollOutput.post(new Runnable() {
            @Override public void run() { scrollOutput.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private void clearOutput() {
        suppressTextWatcher = true;
        txtOutput.setCursorVisible(false);
        txtOutput.setText("");
        suppressTextWatcher = false;
        renderTermBuffer();
    }

    private void updateTerminalGeometry() {
        int usableWidth = txtOutput.getWidth() - txtOutput.getPaddingLeft() - txtOutput.getPaddingRight();
        int usableHeight = scrollOutput.getHeight() - scrollOutput.getPaddingTop() - scrollOutput.getPaddingBottom();
        float charWidth = txtOutput.getPaint().measureText("W");
        int lineHeight = txtOutput.getLineHeight();
        if (usableWidth <= 0 || usableHeight <= 0 || charWidth <= 0f || lineHeight <= 0) {
            return;
        }
        int cols = TerminalGeometry.columns(usableWidth, charWidth);
        int rows = TerminalGeometry.rows(usableHeight, lineHeight);
        if (cols == terminalCols && rows == terminalRows) {
            return;
        }
        terminalCols = cols;
        terminalRows = rows;
        if (sshService != null) { sshService.resizeTerminal(cols, rows); }
        renderTermBuffer();
    }
}
