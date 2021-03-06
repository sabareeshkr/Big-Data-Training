package com.expertzlab.json;

import com.expertzlab.util.HadoopCompat;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.apache.avro.data.Json;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.LineRecordReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Assumes one line per JSON object
 */
public class JsonInputFormat
    extends FileInputFormat<LongWritable, MapWritable> {

  private static final Logger log =
      LoggerFactory.getLogger(JsonInputFormat.class);

  @Override
  public RecordReader<LongWritable, MapWritable> createRecordReader(
      InputSplit split, TaskAttemptContext context) {
    return new JsonRecordReader();
  }

  @Override
  protected boolean isSplitable(JobContext context, Path file) {
    CompressionCodec codec =
        new CompressionCodecFactory(HadoopCompat.getConfiguration(context))
            .getCodec(file);
    return codec == null;
  }

  public static class JsonRecordReader
      extends RecordReader<LongWritable, MapWritable> {
    private static final Logger LOG =
        LoggerFactory.getLogger(JsonRecordReader
            .class);

    private static final JsonFactory jsonFactory = new JsonFactory();

    private LineRecordReader reader = new LineRecordReader();
    private final MapWritable value_ = new MapWritable();

    @Override
    public void initialize(InputSplit split,
                           TaskAttemptContext context)
        throws IOException, InterruptedException {
      reader.initialize(split, context);
    }

    @Override
    public synchronized void close() throws IOException {
      reader.close();
    }

    @Override
    public LongWritable getCurrentKey() throws IOException,
        InterruptedException {
      return reader.getCurrentKey();
    }

    @Override
    public MapWritable getCurrentValue() throws IOException,
        InterruptedException {
      return value_;
    }

    @Override
    public float getProgress()
        throws IOException, InterruptedException {
      return reader.getProgress();
    }

    @Override
    public boolean nextKeyValue()
        throws IOException, InterruptedException {
      while (reader.nextKeyValue()) {
        value_.clear();
        if (decodeLineToJson(reader.getCurrentValue(),
            value_)) {
          return true;
        }
      }
      return false;
    }

    public static boolean decodeLineToJson(Text line,
                                           MapWritable value) throws IOException {
      log.info("Got string '{}'", line);
      String strLine = line.toString();
      if(line.getLength() < 10) {
        return true;
      }

      int length = strLine.length();
      int commpos = strLine.lastIndexOf(',');
      int curlypos = strLine.lastIndexOf('}');
      if(commpos>curlypos) {
        strLine = strLine.substring(0, commpos);
      }
      System.out.println("After comma cut -"+strLine);
      JsonParser parser = jsonFactory.createParser(strLine);
      JsonToken type = parser.nextToken(); //Start array

      try {
        type = parser.nextToken();
        while( type != null) {
          while (type == JsonToken.FIELD_NAME) {
            String fieldName = parser.getCurrentName();
            parser.nextToken();
            String fieldValue = parser.getText();
            Text mapKey = new Text(fieldName);
            Text mapValue = new Text(fieldValue);
            value.put(mapKey, mapValue);
            type= parser.nextToken();
          }
          //parser.nextToken();//end of object
          type = parser.nextToken(); //start of object
          type = parser.nextToken(); //Filed Name
        }
        return true;
      }catch (IOException ioe){
          LOG.warn("Could not json-decode string: " + line, ioe);
          return false;
      }
    }
  }
}
