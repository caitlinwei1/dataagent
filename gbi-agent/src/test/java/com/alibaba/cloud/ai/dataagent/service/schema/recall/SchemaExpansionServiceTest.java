package com.alibaba.cloud.ai.dataagent.service.schema.recall;

import com.alibaba.cloud.ai.dataagent.entity.LogicalRelation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaExpansionServiceTest {

	private static LogicalRelation rel(String s, String sc, String t, String tc) {
		LogicalRelation r = new LogicalRelation();
		r.setSourceTableName(s);
		r.setSourceColumnName(sc);
		r.setTargetTableName(t);
		r.setTargetColumnName(tc);
		return r;
	}

	private static final List<LogicalRelation> FKS = List.of(rel("prd_sku", "spu_id", "prd_spu", "id"),
			rel("prd_spu", "category_id", "prd_category", "id"), rel("wms_inventory_snapshot", "sku_id", "prd_sku", "id"),
			rel("wms_inventory_snapshot", "warehouse_id", "dim_warehouse", "id"),
			rel("trd_order_item", "sku_id", "prd_sku", "id"));

	@Test
	void b4AddsSpuOnFirstHopAndCategoryOnSecond() {
		// B4: SQL used snapshot + sku + warehouse, guessed prd_sku.category_id
		List<String> first = SchemaExpansionService.neighbours(FKS,
				Set.of("wms_inventory_snapshot", "prd_sku", "dim_warehouse"),
				Set.of("wms_inventory_snapshot", "prd_sku", "dim_warehouse"), null);
		assertThat(first).contains("prd_spu").doesNotContain("prd_category");
		List<String> second = SchemaExpansionService.neighbours(FKS,
				Set.of("wms_inventory_snapshot", "prd_sku", "prd_spu"),
				Set.of("wms_inventory_snapshot", "prd_sku", "dim_warehouse", "prd_spu", "trd_order_item"), null);
		assertThat(second).containsExactly("prd_category");
	}

}
