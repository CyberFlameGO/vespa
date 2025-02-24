// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.maintenance.coredump;

import com.yahoo.vespa.hosted.node.admin.configserver.cores.CoreDumpMetadata;
import com.yahoo.vespa.hosted.node.admin.container.ContainerOperations;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentContext;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentContextImpl;
import com.yahoo.vespa.hosted.node.admin.task.util.file.UnixPath;
import com.yahoo.vespa.hosted.node.admin.task.util.fs.ContainerPath;
import com.yahoo.vespa.hosted.node.admin.task.util.process.CommandResult;
import com.yahoo.vespa.test.file.TestFileSystem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.yahoo.vespa.hosted.node.admin.maintenance.coredump.CoreCollector.GDB_PATH_RHEL8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author freva
 */
public class CoreCollectorTest {
    private static final Instant CORE_CREATED = Instant.ofEpochMilli(2233445566L);
    
    private final ContainerOperations docker = mock(ContainerOperations.class);
    private final CoreCollector coreCollector = new CoreCollector(docker);
    private final NodeAgentContext context = NodeAgentContextImpl.builder("container-123.domain.tld")
            .fileSystem(TestFileSystem.create()).build();

    private final ContainerPath TEST_CORE_PATH = (ContainerPath) new UnixPath(context.paths().of("/tmp/core.1234"))
            .createParents()
            .createNewFile()
            .setLastModifiedTime(CORE_CREATED)
            .toPath();
    private final String TEST_BIN_PATH = "/usr/bin/program";
    private final List<String> GDB_BACKTRACE = List.of("[New Thread 2703]",
            "Core was generated by `/usr/bin/program\'.", "Program terminated with signal 11, Segmentation fault.",
            "#0  0x00000000004004d8 in main (argv=...) at main.c:4", "4\t    printf(argv[3]);",
            "#0  0x00000000004004d8 in main (argv=...) at main.c:4");

    @Test
    void extractsBinaryPathTest() {
        final String[] cmd = {"file", TEST_CORE_PATH.pathInContainer()};

        mockExec(cmd,
                "/tmp/core.1234: ELF 64-bit LSB core file x86-64, version 1 (SYSV), SVR4-style, from " +
                        "'/usr/bin/program'");
        assertEquals(TEST_BIN_PATH, coreCollector.readBinPath(context, TEST_CORE_PATH));

        mockExec(cmd,
                "/tmp/core.1234: ELF 64-bit LSB core file x86-64, version 1 (SYSV), SVR4-style, from " +
                        "'/usr/bin/program --foo --bar baz'");
        assertEquals(TEST_BIN_PATH, coreCollector.readBinPath(context, TEST_CORE_PATH));

        mockExec(cmd,
                "/tmp/core.1234: ELF 64-bit LSB core file x86-64, version 1 (SYSV), SVR4-style, " +
                        "from 'program', real uid: 0, effective uid: 0, real gid: 0, effective gid: 0, " +
                        "execfn: '/usr/bin/program', platform: 'x86_64");
        assertEquals(TEST_BIN_PATH, coreCollector.readBinPath(context, TEST_CORE_PATH));

        String fallbackResponse = "/response/from/fallback";
        mockExec(new String[]{GDB_PATH_RHEL8, "-n", "-batch", "-core", "/tmp/core.1234"},
                        """
                        GNU gdb (Ubuntu 7.7.1-0ubuntu5~14.04.2) 7.7.1
                        Type “apropos word” to search for commands related to “word”…
                        Reading symbols from abc…(no debugging symbols found)…done.
                        [New LWP 23678]
                        Core was generated by `/response/from/fallback'. \s
                        Program terminated with signal SIGSEGV, Segmentation fault. \s
                        #0  0x0000000000400541 in main ()
                        #0  0x0000000000400541 in main ()
                        (gdb) bt
                        #0  0x0000000000400541 in main ()
                        (gdb)
                        """);
        mockExec(cmd,
                "/tmp/core.1234: ELF 64-bit LSB core file x86-64, version 1 (SYSV), SVR4-style");
        assertEquals(fallbackResponse, coreCollector.readBinPath(context, TEST_CORE_PATH));

        mockExec(cmd, "", "Error code 1234");
        assertEquals(fallbackResponse, coreCollector.readBinPath(context, TEST_CORE_PATH));
    }

    @Test
    void extractsBinaryPathUsingGdbTest() {
        String[] cmd = new String[]{GDB_PATH_RHEL8, "-n", "-batch", "-core", "/tmp/core.1234"};

        mockExec(cmd, "Core was generated by `/usr/bin/program-from-gdb --identity foo/search/cluster.content_'.");
        assertEquals("/usr/bin/program-from-gdb", coreCollector.readBinPathFallback(context, TEST_CORE_PATH));

        mockExec(cmd, "", "Error 123");
        try {
            coreCollector.readBinPathFallback(context, TEST_CORE_PATH);
            fail("Expected not to be able to get bin path");
        } catch (RuntimeException e) {
            assertEquals("Failed to extract binary path from GDB, result: exit status 1, output 'Error 123', command: " +
                    "[/opt/rh/gcc-toolset-12/root/bin/gdb, -n, -batch, -core, /tmp/core.1234]", e.getMessage());
        }
    }

    @Test
    void extractsBacktraceUsingGdb() {
        mockExec(new String[]{GDB_PATH_RHEL8, "-n", "-ex", "set print frame-arguments none",
                        "-ex", "bt", "-batch", "/usr/bin/program", "/tmp/core.1234"},
                String.join("\n", GDB_BACKTRACE));
        assertEquals(GDB_BACKTRACE, coreCollector.readBacktrace(context, TEST_CORE_PATH, TEST_BIN_PATH, false));

        mockExec(new String[]{GDB_PATH_RHEL8, "-n", "-ex", "set print frame-arguments none",
                        "-ex", "bt", "-batch", "/usr/bin/program", "/tmp/core.1234"},
                "", "Failure");
        try {
            coreCollector.readBacktrace(context, TEST_CORE_PATH, TEST_BIN_PATH, false);
            fail("Expected not to be able to read backtrace");
        } catch (RuntimeException e) {
            assertEquals("Failed to read backtrace exit status 1, output 'Failure', Command: " +
                    "[" + GDB_PATH_RHEL8 + ", -n, -ex, set print frame-arguments none, -ex, bt, -batch, " +
                    "/usr/bin/program, /tmp/core.1234]", e.getMessage());
        }
    }

    @Test
    void extractsBacktraceFromAllThreadsUsingGdb() {
        mockExec(new String[]{GDB_PATH_RHEL8, "-n",
                        "-ex", "set print frame-arguments none",
                        "-ex", "thread apply all bt", "-batch",
                        "/usr/bin/program", "/tmp/core.1234"},
                String.join("\n", GDB_BACKTRACE));
        assertEquals(GDB_BACKTRACE, coreCollector.readBacktrace(context, TEST_CORE_PATH, TEST_BIN_PATH, true));
    }

    @Test
    void collectsDataTest() {
        mockExec(new String[]{"file", TEST_CORE_PATH.pathInContainer()},
                "/tmp/core.1234: ELF 64-bit LSB core file x86-64, version 1 (SYSV), SVR4-style, from " +
                        "'/usr/bin/program'");
        mockExec(new String[]{GDB_PATH_RHEL8, "-n", "-ex", "set print frame-arguments none",
                        "-ex", "bt", "-batch", "/usr/bin/program", "/tmp/core.1234"},
                String.join("\n", GDB_BACKTRACE));
        mockExec(new String[]{GDB_PATH_RHEL8, "-n", "-ex", "set print frame-arguments none",
                        "-ex", "thread apply all bt", "-batch",
                        "/usr/bin/program", "/tmp/core.1234"},
                String.join("\n", GDB_BACKTRACE));

        var expected = new CoreDumpMetadata().setBinPath(TEST_BIN_PATH)
                                             .setCreated(CORE_CREATED)
                                             .setType(CoreDumpMetadata.Type.CORE_DUMP)
                                             .setBacktrace(GDB_BACKTRACE)
                                             .setBacktraceAllThreads(GDB_BACKTRACE);
        assertEquals(expected, coreCollector.collect(context, TEST_CORE_PATH));
    }

    @Test
    void collectsDataRelativePath() {
        mockExec(new String[]{"file", TEST_CORE_PATH.pathInContainer()},
                "/tmp/core.1234: ELF 64-bit LSB core file x86-64, version 1 (SYSV), SVR4-style, from 'sbin/distributord-bin'");
        String absolutePath = "/opt/vespa/sbin/distributord-bin";
        mockExec(new String[]{GDB_PATH_RHEL8, "-n", "-ex", "set print frame-arguments none",
                        "-ex", "bt", "-batch", absolutePath, "/tmp/core.1234"},
                String.join("\n", GDB_BACKTRACE));
        mockExec(new String[]{GDB_PATH_RHEL8, "-n", "-ex", "set print frame-arguments none",
                        "-ex", "thread apply all bt", "-batch", absolutePath, "/tmp/core.1234"},
                String.join("\n", GDB_BACKTRACE));

        var expected = new CoreDumpMetadata()
                .setBinPath(absolutePath)
                .setCreated(CORE_CREATED)
                .setType(CoreDumpMetadata.Type.CORE_DUMP)
                .setBacktrace(GDB_BACKTRACE)
                .setBacktraceAllThreads(GDB_BACKTRACE);
        assertEquals(expected, coreCollector.collect(context, TEST_CORE_PATH));
    }

    @Test
    void collectsPartialIfBacktraceFailsTest() {
        mockExec(new String[]{"file", TEST_CORE_PATH.pathInContainer()},
                "/tmp/core.1234: ELF 64-bit LSB core file x86-64, version 1 (SYSV), SVR4-style, from " +
                        "'/usr/bin/program'");
        mockExec(new String[]{GDB_PATH_RHEL8 + " -n -ex set print frame-arguments none -ex bt -batch /usr/bin/program /tmp/core.1234"},
                "", "Failure");

        var expected = new CoreDumpMetadata().setBinPath(TEST_BIN_PATH).setCreated(CORE_CREATED).setType(CoreDumpMetadata.Type.CORE_DUMP);
        assertEquals(expected, coreCollector.collect(context, TEST_CORE_PATH));
    }

    @Test
    void reportsJstackInsteadOfGdbForJdkCores() {
        mockExec(new String[]{"file", TEST_CORE_PATH.pathInContainer()},
                "dump.core.5954: ELF 64-bit LSB core file x86-64, version 1 (SYSV), too many program header sections (33172)");

        String jdkPath = "/path/to/jdk/java";
        mockExec(new String[]{GDB_PATH_RHEL8, "-n", "-batch", "-core", "/tmp/core.1234"},
                 "Core was generated by `" + jdkPath + " -Dconfig.id=default/container.11 -XX:+Pre'.");

        String jstack = "jstack11";
        mockExec(new String[]{"jhsdb", "jstack", "--exe", jdkPath, "--core", "/tmp/core.1234"},
                jstack);

        var expected = new CoreDumpMetadata().setBinPath(jdkPath)
                                             .setCreated(CORE_CREATED)
                                             .setType(CoreDumpMetadata.Type.CORE_DUMP)
                                             .setBacktraceAllThreads(List.of(jstack));
        assertEquals(expected, coreCollector.collect(context, TEST_CORE_PATH));
    }

    @Test
    void metadata_for_java_heap_dump() {
        var expected = new CoreDumpMetadata().setBinPath("java")
                                             .setType(CoreDumpMetadata.Type.JVM_HEAP)
                                             .setCreated(CORE_CREATED)
                                             .setBacktrace(List.of("Heap dump, no backtrace available"));

        assertEquals(expected, coreCollector.collect(context, (ContainerPath) new UnixPath(context.paths().of("/dump_java_pid123.hprof"))
                .createNewFile()
                .setLastModifiedTime(CORE_CREATED)
                .toPath()));
    }

    private void mockExec(String[] cmd, String output) {
        mockExec(cmd, output, "");
    }

    private void mockExec(String[] cmd, String output, String error) {
        mockExec(context, cmd, output, error);
    }

    private void mockExec(NodeAgentContext context, String[] cmd, String output, String error) {
        when(docker.executeCommandInContainer(context, context.users().root(), cmd))
                .thenReturn(new CommandResult(null, error.isEmpty() ? 0 : 1, error.isEmpty() ? output : error));
    }
}
