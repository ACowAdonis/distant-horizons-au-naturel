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

package com.seibel.distanthorizons.core.sql.repo;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.DbConnectionClosedException;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.BoolUtil;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class FullDataSourceV2Repo extends AbstractDhRepo<Long, FullDataSourceV2DTO>
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public FullDataSourceV2Repo(String databaseType, File databaseFile) throws SQLException, IOException
	{
		super(databaseType, databaseFile, FullDataSourceV2DTO.class);
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override 
	public String getTableName() { return "FullData"; }
	
	@Override
	protected String CreateParameterizedWhereString() { return "DetailLevel = ? AND PosX = ? AND PosZ = ?"; }
	
	@Override
	protected int setPreparedStatementWhereClause(PreparedStatement statement, int index, Long pos) throws SQLException
	{
		int detailLevel = DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL;
		
		statement.setInt(index++, detailLevel);
		statement.setInt(index++, DhSectionPos.getX(pos));
		statement.setInt(index++, DhSectionPos.getZ(pos));
		
		return index;
	}
	
	
	
	@Override @Nullable
	public FullDataSourceV2DTO convertResultSetToDto(ResultSet resultSet) throws ClassCastException, IOException, SQLException
	{ return this.convertResultSetToDto(resultSet, true); }
	
	public FullDataSourceV2DTO convertResultSetToDto(ResultSet resultSet, boolean includeAdjacent) throws ClassCastException, IOException, SQLException
	{
		//======================//
		// get statement values //
		//======================//

		byte detailLevel = resultSet.getByte("DetailLevel");
		byte sectionDetailLevel = (byte) (detailLevel + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		int posX = resultSet.getInt("PosX");
		int posZ = resultSet.getInt("PosZ");
		long pos = DhSectionPos.encode(sectionDetailLevel, posX, posZ);

		byte compressionModeValue = resultSet.getByte("CompressionMode");

		// while these values can be null in the DB, null would just equate to false
		boolean applyToParent = (resultSet.getInt("ApplyToParent")) == 1;
		boolean isComplete = (resultSet.getInt("IsComplete")) == 1;

		long lastModifiedUnixDateTime = resultSet.getLong("LastModifiedUnixDateTime");
		long createdUnixDateTime = resultSet.getLong("CreatedUnixDateTime");



		//===================//
		// set DTO variables //
		//===================//

		FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateEmptyDataSourceForDecoding();

		// set pooled arrays
		dto.compressedDataByteArray = putAllBytes(resultSet.getBinaryStream("Data"), dto.compressedDataByteArray);
		dto.compressedWorldCompressionModeByteArray = putAllBytes(resultSet.getBinaryStream("ColumnWorldCompressionMode"), dto.compressedWorldCompressionModeByteArray);
		dto.compressedMappingByteArray = putAllBytes(resultSet.getBinaryStream("Mapping"), dto.compressedMappingByteArray);

		// adjacent full data
		if (includeAdjacent)
		{
			dto.compressedNorthAdjDataByteArray = putAllBytes(resultSet.getBinaryStream("NorthAdjData"), dto.compressedNorthAdjDataByteArray);
			dto.compressedSouthAdjDataByteArray = putAllBytes(resultSet.getBinaryStream("SouthAdjData"), dto.compressedSouthAdjDataByteArray);
			dto.compressedEastAdjDataByteArray = putAllBytes(resultSet.getBinaryStream("EastAdjData"), dto.compressedEastAdjDataByteArray);
			dto.compressedWestAdjDataByteArray = putAllBytes(resultSet.getBinaryStream("WestAdjData"), dto.compressedWestAdjDataByteArray);
		}

		// set individual variables
		{
			dto.pos = pos;
			dto.compressionModeValue = compressionModeValue;
			dto.lastModifiedUnixDateTime = lastModifiedUnixDateTime;
			dto.createdUnixDateTime = createdUnixDateTime;
			dto.applyToParent = applyToParent;
			dto.isComplete = isComplete;
		}
		return dto;
	}

	@Nullable
	public FullDataSourceV2DTO convertResultSetToAdjDto(long pos, ResultSet resultSet) throws ClassCastException, IOException, SQLException
	{
		//======================//
		// get statement values //
		//======================//

		byte compressionModeValue = resultSet.getByte("CompressionMode");

		// while these values can be null in the DB, null would just equate to false
		boolean applyToParent = (resultSet.getInt("ApplyToParent")) == 1;

		long lastModifiedUnixDateTime = resultSet.getLong("LastModifiedUnixDateTime");
		long createdUnixDateTime = resultSet.getLong("CreatedUnixDateTime");



		//===================//
		// set DTO variables //
		//===================//

		FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateEmptyDataSourceForDecoding();
		// set pooled arrays
		dto.compressedDataByteArray = putAllBytes(resultSet.getBinaryStream("AdjData"), dto.compressedDataByteArray);
		dto.compressedWorldCompressionModeByteArray = putAllBytes(resultSet.getBinaryStream("ColumnWorldCompressionMode"), dto.compressedWorldCompressionModeByteArray);
		dto.compressedMappingByteArray = putAllBytes(resultSet.getBinaryStream("Mapping"), dto.compressedMappingByteArray);

		// set individual variables
		{
			dto.pos = pos;
			dto.compressionModeValue = compressionModeValue;
			dto.lastModifiedUnixDateTime = lastModifiedUnixDateTime;
			dto.createdUnixDateTime = createdUnixDateTime;
			dto.applyToParent = applyToParent;
		}
		return dto;
	}
	
	
	/**
	 * Atomic UPSERT using SQLite's ON CONFLICT clause.
	 * Uses COALESCE for ApplyToParent to preserve existing value when null is passed,
	 * which is needed to prevent concurrent modification during update propagation.
	 */
	private final String upsertSqlTemplate =
		"INSERT INTO "+this.getTableName() + " (\n" +
		"   DetailLevel, PosX, PosZ, \n" +
		"   Data, ColumnWorldCompressionMode, Mapping, \n" +
		"   NorthAdjData, SouthAdjData, EastAdjData, WestAdjData, \n" +
		"   CompressionMode, ApplyToParent, IsComplete, \n" +
		"   LastModifiedUnixDateTime, CreatedUnixDateTime) \n" +
		"VALUES( \n" +
		"    ?, ?, ?, \n" +
		"    ?, ?, ?, \n" +
		"    ?, ?, ?, ?, \n" +
		"    ?, ?, ?, \n" +
		"    ?, ? \n" +
		") \n" +
		"ON CONFLICT(DetailLevel, PosX, PosZ) DO UPDATE SET \n" +
		"   Data = excluded.Data, \n" +
		"   ColumnWorldCompressionMode = excluded.ColumnWorldCompressionMode, \n" +
		"   Mapping = excluded.Mapping, \n" +
		"   NorthAdjData = excluded.NorthAdjData, \n" +
		"   SouthAdjData = excluded.SouthAdjData, \n" +
		"   EastAdjData = excluded.EastAdjData, \n" +
		"   WestAdjData = excluded.WestAdjData, \n" +
		"   CompressionMode = excluded.CompressionMode, \n" +
		"   ApplyToParent = COALESCE(excluded.ApplyToParent, ApplyToParent), \n" +
		"   IsComplete = excluded.IsComplete, \n" +
		"   LastModifiedUnixDateTime = excluded.LastModifiedUnixDateTime;";

	private final String insertSqlTemplate =
		"INSERT INTO "+this.getTableName() + " (\n" +
		"   DetailLevel, PosX, PosZ, \n" +
		"   Data, ColumnWorldCompressionMode, Mapping, \n" +
		"   NorthAdjData, SouthAdjData, EastAdjData, WestAdjData, \n" +
		"   CompressionMode, ApplyToParent, IsComplete, \n" +
		"   LastModifiedUnixDateTime, CreatedUnixDateTime) \n" +
		"VALUES( \n" +
		"    ?, ?, ?, \n" +
		"    ?, ?, ?, \n" +
		"    ?, ?, ?, ?, \n" +
		"    ?, ?, ?, \n" +
		"    ?, ? \n" +
		");";
	@Override
	public PreparedStatement createInsertStatement(FullDataSourceV2DTO dto) throws SQLException
	{
		PreparedStatement statement = this.createPreparedStatement(this.insertSqlTemplate);
		if (statement == null)
		{
			return null;
		}

		int i = 1;
		statement.setInt(i++, DhSectionPos.getDetailLevel(dto.pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		statement.setInt(i++, DhSectionPos.getX(dto.pos));
		statement.setInt(i++, DhSectionPos.getZ(dto.pos));

		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedDataByteArray.elements()), dto.compressedDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedWorldCompressionModeByteArray.elements()), dto.compressedWorldCompressionModeByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedMappingByteArray.elements()), dto.compressedMappingByteArray.size());
		// adjacent full data
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedNorthAdjDataByteArray.elements()), dto.compressedNorthAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedSouthAdjDataByteArray.elements()), dto.compressedSouthAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedEastAdjDataByteArray.elements()), dto.compressedEastAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedWestAdjDataByteArray.elements()), dto.compressedWestAdjDataByteArray.size());

		statement.setByte(i++, dto.compressionModeValue);
		// if nothing is present assume we don't need/want to propagate updates
		statement.setBoolean(i++, BoolUtil.falseIfNull(dto.applyToParent));
		statement.setBoolean(i++, dto.isComplete);

		statement.setLong(i++, System.currentTimeMillis()); // last modified unix time
		statement.setLong(i++, System.currentTimeMillis()); // created unix time

		return statement;
	}

	@Override
	public PreparedStatement createUpdateStatement(FullDataSourceV2DTO dto) throws SQLException
	{
		// Dynamic string so we can update ApplyToParent flag only if present.
		// This is necessary to prevent concurrent modifications when
		// update propagation is run.
		String updateSqlTemplate = (
				"UPDATE "+this.getTableName()+" \n" +
				"SET \n" +
				"   Data = ? \n" +
				"   ,ColumnWorldCompressionMode = ? \n" +
				"   ,Mapping = ? \n" +
				"   ,NorthAdjData = ?, SouthAdjData = ?, EastAdjData = ?, WestAdjData = ? \n" +

				"   ,CompressionMode = ? \n" +
					// only update this value if it's present
					(dto.applyToParent != null ? "   ,ApplyToParent = ? \n" : "" ) +
				"   ,IsComplete = ? \n" +

				"   ,LastModifiedUnixDateTime = ? \n" +
				"   ,CreatedUnixDateTime = ? \n" +

				"WHERE DetailLevel = ? AND PosX = ? AND PosZ = ?"
			// intern should help reduce memory overhead due to this string being dynamic
			).intern();


		PreparedStatement statement = this.createPreparedStatement(updateSqlTemplate);
		if (statement == null)
		{
			return null;
		}


		int i = 1;
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedDataByteArray.elements()), dto.compressedDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedWorldCompressionModeByteArray.elements()), dto.compressedWorldCompressionModeByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedMappingByteArray.elements()), dto.compressedMappingByteArray.size());
		// adjacent full data
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedNorthAdjDataByteArray.elements()), dto.compressedNorthAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedSouthAdjDataByteArray.elements()), dto.compressedSouthAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedEastAdjDataByteArray.elements()), dto.compressedEastAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedWestAdjDataByteArray.elements()), dto.compressedWestAdjDataByteArray.size());


		statement.setByte(i++, dto.compressionModeValue);
		if (dto.applyToParent != null)
		{
			statement.setBoolean(i++, dto.applyToParent);
		}
		statement.setBoolean(i++, dto.isComplete);

		statement.setLong(i++, System.currentTimeMillis()); // last modified unix time
		statement.setLong(i++, dto.createdUnixDateTime);

		statement.setInt(i++, DhSectionPos.getDetailLevel(dto.pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		statement.setInt(i++, DhSectionPos.getX(dto.pos));
		statement.setInt(i++, DhSectionPos.getZ(dto.pos));

		return statement;
	}

	/**
	 * Overrides the base save() to use atomic UPSERT instead of check-then-insert/update.
	 * This eliminates the need for per-position locking and prevents race conditions.
	 */
	@Override
	public void save(FullDataSourceV2DTO dto)
	{
		try (PreparedStatement statement = this.createUpsertStatement(dto);
			 ResultSet result = this.query(statement))
		{
			// result is unused - UPSERT doesn't return rows
		}
		catch (DbConnectionClosedException ignored)
		{
			// Connection was closed, nothing to do
		}
		catch (SQLException e)
		{
			String message = "Unexpected DTO upsert error: [" + e.getMessage() + "].";
			LOGGER.error(message);
			throw new RuntimeException(message, e);
		}
	}

	@Nullable
	private PreparedStatement createUpsertStatement(FullDataSourceV2DTO dto) throws SQLException
	{
		PreparedStatement statement = this.createPreparedStatement(this.upsertSqlTemplate);
		if (statement == null)
		{
			return null;
		}

		int i = 1;
		statement.setInt(i++, DhSectionPos.getDetailLevel(dto.pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		statement.setInt(i++, DhSectionPos.getX(dto.pos));
		statement.setInt(i++, DhSectionPos.getZ(dto.pos));

		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedDataByteArray.elements()), dto.compressedDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedWorldCompressionModeByteArray.elements()), dto.compressedWorldCompressionModeByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedMappingByteArray.elements()), dto.compressedMappingByteArray.size());
		// adjacent full data
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedNorthAdjDataByteArray.elements()), dto.compressedNorthAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedSouthAdjDataByteArray.elements()), dto.compressedSouthAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedEastAdjDataByteArray.elements()), dto.compressedEastAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedWestAdjDataByteArray.elements()), dto.compressedWestAdjDataByteArray.size());

		statement.setByte(i++, dto.compressionModeValue);

		// For UPSERT with COALESCE: null means "keep existing value", false/true means "set this value"
		// We use setObject with null to properly pass NULL to SQLite
		if (dto.applyToParent != null)
		{
			statement.setBoolean(i++, dto.applyToParent);
		}
		else
		{
			statement.setNull(i++, java.sql.Types.BOOLEAN);
		}

		statement.setBoolean(i++, dto.isComplete);

		statement.setLong(i++, System.currentTimeMillis()); // last modified unix time
		statement.setLong(i++, dto.createdUnixDateTime != 0 ? dto.createdUnixDateTime : System.currentTimeMillis()); // created unix time

		return statement;
	}



	//=================//
	// partial selects //
	//=================//
	
	private final String getAdjForDirectionSqlTemplate =
			"SELECT \n" +
					"   ColumnWorldCompressionMode, Mapping, \n" +
					"   CompressionMode, ApplyToParent, \n" +
					"   LastModifiedUnixDateTime, CreatedUnixDateTime, \n" +
					"   DIRECTION_ENUM as AdjData \n" +
					"FROM "+this.getTableName() + "\n" +
					"   WHERE DetailLevel = ? AND PosX = ? AND PosZ = ?; \n";
	private final String getAdjForNorthDirTemplate = this.getAdjForDirectionSqlTemplate.replace("DIRECTION_ENUM", "NorthAdjData");
	private final String getAdjForSouthDirTemplate = this.getAdjForDirectionSqlTemplate.replace("DIRECTION_ENUM", "SouthAdjData");
	private final String getAdjForEastDirTemplate = this.getAdjForDirectionSqlTemplate.replace("DIRECTION_ENUM", "EastAdjData");
	private final String getAdjForWestDirTemplate = this.getAdjForDirectionSqlTemplate.replace("DIRECTION_ENUM", "WestAdjData");
	
	public FullDataSourceV2DTO getAdjByPosAndDirection(long pos, EDhDirection direction)
	{
		// parameters don't work in the select, doing so causes
		// JDBC to return the wrong binary data,
		// so we need to hard code the direction enum
		String sql;
		switch (direction)
		{
			case NORTH:
				sql = this.getAdjForNorthDirTemplate;
				break;
			case SOUTH:
				sql = this.getAdjForSouthDirTemplate;
				break;
			case EAST:
				sql = this.getAdjForEastDirTemplate;
				break;
			case WEST:
				sql = this.getAdjForWestDirTemplate;
				break;
			
			default:
				throw new IllegalArgumentException();
		}
		try(PreparedStatement statement = this.createPreparedStatement(sql))
		{
			if (statement == null)
			{
				return null;
			}
			
			
			
			int i = 1;
			statement.setInt(i++, DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
			statement.setInt(i++, DhSectionPos.getX(pos));
			statement.setInt(i++, DhSectionPos.getZ(pos));
			
			
			try(ResultSet resultSet = this.query(statement))
			{
				if (resultSet != null
					&& resultSet.next())
				{
					return this.convertResultSetToAdjDto(pos, resultSet);
				}
				else
				{
					return null;
				}
			}
		}
		catch (SQLException | IOException e)
		{
			if (e instanceof SQLException
					&& DbConnectionClosedException.isClosedException((SQLException)e))
			{
				//LOGGER.warn("Attempted to get ["+this.dtoClass.getSimpleName()+"] with primary key ["+primaryKey+"] on closed repo ["+this.connectionString+"].");	
			}
			else
			{
				LOGGER.warn("Unexpected issue deserializing DTO ["+this.dtoClass.getSimpleName()+"] with pos ["+DhSectionPos.toString(pos)+"] and direction ["+direction+"]. Error: ["+e.getMessage()+"].", e);
			}
			return null;
		}
	}
	
	
	
	//=========//
	// updates //
	//=========//
	
	private final String setApplyToParentSql =
			"UPDATE "+this.getTableName()+" \n" +
			"SET ApplyToParent = ? \n" +
			"WHERE DetailLevel = ? AND PosX = ? AND PosZ = ?";
	public void setApplyToParent(long pos, boolean applyToParent)
	{
		try (PreparedStatement statement = this.createPreparedStatement(this.setApplyToParentSql))
		{
			if (statement == null)
			{
				return;
			}

			int i = 1;
			statement.setBoolean(i++, applyToParent);

			int detailLevel = DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
			statement.setInt(i++, detailLevel);
			statement.setInt(i++, DhSectionPos.getX(pos));
			statement.setInt(i++, DhSectionPos.getZ(pos));

			try (ResultSet result = this.query(statement))
			{
				// Result is unused
			}
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	
	
	private final String getParentPositionsToUpdateSql =
			"SELECT DetailLevel, PosX, PosZ, " +
			"   abs((PosX << (6 + DetailLevel)) - ?) + abs((PosZ << (6 + DetailLevel)) - ?) AS Distance " +
			"FROM " + this.getTableName() + " " +
			"WHERE ApplyToParent = 1 " +
			"ORDER BY DetailLevel ASC, Distance ASC " +
			"LIMIT ?; ";
	public LongArrayList getPositionsToUpdate(int targetBlockPosX, int targetBlockPosZ, int returnCount)
	{
		LongArrayList list = new LongArrayList();

		try (PreparedStatement statement = this.createPreparedStatement(this.getParentPositionsToUpdateSql))
		{
			if (statement == null)
			{
				return list;
			}
			
			
			int i = 1;
			statement.setInt(i++, targetBlockPosX);
			statement.setInt(i++, targetBlockPosZ);
			
			statement.setInt(i++, returnCount);
			
			try (ResultSet result = this.query(statement))
			{
				while (result != null && result.next())
				{
					byte detailLevel = result.getByte("DetailLevel");
					byte sectionDetailLevel = (byte) (detailLevel + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
					int posX = result.getInt("PosX");
					int posZ = result.getInt("PosZ");
					
					long pos = DhSectionPos.encode(sectionDetailLevel, posX, posZ);
					list.add(pos);
				}
			}
			
			return list;
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}



	//====================//
	// completion queries //
	//====================//

	private final String existsAndIsCompleteSql =
			"SELECT IsComplete " +
			"FROM " + this.getTableName() + " " +
			"WHERE DetailLevel = ? " +
			"AND PosX = ? " +
			"AND PosZ = ?;";

	/**
	 * Returns true if the section exists in the database AND is marked as complete.
	 * Used by generation code to determine if a section needs generation.
	 */
	public boolean existsAndIsComplete(long pos)
	{
		try (PreparedStatement preparedStatement = this.createPreparedStatement(this.existsAndIsCompleteSql))
		{
			if (preparedStatement == null)
			{
				return false;
			}

			int i = 1;
			preparedStatement.setInt(i++, DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
			preparedStatement.setInt(i++, DhSectionPos.getX(pos));
			preparedStatement.setInt(i++, DhSectionPos.getZ(pos));

			try (ResultSet result = this.query(preparedStatement))
			{
				if (result == null || !result.next())
				{
					// Row doesn't exist
					return false;
				}

				// Row exists, check if complete
				return result.getInt("IsComplete") == 1;
			}
		}
		catch (DbConnectionClosedException e)
		{
			return false;
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}

	/**
	 * Batch version of existsAndIsComplete.
	 * Returns a set of positions that exist AND are complete.
	 * Positions not in the returned set either don't exist or are incomplete.
	 */
	public LongOpenHashSet getCompletePositions(LongArrayList positions)
	{
		LongOpenHashSet completePositions = new LongOpenHashSet();
		if (positions.isEmpty())
		{
			return completePositions;
		}

		// Build query: SELECT DetailLevel, PosX, PosZ FROM FullData WHERE IsComplete = 1 AND ((DetailLevel=? AND PosX=? AND PosZ=?) OR ...)
		StringBuilder sql = new StringBuilder();
		sql.append("SELECT DetailLevel, PosX, PosZ FROM ").append(this.getTableName());
		sql.append(" WHERE IsComplete = 1 AND (");

		for (int j = 0; j < positions.size(); j++)
		{
			if (j > 0)
			{
				sql.append(" OR ");
			}
			sql.append("(DetailLevel=? AND PosX=? AND PosZ=?)");
		}
		sql.append(")");

		try (PreparedStatement preparedStatement = this.createPreparedStatement(sql.toString()))
		{
			if (preparedStatement == null)
			{
				return completePositions;
			}

			int i = 1;
			for (int j = 0; j < positions.size(); j++)
			{
				long pos = positions.getLong(j);
				preparedStatement.setInt(i++, DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
				preparedStatement.setInt(i++, DhSectionPos.getX(pos));
				preparedStatement.setInt(i++, DhSectionPos.getZ(pos));
			}

			try (ResultSet result = this.query(preparedStatement))
			{
				while (result != null && result.next())
				{
					byte detailLevel = (byte) (result.getByte("DetailLevel") + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
					int posX = result.getInt("PosX");
					int posZ = result.getInt("PosZ");
					long pos = DhSectionPos.encode(detailLevel, posX, posZ);
					completePositions.add(pos);
				}
			}
		}
		catch (DbConnectionClosedException e)
		{
			return completePositions;
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}

		return completePositions;
	}



	//=============//
	// multiplayer //
	//=============//

	private final String getTimestampForPosSql =
			"SELECT LastModifiedUnixDateTime " +
			"FROM " + this.getTableName() + " " +
			"WHERE DetailLevel = ? " +
			"AND PosX = ? " +
			"AND PosZ = ?;";
	@Nullable
	public Long getTimestampForPos(long pos)
	{
		try(PreparedStatement preparedStatement = this.createPreparedStatement(this.getTimestampForPosSql))
		{
			if (preparedStatement == null)
			{
				return null;
			}
			
			
			int i = 1;
			preparedStatement.setInt(i++, DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
			preparedStatement.setInt(i++, DhSectionPos.getX(pos));
			preparedStatement.setInt(i++, DhSectionPos.getZ(pos));
			
			try (ResultSet result = this.query(preparedStatement))
			{
				if (result == null || !result.next())
				{
					return null;
				}
				
				return result.getLong("LastModifiedUnixDateTime");
			}
		}
		catch (DbConnectionClosedException e)
		{
			return null;
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	private final String getTimestampForRangeSql =
			"SELECT PosX, PosZ, LastModifiedUnixDateTime " +
			"FROM " + this.getTableName() + " " +
			"WHERE DetailLevel = ? " +
			"AND PosX BETWEEN ? AND ? " +
			"AND PosZ BETWEEN ? AND ?;";
	public Map<Long, Long> getTimestampsForRange(byte detailLevel, int startPosX, int startPosZ, int endPosX, int endPosZ)
	{
		try(PreparedStatement preparedStatement = this.createPreparedStatement(this.getTimestampForRangeSql))
		{
			if (preparedStatement == null)
			{
				return new HashMap<>();
			}
			
			
			int i = 1;
			preparedStatement.setInt(i++, detailLevel - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
			preparedStatement.setInt(i++, startPosX);
			preparedStatement.setInt(i++, endPosX - 1);
			preparedStatement.setInt(i++, startPosZ);
			preparedStatement.setInt(i++, endPosZ - 1);
			
			
			try (ResultSet result = this.query(preparedStatement))
			{
				HashMap<Long, Long> returnMap = new HashMap<>();
				while (result != null && result.next())
				{
					long key = DhSectionPos.encode(detailLevel, result.getInt("PosX"), result.getInt("PosZ"));
					long value = result.getLong("LastModifiedUnixDateTime");
					
					returnMap.put(key, value);
				}
				
				return returnMap;
			}
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	
	
	//===================//
	// compression tests //
	//===================//
	
	private final String getAllPositionsSql = 
			"select DetailLevel, PosX, PosZ " +
			"from "+this.getTableName()+"; ";
	/** @return every position in this database */
	public LongArrayList getAllPositions()
	{
		LongArrayList list = new LongArrayList();
		
		try (PreparedStatement statement = this.createPreparedStatement(this.getAllPositionsSql))
		{
			if (statement == null)
			{
				return list;
			}
			
			
			try(ResultSet result = this.query(statement))
			{
				while (result != null && result.next())
				{
					byte detailLevel = result.getByte("DetailLevel");
					byte sectionDetailLevel = (byte) (detailLevel + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
					int posX = result.getInt("PosX");
					int posZ = result.getInt("PosZ");
					
					long pos = DhSectionPos.encode(sectionDetailLevel, posX, posZ);
					list.add(pos);
				}
				
				return list;
			}
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	
	private final String getDataSizeInBytesSql =
			"select LENGTH(Data) as dataSize " +
			"from "+this.getTableName()+" " +
			"WHERE DetailLevel = ? AND PosX = ? AND PosZ = ?";
	/** 
	 * @return the size of the full data at the given position 
	 *          (doesn't include the size of the mapping or any other column)
	 */
	public long getDataSizeInBytes(long pos)
	{
		int detailLevel = DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
		
		try (PreparedStatement statement = this.createPreparedStatement(this.getDataSizeInBytesSql))
		{
			if (statement == null)
			{
				return 0L;
			}
		
			int i = 1;
			statement.setInt(i++, detailLevel);
			statement.setInt(i++, DhSectionPos.getX(pos));
			statement.setInt(i++, DhSectionPos.getZ(pos));
			
			
			try (ResultSet result = this.query(statement)) // TODO check other query's
			{
				if (result == null || !result.next())
				{
					return 0L;
				}
				
				return result.getLong("dataSize");
			}
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	private final String getTotalDataSizeInBytesSql =
			"select SUM(LENGTH(Data)) as dataSize " +
			"from "+this.getTableName()+"; ";
	/** @return the total size in bytes of the full data for this entire database */
	public long getTotalDataSizeInBytes()
	{
		try (PreparedStatement statement = this.createPreparedStatement(this.getTotalDataSizeInBytesSql);
			ResultSet result = this.query(statement))
		{
			if (result == null || !result.next())
			{
				return 0;
			}
			
			return result.getLong("dataSize");
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	private static ByteArrayList putAllBytes(@Nullable InputStream inputStream, @Nullable ByteArrayList existingArrayList) throws IOException
	{
		if (existingArrayList == null)
		{
			// inputStream.available() can throw a null pointer due to a bug with LZMA stream so we have to estimate the array size
			existingArrayList = new ByteArrayList(64);
		}
		else
		{
			existingArrayList.clear();
		}

		try
		{
			if (inputStream != null)
			{
				// Use buffered read instead of byte-by-byte for much better performance
				byte[] buffer = new byte[8192];
				int bytesRead;
				while ((bytesRead = inputStream.read(buffer)) != -1)
				{
					existingArrayList.addElements(existingArrayList.size(), buffer, 0, bytesRead);
				}
			}
		}
		catch (EOFException ignore) { /* shouldn't happen, but just in case */ }

		return existingArrayList;
	}
	
	
	
}
