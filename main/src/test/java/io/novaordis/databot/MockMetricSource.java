/*
 * Copyright (c) 2016 Nova Ordis LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.novaordis.databot;

import io.novaordis.events.api.event.Property;
import io.novaordis.events.api.metric.MetricDefinition;
import io.novaordis.events.api.metric.MetricException;
import io.novaordis.events.api.metric.MetricSource;
import io.novaordis.utilities.os.OS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Ovidiu Feodorov <ovidiu@novaordis.com>
 * @since 8/5/16
 */
public class MockMetricSource implements MetricSource {

    // Constants -------------------------------------------------------------------------------------------------------

    // Static ----------------------------------------------------------------------------------------------------------

    // Attributes ------------------------------------------------------------------------------------------------------

    private Map<OS, Map<MetricDefinition, Property>> bulkReadingsForOs;
    private Map<MetricDefinition, Property> readingsForMetrics;

    private boolean breakOnCollect;

    // Constructors ----------------------------------------------------------------------------------------------------

    public MockMetricSource() {

        bulkReadingsForOs = new HashMap<>();
        readingsForMetrics = new HashMap<>();
    }

    // MetricSource implementation -------------------------------------------------------------------------------------

    @Override
    public String getAddress() {
        throw new RuntimeException("getAddress() NOT YET IMPLEMENTED");
    }

    @Override
    public boolean hasAddress(String address) {
        throw new RuntimeException("hasAddress() NOT YET IMPLEMENTED");
    }

    @Override
    public List<Property> collectMetrics(List<MetricDefinition> metricDefinitions) throws MetricException {

        List<Property> result = new ArrayList<>();

        for(MetricDefinition d: metricDefinitions) {

            Property p = readingsForMetrics.get(d);

            if (p != null) {
                result.add(p);
            }
        }

        return result;
    }

    // Public ----------------------------------------------------------------------------------------------------------

    public void addBulkReading(OS os, Property p) {

        throw new RuntimeException("NYE");

//        Map<MetricDefinition, Property> properties = bulkReadingsForOs.get(os);
//
//        if (properties == null) {
//
//            properties = new HashMap<>();
//            bulkReadingsForOs.put(os, properties);
//        }
//
//        properties.put(new MockMetricDefinition("mock"), p);
    }

    public void addReadingForMetric(MetricDefinition d, Property p) {

        readingsForMetrics.put(d, p);
    }

    public void breakOnCollectMetrics() {

        breakOnCollect = true;
    }

    @Override
    public String toString() {

        return "Mock Metric Source";
    }

    // Package protected -----------------------------------------------------------------------------------------------

    // Protected -------------------------------------------------------------------------------------------------------

    // Private ---------------------------------------------------------------------------------------------------------

    // Inner classes ---------------------------------------------------------------------------------------------------

}