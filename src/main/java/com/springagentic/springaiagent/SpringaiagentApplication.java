package com.springagentic.springaiagent;

import com.springagentic.springaiagent.framework.config.LlmProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(excludeName = {
	"org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
	"org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
	"org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
	"org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
	"org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
	"org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
})
@EnableConfigurationProperties(LlmProperties.class)
public class SpringaiagentApplication {

	public static void main(String[] args) {
		configureLangfuseCloud();
		SpringApplication.run(SpringaiagentApplication.class, args);
	}

	private static void configureLangfuseCloud() {
		String pubKey = System.getenv("LANGFUSE_PUBLIC_KEY");
		String secKey = System.getenv("LANGFUSE_SECRET_KEY");
		String host = System.getenv("LANGFUSE_HOST");

		if (pubKey != null && !pubKey.isBlank() && secKey != null && !secKey.isBlank()) {
			String rawCreds = pubKey.trim() + ":" + secKey.trim();
			String base64 = java.util.Base64.getEncoder().encodeToString(
					rawCreds.getBytes(java.nio.charset.StandardCharsets.UTF_8)
			);
			System.setProperty("LANGFUSE_OTEL_AUTH", "Basic " + base64);
			System.out.println("LANGFUSE CONNECTION: Dynamically generated OTel Auth header from public & secret keys.");
		}

		if (host != null && !host.isBlank()) {
			String endpoint = host.trim();
			if (!endpoint.endsWith("/api/public/otel")) {
				if (endpoint.endsWith("/")) {
					endpoint += "api/public/otel";
				} else {
					endpoint += "/api/public/otel";
				}
			}
			System.setProperty("LANGFUSE_OTEL_ENDPOINT", endpoint);
			System.out.println("LANGFUSE CONNECTION: Dynamically set OTel endpoint to " + endpoint);
		}
	}

}
