package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.client.unsafe.JavaScratch;
import dev.mcpfabric.client.unsafe.JavaScratchContext;
import net.fabricmc.loader.api.FabricLoader;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Explicitly unsafe, authenticated, in-process Java compilation and execution. */
public final class UnsafeJavaHandlers {
	private UnsafeJavaHandlers() {}

	public static void register(RpcRouter router) {
		router.register("unsafe.javaScratch", ctx -> {
			requireUnsafeJava();
			String body = ctx.getString("body");
			List<String> imports = ctx.getStringList("imports");
			boolean mainThread = ctx.optBool("mainThread", true);
			String codeHash = sha256(body + "\n" + String.join("\n", imports));
			long started = System.nanoTime();
			boolean success = false;
			String resultClass = null;
			try {
				Object value;
				try (CompiledScratch compiled = compile(body, imports, codeHash)) {
					JavaScratchContext context = new JavaScratchContext();
					value = mainThread
							? ClientMc.call(() -> runScratch(compiled.scratch(), context, codeHash))
							: runScratch(compiled.scratch(), context, codeHash);
				}
				success = true;
				resultClass = value == null ? "null" : value.getClass().getName();
				JsonObject out = new JsonObject();
				out.addProperty("unsafe", true);
				out.addProperty("codeHash", codeHash);
				out.addProperty("mainThread", mainThread);
				out.addProperty("durationMs", (System.nanoTime() - started) / 1_000_000L);
				out.addProperty("resultClass", resultClass);
				out.add("result", resultJson(value));
				return out;
			} catch (RpcException e) {
				throw e;
			} catch (Throwable t) {
				JsonObject data = new JsonObject();
				data.addProperty("unsafe", true);
				data.addProperty("codeHash", codeHash);
				data.addProperty("exception", t.getClass().getName());
				throw new RpcException("java_execution_failed", t.getMessage() == null ? t.toString() : t.getMessage(), data);
			} finally {
				audit(codeHash, mainThread, success, resultClass, (System.nanoTime() - started) / 1_000_000L);
			}
		});
	}

	private static CompiledScratch compile(String body, List<String> imports, String codeHash) throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw RpcException.unavailable("No Java compiler is available. Run Minecraft with a full JDK, not a stripped JRE.");
		}
		for (String imported : imports) {
			if (!imported.matches("[A-Za-z_$][A-Za-z0-9_$.]*(?:\\.\\*)?")) {
				throw RpcException.badRequest("Invalid Java import: " + imported);
			}
		}
		String simpleName = "Scratch_" + UUID.randomUUID().toString().replace("-", "");
		String qualifiedName = "dev.mcpfabric.runtime." + simpleName;
		StringBuilder source = new StringBuilder("package dev.mcpfabric.runtime;\n");
		for (String imported : imports) source.append("import ").append(imported).append(";\n");
		source.append("public final class ").append(simpleName)
				.append(" implements dev.mcpfabric.client.unsafe.JavaScratch {\n")
				.append("  public Object run(dev.mcpfabric.client.unsafe.JavaScratchContext ctx) throws Exception {\n")
				.append(body).append("\n  }\n}\n");

		Path output = Files.createTempDirectory("mcpfabric-java-");
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
			JavaFileObject sourceFile = new StringSource(qualifiedName, source.toString());
			List<String> options = List.of(
					"-proc:none",
					"--release", Integer.toString(Runtime.version().feature()),
					"-classpath", compilerClasspath());
			boolean compiled = Boolean.TRUE.equals(compiler.getTask(null, files, diagnostics, options, null, List.of(sourceFile)).call());
			if (!compiled) {
				JsonObject data = new JsonObject();
				data.addProperty("unsafe", true);
				data.addProperty("codeHash", codeHash);
				JsonArray items = new JsonArray();
				for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
					JsonObject item = new JsonObject();
					item.addProperty("kind", diagnostic.getKind().name());
					item.addProperty("line", diagnostic.getLineNumber());
					item.addProperty("column", diagnostic.getColumnNumber());
					item.addProperty("message", diagnostic.getMessage(null));
					items.add(item);
				}
				data.add("diagnostics", items);
				deleteTree(output);
				throw new RpcException("java_compile_failed", "Java scratch did not compile.", data);
			}
		}

		URLClassLoader loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, JavaScratch.class.getClassLoader());
		try {
			Class<?> type = Class.forName(qualifiedName, true, loader);
			return new CompiledScratch((JavaScratch) type.getDeclaredConstructor().newInstance(), loader, output);
		} catch (Throwable t) {
			loader.close();
			deleteTree(output);
			throw t;
		}
	}

	private static Object runScratch(JavaScratch scratch, JavaScratchContext context, String codeHash) throws RpcException {
		try {
			return scratch.run(context);
		} catch (Throwable t) {
			JsonObject data = new JsonObject();
			data.addProperty("unsafe", true);
			data.addProperty("codeHash", codeHash);
			data.addProperty("exception", t.getClass().getName());
			throw new RpcException("java_execution_failed", t.getMessage() == null ? t.toString() : t.getMessage(), data);
		}
	}

	private static String compilerClasspath() {
		List<String> entries = new ArrayList<>();
		String system = System.getProperty("java.class.path", "");
		if (!system.isBlank()) entries.add(system);
		FabricLoader.getInstance().getAllMods().forEach(mod -> mod.getOrigin().getPaths().forEach(path -> entries.add(path.toString())));
		return String.join(java.io.File.pathSeparator, entries);
	}

	private static JsonElement resultJson(Object value) {
		if (value == null) return JsonNull.INSTANCE;
		if (value instanceof JsonElement json) return json;
		if (value instanceof Boolean bool) return new JsonPrimitive(bool);
		if (value instanceof Number number) return new JsonPrimitive(number);
		if (value instanceof Character character) return new JsonPrimitive(character);
		if (value instanceof String string) return new JsonPrimitive(string);
		JsonObject out = new JsonObject();
		out.addProperty("class", value.getClass().getName());
		out.addProperty("string", String.valueOf(value));
		return out;
	}

	private static void requireUnsafeJava() throws RpcException {
		if (!McpFabric.config().enableUnsafeJava) {
			throw RpcException.unavailable("Unsafe Java scratch is disabled in mcpfabric.config.json.");
		}
		if (!McpFabric.config().requireAuth) {
			throw RpcException.unavailable("Unsafe Java scratch refuses to run while bridge authentication is disabled.");
		}
	}

	private static void audit(String codeHash, boolean mainThread, boolean success, String resultClass, long durationMs) {
		try {
			JsonObject item = new JsonObject();
			item.addProperty("timestamp", Instant.now().toString());
			item.addProperty("codeHash", codeHash);
			item.addProperty("mainThread", mainThread);
			item.addProperty("success", success);
			item.addProperty("resultClass", resultClass);
			item.addProperty("durationMs", durationMs);
			Path log = McpFabric.config().source.getParent().resolve("mcpfabric-java-audit.jsonl");
			Files.writeString(log, item + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception e) {
			McpFabric.LOGGER.warn("[mcpfabric-java] failed to append audit record", e);
		}
	}

	private static String sha256(String source) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static void deleteTree(Path root) {
		try (var paths = Files.walk(root)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}

	private static final class StringSource extends SimpleJavaFileObject {
		private final String source;

		StringSource(String qualifiedName, String source) {
			super(URI.create("string:///" + qualifiedName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
			this.source = source;
		}

		@Override
		public CharSequence getCharContent(boolean ignoreEncodingErrors) {
			return source;
		}
	}

	private record CompiledScratch(JavaScratch scratch, URLClassLoader loader, Path output) implements AutoCloseable {
		@Override
		public void close() throws IOException {
			loader.close();
			deleteTree(output);
		}
	}
}
