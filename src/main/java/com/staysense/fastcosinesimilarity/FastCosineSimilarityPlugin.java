/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.staysense.fastcosinesimilarity;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.store.ByteArrayDataInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.script.ScoreScript;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptEngine;
import org.elasticsearch.search.lookup.SearchLookup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;


/**
 * Useful links:
 * https://www.elastic.co/guide/en/elasticsearch/reference/master/modules-scripting-engine.html
 */
public final class FastCosineSimilarityPlugin extends Plugin implements ScriptPlugin {
    private static Logger logger = LogManager.getLogger();


    @Override
    public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
        return new FastCosineSimilarityEngine();
    }

    private static class FastCosineSimilarityEngine implements ScriptEngine {
        @Override
        public String getType() {
            return "fast_cosine";
        }

        @Override
        public <T> T compile(String scriptName, String scriptSource, ScriptContext<T> context, Map<String, String> params) {
            logger.debug("Hello");
            if (context.equals(ScoreScript.CONTEXT) == false) {
                throw new IllegalArgumentException(getType() + " scripts cannot be used for context [" + context.name + "]");
            }
            // we use the script "source" as the script identifier
            if ("staysense".equals(scriptSource)) {
                ScoreScript.Factory factory = FastCosineLeafFactory::new;
                return context.factoryClazz.cast(factory);
            }
            throw new IllegalArgumentException("Unknown script name " + scriptSource);
        }

        @Override
        public void close() {
            // optionally close resources
        }

        private static class FastCosineLeafFactory implements ScoreScript.LeafFactory {
            private final Map<String, Object> params;
            private final SearchLookup lookup;

            // The field to compare against
            final String field;
            //Whether this search should be cosine or dot product
            final Boolean cosine;
            //The query embedded vector
            final Object vector;
            //The final comma delimited vector representation of the query vector
            double[] inputVector;

            //
            //The normalized vector score from the query
            //
            double queryVectorNorm;

            private FastCosineLeafFactory(Map<String, Object> params, SearchLookup lookup) {
                this.params = params;
                this.lookup = lookup;

                if (!params.containsKey("field")) {
                    throw new IllegalArgumentException("Missing parameter [field]");
                }

                //Determine if cosine
                final Object cosineBool = params.get("cosine");
                cosine = cosineBool == null || (boolean) cosineBool;

                //Get the field value from the query
                field = params.get("field").toString();

                //Get the query vector embedding
                vector = params.get("encoded_vector");

                logger.debug(
                        "FastCosineLeafFactory: field: {}, cosine: {}",
                        field,
                        cosine
                );

                final Object encodedVector = params.get("encoded_vector");
                if (encodedVector == null) {
                    throw new IllegalArgumentException(
                            "Must have [vector] or [encoded_vector] as a parameter"
                    );
                }
                inputVector = Util.convertBase64ToArray((String) encodedVector);

                //If cosine calculate the query vec norm
                if (cosine) {
                    queryVectorNorm = 0d;
                    // compute query inputVector norm once
                    for (double v : inputVector) {
                        queryVectorNorm += Math.pow(v, 2.0);
                    }
                }
            }

            @Override
            public ScoreScript newInstance(LeafReaderContext context) throws IOException {
                // Use Lucene LeafReadContext to access binary values directly.
                BinaryDocValues accessor = context.reader().getBinaryDocValues(field);

                if (accessor == null) {
                    logger.warn("accessor == null");
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

                return new ScoreScript(params, lookup, context) {
                    int currentDocID = -1;
                    Boolean hasValue = false;

                    @Override
                    public void setDocument(int docID) {
                        /*
                         * advanceExact has undefined behavior calling with a docid <= its current docid
                         */
                        if (accessor.docID() <= docID) {
                            try {
                                hasValue = accessor.advanceExact(docID);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        } else {
                            logger.error("Refusing to advance since accessor's docID > target docID [{} > {}]", accessor.docID(), docID);
                        }
                        currentDocID = docID;
                    }

                    @Override
                    public double execute() {
                        if (accessor.docID() != currentDocID) {
                            /*
                             * advance moved past the current doc, so this doc
                             * has no occurrences of the term
                             */
                            logger.error(
                                    "accessor.docID() [{}] != currentDocID [{}]",
                                    accessor.docID(),
                                    currentDocID
                            );
                            return 0.0d;
                        }

                        if (!hasValue) {
                            logger.trace(
                                    "Doc with ID [{}] does not have a value for field [{}]",
                                    currentDocID,
                                    field
                            );
                        }

                        final byte[] bytes;

                        try {
                            bytes = accessor.binaryValue().bytes;
                        } catch (IOException e) {
                            logger.error("Could not call accessor.binaryValue()", e);
                            return 0d;
                        }

                        final ByteArrayDataInput byteArrayInput = new ByteArrayDataInput(bytes);

                        // XXX: Some kind of prefix?! Haven't been able to find documentation on this.
                        final int unknownPrefixInt = byteArrayInput.readVInt();
                        logger.trace("unknownPrefixInt: {}", unknownPrefixInt);
                        // The length of the array seems to be stored in the array. Classic.
                        final int docVectorLength = byteArrayInput.readVInt();
                        final int docVectorStartPosition = byteArrayInput.getPosition();

                        final DoubleBuffer docDoubleBuffer = ByteBuffer.wrap(
                                bytes,
                                docVectorStartPosition,
                                docVectorLength
                        ).asDoubleBuffer();

                        if (docDoubleBuffer.capacity() != inputVector.length) {
                            throw new IllegalArgumentException(
                                    String.format(
                                            Locale.ENGLISH,
                                            "Input vector length [%d] differs from document vector length [%d] for docID %d",
                                            inputVector.length,
                                            docDoubleBuffer.capacity(),
                                            currentDocID
                                    )
                            );
                        }

                        final double[] docVector = new double[docDoubleBuffer.capacity()];
                        docDoubleBuffer.get(docVector);

                        if (logger.isTraceEnabled()) {
                            logger.trace("docVector as base64: {}", Util.convertArrayToBase64(docVector));
                        }

                        double docVectorNorm = 0d;
                        double score = 0d;

                        // calculate dot product of document vector and query vector
                        for (int i = 0; i < inputVector.length; i++) {
                            score += docVector[i] * inputVector[i];

                            docVectorNorm += Math.pow(docVector[i], 2.0);
                        }

                        if (docVectorNorm == 0 || queryVectorNorm == 0) {
                            logger.debug("docVectorNorm == 0");
                            return 0d;
                        }

                        score = score / (Math.sqrt(docVectorNorm * queryVectorNorm));

                        logger.trace("score: {}", score);

                        return score;
                    }
                };
            }

            @Override
            public boolean needs_score() {
                return false;
            }
        }
    }
}
