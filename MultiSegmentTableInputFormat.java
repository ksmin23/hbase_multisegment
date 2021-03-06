/**
 * Copyright 2007 The Apache Software Foundation
 *
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
package org.apache.hadoop.hbase.mapreduce;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.util.StringUtils;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.hbase.mapreduce.TableInputFormat;
import org.apache.hadoop.hbase.mapreduce.TableInputFormatBase;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.util.Base64;
import org.xerial.snappy.Snappy;

/**
 * Convert HBase tabular data into a format that is consumable by Map/Reduce.
 */
public class MultiSegmentTableInputFormat extends TableInputFormatBase
implements Configurable {


  private final Log LOG = LogFactory.getLog(TableInputFormat.class);

  /** Job parameter that specifies the input table. */
  public static final String INPUT_TABLE = "hbase.mapreduce.inputtable";
  /** Base-64 encoded scanner. All other SCAN_ confs are ignored if this is specified.
   * See {@link TableMapReduceUtil#convertScanToString(Scan)} for more details.
   */
  public static final String SCAN = "hbase.mapreduce.scan";
  /** Number of Scan Objects */
  public static final String SCAN_COUNT = "hbase.mapreduce.scan.count";
  /** Scan start row */
  public static final String SCAN_ROW_START = "hbase.mapreduce.scan.row.start";
  /** Scan stop row */
  public static final String SCAN_ROW_STOP = "hbase.mapreduce.scan.row.stop";
  /** Column Family to Scan */
  public static final String SCAN_COLUMN_FAMILY = "hbase.mapreduce.scan.column.family";
  /** Space delimited list of columns to scan. */
  public static final String SCAN_COLUMNS = "hbase.mapreduce.scan.columns";
  /** The timestamp used to filter columns with a specific timestamp. */
  public static final String SCAN_TIMESTAMP = "hbase.mapreduce.scan.timestamp";
  /** The starting timestamp used to filter columns with a specific range of versions. */
  public static final String SCAN_TIMERANGE_START = "hbase.mapreduce.scan.timerange.start";
  /** The ending timestamp used to filter columns with a specific range of versions. */
  public static final String SCAN_TIMERANGE_END = "hbase.mapreduce.scan.timerange.end";
  /** The maximum number of version to return. */
  public static final String SCAN_MAXVERSIONS = "hbase.mapreduce.scan.maxversions";
  /** Set to false to disable server-side caching of blocks for this scan. */
  public static final String SCAN_CACHEBLOCKS = "hbase.mapreduce.scan.cacheblocks";
  /** The number of rows for caching that will be passed to scanners. */
  public static final String SCAN_CACHEDROWS = "hbase.mapreduce.scan.cachedrows";
  /** Is Scan compressed? or not? */
  public static final String IS_ZIPPED_SCAN = "hbase.mapreduce.scan.is.zipped";

  /** The configuration. */
  private Configuration conf = null;

  private Scan[] scans = null;

  @Override
  public Configuration getConf() {
	return conf;
  }

  /**
   * Sets the configuration. This is used to set the details for the table to
   * be scanned.
   *
   * @param configuration  The configuration to set.
   * @see org.apache.hadoop.conf.Configurable#setConf(
   *   org.apache.hadoop.conf.Configuration)
   */
  @Override
  public void setConf(Configuration configuration) {
    this.conf = configuration;
    String tableName = conf.get(INPUT_TABLE);
    try {
      setHTable(new HTable(new Configuration(conf), tableName));
    } catch (Exception e) {
      LOG.error(StringUtils.stringifyException(e));
    }

    if (conf.get(SCAN_COUNT) != null) {
      try {
        boolean isZippedScan = conf.getBoolean(IS_ZIPPED_SCAN, false);
        int scanCount = Integer.parseInt(conf.get(MultiSegmentTableInputFormat.SCAN_COUNT));
        this.scans = new Scan[scanCount];
        for (int i = 0; i < scanCount; i++) {
          if (isZippedScan) {
            scans[i] = convertZippedStringToScan(conf.get(SCAN + "." + Integer.toString(i)));
          }
          else {
            scans[i] = convertStringToScan(conf.get(SCAN + "." + Integer.toString(i)));
          }
        }
      } catch (IOException e) {
        LOG.error("An error occurred.", e);
      }
    }
    setScan(scans[0]);
  }

  /**
   * Calculates the splits that will serve as input for the map tasks for each Scan. The
   * number of splits matches the number of regions in a table.
   *
   * @param context  The current job context.
   * @return The list of input splits.
   * @throws IOException When creating the list of splits fails.
   * @see org.apache.hadoop.mapreduce.InputFormat#getSplits(
   *   org.apache.hadoop.mapreduce.JobContext)
   */  
  @Override
  public List<InputSplit> getSplits(JobContext context) throws IOException {
     List<InputSplit> splits = new ArrayList<InputSplit>(); 
     for (Scan scan : scans) {
       List<InputSplit> localSplits = null;
       setScan(scan);
       localSplits = super.getSplits(context);
       splits.addAll(localSplits);
     }
     return splits;
  }

  /**
   * Writes the given scan into a Base64 encoded string.
   *
   * @param scan  The scan to write out.
   * @return The scan saved in a Base64 encoded string.
   * @throws IOException When writing the scan fails.
   */
  public static String convertScanToString(Scan scan) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(out);
    scan.write(dos);
    IOUtils.closeQuietly(dos);
    return Base64.encodeBytes(out.toByteArray());
  }

  /**
   * Converts the given Base64 string back into a Scan instance.
   *
   * @param base64  The scan details.
   * @return The newly created Scan instance.
   * @throws IOException When reading the scan instance fails.
   */
  static Scan convertStringToScan(String base64) throws IOException {
    ByteArrayInputStream bis = new ByteArrayInputStream(Base64.decode(base64));
    DataInputStream dis = new DataInputStream(bis);
    Scan scan = new Scan();
    scan.readFields(dis);
    IOUtils.closeQuietly(dis);
    return scan;
  }

  /**
   * Writes the given scan into a Base64 encoded string.
   *
   * @param scan  The scan to write out.
   * @return The scan saved in a Base64 encoded string.
   * @throws IOException When writing the scan fails.
   */
  public static String convertScanToZippedString(Scan scan) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(out);
    scan.write(dos);
    IOUtils.closeQuietly(dos);

    byte[] compressedScan = Snappy.compress(out.toByteArray());
    return Base64.encodeBytes(compressedScan);
  }

  /**
   * Converts the given Base64 string back into a Scan instance.
   *
   * @param base64  The scan details.
   * @return The newly created Scan instance.
   * @throws IOException When reading the scan instance fails.
   */
  static Scan convertZippedStringToScan(String base64) throws IOException {
    byte[] uncompressedScan = Snappy.uncompress(Base64.decode(base64));
    ByteArrayInputStream bis = new ByteArrayInputStream(uncompressedScan);
    DataInputStream dis = new DataInputStream(bis);
    Scan scan = new Scan();
    scan.readFields(dis);
    IOUtils.closeQuietly(dis);
    return scan;
  }
}

