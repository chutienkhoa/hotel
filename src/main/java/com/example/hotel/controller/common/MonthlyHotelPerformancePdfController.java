package com.example.hotel.controller.common;

import com.example.hotel.common.i18n.UiMessages;
import com.example.hotel.dto.common.response.MonthlyHotelPerformanceReport;
import com.example.hotel.exception.ReportDataIntegrityException;
import com.example.hotel.exception.ReportPeriodUnavailableException;
import com.example.hotel.service.common.MonthlyHotelPerformancePdfRenderer;
import com.example.hotel.service.common.MonthlyHotelPerformanceReportService;
import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Serves the one-page Monthly Hotel Performance PDF download. */
@Controller
public class MonthlyHotelPerformancePdfController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonthlyHotelPerformancePdfController.class);
    private static final Pattern MONTH_PATTERN = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MM/yyyy");

    private final MonthlyHotelPerformanceReportService reportService;
    private final MonthlyHotelPerformancePdfRenderer renderer;
    private final Clock clock;
    private final UiMessages messages;

    /**
     * Creates the controller.
     *
     * @param reportService service that builds the business dataset
     * @param renderer renderer that draws the dataset as a PDF
     * @param clock hotel business clock used for the default month
     * @param messageSource message source used for error messages
     */
    public MonthlyHotelPerformancePdfController(
            MonthlyHotelPerformanceReportService reportService,
            MonthlyHotelPerformancePdfRenderer renderer,
            Clock clock,
            MessageSource messageSource) {
        this.reportService = reportService;
        this.renderer = renderer;
        this.clock = clock;
        this.messages = new UiMessages(messageSource);
    }

    /**
     * Downloads the PDF for {@code ?month=yyyy-MM} (default: the current hotel month) in the current PMS
     * locale. An invalid, future or unsupported month, or a data-integrity failure, never produces a PDF:
     * the user is redirected to the Reports page with a translated message.
     *
     * @param month optional month in {@code yyyy-MM} form
     * @param locale current PMS UI locale
     * @param redirectAttributes carries the flash error message
     * @return the PDF attachment, or a redirect to Reports
     */
    @GetMapping("/reports/monthly-performance.pdf")
    @PreAuthorize("hasAuthority('PERM_VIEW_REPORT')")
    public Object download(
            @RequestParam(required = false) String month, Locale locale, RedirectAttributes redirectAttributes) {
        YearMonth selected = YearMonth.now(clock);
        if (month != null && !month.isBlank()) {
            if (!MONTH_PATTERN.matcher(month.trim()).matches()) {
                return reject(redirectAttributes, messages.get(locale, "report.occupancy.error.invalidMonth"));
            }
            selected = YearMonth.parse(month.trim());
        }
        try {
            MonthlyHotelPerformanceReport report = reportService.build(selected);
            byte[] pdf = renderer.render(report, locale);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename("hotel-performance-" + selected + ".pdf").build().toString())
                    .cacheControl(CacheControl.noStore())
                    .contentLength(pdf.length)
                    .body(pdf);
        } catch (ReportPeriodUnavailableException exception) {
            String message = switch (exception.getReason()) {
                case FUTURE_MONTH -> messages.get(locale, "report.occupancy.error.futureMonth");
                case HISTORY_UNAVAILABLE -> messages.get(
                        locale, "report.occupancy.error.historyUnavailable",
                        exception.getFirstSupportedMonth().format(MONTH_FORMAT));
            };
            return reject(redirectAttributes, message);
        } catch (ReportDataIntegrityException exception) {
            LOGGER.error("Monthly Hotel Performance PDF integrity failure: {}", exception.getMessage());
            return reject(redirectAttributes, messages.get(locale, "report.occupancy.error.integrity"));
        }
    }

    private String reject(RedirectAttributes redirectAttributes, String message) {
        redirectAttributes.addFlashAttribute("errorMessage", message);
        return "redirect:/reports";
    }
}
