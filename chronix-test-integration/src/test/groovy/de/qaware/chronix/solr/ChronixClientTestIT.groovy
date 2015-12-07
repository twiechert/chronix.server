/*
 *    Copyright (C) 2015 QAware GmbH
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package de.qaware.chronix.solr
import de.qaware.chronix.ChronixClient
import de.qaware.chronix.converter.KassiopeiaSimpleConverter
import de.qaware.chronix.dts.MetricDataPoint
import de.qaware.chronix.solr.client.ChronixSolrStorage
import de.qaware.chronix.timeseries.MetricTimeSeries
import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.HttpSolrClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Ignore
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.text.DecimalFormat
import java.util.function.BinaryOperator
import java.util.function.Function
import java.util.stream.Collectors
/**
 * Tests the integration of Chronix and an embedded solr.
 * Fields also have to be registered in the schema.xml
 * (\src\test\resources\de\qaware\chronix\chronix\conf\schema.xml)
 *
 * @author f.lautenschlager
 */
class ChronixClientTestIT extends Specification {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChronixClientTestIT.class);

    //Test subjects
    @Shared
    SolrClient solr
    @Shared
    ChronixClient<MetricTimeSeries, SolrClient, SolrQuery> chronix

    @Shared
    def listStringField = ["List first part", "List second part"]
    @Shared
    def listIntField = [1I, 2I]
    @Shared
    def listLongField = [11L, 25L]
    @Shared
    def listDoubleField = [1.5D, 2.6D]


    @Shared
    Function<MetricTimeSeries, String> groupBy = new Function<MetricTimeSeries, String>() {
        @Override
        String apply(MetricTimeSeries ts) {
            StringBuilder metricKey = new StringBuilder();

            metricKey.append(ts.attribute("host")).append("-")
                    .append(ts.attribute("source")).append("-")
                    .append(ts.attribute("group")).append("-")
                    .append(ts.getMetric());

            return metricKey.toString();
        }
    }

    @Shared
    BinaryOperator<MetricTimeSeries> reduce = new BinaryOperator<MetricTimeSeries>() {
        @Override
        MetricTimeSeries apply(MetricTimeSeries ts1, MetricTimeSeries ts2) {
            ts1.addAll(ts2.points);
            return ts1;
        }
    }

    def setupSpec() {
        given:
        LOGGER.info("Setting up the integration test.")
        solr = new HttpSolrClient("http://localhost:8983/solr/chronix/")
        chronix = new ChronixClient(new KassiopeiaSimpleConverter<>(), new ChronixSolrStorage(200, groupBy, reduce))

        when: "We clean the index to ensure that no old data is loaded."
        sleep(30_000)
        solr.deleteByQuery("*:*")
        def result = solr.commit()

        and: "We add new data"

        LOGGER.info("Adding data to Chronix.")
        importTimeSeriesData();
        //we do a hart commit - only for testing purposes
        solr.commit()

        then:
        result.status == 0

    }

    def importTimeSeriesData() {
        def documents = new HashMap<Integer, MetricTimeSeries>()
        def url = ChronixClientTestIT.getResource("/timeSeries");
        def tsDir = new File(url.toURI())

        int pointsPerChunk = 500;

        tsDir.listFiles().each { File entry ->
            LOGGER.info("Processing file {}", entry)

            def attributes = entry.name.split("_")
            def onlyOnce = true
            def nf = DecimalFormat.getInstance(Locale.ENGLISH);

            entry.splitEachLine(";") { fields ->

                //Its the first line of a csv file
                if ("Date" == fields[0]) {
                    if (onlyOnce) {
                        fields.subList(1, fields.size()).eachWithIndex { String field, int i ->
                            def ts = new MetricTimeSeries.Builder(field)
                                    .attribute("host", attributes[0])
                                    .attribute("source", attributes[1])
                                    .attribute("group", attributes[2])

                            //Add some generic fields an values
                                    .attribute("myIntField", 5)
                                    .attribute("myLongField", 8L)
                                    .attribute("myDoubleField", 5.5)
                                    .attribute("myByteField", "String as byte".getBytes("UTF-8"))
                                    .attribute("myStringList", listStringField)
                                    .attribute("myIntList", listIntField)
                                    .attribute("myLongList", listLongField)
                                    .attribute("myDoubleList", listDoubleField)
                                    .build()
                            documents.put(i, ts)

                        }
                    }
                } else {
                    //First field is the timestamp: 26.08.2013 00:00:17.361
                    long date = Date.parse("dd.MM.YYYY HH:mm:ss.SSS", fields[0]).getTime()
                    fields.subList(1, fields.size()).eachWithIndex { String value, int i ->
                        documents.get(i).add(new MetricDataPoint(date, nf.parse(value).doubleValue()))
                    }
                    //Add the document to chronix if the points per chunk are
                    if (documents.get(0).size() == pointsPerChunk) {
                        chronix.add(documents.values(), solr)
                        documents.values().each { doc -> doc.clear() }
                    }
                }

                onlyOnce = false
            }
        }
        chronix.add(documents.values(), solr)
    }

    @Ignore
    def "Test add and query time series to Chronix with Solr"() {
        when:
        //query all documents
        List<MetricTimeSeries> timeSeries = chronix.stream(solr, new SolrQuery("*:*")).collect(Collectors.toList());

        then:
        timeSeries.size() == 26i
        def selectedTimeSeries = timeSeries.get(0)

        selectedTimeSeries.start == 1356908403722
        selectedTimeSeries.end == 1356994758096
        selectedTimeSeries.points.size() == 7389
        selectedTimeSeries.attribute("myIntField") == 5
        selectedTimeSeries.attribute("myLongField") == 8L
        selectedTimeSeries.attribute("myDoubleField") == 5.5D
        selectedTimeSeries.attribute("myByteField") == "String as byte".getBytes("UTF-8")
        selectedTimeSeries.attribute("myStringList") == listStringField
        selectedTimeSeries.attribute("myIntList") == listIntField
        selectedTimeSeries.attribute("myLongList") == listLongField
        selectedTimeSeries.attribute("myDoubleList") == listDoubleField
    }

    @Ignore
    @Unroll
    def "Test analysis query #analysisQuery"() {
        when:
        def query = new SolrQuery("metric:\\\\Load\\\\avg");
        query.addFilterQuery(analysisQuery)
        List<MetricTimeSeries> timeSeries = chronix.stream(solr, query).collect(Collectors.toList())
        then:
        timeSeries.size() == 1
        def selectedTimeSeries = timeSeries.get(0)

        selectedTimeSeries.size() == points
        selectedTimeSeries.attribute("myIntField") == 5
        selectedTimeSeries.attribute("myLongField") == 8L
        selectedTimeSeries.attribute("myDoubleField") == 5.5D
        selectedTimeSeries.attribute("myByteField") == "String as byte".getBytes("UTF-8")
        selectedTimeSeries.attribute("myStringList") == listStringField
        selectedTimeSeries.attribute("myIntList") == listIntField
        selectedTimeSeries.attribute("myLongList") == listLongField
        selectedTimeSeries.attribute("myDoubleList") == listDoubleField

        where:
        analysisQuery << ["ag=max", "ag=min", "ag=avg", "ag=p:0.25", "ag=dev", "analysis=trend", "analysis=outlier"]
        points << [1, 1, 1, 1, 1, 7389, 7389]

    }
}
