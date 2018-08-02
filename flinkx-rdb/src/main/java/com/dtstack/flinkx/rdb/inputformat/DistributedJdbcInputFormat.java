package com.dtstack.flinkx.rdb.inputformat;

import com.dtstack.flinkx.inputformat.RichInputFormat;
import com.dtstack.flinkx.rdb.DatabaseInterface;
import com.dtstack.flinkx.rdb.type.TypeConverterInterface;
import com.dtstack.flinkx.rdb.util.DBUtil;
import com.dtstack.flinkx.util.ClassUtil;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.io.InputSplit;
import org.apache.flink.types.Row;

import java.io.IOException;
import java.sql.*;
import java.util.Arrays;
import java.util.List;

public class DistributedJdbcInputFormat extends RichInputFormat {

    protected static final long serialVersionUID = 1L;

    protected DatabaseInterface databaseInterface;

    protected int numPartitions;

    protected String driverName;

    protected boolean hasNext;

    protected int columnCount;

    protected int resultSetType;

    protected int resultSetConcurrency;

    protected List<String> descColumnTypeList;

    protected List<DistributedJdbcInputSplit.DataSource> sourceList;

    private transient DistributedJdbcInputSplit.DataSource currentSource;

    private transient int sourceIndex;

    private transient Connection currentConn;

    private transient PreparedStatement currentStatement;

    private transient ResultSet currentResultSet;

    protected String username;

    protected String password;

    protected String splitKey;

    protected String where;

    protected List<String> column;

    protected TypeConverterInterface typeConverter;

    public DistributedJdbcInputFormat() {
        resultSetType = ResultSet.TYPE_FORWARD_ONLY;
        resultSetConcurrency = ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public void configure(Configuration configuration) {
        // null
    }

    @Override
    protected void openInternal(InputSplit inputSplit) throws IOException {
        try{
            ClassUtil.forName(driverName, getClass().getClassLoader());
            sourceList = ((DistributedJdbcInputSplit) inputSplit).getSourceList();
            openNextSource();
        }catch (Exception e){
            throw new IllegalArgumentException("open() failed." + e.getMessage(), e);
        }

        LOG.info("JdbcInputFormat[" + jobName + "]open: end");
    }

    private void openNextSource() throws Exception{
        for (DistributedJdbcInputSplit.DataSource dataSource : sourceList) {
            if(!dataSource.isFinished()){
                sourceIndex = sourceList.indexOf(dataSource);
                currentSource = dataSource;
                break;
            }
        }

        if (currentSource == null){
            hasNext = false;
            return;
        }

        currentConn = DBUtil.getConnection(currentSource.getJdbcUrl(), currentSource.getUserName(), currentSource.getPassword());
        String queryTemplate = DBUtil.getQuerySql(databaseInterface,currentSource.getTable(),column,splitKey,where,currentSource.isSplitByKey());
        currentStatement = currentConn.prepareStatement(queryTemplate, resultSetType, resultSetConcurrency);

        if (currentSource.isSplitByKey()){
            for (int i = 0; i < currentSource.getParameterValues().length; i++) {
                Object param = currentSource.getParameterValues()[i];
                DBUtil.setParameterValue(param,currentStatement,i);
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Executing '%s' with parameters %s", queryTemplate,
                        Arrays.deepToString(currentSource.getParameterValues())));
            }
        }

        currentStatement.setFetchSize(1000);
        currentStatement.setQueryTimeout(1000);
        currentResultSet = currentStatement.executeQuery();
        hasNext = currentResultSet.next();
        columnCount = currentResultSet.getMetaData().getColumnCount();
        if(descColumnTypeList == null) {
            descColumnTypeList = DBUtil.analyzeTable(currentConn,databaseInterface,currentSource.getTable(),column);
        }
    }

    @Override
    protected Row nextRecordInternal(Row row) throws IOException {
        row = new Row(columnCount);
        try{
            if (!hasNext){
                closeCurrentSource();
                openNextSource();

                if(!hasNext){
                    return null;
                }
            }

            DBUtil.getRow(currentSource.getJdbcUrl(),row,descColumnTypeList,currentResultSet,typeConverter);

            hasNext = currentResultSet.next();
            return row;
        }catch (SQLException se) {
            throw new IOException("Couldn't read data - " + se.getMessage(), se);
        } catch (Exception npe) {
            throw new IOException("Couldn't access resultSet", npe);
        }
    }

    private void closeCurrentSource(){
        DBUtil.closeDBResources(currentResultSet,currentStatement,currentConn);
        sourceList.get(sourceIndex).setFinished(true);
        currentSource = null;
    }

    @Override
    protected void closeInternal() throws IOException {
        closeCurrentSource();
    }

    @Override
    public InputSplit[] createInputSplits(int minPart) throws IOException {
        DistributedJdbcInputSplit[] inputSplits = new DistributedJdbcInputSplit[numPartitions];
        int partNum = sourceList.size() / numPartitions;
        for (int j = 0; j < numPartitions; j++) {
            DistributedJdbcInputSplit split = new DistributedJdbcInputSplit(j,numPartitions);
            split.setSourceList(sourceList.subList(j * partNum,(j + 1) * partNum));
            inputSplits[j] = split;
        }

        if(partNum * numPartitions < sourceList.size()){
            List<DistributedJdbcInputSplit.DataSource> sourceLeft = sourceList.subList(numPartitions * partNum,sourceList.size());
            Object[][] parmeter = DBUtil.getParameterValues(numPartitions);
            for (int i = 0; i < sourceLeft.size(); i++) {
                if (splitKey != null && splitKey.length() > 0){
                    sourceLeft.get(i).setSplitByKey(true);
                    sourceLeft.get(i).setParameterValues(parmeter[i]);
                }
                inputSplits[i].addSource(sourceLeft.get(i));
            }
        }

        return inputSplits;
    }

    @Override
    public boolean reachedEnd() throws IOException {
        return !hasNext;
    }
}
