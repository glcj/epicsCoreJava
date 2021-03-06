/**
 * Copyright information and license terms for this software can be
 * found in the file LICENSE.TXT included with the distribution.
 */
package org.epics.vtype;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import java.time.Instant;
import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author shroffk
 */
public class VStringArrayTest {
    
    private Time testTime;

    @Before
    public void setUp() {
        testTime = Time.of(Instant.ofEpochSecond(1354719441, 521786982));
    }

    @Test
    public void newVStringArray() {
        Alarm alarm = Alarm.of(AlarmSeverity.MINOR, AlarmStatus.DB, "LOW");
        VStringArray value = VStringArray.of(Arrays.asList("ONE", "TWO", "THREE"), alarm, testTime);
        assertThat(value.getData(), equalTo(Arrays.asList("ONE", "TWO", "THREE")));
        assertThat(value.toString(),
                equalTo(String.format("VStringArray[[ONE, TWO, THREE], size [3], %s, %s]", alarm.toString(), testTime.toString())));
    }
}
