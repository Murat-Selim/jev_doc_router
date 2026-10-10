package com.example.rag.core.ai.routing;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.example.rag.core.ai.cache.CagCacheProvider.CachedContext;
import com.example.rag.core.ai.routing.ChatRoutingClient.Strategy;
import com.example.rag.core.exceptions.types.AiProviderException;

class JevChatRoutingClientTest {

    private MockRestServiceServer server;
    private JevChatRoutingClient client;
    private final CachedContext cache = new CachedContext("cache-key", "Annual leave is 14 days.");

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("https://api.typesafe.ai/v1")
                .defaultHeaders(headers -> headers.setBearerAuth("test-key"));
        server = MockRestServiceServer.bindTo(builder).build();
        client = new JevChatRoutingClient(builder.build(), "jev-latest");
    }

    @Test
    void sendsOfficialChoiceSchemaAndReadsStrategyAndConfidence() {
        server.expect(requestTo("https://api.typesafe.ai/v1/systemone"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("jev-latest"))
                .andExpect(jsonPath("$.state.question").value("İzin kaç gün?"))
                .andExpect(jsonPath("$.state.knowledge_base.reference_excerpt").value(cache.context()))
                .andExpect(jsonPath("$.state.knowledge_base.estimated_tokens").value(6))
                .andExpect(jsonPath("$.questions.route.type").value("choice"))
                .andExpect(jsonPath("$.questions.route.criteria.RAG").exists())
                .andExpect(jsonPath("$.questions.route.criteria.CAG").exists())
                .andRespond(withSuccess(response("RAG", "0.9", "0.98", "0.02"), MediaType.APPLICATION_JSON));
        var decision = client.decide("İzin kaç gün?", cache);
        assertEquals(Strategy.RAG, decision.strategy());
        assertEquals("jev", decision.source());
        assertEquals(0.9, decision.confidence());
        server.verify();
    }

    @Test
    void samplesBeginningMiddleAndEndWithoutSendingTheWholePdfContext() {
        String context = "A".repeat(4000) + "B".repeat(4000) + "C".repeat(4000);
        server.expect(requestTo("https://api.typesafe.ai/v1/systemone"))
                .andExpect(jsonPath("$.state.knowledge_base.reference_excerpt").value(
                        "A".repeat(2000) + "\n[... omitted ...]\n" + "B".repeat(2000)
                                + "\n[... omitted ...]\n" + "C".repeat(2000)))
                .andRespond(withSuccess(response("CAG", "0.8", "0.05", "0.95"), MediaType.APPLICATION_JSON));
        assertEquals(Strategy.CAG, client.decide("Summarize", new CachedContext("key", context)).strategy());
        server.verify();
    }

    @Test
    void rejectsUnknownChoicesMissingConfidenceAndInvalidProbabilityDistributions() {
        String[] invalidResponses = {
                "{}",
                "{\"answers\":{\"route\":{\"type\":\"choice\",\"choice\":\"CAG\"}}}",
                response("DELETE", "0.9", "0.02", "0.98"),
                response("CAG", "1.1", "0.02", "0.98"),
                response("CAG", "\"0.9\"", "0.02", "0.98"),
                response("CAG", "0.9", "-0.02", "1.02"),
                response("CAG", "0.9", "0.3", "0.9"),
                response("CAG", "0.9", "0.98", "0.02")
        };
        for (String invalid : invalidResponses) {
            server.expect(requestTo("https://api.typesafe.ai/v1/systemone"))
                    .andRespond(withSuccess(invalid, MediaType.APPLICATION_JSON));
        }
        for (String ignored : invalidResponses) {
            assertThrows(AiProviderException.class, () -> client.decide("question", cache));
        }
        server.verify();
    }

    @Test
    void doesNotLeakProviderErrorBodiesOrRetryPaidCalls() {
        server.expect(requestTo("https://api.typesafe.ai/v1/systemone"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("secret PDF text and private API key"));
        var error = assertThrows(AiProviderException.class, () -> client.decide("question", cache));
        assertEquals("Jev routing request failed.", error.getMessage());
        assertNull(error.getCause());
        server.verify();
    }

    @Test
    void rejectsMissingKeyBeforeAnyNetworkRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> new JevChatRoutingClient(RestClient.builder(), " ", "jev-latest"));
    }

    private String response(String choice, String confidence, String ragProbability, String cagProbability) {
        return "{\"model\":\"jev-1.13.0\",\"answers\":{\"route\":{\"type\":\"choice\",\"choice\":\""
                + choice + "\",\"confidence\":" + confidence + ",\"probabilities\":{\"RAG\":"
                + ragProbability + ",\"CAG\":" + cagProbability + "}}}}";
    }
}
