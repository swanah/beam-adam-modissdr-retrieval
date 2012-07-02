package org.esa.beam.akh.adam.bbdr;

import org.esa.beam.akh.adam.bbdr.N2ModisUtils;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * @author Olaf Danne
 * @author Marco Zuehlke
 */
public class BbdrUtilsTest {

    @Test
    public void testGetIndexBefore() {
        float[] values = {1.8f, 2.2f, 4.5f, 5.5f};
        assertEquals(0, N2ModisUtils.getIndexBefore(1.2f, values));
        assertEquals(1, N2ModisUtils.getIndexBefore(2.5f, values));
        assertEquals(2, N2ModisUtils.getIndexBefore(4.6f, values));
        assertEquals(2, N2ModisUtils.getIndexBefore(7.7f, values));
    }
}
