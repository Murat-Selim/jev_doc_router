package com.example.rag.core.ai.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.client.RestClient;

import com.example.rag.core.ai.cache.CagCacheProvider;
import com.example.rag.core.ai.cache.InMemoryCagCacheProvider;

class DocumentChatClientConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withUserConfiguration(MockDocumentChatClient.class, OpenAiDocumentChatClient.class,
                    InMemoryCagCacheProvider.class);

    @Test
    void mockIsDefaultAndDoesNotRequireCredentials() {
        context.run(app -> {
            assertThat(app).hasSingleBean(DocumentChatClient.class).hasSingleBean(CagCacheProvider.class);
            assertThat(app).hasSingleBean(TaskExecutor.class);
            assertThat(app.getBean(DocumentChatClient.class)).isInstanceOf(MockDocumentChatClient.class);
        });
    }

    @Test
    void openaiMustBeExplicitlyEnabled() {
        context.withPropertyValues("ai.provider=openai", "ai.openai.api-key=test-placeholder")
                .run(app -> {
                    assertThat(app).hasSingleBean(DocumentChatClient.class);
                    assertThat(app.getBean(DocumentChatClient.class)).isInstanceOf(OpenAiDocumentChatClient.class);
                });
    }

    @Test
    void missingCredentialsFailConfigurationRatherThanSilentlyUsingMock() {
        context.withPropertyValues("ai.provider=openai").run(app -> assertThat(app).hasFailed());
    }

}
