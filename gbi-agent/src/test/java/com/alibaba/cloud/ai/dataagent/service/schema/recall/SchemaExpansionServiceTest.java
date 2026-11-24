package com.alibaba.cloud.ai.dataagent.service.schema.recall;

import com.alibaba.cloud.ai.dataagent.entity.LogicalRelation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
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


	/** Real relations around B4 / B6 (subset of dataset/metadata/logical_relations.json). */
	private static final List<LogicalRelation> GRAPH = List.of(rel("prd_sku", "spu_id", "prd_spu", "id"),
			rel("prd_spu", "category_id", "prd_category", "id"),
			rel("prd_category", "parent_id", "prd_category", "id"),
			rel("wms_inventory_snapshot", "sku_id", "prd_sku", "id"),
			rel("wms_inventory_snapshot", "warehouse_id", "wms_warehouse", "id"),
			rel("trd_order", "shop_id", "dim_shop", "id"), rel("trd_refund", "order_id", "trd_order", "id"),
			rel("dim_shop", "brand_id", "prd_brand", "id"), rel("dim_shop", "region_id", "dim_region", "id"),
			rel("trd_daily_shop_sales", "shop_id", "dim_shop", "id"),
			rel("trd_daily_shop_sales", "stat_date", "dim_date", "date_key"));

	@Test
	void b4BridgesSkuToCategoryThroughSpu() {
		// fine selection picked these four; prd_spu was never recalled and nobody asked for it
		List<String> out = SchemaExpansionService.bridge(GRAPH,
				new LinkedHashSet<>(List.of("wms_warehouse", "wms_inventory_snapshot", "prd_category", "prd_sku")), null);
		assertThat(out).contains("prd_spu").hasSize(5);
	}

	@Test
	void b6BridgesRefundToShopThroughOrder() {
		List<String> out = SchemaExpansionService.bridge(GRAPH, new LinkedHashSet<>(List.of("prd_brand", "dim_region",
				"dim_shop", "trd_daily_shop_sales", "dim_date", "trd_refund")), null);
		assertThat(out).contains("trd_order").hasSize(7);
	}

	@Test
	void connectedSelectionAddsNothingAndPermissionBlocksBridge() {
		var connected = new LinkedHashSet<>(List.of("trd_order", "dim_shop", "prd_brand"));
		assertThat(SchemaExpansionService.bridge(GRAPH, connected, null)).containsExactlyElementsOf(connected);
		// no path longer than MAX_BRIDGE_HOPS: brand -> shop -> order -> refund is 3 edges, still allowed;
		// refund -> order -> sku is not in the graph at all, so the category stays unconnected
		var far = new LinkedHashSet<>(List.of("trd_refund", "prd_category"));
		assertThat(SchemaExpansionService.bridge(GRAPH, far, null)).containsExactlyElementsOf(far);
	}

}
