/*
* Confocal Microscopy Unit - CNIO : BioImage Analysis
*
* Developer : Ana Cayuela
*
* Last Update July 2020
*
*/

import ij.IJ
import ij.ImagePlus
import ij.WindowManager
import ij.gui.Roi
import ij.gui.ShapeRoi
import ij.measure.ResultsTable
import ij.plugin.ChannelSplitter
import ij.plugin.ZProjector
import ij.plugin.frame.RoiManager
import inra.ijpb.binary.BinaryImages
import inra.ijpb.color.CommonColors
import inra.ijpb.data.image.ColorImages
import inra.ijpb.morphology.Strel
import loci.plugins.BF
import loci.plugins.in.ImporterOptions
import net.imglib2.converter.ChannelARGBConverter
import org.apache.commons.compress.utils.FileNameUtils

import java.io.File;


// INPUT UI
//
#@File(label = "Input File Directory", style = "directory") inputDir
#@File(label = "Output directory", style = "directory") outputDir
#@Integer(label = "DAPI Channel", value = 0) dapiChannel
#@Integer(label = "GFP Channel", value = 1) greenChannel
#@Integer(label = "Ki67 Channel", value = 2) redChannel

//def inputDir = new File("/home/cnio-cmu/acayuela/input")
//def outputDir = new File("/home/cnio-cmu/acayuela/output")
//def dapiChannel = 0
//def greenChannel = 1
//def redChannel = 2
// IDE
//
//
//def headless = true;
//new ij.ImageJ().setVisible(false);

IJ.log("-Parameters selected: ")
IJ.log("    -inputFileDir: " + inputDir)
IJ.log("    -outputDir: " + outputDir)
IJ.log("                                                           ");

/** Get files (images) from input directory */
def listOfFiles = inputDir.listFiles();

for (def i = 0; i < listOfFiles.length; i++) {

    if (!listOfFiles[i].getName().contains("DS")) {


        /** Importer options for .lif file */
        def options = new ImporterOptions();
        options.setId(inputDir.getAbsolutePath() + File.separator + listOfFiles[i].getName());
        options.setSplitChannels(false);
        options.setSplitTimepoints(false);
        options.setSplitFocalPlanes(false);
        options.setAutoscale(true);
        options.setStackFormat(ImporterOptions.VIEW_HYPERSTACK);
        options.setStackOrder(ImporterOptions.ORDER_XYCZT);
        options.setColorMode(ImporterOptions.COLOR_MODE_COMPOSITE);
        options.setCrop(false);
        options.setOpenAllSeries(true);
        def imps = BF.openImagePlus(options);
        IJ.log("Analyzing file: " + listOfFiles[i].getName());
        /** Create results table to store results */
        def tableConditions = new ResultsTable()
        def counter = 0
        for (def k = 0.intValue(); k < imps.length; k++) {

            /** Create image for each file in the input directory */

            //Analyze image with number of slices bigger than 1
            if (imps[k].getNSlices() > 1) {
                counter ++
                IJ.log("Analyzing serie: " + imps[k].getTitle());
                def imp = imps[k];
                imp = ZProjector.run(imp, "max");
                /** Split Channels */
                def channels = ChannelSplitter.split(imp)
                /** Get each individual channel */
                def dapi = channels[dapiChannel.intValue()].duplicate()
                def green = channels[greenChannel.intValue()].duplicate()
                def red = channels[redChannel.intValue()].duplicate()

                /** Process dapi channel */
                dapi.show()
                /** Segment dapi channel with Cellpose */
                IJ.run(dapi, "Cellpose Advanced (custom model)", "diameter=14 cellproba_threshold=0.0 flow_threshold=1.1 anisotropy=1.0 diam_threshold=99999999 model_path=cyto2 model=cyto nuclei_channel=0 cyto_channel=1 dimensionmode=2D stitch_threshold=-0.0 omni=false cluster=false additional_flags=");
                dapi.hide()
                def dapiSeg = WindowManager.getImage(dapi.getShortTitle() + "-cellpose")
                /** Cellpose labels to rois */
                IJ.run(dapiSeg, "Label image to ROIs", "");
                IJ.saveAs(dapiSeg, "Tiff", outputDir.getAbsolutePath()+File.separator+dapi.getTitle()+"_seg")
                def rm = RoiManager.getInstance();
                def roisDapi = rm.getRoisAsArray();
                rm.reset()

                /** Convert Cellpose label image to binary image */
                def color = CommonColors.fromLabel("Red").getColor();
                // Call binary overlay conversion
                def binaryDapi = ColorImages.binaryOverlay(dapiSeg, dapiSeg, color);
                dapiSeg.hide()
                IJ.run(binaryDapi, "8-bit", "");
                IJ.run(binaryDapi, "Auto Threshold", "method=Otsu ignore_black white");
                IJ.run(binaryDapi, "Create Selection", "");
                /**  Get dapi roi from binary image */
                def roiDapi = binaryDapi.getRoi()

                /** Process GFP (green) channel- Single positive green */
                green.setRoi(roiDapi)
                def greenMean = green.getStatistics().mean
                def greenStd = green.getStatistics().stdDev
                def positiveGreenRois = new ArrayList<Roi>();
                for (def j = 0.intValue(); j < roisDapi.length; j++) {
                    green.setRoi(roisDapi[j])
                    if (green.getStatistics().mean > (greenMean - greenStd))
                        positiveGreenRois.add(roisDapi[j])
                }

                /** From singe positive green- Double positive for ki67 (red) */
                def doublePositiveGreenRed = new ArrayList<Roi>();
                def meanRed = new ArrayList<Double>();
                def stdRed = new ArrayList<Double>();
                for (def j = 0.intValue(); j /home/cnio-cmu/acayuela/input< positiveGreenRois.size(); j++) {
                    red.setRoi(positiveGreenRois.get(j))
                    meanRed.add(red.getStatistics().mean)
                    stdRed.add(red.getStatistics().stdDev)
                }
                for (def j = 0.intValue(); j < positiveGreenRois.size(); j++) {
                    red.setRoi(positiveGreenRois.get(j))
                    if (red.getStatistics().mean > (meanRed.stream()
                            .mapToDouble(d -> d)
                            .average()
                            .orElse(0.0) - stdRed.stream()
                            .mapToDouble(d -> d)
                            .average()
                            .orElse(0.0)))
                        doublePositiveGreenRed.add(positiveGreenRois.get(j))
                }
                tableConditions.incrementCounter();
                tableConditions.setValue("Image Serie Title", counter, imps[k].getTitle())
                tableConditions.setValue("Total DAPI", counter, roisDapi.length)
                tableConditions.setValue("Total GFP+", counter, positiveGreenRois.size())
                tableConditions.setValue("Total GFP-Ki67+", counter, doublePositiveGreenRed.size())
            }
        }

        tableConditions.saveAs(outputDir.getAbsolutePath() + File.separator + listOfFiles[i].getName() + ".csv")


    }
}


IJ.log("Done!!!")