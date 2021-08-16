package org.jetbrains.kotlinx.multik.cuda

import jcuda.CudaException
import jcuda.Pointer
import jcuda.runtime.JCuda
import jcuda.runtime.cudaError.cudaErrorMemoryAllocation
import jcuda.runtime.cudaMemcpyKind
import mu.KotlinLogging
import org.jetbrains.kotlinx.multik.ndarray.data.*
import java.lang.ref.Cleaner
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.LinkedBlockingQueue


private val logger = KotlinLogging.logger {}

internal class GpuArray constructor(
    val deviceDataPtr: Pointer,
    val byteSize: Long,
    val transposed: Boolean
) {
    var deferredLoadFuncRef: WeakReference<(() -> Unit)> = WeakReference(null)

    var isLoaded = true
        private set

    fun transferFromGpu(hostDataPtr: Pointer) {
        if (!isLoaded)
            throw IllegalStateException("Trying to copy GPU memory that is already freed")

        checkResult(JCuda.cudaMemcpy(hostDataPtr, deviceDataPtr, byteSize, cudaMemcpyKind.cudaMemcpyDeviceToHost))
    }

    fun free(copyToHost: Boolean = true) {
        if (!isLoaded)
            throw IllegalStateException("Trying to free memory that is already freed")

        if (copyToHost)
            deferredLoadFuncRef.get()?.invoke()

        logger.debug { "Freeing GPU memory. Size: ${byteSizeToString(byteSize)}" }
        checkResult(JCuda.cudaFree(deviceDataPtr))

        isLoaded = false
    }
}

internal class GpuCache {
    fun assertAllLoaded(vararg arrays: GpuArray) {
        if (!arrays.all { it.isLoaded })
            throw OutOfMemoryError("Not all arrays are loaded in the GPU memory")
    }

    fun fullCleanup() {
        cache.forEach { it.value.free() }
        cache.clear()

        deleteQueue.clear()
    }

    fun <T : Number, D : Dimension> getOrAlloc(array: MultiArray<T, D>): GpuArray {
        cleanup()

        val hash = System.identityHashCode(array)

        return cache.getOrPut(hash) {
            val (consistentArray, transposed) = getConsistentOrTransposedConsistent(array)
            val gpuArray = allocMemory(consistentArray.size, consistentArray.dtype, consistentArray.data, transposed)
            cleaner.register(array) { deleteQueue.add(hash) }
            gpuArray
        }
    }

    fun <T : Number, D : Dimension> alloc(size: Int, dtype: DataType, shape: IntArray, dim: D): Pair<NDArray<T, D>, GpuArray> {
        cleanup()

        val gpuArray = allocMemory<T>(size, dtype, null)
        val memoryView = CudaMemoryView<T>(size, dtype, gpuArray)
        gpuArray.deferredLoadFuncRef = WeakReference(memoryView::loadToHost)

        val array = NDArray(memoryView, shape = shape, dtype = dtype, dim = dim)
        val hash = System.identityHashCode(array)

        cleaner.register(array) { deleteQueue.add(hash) }
        cache[hash] = gpuArray

        return array to gpuArray
    }

    companion object {
        private const val CACHE_INITIAL_CAPACITY = 16
        private const val CACHE_LOAD_FACTOR = 0.75f


        // Note: NDArray.transpose() only creates a lightweight view
        private fun <T : Number, D : Dimension> isTransposedConsistent(x: MultiArray<T, D>): Boolean =
            x.transpose().consistent


        /**
         * Helper function used to get consistent data from [MultiArray]
         *
         * First value in returned pair - [MultiArray] that is consistent or
         * consistent when transposed
         *
         * Second value in returned pair - transposition flag - indicates whether
         * returned [MultiArray] should be transposed in order to be consistent
         *
         * @return pair of [MultiArray] and transposition flag
         */
        private fun <T : Number, D : Dimension> getConsistentOrTransposedConsistent(x: MultiArray<T, D>): Pair<MultiArray<T, D>, Boolean> =
            when {
                x.consistent -> x to false
                x.dim.d == 2 && isTransposedConsistent(x) -> x to true
                else -> x.deepCopy() to false
            }
    }

    private val cache = LinkedHashMap<Int, GpuArray>(CACHE_INITIAL_CAPACITY, CACHE_LOAD_FACTOR, true)

    private val deleteQueue = LinkedBlockingQueue<Int>()
    private val cleaner = Cleaner.create()

    private fun getGpuMemInfo(): String {
        val free = LongArray(1)
        val total = LongArray(1)

        checkResult(JCuda.cudaMemGetInfo(free, total))
        return "free: ${byteSizeToString(free[0])}, total: ${byteSizeToString(total[0])} MB"
    }

    private fun <T : Number> allocMemory(
        size: Int,
        dtype: DataType,
        setFrom: ImmutableMemoryView<T>?,
        transposed: Boolean = false
    ): GpuArray {
        val elemSize = dtype.itemSize

        val deviceDataPtr = Pointer()
        val byteSize = elemSize.toLong() * size

        logger.debug { "Allocating array on GPU. Size: ${byteSizeToString(byteSize)}" }

        while (true) {
            val result = JCuda.cudaMalloc(deviceDataPtr, byteSize)

            if (result == cudaErrorMemoryAllocation) {
                logger.trace { "Not enough GPU memory for allocation. Trying to free stale memory. Gpu Mem: {${getGpuMemInfo()}}" }

                val iterator = cache.iterator()
                if (iterator.hasNext()) {
                    iterator.next().value.free()
                    iterator.remove()
                } else {
                    throw CudaException("Insufficient GPU memory for the array")
                }
            } else
                break
        }

        if (setFrom != null) {
            val hostDataPtr = dtype.getDataPointer(setFrom)
            checkResult(JCuda.cudaMemcpy(deviceDataPtr, hostDataPtr, byteSize, cudaMemcpyKind.cudaMemcpyHostToDevice))
        }

        return GpuArray(deviceDataPtr, byteSize, transposed)
    }

    private fun cleanup() {
        var firstRun = true
        while (deleteQueue.isNotEmpty()) {
            if (firstRun) {
                logger.trace { "Delete queue is not empty. Cleaning" }
                firstRun = false
            }

            val key = deleteQueue.poll()
            cache.remove(key)?.free(copyToHost = false)
        }
    }
}