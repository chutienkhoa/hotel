package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.common.response.AdditionalRevenueCategoryShare;
import com.example.hotel.dto.common.response.MonthlyFinancialReport;
import com.example.hotel.dto.common.response.MonthlyHotelPerformanceReport;
import com.example.hotel.dto.common.response.MonthlyOccupancyReport;
import com.example.hotel.dto.common.response.MonthlyPerformanceComparison;
import com.example.hotel.dto.common.response.NonVndRoomRevenueWarning;
import com.example.hotel.dto.common.response.ReservationSourceShare;
import com.example.hotel.dto.common.response.RevenueTrendPoint;
import com.example.hotel.dto.common.response.RoomTypeOccupancy;
import com.example.hotel.entity.booking.BookingSource;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.ClassPathResource;

/** Structural tests of the generated PDF: one A4 page, key text in English and Vietnamese, overflow and zero-data behavior. */
class MonthlyHotelPerformancePdfRendererTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final Locale EN = Locale.forLanguageTag("en");
    private static final Locale VI = Locale.forLanguageTag("vi");

    private final MonthlyHotelPerformancePdfRenderer renderer = new MonthlyHotelPerformancePdfRenderer(messages());

    private static ResourceBundleMessageSource messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private static MonthlyFinancialReport financial(NonVndRoomRevenueWarning warning) {
        return new MonthlyFinancialReport(SEPTEMBER, SEPTEMBER.atDay(1), SEPTEMBER.plusMonths(1).atDay(1), "VND",
                n("1180000"), n("104000"), n("1284000"), n("356000"), n("928000"), n("72.27"), warning);
    }

    private static MonthlyOccupancyReport occupancy(BigDecimal rate, List<RoomTypeOccupancy> types, LocalDate end, LocalDate through) {
        return new MonthlyOccupancyReport(SEPTEMBER, SEPTEMBER.atDay(1), SEPTEMBER.plusMonths(1).atDay(1), SEPTEMBER.atDay(1),
                end, through, 2356, 3005, rate, types);
    }

    private static List<RoomTypeOccupancy> types(int count) {
        List<RoomTypeOccupancy> types = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            types.add(new RoomTypeOccupancy(UUID.randomUUID(), String.format("T%02d", index), "Type " + index, 10 * index, 30, n("33.33")));
        }
        return types;
    }

    private static List<AdditionalRevenueCategoryShare> categories(int count) {
        List<AdditionalRevenueCategoryShare> categories = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            categories.add(new AdditionalRevenueCategoryShare(UUID.randomUUID(), "C" + index, "Category " + index,
                    n(String.valueOf(1000 * (count - index + 1))), n("10.00")));
        }
        return categories;
    }

    private static MonthlyHotelPerformanceReport report(
            NonVndRoomRevenueWarning warning, MonthlyOccupancyReport occupancy, List<AdditionalRevenueCategoryShare> categories) {
        List<RevenueTrendPoint> trend = new ArrayList<>();
        for (int back = 5; back >= 0; back--) {
            trend.add(new RevenueTrendPoint(SEPTEMBER.minusMonths(back), n(String.valueOf(1_000_000 + (5 - back) * 100_000))));
        }
        List<ReservationSourceShare> sources = List.of(
                new ReservationSourceShare(BookingSource.DIRECT, 60, n("21.00")),
                new ReservationSourceShare(BookingSource.AGODA, 78, n("27.00")),
                new ReservationSourceShare(BookingSource.BOOKING_COM, 121, n("42.00")),
                new ReservationSourceShare(BookingSource.AIRBNB, 29, n("10.00")));
        return new MonthlyHotelPerformanceReport(SEPTEMBER, LocalDate.of(2026, 9, 30), financial(warning), occupancy,
                new MonthlyPerformanceComparison(n("12.3456"), n("4.1"), n("-15.8"), n("5.2")), trend, 288, sources, categories);
    }

    private static MonthlyHotelPerformanceReport standard() {
        return report(null, occupancy(n("78.40"), types(5), SEPTEMBER.plusMonths(1).atDay(1), SEPTEMBER.atEndOfMonth()), categories(5));
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** Confirms the output is exactly one A4 page. */
    @Test
    void shouldProduceOneA4Page() throws IOException {
        try (PDDocument document = Loader.loadPDF(renderer.render(standard(), EN))) {
            assertEquals(1, document.getNumberOfPages());
            PDRectangle box = document.getPage(0).getMediaBox();
            assertEquals(PDRectangle.A4.getWidth(), box.getWidth(), 0.5f);
            assertEquals(PDRectangle.A4.getHeight(), box.getHeight(), 0.5f);
        }
    }

    /** Confirms the English text: title, month, generated date, KPI values in VND (never JPY), sections and footer. */
    @Test
    void shouldContainEnglishKeyText() throws IOException {
        String text = text(renderer.render(standard(), EN));

        for (String expected : List.of("JERRY HOTEL", "Monthly Hotel Performance Report", "SEPTEMBER 2026", "Generated 30/09/2026",
                "TOTAL REVENUE", "TOTAL EXPENSES", "NET PROFIT", "OCCUPANCY", "VND 1,284,000", "VND 356,000", "VND 928,000",
                "78.4%", "+12.3% vs last month", "-15.8% vs last month", "+5.2 pp vs last month",
                "Revenue Trend", "Reservation Source", "Booking.com", "Sold room nights", "2,356", "Available nights", "3,005",
                "Reservations", "288", "Room Type Performance", "ROOM TYPE", "SOLD", "AVAILABLE",
                "Financial Summary", "Room Revenue", "Additional Revenue", "Total Revenue", "Operating Expenses",
                "Net Operating Profit", "VND 1,180,000", "VND 104,000", "Top Additional Revenue", "Category 1",
                "Apr", "Sep", "Page 1 of 1", "Hotel Management System")) {
            assertTrue(text.contains(expected), "missing: " + expected + "\n" + text);
        }
        assertFalse(text.contains("JPY"));
        assertFalse(text.contains("?"), "no unsupported glyph placeholders");
    }

    /** Confirms Vietnamese labels are extracted with all diacritics intact. */
    @Test
    void shouldContainVietnameseKeyText() throws IOException {
        String text = text(renderer.render(standard(), VI));

        for (String expected : List.of("Báo cáo hiệu suất khách sạn tháng", "THÁNG 9 2026", "Ngày tạo 30/09/2026",
                "TỔNG DOANH THU", "TỔNG CHI PHÍ", "LỢI NHUẬN RÒNG", "CÔNG SUẤT PHÒNG", "so với tháng trước", "Xu hướng doanh thu",
                "Nguồn đặt phòng", "Trực tiếp", "Hiệu suất theo loại phòng", "LOẠI PHÒNG", "ĐÃ BÁN", "KHẢ DỤNG",
                "Tóm tắt tài chính", "Doanh thu bổ sung", "Chi phí vận hành", "Lợi nhuận hoạt động ròng",
                "Doanh thu bổ sung nổi bật", "VND 1,284,000", "Trang 1/1")) {
            assertTrue(text.contains(expected), "missing: " + expected + "\n" + text);
        }
        assertFalse(text.contains("JPY"));
        assertFalse(text.contains("?"), "no unsupported glyph placeholders");
    }

    /** Confirms the current month shows the completed-night cutoff, while a completed month does not. */
    @Test
    void shouldShowDataThroughOnlyForPartialMonth() throws IOException {
        MonthlyHotelPerformanceReport current = report(null,
                occupancy(n("78.40"), types(5), LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 19)), categories(5));

        assertTrue(text(renderer.render(current, EN)).contains("Data through 19/09/2026"));
        assertTrue(text(renderer.render(current, VI)).contains("Dữ liệu đến hết 19/09/2026"));
        assertFalse(text(renderer.render(standard(), EN)).contains("Data through"));
        MonthlyHotelPerformanceReport firstDay = report(null,
                occupancy(null, List.of(), LocalDate.of(2026, 9, 1), null), List.of());
        assertTrue(text(renderer.render(firstDay, EN)).contains("No completed nights yet"));
    }

    /** Confirms more room types than the V3 capacity still give one page, with an Others row that keeps the totals. */
    @Test
    void shouldFoldRoomTypeOverflowIntoOthersOnOnePage() throws IOException {
        byte[] pdf = renderer.render(report(null, occupancy(n("50.00"), types(9), SEPTEMBER.plusMonths(1).atDay(1), SEPTEMBER.atEndOfMonth()),
                categories(5)), EN);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());
        }
        String text = text(pdf);
        assertTrue(text.contains("Type 4"));
        assertFalse(text.contains("Type 5"));
        assertTrue(text.contains("Others"));
        assertTrue(text.contains("350"), "Others sold nights = 50+60+70+80+90");
    }

    /** Confirms Top 4 + Others: with more than four categories the rest are summed, with four or fewer no Others row appears. */
    @Test
    void shouldShowTopFourPlusOthersOnlyWhenMoreThanFourCategories() throws IOException {
        String many = text(renderer.render(report(null, occupancy(n("50.00"), types(5), SEPTEMBER.plusMonths(1).atDay(1), null), categories(9)), EN));
        assertTrue(many.contains("Category 4"));
        assertFalse(many.contains("Category 5"));
        assertTrue(many.contains("VND 15,000"), "Others = 5000+4000+3000+2000+1000");

        String four = text(renderer.render(report(null, occupancy(n("50.00"), types(5), SEPTEMBER.plusMonths(1).atDay(1), null), categories(4)), EN));
        assertTrue(four.contains("Category 4"));
        assertFalse(four.contains("Others"), "no fake Others row");
    }

    /** Confirms the non-VND warning is rendered (localized) without a second page. */
    @Test
    void shouldRenderNonVndWarningOnTheSamePage() throws IOException {
        NonVndRoomRevenueWarning warning = new NonVndRoomRevenueWarning(2, 3, List.of("EUR", "USD"));
        MonthlyOccupancyReport occupancy = occupancy(n("78.40"), types(5), SEPTEMBER.plusMonths(1).atDay(1), null);
        byte[] pdf = renderer.render(report(warning, occupancy, categories(5)), EN);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());
        }
        assertTrue(text(pdf).contains("not converted"), text(pdf));
        assertTrue(text(pdf).contains("EUR, USD"));
        assertTrue(text(renderer.render(report(warning, occupancy, categories(5)), VI)).contains("không được quy đổi"));
        assertFalse(text(renderer.render(standard(), EN)).contains("not converted"));
    }

    /** Confirms very long names and a very long warning are truncated, never spilling to another page. */
    @Test
    void shouldTruncateLongTextWithoutAddingPages() throws IOException {
        String longName = "Extraordinarily long category name ".repeat(12);
        List<AdditionalRevenueCategoryShare> categories = List.of(
                new AdditionalRevenueCategoryShare(UUID.randomUUID(), "C1", longName, n("1000"), n("100.00")));
        List<String> currencies = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            currencies.add("XX" + index);
        }
        byte[] pdf = renderer.render(report(new NonVndRoomRevenueWarning(99, 99, currencies),
                occupancy(n("50.00"), List.of(new RoomTypeOccupancy(UUID.randomUUID(), "L", longName, 1, 2, n("50.00"))),
                        SEPTEMBER.plusMonths(1).atDay(1), null), categories), VI);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());
        }
        assertTrue(text(pdf).contains("…"), "truncation is indicated with an ellipsis");
    }

    /** Confirms characters missing from the font degrade to a placeholder instead of failing the export. */
    @Test
    void shouldSurviveUnsupportedCharactersInBusinessData() throws IOException {
        String name = "Spa " + new String(Character.toChars(0x4e2d)) + " " + (char) 7;
        List<AdditionalRevenueCategoryShare> categories = List.of(
                new AdditionalRevenueCategoryShare(UUID.randomUUID(), "C1", name, n("1000"), n("100.00")));

        byte[] pdf = renderer.render(report(null, occupancy(n("50.00"), types(5), SEPTEMBER.plusMonths(1).atDay(1), null), categories), EN);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());
        }
    }

    /** Confirms a zero-data supported month is a valid one-page PDF with zeros and N/A, without fabricated rows. */
    @Test
    void shouldRenderZeroDataMonth() throws IOException {
        MonthlyFinancialReport zero = new MonthlyFinancialReport(SEPTEMBER, SEPTEMBER.atDay(1), SEPTEMBER.plusMonths(1).atDay(1), "VND",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        List<RevenueTrendPoint> trend = new ArrayList<>();
        for (int back = 5; back >= 0; back--) {
            trend.add(new RevenueTrendPoint(SEPTEMBER.minusMonths(back), BigDecimal.ZERO));
        }
        List<ReservationSourceShare> sources = new ArrayList<>();
        for (BookingSource source : BookingSource.values()) {
            sources.add(new ReservationSourceShare(source, 0, new BigDecimal("0.00")));
        }
        MonthlyHotelPerformanceReport report = new MonthlyHotelPerformanceReport(SEPTEMBER, LocalDate.of(2026, 9, 30), zero,
                new MonthlyOccupancyReport(SEPTEMBER, SEPTEMBER.atDay(1), SEPTEMBER.plusMonths(1).atDay(1), SEPTEMBER.atDay(1),
                        SEPTEMBER.plusMonths(1).atDay(1), SEPTEMBER.atEndOfMonth(), 0, 0, null, List.of()),
                new MonthlyPerformanceComparison(null, null, null, null), trend, 0, sources, List.of());

        byte[] pdf = renderer.render(report, EN);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());
        }
        String text = text(pdf);
        assertTrue(text.contains("VND 0"));
        assertTrue(text.contains("N/A"));
        assertTrue(text.contains("No additional revenue recorded"));
        assertTrue(text.contains("No room types to show"));
        assertTrue(text.contains("0%"));
    }

    /** Confirms the same dataset and locale render identical page content (only the trailer document ID differs). */
    @Test
    void shouldBeDeterministic() throws IOException {
        assertEquals(text(renderer.render(standard(), EN)), text(renderer.render(standard(), EN)));
        assertEquals(text(renderer.render(standard(), VI)), text(renderer.render(standard(), VI)));
    }

    /** Confirms the bundled fonts and their OFL licence are packaged, and the fonts cover every Vietnamese letter. */
    @Test
    void shouldBundleVietnameseCapableFontsWithLicence() throws IOException {
        String vietnamese = "aăâbcdđeêghiklmnoôơpqrstuưvxyAĂÂBCDĐEÊGHIKLMNOÔƠPQRSTUƯVXY"
                + "àáảãạằắẳẵặầấẩẫậèéẻẽẹềếểễệìíỉĩịòóỏõọồốổỗộờớởỡợùúủũụừứửữựỳýỷỹỵ₫";
        for (String font : List.of("fonts/NotoSans-Regular.ttf", "fonts/NotoSans-Bold.ttf")) {
            try (InputStream input = new ClassPathResource(font).getInputStream();
                    TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(input))) {
                var lookup = ttf.getUnicodeCmapLookup();
                vietnamese.codePoints().forEach(codePoint ->
                        assertTrue(lookup.getGlyphId(codePoint) != 0, font + " lacks U+" + Integer.toHexString(codePoint)));
            }
        }
        try (InputStream license = new ClassPathResource("fonts/OFL.txt").getInputStream()) {
            assertTrue(new String(license.readAllBytes()).contains("SIL OPEN FONT LICENSE Version 1.1"));
        }
    }
}
