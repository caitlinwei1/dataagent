package com.alibaba.cloud.ai.dataagent.service.datasource.handler.impl;

import com.alibaba.cloud.ai.dataagent.entity.Datasource;
import com.alibaba.cloud.ai.dataagent.enums.BizDataSourceTypeEnum;
import com.alibaba.cloud.ai.dataagent.enums.DbAccessTypeEnum;
import com.alibaba.cloud.ai.dataagent.service.datasource.handler.DatasourceTypeHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Datasource types of the hybrid deployment. Stored fields: {@code connection_url} = the gateway's base URL,
 * {@code password} = the gateway's HMAC secret (not a database credential), {@code database_name} = schema shown to the
 * model. Host and port hold the gateway's address (the meta table requires them); the database address and account live
 * only in the gateway's config.
 */
@Configuration
public class GatewayDatasourceTypeHandlers {

	static DatasourceTypeHandler handler(BizDataSourceTypeEnum type) {
		return new DatasourceTypeHandler() {
			@Override
			public String typeName() {
				return type.getTypeName();
			}

			@Override
			public String connectionType() {
				return DbAccessTypeEnum.GATEWAY.getCode();
			}

			@Override
			public String dialectType() {
				return type.getDialect();
			}

			@Override
			public boolean hasRequiredConnectionFields(Datasource datasource) {
				return datasource.getConnectionUrl() != null && !datasource.getConnectionUrl().isBlank();
			}
		};
	}

	@Bean
	DatasourceTypeHandler mysqlGatewayDatasourceTypeHandler() {
		return handler(BizDataSourceTypeEnum.MYSQL_GATEWAY);
	}

	@Bean
	DatasourceTypeHandler postgresqlGatewayDatasourceTypeHandler() {
		return handler(BizDataSourceTypeEnum.POSTGRESQL_GATEWAY);
	}

}
