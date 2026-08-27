package com.bedro96.sshclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards the bottom padding of the root layout (issue #66): the terminal
 * {@code scrollOutput} ScrollView is the last child with
 * {@code layout_height="0dp"}/{@code layout_weight="1"}, so it is the only
 * view that grows/shrinks to fill remaining space in the root LinearLayout.
 * That means the root's bottom padding is dead space directly below the
 * terminal box that could instead be extra terminal rows, exactly like the
 * chrome-height reductions in issues #58-#60. This test asserts the root's
 * bottom padding has been split out into its own, much smaller, dimension
 * while the top/start/end padding (which still frames the connection
 * form/status bar) is left untouched.
 *
 * These are plain resource-inspection tests (no Robolectric/instrumentation
 * available in this project — see run-tests.sh) that parse the layout/dimen
 * XML as text, following the same pattern as {@link ChromeLayoutCompactnessTest}.
 */
public final class RootPaddingCompactnessTest {

    // Original uniform root padding values, kept for the top/start/end sides.
    private static final int PHONE_ORIGINAL_PADDING_DP = 12;
    private static final int TABLET_ORIGINAL_PADDING_DP = 24;

    // The new bottom padding must be meaningfully smaller than the original.
    private static final int MAX_PHONE_ROOT_BOTTOM_PADDING_DP = 4;
    private static final int MAX_TABLET_ROOT_BOTTOM_PADDING_DP = 8;

    private RootPaddingCompactnessTest() { }

    public static void main(String[] args) throws Exception {
        testPhoneRootBottomPaddingReduced();
        testTabletRootBottomPaddingReduced();
        testPhoneRootTopStartEndPaddingUnchanged();
        testTabletRootTopStartEndPaddingUnchanged();
        System.out.println("ROOT PADDING COMPACTNESS TESTS PASSED");
    }

    private static void testPhoneRootBottomPaddingReduced() throws IOException {
        assertRootBottomPaddingAtMost(
                "layout/activity_main.xml", "values/dimens.xml", MAX_PHONE_ROOT_BOTTOM_PADDING_DP);
    }

    private static void testTabletRootBottomPaddingReduced() throws IOException {
        assertRootBottomPaddingAtMost(
                "layout-sw600dp/activity_main.xml", "values-sw600dp/dimens.xml",
                MAX_TABLET_ROOT_BOTTOM_PADDING_DP);
    }

    private static void testPhoneRootTopStartEndPaddingUnchanged() throws IOException {
        assertRootTopStartEndPadding("layout/activity_main.xml", PHONE_ORIGINAL_PADDING_DP);
    }

    private static void testTabletRootTopStartEndPaddingUnchanged() throws IOException {
        assertRootTopStartEndPadding("layout-sw600dp/activity_main.xml", TABLET_ORIGINAL_PADDING_DP);
    }

    private static void assertRootBottomPaddingAtMost(String layoutPath, String dimensPath, int maxDp)
            throws IOException {
        String xml = readRes(layoutPath);
        String rootTag = findRootTag(xml);
        if (rootTag.contains("android:padding=\"")) {
            throw new AssertionError("FAILED: " + layoutPath + " root LinearLayout must not use a single "
                    + "uniform android:padding any more — split it into paddingTop/paddingStart/"
                    + "paddingEnd/paddingBottom so the bottom edge can shrink independently: " + rootTag);
        }
        String bottomValue = requireAttr(rootTag, "android:paddingBottom", layoutPath);
        if (!"@dimen/root_bottom_padding".equals(bottomValue)) {
            throw new AssertionError("FAILED: " + layoutPath + " root paddingBottom must be driven by "
                    + "@dimen/root_bottom_padding, got: " + bottomValue);
        }
        int paddingDp = resolveDp(bottomValue, dimensPath);
        if (paddingDp > maxDp) {
            throw new AssertionError("FAILED: " + layoutPath + " root paddingBottom is " + paddingDp
                    + "dp, expected at most " + maxDp + "dp");
        }
    }

    private static void assertRootTopStartEndPadding(String layoutPath, int expectedDp) throws IOException {
        String xml = readRes(layoutPath);
        String rootTag = findRootTag(xml);
        for (String attr : new String[] {"android:paddingTop", "android:paddingStart", "android:paddingEnd"}) {
            String value = requireAttr(rootTag, attr, layoutPath);
            int dp = dpValue(value);
            if (dp != expectedDp) {
                throw new AssertionError("FAILED: " + layoutPath + " root " + attr + " is " + dp
                        + "dp, expected unchanged " + expectedDp + "dp");
            }
        }
    }

    private static String requireAttr(String tag, String attr, String layoutPath) {
        Matcher m = Pattern.compile(Pattern.quote(attr) + "=\"([^\"]+)\"").matcher(tag);
        if (!m.find()) {
            throw new AssertionError("FAILED: " + layoutPath + " root LinearLayout has no " + attr
                    + " attribute: " + tag);
        }
        return m.group(1);
    }

    private static String findRootTag(String xml) {
        Matcher m = Pattern.compile("<LinearLayout\\b[^>]*?>", Pattern.DOTALL).matcher(xml);
        if (!m.find()) {
            throw new AssertionError("FAILED: could not find root <LinearLayout> element");
        }
        return m.group();
    }

    private static int resolveDp(String value, String dimensPath) throws IOException {
        if (value.startsWith("@dimen/")) {
            String dimenName = value.substring("@dimen/".length());
            return dimenValueDp(readRes(dimensPath), dimenName);
        }
        return dpValue(value);
    }

    private static int dimenValueDp(String dimensXml, String name) {
        Matcher m = Pattern.compile(
                "<dimen\\s+name=\"" + name + "\">([^<]+)</dimen>").matcher(dimensXml);
        if (!m.find()) {
            throw new AssertionError("FAILED: dimens.xml has no <dimen name=\"" + name + "\">");
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

    private static String readRes(String relativePath) throws IOException {
        Path path = resDir().resolve(relativePath);
        if (!Files.exists(path)) {
            throw new AssertionError("FAILED: resource file not found: " + path);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path resDir() {
        String repoRoot = System.getProperty("root.padding.test.repoRoot", ".");
        return Paths.get(repoRoot, "app", "src", "main", "res");
    }
}
