package edu.illinois.library.cantaloupe.processor;

import edu.illinois.library.cantaloupe.Application;
import edu.illinois.library.cantaloupe.image.Filter;
import edu.illinois.library.cantaloupe.image.Operation;
import edu.illinois.library.cantaloupe.image.OperationList;
import edu.illinois.library.cantaloupe.image.Rotate;
import edu.illinois.library.cantaloupe.image.Scale;
import edu.illinois.library.cantaloupe.image.SourceFormat;
import edu.illinois.library.cantaloupe.image.OutputFormat;
import edu.illinois.library.cantaloupe.image.Crop;
import edu.illinois.library.cantaloupe.image.Transpose;
import edu.illinois.library.cantaloupe.resource.iiif.ProcessorFeature;
import edu.illinois.library.cantaloupe.util.IOUtils;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.imageio.ImageIO;
import javax.media.jai.RenderedOp;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Processor using the Kakadu kdu_expand and kdu_jp2info command-line tools.
 * Written for version 7.7, but may work with other versions. Uses kdu_expand
 * for cropping and an initial scale reduction factor, and either Java 2D or
 * JAI for all remaining processing steps. kdu_expand generates BMP output
 * which is streamed directly to the ImageIO or JAI reader, which are really
 * fast with BMP for some reason.
 *
 * @see <a href="http://kakadusoftware.com/wp-content/uploads/2014/06/Usage_Examples-v7_7.txt">
 *     Usage Examples for the Demonstration Applications Supplied with Kakadu
 *     V7.7</a>
 */
class KakaduProcessor implements FileProcessor {

    private static Logger logger = LoggerFactory.
            getLogger(KakaduProcessor.class);

    public static final String JAVA2D_SCALE_MODE_CONFIG_KEY =
            "KakaduProcessor.post_processor.java2d.scale_mode";
    public static final String PATH_TO_BINARIES_CONFIG_KEY =
            "KakaduProcessor.path_to_binaries";
    public static final String POST_PROCESSOR_CONFIG_KEY =
            "KakaduProcessor.post_processor";

    private static final short MAX_REDUCTION_FACTOR = 5;
    private static final Set<ProcessorFeature> SUPPORTED_FEATURES =
            new HashSet<>();
    private static final Set<edu.illinois.library.cantaloupe.resource.iiif.v1_1.Quality>
            SUPPORTED_IIIF_1_1_QUALITIES = new HashSet<>();
    private static final Set<edu.illinois.library.cantaloupe.resource.iiif.v2_0.Quality>
            SUPPORTED_IIIF_2_0_QUALITIES = new HashSet<>();

    private static final ExecutorService executorService =
            Executors.newCachedThreadPool();

    private static Path stdoutSymlink;

    static {
        SUPPORTED_IIIF_1_1_QUALITIES.add(
                edu.illinois.library.cantaloupe.resource.iiif.v1_1.Quality.BITONAL);
        SUPPORTED_IIIF_1_1_QUALITIES.add(
                edu.illinois.library.cantaloupe.resource.iiif.v1_1.Quality.COLOR);
        SUPPORTED_IIIF_1_1_QUALITIES.add(
                edu.illinois.library.cantaloupe.resource.iiif.v1_1.Quality.GRAY);
        SUPPORTED_IIIF_1_1_QUALITIES.add(
                edu.illinois.library.cantaloupe.resource.iiif.v1_1.Quality.NATIVE);

        SUPPORTED_IIIF_2_0_QUALITIES.add(
                edu.illinois.library.cantaloupe.resource.iiif.v2_0.Quality.BITONAL);
        SUPPORTED_IIIF_2_0_QUALITIES.add(
                edu.illinois.library.cantaloupe.resource.iiif.v2_0.Quality.COLOR);
        SUPPORTED_IIIF_2_0_QUALITIES.add(
                edu.illinois.library.cantaloupe.resource.iiif.v2_0.Quality.DEFAULT);
        SUPPORTED_IIIF_2_0_QUALITIES.add(
                edu.illinois.library.cantaloupe.resource.iiif.v2_0.Quality.GRAY);

        SUPPORTED_FEATURES.add(ProcessorFeature.MIRRORING);
        SUPPORTED_FEATURES.add(ProcessorFeature.REGION_BY_PERCENT);
        SUPPORTED_FEATURES.add(ProcessorFeature.REGION_BY_PIXELS);
        SUPPORTED_FEATURES.add(ProcessorFeature.ROTATION_ARBITRARY);
        SUPPORTED_FEATURES.add(ProcessorFeature.ROTATION_BY_90S);
        SUPPORTED_FEATURES.add(ProcessorFeature.SIZE_ABOVE_FULL);
        SUPPORTED_FEATURES.add(ProcessorFeature.SIZE_BY_FORCED_WIDTH_HEIGHT);
        SUPPORTED_FEATURES.add(ProcessorFeature.SIZE_BY_HEIGHT);
        SUPPORTED_FEATURES.add(ProcessorFeature.SIZE_BY_PERCENT);
        SUPPORTED_FEATURES.add(ProcessorFeature.SIZE_BY_WIDTH);
        SUPPORTED_FEATURES.add(ProcessorFeature.SIZE_BY_WIDTH_HEIGHT);

        // Due to a quirk of kdu_expand, this processor requires access to
        // /dev/stdout.
        final File devStdout = new File("/dev/stdout");
        if (devStdout.exists() && devStdout.canWrite()) {
            // Due to another quirk of kdu_expand, we need to create a symlink
            // from {temp path}/stdout.bmp to /dev/stdout, to tell kdu_expand
            // what format to write.
            try {
                stdoutSymlink = createStdoutSymlink();
                logger.info("Using stdout symlink at {}", stdoutSymlink);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        } else {
            logger.error("Sorry, but KakaduProcessor won't work on this " +
                    "platform as it requires access to /dev/stdout.");
        }
    }

    private static Path createStdoutSymlink() throws IOException {
        File tempDir = new File(System.getProperty("java.io.tmpdir"));
        final File link = new File(tempDir.getAbsolutePath() + "/cantaloupe-" +
                UUID.randomUUID() + ".bmp");
        link.deleteOnExit();
        final File devStdout = new File("/dev/stdout");
        return Files.createSymbolicLink(Paths.get(link.getAbsolutePath()),
                Paths.get(devStdout.getAbsolutePath()));
    }

    /**
     * @param binaryName Name of one of the kdu_* binaries
     * @return
     */
    private static String getPath(String binaryName) {
        String path = Application.getConfiguration().
                getString(PATH_TO_BINARIES_CONFIG_KEY);
        if (path != null) {
            path = StringUtils.stripEnd(path, File.separator) +
                    File.separator + binaryName;
        } else {
            path = binaryName;
        }
        return path;
    }

    @Override
    public Set<OutputFormat> getAvailableOutputFormats(SourceFormat sourceFormat) {
        Set<OutputFormat> outputFormats = new HashSet<>();
        if (sourceFormat == SourceFormat.JP2) {
            outputFormats.addAll(Java2dUtil.imageIoOutputFormats());
        }
        return outputFormats;
    }

    /**
     * Gets the size of the given image by parsing the XML output of
     * kdu_jp2info.
     *
     * @param inputFile Source image
     * @param sourceFormat Format of the source image
     * @return
     * @throws ProcessorException
     */
    @Override
    public Dimension getSize(File inputFile, SourceFormat sourceFormat)
            throws ProcessorException {
        if (getAvailableOutputFormats(sourceFormat).size() < 1) {
            throw new UnsupportedSourceFormatException(sourceFormat);
        }
        final List<String> command = new ArrayList<>();
        command.add(getPath("kdu_jp2info"));
        command.add("-i");
        command.add(inputFile.getAbsolutePath());
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            logger.debug("Invoking {}", StringUtils.join(pb.command(), " "));
            Process process = pb.start();

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(process.getInputStream());

            XPath xpath = XPathFactory.newInstance().newXPath();
            XPathExpression expr = xpath.compile("//codestream/width");
            int width = (int) Math.round((double) expr.evaluate(doc, XPathConstants.NUMBER));
            expr = xpath.compile("//codestream/height");
            int height = (int) Math.round((double) expr.evaluate(doc, XPathConstants.NUMBER));
            return new Dimension(width, height);
        } catch (SAXException e) {
            throw new ProcessorException("Failed to parse XML. Command: " +
                    StringUtils.join(command, " "), e);
        } catch (Exception e) {
            throw new ProcessorException(e.getMessage(), e);
        }
    }

    @Override
    public Set<ProcessorFeature> getSupportedFeatures(SourceFormat sourceFormat) {
        Set<ProcessorFeature> features = new HashSet<>();
        if (getAvailableOutputFormats(sourceFormat).size() > 0) {
            features.addAll(SUPPORTED_FEATURES);
        }
        return features;
    }

    @Override
    public Set<edu.illinois.library.cantaloupe.resource.iiif.v1_1.Quality>
    getSupportedIiif1_1Qualities(final SourceFormat sourceFormat) {
        Set<edu.illinois.library.cantaloupe.resource.iiif.v1_1.Quality>
                qualities = new HashSet<>();
        if (getAvailableOutputFormats(sourceFormat).size() > 0) {
            qualities.addAll(SUPPORTED_IIIF_1_1_QUALITIES);
        }
        return qualities;
    }

    @Override
    public Set<edu.illinois.library.cantaloupe.resource.iiif.v2_0.Quality>
    getSupportedIiif2_0Qualities(final SourceFormat sourceFormat) {
        Set<edu.illinois.library.cantaloupe.resource.iiif.v2_0.Quality>
                qualities = new HashSet<>();
        if (getAvailableOutputFormats(sourceFormat).size() > 0) {
            qualities.addAll(SUPPORTED_IIIF_2_0_QUALITIES);
        }
        return qualities;
    }

    @Override
    public void process(final OperationList ops,
                        final SourceFormat sourceFormat,
                        final Dimension fullSize,
                        final File inputFile,
                        final WritableByteChannel writableChannel)
            throws ProcessorException {
        final Set<OutputFormat> availableOutputFormats =
                getAvailableOutputFormats(sourceFormat);
        if (getAvailableOutputFormats(sourceFormat).size() < 1) {
            throw new UnsupportedSourceFormatException(sourceFormat);
        } else if (!availableOutputFormats.contains(ops.getOutputFormat())) {
            throw new UnsupportedOutputFormatException();
        }

        class ChannelCopier implements Runnable {
            private final ReadableByteChannel inputChannel;
            private final WritableByteChannel outputChannel;

            public ChannelCopier(ReadableByteChannel in, WritableByteChannel out) {
                inputChannel = in;
                outputChannel = out;
            }

            public void run() {
                try {
                    IOUtils.copy(inputChannel, outputChannel);
                } catch (IOException e) {
                    if (!e.getMessage().startsWith("Broken pipe")) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
        }

        // will receive stderr output from kdu_expand
        final ByteArrayOutputStream errorBucket = new ByteArrayOutputStream();
        final WritableByteChannel errorBucketChannel = Channels.newChannel(errorBucket);
        try {
            final ReductionFactor reductionFactor = new ReductionFactor();
            final ProcessBuilder pb = getProcessBuilder(inputFile, ops,
                    fullSize, reductionFactor);
            logger.debug("Invoking {}", StringUtils.join(pb.command(), " "));
            final Process process = pb.start();

            executorService.submit(
                    new ChannelCopier(
                            Channels.newChannel(process.getErrorStream()),
                            errorBucketChannel));

            Configuration config = Application.getConfiguration();
            switch (config.getString(POST_PROCESSOR_CONFIG_KEY, "java2d").toLowerCase()) {
                case "jai":
                    logger.debug("Post-processing using JAI");
                    postProcessUsingJai(
                            Channels.newChannel(process.getInputStream()), ops,
                            reductionFactor, writableChannel);
                    break;
                default:
                    logger.debug("Post-processing using Java2D");
                    postProcessUsingJava2d(
                            Channels.newChannel(process.getInputStream()), ops,
                            reductionFactor, writableChannel);
                    break;
            }
            try {
                final int code = process.waitFor();
                if (code != 0) {
                    logger.warn("kdu_expand returned with code {}", code);
                    final String errorStr = errorBucket.toString();
                    if (errorStr != null && errorStr.length() > 0) {
                        throw new ProcessorException(errorStr);
                    }
                }
            } finally {
                process.getInputStream().close();
                process.getOutputStream().close();
                process.getErrorStream().close();
                process.destroy();
            }
        } catch (IOException | InterruptedException e) {
            String msg = e.getMessage();
            final String errorStr = errorBucket.toString();
            if (errorStr != null && errorStr.length() > 0) {
                msg += " (command output: " + msg + ")";
            }
            throw new ProcessorException(msg, e);
        }
    }

    /**
     * Gets a ProcessBuilder corresponding to the given parameters.
     *
     * @param inputFile
     * @param opList
     * @param imageSize The full size of the source image
     * @param reduction {@link ReductionFactor#factor} property modified by
     * reference
     * @return Command string
     */
    private ProcessBuilder getProcessBuilder(final File inputFile,
                                             final OperationList opList,
                                             final Dimension imageSize,
                                             final ReductionFactor reduction) {
        final List<String> command = new ArrayList<>();
        command.add(getPath("kdu_expand"));
        command.add("-quiet");
        command.add("-no_alpha");
        command.add("-i");
        command.add(inputFile.getAbsolutePath());

        for (Operation op : opList) {
            if (op instanceof Crop) {
                final Crop crop = (Crop) op;
                if (!crop.isFull()) {
                    final double x = crop.getX() / imageSize.width;
                    final double y = crop.getY() / imageSize.height;
                    final double width = crop.getWidth() / imageSize.width;
                    final double height = crop.getHeight() / imageSize.height;
                    command.add("-region");
                    command.add(String.format("{%.7f,%.7f},{%.7f,%.7f}",
                            y, x, height, width));
                }
            } else if (op instanceof Scale) {
                // kdu_expand is not capable of arbitrary scaling, but it does
                // offer a -reduce option which is capable of downscaling by
                // factors of 2, significantly speeding decompression. We can
                // use it if the scale mode is ASPECT_FIT_* and either the
                // percent is <=50, or the height/width are <=50% of full size.
                // The smaller the scale, the bigger the win.
                final Scale scale = (Scale) op;
                final Dimension tileSize = getCroppedSize(opList, imageSize);
                if (scale.getMode() != Scale.Mode.FULL) {
                    if (scale.getMode() == Scale.Mode.ASPECT_FIT_WIDTH) {
                        double hvScale = (double) scale.getWidth() /
                                (double) tileSize.width;
                        reduction.factor = ProcessorUtil.getReductionFactor(
                                hvScale, MAX_REDUCTION_FACTOR);
                    } else if (scale.getMode() == Scale.Mode.ASPECT_FIT_HEIGHT) {
                        double hvScale = (double) scale.getHeight() /
                                (double) tileSize.height;
                        reduction.factor = ProcessorUtil.getReductionFactor(
                                hvScale, MAX_REDUCTION_FACTOR);
                    } else if (scale.getMode() == Scale.Mode.ASPECT_FIT_INSIDE) {
                        double hScale = (double) scale.getWidth() /
                                (double) tileSize.width;
                        double vScale = (double) scale.getHeight() /
                                (double) tileSize.height;
                        reduction.factor = ProcessorUtil.getReductionFactor(
                                Math.min(hScale, vScale), MAX_REDUCTION_FACTOR);
                    } else if (scale.getPercent() != null) {
                        reduction.factor = ProcessorUtil.getReductionFactor(
                                scale.getPercent(), MAX_REDUCTION_FACTOR);
                    } else {
                        reduction.factor = 0;
                    }
                    if (reduction.factor > 0) {
                        command.add("-reduce");
                        command.add(reduction.factor + "");
                    }
                }
            }
        }

        command.add("-o");
        command.add(stdoutSymlink.toString());

        return new ProcessBuilder(command);
    }

    /**
     * Computes the effective size of an image after all crop operations are
     * applied but excluding any scale operations, in order to use
     * kdu_expand's -reduce argument.
     *
     * @param opList
     * @param fullSize
     * @return
     */
    private Dimension getCroppedSize(OperationList opList, Dimension fullSize) {
        Dimension tileSize = (Dimension) fullSize.clone();
        for (Operation op : opList) {
            if (op instanceof Crop) {
                tileSize = ((Crop) op).getRectangle(tileSize).getSize();
            }
        }
        return tileSize;
    }

    private void postProcessUsingJai(final ReadableByteChannel readableChannel,
                                     final OperationList opList,
                                     final ReductionFactor reductionFactor,
                                     final WritableByteChannel writableChannel)
            throws IOException, ProcessorException {
        RenderedImage renderedImage =
                JaiUtil.readImage(readableChannel);
        RenderedOp renderedOp = JaiUtil.reformatImage(
                RenderedOp.wrapRenderedImage(renderedImage),
                new Dimension(512, 512));
        for (Operation op : opList) {
            if (op instanceof Scale) {
                renderedOp = JaiUtil.scaleImage(renderedOp, (Scale) op,
                        reductionFactor.factor);
            } else if (op instanceof Transpose) {
                renderedOp = JaiUtil.transposeImage(renderedOp,
                        (Transpose) op);
            } else if (op instanceof Rotate) {
                renderedOp = JaiUtil.rotateImage(renderedOp, (Rotate) op);
            } else if (op instanceof Filter) {
                renderedOp = JaiUtil.filterImage(renderedOp, (Filter) op);
            }
        }
        ImageIO.write(renderedOp, opList.getOutputFormat().getExtension(),
                ImageIO.createImageOutputStream(writableChannel));
    }

    private void postProcessUsingJava2d(final ReadableByteChannel readableChannel,
                                        final OperationList opList,
                                        final ReductionFactor reductionFactor,
                                        final WritableByteChannel writableChannel)
            throws IOException, ProcessorException {
        BufferedImage image = Java2dUtil.readImage(readableChannel);
        for (Operation op : opList) {
            if (op instanceof Scale) {
                final boolean highQuality = Application.getConfiguration().
                        getString(JAVA2D_SCALE_MODE_CONFIG_KEY, "speed").
                        equals("quality");
                image = Java2dUtil.scaleImageWithG2d(image,
                        (Scale) op, reductionFactor.factor, highQuality);
            } else if (op instanceof Transpose) {
                image = Java2dUtil.transposeImage(image,
                        (Transpose) op);
            } else if (op instanceof Rotate) {
                image = Java2dUtil.rotateImage(image,
                        (Rotate) op);
            } else if (op instanceof Filter) {
                image = Java2dUtil.filterImage(image,
                        (Filter) op);
            }
        }
        Java2dUtil.writeImage(image, opList.getOutputFormat(),
                writableChannel);
        image.flush();
    }

}
