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

package io.novaordis.osstats;

import io.novaordis.events.core.event.TimedEvent;
import io.novaordis.osstats.metric.MetricDefinition;
import io.novaordis.osstats.metric.MockMetricDefinition;
import io.novaordis.osstats.metric.MockMetricSource;
import io.novaordis.osstats.os.MockOS;
import io.novaordis.utilities.os.OS;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * @author Ovidiu Feodorov <ovidiu@novaordis.com>
 * @since 7/29/16
 */
public abstract class DataCollectorTest {

    // Constants -------------------------------------------------------------------------------------------------------

    // Static ----------------------------------------------------------------------------------------------------------

    // Attributes ------------------------------------------------------------------------------------------------------

    // Constructors ----------------------------------------------------------------------------------------------------

    // Public ----------------------------------------------------------------------------------------------------------

    @Test
    public void read() throws Exception {

        MockOS mos = new MockOS();

        DataCollector c = getDataCollectorToTest(mos);

        long t0 = System.currentTimeMillis();

        MockMetricDefinition mmd = new MockMetricDefinition("TEST");
        MockMetricSource mms = new MockMetricSource();
        assertTrue(mmd.addSource(mos.getName(), mms));

        mms.mockMetricGeneration(mos, new MockProperty("TEST"));

        List<MetricDefinition> metrics = Collections.singletonList(mmd);

        TimedEvent te = c.read(metrics);

        long t1 = System.currentTimeMillis();

        assertTrue(t0 <= te.getTime());
        assertTrue(te.getTime() <= t1);
    }

    // Package protected -----------------------------------------------------------------------------------------------

    // Protected -------------------------------------------------------------------------------------------------------

    protected abstract DataCollector getDataCollectorToTest(OS os) throws Exception;

    // Private ---------------------------------------------------------------------------------------------------------

    // Inner classes ---------------------------------------------------------------------------------------------------

}
