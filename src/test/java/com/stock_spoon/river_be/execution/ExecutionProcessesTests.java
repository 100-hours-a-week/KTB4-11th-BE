package com.stock_spoon.river_be.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ExecutionProcessesTests {
    @TempDir Path directory;
    @Test
    void separateBeAndExecutionProcessesShareDbAndExecuteThroughHttp() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("shared").toAbsolutePath().toString().replace('\\','/')
                + ";MODE=MySQL;AUTO_SERVER=TRUE;DATABASE_TO_LOWER=TRUE";
        try (var execution = new Node("execution", url, "create", "http://localhost:1", directory);
             var be = new Node("api", url, "validate", "http://localhost:" + execution.port, directory)) {
            var ids = execution.command("seed").split(" ");
            be.command("initial " + String.join(" ", ids));
            assertThat(execution.command("state " + ids[1])).isEqualTo("930000 1 1");
            assertThat(be.command("market " + ids[0] + " " + ids[1])).isEqualTo("executed 1");
            assertThat(execution.command("state " + ids[1])).isEqualTo("860000 2 2");
        }
    }
    private static class Node implements AutoCloseable {
        final Process process;
        final java.io.PrintWriter input;
        final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        final int port;
        Node(String role, String db, String ddl, String url, Path directory) throws Exception {
            var args = directory.resolve(role + ".args");
            String classpath = System.getProperty("test.runtime.classpath");
            Files.writeString(args, "-cp\n\"" + classpath.replace("\\", "\\\\") + "\"\n"
                    + ExecutionProcessNode.class.getName() + "\n" + role + "\n\"" + db + "\"\n" + ddl + "\n" + url + "\n");
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + args)
                    .redirectErrorStream(true).start();
            input = new java.io.PrintWriter(process.getOutputStream(), true);
            var reader = new Thread(() -> {
                try (var lines = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                    for (String line; (line = lines.readLine()) != null;) if (line.startsWith("#")) replies.add(line);
                } catch (java.io.IOException error) { replies.add("#ERROR output closed"); }
            });
            reader.setDaemon(true); reader.start();
            String ready = replies.poll(55, TimeUnit.SECONDS);
            if (ready == null || !ready.startsWith("#READY ")) {
                process.destroyForcibly(); throw new AssertionError("node startup failed: " + role);
            }
            port = Integer.parseInt(ready.substring(7));
        }
        String command(String command) throws Exception {
            input.println(command);
            String reply = replies.poll(30, TimeUnit.SECONDS);
            assertThat(reply).startsWith("#RESULT ");
            return reply.substring(8);
        }
        public void close() throws Exception {
            input.println("stop");
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
            input.close();
        }
    }
}
