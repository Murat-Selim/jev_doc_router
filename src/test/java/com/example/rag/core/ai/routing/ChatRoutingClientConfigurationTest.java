package com.example.rag.core.ai.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class ChatRoutingClientConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withUserConfiguration(RulesChatRoutingClient.class, JevChatRoutingClient.class);

    @Test
    void defaultIsLocalRulesEvenWhenAKeyExists() {
        context.withPropertyValues("ai.routing.jev.api-key=test-key").run(app -> {
            assertThat(app).hasSingleBean(ChatRoutingClient.class);
            assertThat(app.getBean(ChatRoutingClient.class)).isInstanceOf(RulesChatRoutingClient.class);
        });
    }

    @Test
    void explicitJevModeCreatesRealHttpClient() {
        context.withPropertyValues("ai.routing.provider=jev", "ai.routing.jev.api-key=test-key").run(app -> {
            assertThat(app).hasSingleBean(ChatRoutingClient.class);
            assertThat(app.getBean(ChatRoutingClient.class)).isInstanceOf(JevChatRoutingClient.class);
        });
    }

    @Test
    void explicitJevModeWithoutKeyFailsClearlyAtStartup() {
        context.withPropertyValues("ai.routing.provider=jev").run(app -> assertThat(app).hasFailed());
    }
}
