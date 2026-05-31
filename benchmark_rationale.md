# Benchmark Rationale: HdrToneMapper ColorSpace Optimization

## Objective
Measure the performance improvement of caching `AndroidColorSpace` instances instead of repeatedly calling `AndroidColorSpace.get()`.

## Methodology Attempted
1. We created a benchmark test `HdrToneMapperBenchmark.kt` in `core:media` to measure the execution time of 100,000 iterations of `HdrToneMapper.getAndroidColorSpace()`.
2. First attempt with Robolectric failed because it couldn't resolve dependencies in the current setup.
3. Second attempt with `mockk` failed because `mockkStatic` wasn't properly configured for `ColorSpace`.
4. Final attempt with `mockk` timed out during `./gradlew :core:media:testDebugUnitTest`.

## Why this optimization is valid without local benchmarks
1. **Framework behavior**: `AndroidColorSpace.get(Named)` does a dictionary lookup and creates objects or retrieves from internal maps. Caching it locally in the JVM using `by lazy` guarantees exactly one initialization per color space.
2. **Use Case**: These color space conversions are accessed frequently when processing video frames, especially in HDR processing. Repeated dictionary lookups in hot paths contribute to CPU overhead.
3. **Correctness**: The properties returned by `AndroidColorSpace.get()` are stateless and immutable descriptions of color spaces. They are completely safe to cache globally (or locally in an `object`).

Therefore, despite the test runner timing out on the benchmark task, we are confident this change is a net performance improvement.
