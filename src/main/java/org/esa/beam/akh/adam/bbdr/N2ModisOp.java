/*
 * Copyright (C) 2012 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.beam.akh.adam.bbdr;

import Jama.Matrix;
import java.awt.Color;
import java.io.IOException;
import static java.lang.Math.PI;
import static java.lang.Math.abs;
import static java.lang.Math.acos;
import static java.lang.Math.cos;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.pow;
import static java.lang.Math.sin;
import static java.lang.Math.sqrt;
import static java.lang.Math.tan;
import static java.lang.StrictMath.toRadians;
import org.esa.beam.akh.adam.landcover.UclCloudDetection;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.ColorPaletteDef;
import org.esa.beam.framework.datamodel.ImageInfo;
import org.esa.beam.framework.datamodel.IndexCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.pointop.PixelOperator;
import org.esa.beam.framework.gpf.pointop.ProductConfigurer;
import org.esa.beam.framework.gpf.pointop.Sample;
import org.esa.beam.framework.gpf.pointop.SampleConfigurer;
import org.esa.beam.framework.gpf.pointop.WritableSample;
import org.esa.beam.gpf.operators.standard.BandMathsOp;
import org.esa.beam.idepix.operators.SchillerAlgorithm;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.math.FracIndex;

/**
 * @author Olaf Danne
 * @author Marco Zuehlke
 */
@OperatorMetadata(alias = "adam.n2modis",
                  description = "Computes MODIS SDRs",
                  authors = "Andreas Heckel, Marco Zuehlke, Olaf Danne",
                  version = "1.0",
                  copyright = "(C) 2011 by Swansea University and Brockmann Consult")
public class N2ModisOp extends PixelOperator {

    //<editor-fold defaultstate="collapsed" desc="private const declaration">
    private static final int SRC_LAND_MASK = 0;
    private static final int SRC_SNOW_MASK = 1;
    private static final int SRC_VZA = 2;
    private static final int SRC_VAA = 3;
    private static final int SRC_SZA = 4;
    private static final int SRC_SAA = 5;
    private static final int SRC_DEM = 6;
    private static final int SRC_AOT = 7;
    private static final int SRC_AOT_ERR = 8;
    private static final int SRC_OZO = 9;
    private static final int SRC_WVP = 10;
    private static final int SRC_TOA_RFL = 11;
    private int SRC_TOA_VAR;
    private int SRC_STATUS;
    
    private static final int TRG_BBDR = 0;
    private static final int TRG_ERRORS = 7; //(7*7-7)/2+7 = 28
    private static final int TRG_KERN = 35; //2*7 = 14 Kernels
    private static final int TRG_NDVI = 49; // 2
    private static final int TRG_VZA = 51;
    private static final int TRG_SZA = 52;
    private static final int TRG_RAA = 53;
    private static final int TRG_DEM = 54;
    private static final int TRG_SNOW = 55;
    private static final int TRG_AOD = 56;
    private static final int TRG_AODERR = 57;
    
    private static final int n_spc = 7; // 7 Modis bands
    private static final int n_kernel = 2; //(geo & vol)
    //</editor-fold>
    
    @SourceProduct
    private Product sourceProduct;

    @Parameter(defaultValue = "MERIS")
    private Sensor sensor;

    @Parameter(defaultValue = "false")
    private boolean sdrOnly;
    @Parameter(defaultValue = "false")
    private boolean doUclCLoudDetection;
    @Parameter
    private String landExpression;


    // Auxdata
    private Matrix nb_coef_arr_all; // = fltarr(n_spc, num_bd)
    private Matrix nb_intcp_arr_all; // = fltarr(n_spc)
    private double[] rmse_arr_all; // = fltarr(n_spc)
    private Matrix[] nb_coef_arr; // = fltarr(n_spc, num_bd)
    private double[] nb_intcp_arr_D; //= fltarr(n_spc)
    private double kpp_vol;
    private double kpp_geo;

    private AotLookupTable aotLut;
    private LookupTable kxAotLut;
    private GasLookupTable gasLookupTable;
    private NskyLookupTable nskyDwLut;
    private NskyLookupTable nskyUpLut;

    private double vzaMin;
    private double vzaMax;
    private double szaMin;
    private double szaMax;
    private double aotMin;
    private double aotMax;
    private double hsfMin;
    private double hsfMax;

    private SchillerAlgorithm landNN;
    private UclCloudDetection uclCloudDetection;
    private static final double[] PATH_RADIANCE = new double[]{
            0.134, 0.103, 0.070, 0.059, 0.040,
            0.027, 0.022, 0.021, 0.018, 0.015,
            Double.NaN, 0.014, 0.010, 0.009, 0.008};
    private static final double[] TRANSMISSION = new double[]{
            0.65277, 0.71155, 0.77224, 0.78085, 0.78185,
            0.81036, 0.86705, 0.88244, 0.88342, 0.92075,
            Double.NaN, 0.93152, 0.9444, 0.9422, 0.58212
    };

    @Override
    protected void configureTargetProduct(ProductConfigurer productConfigurer) {
        super.configureTargetProduct(productConfigurer);

        final Product targetProduct = productConfigurer.getTargetProduct();
        if (sdrOnly) {
            if (sensor == Sensor.MERIS) {
                for (int i = 0; i < sensor.getNumBands(); i++) {
                    Band srcBand = sourceProduct.getBand("reflectance_" + (i + 1));
                    Band band = targetProduct.addBand("sdr_" + (i + 1), ProductData.TYPE_FLOAT32);
                    band.setNoDataValue(Float.NaN);
                    band.setNoDataValueUsed(true);
                    ProductUtils.copySpectralBandProperties(srcBand, band);
                }
                for (int i = 0; i < sensor.getNumBands(); i++) {
                    Band srcBand = sourceProduct.getBand("reflectance_" + (i + 1));
                    Band band = targetProduct.addBand("sdr_error_" + (i + 1), ProductData.TYPE_FLOAT32);
                    band.setNoDataValue(Float.NaN);
                    band.setNoDataValueUsed(true);
                    ProductUtils.copySpectralBandProperties(srcBand, band);
                }
            } else if (sensor == Sensor.VGT) {
                for (String bandname : N2ModisConstants.VGT_TOA_BAND_NAMES) {
                    Band srcBand = sourceProduct.getBand(bandname);
                    Band band = targetProduct.addBand("sdr_" + bandname, ProductData.TYPE_FLOAT32);
                    band.setNoDataValue(Float.NaN);
                    band.setNoDataValueUsed(true);
                    ProductUtils.copySpectralBandProperties(srcBand, band);
                }
                for (String bandname : N2ModisConstants.VGT_TOA_BAND_NAMES) {
                    Band srcBand = sourceProduct.getBand(bandname);
                    Band band = targetProduct.addBand("sdr_error_" + bandname, ProductData.TYPE_FLOAT32);
                    band.setNoDataValue(Float.NaN);
                    band.setNoDataValueUsed(true);
                    ProductUtils.copySpectralBandProperties(srcBand, band);
                }
            }

            Band ndvi = targetProduct.addBand("ndvi", ProductData.TYPE_FLOAT32);
            ndvi.setNoDataValue(Float.NaN);
            ndvi.setNoDataValueUsed(true);
            Band aod = targetProduct.addBand("aod", ProductData.TYPE_FLOAT32);
            aod.setNoDataValue(Float.NaN);
            aod.setNoDataValueUsed(true);

            // copy flag coding and flag images
            ProductUtils.copyFlagBands(sourceProduct, targetProduct, true);

            Band statusBand = targetProduct.addBand("status", ProductData.TYPE_INT8);
            statusBand.setNoDataValue(0);
            statusBand.setNoDataValueUsed(true);
            final IndexCoding indexCoding = new IndexCoding("status");
            ColorPaletteDef.Point[] points = new ColorPaletteDef.Point[8];
            indexCoding.addIndex("land", 1, "");
            points[0] = new ColorPaletteDef.Point(1, Color.GREEN, "land");

            indexCoding.addIndex("water", 2, "");
            points[1] = new ColorPaletteDef.Point(2, Color.BLUE, "water");

            indexCoding.addIndex("snow", 3, "");
            points[2] = new ColorPaletteDef.Point(3, Color.YELLOW, "snow");

            indexCoding.addIndex("cloud", 4, "");
            points[3] = new ColorPaletteDef.Point(4, Color.WHITE, "cloud");

            indexCoding.addIndex("cloud_shadow", 5, "");
            points[4] = new ColorPaletteDef.Point(5, Color.GRAY, "cloud_shadow");

            indexCoding.addIndex("ucl_cloud", 10, "");
            points[5] = new ColorPaletteDef.Point(10, Color.ORANGE, "ucl_cloud");

            indexCoding.addIndex("ucl_cloud_buffer", 11, "");
            points[6] = new ColorPaletteDef.Point(11, Color.RED, "ucl_cloud_buffer");

            indexCoding.addIndex("schiller_cloud", 20, "");
            points[7] = new ColorPaletteDef.Point(20, Color.PINK, "schiller_cloud");

            targetProduct.getIndexCodingGroup().add(indexCoding);
            statusBand.setSampleCoding(indexCoding);
            statusBand.setImageInfo(new ImageInfo(new ColorPaletteDef(points, points.length)));

            targetProduct.setAutoGrouping("sdr_error:sdr");
        } else {
            String[] bandNames = {
                    "MODIS_1", "MODIS_2", "MODIS_3", "MODIS_4", "MODIS_5", "MODIS_6", "MODIS_7", 
                    "sig_MODIS_1_1", "sig_MODIS_1_2", "sig_MODIS_1_3", "sig_MODIS_1_4", "sig_MODIS_1_5", "sig_MODIS_1_6", "sig_MODIS_1_7", 
                    "sig_MODIS_2_2", "sig_MODIS_2_3", "sig_MODIS_2_4", "sig_MODIS_2_5", "sig_MODIS_2_6", "sig_MODIS_2_7", 
                    "sig_MODIS_3_3", "sig_MODIS_3_4", "sig_MODIS_3_5", "sig_MODIS_3_6", "sig_MODIS_3_7", 
                    "sig_MODIS_4_4", "sig_MODIS_4_5", "sig_MODIS_4_6", "sig_MODIS_4_7", 
                    "sig_MODIS_5_5", "sig_MODIS_5_6", "sig_MODIS_5_7", 
                    "sig_MODIS_6_6", "sig_MODIS_6_7", 
                    "sig_MODIS_7_7", 
                    "Kvol_BRDF_1", "Kvol_BRDF_2", "Kvol_BRDF_3", "Kvol_BRDF_4", "Kvol_BRDF_5", "Kvol_BRDF_6", "Kvol_BRDF_7", 
                    "Kgeo_BRDF_1", "Kgeo_BRDF_2", "Kgeo_BRDF_3", "Kgeo_BRDF_4", "Kgeo_BRDF_5", "Kgeo_BRDF_6", "Kgeo_BRDF_7", 
                    "AOD550", "sig_AOD550",
                    "NDVI", "sig_NDVI",
                    "VZA", "SZA", "RAA", "DEM",
            };
            for (String bandName : bandNames) {
                Band band = targetProduct.addBand(bandName, ProductData.TYPE_FLOAT32);
                band.setNoDataValue(Float.NaN);
                band.setNoDataValueUsed(true);
            }
            for (int i = 0; i < n_spc; i++){
                Band band = targetProduct.getBand(bandNames[i]);
                band.setSpectralBandIndex(i);
                band.setSpectralWavelength(N2ModisConstants.MODIS_WAVELENGHTS[i]);
            }
            targetProduct.addBand("snow_mask", ProductData.TYPE_INT8);

            // copy flag coding and flag images
            ProductUtils.copyFlagBands(sourceProduct, targetProduct, true);
        }
        readAuxdata();
    }

    void readAuxdata() {
        N2Bconversion n2Bconversion = new N2Bconversion(sensor, n_spc);
        try {
            n2Bconversion.load();
            rmse_arr_all = n2Bconversion.getRmse_arr_all();
            nb_coef_arr_all = new Matrix(n2Bconversion.getNb_coef_arr_all());
            double[] nb_intcp_arr_all_data = n2Bconversion.getNb_intcp_arr_all();
            nb_intcp_arr_all = new Matrix(nb_intcp_arr_all_data, nb_intcp_arr_all_data.length);

            double[][] nb_coef_arr_D = n2Bconversion.getNb_coef_arr_D();
            nb_coef_arr = new Matrix[n_spc];
            for (int i_bb = 0; i_bb < n_spc; i_bb++) {
                nb_coef_arr[i_bb] = new Matrix(nb_coef_arr_D[i_bb], nb_coef_arr_D[i_bb].length).transpose();
            }
            nb_intcp_arr_D = n2Bconversion.getNb_intcp_arr_D();

            aotLut = N2ModisUtils.getAotLookupTable(sensor);
            kxAotLut = N2ModisUtils.getAotKxLookupTable(sensor);
            nskyDwLut = N2ModisUtils.getNskyLookupTableDw(sensor);
            nskyUpLut = N2ModisUtils.getNskyLookupTableUp(sensor);
            kpp_geo = nskyDwLut.getKppGeo();
            kpp_vol = nskyDwLut.getKppVol();

            gasLookupTable = new GasLookupTable(sensor);
            gasLookupTable.load(sourceProduct);
        } catch (IOException e) {
            throw new OperatorException(e.getMessage());
        }

        LookupTable aotLut_l = this.aotLut.getLut();

        final double[] vzaArray = aotLut_l.getDimension(5).getSequence();
        vzaMin = vzaArray[0];
        vzaMax = vzaArray[vzaArray.length - 1];

        final double[] szaArray = aotLut_l.getDimension(4).getSequence();
        szaMin = szaArray[0];
        szaMax = szaArray[szaArray.length - 1];

        final double[] hsfArray = aotLut_l.getDimension(2).getSequence();
//        hsfMin = hsfArray[0];
        hsfMin = 0.001;
        hsfMax = hsfArray[hsfArray.length - 1];

        final double[] aotArray = aotLut_l.getDimension(1).getSequence();
        aotMin = aotArray[0];
        aotMax = aotArray[aotArray.length - 1];

        landNN = new SchillerAlgorithm(SchillerAlgorithm.Net.LAND);
        if (doUclCLoudDetection) {
            try {
                uclCloudDetection = UclCloudDetection.create();
            } catch (IOException e) {
                throw new OperatorException(e);
            }
        }
    }

    @Override
    protected void configureSourceSamples(SampleConfigurer configurator) {
        String[] toaBandNames = null;

        String landExpr = null;
        final String commonLandExpr;
        if (landExpression != null && !landExpression.isEmpty()) {
            commonLandExpr = landExpression;
        } else {
            if (sensor == Sensor.AATSR_FWARD) {
                commonLandExpr = "cloud_classif_flags_fward.F_CLEAR_LAND OR cloud_classif_flags_fward.F_CLEAR_SNOW";
            } else {
                commonLandExpr = "cloud_classif_flags.F_CLEAR_LAND OR cloud_classif_flags.F_CLEAR_SNOW";
            }
        }
        final String snowMaskExpression = "cloud_classif_flags.F_CLEAR_SNOW";

        BandMathsOp snowOp = BandMathsOp.createBooleanExpressionBand(snowMaskExpression, sourceProduct);
        Product snowMaskProduct = snowOp.getTargetProduct();
        configurator.defineSample(SRC_SNOW_MASK, snowMaskProduct.getBandAt(0).getName(), snowMaskProduct);

        if (sensor == Sensor.MERIS) {
            landExpr = "NOT l1_flags.INVALID AND (" + commonLandExpr + ")";

            configurator.defineSample(SRC_VZA, N2ModisConstants.MERIS_VZA_TP_NAME);
            configurator.defineSample(SRC_VAA, N2ModisConstants.MERIS_VAA_TP_NAME);
            configurator.defineSample(SRC_SZA, N2ModisConstants.MERIS_SZA_TP_NAME);
            configurator.defineSample(SRC_SAA, N2ModisConstants.MERIS_SAA_TP_NAME);
            configurator.defineSample(SRC_DEM, N2ModisConstants.MERIS_DEM_BAND_NAME);
            configurator.defineSample(SRC_AOT, N2ModisConstants.MERIS_AOT_BAND_NAME);
            configurator.defineSample(SRC_AOT_ERR, N2ModisConstants.MERIS_AOTERR_BAND_NAME);
            configurator.defineSample(SRC_OZO, N2ModisConstants.MERIS_OZO_TP_NAME);
            //configurator.defineSample(SRC_WVP, "ozone");

            toaBandNames = new String[N2ModisConstants.MERIS_TOA_BAND_NAMES.length];
            System.arraycopy(N2ModisConstants.MERIS_TOA_BAND_NAMES, 0, toaBandNames, 0,
                             N2ModisConstants.MERIS_TOA_BAND_NAMES.length);
        } else if (sensor == Sensor.AATSR_NADIR) {
            landExpr = commonLandExpr;

            configurator.defineSample(SRC_VZA, "view_elev_nadir");
            configurator.defineSample(SRC_VAA, "view_azimuth_nadir");
            configurator.defineSample(SRC_SZA, "sun_elev_nadir");
            configurator.defineSample(SRC_SAA, "sun_azimuth_nadir");
            configurator.defineSample(SRC_DEM, "elevation");
            configurator.defineSample(SRC_AOT, "aot");
            configurator.defineSample(SRC_AOT_ERR, "aot_err");
//            configurator.defineSample(SRC_OZO, "ozone");
//            configurator.defineSample(SRC_WVP, "ozone");

            toaBandNames = new String[N2ModisConstants.AATSR_TOA_BAND_NAMES_NADIR.length];
            System.arraycopy(N2ModisConstants.AATSR_TOA_BAND_NAMES_NADIR, 0, toaBandNames, 0,
                             N2ModisConstants.AATSR_TOA_BAND_NAMES_NADIR.length);

        } else if (sensor == Sensor.AATSR_FWARD) {
            landExpr = commonLandExpr;

            configurator.defineSample(SRC_VZA, "view_elev_fward");
            configurator.defineSample(SRC_VAA, "view_azimuth_fward");
            configurator.defineSample(SRC_SZA, "sun_elev_fward");
            configurator.defineSample(SRC_SAA, "sun_azimuth_fward");
            configurator.defineSample(SRC_DEM, "elevation");
            configurator.defineSample(SRC_AOT, "aot");
            configurator.defineSample(SRC_AOT_ERR, "aot_err");
//            configurator.defineSample(SRC_OZO, "ozone");
//            configurator.defineSample(SRC_WVP, "ozone");

            toaBandNames = new String[N2ModisConstants.AATSR_TOA_BAND_NAMES_FWARD.length];
            System.arraycopy(N2ModisConstants.AATSR_TOA_BAND_NAMES_FWARD, 0, toaBandNames, 0,
                             N2ModisConstants.AATSR_TOA_BAND_NAMES_FWARD.length);
        } else if (sensor == Sensor.VGT) {

            landExpr =
                    "SM.B0_GOOD AND SM.B2_GOOD AND SM.B3_GOOD AND (" + commonLandExpr + ")";

            configurator.defineSample(SRC_VZA, "VZA");
            configurator.defineSample(SRC_VAA, "VAA");
            configurator.defineSample(SRC_SZA, "SZA");
            configurator.defineSample(SRC_SAA, "SAA");
            configurator.defineSample(SRC_DEM, "elevation");
            configurator.defineSample(SRC_AOT, "aot");
            configurator.defineSample(SRC_AOT_ERR, "aot_err");
            configurator.defineSample(SRC_OZO, "OG");
            configurator.defineSample(SRC_WVP, "WVG");

            toaBandNames = new String[N2ModisConstants.VGT_TOA_BAND_NAMES.length];
            System.arraycopy(N2ModisConstants.VGT_TOA_BAND_NAMES, 0, toaBandNames, 0,
                             N2ModisConstants.VGT_TOA_BAND_NAMES.length);
        }

        BandMathsOp landOp = BandMathsOp.createBooleanExpressionBand(landExpr, sourceProduct);
        Product landMaskProduct = landOp.getTargetProduct();
        configurator.defineSample(SRC_LAND_MASK, landMaskProduct.getBandAt(0).getName(), landMaskProduct);

        for (int i = 0; i < toaBandNames.length; i++) {
            configurator.defineSample(SRC_TOA_RFL + i, toaBandNames[i], sourceProduct);
        }
        SRC_TOA_VAR = SRC_TOA_RFL + toaBandNames.length;

        ImageVarianceOp imageVarianceOp = new ImageVarianceOp();
        imageVarianceOp.setSourceProduct(sourceProduct);
        imageVarianceOp.setParameter("sensor", sensor);
        Product varianceProduct = imageVarianceOp.getTargetProduct();

        for (int i = 0; i < toaBandNames.length; i++) {
            configurator.defineSample(SRC_TOA_VAR + i, toaBandNames[i], varianceProduct);
        }
        if (sdrOnly) {
            SRC_STATUS = SRC_TOA_RFL + toaBandNames.length * 2;
            String expression = 
                    "(not cloud_classif_flags.F_CLOUD and not cloud_classif_flags.F_CLOUD_BUFFER and cloud_classif_flags.F_CLOUD_SHADOW) ? 5 : ("+
                        "(cloud_classif_flags.F_CLOUD or cloud_classif_flags.F_CLOUD_BUFFER) ? 4 : (" +
                            "(cloud_classif_flags.F_CLEAR_SNOW) ? 3 : (" +
                                "(cloud_classif_flags.F_WATER) ? 2 : 1" +
                            ")" +
                        ")" +
                    ")";
            if (sensor == Sensor.MERIS) expression = "(l1_flags.INVALID) ? 0 : (" + expression + ")";
            
            BandMathsOp.BandDescriptor[] bandDescriptors = new BandMathsOp.BandDescriptor[1];
            bandDescriptors[0] = new BandMathsOp.BandDescriptor();
            bandDescriptors[0].name = "status";
            bandDescriptors[0].expression = expression;
            bandDescriptors[0].type = ProductData.TYPESTRING_INT8;

            BandMathsOp bandMathsOp = new BandMathsOp();
            bandMathsOp.setParameter("targetBandDescriptors", bandDescriptors);
            bandMathsOp.setSourceProduct(sourceProduct);
            Product statusProduct = bandMathsOp.getTargetProduct();

            configurator.defineSample(SRC_STATUS, "status", statusProduct);
            if (sensor == Sensor.MERIS) configurator.defineSample(SRC_STATUS + 1, "dem_alt");
        }

    }

    @Override
    protected void configureTargetSamples(SampleConfigurer configurator) {
        if (sdrOnly) {
            int index = 0;
            if (sensor == Sensor.MERIS) {
                for (int i = 0; i < sensor.getNumBands(); i++) {
                    configurator.defineSample(index++, "sdr_" + (i + 1));
                }
                for (int i = 0; i < sensor.getNumBands(); i++) {
                    configurator.defineSample(index++, "sdr_error_" + (i + 1));
                }
            } else if (sensor == Sensor.VGT) {
                for (String bandname : N2ModisConstants.VGT_TOA_BAND_NAMES) {
                    configurator.defineSample(index++, "sdr_" + bandname);
                }
                for (String bandname : N2ModisConstants.VGT_TOA_BAND_NAMES) {
                    configurator.defineSample(index++, "sdr_error_" + bandname);
                }
            }
            configurator.defineSample(index++, "ndvi");
            configurator.defineSample(index++, "aod");
            configurator.defineSample(index, "status");
        } else {
            configurator.defineSample(TRG_BBDR, "MODIS_1");
            configurator.defineSample(TRG_BBDR + 1, "MODIS_2");
            configurator.defineSample(TRG_BBDR + 2, "MODIS_3");
            configurator.defineSample(TRG_BBDR + 3, "MODIS_4");
            configurator.defineSample(TRG_BBDR + 4, "MODIS_5");
            configurator.defineSample(TRG_BBDR + 5, "MODIS_6");
            configurator.defineSample(TRG_BBDR + 6, "MODIS_7");

            configurator.defineSample(TRG_ERRORS, "sig_MODIS_1_1");
            configurator.defineSample(TRG_ERRORS +  1, "sig_MODIS_1_2");
            configurator.defineSample(TRG_ERRORS +  2, "sig_MODIS_1_3");
            configurator.defineSample(TRG_ERRORS +  3, "sig_MODIS_1_4");
            configurator.defineSample(TRG_ERRORS +  4, "sig_MODIS_1_5");
            configurator.defineSample(TRG_ERRORS +  5, "sig_MODIS_1_6");
            configurator.defineSample(TRG_ERRORS +  6, "sig_MODIS_1_7");
            configurator.defineSample(TRG_ERRORS +  7, "sig_MODIS_2_2");
            configurator.defineSample(TRG_ERRORS +  8, "sig_MODIS_2_3");
            configurator.defineSample(TRG_ERRORS +  9, "sig_MODIS_2_4");
            configurator.defineSample(TRG_ERRORS + 10, "sig_MODIS_2_5");
            configurator.defineSample(TRG_ERRORS + 11, "sig_MODIS_2_6");
            configurator.defineSample(TRG_ERRORS + 12, "sig_MODIS_2_7");
            configurator.defineSample(TRG_ERRORS + 13, "sig_MODIS_3_3");
            configurator.defineSample(TRG_ERRORS + 14, "sig_MODIS_3_4");
            configurator.defineSample(TRG_ERRORS + 15, "sig_MODIS_3_5");
            configurator.defineSample(TRG_ERRORS + 16, "sig_MODIS_3_6");
            configurator.defineSample(TRG_ERRORS + 17, "sig_MODIS_3_7");
            configurator.defineSample(TRG_ERRORS + 18, "sig_MODIS_4_4");
            configurator.defineSample(TRG_ERRORS + 19, "sig_MODIS_4_5");
            configurator.defineSample(TRG_ERRORS + 20, "sig_MODIS_4_6");
            configurator.defineSample(TRG_ERRORS + 21, "sig_MODIS_4_7");
            configurator.defineSample(TRG_ERRORS + 22, "sig_MODIS_5_5");
            configurator.defineSample(TRG_ERRORS + 23, "sig_MODIS_5_6");
            configurator.defineSample(TRG_ERRORS + 24, "sig_MODIS_5_7");
            configurator.defineSample(TRG_ERRORS + 25, "sig_MODIS_6_6");
            configurator.defineSample(TRG_ERRORS + 26, "sig_MODIS_6_7");
            configurator.defineSample(TRG_ERRORS + 27, "sig_MODIS_7_7");

            configurator.defineSample(TRG_KERN, "Kvol_BRDF_1");
            configurator.defineSample(TRG_KERN + 2, "Kvol_BRDF_2");
            configurator.defineSample(TRG_KERN + 4, "Kvol_BRDF_3");
            configurator.defineSample(TRG_KERN + 6, "Kvol_BRDF_4");
            configurator.defineSample(TRG_KERN + 8, "Kvol_BRDF_5");
            configurator.defineSample(TRG_KERN + 10, "Kvol_BRDF_6");
            configurator.defineSample(TRG_KERN + 12, "Kvol_BRDF_7");

            configurator.defineSample(TRG_KERN + 1, "Kgeo_BRDF_1");
            configurator.defineSample(TRG_KERN + 3, "Kgeo_BRDF_2");
            configurator.defineSample(TRG_KERN + 5, "Kgeo_BRDF_3");
            configurator.defineSample(TRG_KERN + 7, "Kgeo_BRDF_4");
            configurator.defineSample(TRG_KERN + 9, "Kgeo_BRDF_5");
            configurator.defineSample(TRG_KERN + 11, "Kgeo_BRDF_6");
            configurator.defineSample(TRG_KERN + 13, "Kgeo_BRDF_7");
            
            configurator.defineSample(TRG_NDVI, "NDVI");
            configurator.defineSample(TRG_NDVI + 1, "sig_NDVI");

            configurator.defineSample(TRG_VZA, "VZA");
            configurator.defineSample(TRG_SZA, "SZA");
            configurator.defineSample(TRG_RAA, "RAA");
            configurator.defineSample(TRG_DEM, "DEM");
            configurator.defineSample(TRG_SNOW, "snow_mask");
            configurator.defineSample(TRG_AOD, "AOD550");
            configurator.defineSample(TRG_AODERR, "sig_AOD550");
        }
    }

    @Override
    protected void computePixel(int x, int y, Sample[] sourceSamples, WritableSample[] targetSamples) {
        int status = 0;
        if (sdrOnly) {
            status = sourceSamples[SRC_STATUS].getInt();
            if (status == 2 && sensor == Sensor.MERIS) {
                fillTargetSampleWithNoDataValue(targetSamples);
                // water, do simple atmospheric correction
                if (sourceSamples[SRC_STATUS + 1].getDouble() > -100) {
                    // dem_alt from TP includes sea depth
                    double sdr13 = (sourceSamples[SRC_TOA_RFL + 12].getDouble() - PATH_RADIANCE[12]) / TRANSMISSION[12];
                    for (int i = 0; i < sensor.getNumBands(); i++) {
                        double sdr = (sourceSamples[SRC_TOA_RFL + i].getDouble() - PATH_RADIANCE[i]) / TRANSMISSION[i];
                        sdr = sdr - sdr13;  // normalize
                        targetSamples[i].set(sdr);
                    }
                }
                targetSamples[sensor.getNumBands() * 2 + 2].set(status);
                return;
            } else if (status != 1 && status != 3) {
                // not land and not snow
                fillTargetSampleWithNoDataValue(targetSamples);
                targetSamples[sensor.getNumBands() * 2 + 2].set(status);
                return;
            }
            targetSamples[sensor.getNumBands() * 2 + 2].set(status);
        } else {
            if (!sourceSamples[SRC_LAND_MASK].getBoolean()) {
                // only compute over land
                fillTargetSampleWithNoDataValue(targetSamples);
                return;
            }
        }

        double vza = sourceSamples[SRC_VZA].getDouble();
        double vaa = sourceSamples[SRC_VAA].getDouble();
        double sza = sourceSamples[SRC_SZA].getDouble();
        double saa = sourceSamples[SRC_SAA].getDouble();
        if (sensor == Sensor.AATSR_FWARD || sensor == Sensor.AATSR_NADIR) {
            sza = 90.0 - sza;
            vza = 90.0 - vza;
        }
        double aot = sourceSamples[SRC_AOT].getDouble();
        double delta_aot = sourceSamples[SRC_AOT_ERR].getDouble();
        double hsf = sourceSamples[SRC_DEM].getDouble();
        if (hsf >= 0.0) {
            // add one meter to avoid truncation to zero
            hsf += 1.0;
        }

        hsf *= 0.001;
//        hsf = max(hsf, -0.45); // elevation up to -450m ASL
        // CORRECTION, LG 2011/06/17:
        if (hsf < 0.0 && hsf >= -0.45) {
//            hsf = 0.0;
            hsf = 1.0;
        }

        if (vza < vzaMin || vza > vzaMax ||
                sza < szaMin || sza > szaMax ||
                aot < aotMin || aot > aotMax ||
                hsf < hsfMin || hsf > hsfMax) {
            fillTargetSampleWithNoDataValue(targetSamples);
            if (sdrOnly) {
                // write status
                targetSamples[sensor.getNumBands() * 2 + 2].set(0);
            }
            return;
        }
        if (sdrOnly) {
            targetSamples[sensor.getNumBands() * 2 + 1].set(aot);
        } else {
            targetSamples[TRG_SNOW].set(sourceSamples[SRC_SNOW_MASK].getInt());
            targetSamples[TRG_VZA].set(vza);
            targetSamples[TRG_SZA].set(sza);
            targetSamples[TRG_DEM].set(hsf);
            targetSamples[TRG_AOD].set(aot);
            targetSamples[TRG_AODERR].set(delta_aot);
        }

        double ozo;
        double cwv;
        double gas;

//      CWV & OZO - provided as a constant value and the other as pixel-based, depending on the sensor
//      MERIS: OZO per-pixel, CWV as constant value
//      AATSR: OZO and CWV as constant value
//      VGT: CWV per-pixel, OZO as constant value
        if (sensor == Sensor.MERIS) {
            ozo = 0.001 * sourceSamples[SRC_OZO].getDouble();
            cwv = N2ModisConstants.CWV_CONSTANT_VALUE;  // constant mean value of 1.5
            gas = ozo;
        } else if (sensor == Sensor.AATSR_NADIR || sensor == Sensor.AATSR_FWARD) {
            ozo = N2ModisConstants.OZO_CONSTANT_VALUE;  // constant mean value of 0.32
            cwv = N2ModisConstants.CWV_CONSTANT_VALUE;  // constant mean value of 1.5
            gas = ozo;
        } else if (sensor == Sensor.VGT) {
            ozo = gasLookupTable.getGasMeanVal();   // mean value from whole image
            cwv = sourceSamples[SRC_WVP].getDouble();
            cwv = min(cwv, 4.45);
            gas = cwv;
        } else {
            throw new IllegalArgumentException("Sensor '" + sensor.toString() + "' not supported.");
        }

        double vza_r = toRadians(vza);
        double sza_r = toRadians(sza);
        double muv = cos(vza_r);
        double mus = cos(sza_r);
        double amf = 1.0 / muv + 1.0 / mus;

        double[] toa_rfl = new double[sensor.getNumBands()];
        for (int i = 0; i < toa_rfl.length; i++) {
            double toaRefl = sourceSamples[SRC_TOA_RFL + i].getDouble();
            if (sdrOnly && (toaRefl == 0.0 || Double.isNaN(toaRefl))) {
                // if toa_refl look bad, set to invalid
                targetSamples[sensor.getNumBands() * 2 + 2].set(0);
            }
            toaRefl /= sensor.getCal2Meris()[i];
            if (sensor == Sensor.AATSR_NADIR || sensor == Sensor.AATSR_FWARD) {
                toaRefl *= 0.01 / mus;
            }
            toa_rfl[i] = toaRefl;
        }

        double phi = abs(saa - vaa);
        if (phi > 180.0) {
            phi = 360.0 - phi;
        }
        phi = min(phi, 179);
        phi = max(phi, 1);
        if (!sdrOnly) {
            targetSamples[TRG_RAA].set(phi);
        }

        float[] tg = gasLookupTable.getTg((float) amf, (float) gas);
        float[][][] kx_tg = gasLookupTable.getKxTg((float) amf, (float) gas);

        double[][] f_int_all = interpol_lut_MOMO_kx(vza, sza, phi, hsf, aot);

        double[] sab = new double[sensor.getNumBands()];
        double[] rat_tdw = new double[sensor.getNumBands()];
        double[] rat_tup = new double[sensor.getNumBands()];
        double[] rfl_pix = new double[sensor.getNumBands()];
        for (int i = 0; i < sensor.getNumBands(); i++) {
            double[] f_int = f_int_all[i];

            double rpw = f_int[0] * Math.PI / mus; // Path Radiance
            double ttot = f_int[1] / mus;    // Total TOA flux (Isc*Tup*Tdw)
            sab[i] = f_int[2];        // Spherical Albedo
            rat_tdw[i] = 1.0 - f_int[3];  // tdif_dw / ttot_dw
            rat_tup[i] = 1.0 - f_int[4];  // tup_dw / ttot_dw

            toa_rfl[i] = toa_rfl[i] / tg[i];

            double x_term = (toa_rfl[i] - rpw) / ttot;
            rfl_pix[i] = x_term / (1. + sab[i] * x_term); //calculation of SDR
            if (sdrOnly) {
                targetSamples[i].set(rfl_pix[i]);
            }
        }
        if (sdrOnly && status == 1 && sensor == Sensor.MERIS) {
            float schillerCloud = landNN.compute(new SchillerAlgorithm.SourceSampleAccessor(sourceSamples, SRC_TOA_RFL));
            if (schillerCloud > 1.4) {
                //fillTargetSampleWithNoDataValue(targetSamples);
                targetSamples[sensor.getNumBands() * 2 + 2].set(20);
                //return;
            } else if (uclCloudDetection != null) {
                //do an additional cloud check on the SDRs (only over land)
                float sdrRed = (float) rfl_pix[6]; //sdr_7
                float sdrGreen = (float) rfl_pix[13]; //sdr_14
                float sdrBlue = (float) rfl_pix[2]; //sdr_3
                if (uclCloudDetection.isCloud(sdrRed, sdrGreen, sdrBlue)) {
                    //fillTargetSampleWithNoDataValue(targetSamples);
                    targetSamples[sensor.getNumBands() * 2 + 2].set(10);
                    //return;
                }
            }
        }

        double rfl_red = rfl_pix[sensor.getIndexRed()];
        double rfl_nir = rfl_pix[sensor.getIndexNIR()];
        double norm_ndvi = 1.0 / (rfl_nir + rfl_red);
        double ndvi_land = (sensor.getBndvi() * rfl_nir - sensor.getAndvi() * rfl_red) * norm_ndvi;
        if (sdrOnly) {
            targetSamples[sensor.getNumBands() * 2].set(ndvi_land);
        } else {
            targetSamples[TRG_NDVI].set(ndvi_land);
        }

        double[] err_rad = new double[sensor.getNumBands()];
        double[] err_aod = new double[sensor.getNumBands()];
        double[] err_cwv = new double[sensor.getNumBands()];
        double[] err_ozo = new double[sensor.getNumBands()];
        double[] err_coreg = new double[sensor.getNumBands()];

        for (int i = 0; i < sensor.getNumBands(); i++) {
            double[] f_int = f_int_all[i];
            err_rad[i] = sensor.getRadiometricError() * toa_rfl[i];

            double delta_cwv = sensor.getCwvError() * cwv;
            double delta_ozo = sensor.getOzoError() * ozo;

            err_aod[i] = abs((f_int[5] + f_int[6] * rfl_pix[i]) * delta_aot);
            err_cwv[i] = abs((kx_tg[i][0][0] + kx_tg[i][0][1] * rfl_pix[i]) * delta_cwv);
            err_ozo[i] = abs((kx_tg[i][1][0] + kx_tg[i][1][1] * rfl_pix[i]) * delta_ozo);

            err_coreg[i] = sourceSamples[SRC_TOA_VAR + i].getDouble();
            err_coreg[i] *= sensor.getErrCoregScale();
        }

        Matrix err_aod_cov = matrixSquare(err_aod);
        Matrix err_cwv_cov = matrixSquare(err_cwv);
        Matrix err_ozo_cov = matrixSquare(err_ozo);
        Matrix err_coreg_cov = matrixSquare(err_coreg);

        Matrix err_rad_cov = new Matrix(sensor.getNumBands(), sensor.getNumBands());
        for (int i = 0; i < sensor.getNumBands(); i++) {
            err_rad_cov.set(i, i, err_rad[i] * err_rad[i]);
        }

        Matrix err2_tot_cov = err_aod_cov.plusEquals(err_cwv_cov).plusEquals(err_ozo_cov).plusEquals(
                err_rad_cov).plusEquals(err_coreg_cov);

        if (sdrOnly) {
            for (int i = 0; i < sensor.getNumBands(); i++) {
                targetSamples[sensor.getNumBands() + i].set(err2_tot_cov.get(i, i));
            }
            return;
        }
        // end of implementation needed for landcover cci

        double ndviSum = sensor.getAndvi() + sensor.getBndvi();
        double sig_ndvi_land = pow(
                (pow(ndviSum * rfl_nir * sqrt(
                        err2_tot_cov.get(sensor.getIndexRed(), sensor.getIndexRed())) * norm_ndvi * norm_ndvi, 2) +
                        pow(ndviSum * rfl_red * sqrt(
                                err2_tot_cov.get(sensor.getIndexNIR(), sensor.getIndexNIR())) * norm_ndvi * norm_ndvi, 2)
                ), 0.5);
        targetSamples[TRG_NDVI + 1].set(sig_ndvi_land);

        // BB conversion and error var-cov calculation

        Matrix rfl_pix_m = new Matrix(rfl_pix, rfl_pix.length);
        Matrix bdr_mat_all = nb_coef_arr_all.times(rfl_pix_m).plus(nb_intcp_arr_all);

        double[] bbdrsData = bdr_mat_all.getColumnPackedCopy();
        for (int i = 0; i < bbdrsData.length; i++) {
            targetSamples[TRG_BBDR + i].set(bbdrsData[i]);
        }

        Matrix err2_mat_rfl = nb_coef_arr_all.times(err2_tot_cov).times(nb_coef_arr_all.transpose());
        Matrix err2_n2b_all = new Matrix(n_spc, n_spc);
        for (int i = 0; i < n_spc; i++) {
            err2_n2b_all.set(i, i, rmse_arr_all[i] * rmse_arr_all[i]);
        }
        Matrix err_sum = err2_mat_rfl.plus(err2_n2b_all);

        //int[] relevantErrIndices = {0, 1, 2, 4, 7, 8};
        int[] relevantErrIndices = getRelevantIndices(n_spc);
        double[] columnPackedCopy = err_sum.getColumnPackedCopy();
        for (int i = 0; i < relevantErrIndices.length; i++) {
            final double err_final = sqrt(columnPackedCopy[relevantErrIndices[i]]);
            targetSamples[TRG_ERRORS + i].set(err_final);
        }

        // calculation of kernels (kvol, kgeo) & weighting with (1-Dup)(1-Ddw)
        double[][] f_int_nsky = interpol_lut_Nsky(sza, vza, hsf, aot);

        double phi_r = toRadians(phi);

        double mu_phi = cos(phi_r);
        double mu_ph_ang = mus * muv + sin(vza_r) * sin(sza_r) * mu_phi;
        double ph_ang = acos(mu_ph_ang);

        double kvol = ((PI / 2.0 - ph_ang) * cos(ph_ang) + sin(ph_ang)) / (mus + muv) - PI / 4.0;

        double br = 1.0;
        double hb = 2.0;

        double tan_vp = tan(vza_r);
        double tan_sp = tan(sza_r);
        double sec_vp = 1. / muv;
        double sec_sp = 1. / mus;

        double D2 = tan_vp * tan_vp + tan_sp * tan_sp - 2 * tan_vp * tan_sp * mu_phi;

        double cost = hb * (pow((D2 + pow((tan_vp * tan_sp * sin(phi_r)), 2)), 0.5)) / (sec_vp + sec_sp);
        cost = min(cost, 1.0);
        double t = acos(cost);

        double ocap = (t - sin(t) * cost) * (sec_vp + sec_sp) / PI;

        double kgeo = 0.5 * (1. + mu_ph_ang) * sec_sp * sec_vp + ocap - sec_vp - sec_sp;

        // Nsky-weighted kernels
        Matrix rat_tdw_m = new Matrix(rat_tdw, rat_tdw.length);
        Matrix rat_tup_m = new Matrix(rat_tup, rat_tup.length);
        for (int i_bb = 0; i_bb < n_spc; i_bb++) {
            Matrix nb_coef_arr_D_m = nb_coef_arr[i_bb];
            Matrix m1 = nb_coef_arr_D_m.times(rat_tdw_m);
            double rat_tdw_bb = m1.get(0, 0) + nb_intcp_arr_D[i_bb];

            Matrix m2 = nb_coef_arr_D_m.times(rat_tup_m);
            double rat_tup_bb = m2.get(0, 0) + nb_intcp_arr_D[i_bb];

            // 1/(1-Delta_bb)=(1-rho*S)^2
            Matrix sab_m = new Matrix(sab, sab.length);
            Matrix m3 = nb_coef_arr_D_m.times(sab_m);
            double delta_bb_inv = pow((1. - bdr_mat_all.get(0, 0) * (m3.get(0, 0) + nb_intcp_arr_D[i_bb])), 2);

            double t0 = (1. - rat_tdw_bb) * (1. - rat_tup_bb) * delta_bb_inv;
            double t1 = (1. - rat_tdw_bb) * rat_tup_bb * delta_bb_inv;
            double t2 = rat_tdw_bb * (1. - rat_tup_bb) * delta_bb_inv;
            double t3 = (rat_tdw_bb * rat_tup_bb - (1. - 1. / delta_bb_inv)) * delta_bb_inv;
            double kernel_land_0 = t0 * kvol + t1 * f_int_nsky[i_bb][0] + t2 * f_int_nsky[i_bb][2] + t3 * kpp_vol;
            double kernel_land_1 = t0 * kgeo + t1 * f_int_nsky[i_bb][1] + t2 * f_int_nsky[i_bb][3] + t3 * kpp_geo;
            targetSamples[TRG_KERN + (i_bb * 2)].set(kernel_land_0);
            targetSamples[TRG_KERN + (i_bb * 2) + 1].set(kernel_land_1);
        }
    }

    private void fillTargetSampleWithNoDataValue(WritableSample[] targetSamples) {
        for (WritableSample targetSample : targetSamples) {
            targetSample.set(Float.NaN);
        }
    }

    /**
     * 5-D linear interpolation:
     * returns spectral array [rpw, ttot, sab, rat_tdw, rat_tup, Kx_1, Kx_2]
     * as a function of [vza, sza, phi, hsf, aot] from the interpolation of the MOMO absorption-free LUTs
     */
    private double[][] interpol_lut_MOMO_kx(double vza, double sza, double phi, double hsf, double aot) {
        final LookupTable lut = aotLut.getLut();
        final float[] wvl = aotLut.getWvl();
        final double[] params = aotLut.getLut().getDimension(6).getSequence();
        final double[] kxParams = kxAotLut.getDimension(6).getSequence();
        double[][] result = new double[sensor.getNumBands()][7];

        int lutDimensionCount = lut.getDimensionCount();
        double[] coordinates = new double[lutDimensionCount];
        FracIndex[] fracIndexes = FracIndex.createArray(coordinates.length);
        double[] v = new double[1 << coordinates.length];

        lut.computeFracIndex(lut.getDimension(1), aot, fracIndexes[1]);
        lut.computeFracIndex(lut.getDimension(2), hsf, fracIndexes[2]);
        lut.computeFracIndex(lut.getDimension(3), phi, fracIndexes[3]);
        lut.computeFracIndex(lut.getDimension(4), sza, fracIndexes[4]);
        lut.computeFracIndex(lut.getDimension(5), vza, fracIndexes[5]);

        for (int i = 0; i < result.length; i++) {
            int index = 0;
            lut.computeFracIndex(lut.getDimension(0), wvl[i], fracIndexes[0]);
            for (double param : params) {
                lut.computeFracIndex(lut.getDimension(6), param, fracIndexes[6]);
                result[i][index++] = lut.getValue(fracIndexes, v);
            }
            for (double kxParam : kxParams) {
                lut.computeFracIndex(lut.getDimension(6), kxParam, fracIndexes[6]);
                result[i][index++] = kxAotLut.getValue(fracIndexes, v);
            }
        }
        return result;
    }

    private double[][] interpol_lut_Nsky(double sza, double vza, double hsf, double aot) {
        final LookupTable lut_dw = nskyDwLut.getLut();
        final LookupTable lut_up = nskyUpLut.getLut();
        final double[] broadBandSpecs = lut_dw.getDimension(0).getSequence();
        final double[] dw_params = lut_dw.getDimension(4).getSequence();
        final double[] up_params = lut_up.getDimension(4).getSequence();

        double[][] result = new double[broadBandSpecs.length][4];

        int lutDimensionCount = lut_dw.getDimensionCount();
        double[] coordinates = new double[lutDimensionCount];
        FracIndex[] fracIndexes = FracIndex.createArray(coordinates.length);
        double[] v = new double[1 << coordinates.length];

        lut_dw.computeFracIndex(lut_dw.getDimension(1), aot, fracIndexes[1]);
        lut_dw.computeFracIndex(lut_dw.getDimension(2), hsf, fracIndexes[2]);

        for (int i = 0; i < result.length; i++) {
            int index = 0;
            lut_dw.computeFracIndex(lut_dw.getDimension(0), broadBandSpecs[i], fracIndexes[0]);

            lut_dw.computeFracIndex(lut_dw.getDimension(3), sza, fracIndexes[3]);
            for (double param : dw_params) {
                lut_dw.computeFracIndex(lut_dw.getDimension(4), param, fracIndexes[4]);
                result[i][index++] = lut_dw.getValue(fracIndexes, v);
            }

            lut_up.computeFracIndex(lut_up.getDimension(3), vza, fracIndexes[3]);
            for (double param : up_params) {
                lut_up.computeFracIndex(lut_up.getDimension(4), param, fracIndexes[4]);
                result[i][index++] = lut_up.getValue(fracIndexes, v);
            }
        }
        return result;
    }

    static Matrix matrixSquare(double[] doubles) {
//        Matrix matrix = new Matrix(doubles, doubles.length);
//        return matrix.times(matrix.transpose());

        Matrix matrix = new Matrix(doubles.length, doubles.length);
        for (int i = 0; i < doubles.length; i++) {
            for (int j = 0; j < doubles.length; j++) {
                matrix.set(i, j, doubles[i] * doubles[j]);
            }
        }
        return matrix;
    }

    private int[] getRelevantIndices(int n) {
        int[] idx = new int[(n*n-n)/2+n];
        int k = 0;
        int l = 0;
        for (int i=n; i>0; i--){
            for(int j=0; j<i; j++){
                idx[l++] = j+k;
            }
            k += n+1;
        }
        return idx;
    }

    public static class Spi extends OperatorSpi {

        public Spi()  {
            super(N2ModisOp.class);
        }
    }
}
