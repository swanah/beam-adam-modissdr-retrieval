package org.esa.beam.akh.adam.bbdr;

import java.io.IOException;
import junit.framework.TestCase;

/**
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
public class AatsrLutTest extends TestCase {

    public void testLutAot() throws IOException {
        AotLookupTable lut = N2ModisUtils.getAotLookupTable(Sensor.AATSR_FWARD);
        assertNotNull(lut);
        // TODO write tests
    }

    public void testAotKx() throws IOException {
        LookupTable lut = N2ModisUtils.getAotKxLookupTable(Sensor.AATSR_FWARD);
        assertNotNull(lut);
        // TODO write tests
    }

    //<editor-fold defaultstate="collapsed" desc="NSky stuff : to be removed">
    /*
    public void testMerisNskyLutDw() throws IOException {
        NskyLookupTable lut = BbdrUtils.getNskyLookupTableDw(Sensor.AATSR_FWARD);
        assertNotNull(lut);
        // TODO write tests
    }
    
    public void testMerisNskyLutUp() throws IOException {
        NskyLookupTable lut = BbdrUtils.getNskyLookupTableUp(Sensor.AATSR_FWARD);
        assertNotNull(lut);
        // TODO write tests
    }
    */
    //</editor-fold>
}
