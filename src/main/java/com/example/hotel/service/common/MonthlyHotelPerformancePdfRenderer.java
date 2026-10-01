package com.example.hotel.service.common;

import com.example.hotel.dto.common.response.AdditionalRevenueCategoryShare;
import com.example.hotel.dto.common.response.MonthlyFinancialReport;
import com.example.hotel.dto.common.response.MonthlyHotelPerformanceReport;
import com.example.hotel.dto.common.response.MonthlyOccupancyReport;
import com.example.hotel.dto.common.response.RevenueTrendPoint;
import com.example.hotel.dto.common.response.ReservationSourceShare;
import com.example.hotel.dto.common.response.RoomTypeOccupancy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Draws the approved V3 Monthly Hotel Performance layout (docs/report-templates) as ONE A4 page with PDFBox
 * vector primitives and the bundled Noto Sans font. It only presents an already calculated
 * {@link MonthlyHotelPerformanceReport}: it translates labels, formats numbers and dates, and applies fixed
 * geometry, row capacities and truncation. It never queries data or calculates business figures; the only
 * arithmetic is presentation folding (an "Others" row) and bar fractions.
 */
@Component
public class MonthlyHotelPerformancePdfRenderer {

    private static final String REGULAR_FONT = "fonts/NotoSans-Regular.ttf";
    private static final String BOLD_FONT = "fonts/NotoSans-Bold.ttf";
    private static final int ROOM_TYPE_CAPACITY = 5;
    private static final int TOP_CATEGORY_COUNT = 4;

    private static final float[] BG = rgb(0xf7f9fc);
    private static final float[] NAVY = rgb(0x172235);
    private static final float[] BORDER = rgb(0xe4e7ec);
    private static final float[] TEXT = rgb(0x182230);
    private static final float[] MUTED = rgb(0x667085);
    private static final float[] SUBTLE = rgb(0xcbd5e1);
    private static final float[] RING_TRACK = rgb(0xe9eef5);
    private static final float[] WHITE = rgb(0xffffff);
    private static final float[] BLUE = rgb(0x2f80ed);
    private static final float[] RED = rgb(0xeb5757);
    private static final float[] GREEN = rgb(0x27ae60);
    private static final float[] PURPLE = rgb(0x7b61ff);
    private static final float[] AMBER = rgb(0xb54708);

    private static final float LEFT = 39.69f;
    private static final float RIGHT = 555.59f;
    private static final float PAD = 14.2f;
    private static final float[] KPI_X = {39.69f, 171.5f, 303.3f, 435.1f};
    private static final float KPI_W = 120.5f;

    private final MessageSource messages;
    private final byte[] regularBytes;
    private final byte[] boldBytes;

    /**
     * Creates the renderer and loads the bundled font resources (failing fast if they are missing).
     *
     * @param messages message source used for every translated label
     */
    public MonthlyHotelPerformancePdfRenderer(MessageSource messages) {
        this.messages = messages;
        this.regularBytes = read(REGULAR_FONT);
        this.boldBytes = read(BOLD_FONT);
    }

    /**
     * Renders the report as a one-page A4 PDF held in memory.
     *
     * @param report the calculated dataset
     * @param locale locale of the labels, number and date formatting
     * @return the PDF bytes
     */
    public byte[] render(MonthlyHotelPerformanceReport report, Locale locale) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle(text(locale, "report.performance.title"));
            info.setProducer("Hotel Management System");
            document.setDocumentInformation(info);
            PDType0Font regular = PDType0Font.load(document, new ByteArrayInputStream(regularBytes), true);
            PDType0Font bold = PDType0Font.load(document, new ByteArrayInputStream(boldBytes), true);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                new Drawing(stream, regular, bold, locale, report).draw();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not render the Monthly Hotel Performance PDF", exception);
        }
    }

    private String text(Locale locale, String key, Object... args) {
        return messages.getMessage(key, args, locale);
    }

    private static byte[] read(String path) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Bundled PDF font is missing: " + path, exception);
        }
    }

    private static float[] rgb(int value) {
        return new float[] {((value >> 16) & 0xff) / 255f, ((value >> 8) & 0xff) / 255f, (value & 0xff) / 255f};
    }

    /** One rendering pass: fixed V3 geometry drawn onto a single page. */
    private final class Drawing {
        private final PDPageContentStream cs;
        private final PDType0Font regular;
        private final PDType0Font bold;
        private final Locale locale;
        private final MonthlyHotelPerformanceReport report;
        private final MonthlyFinancialReport financial;
        private final MonthlyOccupancyReport occupancy;
        private final java.util.Map<PDType0Font, java.util.Map<Integer, Boolean>> supported = new java.util.IdentityHashMap<>();
        private final DecimalFormat integer = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));

        private Drawing(
                PDPageContentStream cs, PDType0Font regular, PDType0Font bold, Locale locale,
                MonthlyHotelPerformanceReport report) {
            this.cs = cs;
            this.regular = regular;
            this.bold = bold;
            this.locale = locale;
            this.report = report;
            this.financial = report.financial();
            this.occupancy = report.occupancy();
            integer.setRoundingMode(RoundingMode.HALF_UP);
        }

        private void draw() throws IOException {
            rect(0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight(), 0, BG, null, 0);
            header();
            kpis();
            revenueTrend();
            reservationSource();
            occupancySummary();
            roomTypePerformance();
            financialSummary();
            topAdditionalRevenue();
            notes();
            footer();
        }

        // ----- sections ---------------------------------------------------------------------------------

        private void header() throws IOException {
            rect(LEFT, 722.83f, RIGHT - LEFT, 79.37f, 11.34f, NAVY, null, 0);
            put(bold, 17f, WHITE, 59.53f, 771.02f, text("report.performance.hotelName"));
            put(regular, 8.5f, SUBTLE, 59.53f, 754.02f, text("report.performance.title"));
            String month = DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(report.month()).toUpperCase(locale);
            right(bold, 10f, WHITE, 535.75f, 773.86f, month);
            right(regular, 7.5f, SUBTLE, 535.75f, 756.85f,
                    text("report.performance.generated", date(report.generatedOn())));
        }

        private void kpis() throws IOException {
            var comparison = report.previousMonthComparison();
            String[] labels = {"totalRevenue", "totalExpenses", "netProfit", "occupancy"};
            float[][] dots = {BLUE, RED, GREEN, PURPLE};
            String[] values = {
                money(financial.totalRevenue()), money(financial.expense()), money(financial.netProfit()),
                occupancy.occupancyRate() == null ? na() : oneDecimal(occupancy.occupancyRate()) + "%"};
            BigDecimal[] changes = {
                comparison.totalRevenueChangePercent(), comparison.expenseChangePercent(),
                comparison.netProfitChangePercent(), comparison.occupancyPointDifference()};
            String[] units = {"%", "%", "%", " pp"};
            for (int index = 0; index < 4; index++) {
                float x = KPI_X[index];
                rect(x, 649.1f, KPI_W, 68.1f, 8.5f, WHITE, BORDER, 0.5f);
                circle(x + 17.05f, 697.35f, 6.25f, dots[index]);
                float textX = x + 31.2f;
                float maxWidth = x + KPI_W - 8f - textX;
                fit(bold, 7f, 5f, MUTED, textX, 697.3f, text("report.performance.kpi." + labels[index]).toUpperCase(locale), maxWidth);
                fit(bold, 13.2f, 8.5f, TEXT, textX, 677.5f, values[index], maxWidth);
                BigDecimal change = changes[index];
                String change1 = change == null ? na() : signed(change, units[index]);
                float[] color = change == null || rounded1(change).signum() == 0 ? MUTED : change.signum() > 0 ? GREEN : RED;
                fit(bold, 6.4f, 5f, color, textX, 663.3f, text("report.performance.kpi.vsLastMonth", change1), maxWidth);
            }
        }

        private void revenueTrend() throws IOException {
            card(LEFT, 464.9f, 317.5f, 155.9f);
            title(53.9f, 600.9f, "report.performance.section.revenueTrend");
            for (float gridY : new float[] {493.2f, 521.6f, 549.9f, 578.3f}) {
                line(73.7f, gridY, 334.5f, gridY, BORDER, 0.4f);
            }
            List<RevenueTrendPoint> trend = report.revenueTrend();
            BigDecimal max = trend.stream().map(RevenueTrendPoint::totalRevenue).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            float step = (334.5f - 73.7f) / (trend.size() - 1);
            float[] xs = new float[trend.size()];
            float[] ys = new float[trend.size()];
            for (int index = 0; index < trend.size(); index++) {
                xs[index] = 73.7f + index * step;
                float fraction = max.signum() > 0
                        ? trend.get(index).totalRevenue().divide(max, 6, RoundingMode.HALF_UP).floatValue() : 0f;
                ys[index] = 493.2f + fraction * (571.3f - 493.2f);
            }
            for (int index = 0; index < xs.length - 1; index++) {
                line(xs[index], ys[index], xs[index + 1], ys[index + 1], BLUE, 1.5f);
            }
            DateTimeFormatter label = DateTimeFormatter.ofPattern("MMM", locale);
            for (int index = 0; index < xs.length; index++) {
                circle(xs[index], ys[index], 2.85f, BLUE);
                String month = label.format(trend.get(index).month());
                put(regular, 6f, MUTED, xs[index] - width(regular, 6f, month) / 2f, 481.9f, month);
            }
        }

        private void reservationSource() throws IOException {
            card(368.5f, 464.9f, 187.1f, 155.9f);
            title(382.7f, 600.9f, "report.performance.section.reservationSource");
            List<ReservationSourceShare> shares = new ArrayList<>(report.reservationSources());
            shares.sort(Comparator.comparingLong(ReservationSourceShare::count).reversed());
            for (int index = 0; index < shares.size(); index++) {
                ReservationSourceShare share = shares.get(index);
                float offset = index * 28.35f;
                float[] color = sourceColor(share);
                circle(389.75f, 571.15f - offset, 4.25f, color);
                fit(regular, 7f, 5f, TEXT, 399.7f, 569.8f - offset,
                        text("report.performance.source." + share.source().name()), 120f);
                put(bold, 7f, TEXT, 524.6f, 569.8f - offset, zeroDecimals(share.percentage()) + "%");
                bar(385.5f, 552.8f - offset, 138.9f, 6f, fraction(share.percentage()), color);
            }
        }

        private void occupancySummary() throws IOException {
            card(LEFT, 283.5f, 192.7f, 155.9f);
            title(53.9f, 419.5f, "report.performance.section.occupancy");
            float centerX = 96.4f;
            float centerY = 360.0f;
            ring(centerX, centerY, 31.2f, 5.5f, RING_TRACK, 360f);
            BigDecimal rate = occupancy.occupancyRate();
            if (rate != null && rate.signum() > 0) {
                ring(centerX, centerY, 31.2f, 5.5f, GREEN, Math.min(rate.floatValue(), 100f) * 3.6f);
            }
            String center = rate == null ? na() : oneDecimal(rate) + "%";
            put(bold, 13f, TEXT, centerX - width(bold, 13f, center) / 2f, 354.3f, center);
            String[] labels = {"soldNights", "availableNights", "reservations"};
            String[] values = {
                integer.format(occupancy.occupiedRoomNights()), integer.format(occupancy.sellableRoomNights()),
                integer.format(report.reservationCount())};
            float[] rows = {382.7f, 357.2f, 331.7f};
            for (int index = 0; index < 3; index++) {
                fit(regular, 5.7f, 4.5f, MUTED, 147.4f, rows[index], text("report.performance.occupancy." + labels[index]), 52f);
                right(bold, 6.2f, TEXT, 218.2f, rows[index], values[index]);
            }
            if (occupancy.partialMonth()) {
                String caption = occupancy.reportedThrough() == null
                        ? text("report.performance.occupancy.noCompletedNights")
                        : text("report.performance.occupancy.dataThrough", date(occupancy.reportedThrough()));
                fit(regular, 5.5f, 4.5f, MUTED, 53.9f, 296.5f, caption, 192.7f - 2 * PAD);
            }
        }

        private void roomTypePerformance() throws IOException {
            card(243.8f, 283.5f, 311.8f, 155.9f);
            title(258.0f, 419.5f, "report.performance.section.roomTypePerformance");
            put(bold, 6.1f, MUTED, 260.8f, 391.2f, text("report.performance.table.roomType").toUpperCase(locale));
            right(bold, 6.1f, MUTED, 371.3f, 391.2f, text("report.performance.table.sold").toUpperCase(locale));
            right(bold, 6.1f, MUTED, 425.2f, 391.2f, text("report.performance.table.available").toUpperCase(locale));
            right(bold, 6.1f, MUTED, 541.4f, 391.2f, text("report.performance.table.occupancy").toUpperCase(locale));
            List<RoomTypeOccupancy> types = report.occupancy().roomTypePerformance();
            if (types.isEmpty()) {
                put(regular, 6.5f, MUTED, 260.8f, 371.3f, text("report.performance.roomTypes.empty"));
                return;
            }
            List<Object[]> rows = new ArrayList<>();
            boolean fold = types.size() > ROOM_TYPE_CAPACITY;
            int shown = fold ? ROOM_TYPE_CAPACITY - 1 : types.size();
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
                                .divide(BigDecimal.valueOf(sellable), 2, RoundingMode.HALF_UP);
                rows.add(new Object[] {text("report.performance.others"), occupied, sellable, rate});
            }
            for (int index = 0; index < rows.size(); index++) {
                Object[] row = rows.get(index);
                float y = 371.3f - index * 18.42f;
                BigDecimal rate = (BigDecimal) row[3];
                fit(regular, 6.5f, 5f, TEXT, 260.8f, y, (String) row[0], 80f);
                right(regular, 6.5f, TEXT, 371.3f, y, integer.format((long) row[1]));
                right(regular, 6.5f, TEXT, 425.2f, y, integer.format((long) row[2]));
                bar(439.4f, y - 2.8f, 79.4f, 6.5f, fraction(rate), GREEN);
                put(bold, 6.3f, TEXT, 526.0f, y, rate == null ? na() : zeroDecimals(rate) + "%");
            }
        }

        private void financialSummary() throws IOException {
            card(LEFT, 136.1f, 289.1f, 136.0f);
            title(53.9f, 252.3f, "report.performance.section.financialSummary");
            String[] labels = {"roomRevenue", "additionalRevenue", "totalRevenue", "operatingExpenses", "netOperatingProfit"};
            String[] values = {
                money(financial.roomRevenue()), money(financial.additionalRevenue()), money(financial.totalRevenue()),
                money(financial.expense()), money(financial.netProfit())};
            float[] baselines = {229.6f, 211.5f, 193.3f, 175.2f, 157.0f};
            float[] rules = {222.0f, 203.8f, 185.7f, 167.5f, 149.4f};
            for (int index = 0; index < 5; index++) {
                boolean strong = index == 2 || index == 4;
                var font = strong ? bold : regular;
                float[] valueColor = index == 4 ? (financial.netProfit().signum() < 0 ? RED : GREEN) : TEXT;
                fit(font, 6.8f, 5f, strong ? TEXT : MUTED, 59.5f, baselines[index],
                        text("report.performance.financial." + labels[index]), 120f);
                fitRight(font, 6.8f, 5f, valueColor, 309.0f, baselines[index], values[index], 118f);
                line(59.5f, rules[index], 309.0f, rules[index], BORDER, 0.3f);
            }
        }

        private void topAdditionalRevenue() throws IOException {
            card(340.2f, 136.1f, 215.4f, 136.0f);
            title(354.3f, 252.3f, "report.performance.section.topAdditionalRevenue");
            List<AdditionalRevenueCategoryShare> categories = report.additionalRevenueByCategory();
            if (categories.isEmpty()) {
                put(regular, 6.5f, MUTED, 357.2f, 229.6f, text("report.performance.topAdditional.empty"));
                return;
            }
            List<Object[]> rows = new ArrayList<>();
            int shown = Math.min(TOP_CATEGORY_COUNT, categories.size());
            for (int index = 0; index < shown; index++) {
                AdditionalRevenueCategoryShare category = categories.get(index);
                rows.add(new Object[] {category.categoryName(), category.totalAmount(), category.percentage()});
            }
            if (categories.size() > TOP_CATEGORY_COUNT) {
                BigDecimal amount = BigDecimal.ZERO;
                BigDecimal percentage = BigDecimal.ZERO;
                for (AdditionalRevenueCategoryShare category : categories.subList(TOP_CATEGORY_COUNT, categories.size())) {
                    amount = amount.add(category.totalAmount());
                    percentage = percentage.add(category.percentage());
                }
                rows.add(new Object[] {text("report.performance.others"), amount, percentage});
            }
            BigDecimal max = rows.stream().map(row -> (BigDecimal) row[2]).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            float[] baselines = {229.6f, 211.5f, 193.3f, 175.2f, 157.0f};
            for (int index = 0; index < rows.size(); index++) {
                Object[] row = rows.get(index);
                float y = baselines[index];
                String amount = money((BigDecimal) row[1]);
                float amountWidth = width(regular, 6.1f, amount);
                right(regular, 6.1f, MUTED, 484.7f, y, amount);
                fit(regular, 6.5f, 5f, TEXT, 357.2f, y, (String) row[0], Math.max(20f, 484.7f - amountWidth - 6f - 357.2f));
                BigDecimal percentage = (BigDecimal) row[2];
                float fraction = max.signum() > 0 ? percentage.divide(max, 6, RoundingMode.HALF_UP).floatValue() : 0f;
                bar(493.2f, y - 2.8f, 34.0f, 6.2f, fraction, BLUE);
                right(bold, 6.2f, TEXT, 541.4f, y, zeroDecimals(percentage) + "%");
            }
        }

        private void notes() throws IOException {
            if (financial.nonVndWarning() == null) {
                return;
            }
            var warning = financial.nonVndWarning();
            String note = text("report.performance.warning.nonVnd", warning.reservationCount(),
                    warning.reservationRoomCount(), String.join(", ", warning.currencies()));
            wrap(regular, 6.5f, AMBER, LEFT, 121f, RIGHT - LEFT, 8f, 2, note);
        }

        private void footer() throws IOException {
            put(regular, 6f, MUTED, LEFT, 34.0f, text("report.performance.footer.left"));
            right(regular, 6f, MUTED, RIGHT, 34.0f, text("report.performance.footer.right", "1", "1"));
        }

        private String text(String key, Object... args) {
            return MonthlyHotelPerformancePdfRenderer.this.text(locale, key, args);
        }

        // ----- formatting -------------------------------------------------------------------------------

        private String money(BigDecimal amount) {
            BigDecimal rounded = amount.setScale(0, RoundingMode.HALF_UP);
            return financial.currency() + " " + integer.format(rounded);
        }

        private String date(java.time.LocalDate value) {
            return DateTimeFormatter.ofPattern("dd/MM/yyyy").format(value);
        }

        private String na() {
            return text("report.performance.na");
        }

        private BigDecimal rounded1(BigDecimal value) {
            return value.setScale(1, RoundingMode.HALF_UP);
        }

        private String oneDecimal(BigDecimal value) {
            return rounded1(value).toPlainString();
        }

        private String zeroDecimals(BigDecimal value) {
            return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
        }

        private String signed(BigDecimal value, String unit) {
            BigDecimal rounded = rounded1(value);
            if (rounded.signum() == 0) {
                return "0.0" + unit;
            }
            return (rounded.signum() > 0 ? "+" : "") + rounded.toPlainString() + unit;
        }

        private float fraction(BigDecimal percentage) {
            if (percentage == null) {
                return 0f;
            }
            return Math.max(0f, Math.min(1f, percentage.floatValue() / 100f));
        }

        private float[] sourceColor(ReservationSourceShare share) {
            return switch (share.source()) {
                case BOOKING_COM -> BLUE;
                case AGODA -> RED;
                case DIRECT -> GREEN;
                case AIRBNB -> PURPLE;
            };
        }

        // ----- text -------------------------------------------------------------------------------------

        private String clean(PDType0Font font, String value) {
            StringBuilder builder = new StringBuilder();
            value.codePoints().forEach(codePoint -> {
                int safe = Character.isISOControl(codePoint) ? ' ' : codePoint;
                builder.appendCodePoint(supports(font, safe) ? safe : '?');
            });
            return builder.toString();
        }

        private boolean supports(PDType0Font font, int codePoint) {
            return supported.computeIfAbsent(font, key -> new java.util.HashMap<>()).computeIfAbsent(codePoint, cp -> {
                try {
                    font.encode(new String(Character.toChars(cp)));
                    return true;
                } catch (IOException | IllegalArgumentException exception) {
                    return false;
                }
            });
        }

        private float width(PDType0Font font, float size, String value) throws IOException {
            return font.getStringWidth(clean(font, value)) / 1000f * size;
        }

        private void put(PDType0Font font, float size, float[] color, float x, float y, String value) throws IOException {
            cs.beginText();
            cs.setFont(font, size);
            cs.setNonStrokingColor(color[0], color[1], color[2]);
            cs.newLineAtOffset(x, y);
            cs.showText(clean(font, value));
            cs.endText();
        }

        private void right(PDType0Font font, float size, float[] color, float rightX, float y, String value) throws IOException {
            put(font, size, color, rightX - width(font, size, value), y, value);
        }

        private String ellipsize(PDType0Font font, float size, String value, float maxWidth) throws IOException {
            if (width(font, size, value) <= maxWidth) {
                return value;
            }
            String ellipsis = "…";
            String text = value;
            while (!text.isEmpty() && width(font, size, text + ellipsis) > maxWidth) {
                text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
            }
            return text + ellipsis;
        }

        private void fit(PDType0Font font, float size, float minSize, float[] color, float x, float y, String value, float maxWidth)
                throws IOException {
            float fitted = size;
            while (fitted > minSize && width(font, fitted, value) > maxWidth) {
                fitted -= 0.2f;
            }
            put(font, fitted, color, x, y, ellipsize(font, fitted, value, maxWidth));
        }

        private void fitRight(PDType0Font font, float size, float minSize, float[] color, float rightX, float y, String value, float maxWidth)
                throws IOException {
            float fitted = size;
            while (fitted > minSize && width(font, fitted, value) > maxWidth) {
                fitted -= 0.2f;
            }
            String shown = ellipsize(font, fitted, value, maxWidth);
            put(font, fitted, color, rightX - width(font, fitted, shown), y, shown);
        }

        private void wrap(PDType0Font font, float size, float[] color, float x, float y, float maxWidth, float leading,
                int maxLines, String value) throws IOException {
            List<String> lines = new ArrayList<>();
            String remaining = value.trim();
            while (!remaining.isEmpty() && lines.size() < maxLines) {
                if (width(font, size, remaining) <= maxWidth || lines.size() == maxLines - 1) {
                    lines.add(ellipsize(font, size, remaining, maxWidth));
                    break;
                }
                int cut = remaining.length();
                while (cut > 0 && width(font, size, remaining.substring(0, cut)) > maxWidth) {
                    cut = remaining.lastIndexOf(' ', cut - 1);
                    if (cut < 0) {
                        cut = 0;
                    }
                }
                if (cut == 0) {
                    lines.add(ellipsize(font, size, remaining, maxWidth));
                    break;
                }
                lines.add(remaining.substring(0, cut));
                remaining = remaining.substring(cut).trim();
            }
            for (int index = 0; index < lines.size(); index++) {
                put(font, size, color, x, y - index * leading, lines.get(index));
            }
        }

        private void title(float x, float y, String key) throws IOException {
            put(bold, 9.5f, TEXT, x, y, text(key));
        }

        // ----- shapes -----------------------------------------------------------------------------------

        private void card(float x, float y, float w, float h) throws IOException {
            rect(x, y, w, h, 8.5f, WHITE, BORDER, 0.5f);
        }

        private void rect(float x, float y, float w, float h, float radius, float[] fill, float[] stroke, float lineWidth)
                throws IOException {
            float r = Math.min(radius, Math.min(w, h) / 2f);
            float k = 0.5523f * r;
            cs.moveTo(x + r, y);
            cs.lineTo(x + w - r, y);
            cs.curveTo(x + w - r + k, y, x + w, y + r - k, x + w, y + r);
            cs.lineTo(x + w, y + h - r);
            cs.curveTo(x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h);
            cs.lineTo(x + r, y + h);
            cs.curveTo(x + r - k, y + h, x, y + h - r + k, x, y + h - r);
            cs.lineTo(x, y + r);
            cs.curveTo(x, y + r - k, x + r - k, y, x + r, y);
            cs.closePath();
            cs.setNonStrokingColor(fill[0], fill[1], fill[2]);
            if (stroke == null) {
                cs.fill();
            } else {
                cs.setStrokingColor(stroke[0], stroke[1], stroke[2]);
                cs.setLineWidth(lineWidth);
                cs.fillAndStroke();
            }
        }

        private void circle(float cx, float cy, float r, float[] color) throws IOException {
            rect(cx - r, cy - r, 2 * r, 2 * r, r, color, null, 0);
        }

        private void line(float x1, float y1, float x2, float y2, float[] color, float lineWidth) throws IOException {
            cs.setStrokingColor(color[0], color[1], color[2]);
            cs.setLineWidth(lineWidth);
            cs.setLineCapStyle(1);
            cs.moveTo(x1, y1);
            cs.lineTo(x2, y2);
            cs.stroke();
        }

        private void bar(float x, float y, float w, float h, float fraction, float[] color) throws IOException {
            rect(x, y, w, h, h / 2f, BORDER, null, 0);
            if (fraction > 0f) {
                rect(x, y, Math.max(w * fraction, h), h, h / 2f, color, null, 0);
            }
        }

        /** Strokes an arc clockwise from 12 o'clock through {@code sweepDegrees}. */
        private void ring(float cx, float cy, float radius, float lineWidth, float[] color, float sweepDegrees) throws IOException {
            cs.setStrokingColor(color[0], color[1], color[2]);
            cs.setLineWidth(lineWidth);
            cs.setLineCapStyle(0);
            double start = Math.PI / 2;
            double remaining = Math.toRadians(sweepDegrees);
            int segments = (int) Math.ceil(remaining / (Math.PI / 2) - 1e-9);
            double step = remaining / Math.max(segments, 1);
            double angle = start;
            cs.moveTo((float) (cx + radius * Math.cos(angle)), (float) (cy + radius * Math.sin(angle)));
            for (int index = 0; index < segments; index++) {
                double next = angle - step;
                double alpha = 4.0 / 3.0 * Math.tan((next - angle) / 4.0);
                cs.curveTo(
                        (float) (cx + radius * (Math.cos(angle) - alpha * Math.sin(angle))),
                        (float) (cy + radius * (Math.sin(angle) + alpha * Math.cos(angle))),
                        (float) (cx + radius * (Math.cos(next) + alpha * Math.sin(next))),
                        (float) (cy + radius * (Math.sin(next) - alpha * Math.cos(next))),
                        (float) (cx + radius * Math.cos(next)),
                        (float) (cy + radius * Math.sin(next)));
                angle = next;
            }
            cs.stroke();
        }
    }
}
