package com.staysense.fastcosinesimilarity;

import com.carrotsearch.randomizedtesting.generators.RandomNumbers;
import com.carrotsearch.randomizedtesting.generators.RandomStrings;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public abstract class FastCosIntegTestCase extends ESIntegTestCase {
    /**
     * Duplicated from ESIntegTestCase
     */
    private static boolean isSuiteScopedTest(Class<?> clazz) {
        return clazz.getAnnotation(SuiteScopeTestCase.class) != null;
    }

    public String getLogPrefix() {
        if (isSuiteScopedTest(getClass()) && (getTestName().equals("<unknown>"))) {
            return getTestClass().getSimpleName();
        } else {
            return String.format(Locale.ROOT, "%s#%s", getTestClass().getSimpleName(), getTestName());
        }
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(FastCosineSimilarityPlugin.class);
    }

    public byte[] getBinaryVec(List<Double> doubles) {
        ByteBuffer buf = ByteBuffer.allocate(doubles.size() * Double.BYTES);
        for (Double item : doubles) {
            buf.putDouble(item);
        }
        buf.rewind();
        return buf.array();
    }

    public String base64StringVector(List<Double> vec) {
        return base64StringVector(getBinaryVec(vec));
    }

    public String base64StringVector(byte[] vecBytes) {
        return new String(Base64.getEncoder().encode(vecBytes), StandardCharsets.UTF_8);
    }

    public byte[] base64ByteArrayVector(byte[] vecBytes) {
        return Base64.getEncoder().encode(vecBytes);
    }

    public List<Double> randomVec(int length) {
        List<Double> doubles = new ArrayList<Double>(length);
        for (int i = 0; i < length; i++) {
            doubles.add(randomDoubleBetween(0d, 1d, false));
        }
        return doubles;
    }

    public byte[] randomVecBytes(int length) {
        return getBinaryVec(randomVec(length));
    }

    public String randomDocumentID() {
        return RandomStrings.randomAsciiLettersOfLengthBetween(random(), 3, 64);
    }

    public String randomDocumentName() {
        return RandomStrings.randomRealisticUnicodeOfCodepointLength(
                random(),
                RandomNumbers.randomIntBetween(random(), 3, 256)
        );
    }

    public XContentBuilder buildRandomDoc(int vecLength) throws IOException {
        String name = RandomStrings.randomRealisticUnicodeOfCodepointLength(
                random(),
                RandomNumbers.randomIntBetween(random(), 3, 256)
        );
        byte[] vecBytes = randomVecBytes(vecLength);
        return buildDoc(name, vecBytes);
    }

    public IndexResponse indexDoc(
            String index,
            String id,
            String name,
            byte[] vec
    ) throws IOException {
        return indexDoc(index, id, buildDoc(name, vec));
    }

    public IndexResponse indexDoc(String index, String id, XContentBuilder docBuilder) {
        return client().prepareIndex(index, "_doc", id)
                .setSource(docBuilder)
                .execute().actionGet();
    }

    public XContentBuilder buildDoc(String name, byte[] vec) throws IOException {
        return XContentFactory.jsonBuilder().startObject()
                .field("name", name)
                .field("vec", vec)
                .endObject();
    }

    public void setupIndex(String index) throws IOException {
        createIndex(index);
        logger.info("[{}] created index", getLogPrefix());
        ensureGreen(index);
        logger.info("[{}] ensured green", getLogPrefix());

        AcknowledgedResponse putMappingResponse = client().admin().indices()
                .preparePutMapping(index)
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
        logger.info("[{}] created mapping: {}", getLogPrefix(), putMappingResponse);
    }
}
