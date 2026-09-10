package com.bitesite.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The show/hide eye on a password field is drawn by markup in one file and made to work by
 * {@code password-toggle.js} in another, and nothing connected the two.
 *
 * <p>{@code account/change-password.html} carried the button and loaded no script at all,
 * so the eye there did nothing from the day it was added. It looks completely fine: the
 * button is styled, it has a hover state, it takes the click. It simply never toggled
 * anything, and the only way to find out was to press it on that one page.
 *
 * <p>This is a wiring contract, not a style rule. Any page that draws the button has to
 * load the behaviour.
 */
class PasswordToggleWiringTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    private static List<Path> templatesContaining(String needle) throws IOException {
        List<Path> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                if (!f.getFileName().toString().endsWith(".html")) {
                    continue;
                }
                if (Files.readString(f, StandardCharsets.UTF_8).contains(needle)) {
                    hits.add(f);
                }
            }
        }
        return hits;
    }

    @Test
    void everyPageWithAPasswordEyeAlsoLoadsTheScriptThatMakesItWork() throws IOException {
        List<String> broken = new ArrayList<>();
        for (Path f : templatesContaining("toggle-password")) {
            if (!Files.readString(f, StandardCharsets.UTF_8).contains("password-toggle.js")) {
                broken.add(TEMPLATES.relativize(f).toString());
            }
        }

        assertThat(broken)
                .as("these templates draw a .toggle-password button but never load "
                        + "password-toggle.js, so pressing the eye does nothing at all")
                .isEmpty();
    }

    /**
     * A button nobody can reach with a keyboard is not a button. All five of these carried
     * {@code tabindex="-1"}, which takes them out of the tab order entirely — so a keyboard
     * or screen reader user could never reveal what they had typed.
     */
    @Test
    void thePasswordEyeIsReachableByKeyboardAndAnnouncesItsState() throws IOException {
        List<String> unreachable = new ArrayList<>();
        List<String> silent = new ArrayList<>();

        for (Path f : templatesContaining("toggle-password")) {
            String html = Files.readString(f, StandardCharsets.UTF_8);
            String name = TEMPLATES.relativize(f).toString();
            // Only the toggle's own attributes matter; look at the button element itself.
            for (String button : html.split("<button")) {
                if (!button.contains("toggle-password")) {
                    continue;
                }
                String tag = button.substring(0, Math.min(button.indexOf('>') + 1, button.length()));
                if (tag.contains("tabindex=\"-1\"")) {
                    unreachable.add(name);
                }
                if (!tag.contains("aria-pressed")) {
                    silent.add(name);
                }
            }
        }

        assertThat(unreachable)
                .as("tabindex=\"-1\" removes the password eye from the tab order, so a keyboard "
                        + "user cannot reveal what they typed")
                .isEmpty();
        assertThat(silent)
                .as("the eye is a toggle, so it needs aria-pressed for a screen reader to say "
                        + "whether the password is currently shown")
                .isEmpty();
    }
}
