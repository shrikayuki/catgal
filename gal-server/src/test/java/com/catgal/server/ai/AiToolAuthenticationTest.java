package com.catgal.server.ai;

import com.catgal.server.ai.tools.AiCatalogToolService;
import com.catgal.server.ai.tools.AiToolAuthenticationFilter;
import com.catgal.server.ai.tools.AiToolProperties;
import com.catgal.server.controller.AiToolController;
import com.catgal.server.controller.AiVectorCatalogController;
import com.catgal.server.ai.vector.AiVectorCatalogService;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiToolAuthenticationTest {
    private static final String SECRET = "test-secret-that-is-long-enough-for-hmac";
    private static final String PATH = "/internal/ai/tools/search-games";
    private final AiCatalogToolService tools = mock(AiCatalogToolService.class);
    private final AiServiceRequestSigner signer = new AiServiceRequestSigner();
    private final AiToolProperties properties = new AiToolProperties();
    private final AiServiceProperties service = new AiServiceProperties();
    private final AiVectorCatalogService catalog = mock(AiVectorCatalogService.class);

    private MockMvc mvc() {
        service.setHmacSecret(SECRET);
        properties.setEnabled(true);
        return MockMvcBuilders.standaloneSetup(new AiToolController(tools), new AiVectorCatalogController(catalog))
                .addFilters(new AiToolAuthenticationFilter(properties, service, signer, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private MockHttpServletRequestBuilder signed(String path, String body, String nonce, long timestamp) {
        String time = String.valueOf(timestamp);
        String signature = signer.sign(SECRET, time, nonce, "POST", path, body.getBytes(StandardCharsets.UTF_8));
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Catgal-Timestamp", time).header("X-Catgal-Nonce", nonce)
                .header("X-Catgal-Signature", signature);
    }

    private MockHttpServletRequestBuilder signed(String body) {
        return signed(PATH, body, UUID.randomUUID().toString(), Instant.now().getEpochSecond());
    }

    @Test void signedBodyIsReplayableByMvcAndUsesTrustedIdentity() throws Exception {
        MockMvc mvc = mvc();
        when(tools.search(any())).thenReturn(new AiCatalogToolService.SearchResult(List.of(), false));
        mvc.perform(signed("{\"userId\":7,\"keyword\":\"治愈\",\"limit\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
        verify(tools).search(argThat(request -> request.userId() == 7L && "治愈".equals(request.keyword())));
    }

    @Test void missingSignatureAndJwtAloneCannotInvokeTools() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer user-token")
                        .content("{\"userId\":7}"))
                .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        verifyNoInteractions(tools);
    }

    @Test void expiredAndTamperedBodiesAreRejected() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(signed(PATH, "{\"userId\":7}", UUID.randomUUID().toString(), Instant.now().minusSeconds(61).getEpochSecond()))
                .andExpect(status().isUnauthorized());
        MockHttpServletRequestBuilder request = signed("{\"userId\":7}");
        request.content("{\"userId\":8}");
        mvc.perform(request).andExpect(status().isUnauthorized());
        verifyNoInteractions(tools);
    }

    @Test void sameNonceCannotBeReplayedEvenWithValidSignature() throws Exception {
        MockMvc mvc = mvc();
        when(tools.search(any())).thenReturn(new AiCatalogToolService.SearchResult(List.of(), false));
        String nonce = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();
        mvc.perform(signed(PATH, "{\"userId\":7}", nonce, now)).andExpect(status().isOk());
        mvc.perform(signed(PATH, "{\"userId\":7}", nonce, now)).andExpect(status().isConflict());
        verify(tools, times(1)).search(any());
    }

    @Test void limitAndIdentityTypesAreValidatedBeforeService() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(signed("{\"userId\":7,\"limit\":11}")).andExpect(status().isBadRequest());
        mvc.perform(signed("{\"userId\":\"7\"}")).andExpect(status().isBadRequest());
        mvc.perform(signed("{\"userId\":7.5}")).andExpect(status().isBadRequest());
        mvc.perform(signed("{\"userId\":0}")).andExpect(status().isBadRequest());
        verifyNoInteractions(tools);
    }

    @Test void featureSwitchAndPayloadBoundsAreEnforced() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(signed("{\"userId\":7,\"padding\":\"" + "x".repeat(65_536) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        properties.setEnabled(false);
        mvc.perform(signed("{\"userId\":7}")).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(tools);
    }

    @Test void validSignatureCannotChooseAnotherOrWriteOperationPath() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(signed("/internal/ai/tools/delete-game", "{\"userId\":7}", UUID.randomUUID().toString(), Instant.now().getEpochSecond()))
                .andExpect(status().isNotFound());
        mvc.perform(get(PATH)).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(tools);
    }

    @Test void serviceWorkerUsesFixedServiceIdentityWithoutUserAndIndependentFlag() throws Exception {
        MockMvc mvc = mvc();
        properties.setEnabled(false);
        properties.setSemanticSearchEnabled(true);
        when(catalog.pending(20)).thenReturn(new AiVectorCatalogService.Pending(List.of(), false));
        String path = "/internal/ai/vector-catalog/pending";
        mvc.perform(signed(path, "{}", UUID.randomUUID().toString(), Instant.now().getEpochSecond()))
                .andExpect(status().isOk());
        mvc.perform(signed(path, "{\"userId\":7}", UUID.randomUUID().toString(), Instant.now().getEpochSecond()))
                .andExpect(status().isBadRequest());
        properties.setSemanticSearchEnabled(false);
        mvc.perform(signed(path, "{}", UUID.randomUUID().toString(), Instant.now().getEpochSecond()))
                .andExpect(status().isServiceUnavailable());
        verify(catalog, times(1)).pending(20);
        verifyNoInteractions(tools);
    }

    @Test void serviceSignatureCannotOmitActorOnUserValidationOrReplayAcrossPaths() throws Exception {
        MockMvc mvc = mvc();
        properties.setSemanticSearchEnabled(true);
        String path = "/internal/ai/tools/validate-games";
        mvc.perform(signed(path, "{\"candidates\":[]}", UUID.randomUUID().toString(), Instant.now().getEpochSecond()))
                .andExpect(status().isBadRequest());
        when(tools.validate(any())).thenReturn(new AiCatalogToolService.ValidationResult(List.of()));
        String nonce = UUID.randomUUID().toString();
        long time = Instant.now().getEpochSecond();
        mvc.perform(signed(path, "{\"userId\":7,\"candidates\":[]}", nonce, time)).andExpect(status().isOk());
        mvc.perform(signed("/internal/ai/vector-catalog/status", "{}", nonce, time)).andExpect(status().isConflict());
        properties.setSemanticSearchEnabled(false);
        mvc.perform(signed(path, "{\"userId\":7,\"candidates\":[]}", UUID.randomUUID().toString(), time))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(catalog);
    }

    @Test void catalogBoundsAndFailureCodesAreValidatedBeforeService() throws Exception {
        MockMvc mvc = mvc();
        properties.setSemanticSearchEnabled(true);
        for (String[] input : List.of(new String[]{"pending", "{\"limit\":101}"},
                new String[]{"export", "{\"gameIds\":[]}"}, new String[]{"export", "{\"gameIds\":[0]}"},
                new String[]{"ack", "{\"items\":[{\"gameId\":1,\"version\":0}]}"},
                new String[]{"failed", "{\"items\":[{\"gameId\":1,\"version\":1}],\"errorCode\":\"private text\"}"})) {
            mvc.perform(signed("/internal/ai/vector-catalog/" + input[0], input[1], UUID.randomUUID().toString(),
                    Instant.now().getEpochSecond())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(catalog);
    }
}
