@file:OptIn(UnsafeWasmMemoryApi::class, ExperimentalWasmInterop::class)

package io.github.yuroyami.imagekodec

import kotlin.wasm.ExperimentalWasmInterop
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

@WasmImport("wasi_snapshot_preview1", "environ_sizes_get")
private external fun environSizesGet(countAddress: Int, sizeAddress: Int): Int

@WasmImport("wasi_snapshot_preview1", "environ_get")
private external fun environGet(pointersAddress: Int, bufferAddress: Int): Int

// WASI gives the environment as one buffer of NAME=value strings, each ended by a zero byte.
internal actual fun environmentVariable(name: String): String? = withScopedMemoryAllocator { allocator ->
    val sizes = allocator.allocate(8)
    if (environSizesGet(sizes.address.toInt(), (sizes + 4).address.toInt()) != 0) return@withScopedMemoryAllocator null
    val count = sizes.loadInt()
    val size = (sizes + 4).loadInt()
    if (count <= 0 || size <= 0) return@withScopedMemoryAllocator null
    val pointers = allocator.allocate(count * 4)
    val buffer = allocator.allocate(size)
    if (environGet(pointers.address.toInt(), buffer.address.toInt()) != 0) return@withScopedMemoryAllocator null
    val text = ByteArray(size) { (buffer + it).loadByte() }.decodeToString()
    text.split('\u0000').firstOrNull { it.startsWith("$name=") }?.substring(name.length + 1)
}
