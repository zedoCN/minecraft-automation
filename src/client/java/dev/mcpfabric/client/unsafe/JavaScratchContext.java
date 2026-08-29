package dev.mcpfabric.client.unsafe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.client.ClientMc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Stable, mapping-independent handles and reflection helpers available to Java scratch code. */
public final class JavaScratchContext {
	public Object minecraft() {
		return ClientMc.mc();
	}

	public Object player() {
		return ClientMc.mc().player;
	}

	public Object level() {
		return ClientMc.mc().level;
	}

	public Object server() {
		return ServerHolder.get();
	}

	public ClassLoader classLoader() {
		return JavaScratchContext.class.getClassLoader();
	}

	/** Invoke any registered MCPFabric RPC from scratch code and return its full JSON envelope. */
	public JsonObject rpc(String method, String paramsJson) {
		JsonObject params = paramsJson == null || paramsJson.isBlank()
				? new JsonObject()
				: JsonParser.parseString(paramsJson).getAsJsonObject();
		return McpFabric.router().dispatch(method, params);
	}

	public Object getField(Object target, String fieldName) throws ReflectiveOperationException {
		Field field = findField(typeOf(target), fieldName);
		field.setAccessible(true);
		return field.get(target instanceof Class<?> ? null : target);
	}

	public void setField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
		Field field = findField(typeOf(target), fieldName);
		field.setAccessible(true);
		field.set(target instanceof Class<?> ? null : target, value);
	}

	public Object invoke(Object target, String methodName, Object... args) throws ReflectiveOperationException {
		Class<?> type = typeOf(target);
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			for (Method method : current.getDeclaredMethods()) {
				if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) continue;
				if (!compatible(method.getParameterTypes(), args)) continue;
				method.setAccessible(true);
				return method.invoke(target instanceof Class<?> ? null : target, args);
			}
		}
		throw new NoSuchMethodException(type.getName() + "." + methodName + " with " + args.length + " compatible arguments");
	}

	/** Describe runtime names/signatures, useful when Minecraft mappings differ from source names. */
	public String describeClass(Object target) {
		Class<?> type = typeOf(target);
		List<String> lines = new ArrayList<>();
		lines.add("class " + type.getName());
		for (Field field : type.getDeclaredFields()) {
			lines.add("field " + Modifier.toString(field.getModifiers()) + " " + field.getType().getTypeName() + " " + field.getName());
		}
		for (Method method : type.getDeclaredMethods()) {
			lines.add("method " + Modifier.toString(method.getModifiers()) + " " + method.getReturnType().getTypeName() + " "
					+ method.getName() + Arrays.toString(method.getParameterTypes()));
		}
		lines.sort(Comparator.naturalOrder());
		return String.join("\n", lines);
	}

	public void log(String message) {
		McpFabric.LOGGER.info("[mcpfabric-java] {}", message);
	}

	private static Class<?> typeOf(Object target) {
		if (target == null) throw new IllegalArgumentException("Reflection target is null.");
		return target instanceof Class<?> clazz ? clazz : target.getClass();
	}

	private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			try {
				return current.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
			}
		}
		throw new NoSuchFieldException(type.getName() + "." + name);
	}

	private static boolean compatible(Class<?>[] types, Object[] args) {
		for (int i = 0; i < types.length; i++) {
			if (args[i] == null) {
				if (types[i].isPrimitive()) return false;
				continue;
			}
			Class<?> expected = wrap(types[i]);
			if (!expected.isAssignableFrom(args[i].getClass())) return false;
		}
		return true;
	}

	private static Class<?> wrap(Class<?> type) {
		if (!type.isPrimitive()) return type;
		if (type == boolean.class) return Boolean.class;
		if (type == byte.class) return Byte.class;
		if (type == short.class) return Short.class;
		if (type == int.class) return Integer.class;
		if (type == long.class) return Long.class;
		if (type == float.class) return Float.class;
		if (type == double.class) return Double.class;
		if (type == char.class) return Character.class;
		return Void.class;
	}
}
