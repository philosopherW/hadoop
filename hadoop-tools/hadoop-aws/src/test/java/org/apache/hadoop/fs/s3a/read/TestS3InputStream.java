/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hadoop.fs.s3a.read;

import java.io.EOFException;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Test;

import org.apache.hadoop.fs.FSExceptionMessages;
import org.apache.hadoop.fs.common.ExceptionAsserts;
import org.apache.hadoop.fs.common.ExecutorServiceFuturePool;
import org.apache.hadoop.fs.s3a.S3AInputStream;
import org.apache.hadoop.fs.s3a.S3AReadOpContext;
import org.apache.hadoop.fs.s3a.S3ObjectAttributes;
import org.apache.hadoop.test.AbstractHadoopTestBase;

import static org.junit.Assert.assertEquals;

/**
 * Applies the same set of tests to both S3CachingInputStream and S3InMemoryInputStream.
 */
public class TestS3InputStream extends AbstractHadoopTestBase {

  private static final int FILE_SIZE = 10;

  private final ExecutorService threadPool = Executors.newFixedThreadPool(4);
  private final ExecutorServiceFuturePool futurePool = new ExecutorServiceFuturePool(threadPool);
  private final S3AInputStream.InputStreamCallbacks client = MockS3File.createClient("bucket");

  @Test
  public void testArgChecks() throws Exception {
    S3AReadOpContext readContext = Fakes.createReadContext(futurePool, "key", 10, 10, 1);
    S3ObjectAttributes attrs = Fakes.createObjectAttributes("bucket", "key", 10);

    // Should not throw.
    new S3CachingInputStream(readContext, attrs, client);

    ExceptionAsserts.assertThrows(
        IllegalArgumentException.class,
        "'context' must not be null",
        () -> new S3CachingInputStream(null, attrs, client));

    ExceptionAsserts.assertThrows(
        IllegalArgumentException.class,
        "'s3Attributes' must not be null",
        () -> new S3CachingInputStream(readContext, null, client));

    ExceptionAsserts.assertThrows(
        IllegalArgumentException.class,
        "'client' must not be null",
        () -> new S3CachingInputStream(readContext, attrs, null));
  }

  @Test
  public void testRead0SizedFile() throws Exception {
    S3InputStream inputStream =
        Fakes.createS3InMemoryInputStream(futurePool, "bucket", "key", 0);
    testRead0SizedFileHelper(inputStream, 9);

    inputStream = Fakes.createS3CachingInputStream(futurePool, "bucket", "key", 0, 5, 2);
    testRead0SizedFileHelper(inputStream, 5);
  }

  private void testRead0SizedFileHelper(S3InputStream inputStream, int bufferSize)
      throws Exception {
    assertEquals(0, inputStream.available());
    assertEquals(-1, inputStream.read());
    assertEquals(-1, inputStream.read());

    byte[] buffer = new byte[2];
    assertEquals(-1, inputStream.read(buffer));
    assertEquals(-1, inputStream.read());
  }

  @Test
  public void testRead() throws Exception {
    S3InputStream inputStream =
        Fakes.createS3InMemoryInputStream(futurePool, "bucket", "key", FILE_SIZE);
    testReadHelper(inputStream, FILE_SIZE);

    inputStream =
        Fakes.createS3CachingInputStream(futurePool, "bucket", "key", FILE_SIZE, 5, 2);
    testReadHelper(inputStream, 5);
  }

  private void testReadHelper(S3InputStream inputStream, int bufferSize) throws Exception {
    assertEquals(bufferSize, inputStream.available());
    assertEquals(0, inputStream.read());
    assertEquals(1, inputStream.read());

    byte[] buffer = new byte[2];
    assertEquals(2, inputStream.read(buffer));
    assertEquals(2, buffer[0]);
    assertEquals(3, buffer[1]);

    assertEquals(4, inputStream.read());

    buffer = new byte[10];
    int curPos = (int) inputStream.getPos();
    int expectedRemainingBytes = (int) (FILE_SIZE - curPos);
    int readStartOffset = 2;
    assertEquals(
        expectedRemainingBytes,
        inputStream.read(buffer, readStartOffset, expectedRemainingBytes));

    for (int i = 0; i < expectedRemainingBytes; i++) {
      assertEquals(curPos + i, buffer[readStartOffset + i]);
    }

    assertEquals(-1, inputStream.read());
    Thread.sleep(100);
    assertEquals(-1, inputStream.read());
    assertEquals(-1, inputStream.read());
    assertEquals(-1, inputStream.read(buffer));
    assertEquals(-1, inputStream.read(buffer, 1, 3));
  }

  @Test
  public void testSeek() throws Exception {
    S3InputStream inputStream;
    inputStream = Fakes.createS3InMemoryInputStream(futurePool, "bucket", "key", 9);
    testSeekHelper(inputStream, 9, 9);

    inputStream = Fakes.createS3CachingInputStream(futurePool, "bucket", "key", 9, 5, 1);
    testSeekHelper(inputStream, 5, 9);
  }

  private void testSeekHelper(S3InputStream inputStream, int bufferSize, int fileSize)
      throws Exception {
    assertEquals(0, inputStream.getPos());
    inputStream.seek(7);
    assertEquals(7, inputStream.getPos());
    inputStream.seek(0);

    assertEquals(bufferSize, inputStream.available());
    for (int i = 0; i < fileSize; i++) {
      assertEquals(i, inputStream.read());
    }

    for (int i = 0; i < fileSize; i++) {
      inputStream.seek(i);
      for (int j = i; j < fileSize; j++) {
        assertEquals(j, inputStream.read());
      }
    }

    // Test invalid seeks.
    ExceptionAsserts.assertThrows(
        EOFException.class,
        FSExceptionMessages.NEGATIVE_SEEK,
        () -> inputStream.seek(-1));
  }

  @Test
  public void testRandomSeek() throws Exception {
    S3InputStream inputStream;
    inputStream = Fakes.createS3InMemoryInputStream(futurePool, "bucket", "key", 9);
    testRandomSeekHelper(inputStream, 9, 9);

    inputStream = Fakes.createS3CachingInputStream(futurePool, "bucket", "key", 9, 5, 1);
    testRandomSeekHelper(inputStream, 5, 9);
  }

  private void testRandomSeekHelper(S3InputStream inputStream, int bufferSize, int fileSize)
      throws Exception {
    assertEquals(0, inputStream.getPos());
    inputStream.seek(7);
    assertEquals(7, inputStream.getPos());
    inputStream.seek(0);

    assertEquals(bufferSize, inputStream.available());
    for (int i = 0; i < fileSize; i++) {
      assertEquals(i, inputStream.read());
    }

    for (int i = 0; i < fileSize; i++) {
      inputStream.seek(i);
      for (int j = i; j < fileSize; j++) {
        assertEquals(j, inputStream.read());
      }

      int seekFromEndPos = fileSize - i - 1;
      inputStream.seek(seekFromEndPos);
      for (int j = seekFromEndPos; j < fileSize; j++) {
        assertEquals(j, inputStream.read());
      }
    }
  }

  @Test
  public void testClose() throws Exception {
    S3InputStream inputStream =
        Fakes.createS3InMemoryInputStream(futurePool, "bucket", "key", 9);
    testCloseHelper(inputStream, 9);

    inputStream =
        Fakes.createS3CachingInputStream(futurePool, "bucket", "key", 9, 5, 3);
    testCloseHelper(inputStream, 5);
  }

  private void testCloseHelper(S3InputStream inputStream, int bufferSize) throws Exception {
    assertEquals(bufferSize, inputStream.available());
    assertEquals(0, inputStream.read());
    assertEquals(1, inputStream.read());

    inputStream.close();

    ExceptionAsserts.assertThrows(
        IOException.class,
        FSExceptionMessages.STREAM_IS_CLOSED,
        () -> inputStream.available());

    ExceptionAsserts.assertThrows(
        IOException.class,
        FSExceptionMessages.STREAM_IS_CLOSED,
        () -> inputStream.read());

    byte[] buffer = new byte[10];
    ExceptionAsserts.assertThrows(
        IOException.class,
        FSExceptionMessages.STREAM_IS_CLOSED,
        () -> inputStream.read(buffer));

    // Verify a second close() does not throw.
    inputStream.close();
  }
}