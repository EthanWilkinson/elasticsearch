/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.index.store;

import org.apache.lucene.store.BufferedIndexInput;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.elasticsearch.Version;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.common.TriConsumer;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardPath;
import org.elasticsearch.index.snapshots.blobstore.BlobStoreIndexShardSnapshot;
import org.elasticsearch.index.snapshots.blobstore.BlobStoreIndexShardSnapshot.FileInfo;
import org.elasticsearch.index.store.cache.TestUtils;
import org.elasticsearch.index.store.cache.TestUtils.NoopBlobStoreCacheService;
import org.elasticsearch.indices.recovery.RecoveryState;
import org.elasticsearch.indices.recovery.SearchableSnapshotRecoveryState;
import org.elasticsearch.repositories.IndexId;
import org.elasticsearch.snapshots.Snapshot;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.xpack.searchablesnapshots.AbstractSearchableSnapshotsTestCase;
import org.elasticsearch.xpack.searchablesnapshots.SearchableSnapshots;
import org.elasticsearch.xpack.searchablesnapshots.cache.CacheService;
import org.elasticsearch.xpack.searchablesnapshots.cache.FrozenCacheService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.elasticsearch.index.store.cache.TestUtils.assertCounter;
import static org.elasticsearch.index.store.cache.TestUtils.singleBlobContainer;
import static org.elasticsearch.xpack.searchablesnapshots.SearchableSnapshots.SNAPSHOT_CACHE_ENABLED_SETTING;
import static org.elasticsearch.xpack.searchablesnapshots.SearchableSnapshots.SNAPSHOT_CACHE_PREWARM_ENABLED_SETTING;
import static org.elasticsearch.xpack.searchablesnapshots.SearchableSnapshots.SNAPSHOT_UNCACHED_CHUNK_SIZE_SETTING;
import static org.elasticsearch.xpack.searchablesnapshots.SearchableSnapshotsUtils.toIntBytes;
import static org.elasticsearch.xpack.searchablesnapshots.cache.CacheService.resolveSnapshotCache;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class SearchableSnapshotDirectoryStatsTests extends AbstractSearchableSnapshotsTestCase {

    private static final int MAX_FILE_LENGTH = 10_000;

    /**
     * These tests simulate the passage of time with a clock that advances 100ms each time it is read.
     */
    private static final long FAKE_CLOCK_ADVANCE_NANOS = TimeValue.timeValueMillis(100).nanos();

    public void testOpenCount() throws Exception {
        executeTestCase((fileName, fileContent, directory) -> {
            try {
                for (long i = 0L; i < randomLongBetween(1L, 20L); i++) {
                    IndexInputStats inputStats = directory.getStats(fileName);
                    assertThat(inputStats, (i == 0L) ? nullValue() : notNullValue());

                    final IndexInput input = directory.openInput(fileName, newIOContext(random()));
                    inputStats = directory.getStats(fileName);
                    assertThat(inputStats.getOpened().longValue(), equalTo(i + 1L));
                    input.close();
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    public void testCloseCount() throws Exception {
        executeTestCase((fileName, fileContent, directory) -> {
            try {
                for (long i = 0L; i < randomLongBetween(1L, 20L); i++) {
                    final IndexInput input = directory.openInput(fileName, newIOContext(random()));

                    IndexInputStats inputStats = directory.getStats(fileName);
                    assertThat(inputStats, notNullValue());

                    assertThat(inputStats.getClosed().longValue(), equalTo(i));
                    input.close();
                    assertThat(inputStats.getClosed().longValue(), equalTo(i + 1L));
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    public void testCachedBytesReadsAndWrites() throws Exception {
        // a cache service with a low range size but enough space to not evict the cache file
        final ByteSizeValue rangeSize = new ByteSizeValue(randomLongBetween(MAX_FILE_LENGTH, MAX_FILE_LENGTH * 2), ByteSizeUnit.BYTES);
        final ByteSizeValue cacheSize = new ByteSizeValue(10, ByteSizeUnit.MB);

        executeTestCaseWithCache(cacheSize, rangeSize, (fileName, fileContent, directory) -> {
            try (IndexInput input = directory.openInput(fileName, newIOContext(random()))) {
                final long length = input.length();

                final IndexInputStats inputStats = directory.getStats(fileName);
                assertThat(inputStats, notNullValue());

                final byte[] result = randomReadAndSlice(input, toIntBytes(length));
                assertArrayEquals(fileContent, result);

                final long cachedBytesWriteCount = TestUtils.numberOfRanges(length, rangeSize.getBytes());

                // cache writes are executed in a different thread pool and can take some time to be processed
                assertBusy(() -> {
                    assertThat(inputStats.getCachedBytesWritten(), notNullValue());
                    assertThat(inputStats.getCachedBytesWritten().total(), equalTo(length));
                    final long actualWriteCount = inputStats.getCachedBytesWritten().count();
                    assertThat(actualWriteCount, lessThanOrEqualTo(cachedBytesWriteCount));
                    assertThat(inputStats.getCachedBytesWritten().min(), greaterThan(0L));
                    assertThat(inputStats.getCachedBytesWritten().max(), lessThanOrEqualTo(length));
                    assertThat(
                        inputStats.getCachedBytesWritten().totalNanoseconds(),
                        allOf(
                            // each read takes at least FAKE_CLOCK_ADVANCE_NANOS time
                            greaterThanOrEqualTo(FAKE_CLOCK_ADVANCE_NANOS * actualWriteCount),

                            // worst case: we start all reads before finishing any of them
                            lessThanOrEqualTo(FAKE_CLOCK_ADVANCE_NANOS * actualWriteCount * actualWriteCount)
                        )
                    );
                });

                assertThat(inputStats.getCachedBytesRead(), notNullValue());
                assertThat(inputStats.getCachedBytesRead().total(), greaterThanOrEqualTo(length));
                assertThat(inputStats.getCachedBytesRead().count(), greaterThan(0L));
                assertThat(inputStats.getCachedBytesRead().min(), greaterThan(0L));
                assertThat(inputStats.getCachedBytesRead().max(), lessThanOrEqualTo(length));

                assertCounter(inputStats.getDirectBytesRead(), 0L, 0L, 0L, 0L);
                assertThat(inputStats.getDirectBytesRead().totalNanoseconds(), equalTo(0L));

                assertCounter(inputStats.getOptimizedBytesRead(), 0L, 0L, 0L, 0L);
                assertThat(inputStats.getOptimizedBytesRead().totalNanoseconds(), equalTo(0L));

            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    public void testCachedBytesReadsAndWritesNoCache() throws Exception {
        final ByteSizeValue uncachedChunkSize = new ByteSizeValue(randomIntBetween(512, MAX_FILE_LENGTH), ByteSizeUnit.BYTES);
        executeTestCaseWithoutCache(uncachedChunkSize, (fileName, fileContent, directory) -> {
            try (IndexInput input = directory.openInput(fileName, newIOContext(random()))) {
                final long length = input.length();

                final IndexInputStats inputStats = directory.getStats(fileName);
                assertThat(inputStats, notNullValue());

                final byte[] result = randomReadAndSlice(input, toIntBytes(length));
                assertArrayEquals(fileContent, result);

                assertThat(inputStats.getCachedBytesWritten(), notNullValue());
                assertCounter(inputStats.getCachedBytesWritten(), 0L, 0L, 0L, 0L);

                assertThat(inputStats.getCachedBytesRead(), notNullValue());
                assertCounter(inputStats.getCachedBytesRead(), 0L, 0L, 0L, 0L);

            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    public void testDirectBytesReadsWithCache() throws Exception {
        // Cache service always evicts files
        executeTestCaseWithCache(ByteSizeValue.ZERO, randomCacheRangeSize(), (fileName, fileContent, directory) -> {
            assertThat(directory.getStats(fileName), nullValue());

            final IOContext ioContext = newIOContext(random());
            try {
                IndexInput input = directory.openInput(fileName, ioContext);
                if (randomBoolean()) {
                    input = input.slice("test", 0L, input.length());
                }
                if (randomBoolean()) {
                    input = input.clone();
                }
                final IndexInputStats inputStats = directory.getStats(fileName);

                // account for internal buffered reads
                final long bufferSize = BufferedIndexInput.bufferSize(ioContext);
                final long remaining = input.length() % bufferSize;
                final long expectedTotal = input.length();
                final long expectedCount = input.length() / bufferSize + (remaining > 0L ? 1L : 0L);
                final long minRead = remaining > 0L ? remaining : bufferSize;
                final long maxRead = Math.min(input.length(), bufferSize);

                // read all index input sequentially as it simplifies testing
                final byte[] readBuffer = new byte[512];
                for (long i = 0L; i < input.length();) {
                    int size = between(1, toIntBytes(Math.min(readBuffer.length, input.length() - input.getFilePointer())));
                    input.readBytes(readBuffer, 0, size);
                    i += size;

                    // direct cache file reads are aligned with the internal buffer
                    long currentCount = i / bufferSize + (i % bufferSize > 0L ? 1L : 0L);
                    if (currentCount < expectedCount) {
                        assertCounter(inputStats.getDirectBytesRead(), currentCount * bufferSize, currentCount, bufferSize, bufferSize);
                    } else {
                        assertCounter(inputStats.getDirectBytesRead(), expectedTotal, expectedCount, minRead, maxRead);
                    }
                    assertThat(inputStats.getDirectBytesRead().totalNanoseconds(), equalTo(currentCount * FAKE_CLOCK_ADVANCE_NANOS));
                }

                // cache file has never been written nor read
                assertCounter(inputStats.getCachedBytesWritten(), 0L, 0L, 0L, 0L);
                assertCounter(inputStats.getCachedBytesRead(), 0L, 0L, 0L, 0L);
                assertThat(inputStats.getCachedBytesWritten().totalNanoseconds(), equalTo(0L));

                input.close();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    public void testDirectBytesReadsWithoutCache() throws Exception {
        final ByteSizeValue uncachedChunkSize = new ByteSizeValue(randomIntBetween(512, MAX_FILE_LENGTH), ByteSizeUnit.BYTES);
        executeTestCaseWithoutCache(uncachedChunkSize, (fileName, fileContent, directory) -> {
            assertThat(directory.getStats(fileName), nullValue());

            final IOContext ioContext = newIOContext(random());
            try (IndexInput original = directory.openInput(fileName, ioContext)) {
                final IndexInput input = original.clone(); // always clone to only execute direct reads
                final IndexInputStats inputStats = directory.getStats(fileName);

                // account for internal buffered reads
                final long bufferSize = BufferedIndexInput.bufferSize(ioContext);
                final long remaining = input.length() % bufferSize;
                final long expectedTotal = input.length();
                final long expectedCount = input.length() / bufferSize + (remaining > 0L ? 1L : 0L);
                final long minRead = remaining > 0L ? remaining : bufferSize;
                final long maxRead = Math.min(input.length(), bufferSize);

                // read all index input sequentially as it simplifies testing
                for (long i = 0L; i < input.length(); i++) {
                    input.readByte();
                }

                assertCounter(inputStats.getDirectBytesRead(), expectedTotal, expectedCount, minRead, maxRead);
                assertThat(inputStats.getDirectBytesRead().totalNanoseconds(), equalTo(expectedCount * FAKE_CLOCK_ADVANCE_NANOS));

                // cache file has never been written nor read
                assertCounter(inputStats.getCachedBytesWritten(), 0L, 0L, 0L, 0L);
                assertCounter(inputStats.getCachedBytesRead(), 0L, 0L, 0L, 0L);
                assertThat(inputStats.getCachedBytesWritten().totalNanoseconds(), equalTo(0L));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    public void testOptimizedBytesReads() throws Exception {
        // use a large uncached chunk size that allows to read the file in a single operation
        final ByteSizeValue uncachedChunkSize = new ByteSizeValue(1, ByteSizeUnit.GB);
        executeTestCaseWithoutCache(uncachedChunkSize, (fileName, fileContent, directory) -> {
            final IOContext context = newIOContext(random());
            try (IndexInput input = directory.openInput(fileName, context)) {
                final IndexInputStats inputStats = directory.getStats(fileName);
                assertThat(inputStats, notNullValue());

                // read all index input sequentially as it simplifies testing
                for (long i = 0L; i < input.length(); i++) {
                    input.readByte();
                }

                // account for internal buffered reads
                final long bufferSize = BufferedIndexInput.bufferSize(context);
                if (input.length() <= bufferSize) {
                    // file is read in a single non-optimized read operation
                    assertCounter(inputStats.getDirectBytesRead(), input.length(), 1L, input.length(), input.length());
                    assertThat(inputStats.getDirectBytesRead().totalNanoseconds(), equalTo(FAKE_CLOCK_ADVANCE_NANOS));
                    assertCounter(inputStats.getOptimizedBytesRead(), 0L, 0L, 0L, 0L);
                } else {
                    final long remaining = input.length() % bufferSize;
                    final long expectedClockCounts = input.length() / bufferSize + (remaining > 0L ? 1L : 0L);

                    // file is read in a single optimized read operation
                    IndexInputStats.TimedCounter optimizedBytesRead = inputStats.getOptimizedBytesRead();
                    assertCounter(optimizedBytesRead, input.length(), 1L, input.length(), input.length());
                    assertThat(optimizedBytesRead.totalNanoseconds(), equalTo(expectedClockCounts * FAKE_CLOCK_ADVANCE_NANOS));
                    assertCounter(inputStats.getDirectBytesRead(), 0L, 0L, 0L, 0L);
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    public void testReadBytesContiguously() throws Exception {
        executeTestCaseWithDefaultCache((fileName, fileContent, cacheDirectory) -> {
            final IOContext ioContext = newIOContext(random());

            try (IndexInput input = cacheDirectory.openInput(fileName, ioContext)) {
                final IndexInputStats inputStats = cacheDirectory.getStats(fileName);

                // account for the CacheBufferedIndexInput internal buffer
                final long bufferSize = BufferedIndexInput.bufferSize(ioContext);
                final long remaining = input.length() % bufferSize;
                final long expectedTotal = input.length();
                final long expectedCount = input.length() / bufferSize + (remaining > 0L ? 1L : 0L);
                final long minRead = remaining > 0L ? remaining : bufferSize;
                final long maxRead = input.length() < bufferSize ? input.length() : bufferSize;

                final byte[] readBuffer = new byte[512];

                // read the input input sequentially
                for (long bytesRead = 0L; bytesRead < input.length();) {
                    int size = between(1, toIntBytes(Math.min(readBuffer.length, input.length() - bytesRead)));
                    input.readBytes(readBuffer, 0, size);
                    bytesRead += size;

                    // cache file reads are aligned with internal buffered reads
                    long currentCount = bytesRead / bufferSize + (bytesRead % bufferSize > 0L ? 1L : 0L);
                    if (currentCount < expectedCount) {
                        assertCounter(inputStats.getContiguousReads(), currentCount * bufferSize, currentCount, bufferSize, bufferSize);
                        assertCounter(inputStats.getCachedBytesRead(), currentCount * bufferSize, currentCount, bufferSize, bufferSize);

                    } else {
                        assertCounter(inputStats.getContiguousReads(), expectedTotal, expectedCount, minRead, maxRead);
                        assertCounter(inputStats.getCachedBytesRead(), expectedTotal, expectedCount, minRead, maxRead);
                    }
                }

                // cache file has been written in a single chunk in a different thread pool and can take some time to be processed
                assertBusy(() -> {
                    assertCounter(inputStats.getCachedBytesWritten(), input.length(), 1L, input.length(), input.length());
                    assertThat(inputStats.getCachedBytesWritten().totalNanoseconds(), equalTo(FAKE_CLOCK_ADVANCE_NANOS));
                });

                assertCounter(inputStats.getNonContiguousReads(), 0L, 0L, 0L, 0L);
                assertCounter(inputStats.getDirectBytesRead(), 0L, 0L, 0L, 0L);
                assertThat(inputStats.getDirectBytesRead().totalNanoseconds(), equalTo(0L));

            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    public void testReadBytesNonContiguously() throws Exception {
        executeTestCaseWithDefaultCache((fileName, fileContent, cacheDirectory) -> {
            final IOContext ioContext = newIOContext(random());

            try (IndexInput input = cacheDirectory.openInput(fileName, ioContext)) {
                final IndexInputStats inputStats = cacheDirectory.getStats(fileName);

                long totalBytesRead = 0L;
                long minBytesRead = Long.MAX_VALUE;
                long maxBytesRead = Long.MIN_VALUE;
                long lastReadPosition = 0L;
                int iterations = between(1, 10);

                for (int i = 1; i <= iterations; i++) {
                    final long randomPosition = randomValueOtherThan(lastReadPosition, () -> randomLongBetween(1L, input.length() - 1L));
                    input.seek(randomPosition);

                    final byte[] readBuffer = new byte[512];
                    int size = between(1, toIntBytes(Math.min(readBuffer.length, input.length() - randomPosition)));
                    input.readBytes(readBuffer, 0, size);

                    // BufferedIndexInput tries to read as much bytes as possible
                    final long bytesRead = Math.min(BufferedIndexInput.bufferSize(ioContext), input.length() - randomPosition);
                    lastReadPosition = randomPosition + bytesRead;
                    totalBytesRead += bytesRead;
                    minBytesRead = (bytesRead < minBytesRead) ? bytesRead : minBytesRead;
                    maxBytesRead = (bytesRead > maxBytesRead) ? bytesRead : maxBytesRead;

                    assertCounter(inputStats.getNonContiguousReads(), totalBytesRead, i, minBytesRead, maxBytesRead);

                    // seek to the beginning forces a refill of the internal buffer (and simplifies a lot the test)
                    input.seek(0L);
                }

                // Use assertBusy() here to wait for the cache write to be processed in the searchable snapshot thread pool
                assertBusy(() -> {
                    // cache file has been written in a single chunk
                    assertCounter(inputStats.getCachedBytesWritten(), input.length(), 1L, input.length(), input.length());
                    assertThat(inputStats.getCachedBytesWritten().totalNanoseconds(), equalTo(FAKE_CLOCK_ADVANCE_NANOS));
                });

                assertCounter(inputStats.getContiguousReads(), 0L, 0L, 0L, 0L);
                assertCounter(inputStats.getDirectBytesRead(), 0L, 0L, 0L, 0L);
                assertThat(inputStats.getDirectBytesRead().totalNanoseconds(), equalTo(0L));

            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    public void testForwardSeeks() throws Exception {
        executeTestCaseWithDefaultCache((fileName, fileContent, cacheDirectory) -> {
            final IOContext ioContext = newIOContext(random());
            try (IndexInput indexInput = cacheDirectory.openInput(fileName, ioContext)) {
                IndexInput input = indexInput;
                if (randomBoolean()) {
                    final long sliceOffset = randomLongBetween(0L, input.length() - 1L);
                    final long sliceLength = randomLongBetween(1L, input.length() - sliceOffset);
                    input = input.slice("slice", sliceOffset, sliceLength);
                }
                if (randomBoolean()) {
                    input = input.clone();
                }

                final IndexInputStats inputStats = cacheDirectory.getStats(fileName);
                final IndexInputStats.Counter forwardSmallSeeksCounter = inputStats.getForwardSmallSeeks();
                assertCounter(forwardSmallSeeksCounter, 0L, 0L, 0L, 0L);

                long totalSmallSeeks = 0L;
                long countSmallSeeks = 0L;
                long minSmallSeeks = Long.MAX_VALUE;
                long maxSmallSeeks = Long.MIN_VALUE;

                final IndexInputStats.Counter forwardLargeSeeksCounter = inputStats.getForwardLargeSeeks();
                assertCounter(forwardLargeSeeksCounter, 0L, 0L, 0L, 0L);

                long totalLargeSeeks = 0L;
                long countLargeSeeks = 0L;
                long minLargeSeeks = Long.MAX_VALUE;
                long maxLargeSeeks = Long.MIN_VALUE;

                while (input.getFilePointer() < input.length()) {
                    long moveForward = randomLongBetween(1L, input.length() - input.getFilePointer());
                    input.seek(input.getFilePointer() + moveForward);

                    if (inputStats.isLargeSeek(moveForward)) {
                        minLargeSeeks = (moveForward < minLargeSeeks) ? moveForward : minLargeSeeks;
                        maxLargeSeeks = (moveForward > maxLargeSeeks) ? moveForward : maxLargeSeeks;
                        totalLargeSeeks += moveForward;
                        countLargeSeeks += 1;

                        assertCounter(forwardLargeSeeksCounter, totalLargeSeeks, countLargeSeeks, minLargeSeeks, maxLargeSeeks);

                    } else {
                        minSmallSeeks = (moveForward < minSmallSeeks) ? moveForward : minSmallSeeks;
                        maxSmallSeeks = (moveForward > maxSmallSeeks) ? moveForward : maxSmallSeeks;
                        totalSmallSeeks += moveForward;
                        countSmallSeeks += 1;

                        assertCounter(forwardSmallSeeksCounter, totalSmallSeeks, countSmallSeeks, minSmallSeeks, maxSmallSeeks);
                    }
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    public void testBackwardSeeks() throws Exception {
        executeTestCaseWithDefaultCache((fileName, fileContent, cacheDirectory) -> {
            final IOContext ioContext = newIOContext(random());
            try (IndexInput indexInput = cacheDirectory.openInput(fileName, ioContext)) {
                IndexInput input = indexInput;
                if (randomBoolean()) {
                    final long sliceOffset = randomLongBetween(0L, input.length() - 1L);
                    final long sliceLength = randomLongBetween(1L, input.length() - sliceOffset);
                    input = input.slice("slice", sliceOffset, sliceLength);
                }
                if (randomBoolean()) {
                    input = input.clone();
                }

                final IndexInputStats inputStats = cacheDirectory.getStats(fileName);
                final IndexInputStats.Counter backwardSmallSeeks = inputStats.getBackwardSmallSeeks();
                assertCounter(backwardSmallSeeks, 0L, 0L, 0L, 0L);

                long totalSmallSeeks = 0L;
                long countSmallSeeks = 0L;
                long minSmallSeeks = Long.MAX_VALUE;
                long maxSmallSeeks = Long.MIN_VALUE;

                final IndexInputStats.Counter backwardLargeSeeks = inputStats.getBackwardLargeSeeks();
                assertCounter(backwardLargeSeeks, 0L, 0L, 0L, 0L);

                long totalLargeSeeks = 0L;
                long countLargeSeeks = 0L;
                long minLargeSeeks = Long.MAX_VALUE;
                long maxLargeSeeks = Long.MIN_VALUE;

                input.seek(input.length());
                assertThat(input.getFilePointer(), equalTo(input.length()));

                do {
                    long moveBackward = -1L * randomLongBetween(1L, input.getFilePointer());
                    input.seek(input.getFilePointer() + moveBackward);

                    if (inputStats.isLargeSeek(moveBackward)) {
                        minLargeSeeks = (moveBackward < minLargeSeeks) ? moveBackward : minLargeSeeks;
                        maxLargeSeeks = (moveBackward > maxLargeSeeks) ? moveBackward : maxLargeSeeks;
                        totalLargeSeeks += moveBackward;
                        countLargeSeeks += 1;

                        assertCounter(backwardLargeSeeks, totalLargeSeeks, countLargeSeeks, minLargeSeeks, maxLargeSeeks);

                    } else {
                        minSmallSeeks = (moveBackward < minSmallSeeks) ? moveBackward : minSmallSeeks;
                        maxSmallSeeks = (moveBackward > maxSmallSeeks) ? moveBackward : maxSmallSeeks;
                        totalSmallSeeks += moveBackward;
                        countSmallSeeks += 1;

                        assertCounter(backwardSmallSeeks, totalSmallSeeks, countSmallSeeks, minSmallSeeks, maxSmallSeeks);
                    }

                } while (input.getFilePointer() > 0L);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    private void executeTestCase(final TriConsumer<String, byte[], SearchableSnapshotDirectory> test) throws Exception {
        executeTestCase(
            randomCacheService(),
            randomFrozenCacheService(),
            Settings.builder()
                .put(SNAPSHOT_CACHE_ENABLED_SETTING.getKey(), randomBoolean())
                .put(SNAPSHOT_CACHE_PREWARM_ENABLED_SETTING.getKey(), false) // disable prewarming as it impacts the stats
                .put(SearchableSnapshots.SNAPSHOT_PARTIAL_SETTING.getKey(), randomBoolean())
                .build(),
            test
        );
    }

    private void executeTestCaseWithoutCache(
        final ByteSizeValue uncachedChunkSize,
        final TriConsumer<String, byte[], SearchableSnapshotDirectory> test
    ) throws Exception {
        executeTestCase(
            defaultCacheService(),
            defaultFrozenCacheService(),
            Settings.builder()
                .put(SNAPSHOT_UNCACHED_CHUNK_SIZE_SETTING.getKey(), uncachedChunkSize)
                .put(SNAPSHOT_CACHE_ENABLED_SETTING.getKey(), false)
                .put(SearchableSnapshots.SNAPSHOT_PARTIAL_SETTING.getKey(), randomBoolean())
                .build(),
            test
        );
    }

    private void executeTestCaseWithDefaultCache(final TriConsumer<String, byte[], SearchableSnapshotDirectory> test) throws Exception {
        executeTestCase(
            defaultCacheService(),
            createFrozenCacheService(
                ByteSizeValue.ofMb(10),
                new ByteSizeValue(randomLongBetween(MAX_FILE_LENGTH, MAX_FILE_LENGTH * 2), ByteSizeUnit.BYTES)
            ),
            Settings.builder()
                .put(SNAPSHOT_CACHE_ENABLED_SETTING.getKey(), true)
                .put(SNAPSHOT_CACHE_PREWARM_ENABLED_SETTING.getKey(), false) // disable prewarming as it impacts the stats
                .put(SearchableSnapshots.SNAPSHOT_PARTIAL_SETTING.getKey(), randomBoolean())
                .build(),
            test
        );
    }

    private void executeTestCaseWithCache(
        final ByteSizeValue cacheSize,
        final ByteSizeValue cacheRangeSize,
        final TriConsumer<String, byte[], SearchableSnapshotDirectory> test
    ) throws Exception {
        executeTestCase(
            createCacheService(cacheSize, cacheRangeSize),
            createFrozenCacheService(cacheSize, cacheRangeSize),
            Settings.builder()
                .put(SNAPSHOT_CACHE_ENABLED_SETTING.getKey(), true)
                .put(SNAPSHOT_CACHE_PREWARM_ENABLED_SETTING.getKey(), false) // disable prewarming as it impacts the stats
                .put(SearchableSnapshots.SNAPSHOT_PARTIAL_SETTING.getKey(), randomBoolean())
                .build(),
            test
        );
    }

    private void executeTestCase(
        final CacheService cacheService,
        final FrozenCacheService frozenCacheService,
        final Settings indexSettings,
        final TriConsumer<String, byte[], SearchableSnapshotDirectory> test
    ) throws Exception {

        final byte[] fileContent = randomByteArrayOfLength(randomIntBetween(10, MAX_FILE_LENGTH));
        final String fileExtension = randomAlphaOfLength(3);
        final String fileName = randomAlphaOfLength(10) + '.' + fileExtension;
        final SnapshotId snapshotId = new SnapshotId("_name", "_uuid");
        final IndexId indexId = new IndexId("_name", "_uuid");
        final ShardId shardId = new ShardId("_name", "_uuid", 0);
        final AtomicLong fakeClock = new AtomicLong();
        final LongSupplier statsCurrentTimeNanos = () -> fakeClock.addAndGet(FAKE_CLOCK_ADVANCE_NANOS);

        final Long seekingThreshold = randomBoolean() ? randomLongBetween(1L, fileContent.length) : null;

        final String blobName = randomUnicodeOfLength(10);
        final BlobContainer blobContainer = singleBlobContainer(blobName, fileContent);
        final StoreFileMetadata metadata = new StoreFileMetadata(fileName, fileContent.length, "_checksum", Version.CURRENT.luceneVersion);
        final List<FileInfo> files = Collections.singletonList(new FileInfo(blobName, metadata, new ByteSizeValue(fileContent.length)));
        final BlobStoreIndexShardSnapshot snapshot = new BlobStoreIndexShardSnapshot(snapshotId.getName(), 0L, files, 0L, 0L, 0, 0L);
        final Path shardDir = randomShardPath(shardId);
        final ShardPath shardPath = new ShardPath(false, shardDir, shardDir, shardId);
        final Path cacheDir = Files.createDirectories(resolveSnapshotCache(shardDir).resolve(snapshotId.getUUID()));

        try (
            CacheService ignored = cacheService;
            FrozenCacheService ignored2 = frozenCacheService;
            SearchableSnapshotDirectory directory = new SearchableSnapshotDirectory(
                () -> blobContainer,
                () -> snapshot,
                new NoopBlobStoreCacheService(),
                "_repo",
                snapshotId,
                indexId,
                shardId,
                indexSettings,
                statsCurrentTimeNanos,
                cacheService,
                cacheDir,
                shardPath,
                threadPool,
                frozenCacheService
            ) {
                @Override
                protected IndexInputStats createIndexInputStats(long fileLength) {
                    if (seekingThreshold == null) {
                        return super.createIndexInputStats(fileLength);
                    }
                    return new IndexInputStats(fileLength, seekingThreshold, statsCurrentTimeNanos);
                }
            }
        ) {
            cacheService.start();
            assertThat(directory.getStats(fileName), nullValue());

            ShardRouting shardRouting = TestShardRouting.newShardRouting(
                new ShardId(randomAlphaOfLength(10), randomAlphaOfLength(10), 0),
                randomAlphaOfLength(10),
                true,
                ShardRoutingState.INITIALIZING,
                new RecoverySource.SnapshotRecoverySource(
                    UUIDs.randomBase64UUID(),
                    new Snapshot("repo", new SnapshotId(randomAlphaOfLength(8), UUIDs.randomBase64UUID())),
                    Version.CURRENT,
                    new IndexId("some_index", UUIDs.randomBase64UUID(random()))
                )
            );
            DiscoveryNode targetNode = new DiscoveryNode("local", buildNewFakeTransportAddress(), Version.CURRENT);
            RecoveryState recoveryState = new SearchableSnapshotRecoveryState(shardRouting, targetNode, null);
            final PlainActionFuture<Void> future = PlainActionFuture.newFuture();
            final boolean loaded = directory.loadSnapshot(recoveryState, future);
            future.get();
            assertThat("Failed to load snapshot", loaded, is(true));
            assertThat("Snapshot should be loaded", directory.snapshot(), notNullValue());
            assertThat("BlobContainer should be loaded", directory.blobContainer(), notNullValue());

            test.apply(fileName, fileContent, directory);
        } finally {
            assertThreadPoolNotBusy(threadPool);
        }
    }
}
