package com.alibaba.cloud.ai.dataagent.connector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResultSetLabelTest {

	@Test
	void b6DuplicateNameColumnsKeepTheirTable() {
		String[] head = { "id", "name", "name", "big_region", "name", "shortage_qty" };
		String[] tables = { "trd_order", "dim_shop", "prd_brand", "dim_region", "wms_warehouse", "wms_stockout_record" };
		ResultSetBuilder.uniqueLabels(head, i -> tables[i]);
		assertThat(head).containsExactly("id", "dim_shop.name", "prd_brand.name", "big_region", "wms_warehouse.name",
				"shortage_qty");
	}

	@Test
	void withoutTableNamesDuplicatesAreNumbered() {
		String[] head = { "name", "name", "cnt" };
		ResultSetBuilder.uniqueLabels(head, i -> null);
		assertThat(head).containsExactly("name", "name_1", "cnt");
	}

}
