package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.common.response.AdditionalRevenueCategoryShare;
import com.example.hotel.dto.common.response.MonthlyExpenseExportRow;
import com.example.hotel.dto.common.response.MonthlyFinancialReport;
import com.example.hotel.dto.common.response.MonthlyHotelPerformanceExcelData;
import com.example.hotel.dto.common.response.MonthlyHotelPerformanceReport;
import com.example.hotel.dto.common.response.MonthlyOccupancyReport;
import com.example.hotel.dto.common.response.MonthlyPaymentExportRow;
import com.example.hotel.dto.common.response.MonthlyPerformanceComparison;
import com.example.hotel.dto.common.response.MonthlyReservationExportRow;
import com.example.hotel.dto.common.response.NonVndRoomRevenueWarning;
import com.example.hotel.dto.common.response.ReservationSourceShare;
import com.example.hotel.dto.common.response.RevenueTrendPoint;
import com.example.hotel.dto.common.response.RoomTypeOccupancy;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.entity.booking.PaymentStatus;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.common.ExpenseStatus;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTLineSer;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.ClassPathResource;

/** Structural tests of the generated workbook against the approved template, read back with Apache POI. */
class MonthlyHotelPerformanceExcelRendererTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Locale EN = Locale.forLanguageTag("en");
    private static final Locale VI = Locale.forLanguageTag("vi");

    private final MonthlyHotelPerformanceExcelRenderer renderer = new MonthlyHotelPerformanceExcelRenderer(
            messages(), Clock.fixed(Instant.parse("2026-09-30T03:00:00Z"), ZONE));

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

    // ----- fixtures --------------------------------------------------------------------------------------

    private static List<RoomTypeOccupancy> types(int count) {
        List<RoomTypeOccupancy> types = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            types.add(new RoomTypeOccupancy(UUID.randomUUID(), String.format("T%02d", index), "Type " + index,
                    10 * index, 30, n("33.33")));
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
            NonVndRoomRevenueWarning warning, int typeCount, int categoryCount, boolean partial) {
        MonthlyFinancialReport financial = new MonthlyFinancialReport(SEPTEMBER, SEPTEMBER.atDay(1),
                SEPTEMBER.plusMonths(1).atDay(1), "VND", n("1180000"), n("104000"), n("1284000"), n("356000"),
                n("928000"), n("72.27"), warning);
        MonthlyOccupancyReport occupancy = new MonthlyOccupancyReport(SEPTEMBER, SEPTEMBER.atDay(1),
                SEPTEMBER.plusMonths(1).atDay(1), SEPTEMBER.atDay(1),
                partial ? LocalDate.of(2026, 9, 20) : SEPTEMBER.plusMonths(1).atDay(1),
                partial ? LocalDate.of(2026, 9, 19) : SEPTEMBER.atEndOfMonth(), 2356, 3005, n("78.40"), types(typeCount));
        List<RevenueTrendPoint> trend = new ArrayList<>();
        long[] totals = {780000, 920000, 860000, 1050000, 980000, 1284000};
        long[] rooms = {650000, 780000, 710000, 910000, 850000, 1180000};
        for (int index = 0; index < 6; index++) {
            trend.add(new RevenueTrendPoint(SEPTEMBER.minusMonths(5 - index), BigDecimal.valueOf(totals[index]),
                    BigDecimal.valueOf(rooms[index])));
        }
        List<ReservationSourceShare> sources = List.of(
                new ReservationSourceShare(BookingSource.DIRECT, 60, n("21.00")),
                new ReservationSourceShare(BookingSource.AGODA, 78, n("27.00")),
                new ReservationSourceShare(BookingSource.BOOKING_COM, 121, n("42.00")),
                new ReservationSourceShare(BookingSource.AIRBNB, 29, n("10.00")));
        return new MonthlyHotelPerformanceReport(SEPTEMBER, LocalDate.of(2026, 9, 30), financial, occupancy,
                new MonthlyPerformanceComparison(null, null, null, null), trend, 288, sources, categories(categoryCount));
    }

    private static MonthlyReservationExportRow reservation(String number, String guest, String ref, List<String> roomTypes) {
        return new MonthlyReservationExportRow(number, BookingSource.BOOKING_COM, ref, guest, LocalDate.of(2026, 9, 2),
                LocalDate.of(2026, 9, 5), roomTypes, n("32000"), "VND", ReservationStatus.CHECKED_OUT);
    }

    private static MonthlyPaymentExportRow payment(String number, PaymentStatus status, String amount, PaymentCurrency currency) {
        return new MonthlyPaymentExportRow(Instant.parse("2026-09-08T17:30:00Z"), number, "Maria Santos", PaymentMethod.CASH,
                "Front Desk", n(amount), currency, status);
    }

    private static MonthlyExpenseExportRow expense(String description) {
        return new MonthlyExpenseExportRow(LocalDate.of(2026, 9, 3), "Utilities", description, n("85000"),
                ExpenseStatus.POSTED, "admin");
    }

    private static MonthlyHotelPerformanceExcelData data(MonthlyHotelPerformanceReport report) {
        return new MonthlyHotelPerformanceExcelData(report, List.of(), List.of(), List.of());
    }

    private static MonthlyHotelPerformanceExcelData standard() {
        return new MonthlyHotelPerformanceExcelData(report(null, 5, 5, false),
                List.of(reservation("RSV-001", "Nguyen Van A", "4258392771", List.of("Double"))),
                List.of(payment("RSV-001", PaymentStatus.PAID, "32000", PaymentCurrency.VND)),
                List.of(expense("Electricity")));
    }

    private XSSFWorkbook open(MonthlyHotelPerformanceExcelData data, Locale locale) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(renderer.render(data, locale)));
    }

    private static XSSFWorkbook template() throws IOException {
        try (InputStream input = new ClassPathResource(MonthlyHotelPerformanceExcelRenderer.TEMPLATE_PATH).getInputStream()) {
            return new XSSFWorkbook(input);
        }
    }

    private static String string(Sheet sheet, int row, int column) {
        Row target = sheet.getRow(row);
        Cell cell = target == null ? null : target.getCell(column);
        return cell == null || cell.getCellType() == CellType.BLANK ? null : cell.getStringCellValue();
    }

    private static Double number(Sheet sheet, int row, int column) {
        Row target = sheet.getRow(row);
        Cell cell = target == null ? null : target.getCell(column);
        return cell == null || cell.getCellType() == CellType.BLANK ? null : cell.getNumericCellValue();
    }

    private static boolean isBlank(Sheet sheet, int row, int column) {
        Row target = sheet.getRow(row);
        Cell cell = target == null ? null : target.getCell(column);
        return cell == null || cell.getCellType() == CellType.BLANK;
    }

    // ----- structure -------------------------------------------------------------------------------------

    /** Confirms exactly the five approved sheets, in order, with fixed English names in both languages. */
    @Test
    void shouldKeepFiveEnglishSheetNamesInOrderForBothLocales() throws IOException {
        for (Locale locale : List.of(EN, VI)) {
            try (XSSFWorkbook workbook = open(standard(), locale)) {
                assertEquals(5, workbook.getNumberOfSheets());
                assertEquals(List.of("Monthly Summary", "Reservations", "Payments", "Expenses", "Occupancy"),
                        List.of(workbook.getSheetName(0), workbook.getSheetName(1), workbook.getSheetName(2),
                                workbook.getSheetName(3), workbook.getSheetName(4)));
            }
        }
    }

    /** Confirms merged regions, column widths, row heights and header/border styles equal the approved template. */
    @Test
    void shouldPreserveTemplateGeometryAndStyles() throws IOException {
        try (XSSFWorkbook template = template(); XSSFWorkbook output = open(standard(), EN)) {
            XSSFSheet expected = template.getSheet("Monthly Summary");
            XSSFSheet actual = output.getSheet("Monthly Summary");
            assertEquals(2, actual.getNumMergedRegions());
            assertEquals("A1:H1", actual.getMergedRegion(0).formatAsString());
            assertEquals("A2:H2", actual.getMergedRegion(1).formatAsString());
            for (int column = 0; column < 8; column++) {
                assertEquals(expected.getColumnWidth(column), actual.getColumnWidth(column), "column " + column);
            }
            for (int row = 3; row <= 29; row++) {
                if (expected.getRow(row) != null) {
                    assertEquals(expected.getRow(row).getHeightInPoints(), actual.getRow(row).getHeightInPoints(), "row " + row);
                }
            }
            assertEquals(24f, actual.getRow(0).getHeightInPoints());
            XSSFCellStyle header = actual.getRow(3).getCell(0).getCellStyle();
            XSSFCellStyle templateHeader = expected.getRow(3).getCell(0).getCellStyle();
            assertEquals(fill(templateHeader), fill(header));
            assertEquals(templateHeader.getBorderTop(), header.getBorderTop());
            assertEquals(templateHeader.getFont().getBold(), header.getFont().getBold());
            assertEquals("#,##0", actual.getRow(4).getCell(0).getCellStyle().getDataFormatString());
            assertEquals("0.0%", actual.getRow(4).getCell(3).getCellStyle().getDataFormatString());
            assertEquals("0%", actual.getRow(8).getCell(7).getCellStyle().getDataFormatString());
            for (String detail : List.of("Reservations", "Expenses", "Occupancy")) {
                assertEquals(template.getSheet(detail).getColumnWidth(0), output.getSheet(detail).getColumnWidth(0));
            }
        }
    }

    /** Confirms no formulas, external links, macros or drawings other than the approved chart are produced. */
    @Test
    void shouldContainNoFormulasLinksOrMacros() throws IOException {
        byte[] bytes = renderer.render(standard(), EN);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        assertTrue(cell.getCellType() != CellType.FORMULA, sheet.getSheetName() + "!" + cell.getAddress());
                    }
                }
            }
            assertTrue(workbook.getAllNames().isEmpty());
        }
        for (String name : entryNames(bytes)) {
            assertFalse(name.contains("externalLink"), name);
            assertFalse(name.contains("vbaProject"), name);
        }
    }

    /** Confirms no personal template metadata (Windows path, editor name) reaches the generated package. */
    @Test
    void shouldScrubPersonalMetadata() throws IOException {
        byte[] bytes = renderer.render(standard(), EN);
        StringBuilder all = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            while (zip.getNextEntry() != null) {
                all.append(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        assertFalse(all.toString().contains("KhoaCT1"));
        assertFalse(all.toString().contains("C:\\Users"));
        assertFalse(all.toString().contains("Khoa Chu"));
        assertFalse(all.toString().contains("absPath"));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals("Hotel Management System", workbook.getProperties().getCoreProperties().getLastModifiedByUser());
        }
    }

    /** Confirms the approved workbook is the only template copy (none is duplicated under docs/). */
    @Test
    void shouldKeepASingleTemplateCopy() {
        assertFalse(Files.exists(Path.of("docs/report-templates/hotel_monthly_report_excel_mockup_final.xlsx")));
        assertNotNull(getClass().getClassLoader().getResource(MonthlyHotelPerformanceExcelRenderer.TEMPLATE_PATH));
    }

    // ----- monthly summary -------------------------------------------------------------------------------

    /** Confirms English labels and every dynamic Monthly Summary value. */
    @Test
    void shouldPopulateMonthlySummaryInEnglish() throws IOException {
        try (XSSFWorkbook workbook = open(standard(), EN)) {
            Sheet sheet = workbook.getSheet("Monthly Summary");
            assertEquals("JERRY HOTEL \u2014 MONTHLY PERFORMANCE REPORT", string(sheet, 0, 0));
            assertEquals("September 2026", string(sheet, 1, 0));
            assertEquals(List.of("Total Revenue", "Total Expenses", "Net Profit", "Occupancy Rate", "Reservations",
                    "Sold Room Nights", "Available Nights", "Report Month"),
                    List.of(string(sheet, 3, 0), string(sheet, 3, 1), string(sheet, 3, 2), string(sheet, 3, 3),
                            string(sheet, 3, 4), string(sheet, 3, 5), string(sheet, 3, 6), string(sheet, 3, 7)));
            assertEquals(1284000d, number(sheet, 4, 0));
            assertEquals(356000d, number(sheet, 4, 1));
            assertEquals(928000d, number(sheet, 4, 2));
            assertEquals(0.784d, number(sheet, 4, 3), 1e-9);
            assertEquals(288d, number(sheet, 4, 4));
            assertEquals(2356d, number(sheet, 4, 5));
            assertEquals(3005d, number(sheet, 4, 6));
            assertEquals(LocalDate.of(2026, 9, 1), sheet.getRow(4).getCell(7).getLocalDateTimeCellValue().toLocalDate());
            assertEquals("Financial Summary", string(sheet, 7, 0));
            assertEquals("Amount", string(sheet, 7, 2));
            assertEquals(List.of("Room Revenue", "Additional Revenue", "Total Revenue", "Operating Expenses", "Net Operating Profit"),
                    List.of(string(sheet, 8, 0), string(sheet, 9, 0), string(sheet, 10, 0), string(sheet, 11, 0), string(sheet, 12, 0)));
            assertEquals(List.of(1180000d, 104000d, 1284000d, 356000d, 928000d),
                    List.of(number(sheet, 8, 2), number(sheet, 9, 2), number(sheet, 10, 2), number(sheet, 11, 2), number(sheet, 12, 2)));
            assertEquals("Reservation Source", string(sheet, 7, 5));
            assertEquals(List.of("Booking.com", "Agoda", "Direct", "Airbnb"),
                    List.of(string(sheet, 8, 5), string(sheet, 9, 5), string(sheet, 10, 5), string(sheet, 11, 5)));
            assertEquals(List.of(121d, 78d, 60d, 29d),
                    List.of(number(sheet, 8, 6), number(sheet, 9, 6), number(sheet, 10, 6), number(sheet, 11, 6)));
            assertEquals(0.42d, number(sheet, 8, 7), 1e-9);
            assertEquals("Room Type", string(sheet, 15, 0));
            assertEquals("Sold Nights", string(sheet, 15, 1));
            assertEquals("Available Nights", string(sheet, 15, 2));
            assertEquals("Occupancy", string(sheet, 15, 3));
            assertEquals("Type 1", string(sheet, 16, 0));
            assertEquals(10d, number(sheet, 16, 1));
            assertEquals(30d, number(sheet, 16, 2));
            assertEquals(0.3333d, number(sheet, 16, 3), 1e-9);
            assertEquals("Additional Revenue", string(sheet, 15, 5));
            assertEquals("Category 1", string(sheet, 16, 5));
            assertEquals(5000d, number(sheet, 16, 6));
            assertEquals("Month", string(sheet, 23, 0));
            assertEquals("Total Revenue", string(sheet, 23, 1));
            assertEquals("Room Revenue", string(sheet, 23, 2));
        }
    }

    /** Confirms the six-month trend table keeps A25:C30 in chronological order with both series. */
    @Test
    void shouldPopulateSixMonthTrendWithTwoSeries() throws IOException {
        try (XSSFWorkbook workbook = open(standard(), EN)) {
            Sheet sheet = workbook.getSheet("Monthly Summary");
            List<String> months = List.of("Apr", "May", "Jun", "Jul", "Aug", "Sep");
            double[] totals = {780000, 920000, 860000, 1050000, 980000, 1284000};
            double[] rooms = {650000, 780000, 710000, 910000, 850000, 1180000};
            for (int index = 0; index < 6; index++) {
                assertEquals(months.get(index), string(sheet, 24 + index, 0));
                assertEquals(totals[index], number(sheet, 24 + index, 1));
                assertEquals(rooms[index], number(sheet, 24 + index, 2));
            }
        }
    }

    /** Confirms Vietnamese labels inside the sheets while the sheet names stay English. */
    @Test
    void shouldPopulateVietnameseLabels() throws IOException {
        try (XSSFWorkbook workbook = open(standard(), VI)) {
            Sheet sheet = workbook.getSheet("Monthly Summary");
            assertEquals("JERRY HOTEL \u2014 B\u00c1O C\u00c1O HI\u1ec6U SU\u1ea4T TH\u00c1NG", string(sheet, 0, 0));
            assertEquals("T\u1ed5ng doanh thu", string(sheet, 3, 0));
            assertEquals("T\u00f3m t\u1eaft t\u00e0i ch\u00ednh", string(sheet, 7, 0));
            assertEquals("Tr\u1ef1c ti\u1ebfp", string(sheet, 10, 5));
            assertEquals("Ngu\u1ed3n", string(workbook.getSheet("Reservations"), 0, 1));
            assertEquals("Ph\u01b0\u01a1ng th\u1ee9c", string(workbook.getSheet("Payments"), 0, 3));
            assertEquals("Ng\u01b0\u1eddi t\u1ea1o", string(workbook.getSheet("Expenses"), 0, 5));
            assertEquals("Lo\u1ea1i ph\u00f2ng", string(workbook.getSheet("Occupancy"), 0, 0));
        }
    }

    /** Confirms the chart survives with unchanged references, and its literal title/series/caches follow the locale. */
    @Test
    void shouldPreserveAndLocalizeChart() throws IOException {
        for (Locale locale : List.of(EN, VI)) {
            try (XSSFWorkbook workbook = open(standard(), locale)) {
                var charts = workbook.getSheet("Monthly Summary").getDrawingPatriarch().getCharts();
                assertEquals(1, charts.size());
                XSSFChart chart = charts.get(0);
                assertEquals(locale == EN ? "Revenue Trend" : "Xu h\u01b0\u1edbng doanh thu", chart.getTitleText().getString());
                var line = chart.getCTChart().getPlotArea().getLineChartArray(0);
                assertEquals(2, line.sizeOfSerArray());
                CTLineSer total = line.getSerArray(0);
                CTLineSer room = line.getSerArray(1);
                assertEquals(locale == EN ? "Total Revenue" : "T\u1ed5ng doanh thu", total.getTx().getV());
                assertEquals(locale == EN ? "Room Revenue" : "Doanh thu ph\u00f2ng", room.getTx().getV());
                assertEquals("'Monthly Summary'!$A$25:$A$30", total.getCat().getStrRef().getF());
                assertEquals("'Monthly Summary'!$B$25:$B$30", total.getVal().getNumRef().getF());
                assertEquals("'Monthly Summary'!$A$25:$A$30", room.getCat().getStrRef().getF());
                assertEquals("'Monthly Summary'!$C$25:$C$30", room.getVal().getNumRef().getF());
                assertEquals("1284000", total.getVal().getNumRef().getNumCache().getPtArray(5).getV());
                assertEquals("1180000", room.getVal().getNumRef().getNumCache().getPtArray(5).getV());
                assertEquals(locale == EN ? "Apr" : "thg 4", total.getCat().getStrRef().getStrCache().getPtArray(0).getV());
                var anchor = (org.apache.poi.xssf.usermodel.XSSFClientAnchor) workbook.getSheet("Monthly Summary")
                        .getDrawingPatriarch().getShapes().get(0).getAnchor();
                assertEquals(4, anchor.getCol1());
                assertEquals(23, anchor.getRow1());
                assertEquals(8, anchor.getCol2());
                assertEquals(38, anchor.getRow2());
            }
        }
    }

    /** Confirms the Data Through note appears only for the current month, and A3 is clear otherwise. */
    @Test
    void shouldWriteDataThroughNoteOnlyForCurrentMonth() throws IOException {
        MonthlyHotelPerformanceExcelData current = data(report(null, 5, 5, true));
        try (XSSFWorkbook workbook = open(current, EN)) {
            assertEquals("Data through 19/09/2026", string(workbook.getSheet("Monthly Summary"), 2, 0));
        }
        try (XSSFWorkbook workbook = open(current, VI)) {
            assertEquals("D\u1eef li\u1ec7u \u0111\u1ebfn h\u1ebft 19/09/2026", string(workbook.getSheet("Monthly Summary"), 2, 0));
        }
        try (XSSFWorkbook workbook = open(standard(), EN)) {
            assertTrue(isBlank(workbook.getSheet("Monthly Summary"), 2, 0));
        }
    }

    /** Confirms the non-VND warning is written at A6, and A6 is clear without a warning. */
    @Test
    void shouldWriteNonVndWarningOnlyWhenPresent() throws IOException {
        NonVndRoomRevenueWarning warning = new NonVndRoomRevenueWarning(2, 3, List.of("EUR", "USD"));
        try (XSSFWorkbook workbook = open(data(report(warning, 5, 5, false)), EN)) {
            String note = string(workbook.getSheet("Monthly Summary"), 5, 0);
            assertTrue(note.contains("not converted"), note);
            assertTrue(note.contains("EUR, USD"));
        }
        try (XSSFWorkbook workbook = open(data(report(warning, 5, 5, false)), VI)) {
            assertTrue(string(workbook.getSheet("Monthly Summary"), 5, 0).contains("kh\u00f4ng \u0111\u01b0\u1ee3c quy \u0111\u1ed5i"));
        }
        try (XSSFWorkbook workbook = open(standard(), EN)) {
            assertTrue(isBlank(workbook.getSheet("Monthly Summary"), 5, 0));
        }
    }

    /** Confirms more than five RoomTypes fold to four rows plus Others on the Summary, while Occupancy keeps them all. */
    @Test
    void shouldFoldRoomTypesOnSummaryButKeepFullListOnOccupancySheet() throws IOException {
        try (XSSFWorkbook workbook = open(data(report(null, 8, 5, false)), EN)) {
            Sheet summary = workbook.getSheet("Monthly Summary");
            assertEquals("Type 4", string(summary, 19, 0));
            assertEquals("Others", string(summary, 20, 0));
            assertEquals(50d + 60 + 70 + 80, number(summary, 20, 1));
            assertEquals(120d, number(summary, 20, 2));
            assertEquals(2.1667d, number(summary, 20, 3), 1e-9, "260 occupied / 120 sellable nights, computed from the summed nights");
            Sheet occupancy = workbook.getSheet("Occupancy");
            assertEquals(8, occupancy.getLastRowNum());
            for (int index = 1; index <= 8; index++) {
                assertEquals("Type " + index, string(occupancy, index, 0));
            }
            assertEquals(10d, number(occupancy, 1, 1));
            assertEquals(30d, number(occupancy, 1, 2));
            assertEquals(0.3333d, number(occupancy, 1, 3), 1e-9);
            assertEquals("0%", occupancy.getRow(1).getCell(3).getCellStyle().getDataFormatString());
            for (int index = 1; index <= 8; index++) {
                assertTrue(isBlank(occupancy, index, 4), "Notes stays blank");
            }
        }
    }

    /** Confirms fewer RoomTypes clear the unused fixed rows, leaving no mock values behind. */
    @Test
    void shouldClearUnusedFixedRowsWithoutMockData() throws IOException {
        try (XSSFWorkbook workbook = open(data(report(null, 2, 2, false)), EN)) {
            Sheet summary = workbook.getSheet("Monthly Summary");
            for (int row = 18; row <= 20; row++) {
                for (int column : new int[] {0, 1, 2, 3, 5, 6, 7}) {
                    assertTrue(isBlank(summary, row, column), "row " + (row + 1) + " column " + column);
                }
            }
            assertEquals("Category 2", string(summary, 17, 5));
            assertFalse(java.util.stream.StreamSupport.stream(summary.spliterator(), false).flatMap(r -> java.util.stream.StreamSupport.stream(r.spliterator(), false))
                    .anyMatch(c -> "Late Check-out".equals(c.getCellType() == CellType.STRING ? c.getStringCellValue() : null)));
        }
    }

    /** Confirms Top 4 + Others for more than four categories, and no fake Others for four or fewer. */
    @Test
    void shouldFoldAdditionalRevenueCategories() throws IOException {
        try (XSSFWorkbook workbook = open(data(report(null, 5, 7, false)), EN)) {
            Sheet summary = workbook.getSheet("Monthly Summary");
            assertEquals("Category 4", string(summary, 19, 5));
            assertEquals("Others", string(summary, 20, 5));
            assertEquals(3000d + 2000 + 1000, number(summary, 20, 6));
            assertEquals(0.30d, number(summary, 20, 7), 1e-9);
        }
        try (XSSFWorkbook workbook = open(data(report(null, 5, 4, false)), EN)) {
            Sheet summary = workbook.getSheet("Monthly Summary");
            assertEquals("Category 4", string(summary, 19, 5));
            assertTrue(isBlank(summary, 20, 5));
        }
    }

    // ----- detail sheets ---------------------------------------------------------------------------------

    /** Confirms zero rows leave only the header on every detail sheet, with no template sample rows. */
    @Test
    void shouldLeaveOnlyHeadersForZeroRows() throws IOException {
        try (XSSFWorkbook workbook = open(data(report(null, 0, 0, false)), EN)) {
            for (String name : List.of("Reservations", "Payments", "Expenses", "Occupancy")) {
                assertEquals(0, workbook.getSheet(name).getLastRowNum(), name);
            }
            assertEquals("Reservation No.", string(workbook.getSheet("Reservations"), 0, 0));
        }
    }

    /** Confirms Reservations: one row per Reservation, localized labels, dd/MM/yyyy date cells, multi-room types. */
    @Test
    void shouldWriteReservationRows() throws IOException {
        MonthlyReservationExportRow multi = new MonthlyReservationExportRow("RSV-002", BookingSource.DIRECT, null, "Tanaka Ken",
                LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 6), List.of("Double", "Twin"), n("100.50"), "USD",
                ReservationStatus.CANCELLED);
        MonthlyHotelPerformanceExcelData data = new MonthlyHotelPerformanceExcelData(report(null, 5, 5, false),
                List.of(reservation("RSV-001", "Nguyen Van A", "4258392771", List.of("Double")), multi), List.of(), List.of());
        try (XSSFWorkbook workbook = open(data, EN)) {
            Sheet sheet = workbook.getSheet("Reservations");
            assertEquals(2, sheet.getLastRowNum());
            assertEquals("RSV-001", string(sheet, 1, 0));
            assertEquals("Booking.com", string(sheet, 1, 1));
            assertEquals(CellType.STRING, sheet.getRow(1).getCell(2).getCellType());
            assertEquals("4258392771", string(sheet, 1, 2));
            assertEquals("Nguyen Van A", string(sheet, 1, 3));
            assertEquals(LocalDate.of(2026, 9, 2), sheet.getRow(1).getCell(4).getLocalDateTimeCellValue().toLocalDate());
            assertEquals("dd/MM/yyyy", sheet.getRow(1).getCell(4).getCellStyle().getDataFormatString());
            assertEquals("dd/MM/yyyy", sheet.getRow(1).getCell(5).getCellStyle().getDataFormatString());
            assertEquals("Double", string(sheet, 1, 6));
            assertEquals(32000d, number(sheet, 1, 7));
            assertEquals("#,##0", sheet.getRow(1).getCell(7).getCellStyle().getDataFormatString());
            assertEquals("VND", string(sheet, 1, 8));
            assertEquals("Checked Out", string(sheet, 1, 9));
            assertEquals("Direct", string(sheet, 2, 1));
            assertTrue(isBlank(sheet, 2, 2), "DIRECT has no external reference");
            assertEquals("Double, Twin", string(sheet, 2, 6));
            assertEquals(100.5d, number(sheet, 2, 7));
            assertEquals("#,##0.00", sheet.getRow(2).getCell(7).getCellStyle().getDataFormatString());
            assertEquals("USD", string(sheet, 2, 8));
            assertEquals("Cancelled", string(sheet, 2, 9));
        }
        try (XSSFWorkbook workbook = open(data, VI)) {
            assertEquals("\u0110\u00e3 tr\u1ea3 ph\u00f2ng", string(workbook.getSheet("Reservations"), 1, 9));
            assertEquals("Tr\u1ef1c ti\u1ebfp", string(workbook.getSheet("Reservations"), 2, 1));
        }
    }

    /** Confirms Payments: Currency column after Amount, hotel-time datetime cells, original amounts, refund as one row. */
    @Test
    void shouldWritePaymentRowsWithCurrencyColumn() throws IOException {
        MonthlyHotelPerformanceExcelData data = new MonthlyHotelPerformanceExcelData(report(null, 5, 5, false), List.of(),
                List.of(payment("RSV-001", PaymentStatus.PAID, "32000", PaymentCurrency.VND),
                        payment("RSV-002", PaymentStatus.REFUNDED, "100.50", PaymentCurrency.USD)), List.of());
        try (XSSFWorkbook template = template(); XSSFWorkbook workbook = open(data, EN)) {
            Sheet sheet = workbook.getSheet("Payments");
            assertEquals(List.of("Date", "Reservation No.", "Guest", "Method", "Reference", "Amount", "Currency", "Status"),
                    List.of(string(sheet, 0, 0), string(sheet, 0, 1), string(sheet, 0, 2), string(sheet, 0, 3),
                            string(sheet, 0, 4), string(sheet, 0, 5), string(sheet, 0, 6), string(sheet, 0, 7)));
            assertEquals(LocalDateTime.of(2026, 9, 9, 0, 30), sheet.getRow(1).getCell(0).getLocalDateTimeCellValue());
            assertEquals("dd/MM/yyyy HH:mm", sheet.getRow(1).getCell(0).getCellStyle().getDataFormatString());
            assertEquals("Cash", string(sheet, 1, 3));
            assertEquals("Front Desk", string(sheet, 1, 4));
            assertEquals(32000d, number(sheet, 1, 5));
            assertEquals("VND", string(sheet, 1, 6));
            assertEquals("Paid", string(sheet, 1, 7));
            assertEquals(100.5d, number(sheet, 2, 5));
            assertEquals("USD", string(sheet, 2, 6));
            assertEquals("Refunded", string(sheet, 2, 7));
            assertEquals(2, sheet.getLastRowNum(), "a refund is the same row, never an extra negative row");
            assertTrue(number(sheet, 2, 5) > 0);
            assertEquals(workbook.getSheet("Reservations").getColumnWidth(8), sheet.getColumnWidth(6));
            assertEquals(template.getSheet("Payments").getColumnWidth(5), sheet.getColumnWidth(7));
            assertEquals(fill(template.getSheet("Payments").getRow(0).getCell(0).getCellStyle()),
                    fill(sheet.getRow(0).getCell(6).getCellStyle()));
            assertEquals(1, sheet.getRow(1).getCell(6).getCellStyle().getBorderTop().getCode());
        }
        try (XSSFWorkbook workbook = open(data, VI)) {
            assertEquals("Ti\u1ec1n m\u1eb7t", string(workbook.getSheet("Payments"), 1, 3));
            assertEquals("\u0110\u00e3 ho\u00e0n ti\u1ec1n", string(workbook.getSheet("Payments"), 2, 7));
        }
    }

    /** Confirms Expenses: POSTED row with date cell, category, amount, localized status and creator. */
    @Test
    void shouldWriteExpenseRows() throws IOException {
        MonthlyHotelPerformanceExcelData data = new MonthlyHotelPerformanceExcelData(report(null, 5, 5, false), List.of(),
                List.of(), List.of(expense("Electricity")));
        try (XSSFWorkbook workbook = open(data, EN)) {
            Sheet sheet = workbook.getSheet("Expenses");
            assertEquals(LocalDate.of(2026, 9, 3), sheet.getRow(1).getCell(0).getLocalDateTimeCellValue().toLocalDate());
            assertEquals("dd/MM/yyyy", sheet.getRow(1).getCell(0).getCellStyle().getDataFormatString());
            assertEquals("Utilities", string(sheet, 1, 1));
            assertEquals("Electricity", string(sheet, 1, 2));
            assertEquals(85000d, number(sheet, 1, 3));
            assertEquals("Posted", string(sheet, 1, 4));
            assertEquals("admin", string(sheet, 1, 5));
        }
        try (XSSFWorkbook workbook = open(data, VI)) {
            assertEquals("\u0110\u00e3 ghi s\u1ed5", string(workbook.getSheet("Expenses"), 1, 4));
        }
    }

    /** Confirms many rows keep copying the template data style and row height, with no arbitrary maximum. */
    @Test
    void shouldCopyStylesAndHeightForManyRows() throws IOException {
        List<MonthlyReservationExportRow> rows = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            rows.add(reservation(String.format("RSV-%04d", index), "Guest " + index, null, List.of("Double")));
        }
        MonthlyHotelPerformanceExcelData data = new MonthlyHotelPerformanceExcelData(report(null, 5, 5, false), rows,
                List.of(), List.of());
        try (XSSFWorkbook template = template(); XSSFWorkbook workbook = open(data, EN)) {
            Sheet sheet = workbook.getSheet("Reservations");
            assertEquals(500, sheet.getLastRowNum());
            Row expected = template.getSheet("Reservations").getRow(1);
            for (int index : new int[] {1, 250, 500}) {
                Row row = sheet.getRow(index);
                assertEquals(12f, row.getHeightInPoints());
                assertEquals(expected.getHeightInPoints(), row.getHeightInPoints());
                for (int column = 0; column < 10; column++) {
                    XSSFCellStyle style = (XSSFCellStyle) row.getCell(column).getCellStyle();
                    XSSFCellStyle base = (XSSFCellStyle) expected.getCell(column).getCellStyle();
                    assertEquals(base.getBorderLeft(), style.getBorderLeft());
                    assertEquals(base.getBorderBottom(), style.getBorderBottom());
                    assertEquals(base.getFont().getFontName(), style.getFont().getFontName());
                }
            }
            assertEquals("RSV-0499", string(sheet, 500, 0));
            assertFalse(java.util.stream.StreamSupport.stream(sheet.spliterator(), false)
                    .anyMatch(row -> "RSV-001".equals(row.getCell(0) == null ? null : row.getCell(0).toString())));
        }
    }

    // ----- security --------------------------------------------------------------------------------------

    /** Confirms values starting with =, +, -, @, tab or carriage return stay visible strings and never become formulas. */
    @Test
    void shouldWriteRiskyTextAsQuotePrefixedStrings() throws IOException {
        List<String> risky = List.of("=1+1", "+cmd|' /C calc'!A0", "-2+3", "@SUM(A1)", "\tTAB", "\rCR");
        List<MonthlyReservationExportRow> reservations = new ArrayList<>();
        List<MonthlyPaymentExportRow> payments = new ArrayList<>();
        List<MonthlyExpenseExportRow> expenses = new ArrayList<>();
        for (String value : risky) {
            reservations.add(reservation(value, value, value, List.of(value)));
            payments.add(new MonthlyPaymentExportRow(Instant.parse("2026-09-08T03:00:00Z"), value, value, PaymentMethod.CASH,
                    value, n("1"), PaymentCurrency.VND, PaymentStatus.PAID));
            expenses.add(new MonthlyExpenseExportRow(LocalDate.of(2026, 9, 3), value, value, n("1"), ExpenseStatus.POSTED, value));
        }
        try (XSSFWorkbook workbook = open(new MonthlyHotelPerformanceExcelData(report(null, 5, 5, false), reservations, payments,
                expenses), EN)) {
            for (int index = 0; index < risky.size(); index++) {
                String value = risky.get(index);
                for (int[] target : new int[][] {{0, 0}, {0, 2}, {0, 3}, {0, 6}, {1, 1}, {1, 2}, {1, 4}, {2, 1}, {2, 2}, {2, 5}}) {
                    String sheetName = new String[] {"Reservations", "Payments", "Expenses"}[target[0]];
                    XSSFCell cell = (XSSFCell) workbook.getSheet(sheetName).getRow(index + 1).getCell(target[1]);
                    assertEquals(CellType.STRING, cell.getCellType(), sheetName + " " + cell.getAddress());
                    assertEquals(value, cell.getStringCellValue());
                    assertTrue(cell.getCellStyle().getQuotePrefixed(), sheetName + " " + cell.getAddress());
                }
            }
            assertFalse(workbook.getSheet("Reservations").getRow(1).getCell(1).getCellStyle().getQuotePrefixed(),
                    "ordinary text is not quote-prefixed");
        }
    }

    private static String fill(CellStyle style) {
        return ((XSSFColor) style.getFillForegroundColorColor()).getARGBHex();
    }

    private static List<String> entryNames(byte[] bytes) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
