package com.staysense.fastcosinesimilarity;

import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.test.ESIntegTestCase;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//import org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;

public class FastCosineIT extends ESIntegTestCase {
//    @Override
//    protected Collection<Class<? extends Plugin>> nodePlugins() {
//        return Arrays.asList(FastCosineSimilarityPlugin.class);
//    }
    private void printTestMessage(String message) {
        logger.info("[{}#{}]: {} test", getTestClass().getSimpleName(), getTestName(), message);
    }

    public void testMethod() throws IOException {

        createIndex("test");
        logger.info("created index");
        ensureGreen("test");
        logger.info("ensured green");

//        String mapping =
//                Strings.toString(XContentFactory.jsonBuilder().startObject()
//                        .startObject("_doc")
//                            .startObject("properties")
//                                .startObject("name")
//                                    .field("type", "text")
//                                .endObject()
//                                .startObject("vec")
//                                    .field("type", "binary")
//                                    .field("doc_values", true)
//                                .endObject()
//                            .endObject()
//                        .endObject()
//                .endObject());
        PutMappingResponse putMappingResponse = client().admin().indices()
                .preparePutMapping("test")
                .setType("_doc")
                .setSource(XContentFactory.jsonBuilder().startObject()
                        .startObject("properties")
                            .startObject("name")
                                .field("type", "text")
                            .endObject()
                            .startObject("vec")
                                .field("type", "binary")
                                .field("doc_values", true)
                            .endObject()
                        .endObject()
                .endObject())
                .execute()
                .actionGet();
        logger.info("created mapping: {}", putMappingResponse);

        List<Double> swedishPaymentsVec = Arrays.asList(0.1d, 0.2d);

        byte[] swedishPaymentsVecBytes = getBinaryVec(swedishPaymentsVec);
        byte[] swedishPaymentsVecB64 = Base64.getEncoder().encode(swedishPaymentsVecBytes);
        logger.info("swedishPaymentsVec [Bytes = {}, B64 = {}]", swedishPaymentsVecBytes, swedishPaymentsVecB64);
//        byte[] expectedBytes = new byte[2 * 8];
//        assertEquals(expectedBytes, swedishPaymentsVecBytes.array());
        client().prepareIndex("test", "_doc", "swedish-payments")
                .setSource(
                        XContentFactory.jsonBuilder().startObject()
                                .field("name", "Swedish Payments")
                                .field("vec", swedishPaymentsVecBytes)
                                .endObject()
                )
                .execute().actionGet();

        refresh("test");

        Map<String, Object> params = new HashMap<>();
        params.put("field", "vec");
        params.put(
                "encoded_vector",
                new String(swedishPaymentsVecB64, StandardCharsets.UTF_8)
        );

        SearchResponse searchResponse = client().prepareSearch("test")
                .setQuery(
                        QueryBuilders.functionScoreQuery(
                                ScoreFunctionBuilders.scriptFunction(new Script(
                                        ScriptType.INLINE,
                                        "fast_cosine",
                                        "staysense",
                                        params
                                ))
                        )
                ).execute().actionGet();
        assertEquals(1, searchResponse.getHits().totalHits);

        SearchHit searchHit = searchResponse.getHits().getAt(0);

        Map<String, Object> expectedHitSource = new HashMap<>();
        expectedHitSource.put("name", "Swedish Payments");
        expectedHitSource.put("vec", new String(swedishPaymentsVecB64, StandardCharsets.UTF_8));

        assertEquals(expectedHitSource, searchHit.getSourceAsMap());
    }

    private byte[] getBinaryVec(List<Double> doubles) {
        ByteBuffer buf = ByteBuffer.allocate(doubles.size() * Double.BYTES);
        for (Double item : doubles) {
            buf.putDouble(item);
        }
        buf.rewind();
        return buf.array();
    }
}
