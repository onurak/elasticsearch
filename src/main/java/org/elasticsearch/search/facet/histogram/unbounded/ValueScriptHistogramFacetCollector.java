/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.search.facet.histogram.unbounded;

import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.search.Scorer;
import org.elasticsearch.common.CacheRecycler;
import org.elasticsearch.common.trove.ExtTLongObjectHashMap;
import org.elasticsearch.index.fielddata.DoubleValues;
import org.elasticsearch.index.fielddata.IndexNumericFieldData;
import org.elasticsearch.script.SearchScript;
import org.elasticsearch.search.facet.AbstractFacetCollector;
import org.elasticsearch.search.facet.Facet;
import org.elasticsearch.search.facet.histogram.HistogramFacet;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.Map;

/**
 * A histogram facet collector that uses the same field as the key as well as the
 * value.
 */
public class ValueScriptHistogramFacetCollector extends AbstractFacetCollector {

    private final IndexNumericFieldData indexFieldData;

    private final HistogramFacet.ComparatorType comparatorType;

    private DoubleValues values;
    private final SearchScript valueScript;
    private final HistogramProc histoProc;

    public ValueScriptHistogramFacetCollector(String facetName, IndexNumericFieldData indexFieldData, String scriptLang, String valueScript, Map<String, Object> params, long interval, HistogramFacet.ComparatorType comparatorType, SearchContext context) {
        super(facetName);
        this.comparatorType = comparatorType;
        this.indexFieldData = indexFieldData;
        this.valueScript = context.scriptService().search(context.lookup(), scriptLang, valueScript, params);
        histoProc = new HistogramProc(interval, this.valueScript);
    }

    @Override
    protected void doCollect(int doc) throws IOException {
        values.forEachValueInDoc(doc, histoProc);
    }

    @Override
    public void setScorer(Scorer scorer) throws IOException {
        valueScript.setScorer(scorer);
    }

    @Override
    protected void doSetNextReader(AtomicReaderContext context) throws IOException {
        values = indexFieldData.load(context).getDoubleValues();
        valueScript.setNextReader(context);
    }

    @Override
    public Facet facet() {
        return new InternalFullHistogramFacet(facetName, comparatorType, histoProc.entries, true);
    }

    public static long bucket(double value, long interval) {
        return (((long) (value / interval)) * interval);
    }

    public static class HistogramProc implements DoubleValues.ValueInDocProc {

        private final long interval;

        private final SearchScript valueScript;

        final ExtTLongObjectHashMap<InternalFullHistogramFacet.FullEntry> entries = CacheRecycler.popLongObjectMap();

        public HistogramProc(long interval, SearchScript valueScript) {
            this.interval = interval;
            this.valueScript = valueScript;
        }

        @Override
        public void onMissing(int docId) {
        }

        @Override
        public void onValue(int docId, double value) {
            valueScript.setNextDocId(docId);
            long bucket = bucket(value, interval);
            double scriptValue = valueScript.runAsDouble();

            InternalFullHistogramFacet.FullEntry entry = entries.get(bucket);
            if (entry == null) {
                entry = new InternalFullHistogramFacet.FullEntry(bucket, 1, scriptValue, scriptValue, 1, scriptValue);
                entries.put(bucket, entry);
            } else {
                entry.count++;
                entry.totalCount++;
                entry.total += scriptValue;
                if (scriptValue < entry.min) {
                    entry.min = scriptValue;
                }
                if (scriptValue > entry.max) {
                    entry.max = scriptValue;
                }
            }
        }
    }
}