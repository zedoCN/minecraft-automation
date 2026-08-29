package dev.mcpfabric.client.unsafe;

/** Contract implemented by a runtime-compiled Java scratch body. */
public interface JavaScratch {
	Object run(JavaScratchContext context) throws Exception;
}
