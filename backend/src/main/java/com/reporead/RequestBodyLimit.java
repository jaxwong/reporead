package com.reporead;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * The most of a request body the server reads: every JSON body is read up to here before anything parses it, and one
 * past it is refused (413 REQUEST_TOO_LARGE), so no request can make the server parse or hold more, including the
 * sign-in token route, which needs no session. The largest valid body, a card, holds three texts of at most 10,000
 * UTF-16 units (AnnotationController.MAX_TEXT_CHARS): under 180 KB with every unit a 6-byte JSON escape.
 */
@ControllerAdvice
class RequestBodyLimit extends RequestBodyAdviceAdapter {
    static final int MAX_BYTES = 262_144;

    @Override
    public boolean supports(
            MethodParameter parameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage input,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        byte[] body = input.getBody().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            throw new ApiFailure(
                    ApiFailure.Code.REQUEST_TOO_LARGE, "A request body may be at most " + MAX_BYTES + " bytes.");
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
        };
    }
}
