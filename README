## Example

```java
    Configuration conf = HBaseConfiguration.create());

    ......

    // set scans into configuration
    int num_of_scans = list_of_scan.size();
    conf.set(MultiSegmentTableInputFormat.INPUT_TABLE, table);
    conf.set(MultiSegmentTableInputFormat.SCAN_COUNT, Integer.toString(num_of_scans));
    conf.setBoolean(MultiSegmentTableInputFormat.IS_ZIPPED_SCAN, true);
    for(int i = 0; i < num_of_scans; i++) {
      conf.set(MultiSegmentTableInputFormat.SCAN + "." + Integer.toString(i),
          MultiSegmentTableInputFormat.convertScanToString(list_of_scan.get(i)));
    }

    Job job = new Job(conf, jobName);
    job.setJarByClass(MyJob.class);
    job.setInputFormatClass(MultiSegmentTableInputFormat.class);
    job.setMapOutputKeyClass(NullWritable.class);
    job.setMapOutputValueClass(Text.class);
    job.setMapperClass(Mapper.class);
    TableMapReduceUtil.addDependencyJars(job);
    TableMapReduceUtil.initCredentials(job);
    job.setSpeculativeExecution(false);
    job.setOutputFormatClass(TextOutputFormat.class);
    job.setNumReduceTasks(0);
    FileOutputFormat.setOutputPath(job, new Path(outputDir));
    LazyOutputFormat.setOutputFormatClass(job, TextOutputFormat.class);

```

