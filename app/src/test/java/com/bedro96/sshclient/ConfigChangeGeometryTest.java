package com.bedro96.sshclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards against issue #62 (terminal not resizing to fill available height
 * after the app window is enlarged).
 *
 * <p>Root cause: {@code MainActivity} declares
 * {@code android:configChanges="orientation|screenSize|keyboardHidden"} so
 * Android never recreates the Activity across a window resize (rotation,
 * split-screen, freeform drag-resize, tablet/foldable window changes, etc) —
 * that's needed to keep the live SSH session alive. But that also means
 * Android hands every one of those changes to {@code onConfigurationChanged}
 * instead of tearing down and re-inflating the view tree, and the Activity
 * previously did not override that callback at all. The terminal viewport's
 * geometry was then only ever recomputed reactively, via the
 * {@code OnLayoutChangeListener} wired in {@code wireTerminalViewport()} —
 * and on a real device that listener can miss (or badly lag) a window-resize
 * event that arrives as a configuration change without an intervening
 * bounds-changed layout pass on {@code txtOutput}/{@code scrollOutput}
 * themselves, leaving the terminal grid stuck at whatever row/column count
 * was computed for the previous (smaller) window size.
 *
 * <p>The fix is for {@code MainActivity} to override
 * {@code onConfigurationChanged} and explicitly force a fresh terminal
 * geometry recompute there, so every configuration change — not just the
 * ones that happen to also fire a layout-change callback quickly enough —
 * results in the terminal being resized to use the newly available height.
 *
 * <p>{@code MainActivity} extends {@code android.app.Activity} and cannot be
 * loaded on the host JVM that runs these offline tests (see run-tests.sh),
 * so — consistent with {@link SshResizeRaceTest} and
 * {@link ChromeLayoutCompactnessTest} — this test inspects its source
 * directly rather than exercising the class.
 */
public final class ConfigChangeGeometryTest {

    private ConfigChangeGeometryTest() { }

    public static void main(String[] args) throws Exception {
        testManifestHandlesResizeConfigChangesInPlace();
        testMainActivityOverridesOnConfigurationChanged();
        testOnConfigurationChangedForcesTerminalGeometryRecompute();
        System.out.println("CONFIG CHANGE GEOMETRY TESTS PASSED");
    }

    private static void testManifestHandlesResizeConfigChangesInPlace() throws IOException {
        String manifest = readFile(mainDir().resolve("AndroidManifest.xml"));
        if (!manifest.contains("android:configChanges=\"orientation|screenSize|keyboardHidden\"")) {
            throw new AssertionError("FAILED: AndroidManifest.xml must keep handling "
                    + "orientation|screenSize|keyboardHidden in place (no Activity recreation) so a live "
                    + "SSH session survives a window resize");
        }
    }

    private static void testMainActivityOverridesOnConfigurationChanged() throws IOException {
        String source = readMainActivitySource();
        if (!Pattern.compile("void\\s+onConfigurationChanged\\s*\\(").matcher(source).find()) {
            throw new AssertionError("FAILED: MainActivity must override onConfigurationChanged(...). "
                    + "Without it, Android hands every orientation/screenSize/keyboardHidden change declared in "
                    + "configChanges to the Activity without forcing any re-measure of the already-inflated "
                    + "terminal viewport, so a window resize (split-screen, freeform, tablet/foldable) can leave "
                    + "the terminal grid stuck at its previous, smaller row/column count (issue #62)");
        }
    }

    private static void testOnConfigurationChangedForcesTerminalGeometryRecompute() throws IOException {
        String source = readMainActivitySource();
        Matcher methodMatch = Pattern.compile(
                "void\\s+onConfigurationChanged\\s*\\([^)]*\\)\\s*\\{(.*?)\\n    \\}",
                Pattern.DOTALL).matcher(source);
        if (!methodMatch.find()) {
            throw new AssertionError("FAILED: could not locate the body of onConfigurationChanged(...) in "
                    + "MainActivity.java to verify it forces a terminal geometry recompute");
        }
        String body = methodMatch.group(1);
        if (!body.contains("updateTerminalGeometry()")) {
            throw new AssertionError("FAILED: onConfigurationChanged(...) must call updateTerminalGeometry() "
                    + "(directly or via a posted Runnable) so every window-resize configuration change re-fits "
                    + "the terminal to the newly available height instead of relying solely on the "
                    + "OnLayoutChangeListener, which can miss or lag a resize event (issue #62)");
        }
    }

    private static String readMainActivitySource() throws IOException {
        return readFile(mainDir().resolve("java/com/bedro96/sshclient/MainActivity.java"));
    }

    private static String readFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new AssertionError("FAILED: source file not found: " + path);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path mainDir() {
        String repoRoot = System.getProperty("config.change.geometry.test.repoRoot", ".");
        return Paths.get(repoRoot, "app", "src", "main");
    }
}
