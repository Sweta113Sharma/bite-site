package com.bitesite.controller.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Is this session still good?" — the smallest possible answer.
 *
 * <p>Exists for one caller: the {@code pageshow} handler in app.js, which runs when a page
 * is restored from the browser's back/forward cache. That restore deliberately does not
 * ask the server (it is what makes Back instant), so a page belonging to a student who has
 * since logged out could otherwise reappear on a shared phone. The handler asks here
 * instead, and reloads if the answer is anything other than 204.
 *
 * <p>204 rather than a JSON body because there is nothing to say: the status IS the answer,
 * and an empty response is a couple of hundred bytes against the full page re-render that
 * {@code no-store} used to force on every single Back.
 *
 * <p>When the session has gone, this never reaches the method at all — the security chain
 * redirects to the login page, {@code fetch} follows it, and the caller sees a 200 carrying
 * HTML. Hence the client checks for exactly 204 rather than for {@code response.ok}.
 */
@RestController
@RequestMapping("/api")
public class SessionApiController {

    @GetMapping("/session")
    public ResponseEntity<Void> session() {
        return ResponseEntity.noContent().build();
    }
}
