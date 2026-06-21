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
		String pubKey = cleanValue(System.getenv("LANGFUSE_PUBLIC_KEY"));
		String secKey = cleanValue(System.getenv("LANGFUSE_SECRET_KEY"));
		String host = cleanValue(System.getenv("LANGFUSE_HOST"));
		if (host == null || host.isEmpty()) {
			host = cleanValue(System.getenv("LANGFUSE_BASE_URL"));
		}
		if (host == null || host.isEmpty()) {
			host = "https://us.cloud.langfuse.com";
		}

		if (pubKey != null && !pubKey.isEmpty() && secKey != null && !secKey.isEmpty()) {
			String rawCreds = pubKey + ":" + secKey;
			String base64 = java.util.Base64.getEncoder().encodeToString(
					rawCreds.getBytes(java.nio.charset.StandardCharsets.UTF_8)
			);
			System.setProperty("LANGFUSE_OTEL_AUTH", "Basic " + base64);
			System.out.println("LANGFUSE CONNECTION: Dynamically generated OTel Auth header from public & secret keys.");
		}

		String endpoint = host;
		if (!endpoint.endsWith("/api/public/otel/v1/traces")) {
			if (endpoint.endsWith("/api/public/otel")) {
				endpoint += "/v1/traces";
			} else {
				if (endpoint.endsWith("/")) {
					endpoint += "api/public/otel/v1/traces";
				} else {
					endpoint += "/api/public/otel/v1/traces";
				}
			}
		}
		System.setProperty("LANGFUSE_OTEL_ENDPOINT", endpoint);
		System.out.println("LANGFUSE CONNECTION: Dynamically set OTel endpoint to " + endpoint);
	}

	private static String cleanValue(String val) {
		if (val == null) return null;
		val = val.trim();
		if (val.startsWith("\"") && val.endsWith("\"")) {
			val = val.substring(1, val.length() - 1);
		}
		if (val.startsWith("'") && val.endsWith("'")) {
			val = val.substring(1, val.length() - 1);
		}
		return val.trim();
	}

}
