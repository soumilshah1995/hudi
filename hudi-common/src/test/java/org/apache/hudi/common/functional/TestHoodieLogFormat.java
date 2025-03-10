/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.common.functional;

import org.apache.hudi.avro.HoodieAvroUtils;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.DeleteRecord;
import org.apache.hudi.common.model.HoodieArchivedLogFile;
import org.apache.hudi.common.model.HoodieAvroRecord;
import org.apache.hudi.common.model.HoodieAvroIndexedRecord;
import org.apache.hudi.common.model.HoodieAvroRecordMerger;
import org.apache.hudi.common.model.HoodieLogFile;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecord.HoodieRecordType;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.model.HoodieTableType;
import org.apache.hudi.common.table.cdc.HoodieCDCSupplementalLoggingMode;
import org.apache.hudi.common.table.cdc.HoodieCDCUtils;
import org.apache.hudi.common.table.log.AppendResult;
import org.apache.hudi.common.table.log.HoodieLogFileReader;
import org.apache.hudi.common.table.log.HoodieLogFormat;
import org.apache.hudi.common.table.log.HoodieLogFormat.Reader;
import org.apache.hudi.common.table.log.HoodieLogFormat.Writer;
import org.apache.hudi.common.table.log.HoodieMergedLogRecordScanner;
import org.apache.hudi.common.table.log.block.HoodieAvroDataBlock;
import org.apache.hudi.common.table.log.block.HoodieCDCDataBlock;
import org.apache.hudi.common.table.log.block.HoodieCommandBlock;
import org.apache.hudi.common.table.log.block.HoodieDataBlock;
import org.apache.hudi.common.table.log.block.HoodieDeleteBlock;
import org.apache.hudi.common.table.log.block.HoodieHFileDataBlock;
import org.apache.hudi.common.table.log.block.HoodieLogBlock;
import org.apache.hudi.common.table.log.block.HoodieLogBlock.HeaderMetadataType;
import org.apache.hudi.common.table.log.block.HoodieLogBlock.HoodieLogBlockType;
import org.apache.hudi.common.table.log.block.HoodieParquetDataBlock;
import org.apache.hudi.common.testutils.FileCreateUtils;
import org.apache.hudi.common.testutils.HadoopMapRedUtils;
import org.apache.hudi.common.testutils.HoodieCommonTestHarness;
import org.apache.hudi.common.testutils.HoodieTestUtils;
import org.apache.hudi.common.testutils.SchemaTestUtil;
import org.apache.hudi.common.testutils.minicluster.HdfsTestService;
import org.apache.hudi.common.util.ClosableIterator;
import org.apache.hudi.common.util.HoodieRecordUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.ExternalSpillableMap;
import org.apache.hudi.exception.CorruptedLogFileException;
import org.apache.hudi.exception.HoodieIOException;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.hadoop.util.counters.BenchmarkCounter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.hudi.common.testutils.SchemaTestUtil.getSimpleSchema;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Tests hoodie log format {@link HoodieLogFormat}.
 */
@SuppressWarnings("Duplicates")
public class TestHoodieLogFormat extends HoodieCommonTestHarness {

  private static final HoodieLogBlockType DEFAULT_DATA_BLOCK_TYPE = HoodieLogBlockType.AVRO_DATA_BLOCK;
  private static final int BUFFER_SIZE = 4096;

  private static HdfsTestService hdfsTestService;
  private static FileSystem fs;
  private Path partitionPath;
  private String spillableBasePath;

  @BeforeAll
  public static void setUpClass() throws IOException, InterruptedException {
    // Append is not supported in LocalFileSystem. HDFS needs to be setup.
    hdfsTestService = new HdfsTestService();
    fs = hdfsTestService.start(true).getFileSystem();
  }

  @AfterAll
  public static void tearDownClass() {
    hdfsTestService.stop();
  }

  @BeforeEach
  public void setUp(TestInfo testInfo) throws IOException, InterruptedException {
    Path workDir = fs.getWorkingDirectory();
    basePath = new Path(workDir.toString(), testInfo.getDisplayName() + System.currentTimeMillis()).toString();
    partitionPath = new Path(basePath, "partition_path");
    spillableBasePath = new Path(workDir.toString(), ".spillable_path").toString();
    HoodieTestUtils.init(fs.getConf(), basePath, HoodieTableType.MERGE_ON_READ);
  }

  @Test
  public void testEmptyLog() throws IOException {
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    assertEquals(0, writer.getCurrentSize(), "Just created this log, size should be 0");
    assertTrue(writer.getLogFile().getFileName().startsWith("."), "Check all log files should start with a .");
    assertEquals(1, writer.getLogFile().getLogVersion(), "Version should be 1 for new log created");
    writer.close();
  }

  @ParameterizedTest
  @EnumSource(names = {"AVRO_DATA_BLOCK", "HFILE_DATA_BLOCK", "PARQUET_DATA_BLOCK"})
  public void testBasicAppend(HoodieLogBlockType dataBlockType) throws IOException, InterruptedException, URISyntaxException {
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    List<IndexedRecord> records = SchemaTestUtil.generateTestRecords(0, 100);
    Map<HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());
    long pos = writer.getCurrentSize();
    HoodieDataBlock dataBlock = getDataBlock(dataBlockType, records, header);
    AppendResult result = writer.appendBlock(dataBlock);

    long size = writer.getCurrentSize();
    assertTrue(size > 0, "We just wrote a block - size should be > 0");
    assertEquals(size, fs.getFileStatus(writer.getLogFile().getPath()).getLen(),
        "Write should be auto-flushed. The size reported by FileStatus and the writer should match");
    assertEquals(size, result.size());
    assertEquals(writer.getLogFile(), result.logFile());
    assertEquals(0, result.offset());
    writer.close();
  }

  @Test
  public void testRollover() throws IOException, InterruptedException, URISyntaxException {
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    List<IndexedRecord> records = SchemaTestUtil.generateTestRecords(0, 100);
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records, header);
    // Write out a block
    AppendResult firstAppend = writer.appendBlock(dataBlock);
    // Get the size of the block
    long size = writer.getCurrentSize();
    writer.close();

    assertEquals(0, firstAppend.offset());
    assertEquals(size, firstAppend.size());

    // Create a writer with the size threshold as the size we just wrote - so this has to roll
    writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).withSizeThreshold(size - 1).build();
    records = SchemaTestUtil.generateTestRecords(0, 100);
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records, header);
    AppendResult secondAppend = writer.appendBlock(dataBlock);

    assertEquals(firstAppend.logFile(), secondAppend.logFile());
    assertNotEquals(0, secondAppend.offset());
    assertEquals(0, writer.getCurrentSize(), "This should be a new log file and hence size should be 0");
    assertEquals(2, writer.getLogFile().getLogVersion(), "Version should be rolled to 2");
    Path logFilePath = writer.getLogFile().getPath();
    assertFalse(fs.exists(logFilePath), "Path (" + logFilePath + ") must not exist");

    // Write one more block, which should not go to the new log file.
    records = SchemaTestUtil.generateTestRecords(0, 100);
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records, header);
    AppendResult rolloverAppend = writer.appendBlock(dataBlock);

    assertNotEquals(secondAppend.logFile(), rolloverAppend.logFile());
    assertEquals(0, rolloverAppend.offset());
    writer.close();
  }

  @Test
  public void testConcurrentAppendOnExistingLogFileWithoutWriteToken() throws Exception {
    testConcurrentAppend(true, false);
  }

  @Test
  public void testConcurrentAppendOnExistingLogFileWithWriteToken() throws Exception {
    testConcurrentAppend(true, true);
  }

  @Test
  public void testConcurrentAppendOnFirstLogFileVersion() throws Exception {
    testConcurrentAppend(false, true);
  }

  private void testConcurrentAppend(boolean logFileExists, boolean newLogFileFormat) throws Exception {
    HoodieLogFormat.WriterBuilder builder1 = HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath)
        .withFileExtension(HoodieLogFile.DELTA_EXTENSION).withFileId("test-fileid1").overBaseCommit("100").withFs(fs);
    HoodieLogFormat.WriterBuilder builder2 = HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath)
        .withFileExtension(HoodieLogFile.DELTA_EXTENSION).withFileId("test-fileid1").overBaseCommit("100").withFs(fs);

    if (newLogFileFormat && logFileExists) {
      // Assume there is an existing log-file with write token
      builder1 = builder1.withLogVersion(1).withRolloverLogWriteToken(HoodieLogFormat.UNKNOWN_WRITE_TOKEN);
      builder2 = builder2.withLogVersion(1).withRolloverLogWriteToken(HoodieLogFormat.UNKNOWN_WRITE_TOKEN);
    } else if (newLogFileFormat) {
      // First log file of the file-slice
      builder1 = builder1.withLogVersion(HoodieLogFile.LOGFILE_BASE_VERSION)
          .withRolloverLogWriteToken(HoodieLogFormat.UNKNOWN_WRITE_TOKEN);
      builder2 = builder2.withLogVersion(HoodieLogFile.LOGFILE_BASE_VERSION)
          .withRolloverLogWriteToken(HoodieLogFormat.UNKNOWN_WRITE_TOKEN);
    } else {
      builder1 = builder1.withLogVersion(1).withRolloverLogWriteToken(HoodieLogFormat.UNKNOWN_WRITE_TOKEN);
    }
    Writer writer = builder1.build();
    List<IndexedRecord> records = SchemaTestUtil.generateTestRecords(0, 100);
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records, header);
    writer.appendBlock(dataBlock);
    Writer writer2 = builder2.build();
    writer2.appendBlock(dataBlock);
    HoodieLogFile logFile1 = writer.getLogFile();
    HoodieLogFile logFile2 = writer2.getLogFile();
    writer.close();
    writer2.close();
    assertNotNull(logFile1.getLogWriteToken());
    assertEquals(logFile1.getLogVersion(), logFile2.getLogVersion() - 1, "Log Files must have different versions");
  }

  @ParameterizedTest
  @EnumSource(names = {"AVRO_DATA_BLOCK", "HFILE_DATA_BLOCK", "PARQUET_DATA_BLOCK"})
  public void testMultipleAppend(HoodieLogBlockType dataBlockType) throws IOException, URISyntaxException, InterruptedException {
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    List<IndexedRecord> records = SchemaTestUtil.generateTestRecords(0, 100);
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());
    HoodieDataBlock dataBlock = getDataBlock(dataBlockType, records, header);
    writer.appendBlock(dataBlock);
    long size1 = writer.getCurrentSize();
    writer.close();

    writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    records = SchemaTestUtil.generateTestRecords(0, 100);
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());
    dataBlock = getDataBlock(dataBlockType, records, header);
    writer.appendBlock(dataBlock);
    long size2 = writer.getCurrentSize();
    assertTrue(size2 > size1, "We just wrote a new block - size2 should be > size1");
    assertEquals(size2, fs.getFileStatus(writer.getLogFile().getPath()).getLen(),
        "Write should be auto-flushed. The size reported by FileStatus and the writer should match");
    writer.close();

    // Close and Open again and append 100 more records
    writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    records = SchemaTestUtil.generateTestRecords(0, 100);
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());
    dataBlock = getDataBlock(dataBlockType, records, header);
    writer.appendBlock(dataBlock);
    long size3 = writer.getCurrentSize();
    assertTrue(size3 > size2, "We just wrote a new block - size3 should be > size2");
    assertEquals(size3, fs.getFileStatus(writer.getLogFile().getPath()).getLen(),
        "Write should be auto-flushed. The size reported by FileStatus and the writer should match");
    writer.close();

    // Cannot get the current size after closing the log
    final Writer closedWriter = writer;
    assertThrows(IllegalStateException.class, () -> {
      closedWriter.getCurrentSize();
    }, "getCurrentSize should fail after the logAppender is closed");
  }

  @Test
  public void testAppendNotSupported(@TempDir java.nio.file.Path tempDir) throws IOException, URISyntaxException, InterruptedException {
    // Use some fs like LocalFileSystem, that does not support appends
    Path localTempDir = new Path(tempDir.toUri());
    FileSystem localFs = FSUtils.getFs(localTempDir.toString(), HoodieTestUtils.getDefaultHadoopConf());
    assertTrue(localFs instanceof LocalFileSystem);
    Path testPath = new Path(localTempDir, "append_test");
    localFs.mkdirs(testPath);

    // Some data & append two times.
    List<IndexedRecord> records = SchemaTestUtil.generateTestRecords(0, 5);
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records, header);

    for (int i = 0; i < 2; i++) {
      Writer writer = HoodieLogFormat.newWriterBuilder().onParentPath(testPath)
          .withFileExtension(HoodieArchivedLogFile.ARCHIVE_EXTENSION).withFileId("commits.archive").overBaseCommit("")
          .withFs(localFs).build();
      writer.appendBlock(dataBlock);
      writer.close();
    }

    // ensure there are two log file versions, with same data.
    FileStatus[] statuses = localFs.listStatus(testPath);
    assertEquals(2, statuses.length);
  }

  @Test
  public void testBasicWriteAndScan() throws IOException, URISyntaxException, InterruptedException {
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    Schema schema = getSimpleSchema();
    List<IndexedRecord> records = SchemaTestUtil.generateTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords = records.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records, header);
    writer.appendBlock(dataBlock);
    writer.close();

    Reader reader = HoodieLogFormat.newReader(fs, writer.getLogFile(), SchemaTestUtil.getSimpleSchema());
    assertTrue(reader.hasNext(), "We wrote a block, we should be able to read it");
    HoodieLogBlock nextBlock = reader.next();
    assertEquals(DEFAULT_DATA_BLOCK_TYPE, nextBlock.getBlockType(), "The next block should be a data block");
    HoodieDataBlock dataBlockRead = (HoodieDataBlock) nextBlock;
    List<IndexedRecord> recordsRead = getRecords(dataBlockRead);
    assertEquals(copyOfRecords.size(), recordsRead.size(),
        "Read records size should be equal to the written records size");
    assertEquals(copyOfRecords, recordsRead,
        "Both records lists should be the same. (ordering guaranteed)");
    reader.close();
  }

  @Test
  public void testHugeLogFileWrite() throws IOException, URISyntaxException, InterruptedException {
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).withSizeThreshold(3L * 1024 * 1024 * 1024)
            .build();
    Schema schema = getSimpleSchema();
    List<IndexedRecord> records = SchemaTestUtil.generateTestRecords(0, 1000);
    List<IndexedRecord> copyOfRecords = records.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());
    byte[] dataBlockContentBytes = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records, header).getContentBytes();
    HoodieLogBlock.HoodieLogBlockContentLocation logBlockContentLoc = new HoodieLogBlock.HoodieLogBlockContentLocation(new Configuration(), null, 0, dataBlockContentBytes.length, 0);
    HoodieDataBlock reusableDataBlock = new HoodieAvroDataBlock(null, Option.ofNullable(dataBlockContentBytes), false,
        logBlockContentLoc, Option.ofNullable(getSimpleSchema()), header, new HashMap<>(), HoodieRecord.RECORD_KEY_METADATA_FIELD);
    long writtenSize = 0;
    int logBlockWrittenNum = 0;
    while (writtenSize < Integer.MAX_VALUE) {
      AppendResult appendResult = writer.appendBlock(reusableDataBlock);
      assertTrue(appendResult.size() > 0);
      writtenSize += appendResult.size();
      logBlockWrittenNum++;
    }
    writer.close();

    Reader reader = HoodieLogFormat.newReader(fs, writer.getLogFile(), SchemaTestUtil.getSimpleSchema(),
        true, true);
    assertTrue(reader.hasNext(), "We wrote a block, we should be able to read it");
    HoodieLogBlock nextBlock = reader.next();
    assertEquals(DEFAULT_DATA_BLOCK_TYPE, nextBlock.getBlockType(), "The next block should be a data block");
    HoodieDataBlock dataBlockRead = (HoodieDataBlock) nextBlock;
    List<IndexedRecord> recordsRead = getRecords(dataBlockRead);
    assertEquals(copyOfRecords.size(), recordsRead.size(),
        "Read records size should be equal to the written records size");
    assertEquals(copyOfRecords, recordsRead,
        "Both records lists should be the same. (ordering guaranteed)");
    int logBlockReadNum = 1;
    while (reader.hasNext()) {
      reader.next();
      logBlockReadNum++;
    }
    assertEquals(logBlockWrittenNum, logBlockReadNum, "All written log should be correctly found");
    reader.close();

    // test writing oversize data block which should be rejected
    Writer oversizeWriter =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withSizeThreshold(3L * 1024 * 1024 * 1024).withFs(fs)
            .build();
    List<HoodieLogBlock> dataBlocks = new ArrayList<>(logBlockWrittenNum + 1);
    for (int i = 0; i < logBlockWrittenNum + 1; i++) {
      dataBlocks.add(reusableDataBlock);
    }
    assertThrows(HoodieIOException.class, () -> {
      oversizeWriter.appendBlocks(dataBlocks);
    }, "Blocks appended may overflow. Please decrease log block size or log block amount");
    oversizeWriter.close();
  }

  @ParameterizedTest
  @EnumSource(names = {"AVRO_DATA_BLOCK", "HFILE_DATA_BLOCK", "PARQUET_DATA_BLOCK"})
  public void testBasicAppendAndRead(HoodieLogBlockType dataBlockType) throws IOException, URISyntaxException, InterruptedException {
    Writer writer = HoodieLogFormat.newWriterBuilder()
        .onParentPath(partitionPath)
        .withFileExtension(HoodieLogFile.DELTA_EXTENSION)
        .withFileId("test-fileid1")
        .overBaseCommit("100")
        .withFs(fs)
        .build();
    List<IndexedRecord> records1 = SchemaTestUtil.generateTestRecords(0, 100);
    Schema schema = getSimpleSchema();
    List<IndexedRecord> copyOfRecords1 = records1.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());
    HoodieDataBlock dataBlock = getDataBlock(dataBlockType, records1, header);
    writer.appendBlock(dataBlock);
    writer.close();

    writer = HoodieLogFormat.newWriterBuilder()
        .onParentPath(partitionPath)
        .withFileExtension(HoodieLogFile.DELTA_EXTENSION)
        .withFileId("test-fileid1")
        .overBaseCommit("100")
        .withFs(fs)
        .build();
    List<IndexedRecord> records2 = SchemaTestUtil.generateTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords2 = records2.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());
    dataBlock = getDataBlock(dataBlockType, records2, header);
    writer.appendBlock(dataBlock);
    writer.close();

    // Close and Open again and append 100 more records
    writer = HoodieLogFormat.newWriterBuilder()
        .onParentPath(partitionPath)
        .withFileExtension(HoodieLogFile.DELTA_EXTENSION)
        .withFileId("test-fileid1")
        .overBaseCommit("100")
        .withFs(fs)
        .build();

    List<IndexedRecord> records3 = SchemaTestUtil.generateTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords3 = records3.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());
    dataBlock = getDataBlock(dataBlockType, records3, header);
    writer.appendBlock(dataBlock);
    writer.close();

    Reader reader = HoodieLogFormat.newReader(fs, writer.getLogFile(), SchemaTestUtil.getSimpleSchema());
    assertTrue(reader.hasNext(), "First block should be available");
    HoodieLogBlock nextBlock = reader.next();
    HoodieDataBlock dataBlockRead = (HoodieDataBlock) nextBlock;
    List<IndexedRecord> recordsRead1 = getRecords(dataBlockRead);
    assertEquals(copyOfRecords1.size(),recordsRead1.size(),
        "Read records size should be equal to the written records size");
    assertEquals(copyOfRecords1, recordsRead1,
        "Both records lists should be the same. (ordering guaranteed)");
    assertEquals(dataBlockRead.getSchema(), getSimpleSchema());

    reader.hasNext();
    nextBlock = reader.next();
    dataBlockRead = (HoodieDataBlock) nextBlock;
    List<IndexedRecord> recordsRead2 = getRecords(dataBlockRead);
    assertEquals(copyOfRecords2.size(), recordsRead2.size(),
        "Read records size should be equal to the written records size");
    assertEquals(copyOfRecords2, recordsRead2,
        "Both records lists should be the same. (ordering guaranteed)");

    reader.hasNext();
    nextBlock = reader.next();
    dataBlockRead = (HoodieDataBlock) nextBlock;
    List<IndexedRecord> recordsRead3 = getRecords(dataBlockRead);
    assertEquals(copyOfRecords3.size(), recordsRead3.size(),
        "Read records size should be equal to the written records size");
    assertEquals(copyOfRecords3, recordsRead3,
        "Both records lists should be the same. (ordering guaranteed)");
    reader.close();
  }

  @Test
  public void testCDCBlock() throws IOException, InterruptedException {
    Writer writer = HoodieLogFormat.newWriterBuilder()
        .onParentPath(partitionPath)
        .withFileExtension(HoodieLogFile.DELTA_EXTENSION)
        .withFileId("test-fileid1")
        .overBaseCommit("100")
        .withFs(fs)
        .build();

    String dataSchameString = "{\"type\":\"record\",\"name\":\"Record\","
        + "\"fields\":["
        + "{\"name\":\"uuid\",\"type\":[\"int\",\"null\"]},"
        + "{\"name\":\"name\",\"type\":[\"string\",\"null\"]},"
        + "{\"name\":\"ts\",\"type\":[\"long\",\"null\"]}"
        + "]}";
    Schema dataSchema = new Schema.Parser().parse(dataSchameString);
    Schema cdcSchema = HoodieCDCUtils.schemaBySupplementalLoggingMode(
        HoodieCDCSupplementalLoggingMode.WITH_BEFORE_AFTER, dataSchema);
    GenericRecord insertedRecord = new GenericData.Record(dataSchema);
    insertedRecord.put("uuid", 1);
    insertedRecord.put("name", "apple");
    insertedRecord.put("ts", 1100L);

    GenericRecord updateBeforeImageRecord = new GenericData.Record(dataSchema);
    updateBeforeImageRecord.put("uuid", 2);
    updateBeforeImageRecord.put("name", "banana");
    updateBeforeImageRecord.put("ts", 1000L);
    GenericRecord updateAfterImageRecord = new GenericData.Record(dataSchema);
    updateAfterImageRecord.put("uuid", 2);
    updateAfterImageRecord.put("name", "blueberry");
    updateAfterImageRecord.put("ts", 1100L);

    GenericRecord deletedRecord = new GenericData.Record(dataSchema);
    deletedRecord.put("uuid", 3);
    deletedRecord.put("name", "cherry");
    deletedRecord.put("ts", 1000L);

    GenericRecord record1 = HoodieCDCUtils.cdcRecord(cdcSchema, "i", "100",
        null, insertedRecord);
    GenericRecord record2 = HoodieCDCUtils.cdcRecord(cdcSchema, "u", "100",
        updateBeforeImageRecord, updateAfterImageRecord);
    GenericRecord record3 = HoodieCDCUtils.cdcRecord(cdcSchema, "d", "100",
        deletedRecord, null);
    List<IndexedRecord> records = new ArrayList<>(Arrays.asList(record1, record2, record3));
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, cdcSchema.toString());
    HoodieDataBlock dataBlock = getDataBlock(HoodieLogBlockType.CDC_DATA_BLOCK, records, header);
    writer.appendBlock(dataBlock);
    writer.close();

    Reader reader = HoodieLogFormat.newReader(fs, writer.getLogFile(), cdcSchema);
    assertTrue(reader.hasNext());
    HoodieLogBlock block = reader.next();
    HoodieDataBlock dataBlockRead = (HoodieDataBlock) block;
    List<IndexedRecord> recordsRead = getRecords(dataBlockRead);
    assertEquals(3, recordsRead.size(),
        "Read records size should be equal to the written records size");
    assertEquals(dataBlockRead.getSchema(), cdcSchema);

    GenericRecord insert = (GenericRecord) recordsRead.stream()
        .filter(record -> record.get(0).toString().equals("i")).findFirst().get();
    assertNull(insert.get("before"));
    assertNotNull(insert.get("after"));
    assertEquals(((GenericRecord) insert.get("after")).get("name").toString(), "apple");

    GenericRecord update = (GenericRecord) recordsRead.stream()
        .filter(record -> record.get(0).toString().equals("u")).findFirst().get();
    assertNotNull(update.get("before"));
    assertNotNull(update.get("after"));
    GenericRecord uBefore = (GenericRecord) update.get("before");
    GenericRecord uAfter = (GenericRecord) update.get("after");
    assertEquals(String.valueOf(uBefore.get("name")), "banana");
    assertEquals(Long.valueOf(uBefore.get("ts").toString()), 1000L);
    assertEquals(String.valueOf(uAfter.get("name")), "blueberry");
    assertEquals(Long.valueOf(uAfter.get("ts").toString()), 1100L);

    GenericRecord delete = (GenericRecord) recordsRead.stream()
        .filter(record -> record.get(0).toString().equals("d")).findFirst().get();
    assertNotNull(delete.get("before"));
    assertNull(delete.get("after"));
    assertEquals(((GenericRecord) delete.get("before")).get("name").toString(), "cherry");

    reader.close();
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testBasicAppendAndScanMultipleFiles(ExternalSpillableMap.DiskMapType diskMapType,
                                                  boolean isCompressionEnabled,
                                                  boolean readBlocksLazily,
                                                  boolean useScanv2)
      throws IOException, URISyntaxException, InterruptedException {
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withSizeThreshold(1024).withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    Schema schema = HoodieAvroUtils.addMetadataFields(getSimpleSchema());
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());

    Set<HoodieLogFile> logFiles = new HashSet<>();
    List<List<IndexedRecord>> allRecords = new ArrayList<>();
    // create 4 log files
    while (writer.getLogFile().getLogVersion() != 4) {
      logFiles.add(writer.getLogFile());
      List<IndexedRecord> records1 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
      List<IndexedRecord> copyOfRecords1 = records1.stream()
          .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
      allRecords.add(copyOfRecords1);
      header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
      HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records1, header);
      writer.appendBlock(dataBlock);
    }
    writer.close();
    FileCreateUtils.createDeltaCommit(basePath, "100", fs);
    // scan all log blocks (across multiple log files)
    HoodieMergedLogRecordScanner scanner = HoodieMergedLogRecordScanner.newBuilder()
        .withFileSystem(fs)
        .withBasePath(basePath)
        .withLogFilePaths(
            logFiles.stream()
                .map(logFile -> logFile.getPath().toString()).collect(Collectors.toList()))
        .withReaderSchema(schema)
        .withLatestInstantTime("100")
        .withMaxMemorySizeInBytes(10240L)
        .withReadBlocksLazily(readBlocksLazily)
        .withReverseReader(false)
        .withBufferSize(BUFFER_SIZE)
        .withSpillableMapBasePath(spillableBasePath)
        .withDiskMapType(diskMapType)
        .withBitCaskDiskMapCompressionEnabled(isCompressionEnabled)
        .withUseScanV2(useScanv2)
        .withRecordMerger(HoodieRecordUtils.loadRecordMerger(HoodieAvroRecordMerger.class.getName()))
        .build();

    List<IndexedRecord> scannedRecords = new ArrayList<>();
    for (HoodieRecord record : scanner) {
      scannedRecords.add((IndexedRecord)
          ((HoodieAvroRecord) record).getData().getInsertValue(schema).get());
    }

    assertEquals(scannedRecords.size(), allRecords.stream().mapToLong(Collection::size).sum(),
        "Scanner records count should be the same as appended records");
    scanner.close();
  }

  @Test
  public void testAppendAndReadOnCorruptedLog() throws IOException, URISyntaxException, InterruptedException {
    HoodieLogFile logFile = addValidBlock("test-fileId1", "100", 100);

    // Append some arbit byte[] to the end of the log (mimics a partially written commit)
    fs = FSUtils.getFs(fs.getUri().toString(), fs.getConf());
    FSDataOutputStream outputStream = fs.append(logFile.getPath());
    // create a block with
    outputStream.write(HoodieLogFormat.MAGIC);
    // Write out a length that does not confirm with the content
    outputStream.writeLong(474);
    outputStream.writeInt(HoodieLogBlockType.AVRO_DATA_BLOCK.ordinal());
    outputStream.writeInt(HoodieLogFormat.CURRENT_VERSION);
    // Write out a length that does not confirm with the content
    outputStream.writeLong(400);
    // Write out incomplete content
    outputStream.write("something-random".getBytes());
    outputStream.flush();
    outputStream.close();

    // Append a proper block that is of the missing length of the corrupted block
    logFile = addValidBlock("test-fileId1", "100", 10);

    // First round of reads - we should be able to read the first block and then EOF
    Reader reader = HoodieLogFormat.newReader(fs, logFile, SchemaTestUtil.getSimpleSchema());
    assertTrue(reader.hasNext(), "First block should be available");
    reader.next();
    assertTrue(reader.hasNext(), "We should have corrupted block next");
    HoodieLogBlock block = reader.next();
    assertEquals(HoodieLogBlockType.CORRUPT_BLOCK, block.getBlockType(), "The read block should be a corrupt block");
    assertTrue(reader.hasNext(), "Third block should be available");
    reader.next();
    assertFalse(reader.hasNext(), "There should be no more block left");

    reader.close();

    // Simulate another failure back to back
    outputStream = fs.append(logFile.getPath());
    // create a block with
    outputStream.write(HoodieLogFormat.MAGIC);
    // Write out a length that does not confirm with the content
    outputStream.writeLong(1000);
    outputStream.writeInt(HoodieLogBlockType.AVRO_DATA_BLOCK.ordinal());
    outputStream.writeInt(HoodieLogFormat.CURRENT_VERSION);
    // Write out a length that does not confirm with the content
    outputStream.writeLong(500);
    // Write out some bytes
    outputStream.write("something-else-random".getBytes());
    outputStream.flush();
    outputStream.close();

    // Should be able to append a new block
    logFile = addValidBlock("test-fileId1", "100", 100);

    // Second round of reads - we should be able to read the first and last block
    reader = HoodieLogFormat.newReader(fs, logFile, SchemaTestUtil.getSimpleSchema());
    assertTrue(reader.hasNext(), "First block should be available");
    reader.next();
    assertTrue(reader.hasNext(), "We should get the 1st corrupted block next");
    reader.next();
    assertTrue(reader.hasNext(), "Third block should be available");
    reader.next();
    assertTrue(reader.hasNext(), "We should get the 2nd corrupted block next");
    block = reader.next();
    assertEquals(HoodieLogBlockType.CORRUPT_BLOCK, block.getBlockType(), "The read block should be a corrupt block");
    assertTrue(reader.hasNext(), "We should get the last block next");
    reader.next();
    assertFalse(reader.hasNext(), "We should have no more blocks left");
    reader.close();
  }

  @Test
  public void testMissingBlockExceptMagicBytes() throws IOException, URISyntaxException, InterruptedException {
    HoodieLogFile logFile = addValidBlock("test-fileId1", "100", 100);

    // Append just magic bytes and move onto next block
    fs = FSUtils.getFs(fs.getUri().toString(), fs.getConf());
    FSDataOutputStream outputStream = fs.append(logFile.getPath());
    outputStream.write(HoodieLogFormat.MAGIC);
    outputStream.flush();
    outputStream.close();

    // Append a proper block
    logFile = addValidBlock("test-fileId1", "100", 10);

    // First round of reads - we should be able to read the first block and then EOF
    Reader reader = HoodieLogFormat.newReader(fs, logFile, SchemaTestUtil.getSimpleSchema());
    assertTrue(reader.hasNext(), "First block should be available");
    reader.next();
    assertTrue(reader.hasNext(), "We should have corrupted block next");
    HoodieLogBlock block = reader.next();
    assertEquals(HoodieLogBlockType.CORRUPT_BLOCK, block.getBlockType(), "The read block should be a corrupt block");
    assertTrue(reader.hasNext(), "Third block should be available");
    reader.next();
    assertFalse(reader.hasNext(), "There should be no more block left");

    reader.close();
  }

  private HoodieLogFile addValidBlock(String fileId, String commitTime, int numRecords) throws IOException, URISyntaxException, InterruptedException {
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId(fileId).overBaseCommit(commitTime).withFs(fs).build();
    List<IndexedRecord> records = SchemaTestUtil.generateTestRecords(0, numRecords);
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records, header);
    writer.appendBlock(dataBlock);
    writer.close();
    return writer.getLogFile();
  }

  @Test
  public void testValidateCorruptBlockEndPosition() throws IOException, URISyntaxException, InterruptedException {
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    List<IndexedRecord> records = SchemaTestUtil.generateTestRecords(0, 100);
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records, header);
    writer.appendBlock(dataBlock);
    writer.close();

    // Append some arbit byte[] to the end of the log (mimics a partially written commit)
    fs = FSUtils.getFs(fs.getUri().toString(), fs.getConf());
    FSDataOutputStream outputStream = fs.append(writer.getLogFile().getPath());
    // create a block with
    outputStream.write(HoodieLogFormat.MAGIC);
    // Write out a length that does not confirm with the content
    outputStream.writeLong(474);
    outputStream.writeInt(HoodieLogBlockType.AVRO_DATA_BLOCK.ordinal());
    outputStream.writeInt(HoodieLogFormat.CURRENT_VERSION);
    // Write out a length that does not confirm with the content
    outputStream.writeLong(400);
    // Write out incomplete content
    outputStream.write("something-random".getBytes());
    // get corrupt block end position
    long corruptBlockEndPos = outputStream.getPos();
    outputStream.flush();
    outputStream.close();

    // Append a proper block again
    writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    records = SchemaTestUtil.generateTestRecords(0, 10);
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, getSimpleSchema().toString());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records, header);
    writer.appendBlock(dataBlock);
    writer.close();

    // Read data and corrupt block
    Reader reader = HoodieLogFormat.newReader(fs, writer.getLogFile(), SchemaTestUtil.getSimpleSchema());
    assertTrue(reader.hasNext(), "First block should be available");
    reader.next();
    assertTrue(reader.hasNext(), "We should have corrupted block next");
    HoodieLogBlock block = reader.next();
    assertEquals(HoodieLogBlockType.CORRUPT_BLOCK, block.getBlockType(), "The read block should be a corrupt block");
    // validate the corrupt block end position correctly.
    assertEquals(corruptBlockEndPos, block.getBlockContentLocation().get().getBlockEndPos());
    assertTrue(reader.hasNext(), "Third block should be available");
    reader.next();
    assertFalse(reader.hasNext(), "There should be no more block left");

    reader.close();
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testAvroLogRecordReaderBasic(ExternalSpillableMap.DiskMapType diskMapType,
                                           boolean isCompressionEnabled,
                                           boolean readBlocksLazily,
                                           boolean useScanv2)
      throws IOException, URISyntaxException, InterruptedException {
    Schema schema = HoodieAvroUtils.addMetadataFields(getSimpleSchema());
    // Set a small threshold so that every block is a new version
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).withSizeThreshold(500).build();
    // Write 1
    List<IndexedRecord> records1 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords1 = records1.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());

    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records1, header);
    writer.appendBlock(dataBlock);

    // Write 2
    List<IndexedRecord> records2 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords2 = records2.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records2, header);
    writer.appendBlock(dataBlock);
    writer.close();

    List<String> allLogFiles =
        FSUtils.getAllLogFiles(fs, partitionPath, "test-fileid1", HoodieLogFile.DELTA_EXTENSION, "100")
            .map(s -> s.getPath().toString()).collect(Collectors.toList());

    FileCreateUtils.createDeltaCommit(basePath, "100", fs);

    HoodieMergedLogRecordScanner scanner = HoodieMergedLogRecordScanner.newBuilder()
        .withFileSystem(fs)
        .withBasePath(basePath)
        .withLogFilePaths(allLogFiles)
        .withReaderSchema(schema)
        .withLatestInstantTime("100")
        .withMaxMemorySizeInBytes(10240L)
        .withReadBlocksLazily(readBlocksLazily)
        .withReverseReader(false)
        .withBufferSize(BUFFER_SIZE)
        .withSpillableMapBasePath(spillableBasePath)
        .withDiskMapType(diskMapType)
        .withBitCaskDiskMapCompressionEnabled(isCompressionEnabled)
        .withUseScanV2(useScanv2)
        .withRecordMerger(HoodieRecordUtils.loadRecordMerger(HoodieAvroRecordMerger.class.getName()))
        .build();
    assertEquals(200, scanner.getTotalLogRecords());
    Set<String> readKeys = new HashSet<>(200);
    scanner.forEach(s -> readKeys.add(s.getKey().getRecordKey()));
    assertEquals(200, readKeys.size(), "Stream collect should return all 200 records");
    copyOfRecords1.addAll(copyOfRecords2);
    Set<String> originalKeys =
        copyOfRecords1.stream().map(s -> ((GenericRecord) s).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString())
            .collect(Collectors.toSet());
    assertEquals(originalKeys, readKeys, "CompositeAvroLogReader should return 200 records from 2 versions");
    scanner.close();
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testAvroLogRecordReaderWithRollbackTombstone(ExternalSpillableMap.DiskMapType diskMapType,
                                                           boolean isCompressionEnabled,
                                                           boolean readBlocksLazily,
                                                           boolean useScanv2)
      throws IOException, URISyntaxException, InterruptedException {
    Schema schema = HoodieAvroUtils.addMetadataFields(getSimpleSchema());
    // Set a small threshold so that every block is a new version
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();

    // Write 1
    List<IndexedRecord> records1 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords1 = records1.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");

    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records1, header);
    writer.appendBlock(dataBlock);

    // Write 2
    header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "101");
    List<IndexedRecord> records2 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records2, header);
    writer.appendBlock(dataBlock);

    // Rollback the last write
    header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.TARGET_INSTANT_TIME, "101");
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "102");
    header.put(HoodieLogBlock.HeaderMetadataType.COMMAND_BLOCK_TYPE,
        String.valueOf(HoodieCommandBlock.HoodieCommandBlockTypeEnum.ROLLBACK_BLOCK.ordinal()));
    HoodieCommandBlock commandBlock = new HoodieCommandBlock(header);
    writer.appendBlock(commandBlock);

    // Write 3
    header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "102");
    List<IndexedRecord> records3 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords3 = records3.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records3, header);
    writer.appendBlock(dataBlock);
    writer.close();

    List<String> allLogFiles =
        FSUtils.getAllLogFiles(fs, partitionPath, "test-fileid1", HoodieLogFile.DELTA_EXTENSION, "100")
            .map(s -> s.getPath().toString()).collect(Collectors.toList());

    FileCreateUtils.createDeltaCommit(basePath, "100", fs);
    FileCreateUtils.createDeltaCommit(basePath, "102", fs);
    HoodieMergedLogRecordScanner scanner = HoodieMergedLogRecordScanner.newBuilder()
        .withFileSystem(fs)
        .withBasePath(basePath)
        .withLogFilePaths(allLogFiles)
        .withReaderSchema(schema)
        .withLatestInstantTime("102")
        .withMaxMemorySizeInBytes(10240L)
        .withReadBlocksLazily(readBlocksLazily)
        .withReverseReader(false)
        .withBufferSize(BUFFER_SIZE)
        .withSpillableMapBasePath(spillableBasePath)
        .withDiskMapType(diskMapType)
        .withBitCaskDiskMapCompressionEnabled(isCompressionEnabled)
        .withUseScanV2(useScanv2)
        .withRecordMerger(HoodieRecordUtils.loadRecordMerger(HoodieAvroRecordMerger.class.getName()))
        .build();
    assertEquals(200, scanner.getTotalLogRecords(), "We read 200 records from 2 write batches");
    Set<String> readKeys = new HashSet<>(200);
    scanner.forEach(s -> readKeys.add(s.getKey().getRecordKey()));
    assertEquals(200, readKeys.size(), "Stream collect should return all 200 records");
    copyOfRecords1.addAll(copyOfRecords3);
    Set<String> originalKeys =
        copyOfRecords1.stream().map(s -> ((GenericRecord) s).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString())
            .collect(Collectors.toSet());
    assertEquals(originalKeys, readKeys, "CompositeAvroLogReader should return 200 records from 2 versions");
    scanner.close();
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testAvroLogRecordReaderWithFailedPartialBlock(ExternalSpillableMap.DiskMapType diskMapType,
                                                            boolean isCompressionEnabled,
                                                            boolean useScanv2)
      throws IOException, URISyntaxException, InterruptedException {
    Schema schema = HoodieAvroUtils.addMetadataFields(getSimpleSchema());
    // Set a small threshold so that every block is a new version
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();

    // Write 1
    List<IndexedRecord> records1 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords1 = records1.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records1, header);
    writer.appendBlock(dataBlock);
    writer.close();

    // Write 2
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "101");
    // Append some arbit byte[] to the end of the log (mimics a partially written commit)
    fs = FSUtils.getFs(fs.getUri().toString(), fs.getConf());
    FSDataOutputStream outputStream = fs.append(writer.getLogFile().getPath());
    // create a block with
    outputStream.write(HoodieLogFormat.MAGIC);
    // Write out a length that does not confirm with the content
    outputStream.writeLong(1000);

    outputStream.writeInt(HoodieLogFormat.CURRENT_VERSION);
    outputStream.writeInt(HoodieLogBlockType.AVRO_DATA_BLOCK.ordinal());

    // Write out some header
    outputStream.write(HoodieLogBlock.getLogMetadataBytes(header));
    outputStream.writeLong("something-random".getBytes().length);
    outputStream.write("something-random".getBytes());
    outputStream.flush();
    outputStream.close();

    writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    // Write 3
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "103");
    List<IndexedRecord> records3 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords3 = records3.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());

    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records3, header);
    writer.appendBlock(dataBlock);
    writer.close();

    List<String> allLogFiles =
        FSUtils.getAllLogFiles(fs, partitionPath, "test-fileid1", HoodieLogFile.DELTA_EXTENSION, "100")
            .map(s -> s.getPath().toString()).collect(Collectors.toList());

    FileCreateUtils.createDeltaCommit(basePath, "100", fs);
    FileCreateUtils.createDeltaCommit(basePath, "103", fs);
    HoodieMergedLogRecordScanner scanner = HoodieMergedLogRecordScanner.newBuilder()
        .withFileSystem(fs)
        .withBasePath(basePath)
        .withLogFilePaths(allLogFiles)
        .withReaderSchema(schema)
        .withLatestInstantTime("103")
        .withMaxMemorySizeInBytes(10240L)
        .withReadBlocksLazily(true)
        .withReverseReader(false)
        .withBufferSize(BUFFER_SIZE)
        .withSpillableMapBasePath(spillableBasePath)
        .withDiskMapType(diskMapType)
        .withBitCaskDiskMapCompressionEnabled(isCompressionEnabled)
        .withUseScanV2(useScanv2)
        .withRecordMerger(HoodieRecordUtils.loadRecordMerger(HoodieAvroRecordMerger.class.getName()))
        .build();
    assertEquals(200, scanner.getTotalLogRecords(), "We would read 200 records");
    Set<String> readKeys = new HashSet<>(200);
    scanner.forEach(s -> readKeys.add(s.getKey().getRecordKey()));
    assertEquals(200, readKeys.size(), "Stream collect should return all 200 records");
    copyOfRecords1.addAll(copyOfRecords3);
    Set<String> originalKeys =
        copyOfRecords1.stream().map(s -> ((GenericRecord) s).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString())
            .collect(Collectors.toSet());
    assertEquals(originalKeys, readKeys, "CompositeAvroLogReader should return 200 records from 2 versions");
    scanner.close();
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testAvroLogRecordReaderWithDeleteAndRollback(ExternalSpillableMap.DiskMapType diskMapType,
                                                           boolean isCompressionEnabled,
                                                           boolean readBlocksLazily,
                                                           boolean useScanv2)
      throws IOException, URISyntaxException, InterruptedException {
    Schema schema = HoodieAvroUtils.addMetadataFields(getSimpleSchema());
    // Set a small threshold so that every block is a new version
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();

    // Write 1
    List<IndexedRecord> records1 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords1 = records1.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records1, header);
    writer.appendBlock(dataBlock);

    // Write 2
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "101");
    List<IndexedRecord> records2 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords2 = records2.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records2, header);
    writer.appendBlock(dataBlock);

    // Delete 50 keys
    List<DeleteRecord> deletedRecords = copyOfRecords1.stream()
        .map(s -> (DeleteRecord.create(((GenericRecord) s).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString(),
            ((GenericRecord) s).get(HoodieRecord.PARTITION_PATH_METADATA_FIELD).toString())))
        .collect(Collectors.toList()).subList(0, 50);

    copyOfRecords2.addAll(copyOfRecords1);
    List<String> originalKeys =
        copyOfRecords2.stream().map(s -> ((GenericRecord) s).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString())
            .collect(Collectors.toList());

    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "102");
    HoodieDeleteBlock deleteBlock = new HoodieDeleteBlock(deletedRecords.toArray(new DeleteRecord[50]), header);
    writer.appendBlock(deleteBlock);

    List<String> allLogFiles =
        FSUtils.getAllLogFiles(fs, partitionPath, "test-fileid1", HoodieLogFile.DELTA_EXTENSION, "100")
            .map(s -> s.getPath().toString()).collect(Collectors.toList());

    FileCreateUtils.createDeltaCommit(basePath, "100", fs);
    FileCreateUtils.createDeltaCommit(basePath, "101", fs);
    FileCreateUtils.createDeltaCommit(basePath, "102", fs);

    HoodieMergedLogRecordScanner scanner = HoodieMergedLogRecordScanner.newBuilder()
        .withFileSystem(fs)
        .withBasePath(basePath)
        .withLogFilePaths(allLogFiles)
        .withReaderSchema(schema)
        .withLatestInstantTime("102")
        .withMaxMemorySizeInBytes(10240L)
        .withReadBlocksLazily(readBlocksLazily)
        .withReverseReader(false)
        .withBufferSize(BUFFER_SIZE)
        .withSpillableMapBasePath(spillableBasePath)
        .withDiskMapType(diskMapType)
        .withBitCaskDiskMapCompressionEnabled(isCompressionEnabled)
        .withUseScanV2(useScanv2)
        .withRecordMerger(HoodieRecordUtils.loadRecordMerger(HoodieAvroRecordMerger.class.getName()))
        .build();

    assertEquals(200, scanner.getTotalLogRecords(), "We still would read 200 records");
    final List<String> readKeys = new ArrayList<>(200);
    final List<Boolean> emptyPayloads = new ArrayList<>();
    scanner.forEach(s -> readKeys.add(s.getKey().getRecordKey()));
    scanner.forEach(s -> {
      try {
        if (!((HoodieRecordPayload)s.getData()).getInsertValue(schema).isPresent()) {
          emptyPayloads.add(true);
        }
      } catch (IOException io) {
        throw new UncheckedIOException(io);
      }
    });
    assertEquals(200, readKeys.size(), "Stream collect should return all 200 records");
    assertEquals(50, emptyPayloads.size(), "Stream collect should return all 50 records with empty payloads");
    originalKeys.removeAll(deletedRecords);
    Collections.sort(originalKeys);
    Collections.sort(readKeys);
    assertEquals(originalKeys, readKeys, "CompositeAvroLogReader should return 150 records from 2 versions");

    // Rollback the 1st block i.e. a data block.
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "103");
    header.put(HoodieLogBlock.HeaderMetadataType.TARGET_INSTANT_TIME, "101");
    header.put(HoodieLogBlock.HeaderMetadataType.COMMAND_BLOCK_TYPE,
        String.valueOf(HoodieCommandBlock.HoodieCommandBlockTypeEnum.ROLLBACK_BLOCK.ordinal()));
    HoodieCommandBlock commandBlock = new HoodieCommandBlock(header);
    writer.appendBlock(commandBlock);

    FileCreateUtils.deleteDeltaCommit(basePath, "101", fs);

    readKeys.clear();
    scanner.close();
    scanner = HoodieMergedLogRecordScanner.newBuilder()
        .withFileSystem(fs)
        .withBasePath(basePath)
        .withLogFilePaths(allLogFiles)
        .withReaderSchema(schema)
        .withLatestInstantTime("103")
        .withMaxMemorySizeInBytes(10240L)
        .withReadBlocksLazily(readBlocksLazily)
        .withReverseReader(false)
        .withBufferSize(BUFFER_SIZE)
        .withSpillableMapBasePath(spillableBasePath)
        .withDiskMapType(diskMapType)
        .withBitCaskDiskMapCompressionEnabled(isCompressionEnabled)
        .withRecordMerger(HoodieRecordUtils.loadRecordMerger(HoodieAvroRecordMerger.class.getName()))
        .build();
    scanner.forEach(s -> readKeys.add(s.getKey().getRecordKey()));
    assertEquals(100, readKeys.size(), "Stream collect should return all 200 records after rollback of delete");
    final List<Boolean> newEmptyPayloads = new ArrayList<>();
    scanner.forEach(s -> {
      try {
        if (!((HoodieRecordPayload) s.getData()).getInsertValue(schema).isPresent()) {
          newEmptyPayloads.add(true);
        }
      } catch (IOException io) {
        throw new UncheckedIOException(io);
      }
    });
    assertEquals(100, readKeys.size(), "Stream collect should return 100 records, since 2nd block is rolled back");
    assertEquals(50, newEmptyPayloads.size(), "Stream collect should return all 50 records with empty payloads");
    List<String> firstBlockRecords =
        copyOfRecords1.stream().map(s -> ((GenericRecord) s).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString())
            .collect(Collectors.toList());
    Collections.sort(firstBlockRecords);
    Collections.sort(readKeys);
    assertEquals(firstBlockRecords, readKeys, "CompositeAvroLogReader should return 150 records from 2 versions");
    writer.close();
    scanner.close();
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testAvroLogRecordReaderWithDisorderDelete(ExternalSpillableMap.DiskMapType diskMapType,
                                                        boolean isCompressionEnabled,
                                                        boolean readBlocksLazily)
      throws IOException, URISyntaxException, InterruptedException {
    Schema schema = HoodieAvroUtils.addMetadataFields(getSimpleSchema());
    // Set a small threshold so that every block is a new version
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();

    // Write 1
    List<IndexedRecord> records1 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords1 = records1.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records1, header);
    writer.appendBlock(dataBlock);

    // Write 2
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "101");
    List<IndexedRecord> records2 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords2 = records2.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records2, header);
    writer.appendBlock(dataBlock);

    copyOfRecords1.addAll(copyOfRecords2);
    List<String> originalKeys =
        copyOfRecords1.stream().map(s -> ((GenericRecord) s).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString())
            .collect(Collectors.toList());

    // Delete 10 keys
    // Default orderingVal is 0, which means natural order, the DELETE records
    // should overwrite the data records.
    List<DeleteRecord> deleteRecords1 = copyOfRecords1.subList(0, 10).stream()
        .map(s -> (DeleteRecord.create(((GenericRecord) s).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString(),
            ((GenericRecord) s).get(HoodieRecord.PARTITION_PATH_METADATA_FIELD).toString())))
        .collect(Collectors.toList());

    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "102");
    HoodieDeleteBlock deleteBlock1 = new HoodieDeleteBlock(deleteRecords1.toArray(new DeleteRecord[0]), header);
    writer.appendBlock(deleteBlock1);

    // Delete another 10 keys with -1 as orderingVal.
    // The deletion should not work

    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "103");
    HoodieDeleteBlock deleteBlock2 = new HoodieDeleteBlock(copyOfRecords1.subList(10, 20).stream()
        .map(s -> (DeleteRecord.create(((GenericRecord) s).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString(),
            ((GenericRecord) s).get(HoodieRecord.PARTITION_PATH_METADATA_FIELD).toString(), -1))).toArray(DeleteRecord[]::new), header);
    writer.appendBlock(deleteBlock2);

    // Delete another 10 keys with +1 as orderingVal.
    // The deletion should work because the keys has greater ordering value.
    List<DeleteRecord> deletedRecords3 = copyOfRecords1.subList(20, 30).stream()
        .map(s -> (DeleteRecord.create(((GenericRecord) s).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString(),
            ((GenericRecord) s).get(HoodieRecord.PARTITION_PATH_METADATA_FIELD).toString(), 1)))
        .collect(Collectors.toList());

    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "104");
    HoodieDeleteBlock deleteBlock3 = new HoodieDeleteBlock(deletedRecords3.toArray(new DeleteRecord[0]), header);
    writer.appendBlock(deleteBlock3);

    List<String> allLogFiles =
        FSUtils.getAllLogFiles(fs, partitionPath, "test-fileid1", HoodieLogFile.DELTA_EXTENSION, "100")
            .map(s -> s.getPath().toString()).collect(Collectors.toList());

    FileCreateUtils.createDeltaCommit(basePath, "100", fs);
    FileCreateUtils.createDeltaCommit(basePath, "101", fs);
    FileCreateUtils.createDeltaCommit(basePath, "102", fs);
    FileCreateUtils.createDeltaCommit(basePath, "103", fs);
    FileCreateUtils.createDeltaCommit(basePath, "104", fs);

    HoodieMergedLogRecordScanner scanner = HoodieMergedLogRecordScanner.newBuilder()
        .withFileSystem(fs)
        .withBasePath(basePath)
        .withLogFilePaths(allLogFiles)
        .withReaderSchema(schema)
        .withLatestInstantTime("104")
        .withMaxMemorySizeInBytes(10240L)
        .withReadBlocksLazily(readBlocksLazily)
        .withReverseReader(false)
        .withBufferSize(BUFFER_SIZE)
        .withSpillableMapBasePath(spillableBasePath)
        .withDiskMapType(diskMapType)
        .withBitCaskDiskMapCompressionEnabled(isCompressionEnabled)
        .withRecordMerger(HoodieRecordUtils.loadRecordMerger(HoodieAvroRecordMerger.class.getName()))
        .build();

    assertEquals(200, scanner.getTotalLogRecords(), "We still would read 200 records");
    final List<String> readKeys = new ArrayList<>(200);
    final List<String> emptyPayloadKeys = new ArrayList<>();
    scanner.forEach(s -> readKeys.add(s.getRecordKey()));
    scanner.forEach(s -> {
      try {
        if (!((HoodieRecordPayload)s.getData()).getInsertValue(schema).isPresent()) {
          emptyPayloadKeys.add(s.getRecordKey());
        }
      } catch (IOException io) {
        throw new UncheckedIOException(io);
      }
    });
    assertEquals(200, readKeys.size(), "Stream collect should return all 200 records");
    assertEquals(20, emptyPayloadKeys.size(), "Stream collect should return all 20 records with empty payloads");

    originalKeys.removeAll(deleteRecords1.stream().map(DeleteRecord::getRecordKey).collect(Collectors.toSet()));
    originalKeys.removeAll(deletedRecords3.stream().map(DeleteRecord::getRecordKey).collect(Collectors.toSet()));
    readKeys.removeAll(emptyPayloadKeys);

    Collections.sort(originalKeys);
    Collections.sort(readKeys);
    assertEquals(originalKeys, readKeys, "HoodieMergedLogRecordScanner should return 180 records from 4 versions");
    writer.close();
    scanner.close();
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testAvroLogRecordReaderWithFailedRollbacks(ExternalSpillableMap.DiskMapType diskMapType,
                                                         boolean isCompressionEnabled,
                                                         boolean readBlocksLazily,
                                                         boolean useScanv2)
      throws IOException, URISyntaxException, InterruptedException {

    // Write a Data block and Delete block with same InstantTime (written in same batch)
    Schema schema = HoodieAvroUtils.addMetadataFields(getSimpleSchema());
    // Set a small threshold so that every block is a new version
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();

    // Write 1
    List<IndexedRecord> records1 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords1 = records1.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.TARGET_INSTANT_TIME, "100");

    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records1, header);
    writer.appendBlock(dataBlock);

    // Write 2
    List<IndexedRecord> records2 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records2, header);
    writer.appendBlock(dataBlock);

    // Delete 50 keys
    // Delete 50 keys
    List<DeleteRecord> deleteRecords = copyOfRecords1.stream()
        .map(s -> (DeleteRecord.create(((GenericRecord) s).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString(),
            ((GenericRecord) s).get(HoodieRecord.PARTITION_PATH_METADATA_FIELD).toString())))
        .collect(Collectors.toList()).subList(0, 50);

    HoodieDeleteBlock deleteBlock = new HoodieDeleteBlock(deleteRecords.toArray(new DeleteRecord[50]), header);
    writer.appendBlock(deleteBlock);

    FileCreateUtils.createDeltaCommit(basePath, "100", fs);

    // Attempt 1 : Write rollback block for a failed write
    header.put(HoodieLogBlock.HeaderMetadataType.COMMAND_BLOCK_TYPE,
        String.valueOf(HoodieCommandBlock.HoodieCommandBlockTypeEnum.ROLLBACK_BLOCK.ordinal()));
    HoodieCommandBlock commandBlock = new HoodieCommandBlock(header);
    try {
      writer.appendBlock(commandBlock);
      // Say job failed, retry writing 2 rollback in the next rollback(..) attempt
      throw new Exception("simulating failure");
    } catch (Exception e) {
      // it's okay
    }
    // Attempt 2 : Write another rollback blocks for a failed write
    writer.appendBlock(commandBlock);

    List<String> allLogFiles =
        FSUtils.getAllLogFiles(fs, partitionPath, "test-fileid1", HoodieLogFile.DELTA_EXTENSION, "100")
            .map(s -> s.getPath().toString()).collect(Collectors.toList());

    // all data must be rolled back before merge
    HoodieMergedLogRecordScanner scanner = HoodieMergedLogRecordScanner.newBuilder()
        .withFileSystem(fs)
        .withBasePath(basePath)
        .withLogFilePaths(allLogFiles)
        .withReaderSchema(schema)
        .withLatestInstantTime("100")
        .withMaxMemorySizeInBytes(10240L)
        .withReadBlocksLazily(readBlocksLazily)
        .withReverseReader(false)
        .withBufferSize(BUFFER_SIZE)
        .withSpillableMapBasePath(spillableBasePath)
        .withDiskMapType(diskMapType)
        .withBitCaskDiskMapCompressionEnabled(isCompressionEnabled)
        .withUseScanV2(useScanv2)
        .withRecordMerger(HoodieRecordUtils.loadRecordMerger(HoodieAvroRecordMerger.class.getName()))
        .build();
    assertEquals(0, scanner.getTotalLogRecords(), "We would have scanned 0 records because of rollback");

    final List<String> readKeys = new ArrayList<>();
    scanner.forEach(s -> readKeys.add(s.getKey().getRecordKey()));
    assertEquals(0, readKeys.size(), "Stream collect should return all 0 records");
    FileCreateUtils.deleteDeltaCommit(basePath, "100", fs);
    writer.close();
    scanner.close();
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testAvroLogRecordReaderWithInsertDeleteAndRollback(ExternalSpillableMap.DiskMapType diskMapType,
                                                                 boolean isCompressionEnabled,
                                                                 boolean readBlocksLazily,
                                                                 boolean useScanv2)
      throws IOException, URISyntaxException, InterruptedException {

    // Write a Data block and Delete block with same InstantTime (written in same batch)
    Schema schema = HoodieAvroUtils.addMetadataFields(getSimpleSchema());
    // Set a small threshold so that every block is a new version
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();

    // Write 1
    List<IndexedRecord> records1 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords1 = records1.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.TARGET_INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records1, header);
    writer.appendBlock(dataBlock);

    // Delete 50 keys
    List<DeleteRecord> deleteRecords = copyOfRecords1.stream()
        .map(s -> (DeleteRecord.create(((GenericRecord) s).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString(),
            ((GenericRecord) s).get(HoodieRecord.PARTITION_PATH_METADATA_FIELD).toString())))
        .collect(Collectors.toList()).subList(0, 50);
    HoodieDeleteBlock deleteBlock = new HoodieDeleteBlock(deleteRecords.toArray(new DeleteRecord[50]), header);
    writer.appendBlock(deleteBlock);

    FileCreateUtils.createDeltaCommit(basePath, "100", fs);

    // Write 2 rollback blocks (1 data block + 1 delete bloc) for a failed write
    header.put(HoodieLogBlock.HeaderMetadataType.COMMAND_BLOCK_TYPE,
        String.valueOf(HoodieCommandBlock.HoodieCommandBlockTypeEnum.ROLLBACK_BLOCK.ordinal()));
    HoodieCommandBlock commandBlock = new HoodieCommandBlock(header);
    writer.appendBlock(commandBlock);
    writer.appendBlock(commandBlock);

    List<String> allLogFiles =
        FSUtils.getAllLogFiles(fs, partitionPath, "test-fileid1", HoodieLogFile.DELTA_EXTENSION, "100")
            .map(s -> s.getPath().toString()).collect(Collectors.toList());

    HoodieMergedLogRecordScanner scanner = HoodieMergedLogRecordScanner.newBuilder()
        .withFileSystem(fs)
        .withBasePath(basePath)
        .withLogFilePaths(allLogFiles)
        .withReaderSchema(schema)
        .withLatestInstantTime("100")
        .withMaxMemorySizeInBytes(10240L)
        .withReadBlocksLazily(readBlocksLazily)
        .withReverseReader(false)
        .withBufferSize(BUFFER_SIZE)
        .withSpillableMapBasePath(spillableBasePath)
        .withDiskMapType(diskMapType)
        .withBitCaskDiskMapCompressionEnabled(isCompressionEnabled)
        .withUseScanV2(useScanv2)
        .withRecordMerger(HoodieRecordUtils.loadRecordMerger(HoodieAvroRecordMerger.class.getName()))
        .build();
    assertEquals(0, scanner.getTotalLogRecords(), "We would read 0 records");
    FileCreateUtils.deleteDeltaCommit(basePath, "100", fs);
    writer.close();
    scanner.close();
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testAvroLogRecordReaderWithInvalidRollback(ExternalSpillableMap.DiskMapType diskMapType,
                                                         boolean isCompressionEnabled,
                                                         boolean readBlocksLazily,
                                                         boolean useScanv2)
      throws IOException, URISyntaxException, InterruptedException {
    Schema schema = HoodieAvroUtils.addMetadataFields(getSimpleSchema());
    // Set a small threshold so that every block is a new version
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();

    // Write 1
    List<IndexedRecord> records1 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records1, header);
    writer.appendBlock(dataBlock);

    FileCreateUtils.createDeltaCommit(basePath, "100", fs);

    // Write invalid rollback for a failed write (possible for in-flight commits)
    header.put(HoodieLogBlock.HeaderMetadataType.TARGET_INSTANT_TIME, "101");
    header.put(HoodieLogBlock.HeaderMetadataType.COMMAND_BLOCK_TYPE,
        String.valueOf(HoodieCommandBlock.HoodieCommandBlockTypeEnum.ROLLBACK_BLOCK.ordinal()));
    HoodieCommandBlock commandBlock = new HoodieCommandBlock(header);
    writer.appendBlock(commandBlock);

    List<String> allLogFiles =
        FSUtils.getAllLogFiles(fs, partitionPath, "test-fileid1", HoodieLogFile.DELTA_EXTENSION, "100")
            .map(s -> s.getPath().toString()).collect(Collectors.toList());

    HoodieMergedLogRecordScanner scanner = HoodieMergedLogRecordScanner.newBuilder()
        .withFileSystem(fs)
        .withBasePath(basePath)
        .withLogFilePaths(allLogFiles)
        .withReaderSchema(schema)
        .withLatestInstantTime("100")
        .withMaxMemorySizeInBytes(10240L)
        .withReadBlocksLazily(readBlocksLazily)
        .withReverseReader(false)
        .withBufferSize(BUFFER_SIZE)
        .withSpillableMapBasePath(spillableBasePath)
        .withDiskMapType(diskMapType)
        .withBitCaskDiskMapCompressionEnabled(isCompressionEnabled)
        .withUseScanV2(useScanv2)
        .withRecordMerger(HoodieRecordUtils.loadRecordMerger(HoodieAvroRecordMerger.class.getName()))
        .build();
    assertEquals(100, scanner.getTotalLogRecords(), "We still would read 100 records");
    final List<String> readKeys = new ArrayList<>(100);
    scanner.forEach(s -> readKeys.add(s.getKey().getRecordKey()));
    assertEquals(100, readKeys.size(), "Stream collect should return all 150 records");
    writer.close();
    scanner.close();
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testAvroLogRecordReaderWithInsertsDeleteAndRollback(ExternalSpillableMap.DiskMapType diskMapType,
                                                                  boolean isCompressionEnabled,
                                                                  boolean readBlocksLazily,
                                                                  boolean useScanv2)
      throws IOException, URISyntaxException, InterruptedException {

    // Write a 3 Data blocs with same InstantTime (written in same batch)
    Schema schema = HoodieAvroUtils.addMetadataFields(getSimpleSchema());
    // Set a small threshold so that every block is a new version
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();

    // Write 1
    List<IndexedRecord> records1 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords1 = records1.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.TARGET_INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records1, header);
    writer.appendBlock(dataBlock);
    writer.appendBlock(dataBlock);
    writer.appendBlock(dataBlock);

    // Delete 50 keys
    // Delete 50 keys
    List<DeleteRecord> deleteRecords = copyOfRecords1.stream()
        .map(s -> (DeleteRecord.create(((GenericRecord) s).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString(),
            ((GenericRecord) s).get(HoodieRecord.PARTITION_PATH_METADATA_FIELD).toString())))
        .collect(Collectors.toList()).subList(0, 50);
    HoodieDeleteBlock deleteBlock = new HoodieDeleteBlock(deleteRecords.toArray(new DeleteRecord[50]), header);
    writer.appendBlock(deleteBlock);

    FileCreateUtils.createDeltaCommit(basePath, "100", fs);

    // Write 1 rollback block for a failed write
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "101");
    header.put(HeaderMetadataType.TARGET_INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.COMMAND_BLOCK_TYPE,
        String.valueOf(HoodieCommandBlock.HoodieCommandBlockTypeEnum.ROLLBACK_BLOCK.ordinal()));
    HoodieCommandBlock commandBlock = new HoodieCommandBlock(header);
    writer.appendBlock(commandBlock);

    List<String> allLogFiles =
        FSUtils.getAllLogFiles(fs, partitionPath, "test-fileid1", HoodieLogFile.DELTA_EXTENSION, "100")
            .map(s -> s.getPath().toString()).collect(Collectors.toList());

    HoodieMergedLogRecordScanner scanner = HoodieMergedLogRecordScanner.newBuilder()
        .withFileSystem(fs)
        .withBasePath(basePath)
        .withLogFilePaths(allLogFiles)
        .withReaderSchema(schema)
        .withLatestInstantTime("101")
        .withMaxMemorySizeInBytes(10240L)
        .withReadBlocksLazily(readBlocksLazily)
        .withReverseReader(false)
        .withBufferSize(BUFFER_SIZE)
        .withSpillableMapBasePath(spillableBasePath)
        .withDiskMapType(diskMapType)
        .withBitCaskDiskMapCompressionEnabled(isCompressionEnabled)
        .withUseScanV2(useScanv2)
        .withRecordMerger(HoodieRecordUtils.loadRecordMerger(HoodieAvroRecordMerger.class.getName()))
        .build();
    assertEquals(0, scanner.getTotalLogRecords(), "We would read 0 records");
    writer.close();
    scanner.close();
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testAvroLogRecordReaderWithMixedInsertsCorruptsAndRollback(ExternalSpillableMap.DiskMapType diskMapType,
                                                                         boolean isCompressionEnabled,
                                                                         boolean readBlocksLazily,
                                                                         boolean useScanv2)
      throws IOException, URISyntaxException, InterruptedException {

    // Write a 3 Data blocs with same InstantTime (written in same batch)
    Schema schema = HoodieAvroUtils.addMetadataFields(getSimpleSchema());
    // Set a small threshold so that every block is a new version
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();

    // Write 1
    List<IndexedRecord> records1 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records1, header);
    writer.appendBlock(dataBlock);
    writer.appendBlock(dataBlock);
    writer.appendBlock(dataBlock);

    writer.close();
    FileCreateUtils.createDeltaCommit(basePath, "100", fs);

    // Append some arbit byte[] to the end of the log (mimics a partially written commit)
    fs = FSUtils.getFs(fs.getUri().toString(), fs.getConf());
    FSDataOutputStream outputStream = fs.append(writer.getLogFile().getPath());
    // create a block with
    outputStream.write(HoodieLogFormat.MAGIC);
    outputStream.writeLong(1000);
    outputStream.writeInt(HoodieLogBlockType.AVRO_DATA_BLOCK.ordinal());
    outputStream.writeInt(HoodieLogFormat.CURRENT_VERSION);
    // Write out a length that does not confirm with the content
    outputStream.writeLong(100);
    outputStream.flush();
    outputStream.close();

    // Append some arbit byte[] to the end of the log (mimics a partially written commit)
    fs = FSUtils.getFs(fs.getUri().toString(), fs.getConf());
    outputStream = fs.append(writer.getLogFile().getPath());
    // create a block with
    outputStream.write(HoodieLogFormat.MAGIC);
    outputStream.writeLong(1000);
    outputStream.writeInt(HoodieLogBlockType.AVRO_DATA_BLOCK.ordinal());
    outputStream.writeInt(HoodieLogFormat.CURRENT_VERSION);
    // Write out a length that does not confirm with the content
    outputStream.writeLong(100);
    outputStream.flush();
    outputStream.close();

    writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();

    writer.appendBlock(dataBlock);
    writer.close();

    // Append some arbit byte[] to the end of the log (mimics a partially written commit)
    fs = FSUtils.getFs(fs.getUri().toString(), fs.getConf());
    outputStream = fs.append(writer.getLogFile().getPath());
    // create a block with
    outputStream.write(HoodieLogFormat.MAGIC);
    outputStream.writeLong(1000);
    outputStream.writeInt(HoodieLogBlockType.AVRO_DATA_BLOCK.ordinal());
    outputStream.writeInt(HoodieLogFormat.CURRENT_VERSION);
    // Write out a length that does not confirm with the content
    outputStream.writeLong(100);
    outputStream.flush();
    outputStream.close();

    writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    // Write 1 rollback block for the last commit instant
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "101");
    header.put(HeaderMetadataType.TARGET_INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.COMMAND_BLOCK_TYPE,
        String.valueOf(HoodieCommandBlock.HoodieCommandBlockTypeEnum.ROLLBACK_BLOCK.ordinal()));
    HoodieCommandBlock commandBlock = new HoodieCommandBlock(header);
    writer.appendBlock(commandBlock);
    writer.close();

    List<String> allLogFiles =
        FSUtils.getAllLogFiles(fs, partitionPath, "test-fileid1", HoodieLogFile.DELTA_EXTENSION, "100")
            .map(s -> s.getPath().toString()).collect(Collectors.toList());

    HoodieMergedLogRecordScanner scanner = HoodieMergedLogRecordScanner.newBuilder()
        .withFileSystem(fs)
        .withBasePath(basePath)
        .withLogFilePaths(allLogFiles)
        .withReaderSchema(schema)
        .withLatestInstantTime("101")
        .withMaxMemorySizeInBytes(10240L)
        .withReadBlocksLazily(readBlocksLazily)
        .withReverseReader(false)
        .withBufferSize(BUFFER_SIZE)
        .withSpillableMapBasePath(spillableBasePath)
        .withDiskMapType(diskMapType)
        .withBitCaskDiskMapCompressionEnabled(isCompressionEnabled)
        .withUseScanV2(useScanv2)
        .withRecordMerger(HoodieRecordUtils.loadRecordMerger(HoodieAvroRecordMerger.class.getName()))
        .build();
    assertEquals(0, scanner.getTotalLogRecords(), "We would read 0 records");
    FileCreateUtils.deleteDeltaCommit(basePath, "100", fs);
    scanner.close();
  }

  @ParameterizedTest
  @MethodSource("testArgumentsWithoutScanV2Arg")
  public void testAvroLogRecordReaderWithMixedInsertsCorruptsRollbackAndMergedLogBlock(ExternalSpillableMap.DiskMapType diskMapType,
                                                                                       boolean isCompressionEnabled,
                                                                                       boolean readBlocksLazily)
      throws IOException, URISyntaxException, InterruptedException {

    // Write blocks in this manner.
    // Ex: B1(i1), B2(i2), B3(i3), CRPB, CRPB, CB4(i4, [i1,i2]), CB5(i5, [CB4, B3]), B6(i6), B7(i7), B8(i8), CB9(i9, [B7, B8])
    // CRPB implies a corrupt block and CB implies a compacted block.

    // Write a 3 Data blocks with same InstantTime (written in same batch)
    Schema schema = HoodieAvroUtils.addMetadataFields(getSimpleSchema());
    // Set a small threshold so that every block is a new version
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();

    // Write 1st data blocks multiple times.
    List<IndexedRecord> records1 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    Set<String> recordKeysOfFirstTwoBatches = records1.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)
            .get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString()).collect(Collectors.toSet());
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, new ArrayList<>(records1), header);
    writer.appendBlock(dataBlock);

    FileCreateUtils.createDeltaCommit(basePath, "100", fs);

    // Write 2nd data block
    List<IndexedRecord> records2 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    recordKeysOfFirstTwoBatches.addAll(records2.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)
            .get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString()).collect(Collectors.toList()));
    header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "101");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, new ArrayList<>(records2), header);
    writer.appendBlock(dataBlock);

    FileCreateUtils.createDeltaCommit(basePath, "101", fs);

    // Write 3rd data block
    List<IndexedRecord> records3 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    Set<String> recordKeysOfFirstThreeBatches = new HashSet<>(recordKeysOfFirstTwoBatches);
    recordKeysOfFirstThreeBatches.addAll(records3.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)
            .get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString()).collect(Collectors.toList()));
    header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "102");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, new ArrayList<>(records3), header);
    writer.appendBlock(dataBlock);

    writer.close();
    FileCreateUtils.createDeltaCommit(basePath, "102", fs);

    // Append some arbit byte[] to the end of the log (mimics a partially written commit)
    fs = FSUtils.getFs(fs.getUri().toString(), fs.getConf());
    FSDataOutputStream outputStream = fs.append(writer.getLogFile().getPath());
    // create a block with
    outputStream.write(HoodieLogFormat.MAGIC);
    outputStream.writeLong(1000);
    outputStream.writeInt(HoodieLogBlockType.AVRO_DATA_BLOCK.ordinal());
    outputStream.writeInt(HoodieLogFormat.CURRENT_VERSION);
    // Write out a length that does not confirm with the content
    outputStream.writeLong(100);
    outputStream.flush();
    outputStream.close();

    // Append some arbit byte[] to the end of the log (mimics a partially written commit)
    fs = FSUtils.getFs(fs.getUri().toString(), fs.getConf());
    outputStream = fs.append(writer.getLogFile().getPath());
    // create a block with
    outputStream.write(HoodieLogFormat.MAGIC);
    outputStream.writeLong(1000);
    outputStream.writeInt(HoodieLogBlockType.AVRO_DATA_BLOCK.ordinal());
    outputStream.writeInt(HoodieLogFormat.CURRENT_VERSION);
    // Write out a length that does not confirm with the content
    outputStream.writeLong(100);
    outputStream.flush();
    outputStream.close();

    writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();

    // Create compacted block CB4
    List<IndexedRecord> compactedRecords = Stream.of(records1, records2).flatMap(Collection::stream)
        .collect(Collectors.toList());
    header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "103");
    header.put(HeaderMetadataType.COMPACTED_BLOCK_TIMES, "100,101");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, new ArrayList<>(compactedRecords), header);
    writer.appendBlock(dataBlock);

    FileCreateUtils.createDeltaCommit(basePath, "103", fs);

    // Create compacted block CB5
    List<IndexedRecord> secondCompactedRecords = Stream.of(compactedRecords, records3).flatMap(Collection::stream)
        .collect(Collectors.toList());
    header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "104");
    header.put(HeaderMetadataType.COMPACTED_BLOCK_TIMES, "103,102");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, new ArrayList<>(secondCompactedRecords), header);
    writer.appendBlock(dataBlock);

    FileCreateUtils.createDeltaCommit(basePath, "104", fs);

    // Write 6th data block
    List<IndexedRecord> records6 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "105");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, new ArrayList<>(records6), header);
    writer.appendBlock(dataBlock);

    FileCreateUtils.createDeltaCommit(basePath, "105", fs);

    // Write 7th data block
    List<IndexedRecord> records7 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "106");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, new ArrayList<>(records7), header);
    writer.appendBlock(dataBlock);

    FileCreateUtils.createDeltaCommit(basePath, "106", fs);

    // Write 8th data block
    List<IndexedRecord> records8 = SchemaTestUtil.generateHoodieTestRecords(0, 100);
    header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "107");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, new ArrayList<>(records8), header);
    writer.appendBlock(dataBlock);

    FileCreateUtils.createDeltaCommit(basePath, "107", fs);

    // Create compacted block CB9
    List<IndexedRecord> thirdCompactedBlockRecords = Stream.of(records7, records8).flatMap(Collection::stream)
        .collect(Collectors.toList());
    header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "108");
    header.put(HeaderMetadataType.COMPACTED_BLOCK_TIMES, "106,107");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, new ArrayList<>(thirdCompactedBlockRecords), header);
    writer.appendBlock(dataBlock);
    writer.close();

    FileCreateUtils.createDeltaCommit(basePath, "108", fs);

    List<String> allLogFiles =
        FSUtils.getAllLogFiles(fs, partitionPath, "test-fileid1", HoodieLogFile.DELTA_EXTENSION, "100")
            .map(s -> s.getPath().toString()).collect(Collectors.toList());

    HoodieMergedLogRecordScanner scanner = HoodieMergedLogRecordScanner.newBuilder()
        .withFileSystem(fs)
        .withBasePath(basePath)
        .withLogFilePaths(allLogFiles)
        .withReaderSchema(schema)
        .withLatestInstantTime("108")
        .withMaxMemorySizeInBytes(10240L)
        .withReadBlocksLazily(readBlocksLazily)
        .withReverseReader(false)
        .withBufferSize(BUFFER_SIZE)
        .withSpillableMapBasePath(spillableBasePath)
        .withDiskMapType(diskMapType)
        .withBitCaskDiskMapCompressionEnabled(isCompressionEnabled)
        .withUseScanV2(true)
        .withRecordMerger(HoodieRecordUtils.loadRecordMerger(HoodieAvroRecordMerger.class.getName()))
        .build();
    assertEquals(600, scanner.getTotalLogRecords(), "We would read 600 records from scanner");
    final List<String> readKeys = new ArrayList<>();
    scanner.forEach(s -> readKeys.add(s.getKey().getRecordKey()));
    List<String> expectedRecords = Stream.of(secondCompactedRecords, records6, thirdCompactedBlockRecords)
        .flatMap(Collection::stream)
        .map(s -> ((GenericRecord) s).get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString())
        .sorted()
        .collect(Collectors.toList());
    List<String> validBlockInstants = scanner.getValidBlockInstants();
    List<String> expectedBlockInstants = Arrays.asList("108","105", "104");
    assertEquals(expectedBlockInstants, validBlockInstants);
    Collections.sort(readKeys);
    assertEquals(expectedRecords, readKeys, "Record keys read should be exactly same.");
    scanner.close();
  }

  /*
   * During a spark stage failure, when the stage is retried, tasks that are part of the previous attempt
   * of the stage would continue to run.  As a result two different tasks could be performing the same operation.
   * When trying to update the log file, only one of the tasks would succeed (one holding lease on the log file).
   *
   * In order to make progress in this scenario, second task attempting to update the log file would rollover to
   * a new version of the log file.  As a result, we might end up with two log files with same set of data records
   * present in both of them.
   *
   * Following uint tests mimic this scenario to ensure that the reader can handle merging multiple log files with
   * duplicate data.
   *
   */
  private void testAvroLogRecordReaderMergingMultipleLogFiles(int numRecordsInLog1, int numRecordsInLog2,
                                                              ExternalSpillableMap.DiskMapType diskMapType,
                                                              boolean isCompressionEnabled,
                                                              boolean readBlocksLazily,
                                                              boolean useScanv2) {
    try {
      // Write one Data block with same InstantTime (written in same batch)
      Schema schema = HoodieAvroUtils.addMetadataFields(getSimpleSchema());
      List<IndexedRecord> records = SchemaTestUtil.generateHoodieTestRecords(0, 101);
      List<IndexedRecord> records2 = new ArrayList<>(records);

      // Write1 with numRecordsInLog1 records written to log.1
      Writer writer = HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath)
          .withFileExtension(HoodieLogFile.DELTA_EXTENSION).withFileId("test-fileid1")
          .overBaseCommit("100").withFs(fs).build();

      Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
      header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
      header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
      HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records.subList(0, numRecordsInLog1), header);
      writer.appendBlock(dataBlock);
      // Get the size of the block
      long size = writer.getCurrentSize();
      writer.close();

      // write2 with numRecordsInLog2 records written to log.2
      Writer writer2 = HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath)
          .withFileExtension(HoodieLogFile.DELTA_EXTENSION).withFileId("test-fileid1")
          .overBaseCommit("100").withFs(fs).withSizeThreshold(size - 1).build();

      Map<HoodieLogBlock.HeaderMetadataType, String> header2 = new HashMap<>();
      header2.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
      header2.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
      HoodieDataBlock dataBlock2 = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records2.subList(0, numRecordsInLog2), header2);
      writer2.appendBlock(dataBlock2);
      // Get the size of the block
      writer2.close();

      FileCreateUtils.createDeltaCommit(basePath, "100", fs);

      // From the two log files generated, read the records
      List<String> allLogFiles = FSUtils.getAllLogFiles(fs, partitionPath, "test-fileid1",
          HoodieLogFile.DELTA_EXTENSION, "100").map(s -> s.getPath().toString()).collect(Collectors.toList());

      HoodieMergedLogRecordScanner scanner = HoodieMergedLogRecordScanner.newBuilder()
          .withFileSystem(fs)
          .withBasePath(basePath)
          .withLogFilePaths(allLogFiles)
          .withReaderSchema(schema)
          .withLatestInstantTime("100")
          .withMaxMemorySizeInBytes(10240L)
          .withReadBlocksLazily(readBlocksLazily)
          .withReverseReader(false)
          .withBufferSize(BUFFER_SIZE)
          .withSpillableMapBasePath(spillableBasePath)
          .withDiskMapType(diskMapType)
          .withBitCaskDiskMapCompressionEnabled(isCompressionEnabled)
          .withUseScanV2(useScanv2)
          .withRecordMerger(HoodieRecordUtils.loadRecordMerger(HoodieAvroRecordMerger.class.getName()))
          .build();

      assertEquals(Math.max(numRecordsInLog1, numRecordsInLog2), scanner.getNumMergedRecordsInLog(),
          "We would read 100 records");
      scanner.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testAvroLogRecordReaderWithFailedTaskInFirstStageAttempt(ExternalSpillableMap.DiskMapType diskMapType,
                                                                       boolean isCompressionEnabled,
                                                                       boolean readBlocksLazily,
                                                                       boolean useScanv2) {
    /*
     * FIRST_ATTEMPT_FAILED:
     * Original task from the stage attempt failed, but subsequent stage retry succeeded.
     */
    testAvroLogRecordReaderMergingMultipleLogFiles(77, 100,
        diskMapType, isCompressionEnabled, readBlocksLazily, useScanv2);
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testAvroLogRecordReaderWithFailedTaskInSecondStageAttempt(ExternalSpillableMap.DiskMapType diskMapType,
                                                                        boolean isCompressionEnabled,
                                                                        boolean readBlocksLazily,
                                                                        boolean useScanv2) {
    /*
     * SECOND_ATTEMPT_FAILED:
     * Original task from stage attempt succeeded, but subsequent retry attempt failed.
     */
    testAvroLogRecordReaderMergingMultipleLogFiles(100, 66,
        diskMapType, isCompressionEnabled, readBlocksLazily, useScanv2);
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testAvroLogRecordReaderTasksSucceededInBothStageAttempts(ExternalSpillableMap.DiskMapType diskMapType,
                                                                       boolean isCompressionEnabled,
                                                                       boolean readBlocksLazily,
                                                                       boolean useScanv2) {
    /*
     * BOTH_ATTEMPTS_SUCCEEDED:
     * Original task from the stage attempt and duplicate task from the stage retry succeeded.
     */
    testAvroLogRecordReaderMergingMultipleLogFiles(100, 100,
        diskMapType, isCompressionEnabled, readBlocksLazily, useScanv2);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testBasicAppendAndReadInReverse(boolean readBlocksLazily)
      throws IOException, URISyntaxException, InterruptedException {
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    Schema schema = getSimpleSchema();
    List<IndexedRecord> records1 = SchemaTestUtil.generateTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords1 = records1.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records1, header);
    writer.appendBlock(dataBlock);
    writer.close();

    writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    List<IndexedRecord> records2 = SchemaTestUtil.generateTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords2 = records2.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records2, header);
    writer.appendBlock(dataBlock);
    writer.close();

    // Close and Open again and append 100 more records
    writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    List<IndexedRecord> records3 = SchemaTestUtil.generateTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords3 = records3.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records3, header);
    writer.appendBlock(dataBlock);
    writer.close();

    FileCreateUtils.createDeltaCommit(basePath, "100", fs);

    HoodieLogFile logFile = new HoodieLogFile(writer.getLogFile().getPath(), fs.getFileStatus(writer.getLogFile().getPath()).getLen());
    try (HoodieLogFileReader reader = new HoodieLogFileReader(fs, logFile, SchemaTestUtil.getSimpleSchema(), BUFFER_SIZE, readBlocksLazily, true)) {

      assertTrue(reader.hasPrev(), "Last block should be available");
      HoodieLogBlock prevBlock = reader.prev();
      HoodieDataBlock dataBlockRead = (HoodieDataBlock) prevBlock;

      List<IndexedRecord> recordsRead1 = getRecords(dataBlockRead);
      assertEquals(copyOfRecords3.size(), recordsRead1.size(),
          "Third records size should be equal to the written records size");
      assertEquals(copyOfRecords3, recordsRead1,
          "Both records lists should be the same. (ordering guaranteed)");

      assertTrue(reader.hasPrev(), "Second block should be available");
      prevBlock = reader.prev();
      dataBlockRead = (HoodieDataBlock) prevBlock;
      List<IndexedRecord> recordsRead2 = getRecords(dataBlockRead);
      assertEquals(copyOfRecords2.size(), recordsRead2.size(),
          "Read records size should be equal to the written records size");
      assertEquals(copyOfRecords2, recordsRead2,
          "Both records lists should be the same. (ordering guaranteed)");

      assertTrue(reader.hasPrev(), "First block should be available");
      prevBlock = reader.prev();
      dataBlockRead = (HoodieDataBlock) prevBlock;
      List<IndexedRecord> recordsRead3 = getRecords(dataBlockRead);
      assertEquals(copyOfRecords1.size(), recordsRead3.size(),
          "Read records size should be equal to the written records size");
      assertEquals(copyOfRecords1, recordsRead3,
          "Both records lists should be the same. (ordering guaranteed)");

      assertFalse(reader.hasPrev());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testAppendAndReadOnCorruptedLogInReverse(boolean readBlocksLazily)
      throws IOException, URISyntaxException, InterruptedException {
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    Schema schema = getSimpleSchema();
    List<IndexedRecord> records = SchemaTestUtil.generateTestRecords(0, 100);
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records, header);
    writer.appendBlock(dataBlock);
    writer.close();

    FileCreateUtils.createDeltaCommit(basePath, "100", fs);

    // Append some arbit byte[] to the end of the log (mimics a partially written commit)
    fs = FSUtils.getFs(fs.getUri().toString(), fs.getConf());
    FSDataOutputStream outputStream = fs.append(writer.getLogFile().getPath());
    // create a block with
    outputStream.write(HoodieLogFormat.MAGIC);
    outputStream.writeInt(HoodieLogBlockType.AVRO_DATA_BLOCK.ordinal());
    // Write out a length that does not confirm with the content
    outputStream.writeInt(1000);
    // Write out footer length
    outputStream.writeInt(1);
    // Write out some metadata
    // TODO : test for failure to write metadata - NA ?
    outputStream.write(HoodieLogBlock.getLogMetadataBytes(header));
    outputStream.write("something-random".getBytes());
    outputStream.flush();
    outputStream.close();

    // Should be able to append a new block
    writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    records = SchemaTestUtil.generateTestRecords(0, 100);
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records, header);
    writer.appendBlock(dataBlock);
    writer.close();

    // First round of reads - we should be able to read the first block and then EOF
    HoodieLogFile logFile = new HoodieLogFile(writer.getLogFile().getPath(), fs.getFileStatus(writer.getLogFile().getPath()).getLen());

    try (HoodieLogFileReader reader =
        new HoodieLogFileReader(fs, logFile, schema, BUFFER_SIZE, readBlocksLazily, true)) {

      assertTrue(reader.hasPrev(), "Last block should be available");
      HoodieLogBlock block = reader.prev();
      assertTrue(block instanceof HoodieDataBlock, "Last block should be datablock");

      assertTrue(reader.hasPrev(), "Last block should be available");
      assertThrows(CorruptedLogFileException.class, () -> {
        reader.prev();
      });
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testBasicAppendAndTraverseInReverse(boolean readBlocksLazily)
      throws IOException, URISyntaxException, InterruptedException {
    Writer writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    Schema schema = getSimpleSchema();
    List<IndexedRecord> records1 = SchemaTestUtil.generateTestRecords(0, 100);
    List<IndexedRecord> copyOfRecords1 = records1.stream()
        .map(record -> HoodieAvroUtils.rewriteRecord((GenericRecord) record, schema)).collect(Collectors.toList());
    Map<HoodieLogBlock.HeaderMetadataType, String> header = new HashMap<>();
    header.put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
    header.put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
    HoodieDataBlock dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records1, header);
    writer.appendBlock(dataBlock);
    writer.close();

    writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    List<IndexedRecord> records2 = SchemaTestUtil.generateTestRecords(0, 100);
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records2, header);
    writer.appendBlock(dataBlock);
    writer.close();

    // Close and Open again and append 100 more records
    writer =
        HoodieLogFormat.newWriterBuilder().onParentPath(partitionPath).withFileExtension(HoodieLogFile.DELTA_EXTENSION)
            .withFileId("test-fileid1").overBaseCommit("100").withFs(fs).build();
    List<IndexedRecord> records3 = SchemaTestUtil.generateTestRecords(0, 100);
    dataBlock = getDataBlock(DEFAULT_DATA_BLOCK_TYPE, records3, header);
    writer.appendBlock(dataBlock);
    writer.close();

    FileCreateUtils.createDeltaCommit(basePath, "100", fs);

    HoodieLogFile logFile = new HoodieLogFile(writer.getLogFile().getPath(), fs.getFileStatus(writer.getLogFile().getPath()).getLen());
    try (HoodieLogFileReader reader =
             new HoodieLogFileReader(fs, logFile, SchemaTestUtil.getSimpleSchema(), BUFFER_SIZE, readBlocksLazily, true)) {

      assertTrue(reader.hasPrev(), "Third block should be available");
      reader.moveToPrev();

      assertTrue(reader.hasPrev(), "Second block should be available");
      reader.moveToPrev();

      // After moving twice, this last reader.prev() should read the First block written
      assertTrue(reader.hasPrev(), "First block should be available");
      HoodieLogBlock prevBlock = reader.prev();
      HoodieDataBlock dataBlockRead = (HoodieDataBlock) prevBlock;
      List<IndexedRecord> recordsRead = getRecords(dataBlockRead);
      assertEquals(copyOfRecords1.size(), recordsRead.size(),
          "Read records size should be equal to the written records size");
      assertEquals(copyOfRecords1, recordsRead,
          "Both records lists should be the same. (ordering guaranteed)");

      assertFalse(reader.hasPrev());
    }
  }

  @Test
  public void testV0Format() throws IOException, URISyntaxException {
    // HoodieLogFormatVersion.DEFAULT_VERSION has been deprecated so we cannot
    // create a writer for it. So these tests are only for the HoodieAvroDataBlock
    // of older version.
    Schema schema = getSimpleSchema();
    List<IndexedRecord> records = SchemaTestUtil.generateTestRecords(0, 100);
    List<IndexedRecord> recordsCopy = new ArrayList<>(records);
    assertEquals(100, records.size());
    assertEquals(100, recordsCopy.size());
    HoodieAvroDataBlock dataBlock = new HoodieAvroDataBlock(records.stream().map(HoodieAvroIndexedRecord::new).collect(Collectors.toList()), schema);
    byte[] content = dataBlock.getBytes(schema);
    assertTrue(content.length > 0);

    HoodieLogBlock logBlock = HoodieAvroDataBlock.getBlock(content, schema);
    assertEquals(HoodieLogBlockType.AVRO_DATA_BLOCK, logBlock.getBlockType());
    List<IndexedRecord> readRecords = getRecords((HoodieAvroDataBlock) logBlock);
    assertEquals(readRecords.size(), recordsCopy.size());
    for (int i = 0; i < recordsCopy.size(); ++i) {
      assertEquals(recordsCopy.get(i), readRecords.get(i));
    }

    // Reader schema is optional if it is same as write schema
    logBlock = HoodieAvroDataBlock.getBlock(content, null);
    assertEquals(HoodieLogBlockType.AVRO_DATA_BLOCK, logBlock.getBlockType());
    readRecords = getRecords((HoodieAvroDataBlock) logBlock);
    assertEquals(readRecords.size(), recordsCopy.size());
    for (int i = 0; i < recordsCopy.size(); ++i) {
      assertEquals(recordsCopy.get(i), readRecords.get(i));
    }
  }

  @ParameterizedTest
  @EnumSource(names = {"AVRO_DATA_BLOCK", "HFILE_DATA_BLOCK", "PARQUET_DATA_BLOCK"})
  public void testDataBlockFormatAppendAndReadWithProjectedSchema(
      HoodieLogBlockType dataBlockType
  ) throws IOException, URISyntaxException, InterruptedException {
    Writer writer = HoodieLogFormat.newWriterBuilder()
        .onParentPath(partitionPath)
        .withFileExtension(HoodieLogFile.DELTA_EXTENSION)
        .withFileId("test-fileid1")
        .overBaseCommit("100")
        .withFs(fs)
        .build();

    List<GenericRecord> records = SchemaTestUtil.generateTestGenericRecords(0, 1000);

    Schema schema = getSimpleSchema();

    Map<HoodieLogBlock.HeaderMetadataType, String> header =
        new HashMap<HoodieLogBlock.HeaderMetadataType, String>() {{
          put(HoodieLogBlock.HeaderMetadataType.INSTANT_TIME, "100");
          put(HoodieLogBlock.HeaderMetadataType.SCHEMA, schema.toString());
        }};

    // Init Benchmark to report number of bytes actually read from the Block
    BenchmarkCounter.initCounterFromReporter(HadoopMapRedUtils.createTestReporter(), fs.getConf());

    // NOTE: Have to use this ugly hack since List generic is not covariant in its type param
    HoodieDataBlock dataBlock = getDataBlock(dataBlockType, (List<IndexedRecord>)(List) records, header);

    writer.appendBlock(dataBlock);
    writer.close();

    Schema projectedSchema = HoodieAvroUtils.generateProjectionSchema(schema, Collections.singletonList("name"));

    List<GenericRecord> projectedRecords = HoodieAvroUtils.rewriteRecords(records, projectedSchema);

    try (Reader reader = HoodieLogFormat.newReader(fs, writer.getLogFile(), projectedSchema, true, false)) {
      assertTrue(reader.hasNext(), "First block should be available");

      HoodieLogBlock nextBlock = reader.next();

      HoodieDataBlock dataBlockRead = (HoodieDataBlock) nextBlock;

      Map<HoodieLogBlockType, Integer> expectedReadBytes =
          new HashMap<HoodieLogBlockType, Integer>() {{
            put(HoodieLogBlockType.AVRO_DATA_BLOCK, 0); // not supported
            put(HoodieLogBlockType.HFILE_DATA_BLOCK, 0); // not supported
            put(HoodieLogBlockType.PARQUET_DATA_BLOCK, HoodieAvroUtils.gteqAvro1_9() ? 1802 : 1809);
          }};

      List<IndexedRecord> recordsRead = getRecords(dataBlockRead);
      assertEquals(projectedRecords.size(), recordsRead.size(),
          "Read records size should be equal to the written records size");
      assertEquals(projectedRecords, recordsRead,
          "Both records lists should be the same. (ordering guaranteed)");
      assertEquals(dataBlockRead.getSchema(), projectedSchema);

      int bytesRead = (int) BenchmarkCounter.getBytesRead();

      assertEquals(expectedReadBytes.get(dataBlockType), bytesRead, "Read bytes have to match");
    }
  }

  private HoodieDataBlock getDataBlock(HoodieLogBlockType dataBlockType, List<IndexedRecord> records,
                                       Map<HeaderMetadataType, String> header) {
    return getDataBlock(dataBlockType, records.stream().map(HoodieAvroIndexedRecord::new).collect(Collectors.toList()), header, new Path("dummy_path"));
  }

  private HoodieDataBlock getDataBlock(HoodieLogBlockType dataBlockType, List<HoodieRecord> records,
                                       Map<HeaderMetadataType, String> header, Path pathForReader) {
    switch (dataBlockType) {
      case CDC_DATA_BLOCK:
        return new HoodieCDCDataBlock(records, header, HoodieRecord.RECORD_KEY_METADATA_FIELD);
      case AVRO_DATA_BLOCK:
        return new HoodieAvroDataBlock(records, header, HoodieRecord.RECORD_KEY_METADATA_FIELD);
      case HFILE_DATA_BLOCK:
        return new HoodieHFileDataBlock(records, header, Compression.Algorithm.GZ, pathForReader);
      case PARQUET_DATA_BLOCK:
        return new HoodieParquetDataBlock(records, header, HoodieRecord.RECORD_KEY_METADATA_FIELD, CompressionCodecName.GZIP, 0.1, true);
      default:
        throw new RuntimeException("Unknown data block type " + dataBlockType);
    }
  }

  private static Stream<Arguments> testArguments() {
    // Arg1: ExternalSpillableMap Type, Arg2: isDiskMapCompressionEnabled, Arg3: readBlocksLazily, Arg4: useScanv2
    return Stream.of(
        arguments(ExternalSpillableMap.DiskMapType.BITCASK, false, false, true),
        arguments(ExternalSpillableMap.DiskMapType.ROCKS_DB, false, false, true),
        arguments(ExternalSpillableMap.DiskMapType.BITCASK, true, false, true),
        arguments(ExternalSpillableMap.DiskMapType.ROCKS_DB, true, false, true),
        arguments(ExternalSpillableMap.DiskMapType.BITCASK, false, true, true),
        arguments(ExternalSpillableMap.DiskMapType.ROCKS_DB, false, true, true),
        arguments(ExternalSpillableMap.DiskMapType.BITCASK, true, true, true),
        arguments(ExternalSpillableMap.DiskMapType.ROCKS_DB, true, true, true),
        arguments(ExternalSpillableMap.DiskMapType.BITCASK, false, false, false),
        arguments(ExternalSpillableMap.DiskMapType.ROCKS_DB, false, false, false),
        arguments(ExternalSpillableMap.DiskMapType.BITCASK, true, false, false),
        arguments(ExternalSpillableMap.DiskMapType.ROCKS_DB, true, false, false),
        arguments(ExternalSpillableMap.DiskMapType.BITCASK, false, true, false),
        arguments(ExternalSpillableMap.DiskMapType.ROCKS_DB, false, true, false),
        arguments(ExternalSpillableMap.DiskMapType.BITCASK, true, true, false),
        arguments(ExternalSpillableMap.DiskMapType.ROCKS_DB, true, true, false)
    );
  }

  private static Stream<Arguments> testArgumentsWithoutScanV2Arg() {
    // Arg1: ExternalSpillableMap Type, Arg2: isDiskMapCompressionEnabled, Arg3: readBlocksLazily, Arg4: useScanv2
    return Stream.of(
        arguments(ExternalSpillableMap.DiskMapType.BITCASK, false, false),
        arguments(ExternalSpillableMap.DiskMapType.ROCKS_DB, false, false),
        arguments(ExternalSpillableMap.DiskMapType.BITCASK, true, false),
        arguments(ExternalSpillableMap.DiskMapType.ROCKS_DB, true, false),
        arguments(ExternalSpillableMap.DiskMapType.BITCASK, false, true),
        arguments(ExternalSpillableMap.DiskMapType.ROCKS_DB, false, true),
        arguments(ExternalSpillableMap.DiskMapType.BITCASK, true, true),
        arguments(ExternalSpillableMap.DiskMapType.ROCKS_DB, true, true)
    );
  }

  /**
   * Utility to convert the given iterator to a List.
   */
  private static List<IndexedRecord> getRecords(HoodieDataBlock dataBlock) {
    ClosableIterator<HoodieRecord<IndexedRecord>> itr = dataBlock.getRecordIterator(HoodieRecordType.AVRO);

    List<IndexedRecord> elements = new ArrayList<>();
    itr.forEachRemaining(r -> elements.add(r.getData()));
    return elements;
  }
}
