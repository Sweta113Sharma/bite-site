package com.bitesite.controller.admin;

import com.bitesite.config.AppUserPrincipal;
import com.bitesite.exception.BusinessException;
import com.bitesite.service.CategoryImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Platform artwork for menu categories.
 *
 * <p>The screen answers one question: which category names are canteens actually using
 * that nobody has drawn a picture for? A canteen inventing "Midnight Maggi" shows up here
 * on its own, and one upload covers every outlet that ever uses that name — including
 * colleges onboarded later.
 *
 * <p>An outlet can always override with its own image; this is the floor, not the ceiling.
 * Deliberately not tenant-scoped, which is why it lives behind {@code /admin/**} and the
 * platform roles that guards.
 */
@Controller
@RequestMapping("/admin/category-images")
@RequiredArgsConstructor
public class CategoryImageController {

    private final CategoryImageService categoryImageService;

    @GetMapping
    public String list(Model model) {
        // Two lists on purpose: what still needs attention, and what has already been set
        // (so a wrong picture can be found and replaced without hunting for a canteen
        // that uses it).
        model.addAttribute("needsImage", categoryImageService.listCategoriesNeedingImage());
        model.addAttribute("defaults", categoryImageService.listDefaults());
        model.addAttribute("allDishesDefault", categoryImageService.defaultAllDishesImage());
        model.addAttribute("pageTitle", "Category images");
        return "admin/category-images";
    }

    @PostMapping
    public String upload(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam String categoryName,
            @RequestParam("image") MultipartFile image,
            RedirectAttributes redirectAttributes) {
        if (categoryName == null || categoryName.isBlank()) {
            redirectAttributes.addFlashAttribute("imageError", "Category name is required.");
            return "redirect:/admin/category-images";
        }
        if (image == null || image.isEmpty()) {
            redirectAttributes.addFlashAttribute("imageError", "Choose an image first.");
            return "redirect:/admin/category-images";
        }
        try {
            categoryImageService.setDefaultImage(categoryName, principal.getUser().getId(), image);
            redirectAttributes.addFlashAttribute("imageNotice",
                    "Default image set for \"" + categoryName.trim() + "\".");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("imageError", e.getMessage());
        }
        return "redirect:/admin/category-images";
    }

    /**
     * The platform's picture for the "All Dishes" chip, which every canteen shows first on
     * its menu. Not a category name, so it has its own route rather than a reserved name.
     */
    @PostMapping("/all-dishes")
    public String uploadAllDishes(@RequestParam("image") MultipartFile image,
            RedirectAttributes redirectAttributes) {
        if (image == null || image.isEmpty()) {
            redirectAttributes.addFlashAttribute("imageError", "Choose an image first.");
            return "redirect:/admin/category-images";
        }
        try {
            categoryImageService.setDefaultAllDishesImage(image);
            redirectAttributes.addFlashAttribute("imageNotice", "Default image set for \"All Dishes\".");
        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("imageError", e.getMessage());
        }
        return "redirect:/admin/category-images";
    }

    @PostMapping("/all-dishes/remove")
    public String removeAllDishes(RedirectAttributes redirectAttributes) {
        categoryImageService.clearDefaultAllDishesImage();
        redirectAttributes.addFlashAttribute("imageNotice",
                "Default image removed for \"All Dishes\". Canteens without their own show the bowl illustration.");
        return "redirect:/admin/category-images";
    }

    @PostMapping("/remove")
    public String remove(@RequestParam String categoryName, RedirectAttributes redirectAttributes) {
        categoryImageService.clearDefaultImage(categoryName);
        // Outlets that uploaded their own keep theirs; only the shared fallback goes.
        redirectAttributes.addFlashAttribute("imageNotice",
                "Default image removed for \"" + categoryName.trim() + "\".");
        return "redirect:/admin/category-images";
    }
}
