package com.example.hotel.controller.room;

import com.example.hotel.common.i18n.UiMessages;
import com.example.hotel.service.room.HousekeepingQueryService;
import com.example.hotel.service.room.RoomService;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves the Housekeeping workspace. It is protected by MANAGE_HOUSEKEEPING only and never exposes Room
 * administration; the two transitions are delegated to the existing Room service operations.
 */
@Controller
public class HousekeepingPageController {

    private static final String REDIRECT = "redirect:/housekeeping";

    private final HousekeepingQueryService queryService;
    private final RoomService roomService;
    private final UiMessages messages;

    /**
     * Creates the controller.
     *
     * @param queryService read model for the workspace
     * @param roomService owner of the housekeeping transitions
     * @param messageSource message source for user-facing feedback
     */
    public HousekeepingPageController(
            HousekeepingQueryService queryService, RoomService roomService, MessageSource messageSource) {
        this.queryService = queryService;
        this.roomService = roomService;
        this.messages = new UiMessages(messageSource);
    }

    /**
     * Displays the Housekeeping workspace.
     *
     * @param model model used to render the page
     * @return the workspace template
     */
    @GetMapping("/housekeeping")
    @PreAuthorize("hasAuthority('PERM_MANAGE_HOUSEKEEPING')")
    public String show(Model model) {
        model.addAttribute("workspace", queryService.loadWorkspace());
        return "housekeeping/index";
    }

    /**
     * Starts cleaning a dirty room from the workspace and returns to it.
     *
     * @param id room identifier
     * @param redirectAttributes post-redirect feedback
     * @return a redirect to the workspace
     */
    @PostMapping("/housekeeping/rooms/{id}/start-cleaning")
    @PreAuthorize("hasAuthority('PERM_MANAGE_HOUSEKEEPING')")
    public String startCleaning(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            roomService.startCleaning(id);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("room.housekeeping.success.started"));
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", failureMessage(exception));
        }
        return REDIRECT;
    }

    /**
     * Marks a cleaning room clean from the workspace and returns to it.
     *
     * @param id room identifier
     * @param redirectAttributes post-redirect feedback
     * @return a redirect to the workspace
     */
    @PostMapping("/housekeeping/rooms/{id}/finish-cleaning")
    @PreAuthorize("hasAuthority('PERM_MANAGE_HOUSEKEEPING')")
    public String finishCleaning(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            roomService.finishCleaning(id);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("room.housekeeping.success.finished"));
        } catch (ResponseStatusException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", failureMessage(exception));
        }
        return REDIRECT;
    }

    private String failureMessage(ResponseStatusException exception) {
        if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
            return messages.get("room.housekeeping.error.notFound");
        }
        return messages.get("room.housekeeping.error.stale");
    }
}
