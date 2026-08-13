package com.bitesite.web;

import com.bitesite.exception.BusinessException;
import com.bitesite.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Single place every uncaught exception funnels through. API requests (path under
 * {@code /api/}) get a structured {@link ApiError} JSON body; everything else gets the
 * branded {@code error/generic} page. Nothing here ever puts a stack trace in the response
 * — that only ever goes to the server log.
 */
@ControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    @ExceptionHandler(ResourceNotFoundException.class)
    public ModelAndView notFound(ResourceNotFoundException e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        return respond(HttpStatus.NOT_FOUND, e.getMessage(), request, response);
    }

    @ExceptionHandler(BusinessException.class)
    public ModelAndView business(BusinessException e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        log.warn("Business exception on {}: {}", request.getRequestURI(), e.getMessage());
        return respond(HttpStatus.BAD_REQUEST, e.getMessage(), request, response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ModelAndView accessDenied(AccessDeniedException e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        return respond(HttpStatus.FORBIDDEN, "You don't have permission to do that.", request, response);
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView generic(Exception e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        log.error("Unhandled exception on {}", request.getRequestURI(), e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our end. Please try again.",
                request, response);
    }

    private ModelAndView respond(HttpStatus status, String message, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        if (request.getRequestURI().startsWith("/api/")) {
            response.setStatus(status.value());
            response.setContentType("application/json");
            objectMapper.writeValue(response.getWriter(),
                    new ApiError(status.value(), status.getReasonPhrase(), message, LocalDateTime.now()));
            return null;
        }
        ModelAndView mav = new ModelAndView("error/generic");
        mav.setStatus(status);
        mav.addObject("pageTitle", "Error");
        mav.addObject("statusCode", status.value());
        mav.addObject("message", message);
        return mav;
    }
}
