/**
 * Copyright information and license terms for this software can be
 * found in the file LICENSE.TXT included with the distribution.
 */
package org.epics.util.array;

import org.epics.util.array.ArrayLong;
import org.epics.util.array.CollectionNumbers;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;

/**
 *
 * @author carcassi
 */
public class ArrayLongTest extends FeatureTestListNumber {

    @Override
    public ArrayLong createConstantCollection() {
        return CollectionNumbers.unmodifiableListLong(1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
    }

    @Override
    public ArrayLong createRampCollection() {
        return CollectionNumbers.unmodifiableListLong(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Override
    public ArrayLong createModifiableCollection() {
        return CollectionNumbers.toListLong(new long[10]);
    }

    @Override
    public ListNumber createEmpty() {
        return CollectionNumbers.toListLong(new long[0]);
    }

    @Test
    public void serialization1() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ObjectOutputStream stream = new ObjectOutputStream(buffer);
        ArrayLong array = createRampCollection();
        stream.writeObject(array);
        ObjectInputStream inStream = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()));
        ArrayLong read = (ArrayLong) inStream.readObject();
        assertThat(read, not(sameInstance(array)));
        assertThat(read, equalTo(array));
    }

    @Test
    public void toStringOverflow() {
        ListNumber list = ArrayLong.of(new long[] {-1, 0, 1});
        assertThat(list.toString(), equalTo("[-1, 0, 1]"));
    }
}
