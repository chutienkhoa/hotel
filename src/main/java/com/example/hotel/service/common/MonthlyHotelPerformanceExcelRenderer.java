package com.example.hotel.service.common;

import com.example.hotel.dto.common.response.AdditionalRevenueCategoryShare;
import com.example.hotel.dto.common.response.MonthlyExpenseExportRow;
import com.example.hotel.dto.common.response.MonthlyFinancialReport;
import com.example.hotel.dto.common.response.MonthlyHotelPerformanceExcelData;
import com.example.hotel.dto.common.response.MonthlyHotelPerformanceReport;
import com.example.hotel.dto.common.response.MonthlyOccupancyReport;
import com.example.hotel.dto.common.response.MonthlyPaymentExportRow;
import com.example.hotel.dto.common.response.MonthlyReservationExportRow;
import com.example.hotel.dto.common.response.ReservationSourceShare;
import com.example.hotel.dto.common.response.RevenueTrendPoint;
import com.example.hotel.dto.common.response.RoomTypeOccupancy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTChart;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTLineChart;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTLineSer;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Fills the approved final Excel workbook (a classpath template) with a {@link MonthlyHotelPerformanceExcelData}.
 * The template supplies every border, fill, font, width, row height, merge and the Revenue Trend chart; this class
 * only writes values, translated labels and detail rows, and never calculates business figures. Sheet names stay
 * fixed English because the chart references {@code 'Monthly Summary'}. User-controlled text is always written as a
 * string cell (never a formula), and personal template metadata is scrubbed from the output.
 */
@Component
public class MonthlyHotelPerformanceExcelRenderer {

    /** Classpath location of the approved final workbook (single source of truth). */
    public static final String TEMPLATE_PATH = "report-templates/hotel_monthly_report_excel_mockup_final.xlsx";

    private static final String SUMMARY = "Monthly Summary";
    private static final String RESERVATIONS = "Reservations";
    private static final String PAYMENTS = "Payments";
    private static final String EXPENSES = "Expenses";
    private static final String OCCUPANCY = "Occupancy";
    private static final String DATE_FORMAT = "dd/MM/yyyy";
    private static final String DATE_TIME_FORMAT = "dd/MM/yyyy HH:mm";
    private static final String DECIMAL_MONEY_FORMAT = "#,##0.00";
    private static final String APPLICATION = "Hotel Management System";
    private static final int ROOM_TYPE_ROWS = 5;
    private static final int TOP_CATEGORIES = 4;
    private static final int MAX_CELL_TEXT = 32_767;

    private final MessageSource messages;
    private final Clock clock;
    private final byte[] template;

    /**
     * Creates the renderer and loads the approved template (failing fast if it is missing).
     *
     * @param messages message source used for every translated label
     * @param clock hotel business clock; its zone converts Instants to hotel time
     */
    public MonthlyHotelPerformanceExcelRenderer(MessageSource messages, Clock clock) {
        this.messages = messages;
        this.clock = clock;
        try (InputStream input = new ClassPathResource(TEMPLATE_PATH).getInputStream()) {
            this.template = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Approved Excel template is missing: " + TEMPLATE_PATH, exception);
        }
    }

    /**
     * Renders the workbook in memory.
     *
     * @param data the calculated dataset
     * @param locale locale of the labels (the current PMS UI locale)
     * @return the XLSX bytes
     */
    public byte[] render(MonthlyHotelPerformanceExcelData data, Locale locale) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            new Filling(workbook, data, locale, clock.getZone()).fill();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not render the Monthly Hotel Performance workbook", exception);
        }
    }

    /** One rendering pass over a freshly loaded template. */
    private final class Filling {
        private final XSSFWorkbook workbook;
        private final MonthlyHotelPerformanceExcelData data;
        private final MonthlyHotelPerformanceReport report;
        private final MonthlyFinancialReport financial;
        private final MonthlyOccupancyReport occupancy;
        private final Locale locale;
        private final ZoneId zone;
        private final Map<String, CellStyle> derived = new HashMap<>();

        private Filling(XSSFWorkbook workbook, MonthlyHotelPerformanceExcelData data, Locale locale, ZoneId zone) {
            this.workbook = workbook;
            this.data = data;
            this.report = data.report();
            this.financial = report.financial();
            this.occupancy = report.occupancy();
            this.locale = locale;
            this.zone = zone;
        }

        private void fill() {
            summary();
            reservations();
            payments();
            expenses();
            occupancySheet();
            scrubMetadata();
        }

        // ----- Monthly Summary --------------------------------------------------------------------------

        private void summary() {
            XSSFSheet sheet = workbook.getSheet(SUMMARY);
            text(cell(sheet, 0, 0), text("report.performance.hotelName") + " — "
                    + text("report.excel.title").toUpperCase(locale));
            String month = DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(report.month());
            text(cell(sheet, 1, 0), month.substring(0, 1).toUpperCase(locale) + month.substring(1));

            if (occupancy.partialMonth()) {
                text(cell(sheet, 2, 0), occupancy.reportedThrough() == null
                        ? text("report.performance.occupancy.noCompletedNights")
                        : text("report.performance.occupancy.dataThrough", date(occupancy.reportedThrough())));
            } else {
                clear(sheet, 2, 0);
            }

            String[] kpiLabels = {
                text("report.performance.kpi.totalRevenue"), text("report.performance.kpi.totalExpenses"),
                text("report.performance.kpi.netProfit"), text("report.occupancy.occupancyRate"),
                text("report.performance.occupancy.reservations"), text("report.excel.soldRoomNights"),
                text("report.excel.availableNights"), text("report.excel.reportMonth")};
            for (int column = 0; column < kpiLabels.length; column++) {
                text(cell(sheet, 3, column), kpiLabels[column]);
            }
            number(cell(sheet, 4, 0), financial.totalRevenue());
            number(cell(sheet, 4, 1), financial.expense());
            number(cell(sheet, 4, 2), financial.netProfit());
            fraction(cell(sheet, 4, 3), occupancy.occupancyRate());
            number(cell(sheet, 4, 4), report.reservationCount());
            number(cell(sheet, 4, 5), occupancy.occupiedRoomNights());
            number(cell(sheet, 4, 6), occupancy.sellableRoomNights());
            cell(sheet, 4, 7).setCellValue(report.month().atDay(1));

            if (financial.nonVndWarning() != null) {
                var warning = financial.nonVndWarning();
                text(cell(sheet, 5, 0), text("report.performance.warning.nonVnd", warning.reservationCount(),
                        warning.reservationRoomCount(), String.join(", ", warning.currencies())));
            } else {
                clear(sheet, 5, 0);
            }

            text(cell(sheet, 7, 0), text("report.performance.section.financialSummary"));
            text(cell(sheet, 7, 2), text("report.excel.amount"));
            String[] financialLabels = {"roomRevenue", "additionalRevenue", "totalRevenue", "operatingExpenses", "netOperatingProfit"};
            BigDecimal[] financialValues = {
                financial.roomRevenue(), financial.additionalRevenue(), financial.totalRevenue(), financial.expense(),
                financial.netProfit()};
            for (int index = 0; index < 5; index++) {
                text(cell(sheet, 8 + index, 0), text("report.performance.financial." + financialLabels[index]));
                number(cell(sheet, 8 + index, 2), financialValues[index]);
            }

            text(cell(sheet, 7, 5), text("report.performance.section.reservationSource"));
            text(cell(sheet, 7, 6), text("report.performance.occupancy.reservations"));
            text(cell(sheet, 7, 7), "%");
            List<ReservationSourceShare> sources = new ArrayList<>(report.reservationSources());
            sources.sort(Comparator.comparingLong(ReservationSourceShare::count).reversed());
            for (int index = 0; index < sources.size(); index++) {
                ReservationSourceShare share = sources.get(index);
                text(cell(sheet, 8 + index, 5), text("report.performance.source." + share.source().name()));
                number(cell(sheet, 8 + index, 6), share.count());
                fraction(cell(sheet, 8 + index, 7), share.percentage());
            }

            text(cell(sheet, 15, 0), text("report.performance.table.roomType"));
            text(cell(sheet, 15, 1), text("report.excel.soldNights"));
            text(cell(sheet, 15, 2), text("report.excel.availableNights"));
            text(cell(sheet, 15, 3), text("report.performance.table.occupancy"));
            List<Object[]> typeRows = roomTypeSummaryRows();
            for (int index = 0; index < ROOM_TYPE_ROWS; index++) {
                int row = 16 + index;
                if (index < typeRows.size()) {
                    Object[] values = typeRows.get(index);
                    text(cell(sheet, row, 0), (String) values[0]);
                    number(cell(sheet, row, 1), (long) values[1]);
                    number(cell(sheet, row, 2), (long) values[2]);
                    fraction(cell(sheet, row, 3), (BigDecimal) values[3]);
                } else {
                    for (int column = 0; column < 4; column++) {
                        blank(cell(sheet, row, column));
                    }
                }
            }

            text(cell(sheet, 15, 5), text("report.performance.financial.additionalRevenue"));
            text(cell(sheet, 15, 6), text("report.excel.amount"));
            text(cell(sheet, 15, 7), "%");
            List<Object[]> categoryRows = categorySummaryRows();
            for (int index = 0; index < ROOM_TYPE_ROWS; index++) {
                int row = 16 + index;
                if (index < categoryRows.size()) {
                    Object[] values = categoryRows.get(index);
                    text(cell(sheet, row, 5), (String) values[0]);
                    number(cell(sheet, row, 6), (BigDecimal) values[1]);
                    fraction(cell(sheet, row, 7), (BigDecimal) values[2]);
                } else {
                    for (int column = 5; column < 8; column++) {
                        blank(cell(sheet, row, column));
                    }
                }
            }

            text(cell(sheet, 23, 0), text("report.excel.month"));
            text(cell(sheet, 23, 1), text("report.performance.kpi.totalRevenue"));
            text(cell(sheet, 23, 2), text("report.performance.financial.roomRevenue"));
            DateTimeFormatter monthLabel = DateTimeFormatter.ofPattern("MMM", locale);
            List<RevenueTrendPoint> trend = report.revenueTrend();
            for (int index = 0; index < trend.size(); index++) {
                RevenueTrendPoint point = trend.get(index);
                text(cell(sheet, 24 + index, 0), monthLabel.format(point.month()));
                number(cell(sheet, 24 + index, 1), point.totalRevenue());
                number(cell(sheet, 24 + index, 2), point.roomRevenue());
            }
            localizeChart(sheet, trend, monthLabel);
        }

        private List<Object[]> roomTypeSummaryRows() {
            List<RoomTypeOccupancy> types = occupancy.roomTypePerformance();
            List<Object[]> rows = new ArrayList<>();
            boolean fold = types.size() > ROOM_TYPE_ROWS;
            int shown = fold ? ROOM_TYPE_ROWS - 1 : types.size();
            for (int index = 0; index < shown; index++) {
                RoomTypeOccupancy type = types.get(index);
                rows.add(new Object[] {type.roomTypeName(), type.occupiedRoomNights(), type.sellableRoomNights(), type.occupancyRate()});
            }
            if (fold) {
                long occupied = 0;
                long sellable = 0;
                for (RoomTypeOccupancy type : types.subList(shown, types.size())) {
                    occupied += type.occupiedRoomNights();
                    sellable += type.sellableRoomNights();
                }
                BigDecimal rate = sellable == 0 ? null
                        : BigDecimal.valueOf(occupied).multiply(BigDecimal.valueOf(100))
                                .divide(BigDecimal.valueOf(sellable), 2, java.math.RoundingMode.HALF_UP);
                rows.add(new Object[] {text("report.performance.others"), occupied, sellable, rate});
            }
            return rows;
        }

        private List<Object[]> categorySummaryRows() {
            List<AdditionalRevenueCategoryShare> categories = report.additionalRevenueByCategory();
            List<Object[]> rows = new ArrayList<>();
            int shown = Math.min(TOP_CATEGORIES, categories.size());
            for (int index = 0; index < shown; index++) {
                AdditionalRevenueCategoryShare category = categories.get(index);
                rows.add(new Object[] {category.categoryName(), category.totalAmount(), category.percentage()});
            }
            if (categories.size() > TOP_CATEGORIES) {
                BigDecimal amount = BigDecimal.ZERO;
                BigDecimal percentage = BigDecimal.ZERO;
                for (AdditionalRevenueCategoryShare category : categories.subList(TOP_CATEGORIES, categories.size())) {
                    amount = amount.add(category.totalAmount());
                    percentage = percentage.add(category.percentage());
                }
                rows.add(new Object[] {text("report.performance.others"), amount, percentage});
            }
            return rows;
        }

        /**
         * Localizes the chart's literal title and series names and refreshes the cached category and value
         * points, leaving every reference, the anchor and the chart type untouched.
         */
        private void localizeChart(XSSFSheet sheet, List<RevenueTrendPoint> trend, DateTimeFormatter monthLabel) {
            XSSFChart chart = sheet.getDrawingPatriarch().getCharts().get(0);
            CTChart ctChart = chart.getCTChart();
            ctChart.getTitle().getTx().getRich().getPArray(0).getRArray(0).setT(text("report.performance.section.revenueTrend"));
            CTLineChart line = ctChart.getPlotArea().getLineChartArray(0);
            String[] names = {text("report.performance.kpi.totalRevenue"), text("report.performance.financial.roomRevenue")};
            for (int series = 0; series < 2; series++) {
                CTLineSer ser = line.getSerArray(series);
                ser.getTx().setV(names[series]);
                for (int index = 0; index < trend.size(); index++) {
                    ser.getCat().getStrRef().getStrCache().getPtArray(index).setV(monthLabel.format(trend.get(index).month()));
                    BigDecimal value = series == 0 ? trend.get(index).totalRevenue() : trend.get(index).roomRevenue();
                    ser.getVal().getNumRef().getNumCache().getPtArray(index).setV(value.toPlainString());
                }
            }
        }

        // ----- detail sheets ----------------------------------------------------------------------------

        private void reservations() {
            XSSFSheet sheet = workbook.getSheet(RESERVATIONS);
            String[] headers = {
                text("report.excel.reservationNo"), text("report.excel.source"), text("report.excel.externalBookingRef"),
                text("report.excel.guest"), text("report.excel.checkIn"), text("report.excel.checkOut"),
                text("report.performance.table.roomType"), text("report.excel.bookingAmount"),
                text("report.excel.currency"), text("common.field.status")};
            CellStyle[] styles = prepareDetail(sheet, headers, 10);
            CellStyle dateStyle = derive(styles[4], "date", format(DATE_FORMAT));
            List<MonthlyReservationExportRow> rows = data.reservations();
            for (int index = 0; index < rows.size(); index++) {
                MonthlyReservationExportRow row = rows.get(index);
                Row target = detailRow(sheet, index + 1);
                textCell(target, 0, styles[0], row.reservationNumber());
                textCell(target, 1, styles[1], text("report.performance.source." + row.source().name()));
                textCell(target, 2, styles[2], row.otaBookingReference());
                textCell(target, 3, styles[3], row.guestName());
                dateCell(target, 4, dateStyle, row.checkInDate());
                dateCell(target, 5, dateStyle, row.checkOutDate());
                textCell(target, 6, styles[6], String.join(", ", row.roomTypeNames()));
                moneyCell(target, 7, styles[7], row.totalAmount(), row.currency());
                textCell(target, 8, styles[8], row.currency());
                textCell(target, 9, styles[9], enumLabel("reservationStatus", row.status().name()));
            }
        }

        private void payments() {
            XSSFSheet sheet = workbook.getSheet(PAYMENTS);
            XSSFSheet reservationSheet = workbook.getSheet(RESERVATIONS);
            String[] headers = {
                text("common.field.date"), text("report.excel.reservationNo"), text("report.excel.guest"),
                text("report.excel.method"), text("report.excel.reference"), text("report.excel.amount"),
                text("report.excel.currency"), text("common.field.status")};
            // The approved template has 7 columns; Currency is the single approved addition, right after Amount.
            int statusWidth = sheet.getColumnWidth(6);
            CellStyle[] templateStyles = prepareDetail(sheet, headers, 7);
            CellStyle[] styles = {
                templateStyles[0], templateStyles[1], templateStyles[2], templateStyles[3], templateStyles[4],
                templateStyles[5], templateStyles[3], templateStyles[6]};
            sheet.setColumnWidth(6, reservationSheet.getColumnWidth(8));
            sheet.setColumnWidth(7, statusWidth);
            CellStyle dateTimeStyle = derive(styles[0], "datetime", format(DATE_TIME_FORMAT));
            List<MonthlyPaymentExportRow> rows = data.payments();
            for (int index = 0; index < rows.size(); index++) {
                MonthlyPaymentExportRow row = rows.get(index);
                Row target = detailRow(sheet, index + 1);
                Cell paidAt = target.createCell(0);
                paidAt.setCellStyle(dateTimeStyle);
                paidAt.setCellValue(LocalDateTime.ofInstant(row.paidAt(), zone));
                textCell(target, 1, styles[1], row.reservationNumber());
                textCell(target, 2, styles[2], row.guestName());
                textCell(target, 3, styles[3], enumLabel("paymentMethod", row.method().name()));
                textCell(target, 4, styles[4], row.reference());
                moneyCell(target, 5, styles[5], row.amount(), row.currency().name());
                textCell(target, 6, styles[6], row.currency().name());
                textCell(target, 7, styles[7], enumLabel("paymentStatus", row.status().name()));
            }
        }

        private void expenses() {
            XSSFSheet sheet = workbook.getSheet(EXPENSES);
            String[] headers = {
                text("common.field.date"), text("report.excel.category"), text("report.excel.description"),
                text("report.excel.amount"), text("common.field.status"), text("report.excel.createdBy")};
            CellStyle[] styles = prepareDetail(sheet, headers, 6);
            CellStyle dateStyle = derive(styles[0], "date", format(DATE_FORMAT));
            List<MonthlyExpenseExportRow> rows = data.expenses();
            for (int index = 0; index < rows.size(); index++) {
                MonthlyExpenseExportRow row = rows.get(index);
                Row target = detailRow(sheet, index + 1);
                dateCell(target, 0, dateStyle, row.expenseDate());
                textCell(target, 1, styles[1], row.categoryName());
                textCell(target, 2, styles[2], row.description());
                moneyCell(target, 3, styles[3], row.amount(), "VND");
                textCell(target, 4, styles[4], enumLabel("expenseStatus", row.status().name()));
                textCell(target, 5, styles[5], row.createdBy());
            }
        }

        private void occupancySheet() {
            XSSFSheet sheet = workbook.getSheet(OCCUPANCY);
            String[] headers = {
                text("report.performance.table.roomType"), text("report.excel.soldNights"),
                text("report.excel.availableNights"), text("report.performance.table.occupancy"), text("common.field.notes")};
            CellStyle[] styles = prepareDetail(sheet, headers, 5);
            List<RoomTypeOccupancy> rows = occupancy.roomTypePerformance();
            for (int index = 0; index < rows.size(); index++) {
                RoomTypeOccupancy row = rows.get(index);
                Row target = detailRow(sheet, index + 1);
                textCell(target, 0, styles[0], row.roomTypeName());
                numberCell(target, 1, styles[1], BigDecimal.valueOf(row.occupiedRoomNights()));
                numberCell(target, 2, styles[2], BigDecimal.valueOf(row.sellableRoomNights()));
                Cell rate = target.createCell(3);
                rate.setCellStyle(styles[3]);
                fraction(rate, row.occupancyRate());
                target.createCell(4).setCellStyle(styles[4]);
            }
        }

        /**
         * Rewrites the header row with translated labels and removes every template sample row, returning the
         * per-column data styles (taken from the first template data row) for the production rows.
         */
        private CellStyle[] prepareDetail(XSSFSheet sheet, String[] headers, int templateColumns) {
            Row header = sheet.getRow(0);
            Row firstData = sheet.getRow(1);
            CellStyle headerStyle = header.getCell(0).getCellStyle();
            CellStyle[] styles = new CellStyle[headers.length];
            for (int column = 0; column < templateColumns; column++) {
                styles[column] = firstData.getCell(column).getCellStyle();
            }
            rowHeight = firstData.getHeightInPoints();
            for (int rowIndex = sheet.getLastRowNum(); rowIndex >= 1; rowIndex--) {
                Row sample = sheet.getRow(rowIndex);
                if (sample != null) {
                    sheet.removeRow(sample);
                }
            }
            for (int column = 0; column < headers.length; column++) {
                Cell cell = header.getCell(column);
                if (cell == null) {
                    cell = header.createCell(column);
                    cell.setCellStyle(headerStyle);
                }
                cell.setCellValue(headers[column]);
            }
            return styles;
        }

        private float rowHeight;

        private Row detailRow(XSSFSheet sheet, int index) {
            Row row = sheet.createRow(index);
            row.setHeightInPoints(rowHeight);
            return row;
        }

        // ----- cells ------------------------------------------------------------------------------------

        private Cell cell(XSSFSheet sheet, int rowIndex, int columnIndex) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                row = sheet.createRow(rowIndex);
                row.setHeightInPoints(sheet.getDefaultRowHeightInPoints());
            }
            Cell cell = row.getCell(columnIndex);
            return cell == null ? row.createCell(columnIndex) : cell;
        }

        private void text(Cell cell, String value) {
            if (value == null || value.isEmpty()) {
                cell.setBlank();
                return;
            }
            String safe = value.length() > MAX_CELL_TEXT ? value.substring(0, MAX_CELL_TEXT) : value;
            cell.setCellValue(safe);
            if (needsQuotePrefix(safe)) {
                cell.setCellStyle(derive(cell.getCellStyle(), "quote", style -> style.setQuotePrefixed(true)));
            }
        }

        private void textCell(Row row, int column, CellStyle style, String value) {
            Cell cell = row.createCell(column);
            cell.setCellStyle(style);
            text(cell, value);
        }

        private void dateCell(Row row, int column, CellStyle style, LocalDate value) {
            Cell cell = row.createCell(column);
            cell.setCellStyle(style);
            cell.setCellValue(value);
        }

        private void numberCell(Row row, int column, CellStyle style, BigDecimal value) {
            Cell cell = row.createCell(column);
            cell.setCellStyle(style);
            number(cell, value);
        }

        /** Writes a money value; non-VND amounts get two decimals so tender values such as USD are not rounded on screen. */
        private void moneyCell(Row row, int column, CellStyle style, BigDecimal value, String currency) {
            Cell cell = row.createCell(column);
            cell.setCellStyle("VND".equals(currency) ? style : derive(style, "decimal", format(DECIMAL_MONEY_FORMAT)));
            number(cell, value);
        }

        private void number(Cell cell, BigDecimal value) {
            if (value == null) {
                cell.setBlank();
            } else {
                cell.setCellValue(value.doubleValue());
            }
        }

        private void number(Cell cell, long value) {
            cell.setCellValue((double) value);
        }

        /** Writes a percentage value (for example 42.00) as the fraction 0.42 for the template's percent format. */
        private void fraction(Cell cell, BigDecimal percentage) {
            if (percentage == null) {
                cell.setBlank();
            } else {
                cell.setCellValue(percentage.movePointLeft(2).doubleValue());
            }
        }

        private void blank(Cell cell) {
            cell.setBlank();
        }

        /** Clears a cell only if it exists, so an absent template spacer row stays absent. */
        private void clear(XSSFSheet sheet, int rowIndex, int columnIndex) {
            Row row = sheet.getRow(rowIndex);
            Cell cell = row == null ? null : row.getCell(columnIndex);
            if (cell != null) {
                cell.setBlank();
            }
        }

        private boolean needsQuotePrefix(String value) {
            char first = value.charAt(0);
            return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                    || first == '\n';
        }

        private Consumer<CellStyle> format(String pattern) {
            short index = workbook.createDataFormat().getFormat(pattern);
            return style -> style.setDataFormat(index);
        }

        private CellStyle derive(CellStyle base, String key, Consumer<CellStyle> change) {
            return derived.computeIfAbsent(base.getIndex() + ":" + key, unused -> {
                CellStyle style = workbook.createCellStyle();
                style.cloneStyleFrom(base);
                change.accept(style);
                return style;
            });
        }

        // ----- labels -----------------------------------------------------------------------------------

        private String text(String key, Object... args) {
            return MonthlyHotelPerformanceExcelRenderer.this.messages.getMessage(key, args, locale);
        }

        private String enumLabel(String type, String value) {
            return messages.getMessage("enum." + type + "." + value, null, value, locale);
        }

        private String date(LocalDate value) {
            return DateTimeFormatter.ofPattern(DATE_FORMAT).format(value);
        }

        // ----- metadata ---------------------------------------------------------------------------------

        /** Replaces personal template metadata (editor name, absolute path) with generic application values. */
        private void scrubMetadata() {
            POIXMLProperties properties = workbook.getProperties();
            POIXMLProperties.CoreProperties core = properties.getCoreProperties();
            core.setCreator(APPLICATION);
            core.setLastModifiedByUser(APPLICATION);
            core.setTitle(text("report.performance.title"));
            core.setModified(Optional.of(Date.from(report.generatedOn().atStartOfDay(zone).toInstant())));
            properties.getExtendedProperties().getUnderlyingProperties().setApplication(APPLICATION);
            XmlObject[] alternates = workbook.getCTWorkbook().selectPath(
                    "declare namespace mc='http://schemas.openxmlformats.org/markup-compatibility/2006' $this/mc:AlternateContent");
            for (XmlObject alternate : alternates) {
                try (XmlCursor cursor = alternate.newCursor()) {
                    cursor.removeXml();
                }
            }
        }
    }
}
