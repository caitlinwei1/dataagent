package com.alibaba.cloud.ai.dataagent.service.schema.recall;

/**
 * A datasource's schema vectors or logical relations changed; recall caches built from them must be rebuilt.
 */
public record SchemaChangedEvent(Integer datasourceId) {
}
