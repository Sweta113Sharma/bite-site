package com.bitesite.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service worker installs by calling {@code cache.addAll(PRECACHE_URLS)}, and
 * {@code addAll} rejects the whole batch if any single URL 404s. One stale path therefore
 * disables offline support for every user, silently, in the browser — nothing fails
 * server-side and no log line appears.
 *
 * <p>That had already happened: the list went on naming {@code 03-app.css},
 * {@code 04-shared.css} and {@code 05-outlet.css} after those files stopped being linked,
 * and would have started failing outright the moment they were deleted. This test turns
 * that class of bug into a build failure.
 */
class ServiceWorkerPrecacheTest {

    private static final Pattern ARRAY = Pattern.compile("const PRECACHE_URLS = \\[(.*?)];", Pattern.DOTALL);
    private static final Pattern URL = Pattern.compile("'([^']+)'");

    private static String read(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Resolves the URL the service worker would request to the file that must back it. */
    private static String staticPathFor(String url) {
        return "static" + url;
    }

    @Test
    void everyPrecachedUrlResolvesToAFileThatExists() throws IOException {
        String sw = read("static/sw.js");
        Matcher block = ARRAY.matcher(sw);
        assertThat(block.find())
                .as("PRECACHE_URLS array should be findable in sw.js")
                .isTrue();

        List<String> urls = new ArrayList<>();
        Matcher m = URL.matcher(block.group(1));
        while (m.find()) {
            urls.add(m.group(1));
        }
        // OFFLINE_URL is referenced by constant rather than literal, so it is not matched
        // by the quote pattern above — check it explicitly.
        urls.add("/offline.html");

        assertThat(urls).isNotEmpty();
        for (String url : urls) {
            assertThat(new ClassPathResource(staticPathFor(url)).exists())
                    .as("sw.js precaches %s but src/main/resources/%s does not exist — "
                            + "cache.addAll() would reject and offline support would break", url, staticPathFor(url))
                    .isTrue();
        }
    }

    /**
     * CSS and JS are served from content-hashed URLs, so a hardcoded path to one in the
     * precache list names something the pages never request. They are meant to be picked
     * up by the runtime cache-first handler instead.
     */
    @Test
    void thePrecacheListDoesNotNameHashedAssets() throws IOException {
        String sw = read("static/sw.js");
        Matcher block = ARRAY.matcher(sw);
        assertThat(block.find()).isTrue();

        assertThat(block.group(1))
                .as("CSS and JS are content-hashed; precaching them by literal path is stale by construction")
                .doesNotContain("/css/")
                .doesNotContain("/js/");
    }

    /**
     * Every stylesheet the layout links must exist. The link order in head.html is the
     * cascade, so a typo here is not a missing file so much as a silently different design.
     */
    @Test
    void everyStylesheetLinkedByTheLayoutExists() throws IOException {
        String head = read("templates/fragments/head.html");
        Matcher m = Pattern.compile("@\\{(/css/[^}]+)}").matcher(head);

        List<String> linked = new ArrayList<>();
        while (m.find()) {
            linked.add(m.group(1));
        }

        assertThat(linked).as("head.html should link the ordered stylesheet set").isNotEmpty();
        for (String href : linked) {
            assertThat(new ClassPathResource(staticPathFor(href)).exists())
                    .as("head.html links %s which does not exist", href)
                    .isTrue();
        }
    }
}
