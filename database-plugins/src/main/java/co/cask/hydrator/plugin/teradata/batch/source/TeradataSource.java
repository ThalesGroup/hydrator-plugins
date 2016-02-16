/*
 * Copyright © 2015-2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.hydrator.plugin.teradata.batch.source;

import co.cask.cdap.api.annotation.Description;
import co.cask.cdap.api.annotation.Name;
import co.cask.cdap.api.annotation.Plugin;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.dataset.lib.KeyValue;
import co.cask.cdap.api.plugin.PluginConfig;
import co.cask.cdap.etl.api.Emitter;
import co.cask.cdap.etl.api.PipelineConfigurer;
import co.cask.cdap.etl.api.batch.BatchRuntimeContext;
import co.cask.cdap.etl.api.batch.BatchSource;
import co.cask.cdap.etl.api.batch.BatchSourceContext;
import co.cask.hydrator.common.SourceInputFormatProvider;
import co.cask.hydrator.plugin.DBConfig;
import co.cask.hydrator.plugin.DBManager;
import co.cask.hydrator.plugin.DBRecord;
import co.cask.hydrator.plugin.DBUtils;
import co.cask.hydrator.plugin.FieldCase;
import co.cask.hydrator.plugin.StructuredRecordUtils;
import co.cask.hydrator.plugin.db.batch.source.DataDrivenETLDBInputFormat;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.lib.db.DBConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Driver;
import javax.annotation.Nullable;

/**
 * Batch source to read from a Teradata table
 */
@Plugin(type = "batchsource")
@Name("Teradata")
@Description("Reads from a Teradata table using a configurable SQL query." +
  " Outputs one record for each row returned by the query.")
public class TeradataSource extends BatchSource<LongWritable, DBRecord, StructuredRecord> {
  private static final Logger LOG = LoggerFactory.getLogger(TeradataSource.class);

  private final TeradataSourceConfig sourceConfig;
  private final DBManager dbManager;
  private Class<? extends Driver> driverClass;

  public TeradataSource(TeradataSourceConfig sourceConfig) {
    this.sourceConfig = sourceConfig;
    this.dbManager = new DBManager(sourceConfig);
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) {
    sourceConfig.validate();
    dbManager.validateJDBCPluginPipeline(pipelineConfigurer, getJDBCPluginId());
  }

  @Override
  public void prepareRun(BatchSourceContext context) throws Exception {
    sourceConfig.substituteMacros(context.getLogicalStartTime());
    LOG.debug("pluginType = {}; pluginName = {}; connectionString = {}; importQuery = {}; " +
                "boundingQuery = {}",
              sourceConfig.jdbcPluginType, sourceConfig.jdbcPluginName,
              sourceConfig.connectionString, sourceConfig.importQuery, sourceConfig.boundingQuery);
    Job job = Job.getInstance();
    Configuration hConf = job.getConfiguration();
    hConf.clear();

    // Load the plugin class to make sure it is available.
    Class<? extends Driver> driverClass = context.loadPluginClass(getJDBCPluginId());
    // make sure that the table exists
    try {
      Preconditions.checkArgument(
        dbManager.tableExists(driverClass, sourceConfig.tableName),
        "Table %s does not exist. Please check that the 'tableName' property " +
          "has been set correctly, and that the connection string %s points to a valid database.",
        sourceConfig.tableName, sourceConfig.connectionString);
    } finally {
      DBUtils.cleanup(driverClass);
    }
    if (sourceConfig.user == null && sourceConfig.password == null) {
      DBConfiguration.configureDB(hConf, driverClass.getName(), sourceConfig.connectionString);
    } else {
      DBConfiguration.configureDB(hConf, driverClass.getName(), sourceConfig.connectionString,
                                  sourceConfig.user, sourceConfig.password);
    }
    DataDrivenETLDBInputFormat.setInput(hConf, DBRecord.class, sourceConfig.importQuery,
                                        sourceConfig.boundingQuery, sourceConfig.enableAutoCommit);
    if (sourceConfig.splitBy != null) {
      // this is a terrible configuration key name.
      // DataDrivenDBInputFormat stores the split field name in the order by field name.
      hConf.set(DBConfiguration.INPUT_ORDER_BY_PROPERTY, sourceConfig.splitBy);
    }
    if (sourceConfig.numSplits != null) {
      hConf.setInt(MRJobConfig.NUM_MAPS, sourceConfig.numSplits);
    }
    context.setInput(new SourceInputFormatProvider(DataDrivenETLDBInputFormat.class, hConf));
  }

  @Override
  public void initialize(BatchRuntimeContext context) throws Exception {
    super.initialize(context);
    driverClass = context.loadPluginClass(getJDBCPluginId());
  }

  @Override
  public void transform(KeyValue<LongWritable, DBRecord> input, Emitter<StructuredRecord> emitter) throws Exception {
    emitter.emit(StructuredRecordUtils.convertCase(
      input.getValue().getRecord(), FieldCase.toFieldCase(sourceConfig.columnNameCase)));
  }

  @Override
  public void destroy() {
    try {
      DBUtils.cleanup(driverClass);
    } finally {
      dbManager.destroy();
    }
  }

  private String getJDBCPluginId() {
    return String.format("%s.%s.%s", "source", sourceConfig.jdbcPluginType, sourceConfig.jdbcPluginName);
  }

  /**
   * {@link PluginConfig} for {@link TeradataSource}
   */
  public static class TeradataSourceConfig extends DBConfig {
    public static final String BOUNDING_QUERY = "boundingQuery";
    public static final String SPLIT_BY = "splitBy";

    @Description("The SELECT query to use to import data from the specified table. " +
      "You can specify an arbitrary number of columns to import, or import all columns using *. The Query should" +
      "contain the '$CONDITIONS' string. For example, 'SELECT * FROM table WHERE $CONDITIONS'. " +
      "The '$CONDITIONS' string will be replaced by 'splitBy' field limits specified by the bounding query.")
    String importQuery;

    @Name(BOUNDING_QUERY)
    @Description("Bounding Query should return the min and max of the values of the 'splitBy' field. " +
      "For example, 'SELECT MIN(id),MAX(id) FROM table'. This is required unless numSplits is set to 1.")
    @Nullable
    String boundingQuery;

    @Name(SPLIT_BY)
    @Description("Field Name which will be used to generate splits. This is required unless numSplits is set to 1.")
    @Nullable
    String splitBy;

    @Description("The number of splits to use. " +
      "If this is set to 1, the boundingQuery and splitBy fields are not needed.")
    @Nullable
    Integer numSplits;

    public void validate() {
      super.validate();
      boolean notOneSplit = numSplits == null || numSplits != 1;
      if (notOneSplit && !importQuery.contains("$CONDITIONS")) {
        throw new IllegalArgumentException(String.format(
          "Import Query %s must contain the string '$CONDITIONS'.", importQuery));
      }
      if (notOneSplit && (Strings.isNullOrEmpty(boundingQuery) || Strings.isNullOrEmpty(splitBy))) {
        throw new IllegalArgumentException(
          "The boundingQuery and splitBy settings must both be specified, or numSplits must be set to 1.");
      }
    }
  }
}
