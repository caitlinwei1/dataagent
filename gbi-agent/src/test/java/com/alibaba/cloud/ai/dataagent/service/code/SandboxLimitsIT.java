package com.alibaba.cloud.ai.dataagent.service.code;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.alibaba.cloud.ai.dataagent.enums.CodePoolExecutorEnum;
import com.alibaba.cloud.ai.dataagent.properties.CodeExecutorProperties;
import com.alibaba.cloud.ai.dataagent.service.code.impls.DockerCodePoolExecutorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Runs hostile / heavy Python through the real Docker sandbox pool (image gbi-python-sandbox:0.1) with the production
 * limits (1 CPU, 500 MiB, 60 s, network none) and writes every response to results/step6/sandbox/*.json.
 *
 * <p>
 * Needs a local Docker daemon; run with {@code mvn -Dtest=SandboxLimitsIT test}. Skipped without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SandboxLimitsIT {

	private static final ObjectMapper M = new ObjectMapper();

	private final Path out = Path.of("..", "results", "step6", "sandbox").toAbsolutePath().normalize();

	private CodePoolExecutorService pool;

	@BeforeAll
	void up() throws Exception {
		CodeExecutorProperties p = new CodeExecutorProperties();
		p.setCodePoolExecutor(CodePoolExecutorEnum.DOCKER);
		p.setImageName(System.getProperty("sandbox.image", "gbi-python-sandbox:0.1"));
		p.setCoreContainerNum(2);
		p.setTempContainerNum(2);
		try {
			pool = new DockerCodePoolExecutorService(p);
		}
		catch (Throwable e) {
			assumeTrue(false, "docker not available: " + e.getMessage());
		}
		Files.createDirectories(out);
		M.writerWithDefaultPrettyPrinter()
			.writeValue(out.resolve("_limits.json").toFile(),
					M.createObjectNode()
						.put("cpu", p.getCpuCore())
						.put("memory_mib", p.getLimitMemory())
						.put("code_timeout", p.getCodeTimeout())
						.put("network", p.getNetworkMode())
						.put("pids", p.getPidsLimit())
						.put("python_max_tries", p.getPythonMaxTriesCount()));
	}

	@AfterAll
	void down() {
		if (pool instanceof AutoCloseable c) {
			try {
				c.close();
			}
			catch (Exception ignored) {
			}
		}
	}

	private CodePoolExecutorService.TaskResponse run(String name, String code) throws Exception {
		long t0 = System.nanoTime();
		CodePoolExecutorService.TaskResponse r = pool.runTask(new CodePoolExecutorService.TaskRequest(code, "", null));
		double secs = (System.nanoTime() - t0) / 1e9;
		ObjectNode n = M.createObjectNode();
		n.put("case", name);
		n.put("seconds", Math.round(secs * 10) / 10.0);
		n.put("success", r.isSuccess());
		n.put("stdout", r.stdOut());
		n.put("stderr", r.stdErr());
		n.put("exception", r.exceptionMsg());
		n.put("code", code);
		M.writerWithDefaultPrettyPrinter().writeValue(out.resolve(name + ".json").toFile(), n);
		return r;
	}

	private static String all(CodePoolExecutorService.TaskResponse r) {
		return r.stdOut() + "\n" + r.stdErr() + "\n" + r.exceptionMsg();
	}

	@Test
	void normalAnalysisWorks() throws Exception {
		var r = run("01_normal", """
				import pandas as pd, json
				df = pd.DataFrame({"region": ["华东", "华北", "华东"], "gmv": [10, 20, 30]})
				print(json.dumps(df.groupby("region")["gmv"].sum().to_dict(), ensure_ascii=False))
				""");
		assertTrue(r.isSuccess() && r.stdOut().contains("华东"), all(r));
	}

	@Test
	void cgroupLimitsAreSet() throws Exception {
		var r = run("02_cgroup", """
				import os
				def rd(*ps):
				    for p in ps:
				        if os.path.exists(p):
				            return open(p).read().strip()
				    return "n/a"
				quota = rd("/sys/fs/cgroup/cpu/cpu.cfs_quota_us"); period = rd("/sys/fs/cgroup/cpu/cpu.cfs_period_us")
				print("cpu.max", rd("/sys/fs/cgroup/cpu.max"), "| cfs", quota, "/", period)
				print("memory", rd("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory/memory.limit_in_bytes"))
				print("memory+swap", rd("/sys/fs/cgroup/memory.swap.max", "/sys/fs/cgroup/memory/memory.memsw.limit_in_bytes"))
				print("pids", rd("/sys/fs/cgroup/pids.max", "/sys/fs/cgroup/pids/pids.max"))
				""");
		assertTrue(r.isSuccess(), all(r));
		assertTrue(r.stdOut().contains("100000 / 100000") || r.stdOut().contains("100000 100000"), "1 CPU quota: " + r.stdOut());
		assertTrue(r.stdOut().contains(String.valueOf(500L * 1024 * 1024)), "500 MiB: " + r.stdOut());
	}

	@Test
	void twoBusyProcessesGetOneCpu() throws Exception {
		var r = run("03_cpu_burn", """
				import multiprocessing as mp, time, resource
				def burn(sec):
				    end = time.time() + sec
				    while time.time() < end:
				        pass
				t0 = time.time()
				ps = [mp.Process(target=burn, args=(4,)) for _ in range(2)]
				[p.start() for p in ps]; [p.join() for p in ps]
				wall = time.time() - t0
				cpu = resource.getrusage(resource.RUSAGE_CHILDREN).ru_utime
				print(f"wall={wall:.2f}s cpu={cpu:.2f}s cores_used={cpu / wall:.2f}")
				""");
		assertTrue(r.isSuccess(), all(r));
		double used = Double.parseDouble(r.stdOut().replaceAll("(?s).*cores_used=([0-9.]+).*", "$1"));
		assertTrue(used < 1.3, "two busy processes should share one core, got " + used);
	}

	@Test
	void memoryOverLimitIsKilled() throws Exception {
		var r = run("04_memory", """
				blocks = []
				for i in range(16):
				    blocks.append(bytearray(64 * 1024 * 1024))
				    print("allocated", (i + 1) * 64, "MiB", flush=True)
				print("SHOULD NOT REACH")
				""");
		assertFalse(r.isSuccess() || r.stdOut().contains("SHOULD NOT REACH"), all(r));
		assertTrue(r.stdErr().contains("OOMKilled"), "cause is reported: " + r.stdErr());
	}

	@Test
	void runawayCodeStopsAtTheTimeout() throws Exception {
		long t0 = System.nanoTime();
		var r = run("05_timeout", """
				import time
				print("start", flush=True)
				time.sleep(600)
				print("SHOULD NOT REACH")
				""");
		double secs = (System.nanoTime() - t0) / 1e9;
		assertFalse(r.isSuccess(), all(r));
		assertTrue(secs < 90, "killed near 60 s, took " + secs);
		assertTrue(r.stdErr().contains("timeout"), "cause is reported: " + r.stdErr());
	}

	@Test
	void noNetwork() throws Exception {
		var r = run("06_network", """
				import socket
				for target in [("dashscope.aliyuncs.com", 443), ("1.1.1.1", 53), ("172.17.0.1", 13306)]:
				    try:
				        s = socket.create_connection(target, timeout=3)
				        print("CONNECTED", target); s.close()
				    except Exception as e:
				        print("blocked", target, type(e).__name__)
				""");
		assertTrue(r.isSuccess() && !r.stdOut().contains("CONNECTED"), all(r));
	}

	@Test
	void forkBombStopsAtThePidsLimit() throws Exception {
		var r = run("07_fork_bomb", """
				import os, time
				n = 0
				try:
				    for _ in range(10000):
				        if os.fork() == 0:
				            time.sleep(30); os._exit(0)
				        n += 1
				except OSError as e:
				    print("fork refused after", n, "children:", e)
				""");
		assertTrue(r.stdOut().contains("fork refused") || !r.isSuccess(), all(r));
	}

	@Test
	void noPrivileges() throws Exception {
		var r = run("08_privileges", """
				import os, socket
				for name, fn in [("chown /etc/passwd", lambda: os.chown("/etc/passwd", 1, 1)),
				                 ("raw socket", lambda: socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_ICMP)),
				                 ("mount", lambda: os.system("mount -t tmpfs none /mnt 2>/dev/null") != 0 and (_ for _ in ()).throw(OSError("mount failed")))]:
				    try:
				        fn(); print("ALLOWED", name)
				    except Exception as e:
				        print("denied", name, type(e).__name__)
				""");
		assertTrue(r.isSuccess() && !r.stdOut().contains("ALLOWED"), all(r));
	}

	/** Pooled containers are reused: task N must not see what tasks 1..N-1 printed. */
	@Test
	void noOutputLeaksBetweenTasks() throws Exception {
		for (int i = 0; i < 6; i++) {
			run("09_leak_writer_" + i, "print('SECRET-of-task-" + i + "')");
			var r = run("09_leak_reader_" + i, "print('reader " + i + "')");
			assertTrue(r.isSuccess() && !r.stdOut().contains("SECRET"), "leaked: " + r.stdOut());
			assertTrue(r.stdOut().strip().equals("reader " + i), r.stdOut());
		}
	}

}
