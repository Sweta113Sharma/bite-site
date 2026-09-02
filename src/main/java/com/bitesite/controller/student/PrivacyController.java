package com.bitesite.controller.student;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.dao.UserDao;
import com.bitesite.model.User;
import com.bitesite.privacy.ConsentPurpose;
import com.bitesite.privacy.DataRequest;
import com.bitesite.privacy.PrivacyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/** The student's own privacy centre: what is held, what they agreed to, and how to act on it. */
@Controller
@RequestMapping("/student/privacy")
@RequiredArgsConstructor
public class PrivacyController {

    private final PrivacyService privacyService;
    private final UserDao userDao;
    private final ObjectMapper objectMapper;

    @GetMapping
    public String centre(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = principal.getUser();
        model.addAttribute("consents", privacyService.consentsFor(user.getId()));
        model.addAttribute("user", userDao.findById(user.getId()).orElse(user));
        model.addAttribute("policyVersion", PrivacyService.POLICY_VERSION);
        model.addAttribute("pageTitle", "Privacy");
        return "student/privacy";
    }

    /**
     * Downloads everything held about the caller.
     *
     * <p>Always the authenticated user — there is no id parameter, so there is nothing to
     * tamper with. Served as an attachment rather than rendered, because the point is that
     * the student ends up holding the file.
     */
    @GetMapping("/export")
    @ResponseBody
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AppUserPrincipal principal) throws Exception {
        User user = principal.getUser();
        Map<String, Object> data = privacyService.exportFor(user.getId(), user.getTenantId());
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bitesite-my-data.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @PostMapping("/notifications")
    public String notifications(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(defaultValue = "false") boolean orderUpdates,
            @RequestParam(defaultValue = "false") boolean marketing,
            RedirectAttributes redirectAttributes) {
        User user = principal.getUser();
        userDao.updateNotificationPreferences(user.getId(), orderUpdates, marketing);
        // The consent record and the preference column are kept in step: the column is what
        // the send path reads, the record is the evidence of what was agreed and when.
        privacyService.setConsent(user.getId(), ConsentPurpose.ORDER_NOTIFICATIONS, orderUpdates, user.getTenantId());
        privacyService.setConsent(user.getId(), ConsentPurpose.MARKETING, marketing, user.getTenantId());
        redirectAttributes.addFlashAttribute("privacyNotice", "Preferences saved.");
        return "redirect:/student/privacy";
    }

    @PostMapping("/requests")
    public String raiseRequest(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam DataRequest.Kind kind, @RequestParam(required = false) String note,
            RedirectAttributes redirectAttributes) {
        User user = principal.getUser();
        privacyService.raiseRequest(user.getId(), user.getTenantId(), kind, note);
        redirectAttributes.addFlashAttribute("privacyNotice",
                "Request received. We'll respond to it and you'll see the outcome here.");
        return "redirect:/student/privacy";
    }
}
