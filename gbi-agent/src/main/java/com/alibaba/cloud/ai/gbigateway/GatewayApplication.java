package com.alibaba.cloud.ai.gbigateway;

import com.alibaba.cloud.ai.dataagent.connector.accessor.AccessorFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Local data gateway, the "数据在地" half of the hybrid deployment. Same jar as the cloud service, different main class
 * and a deliberately small context: web server + the JDBC connectors, nothing else (no meta DB, no LLM, no vectors).
 *
 * <pre>
 * java -cp gbi-agent.jar -Dloader.main=com.alibaba.cloud.ai.gbigateway.GatewayApplication \
 *      org.springframework.boot.loader.launch.PropertiesLauncher --spring.config.name=gateway
 * </pre>
 */
@SpringBootConfiguration
@ImportAutoConfiguration({ PropertyPlaceholderAutoConfiguration.class, ConfigurationPropertiesAutoConfiguration.class,
		ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
		WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class })
@ComponentScan(basePackages = { "com.alibaba.cloud.ai.dataagent.connector", "com.alibaba.cloud.ai.gbigateway" })
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

	@Bean
	GatewayService gatewayService(GatewayProperties props, AccessorFactory accessors) {
		return new GatewayService(props, accessors);
	}

	public static void main(String[] args) {
		SpringApplication.run(GatewayApplication.class, args);
	}

}
