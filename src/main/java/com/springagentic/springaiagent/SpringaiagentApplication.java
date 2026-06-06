package com.springagentic.springaiagent;

import com.springagentic.springaiagent.framework.config.LlmProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(excludeName = "org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration")
@EnableConfigurationProperties(LlmProperties.class)
public class SpringaiagentApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringaiagentApplication.class, args);
	}

}
