package com.bitesite.service;

import com.bitesite.dto.MenuImportResult;
import com.bitesite.dto.MenuItemForm;
import com.bitesite.exception.BusinessException;
import com.bitesite.model.Category;
import com.bitesite.model.MenuItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MenuImportServiceTest {

    @Mock private MenuService menuService;
    @Mock private CategoryService categoryService;

    private MenuImportService importService;

    private static final Long TENANT = 1L;
    private static final Long OUTLET = 10L;
    private static final Long ACTOR = 100L;

    @BeforeEach
    void setUp() {
        importService = new MenuImportService(menuService, categoryService);
        when(menuService.listForOutlet(OUTLET, TENANT)).thenReturn(List.of());
        when(categoryService.listForOutlet(OUTLET, TENANT)).thenReturn(List.of());
    }

    private MultipartFile csv(String body) {
        return new MockMultipartFile("file", "menu.csv", "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }

    private MenuImportResult preview(String body) {
        return importService.preview(csv(body), OUTLET, TENANT);
    }

    // ---- the format ------------------------------------------------------

    @Test
    void readsTheHappyPath() {
        MenuImportResult r = preview("""
                category,item,price
                Snacks,Samosa,20
                Beverages,Masala Chai,15.50
                """);

        assertThat(r.hasProblems()).isFalse();
        assertThat(r.getRows()).hasSize(2);
        assertThat(r.getRows().get(0).name()).isEqualTo("Samosa");
        assertThat(r.getRows().get(0).price()).isEqualByComparingTo("20.00");
        assertThat(r.getRows().get(1).price()).isEqualByComparingTo("15.50");
        assertThat(r.getNewCategories()).containsExactly("Snacks", "Beverages");
    }

    /** The first thing a real menu breaks: a comma inside an item name. */
    @Test
    void aQuotedFieldMayContainACommaAndAQuote() {
        MenuImportResult r = preview("""
                category,item,price
                Snacks,"Chicken Roll, Large",90
                Snacks,"The ""Big"" One",120
                """);

        assertThat(r.hasProblems()).isFalse();
        assertThat(r.getRows()).extracting(MenuImportResult.Row::name)
                .containsExactly("Chicken Roll, Large", "The \"Big\" One");
    }

    /** Excel writes a BOM. Left in place it makes the first header not match. */
    @Test
    void aByteOrderMarkFromExcelDoesNotBreakTheHeader() {
        MenuImportResult r = preview("﻿category,item,price\nSnacks,Samosa,20\n");

        assertThat(r.hasProblems()).isFalse();
        assertThat(r.getRows()).hasSize(1);
    }

    @Test
    void windowsLineEndingsAreRead() {
        MenuImportResult r = preview("category,item,price\r\nSnacks,Samosa,20\r\n");

        assertThat(r.hasProblems()).isFalse();
        assertThat(r.getRows()).hasSize(1);
    }

    @Test
    void columnsMayBeInAnyOrderAndTheOptionalOnesMayBeAbsent() {
        MenuImportResult r = preview("""
                Price,ITEM,category,daily_limit
                20,Samosa,Snacks,30
                """);

        assertThat(r.hasProblems()).isFalse();
        assertThat(r.getRows().get(0).name()).isEqualTo("Samosa");
        assertThat(r.getRows().get(0).dailyLimit()).isEqualTo(30);
    }

    /** Spreadsheets leave trailing empty rows behind; they are not 40 broken items. */
    @Test
    void trailingBlankRowsAreIgnored() {
        MenuImportResult r = preview("category,item,price\nSnacks,Samosa,20\n,,\n,,\n");

        assertThat(r.hasProblems()).isFalse();
        assertThat(r.getRows()).hasSize(1);
    }

    @Test
    void currencySymbolsAndThousandSeparatorsArePeeledOff() {
        MenuImportResult r = preview("""
                category,item,price
                Meals,Thali,"₹1,250.00"
                """);

        assertThat(r.hasProblems()).isFalse();
        assertThat(r.getRows().get(0).price()).isEqualByComparingTo("1250.00");
    }

    // ---- what it refuses --------------------------------------------------

    @Test
    void aMissingRequiredColumnIsReportedOnce() {
        MenuImportResult r = preview("category,item\nSnacks,Samosa\n");

        assertThat(r.getProblems()).hasSize(1);
        assertThat(r.getProblems().get(0).message()).contains("price");
        assertThat(r.getRows()).isEmpty();
    }

    @Test
    void everyBadRowIsReportedWithItsLineNumber() {
        MenuImportResult r = preview("""
                category,item,price
                Snacks,Samosa,twenty
                Snacks,,20
                Snacks,Vada,-5
                Snacks,Good One,30
                """);

        assertThat(r.getProblems()).extracting(MenuImportResult.Problem::line)
                .containsExactly(2, 3, 4);
        // The good row still parses: an admin should see every problem in one pass.
        assertThat(r.getRows()).extracting(MenuImportResult.Row::name).containsExactly("Good One");
    }

    @Test
    void theSameItemTwiceInOneFileIsRefusedRatherThanLetTheLastOneWin() {
        MenuImportResult r = preview("""
                category,item,price
                Snacks,Samosa,20
                Snacks,samosa,25
                """);

        assertThat(r.getProblems()).hasSize(1);
        assertThat(r.getProblems().get(0).message()).contains("line 2");
        assertThat(r.getRows()).hasSize(1);
    }

    @Test
    void aDiscountThatIsNotADiscountIsRefused() {
        MenuImportResult r = preview("""
                category,item,price,discount_price
                Snacks,Samosa,20,25
                """);

        assertThat(r.getProblems().get(0).message()).contains("not below");
    }

    @Test
    void bothKindsOfDiscountAtOnceIsRefused() {
        MenuImportResult r = preview("""
                category,item,price,discount_price,discount_percent
                Snacks,Samosa,20,15,10
                """);

        assertThat(r.getProblems().get(0).message()).contains("not both");
    }

    @Test
    void anOverlongNameIsRefusedRatherThanTruncatedByTheDatabase() {
        MenuImportResult r = preview("category,item,price\nSnacks," + "x".repeat(151) + ",20\n");

        assertThat(r.getProblems().get(0).message()).contains("151 characters");
    }

    /** A file with content but nothing in it — a sheet saved with only blank rows. */
    @Test
    void aFileOfBlankRowsSaysSoInsteadOfImportingNothingQuietly() {
        assertThat(preview("\n,,\n,,\n").getProblems().get(0).message()).contains("no rows");
    }

    /** A zero-byte upload is the same mistake as picking no file, and reads the same way. */
    @Test
    void aZeroByteFileIsTreatedAsNoFile() {
        assertThatThrownBy(() -> importService.preview(
                new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]), OUTLET, TENANT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Choose a CSV file");
    }

    @Test
    void noFileAtAllIsAMessageNotACrash() {
        assertThatThrownBy(() -> importService.preview(
                new MockMultipartFile("file", "x.csv", "text/csv", new byte[0]), OUTLET, TENANT))
                .isInstanceOf(BusinessException.class);
    }

    // ---- preview writes nothing -------------------------------------------

    @Test
    void previewTouchesNothing() {
        preview("category,item,price\nSnacks,Samosa,20\n");

        verify(menuService, never()).create(anyLong(), anyLong(), any(), any(), anyLong());
        verify(categoryService, never()).create(anyLong(), anyLong(), any(), anyLong());
    }

    // ---- applying ----------------------------------------------------------

    @Test
    void applyCreatesTheCategoryOnceAndThenTheItems() {
        when(categoryService.create(eq(TENANT), eq(OUTLET), eq("Snacks"), eq(ACTOR)))
                .thenReturn(Category.builder().id(7L).name("Snacks").build());

        MenuImportResult r = importService.apply(csv("""
                category,item,price
                Snacks,Samosa,20
                Snacks,Vada,25
                """), OUTLET, TENANT, ACTOR);

        assertThat(r.isApplied()).isTrue();
        assertThat(r.getCreateCount()).isEqualTo(2);
        // One category for two items, not one per row.
        verify(categoryService).create(TENANT, OUTLET, "Snacks", ACTOR);
        verify(menuService, org.mockito.Mockito.times(2))
                .create(eq(TENANT), eq(OUTLET), any(MenuItemForm.class), isNull(), eq(ACTOR));
    }

    /**
     * The property that lets an admin fix a typo by re-uploading, and the one that keeps
     * the outlet's photos: an existing item is updated, and updated with a null photo.
     */
    @Test
    void anExistingItemIsUpdatedInPlaceAndKeepsItsPhoto() {
        MenuItem existing = MenuItem.builder().id(55L).name("Samosa").price(new BigDecimal("20.00"))
                .photoPath("/uploads/menu-photos/samosa.jpg").build();
        when(menuService.listForOutlet(OUTLET, TENANT)).thenReturn(List.of(existing));
        when(categoryService.listForOutlet(OUTLET, TENANT))
                .thenReturn(List.of(Category.builder().id(7L).name("Snacks").build()));

        MenuImportResult r = importService.apply(csv("""
                category,item,price
                Snacks,Samosa,25
                """), OUTLET, TENANT, ACTOR);

        assertThat(r.getCreateCount()).isZero();
        assertThat(r.getUpdateCount()).isEqualTo(1);
        assertThat(r.getPriceChangeCount()).isEqualTo(1);
        verify(menuService, never()).create(anyLong(), anyLong(), any(), any(), anyLong());

        ArgumentCaptor<MenuItemForm> form = ArgumentCaptor.forClass(MenuItemForm.class);
        verify(menuService).update(eq(55L), eq(TENANT), form.capture(), isNull(), eq(ACTOR));
        assertThat(form.getValue().getPrice()).isEqualByComparingTo("25.00");
        // Null photo and removePhoto false is what makes MenuService keep the old path.
        assertThat(form.getValue().isRemovePhoto()).isFalse();
    }

    @Test
    void anExistingCategoryIsReusedRatherThanDuplicated() {
        when(categoryService.listForOutlet(OUTLET, TENANT))
                .thenReturn(List.of(Category.builder().id(7L).name("snacks").build()));

        MenuImportResult r = importService.apply(csv("category,item,price\nSnacks,Samosa,20\n"),
                OUTLET, TENANT, ACTOR);

        assertThat(r.getNewCategories()).isEmpty();
        verify(categoryService, never()).create(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void aFileWithProblemsWritesNothingAtAll() {
        MenuImportResult r = importService.apply(csv("""
                category,item,price
                Snacks,Samosa,20
                Snacks,Broken,notanumber
                """), OUTLET, TENANT, ACTOR);

        assertThat(r.isApplied()).isFalse();
        assertThat(r.hasProblems()).isTrue();
        verify(menuService, never()).create(anyLong(), anyLong(), any(), any(), anyLong());
        verify(categoryService, never()).create(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void theTemplateIsItselfAValidFile() {
        MenuImportResult r = preview(MenuImportService.templateCsv());

        assertThat(r.hasProblems()).isFalse();
        assertThat(r.getRows()).hasSize(4);
        assertThat(r.getRows()).extracting(MenuImportResult.Row::name)
                .contains("Chicken Roll, Large");
    }
}
