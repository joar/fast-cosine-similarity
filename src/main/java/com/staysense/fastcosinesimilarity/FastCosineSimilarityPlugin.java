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
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.script.ScoreScript;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptEngine;

import java.util.Collection;
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

    public static class FastCosineSimilarityEngine implements ScriptEngine {
        @Override
        public String getType() {
            return "fast_cosine";
        }

        @Override
        public <T> T compile(String scriptName, String scriptSource, ScriptContext<T> context, Map<String, String> params) {
            logger.debug("Compiling script name: [{}] source: [{}]", scriptName, scriptSource);
            if (!context.equals(ScoreScript.CONTEXT)) {
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

    }

}
