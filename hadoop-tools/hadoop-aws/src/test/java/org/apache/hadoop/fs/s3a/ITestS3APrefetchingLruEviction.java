/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.s3a;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.s3a.performance.AbstractS3ACostTest;
import org.apache.hadoop.fs.statistics.IOStatistics;
import org.apache.hadoop.test.LambdaTestUtils;

import static org.apache.hadoop.fs.s3a.Constants.PREFETCH_BLOCK_DEFAULT_SIZE;
import static org.apache.hadoop.fs.s3a.Constants.PREFETCH_BLOCK_SIZE_KEY;
import static org.apache.hadoop.fs.s3a.Constants.PREFETCH_ENABLED_KEY;
import static org.apache.hadoop.fs.s3a.Constants.PREFETCH_MAX_BLOCKS_COUNT;
import static org.apache.hadoop.fs.statistics.IOStatisticAssertions.verifyStatisticGaugeValue;
import static org.apache.hadoop.fs.statistics.StreamStatisticNames.STREAM_READ_BLOCKS_IN_FILE_CACHE;
import static org.apache.hadoop.io.IOUtils.cleanupWithLogger;

/**
 * Test the prefetching input stream with LRU cache eviction on S3ACachingInputStream.
 */
@RunWith(Parameterized.class)
public class ITestS3APrefetchingLruEviction extends AbstractS3ACostTest {

  private final String maxBlocks;

  @Parameterized.Parameters(name = "max-blocks-{0}")
  public static Collection<Object[]> params() {
    return Arrays.asList(new Object[][]{
        {"1"},
        {"2"},
        {"3"},
        {"4"}
    });
  }

  public ITestS3APrefetchingLruEviction(final String maxBlocks) {
    super(true);
    this.maxBlocks = maxBlocks;
  }

  private static final Logger LOG =
      LoggerFactory.getLogger(ITestS3APrefetchingLruEviction.class);

  private static final int S_1K = 1024;
  // Path for file which should have length > block size so S3ACachingInputStream is used
  private Path largeFile;
  private FileSystem largeFileFS;
  private int blockSize;

  private static final int TIMEOUT_MILLIS = 5000;
  private static final int INTERVAL_MILLIS = 500;

  @Override
  public Configuration createConfiguration() {
    Configuration conf = super.createConfiguration();
    S3ATestUtils.removeBaseAndBucketOverrides(conf, PREFETCH_ENABLED_KEY);
    S3ATestUtils.removeBaseAndBucketOverrides(conf, PREFETCH_MAX_BLOCKS_COUNT);
    conf.setBoolean(PREFETCH_ENABLED_KEY, true);
    conf.setInt(PREFETCH_MAX_BLOCKS_COUNT, Integer.parseInt(maxBlocks));
    return conf;
  }

  @Override
  public void teardown() throws Exception {
    super.teardown();
    cleanupWithLogger(LOG, largeFileFS);
    largeFileFS = null;
  }

  private void openFS() throws Exception {
    Configuration conf = getConfiguration();
    String largeFileUri = S3ATestUtils.getCSVTestFile(conf);

    largeFile = new Path(largeFileUri);
    blockSize = conf.getInt(PREFETCH_BLOCK_SIZE_KEY, PREFETCH_BLOCK_DEFAULT_SIZE);
    largeFileFS = new S3AFileSystem();
    largeFileFS.initialize(new URI(largeFileUri), getConfiguration());
  }

  @Test
  public void testSeeksWithLruEviction() throws Throwable {
    IOStatistics ioStats;
    openFS();

    ExecutorService executorService = Executors.newFixedThreadPool(5,
        new ThreadFactoryBuilder()
            .setDaemon(true)
            .setNameFormat("testSeeksWithLruEviction-%d")
            .build());
    CountDownLatch countDownLatch = new CountDownLatch(7);

    try (FSDataInputStream in = largeFileFS.open(largeFile)) {
      ioStats = in.getIOStatistics();
      // tests to add multiple blocks in the prefetch cache
      // and let LRU eviction take place as more cache entries
      // are added with multiple block reads.

      // Don't read block 0 completely
      executorService.submit(() -> readFullyWithPositionedRead(countDownLatch,
          in,
          0,
          blockSize - S_1K * 10));

      // Seek to block 1 and don't read completely
      executorService.submit(() -> readFullyWithPositionedRead(countDownLatch,
          in,
          blockSize,
          2 * S_1K));

      // Seek to block 2 and don't read completely
      executorService.submit(() -> readFullyWithSeek(countDownLatch,
          in,
          blockSize * 2L,
          2 * S_1K));

      // Seek to block 3 and don't read completely
      executorService.submit(() -> readFullyWithPositionedRead(countDownLatch,
          in,
          blockSize * 3L,
          2 * S_1K));

      // Seek to block 4 and don't read completely
      executorService.submit(() -> readFullyWithSeek(countDownLatch,
          in,
          blockSize * 4L,
          2 * S_1K));

      // Seek to block 5 and don't read completely
      executorService.submit(() -> readFullyWithPositionedRead(countDownLatch,
          in,
          blockSize * 5L,
          2 * S_1K));

      // backward seek, can't use block 0 as it is evicted
      executorService.submit(() -> readFullyWithSeek(countDownLatch,
          in,
          S_1K * 5,
          2 * S_1K));

      countDownLatch.await();

      // expect 3 blocks as rest are to be evicted by LRU
      LambdaTestUtils.eventually(TIMEOUT_MILLIS, INTERVAL_MILLIS, () -> {
        LOG.info("IO stats: {}", ioStats);
        verifyStatisticGaugeValue(ioStats, STREAM_READ_BLOCKS_IN_FILE_CACHE,
            Integer.parseInt(maxBlocks));
      });
      // let LRU evictions settle down, if any
      Thread.sleep(TIMEOUT_MILLIS);
    } finally {
      executorService.shutdownNow();
      executorService.awaitTermination(5, TimeUnit.SECONDS);
    }
    LambdaTestUtils.eventually(TIMEOUT_MILLIS, INTERVAL_MILLIS, () -> {
      LOG.info("IO stats: {}", ioStats);
      verifyStatisticGaugeValue(ioStats, STREAM_READ_BLOCKS_IN_FILE_CACHE, 0);
    });
  }

  /**
   * Read the bytes from the given position in the stream to a new buffer using the positioned
   * readable.
   *
   * @param countDownLatch count down latch to mark the operation completed.
   * @param in input stream.
   * @param position position in the given input stream to seek from.
   * @param len the number of bytes to read.
   * @return true if the read operation is successful.
   */
  private boolean readFullyWithPositionedRead(CountDownLatch countDownLatch, FSDataInputStream in,
      long position, int len) {
    byte[] buffer = new byte[blockSize];
    // Don't read block 0 completely
    try {
      in.readFully(position, buffer, 0, len);
      countDownLatch.countDown();
      return true;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Read the bytes from the given position in the stream to a new buffer using seek followed by
   * input stream read.
   *
   * @param countDownLatch count down latch to mark the operation completed.
   * @param in input stream.
   * @param position position in the given input stream to seek from.
   * @param len the number of bytes to read.
   * @return true if the read operation is successful.
   */
  private boolean readFullyWithSeek(CountDownLatch countDownLatch, FSDataInputStream in,
      long position, int len) {
    byte[] buffer = new byte[blockSize];
    // Don't read block 0 completely
    try {
      in.seek(position);
      in.readFully(buffer, 0, len);
      countDownLatch.countDown();
      return true;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

}