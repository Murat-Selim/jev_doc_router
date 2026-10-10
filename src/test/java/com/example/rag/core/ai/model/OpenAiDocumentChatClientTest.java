package com.example.rag.core.ai.model;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.example.rag.core.exceptions.types.AiProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenAiDocumentChatClientTest {

    private MockRestServiceServer server;
    private OpenAiDocumentChatClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode prewarmRequest;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiDocumentChatClient(builder.build(), "gpt-6.1-sol", 4096);
    }

    @Test
    void prewarmAndAnswerUseSamePrefixKeyAndExplicitCacheBoundary() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(jsonPath("$.model").value("gpt-6.1-sol"))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.prompt_cache_options.prewarm").value(true))
                .andExpect(jsonPath("$.prompt_cache_options.mode").value("explicit"))
                .andExpect(jsonPath("$.input[1].content[0].prompt_cache_breakpoint.mode").value("explicit"))
                .andExpect(jsonPath("$.input[2]").doesNotExist())
                .andExpect(request -> prewarmRequest = readRequest(request))
                .andRespond(withSuccess("""
                        {"status":"completed","output":[],"usage":{"input_tokens":2000,
                        "input_tokens_details":{"cache_write_tokens":2000,"cached_tokens":0}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(jsonPath("$.input[2].content").value("Question:\nİzin kaç gün?"))
                .andExpect(jsonPath("$.max_output_tokens").value(4096))
                .andExpect(jsonPath("$.prompt_cache_options.prewarm").doesNotExist())
                .andExpect(request -> {
                    JsonNode answerRequest = readRequest(request);
                    assertEquals(prewarmRequest.path("prompt_cache_key"), answerRequest.path("prompt_cache_key"));
                    assertEquals(prewarmRequest.path("input").get(0), answerRequest.path("input").get(0));
                    assertEquals(prewarmRequest.path("input").get(1), answerRequest.path("input").get(1));
                })
                .andRespond(withSuccess("""
                        {"status":"completed","output":[
                        {"type":"reasoning","summary":[]},
                        {"type":"message","role":"assistant","content":[
                        {"type":"output_text","text":"14 gün."}]}],
                        "usage":{"input_tokens":2010,"input_tokens_details":{"cached_tokens":2000}}}
                        """, MediaType.APPLICATION_JSON));

        client.preload("Yıllık izin 14 gündür.", "cache-1");
        assertEquals("14 gün.", client.answer("Question:\nİzin kaç gün?", "Yıllık izin 14 gündür.", "cache-1"));
        server.verify();
    }

    @Test
    void ragAnswerOmitsCagCacheControls() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(jsonPath("$.prompt_cache_key").doesNotExist())
                .andExpect(jsonPath("$.prompt_cache_options").doesNotExist())
                .andExpect(jsonPath("$.input[1].content[0].prompt_cache_breakpoint").doesNotExist())
                .andRespond(withSuccess("""
                        {"status":"completed","output":[{"type":"message","role":"assistant",
                        "content":[{"type":"output_text","text":"Belgede cevap yok."}]}]}
                        """, MediaType.APPLICATION_JSON));
        assertEquals("Belgede cevap yok.", client.answer("Question:\nSoru", "context", null));
        server.verify();
    }

    @Test
    void providerErrorsDoNotExposeRemoteErrorBody() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("secret document text and sk-private-key").contentType(MediaType.TEXT_PLAIN));
        var error = assertThrows(AiProviderException.class,
                () -> client.answer("question", "context", "cache-1"));
        assertFalse(error.getMessage().contains("secret"));
        assertFalse(error.getMessage().contains("sk-private"));
        assertNull(error.getCause());
        server.verify();
    }

    @Test
    void incompleteResponseAndMissingAnswerAreReportedAsProviderErrors() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withSuccess("{\"status\":\"incomplete\",\"output\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withSuccess("{\"status\":\"completed\",\"output\":[]}", MediaType.APPLICATION_JSON));
        assertThrows(AiProviderException.class, () -> client.preload("context", "cache-1"));
        assertThrows(AiProviderException.class, () -> client.answer("question", "context", "cache-1"));
        server.verify();
    }

    @Test
    void rejectsMissingApiKeyBeforeAnyNetworkRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiDocumentChatClient(RestClient.builder(), " ", "gpt-6.1-sol", 4096));
    }

    private JsonNode readRequest(org.springframework.http.client.ClientHttpRequest request) {
        try {
            return mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
    }

}
