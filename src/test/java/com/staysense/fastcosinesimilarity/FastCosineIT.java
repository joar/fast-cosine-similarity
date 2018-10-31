package com.staysense.fastcosinesimilarity;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.test.ESIntegTestCase;

import java.io.IOException;
import java.net.URISyntaxException;
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

    public void testMethod() throws IOException, URISyntaxException {

        createIndex("test");

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
        client().admin().indices()
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

        List<Double> swedishPaymentsVec = Arrays.asList(0.1d, 0.2d);

        ByteBuffer swedishPaymentsBinaryVec = getBinaryVec(swedishPaymentsVec);
        client().prepareIndex("test", "_doc", "swedish-payments")
                .setSource(
                        XContentFactory.jsonBuilder().startObject()
                                .field("name", "Swedish Payments")
                                .field("vec", swedishPaymentsBinaryVec.array())
                                .endObject()
                )
                .execute().actionGet();

        Map<String, Object> params = new HashMap<>();
        params.put("field", "vec");
        params.put(
                "encoded_vector",
                new String(Base64.getEncoder().encode(swedishPaymentsBinaryVec).array(), StandardCharsets.UTF_8)
        );

        SearchResponse searchResponse = client().prepareSearch("test")
                .setQuery(
                        QueryBuilders.functionScoreQuery(
                                ScoreFunctionBuilders.scriptFunction(new Script(
                                        ScriptType.INLINE,
                                        "staysense",
                                        "fast_cosine",
                                        params
                                ))
                        )
                ).execute().actionGet();
        assert searchResponse.getHits().totalHits == 1;
        SearchHit searchHit = searchResponse.getHits().getAt(0);
        Map<String, Object> expectedHitSource = new HashMap<>();
        expectedHitSource.put("name", "Swedish Payments");
        expectedHitSource.put("vec", swedishPaymentsBinaryVec.array());
        assertEquals(searchHit.getSourceAsMap(), expectedHitSource);
    }

    private ByteBuffer getBinaryVec(List<Double> doubleArray) {
        ByteBuffer buf = ByteBuffer.allocate(doubleArray.size() * Double.BYTES);
        for (Double item : doubleArray) {
            buf.putDouble(item);
        }
        return buf;
    }
}
