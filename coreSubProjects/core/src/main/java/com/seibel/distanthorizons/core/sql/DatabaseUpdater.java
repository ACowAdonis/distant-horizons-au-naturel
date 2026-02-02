/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.sql;

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.sql.dto.IBaseDTO;
import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import com.seibel.distanthorizons.core.logging.DhLogger;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.util.Map;
import java.util.Scanner;

/**
 * Handles initial database schema setup.
 * This simplified version creates all tables if they don't exist.
 * No migration support - this fork uses a single schema version.
 */
public class DatabaseUpdater
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();

	private static final String SCHEMA_SCRIPT_PATH = "sqlScripts/schema.sql";
	private static final String BATCH_SEPARATOR = "--batch--";

	/**
	 * Creates database tables if they don't exist.
	 * Uses a single schema.sql file - no migration tracking needed.
	 */
	public static <TKey, TDTO extends IBaseDTO<TKey>> void runAutoUpdateScripts(AbstractDhRepo<TKey, TDTO> repo) throws SQLException
	{
		// Check if FullData table exists (primary table)
		Map<String, Object> tableExistsResult = repo.queryDictionaryFirst(
				"SELECT COUNT(name) as 'tableCount' FROM sqlite_master WHERE type='table' AND name='FullData';"
		);

		boolean tablesExist = tableExistsResult != null && (int) tableExistsResult.get("tableCount") > 0;

		if (!tablesExist)
		{
			LOGGER.info("Creating database schema for: [" + repo.databaseFile + "]");
			runSchemaScript(repo);
		}
	}

	private static <TKey, TDTO extends IBaseDTO<TKey>> void runSchemaScript(AbstractDhRepo<TKey, TDTO> repo) throws SQLException
	{
		String schemaScript;
		try
		{
			schemaScript = loadSchemaScript();
		}
		catch (IOException e)
		{
			LOGGER.error("Failed to load schema script: " + e.getMessage(), e);
			throw new SQLException("Failed to load schema script", e);
		}

		Connection connection = repo.getConnection();

		// Split by batch separator and execute each statement
		String[] statements = schemaScript.split(BATCH_SEPARATOR);

		// Use auto-commit for PRAGMA statements
		connection.setAutoCommit(true);

		try (Statement stmt = connection.createStatement())
		{
			stmt.setQueryTimeout(AbstractDhRepo.TIMEOUT_SECONDS);

			for (String sql : statements)
			{
				sql = sql.trim();
				if (!sql.isEmpty() && !sql.startsWith("--"))
				{
					stmt.execute(sql);
				}
			}
		}
		catch (SQLException e)
		{
			LOGGER.error("Schema creation failed: " + e.getMessage(), e);
			throw e;
		}

		LOGGER.info("Database schema created successfully");
	}

	private static String loadSchemaScript() throws IOException
	{
		ClassLoader loader = Thread.currentThread().getContextClassLoader();

		try (InputStream inputStream = loader.getResourceAsStream(SCHEMA_SCRIPT_PATH))
		{
			if (inputStream == null)
			{
				throw new IOException("Schema script not found: " + SCHEMA_SCRIPT_PATH);
			}

			try (Scanner scanner = new Scanner(inputStream).useDelimiter("\\A"))
			{
				return scanner.hasNext() ? scanner.next() : "";
			}
		}
	}

	/**
	 * Returns 1 since we only have one schema script.
	 * Kept for compatibility with existing code that checks script count.
	 */
	public static int getAutoUpdateScriptCount() throws IOException
	{
		// Verify the schema script exists
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		try (InputStream inputStream = loader.getResourceAsStream(SCHEMA_SCRIPT_PATH))
		{
			if (inputStream == null)
			{
				throw new IOException("Schema script not found: " + SCHEMA_SCRIPT_PATH);
			}
		}
		return 1;
	}
}
