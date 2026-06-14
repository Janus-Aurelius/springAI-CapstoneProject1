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
		SpringApplication.run(SpringaiagentApplication.class, args);
	}

}
