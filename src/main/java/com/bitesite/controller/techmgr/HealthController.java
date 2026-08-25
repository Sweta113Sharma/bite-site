package com.bitesite.controller.techmgr;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
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

        // healthForPath, not health(): the aggregate omits its components entirely
        // under show-details: when-authorized, so reaching into it returns nothing.
        // Payments is called out by name because "can we take money" is not something
        // an operator should have to read JSON to find out.
        HealthComponent payments = healthEndpoint.healthForPath("payments");
        if (payments instanceof Health p) {
            // Deliberately the "ready" detail and not the status: the indicator always
            // reports UP so it cannot fail the readiness probe (see PaymentsHealthIndicator).
            model.addAttribute("paymentsUp", Boolean.TRUE.equals(p.getDetails().get("ready")));
            model.addAttribute("paymentsDetails", p.getDetails());
        }

        model.addAttribute("pageTitle", "System health");
        return "techmgr/health";
    }
}
