package com.bitesite.web;

import com.bitesite.config.TenantContext;
import com.bitesite.tenant.Tenant;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Makes the subdomain-resolved tenant available to every Thymeleaf view as {@code currentTenant}. */
@ControllerAdvice
public class GlobalModelAttributes {

    @ModelAttribute("currentTenant")
    public Tenant currentTenant() {
        return TenantContext.get();
    }
}
