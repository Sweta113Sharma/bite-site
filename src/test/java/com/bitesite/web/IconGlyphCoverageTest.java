package com.bitesite.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The icon font is subset to the glyphs this app draws — 3.98MB of ~3,000 icons became
 * ~377KB of 62. That trade only holds while the subset and the templates agree.
 *
 * <p>The failure mode is quiet and ugly: Material Symbols picks a glyph by ligature, so
 * the element's text IS the icon name. A name with no glyph in the font does not render
 * as a blank or a box — it renders as the word. Adding {@code <span
 * class="material-symbols-outlined">thumb_up</span>} without re-subsetting puts the
 * literal text "thumb_up" on the page.
 *
 * <p>So this test reads every icon name the templates and stylesheets reference and
 * asserts each one is in the manifest the subsetting step wrote. To add an icon: add it
 * to {@code static/fonts/material-symbols.glyphs.txt} and re-run the subsetting script
 * (documented in README) so the glyph is actually in the font.
 *
 * <p>It cannot prove the glyph is inside the .woff2 — that needs a font parser Java does
 * not have here — so the manifest is the contract, and the browser-side check in the
 * repo's screenshot harness verifies the font itself.
 */
class IconGlyphCoverageTest {

    /** <span class="material-symbols-outlined …">icon_name</span> */
    private static final Pattern LITERAL = Pattern.compile(
            "<span class=\"material-symbols-outlined[^\"]*\"[^>]*>([a-z_]+)</span>");

    /** th:text="cond ? 'icon_a' : 'icon_b'" on an icon span, and the medallion helper. */
    private static final Pattern TERNARY = Pattern.compile(
            "material-symbols-outlined[^>]*th:text=\"[^\"]*?'([a-z_]+)'[^\"]*?'([a-z_]+)'[^\"]*\"");

    private static final Pattern MEDALLION = Pattern.compile("medallion\\('([a-z_]+)'");

    /** The order-status fragment resolves an icon name through a nested ternary. */
    private static final Pattern ICON_VAR = Pattern.compile("icon=\\$\\{[^}]*}", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("'([a-z_]+)'");

    /** CSS pseudo-element icons, e.g. .deal-card-add::before { content: 'add'; } */
    private static final Pattern CSS_CONTENT = Pattern.compile("content: '([a-z_]+)'");

    private static Set<String> referencedIcons() throws IOException {
        Set<String> icons = new LinkedHashSet<>();
        Path resources = Path.of("src/main/resources");

        try (Stream<Path> files = Files.walk(resources)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString();
                boolean template = name.endsWith(".html");
                boolean stylesheet = name.endsWith(".css");
                if (!template && !stylesheet) {
                    continue;
                }
                String text = Files.readString(f, StandardCharsets.UTF_8);

                if (stylesheet) {
                    Matcher m = CSS_CONTENT.matcher(text);
                    while (m.find()) {
                        icons.add(m.group(1));
                    }
                    continue;
                }

                Matcher m = LITERAL.matcher(text);
                while (m.find()) {
                    icons.add(m.group(1));
                }
                m = TERNARY.matcher(text);
                while (m.find()) {
                    icons.add(m.group(1));
                    icons.add(m.group(2));
                }
                m = MEDALLION.matcher(text);
                while (m.find()) {
                    icons.add(m.group(1));
                }
                m = ICON_VAR.matcher(text);
                while (m.find()) {
                    Matcher q = QUOTED.matcher(m.group());
                    while (q.find()) {
                        icons.add(q.group(1));
                    }
                }
            }
        }
        return icons;
    }

    private static Set<String> subsettedGlyphs() throws IOException {
        String manifest = new String(
                new ClassPathResource("static/fonts/material-symbols.glyphs.txt").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        Set<String> glyphs = new TreeSet<>();
        for (String line : manifest.split("\\R")) {
            String g = line.trim();
            if (!g.isEmpty()) {
                glyphs.add(g);
            }
        }
        return glyphs;
    }

    @Test
    void everyIconTheUiReferencesIsInTheSubsettedFont() throws IOException {
        Set<String> referenced = referencedIcons();
        Set<String> available = subsettedGlyphs();

        assertThat(referenced)
                .as("the extraction patterns should be finding icons at all")
                .hasSizeGreaterThan(40);

        Set<String> missing = new TreeSet<>(referenced);
        missing.removeAll(available);

        assertThat(missing)
                .as("these icon names are used in the UI but are not in the font subset, so they "
                        + "will render as their literal text. Add them to "
                        + "static/fonts/material-symbols.glyphs.txt and re-run the subsetting step.")
                .isEmpty();
    }

    /**
     * The other direction is not a failure, only waste — every extra glyph is bytes on
     * every first page load. Kept as a warning rather than an assertion because the
     * extraction above is pattern-based and could miss a reference; failing the build on
     * a suspected-unused glyph would be the wrong trade.
     */
    @Test
    void reportsGlyphsInTheSubsetThatNothingSeemsToUse() throws IOException {
        Set<String> unused = new TreeSet<>(subsettedGlyphs());
        unused.removeAll(referencedIcons());
        if (!unused.isEmpty()) {
            System.out.println("[icon subset] no reference found for: " + String.join(", ", unused)
                    + " — safe to drop from material-symbols.glyphs.txt if genuinely unused.");
        }
        assertThat(true).isTrue();
    }
}
