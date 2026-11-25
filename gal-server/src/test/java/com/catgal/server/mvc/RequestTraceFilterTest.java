package com.catgal.server.mvc;

import com.catgal.common.domain.R;
import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import static com.catgal.common.constants.Constant.REQUEST_ID_HEADER;
import static org.junit.jupiter.api.Assertions.*;

class RequestTraceFilterTest {
    private final RequestTraceFilter filter = new RequestTraceFilter();
    @AfterEach void clear() { MDC.clear(); }
    @Test void headerBodyAndAuditContextShareTheSameTrace() throws Exception {
        var request = new MockHttpServletRequest(); request.addHeader("X-Request-ID", "owned-request");
        var response = new MockHttpServletResponse(); var body = new AtomicReference<R<Void>>();
        filter.doFilter(request, response, (req, res) -> { assertEquals("owned-request", MDC.get(REQUEST_ID_HEADER)); body.set(R.ok()); });
        assertEquals("owned-request", body.get().getRequestId()); assertEquals("owned-request", response.getHeader("X-Request-ID"));
        assertNull(MDC.get(REQUEST_ID_HEADER));
    }
    @Test void unsafeAndOverlongHeadersAreReplaced() throws Exception {
        for (String value : new String[]{"bad\r\nvalue", "x".repeat(65), ""}) {
            var request = new MockHttpServletRequest(); request.addHeader("X-Request-ID", value);
            var response = new MockHttpServletResponse(); filter.doFilter(request, response, (req, res) -> { });
            assertTrue(response.getHeader("X-Request-ID").matches("[0-9a-f-]{36}")); assertNull(MDC.get(REQUEST_ID_HEADER));
        }
    }
    @Test void staleThreadContextDoesNotLeakIntoANewRequest() throws Exception {
        MDC.put(REQUEST_ID_HEADER, "old-thread"); var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> assertNotEquals("old-thread", MDC.get(REQUEST_ID_HEADER)));
        assertNull(MDC.get(REQUEST_ID_HEADER));
    }
    @Test void failuresAlsoClearTheContext() {
        assertThrows(ServletException.class, () -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> { throw new ServletException("isolated failure"); }));
        assertNull(MDC.get(REQUEST_ID_HEADER));
    }
    @Test void existingProjectHeaderCanBeCorrelated() throws Exception {
        var request = new MockHttpServletRequest(); request.addHeader(REQUEST_ID_HEADER, "legacy-request");
        var response = new MockHttpServletResponse(); filter.doFilter(request, response, (req, res) -> { });
        assertEquals("legacy-request", response.getHeader("X-Request-ID"));
    }
}
