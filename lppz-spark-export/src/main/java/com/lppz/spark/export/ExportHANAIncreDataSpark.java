package com.lppz.spark.export;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.apache.spark.SparkContext;

import com.lppz.spark.scala.SparkHdfsUtil;
import com.lppz.spark.export.scala.ExportHanaDataHandler;
import com.lppz.spark.export.bean.Hive2DbmsBean;
import com.lppz.spark.scala.jdbc.MysqlSpark;
import com.lppz.spark.util.SparkHiveUtil;
import com.lppz.spark.util.SparkYamlUtils;

/***
 * 导出HANA增量数据
 * 
 * @author:xiaoyongfei
 */

public class ExportHANAIncreDataSpark {

	private static Logger log = Logger
			.getLogger(ExportHANAIncreDataSpark.class);

	static boolean isTest = false;
	static long totalOnce = 500000;
	static String ds = null;

	public static void main(String[] args) throws Exception {
		// args = new String[] { "/home/hadoop/azihyd0800.yaml",
		// "local[8]","false",500000};

		if (args.length == 0)
			throw new IOException("need yaml config");

		isTest = Boolean.valueOf(args[2]);

		totalOnce = args.length == 5 ? Integer.parseInt(args[4]) : 500000;

		ds = args[3];

		boolean isDir = checkDirectoryExist(args[0]);

		SparkContext sc = null;

		String appName = "export hana table "
				+ ExportHANAIncreDataSpark.class.getName() + ":"
				+ SparkHiveUtil.now();
		sc = new MysqlSpark().buildSc(appName, args[1]);

		if (isDir) {
			File dir = new File(args[0]);

			File[] yamls = dir.listFiles(new FilenameFilter() {

				@Override
				public boolean accept(File dir, String name) {
					return name.endsWith(".yaml");
				}
			});

			if (yamls != null && yamls.length > 0) {
				for (File yaml : yamls) {
					exec(sc, yaml.getAbsolutePath());
				}
			}
		} else {
			exec(sc, args[0]);
		}

	}

	public static void exec(SparkContext sc, String filePath) throws Exception {
		Hive2DbmsBean bean = null;
		bean = SparkYamlUtils.loadYaml(filePath, isTest, Hive2DbmsBean.class);

		String appName = "export hana table " + bean.getRdbmsSchemaName() + ":"
				+ bean.getRdbmsTableName() + SparkHiveUtil.now();

		ExportHanaDataHandler handler = new ExportHanaDataHandler();
		if (!bean.getUsePart()) {
			String loadSql = handler.exportHaNaTableWithRowid(appName, bean,
					sc, 0, 100, ds);
			loadData2Hive(bean, loadSql);
		} else {
			Map<String, Long> fetchMap = fetchMaxAndMin(bean);

			if (null == fetchMap || fetchMap.isEmpty())
				throw new RuntimeException("hana table is empty");

			long max = fetchMap.get("max");
			long min = fetchMap.get("min");
			long k = 0;
			long rows = bean.getTotalOnce() == null ? 500000 : bean
					.getTotalOnce();

			String currentDay = new SimpleDateFormat("yyyy-MM-dd")
					.format(new Date());
			FileSystem hdfs = new SparkHdfsUtil().getFileSystem(bean
					.getHdfsUrl());
			String fileName = bean.getHdfsUrl() + "/tmp/hana2hive/"
					+ bean.getHiveSchema() + "/" + bean.getHiveTableName()
					+ "/" + ds;
			String dest = bean.getHdfsUrl() + bean.getHiveUrl()
					+ "/ds=" + ds;

			hdfs.delete(new Path(fileName), true);
		    hdfs.delete(new Path(dest), true);

			for (long i = min; i <= max; i += rows + 1) {
				k = rows + i > max ? max : rows + i;
				String loadSql = handler.exportIncreHaNaTable(appName,
						bean, sc, i, k, ds);
				loadData2Hive(bean, loadSql);

			}
		}

	}

	private static Map<String, Long> fetchMaxAndMin(Hive2DbmsBean bean)
			throws Exception {
		Connection conn = null;
		String sql = "select max(" + bean.getPrimaryKey() + "),min("
				+ bean.getPrimaryKey() + ") from " + bean.getRdbmsSchemaName()
				+ "." + "\"" + bean.getRdbmsTableName() + "\"";
		String url = bean.getRdbmsJdbcUrl();
		try {
			Class.forName(bean.getRdbmsdbDriver());
			conn = DriverManager.getConnection(url, bean.getRdbmsJdbcUser(),
					bean.getRdbmsJdbcPasswd());
			Statement stmt = conn.createStatement();
			ResultSet rs = stmt.executeQuery(sql);

			Map<String, Long> fetchMap = new HashMap<>();
			while (rs.next()) {
				fetchMap.put("max", rs.getLong(1));
				fetchMap.put("min", rs.getLong(2));
			}
			return fetchMap;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			throw e;
		} finally {
			try {
				conn.close();
			} catch (SQLException e) {
			}
		}
	}

	private static Long fetchCount(Hive2DbmsBean bean) throws Exception {
		Connection conn = null;
		Long result = 0L;
		String sql = "select count(1) as count from (" + bean.getHiveSql()
				+ ") x";
		String url = bean.getRdbmsJdbcUrl();
		try {
			Class.forName(bean.getRdbmsdbDriver());
			conn = DriverManager.getConnection(url, bean.getRdbmsJdbcUser(),
					bean.getRdbmsJdbcPasswd());
			Statement stmt = conn.createStatement();
			ResultSet rs = stmt.executeQuery(sql);
			while (rs.next()) {
				result = rs.getLong(1);
			}
			return result;
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			throw e;
		} finally {
			try {
				conn.close();
			} catch (SQLException e) {
			}
		}
	}

	public static void loadData2Hive(Hive2DbmsBean bean, List<String> loadSql)
			throws SQLException {
		if (CollectionUtils.isEmpty(loadSql))
			return;

		Connection con = null;

		try {
			Class.forName("org.apache.hive.jdbc.HiveDriver");
			con = DriverManager.getConnection(bean.getHiveConnect()
					+ bean.getHiveSchema(), bean.getHiveUser(), bean.getHivePasswd());
			Statement stmt = con.createStatement();
			stmt.execute("use " + bean.getHiveSchema());
			for (String sql : loadSql) {
				stmt.execute(sql);
			}
		} catch (ClassNotFoundException e) {
			log.error(e.getMessage(), e);
			System.exit(1);
		} finally {
			try {
				if (null != con)
					con.close();
			} catch (SQLException e) {
			}
		}
	}

	public static void loadData2Hive(Hive2DbmsBean bean, String loadSql)
			throws SQLException {
		if (StringUtils.isEmpty(loadSql))
			return;

		List<String> sqls = new ArrayList<>();
		sqls.add(loadSql);

		loadData2Hive(bean, sqls);
	}

	private static boolean checkDirectoryExist(String path) throws Exception {
		File file = new File(path);

		if (!file.exists())
			throw new FileNotFoundException();

		if (file.isDirectory())
			return true;

		return false;
	}

}
