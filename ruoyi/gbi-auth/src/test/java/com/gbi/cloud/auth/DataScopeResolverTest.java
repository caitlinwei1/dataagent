package com.gbi.cloud.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.gbi.cloud.common.security.DataScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Same org as ruoyi/sql/gbi_system.sql. */
class DataScopeResolverTest {

	static final List<DataScopeResolver.Dept> DEPTS = List.of(new DataScopeResolver.Dept(100, "0"),
			new DataScopeResolver.Dept(101, "0,100"), new DataScopeResolver.Dept(102, "0,100"),
			new DataScopeResolver.Dept(103, "0,100,101"), new DataScopeResolver.Dept(104, "0,100,101"),
			new DataScopeResolver.Dept(105, "0,100,101"), new DataScopeResolver.Dept(106, "0,100,101"),
			new DataScopeResolver.Dept(107, "0,100,102"), new DataScopeResolver.Dept(108, "0,100,102"));

	static final Map<Long, Map<String, Set<String>>> DATA = Map.of(103L,
			Map.of("shop", Set.of("1", "2", "3", "7", "10")), 104L, Map.of("warehouse", Set.of("1")), 105L,
			Map.of("warehouse", Set.of("3")), 106L, Map.of("warehouse", Set.of("7")), 107L,
			Map.of("shop", Set.of("4", "11")), 108L, Map.of("warehouse", Set.of("4", "8")));

	static DataScope resolve(long dept, String scope, Map<Long, Set<Long>> roleDepts, Set<String> tables) {
		return DataScopeResolver.resolve(dept, List.of(new DataScopeResolver.Role(9, "r", scope)), roleDepts, DEPTS,
				DATA, Map.of(9L, tables));
	}

	@Test
	void all() {
		DataScope s = resolve(100, "1", Map.of(), Set.of("*"));
		assertThat(s.isAllRows()).isTrue();
		assertThat(s.isAllTables()).isTrue();
	}

	@Test
	void deptAndChildren() {
		DataScope s = resolve(101, "4", Map.of(), Set.of("*"));
		assertThat(s.isAllRows()).isFalse();
		assertThat(s.getDims().get("shop")).containsExactly("1", "10", "2", "3", "7");
		assertThat(s.getDims().get("warehouse")).containsExactly("1", "3", "7");
	}

	@Test
	void ownDeptOnly() {
		DataScope s = resolve(106, "3", Map.of(), Set.of("wms_*"));
		assertThat(s.getDims()).containsOnlyKeys("warehouse");
		assertThat(s.getDims().get("warehouse")).containsExactly("7");
		assertThat(s.isAllTables()).isFalse();
		assertThat(s.getTablePatterns()).containsExactly("wms_*");
	}

	@Test
	void custom() {
		DataScope s = resolve(103, "2", Map.of(9L, Set.of(103L, 107L)), Set.of("trd_*"));
		assertThat(s.getDims().get("shop")).containsExactly("1", "10", "11", "2", "3", "4", "7");
		assertThat(s.getDims()).doesNotContainKey("warehouse");
	}

	@Test
	void selfOnlyResolvesToNoRows() {
		DataScope s = resolve(100, "5", Map.of(), Set.of("*"));
		assertThat(s.isAllRows()).isFalse();
		assertThat(s.getDims()).isEmpty();
	}

	@Test
	void rolesAreOred() {
		DataScope s = DataScopeResolver.resolve(106L,
				List.of(new DataScopeResolver.Role(3, "wh_mgr", "3"), new DataScopeResolver.Role(4, "finance", "1")),
				Map.of(), DEPTS, DATA, Map.of(3L, Set.of("wms_*"), 4L, Set.of("fin_*")));
		assertThat(s.isAllRows()).isTrue();
		assertThat(s.getTablePatterns()).containsExactly("fin_*", "wms_*");
	}

}
