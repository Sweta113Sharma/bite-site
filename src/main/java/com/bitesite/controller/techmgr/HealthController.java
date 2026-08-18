package com.bitesite.controller.techmgr;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class HealthController {

    private final HealthEndpoint healthEndpoint;

    @GetMapping("/techmgr/health")
    public String health(Model model) {
        HealthComponent health = healthEndpoint.health();
        model.addAttribute("status", health.getStatus().getCode());
        model.addAttribute("up", health.getStatus().getCode().equals("UP"));
        model.addAttribute("pageTitle", "System health");
        return "techmgr/health";
    }
}
