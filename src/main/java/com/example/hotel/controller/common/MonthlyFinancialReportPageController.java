package com.example.hotel.controller.common;

import com.example.hotel.common.i18n.UiMessages;
import com.example.hotel.dto.common.response.MonthlyFinancialReport;
import com.example.hotel.exception.ReportDataIntegrityException;
import com.example.hotel.service.common.MonthlyFinancialReportService;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.YearMonth;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Serves the Monthly Financial Report page for one selected calendar month. */
@Controller
public class MonthlyFinancialReportPageController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonthlyFinancialReportPageController.class);
    private static final Pattern MONTH_PATTERN = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");
    private static final String TEMPLATE = "report/monthly-financial";

    private final MonthlyFinancialReportService reportService;
    private final Clock clock;
    private final UiMessages messages;

    /**
     * Creates the controller.
     *
     * @param reportService service that calculates the report
     * @param clock hotel business clock used for the default month
     * @param messageSource message source used for user-facing messages
     */
    public MonthlyFinancialReportPageController(
            MonthlyFinancialReportService reportService, Clock clock, MessageSource messageSource) {
        this.reportService = reportService;
        this.clock = clock;
        this.messages = new UiMessages(messageSource);
    }

    /**
     * Displays the report for {@code ?month=yyyy-MM}, defaulting to the current hotel month. An invalid
     * month shows a friendly message and no report.
     *
     * @param month optional month in {@code yyyy-MM} form
     * @param model model used to render the page
     * @param response response, used to mark an integrity failure as HTTP 500
     * @return the report template
     */
    @GetMapping("/reports/monthly-financial")
    @PreAuthorize("hasAuthority('PERM_VIEW_REPORT')")
    public String show(@RequestParam(required = false) String month, Model model, HttpServletResponse response) {
        YearMonth selected = YearMonth.now(clock);
        if (month != null && !month.isBlank()) {
            if (!MONTH_PATTERN.matcher(month.trim()).matches()) {
                model.addAttribute("selectedMonth", selected.toString());
                model.addAttribute("errorMessage", messages.get("report.financial.error.invalidMonth"));
                return TEMPLATE;
            }
            selected = YearMonth.parse(month.trim());
        }
        model.addAttribute("selectedMonth", selected.toString());
        try {
            MonthlyFinancialReport report = reportService.report(selected);
            model.addAttribute("report", report);
            if (report.nonVndWarning() != null) {
                model.addAttribute("warningCurrencies", String.join(", ", report.nonVndWarning().currencies()));
            }
        } catch (ReportDataIntegrityException exception) {
            LOGGER.error("Monthly Financial Report integrity failure: {}", exception.getMessage());
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            model.addAttribute("errorMessage", messages.get("report.financial.error.integrity"));
        }
        return TEMPLATE;
    }
}
