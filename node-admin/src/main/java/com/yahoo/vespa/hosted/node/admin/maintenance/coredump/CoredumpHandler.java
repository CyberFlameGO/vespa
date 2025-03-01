// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.maintenance.coredump;

import com.yahoo.config.provision.DockerImage;
import com.yahoo.jdisc.Timer;
import com.yahoo.security.KeyId;
import com.yahoo.security.SecretSharedKey;
import com.yahoo.vespa.flags.FetchVector;
import com.yahoo.vespa.flags.FlagSource;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.flags.StringFlag;
import com.yahoo.vespa.hosted.node.admin.configserver.cores.CoreDumpMetadata;
import com.yahoo.vespa.hosted.node.admin.configserver.cores.Cores;
import com.yahoo.vespa.hosted.node.admin.configserver.cores.bindings.ReportCoreDumpRequest;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeSpec;
import com.yahoo.vespa.hosted.node.admin.container.metrics.Dimensions;
import com.yahoo.vespa.hosted.node.admin.container.metrics.Metrics;
import com.yahoo.vespa.hosted.node.admin.maintenance.sync.ZstdCompressingInputStream;
import com.yahoo.vespa.hosted.node.admin.nodeadmin.ConvergenceException;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentContext;
import com.yahoo.vespa.hosted.node.admin.task.util.file.FileDeleter;
import com.yahoo.vespa.hosted.node.admin.task.util.file.FileFinder;
import com.yahoo.vespa.hosted.node.admin.task.util.file.FileMover;
import com.yahoo.vespa.hosted.node.admin.task.util.file.MakeDirectory;
import com.yahoo.vespa.hosted.node.admin.task.util.fs.ContainerPath;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static com.yahoo.vespa.hosted.node.admin.task.util.file.FileFinder.nameEndsWith;
import static com.yahoo.vespa.hosted.node.admin.task.util.file.FileFinder.nameMatches;
import static com.yahoo.vespa.hosted.node.admin.task.util.file.FileFinder.nameStartsWith;
import static com.yahoo.yolean.Exceptions.uncheck;

/**
 * Finds coredumps, collects metadata and reports them
 *
 * @author freva
 */
public class CoredumpHandler {

    public static final String COREDUMP_FILENAME_PREFIX = "dump_";

    private static final Logger logger = Logger.getLogger(CoredumpHandler.class.getName());
    private static final Pattern HS_ERR_PATTERN = Pattern.compile("hs_err_pid[0-9]+\\.log");
    private static final String PROCESSING_DIRECTORY_NAME = "processing";
    private static final String METADATA2_FILE_NAME = "metadata2.json";
    private static final String COMPRESSED_EXTENSION = ".zst";
    private static final String ENCRYPTED_EXTENSION = ".enc";

    private final CoreCollector coreCollector;
    private final Cores cores;
    private final String crashPatchInContainer;
    private final Path doneCoredumpsPath;
    private final Metrics metrics;
    private final Timer timer;
    private final Supplier<String> coredumpIdSupplier;
    private final SecretSharedKeySupplier secretSharedKeySupplier;
    private final StringFlag coreEncryptionPublicKeyIdFlag;

    /**
     * @param crashPathInContainer path inside the container where core dump are dumped
     * @param doneCoredumpsPath    path on host where processed core dumps are stored
     */
    public CoredumpHandler(CoreCollector coreCollector, Cores cores,
                           String crashPathInContainer, Path doneCoredumpsPath, Metrics metrics, Timer timer,
                           SecretSharedKeySupplier secretSharedKeySupplier, FlagSource flagSource) {
        this(coreCollector, cores, crashPathInContainer, doneCoredumpsPath,
                metrics, timer, () -> UUID.randomUUID().toString(), secretSharedKeySupplier,
                flagSource);
    }

    CoredumpHandler(CoreCollector coreCollector, Cores cores,
                    String crashPathInContainer, Path doneCoredumpsPath, Metrics metrics,
                    Timer timer, Supplier<String> coredumpIdSupplier,
                    SecretSharedKeySupplier secretSharedKeySupplier, FlagSource flagSource) {
        this.coreCollector = coreCollector;
        this.cores = cores;
        this.crashPatchInContainer = crashPathInContainer;
        this.doneCoredumpsPath = doneCoredumpsPath;
        this.metrics = metrics;
        this.timer = timer;
        this.coredumpIdSupplier = coredumpIdSupplier;
        this.secretSharedKeySupplier = secretSharedKeySupplier;
        this.coreEncryptionPublicKeyIdFlag = Flags.CORE_ENCRYPTION_PUBLIC_KEY_ID.bindTo(flagSource);
    }


    public void converge(NodeAgentContext context, Optional<DockerImage> dockerImage, boolean throwIfCoreBeingWritten) {
        ContainerPath containerCrashPath = context.paths().of(crashPatchInContainer, context.users().vespa());
        ContainerPath containerProcessingPath = containerCrashPath.resolve(PROCESSING_DIRECTORY_NAME);

        updateMetrics(context, containerCrashPath);

        if (throwIfCoreBeingWritten) {
            List<String> pendingCores = FileFinder.files(containerCrashPath)
                    .match(fileAttributes -> !isReadyForProcessing(fileAttributes))
                    .maxDepth(1).stream()
                    .map(FileFinder.FileAttributes::filename)
                    .toList();
            if (!pendingCores.isEmpty())
                throw ConvergenceException.ofError(String.format("Cannot process %s coredumps: Still being written",
                        pendingCores.size() < 5 ? pendingCores : pendingCores.size()));
        }

        // Check if we have already started to process a core dump or we can enqueue a new core one
        getCoredumpToProcess(context, containerCrashPath, containerProcessingPath)
                .ifPresent(path -> processAndReportSingleCoreDump(context, path, dockerImage));
    }

    /** @return path to directory inside processing directory that contains a core dump file to process */
    Optional<ContainerPath> getCoredumpToProcess(NodeAgentContext context, ContainerPath containerCrashPath, ContainerPath containerProcessingPath) {
        return FileFinder.directories(containerProcessingPath).stream()
                .map(FileFinder.FileAttributes::path)
                .findAny()
                .map(ContainerPath.class::cast)
                .or(() -> enqueueCoredump(context, containerCrashPath, containerProcessingPath));
    }

    /**
     * Moves a coredump and related hs_err file(s) to a new directory under the processing/ directory.
     * Limit to only processing one coredump at the time, starting with the oldest.
     *
     * Assumption: hs_err files are much smaller than core files and are written (last modified time)
     * before the core file.
     *
     * @return path to directory inside processing directory which contains the enqueued core dump file
     */
    Optional<ContainerPath> enqueueCoredump(NodeAgentContext context, ContainerPath containerCrashPath, ContainerPath containerProcessingPath) {
        Predicate<String> isCoreDump = filename -> !HS_ERR_PATTERN.matcher(filename).matches();

        List<Path> toProcess = FileFinder.files(containerCrashPath)
                .match(attributes -> {
                    if (isReadyForProcessing(attributes)) {
                        return true;
                    } else {
                        if (isCoreDump.test(attributes.filename()))
                            context.log(logger, attributes.path() + " is still being written");
                        return false;
                    }
                })
                .maxDepth(1)
                .stream()
                .sorted(Comparator.comparing(FileFinder.FileAttributes::lastModifiedTime))
                .map(FileFinder.FileAttributes::path)
                .toList();

        int coredumpIndex = IntStream.range(0, toProcess.size())
                .filter(i -> isCoreDump.test(toProcess.get(i).getFileName().toString()))
                .findFirst()
                .orElse(-1);

        // Either there are no files in crash directory, or all the files are hs_err files.
        if (coredumpIndex == -1) return Optional.empty();

        ContainerPath enqueuedDir = containerProcessingPath.resolve(coredumpIdSupplier.get());
        new MakeDirectory(enqueuedDir).createParents().converge(context);
        IntStream.range(0, coredumpIndex + 1)
                .forEach(i -> {
                    Path path = toProcess.get(i);
                    String prefix = i == coredumpIndex ? COREDUMP_FILENAME_PREFIX : "";
                    new FileMover(path, enqueuedDir.resolve(prefix + path.getFileName())).converge(context);
                });
        return Optional.of(enqueuedDir);
    }

    private String corePublicKeyFlagValue(NodeAgentContext context) {
        return coreEncryptionPublicKeyIdFlag.with(FetchVector.Dimension.NODE_TYPE, context.nodeType().name()).value();
    }

    static OutputStream wrapWithEncryption(OutputStream wrappedStream, SecretSharedKey sharedCoreKey) {
        return sharedCoreKey.makeEncryptionCipher().wrapOutputStream(wrappedStream);
    }

    /**
     * Compresses and, if a key is provided, encrypts core file (and deletes the uncompressed core), then moves
     * the entire core dump processing directory to {@link #doneCoredumpsPath} for archive
     */
    private void finishProcessing(NodeAgentContext context, ContainerPath coredumpDirectory, SecretSharedKey sharedCoreKey) {
        ContainerPath coreFile = findCoredumpFileInProcessingDirectory(coredumpDirectory);
        String extension = COMPRESSED_EXTENSION + ENCRYPTED_EXTENSION;
        ContainerPath compressedCoreFile = coreFile.resolveSibling(coreFile.getFileName() + extension);

        try (ZstdCompressingInputStream zcis = new ZstdCompressingInputStream(Files.newInputStream(coreFile));
             OutputStream fos = wrapWithEncryption(Files.newOutputStream(compressedCoreFile), sharedCoreKey)) {
            zcis.transferTo(fos);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        new FileDeleter(coreFile).converge(context);

        Path newCoredumpDirectory = doneCoredumpsPath.resolve(context.containerName().asString());
        new MakeDirectory(newCoredumpDirectory).createParents().converge(context);
        // Files.move() does not support moving non-empty directories across providers, move using host paths
        new FileMover(coredumpDirectory.pathOnHost(), newCoredumpDirectory.resolve(coredumpDirectory.getFileName().toString()))
                .converge(context);
    }

    ContainerPath findCoredumpFileInProcessingDirectory(ContainerPath coredumpProccessingDirectory) {
        return (ContainerPath) FileFinder.files(coredumpProccessingDirectory)
                .match(nameStartsWith(COREDUMP_FILENAME_PREFIX).and(nameEndsWith(COMPRESSED_EXTENSION).negate())
                                                               .and(nameEndsWith(ENCRYPTED_EXTENSION).negate()))
                .maxDepth(1)
                .stream()
                .map(FileFinder.FileAttributes::path)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No coredump file found in processing directory " + coredumpProccessingDirectory));
    }

    void updateMetrics(NodeAgentContext context, ContainerPath containerCrashPath) {
        Dimensions dimensions = generateDimensions(context);

        // Unprocessed coredumps
        int numberOfUnprocessedCoredumps = FileFinder.files(containerCrashPath)
                .match(nameStartsWith(".").negate())
                .match(nameMatches(HS_ERR_PATTERN).negate())
                .match(nameEndsWith(COMPRESSED_EXTENSION).negate())
                .match(nameEndsWith(ENCRYPTED_EXTENSION).negate())
                .match(nameStartsWith("metadata").negate())
                .list().size();

        metrics.declareGauge(Metrics.APPLICATION_NODE, "coredumps.enqueued", dimensions, Metrics.DimensionType.PRETAGGED).sample(numberOfUnprocessedCoredumps);

        // Processed coredumps
        Path processedCoredumpsPath = doneCoredumpsPath.resolve(context.containerName().asString());
        int numberOfProcessedCoredumps = FileFinder.directories(processedCoredumpsPath)
                .maxDepth(1)
                .list().size();

        metrics.declareGauge(Metrics.APPLICATION_NODE, "coredumps.processed", dimensions, Metrics.DimensionType.PRETAGGED).sample(numberOfProcessedCoredumps);
    }

    private Dimensions generateDimensions(NodeAgentContext context) {
        NodeSpec node = context.node();
        Dimensions.Builder dimensionsBuilder = new Dimensions.Builder()
                .add("host", node.hostname())
                .add("flavor", node.flavor())
                .add("state", node.state().toString())
                .add("zone", context.zone().getId().value());

        node.owner().ifPresent(owner ->
            dimensionsBuilder
                    .add("tenantName", owner.tenant().value())
                    .add("applicationName", owner.application().value())
                    .add("instanceName", owner.instance().value())
                    .add("app", String.join(".", owner.application().value(), owner.instance().value()))
                    .add("applicationId", owner.toFullString())
        );

        node.membership().ifPresent(membership ->
            dimensionsBuilder
                    .add("clustertype", membership.type().value())
                    .add("clusterid", membership.clusterId())
        );

        node.parentHostname().ifPresent(parent -> dimensionsBuilder.add("parentHostname", parent));
        dimensionsBuilder.add("system", context.zone().getSystemName().value());

        return dimensionsBuilder.build();
    }

    private boolean isReadyForProcessing(FileFinder.FileAttributes fileAttributes) {
        // Wait at least a minute until we start processing a core/heap dump to ensure that
        // kernel/JVM has finished writing it
        return timer.currentTime().minusSeconds(60).isAfter(fileAttributes.lastModifiedTime());
    }

    void processAndReportSingleCoreDump(NodeAgentContext context, ContainerPath coreDumpDirectory,
                                        Optional<DockerImage> dockerImage) {
        CoreDumpMetadata metadata = gatherMetadata(context, coreDumpDirectory);
        dockerImage.ifPresent(metadata::setDockerImage);
        dockerImage.flatMap(DockerImage::tag).ifPresent(metadata::setVespaVersion);
        dockerImage.ifPresent(metadata::setDockerImage);
        SecretSharedKey sharedCoreKey = Optional.of(corePublicKeyFlagValue(context))
                .filter(k -> !k.isEmpty())
                .map(KeyId::ofString)
                .flatMap(secretSharedKeySupplier::create)
                .orElseThrow(() -> ConvergenceException.ofError("No core dump encryption key provided"));
        metadata.setDecryptionToken(sharedCoreKey.sealedSharedKey().toTokenString());

        String coreDumpId = coreDumpDirectory.getFileName().toString();
        cores.report(context.hostname(), coreDumpId, metadata);
        context.log(logger, "Core dump reported: " + coreDumpId);
        finishProcessing(context, coreDumpDirectory, sharedCoreKey);
    }

    CoreDumpMetadata gatherMetadata(NodeAgentContext context, ContainerPath coreDumpDirectory) {
        ContainerPath metadataPath = coreDumpDirectory.resolve(METADATA2_FILE_NAME);
        Optional<ReportCoreDumpRequest> request = ReportCoreDumpRequest.load(metadataPath);
        if (request.isPresent()) {
            var metadata = new CoreDumpMetadata();
            request.get().populateMetadata(metadata, doneCoredumpsPath.getFileSystem());
            return metadata;
        }

        ContainerPath coreDumpFile = findCoredumpFileInProcessingDirectory(coreDumpDirectory);
        CoreDumpMetadata metadata = coreCollector.collect(context, coreDumpFile);
        metadata.setCpuMicrocodeVersion(getMicrocodeVersion())
                .setKernelVersion(System.getProperty("os.version"))
                .setCoreDumpPath(doneCoredumpsPath.resolve(context.containerName().asString())
                                                  .resolve(coreDumpDirectory.getFileName().toString())
                                                  .resolve(coreDumpFile.getFileName().toString()));

        ReportCoreDumpRequest requestInstance = new ReportCoreDumpRequest();
        requestInstance.fillFrom(metadata);
        requestInstance.save(metadataPath);
        context.log(logger, "Wrote " + metadataPath.pathOnHost());
        return metadata;
    }

    private String getMicrocodeVersion() {
        String output = uncheck(() -> Files.readAllLines(doneCoredumpsPath.getFileSystem().getPath("/proc/cpuinfo")).stream()
                                           .filter(line -> line.startsWith("microcode"))
                                           .findFirst()
                                           .orElse("microcode : UNKNOWN"));

        String[] results = output.split(":");
        if (results.length != 2) {
            throw ConvergenceException.ofError("Result from detect microcode command not as expected: " + output);
        }

        return results[1].trim();
    }

}
