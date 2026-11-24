package com.alibaba.cloud.ai.dataagent.workflow.node;

import com.alibaba.cloud.ai.dataagent.vo.BusinessKnowledgeVO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceLiteralTermTest {

	private static BusinessKnowledgeVO term(String name, String syn, String desc) {
		BusinessKnowledgeVO k = new BusinessKnowledgeVO();
		k.setBusinessTerm(name);
		k.setSynonyms(syn);
		k.setDescription(desc);
		k.setIsRecall(true);
		return k;
	}

	private static final List<BusinessKnowledgeVO> TERMS = List.of(
			term("销售额", "营业额,销售收入", "按实付口径，排除取消/关闭订单"),
			term("GMV", "成交总额,交易额", "已支付订单的商品原价总额加运费，不按 order_status 过滤"),
			term("2025双11", "", "营销活动取值，对应 mkt_activity.name = '2025双11'"),
			term("退款率", "", "退款订单 / 支付订单"));

	@Test
	void b6MetricNamedInQuestionIsAlwaysEvidence() {
		List<Document> docs = EvidenceRecallNode
			.literalTermDocuments("曜石品牌在华东的销售额，2026年10月12日到18日这一周比前一周下降了多少", TERMS);
		assertThat(docs).extracting(d -> d.getMetadata().get("term")).containsExactly("销售额");
		assertThat(docs.get(0).getText()).startsWith("业务名词: 销售额, 说明: ");
	}

	@Test
	void b5EnumValuesAreLeftToTheVectorChannel() {
		List<Document> docs = EvidenceRecallNode.literalTermDocuments("2025双11期间各品牌的gmv是多少", TERMS);
		assertThat(docs).extracting(d -> d.getMetadata().get("term")).containsExactly("GMV");
	}

}
