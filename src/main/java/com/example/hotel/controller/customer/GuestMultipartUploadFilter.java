package com.example.hotel.controller.customer;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.SessionFlashMapManager;

/**
 * Catches an oversized Guest passport multipart upload that fails while Spring Security's CSRF
 * filter reads the {@code _csrf} request parameter, which happens before Spring MVC dispatch (and
 * therefore before {@link GuestMultipartExceptionHandler}) ever runs. Reading any parameter from a
 * {@code multipart/form-data} request forces the servlet container to eagerly parse every part, so
 * a part that exceeds the configured multipart size limit surfaces here instead.
 *
 * <p>This filter must run before Spring Security's {@code CsrfFilter} so that exceptions thrown by
 * that filter (and by everything after it, including {@code DispatcherServlet}) propagate back up
 * through this filter's {@code try}/{@code catch}. Only {@link MultipartException} (and its
 * {@code MaxUploadSizeExceededException} subtype) on a Guest create/update route is handled here;
 * every other exception, and every other route, is rethrown unchanged.</p>
 */
@Component
public class GuestMultipartUploadFilter extends OncePerRequestFilter {

    private final SessionFlashMapManager flashMapManager = new SessionFlashMapManager();

    /**
     * Lets every request through, redirecting only an oversized Guest passport upload back to its
     * form page with a friendly message instead of letting the container's default error page
     * render.
     *
     * @param request current browser request
     * @param response current browser response
     * @param chain remaining filter chain, including Spring Security and Spring MVC dispatch
     * @throws ServletException if the filter chain fails for an unrelated reason
     * @throws IOException if the response cannot be written
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (MultipartException exception) {
            String redirectPath = GuestMultipartFormRoutes.redirectPathFor(request.getMethod(), applicationPath(request));
            if (redirectPath == null) {
                throw exception;
            }
            redirectWithFriendlyMessage(request, response, redirectPath);
        }
    }

    /**
     * Stashes the friendly passport-size message as a flash attribute and redirects the browser
     * back to the Guest form, mirroring the redirect a normal Spring MVC dispatch would produce.
     *
     * @param request current browser request
     * @param response current browser response
     * @param redirectPath application-relative Guest form path to redirect to
     * @throws IOException if the redirect response cannot be written
     */
    private void redirectWithFriendlyMessage(HttpServletRequest request, HttpServletResponse response, String redirectPath)
            throws IOException {
        FlashMap flashMap = new FlashMap();
        flashMap.put("passportError", GuestMultipartFormRoutes.PASSPORT_SIZE_MESSAGE);
        flashMap.setTargetRequestPath(request.getContextPath() + redirectPath);
        flashMapManager.saveOutputFlashMap(flashMap, request, response);
        response.sendRedirect(request.getContextPath() + redirectPath);
    }

    /**
     * Removes the optional servlet context path before matching an application route.
     *
     * @param request current browser request
     * @return application-relative path
     */
    private String applicationPath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String requestUri = request.getRequestURI();
        return contextPath == null || contextPath.isEmpty() ? requestUri : requestUri.substring(contextPath.length());
    }
}
