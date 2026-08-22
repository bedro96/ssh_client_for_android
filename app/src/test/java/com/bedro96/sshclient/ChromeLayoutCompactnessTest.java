package com.bedro96.sshclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards the "chrome" (non-terminal) UI height budget: the special-key
 * toolbar (issue #58), the connection-status bar (issue #59), and the
 * CONNECT/DISCONNECT button (issue #60) must all use compact vertical
 * padding/min-height so the terminal viewport below them gets the maximum
 * possible number of rows.
 *
 * These are plain resource-inspection tests (no Robolectric/instrumentation
 * available in this project — see run-tests.sh) that parse the layout/style/
 * dimension XML as text, following the same pattern as
 * {@link SshReaderConfigTest}.
 */
public final class ChromeLayoutCompactnessTest {

    private static final int MAX_CHROME_BUTTON_MIN_HEIGHT_DP = 32;
    private static final int MAX_CHROME_BUTTON_VERTICAL_PADDING_DP = 4;
    private static final int MAX_STATUS_BAR_PADDING_DP = 4;
    private static final int MAX_STATUS_BAR_PADDING_SW600DP_DP = 6;

    private ChromeLayoutCompactnessTest() { }

    public static void main(String[] args) throws Exception {
        testKeyToolbarButtonsUseCompactStyle();
        testCompactButtonStyleDefinesReducedDimensions();
        testStatusBarPaddingReducedInBothLayouts();
        testConnectButtonUsesCompactStyleInBothLayouts();
        System.out.println("CHROME LAYOUT COMPACTNESS TESTS PASSED");
    }

    private static void testKeyToolbarButtonsUseCompactStyle() throws IOException {
        String xml = readRes("layout/key_toolbar.xml");
        Matcher buttons = Pattern.compile("<Button\\b[^>]*?/>", Pattern.DOTALL).matcher(xml);
        int count = 0;
        while (buttons.find()) {
            count++;
            String tag = buttons.group();
            if (!tag.contains("style=\"@style/KeyToolbarButton\"")) {
                throw new AssertionError("FAILED: key_toolbar.xml Button must use the compact "
                        + "KeyToolbarButton style (still using the default buttonStyleSmall?): " + tag);
            }
        }
        if (count == 0) {
            throw new AssertionError("FAILED: could not find any <Button> elements in key_toolbar.xml");
        }
    }

    private static void testCompactButtonStyleDefinesReducedDimensions() throws IOException {
        String styles = readRes("values/styles.xml");
        Matcher styleBlock = Pattern.compile(
                "<style\\s+name=\"KeyToolbarButton\"[^>]*>(.*?)</style>", Pattern.DOTALL).matcher(styles);
        if (!styleBlock.find()) {
            throw new AssertionError("FAILED: values/styles.xml must define a KeyToolbarButton style");
        }
        String body = styleBlock.group(1);
        assertDimenRef(body, "android:minHeight", "chrome_button_min_height");
        assertDimenRef(body, "android:paddingTop", "chrome_button_vertical_padding");
        assertDimenRef(body, "android:paddingBottom", "chrome_button_vertical_padding");

        String dimens = readRes("values/dimens.xml");
        assertDimenAtMost(dimens, "chrome_button_min_height", MAX_CHROME_BUTTON_MIN_HEIGHT_DP);
        assertDimenAtMost(dimens, "chrome_button_vertical_padding", MAX_CHROME_BUTTON_VERTICAL_PADDING_DP);
    }

    private static void testStatusBarPaddingReducedInBothLayouts() throws IOException {
        assertStatusPaddingAtMost("layout/activity_main.xml", MAX_STATUS_BAR_PADDING_DP);
        assertStatusPaddingAtMost("layout-sw600dp/activity_main.xml", MAX_STATUS_BAR_PADDING_SW600DP_DP);
    }

    private static void assertStatusPaddingAtMost(String layoutPath, int maxDp) throws IOException {
        String xml = readRes(layoutPath);
        String txtStatusTag = findTagById(xml, "txtStatus");
        int paddingDp = resolvePaddingDp(txtStatusTag, xml);
        if (paddingDp > maxDp) {
            throw new AssertionError("FAILED: " + layoutPath + " txtStatus padding is " + paddingDp
                    + "dp, expected at most " + maxDp + "dp");
        }
    }

    private static void testConnectButtonUsesCompactStyleInBothLayouts() throws IOException {
        assertConnectButtonIsCompact("layout/activity_main.xml");
        assertConnectButtonIsCompact("layout-sw600dp/activity_main.xml");
    }

    private static void assertConnectButtonIsCompact(String layoutPath) throws IOException {
        String xml = readRes(layoutPath);
        String tag = findTagById(xml, "btnConnect");
        if (!tag.contains("style=\"@style/KeyToolbarButton\"")) {
            throw new AssertionError("FAILED: " + layoutPath + " btnConnect must use the compact "
                    + "KeyToolbarButton style: " + tag);
        }
    }

    private static String findTagById(String xml, String id) {
        Matcher m = Pattern.compile(
                "<[A-Za-z.]+\\b[^>]*?android:id=\"@\\+id/" + id + "\"[^>]*?/>", Pattern.DOTALL).matcher(xml);
        if (!m.find()) {
            throw new AssertionError("FAILED: could not find element with id " + id + " in layout");
        }
        return m.group();
    }

    private static int resolvePaddingDp(String tag, String xml) throws IOException {
        Matcher m = Pattern.compile("android:padding=\"([^\"]+)\"").matcher(tag);
        if (!m.find()) {
            throw new AssertionError("FAILED: element has no android:padding attribute: " + tag);
        }
        String value = m.group(1);
        if (value.startsWith("@dimen/")) {
            String dimenName = value.substring("@dimen/".length());
            return dimenValueDp(readRes("values/dimens.xml"), dimenName);
        }
        return dpValue(value);
    }

    private static int dimenValueDp(String dimensXml, String name) {
        Matcher m = Pattern.compile(
                "<dimen\\s+name=\"" + name + "\">([^<]+)</dimen>").matcher(dimensXml);
        if (!m.find()) {
            throw new AssertionError("FAILED: values/dimens.xml has no <dimen name=\"" + name + "\">");
        }
        return dpValue(m.group(1));
    }

    private static int dpValue(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.endsWith("dp") && !trimmed.endsWith("dip")) {
            throw new AssertionError("FAILED: expected a dp/dip dimension, got: " + raw);
        }
        String numeric = trimmed.replaceAll("d(i)?p$", "");
        return Integer.parseInt(numeric);
    }

    private static void assertDimenRef(String styleBody, String item, String expectedDimenName) {
        Matcher m = Pattern.compile(
                "<item\\s+name=\"" + Pattern.quote(item) + "\">@dimen/" + Pattern.quote(expectedDimenName)
                        + "</item>").matcher(styleBody);
        if (!m.find()) {
            throw new AssertionError("FAILED: KeyToolbarButton style must set " + item + " to @dimen/"
                    + expectedDimenName);
        }
    }

    private static void assertDimenAtMost(String dimensXml, String name, int maxDp) {
        int actual = dimenValueDp(dimensXml, name);
        if (actual > maxDp) {
            throw new AssertionError("FAILED: dimen " + name + " is " + actual + "dp, expected at most "
                    + maxDp + "dp");
        }
    }

    private static String readRes(String relativePath) throws IOException {
        Path path = resDir().resolve(relativePath);
        if (!Files.exists(path)) {
            throw new AssertionError("FAILED: resource file not found: " + path);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path resDir() {
        String repoRoot = System.getProperty("chrome.layout.test.repoRoot", ".");
        return Paths.get(repoRoot, "app", "src", "main", "res");
    }
}
