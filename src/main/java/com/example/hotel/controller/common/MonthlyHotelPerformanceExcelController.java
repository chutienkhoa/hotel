package com.example.hotel.controller.common;

import com.example.hotel.common.i18n.UiMessages;
import com.example.hotel.dto.common.response.MonthlyHotelPerformanceExcelData;
import com.example.hotel.exception.ReportDataIntegrityException;
import com.example.hotel.exception.ReportPeriodUnavailableException;
import com.example.hotel.service.common.MonthlyHotelPerformanceExcelRenderer;
import com.example.hotel.service.common.MonthlyHotelPerformanceExcelService;
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

/** Serves the Monthly Hotel Performance Excel download, following the same month rules as the PDF export. */
@Controller
public class MonthlyHotelPerformanceExcelController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonthlyHotelPerformanceExcelController.class);
    private static final Pattern MONTH_PATTERN = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MM/yyyy");
    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final MonthlyHotelPerformanceExcelService service;
    private final MonthlyHotelPerformanceExcelRenderer renderer;
    private final Clock clock;
    private final UiMessages messages;

    /**
     * Creates the controller.
     *
     * @param service service that builds the Excel dataset
     * @param renderer renderer that fills the approved workbook template
     * @param clock hotel business clock used for the default month
     * @param messageSource message source used for error messages
     */
    public MonthlyHotelPerformanceExcelController(
            MonthlyHotelPerformanceExcelService service,
            MonthlyHotelPerformanceExcelRenderer renderer,
            Clock clock,
            MessageSource messageSource) {
        this.service = service;
        this.renderer = renderer;
        this.clock = clock;
        this.messages = new UiMessages(messageSource);
    }

    /**
     * Downloads the workbook for {@code ?month=yyyy-MM} (default: the current hotel month) in the current PMS
     * locale. An invalid, future or unsupported month, or a data-integrity failure, never produces a workbook: the
     * user is redirected to Reports with a translated message.
     *
     * @param month optional month in {@code yyyy-MM} form
     * @param locale current PMS UI locale
     * @param redirectAttributes carries the flash error message
     * @return the XLSX attachment, or a redirect to Reports
     */
    @GetMapping("/reports/monthly-performance.xlsx")
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
            MonthlyHotelPerformanceExcelData data = service.build(selected);
            byte[] workbook = renderer.render(data, locale);
            return ResponseEntity.ok()
                    .contentType(XLSX)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename("hotel-performance-" + selected + ".xlsx").build().toString())
                    .cacheControl(CacheControl.noStore())
                    .contentLength(workbook.length)
                    .body(workbook);
        } catch (ReportPeriodUnavailableException exception) {
            String message = switch (exception.getReason()) {
                case FUTURE_MONTH -> messages.get(locale, "report.occupancy.error.futureMonth");
                case HISTORY_UNAVAILABLE -> messages.get(
                        locale, "report.occupancy.error.historyUnavailable",
                        exception.getFirstSupportedMonth().format(MONTH_FORMAT));
            };
            return reject(redirectAttributes, message);
        } catch (ReportDataIntegrityException exception) {
            LOGGER.error("Monthly Hotel Performance Excel integrity failure: {}", exception.getMessage());
            return reject(redirectAttributes, messages.get(locale, "report.occupancy.error.integrity"));
        }
    }

    private String reject(RedirectAttributes redirectAttributes, String message) {
        redirectAttributes.addFlashAttribute("errorMessage", message);
        return "redirect:/reports";
    }
}
