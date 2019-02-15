package com.staysense.fastcosinesimilarity;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.store.ByteArrayDataInput;
import org.elasticsearch.script.ExplainableScoreScript;
import org.elasticsearch.script.ScoreScript;
import org.elasticsearch.search.lookup.SearchLookup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

public class FastCosineLeafFactory implements ScoreScript.LeafFactory {
    private static Logger logger = LogManager.getLogger();
    private final Map<String, Object> params;
    private final SearchLookup lookup;

    // Field name parameter
    private final String field;
    // Decoded vector parameter
    private double[] queryVector;

    // Computed norm of queryVector
    private double queryVectorNorm;

    /**
     * Re-used, might improve performance
     */
    private final ByteArrayDataInput byteArrayDataInput;

    FastCosineLeafFactory(Map<String, Object> params, SearchLookup lookup) {
        this.params = params;
        this.lookup = lookup;

        if (!params.containsKey("field")) {
            throw new IllegalArgumentException("Missing parameter [field]");
        }

        field = params.get("field").toString();

        logger.debug(
                "field: {}",
                field
        );

        final Object encodedVector = params.get("encoded_vector");
        if (encodedVector == null) {
            throw new IllegalArgumentException(
                    "Must have [vector] or [encoded_vector] as a parameter"
            );
        }
        queryVector = Util.convertBase64ToArray((String) encodedVector);

        // Pre-allocate the document vector reader
        byteArrayDataInput = new ByteArrayDataInput(new byte[1]);

        // Compute query queryVector norm once per query per shard
        queryVectorNorm = 0d;
        for (double v : queryVector) {
            queryVectorNorm += Math.pow(v, 2.0d);
        }
    }

    /**
     * Called once per shard(?) to create the ScoreScript
     */
    @Override
    public ScoreScript newInstance(LeafReaderContext context) throws IOException {
        // Use Lucene LeafReadContext to access binary values directly.
        LeafReader leafReader = context.reader();
        BinaryDocValues binaryDocValues = leafReader.getBinaryDocValues(field);
        logger.debug("leafReader = [{}]", leafReader);

        if (binaryDocValues == null) {
            logger.warn("binaryDocValues == null");
            /*
             * the field and/or term don't exist in this segment,
             * so always return 0
             */
            return new ScoreScript(params, lookup, context) {
                @Override
                public double execute() {
                    return 0.0d;
                }
            };
        }
        return new FastCosineScoreScript(params, lookup, context, binaryDocValues);
    }

    public class FastCosineScoreScript extends ScoreScript implements ExplainableScoreScript {
        BinaryDocValues binaryDocValues;
        int currentDocID = -1;
        Boolean hasValue = false;

        FastCosineScoreScript(
                Map<String, Object> params,
                SearchLookup lookup,
                LeafReaderContext leafContext,
                BinaryDocValues binaryDocValues
        ) {
            super(params, lookup, leafContext);
            this.binaryDocValues = binaryDocValues;
        }

        /**
         * See  {@link org.elasticsearch.common.lucene.search.function.LeafScoreFunction}
         * @param targetDocID The ID of the next document to execute() for.
         */
        @Override
        public void setDocument(int targetDocID) {
            // advance has undefined behavior calling with a docid <= its current docid
            if (binaryDocValues.docID() <= targetDocID) {
                try {
                    hasValue = binaryDocValues.advanceExact(targetDocID);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                logger.error(
                        "Refusing to advance since binaryDocValues.docID() [{}] > targetDocID [{}]",
                        binaryDocValues.docID(),
                        targetDocID
                );
            }
            currentDocID = targetDocID;
        }

        /**
         * Score the current document.
         * Called after {@link #setDocument(int)}.
         */
        @Override
        public double execute() {
            if (!hasValue) {
                logger.trace(
                        "Doc with ID [{}] does not have a value for field [{}]",
                        currentDocID,
                        field
                );
                return 0d;
            }

            if (binaryDocValues.docID() != currentDocID) {
                /*
                 * advance moved past the desired doc.
                 */
                logger.error(
                        "binaryDocValues.docID() [{}] != currentDocID [{}]",
                        binaryDocValues.docID(),
                        currentDocID
                );
                return 0d;
            }

            try {
                return calculateScore(
                        currentDocID,
                        byteArrayDataInput,
                        binaryDocValues.binaryValue().bytes,
                        queryVector,
                        queryVectorNorm
                );
            } catch (IOException e) {
                logger.error("Could not call binaryDocValues.binaryValue()", e);
                return 0d;
            }
        }

        @Override
        public Explanation explain(Explanation subQueryScore) {
            double score = execute();
            Explanation scoreExp = Explanation.match(
                    subQueryScore.getValue(), "_score:",
                    subQueryScore);
            String explanation = String.format(Locale.ROOT, "cosineSimilarity(doc['%s'].value, %s)",
                    field,
                    Arrays.toString(queryVector)
            );
            return Explanation.match(
                    (float) score,
                    explanation,
                    scoreExp
            );
        }
    }

    /**
     * -    Extract the first value's byte array from fieldBytes
     * -    Convert the binary-encoded byte[] to double[]
     * -    Calculate the cosine similarity between queryVector and the extracted value
     *
     * @param currentDocID Used for error reporting
     * @param byteArrayDataInput Re-used when decoding the fieldBytes.
     * @param fieldBytes The field's binary doc values. See
     * {@link org.elasticsearch.index.mapper.BinaryFieldMapper.CustomBinaryDocValuesField#binaryValue()}
     * @param queryVector Query vector
     * @param queryVectorNorm Query vector square sum
     * @return Document score
     * @throws IllegalArgumentException If query vector length differs from field vector length.
     */
    static double calculateScore(
            int currentDocID,
            ByteArrayDataInput byteArrayDataInput,
            byte[] fieldBytes,
            double[] queryVector,
            double queryVectorNorm
    ) throws IllegalArgumentException {
        // Re-use byteArrayDataInput
        byteArrayDataInput.reset(fieldBytes);

        // Number of values stored in the field
        final int numValues = byteArrayDataInput.readVInt();
        // Length of the first field value
        final int docVectorLength = byteArrayDataInput.readVInt();
        final int docVectorStartPosition = byteArrayDataInput.getPosition();

        final DoubleBuffer docDoubleBuffer = ByteBuffer.wrap(
                fieldBytes,
                docVectorStartPosition,
                docVectorLength
        ).asDoubleBuffer();

        if (docDoubleBuffer.capacity() != queryVector.length) {
            throw new IllegalArgumentException(
                    String.format(
                            Locale.ENGLISH,
                            "Input vector length [%d] differs from document vector length [%d] for docID %d",
                            queryVector.length,
                            docDoubleBuffer.capacity(),
                            currentDocID
                    )
            );
        }

        final double[] docVector = new double[docDoubleBuffer.capacity()];
        docDoubleBuffer.get(docVector);

        double docVectorNorm = 0d;
        double score = 0d;

        // calculate dot product of document vector and query vector
        for (int i = 0; i < queryVector.length; i++) {
            score += docVector[i] * queryVector[i];

            docVectorNorm += Math.pow(docVector[i], 2.0);
        }

        if (docVectorNorm <= 0 || queryVectorNorm <= 0) {
            return 0d;
        }

        score = score / (Math.sqrt(docVectorNorm * queryVectorNorm));
        return ( score + 1.0d ) * 0.5d;
    }

    @Override
    public boolean needs_score() {
        return false;
    }
}
