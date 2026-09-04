package com.bitesite.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two mobile properties that fail silently on a desktop browser and are invisible in every
 * test that renders HTML, because both only misbehave on a real phone.
 */
class MobileViewportTest {

    private static String read(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String allStylesheets() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/static/css/parts"))) {
            StringBuilder all = new StringBuilder();
            for (Path f : files.filter(p -> p.toString().endsWith(".css")).toList()) {
                all.append(Files.readString(f, StandardCharsets.UTF_8));
            }
            return all.toString();
        }
    }

    /**
     * env(safe-area-inset-*) returns zero unless the viewport opts in with viewport-fit=cover.
     * The stylesheets lean on it in a dozen-odd places to keep the tab bar and the sticky
     * cart bar off the home indicator; drop the attribute and all of them quietly compute
     * with a zero and the bar sits under the indicator on every notched iPhone.
     */
    @Test
    void theViewportOptsInToSafeAreaInsets() throws IOException {
        String head = read("templates/fragments/head.html");

        assertThat(head)
                .as("safe-area insets are dead without viewport-fit=cover")
                .contains("viewport-fit=cover");
    }

    @Test
    void safeAreaInsetsAreActuallyRelliedOn() throws IOException {
        assertThat(allStylesheets())
                .as("if nothing reads the inset any more, the viewport attribute is the "
                        + "thing to remove — not this test")
                .contains("safe-area-inset-bottom");
    }

    /**
     * iOS Safari zooms the page when a field smaller than 16px takes focus, and does not
     * zoom back out. Anything that ships a smaller size to a touch device makes the layout
     * jump the first time somebody types.
     */
    @Test
    void touchDevicesGetSixteenPixelFields() throws IOException {
        String css = allStylesheets();

        int coarse = css.indexOf("@media (pointer: coarse)");
        assertThat(coarse).as("a coarse-pointer block should set field sizing").isNotNegative();

        String block = css.substring(coarse, Math.min(coarse + 900, css.length()));
        assertThat(block)
                .as("fields under 16px make iOS zoom on focus and stay zoomed")
                .contains("font-size: 16px");
        assertThat(List.of(".form-control", ".form-select"))
                .allSatisfy(selector -> assertThat(block).contains(selector));
    }
}
