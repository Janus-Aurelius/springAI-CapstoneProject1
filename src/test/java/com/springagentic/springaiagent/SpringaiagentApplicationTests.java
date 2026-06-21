package com.springagentic.springaiagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude=" +
				"org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
				"org.springframework.ai.model.chat.memory.redis.autoconfigure.RedisChatMemoryAutoConfiguration," +
				"org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreAutoConfiguration," +
				"org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration",
		"spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
		"spring.jpa.hibernate.ddl-auto=update"
})
class SpringaiagentApplicationTests {

	@Test
	void contextLoads() {
		System.out.println("=== REFLECTING MODELOPTIONSUTILS ===");
		try {
			Class<?> clazz = Class.forName("org.springframework.ai.model.ModelOptionsUtils");
			for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
				if (m.getName().toLowerCase().contains("schema") || m.getName().toLowerCase().contains("json")) {
					System.out.print("Method: " + m.getName() + " (");
					for (Class<?> p : m.getParameterTypes()) {
						System.out.print(p.getSimpleName() + ", ");
					}
					System.out.println(") -> " + m.getReturnType().getSimpleName());
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.out.println("=====================================");
	}

}
